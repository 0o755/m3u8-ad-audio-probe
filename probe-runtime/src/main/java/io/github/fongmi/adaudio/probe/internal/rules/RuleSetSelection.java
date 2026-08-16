/* 为单规则测试创建不泄露内部模型的不可变规则视图。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import java.util.Collections;

import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

public final class RuleSetSelection {
    private RuleSetSelection() {
    }

    public static AdRuleSet select(AdRuleSet source, String ruleId) {
        if (source == null) throw new IllegalStateException("广告规则尚未加载");
        if (ruleId == null || ruleId.trim().isEmpty()) {
            throw new IllegalArgumentException("规则 ID 不能为空");
        }
        String normalized = ruleId.trim();
        for (AdRule rule : source.getRules()) {
            if (rule.getId().equals(normalized)) {
                return new AdRuleSet(source.getRevision(), source.getSampleRate(),
                        source.getWindowMs(), source.getHopMs(), source.getBandCount(),
                        Collections.singletonList(rule));
            }
        }
        throw new IllegalArgumentException("未知规则 ID：" + normalized);
    }

    public static boolean contains(AdRuleSet source, String ruleId) {
        if (source == null || ruleId == null) return false;
        for (AdRule rule : source.getRules()) {
            if (rule.getId().equals(ruleId)) return true;
        }
        return false;
    }
}
