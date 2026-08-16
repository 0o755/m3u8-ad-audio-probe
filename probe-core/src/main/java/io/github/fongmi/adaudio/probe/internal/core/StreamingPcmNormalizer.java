/* 连续下混并重采样 PCM，跨播放器音频块保留插值相位，避免长期媒体时间漂移。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.Arrays;

final class StreamingPcmNormalizer {
    private final int targetSampleRate;
    private int inputSampleRate;
    private int inputChannels;
    private long totalInputFrames;
    private double nextOutputFrame;
    private short previousMonoSample;
    private boolean hasPreviousSample;

    StreamingPcmNormalizer(int targetSampleRate) {
        this.targetSampleRate = targetSampleRate;
    }

    boolean isCompatible(int sampleRate, int channels) {
        return inputSampleRate == 0
                || (inputSampleRate == sampleRate && inputChannels == channels);
    }

    short[] normalize(short[] pcm, int sampleRate, int channels) {
        if (pcm == null || sampleRate <= 0 || channels <= 0) {
            throw new IllegalArgumentException("PCM 格式无效");
        }
        if (!isCompatible(sampleRate, channels)) reset();
        if (inputSampleRate == 0) {
            inputSampleRate = sampleRate;
            inputChannels = channels;
        }

        int frameCount = pcm.length / channels;
        if (frameCount == 0) return new short[0];
        short[] mono = downmix(pcm, frameCount, channels);
        if (sampleRate == targetSampleRate) {
            totalInputFrames += frameCount;
            previousMonoSample = mono[mono.length - 1];
            hasPreviousSample = true;
            nextOutputFrame = totalInputFrames;
            return mono;
        }

        long chunkStart = totalInputFrames;
        long chunkEnd = chunkStart + frameCount - 1L;
        int estimate = Math.max(1,
                (int) Math.ceil((frameCount + 1) * targetSampleRate / (double) sampleRate) + 2);
        short[] output = new short[estimate];
        int outputCount = 0;
        double sourceStep = sampleRate / (double) targetSampleRate;
        while (Math.ceil(nextOutputFrame - 1.0e-9) <= chunkEnd) {
            long leftIndex = (long) Math.floor(nextOutputFrame);
            long rightIndex = (long) Math.ceil(nextOutputFrame - 1.0e-9);
            short left = sampleAt(leftIndex, chunkStart, mono);
            short right = sampleAt(rightIndex, chunkStart, mono);
            double fraction = nextOutputFrame - leftIndex;
            if (outputCount == output.length) output = Arrays.copyOf(output, output.length * 2);
            output[outputCount++] = (short) Math.round(left * (1.0 - fraction) + right * fraction);
            nextOutputFrame += sourceStep;
        }

        totalInputFrames += frameCount;
        previousMonoSample = mono[mono.length - 1];
        hasPreviousSample = true;
        return outputCount == output.length ? output : Arrays.copyOf(output, outputCount);
    }

    void reset() {
        inputSampleRate = 0;
        inputChannels = 0;
        totalInputFrames = 0L;
        nextOutputFrame = 0.0;
        previousMonoSample = 0;
        hasPreviousSample = false;
    }

    private short sampleAt(long globalIndex, long chunkStart, short[] mono) {
        if (globalIndex == chunkStart - 1L && hasPreviousSample) return previousMonoSample;
        int index = (int) (globalIndex - chunkStart);
        if (index < 0 || index >= mono.length) throw new IllegalStateException("重采样边界无效");
        return mono[index];
    }

    private static short[] downmix(short[] pcm, int frameCount, int channels) {
        short[] mono = new short[frameCount];
        for (int frame = 0; frame < frameCount; frame++) {
            long sum = 0L;
            int base = frame * channels;
            for (int channel = 0; channel < channels; channel++) sum += pcm[base + channel];
            mono[frame] = (short) (sum / channels);
        }
        return mono;
    }
}
