/* 官方工厂把 Media3 1.9.2 实现装配到稳定适配器 SPI。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9;

import android.content.Context;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal.Media3ProbeAdapter;

/** Media3 1.9.2 官方适配器工厂；无参构造器供默认服务发现使用。 */
public final class Media3ProbeAdapterFactory implements ProbeAdapterFactory {
    @Override
    public int getSpiVersion() {
        return SPI_VERSION;
    }

    @Override
    public String getId() {
        return "media3-1.9.2";
    }

    @Override
    public ProbeAdapter create(Context applicationContext, Looper controlLooper,
                               ProbeAdapter.Listener listener) {
        return new Media3ProbeAdapter(applicationContext, controlLooper, listener);
    }
}
