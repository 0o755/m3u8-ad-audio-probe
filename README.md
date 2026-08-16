# M3U8 全局广告音频规则

本仓库维护与网站、频道、播放源无关的广告音频频谱规则。发布给 Probe SDK 和采集器使用的唯一数据文件是根目录的 `rules.json`。

## 规则格式

仓库从旧格式直接断代到 Probe Rules v1，不提供旧规则兼容或转换器。根节点固定包含：

- `format: "ad-audio-probe-rules"`；
- `schemaVersion: 1`；
- 从 1 开始、每次发布内容变化都递增的 `revision`；
- `algorithm: "spectral-sequence-v1"`；
- 最多 1024 条广告规则的 `rules` 数组。

每条规则记录广告时长、锚点位置以及 `0`、`64`、`128`、`192` 毫秒四个固定相位的指纹。规则只要存在于数组中就会参与匹配，没有启用、待验证或优先级字段。

规则可以内嵌可选的 `test`：

```json
{
  "test": {
    "url": "https://example.com/video/index.m3u8",
    "adStartMs": 120000
  }
}
```

`test` 只供采集器定位和复测，不参与匹配。规则文件是公开产物，测试地址不得包含账号令牌或其他秘密。

完整结构见 `rule-schema.json`。运行时约束还包括锚点范围、四相位长度公式、开头区分度、65536 帧全局上限和跨规则前缀安全；Node 校验器与 Probe SDK 解析器执行相同合同。

## 贡献流程

1. 在采集器中粘贴普通 M3U8 或 MP4 点播地址，自动扫描候选广告或手工设置广告范围。
2. 采集器生成一条或多条 Probe Rules v1 规则，可同时保存测试地址与广告开始位置。
3. 运行 `node tools/merge-rule.mjs rules.json ad-rule.json` 合并整批规则。
4. 运行 `node tools/validate-rules.mjs rules.json` 校验发布文件。
5. 通过 Pull Request 合并；CI 会再次运行全部合同测试和发布文件校验。

维护工具时运行：

```bash
node --test tools/*.test.mjs
node tools/validate-rules.mjs rules.json
```

批量合并按 ID 覆盖或新增，并且只把目标 `revision` 递增一次。同 ID 的传入规则携带 `test` 时覆盖测试元数据；不携带 `test` 时，规则内容相同则保留旧元数据，内容变化则清除旧元数据。候选校验失败不会改写目标文件，成功后通过同目录原子替换发布。

## 集成地址

稳定 `rules` 分支的真实地址：

```text
https://raw.githubusercontent.com/0o755/m3u8-ad-audio-probe/rules/rules.json
```

SDK 应使用本地缓存和条件请求；下载或新版本校验失败时继续使用上一次有效规则，不能影响宿主播放。

## 合同摘要

- 文件必须是严格 UTF-8，可带 BOM，非空且不超过 4 MiB。
- JSON 拒绝重复字段、未知字段、非普通十进制整数和尾随内容。
- 规则 ID 必须匹配 `^[a-z0-9][a-z0-9._-]{0,63}$` 且全局唯一。
- 算法固定为 16000 Hz、512 ms 窗口、256 ms hop 和 16 个频带。
- 广告时长为 1 秒至 10 分钟，锚点为 2 至 5 秒且必须完整位于广告内。
- 四相位哈希数严格使用 `floor((anchorDurationMs - phaseMs - 512) / 256) + 1`。
- 每个相位前八帧内必须有一帧与首帧的 Hamming 距离大于 5。
- 相同或包含的零相位前缀必须推导出相同的 `durationMs - anchorOffsetMs`。
- `test.url` 只接受最长 8192 字符的 HTTP(S) URL；`adStartMs + durationMs` 不得超过安全整数上限。
