/* 验证广告 occurrence 的等待、消歧、去重及时间轴重置。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import io.github.fongmi.adaudio.probe.internal.core.MatchEvent;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DetectionCoordinatorTest {
    @Test
    public void confirmsNormalOccurrenceOnlyOnce() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent event = start("ad-a", 10_000L, 20_000L, 11_000L, 4, 0.92f);

        assertTrue(coordinator.onMatch(event).isEmpty());
        List<ConfirmedAd> confirmed = coordinator.onAnalyzedThrough(12_280L);

        assertEquals(1, confirmed.size());
        assertEquals("ad-a", confirmed.get(0).getRuleId());
        assertEquals(10_000L, confirmed.get(0).getStartTimeMs());
        assertEquals(20_000L, confirmed.get(0).getEndTimeMs());
        assertEquals(1, confirmed.get(0).getEvidenceCount());
        assertTrue(coordinator.onMatch(event).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(30_000L).isEmpty());
    }

    @Test
    public void waitsUntilAnalyzedWatermarkReachesConfirmationDeadline() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent event = start("ad-a", 10_000L, 20_000L, 11_000L, 4, 0.9f);

        assertTrue(coordinator.onMatch(event).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(12_279L).isEmpty());
        assertEquals(1, coordinator.onAnalyzedThrough(12_280L).size());
    }

    @Test
    public void fullMatchModeIgnoresEarlyStartAndWaitsForWholeAnchor() {
        DetectionCoordinator coordinator = DetectionCoordinator.fullMatchOnly(18, 256);
        MatchEvent early = start("ad-a", 10_000L, 20_000L,
                11_000L, 4, 0.99f);
        MatchEvent full = new MatchEvent(MatchEvent.Type.FULL_MATCHED, "ad-a",
                10_000L, 20_000L, 14_584L, 0.96f, 18);

        assertTrue(coordinator.onMatch(early).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(30_000L).isEmpty());
        coordinator.reset();
        assertTrue(coordinator.onMatch(full).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(14_839L).isEmpty());
        assertEquals(1, coordinator.onAnalyzedThrough(14_840L).size());
    }

    @Test
    public void fullMatchModeWaitsForLongerRuleEvidence() {
        DetectionCoordinator coordinator = DetectionCoordinator.fullMatchOnly(18, 256);
        MatchEvent shortFull = new MatchEvent(MatchEvent.Type.FULL_MATCHED, "short-ad",
                10_000L, 20_000L, 12_000L, 0.98f, 6);

        assertTrue(coordinator.onMatch(shortFull).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(15_327L).isEmpty());
        assertEquals(1, coordinator.onAnalyzedThrough(15_328L).size());
    }

    @Test
    public void rejectsWholeOccurrenceWhenChainedEndsExceedTolerance() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        List<MatchEvent> evidence = Arrays.asList(
                start("ad-a", 10_000L, 20_000L, 11_000L, 4, 0.90f),
                start("ad-b", 10_050L, 20_200L, 11_100L, 5, 0.93f),
                start("ad-c", 10_100L, 20_400L, 11_200L, 6, 0.95f));

        assertTrue(coordinator.onMatches(evidence).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(12_280L).isEmpty());
        assertTrue(coordinator.onMatch(evidence.get(0)).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(30_000L).isEmpty());
    }

    @Test
    public void rejectsDifferentAnchorStartsFromSameDetection() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent first = start("near-a", 10_000L, 20_000L, 11_000L, 4, 0.95f);
        MatchEvent second = start("near-b", 7_000L, 19_000L, 11_000L, 4, 0.95f);

        assertTrue(coordinator.onMatches(Arrays.asList(first, second)).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(12_280L).isEmpty());
    }

    @Test
    public void rejectsOverlappingIntervalsWithDifferentDestinations() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent first = start("overlap-a", 10_000L, 20_000L, 11_000L, 8, 0.96f);
        MatchEvent second = start("overlap-b", 15_000L, 25_000L, 16_000L, 8, 0.96f);

        assertTrue(coordinator.onMatches(Arrays.asList(first, second)).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(17_000L).isEmpty());
    }

    @Test
    public void resolvedConflictEvidenceSuppressesLaterChainInEitherOrder() {
        assertResolvedChainSuppressed(false);
        assertResolvedChainSuppressed(true);
    }

    @Test
    public void resolvedConflictHistoryKeepsAdjacentOccurrenceIndependent() {
        DetectionCoordinator coordinator = coordinatorWithResolvedConflict(false);
        MatchEvent adjacent = start("adjacent", 15_000L, 22_000L,
                16_000L, 8, 0.96f);

        assertTrue(coordinator.onMatch(adjacent).isEmpty());
        List<ConfirmedAd> confirmed = coordinator.onAnalyzedThrough(17_000L);

        assertEquals(1, confirmed.size());
        assertEquals("adjacent", confirmed.get(0).getRuleId());
    }

    @Test
    public void lateDifferentEndRetractsPreviouslyConfirmedOccurrence() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        AdDispatchQueue queue = new AdDispatchQueue();
        MatchEvent first = start("first", 10_000L, 20_000L,
                11_000L, 4, 0.96f);
        coordinator.onMatch(first);
        List<ConfirmedAd> initial = coordinator.onAnalyzedThrough(12_280L);
        queue.addAll(initial);
        AdDispatchQueue.Claim claim = queue.claim(10_000L, 30_000L).get(0);
        MatchEvent conflict = start("late", 10_100L, 25_000L,
                11_100L, 4, 0.95f);

        List<ConfirmedAd> collision = coordinator.onMatch(conflict);
        queue.addAll(collision);

        assertEquals(2, collision.size());
        assertTrue(queue.claim(10_100L, 30_000L).isEmpty());
        assertFalse(queue.isClaimValid(claim));
    }

    @Test
    public void historicalRetractionKeepsDirectConflictWhenBestEvidenceIsCompatible() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        AdDispatchQueue queue = new AdDispatchQueue();
        MatchEvent original = start("original", 10_000L, 20_000L,
                11_000L, 4, 0.96f);
        coordinator.onMatch(original);
        queue.addAll(coordinator.onAnalyzedThrough(12_280L));
        AdDispatchQueue.Claim claim = queue.claim(10_000L, 30_000L).get(0);
        MatchEvent directConflict = start("direct-conflict", 10_100L, 25_000L,
                11_100L, 4, 0.90f);
        MatchEvent betterCompatible = start("better-compatible", 10_050L, 20_100L,
                11_050L, 8, 0.99f);

        List<ConfirmedAd> immediate = coordinator.onMatches(
                Arrays.asList(directConflict, betterCompatible));
        queue.addAll(immediate);
        assertEquals(2, immediate.size());
        assertFalse(queue.isClaimValid(claim));
        List<ConfirmedAd> collision = coordinator.onAnalyzedThrough(13_000L);
        queue.addAll(collision);

        assertEquals(3, collision.size());
        assertTrue(containsRule(collision, "direct-conflict"));
        assertTrue(queue.claim(10_100L, 30_000L).isEmpty());
    }

    @Test
    public void historicalBoundaryConflictRevokesCompatibleSelectedEndpoint() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        AdDispatchQueue queue = new AdDispatchQueue();
        MatchEvent boundary = start("boundary", 10_000L, 20_000L,
                11_000L, 4, 0.90f);
        MatchEvent selected = start("selected", 10_050L, 20_200L,
                11_050L, 8, 0.99f);
        coordinator.onMatches(Arrays.asList(boundary, selected));
        List<ConfirmedAd> initial = coordinator.onAnalyzedThrough(13_000L);
        queue.addAll(initial);
        AdDispatchQueue.Claim claim = queue.claim(10_100L, 30_000L).get(0);
        assertEquals("selected", claim.getAd().getRuleId());
        MatchEvent late = start("late", 10_100L, 20_400L,
                13_100L, 8, 0.95f);

        List<ConfirmedAd> collision = coordinator.onMatch(late);
        queue.addAll(collision);

        assertEquals(3, collision.size());
        assertTrue(containsRule(collision, "boundary"));
        assertFalse(queue.isClaimValid(claim));
        assertTrue(queue.claim(10_100L, 30_000L).isEmpty());
    }

    @Test
    public void confirmsAdjacentOccurrencesIndependently() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent first = start("ad-a", 0L, 10_000L, 1_000L, 8, 0.96f);
        MatchEvent second = start("ad-b", 10_000L, 18_000L, 11_000L, 8, 0.94f);

        assertTrue(coordinator.onMatches(Arrays.asList(first, second)).isEmpty());
        List<ConfirmedAd> confirmed = coordinator.onAnalyzedThrough(11_256L);

        assertEquals(2, confirmed.size());
        assertEquals(0L, confirmed.get(0).getStartTimeMs());
        assertEquals(10_000L, confirmed.get(1).getStartTimeMs());
    }

    @Test
    public void timelineResetDropsPendingAndResolvedHistory() {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent event = start("ad-a", 10_000L, 20_000L, 11_000L, 4, 0.9f);

        assertTrue(coordinator.onMatch(event).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(11_500L).isEmpty());
        coordinator.reset();
        assertTrue(coordinator.onAnalyzedThrough(30_000L).isEmpty());

        coordinator.reset();
        assertTrue(coordinator.onMatch(event).isEmpty());
        assertEquals(1, coordinator.onAnalyzedThrough(12_280L).size());
    }

    @Test
    public void timelineResetClearsSuppressedConflictHistory() {
        DetectionCoordinator coordinator = coordinatorWithResolvedConflict(false);
        coordinator.reset();
        MatchEvent formerlyChained = start("chain-c", 12_000L, 20_000L,
                13_000L, 8, 0.95f);

        assertTrue(coordinator.onMatch(formerlyChained).isEmpty());
        List<ConfirmedAd> confirmed = coordinator.onAnalyzedThrough(14_000L);

        assertEquals(1, confirmed.size());
        assertEquals("chain-c", confirmed.get(0).getRuleId());
    }

    private MatchEvent start(String ruleId, long startTimeMs, long endTimeMs,
                             long matchedAtTimeMs, int matchedFrames, float similarity) {
        return new MatchEvent(MatchEvent.Type.START_MATCHED, ruleId,
                startTimeMs, endTimeMs, matchedAtTimeMs, similarity, matchedFrames);
    }

    private void assertResolvedChainSuppressed(boolean reverse) {
        DetectionCoordinator coordinator = coordinatorWithResolvedConflict(reverse);
        MatchEvent chained = start("chain-c", 12_000L, 20_000L,
                13_000L, 8, 0.95f);

        assertTrue(coordinator.onMatch(chained).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(14_000L).isEmpty());
    }

    private DetectionCoordinator coordinatorWithResolvedConflict(boolean reverse) {
        DetectionCoordinator coordinator = new DetectionCoordinator();
        MatchEvent first = start("chain-a", 0L, 10_000L, 1_000L, 8, 0.96f);
        MatchEvent middle = start("chain-b", 5_000L, 15_000L, 6_000L, 8, 0.96f);
        List<MatchEvent> events = reverse
                ? Arrays.asList(middle, first) : Arrays.asList(first, middle);
        assertTrue(coordinator.onMatches(events).isEmpty());
        assertTrue(coordinator.onAnalyzedThrough(7_000L).isEmpty());
        return coordinator;
    }

    private boolean containsRule(List<ConfirmedAd> ads, String ruleId) {
        for (ConfirmedAd ad : ads) {
            if (ruleId.equals(ad.getRuleId())) return true;
        }
        return false;
    }
}
