/* 播放器异步回调边界阻止运行时和链接错误逃出 Media3 控制 Looper。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

/** 统一保护 Media3 异步回调及其失败处理器。 */
final class Media3PlaybackBoundary {
    @FunctionalInterface
    interface FailureHandler {
        void onFailure(Throwable error);
    }

    private Media3PlaybackBoundary() {
    }

    static void run(Runnable action, FailureHandler failureHandler) {
        if (action == null || failureHandler == null) {
            throw new IllegalArgumentException("播放回调边界参数不能为空");
        }
        try {
            action.run();
        } catch (RuntimeException | LinkageError error) {
            ignore(() -> failureHandler.onFailure(error));
        }
    }

    static void ignore(Runnable action) {
        if (action == null) return;
        try {
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // 最外层清理和诊断不得再次击穿播放器 Looper。
        }
    }
}
