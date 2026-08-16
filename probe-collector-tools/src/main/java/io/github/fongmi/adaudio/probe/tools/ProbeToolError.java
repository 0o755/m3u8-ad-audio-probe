/* 不可变错误对象隔离底层适配器异常与公开工具合同。 */
package io.github.fongmi.adaudio.probe.tools;

/** 一次采集或候选扫描的结构化终止错误。 */
public final class ProbeToolError {
    private final ProbeToolErrorCode code;
    private final long sessionId;
    private final boolean retryable;
    private final String message;
    private final Throwable cause;

    /**
     * 创建结构化错误。
     *
     * @param code 稳定错误码
     * @param sessionId 所属会话 ID
     * @param retryable 相同条件稍后是否可能成功
     * @param message 非空诊断信息
     * @param cause 可选底层原因
     */
    public ProbeToolError(ProbeToolErrorCode code, long sessionId, boolean retryable,
                          String message, Throwable cause) {
        if (code == null) throw new IllegalArgumentException("错误码不能为空");
        if (sessionId <= 0L) throw new IllegalArgumentException("会话 ID 必须为正数");
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("错误信息不能为空");
        }
        this.code = code;
        this.sessionId = sessionId;
        this.retryable = retryable;
        this.message = message;
        this.cause = cause;
    }

    /** @return 稳定错误码 */
    public ProbeToolErrorCode getCode() { return code; }

    /** @return 所属会话 ID */
    public long getSessionId() { return sessionId; }

    /** @return 相同条件稍后是否可能成功 */
    public boolean isRetryable() { return retryable; }

    /** @return 非空诊断信息 */
    public String getMessage() { return message; }

    /** @return 可选底层原因 */
    public Throwable getCause() { return cause; }
}
