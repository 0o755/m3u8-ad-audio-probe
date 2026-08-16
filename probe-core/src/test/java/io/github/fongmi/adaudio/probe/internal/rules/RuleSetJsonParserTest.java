/* 验证 Probe v1 只接受严格字段、整数、测试元数据和四相位指纹。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import org.junit.Test;

import java.io.StringReader;

import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

import static org.junit.Assert.assertEquals;

public class RuleSetJsonParserTest {
    @Test
    public void parsesStrictProbeV1() throws Exception {
        AdRuleSet rules = RuleSetJsonParser.parse(new StringReader(validJson("7", variants())));

        assertEquals(7L, rules.getRevision());
        assertEquals(1, rules.getRules().size());
        assertEquals("ad", rules.getRules().get(0).getId());
        assertEquals(4, rules.getRules().get(0).getFingerprints().size());
    }

    @Test
    public void acceptsExplicitEmptyRuleSet() throws Exception {
        String json = "{\"format\":\"ad-audio-probe-rules\",\"schemaVersion\":1,"
                + "\"revision\":8,\"algorithm\":\"spectral-sequence-v1\",\"rules\":[]}";
        assertEquals(0, RuleSetJsonParser.parse(new StringReader(json)).getRules().size());
    }

    @Test
    public void acceptsOptionalTestMetadataWithoutAddingItToMatcherModel() throws Exception {
        String test = ",\"test\":{\"url\":\"https://example.com/video.m3u8\","
                + "\"adStartMs\":9007199254738991}";
        AdRuleSet rules = RuleSetJsonParser.parse(new StringReader(
                validJson("7", variants(), test)));

        assertEquals(1, rules.getRules().size());
        assertEquals("ad", rules.getRules().get(0).getId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTrailingContent() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants()) + " trailing"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLenientSingleQuotedJson() throws Exception {
        RuleSetJsonParser.parse(new StringReader("{'schemaVersion':1}"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLegacyMetadata() throws Exception {
        String json = validJson("7", variants());
        RuleSetJsonParser.parse(new StringReader(json.substring(0, json.length() - 1)
                + ",\"testUrls\":{} }"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDecimalInteger() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7.0", variants())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExponentInteger() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7e0", variants())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingFixedPhase() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7",
                variant(0) + "," + variant(64) + "," + variant(128))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRevisionZero() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("0", variants())));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncompleteTestMetadata() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\"}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTestMetadataWithoutUrl() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"adStartMs\":0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownTestMetadataField() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"adStartMs\":0,\"label\":\"sample\"}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicateTestMetadataField() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"url\":\"https://example.com/other.mp4\",\"adStartMs\":0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHttpTestUrl() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"file:///video.mp4\",\"adStartMs\":0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsHttpTestUrlWithoutAuthority() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https:video.mp4\",\"adStartMs\":0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedTestUrl() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/" + repeat('a', 8192)
                        + "\",\"adStartMs\":0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeTestStart() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"adStartMs\":-1}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDecimalTestStart() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"adStartMs\":1.0}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTestStartPastSafeInteger() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"adStartMs\":9007199254740992}")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTestEndPastSafeInteger() throws Exception {
        RuleSetJsonParser.parse(new StringReader(validJson("7", variants(),
                ",\"test\":{\"url\":\"https://example.com/video.mp4\","
                        + "\"adStartMs\":9007199254738992}")));
    }

    private String validJson(String revision, String fingerprints) {
        return validJson(revision, fingerprints, "");
    }

    private String validJson(String revision, String fingerprints, String optionalFields) {
        return "{"
                + "\"format\":\"ad-audio-probe-rules\","
                + "\"schemaVersion\":1,"
                + "\"revision\":" + revision + ","
                + "\"algorithm\":\"spectral-sequence-v1\","
                + "\"rules\":[{"
                + "\"id\":\"ad\","
                + "\"durationMs\":2000,"
                + "\"anchorOffsetMs\":0,"
                + "\"anchorDurationMs\":2000,"
                + "\"fingerprints\":[" + fingerprints + "]" + optionalFields + "}]}";
    }

    private String variants() {
        return variant(0) + "," + variant(64) + "," + variant(128) + "," + variant(192);
    }

    private String variant(int phaseMs) {
        return "{\"phaseMs\":" + phaseMs + ",\"hashes\":["
                + "\"1234abcd\",\"fedc4321\",\"1234abcd\","
                + "\"1234abcd\",\"1234abcd\",\"1234abcd\"]}";
    }

    private String repeat(char value, int count) {
        char[] output = new char[count];
        java.util.Arrays.fill(output, value);
        return new String(output);
    }
}
