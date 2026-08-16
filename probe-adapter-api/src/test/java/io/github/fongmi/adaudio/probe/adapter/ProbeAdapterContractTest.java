/* 验证第三方适配器最常用的请求和 PCM 边界。 */
package io.github.fongmi.adaudio.probe.adapter;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ProbeAdapterContractTest {
    @Test
    public void requestKeepsValidatedSessionConfiguration() {
        ProbeMedia media = ProbeMedia.from("https://example.com/video");
        ProbeAdapterRequest request = new ProbeAdapterRequest(7L, media, 1200L, 15_000L);

        assertEquals(7L, request.getSessionId());
        assertSame(media, request.getMedia());
        assertEquals(1200L, request.getStartPositionMs());
        assertEquals(15_000L, request.getMaxLookaheadMs());
    }

    @Test
    public void pcmFrameUsesBorrowedInterleavedSamplesAndExactEndPts() {
        short[] stereo = new short[960];
        ProbePcmFrame frame = new ProbePcmFrame(stereo, 48_000, 2, 1_000_000L);

        assertSame(stereo, frame.getSamples());
        assertEquals(48_000, frame.getSampleRateHz());
        assertEquals(2, frame.getChannelCount());
        assertEquals(1_010_000L, frame.getEndPositionUs());
    }

    @Test
    public void pcmFrameRejectsPartialInterleavedFrame() {
        try {
            new ProbePcmFrame(new short[5], 16_000, 2, 0L);
            fail("应拒绝不完整的交错 PCM 帧");
        } catch (IllegalArgumentException expected) {
            // 合同按构造期失败，避免无效数据进入 matcher。
        }
    }

    @Test
    public void pcmFrameRejectsMoreThanTwoSeconds() {
        try {
            new ProbePcmFrame(new short[16_001], 8000, 1, 0L);
            fail("应拒绝可能阻塞匹配线程的超长 PCM 块");
        } catch (IllegalArgumentException expected) {
            // 短帧上限同时约束第三方适配器的内存和处理延迟。
        }
    }
}
