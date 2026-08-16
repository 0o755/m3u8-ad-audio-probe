/* 规则草稿完整承载单条 rules-v1 规则及可选测试来源。 */
package io.github.fongmi.adaudio.probe.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 可直接写入 rules-v1 {@code rules} 数组的不可变规则草稿。 */
public final class FingerprintRuleDraft {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final String id;
    private final long durationMs;
    private final long anchorOffsetMs;
    private final long anchorDurationMs;
    private final List<FingerprintSequence> fingerprints;
    private final String testUrl;
    private final long testAdStartMs;

    /**
     * 创建一条经过 rules-v1 约束校验的规则草稿。
     *
     * @param id 1 到 64 位的小写规则 ID
     * @param durationMs 完整广告时长，单位毫秒
     * @param anchorOffsetMs 指纹锚点相对广告起点的偏移
     * @param anchorDurationMs 2 到 5 秒的指纹锚点时长
     * @param fingerprints 恰好包含 0/64/128/192 四个相位的指纹
     * @param testUrl 用于复测的 HTTP(S) 媒体地址
     * @param testAdStartMs 测试媒体中的广告起点
     */
    public FingerprintRuleDraft(String id, long durationMs, long anchorOffsetMs,
                                long anchorDurationMs,
                                List<FingerprintSequence> fingerprints,
                                String testUrl, long testAdStartMs) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("广告规则 ID 无效");
        }
        if (durationMs < 1000L || durationMs > 600_000L) {
            throw new IllegalArgumentException("广告时长超出允许范围");
        }
        if (anchorOffsetMs < 0L || anchorDurationMs < 2000L || anchorDurationMs > 5000L
                || anchorOffsetMs > durationMs - anchorDurationMs) {
            throw new IllegalArgumentException("广告锚点范围无效");
        }
        if (fingerprints == null || fingerprints.size() != 4) {
            throw new IllegalArgumentException("规则必须包含四个固定相位");
        }
        validateFingerprints(fingerprints, anchorDurationMs);
        validateTestUrl(testUrl);
        if (testAdStartMs < 0L || testAdStartMs > MAX_SAFE_INTEGER
                || durationMs > MAX_SAFE_INTEGER - testAdStartMs) {
            throw new IllegalArgumentException("测试来源或广告起点无效");
        }
        this.id = id;
        this.durationMs = durationMs;
        this.anchorOffsetMs = anchorOffsetMs;
        this.anchorDurationMs = anchorDurationMs;
        this.fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
        this.testUrl = testUrl;
        this.testAdStartMs = testAdStartMs;
    }

    /** @return 规则 ID */
    public String getId() { return id; }

    /** @return 完整广告时长，单位毫秒 */
    public long getDurationMs() { return durationMs; }

    /** @return 锚点相对广告起点的偏移，单位毫秒 */
    public long getAnchorOffsetMs() { return anchorOffsetMs; }

    /** @return 锚点采集时长，单位毫秒 */
    public long getAnchorDurationMs() { return anchorDurationMs; }

    /** @return 四条不可变固定相位指纹 */
    public List<FingerprintSequence> getFingerprints() { return fingerprints; }

    /** @return 用于复测的原始媒体 URL */
    public String getTestUrl() { return testUrl; }

    /** @return 测试媒体中的广告起点，单位毫秒 */
    public long getTestAdStartMs() { return testAdStartMs; }

    /**
     * 生成可嵌入 rules-v1 的单条规则 JSON。
     *
     * @return 严格 JSON 对象文本，不包含根节点
     */
    public String toRuleJson() {
        StringBuilder output = new StringBuilder(1024);
        output.append('{').append("\"id\":\"").append(escape(id)).append("\",")
                .append("\"durationMs\":").append(durationMs).append(',')
                .append("\"anchorOffsetMs\":").append(anchorOffsetMs).append(',')
                .append("\"anchorDurationMs\":").append(anchorDurationMs).append(',')
                .append("\"fingerprints\":[");
        for (int index = 0; index < fingerprints.size(); index++) {
            if (index > 0) output.append(',');
            FingerprintSequence sequence = fingerprints.get(index);
            output.append('{').append("\"phaseMs\":").append(sequence.getPhaseMs())
                    .append(',').append("\"hashes\":[");
            for (int hashIndex = 0; hashIndex < sequence.getHashes().size(); hashIndex++) {
                if (hashIndex > 0) output.append(',');
                output.append('\"').append(sequence.getHashes().get(hashIndex)).append('\"');
            }
            output.append("]}");
        }
        output.append("],\"test\":{\"url\":\"").append(escape(testUrl))
                .append("\",\"adStartMs\":").append(testAdStartMs).append("}}");
        return output.toString();
    }

    private static String escape(String value) {
        StringBuilder output = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) output.append(String.format(
                            java.util.Locale.US, "\\u%04x", (int) character));
                    else output.append(character);
            }
        }
        return output.toString();
    }

    private static void validateFingerprints(List<FingerprintSequence> fingerprints,
                                             long anchorDurationMs) {
        Set<Integer> phases = new HashSet<>();
        for (FingerprintSequence sequence : fingerprints) {
            if (sequence == null || !phases.add(sequence.getPhaseMs())) {
                throw new IllegalArgumentException("指纹相位不能为空或重复");
            }
            long available = anchorDurationMs - sequence.getPhaseMs() - 512L;
            int expected = available < 0L ? 0 : (int) (available / 256L) + 1;
            if (sequence.getHashes().size() != expected) {
                throw new IllegalArgumentException("指纹长度与锚点时长不一致");
            }
            if (!hasDistinctConfirmation(sequence.getHashes())) {
                throw new IllegalArgumentException("指纹开头区分度不足，不能安全自动跳过");
            }
        }
        if (!phases.contains(0) || !phases.contains(64)
                || !phases.contains(128) || !phases.contains(192)) {
            throw new IllegalArgumentException("规则缺少固定指纹相位");
        }
    }

    private static void validateTestUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192) {
            throw new IllegalArgumentException("测试 URL 长度无效");
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null || uri.getHost().isEmpty()
                    || !("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("测试 URL 必须是有主机的 HTTP(S) URL");
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("测试 URL 无效", error);
        }
    }

    private static boolean hasDistinctConfirmation(List<String> hashes) {
        int first = (int) Long.parseUnsignedLong(hashes.get(0), 16);
        int limit = Math.min(8, hashes.size());
        for (int index = 1; index < limit; index++) {
            int current = (int) Long.parseUnsignedLong(hashes.get(index), 16);
            if (Integer.bitCount(first ^ current) > 5) return true;
        }
        return false;
    }
}
