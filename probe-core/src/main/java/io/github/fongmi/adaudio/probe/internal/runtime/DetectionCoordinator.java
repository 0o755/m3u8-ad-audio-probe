/* 协调同一广告 occurrence 的近似匹配，确认唯一且稳定的跳转区间。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import io.github.fongmi.adaudio.probe.internal.core.MatchEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 匹配事件协调器。调用方应把同批事件一次传入，再以该批 PCM 的末端推进水位。
 */
public final class DetectionCoordinator {
    static final long OCCURRENCE_START_TOLERANCE_MS = 250L;

    private final MatchEvent.Type acceptedType;
    private final int maxFingerprintFrames;
    private final int hopMs;
    private final List<PendingOccurrence> pendingOccurrences = new ArrayList<>();
    private final List<ResolvedOccurrence> resolvedOccurrences = new ArrayList<>();
    private final List<MatchEvent> suppressedEvidence = new ArrayList<>();
    private final List<ConfirmedAd> immediateConflicts = new ArrayList<>();
    private long analyzedThroughMs = -1L;

    /** 保留给核心冲突单测的 START 协调模式。生产跳转应使用 fullMatchOnly。 */
    public DetectionCoordinator() {
        this(MatchEvent.Type.START_MATCHED, 0, 0);
    }

    /** 创建完整锚点验证模式；START 仅作匹配器内部候选，不产生跳转区间。 */
    public static DetectionCoordinator fullMatchOnly(int maxFingerprintFrames, int hopMs) {
        if (maxFingerprintFrames < 4 || hopMs <= 0) {
            throw new IllegalArgumentException("完整指纹窗口参数无效");
        }
        return new DetectionCoordinator(MatchEvent.Type.FULL_MATCHED,
                maxFingerprintFrames, hopMs);
    }

    private DetectionCoordinator(MatchEvent.Type acceptedType,
                                 int maxFingerprintFrames, int hopMs) {
        this.acceptedType = acceptedType;
        this.maxFingerprintFrames = maxFingerprintFrames;
        this.hopMs = hopMs;
    }

    /** 登记一条匹配证据；不符合当前安全模式的事件会被忽略。 */
    public synchronized List<ConfirmedAd> onMatch(MatchEvent event) {
        addStartEvent(event);
        return resolveReadyOccurrences();
    }

    /** 一次登记同批匹配证据，调用后仍由 onAnalyzedThrough 推进水位。 */
    public synchronized List<ConfirmedAd> onMatches(List<MatchEvent> events) {
        if (events != null) {
            for (MatchEvent event : events) addStartEvent(event);
        }
        return resolveReadyOccurrences();
    }

    /** 推进已分析水位，并返回本次刚完成消歧的广告区间。 */
    public synchronized List<ConfirmedAd> onAnalyzedThrough(long analyzedThroughTimeMs) {
        if (analyzedThroughTimeMs >= 0L) {
            analyzedThroughMs = Math.max(analyzedThroughMs, analyzedThroughTimeMs);
        }
        return resolveReadyOccurrences();
    }

    /** 时间轴跳变或媒体切换后必须清空，避免旧区间污染新会话。 */
    public synchronized void reset() {
        resetInternal();
    }

    private void addStartEvent(MatchEvent event) {
        if (!isValidEvidence(event)) return;

        PendingOccurrence occurrence = findPendingOccurrence(event.getStartTimeMs());
        if (occurrence == null) {
            if (isResolvedDuplicate(event)) return;
            occurrence = new PendingOccurrence(event, confirmationDeadline(event));
            pendingOccurrences.add(occurrence);
        } else {
            occurrence.addEvidence(event);
        }
        markCrossOccurrenceConflicts(occurrence, event);
    }

