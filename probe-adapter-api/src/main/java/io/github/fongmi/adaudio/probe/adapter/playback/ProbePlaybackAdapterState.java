/* 可见播放适配器状态只描述媒体生命周期，播放意图由快照单独表达。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

/** 播放适配器向高层门面报告的稳定生命周期状态。 */
public enum ProbePlaybackAdapterState {
    /** 已接收媒体，正在创建数据源和解码器。 */
    PREPARING,
    /** 播放暂时等待更多媒体数据。 */
    BUFFERING,
    /** 媒体已经具备播放条件。 */
    READY,
    /** 有限点播时间轴已经播放完毕。 */
    ENDED,
    /** 适配器当前没有可播放时间轴。 */
    IDLE
}
