package com.github.auties00.smalt.testkit;

/**
 * A slow, literal implementation of the single-layer Dolby Vision reconstruction, sample by sample in the order of
 * the text of ETSI GS CCM 001: the pivot index of clause 5.4.2.2, the pseudo-code of the polynomial and MMR mappings
 * of clauses 5.4.2.3.2 and 5.4.2.3.3, the rounding of clause 5.4.3.3, the filters of clause B.2 and the ST 2084 curves
 * in double precision. It shares nothing with the decoder.
 */
public final class DolbyVisionReference {
    /** A 4:2:0 picture of unsigned samples. */
    public record Planes(int width, int height, int[] y, int[] cb, int[] cr) {
        public int chromaWidth() {
            return (width + 1) / 2;
        }

        public int chromaHeight() {
            return (height + 1) / 2;
        }
    }

    /**
     * The reshaping curves of an RPU.
     *
     * @param rpuId {@code vdr_rpu_id}
     * @param denominatorBits {@code coefficient_log2_denom}
     * @param curves the curves of the three components
     */
    public record Mapping(int rpuId, int denominatorBits, Curve[] curves) {
    }

    /**
     * The reshaping curve of one component.
     *
     * @param pivots the {@code pivot_value} of each pivot point
     * @param pieces the mapping of each interval between consecutive pivots
     */
    public record Curve(int[] pivots, Piece[] pieces) {
    }

    /**
     * The mapping of one interval.
     *
     * @param mmr whether it is a regression instead of a polynomial
     * @param order the order
     * @param coefficients the fixed-point coefficients: {@code order + 1} of a polynomial in increasing powers, or the
     * constant and then {@code 7 * order} of a regression
     */
    public record Piece(boolean mmr, int order, long[] coefficients) {
    }

    private static final int BL_BIT_DEPTH = 10;

    private DolbyVisionReference() {
    }

    /** Composes a base layer picture into the reconstructed picture of a bit depth. */
    public static Planes compose(Planes base, Mapping mapping, int depth) {
        var y = new int[base.width() * base.height()];
        for (var index = 0; index < y.length; index++) {
            y[index] = round(map(mapping, 0, base.y()[index], 0, 0, 0), depth);
        }
        var chromaWidth = base.chromaWidth();
        var chromaHeight = base.chromaHeight();
        var cb = new int[chromaWidth * chromaHeight];
        var cr = new int[chromaWidth * chromaHeight];
        for (var j = 0; j < chromaHeight; j++) {
            for (var i = 0; i < chromaWidth; i++) {
                var s0 = downsampledLuma(base, i, j);
                var s1 = base.cb()[j * chromaWidth + i];
                var s2 = base.cr()[j * chromaWidth + i];
                cb[j * chromaWidth + i] = round(map(mapping, 1, s1, s0, s1, s2), depth);
                cr[j * chromaWidth + i] = round(map(mapping, 2, s2, s0, s1, s2), depth);
            }
        }
        return new Planes(base.width(), base.height(), y, cb, cr);
    }

    private static int downsampledLuma(Planes base, int i, int j) {
        return ((luma(base, 2 * i - 1, 2 * j) + 2 * luma(base, 2 * i, 2 * j) + luma(base, 2 * i + 1, 2 * j) + 2 >> 2)
                + (luma(base, 2 * i - 1, 2 * j + 1) + 2 * luma(base, 2 * i, 2 * j + 1)
                + luma(base, 2 * i + 1, 2 * j + 1) + 2 >> 2) + 1) >> 1;
    }

    private static int luma(Planes base, int column, int row) {
        var x = Math.max(0, Math.min(column, base.width() - 1));
        var y = Math.max(0, Math.min(row, base.height() - 1));
        return base.y()[y * base.width() + x];
    }

    private static int map(Mapping mapping, int cmp, int s, int s0, int s1, int s2) {
        var pivots = mapping.curves()[cmp].pivots();
        var numPivotsMinus2 = pivots.length - 2;
        var pivotIdx = numPivotsMinus2 + 1;
        for (var idx = 0; idx < numPivotsMinus2 + 1; idx++) {
            if (s < pivots[idx + 1]) {
                pivotIdx = idx;
                break;
            }
        }
        // a sample at or above the last pivot names no coefficients in the text; the last interval maps it
        var piece = mapping.curves()[cmp].pieces()[Math.min(pivotIdx, numPivotsMinus2)];
        return piece.mmr() ? mmr(mapping, piece, s0, s1, s2) : polynomial(mapping, piece, pivots, s);
    }

