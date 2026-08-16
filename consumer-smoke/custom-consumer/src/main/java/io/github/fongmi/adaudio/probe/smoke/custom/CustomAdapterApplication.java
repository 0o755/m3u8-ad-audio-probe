/* 独立消费者证明第三方可实现适配器，并且完全不需要 Media3 类型。 */
package io.github.fongmi.adaudio.probe.smoke.custom;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterRequest;

/** Release/R8 编译入口，不包含任何具体播放器依赖。 */
public final class CustomAdapterApplication extends Application {
    private AdAudioProbe probe;

    @Override
    public void onCreate() {
        super.onCreate();
        probe = AdAudioProbe.builder(this, "https://example.com/rules.json")
                .setPlaybackClock(() -> 0L)
                .setListener(request -> { })
                .setAdapterFactory(new NoOpAdapterFactory())
                .build();
    }

    @Override
    public void onTerminate() {
        if (probe != null) probe.close();
        super.onTerminate();
    }

    private static final class NoOpAdapterFactory implements ProbeAdapterFactory {
        @Override
        public int getSpiVersion() {
            return SPI_VERSION;
        }

        @Override
        public String getId() {
            return "smoke-noop";
        }

        @Override
        public ProbeAdapter create(Context applicationContext, Looper controlLooper,
                                   ProbeAdapter.Listener listener) {
            return new NoOpAdapter();
        }
    }

    private static final class NoOpAdapter implements ProbeAdapter {
        @Override public void open(ProbeAdapterRequest request) { }
        @Override public void updateHostPosition(long sessionId, long positionMs) { }
        @Override public void stop(long sessionId) { }
        @Override public void close() { }
    }
}
