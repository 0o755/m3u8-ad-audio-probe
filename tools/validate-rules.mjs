/* 按 Probe Rules v1 严格校验唯一发布文件。 */
import { readFile } from "node:fs/promises";
import { parseDocumentBytes } from "./rule-contract.mjs";

const file = process.argv[2] ?? "rules.json";

try {
  const document = parseDocumentBytes(await readFile(file));
  console.log(`规则校验通过：revision ${document.revision}，${document.rules.length} 条广告规则`);
} catch (error) {
  console.error(`规则校验失败：${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
}
