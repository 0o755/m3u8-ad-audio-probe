/* Android 门面管理规则、无头播放器、宿主时间轴和跳转回调的完整生命周期。 */
package io.github.fongmi.adaudio.probe;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.internal.core.AdRuleSet;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.fongmi.adaudio.probe.internal.rules.AtomicRuleRepository;
import io.github.fongmi.adaudio.probe.internal.rules.RuleSetJsonParser;
import io.github.fongmi.adaudio.probe.internal.rules.RuleSetSelection;
import io.github.fongmi.adaudio.probe.internal.runtime.AdDispatchQueue.Claim;
import io.github.fongmi.adaudio.probe.internal.runtime.ConfirmedAd;
import io.github.fongmi.adaudio.probe.internal.runtime.CallbackGate;
import io.github.fongmi.adaudio.probe.internal.runtime.ProbeAdapterResolver;
import io.github.fongmi.adaudio.probe.internal.runtime.ProbeSessionEngine;
import io.github.fongmi.adaudio.probe.internal.runtime.SerialExecutor;

/**
 * 普通 HLS/MP4 点播的音频广告探针。
 * 公开方法线程安全且不执行网络或解码 I/O；生命周期切换会与正在执行的宿主回调串行。
 */
public final class AdAudioProbe implements Closeable {
    private static final long POLL_INTERVAL_MS = 100L;
    private static final long MIN_LOOKAHEAD_MS = 3000L;
    private static final long MAX_LOOKAHEAD_MS = 60_000L;

    private final Object stateLock = new Object();
    private final PlaybackClock playbackClock;
    private final ProbeListener listener;
    private final SerialExecutor hostExecutor;
    private final HandlerThread engineThread;
    private final Handler engineHandler;
    private final AtomicRuleRepository ruleRepository;
    private final ProbeSessionEngine engine;
    private final AtomicLong hostPositionMs = new AtomicLong();
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicBoolean clockPollInFlight = new AtomicBoolean();
    private final AtomicBoolean clockPollScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CallbackGate callbackGate = new CallbackGate();
    private final Runnable pollTask = this::pollHostPosition;

    private volatile ProbeStatus status = ProbeStatus.idle();
    private volatile ProbeStatus lastNotifiedStatus;
    private volatile AdRuleSet rules;
    private AdRuleSet allRules;
    private String testRuleId;
    private volatile boolean enabled = true;
    private volatile long activeSessionId;
    private ProbeMedia activeMedia;
    private boolean initialPositionKnown;
    private boolean engineStarted;
    private boolean clockErrorReported;

    private AdAudioProbe(Builder builder) {
        playbackClock = requireClock(builder.playbackClock);
        listener = requireListener(builder.listener);
        hostExecutor = new SerialExecutor(builder.callbackExecutor == null
                ? mainThreadExecutor() : builder.callbackExecutor);
        AtomicRuleRepository repository = new AtomicRuleRepository(
                builder.context, builder.ruleUrl, new RuleListener());
        HandlerThread thread = new HandlerThread("ad-audio-probe");
        Handler handler;
        ProbeSessionEngine sessionEngine;
        try {
            thread.start();
            handler = new Handler(thread.getLooper());
            ProbeAdapterFactory factory = ProbeAdapterResolver.resolve(builder.adapterFactory);
            sessionEngine = new ProbeSessionEngine(builder.context.getApplicationContext(),
                    thread.getLooper(), builder.maxLookaheadMs, factory, new EngineListener());
        } catch (RuntimeException | LinkageError error) {
            repository.close();
            thread.quitSafely();
            throw error;
        }
        ruleRepository = repository;
        engineThread = thread;
        engineHandler = handler;
        engine = sessionEngine;
        if (builder.initialRules != null) ruleRepository.replace(builder.initialRules);
        if (ruleRepository.hasRemoteSource()) ruleRepository.refresh();
    }

    /**
     * 创建探针并异步加载规则。监听器通常直接调用宿主 {@code player.seekTo}。
     * 默认在 Android 主线程读取时钟并派发回调。
     */
    public static AdAudioProbe create(Context context, String ruleUrl,
                                      PlaybackClock playbackClock,
                                      ProbeListener listener) {
        return builder(context, ruleUrl)
                .setPlaybackClock(playbackClock)
                .setListener(listener)
                .build();
    }

    /** 创建高级配置入口；规则地址必须是有效 HTTPS URL。 */
    public static Builder builder(Context context, String ruleUrl) {
        return new Builder(context, ruleUrl);
    }

