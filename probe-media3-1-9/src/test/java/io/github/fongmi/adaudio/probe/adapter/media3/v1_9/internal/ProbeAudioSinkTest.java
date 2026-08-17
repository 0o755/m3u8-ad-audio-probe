/* 验证无声 AudioSink 的 PTS、renderer 偏移、声道映射与 seek 交付门闩。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.audio.AudioSink;

import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProbeAudioSinkTest {
    @Test
    public void acceptsNormalCodecTimestampDrift() {
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(
                AudioSink.CURRENT_POSITION_NOT_SET, 10_000L));
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 1_200_000L));
        assertFalse(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 800_000L));
    }

    @Test
    public void rejectsLargeOrOverflowingTimestampJump() {
        assertTrue(ProbeAudioSink.isUnexpectedPresentationTime(1_000_000L, 1_200_001L));
        assertTrue(ProbeAudioSink.isUnexpectedPresentationTime(Long.MAX_VALUE, Long.MIN_VALUE));
    }

    @Test
    public void appliesDecoderChannelMapBeforePublishingPcm() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(48_000)
                .setChannelCount(3)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, new int[]{2, 0});
        sink.confirmVodTimeline();
        ByteBuffer pcm = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN);
        pcm.asShortBuffer().put(new short[]{1, 2, 3, 4, 5, 6});

        assertTrue(sink.handleBuffer(pcm, 0L, 1));
        assertNotNull(consumer.frame);
        assertArrayEquals(new short[]{3, 1, 6, 4}, consumer.frame.getSamples());
    }

    @Test
    public void activeSeekGateRejectsPcmUntilSinkFlush() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(48_000)
                .setChannelCount(1)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, null);
        sink.confirmVodTimeline();
        ByteBuffer pcm = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);

        sink.blockUntilTimelineReset();
        assertFalse(sink.handleBuffer(pcm, 0L, 1));
        sink.flush();
        assertTrue(sink.handleBuffer(pcm, 0L, 1));
    }

    @Test
    public void removesRendererOffsetOnlyFromPublishedMediaTimeline() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(48_000)
                .setChannelCount(1)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, null);
        sink.confirmVodTimeline();
        long rendererOffsetUs = 1_000_000_000_000L;
        sink.setOutputStreamOffsetUs(rendererOffsetUs);
        ByteBuffer pcm = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);

        assertTrue(sink.handleBuffer(pcm, rendererOffsetUs + 2_000_000L, 1));
        assertNotNull(consumer.frame);
        assertEquals(2_000_000L, consumer.frame.getPresentationTimeUs());
        assertEquals(rendererOffsetUs + 2_000_041L, sink.getCurrentPositionUs(false));
    }

    @Test
    public void rendererOffsetDoesNotTripLookaheadGate() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(48_000)
                .setChannelCount(1)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, null);
        sink.confirmVodTimeline();
        long rendererOffsetUs = 1_000_000_000_000L;
        sink.setOutputStreamOffsetUs(rendererOffsetUs);
        ByteBuffer pcm = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);

        assertTrue(sink.handleBuffer(pcm, rendererOffsetUs + 14_000_000L, 1));
        assertEquals(14_000_000L, consumer.frame.getPresentationTimeUs());
    }

    @Test
    public void holdsPcmUntilFiniteVodTimelineIsConfirmed() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(48_000)
                .setChannelCount(1)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, null);
        ByteBuffer pcm = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN);

        assertFalse(sink.handleBuffer(pcm, 0L, 1));
        assertEquals(0, pcm.position());
        assertEquals(null, consumer.frame);

        sink.confirmVodTimeline();
        assertTrue(sink.handleBuffer(pcm, 0L, 1));
        assertNotNull(consumer.frame);
    }

    @Test
    public void bridgesOneMissingAacFrameAtHlsSegmentBoundary() throws Exception {
        RecordingConsumer consumer = new RecordingConsumer();
        ProbeAudioSink sink = new ProbeAudioSink(consumer, () -> { },
                new AtomicLong(), 15_000L);
        Format format = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_RAW)
                .setSampleRate(22_050)
                .setChannelCount(2)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build();
        sink.configure(format, 0, null);
        sink.confirmVodTimeline();
        ByteBuffer first = stereoPcm(1_024, (short) 1_000, (short) -1_000);
        ByteBuffer second = stereoPcm(1_024, (short) 3_000, (short) -3_000);

        assertTrue(sink.handleBuffer(first, 0L, 1));
        assertTrue(sink.handleBuffer(second, 92_888L, 1));

        assertEquals(2, consumer.frames.size());
        ProbePcmFrame bridged = consumer.frames.get(1);
        assertEquals(46_439L, bridged.getPresentationTimeUs());
        assertEquals((1_024 + 1_024) * 2, bridged.getSamples().length);
        assertEquals(1_002, bridged.getSamples()[0]);
        assertEquals(-1_002, bridged.getSamples()[1]);
        assertEquals(3_000, bridged.getSamples()[2_048]);
        assertEquals(-3_000, bridged.getSamples()[2_049]);
    }

    @Test
    public void doesNotBridgeGapLargerThanOneDecoderFrame() {
        assertFalse(ProbeAudioSink.canBridgePcmGap(1_000_000L, 1_060_001L,
                new short[]{1, 2}, 2));
        assertTrue(ProbeAudioSink.canBridgePcmGap(1_000_000L, 1_060_000L,
                new short[]{1, 2}, 2));
    }

    private static ByteBuffer stereoPcm(int frames, short left, short right) {
        ByteBuffer pcm = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < frames; frame++) {
            pcm.putShort(left).putShort(right);
        }
        pcm.flip();
        return pcm;
    }

    private static final class RecordingConsumer implements ProbePcmConsumer {
        ProbePcmFrame frame;
        final List<ProbePcmFrame> frames = new ArrayList<>();

        @Override public void onPcm(ProbePcmFrame frame) {
            this.frame = frame;
            frames.add(frame);
        }
        @Override public void onTimelineReset() { }
        @Override public void onFailure(RuntimeException error) { throw error; }
    }
}
