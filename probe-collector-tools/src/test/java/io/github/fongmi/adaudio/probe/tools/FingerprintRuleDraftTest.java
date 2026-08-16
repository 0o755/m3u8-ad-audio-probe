/* 验证 rules-v1 草稿的固定相位、长度公式与测试 URL 边界。 */
package io.github.fongmi.adaudio.probe.tools;

import org.junit.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.fongmi.adaudio.probe.internal.rules.RuleSetJsonParser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FingerprintRuleDraftTest {
    @Test
    public void writesStrictRuleJson() {
        FingerprintRuleDraft draft = draft("https://example.com/v.m3u8", 1000L, variants());

        String json = draft.toRuleJson();
        assertTrue(json.contains("\"id\":\"sample-ad\""));
        assertTrue(json.contains("\"phaseMs\":192"));
        assertTrue(json.contains("\"test\":{\"url\":\"https://example.com/v.m3u8\""));
    }

    @Test
    public void generatedRulePassesCoreRulesV1Parser() throws Exception {
        FingerprintRuleDraft draft = draft(
                "https://example.com/v.m3u8?token=a%20b&quality=high", 1000L, variants());
        String document = "{\"format\":\"ad-audio-probe-rules\","
                + "\"schemaVersion\":1,\"revision\":1,"
                + "\"algorithm\":\"spectral-sequence-v1\","
                + "\"rules\":[" + draft.toRuleJson() + "]}";

        assertEquals(1, RuleSetJsonParser.parse(new StringReader(document))
                .getRules().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHttpTestUrl() {
        draft("file:///tmp/video.mp4", 0L, variants());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsafeTestEndpoint() {
        draft("https://example.com/v.mp4", 9_007_199_254_739_500L, variants());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicatePhase() {
        List<FingerprintSequence> invalid = variants();
        invalid.set(3, sequence(128));
        draft("https://example.com/v.mp4", 0L, invalid);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongHashCount() {
        List<FingerprintSequence> invalid = variants();
        invalid.set(0, new FingerprintSequence(0,
                Arrays.asList("1234abcd", "2345bcde", "3456cdef", "4567def0")));
        draft("https://example.com/v.mp4", 0L, invalid);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLowDiversityPrefix() {
        List<String> flat = Arrays.asList("1234abcd", "1234abcd", "1234abcd",
                "1234abcd", "1234abcd", "1234abcd");
        List<FingerprintSequence> invalid = Arrays.asList(
                new FingerprintSequence(0, flat), new FingerprintSequence(64, flat),
                new FingerprintSequence(128, flat), new FingerprintSequence(192, flat));
        draft("https://example.com/v.mp4", 0L, invalid);
    }

    private FingerprintRuleDraft draft(String url, long start, List<FingerprintSequence> items) {
        return new FingerprintRuleDraft("sample-ad", 2000L, 0L, 2000L,
                items, url, start);
    }

    private List<FingerprintSequence> variants() {
        return new ArrayList<>(Arrays.asList(
                sequence(0), sequence(64), sequence(128), sequence(192)));
    }

    private FingerprintSequence sequence(int phase) {
        return new FingerprintSequence(phase, Arrays.asList(
                "1234abcd", "2345bcde", "3456cdef",
                "4567def0", "5678ef01", "6789f012"));
    }
}
