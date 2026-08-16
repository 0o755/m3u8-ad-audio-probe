/* 宿主回调门闩把会话切换与最终回调校验线性化，避免旧媒体请求跨代执行。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

public final class CallbackGate {
    @FunctionalInterface
    public interface ValueAction<T> {
        T run();
    }

    @FunctionalInterface
    public interface Condition {
        boolean isValid();
    }

    private final Object monitor = new Object();

    public <T> T update(ValueAction<T> action) {
        if (action == null) throw new IllegalArgumentException("门闩操作不能为空");
        synchronized (monitor) {
            return action.run();
        }
    }

    public void update(Runnable action) {
        if (action == null) throw new IllegalArgumentException("门闩操作不能为空");
        synchronized (monitor) {
            action.run();
        }
    }

    /** 条件与回调在同一临界区完成，代际更新只能发生在回调之前或之后。 */
    public boolean invokeIf(Condition condition, Runnable callback) {
        if (condition == null || callback == null) {
            throw new IllegalArgumentException("回调条件和操作不能为空");
        }
        synchronized (monitor) {
            if (!condition.isValid()) return false;
            callback.run();
            return true;
        }
    }
}
