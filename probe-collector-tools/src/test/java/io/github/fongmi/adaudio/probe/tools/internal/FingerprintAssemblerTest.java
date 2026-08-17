/* 验证采集器按 PTS 对齐变采样率 PCM 并生成四相位草稿。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.adapter.internal.FiniteVodTimelineGate;
import io.github.fongmi.adaudio.probe.internal.core.AdAudioMatcher;
import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FeedResult;
import io.github.fongmi.adaudio.probe.internal.core.FingerprintVariant;
import io.github.fongmi.adaudio.probe.internal.core.MatchEvent;
import io.github.fongmi.adaudio.probe.internal.core.MatcherConfig;
import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.FingerprintSequence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FingerprintAssemblerTest {
    @Test
    public void alignsStereoPcmAndBuildsDraft() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video.m3u8"),
                "sample-ad", 10_000L, 15_000L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        short[] stereo = variedStereo(48_000, 5);

        appendOneSecondChunks(assembler, stereo, 48_000, 2, 10_000_000L);
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
                "sample-ad", 0L, 5000L).build();
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

    @Test
    public void capturedFiveSecondDraftProducesFullMatch() {
        long adStartMs = 342_000L;
        int sampleRate = AdRuleSet.SAMPLE_RATE;
        short[] ad = variedStereo(sampleRate, 5);
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                ProbeMedia.from("https://example.com/video/index.m3u8"),
                "round-trip-ad", adStartMs, adStartMs + 15_132L).build();
        FingerprintAssembler assembler = new FingerprintAssembler(request);
        appendOneSecondChunks(assembler, ad, sampleRate, 2, adStartMs * 1000L);
        FingerprintRuleDraft draft = assembler.finish();

        List<FingerprintVariant> variants = new java.util.ArrayList<>();
        for (FingerprintSequence sequence : draft.getFingerprints()) {
            variants.add(new FingerprintVariant(sequence.getPhaseMs(), sequence.getHashes()));
        }
        AdRule rule = new AdRule(draft.getId(), draft.getDurationMs(),
                draft.getAnchorOffsetMs(), draft.getAnchorDurationMs(), variants);
        AdRuleSet rules = new AdRuleSet(1L, AdRuleSet.SAMPLE_RATE,
                AdRuleSet.WINDOW_MS, AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT,
                java.util.Collections.singletonList(rule));
        AdAudioMatcher matcher = new AdAudioMatcher(rules, MatcherConfig.releaseSafe());

        boolean fullyMatched = false;
        int framesPerChunk = 1024;
        for (int frameOffset = 0; frameOffset < ad.length / 2; frameOffset += framesPerChunk) {
            int frameCount = Math.min(framesPerChunk, ad.length / 2 - frameOffset);
            short[] chunk = Arrays.copyOfRange(ad, frameOffset * 2,
                    (frameOffset + frameCount) * 2);
            long startUs = adStartMs * 1000L + frameOffset * 1_000_000L / sampleRate;
            FeedResult result = matcher.feed(new PcmChunk(chunk, sampleRate, 2,
                    startUs / 1000L));
            for (MatchEvent event : result.getEvents()) {
                if (event.getType() == MatchEvent.Type.FULL_MATCHED) fullyMatched = true;
            }
        }
        assertTrue("采集草稿必须完成五秒指纹验证", fullyMatched);
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
