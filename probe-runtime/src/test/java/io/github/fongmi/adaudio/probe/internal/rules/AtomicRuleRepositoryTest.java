/* 验证本地规则替换与远程刷新辅助逻辑的边界行为。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

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

    @Test
    public void localOnlyRepositoryReplacesAsynchronouslyAndKeepsLastValidRules()
            throws Exception {
        CountDownLatch rulesDelivered = new CountDownLatch(1);
        CountDownLatch failureDelivered = new CountDownLatch(1);
        AtomicReference<Long> appliedRequestId = new AtomicReference<>();
        AtomicReference<ProbeErrorCode> failureCode = new AtomicReference<>();
        AtomicReference<Long> failedRequestId = new AtomicReference<>();
        AtomicBoolean failureHadRules = new AtomicBoolean();
        Context context = new ContextWrapper(null) {
            @Override
            public Context getApplicationContext() {
                return this;
            }

            @Override
            public File getCacheDir() {
                return new File(System.getProperty("java.io.tmpdir"));
            }
        };
        AtomicRuleRepository repository = new AtomicRuleRepository(
                context, null, new AtomicRuleRepository.Listener() {
                    @Override
                    public void onRules(AdRuleSet rules, boolean fromCache,
                                        long replacementRequestId) {
                        appliedRequestId.set(replacementRequestId);
                        rulesDelivered.countDown();
                    }

                    @Override
                    public void onFailure(ProbeErrorCode code, boolean cacheAvailable,
                                          Exception error, long replacementRequestId) {
                        failureCode.set(code);
                        failedRequestId.set(replacementRequestId);
                        failureHadRules.set(cacheAvailable);
                        failureDelivered.countDown();
                    }

                    @Override
                    public void onReplacementSuperseded(long replacementRequestId) {
                    }
                });
        try {
            String valid = "{\"format\":\"ad-audio-probe-rules\","
                    + "\"schemaVersion\":1,\"revision\":3,"
                    + "\"algorithm\":\"spectral-sequence-v1\",\"rules\":[]}";
            long requestId = repository.replace(valid.getBytes(StandardCharsets.UTF_8));
            assertTrue(rulesDelivered.await(5L, TimeUnit.SECONDS));
            assertEquals(Long.valueOf(requestId), appliedRequestId.get());
            assertEquals(3L, repository.getCurrentRules().getRevision());

            long invalidRequestId = repository.replace("{".getBytes(StandardCharsets.UTF_8));
            assertTrue(failureDelivered.await(5L, TimeUnit.SECONDS));
            assertEquals(ProbeErrorCode.RULE_PARSE_FAILED, failureCode.get());
            assertEquals(Long.valueOf(invalidRequestId), failedRequestId.get());
            assertTrue(failureHadRules.get());
            assertEquals(3L, repository.getCurrentRules().getRevision());
            assertThrows(IllegalStateException.class, repository::refresh);
        } finally {
            repository.close();
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder output = new StringBuilder(count);
        for (int i = 0; i < count; i++) output.append(value);
        return output.toString();
    }
}
