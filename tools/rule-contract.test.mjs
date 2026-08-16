/* 用正反例锁定 Probe Rules v1，避免发布工具偏离 SDK 解析合同。 */
import test from "node:test";
import assert from "node:assert/strict";
import {
  ALGORITHM,
  FORMAT,
  MAX_DOCUMENT_BYTES,
  parseDocumentBytes,
  parseDocumentText,
  validateDocument,
} from "./rule-contract.mjs";

test("接受空规则集和带嵌套测试元数据的完整 v1 规则", () => {
  assert.equal(validateDocument(documentOf(1, [])).rules.length, 0);
  const document = documentOf(7, [rule("test-ad")]);
  document.rules[0].test = {
    url: "https://example.com/video/index.m3u8",
    adStartMs: 12_000,
  };

  assert.equal(validateDocument(document), document);
  const parsed = parseDocumentText(JSON.stringify(document));
  assert.equal(parsed.rules[0].test.adStartMs, 12_000);
  assert.equal(parsed.rules[0].fingerprints.length, 4);
});

test("拒绝旧根结构、revision 0、对象算法和未知字段", () => {
  const legacy = documentOf(1, []);
  delete legacy.format;
  assert.throws(() => validateDocument(legacy), /Probe Rules v1/);

  const zero = documentOf(0, []);
  assert.throws(() => validateDocument(zero), /revision/);

  const objectAlgorithm = documentOf(1, []);
  objectAlgorithm.algorithm = { id: ALGORITHM };
  assert.throws(() => validateDocument(objectAlgorithm), /algorithm/);

  const unknown = documentOf(1, []);
  unknown.testUrls = {};
  assert.throws(() => validateDocument(unknown), /Probe Rules v1/);
});

test("严格解析拒绝重复字段、小数、指数、尾随内容和无效 UTF-8", () => {
  const json = JSON.stringify(documentOf(1, []));
  assert.throws(() => parseDocumentText(json.replace('"revision":1',
    '"revision":1,"revision":2')), /重复字段/);
  assert.throws(() => parseDocumentText(json.replace('"revision":1', '"revision":1.0')),
    /普通十进制整数/);
  assert.throws(() => parseDocumentText(json.replace('"revision":1', '"revision":1e0')),
    /普通十进制整数/);
  assert.throws(() => parseDocumentText(`${json} trailing`), /尾随内容/);
  assert.throws(() => parseDocumentBytes(Uint8Array.from([0xc3, 0x28])), /严格 UTF-8/);
});

test("严格字节解析接受 BOM，并在解析前执行 4 MiB 上限", () => {
  const encoded = new TextEncoder().encode(JSON.stringify(documentOf(1, [])));
  const withBom = new Uint8Array(encoded.length + 3);
  withBom.set([0xef, 0xbb, 0xbf]);
  withBom.set(encoded, 3);

  assert.equal(parseDocumentBytes(withBom).revision, 1);
  assert.throws(() => parseDocumentBytes(new Uint8Array(MAX_DOCUMENT_BYTES + 1)), /4 MiB/);
});

test("规则 ID 只接受最长 64 位的小写安全字符", () => {
  for (const id of ["Upper", "-leading", "contains space", "a".repeat(65)]) {
    assert.throws(() => validateDocument(documentOf(1, [rule(id)])), /ID 无效/);
  }
  assert.equal(validateDocument(documentOf(1, [rule("ad_01.hk-v2")])).rules.length, 1);
});

test("恰好要求 0、64、128、192 四个唯一相位", () => {
  const missing = documentOf(1, [rule("missing")]);
  missing.rules[0].fingerprints.pop();
  assert.throws(() => validateDocument(missing), /四个固定相位/);

  const duplicate = documentOf(1, [rule("duplicate")]);
  duplicate.rules[0].fingerprints[3].phaseMs = 128;
  assert.throws(() => validateDocument(duplicate), /无效或重复/);
});

test("拒绝相位长度错误、非小写哈希和开头无区分度", () => {
  const wrongLength = documentOf(1, [rule("wrong-length")]);
  wrongLength.rules[0].fingerprints[3].hashes.pop();
  assert.throws(() => validateDocument(wrongLength), /长度与锚点时长不一致/);

  const uppercase = documentOf(1, [rule("uppercase")]);
  uppercase.rules[0].fingerprints[0].hashes[0] = "ABCDEF12";
  assert.throws(() => validateDocument(uppercase), /小写十六进制/);

  const flat = documentOf(1, [rule("flat")]);
  flat.rules[0].fingerprints[0].hashes.fill("00000000");
  assert.throws(() => validateDocument(flat), /开头区分度不足/);
});

