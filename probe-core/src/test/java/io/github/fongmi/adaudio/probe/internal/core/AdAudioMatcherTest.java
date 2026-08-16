/* 验证候选/确认分级、多相位时间修正、短锚点和异常输入边界。 */
package io.github.fongmi.adaudio.probe.internal.core;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdAudioMatcherTest {
    @Test
    public void releaseConfigSeparatesCandidateAndConfirmedMatch() {
        int rate = 16000;
        short[] audio = buildDynamicAudio(rate, 4);
        MatchSummary result = feed(new AdAudioMatcher(createRules(audio, rate, 4000),
                strictReleaseConfig()), audio, rate, 64);

        assertTrue(result.candidateMatched);
        assertTrue(result.startMatched);
        assertTrue(result.fullMatched);
        assertTrue(result.candidateDetectionTimeMs <= 1000L);
        assertTrue(result.startDetectionTimeMs >= 1200L);
        assertTrue(result.startDetectionTimeMs <= 1400L);
        assertEquals(result.startDetectionTimeMs, result.startMatchedAtTimeMs);
        assertEquals(0L, result.startTimeMs);
        assertEquals(4000L, result.endTimeMs);
    }

    @Test
    public void twoMatchingFramesNeverTriggerSkipWithoutConfirmation() {
        int rate = 16000;
        short[] ruleAudio = buildAudio(rate, 4);
        short[] stream = buildTone(rate, 4, 2600.0);
        int matchingSamples = Math.round(rate * 0.80f);
        System.arraycopy(ruleAudio, 0, stream, 0, matchingSamples);

        MatchSummary result = feed(new AdAudioMatcher(createRules(ruleAudio, rate, 4000),
                strictReleaseConfig()), stream, rate, 64);

        assertTrue(result.candidateMatched);
        assertFalse(result.startMatched);
    }

    @Test
    public void phaseOffsetRestoresNonAlignedAdStart() {
        int rate = 16000;
        short[] audio = buildDynamicAudio(rate, 4);
        int prefixSamples = Math.round(rate * 0.137f);
        short[] shifted = new short[prefixSamples + audio.length];
        System.arraycopy(audio, 0, shifted, prefixSamples, audio.length);

        MatchSummary result = feed(new AdAudioMatcher(createRules(audio, rate, 4000),
                MatcherConfig.releaseSafe()), shifted, rate, 64);

        assertTrue(result.startMatched);
        assertTrue(Math.abs(result.startTimeMs - 137L) <= 64L);
        assertEquals(result.startTimeMs + 4000L, result.endTimeMs);
    }

    @Test
    public void fragmented48kPcmKeepsResamplePhase() {
        short[] ruleAudio = buildAudio(16000, 4);
        short[] playbackAudio = buildAudio(48000, 4);
        AdAudioMatcher matcher = new AdAudioMatcher(createRules(ruleAudio, 16000, 4000),
                strictReleaseConfig());

        MatchSummary result = feedByFrames(matcher, playbackAudio, 48000, 1024);

        assertTrue(result.startMatched);
        assertTrue(result.fullMatched);
        assertEquals(0L, result.startTimeMs);
    }

    @Test
    public void shortAnchorPredictsFullAdEnd() {
        int rate = 16000;
        short[] anchor = buildAudio(rate, 5);
        AdRuleSet rules = createRules(anchor, rate, 15000L, 5000L);
        MatchSummary result = feed(new AdAudioMatcher(rules, strictReleaseConfig()),
                anchor, rate, 64);

        assertTrue(result.startMatched);
        assertEquals(15000L, result.endTimeMs);
    }

    @Test
    public void rejectsInvalidInputWithoutThrowing() {
        AdAudioMatcher matcher = new AdAudioMatcher(AdRuleSet.empty(), MatcherConfig.releaseSafe());
        FeedResult result = matcher.feed(new PcmChunk(new short[0], 16000, 1, 0));
        assertEquals(FeedResult.Status.INVALID_INPUT, result.getStatus());
        assertTrue(result.isTimelineReset());
    }

    @Test
    public void negativeAdStartKeepsOriginalEndPosition() {
        int rate = 16000;
        short[] anchor = buildDynamicAudio(rate, 4);
        List<FingerprintVariant> variants = SpectralFingerprint.extractVariants(
                anchor, rate, 1, AdRuleSet.empty());
        AdRule rule = new AdRule("mid-ad", 10_000L, 2000L, 4000L, variants);
        AdRuleSet rules = new AdRuleSet(1L, AdRuleSet.SAMPLE_RATE,
                AdRuleSet.WINDOW_MS, AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT,
                Collections.singletonList(rule));

        MatchSummary result = feedAt(new AdAudioMatcher(rules, strictReleaseConfig()),
                anchor, rate, 64, 1000L);

        assertTrue(result.startMatched);
        assertEquals(0L, result.startTimeMs);
        assertEquals(9000L, result.endTimeMs);
    }

    @Test
    public void resetAndMatchInSameChunkRemainObservable() {
        int rate = 16000;
        short[] audio = buildDynamicAudio(rate, 4);
        AdAudioMatcher matcher = new AdAudioMatcher(createRules(audio, rate, 4000L),
                strictReleaseConfig());
        matcher.feed(new PcmChunk(new short[rate / 10], rate, 1, 0L));

        FeedResult result = matcher.feed(new PcmChunk(audio, rate, 1, 10_000L));

        assertEquals(FeedResult.Status.MATCHED, result.getStatus());
        assertTrue(result.isTimelineReset());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSilentRuleBeforeMatching() {
        int rate = 16000;
        short[] silence = new short[rate * 4];
        createRules(silence, rate, 4000);
    }

    @Test
    public void resetClearsCooldownForReplay() {
        int rate = 16000;
        short[] audio = buildDynamicAudio(rate, 4);
        AdAudioMatcher matcher = new AdAudioMatcher(createRules(audio, rate, 4000),
                strictReleaseConfig());

        assertTrue(feed(matcher, audio, rate, 64).startMatched);
        matcher.reset();

        assertTrue(feed(matcher, audio, rate, 64).startMatched);
    }

    private MatcherConfig strictReleaseConfig() {
        return new MatcherConfig.Builder()
                .setCandidateFrames(2)
                .setConfirmationFrames(4)
                .setMaxHammingBits(0)
                .setPrefixMatchRatio(1.0f)
                .setFullMatchRatio(1.0f)
                .build();
    }

    private AdRuleSet createRules(short[] audio, int rate, long durationMs) {
        return createRules(audio, rate, durationMs, durationMs);
    }

    private AdRuleSet createRules(short[] audio, int rate, long durationMs,
                                  long anchorDurationMs) {
        AdRuleSet format = AdRuleSet.empty();
        List<FingerprintVariant> variants = SpectralFingerprint.extractVariants(
                audio, rate, 1, format);
        AdRule rule = new AdRule("test-ad", durationMs, 0L, anchorDurationMs, variants);
        return new AdRuleSet(1L, AdRuleSet.SAMPLE_RATE, AdRuleSet.WINDOW_MS,
                AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT, Collections.singletonList(rule));
    }

    private MatchSummary feed(AdAudioMatcher matcher, short[] audio, int rate, int chunkMs) {
        return feedByFrames(matcher, audio, rate, rate * chunkMs / 1000);
    }

    private MatchSummary feedAt(AdAudioMatcher matcher, short[] audio, int rate,
                                int chunkMs, long startTimeMs) {
        int chunkSamples = rate * chunkMs / 1000;
        MatchSummary summary = new MatchSummary();
        for (int offset = 0; offset < audio.length; offset += chunkSamples) {
            int length = Math.min(chunkSamples, audio.length - offset);
            short[] chunk = new short[length];
            System.arraycopy(audio, offset, chunk, 0, length);
            FeedResult result = matcher.feed(new PcmChunk(chunk, rate, 1,
                    startTimeMs + offset * 1000L / rate));
            collect(result, summary, startTimeMs + (offset + length) * 1000L / rate);
        }
        return summary;
    }

    private MatchSummary feedByFrames(AdAudioMatcher matcher, short[] audio,
                                      int rate, int chunkSamples) {
        MatchSummary summary = new MatchSummary();
        for (int offset = 0; offset < audio.length; offset += chunkSamples) {
            int length = Math.min(chunkSamples, audio.length - offset);
            short[] chunk = new short[length];
            System.arraycopy(audio, offset, chunk, 0, length);
            FeedResult result = matcher.feed(new PcmChunk(chunk, rate, 1,
                    offset * 1000L / rate));
            long receivedUntilMs = (offset + length) * 1000L / rate;
            collect(result, summary, receivedUntilMs);
        }
        return summary;
    }

    private void collect(FeedResult result, MatchSummary summary, long receivedUntilMs) {
        for (MatchEvent event : result.getEvents()) {
                if (event.getType() == MatchEvent.Type.CANDIDATE_MATCHED) {
                    summary.candidateMatched = true;
                    if (summary.candidateDetectionTimeMs == 0L) {
                        summary.candidateDetectionTimeMs = receivedUntilMs;
                    }
                } else if (event.getType() == MatchEvent.Type.START_MATCHED) {
                    summary.startMatched = true;
                    summary.startTimeMs = event.getStartTimeMs();
                    summary.endTimeMs = event.getEndTimeMs();
                    summary.startMatchedAtTimeMs = event.getMatchedAtTimeMs();
                    if (summary.startDetectionTimeMs == 0L) {
                        summary.startDetectionTimeMs = receivedUntilMs;
                    }
                } else if (event.getType() == MatchEvent.Type.FULL_MATCHED) {
                    summary.fullMatched = true;
                }
        }
    }

    private short[] buildAudio(int sampleRate, int seconds) {
        short[] output = new short[sampleRate * seconds];
        for (int i = 0; i < output.length; i++) {
            double second = i / (double) sampleRate;
            double frequency = second < seconds * 0.35 ? 440.0
                    : second < seconds * 0.70 ? 880.0 : 660.0;
            output[i] = (short) Math.round(Math.sin(2.0 * Math.PI * frequency * second) * 10000.0);
        }
        return output;
    }

    private short[] buildTone(int sampleRate, int seconds, double frequency) {
        short[] output = new short[sampleRate * seconds];
        for (int i = 0; i < output.length; i++) {
            output[i] = (short) Math.round(Math.sin(2.0 * Math.PI * frequency
                    * i / sampleRate) * 10000.0);
        }
        return output;
    }

    private short[] buildDynamicAudio(int sampleRate, int seconds) {
        short[] output = new short[sampleRate * seconds];
        for (int i = 0; i < output.length; i++) {
            double second = i / (double) sampleRate;
            double frequency = 260.0 + second * 920.0
                    + 180.0 * Math.sin(2.0 * Math.PI * 1.7 * second);
            output[i] = (short) Math.round(Math.sin(2.0 * Math.PI * frequency * second) * 10000.0);
        }
        return output;
    }

    private static final class MatchSummary {
        boolean candidateMatched;
        boolean startMatched;
        boolean fullMatched;
        long startTimeMs;
        long endTimeMs;
        long startMatchedAtTimeMs;
        long candidateDetectionTimeMs;
        long startDetectionTimeMs;
    }
}
