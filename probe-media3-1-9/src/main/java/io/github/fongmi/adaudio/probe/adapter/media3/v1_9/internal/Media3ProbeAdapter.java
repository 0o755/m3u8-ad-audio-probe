/* Media3 1.9.2 适配器只负责无声解码、真实 PTS 与有界前视。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

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
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterRequest;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterState;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLException;

/** 官方 Media3 1.9.2 音频解码实现；公开合同不暴露任何 Media3 类型。 */
@OptIn(markerClass = UnstableApi.class)
public final class Media3ProbeAdapter implements ProbeAdapter {
    private static final long PRE_PCM_BACKWARD_RECOVERY_MS = 500L;

    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final Timeline.Window timelineWindow = new Timeline.Window();
    private final Media3VodTimelineGate timelineGate = new Media3VodTimelineGate();
    private final AtomicLong hostPositionMs = new AtomicLong();

    private ExoPlayer player;
    private ProbeAudioSink audioSink;
    private ProbeAdapterRequest activeRequest;
    private SourceObservation sourceObservation;
    private AutoMediaTypeDetector.Container selectedContainer;
    private volatile long sessionId;
    private volatile long attemptId;
    private long durationMs = C.TIME_UNSET;
    private volatile long decodedThroughMs;
    private long maxLookaheadMs;
    private long resumeLookaheadMs;
    private volatile boolean receivedPcm;
    private volatile boolean aheadPaused;
    private volatile boolean awaitingSinkReset;
    private volatile boolean vodTimelineConfirmed;
    private boolean autoFallbackAttempted;
    private volatile boolean closed;

    public Media3ProbeAdapter(Context context, Looper controlLooper, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(controlLooper);
        this.listener = listener;
    }

    @Override
    public void open(ProbeAdapterRequest request) {
        checkThread();
        if (closed || request == null) return;
        long newSessionId = request.getSessionId();
        sessionId = newSessionId;
        activeRequest = request;
        durationMs = C.TIME_UNSET;
        hostPositionMs.set(request.getStartPositionMs());
        maxLookaheadMs = request.getMaxLookaheadMs();
        resumeLookaheadMs = Math.max(1000L, maxLookaheadMs * 2L / 3L);
        autoFallbackAttempted = false;
        ProbeErrorCode headerError = requestHeaderErrorCode(request.getMedia().getHeaders());
        if (headerError != null) {
            String unsupportedHeader = Media3RequestHeaderPolicy.findFirstUnsupported(
                    request.getMedia().getHeaders());
            sourceObservation = null;
            fail(newSessionId, headerError, true,
                    false, "官方 Media3 适配器不支持请求头：" + unsupportedHeader, null);
            return;
        }
        selectedContainer = AutoMediaTypeDetector.initialContainer(request.getMedia());
        sourceObservation = AutoMediaTypeDetector.allowsFallback(request.getMedia())
                ? new SourceObservation() : null;
        startAttempt(newSessionId, selectedContainer, request.getStartPositionMs());
    }

    /** 每次判型尝试使用独立 token，旧 player 的迟到回调无法污染当前会话。 */
    private void startAttempt(long expectedSessionId, AutoMediaTypeDetector.Container container,
                              long startPositionMs) {
        long currentAttemptId = ++attemptId;
        releasePlayer();
        timelineGate.reset();
        decodedThroughMs = Math.max(0L, startPositionMs);
        receivedPcm = false;
        aheadPaused = false;
        awaitingSinkReset = false;
        vodTimelineConfirmed = false;

        ProbePcmConsumer pcmConsumer = new SessionPcmConsumer(expectedSessionId, currentAttemptId);
        audioSink = new ProbeAudioSink(pcmConsumer,
                () -> pauseForLookahead(expectedSessionId, currentAttemptId),
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
        player.addListener(new SessionPlayerListener(expectedSessionId, currentAttemptId));
        try {
            player.setMediaSource(createMediaSource(activeRequest.getMedia(), container,
                    sourceObservation));
            player.seekTo(Math.max(0L, startPositionMs));
            player.prepare();
            player.play();
            emitState(expectedSessionId, ProbeAdapterState.PREPARING);
        } catch (RuntimeException error) {
            fail(expectedSessionId, ProbeErrorCode.INVALID_SOURCE, true,
                    false, "媒体地址无法创建探针会话", error);
        }
    }

    @Override
    public void updateHostPosition(long expectedSessionId, long positionMs) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return;
        long safePosition = Math.max(0L, positionMs);
        hostPositionMs.set(safePosition);
        long forwardThresholdMs = Math.max(5000L, maxLookaheadMs / 2L);
        if (!receivedPcm && shouldRecoverBeforeFirstPcm(decodedThroughMs, safePosition)) {
            seekAnalyzer(safePosition);
            return;
        }
        boolean hostBeyondAnalysis = receivedPcm
                && safePosition > safeAdd(decodedThroughMs, forwardThresholdMs);
        boolean hostFarBehind = receivedPcm
                && safeAdd(safePosition, maxLookaheadMs + 1000L) < decodedThroughMs;
        if (hostBeyondAnalysis || hostFarBehind) {
            seekAnalyzer(safePosition);
            return;
        }
        if (aheadPaused && decodedThroughMs - safePosition <= resumeLookaheadMs) {
            aheadPaused = false;
            audioSink.allowMoreData();
            player.play();
        }
    }

