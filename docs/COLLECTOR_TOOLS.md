# 采集与扫描工具

`ad-audio-probe-collector-tools` 提供公开的指纹采集与 HLS 候选扫描。两者都只返回稳定 DTO，不暴露 PCM、matcher 或 Media3。

下面的指纹采集示例假定宿主使用默认 `ad-audio-probe` 聚合包。若只依赖 `ad-audio-probe-collector-tools`，还必须同时带入一个音频适配器制品，或调用 `AudioFingerprintCollector.Builder.setAdapterFactory(...)` 显式注入第三方 `ProbeAdapterFactory`。只有清单扫描可以在没有解码适配器时独立使用。

## 指纹采集

```java
AudioFingerprintCollector collector =
        new AudioFingerprintCollector.Builder(context)
                .setCallbackExecutor(mainExecutor)
                .setTimeoutMs(60_000L)
                .build();

FingerprintCaptureRequest request = FingerprintCaptureRequest
        .builder(media, ruleId, adStartMs, adEndMs)
        .build();

ProbeToolSession session = collector.capture(request,
        new FingerprintCaptureListener() {
            @Override public void onProgress(FingerprintCaptureProgress progress) {}
            @Override public void onCompleted(long id, FingerprintRuleDraft draft) {}
            @Override public void onCancelled(long id) {}
            @Override public void onError(ProbeToolError error) {}
        });
```

采集范围必须是 5 秒到 10 分钟的已知广告区间；锚点固定覆盖广告内连续 5000ms。无头播放器可以在不到 5 秒的现实时间内完成高速解码，但草稿必须包含完整 5 秒媒体 PCM。输出固定包含 `0/64/128/192ms` 四相位指纹、测试 URL 和广告起点，并在返回前执行 rules-v1 长度、相位和区分度校验。调用 `session.cancel()` 只取消对应代际；同一 collector 开始新任务会取消旧任务。

## HLS 候选扫描

```java
HlsCandidateScanner scanner = new HlsCandidateScanner.Builder()
        .setCallbackExecutor(mainExecutor)
        .setTimeoutMs(60_000L)
        .build();

ProbeToolSession session = scanner.scan(media, new HlsScanListener() {
    @Override public void onCompleted(HlsScanResult result) {}
    @Override public void onCancelled(long id) {}
    @Override public void onError(ProbeToolError error) {}
});
```

扫描器只分析有限 HLS VOD 的 master/media playlist，不下载分片。它返回候选区间、重复 occurrence、置信度和结构信号；这些结果用于缩小人工确认范围，不能直接当作广告规则或自动跳转依据。

扫描有累计 4 MiB 清单、重定向、嵌套、variant、segment 和 URL 展开上限；拒绝直播、动态清单、DRM、MP4 与 HTTPS 降级。完整请求头只发给同源地址，跨源子清单仅保留安全白名单。

## 生命周期

两个门面都保证每个会话只有一个完成、取消或错误终态。监听器按配置 Executor 串行调用，必须快速返回。`close()` 会取消活动会话并释放内部资源；宿主提供的 Executor 仍由宿主管理。
