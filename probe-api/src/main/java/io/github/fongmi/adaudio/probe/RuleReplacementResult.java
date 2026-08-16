/* 本地规则替换结果携带请求代际和提交后的规则状态。 */
package io.github.fongmi.adaudio.probe;

/** 本地 rules-v1 替换请求的不可变终态。 */
public final class RuleReplacementResult {
    private final long requestId;
    private final RuleReplacementState state;
    private final long sessionId;
    private final long ruleRevision;
    private final int ruleCount;
    private final ProbeError error;

    RuleReplacementResult(long requestId, RuleReplacementState state,
                          long sessionId, long ruleRevision, int ruleCount,
                          ProbeError error) {
        if (requestId <= 0L) throw new IllegalArgumentException("规则替换请求 ID 必须为正数");
        if (state == null) throw new IllegalArgumentException("规则替换状态不能为空");
        ApiValidation.requireNonNegative(sessionId, "会话 ID");
        ApiValidation.requireNonNegative(ruleRevision, "规则 revision");
        if (ruleCount < 0) throw new IllegalArgumentException("规则数量不能为负数");
        if (state == RuleReplacementState.REJECTED) {
            if (error == null || error.getSessionId() != sessionId) {
                throw new IllegalArgumentException("被拒绝的规则替换必须携带同会话错误");
            }
        } else if (error != null) {
            throw new IllegalArgumentException("成功或被覆盖的规则替换不能携带错误");
        }
        this.requestId = requestId;
        this.state = state;
        this.sessionId = sessionId;
        this.ruleRevision = ruleRevision;
        this.ruleCount = ruleCount;
        this.error = error;
    }

    /** @return 对应一次运行时 replaceRules 调用的请求 ID */
    public long getRequestId() { return requestId; }

    /** @return 本次请求的唯一终态 */
    public RuleReplacementState getState() { return state; }

    /** @return 提交或拒绝发生时的活动媒体会话；没有活动媒体时为 0 */
    public long getSessionId() { return sessionId; }

    /** @return 终态产生后仍有效的规则 revision */
    public long getRuleRevision() { return ruleRevision; }

    /** @return 终态产生后仍有效的规则数量 */
    public int getRuleCount() { return ruleCount; }

    /** @return REJECTED 的结构化错误，其他状态为 null */
    public ProbeError getError() { return error; }
}
