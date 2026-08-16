/* 生成带多相位变体的频谱序列，降低采集与播放窗口起点不一致造成的漏报。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SpectralFingerprint {
    private static final int PHASE_VARIANT_COUNT = 4;
    private static final ThreadLocal<Workspace> WORKSPACE = new ThreadLocal<Workspace>() {
        @Override
        protected Workspace initialValue() {
            return new Workspace();
        }
    };

    private SpectralFingerprint() {
    }

    public static List<String> extract(PcmChunk chunk, AdRuleSet format) {
        if (chunk == null || format == null) throw new IllegalArgumentException("音频和规则格式不能为空");
        return extract(chunk.getSamples(), chunk.getSampleRate(), chunk.getChannels(), format);
    }

    public static List<String> extract(short[] pcm, int sampleRate, int channels, AdRuleSet format) {
        List<FingerprintVariant> variants = extractVariants(pcm, sampleRate, channels, format);
        return variants.isEmpty() ? Collections.<String>emptyList() : variants.get(0).getHashes();
    }

    public static List<FingerprintVariant> extractVariants(
            short[] pcm, int sampleRate, int channels, AdRuleSet format) {
        if (format == null) throw new IllegalArgumentException("规则格式不能为空");
        short[] mono = toTargetMono(pcm, sampleRate, channels, format.getSampleRate());
        int windowSamples = millisecondsToSamples(format.getWindowMs(), format.getSampleRate());
        int hopSamples = millisecondsToSamples(format.getHopMs(), format.getSampleRate());
        int phaseStep = Math.max(1, hopSamples / PHASE_VARIANT_COUNT);
        List<FingerprintVariant> variants = new ArrayList<>();
        for (int phase = 0; phase < PHASE_VARIANT_COUNT; phase++) {
            int sampleOffset = phase * phaseStep;
            List<String> sequence = extractMono(mono, sampleOffset, windowSamples, hopSamples,
                    format.getSampleRate(), format.getBandCount());
            if (sequence.size() >= 4) {
                int offsetMs = (int) Math.round(sampleOffset * 1000.0 / format.getSampleRate());
                variants.add(new FingerprintVariant(offsetMs, sequence));
            }
        }
        return Collections.unmodifiableList(variants);
    }

    private static List<String> extractMono(short[] mono, int startOffset, int windowSamples,
                                            int hopSamples, int sampleRate, int bandCount) {
        List<String> output = new ArrayList<>();
        for (int offset = startOffset; offset + windowSamples <= mono.length; offset += hopSamples) {
            output.add(hashWindow(mono, offset, windowSamples, sampleRate, bandCount));
        }
        return output;
    }

    static short[] toTargetMono(short[] pcm, int sampleRate, int channels, int targetRate) {
        if (pcm == null || pcm.length == 0) return new short[0];
        if (sampleRate <= 0 || channels <= 0 || targetRate <= 0) throw new IllegalArgumentException("PCM 格式无效");
        int frames = pcm.length / channels;
        short[] mono = new short[frames];
        for (int frame = 0; frame < frames; frame++) {
            long sum = 0L;
            int base = frame * channels;
            for (int channel = 0; channel < channels; channel++) sum += pcm[base + channel];
            mono[frame] = (short) (sum / channels);
        }
        if (sampleRate == targetRate) return mono;

        int outputLength = Math.max(1, (int) Math.round(mono.length * (targetRate / (double) sampleRate)));
        short[] output = new short[outputLength];
        double step = sampleRate / (double) targetRate;
        for (int i = 0; i < outputLength; i++) {
            double source = i * step;
            int left = Math.min(mono.length - 1, (int) source);
            int right = Math.min(mono.length - 1, left + 1);
            double fraction = source - left;
            output[i] = (short) Math.round(mono[left] * (1.0 - fraction) + mono[right] * fraction);
        }
        return output;
    }

    static String hashWindow(short[] samples, int offset, int length, int sampleRate, int bandCount) {
        return String.format("%08x", hashWindowValue(samples, offset, length, sampleRate, bandCount));
    }

    static int hashWindowValue(short[] samples, int offset, int length, int sampleRate, int bandCount) {
        int fftSize = nextPowerOfTwo(length);
        Workspace workspace = WORKSPACE.get();
        workspace.prepare(fftSize, bandCount);
        double[] real = workspace.real;
        double[] imaginary = workspace.imaginary;
        double[] bands = workspace.bands;
        double energy = 0.0;
        for (int i = 0; i < length; i++) {
            double value = samples[offset + i] / 32768.0;
            energy += value * value;
            double window = 0.5 - 0.5 * Math.cos((2.0 * Math.PI * i) / Math.max(1, length - 1));
            real[i] = value * window;
        }
        if (energy < 1.0e-7) return 0;

        fft(real, imaginary);
        double minFrequency = 180.0;
        double maxFrequency = Math.min(6200.0, sampleRate / 2.0 - 1.0);
        for (int band = 0; band < bandCount; band++) {
            double startRatio = band / (double) bandCount;
            double endRatio = (band + 1) / (double) bandCount;
            double startFrequency = minFrequency * Math.pow(maxFrequency / minFrequency, startRatio);
            double endFrequency = minFrequency * Math.pow(maxFrequency / minFrequency, endRatio);
            int startBin = Math.max(1, (int) Math.floor(startFrequency * fftSize / sampleRate));
            int endBin = Math.min(fftSize / 2,
                    Math.max(startBin + 1, (int) Math.ceil(endFrequency * fftSize / sampleRate)));
            double sum = 0.0;
            for (int bin = startBin; bin < endBin; bin++) {
                sum += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
            }
            bands[band] = Math.log1p(sum / Math.max(1, endBin - startBin));
        }

        double mean = 0.0;
        for (double band : bands) mean += band;
        mean /= bands.length;
        int bits = 0;
        for (int band = 0; band < bandCount && band < 16; band++) {
            if (bands[band] >= mean) bits |= (1 << band);
            if (bands[band] >= bands[(band + 1) % bandCount]) bits |= (1 << (band + 16));
        }
        return bits;
    }

    public static int hammingDistance(String left, String right) {
        try {
            int a = (int) Long.parseUnsignedLong(left, 16);
            int b = (int) Long.parseUnsignedLong(right, 16);
            return Integer.bitCount(a ^ b);
        } catch (RuntimeException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    static int millisecondsToSamples(int milliseconds, int sampleRate) {
        return Math.max(1, (int) Math.round(milliseconds * sampleRate / 1000.0));
    }

    private static int nextPowerOfTwo(int value) {
        int result = 1;
        while (result < value && result < (1 << 20)) result <<= 1;
        return result;
    }

    private static void fft(double[] real, double[] imaginary) {
        int size = real.length;
        for (int i = 1, j = 0; i < size; i++) {
            int bit = size >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double temp = real[i]; real[i] = real[j]; real[j] = temp;
                temp = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = temp;
            }
        }
        for (int length = 2; length <= size; length <<= 1) {
            double angle = -2.0 * Math.PI / length;
            double cosine = Math.cos(angle);
            double sine = Math.sin(angle);
            for (int start = 0; start < size; start += length) {
                double wr = 1.0;
                double wi = 0.0;
                for (int i = 0; i < length / 2; i++) {
                    int even = start + i;
                    int odd = even + length / 2;
                    double tr = wr * real[odd] - wi * imaginary[odd];
                    double ti = wr * imaginary[odd] + wi * real[odd];
                    real[odd] = real[even] - tr;
                    imaginary[odd] = imaginary[even] - ti;
                    real[even] += tr;
                    imaginary[even] += ti;
                    double nextWr = wr * cosine - wi * sine;
                    wi = wr * sine + wi * cosine;
                    wr = nextWr;
                }
            }
        }
    }

    /** 每个匹配线程复用固定尺寸数组，避免播放期间每个窗口产生大型临时对象。 */
    private static final class Workspace {
        double[] real = new double[0];
        double[] imaginary = new double[0];
        double[] bands = new double[0];

        void prepare(int fftSize, int bandCount) {
            if (real.length != fftSize) {
                real = new double[fftSize];
                imaginary = new double[fftSize];
            } else {
                Arrays.fill(real, 0.0);
                Arrays.fill(imaginary, 0.0);
            }
            if (bands.length != bandCount) bands = new double[bandCount];
            else Arrays.fill(bands, 0.0);
        }
    }
}