test("拒绝超过 1024 条规则或 65536 帧指纹", () => {
  const template = rule("template");
  const tooMany = Array.from({ length: 1025 }, (_, index) => ({
    ...structuredClone(template), id: `ad-${index}`,
  }));
  assert.throws(() => validateDocument(documentOf(1, tooMany)), /1024/);

  const tooManyHashes = Array.from({ length: 924 }, (_, index) => ({
    ...rule(`long-ad-${index}`, "ffffffff", 5_000), durationMs: 5_000,
  }));
  assert.throws(() => validateDocument(documentOf(1, tooManyHashes)), /65536/);
});

test("拒绝相同或包含的主指纹前缀推导出不同结束偏移", () => {
  const samePrefix = documentOf(1, [rule("first"), rule("second")]);
  samePrefix.rules[1].durationMs = 16_000;
  assert.throws(() => validateDocument(samePrefix), /相同开头指纹存在不同结束位置/);

  const short = rule("short", "ffffffff", 2_000);
  const long = rule("long", "ffffffff", 5_000);
  long.durationMs = 6_000;
  assert.throws(() => validateDocument(documentOf(1, [long, short])),
    /相同开头指纹存在不同结束位置/);
});

test("test 只接受完整 HTTP(S) 地址和安全的测试终点", () => {
  const incomplete = documentOf(1, [rule("incomplete")]);
  incomplete.rules[0].test = { url: "https://example.com/video.mp4" };
  assert.throws(() => validateDocument(incomplete), /Probe Rules v1/);

  const unsafeUrl = documentOf(1, [rule("unsafe-url")]);
  unsafeUrl.rules[0].test = { url: "file:///video.mp4", adStartMs: 0 };
  assert.throws(() => validateDocument(unsafeUrl), /HTTP\(S\)/);

  const malformedUrl = documentOf(1, [rule("malformed-url")]);
  malformedUrl.rules[0].test = { url: "https://example.com/%", adStartMs: 0 };
  assert.throws(() => validateDocument(malformedUrl), /HTTP\(S\)/);

  const bracketPath = documentOf(1, [rule("bracket-path")]);
  bracketPath.rules[0].test = { url: "https://example.com/[ad]", adStartMs: 0 };
  assert.throws(() => validateDocument(bracketPath), /HTTP\(S\)/);

  const unicodeSpace = documentOf(1, [rule("unicode-space")]);
  unicodeSpace.rules[0].test = { url: "https://example.com/a\u00a0b", adStartMs: 0 };
  assert.throws(() => validateDocument(unicodeSpace), /HTTP\(S\)/);

  const missingHost = documentOf(1, [rule("missing-host")]);
  missingHost.rules[0].test = { url: "https://@/video.mp4", adStartMs: 0 };
  assert.throws(() => validateDocument(missingHost), /HTTP\(S\)/);

  const repairedUrl = documentOf(1, [rule("repaired-url")]);
  repairedUrl.rules[0].test = { url: "https:example.com/video.mp4", adStartMs: 0 };
  assert.throws(() => validateDocument(repairedUrl), /HTTP\(S\)/);

  const nonAsciiHost = documentOf(1, [rule("non-ascii-host")]);
  nonAsciiHost.rules[0].test = { url: "https://例子.测试/video.mp4", adStartMs: 0 };
  assert.throws(() => validateDocument(nonAsciiHost), /HTTP\(S\)/);

  const ipv6 = documentOf(1, [rule("ipv6")]);
  ipv6.rules[0].test = {
    url: "https://[0:0:0:0:0:0:0:1]/video.mp4", adStartMs: 0,
  };
  assert.equal(validateDocument(ipv6).rules.length, 1);

  const overflow = documentOf(1, [rule("overflow")]);
  overflow.rules[0].test = {
    url: "https://example.com/video.mp4",
    adStartMs: Number.MAX_SAFE_INTEGER - overflow.rules[0].durationMs + 1,
  };
  assert.throws(() => validateDocument(overflow), /测试终点/);
});

function documentOf(revision, rules) {
  return { format: FORMAT, schemaVersion: 1, revision, algorithm: ALGORITHM, rules };
}

function rule(id, secondHash = "ffffffff", anchorDurationMs = 2_000) {
  return {
    id,
    durationMs: 15_000,
    anchorOffsetMs: 0,
    anchorDurationMs,
    fingerprints: [0, 64, 128, 192].map((phaseMs) => ({
      phaseMs,
      hashes: hashesFor(anchorDurationMs, phaseMs, secondHash),
    })),
  };
}

function hashesFor(anchorDurationMs, phaseMs, secondHash) {
  const count = Math.floor((anchorDurationMs - phaseMs - 512) / 256) + 1;
  return Array.from({ length: count }, (_, index) => index === 1
    ? secondHash : "00000000");
}
