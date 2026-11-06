package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.audio.aac.internal.SamplingTables;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Holds the band tables and windows of the USAC core coder (ISO/IEC 23003-3 subclause 6.2.10 and Annex A).
 */
final class UsacTables {
    /**
     * The sampling frequency of each {@code usacSamplingFrequencyIndex}, 0 for the reserved indices and for
     * the escape index 31.
     */
    static final int[] SAMPLING_FREQUENCIES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350, 0, 0, 57600,
            51200, 40000, 38400, 34150, 28800, 25600, 20000, 19200, 17075, 14400, 12800, 9600, 0, 0, 0, 0
    };

    private static final int[] LONG_768_96 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 156,
            172, 188, 212, 240, 276, 320, 384, 448, 512, 576, 640, 704, 768
    };

    private static final int[] LONG_768_64 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 100, 112, 124, 140, 156, 172,
            192, 216, 240, 268, 304, 344, 384, 424, 464, 504, 544, 584, 624, 664, 704, 744, 768
    };

    private static final int[] LONG_768_48 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196,
            216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768
    };

    private static final int[] LONG_768_24 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124, 136, 148, 160,
            172, 188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652, 704, 768
    };

    private static final int[] LONG_768_16 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160, 172, 184, 196, 212, 228,
            244, 260, 280, 300, 320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716, 768
    };

    private static final int[] LONG_768_8 = {
            0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 172, 188, 204, 220, 236, 252, 268, 288,
            308, 328, 348, 372, 396, 420, 448, 476, 508, 544, 580, 620, 664, 712, 764, 768
    };

    private static final int[] SHORT_96_96 = {0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 92, 96};
    private static final int[] SHORT_96_48 = {0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96};
    private static final int[] SHORT_96_24 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 96};
    private static final int[] SHORT_96_16 = {0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 60, 72, 88, 96};
    private static final int[] SHORT_96_8 = {0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 60, 72, 88, 96};

    private static final int[][] LONG_768 = {
            LONG_768_96, LONG_768_96, LONG_768_64, LONG_768_48, LONG_768_48, LONG_768_48, LONG_768_24, LONG_768_24,
            LONG_768_16, LONG_768_16, LONG_768_16, LONG_768_8, LONG_768_8
    };

    private static final int[][] SHORT_96 = {
            SHORT_96_96, SHORT_96_96, SHORT_96_96, SHORT_96_48, SHORT_96_48, SHORT_96_48, SHORT_96_24, SHORT_96_24,
            SHORT_96_16, SHORT_96_16, SHORT_96_16, SHORT_96_8, SHORT_96_8
    };

    /**
     * The highest band TNS filters in long windows ({@code tns_max_bands}, USAC column).
     */
    private static final int[] TNS_MAX_BANDS_LONG = {31, 31, 34, 40, 42, 51, 47, 47, 43, 43, 43, 40, 40};

    /**
     * The highest band TNS filters in short windows.
     */
    private static final int[] TNS_MAX_BANDS_SHORT = {9, 9, 10, 14, 14, 14, 15, 15, 15, 15, 15, 15, 15};

    /**
     * The windows built so far, at {@code length * 2 + kbd} for the lengths up to 1024.
     */
    private static final AtomicReferenceArray<float[]> WINDOWS = new AtomicReferenceArray<>(2 * 1025);

    private UsacTables() {
    }

    /**
     * Returns the long-window band offsets, starting with 0 and ending with the frame length.
     *
     * @param tableIndex the sampling table index of the band tables
     * @param frameLength the core frame length, 768 or 1024
     * @return the offsets, shared
     */
    static int[] longOffsets(int tableIndex, int frameLength) {
        return frameLength == 768 ? LONG_768[tableIndex] : SamplingTables.longOffsets(tableIndex, 1024);
    }

    /**
     * Returns the short-window band offsets, starting with 0 and ending with an eighth of the frame length.
     *
     * @param tableIndex the sampling table index of the band tables
     * @param frameLength the core frame length, 768 or 1024
     * @return the offsets, shared
     */
    static int[] shortOffsets(int tableIndex, int frameLength) {
        return frameLength == 768 ? SHORT_96[tableIndex] : SamplingTables.shortOffsets(tableIndex, 1024);
    }

    /**
     * Returns the highest band TNS filters.
     *
     * @param tableIndex the sampling table index
     * @param shortWindow whether the window is short
     * @return the band limit
     */
    static int tnsMaxBands(int tableIndex, boolean shortWindow) {
        return shortWindow ? TNS_MAX_BANDS_SHORT[tableIndex] : TNS_MAX_BANDS_LONG[tableIndex];
    }

    /**
     * Returns the rising half of a window ({@code calc_window}): the sine window, or the KBD window with
     * alpha 4 for the long lengths 1024 and 768 and alpha 6 for every other length.
     *
     * @param length the number of coefficients, half the window length
     * @param kbd whether the shape is the KBD window (window shape 1)
     * @return the coefficients, shared
     */
    static float[] window(int length, boolean kbd) {
        var key = length * 2 + (kbd ? 1 : 0);
        var window = WINDOWS.get(key);
        if (window == null) {
            window = build(length, kbd);
            if (!WINDOWS.compareAndSet(key, null, window)) {
                window = WINDOWS.get(key);
            }
        }
        return window;
    }

    private static float[] build(int length, boolean kbd) {
        var window = new float[length];
        if (!kbd) {
            for (var i = 0; i < length; i++) {
                window[i] = (float) StrictMath.sin((i + 0.5) * Math.PI / (2 * length));
            }
            return window;
        }
        var alpha = (length == 1024 || length == 768 ? 4.0 : 6.0) * Math.PI;
        var full = 2 * length;
        var inverse = 1.0 / besselI0(alpha);
        var raw = new double[length];
        var sum = 0.0;
        for (var i = 0; i < length; i++) {
            var t = 4.0 * i / full - 1.0;
            raw[i] = besselI0(alpha * StrictMath.sqrt(1.0 - t * t)) * inverse;
            sum += raw[i];
        }
        var accumulated = 0.0;
        for (var i = 0; i < length; i++) {
            accumulated += raw[i];
            window[i] = (float) StrictMath.sqrt(accumulated / sum);
        }
        return window;
    }

    private static double besselI0(double x) {
        var sum = 1.0;
        var term = 1.0;
        var half = x / 2;
        var n = 1;
        double t;
        do {
            t = half / n++;
            term *= t * t;
            sum += term;
        } while (term >= 1e-41 * sum);
        return sum;
    }
}
