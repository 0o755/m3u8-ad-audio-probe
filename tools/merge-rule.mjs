/* 读取并合并一批 v3 贡献规则，成功后原子写回主规则文件。 */
import { readFile, writeFile } from "node:fs/promises";
import { validateDocument } from "./rule-contract.mjs";
import { mergeDocuments } from "./merge-rules.mjs";

const targetFile = process.argv[2] ?? "rules.json";
const contributionFile = process.argv[3];
if (!contributionFile) throw new Error("用法：node tools/merge-rule.mjs rules.json ad-rule.json");

const target = validateDocument(JSON.parse(await readFile(targetFile, "utf8")));
const contribution = validateDocument(JSON.parse(await readFile(contributionFile, "utf8")));
const result = mergeDocuments(target, contribution);
await writeFile(targetFile, `${JSON.stringify(target, null, 2)}\n`, "utf8");
console.log(`已合并 ${contribution.rules.length} 条规则：新增 ${result.added}，覆盖 ${result.replaced}，revision ${target.revision}`);
