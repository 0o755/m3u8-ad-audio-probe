/* 采集进度以媒体覆盖范围表示，不泄漏音频缓冲细节。 */
package io.github.fongmi.adaudio.probe.tools;

/** 一次指纹采集的不可变进度快照。 */
public final class FingerprintCaptureProgress {
    private final long sessionId;
    private final long capturedDurationMs;
    private final long requiredDurationMs;

    /**
     * 创建采集进度快照。
     *
     * @param sessionId 所属正会话 ID
     * @param capturedDurationMs 已覆盖时长
     * @param requiredDurationMs 目标锚点时长
     */
    public FingerprintCaptureProgress(long sessionId, long capturedDurationMs,
                                      long requiredDurationMs) {
        if (sessionId <= 0L || capturedDurationMs < 0L || requiredDurationMs <= 0L
                || capturedDurationMs > requiredDurationMs) {
            throw new IllegalArgumentException("采集进度无效");
        }
        this.sessionId = sessionId;
        this.capturedDurationMs = capturedDurationMs;
        this.requiredDurationMs = requiredDurationMs;
    }

    /** @return 所属会话 ID */
    public long getSessionId() { return sessionId; }

    /** @return 已覆盖时长，单位毫秒 */
    public long getCapturedDurationMs() { return capturedDurationMs; }

    /** @return 目标锚点时长，单位毫秒 */
    public long getRequiredDurationMs() { return requiredDurationMs; }

    /** @return 0 到 100 的整数进度 */
    public int getPercent() {
        return (int) Math.min(100L, capturedDurationMs * 100L / requiredDurationMs);
    }
}
