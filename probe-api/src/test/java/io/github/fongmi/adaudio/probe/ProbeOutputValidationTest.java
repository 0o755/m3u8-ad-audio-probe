/* 验证状态和错误快照只允许 SDK 产生一致、可解释的数据。 */
package io.github.fongmi.adaudio.probe;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProbeOutputValidationTest {
    @Test
    public void keepsSdkOutputFactoriesOutsidePublicApi() throws Exception {
        assertNoPublicConstructor(ProbeError.class);
        assertNoPublicConstructor(ProbeStatus.class);
        assertNoPublicConstructor(SkipRequest.class);

        Method idle = ProbeStatus.class.getDeclaredMethod("idle");
        assertFalse(Modifier.isPublic(idle.getModifiers()));
    }

    @Test
    public void exposesStructuredError() {
        IllegalStateException cause = new IllegalStateException("decoder");
        ProbeError error = new ProbeError(ProbeErrorCode.DECODER_FAILED, 7L,
                true, true, "音频解码失败", cause);

        assertEquals(ProbeErrorCode.DECODER_FAILED, error.getCode());
        assertEquals(7L, error.getSessionId());
        assertTrue(error.isFatal());
        assertTrue(error.isRetryable());
        assertEquals("音频解码失败", error.getMessage());
        assertSame(cause, error.getCause());
    }

    @Test
    public void rejectsMalformedErrors() {
        assertRejected(() -> new ProbeError(null, 0L, false,
                false, "错误", null));
        assertRejected(() -> new ProbeError(ProbeErrorCode.INTERNAL, -1L, false,
                false, "错误", null));
        assertRejected(() -> new ProbeError(ProbeErrorCode.INTERNAL, 0L, false,
                false, null, null));
        assertRejected(() -> new ProbeError(ProbeErrorCode.INTERNAL, 0L, false,
                false, "   ", null));
    }

    @Test
    public void exposesIdleAndActiveStatus() {
        ProbeStatus idle = ProbeStatus.idle();
        assertEquals(ProbeState.IDLE, idle.getState());
        assertEquals(0L, idle.getSessionId());
        assertEquals("", idle.getMediaId());
        assertEquals(0L, idle.getLookaheadMs());
        assertNull(idle.getLastError());

        ProbeError warning = new ProbeError(ProbeErrorCode.SOURCE_IO, 7L,
                false, true, "读取重试", null);
        ProbeStatus active = new ProbeStatus(ProbeState.ANALYZING, 7L, "episode",
                5_000L, 12_000L, 4L, 3, warning);

        assertEquals(ProbeState.ANALYZING, active.getState());
        assertEquals(7L, active.getSessionId());
        assertEquals("episode", active.getMediaId());
        assertEquals(5_000L, active.getHostPositionMs());
        assertEquals(12_000L, active.getAnalyzedThroughPositionMs());
        assertEquals(7_000L, active.getLookaheadMs());
        assertEquals(4L, active.getRuleRevision());
        assertEquals(3, active.getRuleCount());
        assertSame(warning, active.getLastError());
        assertFalse(warning.isFatal());
    }

    @Test
    public void clampsNegativeLookaheadOnlyAtReadTime() {
        ProbeStatus status = new ProbeStatus(ProbeState.ANALYZING, 1L, "episode",
                8_000L, 7_000L, 1L, 1, null);

        assertEquals(0L, status.getLookaheadMs());
        assertEquals(7_000L, status.getAnalyzedThroughPositionMs());
    }

    @Test
    public void rejectsMalformedStatusFields() {
        assertRejected(() -> status(null, 1L, "episode", 0L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, -1L, "", 0L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "", 0L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.IDLE, 0L, "episode", 0L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode\n2", 0L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode", -1L, 0L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode", 0L, -1L, 1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode", 0L, 0L, -1L, 1, null));
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode", 0L, 0L, 1L, -1, null));

        ProbeError otherSession = new ProbeError(ProbeErrorCode.INTERNAL, 2L,
                false, false, "错误", null);
        assertRejected(() -> status(ProbeState.ANALYZING, 1L, "episode",
                0L, 0L, 1L, 1, otherSession));
    }

    private static ProbeStatus status(ProbeState state, long sessionId, String mediaId,
                                      long hostMs, long analyzedMs, long revision,
                                      int ruleCount, ProbeError error) {
        return new ProbeStatus(state, sessionId, mediaId, hostMs, analyzedMs,
                revision, ruleCount, error);
    }

    private static void assertNoPublicConstructor(Class<?> type) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("预期拒绝非法参数");
        } catch (IllegalArgumentException expected) {
            // 错误文案不作为兼容合同。
        }
    }
}
