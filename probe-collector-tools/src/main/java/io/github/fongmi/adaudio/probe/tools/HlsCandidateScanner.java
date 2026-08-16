/* 异步 HLS 扫描器负责有界下载、取消、超时和结构化终态。 */
package io.github.fongmi.adaudio.probe.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.tools.internal.HlsManifestAnalyzer;
import io.github.fongmi.adaudio.probe.tools.internal.HlsScanException;
import io.github.fongmi.adaudio.probe.tools.internal.SerialCallbackExecutor;

/**
 * 扫描普通 HLS VOD 清单中的结构型广告候选。
 *
 * <p>扫描只分析清单时间线，不下载媒体分片，也不把候选直接当作已验证规则。MP4、直播、
 * DRM 和动态清单会返回明确错误。同一实例只运行一个活动会话，开始新扫描会取消旧会话。</p>
 */
public final class HlsCandidateScanner implements AutoCloseable {
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final long MIN_TIMEOUT_MS = 5_000L;
    private static final long MAX_TIMEOUT_MS = 120_000L;

    private final Object monitor = new Object();
    private final ExecutorService networkExecutor;
    private final boolean ownsNetworkExecutor;
    private final ScheduledExecutorService timeoutExecutor;
    private final SerialCallbackExecutor callbacks;
    private final long timeoutMs;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final AtomicLong sessionCounter = new AtomicLong();
    private ActiveScan active;
    private boolean closed;

    private HlsCandidateScanner(Builder builder) {
        this.networkExecutor = builder.networkExecutor != null
                ? builder.networkExecutor : Executors.newSingleThreadExecutor(
                        namedThreadFactory("AdAudioHlsScanner"));
        this.ownsNetworkExecutor = builder.networkExecutor == null;
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(
                namedThreadFactory("AdAudioHlsScannerTimeout"));
        this.callbacks = new SerialCallbackExecutor(builder.callbackExecutor != null
                ? builder.callbackExecutor : directExecutor());
        this.timeoutMs = builder.timeoutMs;
        this.connectTimeoutMs = builder.connectTimeoutMs;
        this.readTimeoutMs = builder.readTimeoutMs;
    }

    /**
     * 开始扫描普通 HLS VOD。
     *
     * @param media HLS 或 AUTO 点播媒体；请求头仅在同源请求中完整携带
     * @param listener 单次完成、取消或错误监听器
     * @return 可取消扫描会话
     */
    public ProbeToolSession scan(ProbeMedia media, HlsScanListener listener) {
        if (media == null || listener == null) {
            throw new IllegalArgumentException("媒体请求和监听器不能为空");
        }
        long sessionId = nextSessionId();
        final ActiveScan created = new ActiveScan(sessionId, media, listener);
        ActiveScan replaced;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("HLS 扫描器已关闭");
            replaced = active;
            if (replaced != null) replaced.terminal = true;
            active = created;
        }
        if (replaced != null) cancelResourcesAndDispatch(replaced);
        Future<?> future;
        try {
            future = networkExecutor.submit(new Runnable() {
                @Override public void run() { runScan(created); }
            });
        } catch (RuntimeException rejected) {
            fail(created, ProbeToolErrorCode.INTERNAL, false,
                    "HLS 扫描线程不可用", rejected);
            return new SessionHandle(sessionId);
        }
        synchronized (monitor) {
            created.future = future;
            if (created.terminal) future.cancel(true);
        }
        ScheduledFuture<?> timeout;
        try {
            timeout = timeoutExecutor.schedule(new Runnable() {
                @Override public void run() {
                    fail(created, ProbeToolErrorCode.TIMEOUT, true, "HLS 候选扫描超时", null);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            synchronized (monitor) {
                if (created.terminal) return new SessionHandle(sessionId);
            }
            fail(created, ProbeToolErrorCode.INTERNAL, false,
                    "HLS 超时调度线程不可用", rejected);
            return new SessionHandle(sessionId);
        }
        synchronized (monitor) {
            created.timeoutFuture = timeout;
            if (created.terminal) timeout.cancel(false);
        }
        return new SessionHandle(sessionId);
    }

    /** 取消活动扫描并释放内部线程；外部提供的网络线程池仍归宿主管理。 */
    @Override
    public void close() {
        ActiveScan cancelled;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            cancelled = active;
            if (cancelled != null) cancelled.terminal = true;
            active = null;
        }
        if (cancelled != null) cancelResourcesAndDispatch(cancelled);
        timeoutExecutor.shutdownNow();
        if (ownsNetworkExecutor) networkExecutor.shutdownNow();
    }

    private void runScan(final ActiveScan scan) {
        try {
            scan.check();
            if (scan.media.getType() == ProbeMedia.Type.MP4) {
                throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                        false, "候选扫描仅支持普通 HLS VOD");
            }
            HlsManifestAnalyzer analyzer = new HlsManifestAnalyzer();
            HlsScanResult result = analyzer.scan(scan.sessionId, scan.media.getUrl(),
                    new ManifestLoader(scan), scan);
            complete(scan, result);
        } catch (CancelledScan ignored) {
            // 取消路径已由触发方派发唯一终态。
        } catch (HlsScanException error) {
            fail(scan, error.getCode(), error.isRetryable(), error.getMessage(), error);
        } catch (IOException error) {
            if (scan.isCancelled()) return;
            fail(scan, ProbeToolErrorCode.SOURCE_IO, isRetryableIo(error),
                    safeMessage(error, "HLS 清单读取失败"), error);
        } catch (RuntimeException error) {
            fail(scan, ProbeToolErrorCode.INTERNAL, false,
                    safeMessage(error, "HLS 候选扫描失败"), error);
        }
    }