    @Override
    public void stop(long expectedSessionId) {
        checkThread();
        if (!isMatchingStopSession(sessionId, expectedSessionId)) return;
        sessionId = 0L;
        releasePlayer();
        activeRequest = null;
        sourceObservation = null;
        awaitingSinkReset = false;
        durationMs = C.TIME_UNSET;
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        sessionId = 0L;
        releasePlayer();
        activeRequest = null;
        sourceObservation = null;
        awaitingSinkReset = false;
    }

    private MediaSource createMediaSource(ProbeMedia media,
                                          AutoMediaTypeDetector.Container container,
                                          SourceObservation observation) {
        DataSource.Factory sameOrigin = httpDataSourceFactory(media.getHeaders());
        DataSource.Factory crossOrigin = httpDataSourceFactory(
                OriginAwareDataSource.crossOriginHeaders(media.getHeaders()));
        DataSource.Factory dataSource = new OriginAwareDataSource.Factory(
                sameOrigin, crossOrigin, media.getUrl());
        if (observation != null) {
            dataSource = new SniffingDataSource.Factory(dataSource, observation);
        }
        MediaItem item = new MediaItem.Builder()
                .setMediaId(media.getId())
                .setUri(Uri.parse(media.getUrl()))
                .build();
        if (container == AutoMediaTypeDetector.Container.HLS) {
            return new HlsMediaSource.Factory(dataSource).createMediaSource(item);
        }
        return new ProgressiveMediaSource.Factory(dataSource, new Mp4OnlyExtractorsFactory())
                .createMediaSource(item);
    }

