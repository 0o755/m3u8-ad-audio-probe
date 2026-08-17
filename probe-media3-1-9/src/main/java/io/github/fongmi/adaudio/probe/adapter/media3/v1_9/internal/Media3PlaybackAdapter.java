/* Media3 1.9.2 可见播放器实现普通点播、Surface 输出和真实时间轴。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapter;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterState;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackDiscontinuityReason;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackRequest;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackSnapshot;

/** 官方 Media3 1.9.2 可见播放实现；公开合同不暴露任何 Media3 类型。 */
@OptIn(markerClass = UnstableApi.class)
public final class Media3PlaybackAdapter implements ProbePlaybackAdapter {
    private final Context context;
    private final Handler handler;
    private final Listener listener;
    private final Timeline.Window timelineWindow = new Timeline.Window();
    private final Media3VodTimelineGate timelineGate = new Media3VodTimelineGate();

    private ExoPlayer player;
    private ProbePlaybackRequest activeRequest;
    private SourceObservation sourceObservation;
    private AutoMediaTypeDetector.Container selectedContainer;
    private Surface surface;
    private long sessionId;
    private long attemptId;
    private long durationMs = C.TIME_UNSET;
    private boolean playWhenReady;
    private boolean autoFallbackAttempted;
    private boolean closed;

    public Media3PlaybackAdapter(Context context, Looper controlLooper, Listener listener) {
        this.context = context.getApplicationContext();
        this.handler = new Handler(controlLooper);
        this.listener = listener;
    }

    @Override
    public void open(ProbePlaybackRequest request) {
        checkThread();
        if (closed || request == null) return;
        long newSessionId = request.getSessionId();
        sessionId = newSessionId;
        activeRequest = request;
        durationMs = C.TIME_UNSET;
        playWhenReady = request.isPlayWhenReady();
        autoFallbackAttempted = false;
        String unsupportedHeader = Media3RequestHeaderPolicy.findFirstUnsupported(
                request.getMedia().getHeaders());
        if (unsupportedHeader != null) {
            sourceObservation = null;
            fail(newSessionId, ProbeErrorCode.UNSUPPORTED_SOURCE, true, false,
                    "官方 Media3 播放器不支持请求头：" + unsupportedHeader, null);
            return;
        }
        selectedContainer = AutoMediaTypeDetector.initialContainer(request.getMedia());
        sourceObservation = AutoMediaTypeDetector.allowsFallback(request.getMedia())
                ? new SourceObservation() : null;
        startAttempt(newSessionId, selectedContainer, request.getStartPositionMs());
    }

