/* 锁定匹配事件的绝对时间轴语义，旧兼容方法不得重新猜测相对终点。 */
package io.github.fongmi.adaudio.probe.internal.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MatchEventTest {
    @SuppressWarnings("deprecation")
    @Test
    public void legacyRebaseKeepsAbsoluteEnd() {
        MatchEvent event = new MatchEvent(MatchEvent.Type.START_MATCHED, "ad",
                372_800L, 388_279L, 374_080L, 1.0f, 4);

        assertEquals(388_279L, event.rebaseEndTimeMs(10_000L));
    }
}
