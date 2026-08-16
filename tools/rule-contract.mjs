/* 集中实现 Probe Rules v1 合同，供发布校验和贡献合并共同调用。 */
export const FORMAT = "ad-audio-probe-rules";
export const SCHEMA_VERSION = 1;
export const ALGORITHM = "spectral-sequence-v1";
export const MAX_REVISION = Number.MAX_SAFE_INTEGER;
export const MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;

export const MAX_RULES = 1024;
const MAX_TOTAL_HASHES = 65536;
export const MAX_HASHES_PER_PHASE = 64;
export const MAX_TEST_URL_LENGTH = 8192;
const WINDOW_MS = 512;
const HOP_MS = 256;
export const REQUIRED_PHASES = Object.freeze([0, 64, 128, 192]);
const RULE_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,63}$/;
const HASH_PATTERN = /^[0-9a-f]{8}$/;
const ILLEGAL_URI_CHARACTER = /[\u0000-\u0020\u007f-\u009f\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000<>"{}|\\^`]/u;
const MALFORMED_PERCENT_ESCAPE = /%(?![0-9a-fA-F]{2})/u;

/** 从严格 UTF-8 字节解析规则，并在构建对象时拒绝重复字段和非整数数字。 */
export function parseDocumentBytes(source) {
  if (!(source instanceof Uint8Array)) throw new Error("规则输入必须是 UTF-8 字节");
  if (source.byteLength === 0) throw new Error("规则内容不能为空");
  if (source.byteLength > MAX_DOCUMENT_BYTES) throw new Error("规则超过 4 MiB");
  let text;
  try {
    text = new TextDecoder("utf-8", { fatal: true }).decode(source);
  } catch {
    throw new Error("规则文件不是严格 UTF-8");
  }
  return parseDocumentText(text, { checkBytes: false });
}

/** 解析规则文本；用于测试和没有文件字节可用的调用方。 */
export function parseDocumentText(source, options = {}) {
  if (typeof source !== "string") throw new Error("规则输入必须是字符串");
  assertValidUnicode(source);
  if (options.checkBytes !== false) {
    const byteLength = new TextEncoder().encode(source).byteLength;
    if (byteLength === 0) throw new Error("规则内容不能为空");
    if (byteLength > MAX_DOCUMENT_BYTES) throw new Error("规则超过 4 MiB");
  }
  const text = source.charCodeAt(0) === 0xfeff ? source.slice(1) : source;
  if (text.length === 0) throw new Error("规则内容不能为空");
  return validateDocument(new StrictJsonParser(text).parse());
}

export function validateDocument(document) {
  assertRecord(document, "规则根节点");
  assertKeys(document, ["format", "schemaVersion", "revision", "algorithm", "rules"],
    "规则根节点");
  if (document.format !== FORMAT) throw new Error(`format 必须为 ${FORMAT}`);
  if (document.schemaVersion !== SCHEMA_VERSION) {
    throw new Error(`schemaVersion 必须为 ${SCHEMA_VERSION}`);
  }
  if (!Number.isSafeInteger(document.revision) || document.revision < 1) {
    throw new Error("revision 必须是 1 到 JavaScript 安全整数上限内的整数");
  }
  if (document.algorithm !== ALGORITHM) throw new Error(`algorithm 必须为 ${ALGORITHM}`);
  if (!Array.isArray(document.rules) || document.rules.length > MAX_RULES) {
    throw new Error("rules 不是数组或数量超过 1024 条");
  }

  const ids = new Set();
  let totalHashes = 0;
  for (const rule of document.rules) {
    totalHashes += validateRule(rule);
    if (ids.has(rule.id)) throw new Error(`广告规则 ID 重复：${rule.id}`);
    ids.add(rule.id);
    if (totalHashes > MAX_TOTAL_HASHES) throw new Error("规则指纹总量超过 65536 帧");
  }
  validateAmbiguousPrefixes(document.rules);
  return document;
}

/** 序列化已验证规则，并按最终 UTF-8 字节数执行 4 MiB 发布上限。 */
export function serializeDocument(document, pretty = false) {
  validateDocument(document);
  const text = `${JSON.stringify(document, null, pretty ? 2 : undefined)}${pretty ? "\n" : ""}`;
  if (new TextEncoder().encode(text).byteLength > MAX_DOCUMENT_BYTES) {
    throw new Error("规则超过 4 MiB");
  }
  return text;
}

function validateRule(rule) {
  assertRecord(rule, "广告规则");
  assertKeysWithOptional(rule,
    ["id", "durationMs", "anchorOffsetMs", "anchorDurationMs", "fingerprints"],
    ["test"], "广告规则");
  if (typeof rule.id !== "string" || !RULE_ID_PATTERN.test(rule.id)) {
    throw new Error("广告规则 ID 无效");
  }
  if (!Number.isInteger(rule.durationMs)
      || rule.durationMs < 1000 || rule.durationMs > 600000) {
    throw new Error(`广告时长无效：${rule.id}`);
  }
  if (!Number.isInteger(rule.anchorOffsetMs) || rule.anchorOffsetMs < 0
      || !Number.isInteger(rule.anchorDurationMs) || rule.anchorDurationMs < 2000
      || rule.anchorDurationMs > 5000
      || rule.anchorOffsetMs > rule.durationMs - rule.anchorDurationMs) {
    throw new Error(`广告锚点范围无效：${rule.id}`);
  }
  if (!Array.isArray(rule.fingerprints) || rule.fingerprints.length !== REQUIRED_PHASES.length) {
    throw new Error(`规则必须包含四个固定相位：${rule.id}`);
  }

  const phases = new Set();
  let hashCount = 0;
  for (const fingerprint of rule.fingerprints) {
    hashCount += validateFingerprint(rule, fingerprint, phases);
    phases.add(fingerprint.phaseMs);
  }
  for (const phase of REQUIRED_PHASES) {
    if (!phases.has(phase)) throw new Error(`规则缺少固定相位 ${phase}：${rule.id}`);
  }
  if (Object.prototype.hasOwnProperty.call(rule, "test")) validateTest(rule);
  return hashCount;
}

function validateFingerprint(rule, fingerprint, phases) {
  assertRecord(fingerprint, `指纹相位：${rule.id}`);
  assertKeys(fingerprint, ["phaseMs", "hashes"], `指纹相位：${rule.id}`);
  if (!REQUIRED_PHASES.includes(fingerprint.phaseMs) || phases.has(fingerprint.phaseMs)) {
    throw new Error(`指纹相位无效或重复：${rule.id}`);
  }
  if (!Array.isArray(fingerprint.hashes)
      || fingerprint.hashes.length < 4
      || fingerprint.hashes.length > MAX_HASHES_PER_PHASE
      || fingerprint.hashes.some((hash) => typeof hash !== "string"
        || !HASH_PATTERN.test(hash))) {
    throw new Error(`频谱哈希必须是 8 位小写十六进制：${rule.id}`);
  }
  if (fingerprint.hashes.length
      !== expectedFrames(rule.anchorDurationMs, fingerprint.phaseMs)) {
    throw new Error(`指纹长度与锚点时长不一致：${rule.id}`);
  }
  if (requiredConfirmationFrames(fingerprint.hashes) < 0) {
    throw new Error(`指纹开头区分度不足：${rule.id}`);
  }
  return fingerprint.hashes.length;
}

function validateTest(rule) {
  const metadata = rule.test;
  assertRecord(metadata, `test：${rule.id}`);
  assertKeys(metadata, ["url", "adStartMs"], `test：${rule.id}`);
  if (typeof metadata.url !== "string" || metadata.url.length === 0
      || metadata.url.length > MAX_TEST_URL_LENGTH
      || metadata.url !== metadata.url.trim() || hasUnpairedSurrogate(metadata.url)) {
    throw new Error(`test.url 无效：${rule.id}`);
  }
  try {
    const url = new URL(metadata.url);
    if ((url.protocol !== "http:" && url.protocol !== "https:") || url.hostname === ""
        || ILLEGAL_URI_CHARACTER.test(metadata.url)
        || MALFORMED_PERCENT_ESCAPE.test(metadata.url)
        || hasBareSquareBracket(metadata.url)
        || !hasRealHost(metadata.url, url)) {
      throw new Error("protocol");
    }
  } catch {
    throw new Error(`test.url 必须是有效的 HTTP(S) 地址：${rule.id}`);
  }
  if (!Number.isSafeInteger(metadata.adStartMs) || metadata.adStartMs < 0
      || metadata.adStartMs > MAX_REVISION - rule.durationMs) {
    throw new Error(`test.adStartMs 无效或测试终点超出安全整数范围：${rule.id}`);
  }
}

/** 方括号只允许包围 authority 中由 URL 解析器验证过的 IPv6 主机。 */
function hasBareSquareBracket(value) {
  const separator = value.indexOf("://");
  if (separator < 0) return true;
  const authorityStart = separator + 3;
  let authorityEnd = value.length;
  for (const delimiter of ["/", "?", "#"]) {
    const index = value.indexOf(delimiter, authorityStart);
    if (index >= 0 && index < authorityEnd) authorityEnd = index;
  }
  if (/[[\]]/u.test(value.slice(authorityEnd))) return true;

  const authority = value.slice(authorityStart, authorityEnd);
  const userInfoEnd = authority.lastIndexOf("@");
  if (userInfoEnd >= 0 && /[[\]]/u.test(authority.slice(0, userInfoEnd))) return true;
  const hostPort = authority.slice(userInfoEnd + 1);
  const open = hostPort.indexOf("[");
  const close = hostPort.indexOf("]");
  if (open < 0 && close < 0) return false;
  if (open !== 0 || close <= 1 || hostPort.indexOf("[", 1) >= 0
      || hostPort.indexOf("]", close + 1) >= 0) {
    return true;
  }
  const suffix = hostPort.slice(close + 1);
  return suffix !== "" && !suffix.startsWith(":");
}

/** 使用原始 authority 校验 ASCII 主机，避免 URL 自动修复出 Java URI 不接受的地址。 */
function hasRealHost(value, parsed) {
  const authorityStart = value.indexOf("://") + 3;
  let authorityEnd = value.length;
  for (const delimiter of ["/", "?", "#"]) {
    const index = value.indexOf(delimiter, authorityStart);
    if (index >= 0 && index < authorityEnd) authorityEnd = index;
  }
  const authority = value.slice(authorityStart, authorityEnd);
  const hostPort = authority.slice(authority.lastIndexOf("@") + 1);
  let rawHost;
  if (hostPort.startsWith("[")) {
    rawHost = hostPort.slice(0, hostPort.indexOf("]") + 1);
  } else {
    const colon = hostPort.lastIndexOf(":");
    rawHost = colon < 0 ? hostPort : hostPort.slice(0, colon);
  }
  if (rawHost === "") return false;
  if (rawHost.startsWith("[")) return parsed.hostname.startsWith("[");
  if (rawHost.toLowerCase() !== parsed.hostname.toLowerCase()) return false;

  const labels = rawHost.endsWith(".")
    ? rawHost.slice(0, -1).split(".") : rawHost.split(".");
  return labels.length > 0 && labels.every((label) => label.length <= 63
    && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/iu.test(label));
}

function validateAmbiguousPrefixes(rules) {
  const root = createPrefixNode();
  for (const rule of rules) {
    const primary = rule.fingerprints.find((item) => item.phaseMs === 0);
    const hashes = primary.hashes.slice(0, 8);
    const endpoint = rule.durationMs - rule.anchorOffsetMs;
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
    for (const item of path) recordEndpoint(item, endpoint, rule.id);
  }
}

function createPrefixNode() {
  return {
    children: new Map(), terminal: null,
    subtreeEndpoint: null, subtreeRuleId: null, mixedRuleId: null, subtreeMixed: false,
  };
}

function recordEndpoint(node, endpoint, ruleId) {
  if (node.subtreeEndpoint === null) {
    node.subtreeEndpoint = endpoint;
    node.subtreeRuleId = ruleId;
  } else if (node.subtreeEndpoint !== endpoint) {
    node.subtreeMixed = true;
    if (node.mixedRuleId === null) node.mixedRuleId = ruleId;
  }
}

function prefixConflict(left, right) {
  return new Error(`相同开头指纹存在不同结束位置：${left} / ${right}`);
}

function requiredConfirmationFrames(hashes) {
  const first = Number.parseInt(hashes[0], 16) | 0;
  for (let index = 1; index < Math.min(8, hashes.length); index += 1) {
    const current = Number.parseInt(hashes[index], 16) | 0;
    if (bitCount(first ^ current) > 5) return Math.max(4, index + 1);
  }
  return -1;
}

function expectedFrames(anchorDurationMs, phaseMs) {
  return Math.floor((anchorDurationMs - phaseMs - WINDOW_MS) / HOP_MS) + 1;
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
    throw new Error(`${label}字段不符合 Probe Rules v1`);
  }
}

