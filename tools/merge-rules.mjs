/* 合并一批 v3 贡献规则；同 ID 覆盖、不同 ID 新增，整批只递增一次 revision。 */
import { validateDocument } from "./rule-contract.mjs";

export function mergeDocuments(target, contribution) {
  validateDocument(target);
  validateDocument(contribution);
  if (contribution.rules.length === 0) throw new Error("采集文件没有可合并的广告规则");

  let added = 0;
  let replaced = 0;
  const indexes = new Map(target.rules.map((rule, index) => [rule.id, index]));
  for (const incoming of contribution.rules) {
    const index = indexes.get(incoming.id);
    if (index === undefined) {
      indexes.set(incoming.id, target.rules.length);
      target.rules.push(incoming);
      added += 1;
    } else {
      target.rules[index] = incoming;
      replaced += 1;
    }
  }
  target.rules.sort((left, right) => left.id.localeCompare(right.id));
  target.revision += 1;
  validateDocument(target);
  return { document: target, added, replaced };
}
