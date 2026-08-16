/* PCM 消费合同只在探针内部流转，宿主永远不接触音频缓冲。 */
package io.github.fongmi.adaudio.probe.internal.media3;

import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;

interface ProbePcmConsumer {
    void onPcm(PcmChunk chunk, long endPositionMs);
    void onTimelineReset();
    void onFailure(RuntimeException error);
}
