/* 验证 Media3 placeholder 到真实 VOD 的状态迁移。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_11.internal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Media3VodTimelineGateTest {
    @Test
    public void repeatedPlaceholderThenVodIsAccepted() {
        Media3VodTimelineGate gate = new Media3VodTimelineGate();
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(true, false, true));
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(true, false, true));
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(false, false, false));
        assertEquals(Media3VodTimelineGate.Decision.VOD, gate.markReady());
        assertTrue(gate.isVodConfirmed());
        assertEquals(Media3VodTimelineGate.Decision.IGNORED,
                gate.update(true, false, true));
    }

    @Test
    public void authoritativeLiveAndDynamicAreRejected() {
        Media3VodTimelineGate live = new Media3VodTimelineGate();
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                live.update(false, true, true));
        assertEquals(Media3VodTimelineGate.Decision.REJECT_LIVE, live.markReady());
        assertEquals(Media3VodTimelineGate.Decision.IGNORED,
                live.update(false, false, false));

        Media3VodTimelineGate dynamic = new Media3VodTimelineGate();
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                dynamic.update(false, false, true));
        assertEquals(Media3VodTimelineGate.Decision.REJECT_DYNAMIC, dynamic.markReady());
    }

    @Test
    public void resetStartsNewFallbackAttempt() {
        Media3VodTimelineGate gate = new Media3VodTimelineGate();
        gate.update(false, true, true);
        gate.markReady();
        gate.reset();
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(false, false, false));
        assertEquals(Media3VodTimelineGate.Decision.VOD, gate.markReady());
    }

    @Test
    public void transientDynamicBeforeReadyUsesFinalVodSnapshot() {
        Media3VodTimelineGate gate = new Media3VodTimelineGate();
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(false, false, true));
        assertEquals(Media3VodTimelineGate.Decision.PENDING,
                gate.update(false, false, false));
        assertEquals(Media3VodTimelineGate.Decision.VOD, gate.markReady());
    }
}
