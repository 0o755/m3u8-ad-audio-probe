/* 规则仓库串行处理本地注入与 HTTPS 刷新，并只发布完整验证的 JSON。 */
package io.github.fongmi.adaudio.probe.internal.rules;

import android.content.Context;
import android.util.AtomicFile;

import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;

public final class AtomicRuleRepository implements AutoCloseable {
    public interface Listener {
        void onRules(AdRuleSet rules, boolean fromCache, long replacementRequestId);
        void onFailure(ProbeErrorCode code, boolean cacheAvailable, Exception error,
                       long replacementRequestId);
        void onReplacementSuperseded(long replacementRequestId);
    }

    private static final int MAX_RULE_BYTES = RuleSetJsonParser.MAX_DOCUMENT_BYTES;
    private static final int MAX_URL_LENGTH = 8192;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final long TOTAL_DOWNLOAD_TIMEOUT_NS = 45_000_000_000L;
    private static final long NO_TOTAL_TIMEOUT = -1L;
    private static final ConcurrentMap<String, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

    private final String ruleUrl;
    private final AtomicFile cache;
    private final Object cacheLock;
    private final File tempDirectory;
    private final Listener listener;
    private final ExecutorService executor;
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean refreshAgain = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<ReplacementRequest> pendingReplacement =
            new AtomicReference<>();
    private final AtomicBoolean replacementWorkerScheduled = new AtomicBoolean();
    private final AtomicLong replacementSequence = new AtomicLong();

    private volatile HttpURLConnection activeConnection;
    private volatile AdRuleSet currentRules;
    private volatile String currentDigest;

    public AtomicRuleRepository(Context context, String ruleUrl, Listener listener) {
        Context appContext = context.getApplicationContext();
        this.ruleUrl = ruleUrl == null ? null : validateRuleUrl(ruleUrl);
        this.listener = listener;
        if (this.ruleUrl == null) {
            this.cache = null;
            this.cacheLock = null;
        } else {
            File directory = new File(appContext.getNoBackupFilesDir(), "ad-audio-probe");
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("无法创建规则缓存目录");
            }
            File cacheFile = new File(directory, digest(this.ruleUrl) + ".json");
            this.cache = new AtomicFile(cacheFile);
            this.cacheLock = cacheLockFor(cacheFile.getAbsolutePath());
        }
        this.tempDirectory = appContext.getCacheDir();
        this.executor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    /** 首次调用会先发布有效缓存，再尝试网络更新；重复刷新会合并。 */
    public void refresh() {
        if (ruleUrl == null) throw new IllegalStateException("未配置远程规则地址");
        if (closed.get()) return;
        if (!loading.compareAndSet(false, true)) {
            refreshAgain.set(true);
            return;
        }
        if (!tryExecute(executor, this::loadLoop)) {
            loading.set(false);
            refreshAgain.set(false);
            if (!closed.get()) {
                deliverFailure(ProbeErrorCode.RULE_FETCH_FAILED, currentRules != null,
                        new IllegalStateException("规则加载线程不可用"), 0L);
            }
        }
    }

    public AdRuleSet getCurrentRules() {
        return currentRules;
    }

    public boolean hasRemoteSource() {
        return ruleUrl != null;
    }

    /** 后台严格解析并无条件替换进程内规则；失败时保留最后一个有效版本。 */
    public long replace(byte[] rulesJson) {
        byte[] owned = RuleSetJsonParser.copyDocument(rulesJson);
        if (closed.get()) throw new IllegalStateException("规则仓库已关闭");
        long requestId = nextReplacementId();
        ReplacementRequest request = new ReplacementRequest(requestId, owned);
        ReplacementRequest superseded = pendingReplacement.getAndSet(request);
        if (superseded != null) deliverReplacementSuperseded(superseded.requestId);
        if (closed.get()) {
            pendingReplacement.compareAndSet(request, null);
            throw new IllegalStateException("规则仓库已关闭");
        }
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        scheduleReplacementWorker();
        return requestId;
    }

    /** 高频替换只保留最新待解析载荷，避免调用方在后台队列中堆积大数组。 */
    private void scheduleReplacementWorker() {
        if (!replacementWorkerScheduled.compareAndSet(false, true)) return;
        if (!tryExecute(executor, this::drainReplacements)) {
            ReplacementRequest dropped = pendingReplacement.getAndSet(null);
            replacementWorkerScheduled.set(false);
            if (!closed.get()) {
                deliverFailure(ProbeErrorCode.RULE_PARSE_FAILED, currentRules != null,
                        new IllegalStateException("规则替换线程不可用"),
                        dropped == null ? 0L : dropped.requestId);
                if (pendingReplacement.get() != null) scheduleReplacementWorker();
            }
        }
    }

