/* 时间轴断点原因使用稳定枚举，避免把具体播放器常量暴露给宿主。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

/** 播放时间轴发生不连续时的稳定原因分类。 */
public enum ProbePlaybackDiscontinuityReason {
    /** 宿主或 SDK 主动跳转。 */
    SEEK,
    /** 播放器自动进入下一媒体项。 */
    AUTO_TRANSITION,
    /** 当前媒体源或播放列表结构发生变化。 */
    SOURCE_CHANGE,
    /** 无法稳定映射的播放器内部调整。 */
    INTERNAL
}
