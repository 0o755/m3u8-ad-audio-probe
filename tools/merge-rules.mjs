/* 合并一批 Probe v1 贡献规则；成功候选整批只递增一次 revision。 */
import { MAX_REVISION, serializeDocument, validateDocument } from "./rule-contract.mjs";

export function mergeDocuments(target, contribution) {
  validateDocument(target);
  validateDocument(contribution);
  if (contribution.rules.length === 0) throw new Error("采集文件没有可合并的广告规则");
  if (target.revision >= MAX_REVISION) throw new Error("目标规则修订号已达到安全上限");

  let added = 0;
  let replaced = 0;
  const candidate = structuredClone(target);
  const indexes = new Map(candidate.rules.map((rule, index) => [rule.id, index]));
  for (const incoming of contribution.rules) {
    const index = indexes.get(incoming.id);
    const previous = index === undefined ? undefined : candidate.rules[index];
    const incomingCopy = structuredClone(incoming);
    if (previous !== undefined && !Object.prototype.hasOwnProperty.call(incomingCopy, "test")
        && sameRuleContent(previous, incomingCopy)
        && Object.prototype.hasOwnProperty.call(previous, "test")) {
      incomingCopy.test = structuredClone(previous.test);
    }

    if (index === undefined) {
      indexes.set(incoming.id, candidate.rules.length);
      candidate.rules.push(incomingCopy);
      added += 1;
    } else {
      candidate.rules[index] = incomingCopy;
      replaced += 1;
    }
  }
  candidate.rules.sort((left, right) => left.id < right.id ? -1 : left.id > right.id ? 1 : 0);
  candidate.revision += 1;
  // API 返回前检查紧凑表示，避免调用方拿到无法发布的超限候选。
  serializeDocument(candidate);
  return { document: candidate, added, replaced };
}

/** `test` 不属于音频规则内容，相位数组顺序也不改变规则语义。 */
function sameRuleContent(left, right) {
  if (left.id !== right.id || left.durationMs !== right.durationMs
      || left.anchorOffsetMs !== right.anchorOffsetMs
      || left.anchorDurationMs !== right.anchorDurationMs
      || left.fingerprints.length !== right.fingerprints.length) {
    return false;
  }
  const rightByPhase = new Map(right.fingerprints.map((item) => [item.phaseMs, item.hashes]));
  return left.fingerprints.every((fingerprint) => {
    const hashes = rightByPhase.get(fingerprint.phaseMs);
    return hashes !== undefined && fingerprint.hashes.length === hashes.length
      && fingerprint.hashes.every((hash, index) => hash === hashes[index]);
  });
}
