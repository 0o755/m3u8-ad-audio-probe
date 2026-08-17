# M3U8 Ad Audio Probe

面向普通 HLS/M3U8 与 MP4 点播的 Android 无头广告音频探针。SDK 内部只解码音频并匹配 `rules.json`，宿主不再采集、转换或传递 PCM。

## 能力边界

- 支持：有限时长的 HLS VOD、普通 MP4、安全白名单 HTTP(S) 请求头、宿主主动拖动/换源。
- 不支持：直播、DRM、DASH、RTSP、服务端动态插入且两次请求内容不一致的流。
- 探针 fail-open：规则或媒体分析失败不会暂停、静音或操作宿主播放器。
- SDK 不直接 seek。只有宿主进入已确认广告区间时，才回调一次 `SkipRequest`。

## 依赖

发布到 Maven Central 后，宿主只需一行依赖：

```kotlin
implementation("io.github.0o755:ad-audio-probe:0.1.0")
```

当前预发布包先进入 GitHub Packages。GitHub Packages 仍要求宿主配置仓库地址和读取凭据，因此不把它宣传成“一行接入”；真正的一行依赖以 Maven Central 正式版本为准。源码调试可直接 `includeBuild` 或依赖 `project(":probe")`。

默认聚合包使用官方 Media3 `1.9.2` 适配器，并只在该适配器内严格约束 Media3；公共 runtime 和 adapter SPI 完全不依赖 Media3。宿主已经使用其他 Media3 版本时，应选择对应版本的官方适配器，或实现自己的适配器，而不是让 Gradle 静默混用不稳定接口。当前首个官方实现坐标为 `ad-audio-probe-media3-1.9.2`。

默认坐标同时提供检测、可见播放和采集工具；它们也可以按需独立依赖：

| 制品 | 用途 | 是否依赖 Media3 |
| --- | --- | --- |
| `ad-audio-probe` | 默认一行聚合包 | 运行时带入官方 1.9.2 适配器 |
| `ad-audio-probe-runtime` | 广告检测、规则缓存与跳转安全状态机 | 否 |
| `ad-audio-probe-player` | 可见 HLS/MP4 播放门面 | 否 |
| `ad-audio-probe-collector-tools` | 指纹采集与 HLS 候选扫描 | 否 |
| `ad-audio-probe-adapter-api` | 音频探针和可见播放的第三方 SPI | 否 |
| `ad-audio-probe-media3-1.9.2` | 两套 SPI 的官方 Media3 实现 | 是，严格 1.9.2 |

表中的“否”表示制品本身不绑定 Media3，不表示它能脱离媒体后端独立解码。`player` 和指纹采集在默认聚合包中会自动发现官方适配器；单独依赖这些制品时，还需要同时依赖一个官方适配器，或通过 Builder 显式注入自己的工厂。HLS 清单候选扫描不解码媒体，因此不需要播放器适配器。

## 规则格式

本项目使用独立定义的 Probe Rules v1，根节点固定为：

```json
{
  "format": "ad-audio-probe-rules",
  "schemaVersion": 1,
  "revision": 1,
  "algorithm": "spectral-sequence-v1",
  "rules": []
}
```

规则存在于 JSON 中就表示启用，不再维护“待验证/已启用”状态。每条规则可以携带新的 `test` 元数据，供采集器保存测试链接和广告起点；Probe 会严格校验但不参与匹配。权威语义约束见 [规则合同](docs/RULES.md)，[JSON Schema](docs/rules-v1.schema.json) 负责通用结构校验，另有一份 [示例文件](docs/rules-v1.example.json)。文件最大 4 MiB、最多 1024 条规则；同一 URL 的 `revision` 必须单调递增。

规则生产端直接生成 Probe Rules v1；格式中不定义启用状态、待验证状态或隐式转换层。

### 本地规则与单规则复测

采集器或离线宿主可以完全不配置远程规则 URL：

```java
probe = AdAudioProbe.builder(context)
        .setInitialRulesJson(localRulesJson)
        .setPlaybackClock(player::getCurrentPosition)
        .setListener(listener)
        .build();

probe.open(playUrl);
```

