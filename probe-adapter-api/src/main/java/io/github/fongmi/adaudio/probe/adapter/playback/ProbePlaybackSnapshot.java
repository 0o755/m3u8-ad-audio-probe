/* 不可变快照让高层门面无阻塞读取适配器时间轴。 */
package io.github.fongmi.adaudio.probe.adapter.playback;

/** 可见播放适配器在控制线程生成的不可变时间轴快照。 */
public final class ProbePlaybackSnapshot {
    /** 尚未得知媒体时长时使用的值。 */
    public static final long TIME_UNSET = -1L;

    private final long positionMs;
    private final long bufferedPositionMs;
    private final long durationMs;
    private final boolean playing;

    /**
     * 创建快照；缓冲位置允许在时间轴重建期间短暂落后于当前位置。
     */
    public ProbePlaybackSnapshot(long positionMs, long bufferedPositionMs,
                                 long durationMs, boolean playing) {
        if (positionMs < 0L) throw new IllegalArgumentException("播放位置不能为负数");
        if (bufferedPositionMs < 0L) throw new IllegalArgumentException("缓冲位置不能为负数");
        if (durationMs < 0L && durationMs != TIME_UNSET) {
            throw new IllegalArgumentException("媒体时长必须非负或为 TIME_UNSET");
        }
        this.positionMs = positionMs;
        this.bufferedPositionMs = bufferedPositionMs;
        this.durationMs = durationMs;
        this.playing = playing;
    }

    /** 返回当前播放位置，单位毫秒。 */
    public long getPositionMs() {
        return positionMs;
    }

    /** 返回当前缓冲位置，单位毫秒。 */
    public long getBufferedPositionMs() {
        return bufferedPositionMs;
    }

    /** 返回媒体时长，未知时为 {@link #TIME_UNSET}。 */
    public long getDurationMs() {
        return durationMs;
    }

    /** 返回播放器此刻是否正在推进时间轴。 */
    public boolean isPlaying() {
        return playing;
    }
}
