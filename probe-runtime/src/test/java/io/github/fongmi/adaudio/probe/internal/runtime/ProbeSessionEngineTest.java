/* 验证适配器无关运行时的水位与精确会话边界。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;

public class ProbeSessionEngineTest {
    @Test
    public void timelineResetReanchorsBackwardWatermark() {
        assertEquals(1200L, ProbeSessionEngine.advanceAnalyzedThrough(90_000L, 1200L, true));
        assertEquals(90_000L,
                ProbeSessionEngine.advanceAnalyzedThrough(90_000L, 1200L, false));
    }

    @Test
    public void stopOnlyAcceptsExactPositiveSession() {
        assertTrue(ProbeSessionEngine.isMatchingStopSession(7L, 7L));
        assertFalse(ProbeSessionEngine.isMatchingStopSession(7L, 0L));
        assertFalse(ProbeSessionEngine.isMatchingStopSession(7L, 8L));
        assertFalse(ProbeSessionEngine.isMatchingStopSession(0L, 0L));
    }

    @Test
    public void adapterCannotReportRuleRepositoryErrors() {
        assertEquals(ProbeErrorCode.INTERNAL,
                ProbeSessionEngine.normalizeAdapterErrorCode(
                        ProbeErrorCode.RULE_FETCH_FAILED));
        assertEquals(ProbeErrorCode.SOURCE_IO,
                ProbeSessionEngine.normalizeAdapterErrorCode(ProbeErrorCode.SOURCE_IO));
    }
}
