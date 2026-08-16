/* HLS 扫描监听器以单次完成、取消或错误作为终态。 */
package io.github.fongmi.adaudio.probe.tools;

/** 普通 HLS VOD 候选扫描监听器。 */
public interface HlsScanListener {
    /**
     * 扫描完成；零候选也是正常结果。
     *
     * @param result 完整不可变扫描结果
     */
    void onCompleted(HlsScanResult result);

    /**
     * 会话因明确取消、替换或扫描器关闭而结束。
     *
     * @param sessionId 被取消的会话 ID
     */
    void onCancelled(long sessionId);

    /**
     * 扫描因结构化错误结束。
     *
     * @param error 不可变结构化错误
     */
    void onError(ProbeToolError error);
}
