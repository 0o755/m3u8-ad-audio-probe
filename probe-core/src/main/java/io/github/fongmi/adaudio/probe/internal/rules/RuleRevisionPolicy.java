/* 规则修订策略独立判断升级、重复和发布冲突，避免 I/O 分支改变安全语义。 */
package io.github.fongmi.adaudio.probe.internal.rules;

public final class RuleRevisionPolicy {
    public enum Decision {
        ACCEPT_INITIAL,
        ACCEPT_UPGRADE,
        UNCHANGED,
        REJECT_DOWNGRADE,
        REVISION_CONFLICT
    }

    private RuleRevisionPolicy() {
    }

    public static Decision evaluate(Long currentRevision, String currentDigest,
                                    long incomingRevision, String incomingDigest) {
        if (incomingRevision <= 0L || incomingDigest == null || incomingDigest.isEmpty()) {
            throw new IllegalArgumentException("待评估规则版本或摘要无效");
        }
        if (currentRevision == null) return Decision.ACCEPT_INITIAL;
        if (incomingRevision < currentRevision) return Decision.REJECT_DOWNGRADE;
        if (incomingRevision > currentRevision) return Decision.ACCEPT_UPGRADE;
        return incomingDigest.equals(currentDigest)
                ? Decision.UNCHANGED : Decision.REVISION_CONFLICT;
    }
}
