/* 集中实现 SDK v3 的规则合同，供发布校验和贡献合并共同调用。 */
export const ALGORITHM = Object.freeze({
  id: "spectral-sequence-v3",
  sampleRate: 16000,
  windowMs: 512,
  hopMs: 256,
  bandCount: 16,
});

const MAX_RULES = 5000;
const MAX_TOTAL_HASHES = 250000;

export function validateDocument(document) {
  assertRecord(document, "规则根节点");
  assertKeys(document, ["schemaVersion", "revision", "algorithm", "rules"], "规则根节点");
  if (document.schemaVersion !== 3) throw new Error("schemaVersion 必须为 3");
  if (!isInteger(document.revision) || document.revision < 0) throw new Error("revision 无效");
  validateAlgorithm(document.algorithm);
  if (!Array.isArray(document.rules) || document.rules.length > MAX_RULES) {
    throw new Error("rules 不是数组或数量超过上限");
  }
  const ids = new Set();
  let totalHashes = 0;
  for (const rule of document.rules) {
    validateRule(rule);
    if (ids.has(rule.id)) throw new Error(`广告规则 ID 重复：${rule.id}`);
    ids.add(rule.id);
    for (const variant of rule.fingerprints) totalHashes += variant.hashes.length;
    if (totalHashes > MAX_TOTAL_HASHES) throw new Error("规则指纹总量超过上限");
  }
  validateAmbiguousPrefixes(document.rules);
  return document;
}

function validateAlgorithm(value) {
  assertRecord(value, "algorithm");
  assertKeys(value, ["id", "sampleRate", "windowMs", "hopMs", "bandCount"], "algorithm");
  for (const [key, expected] of Object.entries(ALGORITHM)) {
    if (value[key] !== expected) throw new Error("频谱算法或参数不兼容");
  }
}

function validateRule(rule) {
  assertRecord(rule, "广告规则");
  assertKeys(rule, ["id", "durationMs", "anchorOffsetMs", "anchorDurationMs",
    "fingerprints"], "广告规则");
  if (typeof rule.id !== "string" || rule.id.trim() === "" || rule.id.length > 128
      || rule.id !== rule.id.trim()) {
    throw new Error("广告规则 ID 无效");
  }
  if (!isInteger(rule.durationMs) || rule.durationMs < 1000 || rule.durationMs > 600000) {
    throw new Error(`广告时长无效：${rule.id}`);
  }
  if (!isInteger(rule.anchorOffsetMs) || rule.anchorOffsetMs < 0
      || !isInteger(rule.anchorDurationMs) || rule.anchorDurationMs < 2000
      || rule.anchorDurationMs > 5000
      || rule.anchorOffsetMs + rule.anchorDurationMs > rule.durationMs) {
    throw new Error(`广告锚点范围无效：${rule.id}`);
  }
  if (!Array.isArray(rule.fingerprints)
      || rule.fingerprints.length < 1 || rule.fingerprints.length > 4) {
    throw new Error(`指纹相位数量无效：${rule.id}`);
  }

  const offsets = new Set();
  for (const variant of rule.fingerprints) {
    validateVariant(rule, variant, offsets);
    offsets.add(variant.offsetMs);
  }
  if (!offsets.has(0)) throw new Error(`缺少零偏移主指纹：${rule.id}`);
}

function validateVariant(rule, variant, offsets) {
  assertRecord(variant, `指纹相位：${rule.id}`);
  assertKeys(variant, ["offsetMs", "hashes"], `指纹相位：${rule.id}`);
  if (!isInteger(variant.offsetMs) || variant.offsetMs < 0
      || variant.offsetMs >= ALGORITHM.hopMs || offsets.has(variant.offsetMs)) {
    throw new Error(`指纹相位偏移无效或重复：${rule.id}`);
  }
  if (!Array.isArray(variant.hashes) || variant.hashes.length < 4
      || variant.hashes.length > 64
      || variant.hashes.some((hash) => typeof hash !== "string" || !/^[0-9a-f]{8}$/.test(hash))) {
    throw new Error(`频谱哈希必须是 8 位小写十六进制：${rule.id}`);
  }
  if (variant.hashes.length !== expectedFrames(rule.anchorDurationMs, variant.offsetMs)) {
    throw new Error(`指纹长度与锚点时长不一致：${rule.id}`);
  }
  if (requiredConfirmationFrames(variant.hashes) < 0) {
    throw new Error(`指纹开头区分度不足：${rule.id}`);
  }
}

function validateAmbiguousPrefixes(rules) {
  const prefixes = new Map();
  for (const rule of rules) {
    const primary = rule.fingerprints.find((item) => item.offsetMs === 0);
    const prefix = primary.hashes.slice(0, 8).join(":");
    const previous = prefixes.get(prefix);
    if (previous && previous.durationMs !== rule.durationMs) {
      throw new Error(`相同开头指纹存在不同时长：${previous.id} / ${rule.id}`);
    }
    prefixes.set(prefix, rule);
  }
}

function requiredConfirmationFrames(hashes) {
  const first = Number.parseInt(hashes[0], 16) | 0;
  for (let index = 1; index < Math.min(8, hashes.length); index += 1) {
    const current = Number.parseInt(hashes[index], 16) | 0;
    if (bitCount(first ^ current) > 5) return Math.max(4, index + 1);
  }
  return -1;
}

function expectedFrames(anchorDurationMs, offsetMs) {
  const available = anchorDurationMs - offsetMs - ALGORITHM.windowMs;
  return available < 0 ? 0 : Math.floor(available / ALGORITHM.hopMs) + 1;
}

function bitCount(input) {
  let value = input >>> 0;
  let count = 0;
  while (value !== 0) {
    value &= value - 1;
    count += 1;
  }
  return count;
}

function assertRecord(value, label) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`${label}必须是对象`);
  }
}

function assertKeys(value, expected, label) {
  const allowed = new Set(expected);
  const actual = Object.keys(value);
  if (actual.length !== expected.length || actual.some((key) => !allowed.has(key))) {
    throw new Error(`${label}字段不符合 schemaVersion 3`);
  }
}

function isInteger(value) {
  return typeof value === "number" && Number.isInteger(value);
}
