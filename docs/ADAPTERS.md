# 适配器开发合同

## 职责边界

适配器 API 包含两套互相独立的 SPI：`ProbeAdapter` 负责无头音频解码，`ProbePlaybackAdapter` 负责可见点播。它们都不是广告过滤器。

音频适配器只负责：

- 打开普通 HLS/MP4 点播并选择音轨；
- 输出交错 PCM16 和解码器真实 presentation timestamp；
- 根据宿主位置维持有界前视，必要时暂停和恢复内部解码；
- 报告时间轴重置、有限时长、解码状态和结构化错误；
- 按精确 `sessionId` 停止和丢弃过期回调。

规则解析、指纹算法、冲突确认、Claim 撤销、宿主时钟二次校验和 `SkipRequest` 全部由 `probe-runtime` 独占。适配器不得自行判断广告、调用宿主播放器、缓存规则或产生跳转。

## 依赖与注入

第三方制品只需要：

```kotlin
api("io.github.0o755:ad-audio-probe-adapter-api:<version>")
```

宿主使用自定义实现时依赖 runtime，并显式注入工厂：

```java
AdAudioProbe probe = AdAudioProbe.builder(context, rulesUrl)
        .setPlaybackClock(player::getCurrentPosition)
        .setListener(request -> player.seekTo(request.getSeekTargetPositionMs()))
        .setAdapterFactory(new MyAdapterFactory())
        .build();
```

显式注入不依赖反射或服务发现。若要把适配器做成类似官方默认包的一行依赖，可以额外提供：

```text
META-INF/services/io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory
```

文件内容为工厂完整类名。未显式设置工厂时，runtime 要求 classpath 中恰好存在一个 provider；缺失或存在多个都会在初始化时明确失败，绝不按顺序随机选择。

可见播放器使用独立服务入口：

```text
META-INF/services/io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterFactory
```

用于服务发现的工厂必须提供 `public` 无参构造器，并在自己的 consumer R8 规则中精确保留工厂和服务资源。显式 `setAdapterFactory(...)` 不依赖服务发现，也不需要这条保留规则。

## 最小实现

```java
public final class MyAdapterFactory implements ProbeAdapterFactory {
    @Override public int getSpiVersion() {
        return SPI_VERSION;
    }

    @Override public String getId() {
        return "my-player-1";
    }

    @Override
    public ProbeAdapter create(Context context, Looper controlLooper,
                               ProbeAdapter.Listener listener) {
        return new MyAdapter(context, controlLooper, listener);
    }
}
```

`ProbeAdapterRequest` 提供正数 `sessionId`、不可变 `ProbeMedia`、宿主开始位置和最大前视长度。`open()` 必须先使上一媒体的网络、解码和回调失效，再启动新会话。`stop(id)` 只能停止完全相同的正数 ID；非当前 ID 和 `0` 必须无操作。`close()` 必须幂等。所有控制方法必须快速返回，网络、解码和阻塞式释放必须异步完成；实现不得退出或长期占用 runtime 提供的 `controlLooper`。

## PCM 合同

```java
listener.onPcm(sessionId, new ProbePcmFrame(
        interleavedPcm16,
        sampleRateHz,
        channelCount,
        presentationTimeUs));
```

- `presentationTimeUs` 是该数组首个采样帧在媒体时间轴上的真实 PTS，不是墙钟、AudioTrack 播放量或累计估算值。
- 数组按帧交错，长度必须能被声道数整除；采样率范围 8 kHz 到 384 kHz，声道数 1 到 16。
- 单块 PCM 最长 2 秒；实现应优先提交解码器自然产生的短帧，不能用超大数组绕过前视背压。
- 数组采用同步借用语义：runtime 只在 `onPcm` 调用内读取，不修改、不保留；回调返回后适配器可以复用。
- 同一会话必须按媒体顺序提交。PTS 跳变、flush、内部 seek 或音轨格式切换前，先调用 `onTimelineReset(sessionId, newPositionMs)`。
- 已停止/替换会话的 PCM 可以被 runtime 再次拦截，但实现仍有义务尽快停止对应生产者，避免浪费资源。

## 线程与错误

Factory 在构建 Probe 时调用，不得启动网络或解码。所有 `open/updateHostPosition/stop/close` 控制方法由 runtime 的同一私有 Looper 串行调用。PCM 可以来自专用解码线程；适配器必须保证同一 session 内 `onTimelineReset` 与 `onPcm` 的先后关系。

错误通过 `listener.onError(...)` 报告：

- `fatal=true`：当前会话不能继续，runtime 会立即使所有候选和 Claim 失效；
- `fatal=false`：当前窗口已安全丢弃，但适配器仍可继续；
- `retryable` 仅供诊断，不授权 runtime 自动循环重试。

错误回调必须携带原始 session。切源、停止或关闭后的迟到错误会被丢弃。适配器不得在错误路径暂停、静音或 seek 宿主。

## 版本策略

`ProbeAdapterFactory.SPI_VERSION` 当前为 `1`。工厂必须返回相同版本，否则 runtime 在创建任何媒体资源前拒绝装配。SPI 的破坏性改动会提升该整数和 Maven 主版本。

官方 Media3 适配器按已验证的准确版本发布，例如：

```text
io.github.0o755:ad-audio-probe-media3-1.9.2
io.github.0o755:ad-audio-probe-media3-1.10.1
io.github.0o755:ad-audio-probe-media3-1.11.0
```

不同 Media3 ABI 使用新的制品和版本化实现包；不会把多个 Media3 版本打进 fat AAR，也不会要求第三方复制 matcher。每个制品严格约束整套 Media3 组件版本，宿主只能选择一个 provider；其中 `1.10.1` 和 `1.11.0` 要求 `compileSdk >= 36`。

## 可见播放 SPI

`ProbePlaybackAdapterFactory` 与音频工厂有独立的 `SPI_VERSION`，并通过 `getPlaybackSpiVersion()` 返回播放合同版本，避免同一工厂实现两套 SPI 时版本方法冲突。实现创建 `ProbePlaybackAdapter`，支持 `open`、Surface 附加、播放/暂停、seek、状态快照、精确 session 停止和幂等关闭。

- 只借用宿主 `Surface`，不得调用 `release()`；
- 控制方法和 `getSnapshot` 必须快速、非阻塞；
- 直播或动态时间轴必须如实通过 `onTimeline` 报告，由门面统一拒绝；
- 所有回调携带原始正数 `sessionId`，过期会话必须无操作；
- 适配器只负责播放，不得解析规则、匹配广告或触发跳转。

宿主通过 `ProbePlayer.Builder.setAdapterFactory(...)` 显式注入。第三方可以只实现音频、只实现播放，或由同一个工厂类同时实现两套 SPI；两套服务发现均要求 classpath 中恰好一个 provider。