function assertKeysWithOptional(value, required, optional, label) {
  const expected = [...required, ...optional.filter((field) =>
    Object.prototype.hasOwnProperty.call(value, field))];
  assertKeys(value, expected, label);
}

function assertValidUnicode(value) {
  if (hasUnpairedSurrogate(value)) throw new Error("规则文本包含无效 Unicode 字符");
}

function hasUnpairedSurrogate(value) {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(index + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true;
      index += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      return true;
    }
  }
  return false;
}

/** 极小严格 JSON 解析器：合同内所有数字都必须使用普通十进制整数。 */
class StrictJsonParser {
  constructor(source) {
    this.source = source;
    this.index = 0;
  }

  parse() {
    const value = this.parseValue();
    this.skipWhitespace();
    if (this.index !== this.source.length) throw this.syntax("规则 JSON 含有尾随内容");
    return value;
  }

  parseValue() {
    this.skipWhitespace();
    const token = this.source[this.index];
    if (token === "{") return this.parseObject();
    if (token === "[") return this.parseArray();
    if (token === "\"") return this.parseString();
    if (token === "-" || (token >= "0" && token <= "9")) return this.parseInteger();
    if (this.source.startsWith("true", this.index)) return this.consumeLiteral("true", true);
    if (this.source.startsWith("false", this.index)) return this.consumeLiteral("false", false);
    if (this.source.startsWith("null", this.index)) return this.consumeLiteral("null", null);
    throw this.syntax("规则 JSON 语法无效");
  }

