/* 对外状态保持精简，内部解码和匹配细节不成为兼容合同。 */
package io.github.fongmi.adaudio.probe;

/** 探针会话对宿主可见的生命周期状态。 */
public enum ProbeState {
    /** 当前没有活动媒体。 */
    IDLE,
    /** 正在加载规则、媒体或初始化解码器。 */
    PREPARING,
    /** 正在解码并分析音轨。 */
    ANALYZING,
    /** 已达到配置的前视距离，暂时等待宿主时间轴追上。 */
    LOOKAHEAD_READY,
    /** 当前点播媒体已经分析结束。 */
    ENDED,
    /** 当前会话因致命错误停止分析。 */
    FAILED,
    /** 探针实例已永久关闭。 */
    CLOSED
}