    /** 每次 AUTO 判型尝试使用独立 token，旧播放器迟到事件无法污染当前会话。 */
    private void startAttempt(long expectedSessionId,
                              AutoMediaTypeDetector.Container container,
                              long startPositionMs) {
        long currentAttemptId = ++attemptId;
        try {
            releasePlayer();
            timelineGate.reset();
            durationMs = C.TIME_UNSET;
            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 30_000, 1000, 2000)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build();
            player = new ExoPlayer.Builder(context)
                    .setLooper(handler.getLooper())
                    .setLoadControl(loadControl)
                    .build();
            player.addListener(new SessionPlayerListener(expectedSessionId, currentAttemptId));
            if (surface != null) player.setVideoSurface(surface);
            player.setMediaSource(createMediaSource(activeRequest.getMedia(), container,
                    sourceObservation));
            player.seekTo(Math.max(0L, startPositionMs));
            player.setPlayWhenReady(playWhenReady);
            player.prepare();
            emitState(expectedSessionId, ProbePlaybackAdapterState.PREPARING);
        } catch (RuntimeException error) {
            fail(expectedSessionId, ProbeErrorCode.INVALID_SOURCE, true, false,
                    "媒体地址无法创建播放会话", error);
        } catch (LinkageError error) {
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, true, false,
                    "Media3 播放组件链接不兼容", error);
        }
    }

    @Override
    public void attachSurface(Surface newSurface) {
        checkThread();
        if (closed || newSurface == null || surface == newSurface) return;
        surface = newSurface;
        if (player != null) player.setVideoSurface(newSurface);
    }

    @Override
    public void clearSurface(Surface expectedSurface) {
        checkThread();
        if (closed || expectedSurface == null || surface != expectedSurface) return;
        surface = null;
        if (player != null) player.clearVideoSurface(expectedSurface);
    }

    @Override
    public void play(long expectedSessionId) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return;
        playWhenReady = true;
        player.play();
    }

    @Override
    public void pause(long expectedSessionId) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return;
        playWhenReady = false;
        player.pause();
    }

    @Override
    public void seekTo(long expectedSessionId, long positionMs) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return;
        player.seekTo(Math.max(0L, positionMs));
    }

    @Override
    public void stop(long expectedSessionId) {
        checkThread();
        if (!isMatchingSession(expectedSessionId)) return;
        sessionId = 0L;
        attemptId++;
        try {
            releasePlayer();
        } finally {
            activeRequest = null;
            sourceObservation = null;
            durationMs = C.TIME_UNSET;
            playWhenReady = false;
        }
    }

    @Override
    public ProbePlaybackSnapshot getSnapshot(long expectedSessionId) {
        checkThread();
        if (!isCurrent(expectedSessionId) || player == null) return null;
        long positionMs = normalizePosition(player.getCurrentPosition());
        long bufferedPositionMs = normalizePosition(player.getBufferedPosition());
        long playerDurationMs = normalizeDuration(player.getDuration());
        long knownDurationMs = playerDurationMs == ProbePlaybackSnapshot.TIME_UNSET
                ? normalizeDuration(durationMs) : playerDurationMs;
        return new ProbePlaybackSnapshot(positionMs, bufferedPositionMs,
                knownDurationMs, player.isPlaying());
    }

    @Override
    public void close() {
        checkThread();
        if (closed) return;
        closed = true;
        sessionId = 0L;
        attemptId++;
        try {
            releasePlayer();
        } finally {
            activeRequest = null;
            sourceObservation = null;
            surface = null;
            durationMs = C.TIME_UNSET;
            playWhenReady = false;
        }
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

    /** AUTO 只在首选容器与真实响应证据矛盾时重建一次同会话播放器。 */
    private boolean tryContainerFallback(long expectedSessionId, long expectedAttemptId,
                                         PlaybackException error) {
        if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)
                || autoFallbackAttempted || activeRequest == null || sourceObservation == null
                || !AutoMediaTypeDetector.allowsFallback(activeRequest.getMedia())) {
            return false;
        }
        AutoMediaTypeDetector.Container fallback;
        if (selectedContainer == AutoMediaTypeDetector.Container.MP4
                && Media3ProbeAdapter.containsUnrecognizedInput(error)
                && sourceObservation.hasHlsEvidence()) {
            fallback = AutoMediaTypeDetector.Container.HLS;
        } else if (selectedContainer == AutoMediaTypeDetector.Container.HLS
                && Media3ProbeAdapter.isManifestParsingFailure(error)
                && sourceObservation.hasMp4Evidence()) {
            fallback = AutoMediaTypeDetector.Container.MP4;
        } else {
            return false;
        }
        autoFallbackAttempted = true;
        selectedContainer = fallback;
        sourceObservation = null;
        long restartPositionMs = player == null
                ? activeRequest.getStartPositionMs() : normalizePosition(player.getCurrentPosition());
        attemptId++;
        listener.onTimeline(expectedSessionId, ProbePlaybackSnapshot.TIME_UNSET,
                false, false);
        listener.onPositionDiscontinuity(expectedSessionId, restartPositionMs,
                ProbePlaybackDiscontinuityReason.INTERNAL);
        if (!isCurrent(expectedSessionId)) return true;
        startAttempt(expectedSessionId, selectedContainer, restartPositionMs);
        return true;
    }

    private void emitState(long expectedSessionId, ProbePlaybackAdapterState state) {
        if (listener != null && isCurrent(expectedSessionId)) {
            listener.onState(expectedSessionId, state);
        }
    }

    private void fail(long expectedSessionId, ProbeErrorCode code, boolean fatal,
                      boolean retryable, String message, Throwable cause) {
        if (!isCurrent(expectedSessionId)) return;
        if (fatal) attemptId++;
        if (listener != null) {
            Media3PlaybackBoundary.ignore(() -> listener.onError(expectedSessionId,
                    code, fatal, retryable, message, cause));
        }
        if (fatal) releasePlayerQuietly();
    }

    private boolean isCurrent(long expectedSessionId) {
        return !closed && expectedSessionId > 0L && sessionId == expectedSessionId;
    }

    private boolean isAttemptCurrent(long expectedSessionId, long expectedAttemptId) {
        return isCurrent(expectedSessionId) && attemptId == expectedAttemptId;
    }

    private boolean isMatchingSession(long expectedSessionId) {
        return expectedSessionId > 0L && sessionId == expectedSessionId;
    }

    private void releasePlayer() {
        ExoPlayer current = player;
        player = null;
        if (current != null) current.release();
    }

    private void releasePlayerQuietly() {
        Media3PlaybackBoundary.ignore(this::releasePlayer);
    }

    private void checkThread() {
        if (Looper.myLooper() != handler.getLooper()) {
            throw new IllegalStateException("Media3 播放适配器必须在控制 Looper 调用");
        }
    }

    private static long normalizePosition(long positionMs) {
        return positionMs < 0L ? 0L : positionMs;
    }

    private static long normalizeDuration(long valueMs) {
        return valueMs < 0L ? ProbePlaybackSnapshot.TIME_UNSET : valueMs;
    }

    private static DefaultHttpDataSource.Factory httpDataSourceFactory(
            java.util.Map<String, String> headers) {
        return new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                // 禁止跨协议重定向，避免改变安全边界和请求语义。
                .setAllowCrossProtocolRedirects(false)
                .setDefaultRequestProperties(headers);
    }

    private static ProbePlaybackDiscontinuityReason mapDiscontinuityReason(int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK
                || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            return ProbePlaybackDiscontinuityReason.SEEK;
        }
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            return ProbePlaybackDiscontinuityReason.AUTO_TRANSITION;
        }
        if (reason == Player.DISCONTINUITY_REASON_REMOVE) {
            return ProbePlaybackDiscontinuityReason.SOURCE_CHANGE;
        }
        return ProbePlaybackDiscontinuityReason.INTERNAL;
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
                    true, false, "可见播放器仅支持有限点播时间轴", null);
            return false;
        }
        durationMs = window.getDurationMs();
        listener.onTimeline(expectedSessionId, normalizeDuration(durationMs), false, false);
        return true;
    }

    private static ProbeErrorCode mapPlaybackError(PlaybackException error) {
        if (error.errorCode >= PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
                && error.errorCode <= PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED) {
            return ProbeErrorCode.DRM_NOT_SUPPORTED;
        }
        if (error.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                && error.errorCode <= PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            return ProbeErrorCode.SOURCE_IO;
        }
        if (error.errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                && error.errorCode <= PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            return ProbeErrorCode.UNSUPPORTED_SOURCE;
        }
        return ProbeErrorCode.DECODER_FAILED;
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
            handlePlayerCallback("处理 Media3 播放状态失败", () -> {
                if (player == null) return;
                if (playbackState == Player.STATE_BUFFERING) {
                    emitState(expectedSessionId, ProbePlaybackAdapterState.BUFFERING);
                } else if (playbackState == Player.STATE_READY) {
                    if (publishAuthoritativeTimeline(expectedSessionId, expectedAttemptId)) {
                        emitState(expectedSessionId, ProbePlaybackAdapterState.READY);
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    emitState(expectedSessionId, ProbePlaybackAdapterState.ENDED);
                } else if (playbackState == Player.STATE_IDLE) {
                    emitState(expectedSessionId, ProbePlaybackAdapterState.IDLE);
                }
            });
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            handlePlayerCallback("处理 Media3 时间轴失败", () -> {
                if (timeline == null || timeline.isEmpty()) return;
                if (publishAuthoritativeTimeline(expectedSessionId, expectedAttemptId)
                        && player != null && player.getPlaybackState() == Player.STATE_READY) {
                    emitState(expectedSessionId, ProbePlaybackAdapterState.READY);
                }
            });
        }

        @Override
        public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                                            Player.PositionInfo newPosition, int reason) {
            handlePlayerCallback("处理 Media3 时间轴跳变失败", () -> {
                if (newPosition == null) return;
                listener.onPositionDiscontinuity(expectedSessionId,
                        normalizePosition(newPosition.positionMs), mapDiscontinuityReason(reason));
            });
        }

        @Override
        public void onVideoSizeChanged(VideoSize videoSize) {
            handlePlayerCallback("处理 Media3 视频尺寸失败", () -> {
                if (videoSize == null) return;
                listener.onVideoSize(expectedSessionId, videoSize.width, videoSize.height,
                        videoSize.pixelWidthHeightRatio, videoSize.unappliedRotationDegrees);
            });
        }

        @Override
        public void onRenderedFirstFrame() {
            handlePlayerCallback("处理 Media3 首帧事件失败",
                    () -> listener.onFirstFrame(expectedSessionId));
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            handlePlayerCallback("处理 Media3 播放错误失败", () -> {
                if (tryContainerFallback(expectedSessionId, expectedAttemptId, error)) return;
                ProbeErrorCode code = mapPlaybackError(error);
                fail(expectedSessionId, code, true,
                        Media3ProbeAdapter.isRetryableMediaError(code, error),
                        "可见播放器无法继续播放当前媒体", error);
            });
        }

        private void handlePlayerCallback(String message, Runnable callback) {
            if (!isAttemptCurrent(expectedSessionId, expectedAttemptId)) return;
            Media3PlaybackBoundary.run(callback, error -> fail(expectedSessionId,
                    ProbeErrorCode.INTERNAL, true, false, message, error));
        }
    }
}
