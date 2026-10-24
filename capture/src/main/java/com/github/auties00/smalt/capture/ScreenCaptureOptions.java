package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a screen or window capture: the frame rate, the part of the source to capture and
 * whether the mouse pointer is drawn.
 *
 * <p>Instances are immutable and thread-safe; create them with {@link #builder()} or {@link #defaults()}.
 */
public final class ScreenCaptureOptions {
    /**
     * The default {@linkplain #frameRate() frame rate}, 30 frames per second.
     */
    public static final Rational DEFAULT_FRAME_RATE = new Rational(30, 1);

    /**
     * The options of {@link #defaults()}.
     */
    private static final ScreenCaptureOptions DEFAULTS = new Builder().build();

    private final Rational frameRate;

    /**
     * The region, or {@code null} for the whole source.
     */
    private final Region region;

    private final boolean cursor;

    private ScreenCaptureOptions(Builder builder) {
        this.frameRate = builder.frameRate;
        this.region = builder.region;
        this.cursor = builder.cursor;
    }

    /**
     * Returns the default options: 30 frames per second, the whole source, the pointer drawn.
     *
     * @return the default options
     */
    public static ScreenCaptureOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return a new builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the frames per second a session delivers; {@link #DEFAULT_FRAME_RATE} by default}
     *
     * <p>A session repeats its last picture, with a new timestamp, when the source did not change within a
     * frame interval, so frames arrive at this rate even while nothing moves. Rates above 1000 frames per
     * second are served at 1000.
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the part of the source to capture, or empty for the whole source; empty by default}
     *
     * <p>The region is in the pixels of the source's image, relative to its top-left corner: the screen's
     * pixels for a {@link CaptureDevice.Screen}, the window's for a {@link CaptureDevice.Window}. The part
     * of the region that lies on the source is captured; its size, cropped to even dimensions, is the size
     * of the delivered pictures.
     */
    public Optional<Region> region() {
        return Optional.ofNullable(region);
    }

    /**
     * {@return whether the mouse pointer is drawn into the pictures; {@code true} by default}
     *
     * <p>The screen captures of macOS before 12.3 never show the pointer, whatever this setting.
     */
    public boolean cursor() {
        return cursor;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof ScreenCaptureOptions that
               && frameRate.equals(that.frameRate)
               && Objects.equals(region, that.region)
               && cursor == that.cursor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(frameRate, region, cursor);
    }

    @Override
    public String toString() {
        return "ScreenCaptureOptions[frameRate=" + frameRate
               + ", region=" + (region == null ? "whole" : region)
               + ", cursor=" + cursor + "]";
    }

    /**
     * A rectangle of a source's image, in pixels relative to its top-left corner.
     *
     * @param x the left edge, at least 0
     * @param y the top edge, at least 0
     * @param width the width, positive
     * @param height the height, positive
     */
    public record Region(int x, int y, int width, int height) {
        /**
         * Validates the rectangle.
         *
         * @throws IllegalArgumentException if an edge is negative or a dimension is not positive
         */
        public Region {
            Checks.nonNegative("region x", x);
            Checks.nonNegative("region y", y);
            Checks.positive("region width", width);
            Checks.positive("region height", height);
        }

        /**
         * Returns the part of this region that lies on a source of a size.
         *
         * @param sourceWidth the width of the source, positive
         * @param sourceHeight the height of the source, positive
         * @return the intersection, or empty when the region lies outside the source
         */
        public Optional<Region> clip(int sourceWidth, int sourceHeight) {
            if (x >= sourceWidth || y >= sourceHeight) {
                return Optional.empty();
            }
            var right = (int) Math.min((long) x + width, sourceWidth);
            var bottom = (int) Math.min((long) y + height, sourceHeight);
            return Optional.of(new Region(x, y, right - x, bottom - y));
        }
    }

    /**
     * A builder of {@link ScreenCaptureOptions}. Instances are not thread-safe.
     */
    public static final class Builder {
        private Rational frameRate;

        private Region region;

        private boolean cursor;

        private Builder() {
            this.frameRate = DEFAULT_FRAME_RATE;
            this.cursor = true;
        }

        private Builder(ScreenCaptureOptions options) {
            this.frameRate = options.frameRate;
            this.region = options.region;
            this.cursor = options.cursor;
        }

        /**
         * Sets the frames per second; see {@link ScreenCaptureOptions#frameRate()}.
         *
         * @param frameRate the value, such as {@code 30000/1001} for 29.97 frames per second
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets an integer number of frames per second; see {@link ScreenCaptureOptions#frameRate()}.
         *
         * @param fps the value, positive
         * @return this builder
         * @throws IllegalArgumentException if {@code fps} is not positive
         */
        public Builder frameRate(int fps) {
            this.frameRate = new Rational(fps, 1);
            return this;
        }

        /**
         * Sets the part of the source to capture; see {@link ScreenCaptureOptions#region()}.
         *
         * @param region the rectangle
         * @return this builder
         */
        public Builder region(Region region) {
            this.region = Objects.requireNonNull(region, "region");
            return this;
        }

        /**
         * Sets the part of the source to capture; see {@link ScreenCaptureOptions#region()}.
         *
         * @param x the left edge, at least 0
         * @param y the top edge, at least 0
         * @param width the width, positive
         * @param height the height, positive
         * @return this builder
         * @throws IllegalArgumentException if an edge is negative or a dimension is not positive
         */
        public Builder region(int x, int y, int width, int height) {
            this.region = new Region(x, y, width, height);
            return this;
        }

        /**
         * Captures the whole source, the default; see {@link ScreenCaptureOptions#region()}.
         *
         * @return this builder
         */
        public Builder wholeSource() {
            this.region = null;
            return this;
        }

        /**
         * Sets whether the mouse pointer is drawn; see {@link ScreenCaptureOptions#cursor()}.
         *
         * @param cursor the value
         * @return this builder
         */
        public Builder cursor(boolean cursor) {
            this.cursor = cursor;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public ScreenCaptureOptions build() {
            return new ScreenCaptureOptions(this);
        }
    }
}
