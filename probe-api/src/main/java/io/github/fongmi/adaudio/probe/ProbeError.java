/* 不可变错误对象只暴露安全信息和可选原因，不改变宿主播放状态。 */
package io.github.fongmi.adaudio.probe;

/** 探针产生的不可变结构化错误。 */
public final class ProbeError {
    private final ProbeErrorCode code;
    private final long sessionId;
    private final boolean fatal;
    private final boolean retryable;
    private final String message;
    private final Throwable cause;

    ProbeError(ProbeErrorCode code, long sessionId, boolean fatal,
               boolean retryable, String message, Throwable cause) {
        if (code == null) throw new IllegalArgumentException("错误码不能为空");
        ApiValidation.requireNonNegative(sessionId, "会话 ID");
        this.code = code;
        this.sessionId = sessionId;
        this.fatal = fatal;
        this.retryable = retryable;
        this.message = ApiValidation.requireMessage(message);
        this.cause = cause;
    }

    /**
     * 返回稳定的错误分类。
     *
     * @return 非空错误码
     */
    public ProbeErrorCode getCode() {
        return code;
    }

    /**
     * 返回错误所属会话。
     *
     * @return 非负会话 ID；{@code 0} 表示尚无活动媒体
     */
    public long getSessionId() {
        return sessionId;
    }

    /**
     * 返回本错误是否使当前探针会话无法继续分析。
     *
     * @return 致命错误时为 {@code true}
     */
    public boolean isFatal() {
        return fatal;
    }

    /**
     * 返回相同条件稍后是否可能恢复。
     *
     * @return 允许宿主稍后重试时为 {@code true}
     */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * 返回适合诊断的简短错误信息。
     *
     * @return 非空错误信息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 返回底层异常。
     *
     * @return 原始原因；没有可用原因时为 {@code null}
     */
    public Throwable getCause() {
        return cause;
    }
}
