/* MP4 点播仅注册标准与 fragmented MP4 提取器，避免保留无关容器代码。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;

@UnstableApi
final class Mp4OnlyExtractorsFactory implements ExtractorsFactory {
    @Override
    public Extractor[] createExtractors() {
        return new Extractor[]{new Mp4Extractor(), new FragmentedMp4Extractor()};
    }
}
