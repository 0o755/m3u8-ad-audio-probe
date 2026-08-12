/* 校验唯一发布文件是否完整符合 SDK v3 规则合同。 */
import { readFile } from "node:fs/promises";
import { validateDocument } from "./rule-contract.mjs";

const file = process.argv[2] ?? "rules.json";

try {
  const document = JSON.parse(await readFile(file, "utf8"));
  validateDocument(document);
  console.log(`规则校验通过：revision ${document.revision}，${document.rules.length} 条广告规则`);
} catch (error) {
  console.error(`规则校验失败：${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
