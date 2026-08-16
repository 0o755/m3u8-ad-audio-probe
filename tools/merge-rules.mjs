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
    const hasIncomingUrl = contribution.testUrls !== undefined
      && Object.prototype.hasOwnProperty.call(contribution.testUrls, incoming.id);
    if (hasIncomingUrl) testUrls.set(incoming.id, contribution.testUrls[incoming.id]);
    else if (previous !== undefined && JSON.stringify(previous) !== JSON.stringify(incoming)) {
      testUrls.delete(incoming.id);
    }
  }
  candidate.rules.sort((left, right) => left.id.localeCompare(right.id));
  if (testUrls.size === 0) delete candidate.testUrls;
  else candidate.testUrls = Object.fromEntries(testUrls);
  candidate.revision += 1;
  validateDocument(candidate);
  return { document: candidate, added, replaced };
}
