/* Media3 时间线分类器屏蔽 placeholder，并只发布权威的有限 VOD 时间线。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

final class Media3VodTimelineGate {
    enum Decision { PENDING, VOD, REJECT_LIVE, REJECT_DYNAMIC, IGNORED }

    private enum State { PENDING, VOD, REJECTED }

    private State state = State.PENDING;
    private boolean ready;
    private boolean seenAuthoritative;
    private boolean live;
    private boolean dynamic;

    void reset() {
        state = State.PENDING;
        ready = false;
        seenAuthoritative = false;
        live = false;
        dynamic = false;
    }

    Decision update(boolean placeholder, boolean live, boolean dynamic) {
        if (state == State.REJECTED) return Decision.IGNORED;
        if (placeholder) return state == State.VOD ? Decision.IGNORED : Decision.PENDING;
        seenAuthoritative = true;
        this.live = live;
        this.dynamic = dynamic;
        return decide();
    }

    Decision markReady() {
        ready = true;
        return decide();
    }

    private Decision decide() {
        if (!ready || !seenAuthoritative) return Decision.PENDING;
        if (live) {
            state = State.REJECTED;
            return Decision.REJECT_LIVE;
        }
        if (dynamic) {
            state = State.REJECTED;
            return Decision.REJECT_DYNAMIC;
        }
        state = State.VOD;
        return Decision.VOD;
    }

    boolean isVodConfirmed() {
        return state == State.VOD;
    }
}
