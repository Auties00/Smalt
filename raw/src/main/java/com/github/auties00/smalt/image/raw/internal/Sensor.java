package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.CfaPattern;

/**
 * Describes the sensor image of a raw file the way DNG does, whatever the file's format: its
 * stored size and layout, the color filter array, the areas that hold image data, the levels that
 * map stored values to linear light, the opcode lists, and how to read the samples.
 *
 * <p>Every vendor parser fills one of these, so a single pipeline renders every format. Fields are
 * set by the parsers and read by the pipeline; an instance is confined to one decode.
 */
final class Sensor {
    /**
     * Reads the stored samples into a plane.
     */
    @FunctionalInterface
    interface Reader {
        /**
         * Reads the samples.
         *
         * @param plane the plane to fill, of the sensor's stored size
         * @param limits the resource bounds
         */
        void read(SensorPlane plane, MediaLimits limits);
    }

    /**
     * The stored width in pixels, masked areas included.
     */
    int width;

    /**
     * The stored height in pixels, masked areas included.
     */
    int height;

    /**
     * The samples of each pixel: 1 for a color filter array or monochrome sensor, 3 for linear
     * raw data.
     */
    int samplesPerPixel = 1;

    /**
     * The bits of each stored sample.
     */
    int bitsPerSample = 16;

    /**
     * Whether the samples are floating-point.
     */
    boolean floating;

    /**
     * The color filter array in stored coordinates, or {@code null} for linear raw or monochrome
     * data.
     */
    CfaPattern cfa;

    /**
     * The color plane each CFA color code maps to ({@code CFAPlaneColor}), indexed by code; the
     * default maps red, green and blue to planes 0, 1 and 2.
     */
    int[] planeOfColor = {0, 1, 2, -1, -1, -1, -1};

    /**
     * The area holding image data, as top, left, bottom and right in stored coordinates.
     */
    int[] activeArea;

    /**
     * The origin and size of the default crop, as x, y, width and height relative to the active
     * area, or {@code null} for the whole active area.
     */
    double[] defaultCrop;

    /**
     * The horizontal default scale.
     */
    double scaleH = 1;

    /**
     * The vertical default scale.
     */
    double scaleV = 1;

    /**
     * The rows of the black level pattern.
     */
    int blackRows = 1;

    /**
     * The columns of the black level pattern.
     */
    int blackColumns = 1;

    /**
     * The black level pattern, {@code blackRows * blackColumns * samplesPerPixel} values, in stored
     * units after linearization; {@code null} for zero.
     */
    double[] black;

    /**
     * The black level added per column of the active area, or {@code null}.
     */
    double[] blackDeltaH;

    /**
     * The black level added per row of the active area, or {@code null}.
     */
    double[] blackDeltaV;

    /**
     * The white level of each sample, in stored units after linearization; {@code null} for the
     * largest value the bit depth stores.
     */
    double[] white;

    /**
     * The linearization table mapping stored values to linear ones, or {@code null}.
     */
    int[] linearization;

    /**
     * The opcodes applied to the stored data, or {@code null}.
     */
    byte[] opcodes1;

    /**
     * The opcodes applied to the linear data before demosaicing, or {@code null}.
     */
    byte[] opcodes2;

    /**
     * The opcodes applied to the demosaiced data, or {@code null}.
     */
    byte[] opcodes3;

    /**
     * Whether the as-shot white balance, which the file does not record readably, is estimated
     * from the embedded preview.
     */
    boolean estimateBalance;

    /**
     * Describes the compression, for {@link com.github.auties00.smalt.image.raw.RawInfo}.
     */
    String compression = "uncompressed";

    /**
     * Reads the samples.
     */
    Reader reader;

    /**
     * Returns the active area, defaulting to the whole stored image.
     *
     * @return the top, left, bottom and right edges
     */
    int[] active() {
        return activeArea != null ? activeArea : new int[]{0, 0, height, width};
    }

    /**
     * Returns the largest value a stored sample can take.
     *
     * @return {@code 2^bitsPerSample - 1}, or 1 for floating-point samples
     */
    double maximum() {
        return floating ? 1.0 : (1L << Math.min(bitsPerSample, 16)) - 1;
    }
}