    private void complete(ActiveScan scan, final HlsScanResult result) {
        synchronized (monitor) {
            if (!isActive(scan)) return;
            scan.terminal = true;
            active = null;
            cancelTimeout(scan);
        }
        callbacks.execute(new Runnable() {
            @Override public void run() { scan.listener.onCompleted(result); }
        });
    }

    private void fail(ActiveScan scan, ProbeToolErrorCode code, boolean retryable,
                      String message, Throwable cause) {
        final ProbeToolError error;
        synchronized (monitor) {
            if (!isActive(scan)) return;
            scan.terminal = true;
            active = null;
            cancelTimeout(scan);
            disconnect(scan);
            if (scan.future != null && Thread.currentThread() != scan.workerThread) {
                scan.future.cancel(true);
            }
            error = new ProbeToolError(code, scan.sessionId, retryable, message, cause);
        }
        callbacks.execute(new Runnable() {
            @Override public void run() { scan.listener.onError(error); }
        });
    }

    private void cancel(long sessionId) {
        ActiveScan scan;
        synchronized (monitor) {
            scan = active;
            if (scan == null || scan.terminal || scan.sessionId != sessionId) return;
            scan.terminal = true;
            active = null;
        }
        cancelResourcesAndDispatch(scan);
    }

    private void cancelResourcesAndDispatch(final ActiveScan scan) {
        disconnect(scan);
        Future<?> future = scan.future;
        if (future != null) future.cancel(true);
        cancelTimeout(scan);
        callbacks.execute(new Runnable() {
            @Override public void run() { scan.listener.onCancelled(scan.sessionId); }
        });
    }

    private boolean isActive(ActiveScan scan) {
        return scan != null && !scan.terminal && active == scan;
    }

    private static void disconnect(ActiveScan scan) {
        HttpURLConnection connection = scan.connection;
        if (connection != null) connection.disconnect();
    }

    private static void cancelTimeout(ActiveScan scan) {
        ScheduledFuture<?> timeout = scan.timeoutFuture;
        if (timeout != null) timeout.cancel(false);
    }

    private long nextSessionId() {
        long value = sessionCounter.incrementAndGet();
        if (value > 0L) return value;
        synchronized (sessionCounter) {
            if (sessionCounter.get() <= 0L) sessionCounter.set(1L);
            return sessionCounter.getAndIncrement();
        }
    }

    private final class ManifestLoader implements HlsManifestAnalyzer.Loader {
        private final ActiveScan scan;

        ManifestLoader(ActiveScan scan) { this.scan = scan; }

        @Override
        public HlsManifestAnalyzer.LoadedManifest load(
                String url, HlsManifestAnalyzer.Cancellation cancellation) throws IOException {
            return loadRedirect(url, 0);
        }

