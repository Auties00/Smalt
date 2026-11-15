package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

/**
 * Encodes parametric stereo (HE-AAC v2): downmixes the QMF columns of a left and a right channel into the mono
 * columns the core and the spectral band replication code, and measures, quantizes and writes the inter-channel
 * intensity differences and coherences of each frame ({@code ps_data}).
 *
 * <p>The downmix keeps, sample by sample, the phase of the sum of the channels and the mean power of the two, so
 * that the decoder's upmix restores each channel's power; it gains at most 6 dB where the channels cancel. The
 * parameters use the 20 or 10 stereo bands of ISO/IEC 14496-3 8.6.4 on the coarse quantizers, with the hybrid
 * filterbank of the decoder splitting QMF subbands 0 to 2, fixed envelope borders and no phase parameters. Each
 * envelope's parameters are measured over a window centered on the border where the decoder reaches them.
 * Instances are not thread-safe.
 */
final class PsEncoder {
    /**
     * The number of stereo bands of the 20-band configuration.
     */
    private static final int BINS = 20;

    /**
     * The left channel's columns.
     */
    private final SbrColumns left;

    /**
     * The right channel's columns.
     */
    private final SbrColumns right;

    /**
     * The downmixed columns.
     */
    private final SbrColumns mono;

    /**
     * The QMF columns per frame.
     */
    private final int columnsPerFrame;

    /**
     * Whether the parameters use 10 stereo bands instead of 20.
     */
    private final boolean tenBands;

    /**
     * The number of envelopes per frame, 1 or 2.
     */
    private final int envelopes;

    /**
     * The intensity difference index of each envelope and band.
     */
    private final int[][] iid = new int[2][BINS];

    /**
     * The coherence index of each envelope and band.
     */
    private final int[][] icc = new int[2][BINS];

    /**
     * The last envelope's intensity difference indices of the previous frame, in the coded band count.
     */
    private final int[] previousIid = new int[BINS];

    /**
     * The last envelope's coherence indices of the previous frame.
     */
    private final int[] previousIcc = new int[BINS];

    /**
     * The left channel's power in each stereo band.
     */
    private final double[] powerLeft = new double[BINS];

    /**
     * The right channel's power in each stereo band.
     */
    private final double[] powerRight = new double[BINS];

    /**
     * The real part of the cross power of the channels in each stereo band.
     */
    private final double[] cross = new double[BINS];

    /**
     * The left hybrid sub-subbands of one column.
     */
    private final double[] hybridLeftRe = new double[10];

    /**
     * The left hybrid sub-subbands of one column, imaginary parts.
     */
    private final double[] hybridLeftIm = new double[10];

    /**
     * The right hybrid sub-subbands of one column.
     */
    private final double[] hybridRightRe = new double[10];

    /**
     * The right hybrid sub-subbands of one column, imaginary parts.
     */
    private final double[] hybridRightIm = new double[10];

    /**
     * Scratch outputs of the 8-band complex filter.
     */
    private final double[] filterRe = new double[8];

    /**
     * Scratch outputs of the 8-band complex filter, imaginary parts.
     */
    private final double[] filterIm = new double[8];

    /**
     * Creates an encoder.
     *
     * @param left the left channel's columns
     * @param right the right channel's columns
     * @param mono the destination of the downmix
     * @param timeSlots the number of SBR time slots per frame, 16 or 15
     * @param tenBands whether to code 10 stereo bands instead of 20
     * @param envelopes the number of envelopes per frame, 1 or 2
     */
    PsEncoder(SbrColumns left, SbrColumns right, SbrColumns mono, int timeSlots, boolean tenBands, int envelopes) {
        this.left = left;
        this.right = right;
        this.mono = mono;
        this.columnsPerFrame = 2 * timeSlots;
        this.tenBands = tenBands;
        this.envelopes = envelopes;
    }

    /**
     * Downmixes one column.
     *
     * @param column the index of a column already written to both channels
     */
    void downmix(long column) {
        var offset = SbrColumns.offset(column);
        var lre = left.re();
        var lim = left.im();
        var rre = right.re();
        var rim = right.im();
        var mre = mono.re();
        var mim = mono.im();
        for (var k = 0; k < 64; k++) {
            var i = offset + k;
            var sr = lre[i] + rre[i];
            var si = lim[i] + rim[i];
            var sum = (sr * sr + si * si) / 2;
            var mean = sum - (lre[i] * rre[i] + lim[i] * rim[i]);
            float gain;
            if (mean / 8 >= sum) {
                gain = 2;
            } else {
                gain = (float) Math.sqrt(mean / (2 * sum));
            }
            mre[i] = sr * gain;
            mim[i] = si * gain;
        }
    }

