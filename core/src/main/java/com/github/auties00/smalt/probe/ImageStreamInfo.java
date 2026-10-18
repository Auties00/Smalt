package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Describes the picture of a probed image file, still or animated.
 *
 * <p>The values come from the image headers only (PNG chunks, JPEG markers, GIF blocks, WebP and
 * HEIF boxes, TIFF directories, the EXIF orientation); no pixel is decoded. Instances are
 * immutable and thread-safe.
 */
public final class ImageStreamInfo implements StreamInfo {
    /**
     * The shared fields.
     */
    private final StreamFields fields;

    /**
     * The image format.
     */
    private final ImageFormat format;

    /**
     * The stored width.
     */
    private final int width;

    /**
     * The stored height.
     */
    private final int height;

    /**
     * The display orientation.
     */
    private final Orientation orientation;

    /**
     * The bits per sample, or {@code 0}.
     */
    private final int bitDepth;

    /**
     * Whether the image declares transparency.
     */
    private final boolean alpha;

    /**
     * Whether the image animates.
     */
    private final boolean animated;

    /**
     * The number of plays of an animation, {@code 0} for forever, or {@code -1}.
     */
    private final int loopCount;

    /**
     * Constructs a description.
     *
     * @param fields      the shared fields, whose frame count is the number of frames
     * @param format      the image format
     * @param width       the stored width
     * @param height      the stored height
     * @param orientation the display orientation
     * @param bitDepth    the bits per sample, or {@code 0}
     * @param alpha       whether the image declares transparency
     * @param animated    whether the image animates
     * @param loopCount   the number of plays, {@code 0} for forever, or {@code -1}
     */
    ImageStreamInfo(StreamFields fields, ImageFormat format, int width, int height, Orientation orientation, int bitDepth,
                    boolean alpha, boolean animated, int loopCount) {
        this.fields = fields;
        this.format = format;
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.bitDepth = bitDepth;
        this.alpha = alpha;
        this.animated = animated;
        this.loopCount = loopCount;
    }

    @Override
    public int index() {
        return fields.index();
    }

    @Override
    public OptionalInt trackId() {
        return fields.optionalTrackId();
    }

    @Override
    public Optional<String> language() {
        return fields.optionalLanguage();
    }

    @Override
    public OptionalLong durationMicros() {
        return fields.optionalDuration();
    }

    @Override
    public OptionalLong bitrate() {
        return fields.optionalBitRate();
    }

    /**
     * {@inheritDoc}
     *
     * @return the number of frames: {@code 1} for a still image, the declared or counted frame
     *         count of an animation (or the page count of a multi-page TIFF), empty when it cannot
     *         be known from the headers
     */
    @Override
    public OptionalLong frameCount() {
        return fields.optionalFrameCount();
    }

    @Override
    public Map<String, String> tags() {
        return fields.tags();
    }

    /**
     * Returns the image format.
     *
     * @return the format
     */
    public ImageFormat format() {
        return format;
    }

    /**
     * Returns the stored width.
     *
     * @return the width in pixels, before the orientation is applied
     */
    public int width() {
        return width;
    }

    /**
     * Returns the stored height.
     *
     * @return the height in pixels, before the orientation is applied
     */
    public int height() {
        return height;
    }

    /**
     * Returns the width after applying the orientation.
     *
     * @return the displayed width in pixels
     */
    public int displayWidth() {
        return orientation.swapsDimensions() ? height : width;
    }

    /**
     * Returns the height after applying the orientation.
     *
     * @return the displayed height in pixels
     */
    public int displayHeight() {
        return orientation.swapsDimensions() ? width : height;
    }

    /**
     * Returns the display orientation, from EXIF or the format's own rotation and mirror boxes.
     *
     * @return the orientation, {@link Orientation#NORMAL} when none is declared
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns the number of bits of each sample of each channel, as stored: 1, 2, 4, 8 or 16 for
     * PNG, 8 or 12 for JPEG, 8, 10 or 12 for AVIF and HEIC.
     *
     * @return the bit depth, or empty when the headers do not state it
     */
    public OptionalInt bitDepth() {
        return bitDepth <= 0 ? OptionalInt.empty() : OptionalInt.of(bitDepth);
    }

    /**
     * Returns whether the image declares transparency: an alpha channel, a transparent palette
     * entry or color, or an alpha auxiliary image.
     *
     * @return {@code true} when the image may be transparent
     */
    public boolean hasAlpha() {
        return alpha;
    }

    /**
     * Returns whether the image animates: an animated GIF, APNG or WebP with more than one frame.
     *
     * @return {@code true} for an animation
     */
    public boolean isAnimated() {
        return animated;
    }

    /**
     * Returns how many times an animation plays.
     *
     * @return the number of plays, where {@code 0} means forever, or empty for a still image and
     *         when the format does not say
     */
    public OptionalInt loopCount() {
        return loopCount < 0 ? OptionalInt.empty() : OptionalInt.of(loopCount);
    }

    @Override
    public String toString() {
        return "ImageStreamInfo[" + fields.describe() + ", format=" + format + ", size=" + width + "x" + height
                + ", orientation=" + orientation + (bitDepth > 0 ? ", bitDepth=" + bitDepth : "") + ", alpha=" + alpha
                + ", animated=" + animated + (loopCount >= 0 ? ", loopCount=" + loopCount : "") + "]";
    }
}
