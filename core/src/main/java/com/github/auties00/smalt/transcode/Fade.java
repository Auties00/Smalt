package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A fade of the output: audio rising from or falling to silence, video from or to a color.
 *
 * <p>Times are on the output timeline, which starts at zero at the start of the kept range and runs at the output's
 * speed ({@link TranscodeOptions#speed()}). A fade-in that starts at {@code s} and lasts {@code d} gives silence (or
 * the fade color) before {@code s}, the {@linkplain #curve() curve} between {@code s} and {@code s + d}, and the
 * media unchanged after; a fade-out gives the media unchanged before {@code s}, the curve backwards, and silence (or
 * the color) after {@code s + d}. A fade-out made with {@link #out(long)} ends at the end of the output, which the
 * transcode takes from the kept range or, when the range is open, from the duration the input declares. Several
 * fades of a stream multiply their gains.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Fade {
    /**
     * Enumerates the directions of a fade.
     */
    public enum Direction {
        /**
         * From silence or the fade color to the media.
         */
        IN,

        /**
         * From the media to silence or the fade color.
         */
        OUT
    }

    private final Direction direction;

    /**
     * The start in microseconds, or {@code -1} for a fade-out that ends at the end of the output.
     */
    private final long startMicros;

    private final long durationMicros;
    private final FadeCurve curve;

    private Fade(Direction direction, long startMicros, long durationMicros, FadeCurve curve) {
        this.direction = direction;
        this.startMicros = startMicros;
        this.durationMicros = durationMicros;
        this.curve = curve;
    }

    /**
     * Returns a linear fade-in at the start of the output.
     *
     * @param durationMicros the duration in microseconds, positive
     * @return the fade
     * @throws IllegalArgumentException if the duration is not positive
     */
    public static Fade in(long durationMicros) {
        return new Fade(Direction.IN, 0, Checks.positive("fade duration", durationMicros), FadeCurve.LINEAR);
    }

    /**
     * Returns a linear fade-in at a time of the output.
     *
     * @param startMicros the start in microseconds, zero or more
     * @param durationMicros the duration in microseconds, positive
     * @return the fade
     * @throws IllegalArgumentException if the start is negative or the duration is not positive
     */
    public static Fade in(long startMicros, long durationMicros) {
        return new Fade(Direction.IN, Checks.nonNegative("fade start", startMicros),
                Checks.positive("fade duration", durationMicros), FadeCurve.LINEAR);
    }

    /**
     * Returns a linear fade-out that ends at the end of the output.
     *
     * @param durationMicros the duration in microseconds, positive
     * @return the fade
     * @throws IllegalArgumentException if the duration is not positive
     */
    public static Fade out(long durationMicros) {
        return new Fade(Direction.OUT, -1, Checks.positive("fade duration", durationMicros), FadeCurve.LINEAR);
    }

    /**
     * Returns a linear fade-out at a time of the output.
     *
     * @param startMicros the start in microseconds, zero or more
     * @param durationMicros the duration in microseconds, positive
     * @return the fade
     * @throws IllegalArgumentException if the start is negative or the duration is not positive
     */
    public static Fade out(long startMicros, long durationMicros) {
        return new Fade(Direction.OUT, Checks.nonNegative("fade start", startMicros),
                Checks.positive("fade duration", durationMicros), FadeCurve.LINEAR);
    }

    /**
     * Returns this fade with another curve.
     *
     * @param curve the shape of the gain
     * @return the fade
     */
    public Fade withCurve(FadeCurve curve) {
        return new Fade(direction, startMicros, durationMicros, Objects.requireNonNull(curve, "curve"));
    }

    /**
     * {@return the direction}
     */
    public Direction direction() {
        return direction;
    }

    /**
     * {@return the start on the output timeline in microseconds, or empty for a fade-out that ends at the end of the
     * output}
     */
    public OptionalLong startMicros() {
        return startMicros < 0 ? OptionalLong.empty() : OptionalLong.of(startMicros);
    }

    /**
     * {@return the duration in microseconds}
     */
    public long durationMicros() {
        return durationMicros;
    }

    /**
     * {@return the shape of the gain over the fade, {@link FadeCurve#LINEAR} by default}
     */
    public FadeCurve curve() {
        return curve;
    }

    /**
     * {@return whether the fade's position depends on the output duration: a fade-out without a start}
     */
    public boolean endsWithOutput() {
        return startMicros < 0;
    }

    /**
     * Returns the gain of the fade at a time of the output.
     *
     * @param timeMicros the output time in microseconds
     * @param outputMicros the output duration in microseconds, which places a fade-out without a start, or
     * {@code -1} when unknown
     * @return the gain, from 0 to 1
     * @throws IllegalArgumentException if the fade ends with the output and its duration is unknown
     */
    public double gain(double timeMicros, long outputMicros) {
        var start = (double) startMicros;
        if (startMicros < 0) {
            if (outputMicros < 0) {
                throw new IllegalArgumentException("a fade-out at the end of the output needs the output duration");
            }
            start = outputMicros - durationMicros;
        }
        var fraction = (timeMicros - start) / durationMicros;
        return direction == Direction.IN ? curve.gain(fraction) : curve.gain(1 - fraction);
    }

    /**
     * Returns the product of the gains of several fades at a time of the output.
     *
     * @param fades the fades to combine
     * @param timeMicros the output time in microseconds
     * @param outputMicros the output duration in microseconds, or {@code -1} when unknown
     * @return the gain, from 0 to 1
     */
    static double gain(List<Fade> fades, double timeMicros, long outputMicros) {
        var gain = 1.0;
        for (var fade : fades) {
            gain *= fade.gain(timeMicros, outputMicros);
        }
        return gain;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fade that && direction == that.direction && startMicros == that.startMicros
                && durationMicros == that.durationMicros && curve == that.curve;
    }

    @Override
    public int hashCode() {
        return Objects.hash(direction, startMicros, durationMicros, curve);
    }

    @Override
    public String toString() {
        return "Fade[" + direction + (startMicros < 0 ? ", at the end" : ", start=" + startMicros) + ", duration="
                + durationMicros + ", curve=" + curve + "]";
    }
}
