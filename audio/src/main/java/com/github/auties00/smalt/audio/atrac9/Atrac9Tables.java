/* Ports LibAtrac9 (C/src/tables.c, C/src/decinit.c, C/src/bit_allocation.c) at commit efca2e3. Copyright 2018 Alex
   Barney; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

/**
 * The constant tables of ATRAC9: stream layouts, quantization unit geometry, step sizes, scale factor weights, the
 * gradient curves, and the trigonometric, shuffle and window tables of the inverse MDCT.
 */
final class Atrac9Tables {
    static final int MONO = 0;

    static final int STEREO = 1;

    /**
     * The block type of a low-frequency effects channel.
     */
    static final int LFE = 2;

    /**
     * The block types of each channel configuration index ({@code ChannelConfigs}).
     */
    static final int[][] CHANNEL_CONFIGS = {
            {MONO},
            {MONO, MONO},
            {STEREO},
            {STEREO, MONO, LFE, STEREO},
            {STEREO, MONO, LFE, STEREO, STEREO},
            {STEREO, STEREO}
    };

    /**
     * The channel count of each channel configuration index.
     */
    static final int[] CHANNEL_COUNTS = {1, 2, 2, 6, 8, 4};

    /**
     * The sampling rate of each sampling rate index, in hertz.
     */
    static final int[] SAMPLE_RATES = {
            11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000,
            44100, 48000, 64000, 88200, 96000, 128000, 176400, 192000
    };

    /**
     * The base-2 logarithm of the samples of a frame, per sampling rate index.
     */
    static final int[] FRAME_SAMPLES_POWER = {6, 6, 7, 7, 7, 8, 8, 8, 6, 6, 7, 7, 7, 8, 8, 8};

    /**
     * The largest band count, per sampling rate index.
     */
    static final int[] MAX_BAND_COUNT = {8, 8, 12, 12, 12, 18, 18, 18, 8, 8, 12, 12, 12, 16, 16, 16};

    /**
     * The largest precision coded with prefix codes, for the low and the high sampling rates.
     */
    static final int[] MAX_HUFF_PRECISION = {7, 1};

    /**
     * The smallest band count, for the low and the high sampling rates.
     */
    static final int[] MIN_BAND_COUNT = {3, 1};

    /**
     * The largest band extension band, for the low and the high sampling rates.
     */
    static final int[] MAX_EXTENSION_BAND = {18, 16};

    /**
     * The quantization units below each band count.
     */
    static final int[] BAND_TO_QUANT_UNIT_COUNT = {
            0, 4, 8, 10, 12, 13, 14, 15, 16, 18, 20, 21, 22, 23, 24, 25, 26, 28, 30
    };

    /**
     * The coefficients of each quantization unit.
     */
    static final int[] QUANT_UNIT_TO_COEFF_COUNT = {
            2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 8, 8, 8,
            8, 8, 8, 8, 8, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16
    };

    /**
     * The index of the first coefficient of each quantization unit, and the coefficient count as the last entry.
     */
    static final int[] QUANT_UNIT_TO_COEFF_INDEX = {
            0, 2, 4, 6, 8, 10, 12, 14, 16, 20, 24, 28, 32, 40, 48, 56,
            64, 72, 80, 88, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240, 256
    };

