/* 规则解析器以严格流式 JSON 读取 Probe v1，避免 DOM 峰值和宽松语法。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FingerprintVariant;

public final class RuleSetJsonParser {
    public static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{8}$");
    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?(0|[1-9][0-9]*)$");
    private static final int MAX_HASHES_PER_VARIANT = 64;
    private static final int MAX_TEST_URL_LENGTH = 8192;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int[] REQUIRED_PHASES_MS = {0, 64, 128, 192};

    private RuleSetJsonParser() {
    }

    /** 检查上限后复制调用方字节，避免异步解析期间被外部修改。 */
    public static byte[] copyDocument(byte[] source) {
        if (source == null) throw new IllegalArgumentException("规则输入不能为空");
        if (source.length == 0) throw new IllegalArgumentException("规则内容不能为空");
        if (source.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("规则超过 4 MiB");
        }
        return Arrays.copyOf(source, source.length);
    }

    /** 在分配 UTF-8 字节前计算准确大小，并拒绝不成对的 UTF-16 代理项。 */
    public static byte[] encodeDocument(String source) {
        if (source == null) throw new IllegalArgumentException("规则输入不能为空");
        int byteCount = utf8Length(source);
        if (byteCount == 0) throw new IllegalArgumentException("规则内容不能为空");
        if (byteCount > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("规则超过 4 MiB");
        }
        return source.getBytes(StandardCharsets.UTF_8);
    }

    /** 从严格 UTF-8 字节解析 Probe rules-v1。 */
    public static AdRuleSet parseUtf8(byte[] source) throws IOException {
        byte[] owned = copyDocument(source);
        try (Reader reader = openUtf8Reader(new ByteArrayInputStream(owned))) {
            return parse(reader);
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("规则文件不是严格 UTF-8", error);
        }
    }

    public static AdRuleSet parse(Reader source) throws IOException {
        if (source == null) throw new IllegalArgumentException("规则输入不能为空");
        try {
            JsonReader reader = new JsonReader(source);
            reader.setStrictness(Strictness.STRICT);
            RootDocument root = readRoot(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("规则 JSON 含有尾随内容");
            }
            if (!AdRuleSet.FORMAT_ID.equals(root.format)
                    || root.schemaVersion != AdRuleSet.SCHEMA_VERSION) {
                throw new IllegalArgumentException("不支持的规则格式或结构版本");
            }
            if (!AdRuleSet.ALGORITHM_ID.equals(root.algorithm)
                    || root.rules == null || root.revision == null) {
                throw new IllegalArgumentException("规则根节点缺少必填字段或算法不兼容");
            }
            require(root.revision > 0L, "规则 revision 必须从 1 开始");
            return new AdRuleSet(root.revision, AdRuleSet.SAMPLE_RATE,
                    AdRuleSet.WINDOW_MS, AdRuleSet.HOP_MS,
                    AdRuleSet.BAND_COUNT, root.rules);
        } catch (MalformedJsonException error) {
            throw new IllegalArgumentException("规则 JSON 语法无效", error);
        }
    }

    private static Reader openUtf8Reader(InputStream source) throws IOException {
        PushbackInputStream input = new PushbackInputStream(source, 3);
        byte[] prefix = new byte[3];
        int read = input.read(prefix);
        boolean bom = read == 3 && (prefix[0] & 0xff) == 0xef
                && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf;
        if (!bom && read > 0) input.unread(prefix, 0, read);
        return new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    private static int utf8Length(String source) {
        long bytes = 0L;
        for (int i = 0; i < source.length(); i++) {
            char value = source.charAt(i);
            if (value <= 0x7f) {
                bytes++;
            } else if (value <= 0x7ff) {
                bytes += 2L;
            } else if (Character.isHighSurrogate(value)) {
                if (i + 1 >= source.length()
                        || !Character.isLowSurrogate(source.charAt(i + 1))) {
                    throw new IllegalArgumentException("规则文本包含无效 Unicode 字符");
                }
                i++;
                bytes += 4L;
            } else if (Character.isLowSurrogate(value)) {
                throw new IllegalArgumentException("规则文本包含无效 Unicode 字符");
            } else {
                bytes += 3L;
            }
            if (bytes > MAX_DOCUMENT_BYTES) return MAX_DOCUMENT_BYTES + 1;
        }
        return (int) bytes;
    }

    private static RootDocument readRoot(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "规则根节点必须是对象");
        RootDocument root = new RootDocument();
        ParseBudget budget = new ParseBudget();
        Set<String> fields = new HashSet<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields, "规则根节点");
            switch (name) {
                case "format":
                    root.format = readString(reader, name);
                    break;
                case "schemaVersion":
                    root.schemaVersion = checkedInt(readLong(reader, name), name);
                    break;
                case "revision":
                    root.revision = readLong(reader, name);
                    break;
                case "algorithm":
                    root.algorithm = readString(reader, name);
                    break;
                case "rules":
                    root.rules = readRules(reader, budget);
                    break;
                default:
                    throw new IllegalArgumentException("规则根节点含未知字段：" + name);
            }
        }
        reader.endObject();
        require(fields.size() == 5 && fields.contains("format")
                        && fields.contains("schemaVersion") && fields.contains("revision")
                        && fields.contains("algorithm") && fields.contains("rules"),
                "规则根节点缺少必填字段");
        return root;
    }

    private static List<AdRule> readRules(JsonReader reader, ParseBudget budget)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "rules 必须是数组");
        List<AdRule> rules = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(rules.size() < AdRuleSet.MAX_RULES, "规则数量超过上限");
            rules.add(readRule(reader, budget));
        }
        reader.endArray();
        return rules;
    }

    private static AdRule readRule(JsonReader reader, ParseBudget budget) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "广告规则必须是对象");
        Set<String> fields = new HashSet<>();
        String id = null;
        Long durationMs = null;
        Long anchorOffsetMs = null;
        Long anchorDurationMs = null;
        List<FingerprintVariant> variants = null;
        RuleTestMetadata test = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields, "广告规则");
            switch (name) {
                case "id": id = readString(reader, name); break;
                case "durationMs": durationMs = readLong(reader, name); break;
                case "anchorOffsetMs": anchorOffsetMs = readLong(reader, name); break;
                case "anchorDurationMs": anchorDurationMs = readLong(reader, name); break;
                case "fingerprints": variants = readVariants(reader, budget); break;
                case "test": test = readTestMetadata(reader); break;
                default: throw new IllegalArgumentException("广告规则含未知字段：" + name);
            }
        }
        reader.endObject();
        require((fields.size() == 5 || fields.size() == 6) && id != null && durationMs != null
                        && anchorOffsetMs != null && anchorDurationMs != null && variants != null,
                "广告规则缺少必填字段");
        if (test != null) {
            require(durationMs >= 0L && durationMs <= MAX_SAFE_INTEGER
                            && test.adStartMs <= MAX_SAFE_INTEGER - durationMs,
                    "test.adStartMs 与广告时长之和超出安全整数范围");
        }
        return new AdRule(id, durationMs, anchorOffsetMs, anchorDurationMs, variants);
    }

    /** 测试元数据仅供规则工具使用，校验完成后不进入运行时匹配模型。 */
    private static RuleTestMetadata readTestMetadata(JsonReader reader) throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "test 必须是对象");
        Set<String> fields = new HashSet<>();
        String url = null;
        Long adStartMs = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields, "test");
            if ("url".equals(name)) {
                url = readString(reader, "test.url");
            } else if ("adStartMs".equals(name)) {
                adStartMs = readLong(reader, "test.adStartMs");
            } else {
                throw new IllegalArgumentException("test 含未知字段：" + name);
            }
        }
        reader.endObject();
        require(fields.size() == 2 && url != null && adStartMs != null,
                "test 缺少必填字段");
        validateTestUrl(url);
        require(adStartMs >= 0L && adStartMs <= MAX_SAFE_INTEGER,
                "test.adStartMs 超出安全整数范围");
        return new RuleTestMetadata(adStartMs);
    }

    private static void validateTestUrl(String url) {
        require(!url.isEmpty() && url.length() <= MAX_TEST_URL_LENGTH,
                "test.url 长度必须为 1..8192");
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            require(scheme != null
                            && ("http".equalsIgnoreCase(scheme)
                            || "https".equalsIgnoreCase(scheme))
                            && uri.getHost() != null
                            && !uri.getHost().isEmpty(),
                    "test.url 必须是有效的 HTTP(S) URL");
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("test.url 必须是有效的 HTTP(S) URL", error);
        }
    }

    private static List<FingerprintVariant> readVariants(JsonReader reader, ParseBudget budget)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "fingerprints 必须是数组");
        List<FingerprintVariant> output = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(output.size() < REQUIRED_PHASES_MS.length, "规则指纹相位数量超过上限");
            output.add(readVariant(reader, budget));
        }
        reader.endArray();
        require(output.size() == REQUIRED_PHASES_MS.length, "规则必须包含四个固定相位");
        for (int phase : REQUIRED_PHASES_MS) {
            boolean found = false;
            for (FingerprintVariant variant : output) {
                if (variant.getOffsetMs() == phase) {
                    found = true;
                    break;
                }
            }
            require(found, "规则缺少固定相位：" + phase);
        }
        return output;
    }

    private static FingerprintVariant readVariant(JsonReader reader, ParseBudget budget)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_OBJECT, "指纹相位必须是对象");
        Set<String> fields = new HashSet<>();
        Integer phaseMs = null;
        List<String> hashes = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = uniqueName(reader, fields, "指纹相位");
            if ("phaseMs".equals(name)) {
                phaseMs = checkedInt(readLong(reader, name), name);
            } else if ("hashes".equals(name)) {
                hashes = readHashes(reader, budget);
            } else {
                throw new IllegalArgumentException("指纹相位含未知字段：" + name);
            }
        }
        reader.endObject();
        require(fields.size() == 2 && phaseMs != null && hashes != null,
                "指纹相位缺少必填字段");
        return new FingerprintVariant(phaseMs, hashes);
    }

    private static List<String> readHashes(JsonReader reader, ParseBudget budget)
            throws IOException {
        require(reader.peek() == JsonToken.BEGIN_ARRAY, "hashes 必须是数组");
        List<String> hashes = new ArrayList<>();
        reader.beginArray();
        while (reader.hasNext()) {
            require(hashes.size() < MAX_HASHES_PER_VARIANT, "单个指纹序列超过 64 帧");
            budget.addHash();
            String hash = readString(reader, "hash");
            require(HASH_PATTERN.matcher(hash).matches(), "频谱哈希格式无效");
            hashes.add(hash);
        }
        reader.endArray();
        return hashes;
    }

    private static String uniqueName(JsonReader reader, Set<String> seen, String label)
            throws IOException {
        String name = reader.nextName();
        require(seen.add(name), label + " 含重复字段：" + name);
        return name;
    }

    private static String readString(JsonReader reader, String label) throws IOException {
        require(reader.peek() == JsonToken.STRING, label + " 必须是字符串");
        return reader.nextString();
    }

    private static long readLong(JsonReader reader, String label) throws IOException {
        require(reader.peek() == JsonToken.NUMBER, label + " 必须是整数");
        String raw = reader.nextString();
        require(INTEGER_PATTERN.matcher(raw).matches(), label + " 必须是普通十进制整数");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(label + " 必须是整数", error);
        }
    }

    private static int checkedInt(long value, String label) {
        require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE,
                label + " 超出整数范围");
        return (int) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static final class RootDocument {
        String format;
        int schemaVersion = -1;
        Long revision;
        String algorithm;
        List<AdRule> rules;
    }

    private static final class ParseBudget {
        int totalHashes;

        void addHash() {
            require(totalHashes < AdRuleSet.MAX_TOTAL_HASHES, "规则指纹总量超过上限");
            totalHashes++;
        }
    }

    private static final class RuleTestMetadata {
        final long adStartMs;

        RuleTestMetadata(long adStartMs) {
            this.adStartMs = adStartMs;
        }
    }
}
