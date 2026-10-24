package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.track.Packet;

import java.util.ArrayDeque;

/**
 * Times the pictures of a stream that carries no timestamps from their picture order counts.
 *
 * <p>Pictures are added in decoding order. Each belongs to a period that its first picture opens (an IDR picture,
 * or a picture whose picture order count restarts) and inside which pictures are presented in increasing order count;
 * a period is presented after the previous one. A picture's presentation time is the sum of the durations of the
 * output pictures presented before it; its decoding time is the sum of the durations of the pictures decoded before it
 * minus a fixed delay, the reorder depth, so that no decoding time follows its presentation time. A picture that is
 * not output gets {@link Packet#NO_TIMESTAMP} as its presentation time.
 *
 * <p>A picture's place in presentation order is known once every picture of its period that could precede it has
 * been added: the stream's reorder bound limits how many pictures later in decoding order can come earlier in
 * presentation order, so {@link #poll(boolean)} waits for that many pictures after the earliest unplaced one.
 * Instances are not thread-safe.
 */
final class PresentationOrder {
    /**
     * Marks a presentation time not assigned yet.
     */
    private static final long UNASSIGNED = Long.MAX_VALUE;

    /**
     * One picture.
     */
    static final class Entry {
        /**
         * The picture order count.
         */
        final long poc;

        /**
         * The period, increasing.
         */
        final long period;

        /**
         * Whether the picture is output.
         */
        final boolean output;

        /**
         * The duration, in ticks.
         */
        final int duration;

        /**
         * The payload the caller attached.
         */
        final Object payload;

        /**
         * The presentation time, {@link #UNASSIGNED} until placed.
         */
        long pts = UNASSIGNED;

        /**
         * The decoding time, before the delay is subtracted.
         */
        long decodeTime;

        /**
         * Constructs an entry.
         *
         * @param poc the picture order count
         * @param period the number of the run of order counts, increasing
         * @param output whether the picture is output
         * @param duration the duration in ticks
         * @param payload the object the caller attaches, returned with the entry
         */
        Entry(long poc, long period, boolean output, int duration, Object payload) {
            this.poc = poc;
            this.period = period;
            this.output = output;
            this.duration = duration;
            this.payload = payload;
            if (!output) {
                pts = Packet.NO_TIMESTAMP;
            }
        }

        /**
         * {@return the presentation time, or {@link Packet#NO_TIMESTAMP} for a picture not output}
         */
        long pts() {
            return pts;
        }
    }

    /**
     * The pictures added and not polled, in decoding order.
     */
    private final ArrayDeque<Entry> window = new ArrayDeque<>();

    /**
     * The number of pictures that must follow the earliest unplaced picture of a period before it is placed.
     */
    private final int lookahead;

    /**
     * The decoding delay in ticks, or {@code -1} until measured.
     */
    private long delay;

    /**
     * The presentation time of the next picture placed.
     */
    private long clock;

    /**
     * The decoding time of the next picture added.
     */
    private long decodeClock;

    /**
     * Constructs an ordering.
     *
     * @param lookahead the stream's reorder bound in pictures, from 0 to 16
     * @param delay the decoding delay in ticks, or {@code -1} to measure it over the first pictures
     * @param clock the presentation time of the first picture placed
     * @param decodeClock the decoding time of the first picture added, before the delay is subtracted
     */
    PresentationOrder(int lookahead, long delay, long clock, long decodeClock) {
        this.lookahead = lookahead;
        this.delay = delay;
        this.clock = clock;
        this.decodeClock = decodeClock;
    }

    /**
     * {@return the decoding delay in ticks, or {@code -1} until measured}
     */
    long delay() {
        return delay;
    }

    /**
     * {@return the decoding time of the next picture added, before the delay is subtracted}
     */
    long decodeClock() {
        return decodeClock;
    }

    /**
     * {@return the number of pictures added and not polled}
     */
    int size() {
        return window.size();
    }

