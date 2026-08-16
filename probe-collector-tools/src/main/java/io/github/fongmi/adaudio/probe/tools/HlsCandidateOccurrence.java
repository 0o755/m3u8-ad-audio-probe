/* 单次候选出现记录保留精确时间范围和分片数量。 */
package io.github.fongmi.adaudio.probe.tools;

/** 一个 HLS 广告候选在媒体时间线上的不可变出现记录。 */
public final class HlsCandidateOccurrence {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final long startMs;
    private final long endMs;
    private final int segmentCount;

    /**
     * 创建候选出现记录。
     *
     * @param startMs 开始位置，单位毫秒
     * @param endMs 结束位置，单位毫秒
     * @param segmentCount 区间媒体分片数量
     */
    public HlsCandidateOccurrence(long startMs, long endMs, int segmentCount) {
        if (startMs < 0L || endMs <= startMs || endMs > MAX_SAFE_INTEGER
                || segmentCount <= 0 || segmentCount > 100_000) {
            throw new IllegalArgumentException("候选时间范围或分片数量无效");
        }
        this.startMs = startMs;
        this.endMs = endMs;
        this.segmentCount = segmentCount;
    }

    /** @return 开始位置，单位毫秒 */
    public long getStartMs() { return startMs; }

    /** @return 结束位置，单位毫秒 */
    public long getEndMs() { return endMs; }

    /** @return 区间时长，单位毫秒 */
    public long getDurationMs() { return endMs - startMs; }

    /** @return 区间包含的媒体分片数量 */
    public int getSegmentCount() { return segmentCount; }
}