运行中可调用 `replaceRules(byte[])` / `replaceRulesJson(String)` 原子替换规则，两者返回正数 `requestId`。解析在后台执行；应等待 `ProbeListener.onRulesReplaced` 收到同一 ID 的 `APPLIED`，再调用 `useRuleForTesting(ruleId)`。这在替换前后文件 revision 相同时仍能精确判断提交完成；`REJECTED` 保留当前规则，`SUPERSEDED` 表示尚未解析就被更新请求覆盖。单规则测试只改变当前匹配视图并返回重建后的媒体 `sessionId`，不会修改 JSON、URL 或宿主播放器；`useAllRules()` 恢复全量规则。

## 最小接入

```java
probe = AdAudioProbe.create(
        context,
        "https://example.com/rules.json",
        player::getCurrentPosition,
        request -> player.seekTo(request.getSeekTargetPositionMs()));

// 每次宿主开始播放新链接时调用。
probe.open(playUrl);
```

这就是完整的基础接入。SDK 在主线程读取 `player::getCurrentPosition`，内部建立独立的 audio-only 探针，提前分析最多 15 秒；宿主进入广告区间后收到跳转请求。

Probe 应只在实际播放进程初始化。首版会协调同一进程中的多个实例，但不支持多个 Android 进程同时刷新同一个规则 URL。

宿主销毁时释放：

```java
probe.close();
```

宿主发生手动 seek、切集或播放器时间轴重建时，建议立即通知：

```java
probe.notifyHostDiscontinuity(player.getCurrentPosition());
```

不调用也会由位置轮询发现明显跳变，但显式通知能更快取消过期会话结果。

## 可见播放器

采集器无需直接依赖 Media3，也不需要自己维护播放器代际：

```java
ProbePlayer player = ProbePlayer.create(context, new ProbePlayerListener() {
    @Override
    public void onStatusChanged(ProbePlayerStatus status) {
        // 更新进度、时长和播放状态。
    }
});

player.attachSurface(surface);
long sessionId = player.open(media, startPositionMs, true);
```

`ProbePlayer` 提供 `play()`、`pause()`、`seekTo(long)`、`stop()`、`getStatus()` 和幂等 `close()`。每次 `open` 返回新的 `sessionId`，所有回调都携带或包含该代际；前一媒体的迟到回调不会污染当前媒体。`Surface` 仍归宿主所有，SDK 不会释放它。完整合同见 [可见播放器](docs/PLAYER.md)。

## 指纹采集与候选扫描

从已知广告区间生成一条经过 rules-v1 约束校验的规则草稿：

```java
AudioFingerprintCollector collector =
        new AudioFingerprintCollector.Builder(context).build();
FingerprintCaptureRequest request = FingerprintCaptureRequest
        .builder(media, ruleId, adStartMs, adEndMs)
        .build();
ProbeToolSession session = collector.capture(request, captureListener);
```

采集器固定提取广告内连续 5000ms 媒体音频；无头解码可以快于现实时间，但不会缩短媒体覆盖范围。`FingerprintRuleDraft.toRuleJson()` 只输出一条可合并的规则对象；它不会直接覆盖规则文件。候选扫描只读取 HLS VOD 清单，不下载媒体分片：

```java
HlsCandidateScanner scanner = new HlsCandidateScanner.Builder().build();
ProbeToolSession scan = scanner.scan(media, scanListener);
```

扫描结果是结构候选而不是已验证广告，仍需宿主选择区间并交给指纹采集。采集和扫描均为单活动会话、可取消、结构化终态；详细 DTO、限制和线程合同见 [采集与扫描工具](docs/COLLECTOR_TOOLS.md)。

## 带请求头的媒体

```java
probe.open(ProbeMedia.builder(playUrl)
        .setId(episodeId)
        .setType(ProbeMedia.Type.HLS)
        .setHeader("User-Agent", userAgent)
        .setHeader("Accept", "application/vnd.apple.mpegurl, video/mp4")
        .build());
```

