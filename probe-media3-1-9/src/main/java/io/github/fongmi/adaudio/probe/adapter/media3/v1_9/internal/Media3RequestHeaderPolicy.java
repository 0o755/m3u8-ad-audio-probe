/* 官方 Media3 适配器仅放行不会向重定向目标泄露凭据的普通请求头。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Media3 内置 HTTP 实现无法逐跳改写重定向头，因此采用最小安全白名单。 */
final class Media3RequestHeaderPolicy {
    private static final Set<String> ALLOWED_NAMES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("user-agent", "accept", "accept-language",
                    "cache-control", "pragma")));

    private Media3RequestHeaderPolicy() {
    }

    static String findFirstUnsupported(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        for (String name : headers.keySet()) {
            if (name == null || !ALLOWED_NAMES.contains(name.toLowerCase(Locale.US))) {
                return name == null ? "<null>" : name;
            }
        }
        return null;
    }

    static Map<String, String> copyAllowed(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;
            String normalized = name.toLowerCase(Locale.US);
            if (ALLOWED_NAMES.contains(normalized)) allowed.put(normalized, entry.getValue());
        }
        return allowed;
    }
}
