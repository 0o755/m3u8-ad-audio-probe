/* 默认播放器适配器通过 ServiceLoader 装配，显式工厂始终优先。 */
package io.github.fongmi.adaudio.probe.player.internal;

import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterFactory;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** 解析默认或宿主显式提供的唯一播放适配器工厂。 */
public final class ProbePlaybackAdapterResolver {
    private ProbePlaybackAdapterResolver() {
    }

    public static ProbePlaybackAdapterFactory resolve(ProbePlaybackAdapterFactory explicit) {
        ProbePlaybackAdapterFactory selected = explicit;
        if (selected == null) {
            try {
                ServiceLoader<ProbePlaybackAdapterFactory> loader = ServiceLoader.load(
                        ProbePlaybackAdapterFactory.class,
                        ProbePlaybackAdapterFactory.class.getClassLoader());
                Iterator<ProbePlaybackAdapterFactory> providers = loader.iterator();
                if (!providers.hasNext()) {
                    throw new IllegalStateException("未找到播放适配器；请依赖默认聚合包或显式设置工厂");
                }
                selected = providers.next();
                if (providers.hasNext()) {
                    throw new IllegalStateException("检测到多个播放适配器，请通过 Builder 显式选择");
                }
            } catch (ServiceConfigurationError error) {
                throw new IllegalStateException("播放适配器服务配置无效", error);
            }
        }
        validate(selected);
        return selected;
    }

    private static void validate(ProbePlaybackAdapterFactory factory) {
        int spiVersion = factory.getPlaybackSpiVersion();
        if (spiVersion != ProbePlaybackAdapterFactory.SPI_VERSION) {
            throw new IllegalStateException("播放适配器 SPI 版本不兼容：" + spiVersion);
        }
        String id = factory.getId();
        if (id == null || id.isEmpty() || id.length() > 128) {
            throw new IllegalStateException("播放适配器 ID 长度无效");
        }
        for (int index = 0; index < id.length(); index++) {
            char value = id.charAt(index);
            if (value < 0x21 || value > 0x7e) {
                throw new IllegalStateException("播放适配器 ID 必须为可见 ASCII");
            }
        }
    }
}
