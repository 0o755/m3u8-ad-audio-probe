/* 验证宿主回调即使使用线程池入口也保持严格串行顺序。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerialExecutorTest {
    @Test
    public void schedulesOnlyOneDelegateTaskAtATime() {
        ManualExecutor delegate = new ManualExecutor();
        SerialExecutor serial = new SerialExecutor(delegate);
        List<Integer> order = new ArrayList<>();

        serial.execute(() -> order.add(1));
        serial.execute(() -> order.add(2));
        serial.execute(() -> order.add(3));

        assertEquals(1, delegate.size());
        delegate.runNext();
        assertEquals(1, delegate.size());
        delegate.runNext();
        delegate.runNext();
        assertEquals(Arrays.asList(1, 2, 3), order);
    }

    @Test
    public void concurrentFirstSubmissionsNeverRunInParallel() throws Exception {
        ExecutorService delegate = Executors.newFixedThreadPool(4);
        ExecutorService producers = Executors.newFixedThreadPool(8);
        try {
            SerialExecutor serial = new SerialExecutor(delegate);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(64);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();

            for (int i = 0; i < 64; i++) {
                producers.execute(() -> {
                    await(start);
                    serial.execute(() -> {
                        int running = active.incrementAndGet();
                        maximum.accumulateAndGet(running, Math::max);
                        Thread.yield();
                        active.decrementAndGet();
                        finished.countDown();
                    });
                });
            }
            start.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximum.get());
        } finally {
            producers.shutdownNow();
            delegate.shutdownNow();
        }
    }

    @Test
    public void delayedDelegateRejectionCleansEveryDroppedTask() {
        RejectingExecutor delegate = new RejectingExecutor();
        SerialExecutor serial = new SerialExecutor(delegate);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        serial.execute(ran::incrementAndGet);
        assertTrue(serial.tryExecute(ran::incrementAndGet,
                error -> rejected.incrementAndGet()));
        assertTrue(serial.tryExecute(ran::incrementAndGet,
                error -> rejected.incrementAndGet()));
        delegate.reject = true;

        delegate.runNext();
        // 后继拒绝不得反向抛给已正常完成的第一个宿主任务。
        assertEquals(1, ran.get());
        assertEquals(2, rejected.get());

        delegate.reject = false;
        serial.execute(ran::incrementAndGet);
        delegate.runNext();
        assertEquals(2, ran.get());
    }

    @Test
    public void immediateDelegateRejectionRunsCleanupBeforeReturning() {
        RejectingExecutor delegate = new RejectingExecutor();
        delegate.reject = true;
        SerialExecutor serial = new SerialExecutor(delegate);
        AtomicInteger rejected = new AtomicInteger();

        assertFalse(serial.tryExecute(() -> fail("被拒绝任务不得运行"),
                error -> rejected.incrementAndGet()));
        assertEquals(1, rejected.get());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }

    private static final class RejectingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("测试拒绝");
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