    /** 创建仅使用本地规则的高级配置入口；规则可在构建时或运行时注入。 */
    public static Builder builder(Context context) {
        return new Builder(context, null);
    }

    /**
     * 原子替换当前媒体并返回新会话 ID。宿主应在选择同一媒体源后立即调用。
     * 探针停用或已经关闭时会抛出 {@link IllegalStateException}。
     */
    public long open(String mediaUrl) {
        return open(ProbeMedia.from(mediaUrl));
    }

    /**
     * 原子替换当前媒体请求；请求头和显式媒体类型由 {@link ProbeMedia} 提供。
     * 返回值会出现在该媒体后续的状态、错误和跳转请求中。
     */
    public long open(ProbeMedia media) {
        if (media == null) throw new IllegalArgumentException("媒体请求不能为空");
        SessionStart start = callbackGate.update(() -> {
            SessionStart result;
            synchronized (stateLock) {
                ensureOpenLocked();
                if (!enabled) throw new IllegalStateException("探针已停用，请先重新启用");
                long previousSessionId = activeSessionId;
                long sessionId = beginSessionLocked(media, false, hostPositionMs.get());
                ProbeStatus next = new ProbeStatus(ProbeState.PREPARING, sessionId,
                        media.getId(), hostPositionMs.get(), hostPositionMs.get(),
                        ruleRevision(), ruleCount(), null);
                result = new SessionStart(previousSessionId, sessionId, next);
            }
            stopSessionWithinGate(result.previousSessionId);
            return result;
        });
        updateStatus(start.status, true);
        requestHostPosition(start.sessionId);
        schedulePolling();
        if (rules == null && ruleRepository.hasRemoteSource()) ruleRepository.refresh();
        return start.sessionId;
    }

    /**
     * 宿主主动拖动、跳转或重建时间轴后调用，位置单位为毫秒。
     * 新代际会让所有旧检测结果和排队回调失效。
     */
    public void notifyHostDiscontinuity(long positionMs) {
        if (positionMs < 0L) throw new IllegalArgumentException("宿主位置不能为负数");
        SessionStart start = callbackGate.update(() -> {
            SessionStart result;
            synchronized (stateLock) {
                ensureOpenLocked();
                ProbeMedia media = activeMedia;
                if (media == null || activeSessionId == 0L) return null;
                long previousSessionId = activeSessionId;
                long sessionId = beginSessionLocked(media, true, positionMs);
                ProbeStatus next = new ProbeStatus(ProbeState.PREPARING, sessionId,
                        media.getId(), positionMs, positionMs,
                        ruleRevision(), ruleCount(), null);
                result = new SessionStart(previousSessionId, sessionId, next);
            }
            stopSessionWithinGate(result.previousSessionId);
            return result;
        });
        if (start == null) return;
        updateStatus(start.status, true);
        final long sessionId = start.sessionId;
        engineHandler.post(() -> startEngineIfReady(sessionId));
        schedulePolling();
    }

    /**
     * 启用或停用探针。停用会释放当前分析；重新启用会为最近媒体建立新会话。
     * 停用期间调用 {@link #open(String)} 会失败。
     */
    public void setEnabled(boolean enabled) {
        EnableTransition transition = callbackGate.update(() -> {
            EnableTransition result;
            synchronized (stateLock) {
                ensureOpenLocked();
                if (this.enabled == enabled) return null;
                this.enabled = enabled;
                if (!enabled) {
                    long previous = activeSessionId;
                    activeSessionId = 0L;
                    engineStarted = false;
                    initialPositionKnown = false;
                    result = EnableTransition.disabled(previous);
                } else if (activeMedia == null) {
                    result = EnableTransition.enabledWithoutMedia();
                } else {
                    long resumedSession = beginSessionLocked(
                            activeMedia, false, hostPositionMs.get());
                    ProbeStatus next = new ProbeStatus(ProbeState.PREPARING, resumedSession,
                            activeMedia.getId(), hostPositionMs.get(), hostPositionMs.get(),
                            ruleRevision(), ruleCount(), null);
                    result = EnableTransition.resumed(resumedSession, next);
                }
            }
            if (!enabled) stopSessionWithinGate(result.previousSessionId);
            return result;
        });
        if (transition == null) return;
        if (!enabled) {
            updateStatus(idleStatus(), true);
            return;
        }
        if (transition.status != null) {
            updateStatus(transition.status, true);
            requestHostPosition(transition.resumedSessionId);
            schedulePolling();
        }
    }

    /** 返回实例是否仍处于启用状态；关闭后始终返回 {@code false}。 */
    public boolean isEnabled() {
        return enabled && !closed.get();
    }

