/* 验证无声 AudioSink 的 PTS 连续性容差与溢出保护。 */
package io.github.fongmi.adaudio.probe.internal.media3;

import androidx.media3.exoplayer.audio.AudioSink;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProbeAudioSinkTest {
    @Test
    public void acceptsNormalCodecTimestampDrift() {
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(
                AudioSink.CURRENT_POSITION_NOT_SET, 10_000L));
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 1_200_000L));
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 800_000L));
    }

    @Test
    public void rejectsLargeOrOverflowingTimestampJump() {
        assertTrue(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 1_200_001L));
        assertTrue(ProbeAudioSink.isUnexpectedPresentationTime(Long.MAX_VALUE, Long.MIN_VALUE));
    }
}
