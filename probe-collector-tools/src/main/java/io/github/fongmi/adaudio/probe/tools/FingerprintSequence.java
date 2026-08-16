/* 公开的指纹相位只承载 rules-v1 可序列化字段。 */
package io.github.fongmi.adaudio.probe.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** rules-v1 中一条固定相位的不可变频谱序列。 */
public final class FingerprintSequence {
    private final int phaseMs;
    private final List<String> hashes;

    /**
     * 创建指纹相位。
     *
     * @param phaseMs 相对锚点的相位偏移，单位毫秒
     * @param hashes 8 位小写十六进制哈希序列
     */
    public FingerprintSequence(int phaseMs, List<String> hashes) {
        if (phaseMs != 0 && phaseMs != 64 && phaseMs != 128 && phaseMs != 192) {
            throw new IllegalArgumentException("指纹相位必须为 0、64、128 或 192 毫秒");
        }
        if (hashes == null || hashes.size() < 4 || hashes.size() > 64) {
            throw new IllegalArgumentException("指纹序列长度必须为 4..64");
        }
        List<String> copy = new ArrayList<>(hashes.size());
        for (String hash : hashes) {
            if (hash == null || !hash.matches("[0-9a-f]{8}")) {
                throw new IllegalArgumentException("频谱哈希必须是 8 位小写十六进制");
            }
            copy.add(hash);
        }
        this.phaseMs = phaseMs;
        this.hashes = Collections.unmodifiableList(copy);
    }

    /** @return 相对锚点的相位偏移，单位毫秒 */
    public int getPhaseMs() { return phaseMs; }

    /** @return 不可变哈希序列 */
    public List<String> getHashes() { return hashes; }
}
