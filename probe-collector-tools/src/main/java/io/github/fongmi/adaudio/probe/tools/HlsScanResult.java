/* 扫描结果明确最终媒体清单地址与有限点播时间线。 */
package io.github.fongmi.adaudio.probe.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 普通 HLS VOD 候选扫描的不可变结果。 */
public final class HlsScanResult {
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private final long sessionId;
    private final String mediaPlaylistUrl;
    private final long totalDurationMs;
    private final int discontinuityCount;
    private final List<HlsAdCandidate> candidates;

    /**
     * 创建完整扫描结果；通常只由扫描器调用。
     *
     * @param sessionId 所属正会话 ID
     * @param mediaPlaylistUrl 实际解析的 HTTP(S) 媒体清单地址
     * @param totalDurationMs 有限点播总时长
     * @param discontinuityCount 时间线断点数量
     * @param candidates 按首次出现位置排序的候选
     */
    public HlsScanResult(long sessionId, String mediaPlaylistUrl, long totalDurationMs,
                         int discontinuityCount, List<HlsAdCandidate> candidates) {
        if (sessionId <= 0L || totalDurationMs <= 0L || totalDurationMs > MAX_SAFE_INTEGER
                || discontinuityCount < 0 || discontinuityCount > 100_000
                || candidates == null) {
            throw new IllegalArgumentException("HLS 扫描结果无效");
        }
        validateUrl(mediaPlaylistUrl);
        Set<String> ids = new HashSet<>();
        long previousStart = -1L;
        for (HlsAdCandidate candidate : candidates) {
            if (candidate == null || !ids.add(candidate.getId())) {
                throw new IllegalArgumentException("HLS 候选不能为空或 ID 重复");
            }
            long start = candidate.getOccurrences().get(0).getStartMs();
            if (start <= previousStart) throw new IllegalArgumentException("HLS 候选未按时间排序");
            for (HlsCandidateOccurrence occurrence : candidate.getOccurrences()) {
                if (occurrence.getEndMs() > totalDurationMs) {
                    throw new IllegalArgumentException("HLS 候选超出媒体时间线");
                }
            }
            previousStart = start;
        }
        this.sessionId = sessionId;
        this.mediaPlaylistUrl = mediaPlaylistUrl;
        this.totalDurationMs = totalDurationMs;
        this.discontinuityCount = discontinuityCount;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    /** @return 所属扫描会话 ID */
    public long getSessionId() { return sessionId; }

    /** @return 解析主清单后实际扫描的媒体清单 URL */
    public String getMediaPlaylistUrl() { return mediaPlaylistUrl; }

    /** @return 有限点播总时长，单位毫秒 */
    public long getTotalDurationMs() { return totalDurationMs; }

    /** @return 清单中的时间线断点数量 */
    public int getDiscontinuityCount() { return discontinuityCount; }

    /** @return 按首次出现位置排序的不可变候选列表 */
    public List<HlsAdCandidate> getCandidates() { return candidates; }

    private static void validateUrl(String value) {
        if (value == null || value.isEmpty() || value.length() > 8192) {
            throw new IllegalArgumentException("媒体清单 URL 无效");
        }
        try {
            URI uri = new URI(value).parseServerAuthority();
            String scheme = uri.getScheme();
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("媒体清单 URL 无效");
            }
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("媒体清单 URL 无效", error);
        }
    }
}
