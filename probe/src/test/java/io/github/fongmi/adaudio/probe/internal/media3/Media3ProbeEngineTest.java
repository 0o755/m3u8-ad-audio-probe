/* 验证分析水位在时间轴重置和首块 PCM 前的纯状态判定。 */
package io.github.fongmi.adaudio.probe.internal.media3;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Media3ProbeEngineTest {
    @Test
    public void timelineResetReanchorsBackwardWatermark() {
        assertEquals(1_200L,
                Media3ProbeEngine.advanceAnalyzedThrough(90_000L, 1_200L, true));
        assertEquals(90_000L,
                Media3ProbeEngine.advanceAnalyzedThrough(90_000L, 1_200L, false));
    }

    @Test
    public void firstPcmRecoveryOnlySeeksMeaningfullyBackward() {
        assertTrue(Media3ProbeEngine.shouldRecoverBeforeFirstPcm(90_000L, 1_000L));
        assertFalse(Media3ProbeEngine.shouldRecoverBeforeFirstPcm(1_500L, 1_000L));
        assertFalse(Media3ProbeEngine.shouldRecoverBeforeFirstPcm(1_000L, 2_000L));
    }

    @Test
    public void stopOnlyAcceptsExactPositiveSession() {
        assertTrue(Media3ProbeEngine.isMatchingStopSession(7L, 7L));
        assertFalse(Media3ProbeEngine.isMatchingStopSession(7L, 0L));
        assertFalse(Media3ProbeEngine.isMatchingStopSession(7L, 8L));
        assertFalse(Media3ProbeEngine.isMatchingStopSession(0L, 0L));
    }
}
