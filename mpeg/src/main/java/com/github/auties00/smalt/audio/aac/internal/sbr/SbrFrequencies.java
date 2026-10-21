/* Ports PacketVideo aacdec, Android 4.0.4 (sbr_create_limiter_bands.cpp). Copyright 1998-2010 PacketVideo;
   Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import java.util.Arrays;

/**
 * Derives the frequency band tables, the patches and the limiter bands of spectral band
 * replication from a header and the output sampling rate.
 *
 * <p>All band borders are absolute QMF subband indices. {@link #update(SbrHeader, int)} returns
 * {@code false} for a header that violates the constraints of ISO/IEC 14496-3 (a stop frequency
 * below the start frequency, a range wider than the rate allows, a crossover above subband 32,
 * more than five noise bands, empty bands); the decoder then leaves the high band empty until a
 * valid header arrives. A header whose tables need more than the five patches the standard allows
 * an encoder is decoded with every patch the derivation builds.
 *
 * @implNote This implementation follows the frequency band table and patch derivations of
 * ISO/IEC 14496-3, with the limiter band table built as the PacketVideo AAC decoder builds it. The
 * constraint on the second region of a logarithmic master table limits the width change to half
 * the region's spread, as the PacketVideo decoder does.
 */
final class SbrFrequencies {
    /**
     * The start frequency offsets of ISO/IEC 14496-3, one row per range of output
     * sampling rates.
     */
    private static final int[][] START_OFFSETS = {
            {-8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7},
            {-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13},
            {-5, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16},
            {-6, -4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16},
            {-4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20},
            {-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20, 24}
    };

    /**
     * The number of limiter bands per octave for each value of {@code bs_limiter_bands} from 1.
     */
    private static final double[] LIMITER_BANDS_PER_OCTAVE = {1.2, 2.0, 3.0};

    /**
     * The largest number of patches the derivation can build, one per high subband.
     */
    private static final int PATCH_CAPACITY = 64;

    /**
     * The largest number of noise floor bands the standard allows.
     */
    static final int MAX_NOISE_BANDS = 5;

    /**
     * The master frequency table.
     */
    final int[] master = new int[65];

    /**
     * The number of bands of {@link #master}.
     */
    int masterBands;

    /**
     * The high resolution table, {@link #highBands} + 1 borders.
     */
    final int[] high = new int[65];

    /**
     * The number of high resolution bands.
     */
    int highBands;

    /**
     * The low resolution table, {@link #lowBands} + 1 borders.
     */
    final int[] low = new int[65];

    /**
     * The number of low resolution bands.
     */
    int lowBands;

    /**
     * The noise floor table, {@link #noiseBands} + 1 borders.
     */
    final int[] noise = new int[MAX_NOISE_BANDS + 1];

    /**
     * The number of noise floor bands.
     */
    int noiseBands;

    /**
     * The first subband of the high band, {@code kx}.
     */
    int kx;

    /**
     * The number of subbands of the high band, {@code M}.
     */
    int m;

    /**
     * The first subband of the master table, {@code k0}.
     */
    int k0;

    /**
     * The number of patches.
     */
    int patches;

    /**
     * The number of subbands of each patch.
     */
    final int[] patchSubbands = new int[PATCH_CAPACITY];

    /**
     * The first source subband of each patch.
     */
    final int[] patchStart = new int[PATCH_CAPACITY];

    /**
     * The limiter band table, {@link #limiterBands} + 1 borders.
     */
    final int[] limiter = new int[64 + PATCH_CAPACITY + 1];

    /**
     * The number of limiter bands.
     */
    int limiterBands;

    /**
     * The {@code bs_limiter_bands} value {@link #limiter} was built for, or {@code -1}.
     */
    private int limiterMode = -1;

    /**
     * Scratch widths of the first region of a master table.
     */
    private final int[] widths0 = new int[64];

    /**
     * Scratch widths of the second region of a master table.
     */
    private final int[] widths1 = new int[64];

    /**
     * Creates empty tables, valid once {@link #update} succeeds.
     */
    SbrFrequencies() {
    }

