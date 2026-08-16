/* 跳转请求携带完整媒体区间与代际，宿主无需理解匹配器内部事件。 */
package io.github.fongmi.adaudio.probe;

/**
 * 已完成规则冲突和当前会话复核的不可变跳转决定。
 *
 * <p>对象由 SDK 产生；宿主只需校验自己的播放代际后执行目标位置。</p>
 */
public final class SkipRequest {
    private final long requestId;
    private final long sessionId;
    private final String mediaId;
    private final String ruleId;
    private final long ruleRevision;
    private final long adStartPositionMs;
    private final long adEndPositionMs;
    private final long seekTargetPositionMs;
    private final long hostPositionMsAtDispatch;
    private final long analyzedThroughPositionMs;
    private final float matchSimilarity;

    SkipRequest(long requestId, long sessionId, String mediaId, String ruleId,
                long ruleRevision, long adStartPositionMs, long adEndPositionMs,
                long seekTargetPositionMs, long hostPositionMsAtDispatch,
                long analyzedThroughPositionMs, float matchSimilarity) {
        if (requestId <= 0 || sessionId <= 0) throw new IllegalArgumentException("请求代际无效");
        ApiValidation.requireId(mediaId, "媒体 ID", 256);
        ApiValidation.requireId(ruleId, "规则 ID", 64);
        if (ruleRevision <= 0L) throw new IllegalArgumentException("规则 revision 必须为正数");
        if (adStartPositionMs < 0L || adEndPositionMs <= adStartPositionMs) {
            throw new IllegalArgumentException("广告时间区间无效");
        }
        if (seekTargetPositionMs < adEndPositionMs) {
            throw new IllegalArgumentException("跳转目标不能位于广告结束位置之前");
        }
        if (hostPositionMsAtDispatch < adStartPositionMs
                || hostPositionMsAtDispatch >= adEndPositionMs) {
            throw new IllegalArgumentException("派发位置必须位于广告区间内");
        }
        ApiValidation.requireNonNegative(analyzedThroughPositionMs, "已分析位置");
        if (Float.isNaN(matchSimilarity) || Float.isInfinite(matchSimilarity)
                || matchSimilarity < 0.0f || matchSimilarity > 1.0f) {
            throw new IllegalArgumentException("匹配相似度必须是 0 到 1 的有限数");
        }
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.mediaId = mediaId;
        this.ruleId = ruleId;
        this.ruleRevision = ruleRevision;
        this.adStartPositionMs = adStartPositionMs;
        this.adEndPositionMs = adEndPositionMs;
        this.seekTargetPositionMs = seekTargetPositionMs;
        this.hostPositionMsAtDispatch = hostPositionMsAtDispatch;
        this.analyzedThroughPositionMs = analyzedThroughPositionMs;
        this.matchSimilarity = matchSimilarity;
    }

    /**
     * 返回本次跳转决定的唯一序号。
     *
     * @return 正数请求 ID
     */
    public long getRequestId() {
        return requestId;
    }

    /**
     * 返回决定所属媒体会话。
     *
     * @return 正数会话 ID
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * 返回命中媒体的标识。
     *
     * @return 非空媒体 ID
     */
    public String getMediaId() {
        return mediaId;
    }

    /**
     * 返回命中的广告规则标识。
     *
     * @return 非空规则 ID
     */
    public String getRuleId() {
        return ruleId;
    }

    /**
     * 返回命中规则集的 revision。
     *
     * @return 正数 revision
     */
    public long getRuleRevision() {
        return ruleRevision;
    }

    /**
     * 返回广告开始位置。
     *
     * @return 非负媒体位置，单位毫秒
     */
    public long getAdStartPositionMs() {
        return adStartPositionMs;
    }

    /**
     * 返回广告结束位置。
     *
     * @return 大于开始位置的媒体位置，单位毫秒
     */
    public long getAdEndPositionMs() {
        return adEndPositionMs;
    }

    /**
     * 返回宿主应跳转到的位置。
     *
     * @return 不早于广告结束位置的媒体位置，单位毫秒
     */
    public long getSeekTargetPositionMs() {
        return seekTargetPositionMs;
    }

    /**
     * 返回 SDK 派发前复核到的宿主位置。
     *
     * @return 位于广告区间内的媒体位置，单位毫秒
     */
    public long getHostPositionMsAtDispatch() {
        return hostPositionMsAtDispatch;
    }

    /**
     * 返回派发时探针已连续分析到的位置。
     *
     * @return 非负媒体位置，单位毫秒
     */
    public long getAnalyzedThroughPositionMs() {
        return analyzedThroughPositionMs;
    }

    /**
     * 返回确认帧的平均指纹相似度。
     *
     * @return {@code 0.0} 到 {@code 1.0} 的 32-bit Hamming 相似度；不表示统计概率
     */
    public float getMatchSimilarity() {
        return matchSimilarity;
    }
}
