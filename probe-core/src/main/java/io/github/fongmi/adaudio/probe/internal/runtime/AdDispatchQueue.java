/* 两阶段派发队列只在宿主进入广告区间后占用，最终结果由调用方确认。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public final class AdDispatchQueue {
    private static final long DURATION_TOLERANCE_MS = 250L;
    private final List<Entry> entries = new ArrayList<>();
    private final List<ConfirmedAd> acknowledged = new ArrayList<>();
    private final List<ConfirmedAd> suppressed = new ArrayList<>();

    public synchronized void addAll(List<ConfirmedAd> ads) {
        if (ads == null) return;
        // 撤销标记必须先于普通证据处理，保证同批输入顺序不影响旧 claim 失效。
        for (ConfirmedAd ad : ads) {
            if (ad != null && ad.isRevocation()) revokeExact(ad);
        }
        for (ConfirmedAd ad : ads) {
            if (ad != null && !ad.isRevocation()
                    && !containsEntry(ad) && !isAcknowledged(ad)
                    && !isSuppressed(ad)) {
                entries.add(new Entry(ad));
            }
        }
        suppressAmbiguousEntriesToFixedPoint();
    }

    private void revokeExact(ConfirmedAd revoked) {
        Entry entry = findEntry(revoked);
        if (entry != null) {
            entries.remove(entry);
            rememberSuppressed(entry.ad);
        } else {
            rememberSuppressed(revoked);
        }
    }

    /**
     * 占用宿主当前进入的广告区间。durationMs 传负值表示媒体总时长未知。
     * 占用后必须调用 ack 或 release，未结束前不会被其他线程重复取得。
     */
    public synchronized List<Claim> claim(long hostPositionMs, long durationMs) {
        if (hostPositionMs < 0L || entries.isEmpty()) return Collections.emptyList();
        List<Claim> ready = new ArrayList<>();
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            ConfirmedAd ad = entry.ad;
            if (durationMs >= 0L && ad.getEndTimeMs() > safeAdd(durationMs,
                    DURATION_TOLERANCE_MS)) {
                iterator.remove();
            } else if (hostPositionMs >= ad.getEndTimeMs()) {
                iterator.remove();
            } else if (entry.claim == null && hostPositionMs >= ad.getStartTimeMs()) {
                entry.claim = new Claim(ad);
                ready.add(entry.claim);
            }
        }
        return ready.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(ready);
    }

    /** 仅当前 entry 持有的同一 token 有效，冲突抑制和 reset 会立即撤销旧占用。 */
    public synchronized boolean isClaimValid(Claim claim) {
        return findClaimedEntry(claim) != null;
    }

    /** 宿主最终确认成功后提交占用；同一媒体内该区间不会再次加入或派发。 */
    public synchronized boolean ack(Claim claim) {
        Entry entry = findClaimedEntry(claim);
        if (entry == null) return false;
        entries.remove(entry);
        acknowledged.add(entry.ad);
        return true;
    }

    /** 宿主当前位置暂时不满足最终校验时释放占用，后续时钟更新可以重试。 */
    public synchronized boolean release(Claim claim) {
        Entry entry = findClaimedEntry(claim);
        if (entry == null) return false;
        entry.claim = null;
        return true;
    }

    public synchronized void reset() {
        entries.clear();
        acknowledged.clear();
        suppressed.clear();
    }

    /** 反复回扫到闭包，确保跨批冲突链不受输入顺序影响。 */
    private void suppressAmbiguousEntriesToFixedPoint() {
        boolean changed;
        do {
            boolean[] ambiguous = new boolean[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                ConfirmedAd ad = entries.get(index).ad;
                if (conflictsWithAcknowledged(ad) || conflictsWithSuppressed(ad)) {
                    ambiguous[index] = true;
                }
            }
            for (int first = 0; first < entries.size(); first++) {
                for (int second = first + 1; second < entries.size(); second++) {
                    if (!AdConflictPolicy.conflicts(
                            entries.get(first).ad, entries.get(second).ad)) continue;
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
            changed = false;
            for (int index = entries.size() - 1; index >= 0; index--) {
                if (!ambiguous[index]) continue;
                Entry removed = entries.remove(index);
                rememberSuppressed(removed.ad);
                changed = true;
            }
        } while (changed);
    }

    private boolean containsEntry(ConfirmedAd candidate) {
        return findEntry(candidate) != null;
    }

    private Entry findEntry(ConfirmedAd candidate) {
        if (candidate == null) return null;
        for (Entry entry : entries) {
            if (sameInterval(entry.ad, candidate)) return entry;
        }
        return null;
    }

    private Entry findClaimedEntry(Claim claim) {
        if (claim == null) return null;
        for (Entry entry : entries) {
            if (entry.claim == claim) return entry;
        }
        return null;
    }

    private boolean isAcknowledged(ConfirmedAd candidate) {
        if (candidate == null) return false;
        for (ConfirmedAd ad : acknowledged) {
            if (sameInterval(ad, candidate)) return true;
        }
        return false;
    }

    private boolean conflictsWithAcknowledged(ConfirmedAd candidate) {
        if (candidate == null) return false;
        for (ConfirmedAd ad : acknowledged) {
            if (AdConflictPolicy.conflicts(ad, candidate)) return true;
        }
        return false;
    }

    private boolean isSuppressed(ConfirmedAd candidate) {
        if (candidate == null) return false;
        for (ConfirmedAd ad : suppressed) {
            if (sameInterval(ad, candidate)) return true;
        }
        return false;
    }

    private void rememberSuppressed(ConfirmedAd ad) {
        if (ad != null && !isSuppressed(ad)) suppressed.add(ad);
    }

    private boolean conflictsWithSuppressed(ConfirmedAd candidate) {
        if (candidate == null) return false;
        for (ConfirmedAd ad : suppressed) {
            if (AdConflictPolicy.conflicts(ad, candidate)) return true;
        }
        return false;
    }

    private static boolean sameInterval(ConfirmedAd first, ConfirmedAd second) {
        return first.getRuleId().equals(second.getRuleId())
                && first.getStartTimeMs() == second.getStartTimeMs()
                && first.getEndTimeMs() == second.getEndTimeMs();
    }

    private static long safeAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static final class Entry {
        final ConfirmedAd ad;
        Claim claim;

        Entry(ConfirmedAd ad) {
            this.ad = ad;
        }
    }

    /** 一次占用的不可复用凭证，只暴露对应的不可变广告区间。 */
    public static final class Claim {
        private final ConfirmedAd ad;

        private Claim(ConfirmedAd ad) {
            this.ad = ad;
        }

        public ConfirmedAd getAd() {
            return ad;
        }
    }

}