    /**
     * Recomputes every table for a header.
     *
     * @param header the SBR header whose frequency band parameters apply
     * @param sampleRate the output sampling rate of the SBR tool, twice the core rate
     * @return {@code true} when the header describes valid tables
     */
    boolean update(SbrHeader header, int sampleRate) {
        limiterMode = -1;
        var start = startBand(header.startFrequency, sampleRate);
        var stop = stopBand(header.stopFrequency, sampleRate, start);
        if (start < 1 || stop <= start || stop > 64) {
            return false;
        }
        var maxRange = sampleRate >= 48000 ? 32 : sampleRate >= 44100 ? 35 : 48;
        if (stop - start > maxRange) {
            return false;
        }
        k0 = start;
        if (!(header.frequencyScale == 0 ? linearMaster(start, stop, header.alterScale)
                : logMaster(start, stop, header.frequencyScale, header.alterScale))) {
            return false;
        }
        if (header.crossoverBand >= masterBands) {
            return false;
        }
        highBands = masterBands - header.crossoverBand;
        System.arraycopy(master, header.crossoverBand, high, 0, highBands + 1);
        lowBands = (highBands + 1) >> 1;
        low[0] = high[0];
        var odd = highBands & 1;
        for (var k = 1; k <= lowBands; k++) {
            low[k] = high[2 * k - odd];
        }
        kx = high[0];
        m = high[highBands] - kx;
        if (kx > 32 || m <= 0 || kx + m > 64) {
            return false;
        }
        noiseBands = header.noiseBands == 0 ? 1
                : Math.max(1, (int) Math.round(header.noiseBands * Math.log((double) stop / kx) / Math.log(2)));
        if (noiseBands > MAX_NOISE_BANDS) {
            return false;
        }
        noise[0] = low[0];
        var index = 0;
        for (var k = 1; k <= noiseBands; k++) {
            index += (lowBands - index) / (noiseBands + 1 - k);
            noise[k] = low[index];
            if (noise[k] <= noise[k - 1]) {
                return false;
            }
        }
        return buildPatches(sampleRate);
    }

    /**
     * Returns the first subband of the master table, {@code k0}.
     *
     * @param startFrequency the {@code bs_start_freq} index
     * @param sampleRate the SBR output rate
     * @return the subband
     */
    static int startBand(int startFrequency, int sampleRate) {
        var minimum = (int) Math.round((sampleRate < 32000 ? 3000.0 : sampleRate < 64000 ? 4000.0 : 5000.0)
                * 128 / sampleRate);
        int row;
        if (sampleRate <= 16000) {
            row = 0;
        } else if (sampleRate <= 22050) {
            row = 1;
        } else if (sampleRate <= 24000) {
            row = 2;
        } else if (sampleRate <= 32000) {
            row = 3;
        } else if (sampleRate <= 64000) {
            row = 4;
        } else {
            row = 5;
        }
        return minimum + START_OFFSETS[row][startFrequency];
    }

    /**
     * Returns the subband after the last one of the master table, {@code k2}.
     *
     * @param stopFrequency the {@code bs_stop_freq} index
     * @param sampleRate the SBR output rate
     * @param start the first subband {@code k0}
     * @return the subband, at most 64
     */
    static int stopBand(int stopFrequency, int sampleRate, int start) {
        if (stopFrequency == 14) {
            return Math.min(64, 2 * start);
        }
        if (stopFrequency == 15) {
            return Math.min(64, 3 * start);
        }
        var minimum = (int) Math.round((sampleRate < 32000 ? 6000.0 : sampleRate < 64000 ? 8000.0 : 10000.0)
                * 128 / sampleRate);
        if (minimum >= 64) {
            return 64;
        }
        var widths = new int[13];
        var ratio = 64.0 / minimum;
        for (var i = 0; i < 13; i++) {
            widths[i] = (int) Math.round(minimum * Math.pow(ratio, (i + 1) / 13.0))
                    - (int) Math.round(minimum * Math.pow(ratio, i / 13.0));
        }
        Arrays.sort(widths);
        var stop = minimum;
        for (var i = 0; i < stopFrequency; i++) {
            stop += widths[i];
        }
        return Math.min(64, stop);
    }

