package com.github.auties00.smalt.container.ts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the 33-bit presentation and decoding timestamps of the PES headers into one continuous
 * 90 kHz timeline that starts at the presentation start.
 *
 * <p>Each elementary stream unwraps its timestamps against its own previous one, so a stream
 * that crosses the 2^33 wrap (every 26.5 hours) keeps counting up. A jump of a stream's decoding
 * times (its presentation times when it has no others) by more than {@value #MAX_BACKWARD} ticks
 * backwards or {@value #MAX_FORWARD} ticks forwards is a timeline discontinuity (a splice, or a
 * restarted encoder): the first stream that meets it opens an epoch that continues the timeline
 * {@value #DISCONTINUITY_GAP} ticks after the latest time returned so far, and every other stream
 * that later jumps to within
 * {@value #MAX_FORWARD} ticks of the epoch's raw start joins that epoch, so the streams stay
 * synchronized across the splice. Times are returned relative to the origin, the absolute time
 * presented first.
 *
 * <p>Instances are not thread-safe.
 */
final class TsClock {
    /**
     * The period of the 33-bit timestamps.
     */
    static final long WRAP = 1L << 33;

    /**
     * The value meaning "no timestamp".
     */
    static final long NONE = Long.MIN_VALUE;

    /**
     * The largest backward step of a stream's decoding times that is not a discontinuity: half a
     * second, which tolerates the jitter of badly timed audio PES packets.
     */
    private static final long MAX_BACKWARD = 45_000L;

    /**
     * The largest forward step of a stream's timestamps that is not a discontinuity: ten seconds.
     */
    private static final long MAX_FORWARD = 10 * 90_000L;

    /**
     * The gap left between the last time before a discontinuity and the first time after it:
     * one 25 Hz frame.
     */
    private static final long DISCONTINUITY_GAP = 3600;

    /**
     * The state of each stream, by PID.
     */
    private final Map<Integer, StreamState> streams = new HashMap<>();

    /**
     * The epochs opened by discontinuities, in order.
     */
    private final List<Epoch> epochs = new ArrayList<>();

    /**
     * The absolute time presented first, subtracted from every returned time.
     */
    private long origin;

    /**
     * The absolute time the first timestamp of a stream is unwrapped against, or {@link #NONE}
     * to take that timestamp as it is.
     */
    private long reference;

    /**
     * The largest corrected absolute time returned.
     */
    private long latest = NONE;

    /**
     * Constructs a clock.
     *
     * @param origin the absolute time presented first
     * @param reference the absolute time the first timestamp of each stream is unwrapped against,
     * or {@link #NONE}
     */
    TsClock(long origin, long reference) {
        this.origin = origin;
        this.reference = reference;
    }

    /**
     * Returns the absolute time presented first.
     *
     * @return the origin in 90 kHz ticks
     */
    long origin() {
        return origin;
    }

    /**
     * Converts a 33-bit timestamp of a stream to the continuous timeline.
     *
     * <p>A stream's decoding time must be converted before the presentation time of the same PES
     * packet, so that a discontinuity the decoding time reveals applies to both.
     *
     * @param pid the PID of the stream
     * @param raw the 33-bit timestamp
     * @param decoding whether the timestamp is a decoding time (or a presentation time standing for
     * one), whose jumps reveal discontinuities
     * @return the time in 90 kHz ticks relative to the origin
     */
    long ticks(int pid, long raw, boolean decoding) {
        var state = streams.computeIfAbsent(pid, ignored -> new StreamState());
        var anchor = state.last != NONE ? state.last : latestRaw();
        var absolute = anchor == NONE ? raw : unwrap(raw, anchor);
        if (decoding) {
            if (state.lastDecoding != NONE) {
                var delta = absolute - state.lastDecoding;
                if (delta < -MAX_BACKWARD || delta > MAX_FORWARD) {
                    state.epoch = epochFor(state.epoch, absolute);
                }
            }
            state.lastDecoding = absolute;
        }
        state.last = absolute;
        var corrected = absolute + (state.epoch < 0 ? 0 : epochs.get(state.epoch).correction);
        latest = latest == NONE ? corrected : Math.max(latest, corrected);
        return corrected - origin;
    }

    /**
     * Returns the absolute time against which the first timestamp of a new stream is unwrapped:
     * the latest raw time of any stream, or the reference.
     *
     * @return the anchor, or {@link #NONE}
     */
    private long latestRaw() {
        var anchor = NONE;
        for (var state : streams.values()) {
            if (state.last != NONE && (anchor == NONE || state.last > anchor)) {
                anchor = state.last;
            }
        }
        return anchor != NONE ? anchor : reference;
    }

    /**
     * Returns the epoch a stream joins after a discontinuity, opening one when no later epoch
     * starts near the new time.
     *
     * @param current the index of the stream's epoch, or {@code -1}
     * @param absolute the unwrapped time after the jump
     * @return the index of the epoch
     */
    private int epochFor(int current, long absolute) {
        for (var index = current + 1; index < epochs.size(); index++) {
            var epoch = epochs.get(index);
            if (Math.abs(absolute - epoch.rawStart) <= MAX_FORWARD) {
                return index;
            }
        }
        var base = latest == NONE ? absolute : latest + DISCONTINUITY_GAP;
        epochs.add(new Epoch(absolute, base - absolute));
        return epochs.size() - 1;
    }

    /**
     * Unwraps a 33-bit timestamp to the value nearest an anchor.
     *
     * @param raw the 33-bit timestamp
     * @param anchor the unwrapped time the result must be nearest
     * @return {@code raw} plus the multiple of 2^33 that brings it nearest {@code anchor}
     */
    static long unwrap(long raw, long anchor) {
        var difference = Math.floorMod(raw - anchor + WRAP / 2, WRAP) - WRAP / 2;
        return anchor + difference;
    }

    /**
     * Holds the timeline state of one stream.
     */
    private static final class StreamState {
        /**
         * The last unwrapped timestamp, or {@link #NONE}.
         */
        long last = NONE;

        /**
         * The last unwrapped decoding time, or {@link #NONE}.
         */
        long lastDecoding = NONE;

        /**
         * The index of the stream's epoch, or {@code -1} before any discontinuity.
         */
        int epoch = -1;
    }

    /**
     * Describes a stretch of the timeline after a discontinuity.
     *
     * @param rawStart the unwrapped time at which the epoch was opened
     * @param correction the value added to the unwrapped times of the epoch
     */
    private record Epoch(long rawStart, long correction) {
    }
}
