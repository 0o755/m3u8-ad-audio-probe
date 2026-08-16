/* HLS 候选按稳定 ID 聚合所有出现位置、置信度和结构信号。 */
package io.github.fongmi.adaudio.probe.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.EnumSet;

/** 一个普通 HLS VOD 中的不可变结构型广告候选。 */
public final class HlsAdCandidate {
    private final String id;
    private final long durationMs;
    private final int confidence;
    private final Set<HlsCandidateSignal> signals;
    private final List<HlsCandidateOccurrence> occurrences;

    /**
     * 创建结构候选；通常只由扫描器调用。
     *
     * @param id 稳定的 {@code auto-ad-xxxxxxxxxxxxxxxx} 候选 ID
     * @param durationMs 分片序列时长
     * @param confidence 0 到 100 的结构置信度
     * @param signals 非空结构信号集合
     * @param occurrences 按开始位置排序的非空出现记录
     */
    public HlsAdCandidate(String id, long durationMs, int confidence,
                          Set<HlsCandidateSignal> signals,
                          List<HlsCandidateOccurrence> occurrences) {
        if (id == null || !id.matches("auto-ad-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("候选 ID 无效");
        }
        if (durationMs < 2000L || durationMs > 600_000L
                || confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("候选时长或置信度无效");
        }
        if (signals == null || signals.isEmpty()
                || occurrences == null || occurrences.isEmpty()) {
            throw new IllegalArgumentException("候选信号和出现位置不能为空");
        }
        long previousStart = -1L;
        for (HlsCandidateOccurrence occurrence : occurrences) {
            if (occurrence == null || occurrence.getDurationMs() != durationMs
                    || occurrence.getStartMs() <= previousStart) {
                throw new IllegalArgumentException("候选出现位置无效、未排序或时长不一致");
            }
            previousStart = occurrence.getStartMs();
        }
        this.id = id;
        this.durationMs = durationMs;
        this.confidence = confidence;
        this.signals = Collections.unmodifiableSet(EnumSet.copyOf(signals));
        this.occurrences = Collections.unmodifiableList(new ArrayList<>(occurrences));
    }

    /** @return 稳定候选 ID */
    public String getId() { return id; }

    /** @return 该分片序列的时长，单位毫秒 */
    public long getDurationMs() { return durationMs; }

    /** @return 0 到 100 的结构置信度 */
    public int getConfidence() { return confidence; }

    /** @return 不可变结构信号集合 */
    public Set<HlsCandidateSignal> getSignals() { return signals; }

    /** @return 按时间排序的不可变出现记录 */
    public List<HlsCandidateOccurrence> getOccurrences() { return occurrences; }
}
