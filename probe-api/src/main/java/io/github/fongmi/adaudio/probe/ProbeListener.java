/* 探针监听器统一承载跳转建议，并提供可选的状态与错误通知。 */
package io.github.fongmi.adaudio.probe;

/**
 * 接收跳转决定及可选诊断事件。
 *
 * <p>回调线程和串行策略由探针门面配置的宿主执行器决定，回调实现不应阻塞。</p>
 */
@FunctionalInterface
public interface ProbeListener {
    /**
     * 接收已经过会话和时间轴复核的跳转请求。
     *
     * @param request 非空、只读的跳转决定；宿主应尽快跳到其目标位置
     */
    void onSkipRequested(SkipRequest request);

    /**
     * 接收低频状态快照。
     *
     * @param status 非空、只读的当前状态
     */
    default void onStatusChanged(ProbeStatus status) {
    }

    /**
     * 接收本地 rules-v1 替换请求的唯一终态。
     *
     * @param result 非空结果；可用 requestId 精确关联一次 replaceRules 调用
     */
    default void onRulesReplaced(RuleReplacementResult result) {
    }

    /**
     * 接收结构化错误。
     *
     * @param error 非空错误对象；是否影响当前会话由 {@link ProbeError#isFatal()} 表示
     */
    default void onError(ProbeError error) {
    }
}
