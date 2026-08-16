/* 无声 AudioSink 消费解码 PCM 和真实 PTS，不创建 AudioTrack 或请求音频焦点。 */
package io.github.fongmi.adaudio.probe.internal.media3;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.audio.AudioSink;

import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@UnstableApi
final class ProbeAudioSink implements AudioSink {
    // 与 Media3 默认 AudioSink 一致，超过 200ms 才视为真实时间轴跳变。
    static final long MAX_PRESENTATION_TIME_DRIFT_US = 200_000L;

    interface AheadListener {
        void onAheadLimitReached();
    }

    private final ProbePcmConsumer consumer;
    private final AheadListener aheadListener;
    private final AtomicLong hostPositionMs;
    private final long maxLookaheadMs;
    private final AtomicBoolean aheadNotified = new AtomicBoolean();

    private Listener listener;
    private AudioAttributes audioAttributes = AudioAttributes.DEFAULT;
    private PlaybackParameters playbackParameters = PlaybackParameters.DEFAULT;
    private int sampleRate;
    private int channelCount;
    private int pcmEncoding;
    private long currentPositionUs = CURRENT_POSITION_NOT_SET;
    private long expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
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
        channelCount = inputFormat.channelCount;
        pcmEncoding = inputFormat.pcmEncoding;
        currentPositionUs = CURRENT_POSITION_NOT_SET;
        expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
        aheadNotified.set(false);
        ended = false;
    }

    @Override
    public void play() {
        ended = false;
    }

    @Override
    public void handleDiscontinuity() {
        currentPositionUs = CURRENT_POSITION_NOT_SET;
        expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
        aheadNotified.set(false);
        consumer.onTimelineReset();
        if (listener != null) listener.onPositionDiscontinuity();
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs,
                                int encodedAccessUnitCount) {
        if (sampleRate <= 0 || channelCount <= 0) return false;
        long startMs = Math.max(0L, presentationTimeUs / 1000L);
        if (startMs > safeAdd(hostPositionMs.get(), maxLookaheadMs)) {
            if (aheadNotified.compareAndSet(false, true)) aheadListener.onAheadLimitReached();
            return false;
        }
        aheadNotified.set(false);

        int bytesPerSample = pcmEncoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
        int sampleCount = buffer.remaining() / bytesPerSample;
        if (sampleCount == 0) {
            buffer.position(buffer.limit());
            return true;
        }
        ByteBuffer input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        short[] samples = new short[sampleCount];
        if (pcmEncoding == C.ENCODING_PCM_FLOAT) {
            java.nio.FloatBuffer floats = input.asFloatBuffer();
            for (int i = 0; i < samples.length; i++) {
                float value = floats.get(i);
                if (Float.isNaN(value)) value = 0.0f;
                value = Math.max(-1.0f, Math.min(1.0f, value));
                samples[i] = (short) Math.round(value * 32767.0f);
            }
        } else {
            input.asShortBuffer().get(samples);
        }
        long frames = sampleCount / channelCount;
        long durationUs = frames * 1_000_000L / sampleRate;
        long endUs = safeAddUs(presentationTimeUs, durationUs);
        if (isUnexpectedPresentationTime(expectedPresentationTimeUs, presentationTimeUs)) {
            consumer.onTimelineReset();
            if (listener != null) listener.onPositionDiscontinuity();
        }
        try {
            consumer.onPcm(new PcmChunk(samples, sampleRate, channelCount, startMs), endUs / 1000L);
        } catch (RuntimeException error) {
            consumer.onFailure(error);
        }
        currentPositionUs = endUs;
        expectedPresentationTimeUs = endUs;
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
    @Override public long getAudioTrackBufferSizeUs() { return 0L; }
    @Override public void enableTunnelingV21() { }
    @Override public void disableTunneling() { }
    @Override public void setVolume(float volume) { }
    @Override public void pause() { }

    @Override
    public void flush() {
        currentPositionUs = CURRENT_POSITION_NOT_SET;
        expectedPresentationTimeUs = CURRENT_POSITION_NOT_SET;
        ended = false;
        aheadNotified.set(false);
        consumer.onTimelineReset();
    }

    @Override
    public void reset() {
        sampleRate = 0;
        channelCount = 0;
        pcmEncoding = 0;
        flush();
    }

    void allowMoreData() {
        aheadNotified.set(false);
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
}
