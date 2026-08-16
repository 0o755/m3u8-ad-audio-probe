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
    public void shortAdUsesWholeInterval() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                media, "sample-ad", 10_000L, 12_500L).build();

        assertEquals(2_500L, request.getAnchorDurationMs());
    }

    @Test
    public void acceptsBoundedCustomAnchor() {
        FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                media, "sample-ad", 10_000L, 30_000L)
                .setAnchor(4_000L, 3_000L)
                .build();

        assertEquals(4_000L, request.getAnchorOffsetMs());
        assertEquals(3_000L, request.getAnchorDurationMs());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsAnchorPastAdEnd() {
        FingerprintCaptureRequest.builder(media, "sample-ad", 10_000L, 15_000L)
                .setAnchor(3_000L, 3_000L)
                .build();
    }
}
