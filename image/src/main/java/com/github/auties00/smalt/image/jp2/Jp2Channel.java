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
 * <p>The record holds the sample array itself, not a copy, and compares it by identity.
 *
 * @param x0           the left edge of the channel on its sample grid
 * @param y0           the top edge of the channel on its sample grid
 * @param width        the width in samples
 * @param height       the height in samples
 * @param subsamplingX the horizontal subsampling factor, from 1 to 255
 * @param subsamplingY the vertical subsampling factor, from 1 to 255
 * @param precision    the bits per sample, from 1 to 31 (palette channels up to 32)
 * @param signed       whether the samples are signed
 * @param type         the channel's role
 * @param association  the color the channel belongs to: 1 for the first color of the color
 *                     space, 2 for the second and so on, 0 for the whole image, -1 when
 *                     unspecified
 * @param samples      the samples, row-major, {@code width * height} of them
 */
public record Jp2Channel(int x0, int y0, int width, int height, int subsamplingX, int subsamplingY, int precision,
                         boolean signed, Jp2ChannelType type, int association, int[] samples) {
    /**
     * Validates the channel.
     *
     * @throws NullPointerException     if {@code type} or {@code samples} is {@code null}
     * @throws IllegalArgumentException if a dimension is negative or the sample count does not
     *                                  match it
     */
    public Jp2Channel {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(samples, "samples");
        if (width < 0 || height < 0 || (long) width * height != samples.length) {
            throw new IllegalArgumentException("invalid channel size " + width + "x" + height + " for "
                    + samples.length + " samples");
        }
    }

    /**
     * Returns one sample.
     *
     * @param x the column
     * @param y the row
     * @return the sample
     * @throws IndexOutOfBoundsException if the position is outside the channel
     */
    public int sample(int x, int y) {
        Objects.checkIndex(x, width);
        Objects.checkIndex(y, height);
        return samples[y * width + x];
    }
}
