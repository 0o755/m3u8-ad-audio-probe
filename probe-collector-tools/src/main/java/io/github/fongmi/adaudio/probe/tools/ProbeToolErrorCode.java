/* 定义采集与候选扫描可稳定处理的错误分类。 */
package io.github.fongmi.adaudio.probe.tools;

/** 采集器工具的稳定错误分类。 */
public enum ProbeToolErrorCode {
    /** 请求参数或媒体地址无效。 */
    INVALID_REQUEST,
    /** 媒体类型不属于当前工具支持范围。 */
    UNSUPPORTED_SOURCE,
    /** 输入被识别为直播或动态 HLS。 */
    LIVE_STREAM_NOT_SUPPORTED,
    /** 输入包含不支持的 DRM 保护。 */
    DRM_NOT_SUPPORTED,
    /** 网络或媒体读取失败。 */
    SOURCE_IO,
    /** 媒体中没有可采集的音轨。 */
    NO_AUDIO_TRACK,
    /** 音频格式或解码能力不受支持。 */
    UNSUPPORTED_AUDIO,
    /** 音频解码过程失败。 */
    DECODER_FAILED,
    /** 解码时间轴不连续或无法覆盖采集区间。 */
    TIMELINE_UNRELIABLE,
    /** 指纹或清单达到受控资源上限。 */
    RESOURCE_EXHAUSTED,
    /** 操作超过配置的最长等待时间。 */
    TIMEOUT,
    /** 未归类的内部错误。 */
    INTERNAL
}
