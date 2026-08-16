/* 验证规则模型拒绝坏哈希、重复规则和不完整相位。 */
package io.github.fongmi.adaudio.probe.internal.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class AdRuleSetTest {
    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedHash() {
        new FingerprintVariant(0, Arrays.asList("00000000", "00000000", "not-hash", "00000000"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingZeroPhase() {
        AdRule rule = new AdRule("ad", 2000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(64, hashes(6))));
        createRuleSet(Collections.singletonList(rule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateRuleId() {
        AdRule rule = new AdRule("ad", 2000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(6))));
        createRuleSet(Arrays.asList(rule, rule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFingerprintLengthThatDoesNotMatchAnchor() {
        AdRule rule = new AdRule("ad", 2000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(5))));
        createRuleSet(Collections.singletonList(rule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnchorOffsetOverflow() {
        new AdRule("ad", 2000L, Long.MAX_VALUE, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(6))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdenticalPrefixWithDifferentDurations() {
        FingerprintVariant primary = new FingerprintVariant(0, hashes(6));
        AdRule shortRule = new AdRule("short-ad", 2000L, 0L, 2000L,
                Collections.singletonList(primary));
        AdRule longRule = new AdRule("long-ad", 3000L, 0L, 2000L,
                Collections.singletonList(primary));

        createRuleSet(Arrays.asList(shortRule, longRule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdenticalPrefixWithDifferentAnchorOffsets() {
        FingerprintVariant primary = new FingerprintVariant(0, hashes(6));
        AdRule first = new AdRule("first-ad", 3000L, 0L, 2000L,
                Collections.singletonList(primary));
        AdRule second = new AdRule("second-ad", 3000L, 500L, 2000L,
                Collections.singletonList(primary));

        createRuleSet(Arrays.asList(first, second));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortPrefixOfLongerRuleWithDifferentEndpoint() {
        AdRule shortRule = new AdRule("short-ad", 2000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(6))));
        AdRule longRule = new AdRule("long-ad", 5000L, 0L, 5000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(18))));

        createRuleSet(Arrays.asList(longRule, shortRule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLongerRuleAfterConflictingShortPrefix() {
        AdRule shortRule = new AdRule("short-ad", 2000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(6))));
        AdRule longRule = new AdRule("long-ad", 5000L, 0L, 5000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(18))));

        createRuleSet(Arrays.asList(shortRule, longRule));
    }

    @Test
    public void acceptsShortPrefixOfLongerRuleWithSameEndpoint() {
        AdRule shortRule = new AdRule("short-ad", 5000L, 0L, 2000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(6))));
        AdRule longRule = new AdRule("long-ad", 5000L, 0L, 5000L,
                Collections.singletonList(new FingerprintVariant(0, hashes(18))));

        createRuleSet(Arrays.asList(longRule, shortRule));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRevisionOutsideCrossPlatformSafeRange() {
        new AdRuleSet(AdRuleSet.MAX_REVISION + 1L, AdRuleSet.SAMPLE_RATE,
                AdRuleSet.WINDOW_MS, AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT,
                Collections.<AdRule>emptyList());
    }

    private AdRuleSet createRuleSet(List<AdRule> rules) {
        return new AdRuleSet(1L, AdRuleSet.SAMPLE_RATE, AdRuleSet.WINDOW_MS,
                AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT, rules);
    }

    private List<String> hashes(int count) {
        String[] values = new String[count];
        Arrays.fill(values, "1234abcd");
        if (count > 1) values[1] = "fedc4321";
        return Arrays.asList(values);
    }
}
