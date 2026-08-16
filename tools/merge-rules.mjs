/* 合并一批 v3 贡献规则；同 ID 覆盖、不同 ID 新增，整批只递增一次 revision。 */
import { MAX_REVISION, validateDocument } from "./rule-contract.mjs";

export function mergeDocuments(target, contribution) {
  validateDocument(target);
  validateDocument(contribution);
  if (contribution.rules.length === 0) throw new Error("采集文件没有可合并的广告规则");
  if (target.revision >= MAX_REVISION) throw new Error("目标规则修订号已达到安全上限");

  let added = 0;
  let replaced = 0;
  const candidate = structuredClone(target);
  const testUrls = new Map(Object.entries(candidate.testUrls ?? {}));
  const testPositions = new Map(Object.entries(candidate.testPositionsMs ?? {}));
  const indexes = new Map(candidate.rules.map((rule, index) => [rule.id, index]));
  for (const incoming of contribution.rules) {
    const index = indexes.get(incoming.id);
    const previous = index === undefined ? undefined : candidate.rules[index];
    const incomingCopy = structuredClone(incoming);
    if (index === undefined) {
      indexes.set(incoming.id, candidate.rules.length);
      candidate.rules.push(incomingCopy);
      added += 1;
    } else {
      candidate.rules[index] = incomingCopy;
      replaced += 1;
    }
    const contentChanged = previous !== undefined && !sameRule(previous, incoming);
    const hasIncomingUrl = contribution.testUrls !== undefined
      && Object.prototype.hasOwnProperty.call(contribution.testUrls, incoming.id);
    if (hasIncomingUrl) testUrls.set(incoming.id, contribution.testUrls[incoming.id]);
    else if (contentChanged) testUrls.delete(incoming.id);
    const hasIncomingPosition = contribution.testPositionsMs !== undefined
      && Object.prototype.hasOwnProperty.call(contribution.testPositionsMs, incoming.id);
    if (hasIncomingPosition) {
      testPositions.set(incoming.id, contribution.testPositionsMs[incoming.id]);
    } else if (contentChanged) testPositions.delete(incoming.id);
  }
  candidate.rules.sort((left, right) => left.id.localeCompare(right.id));
  if (testUrls.size === 0) delete candidate.testUrls;
  else candidate.testUrls = Object.fromEntries(testUrls);
  if (testPositions.size === 0) delete candidate.testPositionsMs;
  else candidate.testPositionsMs = Object.fromEntries(testPositions);
  candidate.revision += 1;
  validateDocument(candidate);
  return { document: candidate, added, replaced };
}

/** JSON 字段顺序不属于规则内容，测试元数据只随真实规则变化失效。 */
function sameRule(left, right) {
  if (left.id !== right.id || left.durationMs !== right.durationMs
      || left.anchorOffsetMs !== right.anchorOffsetMs
      || left.anchorDurationMs !== right.anchorDurationMs
      || left.fingerprints.length !== right.fingerprints.length) {
    return false;
  }
  return left.fingerprints.every((variant, index) => {
    const other = right.fingerprints[index];
    return other !== undefined && variant.offsetMs === other.offsetMs
      && variant.hashes.length === other.hashes.length
      && variant.hashes.every((hash, hashIndex) => hash === other.hashes[hashIndex]);
  });
}
