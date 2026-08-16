/* 适配器状态只描述解码生命周期，失败通过结构化错误报告。 */
package io.github.fongmi.adaudio.probe.adapter;

/** 解码适配器可报告的生命周期状态。 */
public enum ProbeAdapterState {
    PREPARING,
    DECODING,
    LOOKAHEAD_READY,
    ENDED
}
