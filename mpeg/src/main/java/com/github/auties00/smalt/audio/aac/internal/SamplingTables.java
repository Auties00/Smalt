/* Ports libxaac (decoder/ixheaacd_aac_rom.c: the scalefactor band offsets and TNS limits of 512- and 480-sample
   frames). Copyright (C) 2018 The Android Open Source Project, Ittiam Systems; Apache License 2.0. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import java.util.Arrays;

/**
 * Holds the per-sampling-rate tables of AAC: the scalefactor band offsets of long and short windows
 * for 1024- and 960-sample frames and of the 512- and 480-sample frames of the low delay object types,
 * the TNS band limits and the AAC Main prediction limit; the rate of
 * each sampling frequency index is {@link com.github.auties00.smalt.internal.header.AacRates}'s.
 *
 * <p>Tables are selected by a table index from 0 to 12, the sampling frequency index of
 * ISO/IEC 14496-3 Table 1.18 (96000 Hz down to 7350 Hz). Streams that code an explicit
 * sampling frequency select their tables through {@link #tableIndexFor(int)}.
 */
public final class SamplingTables {
    /**
     * The long-window scalefactor band offsets for 88.2 and 96 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_96 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144,
            156, 172, 188, 212, 240, 276, 320, 384, 448, 512, 576, 640, 704, 768, 832, 896, 960, 1024
    };

    /**
     * The long-window scalefactor band offsets for 64 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_64 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 52, 56, 64, 72, 80, 88, 100, 112, 124, 140, 156,
            172, 192, 216, 240, 268, 304, 344, 384, 424, 464, 504, 544, 584, 624, 664, 704, 744, 784, 824, 864,
            904, 944, 984, 1024
    };

    /**
     * The long-window scalefactor band offsets for 44.1 and 48 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_48 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176,
            196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768,
            800, 832, 864, 896, 928, 1024
    };

    /**
     * The long-window scalefactor band offsets for 32 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_32 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176,
            196, 216, 240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768,
            800, 832, 864, 896, 928, 960, 992, 1024
    };

    /**
     * The long-window scalefactor band offsets for 22.05 and 24 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_24 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124, 136, 148,
            160, 172, 188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652, 704, 768,
            832, 896, 960, 1024
    };

    /**
     * The long-window scalefactor band offsets for 11.025, 12 and 16 kHz (ISO/IEC
     * 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_16 = {
            0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 88, 100, 112, 124, 136, 148, 160, 172, 184, 196, 212, 228,
            244, 260, 280, 300, 320, 344, 368, 396, 424, 456, 492, 532, 572, 616, 664, 716, 772, 832, 896, 960,
            1024
    };

    /**
     * The long-window scalefactor band offsets for 7.35 and 8 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] LONG_8 = {
            0, 12, 24, 36, 48, 60, 72, 84, 96, 108, 120, 132, 144, 156, 172, 188, 204, 220, 236, 252, 268, 288,
            308, 328, 348, 372, 396, 420, 448, 476, 508, 544, 580, 620, 664, 712, 764, 820, 880, 944, 1024
    };

    /**
     * The short-window scalefactor band offsets for 64, 88.2 and 96 kHz (ISO/IEC
     * 14496-3 subclause 4.5.4).
     */
    private static final int[] SHORT_96 = {
            0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 92, 128
    };

    /**
     * The short-window scalefactor band offsets for 32, 44.1 and 48 kHz (ISO/IEC
     * 14496-3 subclause 4.5.4).
     */
    private static final int[] SHORT_48 = {
            0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128
    };

    /**
     * The short-window scalefactor band offsets for 22.05 and 24 kHz (ISO/IEC
     * 14496-3 subclause 4.5.4).
     */
    private static final int[] SHORT_24 = {
            0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 108, 128
    };

    /**
     * The short-window scalefactor band offsets for 11.025, 12 and 16 kHz (ISO/IEC
     * 14496-3 subclause 4.5.4).
     */
    private static final int[] SHORT_16 = {
            0, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48, 60, 72, 88, 108, 128
    };

