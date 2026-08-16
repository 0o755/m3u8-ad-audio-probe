/* 验证后继回调被执行器拒绝时不会从前一回调的 finally 反抛。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import org.junit.Test;

import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;

public class SerialCallbackExecutorTest {
    @Test
    public void swallowsDelayedRejectionAfterFirstCallback() {
        DelayedRejectingExecutor delegate = new DelayedRejectingExecutor();
        SerialCallbackExecutor serial = new SerialCallbackExecutor(delegate);
        final int[] calls = {0};
        serial.execute(new Runnable() {
            @Override public void run() { calls[0]++; }
        });
        serial.execute(new Runnable() {
            @Override public void run() { calls[0]++; }
        });

        delegate.first.run();

        assertEquals(1, calls[0]);
    }

    @Test
    public void swallowsInitialRejection() {
        SerialCallbackExecutor serial = new SerialCallbackExecutor(new Executor() {
            @Override public void execute(Runnable command) {
                throw new IllegalStateException("rejected");
            }
        });

        serial.execute(new Runnable() {
            @Override public void run() { throw new AssertionError("must not run"); }
        });
    }

    @Test
    public void containsListenerFailureAndContinues() {
        SerialCallbackExecutor serial = new SerialCallbackExecutor(new Executor() {
            @Override public void execute(Runnable command) { command.run(); }
        });
        final int[] calls = {0};
        serial.execute(new Runnable() {
            @Override public void run() { throw new AssertionError("listener"); }
        });
        serial.execute(new Runnable() {
            @Override public void run() { calls[0]++; }
        });

        assertEquals(1, calls[0]);
    }

    private static final class DelayedRejectingExecutor implements Executor {
        Runnable first;
        int dispatches;

        @Override public void execute(Runnable command) {
            dispatches++;
            if (dispatches == 1) first = command;
            else throw new IllegalStateException("rejected");
        }
    }
}