    /**
     * Builds a linear master table.
     *
     * @param start {@code k0}
     * @param stop {@code k2}
     * @param alterScale {@code bs_alter_scale}
     * @return {@code true} when the table is valid
     */
    private boolean linearMaster(int start, int stop, int alterScale) {
        int width;
        int bands;
        if (alterScale == 0) {
            width = 1;
            bands = 2 * ((stop - start) / 2);
        } else {
            width = 2;
            bands = 2 * (int) Math.round((stop - start) / 4.0);
        }
        if (bands <= 0 || bands > 63) {
            return false;
        }
        Arrays.fill(widths0, 0, bands, width);
        var difference = stop - (start + bands * width);
        if (Math.abs(difference) > bands) {
            return false;
        }
        var increment = difference > 0 ? -1 : 1;
        var k = difference > 0 ? bands - 1 : 0;
        while (difference != 0) {
            widths0[k] -= increment;
            k += increment;
            difference += increment;
        }
        masterBands = bands;
        master[0] = start;
        for (var i = 1; i <= bands; i++) {
            if (widths0[i - 1] <= 0) {
                return false;
            }
            master[i] = master[i - 1] + widths0[i - 1];
        }
        return true;
    }

    /**
     * Builds a logarithmic master table of one or two regions.
     *
     * @param start {@code k0}
     * @param stop {@code k2}
     * @param frequencyScale {@code bs_freq_scale}, from 1 to 3
     * @param alterScale {@code bs_alter_scale}
     * @return {@code true} when the table is valid
     */
    private boolean logMaster(int start, int stop, int frequencyScale, int alterScale) {
        var bandsPerOctave = switch (frequencyScale) {
            case 1 -> 12;
            case 2 -> 10;
            default -> 8;
        };
        var warp = alterScale != 0 ? 1.3 : 1.0;
        var twoRegions = (double) stop / start > 2.2449;
        var middle = twoRegions ? 2 * start : stop;
        var bands0 = 2 * (int) Math.round(bandsPerOctave * Math.log((double) middle / start) / (2 * Math.log(2)));
        if (bands0 <= 0 || bands0 > 63) {
            return false;
        }
        regionWidths(widths0, start, middle, bands0);
        master[0] = start;
        for (var i = 1; i <= bands0; i++) {
            if (widths0[i - 1] <= 0) {
                return false;
            }
            master[i] = master[i - 1] + widths0[i - 1];
        }
        masterBands = bands0;
        if (!twoRegions) {
            return true;
        }
        var bands1 = 2 * (int) Math.round(bandsPerOctave * Math.log((double) stop / middle) / (2 * Math.log(2) * warp));
        if (bands1 <= 0 || bands0 + bands1 > 64) {
            return false;
        }
        regionWidths(widths1, middle, stop, bands1);
        if (widths1[0] < widths0[bands0 - 1]) {
            var change = Math.min(widths0[bands0 - 1] - widths1[0], (widths1[bands1 - 1] - widths1[0]) >> 1);
            widths1[0] += change;
            widths1[bands1 - 1] -= change;
            Arrays.sort(widths1, 0, bands1);
        }
        for (var i = 1; i <= bands1; i++) {
            if (widths1[i - 1] <= 0) {
                return false;
            }
            master[bands0 + i] = master[bands0 + i - 1] + widths1[i - 1];
        }
        masterBands = bands0 + bands1;
        return true;
    }

    /**
     * Computes the sorted band widths of one logarithmic region.
     *
     * @param widths the destination
     * @param from the first subband of the region
     * @param to the subband after the region
     * @param bands the number of bands
     */
    private static void regionWidths(int[] widths, int from, int to, int bands) {
        var ratio = (double) to / from;
        var previous = from;
        for (var k = 0; k < bands; k++) {
            var current = (int) Math.round(from * Math.pow(ratio, (k + 1) / (double) bands));
            widths[k] = current - previous;
            previous = current;
        }
        Arrays.sort(widths, 0, bands);
    }

