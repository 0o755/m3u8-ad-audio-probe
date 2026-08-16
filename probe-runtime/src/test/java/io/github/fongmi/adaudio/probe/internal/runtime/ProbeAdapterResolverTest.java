/* 验证显式适配器优先，缺少默认 provider 时给出确定错误。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import android.content.Context;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;

import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProbeAdapterResolverTest {
    @Test
    public void explicitFactoryBypassesServiceDiscovery() {
        ProbeAdapterFactory factory = new StubFactory();
        assertSame(factory, ProbeAdapterResolver.resolve(factory));
    }

    @Test
    public void runtimeWithoutAdapterFailsClearly() {
        try {
            ProbeAdapterResolver.resolve(null);
            fail("纯 runtime 不应伪造默认解码器");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("未找到音频探针适配器"));
        }
    }

    private static final class StubFactory implements ProbeAdapterFactory {
        @Override public int getSpiVersion() { return SPI_VERSION; }
        @Override public String getId() { return "test"; }
        @Override public ProbeAdapter create(Context context, Looper looper,
                                             ProbeAdapter.Listener listener) {
            throw new AssertionError("显式解析不应创建适配器");
        }
    }
}