    private static int polynomial(Mapping mapping, Piece piece, int[] pivots, int input) {
        var s = input;
        if (s < pivots[0]) {
            s = pivots[0];
        }
        if (s > pivots[pivots.length - 1]) {
            s = pivots[pivots.length - 1];
        }
        long ss = 1;
        var shift = 20;
        long vv = 0;
        for (var i = 0; i <= piece.order(); i++) {
            vv += piece.coefficients()[i] * (ss << shift);
            ss *= s;
            shift -= BL_BIT_DEPTH;
        }
        vv = vv < 0 ? 0 : vv;
        var v = vv >> (4 + mapping.denominatorBits());
        return (int) (v > 0xFFFF ? 0xFFFF : v);
    }

    private static int mmr(Mapping mapping, Piece piece, int inputS0, int inputS1, int inputS2) {
        var curves = mapping.curves();
        long s0 = clip(inputS0, curves[0].pivots());
        long s1 = clip(inputS1, curves[1].pivots());
        long s2 = clip(inputS2, curves[2].pivots());
        var tt = new long[22];
        tt[0] = 1L << 20;
        tt[1] = s0 << (20 - BL_BIT_DEPTH);
        tt[2] = s1 << (20 - BL_BIT_DEPTH);
        tt[3] = s2 << (20 - BL_BIT_DEPTH);
        tt[4] = (s0 * s1) << (20 - 2 * BL_BIT_DEPTH);
        tt[5] = (s0 * s2) << (20 - 2 * BL_BIT_DEPTH);
        tt[6] = (s1 * s2) << (20 - 2 * BL_BIT_DEPTH);
        tt[7] = (tt[4] * tt[3]) >> 20;
        tt[8] = (s0 * s0) << (20 - 2 * BL_BIT_DEPTH);
        tt[9] = (s1 * s1) << (20 - 2 * BL_BIT_DEPTH);
        tt[10] = (s2 * s2) << (20 - 2 * BL_BIT_DEPTH);
        tt[11] = (tt[4] * tt[4]) >> 20;
        tt[12] = (tt[5] * tt[5]) >> 20;
        tt[13] = (tt[6] * tt[6]) >> 20;
        tt[14] = (tt[7] * tt[7]) >> 20;
        tt[15] = (tt[1] * tt[8]) >> 20;
        tt[16] = (tt[2] * tt[9]) >> 20;
        tt[17] = (tt[3] * tt[10]) >> 20;
        tt[18] = (tt[4] * tt[11]) >> 20;
        tt[19] = (tt[5] * tt[12]) >> 20;
        tt[20] = (tt[6] * tt[13]) >> 20;
        tt[21] = (tt[7] * tt[14]) >> 20;
        var coefficients = piece.coefficients();
        var rr = coefficients[0] * tt[0];
        var cnt = 1;
        for (var i = 1; i <= piece.order(); i++) {
            for (var j = 0; j < 7; j++) {
                rr += coefficients[cnt] * tt[cnt];
                cnt++;
            }
        }
        rr = rr < 0 ? 0 : rr;
        var v = rr >> (4 + mapping.denominatorBits());
        return (int) (v > 0xFFFF ? 0xFFFF : v);
    }

    private static int clip(int s, int[] pivots) {
        return Math.max(pivots[0], Math.min(s, pivots[pivots.length - 1]));
    }

    private static int round(int v, int outBitDepth) {
        var max = (1 << outBitDepth) - 1;
        var h = v;
        h += 1 << (15 - outBitDepth);
        h >>= 16 - outBitDepth;
        h = h < 0 ? 0 : h;
        return h > max ? max : h;
    }

