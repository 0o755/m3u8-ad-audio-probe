/* 验证采集器按 PTS 对齐变采样率 PCM 并生成四相位草稿。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import org.junit.Test;

import java.util.Arrays;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.adapter.internal.FiniteVodTimelineGate;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void transientDynamicMasterStillProducesKnownCandidateDraft() {
        FiniteVodTimelineGate timeline = new FiniteVodTimelineGate();
        assertEquals(FiniteVodTimelineGate.Decision.PENDING,
                timeline.update(-1L, false, true));
        assertEquals(FiniteVodTimelineGate.Decision.PENDING,
                timeline.update(1_385_172L, false, false));
        assertEquals(FiniteVodTimelineGate.Decision.VOD_CONFIRMED, timeline.markReady());

        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video/index.m3u8"),
                "auto-ad-b5eebc0445f595f6", 342_000L, 357_132L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        short[] pcm = variedStereo(48_000, 5);
        int samplesPerSecond = 48_000 * 2;
        for (int second = 0; second < 5; second++) {
            short[] chunk = Arrays.copyOfRange(pcm, second * samplesPerSecond,
                    (second + 1) * samplesPerSecond);
            assembler.append(new ProbePcmFrame(chunk, 48_000, 2,
                    (342_000L + second * 1000L) * 1000L));
        }

        FingerprintRuleDraft draft = assembler.finish();
        assertTrue(assembler.isComplete());
        assertEquals("auto-ad-b5eebc0445f595f6", draft.getId());
        assertEquals(15_132L, draft.getDurationMs());
        assertEquals(5_000L, draft.getAnchorDurationMs());
        assertEquals(342_000L, draft.getTestAdStartMs());
        assertEquals(4, draft.getFingerprints().size());
    }

    @Test
    public void acceptsOneAacFrameOfLeadingPtsOffset() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video/index.m3u8"),
                "aac-offset-ad", 342_000L, 357_132L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        appendOneSecondChunks(assembler, variedStereo(48_000, 5),
                48_000, 2, 342_023_000L);

        assertTrue(assembler.isComplete());
        assertEquals(4, assembler.finish().getFingerprints().size());
    }

    @Test
    public void rejectsLargeLeadingOrInternalGap() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video/index.m3u8"),
                "unsafe-gap-ad", 342_000L, 347_000L).build();
        FingerprintAssembler leading = new FingerprintAssembler(request);
        appendOneSecondChunks(leading, variedStereo(48_000, 5),
                48_000, 2, 342_080_000L);
        assertFalse(leading.isComplete());

        FingerprintAssembler internal = new FingerprintAssembler(request);
        internal.append(new ProbePcmFrame(variedStereo(48_000, 2),
                48_000, 2, 342_000_000L));
        internal.append(new ProbePcmFrame(variedStereo(48_000, 2),
                48_000, 2, 344_020_000L));
        internal.append(new ProbePcmFrame(variedStereo(48_000, 1),
                48_000, 2, 346_020_000L));
        assertFalse(internal.isComplete());
    }

    @Test
    public void waitsForWatermarkBeforeRepairingTrailingCodecEdge() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video/index.m3u8"),
                "aac-tail-ad", 10_000L, 15_000L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        short[] almostFiveSeconds = variedStereo(48_000, 5);
        int missingFrames = 48_000 * 23 / 1000;
        almostFiveSeconds = Arrays.copyOf(almostFiveSeconds,
                almostFiveSeconds.length - missingFrames * 2);
        appendOneSecondChunks(assembler, almostFiveSeconds,
                48_000, 2, 10_000_000L);

        assertFalse(assembler.isComplete());
        assembler.append(new ProbePcmFrame(variedStereo(48_000, 1),
                48_000, 2, 15_010_000L));
        assertTrue(assembler.isComplete());
    }

    private void appendOneSecondChunks(FingerprintAssembler assembler, short[] pcm,
                                       int sampleRate, int channels, long startUs) {
        int samplesPerSecond = sampleRate * channels;
        for (int offset = 0; offset < pcm.length; offset += samplesPerSecond) {
            int end = Math.min(pcm.length, offset + samplesPerSecond);
            short[] chunk = Arrays.copyOfRange(pcm, offset, end);
            long frameIndex = offset / channels;
            assembler.append(new ProbePcmFrame(chunk, sampleRate, channels,
                    startUs + frameIndex * 1_000_000L / sampleRate));
        }
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