    /** 不同锚点会推导出相距很远的起点，仍需按检测时刻和重叠区间全局消歧。 */
    private void markCrossOccurrenceConflicts(PendingOccurrence occurrence,
                                              MatchEvent newEvidence) {
        for (PendingOccurrence other : pendingOccurrences) {
            if (other == occurrence) continue;
            for (MatchEvent current : other.evidence) {
                if (!AdConflictPolicy.conflicts(newEvidence, current)) continue;
                occurrence.globallyAmbiguous = true;
                other.globallyAmbiguous = true;
                break;
            }
        }
        for (MatchEvent current : suppressedEvidence) {
            if (!AdConflictPolicy.conflicts(newEvidence, current)) continue;
            occurrence.globallyAmbiguous = true;
            break;
        }
        for (ResolvedOccurrence resolved : resolvedOccurrences) {
            if (!resolved.conflictsWith(newEvidence)) continue;
            occurrence.globallyAmbiguous = true;
            rememberSuppressedEvidence(resolved.evidence);
            if (resolved.confirmedAd != null) {
                for (ConfirmedAd ad : resolvedConflictEvidence(resolved, newEvidence)) {
                    occurrence.addConflictingConfirmedAd(ad);
                    addUniqueConfirmed(immediateConflicts, ad);
                }
            }
        }
    }

    private PendingOccurrence findPendingOccurrence(long startTimeMs) {
        for (PendingOccurrence occurrence : pendingOccurrences) {
            if (isNear(occurrence.firstStartTimeMs, startTimeMs,
                    OCCURRENCE_START_TOLERANCE_MS)) {
                return occurrence;
            }
        }
        return null;
    }

    private boolean isResolvedDuplicate(MatchEvent event) {
        for (ResolvedOccurrence occurrence : resolvedOccurrences) {
            if (isNear(occurrence.startTimeMs, event.getStartTimeMs(),
                    OCCURRENCE_START_TOLERANCE_MS)
                    && occurrence.hasCompatibleEnd(event.getEndTimeMs())) {
                return true;
            }
        }
        return false;
    }

    private List<ConfirmedAd> resolveReadyOccurrences() {
        List<ConfirmedAd> confirmed = new ArrayList<>(immediateConflicts);
        immediateConflicts.clear();
        if (analyzedThroughMs < 0L || pendingOccurrences.isEmpty()) {
            return immutableOrEmpty(confirmed);
        }
        Iterator<PendingOccurrence> iterator = pendingOccurrences.iterator();
        while (iterator.hasNext()) {
            PendingOccurrence occurrence = iterator.next();
            if (analyzedThroughMs < occurrence.confirmationDeadlineMs) continue;

            iterator.remove();
            if (occurrence.hasAmbiguousEnd() || occurrence.globallyAmbiguous) {
                rememberSuppressedEvidence(occurrence.evidence);
                if (!occurrence.conflictingConfirmedAds.isEmpty()) {
                    for (ConfirmedAd ad : occurrence.conflictingConfirmedAds) {
                        addUniqueConfirmed(confirmed, ad);
                    }
                    for (ConfirmedAd ad : occurrence.toConfirmedAds(analyzedThroughMs)) {
                        addUniqueConfirmed(confirmed, ad);
                    }
                }
                resolvedOccurrences.add(new ResolvedOccurrence(occurrence.evidence, null));
            } else {
                ConfirmedAd ad = occurrence.toConfirmedAd(analyzedThroughMs);
                addUniqueConfirmed(confirmed, ad);
                resolvedOccurrences.add(new ResolvedOccurrence(occurrence.evidence, ad));
            }
        }
        return immutableOrEmpty(confirmed);
    }

    private List<ConfirmedAd> resolvedConflictEvidence(ResolvedOccurrence resolved,
                                                       MatchEvent newEvidence) {
        List<ConfirmedAd> output = new ArrayList<>();
        addUniqueConfirmed(output, ConfirmedAd.revocationOf(resolved.confirmedAd));
        for (MatchEvent historical : resolved.evidence) {
            ConfirmedAd ad = PendingOccurrence.toConfirmedAd(historical,
                    resolved.confirmedAd.getConfirmedThroughTimeMs(),
                    resolved.evidence.size());
            if (!sameInterval(ad, resolved.confirmedAd)) addUniqueConfirmed(output, ad);
        }
        long confirmedThroughTimeMs = analyzedThroughMs >= 0L
                ? analyzedThroughMs : newEvidence.getMatchedAtTimeMs();
        addUniqueConfirmed(output, PendingOccurrence.toConfirmedAd(
                newEvidence, confirmedThroughTimeMs, 1));
        return output;
    }

