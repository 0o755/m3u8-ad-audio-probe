/* 真实消费者示例只提供宿主位置与 seek，证明接入不需要 PCM 或 Media3 类型。 */
package io.github.fongmi.adaudio.probe.smoke;

import android.content.Context;

import io.github.fongmi.adaudio.probe.AdAudioProbe;

public final class OneLineIntegration {
    private OneLineIntegration() {
    }

    public static AdAudioProbe create(Context context, HostPlayer player, String rulesUrl) {
        return AdAudioProbe.create(context, rulesUrl,
                player::getCurrentPositionMs,
                request -> player.seekTo(request.getSeekTargetPositionMs()));
    }

    public interface HostPlayer {
        long getCurrentPositionMs();
        void seekTo(long positionMs);
    }
}
