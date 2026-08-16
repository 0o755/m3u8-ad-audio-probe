/* 内部扫描异常携带公开错误分类与可重试语义。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import java.io.IOException;

import io.github.fongmi.adaudio.probe.tools.ProbeToolErrorCode;

public final class HlsScanException extends IOException {
    private final ProbeToolErrorCode code;
    private final boolean retryable;

    public HlsScanException(ProbeToolErrorCode code, boolean retryable, String message) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public HlsScanException(ProbeToolErrorCode code, boolean retryable,
                            String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public ProbeToolErrorCode getCode() { return code; }

    public boolean isRetryable() { return retryable; }
}
