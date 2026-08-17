/* 音频适配器只负责媒体读取、PCM 解码和真实时间轴，不参与广告判定。 */
package io.github.fongmi.adaudio.probe.adapter;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;

/**
 * 可替换的音频探针后端。
 *
 * <p>规则解析、指纹匹配、冲突裁决和跳转请求均由 SDK runtime 完成。适配器不得直接
 * 操作宿主播放器，也不得自行派发广告命中。所有控制方法由同一个串行 Looper 调用；
 * 回调可以来自解码线程，但同一会话内必须保持媒体顺序。控制方法必须快速、非阻塞，
 * 网络、解码和阻塞式释放应由实现异步完成；实现不得退出 runtime 提供的 Looper。</p>
 */
public interface ProbeAdapter extends AutoCloseable {
    /** 打开或原子替换媒体；实现必须先废弃上一会话的所有输出。 */
    void open(ProbeAdapterRequest request);

    /** 更新宿主位置，供实现维持前视窗口及纠正分析位置。 */
    void updateHostPosition(long sessionId, long positionMs);

    /** 停止完全匹配的会话；非当前会话必须无操作。 */
    void stop(long sessionId);

    /** 永久释放资源；必须幂等，返回后不得再发出新回调。 */
    @Override
    void close();

    /** 解码适配器向 runtime 报告媒体数据与状态。 */
    interface Listener {
        /**
         * 同步提交一块带真实 PTS 的交错 PCM16。
         *
         * <p>runtime 只在本次调用内读取 {@code frame} 和其样本数组，不会修改或保留；
         * 适配器可在返回后复用缓冲。旧会话数据会被静默丢弃。</p>
         */
        void onPcm(long sessionId, ProbePcmFrame frame);

        /** 报告解码时间轴不连续；runtime 会立即丢弃跨断点候选。 */
        void onTimelineReset(long sessionId, long positionMs);

        /**
         * 报告最新时间线快照。准备期快照允许被后续快照替换；适配器必须在
         * {@link ProbeAdapterState#DECODING} 前报告权威快照。
         */
        void onTimeline(long sessionId, long durationMs, boolean live, boolean dynamic);

        /** 报告播放器侧的低频生命周期状态。 */
        void onState(long sessionId, ProbeAdapterState state);

        /** 报告结构化错误；fatal 表示当前适配器会话不能继续。 */
        void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                     boolean retryable, String message, Throwable cause);
    }
}
