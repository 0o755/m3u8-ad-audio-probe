/* 媒体请求仅描述普通点播源，避免把 Media3 类型泄漏给宿主。 */
package io.github.fongmi.adaudio.probe;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次普通 HLS 或 MP4 点播探测请求。
 *
 * <p>对象在构建时同步校验地址和请求头，构建完成后不可变且可跨线程读取。</p>
 */
public final class ProbeMedia {
    /** 媒体容器提示；它只影响探针选源，不改变宿主播放器。 */
    public enum Type {
        /** 由探针门面自行选择容器。 */
        AUTO,
        /** 明确按 HLS 点播源处理。 */
        HLS,
        /** 明确按 MP4 点播源处理。 */
        MP4
    }

    private static final int MAX_HEADERS = 32;
    private static final int MAX_URL_LENGTH = 8192;
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_HEADER_NAME_LENGTH = 256;
    private static final int MAX_HEADER_LENGTH = 8192;
    private static final int SHORT_ID_BYTES = 16;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String url;
    private final String id;
    private final Type type;
    private final Map<String, String> headers;

    private ProbeMedia(Builder builder) {
        this.url = requireHttpUrl(builder.url);
        this.id = builder.id == null ? shortMediaId(this.url) : builder.id;
        this.type = builder.type == null ? Type.AUTO : builder.type;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(builder.headers));
    }

    /**
     * 使用自动容器类型创建媒体请求。
     *
     * @param url 完整的 HTTP(S) 点播地址，首尾空格会被移除，最长 8192 个字符
     * @return 已完成同步校验的不可变请求
     * @throws IllegalArgumentException 地址为空、格式错误、没有主机名或不是 HTTP(S)
     */
    public static ProbeMedia from(String url) {
        return builder(url).build();
    }

    /**
     * 创建媒体请求构建器。
     *
     * @param url 完整的 HTTP(S) 点播地址；最终校验发生在 {@link Builder#build()}
     * @return 新构建器
     */
    public static Builder builder(String url) {
        return new Builder(url);
    }

    /**
     * 返回校验后的媒体地址。
     *
     * @return 非空 HTTP(S) 地址
     */
    public String getUrl() {
        return url;
    }

    /**
     * 返回本次媒体的稳定标识。
     *
     * <p>未显式设置时为 URL 的 SHA-256 前 128 位，不包含原始 URL 文本。</p>
     *
     * @return 长度为 1 到 256 的非空标识
     */
    public String getId() {
        return id;
    }

    /**
     * 返回媒体容器提示。
     *
     * @return 非空容器类型
     */
    public Type getType() {
        return type;
    }

    /**
     * 返回只读 HTTP 请求头。
     *
     * <p>名称统一为小写，值保留调用方提供的普通空格。</p>
     *
     * @return 非空、不可修改且最多包含 32 项的映射
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /** 用于配置一个不可变 {@link ProbeMedia} 的构建器。 */
    public static final class Builder {
        private final String url;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String id;
        private Type type = Type.AUTO;

        private Builder(String url) {
            this.url = url;
        }

        /**
         * 设置宿主可识别的媒体标识。
         *
         * @param id 长度为 1 到 256、非纯空白且不含控制字符的标识
         * @return 当前构建器
         * @throws IllegalArgumentException 标识不符合约束
         */
        public Builder setId(String id) {
            if (id == null) throw new IllegalArgumentException("媒体 ID 不能为空");
            ApiValidation.requireId(id, "媒体 ID", MAX_ID_LENGTH);
            String normalized = id.trim();
            ApiValidation.requireId(normalized, "媒体 ID", MAX_ID_LENGTH);
            this.id = normalized;
            return this;
        }

        /**
         * 设置容器提示。
         *
         * @param type 容器类型；传入 {@code null} 等同于 {@link Type#AUTO}
         * @return 当前构建器
         */
        public Builder setType(Type type) {
            this.type = type;
            return this;
        }

        /**
         * 添加或替换一个媒体请求头。
         *
         * <p>名称必须是 RFC HTTP token，按小写形式不区分大小写去重。值可以为空，
         * 最长 8192 个字符，普通空格会原样保留，所有控制字符（包括制表符）均被拒绝。</p>
         *
         * @param name 请求头名称，最长 256 个字符
         * @param value 请求头值，不可为 {@code null}
         * @return 当前构建器
         * @throws IllegalArgumentException 名称、值或请求头数量不符合约束
         */
        public Builder setHeader(String name, String value) {
            String safeName = normalizeHeaderName(name);
            String safeValue = requireHeaderValue(value);
            if (!headers.containsKey(safeName) && headers.size() >= MAX_HEADERS) {
                throw new IllegalArgumentException("请求头数量超过上限");
            }
            headers.put(safeName, safeValue);
            return this;
        }

        /**
         * 批量添加或替换媒体请求头。
         *
         * @param values 请求头映射；{@code null} 表示不添加
         * @return 当前构建器
         * @throws IllegalArgumentException 任一名称或值不符合 {@link #setHeader(String, String)} 的约束
         */
        public Builder setHeaders(Map<String, String> values) {
            if (values == null) return this;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                setHeader(entry.getKey(), entry.getValue());
            }
            return this;
        }

        /**
         * 校验当前配置并创建不可变媒体请求。
         *
         * @return 新媒体请求
         * @throws IllegalArgumentException 地址、标识或请求头不符合约束
         */
        public ProbeMedia build() {
            return new ProbeMedia(this);
        }
    }

    private static String requireHttpUrl(String value) {
        if (value == null) throw new IllegalArgumentException("媒体地址不能为空");
        if (value.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("媒体地址长度超过上限");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("媒体地址不能包含控制字符");
            }
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("媒体地址不能为空");
        try {
            URI uri = new URI(normalized).parseServerAuthority();
            String scheme = uri.getScheme();
            if (uri.isOpaque() || scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getPort() > 65535) {
                throw new IllegalArgumentException("媒体地址必须是包含主机名的 HTTP(S) URL");
            }
            return normalized;
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("媒体地址格式无效", error);
        }
    }

    private static String shortMediaId(String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(7 + SHORT_ID_BYTES * 2);
            id.append("sha256-");
            for (int i = 0; i < SHORT_ID_BYTES; i++) {
                int value = digest[i] & 0xff;
                id.append(HEX[value >>> 4]).append(HEX[value & 0x0f]);
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("运行环境缺少 SHA-256", impossible);
        }
    }

    private static String normalizeHeaderName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_HEADER_NAME_LENGTH) {
            throw new IllegalArgumentException("请求头名称长度无效");
        }
        for (int i = 0; i < name.length(); i++) {
            if (!isTokenCharacter(name.charAt(i))) {
                throw new IllegalArgumentException("请求头名称不是有效的 HTTP token");
            }
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String requireHeaderValue(String value) {
        if (value == null || value.length() > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException("请求头内容长度无效");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException("请求头内容不能包含控制字符");
            }
        }
        return value;
    }

    private static boolean isTokenCharacter(char character) {
        if (character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9') {
            return true;
        }
        return character == '!' || character == '#' || character == '$'
                || character == '%' || character == '&' || character == '\''
                || character == '*' || character == '+' || character == '-'
                || character == '.' || character == '^' || character == '_'
                || character == '`' || character == '|' || character == '~';
    }
}
