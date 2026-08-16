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

test("拒绝相同开头指纹对应不同跳转终点", () => {
  const document = validDocument();
  const second = structuredClone(document.rules[0]);
  second.id = "same-prefix-different-end";
  second.anchorOffsetMs = 1_000;
  document.rules.push(second);

  assert.throws(() => validateDocument(document), /相同开头指纹存在不同结束位置/);
});

test("拒绝短主指纹是长主指纹前缀但结束位置不同", () => {
  const document = validDocument();
  const short = structuredClone(document.rules[0]);
  short.id = "short-prefix";
  short.durationMs = 2_000;
  short.anchorDurationMs = 2_000;
  short.fingerprints[0].hashes = short.fingerprints[0].hashes.slice(0, 6);
  document.rules.push(short);

  assert.throws(() => validateDocument(document), /相同开头指纹存在不同结束位置/);
});

test("拒绝超出跨 JavaScript 安全整数范围的 revision", () => {
  const document = validDocument();
  document.revision = Number.MAX_SAFE_INTEGER + 1;
  assert.throws(() => validateDocument(document), /revision 无效/);
});

test("接受与规则 ID 绑定的 HTTP 测试链接", () => {
  const document = validDocument();
  document.testUrls = { "test-ad": "https://example.com/video/index.m3u8" };

  assert.equal(validateDocument(document), document);
});

test("拒绝未知规则或本地协议的测试链接", () => {
  const unknown = validDocument();
  unknown.testUrls = { unknown: "https://example.com/video.m3u8" };
  assert.throws(() => validateDocument(unknown), /测试链接无效/);

  const unsafe = validDocument();
  unsafe.testUrls = { "test-ad": "file:///tmp/video.m3u8" };
  assert.throws(() => validateDocument(unsafe), /HTTP\(S\)/);
});

test("拒绝测试链接总量超过 4 MiB", () => {
  const document = validDocument();
  const template = document.rules[0];
  document.rules = Array.from({ length: 513 }, (_, index) => ({
    ...structuredClone(template),
    id: `test-ad-${index}`,
  }));
  const longUrl = "https://example.com/" + "a".repeat(8_192 - 20);
  document.testUrls = Object.fromEntries(document.rules.map((rule) => [rule.id, longUrl]));

  assert.throws(() => validateDocument(document), /测试链接总量超过 4 MiB/);
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