    private void drainReplacements() {
        try {
            while (!closed.get()) {
                ReplacementRequest request = pendingReplacement.getAndSet(null);
                if (request == null) break;
                replaceOnce(request);
            }
        } finally {
            replacementWorkerScheduled.set(false);
            if (!closed.get() && pendingReplacement.get() != null) {
                scheduleReplacementWorker();
            }
        }
    }

    private void replaceOnce(ReplacementRequest request) {
        try {
            if (closed.get()) return;
            AdRuleSet parsed = RuleSetJsonParser.parseUtf8(request.rulesJson);
            currentRules = parsed;
            currentDigest = toHex(sha256().digest(request.rulesJson));
            deliverRules(parsed, false, request.requestId);
        } catch (Exception error) {
            deliverFailure(ProbeErrorCode.RULE_PARSE_FAILED, currentRules != null, error,
                    request.requestId);
        }
    }

    private void loadLoop() {
        try {
            do {
                refreshAgain.set(false);
                loadOnce();
            } while (!closed.get() && !hasPendingReplacement()
                    && refreshAgain.getAndSet(false));
        } finally {
            loading.set(false);
            if (!closed.get() && refreshAgain.getAndSet(false)) refresh();
        }
    }

    private void loadOnce() {
        boolean cacheAvailable = currentRules != null;
        if (!cacheAvailable) {
            try {
                LoadedRules cached = readCache();
                if (cached != null) {
                    currentRules = cached.rules;
                    currentDigest = cached.digest;
                    cacheAvailable = true;
                    deliverRules(cached.rules, true, 0L);
                }
            } catch (Exception error) {
                if (!hasPendingReplacement()) {
                    deliverFailure(ProbeErrorCode.RULE_PARSE_FAILED, false, error, 0L);
                }
            }
        }
        if (closed.get()) return;

        File downloaded = null;
        try {
            downloaded = download();
            LoadedRules loaded = parseFile(downloaded);
            AdRuleSet parsed = loaded.rules;
            AdRuleSet previous = currentRules;
            CommitOutcome outcome = commitDownloadedRules(loaded, downloaded);
            if (outcome.baseline != null && (previous == null
                    || outcome.baseline.rules.getRevision() > previous.getRevision())) {
                currentRules = outcome.baseline.rules;
                currentDigest = outcome.baseline.digest;
                cacheAvailable = true;
            }
            RuleRevisionPolicy.Decision decision = outcome.decision;
            if (decision == RuleRevisionPolicy.Decision.REJECT_DOWNGRADE) {
                if (currentRules != previous) deliverRules(currentRules, true, 0L);
                throw new RevisionConflictException("远端规则修订号低于当前可信版本");
            } else if (decision == RuleRevisionPolicy.Decision.REVISION_CONFLICT) {
                if (currentRules != previous) deliverRules(currentRules, true, 0L);
                throw new RevisionConflictException("同 revision 的规则内容发生变化");
            } else if (decision == RuleRevisionPolicy.Decision.UNCHANGED) {
                if (currentRules != previous) deliverRules(currentRules, true, 0L);
                return;
            }
            if (closed.get()) return;
            currentRules = parsed;
            currentDigest = loaded.digest;
            if (previous == null || parsed.getRevision() > previous.getRevision()) {
                deliverRules(parsed, false, 0L);
            }
        } catch (RevisionConflictException error) {
            if (!hasPendingReplacement()) {
                deliverFailure(ProbeErrorCode.RULE_REVISION_CONFLICT, cacheAvailable, error, 0L);
            }
        } catch (IllegalArgumentException error) {
            if (!hasPendingReplacement()) {
                deliverFailure(ProbeErrorCode.RULE_PARSE_FAILED, cacheAvailable, error, 0L);
            }
        } catch (Exception error) {
            if (!hasPendingReplacement()) {
                deliverFailure(ProbeErrorCode.RULE_FETCH_FAILED, cacheAvailable, error, 0L);
            }
        } finally {
            activeConnection = null;
            if (downloaded != null && downloaded.exists()) downloaded.delete();
        }
    }

