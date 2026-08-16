/* 验证会话切换不会穿过正在执行的宿主回调。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CallbackGateTest {
    @Test
    public void lifecycleUpdateWaitsForActiveCallback() throws Exception {
        CallbackGate gate = new CallbackGate();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch lifecycleAttempted = new CountDownLatch(1);
        AtomicBoolean lifecycleFinished = new AtomicBoolean();

        Thread callback = new Thread(() -> gate.invokeIf(() -> true, () -> {
            callbackEntered.countDown();
            await(releaseCallback);
        }));
        callback.start();
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

        Thread lifecycle = new Thread(() -> {
            lifecycleAttempted.countDown();
            gate.update(() -> lifecycleFinished.set(true));
        });
        lifecycle.start();
        assertTrue(lifecycleAttempted.await(2, TimeUnit.SECONDS));
        assertFalse(lifecycleFinished.get());

        releaseCallback.countDown();
        callback.join(2000L);
        lifecycle.join(2000L);
        assertTrue(lifecycleFinished.get());
    }

    @Test
    public void staleCallbackDoesNotRun() {
        CallbackGate gate = new CallbackGate();
        AtomicBoolean called = new AtomicBoolean();

        assertFalse(gate.invokeIf(() -> false, () -> called.set(true)));
        assertFalse(called.get());
    }

    @Test
    public void callbackMayReenterLifecycleOnSameThread() {
        CallbackGate gate = new CallbackGate();
        AtomicLong generation = new AtomicLong(1L);

        assertTrue(gate.invokeIf(() -> generation.get() == 1L,
                () -> gate.update(generation::incrementAndGet)));
        assertEquals(2L, generation.get());
    }

    @Test
    public void invalidatedClaimCannotCrossQueuedCallbackGate() {
        CallbackGate gate = new CallbackGate();
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("first", 10_000L, 20_000L);
        queue.addAll(Collections.singletonList(first));
        AdDispatchQueue.Claim claim = queue.claim(10_000L, 30_000L).get(0);
        AtomicBoolean called = new AtomicBoolean();
        Runnable queuedCallback = () -> gate.invokeIf(
                () -> queue.isClaimValid(claim), () -> called.set(true));

        queue.addAll(Collections.singletonList(ad("late", 7_000L, 19_000L)));
        queuedCallback.run();

        assertFalse(called.get());
    }

    @Test
    public void conflictAfterGateCheckStillBlocksFinalCommit() {
        CallbackGate gate = new CallbackGate();
        AdDispatchQueue queue = new AdDispatchQueue();
        ConfirmedAd first = ad("first", 10_000L, 20_000L);
        queue.addAll(Collections.singletonList(first));
        AdDispatchQueue.Claim claim = queue.claim(10_000L, 30_000L).get(0);
        AtomicBoolean gateChecked = new AtomicBoolean();
        AtomicBoolean called = new AtomicBoolean();

        assertTrue(gate.invokeIf(() -> {
            boolean valid = queue.isClaimValid(claim);
            gateChecked.set(valid);
            return valid;
        }, () -> {
            queue.addAll(Collections.singletonList(ad("late", 7_000L, 19_000L)));
            if (queue.ack(claim)) called.set(true);
        }));

        assertTrue(gateChecked.get());
        assertFalse(called.get());
    }

    @Test
    public void fatalTransitionWaitsBehindActiveSkipCommit() throws Exception {
        CallbackGate gate = new CallbackGate();
        CountDownLatch skipEntered = new CountDownLatch(1);
        CountDownLatch releaseSkip = new CountDownLatch(1);
        CountDownLatch fatalAttempted = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        AtomicLong order = new AtomicLong();
        AtomicLong skipOrder = new AtomicLong();
        AtomicLong fatalOrder = new AtomicLong();

        Thread skip = new Thread(() -> gate.invokeIf(() -> !failed.get(), () -> {
            skipEntered.countDown();
            await(releaseSkip);
            skipOrder.set(order.incrementAndGet());
        }));
        skip.start();
        assertTrue(skipEntered.await(2, TimeUnit.SECONDS));

        Thread fatal = new Thread(() -> {
            fatalAttempted.countDown();
            gate.update(() -> {
                failed.set(true);
                fatalOrder.set(order.incrementAndGet());
            });
        });
        fatal.start();
        assertTrue(fatalAttempted.await(2, TimeUnit.SECONDS));
        assertFalse(failed.get());

        releaseSkip.countDown();
        skip.join(2000L);
        fatal.join(2000L);
        assertEquals(1L, skipOrder.get());
        assertEquals(2L, fatalOrder.get());
        assertTrue(failed.get());
    }

    @Test
    public void fatalTransitionVisibleFirstRejectsSkipCallback() {
        CallbackGate gate = new CallbackGate();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean called = new AtomicBoolean();

        gate.update(() -> failed.set(true));

        assertFalse(gate.invokeIf(() -> !failed.get(), () -> called.set(true)));
        assertFalse(called.get());
    }

    private ConfirmedAd ad(String id, long startTimeMs, long endTimeMs) {
        return new ConfirmedAd(id, startTimeMs, endTimeMs, startTimeMs + 1_000L,
                startTimeMs + 2_000L, 1.0f, 8, 1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new AssertionError("等待回调超时");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
