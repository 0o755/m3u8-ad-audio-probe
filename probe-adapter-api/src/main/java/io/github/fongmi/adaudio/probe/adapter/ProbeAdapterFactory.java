/* 定义可替换音频解码适配器的稳定创建入口。 */
package io.github.fongmi.adaudio.probe.adapter;

import android.content.Context;
import android.os.Looper;

/**
 * 创建一个媒体解码适配器。实现不得在公开签名中暴露具体播放器类型。
 *
 * <p>第三方适配器通常只需实现本接口与 {@link ProbeAdapter}，再通过
 * {@code AdAudioProbe.Builder.setAdapterFactory(...)} 显式注入。</p>
 */
public interface ProbeAdapterFactory {
    /** 当前适配器合同版本。不同版本必须拒绝装配。 */
    int SPI_VERSION = 1;

    /**
     * 返回稳定且可读的实现标识。
     *
     * @return 长度为 1 到 128 的非空 ASCII 标识
     */
    String getId();

    /**
     * 返回实现所针对的 SPI 版本。
     *
     * @return 必须等于 {@link #SPI_VERSION}
     */
    int getSpiVersion();

    /**
     * 创建适配器实例。此方法不得启动网络请求或解码器。
     *
     * @param applicationContext Application Context
     * @param controlLooper SDK 调用所有控制方法的串行 Looper
     * @param listener PCM、时间轴和诊断回调接收器
     * @return 全新的、尚未打开媒体的适配器
     */
    ProbeAdapter create(Context applicationContext, Looper controlLooper,
                        ProbeAdapter.Listener listener);
}
