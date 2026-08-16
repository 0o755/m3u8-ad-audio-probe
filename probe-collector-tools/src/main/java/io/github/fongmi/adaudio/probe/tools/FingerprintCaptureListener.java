/* 指纹采集监听器只接收稳定 DTO 与单次终态。 */
package io.github.fongmi.adaudio.probe.tools;

/** 指纹采集异步结果监听器。 */
public interface FingerprintCaptureListener {
    /**
     * 收到单调递增的采集进度；实现应快速返回。
     *
     * @param progress 当前不可变进度快照
     */
    void onProgress(FingerprintCaptureProgress progress);

    /**
     * 指纹规则草稿已生成；每个会话最多调用一次。
     *
     * @param sessionId 完成的会话 ID
     * @param draft 已通过 rules-v1 约束校验的草稿
     */
    void onCompleted(long sessionId, FingerprintRuleDraft draft);

    /**
     * 会话因明确取消或被新会话替换而结束。
     *
     * @param sessionId 被取消的会话 ID
     */
    void onCancelled(long sessionId);

    /**
     * 会话因结构化错误结束；每个会话最多调用一次。
     *
     * @param error 不可变结构化错误
     */
    void onError(ProbeToolError error);
}
