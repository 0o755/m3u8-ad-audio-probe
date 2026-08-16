/* 定义可替换可见播放器的稳定创建入口。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

import android.content.Context;
import android.os.Looper;

/**
 * 创建可见播放适配器，公开签名不得暴露 Media3、mpv 或其他具体播放器类型。
 * 第三方实现可通过 {@code ProbePlayer.Builder.setAdapterFactory(...)} 显式注入。
 */
public interface ProbePlaybackAdapterFactory {
    /** 当前播放适配器合同版本。 */
    int SPI_VERSION = 1;

    /** 返回长度为 1 到 128 的稳定 ASCII 实现标识。 */
    String getId();

    /** 返回实现针对的播放 SPI 版本，必须等于 {@link #SPI_VERSION}。 */
    int getPlaybackSpiVersion();

    /**
     * 创建尚未打开媒体的适配器实例；不得在此方法中发起网络请求。
     *
     * @param applicationContext Application Context
     * @param controlLooper 门面调用全部控制方法的串行 Looper
     * @param listener 生命周期、时间轴和错误接收器
     */
    ProbePlaybackAdapter create(Context applicationContext, Looper controlLooper,
                                ProbePlaybackAdapter.Listener listener);
}
