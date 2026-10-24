package com.github.auties00.smalt.container.raw;

import com.github.auties00.smalt.track.Packet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * Times the pictures of a stream that carries no timestamps from their picture order counts.
 *
 * <p>Pictures are added in decoding order. Each belongs to a period that its first picture opens (an IDR picture,
 * or a picture whose picture order count restarts) and inside which pictures are presented in increasing order count;
 * a period is presented after the previous one. A picture's presentation time is the sum of the durations of the
 * output pictures presented before it. Its decoding time is the presentation time of the output picture that is
 * {@code lookahead} places earlier in presentation order than the number of output pictures decoded before it, or,
 * before the first picture, the first presentation time less as many durations of the first picture: since at most
 * {@code lookahead} pictures decoded before a picture are presented after it, no decoding time follows its
 * presentation time, and decoding times never decrease. A picture that is not output gets
 * {@link Packet#NO_TIMESTAMP} as its presentation time. When {@value #MAX_UNTIMED} pictures that are not output
 * come before the first output one, the pictures are returned without waiting for it and the times before the first
 * place step by one tick instead of the first picture's duration.
 *
 * <p>The ordering follows the output process of a decoder (ITU-T H.264 clause C.4.5.3, H.265 clause C.5.2): after
 * each output picture is added, pictures leave the decoded picture buffer for output in order count order while more
 * than the picture's reorder bound wait, or while one waited its latency bound in pictures. A picture that names the
 * pictures it keeps as references (an HEVC reference picture set) first releases the others and outputs pictures
 * while the buffer holds as many pictures as its size. A period whose first picture discards the prior pictures (its
 * {@code no_output_of_prior_pics_flag}) ends the pictures of the previous periods still waiting: they are not output,
 * keep their place in decoding order, and take no presentation time.
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
     * The number of presentation times kept before the oldest that can still be needed is dropped.
     */
    private static final int TRIM = 4096;

    /**
     * The most reference pictures the decoder model keeps, past which the oldest are released: a decoded picture
     * buffer holds at most 16.
     */
    private static final int MAX_REFERENCES = 32;

    /**
     * The most pictures held while none is output, past which the pictures are returned before the duration of the
     * first output picture is known.
     */
    private static final int MAX_UNTIMED = 256;

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
         * The duration, in ticks.
         */
        final int duration;

        /**
         * Whether the picture, when it opens a period, discards the pictures still waiting for output.
         */
        final boolean discardPrior;

        /**
         * The payload the caller attached.
         */
        final Object payload;

        /**
         * Whether the picture is output.
         */
        boolean output;

        /**
         * Whether the picture was to be output and a later period discarded it.
         */
        boolean discarded;

        /**
         * The presentation time, {@link #UNASSIGNED} until placed.
         */
        long pts = UNASSIGNED;

        /**
         * The number of output pictures added before this one, discarded ones included.
         */
        long outputsBefore;

        /**
         * The sum of the durations of the output pictures added before this one, discarded ones excluded.
         */
        long presentBefore;

        /**
         * The number of pictures added since this one, when it waits for output.
         */
        int latency;

        /**
         * The number of pictures that may wait for output while this one is decoded, or {@code -1} for the
         * ordering's.
         */
        final int reorder;

        /**
         * The latency bound in pictures while this one is decoded, 0 for none, or {@code -1} for the ordering's.
         */
        final int maxLatency;

        /**
         * The size of the decoded picture buffer while this one is decoded, or 0 when the buffer is not modelled.
         */
        final int maxBuffering;

        /**
         * Tells which earlier pictures of the period stay references for this one, by order count, or {@code null}
         * when the stream's reference marking is not modelled.
         */
        final LongPredicate references;

        /**
         * Whether the picture is stored as a reference picture once decoded.
         */
        final boolean referencePicture;

        /**
         * Whether the picture is output at once, without being stored, when the decoded picture buffer is full and
         * it precedes every picture waiting for output (ITU-T H.264 clause C.4.5.2).
         */
        final boolean outputWhenFull;

        /**
         * Whether the picture waits in the decoder model for output.
         */
        boolean waiting;

        /**
         * Whether the picture is a reference picture in the decoder model.
         */
        boolean reference;

        /**
         * Constructs an entry under the ordering's reorder and latency bounds, without a buffer model.
         *
         * @param poc the picture order count
         * @param period the number of the run of order counts, increasing
         * @param output whether the picture is output
         * @param duration the duration in ticks
         * @param discardPrior whether the picture discards the pictures waiting for output when it opens a period
         * @param payload the object the caller attaches, returned with the entry
         */
        Entry(long poc, long period, boolean output, int duration, boolean discardPrior, Object payload) {
            this(poc, period, output, duration, discardPrior, -1, -1, 0, null, false, false, payload);
        }

        /**
         * Constructs an entry.
         *
         * @param poc the picture order count
         * @param period the number of the run of order counts, increasing
         * @param output whether the picture is output
         * @param duration the duration in ticks
         * @param discardPrior whether the picture discards the pictures waiting for output when it opens a period
         * @param reorder the number of pictures that may wait for output, or {@code -1} for the ordering's
         * @param maxLatency the latency bound in pictures, 0 for none, or {@code -1} for the ordering's
         * @param maxBuffering the decoded picture buffer size, or 0 when the buffer is not modelled
         * @param references which earlier pictures of the period stay references, by order count, or {@code null}
         * when the reference marking is not modelled
         * @param referencePicture whether the picture is stored as a reference picture
         * @param outputWhenFull whether the picture is output at once when the buffer is full and it precedes every
         * waiting picture
         * @param payload the object the caller attaches, returned with the entry
         */
        Entry(long poc, long period, boolean output, int duration, boolean discardPrior, int reorder, int maxLatency,
              int maxBuffering, LongPredicate references, boolean referencePicture, boolean outputWhenFull,
              Object payload) {
            this.poc = poc;
            this.period = period;
            this.output = output;
            this.duration = duration;
            this.discardPrior = discardPrior;
            this.reorder = reorder;
            this.maxLatency = maxLatency;
            this.maxBuffering = maxBuffering;
            this.references = references;
            this.referencePicture = referencePicture;
            this.outputWhenFull = outputWhenFull;
            this.payload = payload;
            if (!output) {
                pts = Packet.NO_TIMESTAMP;
            }
        }

        /**
         * {@return the presentation time, or {@link Packet#NO_TIMESTAMP} for a picture not output}
         */
        long pts() {
            return output ? pts : Packet.NO_TIMESTAMP;
        }

        /**
         * {@return whether the picture is output}
         */
        boolean output() {
            return output;
        }
    }

    /**
     * A growable list of presentation times, indexed by place in presentation order from a first place.
     */
    static final class Times {
        private long[] values = new long[64];

        private long first;

        private int size;

        /**
         * Constructs an empty list starting at a place.
         *
         * @param first the place of the first time added
         */
        Times(long first) {
            this.first = first;
        }

        /**
         * Appends the time of the next place.
         *
         * @param time the presentation time
         */
        void add(long time) {
            if (size == values.length) {
                values = Arrays.copyOf(values, 2 * size);
            }
            values[size++] = time;
        }

        /**
         * {@return the place of the first time held}
         */
        long first() {
            return first;
        }

        /**
         * {@return the place after the last time held}
         */
        long end() {
            return first + size;
        }

        /**
         * Returns the time of a place.
         *
         * @param place a place from {@link #first()} to {@link #end()} exclusive
         * @return the time
         */
        long get(long place) {
            return values[(int) (place - first)];
        }

        /**
         * Drops the times before a place.
         *
         * @param place the first place to keep, at most {@link #end()}
         */
        void dropBefore(long place) {
            var count = (int) (place - first);
            if (count <= 0) {
                return;
            }
            System.arraycopy(values, count, values, 0, size - count);
            size -= count;
            first = place;
        }
    }

    /**
     * The pictures added and not polled, in decoding order.
     */
    private final ArrayDeque<Entry> window = new ArrayDeque<>();

    /**
     * The pictures the decoder model holds: waiting for output or used for reference.
     */
    private final List<Entry> buffer = new ArrayList<>();

    /**
     * The number of pictures that must follow the earliest unplaced picture of a period before it is placed.
     */
    private final int lookahead;

    /**
     * The number of pictures that may wait for output in the decoder model.
     */
    private final int reorder;

    /**
     * The latency, in pictures, at which a waiting picture is output, or 0 when only the reorder bound applies.
     */
    private final int maxLatency;

    /**
     * The presentation times of the places this ordering assigns, from the place of its first picture.
     */
    private final Times times;

    /**
     * Whether {@link #times} keeps every time, for an index.
     */
    private final boolean keepTimes;

    /**
     * The presentation times of the places before the first picture, or {@code null} when the ordering starts at
     * the start of the stream.
     */
    private final Times prior;

    /**
     * The presentation time of the first place of the stream, valid once known.
     */
    private long firstTime;

    /**
     * The duration of the picture at the first place of the stream (one tick when many pictures that are not output
     * come first), or {@code -1} until known.
     */
    private int firstDuration;

    /**
     * The presentation time of the next picture placed.
     */
    private long clock;

    /**
     * The number of output pictures added, discarded ones included.
     */
    private long outputs;

    /**
     * The sum of the durations of the output pictures added, discarded ones excluded.
     */
    private long presentEnd;

    /**
     * The period of the last picture added.
     */
    private long lastPeriod = Long.MIN_VALUE;

    /**
     * Constructs an ordering that starts at the start of the stream.
     *
     * @param lookahead the stream's reorder bound in pictures, from 0 to 33
     * @param reorder the number of pictures that may wait for output, from 0 to 16
     * @param maxLatency the latency in pictures at which a waiting picture is output, or 0 for none
     * @param times the list that receives every presentation time, or {@code null} to keep only the ones needed
     */
    PresentationOrder(int lookahead, int reorder, int maxLatency, Times times) {
        this.lookahead = lookahead;
        this.reorder = reorder;
        this.maxLatency = maxLatency;
        this.keepTimes = times != null;
        this.times = times != null ? times : new Times(0);
        this.prior = null;
        this.firstDuration = -1;
    }

    /**
     * Constructs an ordering that resumes at a picture opening a period.
     *
     * @param lookahead the stream's reorder bound in pictures, from 0 to 33
     * @param reorder the number of pictures that may wait for output, from 0 to 16
     * @param maxLatency the latency in pictures at which a waiting picture is output, or 0 for none
     * @param outputs the number of output pictures before the resumption point, discarded ones included
     * @param clock the presentation time at which the resumed period starts
     * @param prior the presentation times of every place of the stream, from place 0
     * @param firstDuration the duration of the picture at place 0
     */
    PresentationOrder(int lookahead, int reorder, int maxLatency, long outputs, long clock, Times prior,
                      int firstDuration) {
        this.lookahead = lookahead;
        this.reorder = reorder;
        this.maxLatency = maxLatency;
        this.keepTimes = false;
        this.times = new Times(outputs);
        this.prior = prior;
        this.outputs = outputs;
        this.clock = clock;
        this.presentEnd = clock;
        this.firstTime = prior.end() > 0 ? prior.get(0) : 0;
        this.firstDuration = firstDuration;
    }

    /**
     * {@return the duration the decoding times before the first place step by, or {@code -1} until it is known}
     */
    int firstDuration() {
        return firstDuration;
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
        var bound = entry.reorder >= 0 ? entry.reorder : reorder;
        var latency = entry.maxLatency >= 0 ? entry.maxLatency : maxLatency;
        if (entry.period != lastPeriod) {
            if (entry.discardPrior) {
                for (var prior : buffer) {
                    if (prior.waiting && prior.pts == UNASSIGNED) {
                        prior.output = false;
                        prior.discarded = true;
                        presentEnd -= prior.duration;
                    }
                }
            }
            buffer.clear();
            lastPeriod = entry.period;
        }
        var outputNow = false;
        if (entry.references != null && entry.period == lastPeriod && !buffer.isEmpty()) {
            for (var other : buffer) {
                other.reference = other.reference && entry.references.test(other.poc);
            }
            buffer.removeIf(other -> !other.waiting && !other.reference);
            var references = 0;
            for (var other : buffer) {
                references += other.reference ? 1 : 0;
            }
            for (var i = 0; i < buffer.size() && references > MAX_REFERENCES; i++) {
                var other = buffer.get(i);
                if (other.reference) {
                    other.reference = false;
                    references--;
                    if (!other.waiting) {
                        buffer.remove(i--);
                    }
                }
            }
            while (waitingCount() > bound || late(latency)
                    || (entry.maxBuffering > 0 && buffer.size() >= entry.maxBuffering)) {
                if (entry.outputWhenFull && entry.output && !outputNow && precedesWaiting(entry)) {
                    outputNow = true;
                    if (!entry.referencePicture) {
                        break;
                    }
                    continue;
                }
                if (!bump()) {
                    break;
                }
            }
        }
        entry.outputsBefore = outputs;
        entry.presentBefore = presentEnd;
        if (entry.output) {
            outputs++;
            presentEnd += entry.duration;
            if (latency > 0) {
                for (var other : buffer) {
                    if (other.waiting && other.poc > entry.poc) {
                        other.latency++;
                    }
                }
            }
            entry.waiting = !outputNow;
        }
        entry.reference = entry.references != null && entry.referencePicture;
        if (entry.waiting || entry.reference) {
            buffer.add(entry);
        }
        while (waitingCount() > bound || late(latency)) {
            if (!bump()) {
                break;
            }
        }
        window.add(entry);
    }

    /**
     * Returns whether a picture precedes every picture waiting for output in the decoder model.
     *
     * @param entry the picture
     * @return {@code true} when no waiting picture has a smaller order count
     */
    private boolean precedesWaiting(Entry entry) {
        for (var other : buffer) {
            if (other.waiting && other.poc < entry.poc) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@return the number of pictures waiting for output in the decoder model}
     */
    private int waitingCount() {
        var count = 0;
        for (var other : buffer) {
            if (other.waiting) {
                count++;
            }
        }
        return count;
    }

    /**
     * Returns whether a waiting picture reached the latency limit.
     *
     * @param latency the latency bound in pictures, or 0 for none
     * @return {@code true} when a picture must be output
     */
    private boolean late(int latency) {
        if (latency <= 0) {
            return false;
        }
        for (var other : buffer) {
            if (other.waiting && other.latency >= latency) {
                return true;
            }
        }
        return false;
    }

    /**
     * Outputs the waiting picture with the smallest order count in the decoder model, releasing it unless it is a
     * reference.
     *
     * @return {@code true} when a picture was waiting
     */
    private boolean bump() {
        Entry first = null;
        for (var other : buffer) {
            if (other.waiting && (first == null || other.poc < first.poc)) {
                first = other;
            }
        }
        if (first == null) {
            return false;
        }
        first.waiting = false;
        if (!first.reference) {
            buffer.remove(first);
        }
        return true;
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
        var front = window.peekFirst();
        while (front.pts == UNASSIGNED) {
            if (!placeNext(front.period, end)) {
                return null;
            }
        }
        // the decoding times before the first place extrapolate from its duration
        while (firstDuration < 0 && prior == null) {
            Entry unplaced = null;
            for (var entry : window) {
                if (entry.pts == UNASSIGNED) {
                    unplaced = entry;
                    break;
                }
            }
            if (unplaced == null) {
                if (end) {
                    break;
                }
                if (window.size() < MAX_UNTIMED) {
                    return null;
                }
                // no picture output yet: the decoding times before the first place step by one tick
                firstDuration = 1;
                break;
            }
            if (!placeNext(unplaced.period, end)) {
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
        var place = entry.outputsBefore - lookahead;
        if (!keepTimes && times.end() - times.first() > 2 * TRIM) {
            var needed = place;
            for (var other : window) {
                needed = Math.min(needed, other.outputsBefore - lookahead);
            }
            if (needed - times.first() > TRIM) {
                times.dropBefore(Math.min(needed, times.end()));
            }
        }
        return time(place);
    }

    /**
     * Returns the presentation time of a place, extrapolating before the first.
     *
     * @param place the place in presentation order
     * @return the time
     */
    private long time(long place) {
        if (place < 0) {
            return firstTime + place * Math.max(1, firstDuration);
        }
        if (place >= times.first() && place < times.end()) {
            return times.get(place);
        }
        if (prior != null && place < prior.end()) {
            return prior.get(place);
        }
        return place < times.first() ? firstTime : clock;
    }

    /**
     * Places the earliest unplaced picture of a period, when it is safe.
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
        if (times.end() == 0 && prior == null) {
            firstTime = clock;
            if (firstDuration < 0) {
                firstDuration = earliest.discarded ? 0 : earliest.duration;
            }
        }
        times.add(clock);
        earliest.pts = clock;
        if (!earliest.discarded) {
            clock += earliest.duration;
        }
        return true;
    }
}