    /**
     * Builds the patches of the high frequency generator of ISO/IEC 14496-3.
     *
     * @param sampleRate the SBR output rate
     * @return {@code true} when the patches are valid
     */
    private boolean buildPatches(int sampleRate) {
        var msb = k0;
        var usb = kx;
        var count = 0;
        var goal = (int) Math.round(2.048e6 / sampleRate);
        int k;
        if (goal < kx + m) {
            k = 0;
            while (k < masterBands && master[k] < goal) {
                k++;
            }
        } else {
            k = masterBands;
        }
        int sb;
        var rounds = 0;
        do {
            if (++rounds > 64) {
                return false;
            }
            var j = k + 1;
            int odd;
            do {
                j--;
                if (j < 0) {
                    return false;
                }
                sb = master[j];
                odd = (sb - 2 + k0) & 1;
            } while (sb > k0 - 1 + msb - odd);
            var width = Math.max(sb - usb, 0);
            if (width > 0) {
                if (count == PATCH_CAPACITY) {
                    return false;
                }
                patchSubbands[count] = width;
                patchStart[count] = k0 - odd - width;
                if (patchStart[count] < 0) {
                    return false;
                }
                usb = sb;
                msb = sb;
                count++;
            } else {
                msb = kx;
            }
            if (master[k] - sb < 3) {
                k = masterBands;
            }
        } while (sb != kx + m);
        if (count > 1 && patchSubbands[count - 1] < 3) {
            count--;
        }
        if (count == 0) {
            return false;
        }
        patches = count;
        return true;
    }

    /**
     * Builds the limiter band table for a {@code bs_limiter_bands} value unless it is current.
     *
     * @param mode the {@code bs_limiter_bands} value, from 0 to 3
     */
    void updateLimiter(int mode) {
        if (mode == limiterMode) {
            return;
        }
        limiterMode = mode;
        if (mode == 0) {
            limiter[0] = low[0];
            limiter[1] = low[lowBands];
            limiterBands = 1;
            return;
        }
        var borders = new int[patches + 1];
        borders[0] = kx;
        for (var p = 0; p < patches; p++) {
            borders[p + 1] = borders[p] + patchSubbands[p];
        }
        var count = lowBands + 1;
        System.arraycopy(low, 0, limiter, 0, count);
        for (var p = 1; p < patches; p++) {
            limiter[count++] = borders[p];
        }
        Arrays.sort(limiter, 0, count);
        var perOctave = LIMITER_BANDS_PER_OCTAVE[mode - 1];
        var k = 1;
        while (k < count) {
            var octaves = Math.log((double) limiter[k] / limiter[k - 1]) / Math.log(2);
            if (octaves * perOctave >= 0.49) {
                k++;
                continue;
            }
            if (limiter[k] == limiter[k - 1] || !isBorder(borders, limiter[k])) {
                System.arraycopy(limiter, k + 1, limiter, k, count - k - 1);
                count--;
            } else if (!isBorder(borders, limiter[k - 1])) {
                System.arraycopy(limiter, k, limiter, k - 1, count - k);
                count--;
            } else {
                k++;
            }
        }
        limiterBands = count - 1;
    }

    /**
     * Returns whether a subband is a patch border.
     *
     * @param borders the patch borders
     * @param band the subband
     * @return {@code true} when {@code band} starts or ends a patch
     */
    private static boolean isBorder(int[] borders, int band) {
        for (var border : borders) {
            if (border == band) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the band table of a frequency resolution.
     *
     * @param resolution 0 for low, 1 for high
     * @return the table
     */
    int[] table(int resolution) {
        return resolution == 0 ? low : high;
    }

    /**
     * Returns the number of bands of a frequency resolution.
     *
     * @param resolution 0 for low, 1 for high
     * @return the number of bands
     */
    int bands(int resolution) {
        return resolution == 0 ? lowBands : highBands;
    }
}
