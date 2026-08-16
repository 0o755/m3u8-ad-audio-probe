/* 验证第三方播放适配器最常用的请求与快照边界。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public class ProbePlaybackContractTest {
    @Test
    public void requestKeepsSessionMediaAndInitialIntent() {
        ProbeMedia media = ProbeMedia.from("https://example.com/video");
        ProbePlaybackRequest request = new ProbePlaybackRequest(9L, media, 5000L, false);

        assertEquals(9L, request.getSessionId());
        assertSame(media, request.getMedia());
        assertEquals(5000L, request.getStartPositionMs());
        assertFalse(request.isPlayWhenReady());
    }

    @Test
    public void snapshotAcceptsUnknownDurationAndBackwardBufferReset() {
        ProbePlaybackSnapshot snapshot = new ProbePlaybackSnapshot(
                5000L, 4000L, ProbePlaybackSnapshot.TIME_UNSET, false);

        assertEquals(5000L, snapshot.getPositionMs());
        assertEquals(4000L, snapshot.getBufferedPositionMs());
        assertEquals(ProbePlaybackSnapshot.TIME_UNSET, snapshot.getDurationMs());
    }

    @Test
    public void requestRejectsNonPositiveSession() {
        try {
            new ProbePlaybackRequest(0L, ProbeMedia.from("https://example.com/video"),
                    0L, true);
            fail("应拒绝无效播放会话");
        } catch (IllegalArgumentException expected) {
            // 构造期拒绝，避免第三方适配器收到不可区分的空会话。
        }
    }
}
