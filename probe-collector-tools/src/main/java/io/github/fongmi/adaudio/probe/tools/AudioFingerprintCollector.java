/* 高层采集门面独立管理适配器、PTS 对齐、超时与单活会话。 */
package io.github.fongmi.adaudio.probe.tools;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import java.util.Iterator;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import io.github.fongmi.adaudio.probe.ProbeErrorCode;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapter;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterFactory;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterRequest;
import io.github.fongmi.adaudio.probe.adapter.ProbeAdapterState;
import io.github.fongmi.adaudio.probe.adapter.ProbePcmFrame;
import io.github.fongmi.adaudio.probe.adapter.internal.FiniteVodTimelineGate;
import io.github.fongmi.adaudio.probe.tools.internal.FingerprintAssembler;
import io.github.fongmi.adaudio.probe.tools.internal.SerialCallbackExecutor;

/**
 * 把普通点播媒体中的一个广告区间采集为 rules-v1 指纹草稿。
 *
 * <p>宿主只提供 {@link FingerprintCaptureRequest}，PCM 解码、时间戳对齐和频谱提取均在
 * SDK 内完成。同一实例只保留一个活动会话；开始新采集会取消旧会话。实例不支持直播、
 * DRM 或并行采集，使用完毕后必须调用 {@link #close()}。</p>
 */
public final class AudioFingerprintCollector implements AutoCloseable {
    private static final long MIN_TIMEOUT_MS = 5_000L;
    private static final long MAX_TIMEOUT_MS = 120_000L;
    private static final long DECODE_PREROLL_MS = 1_000L;
    private static final long DECODE_MARGIN_MS = 1_500L;

    private final Object monitor = new Object();
    private final HandlerThread controlThread;
    private final Handler controlHandler;
    private final SerialCallbackExecutor callbacks;
    private final ProbeAdapter adapter;
    private final long timeoutMs;
    private final AtomicLong sessionCounter = new AtomicLong();
    private ActiveCapture active;
    private boolean closed;

    private AudioFingerprintCollector(Builder builder) {
        Context application = builder.context.getApplicationContext();
        if (application == null) application = builder.context;
        ProbeAdapterFactory factory = resolveFactory(builder.adapterFactory);
        validateFactory(factory);
        this.timeoutMs = builder.timeoutMs;
        this.callbacks = new SerialCallbackExecutor(builder.callbackExecutor != null
                ? builder.callbackExecutor : mainExecutor());
        this.controlThread = new HandlerThread("AdAudioFingerprintCollector");
        controlThread.start();
        this.controlHandler = new Handler(controlThread.getLooper());
        try {
            this.adapter = factory.create(application, controlThread.getLooper(),
                    new AdapterListener());
            if (adapter == null) throw new IllegalStateException("音频适配器工厂返回了 null");
        } catch (RuntimeException | LinkageError error) {
            controlThread.quitSafely();
            throw new IllegalStateException("音频适配器创建失败", error);
        }
    }

    /**
     * 开始采集并返回可取消句柄。
     *
     * @param request 已完成边界校验的采集请求
     * @param listener 进度与单次终态监听器
     * @return 当前采集会话；方法本身不阻塞媒体读取
     */
    public ProbeToolSession capture(FingerprintCaptureRequest request,
                                    FingerprintCaptureListener listener) {
        if (request == null || listener == null) {
            throw new IllegalArgumentException("采集请求和监听器不能为空");
        }
        final long sessionId = nextSessionId();
        final ActiveCapture created = new ActiveCapture(sessionId, request, listener);
        ActiveCapture replaced;
        synchronized (monitor) {
            if (closed) throw new IllegalStateException("指纹采集器已关闭");
            replaced = active;
            if (replaced != null) replaced.terminal = true;
            active = created;
        }
        if (replaced != null) dispatchCancelled(replaced);
        postControl(new Runnable() {
            @Override public void run() { openOnControl(created); }
        }, created);
        controlHandler.postDelayed(new Runnable() {
            @Override public void run() { timeout(sessionId); }
        }, timeoutMs);
        return new SessionHandle(sessionId);
    }

