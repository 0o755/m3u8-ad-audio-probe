/* 验证 Media3 播放回调边界同时截获运行时错误和链接错误。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_10.internal;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Media3PlaybackBoundaryTest {
    @Test
    public void capturesRuntimeExceptionWithoutEscaping() {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        Media3PlaybackBoundary.run(() -> {
            throw new IllegalStateException("runtime");
        }, captured::set);

        assertTrue(captured.get() instanceof IllegalStateException);
    }

    @Test
    public void capturesLinkageErrorWithoutEscaping() {
        AtomicReference<Throwable> captured = new AtomicReference<>();
        Media3PlaybackBoundary.run(() -> {
            throw new NoClassDefFoundError("media3 mismatch");
        }, captured::set);

        assertTrue(captured.get() instanceof NoClassDefFoundError);
    }

    @Test
    public void failureHandlerCannotBreakOuterLooperBoundary() {
        AtomicInteger handled = new AtomicInteger();
        Media3PlaybackBoundary.run(() -> {
            throw new IllegalStateException("primary");
        }, error -> {
            handled.incrementAndGet();
            throw new NoSuchMethodError("diagnostic mismatch");
        });

        assertEquals(1, handled.get());
    }
}