    /**
     * Measures and quantizes the parameters of one frame.
     *
     * @param base the index of the column aligned with the frame's first QMF slot
     */
    void analyze(long base) {
        var span = columnsPerFrame / envelopes;
        for (var e = 0; e < envelopes; e++) {
            var border = base + (long) (e + 1) * columnsPerFrame / envelopes;
            measure(border - span / 2, border + (span - span / 2));
            var bins = tenBands ? BINS / 2 : BINS;
            for (var b = 0; b < bins; b++) {
                double l;
                double r;
                double x;
                if (tenBands) {
                    l = powerLeft[2 * b] + powerLeft[2 * b + 1];
                    r = powerRight[2 * b] + powerRight[2 * b + 1];
                    x = cross[2 * b] + cross[2 * b + 1];
                } else {
                    l = powerLeft[b];
                    r = powerRight[b];
                    x = cross[b];
                }
                iid[e][b] = quantizeIid(l, r);
                icc[e][b] = quantizeIcc(l, r, x);
            }
        }
    }

    /**
     * Accumulates the powers and cross power of every stereo band over a range of columns.
     *
     * @param from the first column
     * @param to the column past the last
     */
    private void measure(long from, long to) {
        java.util.Arrays.fill(powerLeft, 0);
        java.util.Arrays.fill(powerRight, 0);
        java.util.Arrays.fill(cross, 0);
        var lre = left.re();
        var lim = left.im();
        var rre = right.re();
        var rim = right.im();
        for (var c = from; c < to; c++) {
            hybrid(left, c, hybridLeftRe, hybridLeftIm);
            hybrid(right, c, hybridRightRe, hybridRightIm);
            for (var h = 0; h < 10; h++) {
                var bin = PsDecoder.HYBRID_BINS_20[h];
                var ar = hybridLeftRe[h];
                var ai = hybridLeftIm[h];
                var br = hybridRightRe[h];
                var bi = hybridRightIm[h];
                powerLeft[bin] += ar * ar + ai * ai;
                powerRight[bin] += br * br + bi * bi;
                cross[bin] += ar * br + ai * bi;
            }
            var offset = SbrColumns.offset(c);
            var borders = PsDecoder.QMF_BORDERS_20;
            for (var g = 0; g < borders.length - 1; g++) {
                var bin = 8 + g;
                for (var k = borders[g]; k < borders[g + 1]; k++) {
                    double ar = lre[offset + k];
                    double ai = lim[offset + k];
                    double br = rre[offset + k];
                    double bi = rim[offset + k];
                    powerLeft[bin] += ar * ar + ai * ai;
                    powerRight[bin] += br * br + bi * bi;
                    cross[bin] += ar * br + ai * bi;
                }
            }
        }
    }

    /**
     * Splits QMF subbands 0 to 2 of one column into the ten hybrid sub-subbands of the 20-band configuration, as
     * the decoder's hybrid filterbank does, centered on the column.
     *
     * @param columns the channel's columns
     * @param column the index of the column the sub-subbands are centered on
     * @param re the destination real parts
     * @param im the destination imaginary parts
     */
    private void hybrid(SbrColumns columns, long column, double[] re, double[] im) {
        var sre = columns.re();
        var sim = columns.im();
        for (var q = 0; q < 8; q++) {
            var tapsRe = PsDecoder.TAPS_20_RE[q];
            var tapsIm = PsDecoder.TAPS_20_IM[q];
            double sumRe = 0;
            double sumIm = 0;
            for (var n = 0; n < 13; n++) {
                var index = SbrColumns.offset(column - 6 + n);
                double xr = sre[index];
                double xi = sim[index];
                sumRe += tapsRe[n] * xr - tapsIm[n] * xi;
                sumIm += tapsRe[n] * xi + tapsIm[n] * xr;
            }
            filterRe[q] = sumRe;
            filterIm[q] = sumIm;
        }
        re[0] = filterRe[6];
        im[0] = filterIm[6];
        re[1] = filterRe[7];
        im[1] = filterIm[7];
        re[2] = filterRe[0];
        im[2] = filterIm[0];
        re[3] = filterRe[1];
        im[3] = filterIm[1];
        re[4] = filterRe[2] + filterRe[5];
        im[4] = filterIm[2] + filterIm[5];
        re[5] = filterRe[3] + filterRe[4];
        im[5] = filterIm[3] + filterIm[4];
        for (var band = 1; band < 3; band++) {
            double lowRe = 0;
            double lowIm = 0;
            double highRe = 0;
            double highIm = 0;
            for (var n = 0; n < 13; n++) {
                var tap = PsDecoder.PROTOTYPE_2[n];
                if (tap == 0) {
                    continue;
                }
                var index = SbrColumns.offset(column - 6 + n) + band;
                if ((n & 1) == 0) {
                    lowRe += tap * sre[index];
                    lowIm += tap * sim[index];
                } else {
                    highRe += tap * sre[index];
                    highIm += tap * sim[index];
                }
            }
            // QMF subband 1 is spectrally reversed, so its low-pass output is the upper sub-subband
            var lower = band == 1 ? 6 : 8;
            var low = band == 1 ? lower + 1 : lower;
            var high = band == 1 ? lower : lower + 1;
            re[low] = lowRe + highRe;
            im[low] = lowIm + highIm;
            re[high] = lowRe - highRe;
            im[high] = lowIm - highIm;
        }
    }

