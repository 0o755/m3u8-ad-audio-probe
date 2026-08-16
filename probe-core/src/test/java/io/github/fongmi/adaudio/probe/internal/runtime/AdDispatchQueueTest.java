/* 验证前视结果的占用、重试、成功确认及媒体重置语义。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdDispatchQueueTest {
    @Test
    public void releaseAllowsRetryAfterTemporaryHostMismatch() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd ad = ad("a", 10_000L, 20_000L);
        queue.addAll(Collections.singletonList(ad));

        assertTrue(queue.claim(9_999L, 30_000L).isEmpty());
        AdDispatchQueue.Claim firstClaim = claimOne(queue, 10_000L, 30_000L);
        assertTrue(queue.claim(10_001L, 30_000L).isEmpty());

        assertTrue(queue.release(firstClaim));
        AdDispatchQueue.Claim retry = claimOne(queue, 10_002L, 30_000L);
        assertFalse(queue.isClaimValid(firstClaim));
        assertTrue(queue.isClaimValid(retry));
    }

    @Test
    public void acknowledgedOccurrenceDispatchesAtMostOnce() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd ad = ad("a", 10_000L, 20_000L);
        queue.addAll(Collections.singletonList(ad));

        AdDispatchQueue.Claim claim = claimOne(queue, 10_000L, 30_000L);
        assertTrue(queue.ack(claim));
        assertTrue(queue.claim(10_001L, 30_000L).isEmpty());

        queue.addAll(Collections.singletonList(ad));
        assertTrue(queue.claim(10_002L, 30_000L).isEmpty());
        assertFalse(queue.ack(claim));
        assertFalse(queue.release(claim));
    }

    @Test
    public void dropsAdsAlreadyPassedOrOutsideDuration() {
        AdDispatchQueue queue = new AdDispatchQueue();
        queue.addAll(Arrays.asList(ad("passed", 1_000L, 2_000L),
                ad("outside", 8_000L, 12_000L)));

        assertTrue(queue.claim(5_000L, 10_000L).isEmpty());
        assertTrue(queue.claim(8_000L, 10_000L).isEmpty());
    }

    @Test
    public void suppressesOverlappingAdsWithDifferentDestinationsAcrossBatches() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("near-a", 10_000L, 20_000L);
        ConfirmedAd second = ad("near-b", 7_000L, 19_000L);

        queue.addAll(Collections.singletonList(first));
        queue.addAll(Collections.singletonList(second));

        assertTrue(queue.claim(10_000L, 30_000L).isEmpty());
        queue.addAll(Arrays.asList(first, second));
        assertTrue(queue.claim(10_000L, 30_000L).isEmpty());
    }

    @Test
    public void lateConflictInvalidatesAlreadyReturnedClaim() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("claimed", 10_000L, 20_000L);
        ConfirmedAd conflict = ad("late-conflict", 7_000L, 19_000L);
        queue.addAll(Collections.singletonList(first));
        AdDispatchQueue.Claim claim = claimOne(queue, 10_000L, 30_000L);

        queue.addAll(Collections.singletonList(conflict));

        assertFalse(queue.isClaimValid(claim));
        assertFalse(queue.ack(claim));
        assertFalse(queue.release(claim));
    }

    @Test
    public void conflictAfterCommitCannotProduceSecondClaim() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("committed", 10_000L, 20_000L);
        ConfirmedAd conflict = ad("late-conflict", 7_000L, 19_000L);
        queue.addAll(Collections.singletonList(first));
        AdDispatchQueue.Claim claim = claimOne(queue, 10_000L, 30_000L);
        assertTrue(queue.ack(claim));

        queue.addAll(Collections.singletonList(conflict));

        assertTrue(queue.claim(10_000L, 30_000L).isEmpty());
    }

    @Test
    public void acknowledgedConflictClosureIsInputOrderIndependent() {
        assertAcknowledgedChainSuppressed(false);
        assertAcknowledgedChainSuppressed(true);
    }

    @Test
    public void acknowledgedConflictInvalidatesEarlierBatchChainClaim() {
        AdDispatchQueue queue = queueWithAcknowledgedChainRoot();
        ConfirmedAd middle = ad("middle", 5_000L, 15_000L);
        ConfirmedAd last = ad("last", 12_000L, 20_000L);
        queue.addAll(Collections.singletonList(last));
        AdDispatchQueue.Claim lastClaim = claimOne(queue, 12_000L, 30_000L);

        queue.addAll(Collections.singletonList(middle));

        assertFalse(queue.isClaimValid(lastClaim));
        assertTrue(queue.claim(12_000L, 30_000L).isEmpty());
    }

    @Test
    public void suppressedOverlapChainCannotReenterLater() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("chain-a", 0L, 10_000L);
        ConfirmedAd middle = ad("chain-b", 5_000L, 15_000L);
        ConfirmedAd last = ad("chain-c", 12_000L, 20_000L);

        queue.addAll(Arrays.asList(first, middle));
        queue.addAll(Collections.singletonList(last));

        assertTrue(queue.claim(12_000L, 30_000L).isEmpty());
    }

    @Test
    public void adjacentIndependentAdsRemainDispatchable() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("first", 0L, 10_000L);
        ConfirmedAd second = ad("second", 10_000L, 18_000L);
        queue.addAll(Arrays.asList(first, second));

        AdDispatchQueue.Claim firstClaim = claimOne(queue, 0L, 20_000L);
        assertTrue(queue.ack(firstClaim));
        assertEquals(1, queue.claim(10_000L, 20_000L).size());
    }

    @Test
    public void resetInvalidatesClaimsAndAcknowledgedHistory() {
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd claimed = ad("claimed", 10_000L, 20_000L);
        ConfirmedAd acknowledged = ad("acknowledged", 30_000L, 40_000L);
        queue.addAll(Arrays.asList(claimed, acknowledged));

        AdDispatchQueue.Claim claimedToken = claimOne(queue, 10_000L, -1L);
        AdDispatchQueue.Claim acknowledgedToken = claimOne(queue, 30_000L, -1L);
        assertTrue(queue.ack(acknowledgedToken));
        queue.reset();
        assertTrue(queue.claim(10_000L, -1L).isEmpty());
        assertFalse(queue.isClaimValid(claimedToken));

        queue.addAll(Arrays.asList(claimed, acknowledged));
        assertEquals(1, queue.claim(10_000L, -1L).size());
        assertEquals(1, queue.claim(30_000L, -1L).size());
    }

    private AdDispatchQueue.Claim claimOne(AdDispatchQueue queue,
                                           long positionMs, long durationMs) {
        java.util.List<AdDispatchQueue.Claim> claims = queue.claim(positionMs, durationMs);
        assertEquals(1, claims.size());
        return claims.get(0);
    }

    private void assertAcknowledgedChainSuppressed(boolean middleFirst) {
        AdDispatchQueue queue = queueWithAcknowledgedChainRoot();
        ConfirmedAd middle = ad("middle", 5_000L, 15_000L);
        ConfirmedAd last = ad("last", 12_000L, 20_000L);
        queue.addAll(middleFirst ? Arrays.asList(middle, last)
                : Arrays.asList(last, middle));

        assertTrue(queue.claim(12_000L, 30_000L).isEmpty());
    }

    private AdDispatchQueue queueWithAcknowledgedChainRoot() {
        AdDispatchQueue queue = new AdDispatchQueue();
        queue.addAll(Collections.singletonList(ad("root", 0L, 10_000L)));
        assertTrue(queue.ack(claimOne(queue, 0L, 30_000L)));
        return queue;
    }

    private ConfirmedAd ad(String id, long start, long end) {
        return new ConfirmedAd(id, start, end, start + 1000L,
                start + 2000L, 1.0f, 8, 1);
    }
}
