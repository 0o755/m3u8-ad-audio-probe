/* 按媒体 PTS 对齐采样点，并调用核心频谱算法生成 rules-v1 草稿。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FingerprintVariant;
import io.github.fongmi.adaudio.probe.internal.core.SpectralFingerprint;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.FingerprintSequence;

/** 模块内部的有界 PCM 对齐器；公开层永远不返回其缓冲。 */
public final class FingerprintAssembler {
    private static final int TARGET_RATE = AdRuleSet.SAMPLE_RATE;
    private static final int MAX_MISSING_SAMPLES = TARGET_RATE / 200; // 最多允许 5ms 舍入缺口。

    private final FingerprintCaptureRequest request;
    private final long anchorStartUs;
    private final long anchorEndUs;
    private final short[] mono;
    private final BitSet filled;
    private int filledCount;

    public FingerprintAssembler(FingerprintCaptureRequest request) {
        if (request == null) throw new IllegalArgumentException("采集请求不能为空");
        this.request = request;
        this.anchorStartUs = multiplyExact(request.getAdStartMs()
                + request.getAnchorOffsetMs(), 1000L);
        this.anchorEndUs = anchorStartUs + multiplyExact(request.getAnchorDurationMs(), 1000L);
        int sampleCount = (int) (request.getAnchorDurationMs() * TARGET_RATE / 1000L);
        this.mono = new short[sampleCount];
        this.filled = new BitSet(sampleCount);
    }

    /** 写入与锚点相交的帧，返回当前已覆盖样本数。 */
    public synchronized int append(ProbePcmFrame frame) {
        if (frame == null) return filledCount;
        long frameStartUs = frame.getPresentationTimeUs();
        long frameEndUs = frame.getEndPositionUs();
        if (frameEndUs <= anchorStartUs || frameStartUs >= anchorEndUs) return filledCount;

        int first = targetIndexAtOrAfter(Math.max(anchorStartUs, frameStartUs));
        int lastExclusive = targetIndexAtOrAfter(Math.min(anchorEndUs, frameEndUs));
        first = Math.max(0, Math.min(mono.length, first));
        lastExclusive = Math.max(first, Math.min(mono.length, lastExclusive));
        short[] samples = frame.getSamples();
        int channels = frame.getChannelCount();
        int sourceFrames = samples.length / channels;
        for (int index = first; index < lastExclusive; index++) {
            if (filled.get(index)) continue;
            long sampleUs = anchorStartUs + index * 1_000_000L / TARGET_RATE;
            double source = (sampleUs - frameStartUs)
                    * (frame.getSampleRateHz() / 1_000_000.0);
            if (source < 0.0 || source >= sourceFrames) continue;
            int left = Math.min(sourceFrames - 1, (int) Math.floor(source));
            int right = Math.min(sourceFrames - 1, left + 1);
            double fraction = source - left;
            double leftMono = downmix(samples, left * channels, channels);
            double rightMono = downmix(samples, right * channels, channels);
            mono[index] = clamp(Math.round(leftMono * (1.0 - fraction) + rightMono * fraction));
            filled.set(index);
            filledCount++;
        }
        return filledCount;
    }

    public synchronized int getFilledCount() { return filledCount; }

    public int getRequiredCount() { return mono.length; }

    public synchronized long getCoveredDurationMs() {
        return Math.min(request.getAnchorDurationMs(),
                filledCount * 1000L / TARGET_RATE);
    }

    public synchronized boolean isComplete() {
        return mono.length - filledCount <= MAX_MISSING_SAMPLES
                && longestMissingRun() <= MAX_MISSING_SAMPLES;
    }

    /** 丢弃断点前的全部采样，禁止跨时间线拼接指纹。 */
    public synchronized void reset() {
        java.util.Arrays.fill(mono, (short) 0);
        filled.clear();
        filledCount = 0;
    }

    /** 完成有界缺口修复并生成规则；区分度不足由核心规则校验拒绝。 */
    public synchronized FingerprintRuleDraft finish() {
        int missing = mono.length - filledCount;
        if (missing > MAX_MISSING_SAMPLES || longestMissingRun() > MAX_MISSING_SAMPLES) {
            throw new IllegalStateException("音频时间轴未完整覆盖指纹锚点");
        }
        fillSmallGaps();
        AdRuleSet format = AdRuleSet.empty();
        List<FingerprintVariant> variants = SpectralFingerprint.extractVariants(
                mono, TARGET_RATE, 1, format);
        List<FingerprintSequence> sequences = new ArrayList<>(variants.size());
        for (FingerprintVariant variant : variants) {
            sequences.add(new FingerprintSequence(variant.getOffsetMs(), variant.getHashes()));
        }
        AdRule rule = new AdRule(request.getRuleId(),
                request.getAdEndMs() - request.getAdStartMs(),
                request.getAnchorOffsetMs(), request.getAnchorDurationMs(), variants);
        // 核心集合校验四相位、长度、区分度和自动跳过安全约束。
        new AdRuleSet(1L, AdRuleSet.SAMPLE_RATE, AdRuleSet.WINDOW_MS,
                AdRuleSet.HOP_MS, AdRuleSet.BAND_COUNT,
                java.util.Collections.singletonList(rule));
        return new FingerprintRuleDraft(rule.getId(), rule.getDurationMs(),
                rule.getAnchorOffsetMs(), rule.getAnchorDurationMs(), sequences,
                request.getMedia().getUrl(), request.getAdStartMs());
    }

    private int targetIndexAtOrAfter(long positionUs) {
        long relativeUs = Math.max(0L, positionUs - anchorStartUs);
        long numerator = relativeUs * TARGET_RATE;
        return (int) Math.min(mono.length,
                (numerator + 1_000_000L - 1L) / 1_000_000L);
    }

    private int longestMissingRun() {
        int longest = 0;
        int cursor = 0;
        while (cursor < mono.length) {
            int start = filled.nextClearBit(cursor);
            if (start >= mono.length) break;
            int end = filled.nextSetBit(start);
            if (end < 0) end = mono.length;
            longest = Math.max(longest, end - start);
            cursor = end;
        }
        return longest;
    }

    private void fillSmallGaps() {
        int cursor = 0;
        while (cursor < mono.length) {
            int start = filled.nextClearBit(cursor);
            if (start >= mono.length) break;
            int end = filled.nextSetBit(start);
            if (end < 0) end = mono.length;
            short left = start > 0 ? mono[start - 1] : (end < mono.length ? mono[end] : 0);
            short right = end < mono.length ? mono[end] : left;
            int width = end - start;
            for (int index = start; index < end; index++) {
                double fraction = (index - start + 1.0) / (width + 1.0);
                mono[index] = clamp(Math.round(left * (1.0 - fraction) + right * fraction));
            }
            cursor = end;
        }
        filled.set(0, mono.length);
        filledCount = mono.length;
    }

    private static double downmix(short[] samples, int offset, int channels) {
        long sum = 0L;
        for (int channel = 0; channel < channels; channel++) sum += samples[offset + channel];
        return sum / (double) channels;
    }

    private static short clamp(long value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private static long multiplyExact(long value, long factor) {
        if (value > Long.MAX_VALUE / factor) throw new IllegalArgumentException("采集时间超出范围");
        return value * factor;
    }
}
