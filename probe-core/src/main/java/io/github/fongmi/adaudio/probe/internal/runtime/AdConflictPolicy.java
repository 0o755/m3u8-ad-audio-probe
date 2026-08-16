/* 统一判定近似指纹产生的冲突跳转，任何不唯一终点都按 fail-open 处理。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.MatchEvent;

final class AdConflictPolicy {
    static final long DETECTION_TIME_TOLERANCE_MS = 250L;
    static final long END_TIME_TOLERANCE_MS = 250L;
    private static final int MAX_CONFIRMATION_FRAMES = AdRuleSet.MAX_CONFIRMATION_FRAMES;
    private static final long FRAME_HOP_MS = AdRuleSet.HOP_MS;

    private AdConflictPolicy() {
    }

    static boolean conflicts(MatchEvent first, MatchEvent second) {
        return first != null && second != null && conflicts(
                first.getStartTimeMs(), first.getEndTimeMs(),
                normalizedDetectionTime(first.getMatchedAtTimeMs(), first.getMatchedFrames()),
                second.getStartTimeMs(), second.getEndTimeMs(),
                normalizedDetectionTime(second.getMatchedAtTimeMs(), second.getMatchedFrames()));
    }

    static boolean conflicts(ConfirmedAd first, ConfirmedAd second) {
        return first != null && second != null && conflicts(
                first.getStartTimeMs(), first.getEndTimeMs(),
                normalizedDetectionTime(first.getMatchedAtTimeMs(), first.getMatchedFrames()),
                second.getStartTimeMs(), second.getEndTimeMs(),
                normalizedDetectionTime(second.getMatchedAtTimeMs(), second.getMatchedFrames()));
    }

    static boolean hasDifferentDestination(long firstEndTimeMs, long secondEndTimeMs) {
        return distance(firstEndTimeMs, secondEndTimeMs) > END_TIME_TOLERANCE_MS;
    }

    static long normalizedDetectionTime(long matchedAtTimeMs, int matchedFrames) {
        long remainingSteps = Math.max(0L,
                (long) MAX_CONFIRMATION_FRAMES - matchedFrames + 1L);
        return safeAdd(matchedAtTimeMs, remainingSteps * FRAME_HOP_MS);
    }

    private static boolean conflicts(long firstStartTimeMs, long firstEndTimeMs,
                                     long firstDetectionTimeMs,
                                     long secondStartTimeMs, long secondEndTimeMs,
                                     long secondDetectionTimeMs) {
        if (!hasDifferentDestination(firstEndTimeMs, secondEndTimeMs)) return false;
        boolean sameDetection = distance(firstDetectionTimeMs, secondDetectionTimeMs)
                <= DETECTION_TIME_TOLERANCE_MS;
        boolean overlaps = firstStartTimeMs < secondEndTimeMs
                && secondStartTimeMs < firstEndTimeMs;
        return sameDetection || overlaps;
    }

    private static long distance(long first, long second) {
        return first >= second ? first - second : second - first;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
