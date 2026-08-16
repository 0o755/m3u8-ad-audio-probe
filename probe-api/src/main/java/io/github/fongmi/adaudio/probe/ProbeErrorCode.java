/* 结构化错误码让宿主可以稳定区分不支持、网络失败和内部故障。 */
package io.github.fongmi.adaudio.probe;

/** 宿主可稳定处理的探针错误分类。 */
public enum ProbeErrorCode {
    /** 媒体地址或请求参数无效。 */
    INVALID_SOURCE,
    /** 媒体格式超出当前支持范围。 */
    UNSUPPORTED_SOURCE,
    /** 输入被识别为不支持的直播流。 */
    LIVE_STREAM_NOT_SUPPORTED,
    /** 输入包含不支持的 DRM 保护。 */
    DRM_NOT_SUPPORTED,
    /** 读取媒体源失败。 */
    SOURCE_IO,
    /** 媒体中没有可分析的音轨。 */
    NO_AUDIO_TRACK,
    /** 音轨格式或解码能力不受支持。 */
    UNSUPPORTED_AUDIO,
    /** 音频解码器失败。 */
    DECODER_FAILED,
    /** 下载规则文件失败。 */
    RULE_FETCH_FAILED,
    /** 规则文件未通过格式或合同校验。 */
    RULE_PARSE_FAILED,
    /** 规则 revision 降级或同版本内容冲突。 */
    RULE_REVISION_CONFLICT,
    /** 网络和本地缓存均没有可用规则。 */
    RULES_UNAVAILABLE,
    /** 宿主时间轴无法可靠读取或对齐。 */
    TIMELINE_UNRELIABLE,
    /** 探针达到受控资源上限。 */
    RESOURCE_EXHAUSTED,
    /** 未归类的内部错误。 */
    INTERNAL
}
