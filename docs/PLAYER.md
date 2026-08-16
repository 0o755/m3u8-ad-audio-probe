# 可见播放器合同

`ProbePlayer` 是普通 HLS/MP4 点播的稳定播放门面。公开 API 不包含 Media3 类型，采集器可以直接使用默认聚合包，也可以显式注入第三方 `ProbePlaybackAdapterFactory`。

## 创建与打开

```java
ProbePlayer player = ProbePlayer.builder(context)
        .setListener(listener)
        .setCallbackExecutor(mainExecutor)
        .build();

player.attachSurface(surface);
long sessionId = player.open(media, startPositionMs, true);
```

- `open(String)` 和 `open(ProbeMedia)` 从 0 开始并立即播放。
- `open(ProbeMedia, long, boolean)` 固定初始位置和播放意图。
- 每次打开都会使旧会话失效并返回新的正数 `sessionId`。
- `attachSurface` 不转移所有权；宿主负责创建和释放 Surface。
- 需要释放 Surface 时，调用 `clearSurface(surface, onCleared)`，只在 `onCleared` 到达后释放该对象。完成回调与播放器事件一样由宿主 callback Executor 串行派发。
- `clearSurface()` 和 `clearSurface(surface)` 保留为无需释放确认的 fire-and-forget 重载；它们只表示提交清除请求，不表示适配器已经同步完成。
- 同一 Surface 在清除完成前被重新附加时，旧清除请求的完成回调会被抑制，避免宿主释放仍在使用的输出。应在 `close()` 之前提交需要确认的清除请求。

## 控制与状态

`play()`、`pause()`、`seekTo(long)` 和 `stop()` 都只作用于调用时的活动会话。`getStatus()` 返回不可变快照，包含状态、会话、媒体 ID、当前位置、缓冲位置、时长、播放标志、视频尺寸和最后错误。便捷方法 `getCurrentPositionMs()`、`getBufferedPositionMs()`、`getDurationMs()` 与该快照一致。

`PREPARING` 回调中立即调用 `play`、`pause` 或 `seekTo` 是安全的：新会话的 `adapter.open` 已经作为首条会话命令进入控制队列，这些后续控制不会在媒体打开前静默丢失。播放到 `ENDED` 后再次 `seekTo` 或 `play` 会恢复去重的时间轴轮询。

`ProbePlayerListener` 的回调由宿主 Executor 串行派发：

- `onStatusChanged`：生命周期或位置快照变化；
- `onPositionDiscontinuity`：实际 seek 或内部时间轴调整；
- `onFirstFrame`：当前会话首帧已提交给 Surface；
- `onError`：结构化错误，fatal 表示当前会话不能继续。

宿主必须按 `sessionId` 忽略自己的旧 UI 任务。`close()` 幂等，会使尚未开始的排队回调失效；已经进入宿主监听器的回调允许自然返回，因此宿主不能在监听器内部等待同一个 `close()`。

## 自定义后端

不使用默认聚合包时，宿主依赖 `ad-audio-probe-player` 和自己的适配器，并显式设置：

```java
ProbePlayer player = ProbePlayer.builder(context)
        .setAdapterFactory(new MyPlaybackAdapterFactory())
        .setListener(listener)
        .build();
```

第三方实现只能依赖 `probe-adapter-api` 的 `playback` 包，不得引用 runtime 或 matcher internal。完整 SPI 约束见 [ADAPTERS.md](ADAPTERS.md)。
