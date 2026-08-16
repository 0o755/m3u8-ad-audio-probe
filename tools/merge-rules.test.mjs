/* 验证批量贡献的新增、覆盖、排序和单次修订号递增。 */
import test from "node:test";
import assert from "node:assert/strict";
import { ALGORITHM, MAX_REVISION } from "./rule-contract.mjs";
import { mergeDocuments } from "./merge-rules.mjs";

test("整批新增和覆盖只递增一次 revision", () => {
  const target = documentOf(7, [rule("keep", "ffffffff"), rule("replace", "ffffff00")]);
  const originalTarget = structuredClone(target);
  const contribution = documentOf(1, [
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

test("拒绝空的批量贡献文件", () => {
  assert.throws(() => mergeDocuments(documentOf(1, [rule("keep", "ffffffff")]),
    documentOf(1, [])), /没有可合并/);
});

test("合并时保留贡献规则携带的测试链接", () => {
  const target = documentOf(1, [rule("keep", "ffffffff")]);
  const contribution = documentOf(1, [rule("linked", "ffffff00")]);
  contribution.testUrls = { linked: "https://example.com/linked.m3u8" };

  const result = mergeDocuments(target, contribution);

  assert.equal(result.document.testUrls.linked, "https://example.com/linked.m3u8");
});

test("拒绝递增已经达到安全上限的 revision", () => {
  const target = documentOf(MAX_REVISION, [rule("keep", "ffffffff")]);
  const originalTarget = structuredClone(target);

  assert.throws(() => mergeDocuments(target,
    documentOf(1, [rule("added", "ffffff00")])), /修订号已达到安全上限/);
  assert.deepEqual(target, originalTarget);
});

test("候选文档校验失败时不污染目标对象", () => {
  const target = documentOf(9, [rule("keep", "ffffffff")]);
  const originalTarget = structuredClone(target);
  const contribution = documentOf(1, [rule("conflict", "ffffffff", 16_000)]);

  assert.throws(() => mergeDocuments(target, contribution), /相同开头指纹存在不同结束位置/);
  assert.deepEqual(target, originalTarget);
});

test("测试链接只读取保留 ID 的自有属性", () => {
  const target = documentOf(1, [rule("keep", "ffffffff")]);
  const contribution = documentOf(1, [
    rule("__proto__", "ffffff00"),
    rule("toString", "ffff0000"),
    rule("linked", "ff000000"),
  ]);
  contribution.testUrls = { linked: "https://example.com/linked.m3u8" };

  const result = mergeDocuments(target, contribution);

  assert.deepEqual(result.document.testUrls,
    { linked: "https://example.com/linked.m3u8" });
});

function documentOf(revision, rules) {
  return { schemaVersion: 3, revision, algorithm: { ...ALGORITHM }, rules };
}

function rule(id, secondHash, durationMs = 15_000) {
  const hashes = Array.from({ length: 18 }, (_, index) => index === 1
    ? secondHash : "00000000");
  return {
    id,
    durationMs,
    anchorOffsetMs: 0,
    anchorDurationMs: 5_000,
    fingerprints: [{ offsetMs: 0, hashes }],
  };
}
