/* 通用会话引擎把任意解码适配器输出统一接入匹配和防误跳状态机。 */
package io.github.fongmi.adaudio.probe.internal.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.ProbeState;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterRequest;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterState;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.adapter.internal.FiniteVodTimelineGate;
import io.github.fongmi.adaudio.probe.internal.core.AdAudioMatcher;
import io.github.fongmi.adaudio.probe.internal.core.AdRule;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;
import io.github.fongmi.adaudio.probe.internal.core.FeedResult;
import io.github.fongmi.adaudio.probe.internal.core.FingerprintVariant;
import io.github.fongmi.adaudio.probe.internal.core.MatcherConfig;
import io.github.fongmi.adaudio.probe.internal.core.PcmChunk;
import io.github.fongmi.adaudio.probe.internal.runtime.AdDispatchQueue.Claim;

import java.util.ArrayList;
import java.util.List;

/**
 * 适配器无关的媒体会话运行时。所有规则和跳转安全状态都由本类持有，适配器只提供 PCM。
 */
public final class ProbeSessionEngine implements AutoCloseable {
    private static final long MAX_PCM_OVERSHOOT_MS = 2000L;

    /** 将通用引擎事件交给 Android 门面。 */
    public interface Listener {
        void onState(long sessionId, ProbeState state, long analyzedThroughMs, long durationMs);
        void onAdReady(long sessionId, long ruleRevision, Claim claim,
                       long analyzedThroughMs);
        void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                     boolean retryable, String message, Throwable error);
    }

    private final Looper controlLooper;
    private final Handler controlHandler;
    private final Object lifecycleLock = new Object();
    private final long maxLookaheadMs;
    private final Listener listener;
    private final ProbeAdapter adapter;

    private volatile AnalysisContext analysis;
    private volatile long sessionId;
    private volatile long failedSessionId;
    private volatile long durationMs = -1L;
    private volatile boolean closed;

    public ProbeSessionEngine(Context context, Looper controlLooper, long maxLookaheadMs,
                              ProbeAdapterFactory factory, Listener listener) {
        if (context == null) throw new IllegalArgumentException("Application Context 不能为空");
        if (controlLooper == null) throw new IllegalArgumentException("控制 Looper 不能为空");
        if (listener == null) throw new IllegalArgumentException("会话监听器不能为空");
        validateFactory(factory);
        this.controlLooper = controlLooper;
        this.controlHandler = new Handler(controlLooper);
        this.maxLookaheadMs = maxLookaheadMs;
        this.listener = listener;
        ProbeAdapter created;
        try {
            created = factory.create(context.getApplicationContext(), controlLooper,
                    new AdapterListener());
        } catch (LinkageError error) {
            throw new IllegalArgumentException("适配器与当前 SPI 二进制不兼容", error);
        }
        if (created == null) throw new IllegalArgumentException("适配器工厂返回了 null");
        this.adapter = created;
    }

    /** 必须在控制 Looper 调用；新会话会先使全部旧 PCM 与 Claim 失效。 */
    public void open(long newSessionId, ProbeMedia media, AdRuleSet rules, long startPositionMs) {
        checkThread();
        if (newSessionId <= 0L) return;
        long safeStartPositionMs = Math.max(0L, startPositionMs);
        AnalysisContext previousAnalysis;
        long previousSessionId;
        synchronized (lifecycleLock) {
            if (closed) return;
            previousSessionId = sessionId;
            previousAnalysis = analysis;
            sessionId = newSessionId;
            failedSessionId = 0L;
            durationMs = -1L;
            analysis = new AnalysisContext(newSessionId, rules, safeStartPositionMs);
        }
        deactivate(previousAnalysis);
        if (previousSessionId > 0L) safeStopAdapter(previousSessionId);
        emitState(newSessionId, ProbeState.PREPARING);
        try {
            adapter.open(new ProbeAdapterRequest(newSessionId, media,
                    safeStartPositionMs, maxLookaheadMs));
        } catch (LinkageError error) {
            fail(newSessionId, ProbeErrorCode.INTERNAL, true,
                    false, "适配器与当前 SPI 二进制不兼容", error);
        } catch (RuntimeException error) {
            fail(newSessionId, ProbeErrorCode.INVALID_SOURCE, true,
                    false, "适配器无法创建媒体会话", error);
        }
    }

    public void updateHostPosition(long expectedSessionId, long positionMs) {
        checkThread();
        if (!isOperationalCurrent(expectedSessionId)) return;
        AnalysisContext current = analysis;
        long safePositionMs = Math.max(0L, positionMs);
        if (current != null) {
            synchronized (current) {
                if (!isAnalysisCurrent(current)) return;
                current.hostPositionMs = safePositionMs;
            }
            dispatchReadyAds(current, safePositionMs);
        }
        try {
            adapter.updateHostPosition(expectedSessionId, safePositionMs);
        } catch (LinkageError error) {
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, true,
                    false, "适配器与当前 SPI 二进制不兼容", error);
        } catch (RuntimeException error) {
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, true,
                    false, "适配器更新宿主时间轴失败", error);
        }
    }

    public void stop(long expectedSessionId) {
        checkThread();
        AnalysisContext previousAnalysis;
        synchronized (lifecycleLock) {
            if (!isMatchingStopSession(sessionId, expectedSessionId)) return;
            sessionId = 0L;
            failedSessionId = 0L;
            durationMs = -1L;
            previousAnalysis = analysis;
            analysis = null;
        }
        deactivate(previousAnalysis);
        safeStopAdapter(expectedSessionId);
    }

    /** 宿主最终时钟校验结束后确认消费，或释放占用等待下一次轮询。 */
    public void resolveAd(long expectedSessionId, Claim claim, boolean consumed) {
        checkThread();
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            if (consumed) current.dispatchQueue.ack(claim);
            else current.dispatchQueue.release(claim);
        }
    }

    /** 可由宿主回调线程调用；晚到冲突、reset 或媒体切换都会使 token 失效。 */
    public boolean isAdClaimValid(long expectedSessionId, Claim claim) {
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return false;
        synchronized (current) {
            return isAnalysisCurrent(current) && current.dispatchQueue.isClaimValid(claim);
        }
    }

    /** 在真正调用宿主前原子提交 token，提交失败时不得产生跳转回调。 */
    public boolean commitAdClaim(long expectedSessionId, Claim claim) {
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId || claim == null) return false;
        synchronized (current) {
            return isAnalysisCurrent(current) && current.dispatchQueue.ack(claim);
        }
    }

    @Override
    public void close() {
        checkThread();
        AnalysisContext previousAnalysis;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            sessionId = 0L;
            failedSessionId = 0L;
            durationMs = -1L;
            previousAnalysis = analysis;
            analysis = null;
        }
        deactivate(previousAnalysis);
        try {
            adapter.close();
        } catch (RuntimeException | LinkageError ignored) {
            // close 必须保持幂等且 fail-open，宿主资源释放不应被第三方适配器阻断。
        }
    }

    private void onPcm(long expectedSessionId, ProbePcmFrame frame) {
        if (frame == null) {
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, false,
                    true, "适配器提交了空 PCM", null);
            return;
        }
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId) return;
        if (!current.timelineGate.isVodConfirmed()) return;
        long startMs = frame.getPresentationTimeUs() / 1000L;
        long endMs = frame.getEndPositionUs() / 1000L;
        FeedResult result;
        boolean beyondLookahead;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            long allowedStartMs = safeAdd(current.hostPositionMs, maxLookaheadMs);
            long allowedEndMs = safeAdd(allowedStartMs, MAX_PCM_OVERSHOOT_MS);
            beyondLookahead = startMs > allowedStartMs || endMs > allowedEndMs;
            if (beyondLookahead) {
                result = null;
            } else {
                result = current.matcher.feed(new PcmChunk(frame.getSamples(),
                        frame.getSampleRateHz(), frame.getChannelCount(), startMs));
                boolean timelineReset = current.resetWatermarkOnNextPcm
                        || result.isTimelineReset();
                if (timelineReset) {
                    current.dispatchQueue.reset();
                    current.coordinator.reset();
                }
                List<ConfirmedAd> confirmed = new ArrayList<>();
                confirmed.addAll(current.coordinator.onMatches(result.getEvents()));
                confirmed.addAll(current.coordinator.onAnalyzedThrough(endMs));
                current.analyzedThroughMs = advanceAnalyzedThrough(
                        current.analyzedThroughMs, endMs, timelineReset);
                current.receivedPcm = true;
                current.resetWatermarkOnNextPcm = false;
                current.dispatchQueue.addAll(confirmed);
            }
        }
        if (beyondLookahead) {
            fail(expectedSessionId, ProbeErrorCode.TIMELINE_UNRELIABLE, true,
                    false, "适配器提交的 PCM 超出允许前视范围", null);
            return;
        }
        emitState(expectedSessionId, ProbeState.ANALYZING);
        dispatchReadyAds(current, currentHostPosition(current));
        if (result.getStatus() == FeedResult.Status.INTERNAL_ERROR) {
            fail(expectedSessionId, ProbeErrorCode.INTERNAL, false,
                    true, "音频指纹匹配器已安全重置", null);
        }
    }

    private void onTimelineReset(long expectedSessionId, long positionMs) {
        AnalysisContext current = analysis;
        if (current == null || current.sessionId != expectedSessionId) return;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            current.receivedPcm = false;
            current.analyzedThroughMs = Math.max(0L, positionMs);
            current.resetWatermarkOnNextPcm = true;
            current.dispatchQueue.reset();
            current.coordinator.reset();
            current.matcher.reset();
        }
    }

    private void dispatchReadyAds(AnalysisContext current, long hostPositionMs) {
        List<Claim> ready;
        long analyzedThrough;
        synchronized (current) {
            if (!isAnalysisCurrent(current)) return;
            ready = current.dispatchQueue.claim(hostPositionMs, durationMs);
            analyzedThrough = current.analyzedThroughMs;
        }
        for (Claim claim : ready) {
            if (!isAnalysisCurrent(current)) return;
            listener.onAdReady(current.sessionId, current.ruleRevision, claim, analyzedThrough);
        }
    }

    private void emitState(long expectedSessionId, ProbeState state) {
        if (!isOperationalCurrent(expectedSessionId)) return;
        AnalysisContext current = analysis;
        long analyzedThrough = current == null ? 0L : currentAnalyzedThrough(current);
        listener.onState(expectedSessionId, state, analyzedThrough, durationMs);
    }

    private void fail(long expectedSessionId, ProbeErrorCode code, boolean fatal,
                      boolean retryable, String message, Throwable error) {
        if (!fatal) {
            if (!isOperationalCurrent(expectedSessionId)) return;
            listener.onError(expectedSessionId, code, false, retryable, message, error);
            return;
        }
        AnalysisContext previousAnalysis;
        synchronized (lifecycleLock) {
            if (!isOperationalCurrent(expectedSessionId)) return;
            failedSessionId = expectedSessionId;
            previousAnalysis = analysis;
            analysis = null;
        }
        deactivate(previousAnalysis);
        listener.onError(expectedSessionId, code, fatal, retryable, message, error);
        controlHandler.post(() -> stopFailedAdapter(expectedSessionId));
    }

    private void deactivate(AnalysisContext current) {
        if (current == null) return;
        synchronized (current) {
            current.active = false;
            current.dispatchQueue.reset();
            current.coordinator.reset();
            current.matcher.reset();
        }
    }

    private void safeStopAdapter(long expectedSessionId) {
        try {
            adapter.stop(expectedSessionId);
        } catch (RuntimeException | LinkageError error) {
            if (isOperationalCurrent(expectedSessionId)) {
                listener.onError(expectedSessionId, ProbeErrorCode.INTERNAL, false,
                        false, "适配器停止会话失败", error);
            }
        }
    }

    private void stopFailedAdapter(long expectedSessionId) {
        checkThread();
        if (closed || sessionId != expectedSessionId || failedSessionId != expectedSessionId) return;
        safeStopAdapter(expectedSessionId);
    }

    private boolean isCurrent(long expectedSessionId) {
        return !closed && expectedSessionId > 0L && expectedSessionId == sessionId;
    }

    private boolean isOperationalCurrent(long expectedSessionId) {
        return isCurrent(expectedSessionId) && failedSessionId != expectedSessionId;
    }

    private boolean isAnalysisCurrent(AnalysisContext current) {
        return current != null && current.active && analysis == current
                && isOperationalCurrent(current.sessionId);
    }

    private long currentAnalyzedThrough(AnalysisContext current) {
        synchronized (current) {
            return isAnalysisCurrent(current) ? current.analyzedThroughMs : 0L;
        }
    }

    private long currentHostPosition(AnalysisContext current) {
        synchronized (current) {
            return isAnalysisCurrent(current) ? current.hostPositionMs : 0L;
        }
    }

    private void checkThread() {
        if (Looper.myLooper() != controlLooper) {
            throw new IllegalStateException("探针会话引擎必须在控制 Looper 调用");
        }
    }

    private static void validateFactory(ProbeAdapterFactory factory) {
        if (factory == null) throw new IllegalArgumentException("适配器工厂不能为空");
        final int spiVersion;
        final String id;
        try {
            spiVersion = factory.getSpiVersion();
            id = factory.getId();
        } catch (LinkageError error) {
            throw new IllegalArgumentException("适配器与当前 SPI 二进制不兼容", error);
        }
        if (spiVersion != ProbeAdapterFactory.SPI_VERSION) {
            throw new IllegalArgumentException("适配器 SPI 版本不兼容");
        }
        if (id == null || id.isEmpty() || id.length() > 128) {
            throw new IllegalArgumentException("适配器 ID 无效");
        }
        for (int i = 0; i < id.length(); i++) {
            char value = id.charAt(i);
            if (value < 0x21 || value > 0x7e) {
                throw new IllegalArgumentException("适配器 ID 必须是可见 ASCII");
            }
        }
    }

    static long advanceAnalyzedThrough(long previousMs, long endPositionMs,
                                       boolean timelineReset) {
        long safeEnd = Math.max(0L, endPositionMs);
        return timelineReset ? safeEnd : Math.max(previousMs, safeEnd);
    }

    static boolean isMatchingStopSession(long currentSessionId, long expectedSessionId) {
        return expectedSessionId > 0L && expectedSessionId == currentSessionId;
    }

    static ProbeErrorCode normalizeAdapterErrorCode(ProbeErrorCode code) {
        if (code == null) return ProbeErrorCode.INTERNAL;
        switch (code) {
            case INVALID_SOURCE:
            case UNSUPPORTED_SOURCE:
            case LIVE_STREAM_NOT_SUPPORTED:
            case DRM_NOT_SUPPORTED:
            case SOURCE_IO:
            case NO_AUDIO_TRACK:
            case UNSUPPORTED_AUDIO:
            case DECODER_FAILED:
            case TIMELINE_UNRELIABLE:
            case RESOURCE_EXHAUSTED:
            case INTERNAL:
                return code;
            default:
                return ProbeErrorCode.INTERNAL;
        }
    }

    private static long safeAdd(long left, long right) {
        if (left < 0L) left = 0L;
        if (right < 0L) right = 0L;
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private final class AdapterListener implements ProbeAdapter.Listener {
        @Override
        public void onPcm(long sessionId, ProbePcmFrame frame) {
            ProbeSessionEngine.this.onPcm(sessionId, frame);
        }

        @Override
        public void onTimelineReset(long sessionId, long positionMs) {
            ProbeSessionEngine.this.onTimelineReset(sessionId, Math.max(0L, positionMs));
        }

        @Override
        public void onTimeline(long sessionId, long newDurationMs,
                               boolean live, boolean dynamic) {
            AnalysisContext current = analysis;
            if (current == null || current.sessionId != sessionId
                    || !isOperationalCurrent(sessionId)) return;
            FiniteVodTimelineGate.Decision decision;
            synchronized (current) {
                if (!isAnalysisCurrent(current)) return;
                decision = current.timelineGate.update(newDurationMs, live, dynamic);
            }
            if (decision == FiniteVodTimelineGate.Decision.UNSUPPORTED) {
                fail(sessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED, true,
                        false, "仅支持有限时长的普通点播", null);
                return;
            }
            synchronized (lifecycleLock) {
                if (!isOperationalCurrent(sessionId)) return;
                durationMs = Math.max(-1L, newDurationMs);
            }
            if (decision == FiniteVodTimelineGate.Decision.VOD_CONFIRMED) {
                emitState(sessionId, ProbeState.ANALYZING);
            }
        }

        @Override
        public void onState(long sessionId, ProbeAdapterState state) {
            if (state == null || !isOperationalCurrent(sessionId)) return;
            switch (state) {
                case PREPARING:
                    emitState(sessionId, ProbeState.PREPARING);
                    break;
                case DECODING:
                    AnalysisContext current = analysis;
                    if (current == null || current.sessionId != sessionId) return;
                    FiniteVodTimelineGate.Decision decision =
                            current.timelineGate.markReady();
                    if (decision == FiniteVodTimelineGate.Decision.UNSUPPORTED) {
                        fail(sessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED, true,
                                false, "仅支持有限时长的普通点播", null);
                        return;
                    }
                    if (decision == FiniteVodTimelineGate.Decision.PENDING) return;
                    emitState(sessionId, ProbeState.ANALYZING);
                    break;
                case LOOKAHEAD_READY:
                    emitState(sessionId, ProbeState.LOOKAHEAD_READY);
                    break;
                case ENDED:
                    emitState(sessionId, ProbeState.ENDED);
                    break;
                default:
                    throw new AssertionError("未知适配器状态");
            }
        }

        @Override
        public void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                            boolean retryable, String message, Throwable cause) {
            ProbeErrorCode safeCode = normalizeAdapterErrorCode(code);
            String safeMessage = message == null || message.trim().isEmpty()
                    ? "适配器报告了未说明错误" : message;
            fail(sessionId, safeCode, fatal, retryable, safeMessage, cause);
        }
    }

    /** 每个媒体代际独占匹配状态，旧适配器回调无法写入新会话。 */
    private static final class AnalysisContext {
        final long sessionId;
        final long ruleRevision;
        final AdAudioMatcher matcher;
        final DetectionCoordinator coordinator;
        final AdDispatchQueue dispatchQueue = new AdDispatchQueue();
        final FiniteVodTimelineGate timelineGate = new FiniteVodTimelineGate();
        long analyzedThroughMs;
        boolean receivedPcm;
        boolean resetWatermarkOnNextPcm;
        boolean active = true;
        long hostPositionMs;

        AnalysisContext(long sessionId, AdRuleSet rules, long startPositionMs) {
            this.sessionId = sessionId;
            this.ruleRevision = rules.getRevision();
            this.matcher = new AdAudioMatcher(rules, MatcherConfig.releaseSafe());
            this.coordinator = DetectionCoordinator.fullMatchOnly(
                    maxFingerprintFrames(rules), rules.getHopMs());
            this.analyzedThroughMs = startPositionMs;
            this.hostPositionMs = startPositionMs;
        }

        private static int maxFingerprintFrames(AdRuleSet rules) {
            int max = AdRuleSet.MIN_CONFIRMATION_FRAMES;
            for (AdRule rule : rules.getRules()) {
                for (FingerprintVariant variant : rule.getFingerprints()) {
                    max = Math.max(max, variant.getHashes().size());
                }
            }
            return max;
        }
    }
}
