/* 串行化宿主回调，避免线程池打乱进度与终态顺序。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public final class SerialCallbackExecutor implements Executor {
    private final Executor delegate;
    private final Queue<Runnable> queue = new ArrayDeque<>();
    private boolean running;

    public SerialCallbackExecutor(Executor delegate) {
        if (delegate == null) throw new IllegalArgumentException("回调 Executor 不能为空");
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        if (command == null) return;
        boolean dispatch;
        synchronized (queue) {
            queue.offer(command);
            dispatch = !running;
            if (dispatch) running = true;
        }
        if (dispatch) dispatchNext();
    }

    private void dispatchNext() {
        final Runnable next;
        synchronized (queue) {
            next = queue.peek();
            if (next == null) {
                running = false;
                return;
            }
        }
        try {
            delegate.execute(new Runnable() {
                @Override public void run() {
                    try {
                        next.run();
                    } catch (Throwable failure) {
                        rethrowFatal(failure);
                        // 宿主通知异常不能击穿适配器、网络或后继回调线程。
                    }
                    finally {
                        synchronized (queue) { queue.poll(); }
                        dispatchNext();
                    }
                }
            });
        } catch (Throwable rejected) {
            rethrowFatal(rejected);
            synchronized (queue) {
                // 直接执行器可能先运行再抛错，此时队头已经由回调自行推进。
                if (queue.peek() == next) {
                    queue.clear();
                    running = false;
                }
            }
        }
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError) throw (VirtualMachineError) failure;
        if (failure instanceof ThreadDeath) throw (ThreadDeath) failure;
    }
}
