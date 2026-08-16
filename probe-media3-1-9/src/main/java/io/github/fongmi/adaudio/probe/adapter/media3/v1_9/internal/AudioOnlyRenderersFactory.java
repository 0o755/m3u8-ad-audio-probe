/* Renderer 工厂只创建系统音频解码器，明确排除视频、字幕、图片和元数据链路。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

@UnstableApi
final class AudioOnlyRenderersFactory implements RenderersFactory {
    private final Context context;
    private final ProbeAudioSink audioSink;

    AudioOnlyRenderersFactory(Context context, ProbeAudioSink audioSink) {
        this.context = context.getApplicationContext();
        this.audioSink = audioSink;
    }

    @Override
    public Renderer[] createRenderers(Handler eventHandler,
                                      VideoRendererEventListener videoListener,
                                      AudioRendererEventListener audioListener,
                                      TextOutput textOutput,
                                      MetadataOutput metadataOutput) {
        return new Renderer[]{new MediaCodecAudioRenderer(context,
                MediaCodecSelector.DEFAULT, true, eventHandler, audioListener, audioSink)};
    }
}
