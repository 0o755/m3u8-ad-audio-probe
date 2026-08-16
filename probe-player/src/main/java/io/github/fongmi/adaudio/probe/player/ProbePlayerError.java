/* 不可变播放错误沿用 Probe 稳定错误码，不泄露具体播放器异常类型。 */
package io.github.fongmi.adaudio.probe.player;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;

/** 可见播放器产生的不可变结构化错误。 */
public final class ProbePlayerError {
    private final ProbeErrorCode code;
    private final long sessionId;
    private final boolean fatal;
    private final boolean retryable;
    private final String message;
    private final Throwable cause;

    ProbePlayerError(ProbeErrorCode code, long sessionId, boolean fatal,
                     boolean retryable, String message, Throwable cause) {
        if (code == null) throw new IllegalArgumentException("错误码不能为空");
        if (sessionId <= 0L) throw new IllegalArgumentException("错误会话 ID 必须为正数");
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("错误信息不能为空");
        }
        this.code = code;
        this.sessionId = sessionId;
        this.fatal = fatal;
        this.retryable = retryable;
        this.message = message.trim();
        this.cause = cause;
    }

    /** 返回稳定错误分类。 */
    public ProbeErrorCode getCode() {
        return code;
    }

    /** 返回错误所属的正数播放会话 ID。 */
    public long getSessionId() {
        return sessionId;
    }

    /** 返回错误是否终止当前会话。 */
    public boolean isFatal() {
        return fatal;
    }

    /** 返回相同条件稍后是否可能恢复。 */
    public boolean isRetryable() {
        return retryable;
    }

    /** 返回适合诊断的非空简短信息。 */
    public String getMessage() {
        return message;
    }

    /** 返回底层原因；不可用时为 {@code null}。 */
    public Throwable getCause() {
        return cause;
    }
}
