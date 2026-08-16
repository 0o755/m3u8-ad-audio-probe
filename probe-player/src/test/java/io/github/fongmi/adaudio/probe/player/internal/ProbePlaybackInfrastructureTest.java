/* 验证播放适配器解析和宿主回调串行器的关键失败边界。 */
package io.github.fongmi.adaudio.probe.player.internal;

import android.content.Context;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapter;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterFactory;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProbePlaybackInfrastructureTest {
    @Test
    public void explicitFactoryBypassesServiceDiscovery() {
        ProbePlaybackAdapterFactory factory = new StubFactory(1, "custom-player");
        assertSame(factory, ProbePlaybackAdapterResolver.resolve(factory));
    }

    @Test
    public void incompatibleSpiFailsBeforeFactoryCreation() {
        try {
            ProbePlaybackAdapterResolver.resolve(new StubFactory(2, "future-player"));
            fail("应拒绝不兼容 SPI");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("SPI 版本不兼容"));
        }
    }

    @Test
    public void delayedDelegateRejectionCleansQueuedCallbacksAndRecovers() {
        RejectingExecutor delegate = new RejectingExecutor();
        PlayerSerialExecutor serial = new PlayerSerialExecutor(delegate);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        serial.execute(ran::incrementAndGet);
        assertTrue(serial.tryExecute(ran::incrementAndGet,
                error -> rejected.incrementAndGet()));
        assertTrue(serial.tryExecute(ran::incrementAndGet,
                error -> rejected.incrementAndGet()));
        delegate.reject = true;
        delegate.runNext();

        assertEquals(1, ran.get());
        assertEquals(2, rejected.get());

        delegate.reject = false;
        serial.execute(ran::incrementAndGet);
        delegate.runNext();
        assertEquals(2, ran.get());
    }

    @Test
    public void immediateDelegateRejectionRunsCleanup() {
        RejectingExecutor delegate = new RejectingExecutor();
        delegate.reject = true;
        PlayerSerialExecutor serial = new PlayerSerialExecutor(delegate);
        AtomicInteger rejected = new AtomicInteger();

        assertFalse(serial.tryExecute(() -> fail("被拒绝任务不得运行"),
                error -> rejected.incrementAndGet()));
        assertEquals(1, rejected.get());
    }

    private static final class StubFactory implements ProbePlaybackAdapterFactory {
        private final int spiVersion;
        private final String id;

        StubFactory(int spiVersion, String id) {
            this.spiVersion = spiVersion;
            this.id = id;
        }

        @Override public String getId() { return id; }
        @Override public int getPlaybackSpiVersion() { return spiVersion; }
        @Override public ProbePlaybackAdapter create(Context context, Looper looper,
                                                     ProbePlaybackAdapter.Listener listener) {
            throw new AssertionError("解析阶段不应创建适配器");
        }
    }

    private static final class RejectingExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean reject;

        @Override
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("测试拒绝");
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
