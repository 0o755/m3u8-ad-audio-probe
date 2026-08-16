/* 定义带相位偏移的单条频谱序列，匹配器据此还原准确广告起点。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public final class FingerprintVariant {
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{8}$");
    private static final int MAX_HASHES = 64;

    private final int offsetMs;
    private final List<String> hashes;

    public FingerprintVariant(int offsetMs, List<String> hashes) {
        if (offsetMs < 0) throw new IllegalArgumentException("指纹相位偏移不能为负数");
        if (hashes == null || hashes.size() < 4 || hashes.size() > MAX_HASHES) {
            throw new IllegalArgumentException("指纹序列长度无效");
        }
        List<String> normalized = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            String value = hash == null ? "" : hash.trim().toLowerCase();
            if (!HASH_PATTERN.matcher(value).matches()) {
                throw new IllegalArgumentException("频谱哈希必须是 8 位十六进制");
            }
            normalized.add(value);
        }
        this.offsetMs = offsetMs;
        this.hashes = Collections.unmodifiableList(normalized);
    }

    public int getOffsetMs() {
        return offsetMs;
    }

    public List<String> getHashes() {
        return hashes;
    }
}
