/* 验证 Surface 快速替换、取消和失败恢复的控制线程对账语义。 */
package io.github.fongmi.adaudio.probe.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlayerSurfaceReconcilerTest {
    @Test
    public void attachThenImmediateClearStillClearsPreviouslyAppliedSurface() {
        Object oldSurface = new Object();
        Object cancelledSurface = new Object();
        AtomicReference<Object> desired = new AtomicReference<>(oldSurface);
        RecordingTarget target = new RecordingTarget();
        PlayerSurfaceReconciler<Object> reconciler = new PlayerSurfaceReconciler<>();
        reconciler.reconcile(desired::get, target);

        // attach(N) 与 clear(N) 的控制任务执行前，最新期望已经回到 null。
        desired.set(cancelledSurface);
        desired.set(null);
        reconciler.reconcile(desired::get, target);

        assertEquals("attach:old", target.events.get(0));
        assertEquals("clear:old", target.events.get(1));
        assertEquals(2, target.events.size());
        assertFalse(reconciler.isApplied(oldSurface));
        assertFalse(reconciler.isApplied(cancelledSurface));
    }

    @Test
    public void desiredChangeWhileClearingDoesNotAttachCancelledSurface() {
        Object oldSurface = new Object();
        Object cancelledSurface = new Object();
        AtomicReference<Object> desired = new AtomicReference<>(oldSurface);
        PlayerSurfaceReconciler<Object> reconciler = new PlayerSurfaceReconciler<>();
        RecordingTarget target = new RecordingTarget();
        reconciler.reconcile(desired::get, target);

        desired.set(cancelledSurface);
        target.onClear = () -> desired.set(null);
        reconciler.reconcile(desired::get, target);

        assertEquals(2, target.events.size());
        assertFalse(reconciler.isApplied(cancelledSurface));
    }

    @Test
    public void clearFailureKeepsAppliedIdentityAndNeverClaimsRelease() {
        Object surface = new Object();
        AtomicReference<Object> desired = new AtomicReference<>(surface);
        PlayerSurfaceReconciler<Object> reconciler = new PlayerSurfaceReconciler<>();
        RecordingTarget target = new RecordingTarget();
        reconciler.reconcile(desired::get, target);

        desired.set(null);
        target.failClear = true;
        try {
            reconciler.reconcile(desired::get, target);
            fail("适配器清除失败必须向门面传播");
        } catch (IllegalStateException expected) {
            assertTrue(reconciler.isApplied(surface));
        }
    }

    private static final class RecordingTarget
            implements PlayerSurfaceReconciler.Target<Object> {
        final List<String> events = new ArrayList<>();
        Runnable onClear;
        boolean failClear;
        Object oldSurface;

        @Override
        public void attach(Object value) {
            if (oldSurface == null) oldSurface = value;
            events.add("attach:" + (value == oldSurface ? "old" : "new"));
        }

        @Override
        public void clear(Object value) {
            events.add("clear:" + (value == oldSurface ? "old" : "new"));
            if (onClear != null) onClear.run();
            if (failClear) throw new IllegalStateException("clear failed");
        }
    }
}
