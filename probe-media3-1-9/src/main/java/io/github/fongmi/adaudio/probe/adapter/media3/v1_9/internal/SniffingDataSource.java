/* DataSource 装饰器旁路观察真实响应，不增加 HEAD/Range 预检请求。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 将实际 Progressive 请求的少量响应证据交给 AUTO 判型器。 */
@UnstableApi
final class SniffingDataSource implements DataSource {
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory delegate;
        private final SourceObservation observation;

        Factory(DataSource.Factory delegate, SourceObservation observation) {
            this.delegate = delegate;
            this.observation = observation;
        }

        @Override
        public DataSource createDataSource() {
            return new SniffingDataSource(delegate.createDataSource(), observation);
        }
    }

    private final DataSource delegate;
    private final SourceObservation observation;
    private boolean observeRoot;

    private SniffingDataSource(DataSource delegate, SourceObservation observation) {
        this.delegate = delegate;
        this.observation = observation;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        delegate.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long result = delegate.open(dataSpec);
        observeRoot = dataSpec.position == 0L
                && observation.beginResponse(delegate.getResponseHeaders());
        return result;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = delegate.read(buffer, offset, length);
        if (observeRoot && read > 0) observation.recordBytes(buffer, offset, read);
        return read;
    }

    @Override
    public Uri getUri() {
        return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return delegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
