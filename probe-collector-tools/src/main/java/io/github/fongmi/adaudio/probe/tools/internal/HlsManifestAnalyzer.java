/* 严格解析有限 HLS 时间线，并用结构差异识别广告候选。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.fongmi.adaudio.probe.tools.HlsAdCandidate;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateOccurrence;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateSignal;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;
import io.github.fongmi.adaudio.probe.tools.ProbeToolErrorCode;

public final class HlsManifestAnalyzer {
    private static final long MIN_CANDIDATE_MS = 2_000L;
    private static final long MAX_CANDIDATE_MS = 600_000L;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MIN_CONFIDENCE = 70;
    private static final int MAX_MASTER_DEPTH = 3;
    private static final int MAX_VARIANTS = 256;
    private static final int MAX_SEGMENTS = 20_000;
    private static final long MAX_EXPANDED_URL_CHARS = 8L * 1024L * 1024L;
    private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
    private static final Pattern METHOD_ATTRIBUTE = Pattern.compile("(?:^|,)METHOD=([^,]+)");
    private static final Pattern KEYFORMAT_ATTRIBUTE = Pattern.compile("(?:^|,)KEYFORMAT=\"([^\"]+)\"");

    public interface Loader {
        LoadedManifest load(String url, Cancellation cancellation) throws IOException;
    }

    public interface Cancellation {
        void check() throws IOException;
    }

    public static final class LoadedManifest {
        final String url;
        final String text;

        public LoadedManifest(String url, String text) {
            if (url == null || text == null) throw new IllegalArgumentException("清单不能为空");
            this.url = url;
            this.text = text;
        }
    }

    public HlsScanResult scan(long sessionId, String inputUrl, Loader loader,
                              Cancellation cancellation) throws IOException {
        if (sessionId <= 0L || inputUrl == null || loader == null || cancellation == null) {
            throw new IllegalArgumentException("扫描参数不能为空");
        }
        LoadedManifest media = resolveMediaPlaylist(inputUrl, loader, cancellation, 0);
        ParsedPlaylist parsed = parseMediaPlaylist(media.text, media.url, cancellation);
        if (!parsed.endList) {
            throw new HlsScanException(ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED,
                    false, "HLS 清单没有 EXT-X-ENDLIST，仅支持普通点播");
        }
        if (parsed.segments.isEmpty()) {
            throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                    false, "媒体清单没有可分析的分片");
        }
        List<SegmentRun> runs = buildRuns(parsed.segments, cancellation);
        SegmentRun main = Collections.max(runs, new Comparator<SegmentRun>() {
            @Override public int compare(SegmentRun left, SegmentRun right) {
                return Long.compare(left.endMs - left.startMs, right.endMs - right.startMs);
            }
        });
        Map<String, Integer> repetitions = countSequences(runs);
        Map<String, MutableCandidate> grouped = new LinkedHashMap<>();
        for (SegmentRun run : runs) {
            cancellation.check();
            if (run == main) continue;
            long durationMs = run.endMs - run.startMs;
            if (durationMs < MIN_CANDIDATE_MS || durationMs > MAX_CANDIDATE_MS) continue;
            Set<HlsCandidateSignal> signals = EnumSet.noneOf(HlsCandidateSignal.class);
            int confidence = score(run, main,
                    repetitions.get(run.sequenceKey) > 1, durationMs, signals);
            if (confidence < MIN_CONFIDENCE) continue;
            MutableCandidate candidate = grouped.get(run.sequenceKey);
            if (candidate == null) {
                candidate = new MutableCandidate("auto-ad-" + hashKey(run.sequenceKey),
                        durationMs, confidence);
                grouped.put(run.sequenceKey, candidate);
            }
            candidate.confidence = Math.max(candidate.confidence, confidence);
            candidate.signals.addAll(signals);
            candidate.occurrences.add(new HlsCandidateOccurrence(
                    run.startMs, run.endMs, run.segmentCount));
        }
        List<HlsAdCandidate> candidates = new ArrayList<>(grouped.size());
        for (MutableCandidate candidate : grouped.values()) candidates.add(candidate.freeze());
        Collections.sort(candidates, new Comparator<HlsAdCandidate>() {
            @Override public int compare(HlsAdCandidate left, HlsAdCandidate right) {
                return Long.compare(left.getOccurrences().get(0).getStartMs(),
                        right.getOccurrences().get(0).getStartMs());
            }
        });
        return new HlsScanResult(sessionId, media.url, parsed.totalDurationMs,
                parsed.discontinuityCount, candidates);
    }

    private LoadedManifest resolveMediaPlaylist(String url, Loader loader,
                                                Cancellation cancellation, int depth)
            throws IOException {
        cancellation.check();
        if (depth > MAX_MASTER_DEPTH) {
            throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                    false, "HLS 主清单嵌套超过 3 层");
        }
        LoadedManifest loaded = loader.load(url, cancellation);
        String text = loaded.text.startsWith("\uFEFF") ? loaded.text.substring(1) : loaded.text;
        if (!text.startsWith("#EXTM3U")) {
            throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                    false, "返回内容不是有效 HLS 清单");
        }
        if (text.contains("#EXTINF:")) return new LoadedManifest(loaded.url, text);

        String[] lines = text.split("\\r?\\n");
        List<Variant> variants = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            cancellation.check();
            String line = lines[index].trim();
            if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                rejectDrm(line.substring(19));
                continue;
            }
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
            if (variants.size() >= MAX_VARIANTS) {
                throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                        false, "HLS 主清单变体数量超过上限");
            }
            Matcher matcher = BANDWIDTH.matcher(line.substring(18));
            long bandwidth = matcher.find() ? parseLong(matcher.group(1)) : 0L;
            int next = index + 1;
            while (next < lines.length
                    && (lines[next].trim().isEmpty() || lines[next].trim().startsWith("#"))) {
                next++;
            }
            if (next < lines.length) {
                variants.add(new Variant(bandwidth, resolve(loaded.url, lines[next].trim())));
            }
        }
        if (variants.isEmpty()) {
            throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                    false, "HLS 主清单没有可用媒体变体");
        }
        Collections.sort(variants, new Comparator<Variant>() {
            @Override public int compare(Variant left, Variant right) {
                return Long.compare(right.bandwidth, left.bandwidth);
            }
        });
        return resolveMediaPlaylist(variants.get(0).url, loader, cancellation, depth + 1);
    }

    private ParsedPlaylist parseMediaPlaylist(String text, String baseUrl,
                                               Cancellation cancellation) throws IOException {
        List<Segment> segments = new ArrayList<>();
        long elapsedMs = 0L;
        Long durationMs = null;
        String key = "METHOD=NONE";
        String map = "";
        String byteRange = "";
        boolean discontinuityBefore = false;
        boolean endList = false;
        int discontinuityCount = 0;
        long expandedUrlChars = 0L;
        for (String raw : text.split("\\r?\\n")) {
            cancellation.check();
            String line = raw.trim();
            if (line.startsWith("#EXTINF:")) {
                String value = line.substring(8).split(",", 2)[0];
                try {
                    double seconds = Double.parseDouble(value);
                    if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0.0) {
                        throw new NumberFormatException();
                    }
                    durationMs = Math.round(seconds * 1000.0);
                    if (durationMs <= 0L) throw new NumberFormatException();
                } catch (NumberFormatException error) {
                    throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                            false, "EXTINF 时长无效", error);
                }
            } else if (line.startsWith("#EXT-X-KEY:")) {
                String attributes = line.substring(11);
                rejectDrm(attributes);
                key = normalizeUriAttribute(attributes, baseUrl);
            } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                rejectDrm(line.substring(19));
            } else if (line.startsWith("#EXT-X-MAP:")) {
                map = normalizeUriAttribute(line.substring(11), baseUrl);
            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                byteRange = line.substring(17).trim();
            } else if (line.equals("#EXT-X-DISCONTINUITY")) {
                discontinuityBefore = true;
                discontinuityCount++;
            } else if (line.equals("#EXT-X-ENDLIST")) {
                endList = true;
            } else if (!line.isEmpty() && !line.startsWith("#") && durationMs != null) {
                if (segments.size() >= MAX_SEGMENTS) {
                    throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                            false, "HLS 媒体分片数量超过上限");
                }
                if (elapsedMs > MAX_SAFE_INTEGER - durationMs) {
                    throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                            false, "HLS 时间线超出安全整数范围");
                }
                String uri = resolve(baseUrl, line);
                expandedUrlChars += uri.length();
                if (expandedUrlChars > MAX_EXPANDED_URL_CHARS) {
                    throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                            false, "HLS 展开后的分片 URL 总量超过上限");
                }
                segments.add(new Segment(elapsedMs, durationMs, uri, byteRange,
                        mediaGroup(uri), key, map, discontinuityBefore));
                elapsedMs += durationMs;
                durationMs = null;
                byteRange = "";
                discontinuityBefore = false;
            }
        }
        if (durationMs != null) {
            throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                    false, "EXTINF 后缺少媒体分片 URI");
        }
        return new ParsedPlaylist(segments, elapsedMs, discontinuityCount, endList);
    }

    private List<SegmentRun> buildRuns(List<Segment> segments, Cancellation cancellation)
            throws IOException {
        List<SegmentRun> runs = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            cancellation.check();
            Segment segment = segments.get(index);
            String signature = segment.group + "|" + segment.key + "|" + segment.map;
            SegmentRun previous = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            boolean split = previous == null || segment.discontinuityBefore
                    || !previous.signature.equals(signature);
            if (split) runs.add(new SegmentRun(segment, signature));
            else previous.append(segment);
        }
        for (int index = 0; index < runs.size() - 1; index++) {
            runs.get(index).discontinuityAfter = runs.get(index + 1).discontinuityBefore;
        }
        for (SegmentRun run : runs) run.seal();
        return runs;
    }

    private int score(SegmentRun run, SegmentRun main, boolean repeated, long durationMs,
                      Set<HlsCandidateSignal> signals) {
        int score = 0;
        if (run.discontinuityBefore) {
            score += 25; signals.add(HlsCandidateSignal.DISCONTINUITY_BEFORE);
        }
        if (run.discontinuityAfter) {
            score += 25; signals.add(HlsCandidateSignal.DISCONTINUITY_AFTER);
        }
        if (!run.group.equals(main.group)) {
            score += 20; signals.add(HlsCandidateSignal.SOURCE_GROUP_CHANGED);
        }
        if (!run.key.equals(main.key)) {
            score += 20; signals.add(HlsCandidateSignal.ENCRYPTION_CHANGED);
        }
        if (!run.map.equals(main.map)) {
            score += 10; signals.add(HlsCandidateSignal.INIT_SEGMENT_CHANGED);
        }
        if (repeated) {
            score += 40; signals.add(HlsCandidateSignal.REPEATED_SEQUENCE);
        }
        if (durationMs <= 120_000L) {
            score += 10; signals.add(HlsCandidateSignal.COMMON_AD_DURATION);
        }
        return Math.min(100, score);
    }

    private Map<String, Integer> countSequences(List<SegmentRun> runs) {
        Map<String, Integer> counts = new HashMap<>();
        for (SegmentRun run : runs) {
            Integer count = counts.get(run.sequenceKey);
            counts.put(run.sequenceKey, count == null ? 1 : count + 1);
        }
        return counts;
    }

    private void rejectDrm(String attributes) throws HlsScanException {
        Matcher methodMatcher = METHOD_ATTRIBUTE.matcher(attributes);
        String method = methodMatcher.find() ? methodMatcher.group(1).trim() : "";
        Matcher keyFormatMatcher = KEYFORMAT_ATTRIBUTE.matcher(attributes);
        String keyFormat = keyFormatMatcher.find() ? keyFormatMatcher.group(1).trim() : "identity";
        if (method.toUpperCase(Locale.US).startsWith("SAMPLE-AES")
                || !"identity".equalsIgnoreCase(keyFormat)) {
            throw new HlsScanException(ProbeToolErrorCode.DRM_NOT_SUPPORTED,
                    false, "HLS 清单包含不支持的 DRM 密钥格式");
        }
    }

    private String normalizeUriAttribute(String attributes, String baseUrl) throws IOException {
        Matcher matcher = URI_ATTRIBUTE.matcher(attributes);
        if (!matcher.find()) return attributes;
        return matcher.replaceFirst("URI=\"" + Matcher.quoteReplacement(
                resolve(baseUrl, matcher.group(1))) + "\"");
    }

    private String mediaGroup(String raw) throws IOException {
        try {
            URI uri = new URI(raw);
            String path = uri.getPath() == null ? "/" : uri.getPath();
            int slash = path.lastIndexOf('/');
            String directory = slash >= 0 ? path.substring(0, slash + 1) : "/";
            return new URI(uri.getScheme(), uri.getAuthority(), directory, null, null).toString();
        } catch (URISyntaxException error) {
            throw new HlsScanException(ProbeToolErrorCode.INVALID_REQUEST,
                    false, "分片 URL 无效", error);
        }
    }

    private static String resolve(String baseUrl, String value) throws IOException {
        try {
            URI resolved = new URI(baseUrl).resolve(value);
            String scheme = resolved.getScheme();
            if (scheme == null || resolved.getRawAuthority() == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new URISyntaxException(resolved.toString(), "仅支持 HTTP(S)");
            }
            if (resolved.toString().length() > 8192) {
                throw new URISyntaxException(resolved.toString(), "URL 过长");
            }
            return resolved.toString();
        } catch (URISyntaxException error) {
            throw new HlsScanException(ProbeToolErrorCode.INVALID_REQUEST,
                    false, "HLS URL 无效", error);
        }
    }

    private static String segmentIdentity(Segment segment) {
        return segment.uri + "|" + segment.byteRange + "|" + segment.durationMs;
    }

    private static String hashKey(String input) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < input.length(); index++) {
            hash ^= input.charAt(index);
            hash *= 0x100000001b3L;
        }
        return String.format(Locale.US, "%016x", hash);
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static final class Variant {
        final long bandwidth;
        final String url;
        Variant(long bandwidth, String url) { this.bandwidth = bandwidth; this.url = url; }
    }

    private static final class ParsedPlaylist {
        final List<Segment> segments;
        final long totalDurationMs;
        final int discontinuityCount;
        final boolean endList;
        ParsedPlaylist(List<Segment> segments, long totalDurationMs,
                       int discontinuityCount, boolean endList) {
            this.segments = segments;
            this.totalDurationMs = totalDurationMs;
            this.discontinuityCount = discontinuityCount;
            this.endList = endList;
        }
    }

    private static final class Segment {
        final long startMs;
        final long durationMs;
        final String uri;
        final String byteRange;
        final String group;
        final String key;
        final String map;
        final boolean discontinuityBefore;
        Segment(long startMs, long durationMs, String uri, String byteRange,
                String group, String key, String map, boolean discontinuityBefore) {
            this.startMs = startMs;
            this.durationMs = durationMs;
            this.uri = uri;
            this.byteRange = byteRange;
            this.group = group;
            this.key = key;
            this.map = map;
            this.discontinuityBefore = discontinuityBefore;
        }
    }

    private static final class SegmentRun {
        final long startMs;
        long endMs;
        int segmentCount;
        final String group;
        final String key;
        final String map;
        final String signature;
        final boolean discontinuityBefore;
        boolean discontinuityAfter;
        final StringBuilder sequenceBuilder;
        String sequenceKey;
        SegmentRun(Segment segment, String signature) {
            this.startMs = segment.startMs;
            this.endMs = segment.startMs + segment.durationMs;
            this.segmentCount = 1;
            this.group = segment.group;
            this.key = segment.key;
            this.map = segment.map;
            this.signature = signature;
            this.discontinuityBefore = segment.discontinuityBefore;
            this.sequenceBuilder = new StringBuilder(segmentIdentity(segment));
        }
        void append(Segment segment) {
            endMs = segment.startMs + segment.durationMs;
            segmentCount++;
            sequenceBuilder.append('\n').append(segmentIdentity(segment));
        }
        void seal() {
            sequenceKey = sequenceBuilder.toString();
        }
    }

    private static final class MutableCandidate {
        final String id;
        final long durationMs;
        int confidence;
        final Set<HlsCandidateSignal> signals = EnumSet.noneOf(HlsCandidateSignal.class);
        final List<HlsCandidateOccurrence> occurrences = new ArrayList<>();
        MutableCandidate(String id, long durationMs, int confidence) {
            this.id = id;
            this.durationMs = durationMs;
            this.confidence = confidence;
        }
        HlsAdCandidate freeze() {
            return new HlsAdCandidate(id, durationMs, confidence, signals, occurrences);
        }
    }
}