    /**
     * Adds the next picture in decoding order.
     *
     * @param entry the picture
     */
    void add(Entry entry) {
        entry.decodeTime = decodeClock;
        // a picture that is not output takes no display time, so it does not delay the decoding of the next one
        decodeClock += entry.output ? entry.duration : 0;
        window.add(entry);
    }

    /**
     * Returns the earliest picture in decoding order once its presentation time is known.
     *
     * @param end whether no picture follows the ones added
     * @return the picture, removed from the ordering, or {@code null} when more pictures must be added first
     */
    Entry poll(boolean end) {
        if (window.isEmpty()) {
            return null;
        }
        if (delay < 0) {
            // twice the reorder bound: the pictures of the first half can all be placed
            if (window.size() <= 2 * lookahead && !end) {
                return null;
            }
            measureDelay(end);
        }
        var front = window.peekFirst();
        while (front.pts == UNASSIGNED) {
            if (!placeNext(front.period, end)) {
                return null;
            }
        }
        return window.pollFirst();
    }

    /**
     * Returns the decoding time of a polled picture.
     *
     * @param entry the picture
     * @return its decoding time
     */
    long dts(Entry entry) {
        return entry.decodeTime - Math.max(0, delay);
    }

    /**
     * Places the earliest unplaced output picture of a period, when it is safe.
     *
     * @param period the number of the run of order counts
     * @param end whether no picture follows the ones added
     * @return {@code true} when a picture was placed
     */
    private boolean placeNext(long period, boolean end) {
        Entry earliest = null;
        var after = 0;
        var closed = end;
        for (var entry : window) {
            if (entry.period < period) {
                continue;
            }
            if (entry.period > period) {
                closed = true;
                break;
            }
            if (entry.pts == UNASSIGNED && (earliest == null || entry.poc < earliest.poc)) {
                earliest = entry;
                after = 0;
            } else if (earliest != null) {
                after++;
            }
        }
        if (earliest == null || (!closed && after < lookahead)) {
            return false;
        }
        earliest.pts = clock;
        clock += earliest.duration;
        return true;
    }

    /**
     * Measures the decoding delay as the largest gap by which a decoding time would follow its presentation time
     * among the pictures in the window, by placing every picture that can be placed.
     *
     * @param end whether no picture follows the ones added
     */
    private void measureDelay(boolean end) {
        var measured = 0L;
        var saved = clock;
        var placed = new ArrayDeque<Entry>();
        for (var entry : window) {
            if (entry.pts == UNASSIGNED) {
                placed.add(entry);
            }
        }
        long period = Long.MIN_VALUE;
        for (var entry : window) {
            if (entry.period == period) {
                continue;
            }
            period = entry.period;
            var later = end || hasLaterPeriod(period);
            var progress = true;
            while (progress) {
                progress = placeNext(period, later);
            }
            if (hasUnplaced(period)) {
                break;
            }
        }
        for (var entry : window) {
            if (entry.pts != UNASSIGNED && entry.pts != Packet.NO_TIMESTAMP) {
                measured = Math.max(measured, entry.decodeTime - entry.pts);
            }
        }
        for (var entry : placed) {
            entry.pts = UNASSIGNED;
        }
        clock = saved;
        delay = measured;
    }

    /**
     * Returns whether the window holds an output picture of a period that is not placed.
     *
     * @param period the number of the run of order counts
     * @return {@code true} when a picture of the period waits for its presentation time
     */
    private boolean hasUnplaced(long period) {
        for (var entry : window) {
            if (entry.period == period && entry.pts == UNASSIGNED) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the window holds a picture of a later period.
     *
     * @param period the number of the run of order counts
     * @return {@code true} when a later period has started
     */
    private boolean hasLaterPeriod(long period) {
        for (var entry : window) {
            if (entry.period > period) {
                return true;
            }
        }
        return false;
    }
}
