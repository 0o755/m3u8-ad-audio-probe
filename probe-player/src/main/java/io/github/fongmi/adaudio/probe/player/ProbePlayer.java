/* 可见播放器门面统一管理适配器发现、串行控制、会话代际和宿主回调。 */
package io.github.fongmi.adaudio.probe.player;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapter;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackAdapterState;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackDiscontinuityReason;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackRequest;
import io.github.fongmi.adaudio.probe.adapter.playback.ProbePlaybackSnapshot;
import io.github.fongmi.adaudio.probe.player.internal.PlayerSerialExecutor;
import io.github.fongmi.adaudio.probe.player.internal.ProbePlaybackAdapterResolver;

import java.io.Closeable;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 不向宿主暴露具体播放器类型的普通 HLS/MP4 点播门面。
 *
 * <p>所有公开方法均可从任意线程调用且不会执行网络 I/O。播放器控制在专用 Looper
 * 串行执行，回调按宿主 Executor 串行派发。每次 {@code open} 都生成新会话 ID，旧会话
 * 的控制、状态和错误会被丢弃。宿主拥有并释放传入的 {@link Surface}。</p>
 */
public final class ProbePlayer implements Closeable {
    private static final long DEFAULT_POLL_INTERVAL_MS = 200L;
    private static final long MIN_POLL_INTERVAL_MS = 50L;
    private static final long MAX_POLL_INTERVAL_MS = 2000L;

    private final Object stateLock = new Object();
    private final ProbePlayerListener listener;
    private final PlayerSerialExecutor callbackExecutor;
    private final HandlerThread controlThread;
    private final Handler controlHandler;
    private final ProbePlaybackAdapter adapter;
    private final PlayerSurfaceReconciler<Surface> surfaceReconciler =
            new PlayerSurfaceReconciler<>();
    private final PlayerPollGate pollGate = new PlayerPollGate();
    private final long pollIntervalMs;
    private final AtomicLong sessionSequence = new AtomicLong();
    private final AtomicLong callbackGeneration = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile ProbePlayerStatus status = ProbePlayerStatus.idle(ProbePlayerState.IDLE);
    private volatile long activeSessionId;
    private volatile Surface attachedSurface;

    private ProbePlayer(Builder builder) {
        listener = builder.listener == null ? new ProbePlayerListener() { } : builder.listener;
        callbackExecutor = new PlayerSerialExecutor(builder.callbackExecutor == null
                ? mainThreadExecutor() : builder.callbackExecutor);
        pollIntervalMs = builder.pollIntervalMs;

        HandlerThread thread = new HandlerThread("ad-audio-probe-player");
        Handler handler;
        ProbePlaybackAdapter playbackAdapter;
        thread.start();
        try {
            handler = new Handler(thread.getLooper());
            ProbePlaybackAdapterFactory factory = ProbePlaybackAdapterResolver.resolve(
                    builder.adapterFactory);
            playbackAdapter = factory.create(builder.context, thread.getLooper(),
                    new AdapterListener());
            if (playbackAdapter == null) {
                throw new IllegalStateException("播放适配器工厂返回了 null");
            }
        } catch (RuntimeException | LinkageError error) {
            thread.quitSafely();
            throw error;
        }
        controlThread = thread;
        controlHandler = handler;
        adapter = playbackAdapter;
    }

    /** 使用默认适配器创建播放器；回调默认在 Android 主线程派发。 */
    public static ProbePlayer create(Context context, ProbePlayerListener listener) {
        return builder(context).setListener(listener).build();
    }

    /** 创建高级配置入口。 */
    public static Builder builder(Context context) {
        return new Builder(context);
    }

    /** 从 0 毫秒开始并自动播放媒体。 */
    public long open(String mediaUrl) {
        return open(ProbeMedia.from(mediaUrl));
    }

    /** 从 0 毫秒开始并自动播放媒体。 */
    public long open(ProbeMedia media) {
        return open(media, 0L, true);
    }

