/* 用正反例锁定 v3 合同，避免校验器意外重新接受旧字段或低区分度指纹。 */
import test from "node:test";
import assert from "node:assert/strict";
import { ALGORITHM, validateDocument } from "./rule-contract.mjs";

test("接受结构完整且具有开头区分度的 v3 规则", () => {
  const document = validDocument();
  assert.equal(validateDocument(document), document);
});

test("拒绝任何旧 schema", () => {
  const document = validDocument();
  document.schemaVersion = 2;
  assert.throws(() => validateDocument(document), /schemaVersion 必须为 3/);
});

test("拒绝已删除的 fingerprint 和 variants 字段", () => {
  const document = validDocument();
  const rule = document.rules[0];
  delete rule.fingerprints;
  rule.fingerprint = ["00000000"];
  rule.variants = [];
  assert.throws(() => validateDocument(document), /字段不符合 schemaVersion 3/);
});

test("拒绝开头八帧没有区分度的规则", () => {
  const document = validDocument();
  document.rules[0].fingerprints[0].hashes.fill("12345678");
  assert.throws(() => validateDocument(document), /指纹开头区分度不足/);
});

test("拒绝与相位偏移不一致的哈希数量", () => {
  const document = validDocument();
  document.rules[0].fingerprints[0].hashes.pop();
  assert.throws(() => validateDocument(document), /指纹长度与锚点时长不一致/);
});

function validDocument() {
  const hashes = Array.from({ length: 18 }, (_, index) => index === 1
    ? "ffffffff" : "00000000");
  return {
    schemaVersion: 3,
    revision: 1,
    algorithm: { ...ALGORITHM },
    rules: [{
      id: "test-ad",
      durationMs: 15000,
      anchorOffsetMs: 0,
      anchorDurationMs: 5000,
      fingerprints: [{ offsetMs: 0, hashes }],
    }],
  };
}
