package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of {@link Thumbnails}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ThumbnailOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final ThumbnailOptions DEFAULTS = new ThumbnailOptions(320, 320, ResizeFilter.BICUBIC, true, false,
            MediaLimits.DEFAULT);

    /**
     * The options of {@link #fullSize()}.
     */
    private static final ThumbnailOptions FULL_SIZE = DEFAULTS.toBuilder().fullSize().build();

    /**
     * The largest width, or {@code 0} for no bound.
     */
    private final int maxWidth;

    /**
     * The largest height, or {@code 0} for no bound.
     */
    private final int maxHeight;

    private final ResizeFilter filter;
    private final boolean upright;
    private final boolean bestFrame;
    private final MediaLimits limits;

    private ThumbnailOptions(int maxWidth, int maxHeight, ResizeFilter filter, boolean upright, boolean bestFrame,
                             MediaLimits limits) {
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.filter = filter;
        this.upright = upright;
        this.bestFrame = bestFrame;
        this.limits = limits;
    }

    /**
     * Returns thumbnails that fit within 320 by 320 pixels, scaled with
     * {@link ResizeFilter#BICUBIC}, turned upright, taken at exactly the requested time, under
     * {@link MediaLimits#defaults()}.
     *
     * @return the default options
     */
    public static ThumbnailOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns thumbnails that fit within a box, otherwise as {@link #defaults()}.
     *
     * @param maxWidth  the largest width in pixels, positive
     * @param maxHeight the largest height in pixels, positive
     * @return the options
     * @throws IllegalArgumentException if a bound is not positive
     */
    public static ThumbnailOptions fitting(int maxWidth, int maxHeight) {
        return DEFAULTS.toBuilder().maxSize(maxWidth, maxHeight).build();
    }

    /**
     * Returns full-size frames: no scaling, turned upright, as {@link #defaults()} otherwise.
     *
     * @return the options
     */
    public static ThumbnailOptions fullSize() {
        return FULL_SIZE;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the largest width of a thumbnail, in pixels, or 0 when the thumbnail keeps the size of
     * the picture; 320 by default}
     */
    public int maxWidth() {
        return maxWidth;
    }

    /**
     * {@return the largest height of a thumbnail, in pixels, or 0 when the thumbnail keeps the size of
     * the picture; 320 by default}
     */
    public int maxHeight() {
        return maxHeight;
    }

    /**
     * {@return the filter that scales pictures down, pictures never being scaled up;
     * {@link ResizeFilter#BICUBIC} by default}
     */
    public ResizeFilter filter() {
        return filter;
    }

    /**
     * {@return whether the picture is turned upright according to the track's or the image's
     * orientation before it is returned; {@code true} by default}
     */
    public boolean upright() {
        return upright;
    }

    /**
     * {@return whether a representative frame is chosen instead of the frame displayed at the requested
     * time; {@code false} by default}
     *
     * <p>The representative frame is, among the frames of the following two seconds (at most 60),
     * the first one that is neither nearly black nor nearly flat, or else the one with the most luma
     * contrast.
     */
    public boolean bestFrame() {
        return bestFrame;
    }

    /**
     * {@return the resource bounds of the demuxer and decoders; {@link MediaLimits#defaults()} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ThumbnailOptions that && maxWidth == that.maxWidth && maxHeight == that.maxHeight
                && filter == that.filter && upright == that.upright && bestFrame == that.bestFrame
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxWidth, maxHeight, filter, upright, bestFrame, limits);
    }

    @Override
    public String toString() {
        return "ThumbnailOptions[maxWidth=" + maxWidth + ", maxHeight=" + maxHeight + ", filter=" + filter + ", upright="
                + upright + ", bestFrame=" + bestFrame + ", limits=" + limits + "]";
    }

    /**
     * Assembles {@link ThumbnailOptions} setting by setting.
     *
     * <p>Setters reject {@code null} and store numbers unchecked; {@link #build()} checks the ranges.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private int maxWidth;
        private int maxHeight;

        /**
         * Whether {@link #maxWidth} and {@link #maxHeight} bound the thumbnail; {@code false} after
         * {@link #fullSize()}.
         */
        private boolean bounded;

        private ResizeFilter filter;
        private boolean upright;
        private boolean bestFrame;
        private MediaLimits limits;

        private Builder(ThumbnailOptions options) {
            this.maxWidth = options.maxWidth;
            this.maxHeight = options.maxHeight;
            this.bounded = options.maxWidth > 0;
            this.filter = options.filter;
            this.upright = options.upright;
            this.bestFrame = options.bestFrame;
            this.limits = options.limits;
        }

        /**
         * Sets the bounding box; see {@link ThumbnailOptions#maxWidth()} and
         * {@link ThumbnailOptions#maxHeight()}.
         *
         * @param maxWidth the largest width in pixels, positive
         * @param maxHeight the largest height in pixels, positive
         * @return this builder
         */
        public Builder maxSize(int maxWidth, int maxHeight) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.bounded = true;
            return this;
        }

        /**
         * Removes the bounding box, so the thumbnail keeps the size of the picture; see
         * {@link ThumbnailOptions#maxWidth()}.
         *
         * @return this builder
         */
        public Builder fullSize() {
            this.bounded = false;
            return this;
        }

        /**
         * Sets the scaling filter; see {@link ThumbnailOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(ResizeFilter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets whether the picture is turned upright; see {@link ThumbnailOptions#upright()}.
         *
         * @param upright the value
         * @return this builder
         */
        public Builder upright(boolean upright) {
            this.upright = upright;
            return this;
        }

        /**
         * Sets whether a representative frame is chosen; see {@link ThumbnailOptions#bestFrame()}.
         *
         * @param bestFrame the value
         * @return this builder
         */
        public Builder bestFrame(boolean bestFrame) {
            this.bestFrame = bestFrame;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link ThumbnailOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a bound of the box set by {@link #maxSize(int, int)} is
         * not positive
         */
        public ThumbnailOptions build() {
            if (!bounded) {
                return new ThumbnailOptions(0, 0, filter, upright, bestFrame, limits);
            }
            return new ThumbnailOptions(Checks.positive("maximum width", maxWidth),
                    Checks.positive("maximum height", maxHeight), filter, upright, bestFrame, limits);
        }
    }
}
