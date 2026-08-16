/* 定义候选、可跳过确认和完整锚点验证三类匹配事件。 */
package io.github.fongmi.adaudio.probe.internal.core;

/** 匹配事件只提供不可变建议，播放器是否跳转仍由宿主决定。 */
public final class MatchEvent {
    public enum Type { CANDIDATE_MATCHED, START_MATCHED, FULL_MATCHED }

    private final Type type;
    private final String ruleId;
    private final long startTimeMs;
    private final long endTimeMs;
    private final long matchedAtTimeMs;
    private final float matchSimilarity;
    private final int matchedFrames;

    public MatchEvent(Type type, String ruleId, long startTimeMs, long endTimeMs,
                      long matchedAtTimeMs,
                      float matchSimilarity, int matchedFrames) {
        this.type = type;
        this.ruleId = ruleId;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.matchedAtTimeMs = Math.max(0L, matchedAtTimeMs);
        this.matchSimilarity = Math.max(0.0f, Math.min(1.0f, matchSimilarity));
        this.matchedFrames = Math.max(0, matchedFrames);
    }

    public Type getType() { return type; }
    public String getRuleId() { return ruleId; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public long getMatchedAtTimeMs() { return matchedAtTimeMs; }
    public long getRemainingDurationMs() {
        return Math.max(0L, endTimeMs - matchedAtTimeMs);
    }

    /**
     * 跨时钟重映射无法证明安全；保留旧接口只为二进制兼容，并始终返回绝对终点。
     * @deprecated 使用播放器适配层的挂起计划，在到达 startTimeMs 后跳 endTimeMs。
     */
    @Deprecated
    public long rebaseEndTimeMs(long currentPositionMs) {
        return endTimeMs;
    }
    /** 返回确认帧的平均 32-bit Hamming 相似度，不表示统计概率。 */
    public float getMatchSimilarity() { return matchSimilarity; }
    public int getMatchedFrames() { return matchedFrames; }
}
