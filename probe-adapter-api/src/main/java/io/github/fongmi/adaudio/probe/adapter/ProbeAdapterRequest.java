/* 不可变请求向适配器传递媒体、会话与前视边界。 */
package io.github.fongmi.adaudio.probe.adapter;

import io.github.fongmi.adaudio.probe.ProbeMedia;

/** 单次适配器媒体会话的不可变配置。 */
public final class ProbeAdapterRequest {
    private final long sessionId;
    private final ProbeMedia media;
    private final long startPositionMs;
    private final long maxLookaheadMs;

    /**
     * 创建请求。通常由 SDK runtime 调用，第三方适配器只需读取。
     */
    public ProbeAdapterRequest(long sessionId, ProbeMedia media,
                               long startPositionMs, long maxLookaheadMs) {
        if (sessionId <= 0L) throw new IllegalArgumentException("会话 ID 必须为正数");
        if (media == null) throw new IllegalArgumentException("媒体请求不能为空");
        if (startPositionMs < 0L) throw new IllegalArgumentException("开始位置不能为负数");
        if (maxLookaheadMs <= 0L) throw new IllegalArgumentException("前视长度必须为正数");
        this.sessionId = sessionId;
        this.media = media;
        this.startPositionMs = startPositionMs;
        this.maxLookaheadMs = maxLookaheadMs;
    }

    public long getSessionId() {
        return sessionId;
    }

    public ProbeMedia getMedia() {
        return media;
    }

    public long getStartPositionMs() {
        return startPositionMs;
    }

    public long getMaxLookaheadMs() {
        return maxLookaheadMs;
    }
}
