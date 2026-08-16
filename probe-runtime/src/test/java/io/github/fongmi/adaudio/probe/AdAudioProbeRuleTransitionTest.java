/* 验证规则切换生成的公开状态不会泄漏停用期间保留的媒体。 */
package io.github.fongmi.adaudio.probe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AdAudioProbeRuleTransitionTest {
    @Test
    public void disabledRuleTransitionUsesEmptyMediaId() {
        ProbeMedia retained = ProbeMedia.builder("https://example.com/video.m3u8")
                .setId("retained-media")
                .build();

        assertEquals("", AdAudioProbe.statusMediaId(0L, retained));
        assertEquals("retained-media", AdAudioProbe.statusMediaId(7L, retained));
        assertEquals("", AdAudioProbe.statusMediaId(0L, null));
    }
}
