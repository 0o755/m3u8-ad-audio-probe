/* 定义单条全局广告规则及带偏移的多相位指纹。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** 单条全局广告规则，只包含频谱序列和时长，不绑定任何来源。 */
public final class AdRule {
    public static final long MIN_DURATION_MS = 1000L;
    public static final long MAX_DURATION_MS = 10L * 60L * 1000L;
    public static final long MIN_ANCHOR_DURATION_MS = 2000L;
    public static final long MAX_ANCHOR_DURATION_MS = 5000L;
    public static final int MAX_ID_LENGTH = 64;
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

    private final String id;
    private final long durationMs;
    private final long anchorOffsetMs;
    private final long anchorDurationMs;
    private final List<FingerprintVariant> fingerprints;

    public AdRule(String id, long durationMs, long anchorOffsetMs, long anchorDurationMs,
                  List<FingerprintVariant> fingerprints) {
        String normalizedId = id == null ? "" : id;
        if (normalizedId.length() > MAX_ID_LENGTH
                || !ID_PATTERN.matcher(normalizedId).matches()) {
            throw new IllegalArgumentException("广告规则 ID 无效");
        }
        if (durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("广告时长超出允许范围");
        }
        if (anchorOffsetMs < 0 || anchorDurationMs < MIN_ANCHOR_DURATION_MS
                || anchorDurationMs > MAX_ANCHOR_DURATION_MS
                || anchorOffsetMs > durationMs - anchorDurationMs) {
            throw new IllegalArgumentException("广告锚点范围无效");
        }
        if (fingerprints == null || fingerprints.isEmpty() || fingerprints.size() > 4) {
            throw new IllegalArgumentException("广告指纹相位数量无效");
        }
        this.id = normalizedId;
        this.durationMs = durationMs;
        this.anchorOffsetMs = anchorOffsetMs;
        this.anchorDurationMs = anchorDurationMs;
        this.fingerprints = Collections.unmodifiableList(new ArrayList<>(fingerprints));
    }

    public String getId() {
        return id;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getAnchorOffsetMs() {
        return anchorOffsetMs;
    }

    public long getAnchorDurationMs() {
        return anchorDurationMs;
    }

    public List<FingerprintVariant> getFingerprints() {
        return fingerprints;
    }
}
