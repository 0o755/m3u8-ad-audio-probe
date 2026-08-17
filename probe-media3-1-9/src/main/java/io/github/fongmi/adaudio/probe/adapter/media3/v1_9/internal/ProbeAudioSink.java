/* 无声 AudioSink 消费解码 PCM 和真实 PTS，不创建 AudioTrack 或请求音频焦点。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;

import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@UnstableApi
final class ProbeAudioSink implements AudioSink {
    // 与 Media3 默认 AudioSink 一致，超过 200ms 才视为真实时间轴跳变。
    static final long MAX_PRESENTATION_TIME_DRIFT_US = 200_000L;
    // AAC 解码在 HLS 分片边界可能少交付一帧；仅桥接一个常见 AAC 帧量级的空洞。
    static final long MAX_PCM_BRIDGE_US = 60_000L;

    interface AheadListener {
        void onAheadLimitReached();
    }

    private final ProbePcmConsumer consumer;
    private final AheadListener aheadListener;
    private final AtomicLong hostPositionMs;
    private final long maxLookaheadMs;
    private final AtomicBoolean aheadNotified = new AtomicBoolean();
    private final AtomicBoolean acceptingData = new AtomicBoolean(true);
    private final AtomicBoolean vodTimelineConfirmed = new AtomicBoolean();
    private final AtomicLong timelineEpoch = new AtomicLong();
    private final Object deliveryLock = new Object();

    private Listener listener;
    private AudioAttributes audioAttributes = AudioAttributes.DEFAULT;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private int sampleRate;
    private int inputChannelCount;
    private int channelCount;
    private int[] channelMap;
    private int pcmEncoding;
    private long outputStreamOffsetUs;
    private long currentPositionUs = CURRENT_POSITION_NOT_SET;
    private long expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
    private short[] lastOutputSamples;
    private boolean ended;

    ProbeAudioSink(ProbePcmConsumer consumer, AheadListener aheadListener,
                   AtomicLong hostPositionMs, long maxLookaheadMs) {
        this.consumer = consumer;
        this.aheadListener = aheadListener;
        this.hostPositionMs = hostPositionMs;
        this.maxLookaheadMs = maxLookaheadMs;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean supportsFormat(Format format) {
        return format != null && "audio/raw".equals(format.sampleMimeType)
                && (format.pcmEncoding == C.ENCODING_PCM_16BIT
                || format.pcmEncoding == C.ENCODING_PCM_FLOAT);
    }

    @Override
    public int getFormatSupport(Format format) {
        if (format == null || format.sampleMimeType == null
                || !format.sampleMimeType.startsWith("audio/")) {
            return SINK_FORMAT_UNSUPPORTED;
        }
        return supportsFormat(format)
                ? SINK_FORMAT_SUPPORTED_DIRECTLY : SINK_FORMAT_SUPPORTED_WITH_TRANSCODING;
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        return currentPositionUs;
    }

    @Override
    public void configure(Format inputFormat, int specifiedBufferSize,
                          int[] outputChannels) throws ConfigurationException {
        if (!supportsFormat(inputFormat) || inputFormat.sampleRate <= 0
                || inputFormat.channelCount <= 0) {
            throw new ConfigurationException("探针只接受 PCM16 或 PCM float 解码输出", inputFormat);
        }
        sampleRate = inputFormat.sampleRate;
        inputChannelCount = inputFormat.channelCount;
        channelMap = validateChannelMap(outputChannels, inputChannelCount, inputFormat);
        channelCount = channelMap == null ? inputChannelCount : channelMap.length;
        pcmEncoding = inputFormat.pcmEncoding;
        currentPositionUs = CURRENT_POSITION_NOT_SET;
        expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
        lastOutputSamples = null;
        aheadNotified.set(false);
        synchronized (deliveryLock) {
            timelineEpoch.incrementAndGet();
        }
        ended = false;
    }

    @Override
    public void play() {
        ended = false;
    }

    @Override
    public void handleDiscontinuity() {
        resetTimelineGate();
        if (listener != null) listener.onPositionDiscontinuity();
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs,
                                int encodedAccessUnitCount) {
        if (sampleRate <= 0 || inputChannelCount <= 0 || !acceptingData.get()
                || !vodTimelineConfirmed.get()) return false;
        long epoch = timelineEpoch.get();
        // Media3 为 renderer 使用大偏移时钟；匹配器只接收宿主可见的媒体时间。
        long mediaPresentationTimeUs = toMediaTimeUs(presentationTimeUs, outputStreamOffsetUs);
        long startMs = mediaPresentationTimeUs / 1000L;
        if (startMs > safeAdd(hostPositionMs.get(), maxLookaheadMs)) {
            if (aheadNotified.compareAndSet(false, true)) aheadListener.onAheadLimitReached();
            return false;
        }
        aheadNotified.set(false);

        int bytesPerSample = pcmEncoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        int inputSampleCount = buffer.remaining() / bytesPerSample;
        if (inputSampleCount == 0) {
            buffer.position(buffer.limit());
            return true;
        }
        if (inputSampleCount % inputChannelCount != 0) {
            consumer.onFailure(new IllegalArgumentException("PCM 缓冲不包含完整输入帧"));
            buffer.position(buffer.limit());
            return true;
        }
        ByteBuffer input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int frames = inputSampleCount / inputChannelCount;
        short[] samples = decodeAndMap(input, frames);
        long durationUs = frames * 1_000_000L / sampleRate;
        long endUs = safeAddUs(presentationTimeUs, durationUs);
        if (isUnexpectedPresentationTime(expectedPresentationTimeUs, presentationTimeUs)) {
            epoch = resetUnexpectedTimeline(epoch);
            if (epoch < 0L) return false;
            if (listener != null) listener.onPositionDiscontinuity();
        }
        long publishedPresentationTimeUs = presentationTimeUs;
        if (canBridgePcmGap(expectedPresentationTimeUs, presentationTimeUs,
                lastOutputSamples, channelCount)) {
            long gapUs = presentationTimeUs - expectedPresentationTimeUs;
            int missingFrames = gapFrames(gapUs, sampleRate);
            if (missingFrames > 0) {
                samples = prependInterpolatedFrames(lastOutputSamples, samples,
                        channelCount, missingFrames);
                publishedPresentationTimeUs = expectedPresentationTimeUs;
            }
        }
        long publishedMediaTimeUs = toMediaTimeUs(
                publishedPresentationTimeUs, outputStreamOffsetUs);
        synchronized (deliveryLock) {
            if (!acceptingData.get() || epoch != timelineEpoch.get()) return false;
            try {
                consumer.onPcm(new ProbePcmFrame(samples, sampleRate, channelCount,
                        publishedMediaTimeUs));
            } catch (RuntimeException error) {
                consumer.onFailure(error);
            }
            lastOutputSamples = lastFrameSamples(samples, channelCount);
            currentPositionUs = endUs;
            expectedPresentationTimeUs = endUs;
        }
        buffer.position(buffer.limit());
        return true;
    }

    @Override
    public void playToEndOfStream() {
        ended = true;
    }

    @Override
    public boolean isEnded() {
        return ended;
    }

    @Override
    public boolean hasPendingData() {
        // 虚拟音频时钟在配置后保持 ready，直到解码器明确送达流尾。
        return sampleRate > 0 && channelCount > 0 && !ended;
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters == null
                ? PlaybackParameters.DEFAULT : playbackParameters;
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return playbackParameters;
    }

    @Override
    public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
        if (listener != null) listener.onSkipSilenceEnabledChanged(false);
    }

    @Override
    public boolean getSkipSilenceEnabled() {
        return false;
    }

    @Override
    public void setAudioAttributes(AudioAttributes audioAttributes) {
        this.audioAttributes = audioAttributes == null ? AudioAttributes.DEFAULT : audioAttributes;
    }

    @Override
    public AudioAttributes getAudioAttributes() {
        return audioAttributes;
    }

    @Override public void setAudioSessionId(int audioSessionId) { }
    @Override public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) { }
    @Override public long getAudioTrackBufferSizeUs() { return C.TIME_UNSET; }
    @Override public void enableTunnelingV21() { }
    @Override public void disableTunneling() { }
    @Override public void setVolume(float volume) { }
    @Override public void pause() { }

    @Override
    public void setOutputStreamOffsetUs(long outputStreamOffsetUs) {
        this.outputStreamOffsetUs = outputStreamOffsetUs;
    }

    @Override
    public void flush() {
        resetTimelineGate();
        ended = false;
    }

    @Override
    public void reset() {
        sampleRate = 0;
        inputChannelCount = 0;
        channelCount = 0;
        channelMap = null;
        pcmEncoding = 0;
        flush();
    }

    void allowMoreData() {
        aheadNotified.set(false);
    }

    /** 权威 VOD 时间线先到达，PCM 才允许离开解码器。 */
    void confirmVodTimeline() {
        vodTimelineConfirmed.set(true);
    }

    /** 主动 seek 前关闭 PCM 交付；真正 flush 到达后才重新开放。 */
    void blockUntilTimelineReset() {
        synchronized (deliveryLock) {
            acceptingData.set(false);
            timelineEpoch.incrementAndGet();
        }
    }

    private void resetTimelineGate() {
        synchronized (deliveryLock) {
            currentPositionUs = CURRENT_POSITION_NOT_SET;
            expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
            lastOutputSamples = null;
            aheadNotified.set(false);
            timelineEpoch.incrementAndGet();
            acceptingData.set(true);
            consumer.onTimelineReset();
        }
    }

    private long resetUnexpectedTimeline(long expectedEpoch) {
        synchronized (deliveryLock) {
            if (!acceptingData.get() || timelineEpoch.get() != expectedEpoch) return -1L;
            currentPositionUs = CURRENT_POSITION_NOT_SET;
            expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
            lastOutputSamples = null;
            aheadNotified.set(false);
            long nextEpoch = timelineEpoch.incrementAndGet();
            consumer.onTimelineReset();
            return nextEpoch;
        }
    }

    private short[] decodeAndMap(ByteBuffer input, int frames) {
        short[] output = new short[frames * channelCount];
        if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
            java.nio.FloatBuffer values = input.asFloatBuffer();
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < channelCount; channel++) {
                    int inputChannel = channelMap == null ? channel : channelMap[channel];
                    float value = values.get(frame * inputChannelCount + inputChannel);
                    if (Float.isNaN(value)) value = 0.0f;
                    value = Math.max(-1.0f, Math.min(1.0f, value));
                    output[frame * channelCount + channel] =
                            (short) Math.round(value * 32767.0f);
                }
            }
        } else {
            java.nio.ShortBuffer values = input.asShortBuffer();
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < channelCount; channel++) {
                    int inputChannel = channelMap == null ? channel : channelMap[channel];
                    output[frame * channelCount + channel] =
                            values.get(frame * inputChannelCount + inputChannel);
                }
            }
        }
        return output;
    }

    static boolean canBridgePcmGap(long expectedUs, long actualUs,
                                   short[] previousSamples, int channels) {
        if (expectedUs == CURRENT_POSITION_NOT_SET || previousSamples == null
                || channels <= 0 || previousSamples.length != channels
                || actualUs <= expectedUs) return false;
        long gapUs = actualUs - expectedUs;
        return gapUs > 0L && gapUs <= MAX_PCM_BRIDGE_US;
    }

    static int gapFrames(long gapUs, int sampleRate) {
        if (gapUs <= 0L || sampleRate <= 0) return 0;
        long rounded = (gapUs * sampleRate + 500_000L) / 1_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, rounded);
    }

    static short[] prependInterpolatedFrames(short[] previousSamples, short[] currentSamples,
                                             int channels, int missingFrames) {
        if (missingFrames <= 0) return currentSamples;
        if (previousSamples == null || currentSamples == null || channels <= 0
                || previousSamples.length != channels || currentSamples.length < channels
                || currentSamples.length % channels != 0) {
            throw new IllegalArgumentException("PCM 缺口桥接参数无效");
        }
        int bridgeSamples = Math.multiplyExact(missingFrames, channels);
        short[] output = new short[Math.addExact(bridgeSamples, currentSamples.length)];
        for (int frame = 0; frame < missingFrames; frame++) {
            double fraction = (frame + 1.0) / (missingFrames + 1.0);
            for (int channel = 0; channel < channels; channel++) {
                long value = Math.round(previousSamples[channel] * (1.0 - fraction)
                        + currentSamples[channel] * fraction);
                output[frame * channels + channel] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, value));
            }
        }
        System.arraycopy(currentSamples, 0, output, bridgeSamples, currentSamples.length);
        return output;
    }

    private static short[] lastFrameSamples(short[] samples, int channels) {
        short[] output = new short[channels];
        System.arraycopy(samples, samples.length - channels, output, 0, channels);
        return output;
    }

    private static int[] validateChannelMap(int[] outputChannels, int inputChannelCount,
                                            Format inputFormat)
            throws ConfigurationException {
        if (outputChannels == null) return null;
        if (outputChannels.length == 0 || outputChannels.length > 16) {
            throw new ConfigurationException("PCM 输出声道映射无效", inputFormat);
        }
        int[] copy = Arrays.copyOf(outputChannels, outputChannels.length);
        for (int channel : copy) {
            if (channel < 0 || channel >= inputChannelCount) {
                throw new ConfigurationException("PCM 输出声道索引越界", inputFormat);
            }
        }
        return copy;
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static long safeAddUs(long left, long right) {
        if (left < 0) left = 0;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    static boolean isUnexpectedPresentationTime(long expectedUs, long actualUs) {
        if (expectedUs == CURRENT_POSITION_NOT_SET) return false;
        long distance = expectedUs >= actualUs
                ? expectedUs - actualUs : actualUs - expectedUs;
        return distance < 0L || distance > MAX_PRESENTATION_TIME_DRIFT_US;
    }

    static long toMediaTimeUs(long rendererTimeUs, long outputStreamOffsetUs) {
        long mediaTimeUs = rendererTimeUs - outputStreamOffsetUs;
        // 仅防御溢出和编解码器 preroll；公开 PCM PTS 必须非负。
        if (((rendererTimeUs ^ outputStreamOffsetUs) & (rendererTimeUs ^ mediaTimeUs)) < 0L) {
            return rendererTimeUs < 0L ? 0L : Long.MAX_VALUE;
        }
        return Math.max(0L, mediaTimeUs);
    }
}
