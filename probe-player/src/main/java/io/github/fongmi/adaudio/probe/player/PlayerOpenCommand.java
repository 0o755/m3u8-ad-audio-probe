/* 打开命令保证 PREPARING 通知入队后才执行新会话的适配器打开。 */
package io.github.fongmi.adaudio.probe.player;

/** 控制线程内固定新会话通知与适配器打开的先后顺序。 */
final class PlayerOpenCommand implements Runnable {
    private final Runnable preparing;
    private final Runnable openAdapter;

    PlayerOpenCommand(Runnable preparing, Runnable openAdapter) {
        if (preparing == null || openAdapter == null) {
            throw new IllegalArgumentException("打开命令不能为空");
        }
        this.preparing = preparing;
        this.openAdapter = openAdapter;
    }

    @Override
    public void run() {
        preparing.run();
        openAdapter.run();
    }
}