    private void pauseForLookahead(long expectedSessionId, long expectedAttemptId) {
        handler.post(() -> {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)
                    || player == null || aheadPaused) return;
            aheadPaused = true;
            player.pause();
            emitState(expectedSessionId, ProbeAdapterState.LOOKAHEAD_READY);
        });
    }

    private void seekAnalyzer(long positionMs) {
        if (player == null || audioSink == null) return;
        awaitingSinkReset = true;
        audioSink.blockUntilTimelineReset();
        decodedThroughMs = positionMs;
        receivedPcm = false;
        aheadPaused = false;
        listener.onTimelineReset(sessionId, positionMs);
        audioSink.allowMoreData();
        player.seekTo(positionMs);
        player.play();
        emitState(sessionId, ProbeAdapterState.DECODING);
    }

    private void emitState(long expectedSessionId, ProbeAdapterState state) {
        if (listener != null && isCurrent(expectedSessionId)) {
            listener.onState(expectedSessionId, state);
        }
    }

    /** AUTO 仅在真实响应证据与首选容器矛盾时进行一次同会话重建。 */
    private boolean tryContainerFallback(long expectedSessionId, long expectedAttemptId,
                                         PlaybackException error) {
        if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)
                || autoFallbackAttempted
                || activeRequest == null || sourceObservation == null
                || !AutoMediaTypeDetector.allowsFallback(activeRequest.getMedia())) {
            return false;
        }
        AutoMediaTypeDetector.Container fallback;
        if (selectedContainer == AutoMediaTypeDetector.Container.MP4
                && containsUnrecognizedInput(error)
                && sourceObservation.hasHlsEvidence()) {
            fallback = AutoMediaTypeDetector.Container.HLS;
        } else if (selectedContainer == AutoMediaTypeDetector.Container.HLS
                && isManifestParsingFailure(error)
                && sourceObservation.hasMp4Evidence()) {
            fallback = AutoMediaTypeDetector.Container.MP4;
        } else {
            return false;
        }
        autoFallbackAttempted = true;
        selectedContainer = fallback;
        sourceObservation = null;
        long restartPositionMs = Math.max(0L, hostPositionMs.get());
        // 先废弃旧 attempt，防止 reset 与重建之间仍接收旧播放器 PCM。
        attemptId++;
        listener.onTimelineReset(expectedSessionId, restartPositionMs);
        if (!isCurrent(expectedSessionId)) return true;
        startAttempt(expectedSessionId, selectedContainer, restartPositionMs);
        return true;
    }

    static boolean containsUnrecognizedInput(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof UnrecognizedInputFormatException) return true;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    static boolean isManifestParsingFailure(PlaybackException error) {
        if (error == null) return false;
        return error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    static boolean isRetryableMediaError(ProbeErrorCode code, Throwable error) {
        if (code != ProbeErrorCode.SOURCE_IO || error == null) return false;
        Integer responseCode = findHttpResponseCode(error);
        if (responseCode != null) return isRetryableHttpStatus(responseCode);
        Integer playbackErrorCode = findPlaybackErrorCode(error);
        return isRetryableIoFailure(playbackErrorCode == null
                ? PlaybackException.ERROR_CODE_IO_UNSPECIFIED : playbackErrorCode, error);
    }

    static boolean isRetryableIoFailure(int playbackErrorCode, Throwable error) {
        if (error == null || containsCause(error, SSLException.class)) return false;
        if (playbackErrorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) {
            return true;
        }
        if (playbackErrorCode != PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                && playbackErrorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
            return false;
        }
        return containsCause(error, SocketTimeoutException.class)
                || containsCause(error, UnknownHostException.class)
                || containsCause(error, SocketException.class);
    }

    static boolean isRetryableHttpStatus(int responseCode) {
        return responseCode == 408 || responseCode == 429
                || responseCode >= 500 && responseCode <= 599;
    }

    private static Integer findHttpResponseCode(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof HttpDataSource.InvalidResponseCodeException) {
                return ((HttpDataSource.InvalidResponseCodeException) current).responseCode;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return null;
    }

    private static Integer findPlaybackErrorCode(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof PlaybackException) {
                return ((PlaybackException) current).errorCode;
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return null;
    }

    private static boolean containsCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (type.isInstance(current)) return true;
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }

    private void fail(long expectedSessionId, ProbeErrorCode code, boolean fatal,
                      boolean retryable, String message, Throwable error) {
        if (!isCurrent(expectedSessionId)) return;
        if (fatal) {
            attemptId++;
            awaitingSinkReset = true;
        }
        if (listener != null) {
            listener.onError(expectedSessionId, code, fatal, retryable, message, error);
        }
        if (fatal) releasePlayer();
    }

    private boolean isCurrent(long expectedSessionId) {
        return !closed && expectedSessionId > 0L && expectedSessionId == sessionId;
    }

    private boolean isAttemptCurrent(long expectedSessionId, long expectedAttemptId) {
        return isCurrent(expectedSessionId) && expectedAttemptId == attemptId;
    }

    private void releasePlayer() {
        audioSink = null;
        aheadPaused = false;
        receivedPcm = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void checkThread() {
        if (Looper.myLooper() != handler.getLooper()) {
            throw new IllegalStateException("Media3 适配器必须在控制 Looper 调用");
        }
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static DefaultHttpDataSource.Factory httpDataSourceFactory(
            java.util.Map<String, String> headers) {
        return new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                // Media3 的 false 同时禁止跨协议重定向，安全优先于自动升级。
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(headers);
    }

    static boolean shouldRecoverBeforeFirstPcm(long decodedThroughMs, long hostPositionMs) {
        return safeAdd(Math.max(0L, hostPositionMs), PRE_PCM_BACKWARD_RECOVERY_MS)
                < Math.max(0L, decodedThroughMs);
    }

    static boolean isMatchingStopSession(long currentSessionId, long expectedSessionId) {
        return expectedSessionId > 0L && expectedSessionId == currentSessionId;
    }

    static ProbeErrorCode requestHeaderErrorCode(java.util.Map<String, String> headers) {
        return Media3RequestHeaderPolicy.findFirstUnsupported(headers) == null
                ? null : ProbeErrorCode.UNSUPPORTED_SOURCE;
    }

    private boolean publishAuthoritativeTimeline(long expectedSessionId,
                                                 long expectedAttemptId) {
        if (!isAttemptCurrent(expectedSessionId, expectedAttemptId) || player == null) {
            return false;
        }
        Timeline timeline = player.getCurrentTimeline();
        if (timeline.isEmpty()) return false;
        int windowIndex = player.getCurrentMediaItemIndex();
        if (windowIndex < 0 || windowIndex >= timeline.getWindowCount()) return false;
        Timeline.Window window = timeline.getWindow(windowIndex, timelineWindow);
        Media3VodTimelineGate.Decision decision = timelineGate.update(
                window.isPlaceholder, window.isLive(), window.isDynamic);
        if (decision == Media3VodTimelineGate.Decision.PENDING
                && player.getPlaybackState() == Player.STATE_READY) {
            decision = timelineGate.markReady();
        }
        if (decision == Media3VodTimelineGate.Decision.PENDING
                || decision == Media3VodTimelineGate.Decision.IGNORED) return false;
        if (decision == Media3VodTimelineGate.Decision.REJECT_LIVE
                || decision == Media3VodTimelineGate.Decision.REJECT_DYNAMIC) {
            fail(expectedSessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                    true, false, "首版仅支持有限时长的普通点播", null);
            return false;
        }
        durationMs = window.getDurationMs();
        listener.onTimeline(expectedSessionId, durationMs, false, false);
        if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return false;
        vodTimelineConfirmed = true;
        if (audioSink != null) audioSink.confirmVodTimeline();
        return true;
    }

    private final class SessionPcmConsumer implements ProbePcmConsumer {
        private final long expectedSessionId;
        private final long expectedAttemptId;

        SessionPcmConsumer(long expectedSessionId, long expectedAttemptId) {
            this.expectedSessionId = expectedSessionId;
            this.expectedAttemptId = expectedAttemptId;
        }

        @Override
        public void onPcm(ProbePcmFrame frame) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)
                    || awaitingSinkReset || !vodTimelineConfirmed) return;
            listener.onPcm(expectedSessionId, frame);
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)
                    || awaitingSinkReset) return;
            decodedThroughMs = Math.max(decodedThroughMs, frame.getEndPositionUs() / 1000L);
            receivedPcm = true;
            emitState(expectedSessionId, ProbeAdapterState.DECODING);
        }

        @Override
        public void onTimelineReset() {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return;
            awaitingSinkReset = false;
            receivedPcm = false;
            decodedThroughMs = Math.max(0L, hostPositionMs.get());
            listener.onTimelineReset(expectedSessionId, decodedThroughMs);
        }

        @Override
        public void onFailure(RuntimeException error) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return;
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, false,
                    true, "PCM 处理失败，当前检测窗口已丢弃", error);
        }
    }

    private final class SessionPlayerListener implements Player.Listener {
        private final long expectedSessionId;
        private final long expectedAttemptId;

        SessionPlayerListener(long expectedSessionId, long expectedAttemptId) {
            this.expectedSessionId = expectedSessionId;
            this.expectedAttemptId = expectedAttemptId;
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId) || player == null) return;
            if (playbackState == Player.STATE_READY) {
                if (!publishAuthoritativeTimeline(expectedSessionId, expectedAttemptId)) {
                    return;
                }
                if (!player.getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO)) {
                    fail(expectedSessionId, ProbeErrorCode.NO_AUDIO_TRACK,
                            true, false, "媒体中没有可解码音轨", null);
                } else if (!player.getCurrentTracks().isTypeSupported(C.TRACK_TYPE_AUDIO)
                        || !player.getCurrentTracks().isTypeSelected(C.TRACK_TYPE_AUDIO)) {
                    fail(expectedSessionId, ProbeErrorCode.UNSUPPORTED_AUDIO,
                            true, false, "媒体音轨无法由当前设备解码", null);
                } else {
                    emitState(expectedSessionId, ProbeAdapterState.DECODING);
                }
            } else if (playbackState == Player.STATE_ENDED) {
                emitState(expectedSessionId, ProbeAdapterState.ENDED);
            }
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId) || timeline.isEmpty()) return;
            if (publishAuthoritativeTimeline(expectedSessionId, expectedAttemptId)
                    && player != null && player.getPlaybackState() == Player.STATE_READY) {
                onPlaybackStateChanged(Player.STATE_READY);
            }
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return;
            if (!tracks.isEmpty() && !tracks.containsType(C.TRACK_TYPE_AUDIO)) {
                fail(expectedSessionId, ProbeErrorCode.NO_AUDIO_TRACK,
                        true, false, "媒体中没有可解码音轨", null);
            }
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return;
            if (tryContainerFallback(expectedSessionId, expectedAttemptId, error)) return;
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
            fail(expectedSessionId, code, true, isRetryableMediaError(code, error),
                    "音频探针无法继续分析当前媒体", error);
        }
    }
}
