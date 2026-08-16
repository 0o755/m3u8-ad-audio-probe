/* 指纹采集请求固定媒体、广告范围与受控锚点窗口。 */
package io.github.fongmi.adaudio.probe.tools;

import io.github.fongmi.adaudio.probe.ProbeMedia;

/** 一次广告音频指纹采集的不可变请求。 */
public final class FingerprintCaptureRequest {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final ProbeMedia media;
    private final String ruleId;
    private final long adStartMs;
    private final long adEndMs;
    private final long anchorOffsetMs;
    private final long anchorDurationMs;

    private FingerprintCaptureRequest(Builder builder) {
        this.media = builder.media;
        this.ruleId = builder.ruleId;
        this.adStartMs = builder.adStartMs;
        this.adEndMs = builder.adEndMs;
        this.anchorOffsetMs = builder.anchorOffsetMs;
        this.anchorDurationMs = builder.anchorDurationMs;
    }

    /**
     * 创建请求构建器，默认锚点从广告开头起取最多 5 秒。
     *
     * @param media 普通 HLS/MP4 点播媒体
     * @param ruleId rules-v1 规则 ID
     * @param adStartMs 广告开始位置，单位毫秒
     * @param adEndMs 广告结束位置，单位毫秒
     * @return 已完成基础校验的构建器
     */
    public static Builder builder(ProbeMedia media, String ruleId,
                                  long adStartMs, long adEndMs) {
        return new Builder(media, ruleId, adStartMs, adEndMs);
    }

    /** @return 点播媒体请求 */
    public ProbeMedia getMedia() { return media; }

    /** @return 规则 ID */
    public String getRuleId() { return ruleId; }

    /** @return 广告开始位置，单位毫秒 */
    public long getAdStartMs() { return adStartMs; }

    /** @return 广告结束位置，单位毫秒 */
    public long getAdEndMs() { return adEndMs; }

    /** @return 锚点相对广告起点的偏移，单位毫秒 */
    public long getAnchorOffsetMs() { return anchorOffsetMs; }

    /** @return 锚点采集时长，单位毫秒 */
    public long getAnchorDurationMs() { return anchorDurationMs; }

    /** 指纹采集请求构建器。 */
    public static final class Builder {
        private final ProbeMedia media;
        private final String ruleId;
        private final long adStartMs;
        private final long adEndMs;
        private long anchorOffsetMs;
        private long anchorDurationMs;

        private Builder(ProbeMedia media, String ruleId, long adStartMs, long adEndMs) {
            if (media == null) throw new IllegalArgumentException("媒体请求不能为空");
            if (ruleId == null || !ruleId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("广告规则 ID 无效");
            }
            if (adStartMs < 0L || adEndMs <= adStartMs || adEndMs > MAX_SAFE_INTEGER) {
                throw new IllegalArgumentException("广告时间范围无效");
            }
            long durationMs = adEndMs - adStartMs;
            if (durationMs < 2000L || durationMs > 600_000L) {
                throw new IllegalArgumentException("可采集广告时长必须为 2 秒到 10 分钟");
            }
            this.media = media;
            this.ruleId = ruleId;
            this.adStartMs = adStartMs;
            this.adEndMs = adEndMs;
            this.anchorOffsetMs = 0L;
            this.anchorDurationMs = Math.min(5000L, durationMs);
        }

        /**
         * 自定义广告内的锚点范围。
         *
         * @param offsetMs 相对广告起点的偏移
         * @param durationMs 2 到 5 秒的锚点时长
         * @return 当前构建器
         */
        public Builder setAnchor(long offsetMs, long durationMs) {
            this.anchorOffsetMs = offsetMs;
            this.anchorDurationMs = durationMs;
            return this;
        }

        /** @return 经过完整边界校验的不可变请求 */
        public FingerprintCaptureRequest build() {
            long adDurationMs = adEndMs - adStartMs;
            if (anchorOffsetMs < 0L || anchorDurationMs < 2000L || anchorDurationMs > 5000L
                    || anchorOffsetMs > adDurationMs - anchorDurationMs) {
                throw new IllegalArgumentException("广告锚点范围无效");
            }
            return new FingerprintCaptureRequest(this);
        }
    }
}
