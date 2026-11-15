package com.github.auties00.smalt.image.raw;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the sensor image of a camera raw file before demosaicing and color rendering: one sample
 * per photosite of a color filter array, or three per pixel of linear raw data, as linear 16-bit
 * values in which 0 is the black level and 65535 the white level. Sensors that read each photosite
 * twice (the two photodiodes of different sensitivity of Fujifilm SuperCCD SR sensors, the two
 * photosites of a pair of Fujifilm EXR sensors in their DR and SN modes) give two samples per
 * photosite, each scaled by its own black and white levels, in the order the file stores them;
 * the rendering merges them.
 *
 * <p>The values are the DNG "linear reference values" of the default crop (or of the whole active
 * area): stored samples after the linearization table, black level subtraction and white level
 * scaling, and after the DNG opcode lists 1 and 2 when the options apply them. Values below black
 * or above white are clipped. The color description maps them to CIE XYZ: divide each channel by
 * {@link #cameraNeutral()} to white balance it, then multiply by {@link #balancedToXyz()}.
 *
 * <p>{@link RawDecoder#decodeRaw(com.github.auties00.smalt.io.MediaSource) RawDecoder.decodeRaw} extracts the
 * image of a file; {@link #ofCfa}, {@link #ofLinear} and {@link #ofMonochrome} build one from samples, for example
 * to write it with {@link DngEncoder}.
 *
 * <p>Instances are immutable and thread-safe, except that {@link #samples()} returns the image's own
 * array, which callers must not modify while others use the image.
 */
public final class RawImage {
    /**
     * The D50 white, CIE XYZ with Y of 1, that a white-balanced neutral maps to.
     */
    private static final double[] D50_WHITE = {0.9642, 1.0, 0.8249};

    /**
     * The largest difference from {@link #D50_WHITE} of each component of the white a factory's matrix maps a
     * white-balanced neutral to.
     */
    private static final double WHITE_TOLERANCE = 0.01;

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

    private final List<CfaColor> planes;

    /**
     * Constructs an image; {@link RawDecoder} and the factories create instances.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples of each pixel, 1, 2 or 3
     * @param samples the 16-bit samples, row by row with the channels of a pixel interleaved; not copied
     * @param cfa the color filter array, or {@code null}
     * @param neutral the camera neutral
     * @param balancedToXyz the white-balanced camera to XYZ matrix, or an empty array
     * @param layout the spatial layout of the color filter array
     * @param planes the color of each component of the neutral
     */
    RawImage(int width, int height, int channels, short[] samples, CfaPattern cfa, double[] neutral,
             double[] balancedToXyz, CfaLayout layout, List<CfaColor> planes) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.samples = Objects.requireNonNull(samples, "samples");
        this.cfa = cfa;
        this.neutral = neutral.clone();
        this.balancedToXyz = balancedToXyz.clone();
        this.layout = Objects.requireNonNull(layout, "layout");
        this.planes = List.copyOf(planes);
    }

    /**
     * Returns the image of a sensor with a color filter array: one sample per photosite, with a rectangular layout.
     *
     * <p>The color planes ({@link #colorPlanes()}) are red, green and blue when the pattern holds no other
     * colors, and otherwise the pattern's colors in ascending {@linkplain CfaColor#code() code} order.
     *
     * @param width the width in photosites
     * @param height the height in photosites
     * @param samples the {@code width * height} linear values, row by row, 0 at black and 65535 at white; not
     * copied
     * @param cfa the color filter array, its top-left photosite at the image's top-left one
     * @param cameraNeutral the camera's response to a neutral, one positive component per color plane
     * @param balancedToXyz the matrix mapping white-balanced camera values to CIE XYZ (D50), three rows of one
     * column per color plane; it maps a white-balanced neutral (each component 1) to the D50 white
     * {@code (0.9642, 1, 0.8249)}, each component to within 0.01
     * @return the image
     * @throws IllegalArgumentException if a size is not positive, the sample count does not match it, the pattern
     * has other than three or four colors when not all red, green or blue, or the neutral or the matrix does not
     * match the color planes, has a value that is not finite or, for the neutral, not positive, or the matrix
     * does not map a white-balanced neutral to the D50 white
     */
    public static RawImage ofCfa(int width, int height, short[] samples, CfaPattern cfa, double[] cameraNeutral,
                                 double[] balancedToXyz) {
        Objects.requireNonNull(cfa, "cfa");
        List<CfaColor> planes;
        if (cfa.isRgb()) {
            planes = List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE);
        } else {
            var distinct = new ArrayList<CfaColor>();
            for (var color : CfaColor.values()) {
                if (cfa.colors().contains(color)) {
                    distinct.add(color);
                }
            }
            if (distinct.size() < 3 || distinct.size() > 4) {
                throw new IllegalArgumentException("a color filter array of " + distinct.size()
                        + " colors other than red, green and blue has no color planes");
            }
            planes = distinct;
        }
        checkSize(width, height, 1, samples);
        checkColor(planes.size(), cameraNeutral, balancedToXyz);
        return new RawImage(width, height, 1, samples, cfa, cameraNeutral, balancedToXyz, CfaLayout.RECTANGULAR,
                planes);
    }

    /**
     * Returns a linear raw image: three samples per pixel, of red, green and blue camera values.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samples the {@code 3 * width * height} linear values, row by row with the channels of a pixel
     * interleaved, 0 at black and 65535 at white; not copied
     * @param cameraNeutral the camera's response to a neutral: three positive components
     * @param balancedToXyz the matrix mapping white-balanced camera values to CIE XYZ (D50): nine values, row by
     * row; it maps a white-balanced neutral (each component 1) to the D50 white {@code (0.9642, 1, 0.8249)}, each
     * component to within 0.01
     * @return the image
     * @throws IllegalArgumentException if a size is not positive, the sample count does not match it, or the
     * neutral or the matrix has the wrong length, a value that is not finite or, for the neutral, not positive, or
     * the matrix does not map a white-balanced neutral to the D50 white
     */
    public static RawImage ofLinear(int width, int height, short[] samples, double[] cameraNeutral,
                                    double[] balancedToXyz) {
        checkSize(width, height, 3, samples);
        checkColor(3, cameraNeutral, balancedToXyz);
        return new RawImage(width, height, 3, samples, null, cameraNeutral, balancedToXyz, CfaLayout.RECTANGULAR,
                List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
    }

    /**
     * Returns a monochrome image: one sample per pixel, without a color filter array. Its neutral is
     * {@code (1, 1, 1)} and it has no color matrix.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samples the {@code width * height} linear values, row by row, 0 at black and 65535 at white; not
     * copied
     * @return the image
     * @throws IllegalArgumentException if a size is not positive or the sample count does not match it
     */
    public static RawImage ofMonochrome(int width, int height, short[] samples) {
        checkSize(width, height, 1, samples);
        return new RawImage(width, height, 1, samples, null, new double[]{1, 1, 1}, new double[0],
                CfaLayout.RECTANGULAR, List.of(CfaColor.RED, CfaColor.GREEN, CfaColor.BLUE));
    }

    /**
     * Checks the size of an image against its samples.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples of each pixel
     * @param samples the sample array checked
     * @throws IllegalArgumentException if a size is not positive or the sample count does not match it
     */
    private static void checkSize(int width, int height, int channels, short[] samples) {
        Objects.requireNonNull(samples, "samples");
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("invalid image size " + width + "x" + height);
        }
        if ((long) width * height * channels != samples.length) {
            throw new IllegalArgumentException(samples.length + " samples for a " + width + "x" + height
                    + " image of " + channels + " samples per pixel");
        }
    }

    /**
     * Checks a color description against the number of color planes.
     *
     * @param planes the number of color planes
     * @param neutral the camera neutral
     * @param matrix the white-balanced camera to XYZ matrix
     * @throws IllegalArgumentException if the neutral or the matrix does not match the planes, has an invalid
     * value, or the matrix does not map a white-balanced neutral to the D50 white
     */
    private static void checkColor(int planes, double[] neutral, double[] matrix) {
        Objects.requireNonNull(neutral, "cameraNeutral");
        Objects.requireNonNull(matrix, "balancedToXyz");
        if (neutral.length != planes || matrix.length != 3 * planes) {
            throw new IllegalArgumentException("a neutral of " + neutral.length + " components and a matrix of "
                    + matrix.length + " values for " + planes + " color planes");
        }
        for (var value : neutral) {
            if (!(value > 0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("invalid camera neutral component " + value);
            }
        }
        for (var value : matrix) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("invalid color matrix value " + value);
            }
        }
        for (var row = 0; row < 3; row++) {
            var white = 0.0;
            for (var column = 0; column < planes; column++) {
                white += matrix[row * planes + column];
            }
            if (!(Math.abs(white - D50_WHITE[row]) <= WHITE_TOLERANCE)) {
                throw new IllegalArgumentException("the color matrix maps a white-balanced neutral to XYZ component "
                        + row + " of " + white + ", not to the D50 white's " + D50_WHITE[row]);
            }
        }
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
     * {@return the number of samples of each pixel: 1 for color filter array and monochrome images, 2
     * for color filter arrays that read each photosite twice, 3 for linear raw images}
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
     * {@return the color of each component of {@link #cameraNeutral()} and of each column of
     * {@link #balancedToXyz()}: red, green and blue, or the four colors of a four-color sensor in the order of the
     * file's color planes}
     */
    public List<CfaColor> colorPlanes() {
        return planes;
    }

    /**
     * {@return a copy of the matrix that maps white-balanced camera values (each channel divided by
     * {@link #cameraNeutral()}) to CIE XYZ relative to a D50 white, row by row: three rows of one
     * column per color plane, nine values or twelve for a four-color sensor; empty for a four-color
     * sensor whose file carries no color matrices when the options supply none, and for an image of
     * {@link #ofMonochrome}}
     */
    public double[] balancedToXyz() {
        return balancedToXyz.clone();
    }
}