    /** 异步刷新规则；只接受更高 revision，不会降级可信缓存。 */
    public void refreshRules() {
        synchronized (stateLock) {
            ensureOpenLocked();
            if (!ruleRepository.hasRemoteSource()) {
                throw new IllegalStateException("未配置远程规则地址");
            }
        }
        ruleRepository.refresh();
    }

    /**
     * 后台严格解析并原子替换本地 rules-v1。提交时会复制输入；解析失败保留旧规则，
     * 并通过 {@link ProbeListener#onError(ProbeError)} 返回结构化错误。无论成功、失败或
     * 被更新请求覆盖，都会通过规则替换终态回调结束一次。
     *
     * @return 正数请求 ID；对应唯一终态通过
     * {@link ProbeListener#onRulesReplaced(RuleReplacementResult)} 返回
     */
    public long replaceRules(byte[] rulesJson) {
        return callbackGate.update(() -> {
            synchronized (stateLock) {
                ensureOpenLocked();
            }
            return ruleRepository.replace(rulesJson);
        });
    }

    /**
     * 与 {@link #replaceRules(byte[])} 相同，但接收 Java 字符串并编码为严格 UTF-8。
     *
     * @return 可精确关联替换终态的正数请求 ID
     */
    public long replaceRulesJson(String rulesJson) {
        return replaceRules(RuleSetJsonParser.encodeDocument(rulesJson));
    }

    /**
     * 只启用指定规则进行测试，并使旧会话及其待派发跳转立即失效。
     *
     * @return 重建后的会话 ID；当前尚未打开媒体时返回 {@code 0}
     * @throws IllegalStateException 规则尚未加载或探针已经关闭
     * @throws IllegalArgumentException 规则 ID 为空或不存在
     */
    public long useRuleForTesting(String ruleId) {
        RuleTransition transition = callbackGate.update(() -> {
            RuleTransition result;
            synchronized (stateLock) {
                ensureOpenLocked();
                AdRuleSet selected = RuleSetSelection.select(allRules, ruleId);
                testRuleId = selected.getRules().get(0).getId();
                result = createRuleTransitionLocked(selected, true);
            }
            stopSessionWithinGate(result.previousSessionId);
            return result;
        });
        applyRuleTransition(transition);
        return transition.sessionId;
    }

    /**
     * 退出单规则测试并恢复当前全量规则，同时使旧会话及其待派发跳转失效。
     *
     * @return 重建后的会话 ID；当前尚未打开媒体时返回 {@code 0}
     * @throws IllegalStateException 规则尚未加载或探针已经关闭
     */
    public long useAllRules() {
        RuleTransition transition = callbackGate.update(() -> {
            RuleTransition result;
            synchronized (stateLock) {
                ensureOpenLocked();
                if (allRules == null) throw new IllegalStateException("广告规则尚未加载");
                testRuleId = null;
                result = createRuleTransitionLocked(allRules, true);
            }
            stopSessionWithinGate(result.previousSessionId);
            return result;
        });
        applyRuleTransition(transition);
        return transition.sessionId;
    }

    /** 停止当前探针会话，但保留规则缓存和实例供后续 open。 */
    public void stop() {
        Long previous = callbackGate.update(() -> {
            long sessionId;
            synchronized (stateLock) {
                if (closed.get()) return null;
                sessionId = activeSessionId;
                activeSessionId = 0L;
                activeMedia = null;
                engineStarted = false;
                initialPositionKnown = false;
            }
            stopSessionWithinGate(sessionId);
            return sessionId;
        });
        if (previous == null) return;
        updateStatus(idleStatus(), true);
    }

    /** 返回最近一次不可变状态快照，不触发宿主时钟读取。 */
    public ProbeStatus getStatus() {
        return status;
    }

    /**
     * 永久关闭实例并禁止新回调。若宿主回调正在执行，本方法会等其返回后再完成关闭。
     * 内部适配器资源随后在专用 Looper 释放；重复调用无副作用。
     */
    @Override
    public void close() {
        boolean changed = callbackGate.update(() -> {
            synchronized (stateLock) {
                if (!closed.compareAndSet(false, true)) return false;
                enabled = false;
                activeSessionId = 0L;
                activeMedia = null;
                engineStarted = false;
                return true;
            }
        });
        if (!changed) return;
        ruleRepository.close();
        stopPolling();
        engineHandler.removeCallbacksAndMessages(null);
        engineHandler.post(() -> {
            engine.close();
            engineThread.quitSafely();
        });
        status = new ProbeStatus(ProbeState.CLOSED, 0L, "", hostPositionMs.get(),
                hostPositionMs.get(), ruleRevision(), ruleCount(), null);
    }