    private static void addUniqueConfirmed(List<ConfirmedAd> output, ConfirmedAd candidate) {
        for (ConfirmedAd current : output) {
            if (current.getRuleId().equals(candidate.getRuleId())
                    && current.getStartTimeMs() == candidate.getStartTimeMs()
                    && current.getEndTimeMs() == candidate.getEndTimeMs()
                    && current.isRevocation() == candidate.isRevocation()) return;
        }
        output.add(candidate);
    }

    private static boolean sameInterval(ConfirmedAd first, ConfirmedAd second) {
        return first.getRuleId().equals(second.getRuleId())
                && first.getStartTimeMs() == second.getStartTimeMs()
                && first.getEndTimeMs() == second.getEndTimeMs();
    }

    private static List<ConfirmedAd> immutableOrEmpty(List<ConfirmedAd> ads) {
        return ads.isEmpty() ? Collections.<ConfirmedAd>emptyList()
                : Collections.unmodifiableList(ads);
    }

    private void rememberSuppressedEvidence(List<MatchEvent> evidence) {
        for (MatchEvent candidate : evidence) {
            boolean duplicate = false;
            for (MatchEvent current : suppressedEvidence) {
                if (PendingOccurrence.isEquivalentEvidence(current, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) suppressedEvidence.add(candidate);
        }
    }

    private void resetInternal() {
        pendingOccurrences.clear();
        resolvedOccurrences.clear();
        suppressedEvidence.clear();
        immediateConflicts.clear();
        analyzedThroughMs = -1L;
    }

    private boolean isValidEvidence(MatchEvent event) {
        return event != null
                && event.getType() == acceptedType
                && event.getRuleId() != null
                && !event.getRuleId().trim().isEmpty()
                && event.getStartTimeMs() >= 0L
                && event.getEndTimeMs() > event.getStartTimeMs();
    }

    private long confirmationDeadline(MatchEvent event) {
        if (acceptedType != MatchEvent.Type.FULL_MATCHED) {
            return AdConflictPolicy.normalizedDetectionTime(
                    event.getMatchedAtTimeMs(), event.getMatchedFrames());
        }
        long remainingFrames = Math.max(0L,
                (long) maxFingerprintFrames - event.getMatchedFrames());
        long settleMs = saturatingMultiply(remainingFrames + 1L, hopMs);
        return saturatingAdd(event.getMatchedAtTimeMs(), settleMs);
    }

    private static long saturatingMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static boolean isNear(long first, long second, long toleranceMs) {
        long distance = first >= second ? first - second : second - first;
        return distance >= 0L && distance <= toleranceMs;
    }

    private static final class PendingOccurrence {
        final long firstStartTimeMs;
        final long confirmationDeadlineMs;
        final List<MatchEvent> evidence = new ArrayList<>();
        final List<ConfirmedAd> conflictingConfirmedAds = new ArrayList<>();
        long minEndTimeMs;
        long maxEndTimeMs;
        boolean globallyAmbiguous;

        PendingOccurrence(MatchEvent first, long confirmationDeadlineMs) {
            firstStartTimeMs = first.getStartTimeMs();
            this.confirmationDeadlineMs = confirmationDeadlineMs;
            minEndTimeMs = first.getEndTimeMs();
            maxEndTimeMs = first.getEndTimeMs();
            evidence.add(first);
        }

        void addEvidence(MatchEvent event) {
            if (containsEquivalentEvidence(event)) return;
            evidence.add(event);
            minEndTimeMs = Math.min(minEndTimeMs, event.getEndTimeMs());
            maxEndTimeMs = Math.max(maxEndTimeMs, event.getEndTimeMs());
        }

        boolean hasAmbiguousEnd() {
            return AdConflictPolicy.hasDifferentDestination(minEndTimeMs, maxEndTimeMs);
        }

        void addConflictingConfirmedAd(ConfirmedAd candidate) {
            for (ConfirmedAd current : conflictingConfirmedAds) {
                if (current.getRuleId().equals(candidate.getRuleId())
                        && current.getStartTimeMs() == candidate.getStartTimeMs()
                        && current.getEndTimeMs() == candidate.getEndTimeMs()
                        && current.isRevocation() == candidate.isRevocation()) return;
            }
            conflictingConfirmedAds.add(candidate);
        }

        ConfirmedAd toConfirmedAd(long confirmedThroughTimeMs) {
            MatchEvent best = evidence.get(0);
            for (int i = 1; i < evidence.size(); i++) {
                MatchEvent candidate = evidence.get(i);
                if (isBetter(candidate, best)) best = candidate;
            }
            return toConfirmedAd(best, confirmedThroughTimeMs, evidence.size());
        }

        List<ConfirmedAd> toConfirmedAds(long confirmedThroughTimeMs) {
            List<ConfirmedAd> output = new ArrayList<>(evidence.size());
            for (MatchEvent event : evidence) {
                output.add(toConfirmedAd(event, confirmedThroughTimeMs, evidence.size()));
            }
            return output;
        }

        static ConfirmedAd toConfirmedAd(MatchEvent event,
                                         long confirmedThroughTimeMs,
                                         int evidenceCount) {
            return new ConfirmedAd(event.getRuleId(), event.getStartTimeMs(),
                    event.getEndTimeMs(), event.getMatchedAtTimeMs(),
                    confirmedThroughTimeMs, event.getMatchSimilarity(),
                    event.getMatchedFrames(), evidenceCount);
        }

        private boolean containsEquivalentEvidence(MatchEvent event) {
            for (MatchEvent current : evidence) {
                if (isEquivalentEvidence(current, event)) {
                    return true;
                }
            }
            return false;
        }

        static boolean isEquivalentEvidence(MatchEvent first, MatchEvent second) {
            return first.getRuleId().equals(second.getRuleId())
                    && first.getStartTimeMs() == second.getStartTimeMs()
                    && first.getEndTimeMs() == second.getEndTimeMs();
        }

        private static boolean isBetter(MatchEvent candidate, MatchEvent current) {
            if (candidate.getMatchedFrames() != current.getMatchedFrames()) {
                return candidate.getMatchedFrames() > current.getMatchedFrames();
            }
            int similarityOrder = Float.compare(
                    candidate.getMatchSimilarity(), current.getMatchSimilarity());
            if (similarityOrder != 0) return similarityOrder > 0;
            if (candidate.getMatchedAtTimeMs() != current.getMatchedAtTimeMs()) {
                return candidate.getMatchedAtTimeMs() < current.getMatchedAtTimeMs();
            }
            return candidate.getRuleId().compareTo(current.getRuleId()) < 0;
        }
    }

    private static final class ResolvedOccurrence {
        final long startTimeMs;
        final List<MatchEvent> evidence;
        final ConfirmedAd confirmedAd;

        ResolvedOccurrence(List<MatchEvent> evidence, ConfirmedAd confirmedAd) {
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
            this.startTimeMs = evidence.get(0).getStartTimeMs();
            this.confirmedAd = confirmedAd;
        }

        boolean hasCompatibleEnd(long endTimeMs) {
            for (MatchEvent current : evidence) {
                if (AdConflictPolicy.hasDifferentDestination(
                        current.getEndTimeMs(), endTimeMs)) return false;
            }
            return true;
        }

        boolean conflictsWith(MatchEvent candidate) {
            for (MatchEvent current : evidence) {
                if (AdConflictPolicy.conflicts(current, candidate)) return true;
            }
            return false;
        }
    }
}
