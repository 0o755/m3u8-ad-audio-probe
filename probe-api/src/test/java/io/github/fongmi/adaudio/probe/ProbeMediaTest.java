/* 验证媒体地址、脱敏 ID、请求头规范化和不可变边界。 */
package io.github.fongmi.adaudio.probe;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProbeMediaTest {
    @Test
    public void buildsImmutableMediaRequest() {
        ProbeMedia media = ProbeMedia.builder(" https://example.com/a.m3u8 ")
                .setId(" episode-1 ")
                .setType(ProbeMedia.Type.HLS)
                .setHeader("Referer", "https://example.com/")
                .build();

        assertEquals("https://example.com/a.m3u8", media.getUrl());
        assertEquals("episode-1", media.getId());
        assertEquals(ProbeMedia.Type.HLS, media.getType());
        assertEquals("https://example.com/", media.getHeaders().get("referer"));
        assertFalse(media.getHeaders().containsKey("Referer"));
    }

    @Test
    public void derivesStableShortIdWithoutLeakingUrl() {
        ProbeMedia first = ProbeMedia.from(" https://example.com/a.mp4 ");
        ProbeMedia second = ProbeMedia.from("https://example.com/a.mp4");

        assertEquals("sha256-0e06dca0234da29358bb3b0f700b1473", first.getId());
        assertEquals(first.getId(), second.getId());
        assertEquals(39, first.getId().length());
        assertFalse(first.getId().contains("example.com"));
    }

    @Test
    public void acceptsOnlyBoundedHttpUrlsWithHosts() {
        String prefix = "https://example.com/";
        String longest = prefix + repeat('a', 8192 - prefix.length());
        assertEquals(8192, ProbeMedia.from(longest).getUrl().length());
        assertEquals("HTTP://example.com/a.mp4",
                ProbeMedia.from("HTTP://example.com/a.mp4").getUrl());

        assertRejected(() -> ProbeMedia.from(null));
        assertRejected(() -> ProbeMedia.from("  "));
        assertRejected(() -> ProbeMedia.from("ftp://example.com/a.mp4"));
        assertRejected(() -> ProbeMedia.from("https:///a.mp4"));
        assertRejected(() -> ProbeMedia.from("https://exa mple.com/a.mp4"));
        assertRejected(() -> ProbeMedia.from("\thttps://example.com/a.mp4"));
        assertRejected(() -> ProbeMedia.from(longest + "a"));
    }

    @Test
    public void validatesExplicitMediaId() {
        String longest = repeat('x', 256);
        assertEquals(longest, ProbeMedia.builder("https://example.com/a.mp4")
                .setId(longest)
                .build()
                .getId());

        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4").setId(null));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4").setId("   "));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4").setId("a\tb"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setId(longest + "x"));
    }

    @Test
    public void normalizesHeaderNamesAndPreservesValues() {
        ProbeMedia media = ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("X-Custom", "  first value  ")
                .setHeader("x-custom", "  replacement value  ")
                .setHeader("X!#$%&'*+-.^_`|~09", "")
                .build();

        assertEquals(2, media.getHeaders().size());
        assertEquals("  replacement value  ", media.getHeaders().get("x-custom"));
        assertTrue(media.getHeaders().containsKey("x!#$%&'*+-.^_`|~09"));
        assertEquals("", media.getHeaders().get("x!#$%&'*+-.^_`|~09"));
    }

    @Test
    public void validatesHeaderNamesAndValues() {
        String longestValue = repeat('v', 8192);
        assertEquals(longestValue, ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("X-Long", longestValue)
                .build()
                .getHeaders()
                .get("x-long"));

        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader(null, "value"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("", "value"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("Bad Name", "value"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("Bad:Name", "value"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("请求头", "value"));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("X-Test", null));
        assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                .setHeader("X-Test", longestValue + "v"));

        char[] controls = {'\0', '\t', '\r', '\n', 0x7f, 0x85};
        for (char control : controls) {
            assertRejected(() -> ProbeMedia.builder("https://example.com/a.mp4")
                    .setHeader("X-Test", "before" + control + "after"));
        }
    }

    @Test
    public void enforcesHeaderCountAfterCaseInsensitiveDeduplication() {
        ProbeMedia.Builder builder = ProbeMedia.builder("https://example.com/a.mp4");
        for (int i = 0; i < 32; i++) builder.setHeader("X-" + i, "value");
        builder.setHeader("x-0", "replacement");

        ProbeMedia media = builder.build();
        assertEquals(32, media.getHeaders().size());
        assertEquals("replacement", media.getHeaders().get("x-0"));
        assertRejected(() -> builder.setHeader("X-32", "value"));
    }

    @Test
    public void supportsBulkHeadersAndNullAutoType() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("Authorization", "Bearer value");
        source.put("User-Agent", "Probe Test");

        ProbeMedia media = ProbeMedia.builder("https://example.com/a.mp4")
                .setHeaders(null)
                .setHeaders(source)
                .setType(null)
                .build();

        assertEquals(ProbeMedia.Type.AUTO, media.getType());
        assertEquals("Bearer value", media.getHeaders().get("authorization"));
        assertEquals("Probe Test", media.getHeaders().get("user-agent"));
    }

    @Test(expected = UnsupportedOperationException.class)
    public void headersCannotBeMutated() {
        ProbeMedia.from("https://example.com/a.mp4").getHeaders().put("x", "y");
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("预期拒绝非法参数");
        } catch (IllegalArgumentException expected) {
            // 只关心公开合同是否同步拒绝，错误文案不作为兼容合同。
        }
    }
}
