/* Ports libxaac (encoder/ixheaace_sbr_rom.c: the spectral band replication tuning table of AAC-LC cores).
   Copyright (C) 2023 The Android Open Source Project, Ittiam Systems; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import java.util.Optional;

/**
 * Selects the frequency range, noise floor resolution and stereo coding of spectral band replication from the core
 * sampling rate, the channel count of an element and its bitrate.
 *
 * <p>An entry whose frequency range the output rate does not allow is narrowed until it does.
 *
 * @param startFrequency the {@code bs_start_freq} index
 * @param stopFrequency the {@code bs_stop_freq} index
 * @param frequencyScale the {@code bs_freq_scale} value, 1 to 3
 * @param noiseBands the {@code bs_noise_bands} value, 1 to 3
 * @param maxNoiseLevel the largest noise floor the encoder sends, in decibels: -3, 3 or 6
 * @param stereoMode the stereo coding of a channel pair: {@link #MONO} for a single channel, {@link #SWITCHED} to
 * choose between independent and coupled coding frame by frame, {@link #INDEPENDENT} to code the channels apart
 */
record SbrTuning(int startFrequency, int stopFrequency, int frequencyScale, int noiseBands, int maxNoiseLevel,
                 int stereoMode) {
    /**
     * The stereo mode of a single channel.
     */
    static final int MONO = 0;

    /**
     * The stereo mode choosing coupled or independent coding per frame.
     */
    static final int SWITCHED = 1;

    /**
     * The stereo mode coding both channels independently.
     */
    static final int INDEPENDENT = 2;

    /**
     * The core sampling rates of the table rows.
     */
    private static final int[] RATES = {8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000};

    /**
     * The entries of each core rate and channel count (mono, then stereo): the lowest and one past the highest
     * bitrate, the start and stop frequency indices, the frequency scale, the noise bands, the noise floor offset,
     * the largest noise level and the stereo mode.
     */
    private static final int[][][][] TABLE = {
            {
                    {{8000, 10000, 7, 11, 3, 1, 0, 6, MONO}, {10000, 12000, 11, 13, 3, 1, 0, 6, MONO},
                            {12000, 48001, 14, 13, 3, 1, 0, 6, MONO}},
                    {}
            },
            {
                    {{8000, 10000, 5, 6, 3, 1, 0, 6, MONO}, {10000, 12000, 8, 12, 3, 1, 0, 6, MONO},
                            {12000, 66001, 12, 13, 3, 1, 0, 6, MONO}},
                    {}
            },
            {
                    {{8000, 10000, 4, 6, 3, 1, 0, 6, MONO}, {10000, 12000, 7, 11, 3, 1, 0, 6, MONO},
                            {12000, 72001, 11, 12, 3, 1, 0, 6, MONO}},
                    {}
            },
            {
                    {{8000, 10000, 1, 0, 3, 1, 0, 6, MONO}, {10000, 12000, 2, 6, 3, 1, 0, 6, MONO},
                            {12000, 16000, 4, 6, 3, 1, 0, 6, MONO}, {16000, 18000, 4, 8, 3, 1, 0, 6, MONO},
                            {18000, 22000, 6, 11, 2, 2, 0, 6, MONO}, {22000, 28000, 10, 12, 2, 2, 0, 6, MONO},
                            {28000, 36000, 12, 13, 2, 2, 0, 3, MONO}, {36000, 44000, 14, 13, 1, 2, 0, 3, MONO},
                            {44000, 96001, 15, 13, 1, 2, 0, 3, MONO}},
                    {{16000, 24000, 4, 1, 3, 1, 0, -3, SWITCHED}, {24000, 28000, 8, 10, 3, 1, 0, -3, SWITCHED},
                            {28000, 36000, 10, 12, 2, 2, 0, -3, SWITCHED}, {36000, 44000, 13, 13, 2, 2, 0, -3, SWITCHED},
                            {44000, 52000, 15, 13, 2, 2, 0, -3, SWITCHED}, {52000, 60000, 15, 13, 1, 3, 0, -3, SWITCHED},
                            {60000, 76000, 15, 13, 1, 3, 0, -3, INDEPENDENT},
                            {76000, 192001, 15, 13, 1, 3, 0, -3, INDEPENDENT}}
            },
            {
                    {{8000, 11369, 1, 1, 3, 1, 0, 6, MONO}, {11369, 16000, 3, 4, 3, 1, 0, 6, MONO},
                            {16000, 18000, 3, 5, 3, 1, 0, 6, MONO}, {18000, 22000, 4, 8, 2, 2, 0, 6, MONO},
                            {22000, 28000, 7, 8, 2, 2, 0, 6, MONO}, {28000, 36000, 10, 9, 2, 2, 0, 3, MONO},
                            {36000, 44000, 11, 10, 1, 2, 0, 3, MONO}, {44000, 132301, 13, 12, 1, 2, 0, 3, MONO}},
                    {{16000, 24000, 2, 1, 3, 1, 0, -3, SWITCHED}, {24000, 28000, 5, 6, 3, 1, 0, -3, SWITCHED},
                            {28000, 32000, 5, 8, 2, 2, 0, -3, SWITCHED}, {32000, 36000, 7, 8, 2, 2, 0, -3, SWITCHED},
                            {36000, 44000, 10, 9, 2, 2, 0, -3, SWITCHED}, {44000, 52000, 12, 9, 2, 3, 0, -3, SWITCHED},
                            {52000, 60000, 13, 10, 1, 3, 0, -3, SWITCHED},
                            {60000, 76000, 14, 12, 1, 3, 0, -3, INDEPENDENT},
                            {76000, 264601, 14, 12, 1, 3, 0, -3, INDEPENDENT}}
            },
            {
                    {{8000, 12000, 1, 1, 3, 1, 0, 6, MONO}, {12000, 16000, 3, 4, 3, 1, 0, 6, MONO},
                            {16000, 18000, 3, 5, 3, 1, 0, 6, MONO}, {18000, 22000, 4, 8, 2, 2, 0, 6, MONO},
                            {22000, 28000, 7, 8, 2, 2, 0, 6, MONO}, {28000, 36000, 10, 9, 2, 2, 0, 3, MONO},
                            {36000, 44000, 11, 10, 1, 2, 0, 3, MONO}, {44000, 144001, 13, 11, 1, 2, 0, 3, MONO}},
                    {{16000, 24000, 2, 1, 3, 1, 0, -3, SWITCHED}, {24000, 28000, 5, 6, 3, 1, 0, -3, SWITCHED},
                            {28000, 36000, 7, 8, 2, 2, 0, -3, SWITCHED}, {36000, 44000, 10, 9, 2, 2, 0, -3, SWITCHED},
                            {44000, 52000, 12, 9, 2, 3, 0, -3, SWITCHED}, {52000, 60000, 13, 10, 1, 3, 0, -3, SWITCHED},
                            {60000, 76000, 14, 12, 1, 3, 0, -3, INDEPENDENT},
                            {76000, 288001, 15, 12, 1, 3, 0, -3, INDEPENDENT}}
            },
            {
                    {{24000, 36000, 4, 4, 3, 2, 0, 3, MONO}, {36000, 60000, 7, 6, 2, 2, 0, 3, MONO},
                            {60000, 72000, 9, 8, 1, 2, 0, 3, MONO}, {72000, 100000, 11, 10, 1, 2, 0, 3, MONO},
                            {100000, 192001, 13, 11, 1, 2, 0, 3, MONO}},
                    {{32000, 60000, 4, 4, 3, 2, 0, -3, SWITCHED}, {60000, 80000, 7, 6, 2, 3, 0, -3, SWITCHED},
                            {80000, 112000, 9, 8, 1, 3, 0, -3, INDEPENDENT},
                            {112000, 144000, 11, 10, 1, 3, 0, -3, INDEPENDENT},
                            {144000, 384001, 13, 11, 1, 3, 0, -3, INDEPENDENT}}
            },
            {
                    {{24000, 36000, 4, 4, 3, 2, 0, 3, MONO}, {36000, 60000, 7, 6, 2, 2, 0, 3, MONO},
                            {60000, 72000, 9, 8, 1, 2, 0, 3, MONO}, {72000, 100000, 11, 10, 1, 2, 0, 3, MONO},
                            {100000, 264601, 13, 11, 1, 2, 0, 3, MONO}},
                    {{32000, 60000, 4, 4, 3, 2, 0, -3, SWITCHED}, {60000, 80000, 7, 6, 2, 3, 0, -3, SWITCHED},
                            {80000, 112000, 9, 8, 1, 3, 0, -3, INDEPENDENT},
                            {112000, 144000, 11, 10, 1, 3, 0, -3, INDEPENDENT},
                            {144000, 529201, 13, 11, 1, 3, 0, -3, INDEPENDENT}}
            },
            {
                    {{24000, 36000, 4, 9, 3, 2, 0, 3, MONO}, {36000, 60000, 7, 10, 2, 2, 0, 3, MONO},
                            {60000, 72000, 9, 10, 1, 2, 0, 3, MONO}, {72000, 100000, 11, 11, 1, 2, 0, 3, MONO},
                            {100000, 288001, 13, 11, 1, 2, 0, 3, MONO}},
                    {{32000, 60000, 4, 9, 3, 2, 0, -3, SWITCHED}, {60000, 80000, 7, 10, 2, 3, 0, -3, SWITCHED},
                            {80000, 112000, 9, 10, 1, 3, 0, -3, INDEPENDENT},
                            {112000, 144000, 11, 11, 1, 3, 0, -3, INDEPENDENT},
                            {144000, 576001, 13, 11, 1, 3, 0, -3, INDEPENDENT}}
            }
    };

    /**
     * Returns the tuning of an element, or empty when the table has none for the rate, the channel count and the
     * bitrate.
     *
     * @param coreRate the core sampling rate
     * @param channels the channels of the element, 1 or 2
     * @param bitrate the bitrate of the element, core and extension, in bits per second
     * @return the tuning
     */
    static Optional<SbrTuning> of(int coreRate, int channels, int bitrate) {
        var row = row(coreRate, channels);
        if (row == null) {
            return Optional.empty();
        }
        for (var entry : row) {
            if (bitrate >= entry[0] && bitrate < entry[1]) {
                return Optional.of(fit(entry, 2 * coreRate));
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the tuning of a table entry with frequency band tables the decoder accepts at an output rate.
     *
     * <p>The entry's start and stop frequencies are kept when they satisfy the constraints of ISO/IEC 14496-3 (the
     * range of subbands an output rate allows, at most five patches); otherwise the stop frequency, and if needed
     * the start frequency, is lowered until they do.
     *
     * @param entry the table entry
     * @param outputRate the output rate
     * @return the tuning
     */
    private static SbrTuning fit(int[] entry, int outputRate) {
        for (var start = entry[2]; start >= 0; start--) {
            for (var stop = entry[3]; stop >= 0; stop--) {
                var tuning = new SbrTuning(start, stop, entry[4], entry[5], entry[7], entry[8]);
                if (valid(tuning, outputRate)) {
                    return tuning;
                }
            }
        }
        throw new AssertionError("no valid SBR tuning near " + java.util.Arrays.toString(entry));
    }

    /**
     * Returns whether a tuning derives frequency band tables the decoder accepts, with at most five patches.
     *
     * @param tuning the frequency range and noise resolution to check
     * @param outputRate the output rate
     * @return {@code true} when the tables are valid
     */
    private static boolean valid(SbrTuning tuning, int outputRate) {
        var header = new SbrHeader();
        header.startFrequency = tuning.startFrequency();
        header.stopFrequency = tuning.stopFrequency();
        header.frequencyScale = tuning.frequencyScale();
        header.noiseBands = tuning.noiseBands();
        var tables = new SbrFrequencies();
        return tables.update(header, outputRate) && tables.patches <= 5;
    }

    /**
     * Returns the lowest bitrate the table tunes for a core rate and a channel count, or 0 when it has none.
     *
     * @param coreRate the core sampling rate
     * @param channels the channels of the element, 1 or 2
     * @return the bitrate in bits per second
     */
    static int minimumBitrate(int coreRate, int channels) {
        var row = row(coreRate, channels);
        return row == null ? 0 : row[0][0];
    }

    /**
     * Returns the highest bitrate the table tunes for a core rate and a channel count, or 0 when it has none.
     *
     * @param coreRate the core sampling rate
     * @param channels the channels of the element, 1 or 2
     * @return the bitrate in bits per second
     */
    static int maximumBitrate(int coreRate, int channels) {
        var row = row(coreRate, channels);
        return row == null ? 0 : row[row.length - 1][1] - 1;
    }

    /**
     * Returns the entries of a core rate and channel count.
     *
     * @param coreRate the core sampling rate
     * @param channels the channels, 1 or 2
     * @return the entries, or {@code null} when the table has none
     */
    private static int[][] row(int coreRate, int channels) {
        for (var i = 0; i < RATES.length; i++) {
            if (RATES[i] == coreRate) {
                var row = TABLE[i][channels - 1];
                return row.length == 0 ? null : row;
            }
        }
        return null;
    }
}
