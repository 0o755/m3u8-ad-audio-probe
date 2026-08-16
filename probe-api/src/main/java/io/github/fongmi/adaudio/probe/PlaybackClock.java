/* 宿主时间轴接口：探针只读取当前位置，不接管宿主播放器。 */
package io.github.fongmi.adaudio.probe;

/**
 * 为探针提供宿主播放器的当前媒体时间轴。
 *
 * <p>调用线程由探针门面配置的宿主执行器决定，实现不应阻塞。</p>
 */
@FunctionalInterface
public interface PlaybackClock {
    /**
     * 读取宿主当前媒体位置。
     *
     * @return 从媒体起点计算的非负位置，单位毫秒
     */
    long getCurrentPositionMs();
}
