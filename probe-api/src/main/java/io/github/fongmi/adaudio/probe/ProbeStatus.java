/* 状态快照汇总会话、时间轴和规则信息，避免高频暴露内部事件。 */
package io.github.fongmi.adaudio.probe;

/** 某一时刻的不可变探针状态快照。 */
public final class ProbeStatus {
    private final ProbeState state;
    private final long sessionId;
    private final String mediaId;
    private final long hostPositionMs;
    private final long analyzedThroughPositionMs;
    private final long ruleRevision;
    private final int ruleCount;
    private final ProbeError lastError;

    ProbeStatus(ProbeState state, long sessionId, String mediaId,
                long hostPositionMs, long analyzedThroughPositionMs,
                long ruleRevision, int ruleCount, ProbeError lastError) {
        if (state == null) throw new IllegalArgumentException("探针状态不能为空");
        ApiValidation.requireNonNegative(sessionId, "会话 ID");
        ApiValidation.requireNonNegative(hostPositionMs, "宿主位置");
        ApiValidation.requireNonNegative(analyzedThroughPositionMs, "已分析位置");
        ApiValidation.requireNonNegative(ruleRevision, "规则 revision");
        if (ruleCount < 0) throw new IllegalArgumentException("规则数量不能为负数");
        if (sessionId > 0L) {
            ApiValidation.requireId(mediaId, "媒体 ID", 256);
        } else if (mediaId == null || !mediaId.isEmpty()) {
            throw new IllegalArgumentException("无活动会话时媒体 ID 必须为空");
        }
        if (lastError != null && lastError.getSessionId() != sessionId) {
            throw new IllegalArgumentException("错误对象不属于当前会话");
        }
        this.state = state;
        this.sessionId = sessionId;
        this.mediaId = mediaId;
        this.hostPositionMs = hostPositionMs;
        this.analyzedThroughPositionMs = analyzedThroughPositionMs;
        this.ruleRevision = ruleRevision;
        this.ruleCount = ruleCount;
        this.lastError = lastError;
    }

    static ProbeStatus idle() {
        return new ProbeStatus(ProbeState.IDLE, 0L, "", 0L,
                0L, 0L, 0, null);
    }

    /**
     * 返回会话生命周期状态。
     *
     * @return 非空状态
     */
    public ProbeState getState() {
        return state;
    }

    /**
     * 返回快照所属会话。
     *
     * @return 非负会话 ID；没有活动媒体时为 {@code 0}
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * 返回媒体标识。
     *
     * @return 活动会话的非空媒体 ID；没有活动媒体时为空字符串
     */
    public String getMediaId() {
        return mediaId;
    }

    /**
     * 返回采样该快照时的宿主位置。
     *
     * @return 非负媒体位置，单位毫秒
     */
    public long getHostPositionMs() {
        return hostPositionMs;
    }

    /**
     * 返回探针已连续分析到的媒体位置。
     *
     * @return 非负媒体位置，单位毫秒
     */
    public long getAnalyzedThroughPositionMs() {
        return analyzedThroughPositionMs;
    }

    /**
     * 返回当前可用前视距离。
     *
     * @return {@code max(0, 已分析位置 - 宿主位置)}，单位毫秒
     */
    public long getLookaheadMs() {
        return Math.max(0L, analyzedThroughPositionMs - hostPositionMs);
    }

    /**
     * 返回当前规则集 revision。
     *
     * @return 非负 revision；尚未加载规则时为 {@code 0}
     */
    public long getRuleRevision() {
        return ruleRevision;
    }

    /**
     * 返回当前规则数量。
     *
     * @return 非负规则数
     */
    public int getRuleCount() {
        return ruleCount;
    }

    /**
     * 返回当前会话最近一次错误。
     *
     * @return 错误对象；当前会话尚无错误时为 {@code null}
     */
    public ProbeError getLastError() {
        return lastError;
    }
}
