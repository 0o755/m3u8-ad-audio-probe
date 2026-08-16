/* 可取消会话为异步采集与扫描提供一致的生命周期入口。 */
package io.github.fongmi.adaudio.probe.tools;

/** 正在执行的采集器工具会话。 */
public interface ProbeToolSession {
    /** @return 单个工具实例内单调递增的正会话 ID */
    long getSessionId();

    /** 请求取消当前会话；重复调用或会话已结束时无操作。 */
    void cancel();
}
