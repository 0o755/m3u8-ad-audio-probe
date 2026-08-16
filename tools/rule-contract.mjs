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
const MAX_TEST_URL_BYTES = 4 * 1024 * 1024;
export const MAX_REVISION = Number.MAX_SAFE_INTEGER;

export function validateDocument(document) {
  assertRecord(document, "规则根节点");
  assertKeysWithOptional(document, ["schemaVersion", "revision", "algorithm", "rules"],
    ["testUrls", "testPositionsMs"], "规则根节点");
  if (document.schemaVersion !== 3) throw new Error("schemaVersion 必须为 3");
  if (!Number.isSafeInteger(document.revision) || document.revision < 0) {
    throw new Error("revision 无效");
  }
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
  validateTestUrls(document.testUrls, ids);
  validateTestPositions(document.testPositionsMs, ids);
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
  const root = createPrefixNode();
  for (const rule of rules) {
    const primary = rule.fingerprints.find((item) => item.offsetMs === 0);
    const hashes = primary.hashes.slice(0, 8);
    const endpoint = endpointOffset(rule);
    const path = [];
    let node = root;
    for (const hash of hashes) {
      if (node.terminal && node.terminal.endpoint !== endpoint) {
        throw prefixConflict(node.terminal.ruleId, rule.id);
      }
      if (!node.children.has(hash)) node.children.set(hash, createPrefixNode());
      node = node.children.get(hash);
      path.push(node);
    }
    if (node.subtreeEndpoint !== null
        && (node.subtreeMixed || node.subtreeEndpoint !== endpoint)) {
      const previous = node.subtreeEndpoint !== endpoint
        ? node.subtreeRuleId : node.mixedRuleId;
      throw prefixConflict(previous, rule.id);
    }
    node.terminal = { endpoint, ruleId: rule.id };
    for (const item of path) {
      if (item.subtreeEndpoint === null) {
        item.subtreeEndpoint = endpoint;
        item.subtreeRuleId = rule.id;
      } else if (item.subtreeEndpoint !== endpoint) {
        item.subtreeMixed = true;
        if (item.mixedRuleId === null) item.mixedRuleId = rule.id;
      }
    }
  }
}

function createPrefixNode() {
  return {
    children: new Map(), terminal: null,
    subtreeEndpoint: null, subtreeRuleId: null, mixedRuleId: null, subtreeMixed: false,
  };
}

function prefixConflict(left, right) {
  return new Error(`相同开头指纹存在不同结束位置：${left} / ${right}`);
}

function endpointOffset(rule) {
  return rule.durationMs - rule.anchorOffsetMs;
}

function validateTestUrls(value, ids) {
  if (value === undefined) return;
  assertRecord(value, "testUrls");
  const entries = Object.entries(value);
  if (entries.length > ids.size) throw new Error("测试链接数量超过规则数量");
  let totalBytes = 0;
  const encoder = new TextEncoder();
  for (const [id, raw] of entries) {
    if (!ids.has(id) || typeof raw !== "string" || raw.length === 0
        || raw.length > 8192 || raw !== raw.trim()) {
      throw new Error(`测试链接无效：${id}`);
    }
    totalBytes += encoder.encode(raw).byteLength;
    if (totalBytes > MAX_TEST_URL_BYTES) throw new Error("测试链接总量超过 4 MiB");
    try {
      const url = new URL(raw);
      if ((url.protocol !== "http:" && url.protocol !== "https:") || url.host === "") {
        throw new Error("protocol");
      }
    } catch {
      throw new Error(`测试链接必须是 HTTP(S) 地址：${id}`);
    }
  }
}

// 测试位置用于从广告前方开始复测，不参与规则匹配和身份判断。
function validateTestPositions(value, ids) {
  if (value === undefined) return;
  assertRecord(value, "testPositionsMs");
  const entries = Object.entries(value);
  if (entries.length > ids.size) throw new Error("测试位置数量超过规则数量");
  for (const [id, positionMs] of entries) {
    if (!ids.has(id) || !Number.isSafeInteger(positionMs) || positionMs < 0) {
      throw new Error(`测试位置无效：${id}`);
    }
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

function assertKeysWithOptional(value, required, optional, label) {
  const expected = [...required, ...optional.filter((field) =>
    Object.prototype.hasOwnProperty.call(value, field))];
  assertKeys(value, expected, label);
}

function isInteger(value) {
  return typeof value === "number" && Number.isInteger(value);
}
