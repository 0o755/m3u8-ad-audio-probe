/* 候选信号使用稳定枚举，避免宿主解析诊断文案。 */
package io.github.fongmi.adaudio.probe.tools;

/** HLS 结构候选获得置信分的稳定依据。 */
public enum HlsCandidateSignal {
    /** 候选开始位置存在 EXT-X-DISCONTINUITY。 */
    DISCONTINUITY_BEFORE,
    /** 候选结束位置存在 EXT-X-DISCONTINUITY。 */
    DISCONTINUITY_AFTER,
    /** 分片来源目录与主内容区间不同。 */
    SOURCE_GROUP_CHANGED,
    /** HLS 加密声明与主内容区间不同。 */
    ENCRYPTION_CHANGED,
    /** 初始化段声明与主内容区间不同。 */
    INIT_SEGMENT_CHANGED,
    /** 完整分片序列在清单时间线上重复。 */
    REPEATED_SEQUENCE,
    /** 区间长度不超过常见广告上限两分钟。 */
    COMMON_AD_DURATION
}
