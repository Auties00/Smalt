package com.github.auties00.smalt.internal.dolbyvision;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts a reconstructed 4:2:0 picture to BT.2020 RGB coded with the SMPTE ST 2084 inverse EOTF: chroma upsampling
 * with the filters of ETSI GS CCM 001, clause B.2, the RPU's {@code YCCtoRGB} matrix to nonlinear trichromatic values,
 * the EOTF, the RPU's {@code RGBtoLMS} matrix to the LMS space of the Dolby Vision display management, the fixed
 * matrix from that space to BT.2020, and the inverse EOTF.
 *
 * <p>An RPU's trichromatic signal is whatever its {@code YCCtoRGB} matrix produces (LMS with crosstalk for IPT-PQ-c2,
 * LMS for ICtCp, RGB for Y'CbCr), and its {@code RGBtoLMS} matrix takes that signal to one LMS space, so the conversion
 * does not depend on {@code signal_color_space}.
 *
 * <p>Instances are not thread-safe. Arguments are not checked; values outside the documented ranges are undefined
 * behavior.
 *
 * @spec https://www.etsi.org/deliver/etsi_gs/CCM/001_099/001/01.01.01_60/gs_CCM001v010101p.pdf ETSI GS CCM 001 V1.1.1
 * @spec https://doi.org/10.5594/SMPTE.ST2084.2014 SMPTE ST 2084
 */
final class DolbyVisionColor {
    /**
     * The conversion matrices of one RPU.
     *
     * @param yccToNonlinear the {@code YCCtoRGB} matrix in row order
     * @param offsets the {@code YCCtoRGB} offsets as fractions of the signal range
     * @param lmsToRgb the matrix from the linear trichromatic signal to linear BT.2020 RGB in row order
     */
    record Matrices(float[] yccToNonlinear, float[] offsets, float[] lmsToRgb) {
        /**
         * The matrices ETSI GS CCM 001, clause 6.2.2, assigns when the information is not available. The clause's
         * offsets are in units of 1/2^30 of the signal range, while those streams carry are in units of 1/2^28.
         */
        static final Matrices DEFAULTS = of(
                new short[]{9575, 0, 14742, 9575, -1754, -4383, 9575, 17372, 0},
                new long[]{1L << 24, 1L << 27, 1L << 27},
                new short[]{5845, 9702, 837, 2568, 12256, 1561, 0, 679, 15705});