`ProbeMedia.Type.AUTO` 不再把后缀当结论：`.m3u8`、`.mp4/.m4a/.m4v` 只决定首次尝试，无扩展名默认先按 MP4/Progressive 读取。适配器会旁路观察这次真实请求的 `Content-Type` 与最多 4096 字节响应前缀，并按合法顶层 box 扫描 MP4 `ftyp`；只有容器解析失败且证据明确为 `#EXTM3U`/HLS MIME 或 `ftyp`/MP4 MIME 时，才在同一会话内反向回退一次。它不会额外发送 HEAD/Range 预检，也不会因 401/403、HTML 或普通解码失败盲目重试。显式 `Type.HLS/MP4` 始终不回退。

官方 Media3 适配器只接受 `User-Agent`、`Accept`、`Accept-Language`、`Cache-Control` 和 `Pragma`；其他头会在发起网络请求前以 fatal `UNSUPPORTED_SOURCE` 拒绝。这个限制确保底层同协议跨主机 30x 即使继续携带请求头，也不会携带 Cookie、Authorization、Referer 或自定义令牌。确实需要鉴权头的宿主应使用能逐跳控制重定向的自定义适配器。SDK 默认禁止全部跨协议重定向；HTTP 明文媒体是否可访问仍由宿主应用的 Network Security Config 决定，SDK 不放宽全局安全策略。

## 跳转数据

`SkipRequest` 是在当前连续分析水位内完成冲突确认后提交的不可变决定，包含：

| 字段 | 含义 |
| --- | --- |
| `requestId` | 全局递增请求 ID |
| `sessionId` | 当前媒体代际，换源或时间轴跳变后改变 |
| `mediaId` | 宿主提供的媒体 ID |
| `ruleId` / `ruleRevision` | 命中的规则与规则版本 |
| `adStartPositionMs` / `adEndPositionMs` | 广告在媒体时间轴上的完整区间 |
| `seekTargetPositionMs` | 宿主应跳转到的位置，首版等于广告结束位置 |
| `hostPositionMsAtDispatch` | 回调前二次读取的宿主位置 |
| `analyzedThroughPositionMs` | 探针已经连续分析到的位置 |
| `matchSimilarity` | 0 到 1 的确认帧平均 Hamming 相似度，不是概率 |

SDK 不会提前派发未来跳转。回调真正执行前会再次检查会话、规则版本、启用状态和宿主位置；已替换媒体排队中的请求会自动失效。

跳转请求采用一次性 fail-open：若宿主回调抛出异常，SDK 会报告结构化错误但不会循环重试和反复操作播放器。异步 seek 最终是否成功仍由宿主播放器负责。

## 高级配置

```java
probe = AdAudioProbe.builder(context, rulesUrl)
        .setPlaybackClock(player::getCurrentPosition)
        .setListener(new ProbeListener() {
            @Override
            public void onSkipRequested(SkipRequest request) {
                player.seekTo(request.getSeekTargetPositionMs());
            }

            @Override
            public void onStatusChanged(ProbeStatus status) {
                // 可选：观察准备、分析、前视就绪、结束等状态。
            }

            @Override
            public void onError(ProbeError error) {
                // 可选：记录结构化错误；宿主播放不受影响。
            }
        })
        .setMaxLookaheadMs(15_000L)
        .build();
```

公开控制方法：

- `open(String)` / `open(ProbeMedia)`：原子替换当前媒体，返回新 `sessionId`。
- `notifyHostDiscontinuity(long)`：宿主时间轴跳变并废弃过期结果。
- `setEnabled(boolean)`：关闭时释放当前分析，重新启用后按当前媒体新建会话。
- `refreshRules()`：后台刷新规则；永不降级到更低 revision。
- `stop()`：停止当前媒体，保留规则缓存和 SDK 实例。
- `getStatus()`：随时读取不可变状态快照。
- `close()`：永久释放，幂等；返回后不会再调用宿主监听器。

## 可替换适配器

默认依赖会自动发现官方 Media3 1.9.2 实现。底层已经拆成独立的音频探针 SPI 与可见播放 SPI，第三方可以实现 VLC、FFmpeg、系统 MediaCodec 或其他播放器后端；音频适配器只提交 PCM16、真实 PTS、时间轴和结构化错误，不能接触规则、匹配结果或宿主 seek。

自定义实现只依赖 runtime，不会携带 Media3：

```kotlin
implementation("io.github.0o755:ad-audio-probe-runtime:0.1.0")
```

