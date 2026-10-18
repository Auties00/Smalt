package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;

import java.util.function.Consumer;

/**
 * Joins consecutive cues that show the same thing back to back, holding back one cue.
 *
 * <p>MP4 subtitle tracks repeat a cue in every sample during which it is shown, so a cue that
 * overlaps others reads back as several pieces; joining a cue with the next one when their text,
 * identifier and settings are equal and the next starts exactly where the first ends restores it.
 * A cue without a duration (a container block without one) ends where the next cue starts.
 *
 * <p>Instances are not thread-safe.
 */
public final class CueMerger {
    /**
     * The receiver of the joined cues.
     */
    private final Consumer<SubtitleCue> output;

    /**
     * The cue held back, or {@code null}.
     */
    private SubtitleCue pending;

    /**
     * Constructs a merger.
     *
     * @param output the receiver of the joined cues
     */
    public CueMerger(Consumer<SubtitleCue> output) {
        this.output = output;
    }

    /**
     * Adds the next cue, passing on the previous one when it cannot be joined with it.
     *
     * @param cue the cue, in start order
     */
    public void offer(SubtitleCue cue) {
        if (pending == null) {
            pending = cue;
            return;
        }
        if (pending.endMicros() == cue.startMicros() && pending.text().equals(cue.text())
                && pending.identifier().equals(cue.identifier()) && pending.settings().equals(cue.settings())
                && pending.durationMicros() > 0) {
            pending = pending.withTiming(pending.startMicros(), cue.endMicros());
            return;
        }
        if (pending.durationMicros() == 0 && cue.startMicros() > pending.startMicros()) {
            pending = pending.withTiming(pending.startMicros(), cue.startMicros());
        }
        output.accept(pending);
        pending = cue;
    }

    /**
     * Passes on the cue held back.
     */
    public void finish() {
        if (pending != null) {
            output.accept(pending);
            pending = null;
        }
    }
}
