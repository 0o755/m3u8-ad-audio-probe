/* 验证打开命令排序与播放时间轴轮询门闩。 */
package io.github.fongmi.adaudio.probe.player;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerControlFlowTest {
    @Test
    public void preparingCallbackControlRunsAfterAdapterOpen() {
        Queue<Runnable> controlQueue = new ArrayDeque<>();
        List<String> events = new ArrayList<>();
        PlayerOpenCommand open = new PlayerOpenCommand(() -> {
            events.add("preparing");
            controlQueue.add(() -> events.add("pause"));
        }, () -> events.add("open"));

        controlQueue.add(open);
        controlQueue.remove().run();
        controlQueue.remove().run();

        assertEquals(Arrays.asList("preparing", "open", "pause"), events);
    }

    @Test
    public void pollGateDeduplicatesAndRestartsAfterEnded() {
        PlayerPollGate gate = new PlayerPollGate();
        assertTrue(gate.reserve(7L));
        assertFalse(gate.reserve(7L));
        assertTrue(gate.begin(7L));

        gate.stop(7L);
        assertTrue(gate.reserve(7L));
        assertTrue(gate.begin(7L));
    }

    @Test
    public void replacementSessionInvalidatesOldDelayedPoll() {
        PlayerPollGate gate = new PlayerPollGate();
        assertTrue(gate.reserve(7L));
        assertTrue(gate.reserve(8L));
        assertFalse(gate.begin(7L));
        assertTrue(gate.begin(8L));
    }
}
