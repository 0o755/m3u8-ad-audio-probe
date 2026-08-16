/* 验证单规则测试视图不会泄露空集或改变原始规则集合。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import org.junit.Test;

import java.io.StringReader;

import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RuleSetSelectionTest {
    @Test
    public void selectsKnownRuleWithoutChangingRevision() throws Exception {
        AdRuleSet source = parseRules();

        AdRuleSet selected = RuleSetSelection.select(source, " ad ");

        assertEquals(9L, selected.getRevision());
        assertEquals(1, selected.getRules().size());
        assertEquals("ad", selected.getRules().get(0).getId());
        assertEquals(1, source.getRules().size());
    }

    @Test
    public void rejectsUnavailableBlankAndUnknownRule() throws Exception {
        AdRuleSet source = parseRules();

        assertThrows(IllegalStateException.class,
                () -> RuleSetSelection.select(null, "ad"));
        assertThrows(IllegalArgumentException.class,
                () -> RuleSetSelection.select(source, " "));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RuleSetSelection.select(source, "missing"));
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    public void reportsMembershipWithoutCreatingAnEmptyView() throws Exception {
        AdRuleSet source = parseRules();

        assertTrue(RuleSetSelection.contains(source, "ad"));
        assertFalse(RuleSetSelection.contains(source, "missing"));
        assertFalse(RuleSetSelection.contains(null, "ad"));
    }

    private static AdRuleSet parseRules() throws Exception {
        String hashes = "\"1234abcd\",\"fedc4321\",\"1234abcd\","
                + "\"1234abcd\",\"1234abcd\",\"1234abcd\"";
        StringBuilder variants = new StringBuilder();
        int[] phases = {0, 64, 128, 192};
        for (int phase : phases) {
            if (variants.length() > 0) variants.append(',');
            variants.append("{\"phaseMs\":").append(phase)
                    .append(",\"hashes\":[").append(hashes).append("]}");
        }
        String json = "{\"format\":\"ad-audio-probe-rules\","
                + "\"schemaVersion\":1,\"revision\":9,"
                + "\"algorithm\":\"spectral-sequence-v1\",\"rules\":[{"
                + "\"id\":\"ad\",\"durationMs\":2000,\"anchorOffsetMs\":0,"
                + "\"anchorDurationMs\":2000,\"fingerprints\":["
                + variants + "]}]}";
        return RuleSetJsonParser.parse(new StringReader(json));
    }
}
