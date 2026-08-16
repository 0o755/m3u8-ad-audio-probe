/* 验证规则替换终态不会产生无法关联或自相矛盾的公共结果。 */
package io.github.fongmi.adaudio.probe;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

public class RuleReplacementResultTest {
    @Test
    public void appliedResultCarriesExactRequestAndRuleState() {
        RuleReplacementResult result = new RuleReplacementResult(9L,
                RuleReplacementState.APPLIED, 4L, 3L, 2, null);

        assertEquals(9L, result.getRequestId());
        assertEquals(RuleReplacementState.APPLIED, result.getState());
        assertEquals(4L, result.getSessionId());
        assertEquals(3L, result.getRuleRevision());
        assertEquals(2, result.getRuleCount());
        assertNull(result.getError());
    }

    @Test
    public void rejectedResultRequiresMatchingErrorSession() {
        ProbeError error = new ProbeError(ProbeErrorCode.RULE_PARSE_FAILED,
                7L, false, false, "invalid rules", null);

        assertThrows(IllegalArgumentException.class, () -> new RuleReplacementResult(
                1L, RuleReplacementState.REJECTED, 8L, 3L, 2, error));
        assertThrows(IllegalArgumentException.class, () -> new RuleReplacementResult(
                1L, RuleReplacementState.APPLIED, 7L, 3L, 2, error));
    }
}
