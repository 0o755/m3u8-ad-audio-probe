/* 验证规则修订策略只允许明确的单调升级。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import org.junit.Test;

import static io.github.fongmi.adaudio.probe.internal.rules.RuleRevisionPolicy.Decision.ACCEPT_INITIAL;
import static io.github.fongmi.adaudio.probe.internal.rules.RuleRevisionPolicy.Decision.ACCEPT_UPGRADE;
import static io.github.fongmi.adaudio.probe.internal.rules.RuleRevisionPolicy.Decision.REJECT_DOWNGRADE;
import static io.github.fongmi.adaudio.probe.internal.rules.RuleRevisionPolicy.Decision.REVISION_CONFLICT;
import static io.github.fongmi.adaudio.probe.internal.rules.RuleRevisionPolicy.Decision.UNCHANGED;
import static org.junit.Assert.assertEquals;

public class RuleRevisionPolicyTest {
    @Test
    public void acceptsInitialAndHigherRevision() {
        assertEquals(ACCEPT_INITIAL, RuleRevisionPolicy.evaluate(null, null, 1L, "a"));
        assertEquals(ACCEPT_UPGRADE, RuleRevisionPolicy.evaluate(1L, "a", 2L, "b"));
    }

    @Test
    public void identifiesExactRepeat() {
        assertEquals(UNCHANGED, RuleRevisionPolicy.evaluate(7L, "same", 7L, "same"));
    }

    @Test
    public void rejectsRollbackAndSameRevisionMutation() {
        assertEquals(REJECT_DOWNGRADE,
                RuleRevisionPolicy.evaluate(7L, "a", 6L, "b"));
        assertEquals(REVISION_CONFLICT,
                RuleRevisionPolicy.evaluate(7L, "a", 7L, "b"));
    }
}