    private void pollHostPosition() {
        clockPollScheduled.set(false);
        if (closed.get()) return;
        long sessionId = activeSessionId;
        if (enabled && sessionId > 0L) requestHostPosition(sessionId);
        schedulePolling();
    }

    private void schedulePolling() {
        long sessionId = activeSessionId;
        if (!isActive(sessionId) || !clockPollScheduled.compareAndSet(false, true)) return;
        if (!engineHandler.postDelayed(pollTask, POLL_INTERVAL_MS)) {
            clockPollScheduled.set(false);
        }
    }

    private void stopPolling() {
        engineHandler.removeCallbacks(pollTask);
        clockPollScheduled.set(false);
    }

    /** 调用方必须持有 callbackGate，使 teardown 先于后续 open/enable 的启动动作。 */
    private void stopSessionWithinGate(long sessionId) {
        stopPolling();
        if (sessionId > 0L) engineHandler.post(() -> engine.stop(sessionId));
    }

    private void requestHostPosition(long sessionId) {
        if (!clockPollInFlight.compareAndSet(false, true)) return;
        hostExecutor.tryExecute(() -> {
            try {
                Long position = callbackGate.update(() -> {
                    if (!isActive(sessionId)) return null;
                    long value = playbackClock.getCurrentPositionMs();
                    if (value < 0L) throw new IllegalStateException("宿主返回了负时间轴");
                    return value;
                });
                if (position == null) {
                    clockPollInFlight.set(false);
                    return;
                }
                hostPositionMs.set(position);
                engineHandler.post(() -> acceptHostPosition(sessionId, position));
            } catch (RuntimeException error) {
                clockPollInFlight.set(false);
                reportClockFailure(sessionId, error);
            }
        }, rejected -> {
            clockPollInFlight.set(false);
            reportClockFailure(sessionId, rejected);
        });
    }

    private void acceptHostPosition(long sessionId, long positionMs) {
        clockPollInFlight.set(false);
        if (!isActive(sessionId)) return;
        boolean shouldStart;
        ProbeStatus recovered = null;
        synchronized (stateLock) {
            if (!isActive(sessionId)) return;
            initialPositionKnown = true;
            shouldStart = !engineStarted;
            if (clockErrorReported) {
                clockErrorReported = false;
                ProbeStatus current = status;
                ProbeError error = current.getLastError();
                if (error != null && error.getCode() == ProbeErrorCode.TIMELINE_UNRELIABLE) {
                    recovered = new ProbeStatus(current.getState(), sessionId,
                            current.getMediaId(), positionMs,
                            current.getAnalyzedThroughPositionMs(), ruleRevision(),
                            ruleCount(), null);
                }
            }
        }
        if (recovered != null) updateStatus(recovered, true);
        if (shouldStart) startEngineIfReady(sessionId);
        else engine.updateHostPosition(sessionId, positionMs);
    }

    private void startEngineIfReady(long sessionId) {
        if (!isActive(sessionId)) return;
        ProbeMedia media;
        AdRuleSet currentRules = rules;
        synchronized (stateLock) {
            if (!isActive(sessionId) || engineStarted || !initialPositionKnown
                    || currentRules == null || currentRules != rules) return;
            media = activeMedia;
            if (media == null) return;
            engineStarted = true;
        }
        engine.open(sessionId, media, currentRules, hostPositionMs.get());
    }

    private void dispatchSkip(long sessionId, long revision, Claim claim,
                              long analyzedThroughMs) {
        ConfirmedAd ad = claim.getAd();
        AdRuleSet snapshot = rules;
        if (snapshot == null || snapshot.getRevision() != revision) {
            resolveAd(sessionId, claim, true);
            return;
        }
        long requestId = requestSequence.incrementAndGet();
        hostExecutor.tryExecute(() -> {
            boolean invoked = callbackGate.invokeIf(
                    () -> canDispatchSkip(sessionId, revision)
                            && engine.isAdClaimValid(sessionId, claim),
                    () -> performSkipCallback(requestId, sessionId, revision,
                            claim, analyzedThroughMs));
            if (!invoked) resolveAd(sessionId, claim, true);
        }, error -> {
            resolveAd(sessionId, claim, true);
            reportError(ProbeErrorCode.INTERNAL, sessionId, false,
                    false, "宿主 Executor 拒绝跳转回调", error);
        });
    }

