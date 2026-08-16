/* 验证 v1 批量合并、嵌套 test 语义和失败不污染。 */
import test from "node:test";
import assert from "node:assert/strict";
import { ALGORITHM, FORMAT, MAX_REVISION } from "./rule-contract.mjs";
import { mergeRuleFiles } from "./merge-rule.mjs";
import { mergeDocuments } from "./merge-rules.mjs";

test("整批新增和覆盖按目标 revision 只递增一次", () => {
  const target = documentOf(7, [rule("keep", "ffffffff"), rule("replace", "ffffff00")]);
  const originalTarget = structuredClone(target);
  const contribution = documentOf(99, [
    rule("replace", "ffff0000", 30_000),
    rule("added", "ff000000"),
  ]);
  const result = mergeDocuments(target, contribution);

  assert.equal(result.added, 1);
  assert.equal(result.replaced, 1);
  assert.equal(result.document.revision, 8);
  assert.deepEqual(result.document.rules.map((item) => item.id), ["added", "keep", "replace"]);
  assert.equal(result.document.rules.find((item) => item.id === "replace").durationMs, 30_000);
  assert.deepEqual(target, originalTarget);
});

test("传入 test 时覆盖旧测试元数据", () => {
  const targetRule = withTest(rule("same"), "https://example.com/old.m3u8", 1_000);
  const incoming = withTest(rule("same"), "https://example.com/new.m3u8", 2_000);

  const result = mergeDocuments(documentOf(1, [targetRule]), documentOf(1, [incoming]));

  assert.deepEqual(result.document.rules[0].test, incoming.test);
});

test("相同规则不带 test 时保留旧元数据且忽略相位顺序", () => {
  const original = withTest(rule("same"), "https://example.com/same.m3u8", 4_000);
  const incoming = structuredClone(original);
  delete incoming.test;
  incoming.fingerprints.reverse();
  const reordered = {
    fingerprints: incoming.fingerprints.map((fingerprint) => ({
      hashes: [...fingerprint.hashes], phaseMs: fingerprint.phaseMs,
    })),
    anchorDurationMs: incoming.anchorDurationMs,
    id: incoming.id,
    anchorOffsetMs: incoming.anchorOffsetMs,
    durationMs: incoming.durationMs,
  };

  const result = mergeDocuments(documentOf(1, [original]), documentOf(1, [reordered]));

  assert.deepEqual(result.document.rules[0].test, original.test);
});

test("规则内容变化且不带 test 时清除旧元数据", () => {
  const original = withTest(rule("changed"), "https://example.com/old.m3u8", 3_000);
  const incoming = rule("changed", "ffffff00", 30_000);

  const result = mergeDocuments(documentOf(1, [original]), documentOf(1, [incoming]));

  assert.equal(Object.prototype.hasOwnProperty.call(result.document.rules[0], "test"), false);
});

test("拒绝空贡献和已到安全上限的目标 revision", () => {
  const target = documentOf(1, [rule("keep")]);
  assert.throws(() => mergeDocuments(target, documentOf(1, [])), /没有可合并/);

  const maxTarget = documentOf(MAX_REVISION, [rule("keep")]);
  const original = structuredClone(maxTarget);
  assert.throws(() => mergeDocuments(maxTarget, documentOf(1, [rule("added", "ffffff00")])),
    /修订号已达到安全上限/);
  assert.deepEqual(maxTarget, original);
});

test("贡献或最终候选校验失败时不污染目标对象", () => {
  const target = documentOf(9, [rule("keep")]);
  const original = structuredClone(target);
  const invalid = documentOf(1, [rule("Upper")]);
  assert.throws(() => mergeDocuments(target, invalid), /ID 无效/);
  assert.deepEqual(target, original);

  const conflict = documentOf(1, [rule("conflict", "ffffffff", 16_000)]);
  assert.throws(() => mergeDocuments(target, conflict), /相同开头指纹存在不同结束位置/);
  assert.deepEqual(target, original);
});

test("合并 API 拒绝超过 4 MiB 的候选且不污染目标对象", () => {
  const target = largeDocument(1, 0, 400);
  const contribution = largeDocument(1, 400, 400);
  const original = structuredClone(target);

  assert.throws(() => mergeDocuments(target, contribution), /4 MiB/);
  assert.deepEqual(target, original);
});

test("格式化后的最终文件超过 4 MiB 时不执行原子写", async () => {
  const target = largeDocument(1, 0, 240);
  const contribution = largeDocument(1, 240, 240);
  const inputs = new Map([
    ["target.json", Buffer.from(JSON.stringify(target))],
    ["contribution.json", Buffer.from(JSON.stringify(contribution))],
  ]);
  let writes = 0;

  await assert.rejects(mergeRuleFiles("target.json", "contribution.json", {
    readFile: async (path) => inputs.get(path),
    writeFileAtomically: async () => { writes += 1; },
  }), /4 MiB/);
  assert.equal(writes, 0);
});

function documentOf(revision, rules) {
  return { format: FORMAT, schemaVersion: 1, revision, algorithm: ALGORITHM, rules };
}

function largeDocument(revision, start, count) {
  const prefix = "https://example.com/";
  const url = `${prefix}${"a".repeat(8192 - prefix.length)}`;
  return documentOf(revision, Array.from({ length: count }, (_, index) => ({
    ...rule(`large-${start + index}`), test: { url, adStartMs: 0 },
  })));
}

function rule(id, secondHash = "ffffffff", durationMs = 15_000) {
  const anchorDurationMs = 5_000;
  return {
    id,
    durationMs,
    anchorOffsetMs: 0,
    anchorDurationMs,
    fingerprints: [0, 64, 128, 192].map((phaseMs) => ({
      phaseMs,
      hashes: hashesFor(anchorDurationMs, phaseMs, secondHash),
    })),
  };
}

function withTest(value, url, adStartMs) {
  return { ...value, test: { url, adStartMs } };
}

function hashesFor(anchorDurationMs, phaseMs, secondHash) {
  const count = Math.floor((anchorDurationMs - phaseMs - 512) / 256) + 1;
  return Array.from({ length: count }, (_, index) => index === 1
    ? secondHash : "00000000");
}
