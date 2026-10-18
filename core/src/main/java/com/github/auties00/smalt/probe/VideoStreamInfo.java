package com.github.auties00.smalt.probe;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Describes a video stream of a probed file.
 *
 * <p>The values come from the container and from the codec configuration record (the H.264
 * sequence parameter set in {@code avcC}, {@code hvcC}, {@code av1C} and its sequence header,
 * {@code vpcC}); when a track has no configuration record, from the header of its first key
 * frame. Instances are immutable and thread-safe.
 */
public final class VideoStreamInfo implements StreamInfo {
    /**
     * The shared fields.
     */
    private final StreamFields fields;

    /**
     * The codec.
     */
    private final Codec codec;

    /**
     * The profile name, or {@code null}.
     */
    private final String profile;

    /**
     * The level code, or {@code -1}.
     */
    private final int level;

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
     * The frame rate, or {@code null}.
     */
    private final Rational frameRate;

    /**
     * The sample aspect ratio, or {@code null}.
     */
    private final Rational sampleAspectRatio;

    /**
     * The color description, or {@code null}.
     */
    private final ColorInfo color;

    /**
     * The luma bit depth, or {@code 0}.
     */
    private final int bitDepth;

    /**
     * The chroma subsampling, or {@code null}.
     */
    private final ChromaFormat chromaFormat;

    /**
     * Constructs a description.
     *
     * @param fields            the shared fields
     * @param codec             the codec of the stream
     * @param profile           the profile name, or {@code null}
     * @param level             the level code, or {@code -1}
     * @param width             the stored width
     * @param height            the stored height
     * @param orientation       the display orientation
     * @param frameRate         the frame rate, or {@code null}
     * @param sampleAspectRatio the sample aspect ratio, or {@code null}
     * @param color             the color description, or {@code null}
     * @param bitDepth          the luma bit depth, or {@code 0}
     * @param chromaFormat      the chroma subsampling, or {@code null}
     */
    VideoStreamInfo(StreamFields fields, Codec codec, String profile, int level, int width, int height,
                    Orientation orientation, Rational frameRate, Rational sampleAspectRatio, ColorInfo color, int bitDepth,
                    ChromaFormat chromaFormat) {
        this.fields = fields;
        this.codec = codec;
        this.profile = profile;
        this.level = level;
        this.width = width;
        this.height = height;
        this.orientation = orientation;
        this.frameRate = frameRate;
        this.sampleAspectRatio = sampleAspectRatio;
        this.color = color;
        this.bitDepth = bitDepth;
        this.chromaFormat = chromaFormat;
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

    @Override
    public OptionalLong frameCount() {
        return fields.optionalFrameCount();
    }

    @Override
    public Map<String, String> tags() {
        return fields.tags();
    }

    /**
     * Returns the codec of the stream.
     *
     * @return the codec
     */
    public Codec codec() {
        return codec;
    }

    /**
     * Returns the profile the configuration record declares, spelled as the codec specification
     * and ffprobe spell it, for example {@code "High"} (H.264), {@code "Main 10"} (HEVC),
     * {@code "Main"} (AV1) or {@code "Profile 0"} (VP9).
     *
     * @return the profile, or empty when unknown
     */
    public Optional<String> profile() {
        return Optional.ofNullable(profile);
    }

    /**
     * Returns the level the configuration record declares, as its codec codes it: H.264
     * {@code level_idc} ({@code 31} for level 3.1), HEVC {@code general_level_idc} ({@code 93}
     * for level 3.1), AV1 {@code seq_level_idx} ({@code 5} for level 3.1) or the VP9 level
     * ({@code 31}).
     *
     * @return the level code, or empty when unknown
     */
    public OptionalInt level() {
        return level < 0 ? OptionalInt.empty() : OptionalInt.of(level);
    }

    /**
     * Returns the width of the stored pictures.
     *
     * @return the width in pixels, before the orientation and the sample aspect ratio are applied
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height of the stored pictures.
     *
     * @return the height in pixels, before the orientation and the sample aspect ratio are applied
     */
    public int height() {
        return height;
    }

    /**
     * Returns the width a player displays: the stored width stretched by the sample aspect ratio,
     * then swapped with the height when the orientation rotates by a quarter turn.
     *
     * @return the displayed width in pixels
     */
    public int displayWidth() {
        return orientation.swapsDimensions() ? height : stretchedWidth();
    }

    /**
     * Returns the height a player displays: the stored height, swapped with the stretched width
     * when the orientation rotates by a quarter turn.
     *
     * @return the displayed height in pixels
     */
    public int displayHeight() {
        return orientation.swapsDimensions() ? stretchedWidth() : height;
    }

    /**
     * Returns the stored width stretched by the sample aspect ratio.
     *
     * @return the width in square pixels
     */
    private int stretchedWidth() {
        if (sampleAspectRatio == null || sampleAspectRatio.numerator() <= 0 || sampleAspectRatio.denominator() <= 0) {
            return width;
        }
        return (int) Math.round((double) width * sampleAspectRatio.numerator() / sampleAspectRatio.denominator());
    }

    /**
     * Returns how the stored pictures are rotated or mirrored for display, from the MP4 track
     * matrix or the container's rotation metadata.
     *
     * @return the orientation, {@link Orientation#NORMAL} when none is declared
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns the frame rate.
     *
     * @return the frames per second as a fraction, or empty when unknown
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    /**
     * Returns the shape of the stored pixels.
     *
     * @return the width of a pixel relative to its height, or empty when square or undeclared
     */
    public Optional<Rational> sampleAspectRatio() {
        return Optional.ofNullable(sampleAspectRatio);
    }

    /**
     * Returns the color description: range, matrix, primaries, transfer, chroma siting and
     * high-dynamic-range metadata.
     *
     * @return the color description, or empty when neither the container nor the configuration
     *         record declares one
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
    }

    /**
     * Returns the bit depth of the luma samples.
     *
     * @return 8, 10, 12 or 16, or empty when unknown
     */
    public OptionalInt bitDepth() {
        return bitDepth <= 0 ? OptionalInt.empty() : OptionalInt.of(bitDepth);
    }

    /**
     * Returns the chroma subsampling.
     *
     * @return the chroma format, or empty when unknown
     */
    public Optional<ChromaFormat> chromaFormat() {
        return Optional.ofNullable(chromaFormat);
    }

    @Override
    public String toString() {
        return "VideoStreamInfo[" + fields.describe() + ", codec=" + codec + (profile != null ? ", profile=" + profile : "")
                + (level >= 0 ? ", level=" + level : "") + ", size=" + width + "x" + height + ", orientation=" + orientation
                + (frameRate != null ? ", frameRate=" + frameRate : "")
                + (sampleAspectRatio != null ? ", sampleAspectRatio=" + sampleAspectRatio : "")
                + (bitDepth > 0 ? ", bitDepth=" + bitDepth : "") + (chromaFormat != null ? ", chroma=" + chromaFormat : "")
                + (color != null ? ", color=" + color : "") + "]";
    }
}
