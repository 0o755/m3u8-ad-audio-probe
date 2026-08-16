/* 验证真实匹配器同时命中近似规则时，不会把不同终点送入派发队列。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import io.github.fongmi.adaudio.probe.internal.core.AdAudioMatcher;
import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FeedResult;
import io.github.fongmi.adaudio.probe.internal.core.FingerprintVariant;
import io.github.fongmi.adaudio.probe.internal.core.MatchEvent;
import io.github.fongmi.adaudio.probe.internal.core.MatcherConfig;
import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;
import io.github.fongmi.adaudio.probe.internal.core.SpectralFingerprint;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ApproximateRuleConflictTest {
    @Test
    public void approximateRulesWithDifferentAnchorOffsetsFailOpen() {
        int sampleRate = AdRuleSet.SAMPLE_RATE;
        short[] anchor = buildDynamicAudio(sampleRate, 4);
        List<FingerprintVariant> exact = SpectralFingerprint.extractVariants(
                anchor, sampleRate, 1, AdRuleSet.empty());
        List<FingerprintVariant> near = toggleOneBit(exact);
        AdRule first = new AdRule("near-a", 10_000L, 0L, 4_000L, exact);
        AdRule second = new AdRule("near-b", 12_000L, 3_000L, 4_000L, near);
        AdRuleSet rules = new AdRuleSet(1L, sampleRate, AdRuleSet.WINDOW_MS,
                AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT, Arrays.asList(first, second));
        AdAudioMatcher matcher = new AdAudioMatcher(rules, MatcherConfig.releaseSafe());

        FeedResult result = matcher.feed(new PcmChunk(anchor, sampleRate, 1, 10_000L));
        Set<String> confirmedRuleIds = new HashSet<>();
        Map<String, MatchEvent> starts = new HashMap<>();
        for (MatchEvent event : result.getEvents()) {
            if (event.getType() == MatchEvent.Type.START_MATCHED) {
                confirmedRuleIds.add(event.getRuleId());
                starts.put(event.getRuleId(), event);
            }
        }
        DetectionCoordinator coordinator = new DetectionCoordinator();
        List<ConfirmedAd> ready = new ArrayList<>(coordinator.onMatches(result.getEvents()));
        ready.addAll(coordinator.onAnalyzedThrough(14_000L));

        assertEquals(new HashSet<>(Arrays.asList("near-a", "near-b")), confirmedRuleIds);
        assertEquals(1, SpectralFingerprint.hammingDistance(
                exact.get(0).getHashes().get(0), near.get(0).getHashes().get(0)));
        MatchEvent firstMatch = starts.get("near-a");
        MatchEvent secondMatch = starts.get("near-b");
        assertEquals(1.0f, firstMatch.getMatchSimilarity(), 0.0001f);
        assertEquals(31.0f / 32.0f, secondMatch.getMatchSimilarity(), 0.0001f);
        assertTrue(Math.abs(firstMatch.getStartTimeMs() - secondMatch.getStartTimeMs()) > 250L);
        assertTrue(firstMatch.getStartTimeMs() < secondMatch.getEndTimeMs()
                && secondMatch.getStartTimeMs() < firstMatch.getEndTimeMs());
        assertTrue(Math.abs(firstMatch.getEndTimeMs() - secondMatch.getEndTimeMs()) > 250L);
        assertTrue(ready.isEmpty());
    }

    private List<FingerprintVariant> toggleOneBit(List<FingerprintVariant> variants) {
        List<FingerprintVariant> output = new ArrayList<>();
        for (FingerprintVariant variant : variants) {
            List<String> hashes = new ArrayList<>();
            for (String hash : variant.getHashes()) {
                long value = Long.parseUnsignedLong(hash, 16) ^ 1L;
                hashes.add(String.format(Locale.ROOT, "%08x", value));
            }
            output.add(new FingerprintVariant(variant.getOffsetMs(), hashes));
        }
        return output;
    }

    private short[] buildDynamicAudio(int sampleRate, int seconds) {
        short[] output = new short[sampleRate * seconds];
        for (int index = 0; index < output.length; index++) {
            double second = index / (double) sampleRate;
            double frequency = 260.0 + second * 920.0
                    + 180.0 * Math.sin(2.0 * Math.PI * 1.7 * second);
            output[index] = (short) Math.round(
                    Math.sin(2.0 * Math.PI * frequency * second) * 10_000.0);
        }
        return output;
    }
}
