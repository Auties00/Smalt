package com.github.auties00.smalt.image.jp2;

import java.util.Objects;

/**
 * Holds the decoded samples of one channel of a JPEG 2000 image: a codestream component, or a
 * channel a JP2 palette produced from one.
 *
 * <p>Samples keep the precision and signedness the file declares, for example 12-bit unsigned
 * values from 0 to 4095 or 16-bit signed values from -32768 to 32767. A subsampled channel covers
 * the image at its own resolution: its sample {@code (x, y)} sits at reference grid position
 * {@code (x0 + x) * subsamplingX}, {@code (y0 + y) * subsamplingY} of the (reduced) image.
 *
 * <p>A channel holds its samples in {@link #samples()}, except a decoded codestream component wider than 31 bits
 * (up to 38 bits), which holds them in {@link #wideSamples()}. Exactly one of the two arrays is present.
 *
 * <p>The record holds the sample array itself, not a copy, and compares it by identity.
 *
 * @param x0 the left edge of the channel on its sample grid
 * @param y0 the top edge of the channel on its sample grid
 * @param width the width in samples
 * @param height the height in samples
 * @param subsamplingX the horizontal subsampling factor, at least 1: from 1 to 255 for a codestream component,
 * larger for a component a JPX codestream registration scales
 * @param subsamplingY the vertical subsampling factor, at least 1, as for {@code subsamplingX}
 * @param precision the bits per sample, from 1 to 38 (32 at most in {@code samples})
 * @param signed whether the samples are signed
 * @param type the channel's role
 * @param association the color the channel belongs to: 1 for the first color of the color space, 2 for the second
 * and so on, 0 for the whole image, -1 when unspecified
 * @param samples the samples, row-major, {@code width * height} of them, or {@code null} for a channel wider than 31
 * bits
 * @param wideSamples the samples of a channel wider than 31 bits, row-major, or {@code null}
 */
public record Jp2Channel(long x0, long y0, int width, int height, int subsamplingX, int subsamplingY, int precision,
                         boolean signed, Jp2ChannelType type, int association, int[] samples, long[] wideSamples) {
    /**
     * Validates the channel.
     *
     * @throws NullPointerException     if {@code type} is {@code null}, or both sample arrays are
     * @throws IllegalArgumentException if a dimension or the position is negative, both sample arrays are given,
     *                                  the sample count does not match the dimensions, or the precision is out of
     *                                  range
     */
    public Jp2Channel {
        Objects.requireNonNull(type, "type");
        if (samples == null && wideSamples == null) {
            throw new NullPointerException("samples");
        }
        if (samples != null && wideSamples != null) {
            throw new IllegalArgumentException("a channel holds either samples or wide samples, not both");
        }
        var count = samples != null ? samples.length : wideSamples.length;
        if (width < 0 || height < 0 || x0 < 0 || y0 < 0 || (long) width * height != count) {
            throw new IllegalArgumentException("invalid channel size " + width + "x" + height + " for "
                    + count + " samples");
        }
        if (precision < 1 || precision > (samples != null ? 32 : 38)) {
            throw new IllegalArgumentException("invalid channel precision " + precision);
        }
    }

    /**
     * Constructs a channel of up to 32 bits.
     *
     * @param x0           the left edge of the channel on its sample grid
     * @param y0           the top edge of the channel on its sample grid
     * @param width        the width in samples
     * @param height       the height in samples
     * @param subsamplingX the horizontal subsampling factor
     * @param subsamplingY the vertical subsampling factor
     * @param precision    the bits per sample
     * @param signed       whether the samples are signed
     * @param type         the channel's role
     * @param association  the color the channel belongs to
     * @param samples      the samples, row-major
     * @throws IllegalArgumentException if a dimension or the position is negative, the sample count does not
     *                                  match the dimensions, or the precision is out of range
     */
    public Jp2Channel(long x0, long y0, int width, int height, int subsamplingX, int subsamplingY, int precision,
                      boolean signed, Jp2ChannelType type, int association, int[] samples) {
        this(x0, y0, width, height, subsamplingX, subsamplingY, precision, signed, type, association,
                Objects.requireNonNull(samples, "samples"), null);
    }

    /**
     * Returns one sample.
     *
     * @param x the column
     * @param y the row
     * @return the sample
     * @throws IndexOutOfBoundsException if the position is outside the channel
     */
    public long sample(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        var index = y * width + x;
        return samples != null ? samples[index] : wideSamples[index];
    }

    /**
     * {@return whether the channel holds its samples in {@link #wideSamples()}}
     */
    public boolean wide() {
        return wideSamples != null;
    }
}