    /** 取消活动会话并永久释放适配器和工作线程；方法幂等。 */
    @Override
    public void close() {
        ActiveCapture cancelled;
        synchronized (monitor) {
            if (closed) return;
            closed = true;
            cancelled = active;
            if (cancelled != null) cancelled.terminal = true;
            active = null;
        }
        if (cancelled != null) dispatchCancelled(cancelled);
        final long stoppedId = cancelled == null ? 0L : cancelled.sessionId;
        boolean posted = controlHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    if (stoppedId > 0L) adapter.stop(stoppedId);
                } catch (RuntimeException | LinkageError ignored) {
                    // close 仍必须执行；活动会话已经收到取消终态。
                }
                try {
                    adapter.close();
                } catch (RuntimeException | LinkageError ignored) {
                    // close 无返回值；此时已无活动会话，仍需保证控制线程退出。
                } finally {
                    controlThread.quitSafely();
                }
            }
        });
        if (!posted) controlThread.quitSafely();
    }

    private void handlePcm(long sessionId, ProbePcmFrame frame) {
        ActiveCapture capture;
        FingerprintCaptureProgress progress = null;
        FingerprintRuleDraft completed = null;
        ProbeToolError failure = null;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId) || frame == null
                    || !capture.timelineGate.isVodConfirmed()) return;
            try {
                capture.assembler.append(frame);
                boolean complete = capture.assembler.isComplete();
                int percent = complete ? 100 : (int) Math.min(99L,
                        capture.assembler.getFilledCount() * 100L
                                / capture.assembler.getRequiredCount());
                if (percent > capture.lastPercent) {
                    capture.lastPercent = percent;
                    progress = new FingerprintCaptureProgress(sessionId,
                            complete ? capture.request.getAnchorDurationMs()
                                    : capture.assembler.getCoveredDurationMs(),
                            capture.request.getAnchorDurationMs());
                }
                if (complete) {
                    completed = capture.assembler.finish();
                    capture.terminal = true;
                    active = null;
                }
            } catch (IllegalArgumentException error) {
                failure = finishWithErrorLocked(capture, ProbeToolErrorCode.INVALID_REQUEST,
                        false, error.getMessage(), error);
            } catch (RuntimeException error) {
                failure = finishWithErrorLocked(capture, ProbeToolErrorCode.TIMELINE_UNRELIABLE,
                        false, safeMessage(error, "指纹锚点无法可靠生成"), error);
            }
        }
        if (progress != null) dispatchProgress(capture, progress);
        if (completed != null) {
            stopThenComplete(capture, completed);
        } else if (failure != null) {
            stopOnControl(sessionId);
            dispatchError(capture, failure);
        }
    }

    private void handleTimelineReset(long sessionId, long positionMs) {
        synchronized (monitor) {
            ActiveCapture capture = active;
            if (!isActive(capture, sessionId)) return;
            if (capture.assembler.getFilledCount() > 0) {
                capture.assembler.reset();
            }
        }
    }

    private void handleTimeline(long sessionId, long durationMs, boolean live, boolean dynamic) {
        ActiveCapture capture;
        ProbeToolError error = null;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
            FiniteVodTimelineGate.Decision decision = capture.timelineGate.update(
                    durationMs, live, dynamic);
            if (decision == FiniteVodTimelineGate.Decision.UNSUPPORTED) {
                error = finishWithErrorLocked(capture,
                        ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED, false,
                        "指纹采集仅支持有限时长点播", null);
            } else if (decision == FiniteVodTimelineGate.Decision.VOD_CONFIRMED
                    && durationMs > 0L && capture.request.getAdEndMs() > durationMs) {
                error = finishWithErrorLocked(capture, ProbeToolErrorCode.INVALID_REQUEST,
                        false, "广告结束位置超过媒体时长", null);
            }
        }
        if (error != null) {
            stopOnControl(sessionId);
            dispatchError(capture, error);
        }
    }

    private void handleState(long sessionId, ProbeAdapterState state) {
        ActiveCapture capture;
        ProbeToolError error = null;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
            if (state == ProbeAdapterState.DECODING) {
                FiniteVodTimelineGate.Decision decision = capture.timelineGate.markReady();
                if (decision == FiniteVodTimelineGate.Decision.UNSUPPORTED) {
                    error = finishWithErrorLocked(capture,
                            ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED, false,
                            "指纹采集仅支持有限时长点播", null);
                } else if (decision == FiniteVodTimelineGate.Decision.VOD_CONFIRMED
                        && capture.timelineGate.getDurationMs() > 0L
                        && capture.request.getAdEndMs()
                        > capture.timelineGate.getDurationMs()) {
                    error = finishWithErrorLocked(capture, ProbeToolErrorCode.INVALID_REQUEST,
                            false, "广告结束位置超过媒体时长", null);
                }
            } else if (state == ProbeAdapterState.ENDED) {
                error = finishWithErrorLocked(capture, ProbeToolErrorCode.TIMELINE_UNRELIABLE,
                        false, "媒体结束前未完整覆盖指纹锚点", null);
            }
        }
        if (error != null) {
            stopOnControl(sessionId);
            dispatchError(capture, error);
        }
    }

    private void handleAdapterError(long sessionId, ProbeErrorCode code, boolean fatal,
                                    boolean retryable, String message, Throwable cause) {
        if (!fatal) return;
        ActiveCapture capture;
        ProbeToolError error;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
            error = finishWithErrorLocked(capture, mapCode(code), retryable,
                    message == null || message.trim().isEmpty() ? "音频适配器失败" : message,
                    cause);
        }
        stopOnControl(sessionId);
        dispatchError(capture, error);
    }

    private void openOnControl(ActiveCapture capture) {
        synchronized (monitor) {
            if (!isActive(active, capture.sessionId)) return;
        }
        try {
            long anchorStartMs = capture.request.getAdStartMs()
                    + capture.request.getAnchorOffsetMs();
            long prerollMs = Math.min(DECODE_PREROLL_MS, anchorStartMs);
            long startMs = anchorStartMs - prerollMs;
            adapter.open(new ProbeAdapterRequest(capture.sessionId,
                    capture.request.getMedia(), startMs,
                    prerollMs + capture.request.getAnchorDurationMs() + DECODE_MARGIN_MS));
        } catch (LinkageError failure) {
            fail(capture.sessionId, ProbeToolErrorCode.UNSUPPORTED_SOURCE, false,
                    "音频适配器二进制版本不兼容", failure);
        } catch (RuntimeException failure) {
            fail(capture.sessionId, ProbeToolErrorCode.INTERNAL, false,
                    "音频适配器无法打开媒体", failure);
        }
    }

    private void timeout(long sessionId) {
        ActiveCapture capture;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
        }
        fail(sessionId, ProbeToolErrorCode.TIMEOUT, true,
                "指纹采集超时：" + capture.assembler.coverageDiagnostics(), null);
    }

    private void fail(long sessionId, ProbeToolErrorCode code, boolean retryable,
                      String message, Throwable cause) {
        ActiveCapture capture;
        ProbeToolError error;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
            error = finishWithErrorLocked(capture, code, retryable, message, cause);
        }
        stopOnControl(sessionId);
        dispatchError(capture, error);
    }

    private void cancel(long sessionId) {
        ActiveCapture capture;
        synchronized (monitor) {
            capture = active;
            if (!isActive(capture, sessionId)) return;
            capture.terminal = true;
            active = null;
        }
        stopOnControl(sessionId);
        dispatchCancelled(capture);
    }

    private ProbeToolError finishWithErrorLocked(ActiveCapture capture,
                                                  ProbeToolErrorCode code,
                                                  boolean retryable, String message,
                                                  Throwable cause) {
        capture.terminal = true;
        if (active == capture) active = null;
        return new ProbeToolError(code, capture.sessionId, retryable, message, cause);
    }

    private void postControl(final Runnable command, final ActiveCapture capture) {
        if (controlHandler.post(command)) return;
        fail(capture.sessionId, ProbeToolErrorCode.INTERNAL, false,
                "采集控制线程不可用", null);
    }

    private void stopOnControl(final long sessionId) {
        boolean posted = controlHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    adapter.stop(sessionId);
                } catch (RuntimeException | LinkageError ignored) {
                    // 原始完成、取消或错误终态已经确定，清理失败不能产生第二终态。
                }
            }
        });
        if (!posted) controlThread.quitSafely();
    }

    private void stopThenComplete(final ActiveCapture capture,
                                  final FingerprintRuleDraft draft) {
        boolean posted = controlHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    adapter.stop(capture.sessionId);
                    dispatchCompleted(capture, draft);
                } catch (LinkageError error) {
                    dispatchError(capture, new ProbeToolError(
                            ProbeToolErrorCode.UNSUPPORTED_SOURCE, capture.sessionId, false,
                            "音频适配器二进制版本不兼容", error));
                } catch (RuntimeException error) {
                    dispatchError(capture, new ProbeToolError(
                            ProbeToolErrorCode.INTERNAL, capture.sessionId, false,
                            "音频适配器无法结束采集会话", error));
                }
            }
        });
        if (!posted) {
            controlThread.quitSafely();
            dispatchError(capture, new ProbeToolError(ProbeToolErrorCode.INTERNAL,
                    capture.sessionId, false, "采集控制线程不可用", null));
        }
    }

    private boolean isActive(ActiveCapture capture, long sessionId) {
        return capture != null && !capture.terminal && capture.sessionId == sessionId;
    }

    private void dispatchProgress(final ActiveCapture capture,
                                  final FingerprintCaptureProgress progress) {
        callbacks.execute(new Runnable() {
            @Override public void run() {
                synchronized (capture) {
                    if (capture.callbackTerminal) return;
                }
                capture.listener.onProgress(progress);
            }
        });
    }

    private void dispatchCompleted(final ActiveCapture capture,
                                   final FingerprintRuleDraft draft) {
        callbacks.execute(new Runnable() {
            @Override public void run() {
                synchronized (capture) {
                    if (capture.callbackTerminal) return;
                    capture.callbackTerminal = true;
                }
                capture.listener.onCompleted(capture.sessionId, draft);
            }
        });
    }

    private void dispatchCancelled(final ActiveCapture capture) {
        callbacks.execute(new Runnable() {
            @Override public void run() {
                synchronized (capture) {
                    if (capture.callbackTerminal) return;
                    capture.callbackTerminal = true;
                }
                capture.listener.onCancelled(capture.sessionId);
            }
        });
    }

    private void dispatchError(final ActiveCapture capture, final ProbeToolError error) {
        callbacks.execute(new Runnable() {
            @Override public void run() {
                synchronized (capture) {
                    if (capture.callbackTerminal) return;
                    capture.callbackTerminal = true;
                }
                capture.listener.onError(error);
            }
        });
    }

    private long nextSessionId() {
        long value = sessionCounter.incrementAndGet();
        if (value > 0L) return value;
        synchronized (sessionCounter) {
            if (sessionCounter.get() <= 0L) sessionCounter.set(1L);
            return sessionCounter.getAndIncrement();
        }
    }

    private static ProbeToolErrorCode mapCode(ProbeErrorCode code) {
        if (code == null) return ProbeToolErrorCode.INTERNAL;
        switch (code) {
            case INVALID_SOURCE: return ProbeToolErrorCode.INVALID_REQUEST;
            case UNSUPPORTED_SOURCE: return ProbeToolErrorCode.UNSUPPORTED_SOURCE;
            case LIVE_STREAM_NOT_SUPPORTED: return ProbeToolErrorCode.LIVE_STREAM_NOT_SUPPORTED;
            case DRM_NOT_SUPPORTED: return ProbeToolErrorCode.DRM_NOT_SUPPORTED;
            case SOURCE_IO: return ProbeToolErrorCode.SOURCE_IO;
            case NO_AUDIO_TRACK: return ProbeToolErrorCode.NO_AUDIO_TRACK;
            case UNSUPPORTED_AUDIO: return ProbeToolErrorCode.UNSUPPORTED_AUDIO;
            case DECODER_FAILED: return ProbeToolErrorCode.DECODER_FAILED;
            case TIMELINE_UNRELIABLE: return ProbeToolErrorCode.TIMELINE_UNRELIABLE;
            case RESOURCE_EXHAUSTED: return ProbeToolErrorCode.RESOURCE_EXHAUSTED;
            default: return ProbeToolErrorCode.INTERNAL;
        }
    }

    private static String safeMessage(Throwable error, String fallback) {
        return error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? fallback : error.getMessage();
    }

    private static ProbeAdapterFactory resolveFactory(ProbeAdapterFactory explicit) {
        if (explicit != null) return explicit;
        try {
            ServiceLoader<ProbeAdapterFactory> loader = ServiceLoader.load(
                    ProbeAdapterFactory.class, ProbeAdapterFactory.class.getClassLoader());
            Iterator<ProbeAdapterFactory> iterator = loader.iterator();
            if (!iterator.hasNext()) {
                throw new IllegalStateException("未找到音频探针适配器");
            }
            ProbeAdapterFactory selected = iterator.next();
            if (iterator.hasNext()) {
                throw new IllegalStateException("检测到多个音频探针适配器，请显式选择");
            }
            return selected;
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("音频探针适配器加载失败", error);
        }
    }

    private static void validateFactory(ProbeAdapterFactory factory) {
        if (factory.getSpiVersion() != ProbeAdapterFactory.SPI_VERSION) {
            throw new IllegalStateException("音频适配器 SPI 版本不兼容");
        }
        String id = factory.getId();
        if (id == null || id.isEmpty() || id.length() > 128) {
            throw new IllegalStateException("音频适配器 ID 无效");
        }
        for (int index = 0; index < id.length(); index++) {
            if (id.charAt(index) < 0x21 || id.charAt(index) > 0x7e) {
                throw new IllegalStateException("音频适配器 ID 必须是可打印 ASCII");
            }
        }
    }

    private static Executor mainExecutor() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return new Executor() {
            @Override public void execute(Runnable command) {
                if (!handler.post(command)) throw new IllegalStateException("主线程不可用");
            }
        };
    }

    /** 创建指纹采集器；构建过程只创建适配器，不读取媒体。 */
    public static final class Builder {
        private final Context context;
        private ProbeAdapterFactory adapterFactory;
        private Executor callbackExecutor;
        private long timeoutMs = 30_000L;

        /** @param context 任意 Context，内部只保留 Application Context */
        public Builder(Context context) {
            if (context == null) throw new IllegalArgumentException("Context 不能为空");
            this.context = context;
        }

        /**
         * 显式选择第三方音频适配器；未设置时使用唯一的服务提供者。
         *
         * @param factory 与当前 SPI 版本兼容的工厂
         * @return 当前构建器
         */
        public Builder setAdapterFactory(ProbeAdapterFactory factory) {
            this.adapterFactory = factory;
            return this;
        }

        /**
         * 设置宿主回调执行器；未设置时回调 Android 主线程。
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
         * 设置单次采集超时，允许 5 到 120 秒。
         *
         * @param timeoutMs 超时时间，单位毫秒
         * @return 当前构建器
         */
        public Builder setTimeoutMs(long timeoutMs) {
            if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
                throw new IllegalArgumentException("采集超时必须为 5 到 120 秒");
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        /** @return 独立、可复用且同一时刻只运行一个会话的采集器 */
        public AudioFingerprintCollector build() {
            return new AudioFingerprintCollector(this);
        }
    }

    private final class SessionHandle implements ProbeToolSession {
        private final long sessionId;
        SessionHandle(long sessionId) { this.sessionId = sessionId; }
        @Override public long getSessionId() { return sessionId; }
        @Override public void cancel() { AudioFingerprintCollector.this.cancel(sessionId); }
    }

    /** 私有桥接层确保 PCM 和适配器生命周期不进入采集门面的公开方法表。 */
    private final class AdapterListener implements ProbeAdapter.Listener {
        @Override public void onPcm(long sessionId, ProbePcmFrame frame) {
            handlePcm(sessionId, frame);
        }

        @Override public void onTimelineReset(long sessionId, long positionMs) {
            handleTimelineReset(sessionId, positionMs);
        }

        @Override public void onTimeline(long sessionId, long durationMs,
                                         boolean live, boolean dynamic) {
            handleTimeline(sessionId, durationMs, live, dynamic);
        }

        @Override public void onState(long sessionId, ProbeAdapterState state) {
            handleState(sessionId, state);
        }

        @Override public void onError(long sessionId, ProbeErrorCode code, boolean fatal,
                                      boolean retryable, String message, Throwable cause) {
            handleAdapterError(sessionId, code, fatal, retryable, message, cause);
        }
    }

    private static final class ActiveCapture {
        final long sessionId;
        final FingerprintCaptureRequest request;
        final FingerprintCaptureListener listener;
        final FingerprintAssembler assembler;
        final FiniteVodTimelineGate timelineGate = new FiniteVodTimelineGate();
        int lastPercent = -1;
        boolean terminal;
        boolean callbackTerminal;

        ActiveCapture(long sessionId, FingerprintCaptureRequest request,
                      FingerprintCaptureListener listener) {
            this.sessionId = sessionId;
            this.request = request;
            this.listener = listener;
            this.assembler = new FingerprintAssembler(request);
        }
    }
}
