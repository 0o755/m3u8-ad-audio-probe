/* 播放回调均按宿主 Executor 串行派发，并携带可校验的会话 ID。 */
package io.github.fongmi.adaudio.probe.player;

import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackDiscontinuityReason;

/** 可见播放器的宿主回调；实现只需覆盖关心的方法。 */
public interface ProbePlayerListener {
    /** 状态或时间轴快照发生变化。 */
    default void onStatusChanged(ProbePlayerStatus status) {
    }

    /** 当前会话完成一次实际时间轴跳变。 */
    default void onPositionDiscontinuity(long sessionId, long positionMs,
                                         ProbePlaybackDiscontinuityReason reason) {
    }

    /** 当前会话的第一帧已经送达宿主 Surface。 */
    default void onFirstFrame(long sessionId) {
    }

    /** 当前会话产生结构化错误。 */
    default void onError(ProbePlayerError error) {
    }
}
