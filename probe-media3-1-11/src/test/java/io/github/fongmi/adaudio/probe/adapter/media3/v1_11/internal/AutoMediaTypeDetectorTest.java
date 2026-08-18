/* 验证 AUTO 判型不会把查询参数、错误 MIME 或普通正文误当成 HLS。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_11.internal;

import androidx.media3.exoplayer.source.UnrecognizedInputFormatException;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class AutoMediaTypeDetectorTest {
    @Test
    public void explicitTypeAlwaysWins() {
        ProbeMedia hls = ProbeMedia.builder("https://example.com/video.mp4")
                .setType(ProbeMedia.Type.HLS).build();
        ProbeMedia mp4 = ProbeMedia.builder("https://example.com/video.m3u8")
                .setType(ProbeMedia.Type.MP4).build();

        assertEquals(AutoMediaTypeDetector.Container.HLS,
                AutoMediaTypeDetector.initialContainer(hls));
        assertEquals(AutoMediaTypeDetector.Container.MP4,
                AutoMediaTypeDetector.initialContainer(mp4));
        assertFalse(AutoMediaTypeDetector.allowsFallback(hls));
        assertFalse(AutoMediaTypeDetector.allowsFallback(mp4));
    }

    @Test
    public void pathHintIgnoresQueryAndCase() {
        ProbeMedia media = ProbeMedia.from("https://example.com/VIDEO.M3U8?token=.mp4");
        assertEquals(AutoMediaTypeDetector.Container.HLS,
                AutoMediaTypeDetector.initialContainer(media));
        assertTrue(AutoMediaTypeDetector.allowsFallback(media));
    }

    @Test
    public void extensionlessSourceAllowsOneControlledFallback() {
        ProbeMedia media = ProbeMedia.from("https://example.com/play?id=42");
        assertEquals(AutoMediaTypeDetector.Container.MP4,
                AutoMediaTypeDetector.initialContainer(media));
        assertTrue(AutoMediaTypeDetector.allowsFallback(media));
    }

    @Test
    public void detectsBomWhitespaceAndExtM3uSignature() {
        byte[] prefix = ("\ufeff \r\n#EXTM3U\n#EXT-X-VERSION:3")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(AutoMediaTypeDetector.hasHlsEvidence(prefix, prefix.length, null));
    }

    @Test
    public void acceptsOnlyKnownHlsMimeTypes() {
        assertTrue(AutoMediaTypeDetector.hasHlsEvidence(
                "not-yet-read".getBytes(StandardCharsets.US_ASCII), 12,
                "application/vnd.apple.mpegurl; charset=utf-8"));

        assertFalse(AutoMediaTypeDetector.hasHlsEvidence(
                "<html>".getBytes(StandardCharsets.US_ASCII), 6, "text/html"));
    }

    @Test
    public void detectsMp4FtypOrMimeEvidence() {
        byte[] prefix = mp4Prefix(0);
        assertTrue(AutoMediaTypeDetector.hasMp4Evidence(prefix, prefix.length, null));
        assertTrue(AutoMediaTypeDetector.hasMp4Evidence(
                new byte[0], 0, "video/mp4; charset=binary"));
        assertFalse(AutoMediaTypeDetector.hasMp4Evidence(
                "#EXTM3U".getBytes(StandardCharsets.US_ASCII), 7, "text/plain"));
    }

    @Test
    public void scansFtypAfterAValidTopLevelBoxAcrossShortReads() {
        byte[] prefix = mp4Prefix(256);
        SourceObservation observation = new SourceObservation();
        assertTrue(observation.beginResponse(Collections.emptyMap()));
        observation.recordBytes(prefix, 0, 37);
        observation.recordBytes(prefix, 37, prefix.length - 37);

        assertTrue(observation.hasMp4Evidence());
    }

    @Test
    public void sourceObservationCapsContainerEvidenceAtFourKiB() {
        byte[] prefix = mp4Prefix(4 * 1024);
        SourceObservation observation = new SourceObservation();
        assertTrue(observation.beginResponse(Collections.emptyMap()));
        observation.recordBytes(prefix, 0, prefix.length);

        assertFalse(observation.hasMp4Evidence());
    }

    @Test
    public void ignoresFtypBytesInsideMalformedOrBoxPayloadData() {
        byte[] payloadOnly = new byte[128];
        ByteBuffer payload = ByteBuffer.wrap(payloadOnly).order(ByteOrder.BIG_ENDIAN);
        payload.putInt(128).put("free".getBytes(StandardCharsets.US_ASCII));
        payload.position(32);
        payload.put("ftyp".getBytes(StandardCharsets.US_ASCII));
        assertFalse(AutoMediaTypeDetector.hasMp4Evidence(
                payloadOnly, payloadOnly.length, null));

        byte[] malformed = mp4Prefix(16);
        ByteBuffer.wrap(malformed).order(ByteOrder.BIG_ENDIAN).putInt(4);
        assertFalse(AutoMediaTypeDetector.hasMp4Evidence(
                malformed, malformed.length, null));
    }

    @Test
    public void fallbackRequiresUnrecognizedContainerCause() {
        Throwable cause = new UnrecognizedInputFormatException("unknown",
                null);
        assertTrue(Media3ProbeAdapter.containsUnrecognizedInput(
                new IllegalStateException("wrapper", cause)));
        assertFalse(Media3ProbeAdapter.containsUnrecognizedInput(
                new IllegalStateException("network")));
    }

    private static byte[] mp4Prefix(int leadingBoxSize) {
        int safeLeadingSize = Math.max(0, leadingBoxSize);
        if (safeLeadingSize > 0 && safeLeadingSize < 8) {
            throw new IllegalArgumentException("前置 box 至少需要 8 字节");
        }
        ByteBuffer buffer = ByteBuffer.allocate(safeLeadingSize + 24)
                .order(ByteOrder.BIG_ENDIAN);
        if (safeLeadingSize > 0) {
            buffer.putInt(safeLeadingSize)
                    .put("free".getBytes(StandardCharsets.US_ASCII));
            buffer.position(safeLeadingSize);
        }
        buffer.putInt(24).put("ftyp".getBytes(StandardCharsets.US_ASCII));
        buffer.put("isom".getBytes(StandardCharsets.US_ASCII)).putInt(0);
        buffer.put("isom".getBytes(StandardCharsets.US_ASCII));
        buffer.put("iso2".getBytes(StandardCharsets.US_ASCII));
        return buffer.array();
    }
}
