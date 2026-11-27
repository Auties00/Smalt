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
 * A cue offered as open (from a container block without a duration) ends where the next cue starts; a
 * cue of an explicit zero duration keeps it. By default
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
    private final ArrayDeque<Held> pending = new ArrayDeque<>();

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
     * Adds the next cue, of a known duration, passing on the held-back cues that can no longer be joined.
     *
     * @param cue the cue, in start order
     */
    public void offer(SubtitleCue cue) {
        offer(cue, false);
    }

    /**
     * Adds the next cue, passing on the held-back cues that can no longer be joined.
     *
     * @param cue the cue, in start order
     * @param open whether the cue's duration is unknown, so that it ends where the next cue starts
     */
    public void offer(SubtitleCue cue, boolean open) {
        var next = new Held(cue, open && cue.durationMicros() == 0);
        if (pending.isEmpty()) {
            pending.addLast(next);
            return;
        }
        if (several) {
            var extended = false;
            var copy = new ArrayDeque<Held>(pending.size() + 1);
            for (var held : pending) {
                if (!extended && joins(held.cue(), cue)) {
                    copy.addLast(new Held(held.cue().withTiming(held.cue().startMicros(), cue.endMicros()), false));
                    extended = true;
                } else {
                    copy.addLast(held.closedBy(cue));
                }
            }
            pending.clear();
            pending.addAll(copy);
            while (!pending.isEmpty() && (pending.peekFirst().cue().endMicros() < cue.startMicros()
                    || pending.size() >= MAX_PENDING)) {
                output.accept(pending.pollFirst().cue());
            }
            if (!extended) {
                pending.addLast(next);
            }
            return;
        }
        var held = pending.pollFirst();
        if (joins(held.cue(), cue)) {
            pending.addLast(new Held(held.cue().withTiming(held.cue().startMicros(), cue.endMicros()), false));
            return;
        }
        output.accept(held.closedBy(cue).cue());
        pending.addLast(next);
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
            output.accept(pending.pollFirst().cue());
        }
    }

    /**
     * A held-back cue.
     *
     * @param cue the cue
     * @param open whether it ends where the next cue starts
     */
    private record Held(SubtitleCue cue, boolean open) {
        /**
         * Returns this cue ended by the start of the next one when it is open and the next one starts later.
         */
        Held closedBy(SubtitleCue next) {
            if (!open || next.startMicros() <= cue.startMicros()) {
                return this;
            }
            return new Held(cue.withTiming(cue.startMicros(), next.startMicros()), false);
        }
    }
}
