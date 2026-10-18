package com.github.auties00.smalt.internal.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a stream of possibly overlapping cues into a gapless sequence of segments, each listing the
 * cues shown during it, the form of subtitle tracks in MP4 files.
 *
 * <p>Cues arrive in start order through {@link #offer(long, long, Object)}. A segment ends wherever
 * a cue starts or ends, so within a segment the set of shown cues is constant; a segment where no
 * cue is shown has an empty list, and the timeline starts at zero (or at the first cue, when it
 * starts earlier), so a leading gap becomes an empty segment. A cue that starts before the end of
 * the segments already emitted is clipped to start there. Memory is bounded by the cues shown at
 * once.
 *
 * <p>Instances are not thread-safe.
 *
 * @param <T> the type of the value each cue carries
 */
public final class CueSegmenter<T> {
    /**
     * Receives the segments.
     *
     * @param <T> the type of the value each cue carries
     */
    @FunctionalInterface
    public interface Output<T> {
        /**
         * Receives one segment.
         *
         * @param start the segment start
         * @param end the segment end, after {@code start}
         * @param cues the values of the cues shown, in arrival order, empty in a gap; valid only
         * during the call
         */
        void segment(long start, long end, List<T> cues);
    }

    /**
     * Holds a cue being shown.
     *
     * @param end the cue end
     * @param value the cue value
     * @param <T> the type of the value
     */
    private record Entry<T>(long end, T value) {
    }

    /**
     * The receiver of the segments.
     */
    private final Output<T> output;

    /**
     * The cues not yet ended at {@link #cursor}, in arrival order.
     */
    private final List<Entry<T>> active;

    /**
     * The values passed to the output, reused across segments.
     */
    private final List<T> shown;

    /**
     * The end of the emitted segments.
     */
    private long cursor;

    /**
     * Whether a cue was offered.
     */
    private boolean started;

    /**
     * Constructs a segmenter.
     *
     * @param output the receiver of the segments
     */
    public CueSegmenter(Output<T> output) {
        this.output = output;
        this.active = new ArrayList<>();
        this.shown = new ArrayList<>();
    }

    /**
     * Adds a cue, emitting the segments that end at or before its start.
     *
     * @param start the cue start
     * @param end the cue end; a cue that ends at or before its start, or at or before the emitted
     * segments, is ignored
     * @param value the value the cue carries
     */
    public void offer(long start, long end, T value) {
        if (!started) {
            started = true;
            cursor = Math.min(0, start);
        }
        start = Math.max(start, cursor);
        if (end <= start) {
            return;
        }
        advance(start);
        active.add(new Entry<>(end, value));
    }

    /**
     * Emits the segments up to the end of the last cue shown.
     */
    public void finish() {
        var last = cursor;
        for (var entry : active) {
            last = Math.max(last, entry.end());
        }
        advance(last);
    }

    /**
     * {@return the end of the segments emitted so far}
     */
    public long position() {
        return cursor;
    }

    /**
     * Emits segments until the cursor reaches a time.
     *
     * @param time the time to reach, in microseconds
     */
    private void advance(long time) {
        while (cursor < time) {
            var boundary = time;
            for (var entry : active) {
                boundary = Math.min(boundary, entry.end());
            }
            shown.clear();
            for (var entry : active) {
                shown.add(entry.value());
            }
            output.segment(cursor, boundary, shown);
            cursor = boundary;
            active.removeIf(entry -> entry.end() <= cursor);
        }
    }
}
