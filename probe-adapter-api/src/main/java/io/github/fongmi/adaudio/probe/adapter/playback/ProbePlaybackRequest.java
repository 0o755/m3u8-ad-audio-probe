/* 不可变播放请求携带媒体、会话、起播位置和初始播放意图。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

import io.github.fongmi.adaudio.probe.ProbeMedia;

/** 单次可见播放会话的不可变配置。 */
public final class ProbePlaybackRequest {
    private final long sessionId;
    private final ProbeMedia media;
    private final long startPositionMs;
    private final boolean playWhenReady;

    /**
     * 创建播放请求。
     *
     * @param sessionId 正数会话 ID
     * @param media 已校验的普通 HLS/MP4 点播请求
     * @param startPositionMs 非负起播位置，单位毫秒
     * @param playWhenReady 准备完成后是否立即播放
     */
    public ProbePlaybackRequest(long sessionId, ProbeMedia media, long startPositionMs,
                                boolean playWhenReady) {
        if (sessionId <= 0L) throw new IllegalArgumentException("会话 ID 必须为正数");
        if (media == null) throw new IllegalArgumentException("媒体请求不能为空");
        if (startPositionMs < 0L) throw new IllegalArgumentException("开始位置不能为负数");
        this.sessionId = sessionId;
        this.media = media;
        this.startPositionMs = startPositionMs;
        this.playWhenReady = playWhenReady;
    }

    /** 返回正数会话 ID。 */
    public long getSessionId() {
        return sessionId;
    }

    /** 返回已校验且不可变的媒体请求。 */
    public ProbeMedia getMedia() {
        return media;
    }

    /** 返回起播位置，单位毫秒。 */
    public long getStartPositionMs() {
        return startPositionMs;
    }

    /** 返回准备完成后是否应立即播放。 */
    public boolean isPlayWhenReady() {
        return playWhenReady;
    }
}
