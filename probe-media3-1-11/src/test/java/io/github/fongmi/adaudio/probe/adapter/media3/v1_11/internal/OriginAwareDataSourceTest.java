/* 验证 Media3 请求头白名单与媒体子资源的源站判断。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_11.internal;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OriginAwareDataSourceTest {
    @Test
    public void originComparisonNormalizesCaseAndDefaultPort() {
        assertTrue(OriginAwareDataSource.sameOrigin(
                "https://EXAMPLE.com/master.m3u8",
                "https://example.com:443/audio/segment.ts"));
        assertFalse(OriginAwareDataSource.sameOrigin(
                "https://example.com/master.m3u8",
                "https://cdn.example.com/audio/segment.ts"));
        assertFalse(OriginAwareDataSource.sameOrigin(
                "https://example.com/master.m3u8",
                "http://example.com/audio/segment.ts"));
    }

    @Test
    public void media3HeaderPolicyOnlyKeepsRedirectSafeNames() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("user-agent", "Probe");
        input.put("accept", "video/mp4");
        input.put("accept-language", "zh-CN");
        input.put("cache-control", "no-cache");
        input.put("pragma", "no-cache");

        Map<String, String> output = OriginAwareDataSource.crossOriginHeaders(input);

        assertNull(Media3RequestHeaderPolicy.findFirstUnsupported(input));
        assertEquals(5, output.size());
        assertEquals("Probe", output.get("user-agent"));
        assertEquals("no-cache", output.get("cache-control"));
    }

    @Test
    public void media3HeaderPolicyRejectsCredentialsRefererAndCustomHeaders() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("authorization", "Bearer secret");
        input.put("cookie", "session=secret");
        input.put("referer", "https://example.com/?token=secret");
        input.put("x-token", "secret");

        assertEquals("authorization",
                Media3RequestHeaderPolicy.findFirstUnsupported(input));
        assertTrue(OriginAwareDataSource.crossOriginHeaders(input).isEmpty());
    }
}
