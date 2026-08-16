/* 实时匹配器以两帧产生候选、四至八帧确认跳过，并按相位偏移还原广告时间。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AdAudioMatcher {
    private final AdRuleSet ruleSet;
    private final MatcherConfig config;
    private final List<CompiledRule> compiledRules;
    private final Map<String, ActiveCandidate> activeCandidates = new HashMap<>();
    private final Map<String, Long> nextAllowedTime = new HashMap<>();
    private final int windowSamples;
    private final int hopSamples;
    private final int[] historyHashes;
    private final StreamingPcmNormalizer normalizer;
    private final int pendingBaseCapacity;

    private short[] pendingSamples;
    private int pendingStart;
    private int pendingCount;
    private int historyWrite;
    private int historyCount;
    private long nextFrameTimeMs = Long.MIN_VALUE;
    private long expectedChunkTimeMs = Long.MIN_VALUE;

    public AdAudioMatcher(AdRuleSet ruleSet, MatcherConfig config) {
        if (ruleSet == null) throw new IllegalArgumentException("规则集合不能为空");
        this.ruleSet = ruleSet;
        this.config = config == null ? MatcherConfig.releaseSafe() : config;
        this.windowSamples = SpectralFingerprint.millisecondsToSamples(
                ruleSet.getWindowMs(), ruleSet.getSampleRate());
        this.hopSamples = SpectralFingerprint.millisecondsToSamples(
                ruleSet.getHopMs(), ruleSet.getSampleRate());
        this.compiledRules = compileRules(ruleSet);
        this.normalizer = new StreamingPcmNormalizer(ruleSet.getSampleRate());
        int historyCapacity = Math.max(32, findMaxFingerprintFrames(compiledRules) + 4);
        this.historyHashes = new int[historyCapacity];
        this.pendingBaseCapacity = Math.max(windowSamples * 2, 16384);
        this.pendingSamples = new short[pendingBaseCapacity];
    }

    public synchronized FeedResult feed(PcmChunk chunk) {
        if (!isValid(chunk)) {
            resetInternal();
            return FeedResult.of(FeedResult.Status.INVALID_INPUT,
                    Collections.<MatchEvent>emptyList(), "PCM 输入参数无效", true);
        }
        try {
            boolean timelineReset = !normalizer.isCompatible(chunk.getSampleRate(), chunk.getChannels())
                    || shouldResetTimeline(chunk);
            if (timelineReset) resetInternal();
            if (nextFrameTimeMs == Long.MIN_VALUE) nextFrameTimeMs = chunk.getStartTimeMs();

            short[] mono = normalizer.normalize(
                    chunk.getSamples(), chunk.getSampleRate(), chunk.getChannels());
            appendSamples(mono);
            expectedChunkTimeMs = chunk.getStartTimeMs()
                    + Math.round((chunk.getSamples().length / chunk.getChannels())
                    * 1000.0 / chunk.getSampleRate());

            List<MatchEvent> events = new ArrayList<>();
            while (pendingCount >= windowSamples) {
                int hash = SpectralFingerprint.hashWindowValue(
                        pendingSamples, pendingStart, windowSamples,
                        ruleSet.getSampleRate(), ruleSet.getBandCount());
                appendHistory(hash);
                evaluate(nextFrameTimeMs, events);
                discardSamples(hopSamples);
                nextFrameTimeMs += ruleSet.getHopMs();
            }
            if (!events.isEmpty()) {
                return FeedResult.of(FeedResult.Status.MATCHED, events, "", timelineReset);
            }
            if (timelineReset) {
                return FeedResult.of(FeedResult.Status.RESET, events,
                        "媒体时间不连续，匹配窗口已安全重置");
            }
            return FeedResult.of(FeedResult.Status.NO_MATCH, events, "");
        } catch (RuntimeException error) {
            resetInternal();
            return FeedResult.of(FeedResult.Status.INTERNAL_ERROR,
                    Collections.<MatchEvent>emptyList(), error.getMessage(), true);
        }
    }

    public synchronized void reset() {
        resetInternal();
    }

    private void evaluate(long currentTimeMs, List<MatchEvent> events) {
        expireCandidates(currentTimeMs);
        // 当前帧只有在完整窗口到齐后才会被计算，事件时间应表示已消费到的位置。
        long matchedAtTimeMs = safeAdd(currentTimeMs, ruleSet.getWindowMs());
        for (CompiledRule compiled : compiledRules) {
            ActiveCandidate active = activeCandidates.get(compiled.rule.getId());
            if (active != null) {
                if (!active.confirmed) {
                    if (currentTimeMs < active.confirmAtMs) continue;
                    int length = active.confirmationLength;
                    MatchQuality quality = suffixMatchQuality(active.variant.hashes, length);
                    if (currentTimeMs == active.confirmAtMs
                            && quality.thresholdRatio >= config.getPrefixMatchRatio()) {
                        active.confirmed = true;
                        nextAllowedTime.put(compiled.rule.getId(),
                                active.endTimeMs + config.getCooldownMs());
                        events.add(new MatchEvent(MatchEvent.Type.START_MATCHED,
                                compiled.rule.getId(), active.startTimeMs, active.endTimeMs,
                                matchedAtTimeMs, quality.similarity, length));
                        // 短指纹可能在确认帧处同时完成全量校验，不能等下一帧才发出完成事件。
                        if (length == active.variant.hashes.length
                                && quality.thresholdRatio >= config.getFullMatchRatio()) {
                            events.add(new MatchEvent(MatchEvent.Type.FULL_MATCHED,
                                    compiled.rule.getId(), active.startTimeMs, active.endTimeMs,
                                    matchedAtTimeMs, quality.similarity, length));
                            activeCandidates.remove(compiled.rule.getId());
                        }
                    } else {
                        activeCandidates.remove(compiled.rule.getId());
                        // 当前帧也可能是真实广告的前缀，淘汰旧候选后继续尝试新候选。
                    }
                    if (active.confirmed
                            || activeCandidates.containsKey(compiled.rule.getId())) continue;
                }

                if (active.confirmed) {
                    long fullAtMs = active.firstFrameTimeMs
                            + (long) (active.variant.hashes.length - 1) * ruleSet.getHopMs();
                    if (currentTimeMs < fullAtMs) continue;
                    MatchQuality quality = suffixMatchQuality(
                            active.variant.hashes, active.variant.hashes.length);
                    if (currentTimeMs == fullAtMs
                            && quality.thresholdRatio >= config.getFullMatchRatio()) {
                        events.add(new MatchEvent(MatchEvent.Type.FULL_MATCHED,
                                compiled.rule.getId(), active.startTimeMs, active.endTimeMs,
                                matchedAtTimeMs, quality.similarity,
                                active.variant.hashes.length));
                    }
                    activeCandidates.remove(compiled.rule.getId());
                    continue;
                }
            }

            long allowed = nextAllowedTime.containsKey(compiled.rule.getId())
                    ? nextAllowedTime.get(compiled.rule.getId()) : Long.MIN_VALUE;
            if (currentTimeMs < allowed) continue;
            SequenceMatch candidate = bestPrefixMatch(compiled, config.getCandidateFrames());
            if (candidate.variant == null || candidate.ratio < config.getPrefixMatchRatio()) continue;

            long firstFrameTimeMs = currentTimeMs
                    - (long) (candidate.length - 1) * ruleSet.getHopMs();
            long anchorStartMs = firstFrameTimeMs - candidate.variant.offsetMs;
            long rawStartTimeMs = anchorStartMs - compiled.rule.getAnchorOffsetMs();
            long startTimeMs = Math.max(0L, rawStartTimeMs);
            long endTimeMs = Math.max(0L, safeAdd(rawStartTimeMs, compiled.rule.getDurationMs()));
            if (endTimeMs <= startTimeMs) continue;
            int confirmationLength = Math.min(candidate.variant.hashes.length,
                    Math.max(config.getConfirmationFrames(), candidate.variant.requiredConfirmationFrames));
            long confirmAtMs = firstFrameTimeMs
                    + (long) (confirmationLength - 1) * ruleSet.getHopMs();
            activeCandidates.put(compiled.rule.getId(), new ActiveCandidate(
                    candidate.variant, confirmationLength, firstFrameTimeMs,
                    confirmAtMs, startTimeMs, endTimeMs));
            events.add(new MatchEvent(MatchEvent.Type.CANDIDATE_MATCHED,
                    compiled.rule.getId(), startTimeMs, endTimeMs,
                    matchedAtTimeMs, candidate.similarity, candidate.length));
        }
    }

    private SequenceMatch bestPrefixMatch(CompiledRule rule, int requestedLength) {
        SequenceMatch best = SequenceMatch.NONE;
        for (CompiledVariant variant : rule.variants) {
            int length = Math.min(requestedLength, variant.hashes.length);
            MatchQuality quality = suffixMatchQuality(variant.hashes, length);
            if (quality.thresholdRatio > best.ratio
                    || (quality.thresholdRatio == best.ratio
                    && quality.similarity > best.similarity)) {
                best = new SequenceMatch(variant, quality.thresholdRatio,
                        quality.similarity, length);
            }
        }
        return best;
    }

    private MatchQuality suffixMatchQuality(int[] template, int length) {
        if (length <= 0 || historyCount < length || template.length < length) {
            return MatchQuality.NONE;
        }
        int start = historyWrite - length;
        if (start < 0) start += historyHashes.length;
        int matches = 0;
        long hammingBits = 0L;
        for (int i = 0; i < length; i++) {
            int historyIndex = (start + i) % historyHashes.length;
            int distance = Integer.bitCount(historyHashes[historyIndex] ^ template[i]);
            hammingBits += distance;
            if (distance <= config.getMaxHammingBits()) matches++;
        }
        float ratio = matches / (float) length;
        float similarity = 1.0f - hammingBits / (32.0f * length);
        return new MatchQuality(ratio, Math.max(0.0f, Math.min(1.0f, similarity)));
    }

    private boolean shouldResetTimeline(PcmChunk chunk) {
        return expectedChunkTimeMs != Long.MIN_VALUE
                && Math.abs(chunk.getStartTimeMs() - expectedChunkTimeMs) > config.getMaxTimelineGapMs();
    }

    private boolean isValid(PcmChunk chunk) {
        return chunk != null && chunk.getSamples() != null && chunk.getSamples().length > 0
                && chunk.getSampleRate() >= 8000 && chunk.getSampleRate() <= 384000
                && chunk.getChannels() > 0 && chunk.getChannels() <= 16
                && chunk.getSamples().length >= chunk.getChannels() && chunk.getStartTimeMs() >= 0;
    }

    private void appendSamples(short[] input) {
        if (input.length == 0) return;
        ensurePendingCapacity(pendingCount + input.length);
        System.arraycopy(input, 0, pendingSamples, pendingStart + pendingCount, input.length);
        pendingCount += input.length;
    }

    private void ensurePendingCapacity(int required) {
        if (required <= pendingSamples.length - pendingStart) return;
        if (required <= pendingSamples.length) {
            System.arraycopy(pendingSamples, pendingStart, pendingSamples, 0, pendingCount);
            pendingStart = 0;
            return;
        }
        int capacity = pendingSamples.length;
        while (capacity < required) capacity = Math.max(capacity * 2, required);
        short[] grown = new short[capacity];
        System.arraycopy(pendingSamples, pendingStart, grown, 0, pendingCount);
        pendingSamples = grown;
        pendingStart = 0;
    }

    private void discardSamples(int count) {
        if (count >= pendingCount) {
            pendingStart = 0;
            pendingCount = 0;
            return;
        }
        pendingStart += count;
        pendingCount -= count;
    }

    private void appendHistory(int hash) {
        historyHashes[historyWrite] = hash;
        historyWrite = (historyWrite + 1) % historyHashes.length;
        historyCount = Math.min(historyHashes.length, historyCount + 1);
    }

    private void expireCandidates(long timeMs) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, ActiveCandidate> entry : activeCandidates.entrySet()) {
            ActiveCandidate candidate = entry.getValue();
            long deadline = candidate.confirmed
                    ? candidate.endTimeMs + config.getCooldownMs()
                    : candidate.confirmAtMs;
            if (timeMs > deadline) expired.add(entry.getKey());
        }
        for (String id : expired) activeCandidates.remove(id);
    }

    private void resetInternal() {
        pendingStart = 0;
        pendingCount = 0;
        if (pendingSamples.length > pendingBaseCapacity * 4) {
            pendingSamples = new short[pendingBaseCapacity];
        }
        historyWrite = 0;
        historyCount = 0;
        activeCandidates.clear();
        nextAllowedTime.clear();
        nextFrameTimeMs = Long.MIN_VALUE;
        expectedChunkTimeMs = Long.MIN_VALUE;
        normalizer.reset();
    }

    private static List<CompiledRule> compileRules(AdRuleSet ruleSet) {
        List<CompiledRule> output = new ArrayList<>();
        for (AdRule rule : ruleSet.getRules()) {
            List<CompiledVariant> variants = new ArrayList<>();
            for (FingerprintVariant source : rule.getFingerprints()) {
                int[] hashes = new int[source.getHashes().size()];
                for (int i = 0; i < hashes.length; i++) {
                    hashes[i] = (int) Long.parseUnsignedLong(source.getHashes().get(i), 16);
                }
                variants.add(new CompiledVariant(source.getOffsetMs(), hashes,
                        AdRuleSet.requiredConfirmationFrames(source.getHashes())));
            }
            output.add(new CompiledRule(rule, variants));
        }
        return Collections.unmodifiableList(output);
    }

    private static int findMaxFingerprintFrames(List<CompiledRule> rules) {
        int max = 0;
        for (CompiledRule rule : rules) {
            for (CompiledVariant variant : rule.variants) max = Math.max(max, variant.hashes.length);
        }
        return max;
    }

    private static long safeAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static final class CompiledRule {
        final AdRule rule;
        final List<CompiledVariant> variants;

        CompiledRule(AdRule rule, List<CompiledVariant> variants) {
            this.rule = rule;
            this.variants = variants;
        }
    }

    private static final class CompiledVariant {
        final int offsetMs;
        final int[] hashes;
        final int requiredConfirmationFrames;

        CompiledVariant(int offsetMs, int[] hashes, int requiredConfirmationFrames) {
            this.offsetMs = offsetMs;
            this.hashes = hashes;
            this.requiredConfirmationFrames = requiredConfirmationFrames;
        }
    }

    private static final class ActiveCandidate {
        final CompiledVariant variant;
        final int confirmationLength;
        final long firstFrameTimeMs;
        final long confirmAtMs;
        final long startTimeMs;
        final long endTimeMs;
        boolean confirmed;

        ActiveCandidate(CompiledVariant variant, int confirmationLength,
                        long firstFrameTimeMs, long confirmAtMs,
                        long startTimeMs, long endTimeMs) {
            this.variant = variant;
            this.confirmationLength = confirmationLength;
            this.firstFrameTimeMs = firstFrameTimeMs;
            this.confirmAtMs = confirmAtMs;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
        }
    }

    private static final class SequenceMatch {
        static final SequenceMatch NONE = new SequenceMatch(null, 0.0f, 0.0f, 0);
        final CompiledVariant variant;
        final float ratio;
        final float similarity;
        final int length;

        SequenceMatch(CompiledVariant variant, float ratio, float similarity, int length) {
            this.variant = variant;
            this.ratio = ratio;
            this.similarity = similarity;
            this.length = length;
        }
    }

    private static final class MatchQuality {
        static final MatchQuality NONE = new MatchQuality(0.0f, 0.0f);
        final float thresholdRatio;
        final float similarity;

        MatchQuality(float thresholdRatio, float similarity) {
            this.thresholdRatio = thresholdRatio;
            this.similarity = similarity;
        }
    }
}