    /**
     * Quantizes the intensity difference of two powers to the coarse quantizer.
     *
     * @param left the left power
     * @param right the right power
     * @return the index, from -7 to 7
     */
    private static int quantizeIid(double left, double right) {
        if (left + right < 1e-6) {
            return 0;
        }
        var db = 10 * StrictMath.log10((left + 1e-9) / (right + 1e-9));
        var levels = PsDecoder.IID_COARSE;
        var best = 0;
        var error = Double.MAX_VALUE;
        for (var i = 0; i < levels.length; i++) {
            var e = Math.abs(levels[i] - db);
            if (e < error) {
                error = e;
                best = i;
            }
        }
        return best - 7;
    }

    /**
     * Quantizes the normalized correlation of two channels.
     *
     * @param left the left power
     * @param right the right power
     * @param cross the real part of the cross power
     * @return the index, from 0 to 7
     */
    private static int quantizeIcc(double left, double right, double cross) {
        if (left < 1e-6 || right < 1e-6) {
            return 0;
        }
        var correlation = Math.clamp(cross / Math.sqrt(left * right), -1, 1);
        var values = PsDecoder.ICC_VALUES;
        var best = 0;
        var error = Double.MAX_VALUE;
        for (var i = 0; i < values.length; i++) {
            var e = Math.abs(values[i] - correlation);
            if (e < error) {
                error = e;
                best = i;
            }
        }
        return best;
    }

    /**
     * Writes {@code ps_data} for the frame last analyzed.
     *
     * @param writer the destination
     * @param header whether to send the parametric stereo header
     */
    void write(MsbBitWriter writer, boolean header) {
        var mode = tenBands ? 0 : 1;
        writer.write(header ? 1 : 0, 1);
        if (header) {
            writer.write(1, 1);
            writer.write(mode, 3);
            writer.write(1, 1);
            writer.write(mode, 3);
            writer.write(0, 1);
        }
        writer.write(0, 1);
        writer.write(envelopes, 2);
        var bins = tenBands ? BINS / 2 : BINS;
        writeParameters(writer, iid, previousIid, bins, SbrHuffmanCodes.IID_TIME, SbrHuffmanCodes.IID_FREQUENCY);
        writeParameters(writer, icc, previousIcc, bins, SbrHuffmanCodes.ICC_TIME, SbrHuffmanCodes.ICC_FREQUENCY);
    }

    /**
     * Writes one parameter of every envelope with the cheaper of time and frequency differences, and keeps the
     * last envelope as the next frame's reference.
     *
     * @param writer the destination
     * @param values the indices of each envelope and band
     * @param previous the previous frame's last indices, updated
     * @param bins the number of bands
     * @param time the codebook of time differences
     * @param frequency the codebook of frequency differences
     */
    private void writeParameters(MsbBitWriter writer, int[][] values, int[] previous, int bins,
                                 SbrHuffmanCodes time, SbrHuffmanCodes frequency) {
        for (var e = 0; e < envelopes; e++) {
            var current = values[e];
            var reference = e == 0 ? previous : values[e - 1];
            var timeBits = 0;
            var frequencyBits = 0;
            for (var b = 0; b < bins; b++) {
                timeBits += time.length(current[b] - reference[b]);
                frequencyBits += frequency.length(b == 0 ? current[0] : current[b] - current[b - 1]);
            }
            var useTime = timeBits < frequencyBits;
            writer.write(useTime ? 1 : 0, 1);
            for (var b = 0; b < bins; b++) {
                if (useTime) {
                    time.write(writer, current[b] - reference[b]);
                } else {
                    frequency.write(writer, b == 0 ? current[0] : current[b] - current[b - 1]);
                }
            }
        }
        System.arraycopy(values[envelopes - 1], 0, previous, 0, bins);
    }
}
