/* 验证批量贡献的新增、覆盖、排序和单次修订号递增。 */
import test from "node:test";
import assert from "node:assert/strict";
import { ALGORITHM } from "./rule-contract.mjs";
import { mergeDocuments } from "./merge-rules.mjs";

test("整批新增和覆盖只递增一次 revision", () => {
  const target = documentOf(7, [rule("keep", "ffffffff"), rule("replace", "ffffff00")]);
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
});

test("拒绝空的批量贡献文件", () => {
  assert.throws(() => mergeDocuments(documentOf(1, [rule("keep", "ffffffff")]),
    documentOf(1, [])), /没有可合并/);
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
