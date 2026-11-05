/* Ports the ISO/IEC 23003-3:2019 reference software (ct_freqsca.c and resetSbrFreqBandTables of ct_sbrdec.c).
   Copyright Coding Technologies and ISO/IEC 2002; ISO/IEC reference software licence (use in products conforming
   to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;

/**
 * Holds the frequency band tables of one enhanced spectral band replication channel: the master table, the high
 * and low resolution envelope tables and the noise floor table (ISO/IEC 14496-3 subclause 4.6.18.3.2 with the
 * 4:1 extensions of ISO/IEC 23003-3).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrFrequencies {
    /**
     * The index of the low resolution tables.
     */
    static final int LOW = 0;

    /**
     * The index of the high resolution tables.
     */
    static final int HIGH = 1;

    private static final int CAPACITY = 65;

    /**
     * The master band table, {@code masterBands + 1} borders.
     */
    final int[] master = new int[CAPACITY];

    /**
     * The number of master bands.
     */
    int masterBands;

    /**
     * The envelope band tables, indexed by resolution.
     */
    final int[][] table = {new int[CAPACITY], new int[CAPACITY]};

    /**
     * The number of envelope bands, indexed by resolution.
     */
    final int[] bands = new int[2];

    /**
     * The noise floor band table, {@code noiseBands + 1} borders.
     */
    final int[] noise = new int[CAPACITY];

    /**
     * The number of noise floor bands.
     */
    int noiseBands;

    /**
     * The first subband of the replicated range.
     */
    int lowSubband;

    /**
     * The subband past the replicated range.
     */
    int highSubband;

    private final int[] scratch = new int[CAPACITY];
    private final int[] scratch2 = new int[CAPACITY];
    private final int[] stopDeltas = new int[14];

    /**
     * Rebuilds every table from a header.
     *
     * @param header the SBR header the tables follow
     * @param sampleRate the output sampling rate
     * @param upsample the SBR ratio, 2 or 4
     * @throws MediaException.Malformed if the header describes an invalid band layout
     */
    void reset(EsbrHeader header, int sampleRate, int upsample) {
        var start = startBand(sampleRate, header.startFrequency, upsample);
        int stop;
        if (header.stopFrequency < 14) {
            stop = stopBand(sampleRate, header.stopFrequency, upsample);
        } else if (header.stopFrequency == 14) {
            stop = 2 * start;
        } else {
            stop = 3 * start;
        }
        stop = Math.min(stop, 64);
        var span = stop - start;
        if (upsample == 4 ? span > 56 : span > 48 || sampleRate == 44100 && span > 35 || sampleRate >= 48000 && span > 32) {
            throw new MediaException.Malformed("SBR header describes a replicated range of " + span + " subbands");
        }
        if (start < 1 || span <= 0) {
            throw new MediaException.Malformed("SBR header describes an empty replicated range");
        }
        updateMaster(start, stop, header.frequencyScale, header.alterScale, upsample);
        var high = masterBands - header.crossoverBand;
        if (high <= 0) {
            throw new MediaException.Malformed("SBR crossover band exceeds the master table");
        }
        bands[HIGH] = high;
        for (var i = header.crossoverBand; i <= masterBands; i++) {
            table[HIGH][i - header.crossoverBand] = master[i];
        }
        var hiRes = table[HIGH];
        var loRes = table[LOW];
        if (high % 2 == 0) {
            bands[LOW] = high / 2;
            for (var i = 0; i <= bands[LOW]; i++) {
                loRes[i] = hiRes[2 * i];
            }
        } else {
            bands[LOW] = (high + 1) / 2;
            loRes[0] = hiRes[0];
            for (var i = 1; i <= bands[LOW]; i++) {
                loRes[i] = hiRes[2 * i - 1];
            }
        }
        lowSubband = loRes[0];
        highSubband = loRes[bands[LOW]];
        if (header.noiseBands == 0) {
            noiseBands = 1;
        } else {
            noiseBands = (int) (header.noiseBands * StrictMath.log((double) highSubband / lowSubband) / StrictMath.log(2.0) + 0.5);
            if (noiseBands == 0) {
                noiseBands = 1;
            }
        }
        if (noiseBands > 5 || noiseBands > bands[LOW]) {
            throw new MediaException.Malformed("SBR header describes " + noiseBands + " noise floor bands");
        }
        downsample(noise, noiseBands, loRes, bands[LOW]);
    }

    private static int startBand(int fs, int startFrequency, int upsample) {
        if (upsample == 4) {
            fs = fs / 2;
        }
        int mapped;
        if (fs < 18783) {
            mapped = 16000;
        } else if (fs < 23004) {
            mapped = 22050;
        } else if (fs < 27713) {
            mapped = 24000;
        } else if (fs < 35777) {
            mapped = 32000;
        } else if (fs < 42000) {
            mapped = 40000;
        } else if (fs < 46009) {
            mapped = 44100;
        } else if (fs < 55426) {
            mapped = 48000;
        } else if (fs < 75132) {
            mapped = 64000;
        } else if (fs < 92017) {
            mapped = 88200;
        } else {
            mapped = 96000;
        }
        var bandsPerHalf = upsample == 4 ? 32 : 64;
        int minimum;
        if (mapped < 32000) {
            minimum = (int) ((float) (3000 * 2 * bandsPerHalf) / mapped + 0.5);
        } else if (mapped < 64000) {
            minimum = (int) ((float) (4000 * 2 * bandsPerHalf) / mapped + 0.5);
        } else {
            minimum = (int) ((float) (5000 * 2 * bandsPerHalf) / mapped + 0.5);
        }
        var offsets = switch (mapped) {
            case 16000 -> START_16000;
            case 22050 -> START_22050;
            case 24000 -> START_24000;
            case 32000 -> START_32000;
            case 40000 -> START_40000;
            case 44100, 48000, 64000 -> START_48000;
            default -> START_96000;
        };
        return minimum + offsets[startFrequency];
    }

    private static final int[] START_16000 = {-8, -7, -6, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7};
    private static final int[] START_22050 = {-5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13};
    private static final int[] START_24000 = {-5, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16};
    private static final int[] START_32000 = {-6, -4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16};
    private static final int[] START_40000 = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 13, 15, 17, 19};
    private static final int[] START_48000 = {-4, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20};
    private static final int[] START_96000 = {-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 13, 16, 20, 24};

    private int stopBand(int fs, int stopFrequency, int upsample) {
        var bandsPerHalf = 64;
        if (upsample == 4) {
            fs = fs / 2;
            bandsPerHalf = 32;
        }
        int minimum;
        if (fs < 32000) {
            minimum = (int) ((float) (6000 * 2 * bandsPerHalf) / fs + 0.5);
        } else if (fs < 64000) {
            minimum = (int) ((float) (8000 * 2 * bandsPerHalf) / fs + 0.5);
        } else {
            minimum = (int) ((float) (10000 * 2 * bandsPerHalf) / fs + 0.5);
        }
        var previous = minimum;
        for (var i = 1; i <= 13; i++) {
            var current = (int) (minimum * StrictMath.pow(64.0 / minimum, i / 13.0) + 0.5);
            stopDeltas[i - 1] = current - previous;
            previous = current;
        }
        shellsort(stopDeltas, 13);
        var result = minimum;
        for (var i = 0; i < stopFrequency; i++) {
            result += stopDeltas[i];
        }
        return result;
    }

    private void updateMaster(int start, int stop, int frequencyScale, int alterScale, int upsample) {
        if (frequencyScale > 0) {
            var bandsPerOctave = frequencyScale == 1 ? 12 : frequencyScale == 2 ? 10 : 8;
            var warp = alterScale == 0 ? 1.0f : 1.3f;
            if (upsample == 4 && start < bandsPerOctave) {
                bandsPerOctave = (start / 2) * 2;
            }
            int middle;
            int regions;
            if ((float) stop / start > 2.2449) {
                regions = 2;
                middle = 2 * start;
            } else {
                regions = 1;
                middle = stop;
            }
            var first = numberOfBands(bandsPerOctave, start, middle, 1.0f);
            checkBands(first);
            calcBands(scratch, start, middle, first);
            shellsort(scratch, first);
            cumulate(start, scratch, first, master, 0);
            masterBands = first;
            if (regions == 2) {
                var second = numberOfBands(bandsPerOctave, middle, stop, warp);
                checkBands(first + second);
                calcBands(scratch2, middle, stop, second);
                shellsort(scratch2, second);
                if (first > 0 && second > 0 && scratch[first - 1] > scratch2[0]) {
                    var change = scratch[first - 1] - scratch2[0];
                    if (change > (scratch2[second - 1] - scratch2[0]) / 2) {
                        change = (scratch2[second - 1] - scratch2[0]) / 2;
                    }
                    scratch2[0] += change;
                    scratch2[second - 1] -= change;
                    shellsort(scratch2, second);
                }
                cumulate(middle, scratch2, second, master, masterBands);
                masterBands += second;
            }
        } else {
            var step = alterScale == 0 ? 1 : 2;
            var count = 2 * (int) ((float) (stop - start) / (step * 2) + (-1 + step) / 2.0f);
            checkBands(count);
            var difference = stop - (start + count * step);
            for (var i = 0; i < count; i++) {
                scratch[i] = step;
            }
            var increment = 0;
            var i = 0;
            if (difference < 0) {
                increment = 1;
                i = 0;
            }
            if (difference > 0) {
                increment = -1;
                i = count - 1;
            }
            while (difference != 0) {
                if (i < 0 || i >= count) {
                    throw new MediaException.Malformed("SBR header describes an invalid linear master table");
                }
                scratch[i] -= increment;
                i += increment;
                difference += increment;
            }
            cumulate(start, scratch, count, master, 0);
            masterBands = count;
        }
        if (masterBands < 1 || master[masterBands] - master[masterBands - 1] > start - 2) {
            throw new MediaException.Malformed("SBR header describes an invalid master table");
        }
        for (var i = 0; i < masterBands; i++) {
            if (master[i + 1] <= master[i]) {
                throw new MediaException.Malformed("SBR header describes an invalid master table");
            }
        }
    }

    private static void checkBands(int count) {
        if (count < 0 || count > CAPACITY - 2) {
            throw new MediaException.Malformed("SBR header describes " + count + " master bands");
        }
    }

    private static int numberOfBands(int bandsPerOctave, int start, int stop, float warp) {
        return 2 * (int) (bandsPerOctave * StrictMath.log((float) stop / start) / (2 * StrictMath.log(2.0) * warp) + 0.5);
    }

    private static void calcBands(int[] difference, int start, int stop, int count) {
        var previous = start;
        for (var i = 1; i <= count; i++) {
            var current = (int) (start * StrictMath.pow((float) stop / start, (float) i / count) + 0.5);
            difference[i - 1] = current - previous;
            previous = current;
        }
    }

    private static void cumulate(int start, int[] difference, int length, int[] out, int offset) {
        out[offset] = start;
        for (var i = 1; i <= length; i++) {
            out[offset + i] = out[offset + i - 1] + difference[i - 1];
        }
    }

    private static void downsample(int[] result, int count, int[] reference, int referenceCount) {
        var original = referenceCount;
        var remaining = count;
        var index = 0;
        result[0] = reference[0];
        var i = 0;
        while (original > 0) {
            i++;
            var step = original / remaining;
            original -= step;
            remaining--;
            index += step;
            result[i] = reference[index];
        }
    }

    /**
     * Sorts the first values of an array in ascending order, with the shell sort of the reference decoder.
     *
     * @param values the array to sort
     * @param count the number of values to sort
     */
    static void shellsort(int[] values, int count) {
        var increment = 1;
        do {
            increment = 3 * increment + 1;
        } while (increment <= count);
        do {
            increment = increment / 3;
            for (var i = increment + 1; i <= count; i++) {
                var v = values[i - 1];
                var j = i;
                while (values[j - increment - 1] > v) {
                    values[j - 1] = values[j - increment - 1];
                    j -= increment;
                    if (j <= increment) {
                        break;
                    }
                }
                values[j - 1] = v;
            }
        } while (increment > 1);
    }
}
