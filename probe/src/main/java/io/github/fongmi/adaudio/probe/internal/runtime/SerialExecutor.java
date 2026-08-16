/* 串行执行器保证宿主时钟和回调顺序稳定，即使底层 Executor 是线程池。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

public final class SerialExecutor implements Executor {
    @FunctionalInterface
    public interface RejectionHandler {
        void onRejected(RuntimeException error);
    }

    private final Executor delegate;
    private final Queue<Task> tasks = new ArrayDeque<>();
    private Task active;

    public SerialExecutor(Executor delegate) {
        if (delegate == null) throw new IllegalArgumentException("宿主 Executor 不能为空");
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        enqueue(command, null);
    }

    /**
     * 排队任务即使在稍后的串行切换阶段被拒绝，也会收到一次清理通知。
     * 返回 false 仅表示首次派发已同步失败，清理通知仍已执行。
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
        // 前一任务已经正常完成，后继拒绝只负责清理，不能反向污染宿主工作线程。
        if (next != null) dispatch(next, false);
    }

    private void dispatch(Task next, boolean propagateRejection) {
        try {
            delegate.execute(next);
        } catch (RuntimeException error) {
            List<Task> rejected = new ArrayList<>();
            synchronized (this) {
                // 直接 Executor 可能先运行任务再抛错，此时后继任务已自行处理。
                if (active == next) {
                    active = null;
                    rejected.add(next);
                    Task queued;
                    while ((queued = tasks.poll()) != null) rejected.add(queued);
                }
            }
            for (Task task : rejected) task.reject(error);
            // 没有任务被拒绝说明异常来自同步执行的 command，仍应按原语义传播。
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
                // 一个清理回调异常不能阻止其余被丢弃任务释放资源。
            }
        }
    }
}