    /** 在回调门闩内完成最终时钟校验，生命周期切换无法穿过宿主 seek 回调。 */
    private void performSkipCallback(long requestId, long sessionId, long revision,
                                     Claim claim, long analyzedThroughMs) {
        ConfirmedAd ad = claim.getAd();
        long currentPosition;
        try {
            currentPosition = playbackClock.getCurrentPositionMs();
        } catch (RuntimeException error) {
            resolveAd(sessionId, claim, true);
            reportClockFailure(sessionId, error);
            return;
        }
        if (currentPosition < ad.getStartTimeMs()) {
            resolveAd(sessionId, claim, false);
            return;
        }
        if (currentPosition >= ad.getEndTimeMs()) {
            resolveAd(sessionId, claim, true);
            return;
        }
        hostPositionMs.set(currentPosition);
        ProbeMedia media;
        synchronized (stateLock) {
            media = activeMedia;
        }
        if (media == null || !canDispatchSkip(sessionId, revision)) {
            resolveAd(sessionId, claim, true);
            return;
        }
        SkipRequest request = new SkipRequest(requestId, sessionId, media.getId(),
                ad.getRuleId(), revision, ad.getStartTimeMs(), ad.getEndTimeMs(),
                ad.getEndTimeMs(), currentPosition, analyzedThroughMs,
                ad.getMatchSimilarity());
        // 该提交与晚到冲突线性化；只有仍有效的 token 才能进入宿主回调。
        if (!engine.commitAdClaim(sessionId, claim)) return;
        try {
            listener.onSkipRequested(request);
        } catch (RuntimeException error) {
            reportError(ProbeErrorCode.INTERNAL, sessionId, false,
                    false, "宿主跳转回调执行失败", error);
        }
    }

    private boolean canDispatchSkip(long sessionId, long revision) {
        AdRuleSet snapshot = rules;
        if (!isActive(sessionId) || snapshot == null || snapshot.getRevision() != revision) {
            return false;
        }
        synchronized (stateLock) {
            return status.getSessionId() == sessionId
                    && status.getState() != ProbeState.FAILED
                    && activeMedia != null;
        }
    }

    private void resolveAd(long sessionId, Claim claim, boolean consumed) {
        engineHandler.post(() -> engine.resolveAd(sessionId, claim, consumed));
    }

    private void reportClockFailure(long sessionId, RuntimeException error) {
        synchronized (stateLock) {
            if (clockErrorReported || !isActive(sessionId)) return;
            clockErrorReported = true;
        }
        reportError(ProbeErrorCode.TIMELINE_UNRELIABLE, sessionId, false,
                true, "无法读取宿主播放器时间轴", error);
    }

    private ProbeError reportError(ProbeErrorCode code, long sessionId, boolean fatal,
                                   boolean retryable, String message, Throwable cause) {
        ErrorTransition transition;
        if (fatal) {
            transition = callbackGate.update(() -> {
                ErrorTransition committed = commitError(code, sessionId, true,
                        retryable, message, cause);
                if (committed != null) stopPolling();
                return committed;
            });
        } else {
            transition = commitError(code, sessionId, false,
                    retryable, message, cause);
        }
        if (transition == null) return null;

        dispatchStatusCallback(transition.status, transition.statusChanged);
        ProbeError error = transition.error;
        // 状态和错误通知不占用 fatal 的线性化临界区。
        executeHostCallback(() -> {
            synchronized (stateLock) {
                if (!isSessionCurrentLocked(sessionId)
                        || status.getLastError() != error) return;
            }
            listener.onError(error);
        });
        return error;
    }

    /** 调用方决定是否持有 callbackGate；这里只在 stateLock 内提交不可变状态。 */
    private ErrorTransition commitError(ProbeErrorCode code, long sessionId, boolean fatal,
                                        boolean retryable, String message, Throwable cause) {
        synchronized (stateLock) {
            if (!isSessionCurrentLocked(sessionId)) return null;
            ProbeError error = new ProbeError(
                    code, sessionId, fatal, retryable, message, cause);
            ProbeStatus current = status;
            ProbeState state = fatal ? ProbeState.FAILED : current.getState();
            ProbeStatus next = new ProbeStatus(state, sessionId, current.getMediaId(),
                    current.getHostPositionMs(), current.getAnalyzedThroughPositionMs(),
                    ruleRevision(), ruleCount(), error);
            boolean changed = setStatusLocked(next, true);
            return new ErrorTransition(error, next, changed);
        }
    }

    private void updateStatus(ProbeStatus next, boolean forceNotify) {
        boolean changed;
        synchronized (stateLock) {
            if (!isSessionCurrentLocked(next.getSessionId())) return;
            changed = setStatusLocked(next, forceNotify);
        }
        dispatchStatusCallback(next, changed);
    }

