/* 播放适配器只承载可见媒体播放，不参与规则解析、指纹匹配或广告裁决。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

import android.view.Surface;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;

/**
 * 可替换的普通 HLS/MP4 点播后端。
 *
 * <p>所有控制方法及 {@link #getSnapshot(long)} 均由同一个串行 Looper 调用，必须快速且
 * 非阻塞。回调可以源于播放器内部线程，但必须保留同一会话内的媒体顺序。实现只借用
 * {@link Surface}，不得调用 {@link Surface#release()}。</p>
 */
public interface ProbePlaybackAdapter extends AutoCloseable {
    /** 打开或原子替换媒体；旧会话回调必须立即失效。 */
    void open(ProbePlaybackRequest request);

    /** 附加可见输出 Surface；允许在打开媒体之前调用。 */
    void attachSurface(Surface surface);

    /** 仅当给定对象仍为当前输出时清除 Surface。 */
    void clearSurface(Surface surface);

    /** 恢复完全匹配的会话；旧会话必须无操作。 */
    void play(long sessionId);

    /** 暂停完全匹配的会话；旧会话必须无操作。 */
    void pause(long sessionId);

    /** 跳转完全匹配的会话，位置单位为毫秒。 */
    void seekTo(long sessionId, long positionMs);

    /** 停止完全匹配的会话并释放其媒体资源。 */
    void stop(long sessionId);

    /** 返回完全匹配会话的当前快照；会话不匹配时返回 {@code null}。 */
    ProbePlaybackSnapshot getSnapshot(long sessionId);

    /** 永久释放资源；必须幂等，返回后不得发出新回调。 */
    @Override
    void close();

    /** 播放适配器向高层门面报告低频生命周期和结构化事件。 */
    interface Listener {
        /** 报告播放生命周期状态。 */
        void onState(long sessionId, ProbePlaybackAdapterState state);

        /**
         * 报告最新时间线快照；时长未知时使用 {@link ProbePlaybackSnapshot#TIME_UNSET}。
         * 准备期快照允许被后续快照替换，适配器必须在 READY 前报告权威快照。
         */
        void onTimeline(long sessionId, long durationMs, boolean live, boolean dynamic);

        /** 报告跳转或播放器内部调整后的实际位置。 */
        void onPositionDiscontinuity(long sessionId, long positionMs,
                                     ProbePlaybackDiscontinuityReason reason);

        /** 报告视频显示参数；没有视频轨时可以不调用。 */
        void onVideoSize(long sessionId, int width, int height,
                         float pixelWidthHeightRatio, int rotationDegrees);

        /** 报告当前会话的第一帧已经送达 Surface。 */
        void onFirstFrame(long sessionId);

        /** 报告结构化错误；fatal 表示当前播放会话不能继续。 */
        void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                     boolean retryable, String message, Throwable cause);
    }
}
