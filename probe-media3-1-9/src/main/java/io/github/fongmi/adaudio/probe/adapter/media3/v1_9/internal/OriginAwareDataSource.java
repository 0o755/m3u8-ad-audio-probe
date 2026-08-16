/* 媒体请求头按源站隔离，避免直接跨域的 HLS 分片自动携带敏感凭据。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import android.net.Uri;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 为原始站点和跨域子资源选择不同请求头集合。 */
@UnstableApi
final class OriginAwareDataSource implements DataSource {
    static final class Factory implements DataSource.Factory {
        private final DataSource.Factory sameOriginFactory;
        private final DataSource.Factory crossOriginFactory;
        private final Origin origin;

        Factory(DataSource.Factory sameOriginFactory, DataSource.Factory crossOriginFactory,
                String sourceUrl) {
            this.sameOriginFactory = sameOriginFactory;
            this.crossOriginFactory = crossOriginFactory;
            this.origin = Origin.parse(sourceUrl);
        }

        @Override
        public DataSource createDataSource() {
            return new OriginAwareDataSource(sameOriginFactory, crossOriginFactory, origin);
        }
    }

    private final DataSource.Factory sameOriginFactory;
    private final DataSource.Factory crossOriginFactory;
    private final Origin origin;
    private final List<TransferListener> transferListeners = new ArrayList<>();
    private DataSource active;

    private OriginAwareDataSource(DataSource.Factory sameOriginFactory,
                                  DataSource.Factory crossOriginFactory, Origin origin) {
        this.sameOriginFactory = sameOriginFactory;
        this.crossOriginFactory = crossOriginFactory;
        this.origin = origin;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        if (transferListener == null) return;
        transferListeners.add(transferListener);
        if (active != null) active.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (active != null) throw new IllegalStateException("DataSource 已打开");
        DataSource.Factory selected = origin.matches(dataSpec.uri)
                ? sameOriginFactory : crossOriginFactory;
        active = selected.createDataSource();
        for (TransferListener transferListener : transferListeners) {
            active.addTransferListener(transferListener);
        }
        return active.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (active == null) throw new IllegalStateException("DataSource 尚未打开");
        return active.read(buffer, offset, length);
    }

    @Override
    public Uri getUri() {
        return active == null ? null : active.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return active == null ? Collections.emptyMap() : active.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        DataSource current = active;
        active = null;
        if (current != null) current.close();
    }

    static Map<String, String> crossOriginHeaders(Map<String, String> headers) {
        return Media3RequestHeaderPolicy.copyAllowed(headers);
    }

    static boolean sameOrigin(String sourceUrl, String requestUrl) {
        return Origin.parse(sourceUrl).matches(requestUrl);
    }

    private static final class Origin {
        final String scheme;
        final String host;
        final int port;

        private Origin(String scheme, String host, int port) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
        }

        static Origin parse(String value) {
            URI uri = URI.create(value);
            String scheme = uri.getScheme().toLowerCase(Locale.US);
            String host = uri.getHost().toLowerCase(Locale.US);
            int port = uri.getPort();
            if (port < 0) port = "https".equals(scheme) ? 443 : 80;
            return new Origin(scheme, host, port);
        }

        boolean matches(Uri value) {
            return value != null && matches(value.toString());
        }

        boolean matches(String value) {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) return false;
            String valueScheme = uri.getScheme().toLowerCase(Locale.US);
            String valueHost = uri.getHost().toLowerCase(Locale.US);
            int valuePort = uri.getPort();
            if (valuePort < 0) valuePort = "https".equals(valueScheme) ? 443 : 80;
            return scheme.equals(valueScheme) && host.equals(valueHost) && port == valuePort;
        }
    }
}
