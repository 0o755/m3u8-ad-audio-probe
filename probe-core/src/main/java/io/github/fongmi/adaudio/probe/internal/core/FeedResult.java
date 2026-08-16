/* 定义每次 PCM 喂入后的安全状态和事件集合。 */
package io.github.fongmi.adaudio.probe.internal.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** feed 的安全返回值；调用方无需捕获匹配过程中的常见输入错误。 */
public final class FeedResult {
    public enum Status { NO_MATCH, MATCHED, RESET, INVALID_INPUT, INTERNAL_ERROR }

    private final Status status;
    private final List<MatchEvent> events;
    private final String message;
    private final boolean timelineReset;

    private FeedResult(Status status, List<MatchEvent> events, String message,
                       boolean timelineReset) {
        this.status = status;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.message = message == null ? "" : message;
        this.timelineReset = timelineReset;
    }

    static FeedResult of(Status status, List<MatchEvent> events, String message) {
        return new FeedResult(status, events, message, status == Status.RESET);
    }

    static FeedResult of(Status status, List<MatchEvent> events, String message,
                         boolean timelineReset) {
        return new FeedResult(status, events, message, timelineReset);
    }

    public Status getStatus() { return status; }
    public List<MatchEvent> getEvents() { return events; }
    public String getMessage() { return message; }
    /** 一块 PCM 可以在新时间轴内命中，因此重置信号与 MATCHED 状态彼此独立。 */
    public boolean isTimelineReset() { return timelineReset; }
}
