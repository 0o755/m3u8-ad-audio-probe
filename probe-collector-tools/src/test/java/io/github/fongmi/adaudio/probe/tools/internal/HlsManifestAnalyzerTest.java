/* 验证 HLS VOD 候选、主清单、直播与 DRM 拒绝合同。 */
package io.github.fongmi.adaudio.probe.tools.internal;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import io.github.fongmi.adaudio.probe.tools.HlsScanResult;
import io.github.fongmi.adaudio.probe.tools.ProbeToolErrorCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HlsManifestAnalyzerTest {
    private static final HlsManifestAnalyzer.Cancellation ACTIVE =
            new HlsManifestAnalyzer.Cancellation() {
                @Override public void check() { }
            };

    @Test
    public void findsDiscontinuousForeignCandidate() throws Exception {
        String url = "https://example.com/media/index.m3u8";
        Map<String, String> manifests = new HashMap<>();
        manifests.put(url, candidatePlaylist());

        HlsScanResult result = new HlsManifestAnalyzer().scan(
                7L, url, loader(manifests), ACTIVE);

        assertEquals(45_000L, result.getTotalDurationMs());
        assertEquals(1, result.getCandidates().size());
        assertEquals(20_000L, result.getCandidates().get(0)
                .getOccurrences().get(0).getStartMs());
        assertTrue(result.getCandidates().get(0).getConfidence() >= 80);
    }

    @Test
    public void resolvesHighestBandwidthVariant() throws Exception {
        String master = "https://example.com/master.m3u8";
        Map<String, String> manifests = new HashMap<>();
        manifests.put(master, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nlow.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=200\nhigh.m3u8\n");
        manifests.put("https://example.com/high.m3u8", simpleVod());

        HlsScanResult result = new HlsManifestAnalyzer().scan(
                8L, master, loader(manifests), ACTIVE);

        assertEquals("https://example.com/high.m3u8", result.getMediaPlaylistUrl());
    }

    @Test
    public void rejectsLivePlaylist() throws Exception {
        String url = "https://example.com/live.m3u8";
        Map<String, String> manifests = new HashMap<>();
        manifests.put(url, "#EXTM3U\n#EXTINF:5,\na.ts\n");
        try {
            new HlsManifestAnalyzer().scan(9L, url, loader(manifests), ACTIVE);
        } catch (HlsScanException error) {
            assertEquals(ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED, error.getCode());
            return;
        }
        throw new AssertionError("应拒绝直播清单");
    }

    @Test
    public void rejectsDrmPlaylist() throws Exception {
        String url = "https://example.com/drm.m3u8";
        Map<String, String> manifests = new HashMap<>();
        manifests.put(url, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,"
                + "URI=\"key\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n"
                + "#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n");
        try {
            new HlsManifestAnalyzer().scan(10L, url, loader(manifests), ACTIVE);
        } catch (HlsScanException error) {
            assertEquals(ProbeToolErrorCode.DRM_NOT_SUPPORTED, error.getCode());
            return;
        }
        throw new AssertionError("应拒绝 DRM 清单");
    }

    private HlsManifestAnalyzer.Loader loader(final Map<String, String> manifests) {
        return new HlsManifestAnalyzer.Loader() {
            @Override public HlsManifestAnalyzer.LoadedManifest load(
                    String url, HlsManifestAnalyzer.Cancellation cancellation) throws IOException {
                String text = manifests.get(url);
                if (text == null) throw new IOException("missing " + url);
                return new HlsManifestAnalyzer.LoadedManifest(url, text);
            }
        };
    }

    private String candidatePlaylist() {
        return "#EXTM3U\n"
                + "#EXTINF:10,\nmain/1.ts\n#EXTINF:10,\nmain/2.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:5,\nad/spot.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:10,\nmain/3.ts\n"
                + "#EXTINF:10,\nmain/4.ts\n#EXT-X-ENDLIST\n";
    }

    private String simpleVod() {
        return "#EXTM3U\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n";
    }
}