        /**
         * Builds the matrices of an RPU.
         *
         * @param yccToRgb the {@code ycc_to_rgb_coef} values, in units of 1/8192
         * @param offsets the {@code ycc_to_rgb_offset} values, in units of 1/2^28
         * @param rgbToLms the {@code rgb_to_lms_coef} values, in units of 1/16384
         * @return the matrices
         */
        static Matrices of(short[] yccToRgb, long[] offsets, short[] rgbToLms) {
            var nonlinear = new float[9];
            var product = new float[9];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    nonlinear[row * 3 + column] = yccToRgb[row * 3 + column] / 8192f;
                    var value = 0.0;
                    for (var inner = 0; inner < 3; inner++) {
                        value += LMS_TO_BT2020[row * 3 + inner] * rgbToLms[inner * 3 + column] / 16384.0;
                    }
                    product[row * 3 + column] = (float) value;
                }
            }
            return new Matrices(nonlinear, new float[]{(float) (offsets[0] / (double) (1L << 28)),
                    (float) (offsets[1] / (double) (1L << 28)), (float) (offsets[2] / (double) (1L << 28))}, product);
        }

        /**
         * Builds the matrices of the display management data of an RPU.
         *
         * @param data the uncompressed data
         * @return the matrices
         */
        static Matrices of(DolbyVisionRpu.DisplayManagement data) {
            return of(data.yccToRgb(), data.yccToRgbOffset(), data.rgbToLms());
        }
    }

    /**
     * The matrix from linear BT.2020 RGB to the LMS space of the Dolby Vision display management, in units of 1/16384,
     * as a profile 8 RPU carries it in {@code rgb_to_lms_coef}.
     */
    private static final int[] BT2020_TO_LMS = {7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962};

    /**
     * The inverse of {@link #BT2020_TO_LMS}.
     */
    private static final double[] LMS_TO_BT2020 = inverse(BT2020_TO_LMS);

    /**
     * The intervals of the EOTF and inverse EOTF tables.
     */
    private static final int TABLE_SIZE = 4096;

    /**
     * ST 2084 constant m1.
     */
    private static final double M1 = 2610.0 / 16384;

    /**
     * ST 2084 constant m2.
     */
    private static final double M2 = 2523.0 / 4096 * 128;

    /**
     * ST 2084 constant c1.
     */
    private static final double C1 = 3424.0 / 4096;

    /**
     * ST 2084 constant c2.
     */
    private static final double C2 = 2413.0 / 4096 * 32;

    /**
     * ST 2084 constant c3.
     */
    private static final double C3 = 2392.0 / 4096 * 32;

    /**
     * The EOTF at {@code i / TABLE_SIZE}, as a fraction of 10000 cd/m2.
     */
    private static final float[] EOTF = new float[TABLE_SIZE + 2];

    /**
     * The inverse EOTF at the linear value {@code (i / TABLE_SIZE)^4}: the fourth root takes out most of the curve's
     * steepness near black, and two square roots are cheap.
     */
    private static final float[] INVERSE_EOTF = new float[TABLE_SIZE + 2];

    static {
        for (var index = 0; index < TABLE_SIZE + 2; index++) {
            EOTF[index] = (float) eotf(Math.min(1.0, index / (double) TABLE_SIZE));
            var root = Math.min(1.0, index / (double) TABLE_SIZE);
            INVERSE_EOTF[index] = (float) inverseEotf(root * root * root * root);
        }
    }

    /**
     * The approximate operations per pixel of the conversion, which weighs its work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long PIXEL_WORK = 64;

    /**
     * The vertically upsampled first chroma plane, 16-bit samples, of the chroma width and the picture height.
     */
    private short[] tallCb = new short[0];

    /**
     * The vertically upsampled second chroma plane.
     */
    private short[] tallCr = new short[0];

    /**
     * Converts a reconstructed picture.
     *
     * @param y the luma plane, rows of the picture width
     * @param cb the first chroma plane, rows of the chroma width
     * @param cr the second chroma plane
     * @param depth the bit depth of the planes
     * @param matrices the conversion matrices
     * @param output a {@link Yuv16Picture} or {@link YuvPicture} in 4:4:4 of the same size: G in Y, B in U, R in V
     * @param threads the decoder's thread count, 1 keeping the work on the calling thread
     */
    void convert(short[] y, short[] cb, short[] cr, int depth, Matrices matrices, Picture output, int threads) {
        var width = output.width();
        var height = output.height();
        var chromaWidth = (width + 1) / 2;
        var chromaHeight = (height + 1) / 2;
        if (tallCb.length != chromaWidth * height) {
            tallCb = new short[chromaWidth * height];
            tallCr = new short[chromaWidth * height];
        }
        var tallCb = this.tallCb;
        var tallCr = this.tallCr;
        var bands = threads == 1 ? 1 : ParallelBands.bands(height, width * height * PIXEL_WORK);
        ParallelBands.run(bands, band -> {
            var end = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < end; row++) {
                upsampleRow(cb, tallCb, row, chromaWidth, chromaHeight, depth);
                upsampleRow(cr, tallCr, row, chromaWidth, chromaHeight, depth);
            }
        });
        ParallelBands.run(bands, band -> {
            var end = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < end; row++) {
                convertRow(y, tallCb, tallCr, row, width, chromaWidth, depth, matrices, output);
            }
        });
    }

    /**
     * Computes one row of a vertically upsampled plane with {@code SPATIAL_RESAMPLING_FILTER_V}.
     *
     * @param source the chroma plane
     * @param target the upsampled plane
     * @param row the output row
     * @param width the chroma width
     * @param height the chroma height
     * @param depth the bit depth of the chroma plane
     */
    private static void upsampleRow(short[] source, short[] target, int row, int width, int height, int depth) {
        var n = row >> 1;
        var odd = (row & 1) != 0;
        var r0 = width * Math.max(n - 3 + (odd ? 1 : 0), 0);
        var r1 = width * Math.max(n - 2 + (odd ? 1 : 0), 0);
        var r2 = width * Math.max(n - 1 + (odd ? 1 : 0), 0);
        var r3 = width * Math.min(n + (odd ? 1 : 0), height - 1);
        var r4 = width * Math.min(n + 1 + (odd ? 1 : 0), height - 1);
        var r5 = width * Math.min(n + 2 + (odd ? 1 : 0), height - 1);
        var c0 = odd ? 4 : 2;
        var c1 = odd ? -25 : -12;
        var c2 = odd ? 222 : 65;
        var c3 = odd ? 65 : 222;
        var c4 = odd ? -12 : -25;
        var c5 = odd ? 2 : 4;
        var shift = 16 - depth;
        var output = row * width;
        for (var x = 0; x < width; x++) {
            var value = c0 * (source[r0 + x] & 0xFFFF) + c1 * (source[r1 + x] & 0xFFFF) + c2 * (source[r2 + x] & 0xFFFF)
                    + c3 * (source[r3 + x] & 0xFFFF) + c4 * (source[r4 + x] & 0xFFFF) + c5 * (source[r5 + x] & 0xFFFF);
            target[output + x] = (short) Math.max(0, Math.min(0xFFFF, ((value << shift) + 128) >> 8));
        }
    }

    /**
     * Converts one row.
     *
     * @param y the luma plane
     * @param tallCb the vertically upsampled first chroma plane
     * @param tallCr the vertically upsampled second chroma plane
     * @param row the index of the output row
     * @param width the picture width
     * @param chromaWidth the chroma width
     * @param depth the bit depth of the luma plane
     * @param matrices the conversion matrices
     * @param output the output picture
     */
    private static void convertRow(short[] y, short[] tallCb, short[] tallCr, int row, int width, int chromaWidth,
                                   int depth, Matrices matrices, Picture output) {
        var m = matrices.yccToNonlinear();
        var m00 = m[0];
        var m01 = m[1];
        var m02 = m[2];
        var m10 = m[3];
        var m11 = m[4];
        var m12 = m[5];
        var m20 = m[6];
        var m21 = m[7];
        var m22 = m[8];
        var offsets = matrices.offsets();
        var o0 = offsets[0];
        var o1 = offsets[1];
        var o2 = offsets[2];
        var a = matrices.lmsToRgb();
        var a00 = a[0];
        var a01 = a[1];
        var a02 = a[2];
        var a10 = a[3];
        var a11 = a[4];
        var a12 = a[5];
        var a20 = a[6];
        var a21 = a[7];
        var a22 = a[8];
        var lumaScale = 1f / (1 << depth);
        var chromaScale = 1f / (1 << 16);
        var deep = output instanceof Yuv16Picture ? (Yuv16Picture) output : null;
        var narrow = deep == null ? (YuvPicture) output : null;
        var scale = deep != null ? (float) ((1 << deep.bitDepth()) - 1) : 255f;
        var lumaRow = row * width;
        var chromaRow = row * chromaWidth;
        var outputRow = row * (deep != null ? deep.yStride() : narrow.yStride());
        var deepY = deep != null ? deep.y() : null;
        var deepU = deep != null ? deep.u() : null;
        var deepV = deep != null ? deep.v() : null;
        var narrowY = narrow != null ? narrow.y() : null;
        var narrowU = narrow != null ? narrow.u() : null;
        var narrowV = narrow != null ? narrow.v() : null;
        for (var x = 0; x < width; x++) {
            var i = (y[lumaRow + x] & 0xFFFF) * lumaScale - o0;
            var p = horizontal(tallCb, chromaRow, x, chromaWidth) * chromaScale - o1;
            var t = horizontal(tallCr, chromaRow, x, chromaWidth) * chromaScale - o2;
            var l = eotf(m00 * i + m01 * p + m02 * t);
            var n = eotf(m10 * i + m11 * p + m12 * t);
            var s = eotf(m20 * i + m21 * p + m22 * t);
            var red = (int) (inverseEotf(a00 * l + a01 * n + a02 * s) * scale + 0.5f);
            var green = (int) (inverseEotf(a10 * l + a11 * n + a12 * s) * scale + 0.5f);
            var blue = (int) (inverseEotf(a20 * l + a21 * n + a22 * s) * scale + 0.5f);
            if (deep != null) {
                deepY[outputRow + x] = (short) green;
                deepU[outputRow + x] = (short) blue;
                deepV[outputRow + x] = (short) red;
            } else {
                narrowY[outputRow + x] = (byte) green;
                narrowU[outputRow + x] = (byte) blue;
                narrowV[outputRow + x] = (byte) red;
            }
        }
    }

    /**
     * Computes one sample of a horizontally upsampled row with {@code SPATIAL_RESAMPLING_FILTER_H}, which keeps the
     * even positions, co-sited with the chroma samples.
     *
     * @param plane the vertically upsampled plane
     * @param row the index of the start of the row
     * @param x the output column
     * @param width the chroma width
     * @return the 16-bit sample
     */
    private static int horizontal(short[] plane, int row, int x, int width) {
        var n = x >> 1;
        if ((x & 1) == 0) {
            return plane[row + n] & 0xFFFF;
        }
        int value;
        if (n >= 3 && n + 4 < width) {
            var at = row + n;
            value = 22 * ((plane[at - 3] & 0xFFFF) + (plane[at + 4] & 0xFFFF))
                    + 94 * ((plane[at - 2] & 0xFFFF) + (plane[at + 3] & 0xFFFF))
                    - 524 * ((plane[at - 1] & 0xFFFF) + (plane[at + 2] & 0xFFFF))
                    + 2456 * ((plane[at] & 0xFFFF) + (plane[at + 1] & 0xFFFF));
        } else {
            value = 22 * (edge(plane, row, n - 3, width) + edge(plane, row, n + 4, width))
                    + 94 * (edge(plane, row, n - 2, width) + edge(plane, row, n + 3, width))
                    - 524 * (edge(plane, row, n - 1, width) + edge(plane, row, n + 2, width))
                    + 2456 * (edge(plane, row, n, width) + edge(plane, row, n + 1, width));
        }
        return Math.max(0, Math.min(0xFFFF, (value + 2048) >> 12));
    }

    /**
     * {@return a sample of a row with pixel replication at its ends}
     *
     * @param plane the samples
     * @param row the index of the start of the row
     * @param column the column, possibly outside the row
     * @param width the row width
     */
    private static int edge(short[] plane, int row, int column, int width) {
        return plane[row + Math.max(0, Math.min(column, width - 1))] & 0xFFFF;
    }

    /**
     * {@return the EOTF of a nonlinear value, clamped to the range, from the table}
     *
     * @param value the nonlinear value
     */
    private static float eotf(float value) {
        var clamped = Math.max(0f, Math.min(1f, value));
        var position = clamped * TABLE_SIZE;
        var index = (int) position;
        var low = EOTF[index];
        return low + (EOTF[index + 1] - low) * (position - index);
    }

    /**
     * {@return the inverse EOTF of a linear value, clamped to the range, from the table}
     *
     * @param value the linear value
     */
    private static float inverseEotf(float value) {
        var clamped = Math.max(0f, Math.min(1f, value));
        var position = (float) Math.sqrt(Math.sqrt(clamped)) * TABLE_SIZE;
        var index = (int) position;
        var low = INVERSE_EOTF[index];
        return low + (INVERSE_EOTF[index + 1] - low) * (position - index);
    }

    /**
     * {@return the exact ST 2084 EOTF, as a fraction of 10000 cd/m2}
     *
     * @param value the nonlinear value, from 0 to 1
     */
    private static double eotf(double value) {
        var power = StrictMath.pow(value, 1 / M2);
        return StrictMath.pow(Math.max(power - C1, 0) / (C2 - C3 * power), 1 / M1);
    }

    /**
     * {@return the exact ST 2084 inverse EOTF}
     *
     * @param value the linear value as a fraction of 10000 cd/m2, from 0 to 1
     */
    private static double inverseEotf(double value) {
        var power = StrictMath.pow(value, M1);
        return StrictMath.pow((C1 + C2 * power) / (1 + C3 * power), M2);
    }

    /**
     * {@return the inverse of a 3x3 matrix in units of 1/16384, in row order}
     *
     * @param m the matrix
     */
    private static double[] inverse(int[] m) {
        var a = new double[9];
        for (var index = 0; index < 9; index++) {
            a[index] = m[index] / 16384.0;
        }
        var c00 = a[4] * a[8] - a[5] * a[7];
        var c01 = a[5] * a[6] - a[3] * a[8];
        var c02 = a[3] * a[7] - a[4] * a[6];
        var determinant = a[0] * c00 + a[1] * c01 + a[2] * c02;
        return new double[]{
                c00 / determinant, (a[2] * a[7] - a[1] * a[8]) / determinant, (a[1] * a[5] - a[2] * a[4]) / determinant,
                c01 / determinant, (a[0] * a[8] - a[2] * a[6]) / determinant, (a[2] * a[3] - a[0] * a[5]) / determinant,
                c02 / determinant, (a[1] * a[6] - a[0] * a[7]) / determinant, (a[0] * a[4] - a[1] * a[3]) / determinant};
    }
}
