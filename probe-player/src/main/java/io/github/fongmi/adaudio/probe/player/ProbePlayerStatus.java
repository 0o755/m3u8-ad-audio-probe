/* 状态快照汇总会话、时间轴、播放意图、显示参数和最近错误。 */
package io.github.fongmi.adaudio.probe.player;

import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackSnapshot;

/** 任意线程可读取的不可变播放器状态快照。 */
public final class ProbePlayerStatus {
    private final ProbePlayerState state;
    private final long sessionId;
    private final String mediaId;
    private final long positionMs;
    private final long bufferedPositionMs;
    private final long durationMs;
    private final boolean playing;
    private final ProbePlayerVideoSize videoSize;
    private final ProbePlayerError lastError;

    ProbePlayerStatus(ProbePlayerState state, long sessionId, String mediaId,
                      long positionMs, long bufferedPositionMs, long durationMs,
                      boolean playing, ProbePlayerVideoSize videoSize,
                      ProbePlayerError lastError) {
        if (state == null) throw new IllegalArgumentException("播放状态不能为空");
        if (sessionId < 0L) throw new IllegalArgumentException("会话 ID 不能为负数");
        if (positionMs < 0L || bufferedPositionMs < 0L) {
            throw new IllegalArgumentException("时间轴位置不能为负数");
        }
        if (durationMs < 0L && durationMs != ProbePlaybackSnapshot.TIME_UNSET) {
            throw new IllegalArgumentException("媒体时长无效");
        }
        if (sessionId == 0L && mediaId != null && !mediaId.isEmpty()) {
            throw new IllegalArgumentException("无活动会话时媒体 ID 必须为空");
        }
        if (sessionId > 0L && (mediaId == null || mediaId.isEmpty())) {
            throw new IllegalArgumentException("活动会话必须包含媒体 ID");
        }
        if (lastError != null && lastError.getSessionId() != sessionId) {
            throw new IllegalArgumentException("错误对象不属于当前会话");
        }
        this.state = state;
        this.sessionId = sessionId;
        this.mediaId = mediaId == null ? "" : mediaId;
        this.positionMs = positionMs;
        this.bufferedPositionMs = bufferedPositionMs;
        this.durationMs = durationMs;
        this.playing = playing;
        this.videoSize = videoSize == null ? ProbePlayerVideoSize.unknown() : videoSize;
        this.lastError = lastError;
    }

    static ProbePlayerStatus idle(ProbePlayerState state) {
        return new ProbePlayerStatus(state, 0L, "", 0L, 0L,
                ProbePlaybackSnapshot.TIME_UNSET, false,
                ProbePlayerVideoSize.unknown(), null);
    }

    /** 返回稳定播放生命周期状态。 */
    public ProbePlayerState getState() {
        return state;
    }

    /** 返回正数活动会话 ID；无媒体时为 0。 */
    public long getSessionId() {
        return sessionId;
    }

    /** 返回活动媒体 ID；无媒体时为空字符串。 */
    public String getMediaId() {
        return mediaId;
    }

    /** 返回当前播放位置，单位毫秒。 */
    public long getPositionMs() {
        return positionMs;
    }

    /** 返回当前缓冲位置，单位毫秒。 */
    public long getBufferedPositionMs() {
        return bufferedPositionMs;
    }

    /** 返回媒体时长；尚未知晓时为 {@link ProbePlaybackSnapshot#TIME_UNSET}。 */
    public long getDurationMs() {
        return durationMs;
    }

    /** 返回播放器此刻是否正在推进时间轴。 */
    public boolean isPlaying() {
        return playing;
    }

    /** 返回当前视频显示参数；未知时 {@link ProbePlayerVideoSize#isKnown()} 为 false。 */
    public ProbePlayerVideoSize getVideoSize() {
        return videoSize;
    }

    /** 返回当前会话最近一次错误；没有时为 {@code null}。 */
    public ProbePlayerError getLastError() {
        return lastError;
    }
}