  parseObject() {
    this.index += 1;
    const result = {};
    const keys = new Set();
    this.skipWhitespace();
    if (this.consumeIf("}")) return result;
    while (true) {
      this.skipWhitespace();
      if (this.source[this.index] !== "\"") throw this.syntax("对象字段名必须是字符串");
      const key = this.parseString();
      if (keys.has(key)) throw this.syntax(`JSON 对象含重复字段：${key}`);
      keys.add(key);
      this.skipWhitespace();
      if (!this.consumeIf(":")) throw this.syntax("对象字段缺少冒号");
      const value = this.parseValue();
      Object.defineProperty(result, key, {
        value, enumerable: true, configurable: true, writable: true,
      });
      this.skipWhitespace();
      if (this.consumeIf("}")) return result;
      if (!this.consumeIf(",")) throw this.syntax("对象字段之间缺少逗号");
    }
  }

  parseArray() {
    this.index += 1;
    const result = [];
    this.skipWhitespace();
    if (this.consumeIf("]")) return result;
    while (true) {
      result.push(this.parseValue());
      this.skipWhitespace();
      if (this.consumeIf("]")) return result;
      if (!this.consumeIf(",")) throw this.syntax("数组元素之间缺少逗号");
    }
  }

  parseString() {
    const start = this.index;
    this.index += 1;
    let escaped = false;
    while (this.index < this.source.length) {
      const character = this.source[this.index];
      this.index += 1;
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === "\"") {
        const token = this.source.slice(start, this.index);
        try {
          return JSON.parse(token);
        } catch {
          throw this.syntax("JSON 字符串无效");
        }
      } else if (character.charCodeAt(0) <= 0x1f) {
        throw this.syntax("JSON 字符串含未转义控制字符");
      }
    }
    throw this.syntax("JSON 字符串未结束");
  }

  parseInteger() {
    const remaining = this.source.slice(this.index);
    const match = /^-?(?:0|[1-9][0-9]*)/.exec(remaining);
    if (match === null) throw this.syntax("数字必须是普通十进制整数");
    const end = this.index + match[0].length;
    const following = this.source[end];
    if (following === "." || following === "e" || following === "E"
        || (following !== undefined && !/[\s,}\]]/u.test(following))) {
      throw this.syntax("数字必须是普通十进制整数");
    }
    this.index = end;
    return Number(match[0]);
  }

  consumeLiteral(token, value) {
    this.index += token.length;
    return value;
  }

  consumeIf(token) {
    if (this.source[this.index] !== token) return false;
    this.index += 1;
    return true;
  }

  skipWhitespace() {
    while (/[\u0009\u000a\u000d\u0020]/u.test(this.source[this.index] ?? "")) {
      this.index += 1;
    }
  }

  syntax(message) {
    return new Error(`${message}（位置 ${this.index}）`);
  }
}
