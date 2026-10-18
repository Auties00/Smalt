package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Computes the spectral extension parameters of the full-bandwidth channels of an E-AC-3 frame
 * (ATSC A/52 Annex E section 3.6, ETSI TS 102 366 clause E.2.6): the noise blend of each channel and
 * the coordinates of each block, channel and band.
 *
 * <p>The decoder rebuilds the extension region of a channel by copying the coefficients of its
 * copy region (translation), blending them with noise of the same banded energy and scaling each
 * band by a coordinate. The coordinate of a band is therefore the square root of the ratio between
 * the energy of the original band and the energy of the coefficients copied into it; the copied
 * energy is measured on the coefficients the decoder will hold, with the quantization of the
 * baseband and the expected energy of its dither, so that the synthesized band matches the energy
 * of the original. The noise blend of a channel compares the spectral flatness of its original
 * extension region with the flatness of the copied coefficients: the flatter the original is than
 * the copy, the more noise is blended in.
 *
 * <p>Every channel's coordinates are sent in the first block of a frame, where the syntax implies
 * them, and again in a later block when the bands' energy ratios drift from the ones in effect: when
 * the root-mean-square of the natural logarithm of each band's change, weighted by the band's
 * energy, exceeds the logarithm of {@link #DRIFT}. The default band structure of A/52 Annex E
 * table E1.10 is used. An instance is reused from frame to frame. Instances are not thread-safe.
 *
 * @implNote This implementation sets the drift threshold, a factor of 4 in power weighted by band
 * energy, by measuring music and film sound: lower thresholds spend the bits below the extension on
 * coordinates, and never resending within a frame loses the band-energy accuracy of the extended
 * region. Wider bands than the default structure follow the spectral envelope much less closely.
 */
final class SpxAnalysis {
    /**
     * The largest energy-weighted change of the bands' energy ratios, as a factor of power,
     * tolerated before new coordinates are sent within a frame.
     */
    static final float DRIFT = 4.0f;

    /**
     * The spectral extension start copy frequency code {@code spxstrtf}.
     */
    final int startCode;

    /**
     * The spectral extension begin frequency code {@code spxbegf}.
     */
    final int beginCode;

    /**
     * The spectral extension end frequency code {@code spxendf}.
     */
    final int endCode;

    /**
     * The first coefficient of the copy region.
     */
    final int copyStart;

    /**
     * The first coefficient of the extension region: one past the last coefficient the channels
     * in spectral extension code.
     */
    final int begin;

    /**
     * One past the last coefficient of the extension region.
     */
    final int end;

    /**
     * The number of spectral extension bands of the default band structure.
     */
    final int bands;

    /**
     * The first coefficient of each band, and {@link #end} at index {@link #bands}.
     */
    private final int[] bandStart = new int[18];

    /**
     * The coefficient the decoder copies into each coefficient of the extension region.
     */
    private final int[] source = new int[256];

    /**
     * The number of full-bandwidth channels.
     */
    private final int channels;

    /**
     * Whether new coordinates of each channel are sent in each block.
     */
    final boolean[][] sent;

    /**
     * The noise blend {@code spxblnd} of each channel in the frame.
     */
    final int[] blend;

    /**
     * The master coordinate {@code mstrspxco} of each block and channel.
     */
    final int[][] master;

    /**
     * The coordinate exponent {@code spxcoexp} of each block, channel and band.
     */
    final int[][][] exponents;

    /**
     * The coordinate mantissa {@code spxcomant} of each block, channel and band.
     */
    final int[][][] mantissas;

    /**
     * The energy ratio of each channel and band whose coordinates are in effect, measured on the
     * original coefficients.
     */
    private final float[][] current;

    /**
     * The energy ratio of each band of the block being analyzed, scratch.
     */
    private final float[] candidate = new float[17];

    /**
     * The energy of each original band of the block being analyzed, scratch.
     */
    private final double[] energy = new double[17];

    /**
     * The coordinate of each band of the block being coded, scratch.
     */
    private final float[] coordinates = new float[17];

    /**
     * Creates the analysis of a spectral extension configuration.
     *
     * @param startCode the start copy frequency code, from 0 to 3
     * @param beginCode the begin frequency code, from 0 to 7
     * @param endCode the end frequency code, from 0 to 7
     * @param channels the number of full-bandwidth channels
     * @throws IllegalArgumentException if the copy region does not start below the extension region
     * or the extension region is empty
     */
    SpxAnalysis(int startCode, int beginCode, int endCode, int channels) {
        var beginSubband = beginSubband(beginCode);
        var endSubband = endSubband(endCode);
        if (startCode >= beginSubband || beginSubband >= endSubband) {
            throw new IllegalArgumentException("invalid spectral extension range " + startCode + "/" + beginCode + "/"
                    + endCode);
        }
        this.startCode = startCode;
        this.beginCode = beginCode;
        this.endCode = endCode;
        this.copyStart = 25 + 12 * startCode;
        this.begin = 25 + 12 * beginSubband;
        this.end = 25 + 12 * endSubband;
        this.channels = channels;
        var count = 0;
        bandStart[count++] = begin;
        for (var subband = beginSubband + 1; subband < endSubband; subband++) {
            if (!Ac3Tables.DEFAULT_SPX_BANDS[subband]) {
                bandStart[count++] = 25 + 12 * subband;
            }
        }
        bandStart[count] = end;
        this.bands = count;
        var copy = copyStart;
        for (var band = 0; band < bands; band++) {
            var size = bandStart[band + 1] - bandStart[band];
            if (copy + size > begin) {
                copy = copyStart;
            }
            for (var bin = bandStart[band]; bin < bandStart[band + 1]; bin++) {
                if (copy == begin) {
                    copy = copyStart;
                }
                source[bin] = copy++;
            }
        }
        this.sent = new boolean[6][channels];
        this.blend = new int[channels];
        this.master = new int[6][channels];
        this.exponents = new int[6][channels][17];
        this.mantissas = new int[6][channels][17];
        this.current = new float[channels][17];
    }

    /**
     * Returns the first spectral extension sub-band of a begin frequency code.
     *
     * @param code the code {@code spxbegf}
     * @return the sub-band {@code spx_begin_subbnd}
     */
    static int beginSubband(int code) {
        return code < 6 ? code + 2 : code * 2 - 3;
    }

    /**
     * Returns one past the last spectral extension sub-band of an end frequency code.
     *
     * @param code the code {@code spxendf}
     * @return the sub-band {@code spx_end_subbnd}
     */
    static int endSubband(int code) {
        return code < 3 ? code + 5 : code * 2 + 3;
    }

    /**
     * Chooses the noise blend of every channel and the blocks in which every channel sends new
     * coordinates, from the original coefficients of a frame.
     *
     * @implNote This implementation sums the energies of two consecutive sub-bands in one loop, so
     * that their independent double-precision accumulations overlap; each sub-band is still summed
     * in ascending order of mantissa.
     *
     * @param coefficients the coefficients of each block and slot, before rematrixing
     * @param blocks the number of blocks of the frame
     */
    void analyze(float[][][] coefficients, int blocks) {
        for (var ch = 0; ch < channels; ch++) {
            blend[ch] = chooseBlend(coefficients, blocks, ch);
            for (var b = 0; b < blocks; b++) {
                var values = coefficients[b][ch];
                var at = 0;
                for (; at + 1 < bands; at += 2) {
                    var from = bandStart[at];
                    var middle = bandStart[at + 1];
                    var to = bandStart[at + 2];
                    var common = Math.min(middle - from, to - middle);
                    var originalA = 0.0;
                    var copiedA = 0.0;
                    var originalB = 0.0;
                    var copiedB = 0.0;
                    for (var k = 0; k < common; k++) {
                        var a = values[from + k];
                        var b2 = values[middle + k];
                        var copyA = values[source[from + k]];
                        var copyB = values[source[middle + k]];
                        originalA += (double) a * a;
                        originalB += (double) b2 * b2;
                        copiedA += (double) copyA * copyA;
                        copiedB += (double) copyB * copyB;
                    }
                    for (var bin = from + common; bin < middle; bin++) {
                        originalA += (double) values[bin] * values[bin];
                        var copy = values[source[bin]];
                        copiedA += (double) copy * copy;
                    }
                    for (var bin = middle + common; bin < to; bin++) {
                        originalB += (double) values[bin] * values[bin];
                        var copy = values[source[bin]];
                        copiedB += (double) copy * copy;
                    }
                    candidate[at] = (float) ((originalA + 1e-20) / (copiedA + 1e-20));
                    energy[at] = originalA;
                    candidate[at + 1] = (float) ((originalB + 1e-20) / (copiedB + 1e-20));
                    energy[at + 1] = originalB;
                }
                for (var band = at; band < bands; band++) {
                    var original = 0.0;
                    var copied = 0.0;
                    for (var bin = bandStart[band]; bin < bandStart[band + 1]; bin++) {
                        original += (double) values[bin] * values[bin];
                        var copy = values[source[bin]];
                        copied += (double) copy * copy;
                    }
                    candidate[band] = (float) ((original + 1e-20) / (copied + 1e-20));
                    energy[band] = original;
                }
                var effective = current[ch];
                var error = 0.0;
                var total = 0.0;
                for (var band = 0; band < bands; band++) {
                    var log = Math.log(candidate[band] / effective[band]);
                    error += energy[band] * log * log;
                    total += energy[band];
                }
                var limit = Math.log(DRIFT);
                var send = b == 0 || error > total * limit * limit;
                sent[b][ch] = send;
                if (send) {
                    System.arraycopy(candidate, 0, effective, 0, bands);
                }
            }
        }
    }

    /**
     * Returns the noise blend of a channel over a frame.
     *
     * @param coefficients the coefficients of each block and slot
     * @param blocks the number of blocks
     * @param ch the channel
     * @return the blend {@code spxblnd}, from 0 to 31
     */
    private int chooseBlend(float[][][] coefficients, int blocks, int ch) {
        var original = 0.0;
        var copied = 0.0;
        var middle = 0.0;
        for (var band = 0; band < bands; band++) {
            middle += (bandStart[band] + bandStart[band + 1]) * 0.5 / end;
        }
        middle /= bands;
        for (var b = 0; b < blocks; b++) {
            var values = coefficients[b][ch];
            for (var band = 0; band < bands; band++) {
                original += flatness(values, band, false);
                copied += flatness(values, band, true);
            }
        }
        original /= blocks * bands;
        copied /= blocks * bands;
        var ratio = Math.max(0, Math.min(1, (original - copied) / Math.max(1e-3, 1 - copied)));
        return (int) Math.max(0, Math.min(31, Math.round(32 * (middle - ratio))));
    }

    /**
     * Returns the spectral flatness of one band: the geometric mean of its powers over their
     * arithmetic mean.
     *
     * @param values the coefficients of the block and channel
     * @param band the spectral extension band index
     * @param copied whether to measure the coefficients the decoder copies into the band instead of
     * the band's own
     * @return the flatness, from 0 to 1
     */
    private double flatness(float[] values, int band, boolean copied) {
        var logSum = 0.0;
        var sum = 0.0;
        var from = bandStart[band];
        var to = bandStart[band + 1];
        for (var bin = from; bin < to; bin++) {
            var value = values[copied ? source[bin] : bin];
            var power = (double) value * value + 1e-20;
            logSum += Math.log(power);
            sum += power;
        }
        var count = to - from;
        return Math.exp(logSum / count) / (sum / count);
    }

    /**
     * Computes and quantizes the coordinates of a channel in a block that sends them (A/52 Annex E
     * section 3.6.3): the square root of the ratio between the energy of each original band and the
     * energy the decoder copies into it, divided by the decoder's scale of 32.
     *
     * @param b the block
     * @param ch the channel
     * @param original the original coefficients of the channel
     * @param values the coefficients the decoder reconstructs below {@link #begin}, without
     * their dither
     * @param variance the expected power of the dither the decoder adds to each coefficient below
     * {@link #begin}
     */
    void code(int b, int ch, float[] original, float[] values, float[] variance) {
        var smallest = 24;
        for (var band = 0; band < bands; band++) {
            var target = 0.0;
            var copied = 0.0;
            for (var bin = bandStart[band]; bin < bandStart[band + 1]; bin++) {
                target += (double) original[bin] * original[bin];
                var copy = source[bin];
                copied += (double) values[copy] * values[copy] + variance[copy];
            }
            var coordinate = copied > 0 ? (float) (Math.sqrt(target / copied) / 32) : 0;
            coordinates[band] = Math.min(coordinate, 0.875f);
            smallest = Math.min(smallest, leadingZeros(coordinates[band]));
        }
        var masterCode = Math.min(3, smallest / 3);
        master[b][ch] = masterCode;
        var exps = exponents[b][ch];
        var mants = mantissas[b][ch];
        for (var band = 0; band < bands; band++) {
            var value = coordinates[band];
            var exponent = leadingZeros(value) - 3 * masterCode;
            int mantissa;
            if (exponent >= 15) {
                exponent = 15;
                mantissa = Math.min(3, Math.round(value * (1 << (15 + 3 * masterCode)) * 4));
            } else {
                mantissa = Math.round(value * (1 << (exponent + 3 * masterCode)) * 8) - 4;
                if (mantissa >= 4) {
                    if (exponent > 0) {
                        exponent--;
                        mantissa = 0;
                    } else {
                        mantissa = 3;
                    }
                }
                mantissa = Math.max(0, mantissa);
            }
            exps[band] = exponent;
            mants[band] = mantissa;
        }
    }

    /**
     * Returns the number of leading zeros of a coordinate: the smallest {@code e} with
     * {@code value * 2^e >= 0.5}, at most 24.
     *
     * @param value the coordinate, from 0 to below 1
     * @return the count
     */
    private static int leadingZeros(float value) {
        if (value < 0x1p-25f) {
            return 24;
        }
        return Math.min(24, -Math.getExponent(value) - 1);
    }
}
