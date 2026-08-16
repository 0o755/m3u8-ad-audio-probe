/* 表示协调器输出的不可变广告区间，以及仅供派发队列消费的撤销证据。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import java.util.Objects;

/**
 * 已确认广告只描述媒体时间轴，不持有播放器或匹配器的可变状态。
 */
public final class ConfirmedAd {
    private final String ruleId;
    private final long startTimeMs;
    private final long endTimeMs;
    private final long matchedAtTimeMs;
    private final long confirmedThroughTimeMs;
    private final float matchSimilarity;
    private final int matchedFrames;
    private final int evidenceCount;
    private final boolean revocation;

    ConfirmedAd(String ruleId, long startTimeMs, long endTimeMs,
                long matchedAtTimeMs, long confirmedThroughTimeMs,
                float matchSimilarity, int matchedFrames, int evidenceCount) {
        this(ruleId, startTimeMs, endTimeMs, matchedAtTimeMs, confirmedThroughTimeMs,
                matchSimilarity, matchedFrames, evidenceCount, false);
    }

    private ConfirmedAd(String ruleId, long startTimeMs, long endTimeMs,
                        long matchedAtTimeMs, long confirmedThroughTimeMs,
                        float matchSimilarity, int matchedFrames, int evidenceCount,
                        boolean revocation) {
        this.ruleId = Objects.requireNonNull(ruleId, "规则 ID 不能为空");
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.matchedAtTimeMs = matchedAtTimeMs;
        this.confirmedThroughTimeMs = confirmedThroughTimeMs;
        this.matchSimilarity = matchSimilarity;
        this.matchedFrames = matchedFrames;
        this.evidenceCount = evidenceCount;
        this.revocation = revocation;
    }

    public String getRuleId() {
        return ruleId;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }

    public long getEndTimeMs() {
        return endTimeMs;
    }

    public long getDurationMs() {
        return endTimeMs - startTimeMs;
    }

    public long getMatchedAtTimeMs() {
        return matchedAtTimeMs;
    }

    public long getConfirmedThroughTimeMs() {
        return confirmedThroughTimeMs;
    }

    public float getMatchSimilarity() {
        return matchSimilarity;
    }

    public int getMatchedFrames() {
        return matchedFrames;
    }

    public int getEvidenceCount() {
        return evidenceCount;
    }

    static ConfirmedAd revocationOf(ConfirmedAd ad) {
        return new ConfirmedAd(ad.ruleId, ad.startTimeMs, ad.endTimeMs,
                ad.matchedAtTimeMs, ad.confirmedThroughTimeMs, ad.matchSimilarity,
                ad.matchedFrames, ad.evidenceCount, true);
    }

    boolean isRevocation() {
        return revocation;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConfirmedAd)) return false;
        ConfirmedAd that = (ConfirmedAd) other;
        return startTimeMs == that.startTimeMs
                && endTimeMs == that.endTimeMs
                && matchedAtTimeMs == that.matchedAtTimeMs
                && confirmedThroughTimeMs == that.confirmedThroughTimeMs
                && Float.compare(matchSimilarity, that.matchSimilarity) == 0
                && matchedFrames == that.matchedFrames
                && evidenceCount == that.evidenceCount
                && revocation == that.revocation
                && ruleId.equals(that.ruleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ruleId, startTimeMs, endTimeMs, matchedAtTimeMs,
                confirmedThroughTimeMs, matchSimilarity, matchedFrames, evidenceCount,
                revocation);
    }

    @Override
    public String toString() {
        return "ConfirmedAd{" + ruleId + ", " + startTimeMs + ".." + endTimeMs + '}';
    }
}