    private boolean setStatusLocked(ProbeStatus next, boolean forceNotify) {
        status = next;
        ProbeStatus previous = lastNotifiedStatus;
        boolean changed = forceNotify || previous == null
                || previous.getState() != next.getState()
                || previous.getSessionId() != next.getSessionId()
                || Math.abs(previous.getAnalyzedThroughPositionMs()
                - next.getAnalyzedThroughPositionMs()) >= 1000L
                || previous.getRuleRevision() != next.getRuleRevision();
        if (changed) lastNotifiedStatus = next;
        return changed;
    }

    private void dispatchStatusCallback(ProbeStatus next, boolean changed) {
        if (!changed) return;
        executeHostCallback(() -> {
            synchronized (stateLock) {
                if (status != next || !isSessionCurrentLocked(next.getSessionId())) return;
            }
            listener.onStatusChanged(next);
        });
    }

    private void executeHostCallback(Runnable callback) {
        try {
            hostExecutor.execute(() -> {
                try {
                    callbackGate.invokeIf(() -> !closed.get(), callback);
                } catch (RuntimeException ignored) {
                    // 监听器异常不得终止规则、解码或轮询线程。
                }
            });
        } catch (RuntimeException ignored) {
            // Executor 已关闭时 fail-open，不影响宿主播放。
        }
    }

    private boolean isActive(long sessionId) {
        return !closed.get() && enabled && sessionId > 0L && sessionId == activeSessionId;
    }

    private boolean isSessionCurrentLocked(long sessionId) {
        if (closed.get()) return false;
        return sessionId > 0L ? enabled && sessionId == activeSessionId
                : activeSessionId == 0L;
    }

    private long beginSessionLocked(ProbeMedia media, boolean positionKnown,
                                    long positionMs) {
        long sessionId = nextSessionId();
        activeSessionId = sessionId;
        activeMedia = media;
        initialPositionKnown = positionKnown;
        engineStarted = false;
        clockErrorReported = false;
        if (positionKnown) hostPositionMs.set(Math.max(0L, positionMs));
        return sessionId;
    }

    private long nextSessionId() {
        long next = sessionSequence.incrementAndGet();
        if (next <= 0L) throw new IllegalStateException("探针会话 ID 已耗尽");
        return next;
    }

    private long ruleRevision() {
        AdRuleSet snapshot = rules;
        return snapshot == null ? 0L : snapshot.getRevision();
    }

    private int ruleCount() {
        AdRuleSet snapshot = rules;
        return snapshot == null ? 0 : snapshot.getRules().size();
    }

    private ProbeStatus idleStatus() {
        return new ProbeStatus(ProbeState.IDLE, 0L, "", 0L, 0L,
                ruleRevision(), ruleCount(), null);
    }

    private void ensureOpenLocked() {
        if (closed.get()) throw new IllegalStateException("探针已经关闭");
    }

    private static PlaybackClock requireClock(PlaybackClock clock) {
        if (clock == null) throw new IllegalArgumentException("宿主时间轴不能为空");
        return clock;
    }

    private static ProbeListener requireListener(ProbeListener listener) {
        if (listener == null) throw new IllegalArgumentException("探针监听器不能为空");
        return listener;
    }

