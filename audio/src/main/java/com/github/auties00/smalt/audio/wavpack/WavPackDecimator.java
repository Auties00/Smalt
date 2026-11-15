/* Ports WavPack 5.8.1 (src/unpack_dsd.c). Copyright (c) 2013 - 2025 David Bryant; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.wavpack;

/**
 * Converts DSD bytes to 24-bit PCM at an eighth of the DSD rate with the reference library's 56-term decimation
 * filter, extrapolating the first samples the filter delay leaves undefined ({@code decimate_dsd_run}).
 *
 * <p>Instances are not thread-safe.
 */
final class WavPackDecimator {
    private static final int[] FILTER = {
            4, 17, 56, 147, 336, 692, 1315, 2337,
            3926, 6281, 9631, 14216, 20275, 28021, 37619, 49155,
            62616, 77870, 94649, 112551, 131049, 149507, 167220, 183448,
            197472, 208636, 216402, 220385, 220385, 216402, 208636, 197472,
            183448, 167220, 149507, 131049, 112551, 94649, 77870, 62616,
            49155, 37619, 28021, 20275, 14216, 9631, 6281, 3926,
            2337, 1315, 692, 336, 147, 56, 17, 4,
    };

    private static final int HISTORY_BYTES = (FILTER.length + 7) / 8;

    private static final int[][] TABLES = tables();

    private final int channels;

    private final int[][] delay;

    private boolean reset = true;

    /**
     * Constructs a decimator for a channel count.
     */
    WavPackDecimator(int channels) {
        this.channels = channels;
        this.delay = new int[channels][HISTORY_BYTES];
        for (var line : delay) {
            java.util.Arrays.fill(line, 0x55);
        }
    }

    /**
     * {@return the channel count}
     */
    int channels() {
        return channels;
    }

    private static int[][] tables() {
        var tables = new int[HISTORY_BYTES][256];
        double sum = 0;
        for (var term : FILTER) {
            sum += term;
        }
        var scale = ((1 << 23) - 1) / sum * 16.0;
        for (var i = 0; i < FILTER.length; i++) {
            var scaled = (int) StrictMath.floor(FILTER[i] * scale + 0.5);
            if (scaled != 0) {
                for (var j = 0; j < 256; j++) {
                    if ((j & (0x80 >> (i & 7))) != 0) {
                        tables[i >> 3][j] += scaled;
                    } else {
                        tables[i >> 3][j] -= scaled;
                    }
                }
            }
        }
        return tables;
    }

    /**
     * Replaces the DSD bytes of each plane with their PCM samples.
     *
     * @param planes the planes, one DSD byte per element
     * @param channels the number of planes
     * @param frames the number of bytes per plane
     */
    void run(int[][] planes, int channels, int frames) {
        for (var i = 0; i < frames; i++) {
            for (var c = 0; c < channels; c++) {
                var line = delay[c];
                var sum = 0;
                for (var k = 0; k < HISTORY_BYTES - 1; k++) {
                    line[k] = line[k + 1];
                    sum += TABLES[k][line[k]];
                }
                line[HISTORY_BYTES - 1] = planes[c][i] & 0xFF;
                sum += TABLES[HISTORY_BYTES - 1][line[HISTORY_BYTES - 1]];
                planes[c][i] = (sum + 8) >> 4;
            }
        }
        if (reset) {
            for (var c = 0; c < channels; c++) {
                extrapolate(planes[c], HISTORY_BYTES - 1, frames);
            }
            reset = false;
        }
    }

    /**
     * Extrapolates the first samples of a plane linearly from the following ones ({@code extrapolate_pcm}).
     */
    private static void extrapolate(int[] samples, int count, int visible) {
        var minPeriod = 5;
        var maxPeriod = 10;
        if (visible < count + minPeriod * 2) {
            return;
        }
        if (visible < count + maxPeriod * 2) {
            maxPeriod = (visible - count) / 2;
        }
        var leftAverage = 0.0f;
        var rightAverage = 0.0f;
        for (var period = minPeriod; period <= maxPeriod; period++) {
            var leftRatio = (count + period / 2.0f) / period;
            var rightRatio = (period / 2.0f) / period;
            var first = count;
            var second = first + period;
            var average1 = 0.0f;
            var average2 = 0.0f;
            for (var i = 0; i < period; i++) {
                average1 += (float) samples[first + i] / period;
                average2 += (float) samples[second + i] / period;
            }
            leftAverage += average1 + (average1 - average2) * leftRatio;
            rightAverage += average1 + (average1 - average2) * rightRatio;
        }
        rightAverage /= (maxPeriod - minPeriod + 1);
        leftAverage /= (maxPeriod - minPeriod + 1);
        var slope = (rightAverage - leftAverage) / (count - 1);
        for (var i = 0; i < count; i++) {
            // the float sum is rounded with a double 0.5, as the reference computes it
            samples[i] = (int) ((double) (leftAverage + i * slope) + 0.5);
        }
    }
}
