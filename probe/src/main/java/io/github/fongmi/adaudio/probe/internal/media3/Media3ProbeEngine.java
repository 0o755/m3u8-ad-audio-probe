/* Media3 引擎在独立 Looper 上高速解码音频，并按宿主时间轴派发已确认区间。 */
package io.github.fongmi.adaudio.probe.internal.media3;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import io.github.fongmi.adaudio.probe.internal.core.AdAudioMatcher;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FeedResult;
import io.github.fongmi.adaudio.probe.internal.core.MatcherConfig;
import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.ProbeState;
import io.github.fongmi.adaudio.probe.internal.runtime.AdDispatchQueue;
import io.github.fongmi.adaudio.probe.internal.runtime.AdDispatchQueue.Claim;
import io.github.fongmi.adaudio.probe.internal.runtime.ConfirmedAd;
import io.github.fongmi.adaudio.probe.internal.runtime.DetectionCoordinator;

@OptIn(markerClass = UnstableApi.class)
public final class Media3ProbeEngine implements AutoCloseable {
    private static final long PRE_PCM_BACKWARD_RECOVERY_MS = 500L;

    public interface Listener {
        void onState(long sessionId, ProbeState state, long analyzedThroughMs, long durationMs);
        void onAdReady(long sessionId, long ruleRevision, Claim claim,
                       long analyzedThroughMs);
        void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                     boolean retryable, String message, Exception error);
    }

    private final Context context;
    private final Handler handler;
    private final AtomicLong hostPositionMs;
    private final long maxLookaheadMs;
    private final long resumeLookaheadMs;
    private final Listener listener;
    private final Timeline.Window timelineWindow = new Timeline.Window();
    private ExoPlayer player;
    private ProbeAudioSink audioSink;
    private volatile AnalysisContext analysis;
    private volatile long sessionId;
    private volatile long durationMs = C.TIME_UNSET;
    private volatile boolean aheadPaused;
    private volatile boolean closed;

    public Media3ProbeEngine(Context context, Looper looper, AtomicLong hostPositionMs,
                             long maxLookaheadMs, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(looper);
        this.hostPositionMs = hostPositionMs;
        this.maxLookaheadMs = maxLookaheadMs;
        this.resumeLookaheadMs = Math.max(1000L, maxLookaheadMs * 2L / 3L);
        this.listener = listener;
    }

    /** 必须在构造时指定的 Looper 调用；新会话会完整释放旧解码器。 */
    public void open(long newSessionId, ProbeMedia media, AdRuleSet rules, long startPositionMs) {
        checkThread();
        if (closed || newSessionId <= 0) return;
        // 先切换代际再释放旧播放器，release 期间产生的旧回调会立即失效。
        sessionId = newSessionId;
        releasePlayer();
        durationMs = C.TIME_UNSET;
        aheadPaused = false;
        AnalysisContext next = new AnalysisContext(newSessionId, rules,
                Math.max(0L, startPositionMs));
        analysis = next;

        ProbePcmConsumer pcmConsumer = new SessionPcmConsumer(next);
        audioSink = new ProbeAudioSink(pcmConsumer, () -> pauseForLookahead(newSessionId),
                hostPositionMs, maxLookaheadMs);
        AudioOnlyRenderersFactory renderers = new AudioOnlyRenderersFactory(context, audioSink);
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 5000, 250, 500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(context, renderers)
                .setLooper(handler.getLooper())
                .setLoadControl(loadControl)
                .build();
        player.addListener(new SessionPlayerListener(newSessionId));
        try {
            player.setMediaSource(createMediaSource(media));
            player.seekTo(Math.max(0L, startPositionMs));
            player.prepare();
            player.play();
            emitState(newSessionId, ProbeState.PREPARING);
        } catch (RuntimeException error) {
            fail(newSessionId, ProbeErrorCode.INVALID_SOURCE, true,
                    false, "媒体地址无法创建探针会话", error);
        }
    }

    public void updateHostPosition(long expectedSessionId, long positionMs) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return;
        long safePosition = Math.max(0L, positionMs);
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId) return;
        dispatchReadyAds(current, safePosition);

        long forwardThresholdMs = Math.max(5000L, maxLookaheadMs / 2L);
        long analyzedThrough;
        boolean receivedPcm;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            analyzedThrough = current.analyzedThroughMs;
            receivedPcm = current.receivedPcm;
        }
        if (!receivedPcm && shouldRecoverBeforeFirstPcm(analyzedThrough, safePosition)) {
            // 换源初期宿主可能短暂返回上一媒体的位置，首块 PCM 前允许向后纠偏。
            seekAnalyzer(safePosition);
            return;
        }
        boolean hostBeyondAnalysis = receivedPcm
                && safePosition > safeAdd(analyzedThrough, forwardThresholdMs);
        boolean hostFarBehind = receivedPcm
                && safeAdd(safePosition, maxLookaheadMs + 1000L) < analyzedThrough;
        if (hostBeyondAnalysis || hostFarBehind) {
            seekAnalyzer(safePosition);
            return;
        }
        if (aheadPaused && analyzedThrough - safePosition <= resumeLookaheadMs) {
            aheadPaused = false;
            audioSink.allowMoreData();
            player.play();
        }
    }

    public void notifyHostDiscontinuity(long expectedSessionId, long positionMs) {
        checkThread();
        if (isCurrent(expectedSessionId) && player != null) seekAnalyzer(Math.max(0L, positionMs));
    }

    public void stop(long expectedSessionId) {
        checkThread();
        if (!isMatchingStopSession(sessionId, expectedSessionId)) return;
        sessionId = 0L;
        releasePlayer();
        durationMs = C.TIME_UNSET;
    }

    /** 宿主最终时钟校验结束后确认消费，或释放占用等待下一次轮询。 */
    public void resolveAd(long expectedSessionId, Claim claim, boolean consumed) {
        checkThread();
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            if (consumed) current.dispatchQueue.ack(claim);
            else current.dispatchQueue.release(claim);
        }
    }

    /** 可由宿主回调线程调用；晚到冲突、reset 或媒体切换都会使 token 失效。 */
    public boolean isAdClaimValid(long expectedSessionId, Claim claim) {
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return false;
        synchronized (current) {
            return isAnalysisCurrent(current) && current.dispatchQueue.isClaimValid(claim);
        }
    }

    /** 在真正调用宿主前原子提交 token，提交失败时不得产生跳转回调。 */
    public boolean commitAdClaim(long expectedSessionId, Claim claim) {
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return false;
        synchronized (current) {
            return isAnalysisCurrent(current) && current.dispatchQueue.ack(claim);
        }
    }

    private MediaSource createMediaSource(ProbeMedia media) {
        URI uri = URI.create(media.getUrl());
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("媒体地址必须是 HTTP(S) URL");
        }
        if (media.getUrl().toLowerCase(Locale.US).contains(".mpd")) {
            throw new IllegalArgumentException("首版不支持 DASH");
        }
        DefaultHttpDataSource.Factory dataSource = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(media.getHeaders());
        MediaItem item = new MediaItem.Builder()
                .setMediaId(media.getId())
                .setUri(Uri.parse(media.getUrl()))
                .build();
        if (isHls(media)) {
            return new HlsMediaSource.Factory(dataSource).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSource, new Mp4OnlyExtractorsFactory())
                .createMediaSource(item);
    }

    private static boolean isHls(ProbeMedia media) {
        if (media.getType() == ProbeMedia.Type.HLS) return true;
        if (media.getType() == ProbeMedia.Type.MP4) return false;
        String path = URI.create(media.getUrl()).getPath();
        return path != null && path.toLowerCase(Locale.US).endsWith(".m3u8");
    }

    private void pauseForLookahead(long expectedSessionId) {
        handler.post(() -> {
            if (!isCurrent(expectedSessionId) || player == null || aheadPaused) return;
            aheadPaused = true;
            player.pause();
            emitState(expectedSessionId, ProbeState.LOOKAHEAD_READY);
        });
    }

    private void onPcm(AnalysisContext current, PcmChunk chunk, long endPositionMs) {
        FeedResult result;
        synchronized (current) {
            if (!isAnalysisCurrent(current) || current.awaitingTimelineReset) return;
            current.receivedPcm = true;
            result = current.matcher.feed(chunk);
            boolean timelineReset = current.resetWatermarkOnNextPcm
                    || result.isTimelineReset();
            if (timelineReset) {
                current.dispatchQueue.reset();
                current.coordinator.reset();
            }
            List<ConfirmedAd> confirmed = new ArrayList<>();
            confirmed.addAll(current.coordinator.onMatches(result.getEvents()));
            confirmed.addAll(current.coordinator.onAnalyzedThrough(endPositionMs));
            current.analyzedThroughMs = advanceAnalyzedThrough(
                    current.analyzedThroughMs, endPositionMs, timelineReset);
            current.resetWatermarkOnNextPcm = false;
            current.dispatchQueue.addAll(confirmed);
        }
        dispatchReadyAds(current, hostPositionMs.get());
        emitState(current.sessionId,
                aheadPaused ? ProbeState.LOOKAHEAD_READY : ProbeState.ANALYZING);
        if (result.getStatus() == FeedResult.Status.INTERNAL_ERROR) {
            fail(current.sessionId, ProbeErrorCode.INTERNAL, false,
                    true, "音频指纹匹配器已安全重置", null);
        }
    }

    private void dispatchReadyAds(AnalysisContext current, long hostPosition) {
        long knownDuration = durationMs == C.TIME_UNSET ? -1L : durationMs;
        List<Claim> ready;
        long analyzedThrough;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            ready = current.dispatchQueue.claim(hostPosition, knownDuration);
            analyzedThrough = current.analyzedThroughMs;
        }
        for (Claim claim : ready) {
            if (!isAnalysisCurrent(current)) return;
            listener.onAdReady(current.sessionId, current.ruleRevision, claim, analyzedThrough);
        }
    }

    private void seekAnalyzer(long positionMs) {
        AnalysisContext current = analysis;
        if (current == null) return;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            current.awaitingTimelineReset = true;
            current.dispatchQueue.reset();
            current.coordinator.reset();
            current.matcher.reset();
            current.analyzedThroughMs = positionMs;
            current.receivedPcm = false;
        }
        aheadPaused = false;
        audioSink.allowMoreData();
        player.seekTo(positionMs);
        player.play();
        emitState(current.sessionId, ProbeState.ANALYZING);
    }

    private void emitState(long expectedSessionId, ProbeState state) {
        if (listener == null || !isCurrent(expectedSessionId)) return;
        AnalysisContext current = analysis;
        long analyzedThrough = current == null ? 0L : currentAnalyzedThrough(current);
        listener.onState(expectedSessionId, state, analyzedThrough, durationMs);
    }

    private void fail(long expectedSessionId, ProbeErrorCode code, boolean fatal,
                      boolean retryable, String message, Exception error) {
        if (!isCurrent(expectedSessionId)) return;
        if (listener != null) listener.onError(expectedSessionId, code, fatal,
                retryable, message, error);
        if (fatal) {
            releasePlayer();
            emitState(expectedSessionId, ProbeState.FAILED);
        }
    }

    private boolean isCurrent(long expectedSessionId) {
        return !closed && expectedSessionId > 0L && expectedSessionId == sessionId;
    }

    private void releasePlayer() {
        AnalysisContext current = analysis;
        analysis = null;
        if (current != null) {
            synchronized (current) {
                current.active = false;
                current.dispatchQueue.reset();
                current.coordinator.reset();
                current.matcher.reset();
            }
        }
        audioSink = null;
        aheadPaused = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        releasePlayer();
        sessionId = 0L;
    }

    private void checkThread() {
        if (Looper.myLooper() != handler.getLooper()) {
            throw new IllegalStateException("Media3 探针必须在专用 Looper 调用");
        }
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    static long advanceAnalyzedThrough(long previousMs, long endPositionMs,
                                       boolean timelineReset) {
        long safeEnd = Math.max(0L, endPositionMs);
        return timelineReset ? safeEnd : Math.max(previousMs, safeEnd);
    }

    static boolean shouldRecoverBeforeFirstPcm(long analyzedThroughMs, long hostPositionMs) {
        return safeAdd(Math.max(0L, hostPositionMs), PRE_PCM_BACKWARD_RECOVERY_MS)
                < Math.max(0L, analyzedThroughMs);
    }

    static boolean isMatchingStopSession(long currentSessionId, long expectedSessionId) {
        return expectedSessionId > 0L && expectedSessionId == currentSessionId;
    }

    private final class SessionPcmConsumer implements ProbePcmConsumer {
        private final AnalysisContext expected;

        SessionPcmConsumer(AnalysisContext expected) {
            this.expected = expected;
        }

        @Override
        public void onPcm(PcmChunk chunk, long endPositionMs) {
            Media3ProbeEngine.this.onPcm(expected, chunk, endPositionMs);
        }

        @Override
        public void onTimelineReset() {
            synchronized (expected) {
                if (!isAnalysisCurrent(expected)) return;
                expected.receivedPcm = false;
                expected.awaitingTimelineReset = false;
                expected.analyzedThroughMs = Math.max(0L, hostPositionMs.get());
                expected.resetWatermarkOnNextPcm = true;
                expected.dispatchQueue.reset();
                expected.coordinator.reset();
                expected.matcher.reset();
            }
        }

        @Override
        public void onFailure(RuntimeException error) {
            fail(expected.sessionId, ProbeErrorCode.INTERNAL, false,
                    true, "PCM 处理失败，当前检测窗口已丢弃", error);
        }
    }

    private final class SessionPlayerListener implements Player.Listener {
        private final long expectedSessionId;

        SessionPlayerListener(long expectedSessionId) {
            this.expectedSessionId = expectedSessionId;
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (!isCurrent(expectedSessionId) || player == null) return;
            if (playbackState == Player.STATE_READY) {
                if (player.isCurrentMediaItemLive()) {
                    fail(expectedSessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                            true, false, "首版仅支持普通点播，不支持直播", null);
                } else if (!player.getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO)) {
                    fail(expectedSessionId, ProbeErrorCode.NO_AUDIO_TRACK,
                            true, false, "媒体中没有可解码音轨", null);
                } else if (!player.getCurrentTracks().isTypeSupported(C.TRACK_TYPE_AUDIO)
                        || !player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                    fail(expectedSessionId, ProbeErrorCode.UNSUPPORTED_AUDIO,
                            true, false, "媒体音轨无法由当前设备解码", null);
                } else {
                    emitState(expectedSessionId, ProbeState.ANALYZING);
                }
            } else if (playbackState == Player.STATE_ENDED) {
                emitState(expectedSessionId, ProbeState.ENDED);
            }
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            if (!isCurrent(expectedSessionId) || timeline.isEmpty()) return;
            Timeline.Window window = timeline.getWindow(0, timelineWindow);
            if (window.isLive() || window.isDynamic) {
                fail(expectedSessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                        true, false, "动态或直播时间轴不受支持", null);
                return;
            }
            durationMs = window.getDurationMs();
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            if (!isCurrent(expectedSessionId)) return;
            if (!tracks.isEmpty() && !tracks.containsType(C.TRACK_TYPE_AUDIO)) {
                fail(expectedSessionId, ProbeErrorCode.NO_AUDIO_TRACK,
                        true, false, "媒体中没有可解码音轨", null);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            ProbeErrorCode code;
            if (error.errorCode >= PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
                    && error.errorCode <= PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED) {
                code = ProbeErrorCode.DRM_NOT_SUPPORTED;
            } else if (error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                    && error.errorCode <= PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
                code = ProbeErrorCode.SOURCE_IO;
            } else if (error.errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    && error.errorCode <= PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
                code = ProbeErrorCode.UNSUPPORTED_SOURCE;
            } else {
                code = ProbeErrorCode.DECODER_FAILED;
            }
            fail(expectedSessionId, code, true, true,
                    "音频探针无法继续分析当前媒体", error);
        }
    }

    private boolean isAnalysisCurrent(AnalysisContext current) {
        return current != null && current.active && analysis == current
                && isCurrent(current.sessionId);
    }

    private long currentAnalyzedThrough(AnalysisContext current) {
        synchronized (current) {
            return isAnalysisCurrent(current) ? current.analyzedThroughMs : 0L;
        }
    }

    /** 每个媒体代际独占匹配状态，旧解码回调无法写入新会话。 */
    private static final class AnalysisContext {
        final long sessionId;
        final long ruleRevision;
        final AdAudioMatcher matcher;
        final DetectionCoordinator coordinator = new DetectionCoordinator();
        final AdDispatchQueue dispatchQueue = new AdDispatchQueue();
        long analyzedThroughMs;
        boolean receivedPcm;
        boolean awaitingTimelineReset;
        boolean resetWatermarkOnNextPcm;
        boolean active = true;

        AnalysisContext(long sessionId, AdRuleSet rules, long startPositionMs) {
            this.sessionId = sessionId;
            this.ruleRevision = rules.getRevision();
            this.matcher = new AdAudioMatcher(rules, MatcherConfig.releaseSafe());
            this.analyzedThroughMs = startPositionMs;
        }
    }

}
