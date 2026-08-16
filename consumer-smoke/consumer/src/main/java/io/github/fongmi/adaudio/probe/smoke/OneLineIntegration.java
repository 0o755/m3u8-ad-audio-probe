/* 真实消费者示例只提供宿主位置与 seek，证明接入不需要 PCM 或 Media3 类型。 */
package io.github.fongmi.adaudio.probe.smoke;

import android.content.Context;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.RuleReplacementResult;
import io.github.fongmi.adaudio.probe.RuleReplacementState;
import io.github.fongmi.adaudio.probe.player.ProbePlayer;
import io.github.fongmi.adaudio.probe.player.ProbePlayerListener;
import io.github.fongmi.adaudio.probe.tools.AudioFingerprintCollector;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateScanner;

public final class OneLineIntegration {
    private OneLineIntegration() {
    }

    public static AdAudioProbe create(Context context, HostPlayer player, String rulesUrl) {
        return AdAudioProbe.create(context, rulesUrl,
                player::getCurrentPositionMs,
                request -> player.seekTo(request.getSeekTargetPositionMs()));
    }

    public static AdAudioProbe createWithLocalRules(
            Context context, HostPlayer player, byte[] rulesJson) {
        return AdAudioProbe.builder(context)
                .setInitialRules(rulesJson)
                .setPlaybackClock(player::getCurrentPositionMs)
                .setListener(request -> player.seekTo(request.getSeekTargetPositionMs()))
                .build();
    }

    public static ProbePlayer createVisiblePlayer(
            Context context, ProbePlayerListener listener) {
        return ProbePlayer.create(context, listener);
    }

    public static long replaceLocalRules(AdAudioProbe probe, byte[] rulesJson) {
        return probe.replaceRules(rulesJson);
    }

    public static boolean isAppliedReplacement(
            RuleReplacementResult result, long requestId) {
        return result.getRequestId() == requestId
                && result.getState() == RuleReplacementState.APPLIED;
    }

    public static AudioFingerprintCollector createCollector(Context context) {
        return new AudioFingerprintCollector.Builder(context).build();
    }

    public static HlsCandidateScanner createScanner() {
        return new HlsCandidateScanner.Builder().build();
    }

    public interface HostPlayer {
        long getCurrentPositionMs();
        void seekTo(long positionMs);
    }
}
