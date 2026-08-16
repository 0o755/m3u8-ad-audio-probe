/* 验证跨源重定向不会泄漏认证、Cookie 或 Referer 请求头。 */
package io.github.fongmi.adaudio.probe.tools;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.fongmi.adaudio.probe.ProbeMedia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HlsCandidateScannerTest {
    @Test
    public void closeDuringSubmitDoesNotLeakTimeoutRejection() throws Exception {
        final AtomicReference<HlsCandidateScanner> scannerRef = new AtomicReference<>();
        ClosingExecutor network = new ClosingExecutor(new Runnable() {
            @Override public void run() { scannerRef.get().close(); }
        });
        HlsCandidateScanner scanner = new HlsCandidateScanner.Builder()
                .setNetworkExecutor(network)
                .build();
        scannerRef.set(scanner);
        final CountDownLatch cancelled = new CountDownLatch(1);

        ProbeToolSession session = scanner.scan(
                ProbeMedia.from("https://example.com/video.m3u8"),
                new HlsScanListener() {
                    @Override public void onCompleted(HlsScanResult result) { }
                    @Override public void onCancelled(long sessionId) { cancelled.countDown(); }
                    @Override public void onError(ProbeToolError error) { }
                });

        assertTrue(session.getSessionId() > 0L);
        assertTrue(cancelled.await(1, TimeUnit.SECONDS));
        network.shutdownNow();
    }

    @Test
    public void stripsSensitiveHeadersAcrossOriginRedirect() throws Exception {
        final AtomicReference<Map<String, String>> received = new AtomicReference<>();
        MiniServer destination = new MiniServer(new Responder() {
            @Override public Response respond(Map<String, String> headers) {
                received.set(headers);
                return new Response(200, null,
                        "#EXTM3U\n#EXTINF:5,\na.ts\n#EXT-X-ENDLIST\n");
            }
        });
        final String destinationUrl = destination.url("/vod.m3u8");
        MiniServer redirect = new MiniServer(new Responder() {
            @Override public Response respond(Map<String, String> headers) {
                return new Response(302, destinationUrl, "");
            }
        });
        HlsCandidateScanner scanner = new HlsCandidateScanner.Builder().build();
        try {
            ProbeMedia media = ProbeMedia.builder(redirect.url("/start"))
                    .setType(ProbeMedia.Type.HLS)
                    .setHeader("Authorization", "Bearer secret")
                    .setHeader("Cookie", "session=secret")
                    .setHeader("Referer", "https://private.example/")
                    .setHeader("X-Api-Key", "private-key")
                    .setHeader("User-Agent", "Probe Scanner Test")
                    .setHeader("Cache-Control", "no-cache")
                    .build();
            final CountDownLatch finished = new CountDownLatch(1);
            final AtomicReference<HlsScanResult> result = new AtomicReference<>();
            final AtomicReference<ProbeToolError> error = new AtomicReference<>();
            scanner.scan(media, new HlsScanListener() {
                @Override public void onCompleted(HlsScanResult value) {
                    result.set(value); finished.countDown();
                }
                @Override public void onCancelled(long sessionId) { finished.countDown(); }
                @Override public void onError(ProbeToolError value) {
                    error.set(value); finished.countDown();
                }
            });

            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertNull(error.get());
            assertEquals(5_000L, result.get().getTotalDurationMs());
            assertNull(received.get().get("authorization"));
            assertNull(received.get().get("cookie"));
            assertNull(received.get().get("referer"));
            assertNull(received.get().get("x-api-key"));
            assertEquals("Probe Scanner Test", received.get().get("user-agent"));
            assertEquals("no-cache", received.get().get("cache-control"));
        } finally {
            scanner.close();
            redirect.close();
            destination.close();
        }
    }

    private interface Responder {
        Response respond(Map<String, String> headers);
    }

    private static final class Response {
        final int status;
        final String location;
        final String body;
        Response(int status, String location, String body) {
            this.status = status;
            this.location = location;
            this.body = body;
        }
    }

    private static final class ClosingExecutor extends AbstractExecutorService {
        private final Runnable closeHook;
        private volatile boolean shutdown;

        ClosingExecutor(Runnable closeHook) { this.closeHook = closeHook; }

        @Override public void shutdown() { shutdown = true; }

        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override public boolean isShutdown() { return shutdown; }

        @Override public boolean isTerminated() { return shutdown; }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override public void execute(Runnable command) { closeHook.run(); }
    }

    /** 单请求测试服务避免依赖 Android 测试类路径之外的 HTTP 实现。 */
    private static final class MiniServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;

        MiniServer(final Responder responder) throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(new Runnable() {
                @Override public void run() {
                    try (Socket socket = server.accept()) {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(
                                socket.getInputStream(), StandardCharsets.ISO_8859_1));
                        reader.readLine();
                        Map<String, String> headers = new LinkedHashMap<>();
                        String line;
                        while ((line = reader.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            if (colon > 0) {
                                headers.put(line.substring(0, colon).trim()
                                                .toLowerCase(Locale.US),
                                        line.substring(colon + 1).trim());
                            }
                        }
                        Response response = responder.respond(headers);
                        byte[] body = response.body.getBytes(StandardCharsets.UTF_8);
                        String reason = response.status == 302 ? "Found" : "OK";
                        StringBuilder head = new StringBuilder("HTTP/1.1 ")
                                .append(response.status).append(' ').append(reason).append("\r\n")
                                .append("Content-Length: ").append(body.length).append("\r\n")
                                .append("Connection: close\r\n");
                        if (response.location != null) {
                            head.append("Location: ").append(response.location).append("\r\n");
                        }
                        head.append("\r\n");
                        OutputStream output = socket.getOutputStream();
                        output.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                        output.write(body);
                        output.flush();
                    } catch (IOException ignored) {
                        // 测试取消或关闭服务时 accept/read 会正常中断。
                    }
                }
            }, "ProbeMiniHttpServer");
            thread.setDaemon(true);
            thread.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getLocalPort() + path;
        }

        @Override public void close() throws Exception {
            server.close();
            thread.join(1000L);
        }
    }
}
