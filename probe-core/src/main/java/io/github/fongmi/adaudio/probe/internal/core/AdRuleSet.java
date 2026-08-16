/* 定义与频谱算法参数绑定的不可变规则集合。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 不可变规则集合，解析失败的规则不应被调用方直接塞入匹配器。 */
public final class AdRuleSet {
    public static final String FORMAT_ID = "ad-audio-probe-rules";
    public static final int SCHEMA_VERSION = 1;
    public static final String ALGORITHM_ID = "spectral-sequence-v1";
    public static final int SAMPLE_RATE = 16000;
    public static final int WINDOW_MS = 512;
    public static final int HOP_MS = 256;
    public static final int BAND_COUNT = 16;
    public static final int MIN_CONFIRMATION_FRAMES = 4;
    public static final int MAX_CONFIRMATION_FRAMES = 8;
    public static final int MAX_RULES = 1024;
    public static final int MAX_TOTAL_HASHES = 65536;
    public static final long MAX_REVISION = 9_007_199_254_740_991L;

    private final long revision;
    private final int sampleRate;
    private final int windowMs;
    private final int hopMs;
    private final int bandCount;
    private final List<AdRule> rules;

    public AdRuleSet(long revision, int sampleRate, int windowMs, int hopMs,
                     int bandCount, List<AdRule> rules) {
        if (revision < 0 || revision > MAX_REVISION) {
            throw new IllegalArgumentException("规则修订号超出安全整数范围");
        }
        if (sampleRate != SAMPLE_RATE || windowMs != WINDOW_MS
                || hopMs != HOP_MS || bandCount != BAND_COUNT) {
            throw new IllegalArgumentException("频谱算法参数不兼容");
        }
        List<AdRule> safeRules = rules == null
                ? Collections.<AdRule>emptyList() : new ArrayList<>(rules);
        validateRules(safeRules);
        this.revision = revision;
        this.sampleRate = sampleRate;
        this.windowMs = windowMs;
        this.hopMs = hopMs;
        this.bandCount = bandCount;
        this.rules = Collections.unmodifiableList(safeRules);
    }

    public static AdRuleSet empty() {
        return new AdRuleSet(0L, SAMPLE_RATE, WINDOW_MS, HOP_MS, BAND_COUNT,
                Collections.<AdRule>emptyList());
    }

    public long getRevision() {
        return revision;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getWindowMs() {
        return windowMs;
    }

    public int getHopMs() {
        return hopMs;
    }

    public int getBandCount() {
        return bandCount;
    }

    public List<AdRule> getRules() {
        return rules;
    }

    private static void validateRules(List<AdRule> rules) {
        if (rules.size() > MAX_RULES) throw new IllegalArgumentException("规则数量超过上限");
        java.util.Set<String> ids = new java.util.HashSet<>();
        PrefixNode primaryPrefixes = new PrefixNode();
        int totalHashes = 0;
        for (AdRule rule : rules) {
            if (rule == null) throw new IllegalArgumentException("规则不能为空");
            if (!ids.add(rule.getId())) throw new IllegalArgumentException("广告规则 ID 重复");
            java.util.Set<Integer> offsets = new java.util.HashSet<>();
            FingerprintVariant primary = null;
            for (FingerprintVariant variant : rule.getFingerprints()) {
                if (variant == null || variant.getOffsetMs() < 0 || variant.getOffsetMs() >= HOP_MS
                        || !offsets.add(variant.getOffsetMs())) {
                    throw new IllegalArgumentException("指纹相位偏移无效或重复");
                }
                if (variant.getOffsetMs() == 0) primary = variant;
                int expected = expectedFrames(rule.getAnchorDurationMs(), variant.getOffsetMs());
                if (variant.getHashes().size() != expected) {
                    throw new IllegalArgumentException("指纹长度与锚点时长不一致");
                }
                if (requiredConfirmationFrames(variant.getHashes()) < 0) {
                    throw new IllegalArgumentException("指纹开头区分度不足，自动跳过风险过高");
                }
                totalHashes += variant.getHashes().size();
                if (totalHashes > MAX_TOTAL_HASHES) {
                    throw new IllegalArgumentException("规则指纹总量超过上限");
                }
            }
            if (primary == null) throw new IllegalArgumentException("规则缺少零偏移主指纹");
            validatePrimaryPrefix(primaryPrefixes, primary, rule);
        }
    }

    /** 短指纹与长指纹存在包含关系时，也必须给出相同的结束偏移。 */
    private static void validatePrimaryPrefix(PrefixNode root, FingerprintVariant variant,
                                              AdRule rule) {
        int limit = Math.min(MAX_CONFIRMATION_FRAMES, variant.getHashes().size());
        long endpoint = endpointOffset(rule);
        java.util.List<PrefixNode> path = new java.util.ArrayList<>(limit);
        PrefixNode node = root;
        for (int i = 0; i < limit; i++) {
            if (node.terminalEndpoint != null && node.terminalEndpoint != endpoint) {
                throw prefixConflict(node.terminalRuleId, rule.getId());
            }
            String hash = variant.getHashes().get(i);
            PrefixNode child = node.children.get(hash);
            if (child == null) {
                child = new PrefixNode();
                node.children.put(hash, child);
            }
            node = child;
            path.add(node);
        }
        if (node.subtreeEndpoint != null
                && (node.subtreeMixed || node.subtreeEndpoint != endpoint)) {
            String previous = node.subtreeEndpoint != endpoint
                    ? node.subtreeRuleId : node.mixedRuleId;
            throw prefixConflict(previous, rule.getId());
        }
        node.terminalEndpoint = endpoint;
        node.terminalRuleId = rule.getId();
        for (PrefixNode item : path) item.record(endpoint, rule.getId());
    }

    private static IllegalArgumentException prefixConflict(String left, String right) {
        return new IllegalArgumentException("相同开头指纹存在不同结束位置："
                + left + " / " + right);
    }

    private static long endpointOffset(AdRule rule) {
        return rule.getDurationMs() - rule.getAnchorOffsetMs();
    }

    private static int expectedFrames(long anchorDurationMs, int offsetMs) {
        long available = anchorDurationMs - offsetMs - WINDOW_MS;
        return available < 0 ? 0 : (int) (available / HOP_MS) + 1;
    }

    /** 在最多八帧内找到与首帧显著不同的频谱，供匹配器自适应延长确认。 */
    static int requiredConfirmationFrames(List<String> hashes) {
        int limit = Math.min(MAX_CONFIRMATION_FRAMES, hashes.size());
        int first = (int) Long.parseUnsignedLong(hashes.get(0), 16);
        for (int i = 1; i < limit; i++) {
            int current = (int) Long.parseUnsignedLong(hashes.get(i), 16);
            if (Integer.bitCount(first ^ current) > 5) {
                return Math.max(MIN_CONFIRMATION_FRAMES, i + 1);
            }
        }
        return -1;
    }

    private static final class PrefixNode {
        final java.util.Map<String, PrefixNode> children = new java.util.HashMap<>();
        Long terminalEndpoint;
        String terminalRuleId;
        Long subtreeEndpoint;
        String subtreeRuleId;
        String mixedRuleId;
        boolean subtreeMixed;

        void record(long endpoint, String ruleId) {
            if (subtreeEndpoint == null) {
                subtreeEndpoint = endpoint;
                subtreeRuleId = ruleId;
            } else if (subtreeEndpoint != endpoint) {
                subtreeMixed = true;
                if (mixedRuleId == null) mixedRuleId = ruleId;
            }
        }
    }
}
