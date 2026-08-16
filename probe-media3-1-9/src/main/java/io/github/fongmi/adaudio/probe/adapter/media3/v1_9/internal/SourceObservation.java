/* 单次真实媒体请求只保留判型所需的少量响应信息，不记录完整地址或令牌。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 有界保存根响应前缀和响应头，供一次性 AUTO 回退判断。 */
final class SourceObservation {
    private static final int MAX_PREFIX_BYTES = 4 * 1024;

    private final byte[] prefix = new byte[MAX_PREFIX_BYTES];
    private int prefixLength;
    private String contentType;
    private boolean responseStarted;

    synchronized boolean beginResponse(Map<String, List<String>> headers) {
        if (responseStarted) return false;
        responseStarted = true;
        contentType = findContentType(headers);
        return true;
    }

    synchronized void recordBytes(byte[] source, int offset, int length) {
        if (source == null || length <= 0 || prefixLength >= prefix.length) return;
        int copied = Math.min(length, prefix.length - prefixLength);
        System.arraycopy(source, offset, prefix, prefixLength, copied);
        prefixLength += copied;
    }

    synchronized boolean hasHlsEvidence() {
        return AutoMediaTypeDetector.hasHlsEvidence(prefix, prefixLength, contentType);
    }

    synchronized boolean hasMp4Evidence() {
        return AutoMediaTypeDetector.hasMp4Evidence(prefix, prefixLength, contentType);
    }

    private static String findContentType(Map<String, List<String>> headers) {
        if (headers == null) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null
                    || !"content-type".equals(entry.getKey().toLowerCase(Locale.US))) continue;
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) return null;
            return values.get(0);
        }
        return null;
    }
}
