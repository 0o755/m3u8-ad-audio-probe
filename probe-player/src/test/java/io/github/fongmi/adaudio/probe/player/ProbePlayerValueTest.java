/* 验证播放器公开状态、显示参数和错误对象的不可变合同。 */
package io.github.fongmi.adaudio.probe.player;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackSnapshot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProbePlayerValueTest {
    @Test
    public void statusKeepsIndependentTimelineAndVideoSize() {
        ProbePlayerVideoSize size = new ProbePlayerVideoSize(1920, 1080, 1f, 0);
        ProbePlayerStatus status = new ProbePlayerStatus(ProbePlayerState.READY,
                7L, "movie", 2000L, 8000L, 60_000L, true, size, null);

        assertEquals(7L, status.getSessionId());
        assertEquals(2000L, status.getPositionMs());
        assertEquals(8000L, status.getBufferedPositionMs());
        assertEquals(60_000L, status.getDurationMs());
        assertTrue(status.isPlaying());
        assertTrue(status.getVideoSize().isKnown());
    }

    @Test
    public void idleStatusUsesUnknownDurationAndNoVideo() {
        ProbePlayerStatus status = ProbePlayerStatus.idle(ProbePlayerState.IDLE);

        assertEquals(0L, status.getSessionId());
        assertEquals(ProbePlaybackSnapshot.TIME_UNSET, status.getDurationMs());
        assertFalse(status.getVideoSize().isKnown());
    }

    @Test
    public void videoSizeRejectsNonFiniteRatioWithoutApi24Method() {
        try {
            new ProbePlayerVideoSize(1920, 1080, Float.NaN, 0);
            fail("应拒绝 NaN 像素比例");
        } catch (IllegalArgumentException expected) {
            // minSdk 23 使用 isNaN/isInfinite 完成同步校验。
        }
    }

    @Test
    public void statusRejectsErrorFromDifferentSession() {
        ProbePlayerError error = new ProbePlayerError(ProbeErrorCode.SOURCE_IO,
                8L, false, true, "读取失败", null);
        try {
            new ProbePlayerStatus(ProbePlayerState.READY, 7L, "movie",
                    0L, 0L, ProbePlaybackSnapshot.TIME_UNSET,
                    false, ProbePlayerVideoSize.unknown(), error);
            fail("应拒绝其他会话的错误对象");
        } catch (IllegalArgumentException expected) {
            // 错误与状态必须属于同一播放代际。
        }
    }
}