    /**
     * Upsamples a chroma plane of a reconstructed picture to the picture size with the filters of clause B.2, vertical
     * first, its samples scaled to 16 bits.
     */
    public static int[] upsample(int[] plane, int chromaWidth, int chromaHeight, int width, int height, int depth) {
        var tall = new int[chromaWidth * height];
        for (var column = 0; column < chromaWidth; column++) {
            var c = column;
            for (var row = 0; row < height; row++) {
                var n = row >> 1;
                int sum;
                if ((row & 1) == 0) {
                    sum = 2 * at(plane, chromaWidth, chromaHeight, c, n - 3) - 12 * at(plane, chromaWidth, chromaHeight, c, n - 2)
                            + 65 * at(plane, chromaWidth, chromaHeight, c, n - 1) + 222 * at(plane, chromaWidth, chromaHeight, c, n)
                            - 25 * at(plane, chromaWidth, chromaHeight, c, n + 1) + 4 * at(plane, chromaWidth, chromaHeight, c, n + 2);
                } else {
                    sum = 4 * at(plane, chromaWidth, chromaHeight, c, n - 2) - 25 * at(plane, chromaWidth, chromaHeight, c, n - 1)
                            + 222 * at(plane, chromaWidth, chromaHeight, c, n) + 65 * at(plane, chromaWidth, chromaHeight, c, n + 1)
                            - 12 * at(plane, chromaWidth, chromaHeight, c, n + 2) + 2 * at(plane, chromaWidth, chromaHeight, c, n + 3);
                }
                tall[row * chromaWidth + column] = clip3(((sum << (16 - depth)) + 128) >> 8);
            }
        }
        var full = new int[width * height];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                var n = x >> 1;
                if ((x & 1) == 0) {
                    full[row * width + x] = tall[row * chromaWidth + n];
                } else {
                    var sum = 22 * at(tall, chromaWidth, height, n - 3, row) + 94 * at(tall, chromaWidth, height, n - 2, row)
                            - 524 * at(tall, chromaWidth, height, n - 1, row) + 2456 * at(tall, chromaWidth, height, n, row)
                            + 2456 * at(tall, chromaWidth, height, n + 1, row) - 524 * at(tall, chromaWidth, height, n + 2, row)
                            + 94 * at(tall, chromaWidth, height, n + 3, row) + 22 * at(tall, chromaWidth, height, n + 4, row);
                    full[row * width + x] = clip3((sum + 2048) >> 12);
                }
            }
        }
        return full;
    }

    private static int at(int[] plane, int width, int height, int x, int y) {
        return plane[Math.max(0, Math.min(y, height - 1)) * width + Math.max(0, Math.min(x, width - 1))];
    }

    private static int clip3(int value) {
        return Math.max(0, Math.min(0xFFFF, value));
    }

    /**
     * Converts a reconstructed picture to BT.2020 RGB coded with the ST 2084 inverse EOTF.
     *
     * @return the G, B and R values of each pixel in that order, from 0 to 1, one plane after another
     */
    public static double[][] toRgb(Planes planes, int depth, short[] yccToRgb, long[] offsets, short[] rgbToLms) {
        var width = planes.width();
        var height = planes.height();
        var cb = upsample(planes.cb(), planes.chromaWidth(), planes.chromaHeight(), width, height, depth);
        var cr = upsample(planes.cr(), planes.chromaWidth(), planes.chromaHeight(), width, height, depth);
        var lmsToBt2020 = invert(new double[][]{
                {7222 / 16384.0, 8771 / 16384.0, 390 / 16384.0},
                {2654 / 16384.0, 12430 / 16384.0, 1300 / 16384.0},
                {0, 422 / 16384.0, 15962 / 16384.0}});
        var result = new double[3][width * height];
        for (var index = 0; index < width * height; index++) {
            var input = new double[]{planes.y()[index] / (double) (1 << depth) - offsets[0] / (double) (1L << 28),
                    cb[index] / 65536.0 - offsets[1] / (double) (1L << 28),
                    cr[index] / 65536.0 - offsets[2] / (double) (1L << 28)};
            var linear = new double[3];
            for (var row = 0; row < 3; row++) {
                var value = 0.0;
                for (var column = 0; column < 3; column++) {
                    value += yccToRgb[row * 3 + column] / 8192.0 * input[column];
                }
                linear[row] = eotf(Math.max(0, Math.min(1, value)));
            }
            var lms = new double[3];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    lms[row] += rgbToLms[row * 3 + column] / 16384.0 * linear[column];
                }
            }
            var rgb = new double[3];
            for (var row = 0; row < 3; row++) {
                for (var column = 0; column < 3; column++) {
                    rgb[row] += lmsToBt2020[row][column] * lms[column];
                }
                rgb[row] = inverseEotf(Math.max(0, Math.min(1, rgb[row])));
            }
            result[0][index] = rgb[1];
            result[1][index] = rgb[2];
            result[2][index] = rgb[0];
        }
        return result;
    }

    /** {@return the ST 2084 EOTF of a nonlinear value, as a fraction of 10000 cd/m2} */
    public static double eotf(double value) {
        var power = Math.pow(value, 4096.0 / (2523 * 128));
        return Math.pow(Math.max(power - 3424.0 / 4096, 0) / (2413.0 / 4096 * 32 - 2392.0 / 4096 * 32 * power),
                16384.0 / 2610);
    }

    /** {@return the ST 2084 inverse EOTF of a linear value given as a fraction of 10000 cd/m2} */
    public static double inverseEotf(double value) {
        var power = Math.pow(value, 2610.0 / 16384);
        return Math.pow((3424.0 / 4096 + 2413.0 / 4096 * 32 * power) / (1 + 2392.0 / 4096 * 32 * power),
                2523.0 / 4096 * 128);
    }

    private static double[][] invert(double[][] m) {
        var determinant = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        var result = new double[3][3];
        for (var row = 0; row < 3; row++) {
            for (var column = 0; column < 3; column++) {
                var a = m[(column + 1) % 3][(row + 1) % 3] * m[(column + 2) % 3][(row + 2) % 3];
                var b = m[(column + 1) % 3][(row + 2) % 3] * m[(column + 2) % 3][(row + 1) % 3];
                result[row][column] = (a - b) / determinant;
            }
        }
        return result;
    }
}
