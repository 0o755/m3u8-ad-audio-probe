/* 轮询门闩按播放会话去重延迟任务，并允许终态恢复后重新启动。 */
package io.github.fongmi.adaudio.probe.player;

/** 仅由播放器控制线程访问的会话轮询门闩。 */
final class PlayerPollGate {
    private long sessionId;
    private boolean scheduled;

    /** 为会话预留一个轮询任务；已有预留时返回 false。 */
    boolean reserve(long expectedSessionId) {
        if (expectedSessionId <= 0L) return false;
        if (sessionId != expectedSessionId) {
            sessionId = expectedSessionId;
            scheduled = false;
        }
        if (scheduled) return false;
        scheduled = true;
        return true;
    }

    /** 延迟任务开始执行时消费预留；旧会话任务返回 false。 */
    boolean begin(long expectedSessionId) {
        if (sessionId != expectedSessionId || !scheduled) return false;
        scheduled = false;
        return true;
    }

    /** 发布失败或终态停止时撤销当前会话的预留。 */
    void stop(long expectedSessionId) {
        if (sessionId != expectedSessionId) return;
        sessionId = 0L;
        scheduled = false;
    }
}