    /**
     * 原子替换当前媒体并返回新会话 ID。
     *
     * @param media 已校验的点播媒体请求
     * @param startPositionMs 非负起播位置，单位毫秒
     * @param playWhenReady 准备完成后是否自动播放
     */
    public long open(ProbeMedia media, long startPositionMs, boolean playWhenReady) {
        if (media == null) throw new IllegalArgumentException("媒体请求不能为空");
        if (startPositionMs < 0L) throw new IllegalArgumentException("开始位置不能为负数");
        final long previousSessionId;
        final long sessionId;
        final long generation;
        final ProbePlayerStatus next;
        final boolean posted;
        synchronized (stateLock) {
            ensureOpenLocked();
            previousSessionId = activeSessionId;
            sessionId = nextSessionId();
            activeSessionId = sessionId;
            generation = callbackGeneration.incrementAndGet();
            next = new ProbePlayerStatus(ProbePlayerState.PREPARING, sessionId,
                    media.getId(), startPositionMs, startPositionMs,
                    ProbePlaybackSnapshot.TIME_UNSET, false,
                    ProbePlayerVideoSize.unknown(), null);
            status = next;
            ProbePlaybackRequest request = new ProbePlaybackRequest(sessionId, media,
                    startPositionMs, playWhenReady);
            posted = controlHandler.post(() -> {
                if (!isActive(sessionId)) return;
                new PlayerOpenCommand(
                        () -> dispatchStatus(next, generation),
                        () -> openAdapter(previousSessionId, sessionId, request)).run();
            });
        }
        if (!posted) {
            // 控制线程拒绝任务时仍保持 PREPARING -> FAILED 的宿主可见顺序。
            dispatchStatus(next, generation);
            failWithoutAdapter(sessionId, "播放器控制线程无法接收打开任务", null);
        }
        return sessionId;
    }

    /**
     * 附加可见输出 Surface。播放器只借用对象，宿主必须先清除再自行释放。
     */
    public void attachSurface(Surface surface) {
        if (surface == null) throw new IllegalArgumentException("Surface 不能为空");
        final boolean posted;
        synchronized (stateLock) {
            ensureOpenLocked();
            attachedSurface = surface;
            posted = controlHandler.post(() -> reconcileSurfaceSafely(null, null));
        }
        if (!posted) {
            reportSurfaceFailure("播放器控制线程无法接收 Surface 任务", null);
            if (activeSessionId == 0L) {
                throw new IllegalStateException("播放器控制线程无法接收 Surface 任务");
            }
        }
    }

    /** 仅当给定 Surface 仍为当前输出时清除它，避免旧生命周期误清新输出。 */
    public void clearSurface(Surface surface) {
        clearSurface(surface, null);
    }

    /**
     * 清除给定 Surface，并在适配器确认不再持有它后派发完成回调。
     *
     * <p>完成回调使用宿主配置的 callback Executor；同一对象在完成前被重新附加时，本次
     * 回调不会执行，避免宿主释放仍在使用的 Surface。</p>
     */
    public void clearSurface(Surface surface, Runnable onCleared) {
        if (surface == null) return;
        final boolean posted;
        synchronized (stateLock) {
            if (closed.get()) return;
            if (attachedSurface == surface) attachedSurface = null;
            posted = controlHandler.post(
                    () -> reconcileSurfaceSafely(surface, onCleared));
        }
        if (!posted) {
            reportSurfaceFailure("播放器控制线程无法接收 Surface 清理任务", null);
            if (activeSessionId == 0L) {
                throw new IllegalStateException("播放器控制线程无法接收 Surface 清理任务");
            }
        }
    }

    /** 清除当前 Surface；对象仍由宿主负责释放。 */
    public void clearSurface() {
        clearSurface((Runnable) null);
    }

    /** 清除当前 Surface，并在适配器确认完成后通过宿主 callback Executor 回调。 */
    public void clearSurface(Runnable onCleared) {
        final Surface expectedSurface;
        final boolean posted;
        synchronized (stateLock) {
            if (closed.get()) return;
            expectedSurface = attachedSurface;
            attachedSurface = null;
            posted = controlHandler.post(
                    () -> reconcileSurfaceSafely(expectedSurface, onCleared));
        }
        if (!posted) {
            reportSurfaceFailure("播放器控制线程无法接收 Surface 清理任务", null);
            if (activeSessionId == 0L) {
                throw new IllegalStateException("播放器控制线程无法接收 Surface 清理任务");
            }
        }
    }

    /** 恢复当前会话；没有活动媒体时无操作。 */
    public void play() {
        postSessionControl("播放适配器无法恢复播放", adapter::play);
    }

