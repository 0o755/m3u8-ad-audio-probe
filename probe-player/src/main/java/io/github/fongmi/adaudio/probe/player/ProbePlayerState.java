/* 宿主可稳定观察的播放器生命周期状态。 */
package io.github.fongmi.adaudio.probe.player;

/** 可见点播播放器的稳定生命周期状态。 */
public enum ProbePlayerState {
    /** 没有活动媒体。 */
    IDLE,
    /** 正在创建和准备媒体。 */
    PREPARING,
    /** 播放暂时等待更多媒体数据。 */
    BUFFERING,
    /** 媒体已经具备播放条件。 */
    READY,
    /** 有限点播时间轴已经播放完毕。 */
    ENDED,
    /** 当前会话发生致命错误，不再接受控制。 */
    FAILED,
    /** 实例已经永久释放。 */
    CLOSED
}
