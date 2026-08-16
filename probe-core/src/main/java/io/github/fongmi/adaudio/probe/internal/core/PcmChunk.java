/* 定义宿主送入匹配器的带媒体时间 PCM 数据块。 */
package io.github.fongmi.adaudio.probe.internal.core;

/** 一段带媒体时间的 PCM 数据；SDK 不持有调用方数组，调用方可在 feed 返回后复用数组。 */
public final class PcmChunk {
    private final short[] samples;
    private final int sampleRate;
    private final int channels;
    private final long startTimeMs;

    public PcmChunk(short[] samples, int sampleRate, int channels, long startTimeMs) {
        this.samples = samples;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.startTimeMs = startTimeMs;
    }

    public short[] getSamples() {
        return samples;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public long getStartTimeMs() {
        return startTimeMs;
    }
}
