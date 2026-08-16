/* 验证 Media3 适配器的首块恢复、停止边界和保守重试分类。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import androidx.media3.common.PlaybackException;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.media3.v1_9.Media3ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterFactory;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Media3ProbeAdapterTest {
    @Test
    public void officialFactoryPublishesBothVersionedSpis() {
        Media3ProbeAdapterFactory factory = new Media3ProbeAdapterFactory();

        assertTrue(factory instanceof ProbeAdapterFactory);
        assertTrue(factory instanceof ProbePlaybackAdapterFactory);
        assertEquals(ProbeAdapterFactory.SPI_VERSION, factory.getSpiVersion());
        assertEquals(ProbePlaybackAdapterFactory.SPI_VERSION,
                factory.getPlaybackSpiVersion());
    }

    @Test
    public void firstPcmRecoveryOnlySeeksMeaningfullyBackward() {
        assertTrue(Media3ProbeAdapter.shouldRecoverBeforeFirstPcm(90_000L, 1_000L));
        assertFalse(Media3ProbeAdapter.shouldRecoverBeforeFirstPcm(1_500L, 1_000L));
        assertFalse(Media3ProbeAdapter.shouldRecoverBeforeFirstPcm(1_000L, 2_000L));
    }

    @Test
    public void stopOnlyAcceptsExactPositiveSession() {
        assertTrue(Media3ProbeAdapter.isMatchingStopSession(7L, 7L));
        assertFalse(Media3ProbeAdapter.isMatchingStopSession(7L, 0L));
        assertFalse(Media3ProbeAdapter.isMatchingStopSession(7L, 8L));
        assertFalse(Media3ProbeAdapter.isMatchingStopSession(0L, 0L));
    }

    @Test
    public void retriesOnlyExplicitTransientNetworkFailures() {
        assertTrue(retryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                new SocketTimeoutException("timeout")));
        assertTrue(retryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                new UnknownHostException("temporary dns")));
        assertTrue(retryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                new SocketException("connection reset")));

        assertFalse(retryable(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                new SSLHandshakeException("certificate rejected")));
        assertFalse(retryable(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                new IOException("cleartext denied")));
        assertFalse(retryable(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                new IOException("range")));
        assertFalse(retryable(PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                new IOException("permanent io")));
    }

    @Test
    public void retriesOnlyTransientHttpStatuses() {
        assertTrue(Media3ProbeAdapter.isRetryableHttpStatus(408));
        assertTrue(Media3ProbeAdapter.isRetryableHttpStatus(429));
        assertTrue(Media3ProbeAdapter.isRetryableHttpStatus(503));
        assertFalse(Media3ProbeAdapter.isRetryableHttpStatus(401));
        assertFalse(Media3ProbeAdapter.isRetryableHttpStatus(404));
        assertFalse(Media3ProbeAdapter.isRetryableHttpStatus(600));
    }

    @Test
    public void unsupportedHeadersMapToUnsupportedSourceBeforeOpen() {
        assertNull(Media3ProbeAdapter.requestHeaderErrorCode(Collections.singletonMap(
                "user-agent", "Probe")));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer secret");
        assertEquals(ProbeErrorCode.UNSUPPORTED_SOURCE,
                Media3ProbeAdapter.requestHeaderErrorCode(headers));
    }

    private static boolean retryable(int errorCode, Throwable cause) {
        return Media3ProbeAdapter.isRetryableIoFailure(errorCode, cause);
    }
}
