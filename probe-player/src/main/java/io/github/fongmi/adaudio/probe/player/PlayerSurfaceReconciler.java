/* Surface 对账器在控制线程维护适配器实际持有的输出对象。 */
package io.github.fongmi.adaudio.probe.player;

/**
 * 将宿主最新期望输出与适配器实际输出按对象身份对账。
 *
 * <p>调用方必须只在同一控制线程调用本类。清除成功后会再次读取期望值，避免快速
 * attach/clear 把已经取消的 Surface 重新附加。</p>
 */
final class PlayerSurfaceReconciler<T> {
    private T applied;

    /** 读取可由其他线程更新的最新期望输出。 */
    @FunctionalInterface
    interface Desired<T> {
        T get();
    }

    /** 在适配器边界执行实际的附加和清除。 */
    interface Target<T> {
        void attach(T value);

        void clear(T value);
    }

    /**
     * 对账一次；适配器异常会原样抛出，并保守保留仍可能被适配器持有的对象身份。
     */
    void reconcile(Desired<T> desired, Target<T> target) {
        if (desired == null || target == null) {
            throw new IllegalArgumentException("Surface 对账参数不能为空");
        }
        T wanted = desired.get();
        if (applied == wanted) return;

        T previous = applied;
        if (previous != null) {
            target.clear(previous);
            applied = null;
        }

        // 清除可能耗时，期间宿主可能已经取消或替换了原期望 Surface。
        wanted = desired.get();
        if (wanted != null) {
            // 先记录再调用；若适配器部分成功后抛错，后续仍会尝试清除此对象。
            applied = wanted;
            target.attach(wanted);
        }
    }

    /** 返回给定对象当前是否仍可能被适配器持有。 */
    boolean isApplied(T value) {
        return value != null && applied == value;
    }

    /** 适配器完成永久关闭后清除本地持有状态。 */
    void onAdapterClosed() {
        applied = null;
    }
}
