/* 验证 SDK 跳转决定不会携带模糊、越界或非有限数据。 */
package io.github.fongmi.adaudio.probe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SkipRequestTest {
    @Test
    public void exposesCompleteStableDecision() {
        SkipRequest request = request(1L, 2L, "episode", "ad", 3L,
                10_000L, 20_000L, 20_000L, 10_050L, 15_000L, 0.75f);

        assertEquals(1L, request.getRequestId());
        assertEquals(2L, request.getSessionId());
        assertEquals("episode", request.getMediaId());
        assertEquals("ad", request.getRuleId());
        assertEquals(3L, request.getRuleRevision());
        assertEquals(10_000L, request.getAdStartPositionMs());
        assertEquals(20_000L, request.getAdEndPositionMs());
        assertEquals(20_000L, request.getSeekTargetPositionMs());
        assertEquals(10_050L, request.getHostPositionMsAtDispatch());
        assertEquals(15_000L, request.getAnalyzedThroughPositionMs());
        assertEquals(0.75f, request.getMatchSimilarity(), 0.0f);
    }

    @Test
    public void rejectsInvalidIdsAndRevisions() {
        assertRejected(() -> request(0L, 2L, "episode", "ad", 3L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 0L, "episode", "ad", 3L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "", "ad", 3L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "   ", 3L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode\n2", "ad", 3L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 0L,
                10L, 20L, 20L, 10L, 10L, 1.0f));
    }

    @Test
    public void rejectsInvalidIntervalsAndPositions() {
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                -1L, 20L, 20L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                10L, 10L, 10L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                10L, 20L, 19L, 10L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                10L, 20L, 20L, 9L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                10L, 20L, 20L, 20L, 10L, 1.0f));
        assertRejected(() -> request(1L, 2L, "episode", "ad", 3L,
                10L, 20L, 20L, 10L, -1L, 1.0f));
    }

    @Test
    public void rejectsNonFiniteOrOutOfRangeSimilarity() {
        assertRejected(() -> validWithSimilarity(Float.NaN));
        assertRejected(() -> validWithSimilarity(Float.POSITIVE_INFINITY));
        assertRejected(() -> validWithSimilarity(Float.NEGATIVE_INFINITY));
        assertRejected(() -> validWithSimilarity(-0.01f));
        assertRejected(() -> validWithSimilarity(1.01f));
    }

    private static SkipRequest validWithSimilarity(float similarity) {
        return request(1L, 2L, "episode", "ad", 3L,
                10L, 20L, 20L, 10L, 10L, similarity);
    }

    private static SkipRequest request(long requestId, long sessionId,
                                       String mediaId, String ruleId, long revision,
                                       long startMs, long endMs, long seekMs,
                                       long hostMs, long analyzedMs, float similarity) {
        return new SkipRequest(requestId, sessionId, mediaId, ruleId, revision,
                startMs, endMs, seekMs, hostMs, analyzedMs, similarity);
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("预期拒绝非法参数");
        } catch (IllegalArgumentException expected) {
            // 错误文案不作为兼容合同。
        }
    }
}
