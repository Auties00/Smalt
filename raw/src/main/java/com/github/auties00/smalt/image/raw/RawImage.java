package com.github.auties00.smalt.image.raw;

import java.util.Objects;
import java.util.Optional;

/**
 * Holds the sensor image of a camera raw file before demosaicing and color rendering: one sample
 * per photosite of a color filter array, or three per pixel of linear raw data, as linear 16-bit
 * values in which 0 is the black level and 65535 the white level.
 *
 * <p>The values are the DNG "linear reference values" of the default crop (or of the whole active
 * area): stored samples after the linearization table, black level subtraction and white level
 * scaling, and after the DNG opcode lists 1 and 2 when the options apply them. Values below black
 * or above white are clipped. The color description maps them to CIE XYZ: divide each channel by
 * {@link #cameraNeutral()} to white balance it, then multiply by {@link #balancedToXyz()}.
 *
 * <p>Instances are immutable and thread-safe, except that {@link #samples()} returns the image's own
 * array, which callers must not modify while others use the image.
 */
public final class RawImage {
    private final int width;

    private final int height;

    private final int channels;

    private final short[] samples;

    /**
     * The color filter array, or {@code null}.
     */
    private final CfaPattern cfa;

    /**
     * The camera neutral.
     */
    private final double[] neutral;

    private final double[] balancedToXyz;

    private final CfaLayout layout;

    /**
     * Constructs an image; {@link RawDecoder} creates instances.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples of each pixel, 1 or 3
     * @param samples the 16-bit samples, row by row with the channels of a pixel interleaved; not copied
     * @param cfa the color filter array, or {@code null}
     * @param neutral the camera neutral
     * @param balancedToXyz the white-balanced camera to XYZ matrix, or an empty array
     * @param layout the spatial layout of the color filter array
     */
    RawImage(int width, int height, int channels, short[] samples, CfaPattern cfa, double[] neutral,
             double[] balancedToXyz, CfaLayout layout) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.samples = Objects.requireNonNull(samples, "samples");
        this.cfa = cfa;
        this.neutral = neutral.clone();
        this.balancedToXyz = balancedToXyz.clone();
        this.layout = Objects.requireNonNull(layout, "layout");
    }

    /**
     * {@return the width in pixels}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the height in pixels}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the number of samples of each pixel: 1 for color filter array and monochrome images, 3
     * for linear raw images}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the image's own sample array, of unsigned 16-bit values}
     *
     * <p>The sample {@code c} of the pixel at row {@code y} and column {@code x} is
     * {@code samples()[(y * width() + x) * channels() + c] & 0xFFFF}.
     */
    public short[] samples() {
        return samples;
    }

    /**
     * {@return the color filter array, aligned so that its top-left photosite is the image's top-left
     * pixel, or empty for linear raw and monochrome images}
     */
    public Optional<CfaPattern> cfaPattern() {
        return Optional.ofNullable(cfa);
    }

    /**
     * {@return the spatial layout of the color filter array's photosites, aligned as
     * {@link #cfaPattern()} is: {@link CfaLayout#RECTANGULAR} for most sensors and for linear raw and
     * monochrome images, a staggered layout for sensors whose photosites are offset by half a row or
     * column}
     */
    public CfaLayout cfaLayout() {
        return layout;
    }

    /**
     * {@return a copy of the camera's response to a neutral under the shot's light (the white balance
     * the file records, scaled so its largest component is 1), one component per color plane: red,
     * green and blue, or the four colors of a four-color sensor in the order of the file's color
     * planes}
     */
    public double[] cameraNeutral() {
        return neutral.clone();
    }

    /**
     * {@return a copy of the matrix that maps white-balanced camera values (each channel divided by
     * {@link #cameraNeutral()}) to CIE XYZ relative to a D50 white, row by row: three rows of one
     * column per color plane, nine values or twelve for a four-color sensor; empty for a four-color
     * sensor whose file carries no color matrices when the options supply none}
     */
    public double[] balancedToXyz() {
        return balancedToXyz.clone();
    }
}