    /** 暂停当前会话；没有活动媒体时无操作。 */
    public void pause() {
        postSessionControl("播放适配器无法暂停播放", adapter::pause);
    }

    /** 跳转当前会话，实际落点通过 discontinuity 回调确认。 */
    public void seekTo(long positionMs) {
        if (positionMs < 0L) throw new IllegalArgumentException("跳转位置不能为负数");
        final long sessionId;
        final boolean posted;
        synchronized (stateLock) {
            sessionId = activeSessionId;
            if (!isSessionControllableLocked(sessionId)) return;
            posted = controlHandler.post(() -> {
                if (!isControllable(sessionId)) return;
                try {
                    adapter.seekTo(sessionId, positionMs);
                    schedulePoll(sessionId);
                } catch (RuntimeException | LinkageError error) {
                    handleError(sessionId, ProbeErrorCode.INTERNAL, true, false,
                            "播放适配器无法完成跳转", error);
                }
            });
        }
        if (!posted) failWithoutAdapter(sessionId, "播放器控制线程无法接收跳转任务", null);
    }

    /** 停止当前媒体但保留适配器和 Surface，供后续再次打开。 */
    public void stop() {
        final long previousSessionId;
        final long generation;
        final ProbePlayerStatus next;
        synchronized (stateLock) {
            if (closed.get()) return;
            previousSessionId = activeSessionId;
            activeSessionId = 0L;
            generation = callbackGeneration.incrementAndGet();
            next = ProbePlayerStatus.idle(ProbePlayerState.IDLE);
            status = next;
        }
        // IDLE 必须先于旧适配器的清理诊断进入宿主回调队列。
        dispatchStatus(next, generation);
        if (previousSessionId > 0L) {
            boolean posted = controlHandler.post(() -> {
                try {
                    adapter.stop(previousSessionId);
                } catch (RuntimeException | LinkageError error) {
                    dispatchDetachedError(previousSessionId, generation,
                            "播放适配器无法停止旧会话", error);
                }
            });
            if (!posted) {
                dispatchDetachedError(previousSessionId, generation,
                        "播放器控制线程无法接收停止任务", null);
            }
        }
    }

    /** 返回最近一次不可变状态快照，不阻塞播放器线程。 */
    public ProbePlayerStatus getStatus() {
        return status;
    }

    /** 返回最近快照中的当前播放位置，单位毫秒。 */
    public long getCurrentPositionMs() {
        return status.getPositionMs();
    }

    /** 返回最近快照中的缓冲位置，单位毫秒。 */
    public long getBufferedPositionMs() {
        return status.getBufferedPositionMs();
    }

    /** 返回时长；未知时为 {@link ProbePlaybackSnapshot#TIME_UNSET}。 */
    public long getDurationMs() {
        return status.getDurationMs();
    }

    /** 返回最近快照中的实际播放状态。 */
    public boolean isPlaying() {
        return status.isPlaying();
    }

    /**
     * 永久释放播放器。已开始执行的宿主回调允许自然返回，排队中的旧代际回调会失效。
     */
    @Override
    public void close() {
        synchronized (stateLock) {
            if (!closed.compareAndSet(false, true)) return;
            activeSessionId = 0L;
            attachedSurface = null;
            callbackGeneration.incrementAndGet();
            status = ProbePlayerStatus.idle(ProbePlayerState.CLOSED);
        }
        boolean posted = controlHandler.post(() -> {
            try {
                try {
                    adapter.close();
                } catch (RuntimeException | LinkageError ignored) {
                    // close 已不可再向宿主派发事件，但仍必须回收控制线程。
                }
            } finally {
                surfaceReconciler.onAdapterClosed();
                controlThread.quitSafely();
            }
        });
        if (!posted) controlThread.quitSafely();
    }

    private void postSessionControl(String failureMessage, SessionControl control) {
        final long sessionId;
        final boolean posted;
        synchronized (stateLock) {
            sessionId = activeSessionId;
            if (!isSessionControllableLocked(sessionId)) return;
            posted = controlHandler.post(() -> {
                if (!isControllable(sessionId)) return;
                try {
                    control.run(sessionId);
                    schedulePoll(sessionId);
                } catch (RuntimeException | LinkageError error) {
                    handleError(sessionId, ProbeErrorCode.INTERNAL, true, false,
                            failureMessage, error);
                }
            });
        }
        if (!posted) failWithoutAdapter(sessionId,
                "播放器控制线程无法接收控制任务", null);
    }