    /**
     * The short-window scalefactor band offsets for 7.35 and 8 kHz (ISO/IEC 14496-3 subclause 4.5.4).
     */
    private static final int[] SHORT_8 = {
            0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 60, 72, 88, 108, 128
    };

    /**
     * The long-window table of each table index.
     */
    private static final int[][] LONG_1024 = {
            LONG_96, LONG_96, LONG_64, LONG_48, LONG_48, LONG_32, LONG_24, LONG_24, LONG_16, LONG_16, LONG_16,
            LONG_8, LONG_8
    };

    /**
     * The short-window table of each table index.
     */
    private static final int[][] SHORT_128 = {
            SHORT_96, SHORT_96, SHORT_96, SHORT_48, SHORT_48, SHORT_48, SHORT_24, SHORT_24, SHORT_16, SHORT_16,
            SHORT_16, SHORT_8, SHORT_8
    };

    /**
     * The long-window tables of 960-sample frames, derived from {@link #LONG_1024}.
     */
    private static final int[][] LONG_960 = truncateAll(LONG_1024, 960);

    /**
     * The short-window tables of 960-sample frames, derived from {@link #SHORT_128}.
     */
    private static final int[][] SHORT_120 = truncateAll(SHORT_128, 120);

    /**
     * The scalefactor band offsets of 512-sample low delay frames at 44.1 and 48 kHz (ISO/IEC 14496-3
     * subclause 4.5.4).
     */
    private static final int[] LONG_512_48 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8,
            8, 12, 12, 12, 12, 16, 20, 24, 28, 32, 32, 32, 32, 32, 32, 32, 52);

    /**
     * The scalefactor band offsets of 512-sample low delay frames at 32 kHz.
     */
    private static final int[] LONG_512_32 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 8,
            12, 12, 12, 12, 16, 16, 16, 20, 24, 24, 28, 32, 32, 32, 32, 32, 32, 32);

    /**
     * The scalefactor band offsets of 512-sample low delay frames at 22.05 and 24 kHz.
     */
    private static final int[] LONG_512_24 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 12, 12, 12, 16,
            20, 24, 28, 32, 32, 32, 32, 32, 32, 32, 32, 32, 32);

    /**
     * The scalefactor band offsets of 480-sample low delay frames at 44.1 and 48 kHz.
     */
    private static final int[] LONG_480_48 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 8, 8,
            12, 12, 12, 12, 12, 16, 16, 24, 28, 32, 32, 32, 32, 32, 32, 48);

    /**
     * The scalefactor band offsets of 480-sample low delay frames at 32 kHz.
     */
    private static final int[] LONG_480_32 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8,
            8, 8, 8, 12, 12, 12, 16, 16, 20, 24, 32, 32, 32, 32, 32, 32, 32, 32);

    /**
     * The scalefactor band offsets of 480-sample low delay frames at 22.05 and 24 kHz.
     */
    private static final int[] LONG_480_24 = offsetsOf(4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 8, 8, 8, 12, 12, 12, 16,
            20, 24, 28, 32, 32, 32, 32, 32, 32, 32, 32, 32);

    /**
     * The 512-sample low delay table of each table index.
     */
    private static final int[][] LONG_512 = lowDelay(LONG_512_48, LONG_512_32, LONG_512_24);

    /**
     * The 480-sample low delay table of each table index.
     */
    private static final int[][] LONG_480 = lowDelay(LONG_480_48, LONG_480_32, LONG_480_24);

    /**
     * The highest scalefactor band TNS may filter in 512-sample low delay frames, per table index.
     */
    private static final int[] TNS_MAX_BANDS_512 = {31, 31, 31, 31, 32, 37, 31, 31, 31, 31, 31, 31, 31};

    /**
     * The highest scalefactor band TNS may filter in 480-sample low delay frames, per table index.
     */
    private static final int[] TNS_MAX_BANDS_480 = {31, 31, 31, 31, 32, 37, 30, 30, 30, 30, 30, 30, 30};

    /**
     * The highest scalefactor band TNS may filter in long windows, per table index, for the Low
     * Complexity profile (ISO/IEC 14496-3 subclause 4.6.9).
     */
    private static final int[] TNS_MAX_BANDS_LONG = {31, 31, 34, 40, 42, 51, 46, 46, 42, 42, 42, 39, 39};

    /**
     * The highest scalefactor band TNS may filter in short windows, per table index, for the Low
     * Complexity profile (ISO/IEC 14496-3 subclause 4.6.9).
     */
    private static final int[] TNS_MAX_BANDS_SHORT = {9, 9, 10, 14, 14, 14, 14, 14, 14, 14, 14, 14, 14};

    /**
     * The highest scalefactor band TNS may filter in long windows, per table index, for the object
     * types with the PQF filterbank (AAC SSR), from ISO/IEC 14496-3 Table 4.157.
     */
    private static final int[] TNS_MAX_BANDS_PQF_LONG = {28, 28, 27, 26, 26, 26, 29, 29, 23, 23, 23, 19, 19};

    /**
     * The highest scalefactor band TNS may filter in short windows, per table index, for the object
     * types with the PQF filterbank (AAC SSR), from ISO/IEC 14496-3 Table 4.157.
     */
    private static final int[] TNS_MAX_BANDS_PQF_SHORT = {7, 7, 7, 6, 6, 6, 7, 7, 8, 8, 8, 7, 7};

    /**
     * The number of long-window scalefactor bands AAC Main prediction covers, per table index
     * ({@code PRED_SFB_MAX}, ISO/IEC 13818-7 Table 62).
     */
    private static final int[] PREDICTION_MAX_BANDS = {33, 33, 38, 40, 40, 40, 41, 41, 37, 37, 37, 34, 34};

    /**
     * Prevents instantiation.
     */
    private SamplingTables() {
        throw new AssertionError();
    }

    /**
     * Returns the table index serving an arbitrary sampling frequency.
     *
     * <p>Frequencies are mapped to the nearest standard frequency by the ranges ISO/IEC 14496-3
     * subclause 4.5.4 prescribes for explicit frequencies.
     *
     * @param rate the frequency in hertz
     * @return the table index, from 0 to 11
     */
    public static int tableIndexFor(int rate) {
        if (rate >= 92017) {
            return 0;
        }
        if (rate >= 75132) {
            return 1;
        }
        if (rate >= 55426) {
            return 2;
        }
        if (rate >= 46009) {
            return 3;
        }
        if (rate >= 37566) {
            return 4;
        }
        if (rate >= 27713) {
            return 5;
        }
        if (rate >= 23004) {
            return 6;
        }
        if (rate >= 18783) {
            return 7;
        }
        if (rate >= 13856) {
            return 8;
        }
        if (rate >= 11502) {
            return 9;
        }
        if (rate >= 9391) {
            return 10;
        }
        return 11;
    }

    /**
     * Returns the long-window scalefactor band offsets, including the end offset.
     *
     * @param tableIndex the table index
     * @param frameLength the frame length: 1024 or 960, or 512 or 480 for the low delay object types
     * @return the offsets; the array is shared and must not be modified
     */
    public static int[] longOffsets(int tableIndex, int frameLength) {
        return switch (frameLength) {
            case 960 -> LONG_960[tableIndex];
            case 512 -> LONG_512[tableIndex];
            case 480 -> LONG_480[tableIndex];
            default -> LONG_1024[tableIndex];
        };
    }

    /**
     * Returns the short-window scalefactor band offsets, including the end offset.
     *
     * @param tableIndex the table index
     * @param frameLength the long frame length, 1024 or 960
     * @return the offsets; the array is shared and must not be modified
     */
    public static int[] shortOffsets(int tableIndex, int frameLength) {
        return frameLength == 960 ? SHORT_120[tableIndex] : SHORT_128[tableIndex];
    }

    /**
     * Returns the highest band TNS may filter.
     *
     * @param tableIndex the table index
     * @param shortWindow whether the window is short
     * @return the band limit
     */
    public static int tnsMaxBands(int tableIndex, boolean shortWindow) {
        return shortWindow ? TNS_MAX_BANDS_SHORT[tableIndex] : TNS_MAX_BANDS_LONG[tableIndex];
    }

    /**
     * Returns the highest band TNS may filter in a low delay frame.
     *
     * @param tableIndex the table index
     * @param frameLength the frame length, 512 or 480
     * @return the band limit
     */
    public static int tnsMaxBandsLowDelay(int tableIndex, int frameLength) {
        return frameLength == 480 ? TNS_MAX_BANDS_480[tableIndex] : TNS_MAX_BANDS_512[tableIndex];
    }

    /**
     * Returns the highest band TNS may filter in a stream coded with the PQF filterbank (AAC SSR).
     *
     * @param tableIndex the table index
     * @param shortWindow whether the window is short
     * @return the band limit
     */
    public static int tnsMaxBandsPqf(int tableIndex, boolean shortWindow) {
        return shortWindow ? TNS_MAX_BANDS_PQF_SHORT[tableIndex] : TNS_MAX_BANDS_PQF_LONG[tableIndex];
    }

    /**
     * Returns the number of long-window scalefactor bands AAC Main prediction covers.
     *
     * @param tableIndex the table index
     * @return {@code PRED_SFB_MAX}
     */
    public static int predictionMaxBands(int tableIndex) {
        return PREDICTION_MAX_BANDS[tableIndex];
    }

    /**
     * Builds band offsets, closed by the frame length, from band widths.
     *
     * @param widths the width of each band
     * @return the offsets, one more than the widths
     */
    private static int[] offsetsOf(int... widths) {
        var offsets = new int[widths.length + 1];
        for (var i = 0; i < widths.length; i++) {
            offsets[i + 1] = offsets[i] + widths[i];
        }
        return offsets;
    }

    /**
     * Assigns the three low delay tables of a frame length to the table indices.
     *
     * @implNote This implementation serves the rates above 48 kHz with the 48 kHz table and the rates
     * below 22.05 kHz with the 24 kHz table, as the Android libxaac decoder does; ISO/IEC 14496-3
     * defines the low delay tables from 22.05 to 48 kHz only.
     *
     * @param high the table of 44.1 and 48 kHz
     * @param middle the table of 32 kHz
     * @param low the table of 22.05 and 24 kHz
     * @return the table of each table index
     */
    private static int[][] lowDelay(int[] high, int[] middle, int[] low) {
        return new int[][]{high, high, high, high, high, middle, low, low, low, low, low, low, low};
    }

    /**
     * Derives the tables of a shorter transform by truncating each table at {@code end}.
     *
     * @param tables the full-length tables
     * @param end the transform length
     * @return the truncated tables
     */
    private static int[][] truncateAll(int[][] tables, int end) {
        var result = new int[tables.length][];
        for (var i = 0; i < tables.length; i++) {
            result[i] = truncate(tables[i], end);
        }
        return result;
    }

    /**
     * Keeps every band offset below {@code end} and closes the table with {@code end}.
     *
     * @implNote This implementation derives the 960- and 120-line tables of ISO/IEC 14496-3
     * subclause 4.5.4 from the 1024- and 128-line ones: the shorter tables coincide with
     * the longer ones below the transform length.
     *
     * @param offsets the full-length offsets
     * @param end the transform length
     * @return the truncated offsets
     */
    private static int[] truncate(int[] offsets, int end) {
        var count = 0;
        while (offsets[count] < end) {
            count++;
        }
        var result = Arrays.copyOf(offsets, count + 1);
        result[count] = end;
        return result;
    }
}
