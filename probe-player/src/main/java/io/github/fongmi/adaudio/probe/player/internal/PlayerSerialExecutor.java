/* 串行执行器避免线程池打乱播放器宿主回调顺序。 */
package io.github.fongmi.adaudio.probe.player.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

/** 在任意宿主 Executor 上维持严格 FIFO 的轻量执行器。 */
public final class PlayerSerialExecutor implements Executor {
    /** 后继任务被宿主 Executor 拒绝时接收一次清理通知。 */
    @FunctionalInterface
    public interface RejectionHandler {
        void onRejected(RuntimeException error);
    }

    private final Executor delegate;
    private final Queue<Task> tasks = new ArrayDeque<>();
    private Task active;

    public PlayerSerialExecutor(Executor delegate) {
        if (delegate == null) throw new IllegalArgumentException("宿主 Executor 不能为空");
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        enqueue(command, null);
    }

    /**
     * 排队任务即使在后续串行切换阶段被拒绝，也会收到一次清理通知。
     *
     * @return {@code false} 仅表示首次派发已同步失败
     */
    public boolean tryExecute(Runnable command, RejectionHandler rejectionHandler) {
        try {
            enqueue(command, rejectionHandler);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void enqueue(Runnable command, RejectionHandler rejectionHandler) {
        if (command == null) return;
        Task first = null;
        synchronized (this) {
            tasks.offer(new Task(command, rejectionHandler));
            if (active == null) {
                active = tasks.poll();
                first = active;
            }
        }
        if (first != null) dispatch(first, true);
    }

    private void scheduleNext() {
        Task next;
        synchronized (this) {
            active = tasks.poll();
            next = active;
        }
        if (next != null) dispatch(next, false);
    }

    private void dispatch(Task next, boolean propagateRejection) {
        try {
            delegate.execute(next);
        } catch (RuntimeException error) {
            List<Task> rejected = new ArrayList<>();
            synchronized (this) {
                // 直接 Executor 可能先执行任务再抛错，不能重复清理已经运行的任务。
                if (active == next) {
                    active = null;
                    rejected.add(next);
                    Task queued;
                    while ((queued = tasks.poll()) != null) rejected.add(queued);
                }
            }
            for (Task task : rejected) task.reject(error);
            if (propagateRejection || rejected.isEmpty()) throw error;
        }
    }

    private final class Task implements Runnable {
        private final Runnable command;
        private final RejectionHandler rejectionHandler;

        Task(Runnable command, RejectionHandler rejectionHandler) {
            this.command = command;
            this.rejectionHandler = rejectionHandler;
        }

        @Override
        public void run() {
            try {
                command.run();
            } finally {
                scheduleNext();
            }
        }

        void reject(RuntimeException error) {
            if (rejectionHandler == null) return;
            try {
                rejectionHandler.onRejected(error);
            } catch (RuntimeException ignored) {
                // 一个清理回调异常不能阻断其余拒绝任务。
            }
        }
    }
}
