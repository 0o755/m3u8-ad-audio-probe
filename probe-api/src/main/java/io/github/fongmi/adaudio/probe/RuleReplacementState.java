/* 本地规则替换状态用于精确区分提交、拒绝和被新请求覆盖。 */
package io.github.fongmi.adaudio.probe;

/** 一次本地 rules-v1 替换请求的唯一终态。 */
public enum RuleReplacementState {
    /** 新规则已经原子提交，并已使旧媒体会话失效。 */
    APPLIED,
    /** 输入未通过校验或后台执行失败，旧规则继续有效。 */
    REJECTED,
    /** 请求尚未开始解析时被更新的替换请求覆盖。 */
    SUPERSEDED
}
