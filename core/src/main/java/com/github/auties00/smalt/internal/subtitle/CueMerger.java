package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Joins consecutive cues that show the same thing back to back.
 *
 * <p>MP4 subtitle tracks repeat a cue in every sample during which it is shown, so a cue that
 * overlaps others reads back as several pieces; joining a cue with a later one when their text,
 * identifier and settings are equal and the later starts exactly where the first ends restores it.
 * A cue without a duration (a container block without one) ends where the next cue starts. By default
 * one cue is held back and a cue joins only the cue just before it; for documents of several cues, as
 * TTML samples are, the merger holds back the cues that may still continue, so that a cue joins its
 * continuation even when other cues come between them.
 *
 * <p>Instances are not thread-safe.
 */
public final class CueMerger {
    /**
     * The most cues held back when joining across other cues.
     */
    private static final int MAX_PENDING = 64;

    /**
     * The receiver of the joined cues.
     */
    private final Consumer<SubtitleCue> output;

    /**
     * Whether a cue may join a cue that is not the one just before it.
     */
    private final boolean several;

    /**
     * The cues held back, in start order.
     */
    private final ArrayDeque<SubtitleCue> pending = new ArrayDeque<>();

    /**
     * Constructs a merger that joins a cue only with the cue just before it.
     *
     * @param output the receiver of the joined cues
     */
    public CueMerger(Consumer<SubtitleCue> output) {
        this(output, false);
    }

    /**
     * Constructs a merger.
     *
     * @param output the receiver of the joined cues
     * @param several whether a cue may join a held-back cue other than the one just before it
     */
    public CueMerger(Consumer<SubtitleCue> output, boolean several) {
        this.output = output;
        this.several = several;
    }

    /**
     * Adds the next cue, passing on the held-back cues that can no longer be joined.
     *
     * @param cue the cue, in start order
     */
    public void offer(SubtitleCue cue) {
        if (pending.isEmpty()) {
            pending.addLast(cue);
            return;
        }
        if (several) {
            var extended = false;
            var copy = new ArrayDeque<SubtitleCue>(pending.size() + 1);
            for (var held : pending) {
                if (!extended && joins(held, cue)) {
                    copy.addLast(held.withTiming(held.startMicros(), cue.endMicros()));
                    extended = true;
                } else if (held.durationMicros() == 0 && cue.startMicros() > held.startMicros()) {
                    copy.addLast(held.withTiming(held.startMicros(), cue.startMicros()));
                } else {
                    copy.addLast(held);
                }
            }
            pending.clear();
            pending.addAll(copy);
            while (!pending.isEmpty() && (pending.peekFirst().endMicros() < cue.startMicros()
                    || pending.size() >= MAX_PENDING)) {
                output.accept(pending.pollFirst());
            }
            if (!extended) {
                pending.addLast(cue);
            }
            return;
        }
        var held = pending.pollFirst();
        if (joins(held, cue)) {
            pending.addLast(held.withTiming(held.startMicros(), cue.endMicros()));
            return;
        }
        if (held.durationMicros() == 0 && cue.startMicros() > held.startMicros()) {
            held = held.withTiming(held.startMicros(), cue.startMicros());
        }
        output.accept(held);
        pending.addLast(cue);
    }

    /**
     * Returns whether a cue continues a held-back one.
     *
     * @param held the held-back cue
     * @param cue the next cue
     * @return {@code true} when the next cue starts where the held-back one ends and shows the same thing
     */
    private static boolean joins(SubtitleCue held, SubtitleCue cue) {
        return held.endMicros() == cue.startMicros() && held.durationMicros() > 0 && held.text().equals(cue.text())
                && held.identifier().equals(cue.identifier()) && held.settings().equals(cue.settings());
    }

    /**
     * Passes on the cues held back.
     */
    public void finish() {
        while (!pending.isEmpty()) {
            output.accept(pending.pollFirst());
        }
    }
}
