/* 验证 HLS 准备期动态快照不会覆盖最终有限 VOD 判定。 */
package io.github.fongmi.adaudio.probe.adapter.internal;

import org.junit.Test;

import io.github.fongmi.adaudio.probe.adapter.internal.FiniteVodTimelineGate.Decision;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FiniteVodTimelineGateTest {
    @Test
    public void transientDynamicThenVodIsAcceptedAtReady() {
        FiniteVodTimelineGate gate = new FiniteVodTimelineGate();
        assertEquals(Decision.PENDING, gate.update(-1L, false, true));
        assertEquals(Decision.PENDING, gate.update(1_385_173L, false, false));
        assertEquals(Decision.VOD_CONFIRMED, gate.markReady());
        assertTrue(gate.isVodConfirmed());
        assertEquals(1_385_173L, gate.getDurationMs());
    }

    @Test
    public void authoritativeLiveOrDynamicIsRejected() {
        FiniteVodTimelineGate live = new FiniteVodTimelineGate();
        live.update(-1L, true, true);
        assertEquals(Decision.UNSUPPORTED, live.markReady());

        FiniteVodTimelineGate dynamic = new FiniteVodTimelineGate();
        dynamic.update(60_000L, false, true);
        assertEquals(Decision.UNSUPPORTED, dynamic.markReady());
        assertFalse(dynamic.isVodConfirmed());
    }

    @Test
    public void laterDynamicTransitionRevokesVod() {
        FiniteVodTimelineGate gate = new FiniteVodTimelineGate();
        gate.update(60_000L, false, false);
        gate.markReady();
        assertEquals(Decision.UNSUPPORTED, gate.update(60_000L, false, true));
    }
}
