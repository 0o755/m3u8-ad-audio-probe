/* 严格读取并合并一批 Probe v1 规则，成功后原子写回主规则文件。 */
import { readFile } from "node:fs/promises";
import { pathToFileURL } from "node:url";
import { writeFileAtomically } from "./atomic-write.mjs";
import { parseDocumentBytes, serializeDocument } from "./rule-contract.mjs";
import { mergeDocuments } from "./merge-rules.mjs";

/** 合并文件时先生成并校验最终字节，再执行唯一一次原子写。 */
export async function mergeRuleFiles(targetFile, contributionFile, options = {}) {
  const loadFile = options.readFile ?? readFile;
  const commitFile = options.writeFileAtomically ?? writeFileAtomically;
  const target = parseDocumentBytes(await loadFile(targetFile));
  const contribution = parseDocumentBytes(await loadFile(contributionFile));
  const result = mergeDocuments(target, contribution);
  const output = serializeDocument(result.document, true);
  await commitFile(targetFile, output);
  return { ...result, contributionCount: contribution.rules.length };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const targetFile = process.argv[2] ?? "rules.json";
  const contributionFile = process.argv[3];
  if (!contributionFile) throw new Error("用法：node tools/merge-rule.mjs rules.json ad-rule.json");
  const result = await mergeRuleFiles(targetFile, contributionFile);
  console.log(`已合并 ${result.contributionCount} 条规则：新增 ${result.added}，覆盖 ${result.replaced}，revision ${result.document.revision}`);
}