    private static Executor mainThreadExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return command -> handler.post(command);
    }

    /** 调用方持有 stateLock；规则提交与会话代际在同一临界区完成。 */
    private RuleTransition createRuleTransitionLocked(AdRuleSet loaded,
                                                      boolean forceRestart) {
        long sessionId = activeSessionId;
        long previousSessionId = 0L;
        boolean recoveringFailedSession = status.getState() == ProbeState.FAILED
                && status.getSessionId() == sessionId;
        if ((forceRestart || rules != null || recoveringFailedSession)
                && sessionId > 0L && activeMedia != null) {
            boolean hadPosition = initialPositionKnown;
            previousSessionId = sessionId;
            sessionId = beginSessionLocked(activeMedia, hadPosition, hostPositionMs.get());
        }
        rules = loaded;
        boolean needsClock = sessionId > 0L && !initialPositionKnown;
        ProbeState state = sessionId > 0L ? ProbeState.PREPARING : ProbeState.IDLE;
        String mediaId = statusMediaId(sessionId, activeMedia);
        ProbeStatus next = new ProbeStatus(state, sessionId, mediaId,
                hostPositionMs.get(), hostPositionMs.get(), loaded.getRevision(),
                loaded.getRules().size(), null);
        return new RuleTransition(previousSessionId, sessionId, needsClock, next);
    }

    /** 停用时会保留媒体供恢复，但无活动会话的公开状态不得携带媒体 ID。 */
    static String statusMediaId(long sessionId, ProbeMedia media) {
        return sessionId > 0L && media != null ? media.getId() : "";
    }

    private void applyRuleTransition(RuleTransition transition) {
        updateStatus(transition.status, true);
        long sessionId = transition.sessionId;
        if (transition.needsClock) requestHostPosition(sessionId);
        else engineHandler.post(() -> startEngineIfReady(sessionId));
        if (sessionId > 0L) schedulePolling();
    }

    private final class RuleListener implements AtomicRuleRepository.Listener {
        @Override
        public void onRules(AdRuleSet loaded, boolean fromCache, long replacementRequestId) {
            boolean forceReplace = replacementRequestId > 0L;
            RuleTransition transition = callbackGate.update(() -> {
                RuleTransition result;
                synchronized (stateLock) {
                    if (closed.get()) return null;
                    AdRuleSet previous = allRules;
                    if (!forceReplace && previous != null
                            && loaded.getRevision() <= previous.getRevision()) {
                        return null;
                    }

                    AdRuleSet effective = loaded;
                    if (testRuleId != null) {
                        if (RuleSetSelection.contains(loaded, testRuleId)) {
                            effective = RuleSetSelection.select(loaded, testRuleId);
                        } else {
                            // 新规则不再含目标 ID 时恢复全量，绝不静默进入空匹配集。
                            testRuleId = null;
                        }
                    }
                    allRules = loaded;
                    result = createRuleTransitionLocked(effective, forceReplace);
                }
                stopSessionWithinGate(result.previousSessionId);
                return result;
            });
            if (transition == null) return;
            applyRuleTransition(transition);
            if (replacementRequestId > 0L) {
                dispatchRuleReplacement(new RuleReplacementResult(replacementRequestId,
                        RuleReplacementState.APPLIED, transition.sessionId,
                        transition.status.getRuleRevision(), transition.status.getRuleCount(),
                        null));
            }
        }

        @Override
        public void onFailure(ProbeErrorCode code, boolean cacheAvailable, Exception error,
                              long replacementRequestId) {
            long sessionId = activeSessionId;
            ProbeErrorCode reported = cacheAvailable || code == ProbeErrorCode.RULE_PARSE_FAILED
                    ? code : ProbeErrorCode.RULES_UNAVAILABLE;
            String message = cacheAvailable
                    ? "规则更新失败，继续使用当前有效规则"
                    : code == ProbeErrorCode.RULE_PARSE_FAILED
                    ? "本地规则未通过 rules-v1 校验" : "没有可用的广告规则";
            ProbeError reportedError = reportError(reported,
                    sessionId, !cacheAvailable, true, message, error);
            if (replacementRequestId > 0L && reportedError != null) {
                dispatchRuleReplacement(new RuleReplacementResult(replacementRequestId,
                        RuleReplacementState.REJECTED, reportedError.getSessionId(),
                        ruleRevision(), ruleCount(), reportedError));
            }
        }

        @Override
        public void onReplacementSuperseded(long replacementRequestId) {
            ProbeStatus current = status;
            dispatchRuleReplacement(new RuleReplacementResult(replacementRequestId,
                    RuleReplacementState.SUPERSEDED, current.getSessionId(),
                    current.getRuleRevision(), current.getRuleCount(), null));
        }
    }

    private void dispatchRuleReplacement(RuleReplacementResult result) {
        executeHostCallback(() -> listener.onRulesReplaced(result));
    }

    private final class EngineListener implements ProbeSessionEngine.Listener {
        @Override
        public void onState(long sessionId, ProbeState state,
                            long analyzedThroughMs, long durationMs) {
            ProbeStatus next;
            boolean changed;
            synchronized (stateLock) {
                if (!isSessionCurrentLocked(sessionId) || activeMedia == null) return;
                ProbeStatus current = status;
                if (current.getState() == ProbeState.FAILED) return;
                next = new ProbeStatus(state, sessionId, activeMedia.getId(),
                        hostPositionMs.get(), analyzedThroughMs,
                        ruleRevision(), ruleCount(), current.getLastError());
                changed = setStatusLocked(next, false);
            }
            dispatchStatusCallback(next, changed);
        }

        @Override
        public void onAdReady(long sessionId, long ruleRevision, Claim claim,
                              long analyzedThroughMs) {
            if (isActive(sessionId)) {
                dispatchSkip(sessionId, ruleRevision, claim, analyzedThroughMs);
            }
        }

        @Override
        public void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                            boolean retryable, String message, Throwable error) {
            if (isActive(sessionId)) {
                reportError(code, sessionId, fatal, retryable, message, error);
            }
        }
    }

    private static final class SessionStart {
        final long previousSessionId;
        final long sessionId;
        final ProbeStatus status;

        SessionStart(long previousSessionId, long sessionId, ProbeStatus status) {
            this.previousSessionId = previousSessionId;
            this.sessionId = sessionId;
            this.status = status;
        }
    }

    private static final class EnableTransition {
        final long previousSessionId;
        final long resumedSessionId;
        final ProbeStatus status;

        private EnableTransition(long previousSessionId, long resumedSessionId,
                                 ProbeStatus status) {
            this.previousSessionId = previousSessionId;
            this.resumedSessionId = resumedSessionId;
            this.status = status;
        }

        static EnableTransition disabled(long previousSessionId) {
            return new EnableTransition(previousSessionId, 0L, null);
        }

        static EnableTransition enabledWithoutMedia() {
            return new EnableTransition(0L, 0L, null);
        }

        static EnableTransition resumed(long sessionId, ProbeStatus status) {
            return new EnableTransition(0L, sessionId, status);
        }
    }

    private static final class RuleTransition {
        final long previousSessionId;
        final long sessionId;
        final boolean needsClock;
        final ProbeStatus status;

        RuleTransition(long previousSessionId, long sessionId,
                       boolean needsClock, ProbeStatus status) {
            this.previousSessionId = previousSessionId;
            this.sessionId = sessionId;
            this.needsClock = needsClock;
            this.status = status;
        }
    }

    private static final class ErrorTransition {
        final ProbeError error;
        final ProbeStatus status;
        final boolean statusChanged;

        ErrorTransition(ProbeError error, ProbeStatus status, boolean statusChanged) {
            this.error = error;
            this.status = status;
            this.statusChanged = statusChanged;
        }
    }

    public static final class Builder {
        private final Context context;
        private final String ruleUrl;
        private PlaybackClock playbackClock;
        private ProbeListener listener;
        private Executor callbackExecutor;
        private ProbeAdapterFactory adapterFactory;
        private byte[] initialRules;
        private long maxLookaheadMs = 15_000L;

        private Builder(Context context, String ruleUrl) {
            if (context == null) throw new IllegalArgumentException("Context 不能为空");
            this.context = context.getApplicationContext();
            this.ruleUrl = ruleUrl;
        }

        /** 设置宿主当前媒体位置读取器，返回值单位为毫秒且不得为负数。 */
        public Builder setPlaybackClock(PlaybackClock playbackClock) {
            this.playbackClock = playbackClock;
            return this;
        }

        /** 设置必需监听器，至少处理已验证的跳转请求。 */
        public Builder setListener(ProbeListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * 设置宿主 Executor；它同时读取播放器时间轴并串行派发监听器。
         * Executor 必须异步执行、允许持续提交，并运行在宿主播放器许可的线程。
         */
        public Builder setHostExecutor(Executor executor) {
            this.callbackExecutor = executor;
            return this;
        }

        /**
         * 显式选择解码适配器。第三方实现使用此入口可完全移除官方 Media3 依赖；
         * 未设置时从默认聚合包发现唯一服务实现。
         */
        public Builder setAdapterFactory(ProbeAdapterFactory factory) {
            this.adapterFactory = factory;
            return this;
        }

        /**
         * 设置构建后在后台解析的本地 rules-v1 字节。输入会立即防御性复制，
         * 且必须为不超过 4 MiB 的严格 UTF-8 JSON。
         */
        public Builder setInitialRules(byte[] rulesJson) {
            initialRules = RuleSetJsonParser.copyDocument(rulesJson);
            return this;
        }

        /** 设置构建后在后台解析的本地 rules-v1 JSON 文本。 */
        public Builder setInitialRulesJson(String rulesJson) {
            initialRules = RuleSetJsonParser.encodeDocument(rulesJson);
            return this;
        }

        /** 设置 3 到 60 秒的最大前视窗口，默认 15 秒。 */
        public Builder setMaxLookaheadMs(long value) {
            if (value < MIN_LOOKAHEAD_MS || value > MAX_LOOKAHEAD_MS) {
                throw new IllegalArgumentException("前视窗口必须在 3 到 60 秒之间");
            }
            maxLookaheadMs = value;
            return this;
        }

        /** 校验配置并创建实例；本地或远程规则会在后台加载，媒体分析在首次 open 后开始。 */
        public AdAudioProbe build() {
            return new AdAudioProbe(this);
        }
    }
}