    private void schedulePoll(long sessionId) {
        if (!isControllable(sessionId)) return;
        if (!pollGate.reserve(sessionId)) return;
        if (!controlHandler.postDelayed(() -> poll(sessionId), pollIntervalMs)) {
            pollGate.stop(sessionId);
            handleError(sessionId, ProbeErrorCode.INTERNAL, true, false,
                    "播放器控制线程无法继续更新时间轴", null);
        }
    }

    private void poll(long sessionId) {
        if (!pollGate.begin(sessionId)) return;
        if (!isControllable(sessionId)) return;
        ProbePlaybackSnapshot snapshot;
        try {
            snapshot = adapter.getSnapshot(sessionId);
        } catch (RuntimeException | LinkageError error) {
            handleError(sessionId, ProbeErrorCode.INTERNAL, true, false,
                    "播放适配器无法读取时间轴", error);
            return;
        }
        if (snapshot != null) applySnapshot(sessionId, snapshot);
        ProbePlayerState state = status.getState();
        if (isControllable(sessionId) && state != ProbePlayerState.ENDED
                && state != ProbePlayerState.FAILED) {
            schedulePoll(sessionId);
        } else {
            pollGate.stop(sessionId);
        }
    }

    private void openAdapter(long previousSessionId, long sessionId,
                             ProbePlaybackRequest request) {
        // PREPARING 的同步宿主回调可能打开、停止或关闭播放器，必须重新校验代际。
        if (!isActive(sessionId)) return;
        try {
            if (previousSessionId > 0L) adapter.stop(previousSessionId);
            reconcileSurface();
            adapter.open(request);
            schedulePoll(sessionId);
        } catch (RuntimeException | LinkageError error) {
            handleError(sessionId, ProbeErrorCode.INTERNAL, true, false,
                    "播放适配器无法打开媒体", error);
        }
    }

