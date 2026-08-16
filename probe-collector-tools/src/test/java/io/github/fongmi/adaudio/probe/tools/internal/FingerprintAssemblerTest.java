/* 验证采集器按 PTS 对齐变采样率 PCM 并生成四相位草稿。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import org.junit.Test;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FingerprintAssemblerTest {
    @Test
    public void alignsStereoPcmAndBuildsDraft() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video.m3u8"),
                "sample-ad", 10_000L, 12_000L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        short[] stereo = variedStereo(48_000, 2);

        assembler.append(new ProbePcmFrame(stereo, 48_000, 2, 10_000_000L));
        FingerprintRuleDraft draft = assembler.finish();

        assertTrue(assembler.isComplete());
        assertEquals(4, draft.getFingerprints().size());
        assertEquals(10_000L, draft.getTestAdStartMs());
        assertEquals("https://example.com/video.m3u8", draft.getTestUrl());
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsLargeTimelineGap() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video.m3u8"),
                "sample-ad", 0L, 2000L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        assembler.append(new ProbePcmFrame(variedStereo(48_000, 1),
                48_000, 2, 0L));
        assembler.finish();
    }

    private short[] variedStereo(int sampleRate, int seconds) {
        short[] output = new short[sampleRate * seconds * 2];
        long state = 0x12345678L;
        for (int frame = 0; frame < sampleRate * seconds; frame++) {
            state = (state * 1103515245L + 12345L) & 0x7fffffffL;
            double sweep = Math.sin(2.0 * Math.PI
                    * (180.0 + (frame % sampleRate) * 1800.0 / sampleRate)
                    * frame / sampleRate);
            short value = (short) Math.round(sweep * 18000.0 + ((state & 1023) - 512));
            output[frame * 2] = value;
            output[frame * 2 + 1] = (short) (value / 2);
        }
        return output;
    }
}