```java
probe = AdAudioProbe.builder(context, rulesUrl)
        .setPlaybackClock(player::getCurrentPosition)
        .setListener(request -> player.seekTo(request.getSeekTargetPositionMs()))
        .setAdapterFactory(new MyProbeAdapterFactory())
        .build();
```

检测侧通过 `ProbeAdapterFactory` 注入，可见播放侧通过 `ProbePlaybackAdapterFactory` 注入。SPI 版本、PCM 借用语义、Surface 所有权、线程和会话约束见 [适配器开发合同](docs/ADAPTERS.md)。未来的官方 Media3 版本适配器使用独立制品和独立包名发布，不会复制 runtime，也不会在一个 APK 中保留多套匹配状态机。

所有公开方法线程安全，且不会在调用线程执行网络或解码 I/O。为保证过期媒体回调绝不跨代执行，`open`、`stop`、`setEnabled` 和 `close` 会与正在执行的宿主回调串行；宿主回调必须快速返回。默认在 Android 主线程串行读取 `PlaybackClock` 并调用监听器；宿主播放器使用专用线程时，通过 `setHostExecutor()` 指定一个真正异步、可持续提交任务的 Executor。

## 实现与体积

官方 Media3 1.9.2 适配器使用一个 `MediaCodecAudioRenderer` 和自定义无声 `AudioSink`：

- 不创建 Surface、视频 Renderer、AudioTrack 或音频焦点。
- 解码 PCM 带真实 presentation timestamp，直接在 SDK 内同步匹配。
- 达到前视上限后暂停内部播放器，宿主追上后再恢复。
- MP4 只注册标准 MP4/fMP4 extractor；HLS 保留 TS、fMP4 和常见 AAC 兼容性。

独立音频 rendition 的 HLS 通常只下载音频；如果 HLS 分片本身复用音视频，探针仍需读取完整分片，可能与宿主产生接近一次额外流量。片头广告也必须先完成最小帧数的网络获取和解码，不能在首个字节到达前凭空预知。

宿主与探针必须使用能稳定返回同一内容、同一媒体时间轴和同一目标音轨的 URL 与请求头。每次请求动态个性化内容、带 `ENDLIST` 的 SSAI 伪点播或宿主主动切换到不同语言音轨，都超出首版自动跳转保证；这类来源应显式停用 Probe 或提供能复现宿主音轨的自定义适配器。

Media3 `1.9.2` 相关原始 AAR 合计约 3.36 MiB，但不会原样打进宿主。当前 Release AAR 大小为：薄聚合 1.1 KiB、runtime 56.5 KiB、适配器 API 8.9 KiB、player 30.9 KiB、collector-tools 76.1 KiB、官方 Media3 适配器 45.4 KiB。Release R8 烟测同时引用检测、可见播放、采集和扫描 API：默认一行依赖 APK 为 128.5 KiB，只接 runtime/player/tools 与自定义空适配器的无 Media3 APK 为 25.9 KiB。这些是最小测试宿主的结果，真实增量仍应以应用自己的 Release APK 差值为准。

当前 `0.1.x` 是预发布线。JVM 状态机、lint、AAR、Maven 传递依赖和 Release R8 已纳入 CI；正式宣称稳定前仍需完成 API 23/29/35 真机的 AAC-TS HLS、fMP4 HLS、普通 MP4、seek/回退与 ENDED 恢复测试。

## 构建

要求 JDK 17、Android SDK 35：

```bash
./gradlew test lintRelease assembleRelease publishToMavenLocal
./gradlew -p consumer-smoke verifyPublishedModuleGraph verifyMinifiedServiceProviders :custom-consumer:assembleRelease
```

## 发布

`vX.Y.Z` 标签会先执行测试、lint、本地 Maven 发布和独立 Release/R8 消费验证，然后由两个独立 job 分别发布 GitHub Packages 与 Maven Central，单个发布端失败时可以单独重跑。Central Portal 需要在仓库中配置 `MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`、`SIGNING_KEY` 和 `SIGNING_PASSWORD` 四个 Secrets，并预先验证 `io.github.0o755` namespace。签名只在正式发布任务启用，不影响本地构建和 PR。

核心设计和安全状态机见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## License

MIT
