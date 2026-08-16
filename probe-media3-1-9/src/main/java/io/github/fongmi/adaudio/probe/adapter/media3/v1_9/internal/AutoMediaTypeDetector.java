/* AUTO 判型综合路径提示、真实响应类型与首字节签名，避免只依赖后缀。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import java.net.URI;
import java.util.Locale;

/** 官方适配器的纯判型策略；网络请求仍由实际 MediaSource 完成。 */
final class AutoMediaTypeDetector {
    enum Container {
        HLS,
        MP4
    }

    private AutoMediaTypeDetector() {
    }

    static Container initialContainer(ProbeMedia media) {
        if (media.getType() == ProbeMedia.Type.HLS) return Container.HLS;
        if (media.getType() == ProbeMedia.Type.MP4) return Container.MP4;
        String path = URI.create(media.getUrl()).getPath();
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        return lower.endsWith(".m3u8") ? Container.HLS : Container.MP4;
    }

    static boolean allowsFallback(ProbeMedia media) {
        return media.getType() == ProbeMedia.Type.AUTO;
    }

    static boolean hasHlsEvidence(byte[] prefix, int length, String contentType) {
        if (startsWithExtM3u(prefix, length)) return true;
        return isHlsMimeType(contentType);
    }

    static boolean hasMp4Evidence(byte[] prefix, int length, String contentType) {
        if (containsTopLevelFtyp(prefix, length)) return true;
        return isMp4MimeType(contentType);
    }

    private static boolean startsWithExtM3u(byte[] value, int length) {
        if (value == null || length <= 0) return false;
        int limit = Math.min(length, value.length);
        int index = 0;
        if (limit >= 3 && (value[0] & 0xff) == 0xef
                && (value[1] & 0xff) == 0xbb && (value[2] & 0xff) == 0xbf) {
            index = 3;
        }
        while (index < limit) {
            int current = value[index] & 0xff;
            if (current != ' ' && current != '\t' && current != '\r' && current != '\n') break;
            index++;
        }
        byte[] signature = new byte[]{'#', 'E', 'X', 'T', 'M', '3', 'U'};
        if (limit - index < signature.length) return false;
        for (int offset = 0; offset < signature.length; offset++) {
            if (value[index + offset] != signature[offset]) return false;
        }
        return true;
    }

    private static boolean isHlsMimeType(String value) {
        if (value == null) return false;
        int separator = value.indexOf(';');
        String mime = (separator < 0 ? value : value.substring(0, separator))
                .trim().toLowerCase(Locale.US);
        return "application/vnd.apple.mpegurl".equals(mime)
                || "application/x-mpegurl".equals(mime)
                || "audio/mpegurl".equals(mime)
                || "audio/x-mpegurl".equals(mime);
    }

    private static boolean containsTopLevelFtyp(byte[] value, int length) {
        if (value == null || length <= 0) return false;
        int limit = Math.min(length, value.length);
        int offset = 0;
        while (limit - offset >= 8) {
            long boxSize = readUnsignedInt(value, offset);
            int headerSize = 8;
            if (boxSize == 1L) {
                if (limit - offset < 16) return false;
                long high = readUnsignedInt(value, offset + 8);
                if (high != 0L) return false;
                boxSize = readUnsignedInt(value, offset + 12);
                headerSize = 16;
            } else if (boxSize == 0L) {
                return false;
            }
            if (boxSize < headerSize) return false;
            boolean isFtyp = value[offset + 4] == 'f' && value[offset + 5] == 't'
                    && value[offset + 6] == 'y' && value[offset + 7] == 'p';
            if (isFtyp) {
                return boxSize >= headerSize + 8L && limit - offset >= headerSize + 8;
            }
            if (boxSize > limit - offset) return false;
            offset += (int) boxSize;
        }
        return false;
    }

    private static long readUnsignedInt(byte[] value, int offset) {
        return ((long) value[offset] & 0xffL) << 24
                | ((long) value[offset + 1] & 0xffL) << 16
                | ((long) value[offset + 2] & 0xffL) << 8
                | (long) value[offset + 3] & 0xffL;
    }

    private static boolean isMp4MimeType(String value) {
        if (value == null) return false;
        int separator = value.indexOf(';');
        String mime = (separator < 0 ? value : value.substring(0, separator))
                .trim().toLowerCase(Locale.US);
        return "video/mp4".equals(mime) || "audio/mp4".equals(mime)
                || "application/mp4".equals(mime);
    }
}
