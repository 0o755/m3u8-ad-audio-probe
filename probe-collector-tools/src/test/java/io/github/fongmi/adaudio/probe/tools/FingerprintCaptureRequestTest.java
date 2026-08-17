/* 验证采集请求默认锚点与广告边界合同。 */
package io.github.fongmi.adaudio.probe.tools;

import org.junit.Test;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import static org.junit.Assert.assertEquals;

public class FingerprintCaptureRequestTest {
    private final ProbeMedia media = ProbeMedia.from("https://example.com/video.m3u8");

    @Test
    public void defaultsToFirstFiveSeconds() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                media, "sample-ad", 10_000L, 30_000L).build();

        assertEquals(0L, request.getAnchorOffsetMs());
        assertEquals(5_000L, request.getAnchorDurationMs());
    }

    @Test
    public void customAnchorStillRequiresFiveSeconds() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                media, "sample-ad", 10_000L, 30_000L)
                .setAnchor(4_000L, 5_000L)
                .build();

        assertEquals(4_000L, request.getAnchorOffsetMs());
        assertEquals(5_000L, request.getAnchorDurationMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAdShorterThanFiveSeconds() {
        FingerprintCaptureRequest.builder(media, "sample-ad", 10_000L, 12_500L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortCustomAnchor() {
        FingerprintCaptureRequest.builder(media, "sample-ad", 10_000L, 20_000L)
                .setAnchor(3_000L, 3_000L)
                .build();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnchorPastAdEnd() {
        FingerprintCaptureRequest.builder(media, "sample-ad", 10_000L, 15_000L)
                .setAnchor(1L, 5_000L)
                .build();
    }
}
