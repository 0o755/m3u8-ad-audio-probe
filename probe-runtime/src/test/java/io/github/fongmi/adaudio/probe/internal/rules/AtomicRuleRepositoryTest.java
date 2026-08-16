/* 验证规则刷新在线程池关闭竞态下不会向宿主抛出拒绝异常。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AtomicRuleRepositoryTest {
    @Test
    public void rejectedExecutorIsReportedWithoutThrowing() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdownNow();

        assertFalse(AtomicRuleRepository.tryExecute(executor, () -> { }));
    }

    @Test
    public void availableExecutorRunsTask() {
        AtomicBoolean called = new AtomicBoolean();
        assertTrue(AtomicRuleRepository.tryExecute(Runnable::run, () -> called.set(true)));
        assertTrue(called.get());
    }

    @Test
    public void elapsedTimeoutSurvivesNanoTimeWrapAndSupportsNoTimeout() {
        long started = Long.MAX_VALUE - 10L;
        long afterWrap = Long.MIN_VALUE + 40L;

        assertTrue(AtomicRuleRepository.hasTimedOut(started, afterWrap, 50L));
        assertFalse(AtomicRuleRepository.hasTimedOut(started, afterWrap, -1L));
        assertFalse(AtomicRuleRepository.hasTimedOut(1_000L, 1_049L, 50L));
    }

    @Test
    public void ruleUrlRequiresBoundedHttpsAddress() {
        assertEquals("https://example.com/rules.json",
                AtomicRuleRepository.validateRuleUrl(" https://example.com/rules.json "));
        assertThrows(IllegalArgumentException.class,
                () -> AtomicRuleRepository.validateRuleUrl("http://example.com/rules.json"));
        assertThrows(IllegalArgumentException.class,
                () -> AtomicRuleRepository.validateRuleUrl("https://example.com/rules.json\n"));
        assertThrows(IllegalArgumentException.class,
                () -> AtomicRuleRepository.validateRuleUrl(
                        "https://example.com/" + repeat('a', 8192)));
    }

    @Test
    public void sameCachePathSharesProcessLock() {
        assertSame(AtomicRuleRepository.cacheLockFor("rules-a"),
                AtomicRuleRepository.cacheLockFor("rules-a"));
        assertNotSame(AtomicRuleRepository.cacheLockFor("rules-a"),
                AtomicRuleRepository.cacheLockFor("rules-b"));
    }

    private static String repeat(char value, int count) {
        StringBuilder output = new StringBuilder(count);
        for (int i = 0; i < count; i++) output.append(value);
        return output.toString();
    }
}
