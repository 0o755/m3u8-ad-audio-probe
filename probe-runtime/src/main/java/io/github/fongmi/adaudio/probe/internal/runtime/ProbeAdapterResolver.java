/* 默认适配器通过标准服务发现装配，显式工厂始终优先且无反射依赖。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/** 解析默认或宿主显式提供的唯一适配器工厂。 */
public final class ProbeAdapterResolver {
    private ProbeAdapterResolver() {
    }

    public static ProbeAdapterFactory resolve(ProbeAdapterFactory explicitFactory) {
        if (explicitFactory != null) return explicitFactory;
        try {
            ServiceLoader<ProbeAdapterFactory> loader = ServiceLoader.load(
                    ProbeAdapterFactory.class, ProbeAdapterFactory.class.getClassLoader());
            Iterator<ProbeAdapterFactory> providers = loader.iterator();
            if (!providers.hasNext()) {
                throw new IllegalStateException("未找到音频探针适配器；请依赖默认聚合包或显式设置工厂");
            }
            ProbeAdapterFactory selected = providers.next();
            if (providers.hasNext()) {
                throw new IllegalStateException("检测到多个音频探针适配器，请通过 Builder 显式选择");
            }
            return selected;
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("音频探针适配器加载失败", error);
        }
    }
}