    private LoadedRules readCache() throws IOException {
        if (cache == null) return null;
        synchronized (cacheLock) {
            return readCacheLocked();
        }
    }

    private LoadedRules readCacheLocked() throws IOException {
        if (!cache.getBaseFile().isFile()
                && !new File(cache.getBaseFile().getPath() + ".bak").isFile()) return null;
        try (FileInputStream input = cache.openRead()) {
            long length = input.getChannel().size();
            if (length <= 0 || length > MAX_RULE_BYTES) {
                throw new IOException("缓存规则大小无效");
            }
            return parseInput(input);
        }
    }

    private File download() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(ruleUrl).openConnection();
        activeConnection = connection;
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "m3u8-ad-audio-probe/0.1");
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IOException("规则请求失败：HTTP " + status);
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
            throw new IOException("规则请求被重定向到非 HTTPS 地址");
        }
        long contentLength = connection.getContentLength();
        if (contentLength > MAX_RULE_BYTES) throw new IOException("远端规则超过 4 MiB");

        File output = File.createTempFile("ad-rules-", ".json", tempDirectory);
        boolean success = false;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             OutputStream target = new BufferedOutputStream(new FileOutputStream(output))) {
            copyBounded(input, target, TOTAL_DOWNLOAD_TIMEOUT_NS);
            success = true;
            return output;
        } finally {
            connection.disconnect();
            if (!success) output.delete();
        }
    }

    private LoadedRules parseFile(File file) throws IOException {
        long length = file.length();
        if (length <= 0L || length > MAX_RULE_BYTES) throw new IOException("下载规则大小无效");
        try (FileInputStream input = new FileInputStream(file)) {
            return parseInput(input);
        }
    }

    private LoadedRules parseInput(InputStream source) throws IOException {
        MessageDigest digest = sha256();
        try (Reader reader = openUtf8Reader(new DigestInputStream(source, digest))) {
            AdRuleSet parsed = RuleSetJsonParser.parse(reader);
            return new LoadedRules(parsed, toHex(digest.digest()));
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("规则文件不是严格 UTF-8", error);
        }
    }

    /** 同 URL 的多个实例在同一锁内重读 revision 并提交，避免共享 AtomicFile 回退。 */
    private CommitOutcome commitDownloadedRules(LoadedRules incoming, File source)
            throws IOException {
        synchronized (cacheLock) {
            LoadedRules disk;
            try {
                disk = readCacheLocked();
            } catch (IllegalArgumentException | IOException invalidCache) {
                // 已严格验证的网络版本可以修复损坏的私有缓存。
                disk = null;
            }
            LoadedRules memory = currentRules == null
                    ? null : new LoadedRules(currentRules, currentDigest);
            LoadedRules baseline = selectTrustedBaseline(memory, disk);
            RuleRevisionPolicy.Decision decision = RuleRevisionPolicy.evaluate(
                    baseline == null ? null : baseline.rules.getRevision(),
                    baseline == null ? null : baseline.digest,
                    incoming.rules.getRevision(), incoming.digest);
            if (decision == RuleRevisionPolicy.Decision.ACCEPT_INITIAL
                    || decision == RuleRevisionPolicy.Decision.ACCEPT_UPGRADE) {
                writeCacheLocked(source);
            }
            return new CommitOutcome(baseline, decision);
        }
    }

    private static LoadedRules selectTrustedBaseline(LoadedRules memory, LoadedRules disk)
            throws RevisionConflictException {
        if (memory == null) return disk;
        if (disk == null) return memory;
        long memoryRevision = memory.rules.getRevision();
        long diskRevision = disk.rules.getRevision();
        if (memoryRevision > diskRevision) return memory;
        if (diskRevision > memoryRevision) return disk;
        if (!memory.digest.equals(disk.digest)) {
            throw new RevisionConflictException("同 revision 的进程缓存内容不一致");
        }
        return memory;
    }

    private void writeCacheLocked(File source) throws IOException {
        FileOutputStream output = null;
        try (InputStream input = new BufferedInputStream(new FileInputStream(source))) {
            output = cache.startWrite();
            copyBounded(input, output, NO_TOTAL_TIMEOUT);
            cache.finishWrite(output);
            output = null;
        } finally {
            if (output != null) cache.failWrite(output);
        }
    }

    private static Reader openUtf8Reader(InputStream source) throws IOException {
        PushbackInputStream input = new PushbackInputStream(new BufferedInputStream(source), 3);
        byte[] prefix = new byte[3];
        int read = input.read(prefix);
        boolean bom = read == 3 && (prefix[0] & 0xff) == 0xef
                && (prefix[1] & 0xff) == 0xbb && (prefix[2] & 0xff) == 0xbf;
        if (!bom && read > 0) input.unread(prefix, 0, read);
        return new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }

    private static void copyBounded(InputStream input, OutputStream output,
                                    long timeoutNanos) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int zeroReads = 0;
        long startedNanos = System.nanoTime();
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("规则任务已取消");
            if (hasTimedOut(startedNanos, System.nanoTime(), timeoutNanos)) {
                throw new IOException("规则下载超过总时限");
            }
            int read = input.read(buffer);
            if (read < 0) break;
            if (read == 0) {
                if (++zeroReads > 8) throw new IOException("规则数据流无进展");
                continue;
            }
            zeroReads = 0;
            if (read > MAX_RULE_BYTES - total) throw new IOException("规则超过 4 MiB");
            output.write(buffer, 0, read);
            total += read;
        }
        if (total == 0) throw new IOException("规则内容为空");
        output.flush();
    }

    private void deliverRules(AdRuleSet rules, boolean fromCache, long replacementRequestId) {
        if (!closed.get() && listener != null) {
            listener.onRules(rules, fromCache, replacementRequestId);
        }
    }

    private void deliverFailure(ProbeErrorCode code, boolean cacheAvailable, Exception error,
                                long replacementRequestId) {
        if (!closed.get() && listener != null) {
            listener.onFailure(code, cacheAvailable, error, replacementRequestId);
        }
    }

    private void deliverReplacementSuperseded(long requestId) {
        if (!closed.get() && listener != null) listener.onReplacementSuperseded(requestId);
    }

    private boolean hasPendingReplacement() {
        return replacementWorkerScheduled.get() || pendingReplacement.get() != null;
    }

    private long nextReplacementId() {
        long value = replacementSequence.incrementAndGet();
        if (value > 0L) return value;
        synchronized (replacementSequence) {
            if (replacementSequence.get() <= 0L) replacementSequence.set(1L);
            return replacementSequence.getAndIncrement();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
        pendingReplacement.set(null);
        executor.shutdownNow();
    }

    static String validateRuleUrl(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("规则地址不能为空");
        }
        if (value.length() > MAX_URL_LENGTH || containsControlCharacter(value)) {
            throw new IllegalArgumentException("规则地址长度或字符无效");
        }
        String normalized = value.trim();
        URI uri = URI.create(normalized);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalArgumentException("规则地址必须是有效 HTTPS URL");
        }
        return uri.toString();
    }

    static Object cacheLockFor(String cachePath) {
        Object existing = CACHE_LOCKS.get(cachePath);
        if (existing != null) return existing;
        Object candidate = new Object();
        Object raced = CACHE_LOCKS.putIfAbsent(cachePath, candidate);
        return raced == null ? candidate : raced;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character <= 0x1f || character == 0x7f) return true;
        }
        return false;
    }

    private static String digest(String value) {
        return toHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IllegalStateException("当前运行环境不支持 SHA-256", error);
        }
    }

    static boolean tryExecute(Executor executor, Runnable task) {
        try {
            executor.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            return false;
        }
    }

    static boolean hasTimedOut(long startedNanos, long nowNanos, long timeoutNanos) {
        return timeoutNanos >= 0L && nowNanos - startedNanos >= timeoutNanos;
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            output[i * 2] = digits[value >>> 4];
            output[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    private static final class LoadedRules {
        final AdRuleSet rules;
        final String digest;

        LoadedRules(AdRuleSet rules, String digest) {
            this.rules = rules;
            this.digest = digest;
        }
    }

    private static final class ReplacementRequest {
        final long requestId;
        final byte[] rulesJson;

        ReplacementRequest(long requestId, byte[] rulesJson) {
            this.requestId = requestId;
            this.rulesJson = rulesJson;
        }
    }

    private static final class CommitOutcome {
        final LoadedRules baseline;
        final RuleRevisionPolicy.Decision decision;

        CommitOutcome(LoadedRules baseline, RuleRevisionPolicy.Decision decision) {
            this.baseline = baseline;
            this.decision = decision;
        }
    }

    private static final class RevisionConflictException extends IOException {
        RevisionConflictException(String message) {
            super(message);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "ad-audio-rule-loader");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
