# 架构与安全合同

## 模块

```text
probe-api          宿主 DTO、状态、错误、时钟和监听器（纯 Java）
probe-adapter-api  PCM 解码适配器 SPI，不包含具体播放器
probe-core         规则解析、指纹匹配、冲突协调、派发队列（纯 Java）
probe-runtime      Android 门面、规则缓存、会话与跳转安全状态机
probe-media3-1-9   官方 Media3 1.9.2 音频解码适配器
probe              默认薄聚合，只组合 runtime 与一个官方适配器
```

普通宿主只依赖 `ad-audio-probe`。自定义适配器宿主依赖 `ad-audio-probe-runtime` 并显式注入工厂，因此运行时依赖图可以完全没有 Media3。所有 Media3、PCM 和 matcher 内部类型都不会进入宿主播放 API。

## 数据流

```text
媒体 URL + headers
        |
        v
ProbeAdapter（Media3 / 第三方实现）
        |
        v
PCM16 + 真实 PTS --> ProbeSessionEngine --> AdAudioMatcher
                                             |
                                             v
                                  DetectionCoordinator
                                             |
宿主 PlaybackClock --> 会话/位置二次校验 --> SkipRequest --> 宿主 seekTo
```

探针播放器与宿主播放器是两个独立请求，因此首版只承诺内容确定的普通点播。直播滑动窗口、DRM 和服务端个性化广告无法仅凭 URL 安全证明同一时间轴，必须 fail-open。

## 时间轴

- 所有公开位置都是媒体起点后的非负毫秒。
- 解码时间来自 MediaCodec 输出 PTS，不用 AudioTrack 消费量推算。
- 宿主时间由配置的 Executor 每 100ms 读取一次，解码线程不跨线程调用宿主播放器。
- 探针只分析 `[hostPosition, hostPosition + maxLookahead]`；超出后以不消费 ByteBuffer 的方式背压并暂停。
- 明显前跳或回退会重建分析窗口，旧广告区间全部失效。

## 匹配安全

- 两帧只产生候选，至少四帧才产生 START 命中。
- 第一个 START 后继续按媒体水位等待到最多八帧，并额外覆盖一个相位 hop。
- 同一 occurrence 的所有结束位置全跨度超过 250ms 时，整组拒绝自动跳过。
- 归一化检测时刻相近或区间重叠的跨 occurrence 结果会继续做全局消歧；跨批次和链式冲突同样全部 fail-open。
- 已占用的跳转使用可撤销 Claim；宿主回调排队期间出现晚冲突会立即失效，真正调用宿主前的原子提交是最终线性化点。
- 提交后才到达的冲突证据无法撤销已经执行的宿主操作，但会保留为抑制证据，禁止同一冲突链产生第二次跳转。
- `matchSimilarity` 是确认帧的平均 32-bit Hamming 相似度，用于稳定选择证据，不表示统计概率。
- 时间轴 reset、非法 PCM、换源和关闭都切断候选。
- 匹配在 AudioSink 调用链同步完成，天然背压，不使用“队列满就丢 PCM”的策略。
- 只有宿主真实进入已确认区间后才派发，每个区间每个会话最多一次。

## 并发和生命周期

- runtime 在私有 HandlerThread 串行调用适配器控制方法；PCM 可以来自适配器解码线程，但同一会话必须保持顺序。
- 每个媒体代际独占匹配器、确认器和派发队列；旧 PCM 回调只能访问已作废的上下文。
- 规则下载使用独立单线程 Executor，重复刷新合并。
- 宿主时钟与监听器经 SerialExecutor 串行执行，默认落在主线程。
- `open`、显式 discontinuity、停用和重新启用都会改变媒体代际。
- 每个异步边界都重新检查 `closed/enabled/sessionId/ruleRevision`。
- 回调不持匹配器或状态锁，只持生命周期门闩；监听器必须快速返回，异常不会终止规则、解码或轮询线程。
- `close()` 穿过生命周期门闩后再取消网络并释放适配器，已排队任务因代际检查失效。

## 规则更新

- 规则地址必须是 HTTPS，重定向后仍必须是 HTTPS。
- 下载、缓存和解析都限制为 4 MiB，下载还有连接、读空闲和 45 秒总时限。
- JSON 使用严格流式解析，拒绝尾随内容、重复/未知字段和宽松语法。
- 新规则先落临时文件并完整解析，成功后才通过私有 AtomicFile 发布。
- 同一进程的多个 Probe 实例按规则 URL 共享缓存锁并在提交前重读 revision，避免并发写回退。
- 只接受更高 revision；同 revision 不同内容会报告发布冲突，绝不静默替换。
- Probe v1 不读取旧 SDK 规则；规则存在即启用。每条规则可携带严格校验的 `test` 工具元数据，运行时匹配器会忽略它。

首版缓存协调是进程内合同。应用应只在实际播放进程创建 Probe，不要从多个 Android 进程同时刷新同一规则 URL。

## 发布约束

- `minSdk 23`、`compileSdk 35`、Java 8 ABI、构建 JDK 17。
- Media3 1.9.2 严格约束只属于对应官方适配器；runtime 和第三方适配器不继承该约束。
- AAR 不预混淆、不打 fat AAR；宿主 Release R8 负责全局裁剪。
- consumer rules 不保留整套 Media3，避免体积失控。
- 独立 `consumer-smoke` 同时构建默认聚合消费者与零 Media3 的自定义适配器消费者，验证 Maven 传递依赖、ServiceLoader 和 R8 合同。
- `0.1.x` 作为预发布线；升为稳定版前必须完成 API 23/29/35 真机的 AAC-TS HLS、fMP4 HLS、MP4、seek/回退与 ENDED 恢复矩阵。

## 资源边界

- 独立 audio rendition 的 HLS 通常只拉音频；音视频复用的 TS/fMP4 仍需下载完整分片。
- 交错 MP4 也可能产生额外范围读取，因此探针不是零流量功能。
- 前视上限默认 15 秒并带滞回暂停，避免探针无限跑到宿主前方。
- 片头广告必须先取得并解码最小确认帧，无法在首个网络字节到达前预知。
