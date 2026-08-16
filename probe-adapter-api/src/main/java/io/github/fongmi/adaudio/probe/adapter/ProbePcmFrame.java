/* PCM 帧以借用数组承载真实 PTS，避免高速解码路径重复复制。 */
package io.github.fongmi.adaudio.probe.adapter;

/**
 * 一块交错 PCM16 音频及其媒体起始 PTS。
 *
 * <p>样本数组采用同步借用语义：监听器返回前不得修改，返回后所有权仍属于适配器。</p>
 */
public final class ProbePcmFrame {
    private static final int MAX_SAMPLES = 4 * 1024 * 1024;
    private static final long MAX_DURATION_US = 2_000_000L;

    private final short[] samples;
    private final int sampleRateHz;
    private final int channelCount;
    private final long presentationTimeUs;

    public ProbePcmFrame(short[] samples, int sampleRateHz, int channelCount,
                         long presentationTimeUs) {
        if (samples == null || samples.length == 0 || samples.length > MAX_SAMPLES) {
            throw new IllegalArgumentException("PCM 样本数量无效");
        }
        if (sampleRateHz < 8000 || sampleRateHz > 384000) {
            throw new IllegalArgumentException("PCM 采样率无效");
        }
        if (channelCount < 1 || channelCount > 16 || samples.length % channelCount != 0) {
            throw new IllegalArgumentException("PCM 声道或交错帧无效");
        }
        long frameCount = samples.length / channelCount;
        if (frameCount * 1_000_000L / sampleRateHz > MAX_DURATION_US) {
            throw new IllegalArgumentException("单块 PCM 时长不能超过 2 秒");
        }
        if (presentationTimeUs < 0L) throw new IllegalArgumentException("PCM PTS 不能为负数");
        this.samples = samples;
        this.sampleRateHz = sampleRateHz;
        this.channelCount = channelCount;
        this.presentationTimeUs = presentationTimeUs;
    }

    /** 返回借用的交错 PCM16 数组；调用方不得修改或保留。 */
    public short[] getSamples() {
        return samples;
    }

    public int getSampleRateHz() {
        return sampleRateHz;
    }

    public int getChannelCount() {
        return channelCount;
    }

    /** 返回首个采样帧的媒体时间，单位微秒。 */
    public long getPresentationTimeUs() {
        return presentationTimeUs;
    }

    /** 返回本块音频结束位置，单位微秒；溢出时饱和为 Long.MAX_VALUE。 */
    public long getEndPositionUs() {
        long frameCount = samples.length / channelCount;
        long durationUs = frameCount > Long.MAX_VALUE / 1_000_000L
                ? Long.MAX_VALUE : frameCount * 1_000_000L / sampleRateHz;
        return Long.MAX_VALUE - presentationTimeUs < durationUs
                ? Long.MAX_VALUE : presentationTimeUs + durationUs;
    }
}
