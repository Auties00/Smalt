package com.github.auties00.smalt.image.ico;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes an animated cursor (ANI) as its {@code anih}, {@code rate}, {@code seq } and
 * {@code INFO} chunks declare it.
 *
 * <p>The file stores {@link #frameCount()} frames, each a complete icon or cursor file, and plays
 * {@link #steps()} in order, forever. A step shows the frame its {@code seq } entry names, or the
 * frame of its own index when the file has no {@code seq } chunk, for the time its {@code rate}
 * entry gives, or {@link #displayRate()} when the file has no {@code rate} chunk.
 *
 * @param frameCount the number of stored frames, positive
 * @param steps the displayed steps, in order, at least one; unmodifiable
 * @param displayRate the default display time of a step, in jiffies (sixtieths of a second)
 * @param title the title of the {@code INAM} entry, or empty when absent
 * @param artist the author of the {@code IART} entry, or empty when absent
 */
public record AniHeader(int frameCount, List<AniStep> steps, long displayRate, Optional<String> title,
                        Optional<String> artist) {
    /**
     * Validates the header and copies the steps.
     *
     * @throws NullPointerException if a step is {@code null}
     * @throws IllegalArgumentException if {@code frameCount} is not positive, {@code steps} is empty, a
     * step names a frame at or past {@code frameCount}, or {@code displayRate} is outside 0 to
     * {@value AniStep#MAX_JIFFIES}
     */
    public AniHeader {
        steps = List.copyOf(steps);
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(artist, "artist");
        if (frameCount <= 0) {
            throw new IllegalArgumentException("an animated cursor needs at least one frame, not " + frameCount);
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("an animated cursor needs at least one step");
        }
        for (var step : steps) {
            if (step.frame() >= frameCount) {
                throw new IllegalArgumentException("step frame " + step.frame() + " is outside 0.." + (frameCount - 1));
            }
        }
        if (displayRate < 0 || displayRate > AniStep.MAX_JIFFIES) {
            throw new IllegalArgumentException("display rate " + displayRate + " is outside 0.." + AniStep.MAX_JIFFIES);
        }
    }
}