    private void applySnapshot(long sessionId, ProbePlaybackSnapshot snapshot) {
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)) return;
            long duration = snapshot.getDurationMs() == ProbePlaybackSnapshot.TIME_UNSET
                    ? current.getDurationMs() : snapshot.getDurationMs();
            next = new ProbePlayerStatus(current.getState(), sessionId, current.getMediaId(),
                    snapshot.getPositionMs(), snapshot.getBufferedPositionMs(), duration,
                    snapshot.isPlaying(), current.getVideoSize(), current.getLastError());
            if (sameStatus(current, next)) return;
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
    }

    private void handleState(long sessionId, ProbePlaybackAdapterState adapterState) {
        if (adapterState == null) return;
        ProbePlayerState mapped;
        switch (adapterState) {
            case BUFFERING:
                mapped = ProbePlayerState.BUFFERING;
                break;
            case READY:
                mapped = ProbePlayerState.READY;
                break;
            case ENDED:
                mapped = ProbePlayerState.ENDED;
                break;
            case IDLE:
            case PREPARING:
            default:
                mapped = ProbePlayerState.PREPARING;
                break;
        }
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId) || current.getState() == mapped) return;
            next = copyStatus(current, mapped, current.getPositionMs(),
                    current.getBufferedPositionMs(), current.getDurationMs(),
                    mapped == ProbePlayerState.ENDED ? false : current.isPlaying(),
                    current.getVideoSize(), current.getLastError());
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
        if (mapped != ProbePlayerState.ENDED) schedulePoll(sessionId);
    }

    private void handleTimeline(long sessionId, long durationMs, boolean live, boolean dynamic) {
        if (live || dynamic) {
            handleError(sessionId, ProbeErrorCode.LIVE_STREAM_NOT_SUPPORTED, true, false,
                    "可见播放器仅支持普通点播，不支持直播或动态时间轴", null);
            return;
        }
        long normalizedDuration = durationMs < 0L
                ? ProbePlaybackSnapshot.TIME_UNSET : durationMs;
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)
                    || current.getDurationMs() == normalizedDuration) return;
            next = copyStatus(current, current.getState(), current.getPositionMs(),
                    current.getBufferedPositionMs(), normalizedDuration, current.isPlaying(),
                    current.getVideoSize(), current.getLastError());
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
    }

    private void handleDiscontinuity(long sessionId, long positionMs,
                                     ProbePlaybackDiscontinuityReason reason) {
        if (positionMs < 0L) return;
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)) return;
            next = copyStatus(current, current.getState(), positionMs,
                    positionMs,
                    current.getDurationMs(), current.isPlaying(),
                    current.getVideoSize(), current.getLastError());
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
        ProbePlaybackDiscontinuityReason safeReason = reason == null
                ? ProbePlaybackDiscontinuityReason.INTERNAL : reason;
        dispatchEvent(generation, () -> listener.onPositionDiscontinuity(
                sessionId, positionMs, safeReason));
    }

    private void handleVideoSize(long sessionId, int width, int height,
                                 float ratio, int rotationDegrees) {
        ProbePlayerVideoSize size;
        try {
            size = new ProbePlayerVideoSize(width, height, ratio, rotationDegrees);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)
                    || sameVideoSize(current.getVideoSize(), size)) return;
            next = copyStatus(current, current.getState(), current.getPositionMs(),
                    current.getBufferedPositionMs(), current.getDurationMs(),
                    current.isPlaying(), size, current.getLastError());
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
    }

    private void handleFirstFrame(long sessionId) {
        long generation;
        synchronized (stateLock) {
            if (!isSessionMutableLocked(sessionId)) return;
            generation = callbackGeneration.get();
        }
        dispatchEvent(generation, () -> listener.onFirstFrame(sessionId));
    }

    private void handleError(long sessionId, ProbeErrorCode code, boolean fatal,
                             boolean retryable, String message, Throwable cause) {
        ProbePlayerError error;
        try {
            error = new ProbePlayerError(code, sessionId, fatal, retryable, message, cause);
        } catch (IllegalArgumentException invalidError) {
            error = new ProbePlayerError(ProbeErrorCode.INTERNAL, sessionId, fatal, false,
                    "播放适配器报告了无效错误", invalidError);
        }
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)) return;
            next = copyStatus(current, fatal ? ProbePlayerState.FAILED : current.getState(),
                    current.getPositionMs(), current.getBufferedPositionMs(),
                    current.getDurationMs(), fatal ? false : current.isPlaying(),
                    current.getVideoSize(), error);
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
        ProbePlayerError committed = error;
        dispatchEvent(generation, () -> listener.onError(committed));
        if (fatal) {
            try {
                adapter.stop(sessionId);
            } catch (RuntimeException | LinkageError ignored) {
                // FAILED 已提交；适配器清理异常不能恢复或覆盖该状态。
            }
        }
    }

    /** 控制线程不可用时直接提交 FAILED，避免从错误线程调用第三方适配器。 */
    private void failWithoutAdapter(long sessionId, String message, Throwable cause) {
        ProbePlayerError error = new ProbePlayerError(ProbeErrorCode.INTERNAL, sessionId,
                true, false, message, cause);
        ProbePlayerStatus next;
        long generation;
        synchronized (stateLock) {
            ProbePlayerStatus current = status;
            if (!isSessionMutableLocked(sessionId)) return;
            next = copyStatus(current, ProbePlayerState.FAILED, current.getPositionMs(),
                    current.getBufferedPositionMs(), current.getDurationMs(), false,
                    current.getVideoSize(), error);
            status = next;
            generation = callbackGeneration.get();
        }
        dispatchStatus(next, generation);
        dispatchEvent(generation, () -> listener.onError(error));
    }

    private void reportSurfaceFailure(String message, Throwable cause) {
        long sessionId = activeSessionId;
        if (!isActive(sessionId)) return;
        if (Looper.myLooper() == controlHandler.getLooper()) {
            handleError(sessionId, ProbeErrorCode.INTERNAL, false, true, message, cause);
        } else {
            postAdapterEventOrFail(sessionId,
                    () -> handleError(sessionId, ProbeErrorCode.INTERNAL,
                            false, true, message, cause));
        }
    }

    /** 仅由控制线程执行实际 Surface 切换。 */
    private void reconcileSurface() {
        surfaceReconciler.reconcile(
                () -> closed.get() ? null : attachedSurface,
                new PlayerSurfaceReconciler.Target<Surface>() {
                    @Override
                    public void attach(Surface value) {
                        adapter.attachSurface(value);
                    }

                    @Override
                    public void clear(Surface value) {
                        adapter.clearSurface(value);
                    }
                });
    }

    private void reconcileSurfaceSafely(Surface expectedSurface, Runnable onCleared) {
        try {
            reconcileSurface();
        } catch (RuntimeException | LinkageError error) {
            reportSurfaceFailure("播放适配器无法对账 Surface", error);
            return;
        }
        if (onCleared != null && !surfaceReconciler.isApplied(expectedSurface)) {
            dispatchSurfaceCompletion(onCleared);
        }
    }

    /** Surface 资源回调不绑定媒体代际，后续 open 不会吞掉已经完成的释放确认。 */
    private void dispatchSurfaceCompletion(Runnable callback) {
        callbackExecutor.tryExecute(() -> {
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 宿主资源回调异常不能破坏其余播放器回调。
            }
        }, ignored -> { });
    }

    private void dispatchDetachedError(long sessionId, long generation,
                                       String message, Throwable cause) {
        ProbePlayerError error = new ProbePlayerError(ProbeErrorCode.INTERNAL, sessionId,
                false, false, message, cause);
        dispatchEvent(generation, () -> listener.onError(error));
    }

    private void postAdapterEventOrFail(long sessionId, Runnable event) {
        boolean posted = controlHandler.post(() -> {
            if (isActive(sessionId)) event.run();
        });
        if (!posted) failWithoutAdapter(sessionId,
                "播放器控制线程无法接收适配器事件", null);
    }

    private void dispatchStatus(ProbePlayerStatus snapshot, long generation) {
        dispatchEvent(generation, () -> listener.onStatusChanged(snapshot));
    }

    private void dispatchEvent(long generation, Runnable callback) {
        callbackExecutor.tryExecute(() -> {
            if (closed.get() || callbackGeneration.get() != generation) return;
            try {
                callback.run();
            } catch (RuntimeException ignored) {
                // 宿主回调异常不能破坏播放器的控制线程和后续回调顺序。
            }
        }, ignored -> { });
    }

    private boolean isActive(long sessionId) {
        return !closed.get() && sessionId > 0L && activeSessionId == sessionId;
    }

    private boolean isControllable(long sessionId) {
        return isActive(sessionId) && status.getState() != ProbePlayerState.FAILED;
    }

    private boolean isSessionControllableLocked(long sessionId) {
        return !closed.get() && sessionId > 0L && activeSessionId == sessionId
                && status.getSessionId() == sessionId
                && status.getState() != ProbePlayerState.FAILED;
    }

    private boolean isSessionMutableLocked(long sessionId) {
        return !closed.get() && sessionId > 0L && activeSessionId == sessionId
                && status.getSessionId() == sessionId
                && status.getState() != ProbePlayerState.FAILED;
    }

    private void ensureOpenLocked() {
        if (closed.get()) throw new IllegalStateException("播放器已经关闭");
    }

    private long nextSessionId() {
        long next = sessionSequence.incrementAndGet();
        if (next <= 0L) throw new IllegalStateException("播放器会话 ID 已耗尽");
        return next;
    }

    private static ProbePlayerStatus copyStatus(ProbePlayerStatus current,
                                                 ProbePlayerState state,
                                                 long positionMs,
                                                 long bufferedPositionMs,
                                                 long durationMs,
                                                 boolean playing,
                                                 ProbePlayerVideoSize videoSize,
                                                 ProbePlayerError error) {
        return new ProbePlayerStatus(state, current.getSessionId(), current.getMediaId(),
                positionMs, bufferedPositionMs, durationMs, playing, videoSize, error);
    }

    private static boolean sameStatus(ProbePlayerStatus left, ProbePlayerStatus right) {
        return left.getState() == right.getState()
                && left.getSessionId() == right.getSessionId()
                && left.getPositionMs() == right.getPositionMs()
                && left.getBufferedPositionMs() == right.getBufferedPositionMs()
                && left.getDurationMs() == right.getDurationMs()
                && left.isPlaying() == right.isPlaying()
                && sameVideoSize(left.getVideoSize(), right.getVideoSize())
                && left.getLastError() == right.getLastError();
    }

    private static boolean sameVideoSize(ProbePlayerVideoSize left,
                                         ProbePlayerVideoSize right) {
        return left.getWidth() == right.getWidth() && left.getHeight() == right.getHeight()
                && Float.compare(left.getPixelWidthHeightRatio(),
                right.getPixelWidthHeightRatio()) == 0
                && left.getRotationDegrees() == right.getRotationDegrees();
    }

    private static Executor mainThreadExecutor() {
        Handler handler = new Handler(Looper.getMainLooper());
        return command -> {
            if (!handler.post(command)) {
                throw new IllegalStateException("Android 主线程已退出");
            }
        };
    }

    private interface SessionControl {
        void run(long sessionId);
    }

    private final class AdapterListener implements ProbePlaybackAdapter.Listener {
        @Override
        public void onState(long sessionId, ProbePlaybackAdapterState state) {
            postAdapterEvent(sessionId, () -> handleState(sessionId, state));
        }

        @Override
        public void onTimeline(long sessionId, long durationMs, boolean live, boolean dynamic) {
            postAdapterEvent(sessionId,
                    () -> handleTimeline(sessionId, durationMs, live, dynamic));
        }

        @Override
        public void onPositionDiscontinuity(long sessionId, long positionMs,
                                            ProbePlaybackDiscontinuityReason reason) {
            postAdapterEvent(sessionId,
                    () -> handleDiscontinuity(sessionId, positionMs, reason));
        }

        @Override
        public void onVideoSize(long sessionId, int width, int height,
                                float pixelWidthHeightRatio, int rotationDegrees) {
            postAdapterEvent(sessionId, () -> handleVideoSize(sessionId, width, height,
                    pixelWidthHeightRatio, rotationDegrees));
        }

        @Override
        public void onFirstFrame(long sessionId) {
            postAdapterEvent(sessionId, () -> handleFirstFrame(sessionId));
        }

        @Override
        public void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                            boolean retryable, String message, Throwable cause) {
            postAdapterEvent(sessionId, () -> handleError(sessionId, code, fatal,
                    retryable, message, cause));
        }

        private void postAdapterEvent(long sessionId, Runnable event) {
            if (!isActive(sessionId)) return;
            postAdapterEventOrFail(sessionId, event);
        }
    }

    /** 可见播放器高级配置。 */
    public static final class Builder {
        private final Context context;
        private ProbePlayerListener listener;
        private Executor callbackExecutor;
        private ProbePlaybackAdapterFactory adapterFactory;
        private long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;

        private Builder(Context context) {
            if (context == null) throw new IllegalArgumentException("Context 不能为空");
            Context applicationContext = context.getApplicationContext();
            this.context = applicationContext == null ? context : applicationContext;
        }

        /** 设置宿主回调；{@code null} 表示只通过状态快照读取。 */
        public Builder setListener(ProbePlayerListener listener) {
            this.listener = listener;
            return this;
        }

        /** 设置宿主回调 Executor；默认使用 Android 主线程。 */
        public Builder setCallbackExecutor(Executor callbackExecutor) {
            if (callbackExecutor == null) throw new IllegalArgumentException("Executor 不能为空");
            this.callbackExecutor = callbackExecutor;
            return this;
        }

        /** 显式设置第三方播放适配器；为空时通过 ServiceLoader 发现唯一实现。 */
        public Builder setAdapterFactory(ProbePlaybackAdapterFactory adapterFactory) {
            if (adapterFactory == null) throw new IllegalArgumentException("适配器工厂不能为空");
            this.adapterFactory = adapterFactory;
            return this;
        }

        /** 设置时间轴快照间隔，允许 50 到 2000 毫秒。 */
        public Builder setPositionPollIntervalMs(long pollIntervalMs) {
            if (pollIntervalMs < MIN_POLL_INTERVAL_MS || pollIntervalMs > MAX_POLL_INTERVAL_MS) {
                throw new IllegalArgumentException("时间轴轮询间隔必须在 50 到 2000 毫秒之间");
            }
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        /** 校验配置并创建播放器；失败时已经启动的控制线程会立即回收。 */
        public ProbePlayer build() {
            return new ProbePlayer(this);
        }
    }
}