        private HlsManifestAnalyzer.LoadedManifest loadRedirect(String rawUrl, int redirects)
                throws IOException {
            scan.check();
            if (redirects > MAX_REDIRECTS) {
                throw new HlsScanException(ProbeToolErrorCode.SOURCE_IO,
                        false, "HLS 重定向次数超过上限");
            }
            URI uri = checkedHttpUri(rawUrl);
            URI original = checkedHttpUri(scan.media.getUrl());
            if ("https".equalsIgnoreCase(original.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                        false, "拒绝从 HTTPS 降级读取 HTTP 清单");
            }
            HttpURLConnection connection = (HttpURLConnection) new URL(uri.toString())
                    .openConnection();
            scan.connection = connection;
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setUseCaches(false);
            applyHeaders(connection, scan.media.getHeaders(), sameOrigin(original, uri));
            try {
                int status = connection.getResponseCode();
                scan.check();
                if (status >= 300 && status <= 399) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new HlsScanException(ProbeToolErrorCode.SOURCE_IO,
                                false, "HLS 重定向缺少 Location");
                    }
                    String next = uri.resolve(location).toString();
                    return loadRedirect(next, redirects + 1);
                }
                if (status < 200 || status > 299) {
                    boolean retryable = status == 408 || status == 429 || status >= 500;
                    throw new HlsScanException(ProbeToolErrorCode.SOURCE_IO,
                            retryable, "HLS 清单请求失败，HTTP " + status);
                }
                long contentLength = connection.getContentLength();
                if (contentLength > MAX_MANIFEST_BYTES
                        || contentLength >= 0L
                        && contentLength > MAX_TOTAL_MANIFEST_BYTES - scan.manifestBytes) {
                    throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                            false, "HLS 清单累计超过 4 MiB");
                }
                byte[] data = readBounded(connection.getInputStream(), scan);
                return new HlsManifestAnalyzer.LoadedManifest(
                        connection.getURL().toString(), decodeUtf8(data));
            } finally {
                if (scan.connection == connection) scan.connection = null;
                connection.disconnect();
            }
        }
    }

    private static void applyHeaders(HttpURLConnection connection,
                                     Map<String, String> headers, boolean sameOrigin) {
        boolean hasUserAgent = false;
        boolean hasAccept = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.US);
            if (!sameOrigin && !isCrossOriginSafe(name)) continue;
            connection.setRequestProperty(entry.getKey(), entry.getValue());
            if ("user-agent".equals(name)) hasUserAgent = true;
            if ("accept".equals(name)) hasAccept = true;
        }
        if (!hasUserAgent) connection.setRequestProperty("User-Agent", "m3u8-ad-audio-probe/0.1");
        if (!hasAccept) connection.setRequestProperty("Accept",
                "application/vnd.apple.mpegurl,application/x-mpegURL,*/*");
    }

    private static boolean isCrossOriginSafe(String name) {
        return "user-agent".equals(name) || "accept".equals(name)
                || "accept-language".equals(name) || "cache-control".equals(name)
                || "pragma".equals(name);
    }

    private static byte[] readBounded(InputStream input, ActiveScan scan) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024)) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            int read;
            while ((read = source.read(buffer)) != -1) {
                scan.check();
                total += read;
                scan.manifestBytes += read;
                if (total > MAX_MANIFEST_BYTES
                        || scan.manifestBytes > MAX_TOTAL_MANIFEST_BYTES) {
                    throw new HlsScanException(ProbeToolErrorCode.RESOURCE_EXHAUSTED,
                            false, "HLS 清单累计超过 4 MiB");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) throws HlsScanException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new HlsScanException(ProbeToolErrorCode.UNSUPPORTED_SOURCE,
                    false, "HLS 清单不是有效 UTF-8", error);
        }
    }

    private static URI checkedHttpUri(String raw) throws HlsScanException {
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getRawAuthority() == null || uri.getRawAuthority().isEmpty()
                    || uri.getHost() == null || uri.getHost().isEmpty()
                    || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new URISyntaxException(raw, "仅支持不含用户信息的 HTTP(S) URL");
            }
            if (raw.length() > 8192) throw new URISyntaxException(raw, "URL 过长");
            return uri;
        } catch (URISyntaxException error) {
            throw new HlsScanException(ProbeToolErrorCode.INVALID_REQUEST,
                    false, "HLS URL 无效", error);
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && normalizedHost(left).equals(normalizedHost(right))
                && effectivePort(left) == effectivePort(right);
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Executor directExecutor() {
        return new Executor() {
            @Override public void execute(Runnable command) { command.run(); }
        };
    }

    private static ThreadFactory namedThreadFactory(final String name) {
        return new ThreadFactory() {
            @Override public Thread newThread(Runnable command) {
                Thread thread = new Thread(command, name);
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static String safeMessage(Throwable error, String fallback) {
        return error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? fallback : error.getMessage();
    }

    private static boolean isRetryableIo(IOException error) {
        return error instanceof SocketTimeoutException || error instanceof ConnectException
                || error instanceof NoRouteToHostException || error instanceof UnknownHostException
                || error instanceof SocketException;
    }

    /** 创建独立的 HLS 候选扫描器。 */
    public static final class Builder {
        private ExecutorService networkExecutor;
        private Executor callbackExecutor;
        private long timeoutMs = 30_000L;
        private int connectTimeoutMs = 10_000;
        private int readTimeoutMs = 15_000;

        /**
         * 使用宿主管理的网络线程池；扫描器关闭时不会关闭它。
         *
         * @param executor 可提交阻塞式清单读取任务的线程池
         * @return 当前构建器
         */
        public Builder setNetworkExecutor(ExecutorService executor) {
            if (executor == null) throw new IllegalArgumentException("网络线程池不能为空");
            this.networkExecutor = executor;
            return this;
        }

        /**
         * 设置宿主回调执行器；默认在内部扫描线程完成后同步串行回调。
         *
         * @param executor 可接受串行包装任务的执行器
         * @return 当前构建器
         */
        public Builder setCallbackExecutor(Executor executor) {
            if (executor == null) throw new IllegalArgumentException("回调 Executor 不能为空");
            this.callbackExecutor = executor;
            return this;
        }

        /**
         * 设置整个扫描会话超时，允许 5 到 120 秒。
         *
         * @param timeoutMs 超时时间，单位毫秒
         * @return 当前构建器
         */
        public Builder setTimeoutMs(long timeoutMs) {
            if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
                throw new IllegalArgumentException("扫描超时必须为 5 到 120 秒");
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置单次连接超时，允许 1 到 30 秒。
         *
         * @param timeoutMs 连接超时，单位毫秒
         * @return 当前构建器
         */
        public Builder setConnectTimeoutMs(int timeoutMs) {
            if (timeoutMs < 1000 || timeoutMs > 30_000) {
                throw new IllegalArgumentException("连接超时必须为 1 到 30 秒");
            }
            this.connectTimeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置单次读取超时，允许 1 到 60 秒。
         *
         * @param timeoutMs 读取超时，单位毫秒
         * @return 当前构建器
         */
        public Builder setReadTimeoutMs(int timeoutMs) {
            if (timeoutMs < 1000 || timeoutMs > 60_000) {
                throw new IllegalArgumentException("读取超时必须为 1 到 60 秒");
            }
            this.readTimeoutMs = timeoutMs;
            return this;
        }

        /** @return 可复用且同一时刻只运行一个会话的扫描器 */
        public HlsCandidateScanner build() { return new HlsCandidateScanner(this); }
    }

    private final class SessionHandle implements ProbeToolSession {
        private final long sessionId;
        SessionHandle(long sessionId) { this.sessionId = sessionId; }
        @Override public long getSessionId() { return sessionId; }
        @Override public void cancel() { HlsCandidateScanner.this.cancel(sessionId); }
    }

    private static final class ActiveScan implements HlsManifestAnalyzer.Cancellation {
        final long sessionId;
        final ProbeMedia media;
        final HlsScanListener listener;
        volatile HttpURLConnection connection;
        volatile Thread workerThread;
        volatile Future<?> future;
        volatile ScheduledFuture<?> timeoutFuture;
        volatile boolean terminal;
        int manifestBytes;

        ActiveScan(long sessionId, ProbeMedia media, HlsScanListener listener) {
            this.sessionId = sessionId;
            this.media = media;
            this.listener = listener;
        }

        @Override public void check() throws IOException {
            workerThread = Thread.currentThread();
            if (terminal || Thread.currentThread().isInterrupted()) throw new CancelledScan();
        }

        boolean isCancelled() { return terminal || Thread.currentThread().isInterrupted(); }
    }

    private static final class CancelledScan extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
