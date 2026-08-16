/* 校验公开 JSON Schema 的核心常量与实际 Probe v1 合同保持一致。 */
import { readFile } from "node:fs/promises";
import {
  ALGORITHM,
  FORMAT,
  MAX_HASHES_PER_PHASE,
  MAX_REVISION,
  MAX_RULES,
  MAX_TEST_URL_LENGTH,
  REQUIRED_PHASES,
  SCHEMA_VERSION,
} from "./rule-contract.mjs";

const schemaFile = process.argv[2] ?? "rule-schema.json";
const schema = JSON.parse(await readFile(schemaFile, "utf8"));
const root = schema.properties;
const definitions = schema.$defs;

assert(root?.format?.const === FORMAT, "Schema format 与运行时不一致");
assert(root?.schemaVersion?.const === SCHEMA_VERSION,
  "Schema schemaVersion 与运行时不一致");
assert(root?.revision?.minimum === 1 && root?.revision?.maximum === MAX_REVISION,
  "Schema revision 范围与运行时不一致");
assert(root?.algorithm?.const === ALGORITHM, "Schema algorithm 与运行时不一致");
assert(root?.rules?.maxItems === MAX_RULES, "Schema rules 上限与运行时不一致");
assert(definitions?.test?.properties?.url?.maxLength === MAX_TEST_URL_LENGTH,
  "Schema test.url 上限与运行时不一致");
assert(definitions?.fingerprint?.properties?.hashes?.maxItems === MAX_HASHES_PER_PHASE,
  "Schema hashes 上限与运行时不一致");
assert(JSON.stringify(definitions?.fingerprint?.properties?.phaseMs?.enum)
    === JSON.stringify(REQUIRED_PHASES), "Schema 指纹相位与运行时不一致");

console.log(`Schema 校验通过：${schemaFile}`);

function assert(condition, message) {
  if (!condition) throw new Error(message);
}