    /**
     * The spectrum codebook group of each quantization unit.
     */
    static final int[] QUANT_UNIT_TO_CODEBOOK_INDEX = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2,
            2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3
    };

    /**
     * The weights subtracted from the scale factors coded as offset deltas, per weight index.
     */
    static final int[][] SCALE_FACTOR_WEIGHTS = {
            {0, 0, 0, 1, 1, 2, 2, 2, 2, 2, 2, 3, 2, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 6, 6, 7, 7, 8, 10, 12, 12, 12},
            {3, 2, 2, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 0, 1, 1, 1, 1, 1, 1, 2, 3, 3, 4, 5, 7, 10, 10, 10},
            {0, 2, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 7, 7, 7, 7, 8, 9, 12, 12, 12},
            {0, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 5, 5, 5, 6, 6, 6, 6, 7, 8, 8, 10, 11, 11, 12, 13, 13, 13, 13},
            {0, 2, 2, 3, 3, 4, 4, 5, 4, 5, 5, 5, 5, 6, 7, 8, 8, 8, 8, 9, 9, 9, 10, 10, 11, 12, 12, 13, 13, 14, 14, 14},
            {1, 1, 0, 0, 0, 0, 1, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 7, 9, 11, 11, 11},
            {0, 5, 8, 10, 11, 11, 12, 12, 12, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 12, 12, 12,
                    12, 13, 15, 15, 15},
            {0, 2, 3, 4, 5, 6, 6, 7, 7, 8, 8, 8, 9, 9, 10, 10, 10, 11, 11, 11, 11, 11, 11, 12, 12, 12, 12, 13, 13,
                    15, 15, 15}
    };

    /**
     * The scale of each scale factor, {@code 2^(index - 15)}.
     */
    static final double[] SPECTRUM_SCALE = {
            3.0517578125e-5, 6.1035156250e-5, 1.2207031250e-4, 2.4414062500e-4,
            4.8828125000e-4, 9.7656250000e-4, 1.9531250000e-3, 3.9062500000e-3,
            7.8125000000e-3, 1.5625000000e-2, 3.1250000000e-2, 6.2500000000e-2,
            1.2500000000e-1, 2.5000000000e-1, 5.0000000000e-1, 1.0000000000e+0,
            2.0000000000e+0, 4.0000000000e+0, 8.0000000000e+0, 1.6000000000e+1,
            3.2000000000e+1, 6.4000000000e+1, 1.2800000000e+2, 2.5600000000e+2,
            5.1200000000e+2, 1.0240000000e+3, 2.0480000000e+3, 4.0960000000e+3,
            8.1920000000e+3, 1.6384000000e+4, 3.2768000000e+4, 6.5536000000e+4
    };

    /**
     * The quantizer step of each precision.
     */
    static final double[] QUANTIZER_STEP_SIZE = {
            2.0000000000000000e+0, 6.6666666666666663e-1, 2.8571428571428570e-1, 1.3333333333333333e-1,
            6.4516129032258063e-2, 3.1746031746031744e-2, 1.5748031496062992e-2, 7.8431372549019607e-3,
            3.9138943248532287e-3, 1.9550342130987292e-3, 9.7703957010258913e-4, 4.8840048840048840e-4,
            2.4417043096081065e-4, 1.2207776353537203e-4, 6.1037018951994385e-5, 3.0518043793392844e-5
    };

    /**
     * The quantizer step of each fine precision.
     */
    static final double[] QUANTIZER_FINE_STEP_SIZE = {
            3.0518043793392844e-05, 1.0172681264464281e-05, 4.3597205419132631e-06, 2.0345362528928561e-06,
            9.8445302559331759e-07, 4.8441339354591809e-07, 2.4029955742829012e-07, 1.1967860311134448e-07,
            5.9722199204291275e-08, 2.9831909866464167e-08, 1.4908668194134265e-08, 7.4525137468602791e-09,
            3.7258019525568114e-09, 1.8627872668859698e-09, 9.3136520869755679e-10, 4.6567549848772173e-10
    };

    /**
     * The gradient curve of each length from 1 to 48, indexed by length minus one ({@code GradientCurves}).
     */
    static final int[][] GRADIENT_CURVES = gradientCurves();

    /**
     * The sines of {@code pi (4i + 1) / (4n)} for each table size {@code n = 2^bits}, {@code bits} from 0 to 8.
     */
    static final double[][] SIN_TABLES = new double[9][];

    /**
     * The cosines matching {@link #SIN_TABLES}.
     */
    static final double[][] COS_TABLES = new double[9][];

    /**
     * The output order of the DCT-IV of each size.
     */
    static final int[][] SHUFFLE_TABLES = new int[9][];

    /**
     * The synthesis window of each frame size, indexed by its base-2 logarithm minus 6.
     */
    static final double[][] IMDCT_WINDOWS = new double[3][];

    static {
        for (var bits = 0; bits < 9; bits++) {
            trigTables(bits);
        }
        for (var power = 6; power <= 8; power++) {
            IMDCT_WINDOWS[power - 6] = imdctWindow(power);
        }
    }

    private Atrac9Tables() {
    }

    private static void trigTables(int bits) {
        var size = 1 << bits;
        var sin = new double[size];
        var cos = new double[size];
        var shuffle = new int[size];
        for (var i = 0; i < size; i++) {
            var value = Math.PI * (4 * i + 1) / (4 * size);
            sin[i] = StrictMath.sin(value);
            cos[i] = StrictMath.cos(value);
            shuffle[i] = bits == 0 ? 0 : Integer.reverse(i ^ (i / 2)) >>> (32 - bits);
        }
        SIN_TABLES[bits] = sin;
        COS_TABLES[bits] = cos;
        SHUFFLE_TABLES[bits] = shuffle;
    }

    /**
     * Returns the synthesis window: the analysis window {@code w} divided by {@code w(i)^2 + w(n - 1 - i)^2}.
     */
    private static double[] imdctWindow(int power) {
        var size = 1 << power;
        var mdct = new double[size];
        for (var i = 0; i < size; i++) {
            mdct[i] = (StrictMath.sin(((i + 0.5) / size - 0.5) * Math.PI) + 1.0) * 0.5;
        }
        var imdct = new double[size];
        for (var i = 0; i < size; i++) {
            imdct[i] = mdct[i] / (mdct[size - 1 - i] * mdct[size - 1 - i] + mdct[i] * mdct[i]);
        }
        return imdct;
    }

    private static int[][] gradientCurves() {
        int[] base = {
                1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 11, 12, 13,
                15, 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 26, 27, 27, 28, 28, 28, 29, 29,
                29, 29, 30, 30, 30, 30
        };
        var curves = new int[base.length][];
        for (var length = 1; length <= base.length; length++) {
            var curve = new int[length];
            for (var i = 0; i < length; i++) {
                curve[i] = base[i * base.length / length];
            }
            curves[length - 1] = curve;
        }
        return curves;
    }
}
