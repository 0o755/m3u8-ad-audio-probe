/* 有限点播时间线门闩：准备期只记录快照，解码就绪后才作终局裁决。 */
package io.github.fongmi.adaudio.probe.adapter.internal;

/** SDK 内部共享的时间线稳定状态机，不属于宿主公开扩展面。 */
public final class FiniteVodTimelineGate {
    public enum Decision { PENDING, VOD_CONFIRMED, UNSUPPORTED }

    private boolean ready;
    private boolean seen;
    private boolean live;
    private boolean dynamic;
    private long durationMs = -1L;

    public synchronized void reset() {
        ready = false;
        seen = false;
        live = false;
        dynamic = false;
        durationMs = -1L;
    }

    public synchronized Decision update(long newDurationMs,
                                        boolean newLive, boolean newDynamic) {
        seen = true;
        live = newLive;
        dynamic = newDynamic;
        durationMs = Math.max(-1L, newDurationMs);
        return decision();
    }

    public synchronized Decision markReady() {
        ready = true;
        return decision();
    }

    public synchronized boolean isVodConfirmed() {
        return decision() == Decision.VOD_CONFIRMED;
    }

    public synchronized boolean isReady() {
        return ready;
    }

    public synchronized long getDurationMs() {
        return durationMs;
    }

    private Decision decision() {
        if (!ready || !seen) return Decision.PENDING;
        return live || dynamic ? Decision.UNSUPPORTED : Decision.VOD_CONFIRMED;
    }
}
