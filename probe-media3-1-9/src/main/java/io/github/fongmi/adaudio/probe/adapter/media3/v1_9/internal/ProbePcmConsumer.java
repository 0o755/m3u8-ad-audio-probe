/* Media3 解码层把借用 PCM 帧同步交给通用适配器监听器。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;

interface ProbePcmConsumer {
    void onPcm(ProbePcmFrame frame);
    void onTimelineReset();
    void onFailure(RuntimeException error);
}
