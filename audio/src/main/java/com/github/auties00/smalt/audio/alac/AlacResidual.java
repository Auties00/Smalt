/* Ports Apple Lossless Audio Codec (codec/ag_dec.c). Copyright (c) 2011 Apple Inc.; Apache License 2.0. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the adaptive Golomb residual of one ALAC channel.
 *
 * <p>Each residual is a Golomb code whose parameter follows a running mean of the decoded
 * magnitudes; a prefix of nine or more ones escapes to the value in raw bits. When the mean falls
 * low, a run of zeros follows, coded with its own parameter.
 *
 * @implNote This implementation follows the reference's {@code dyn_decomp}, {@code dyn_get} and
 * {@code dyn_get_32bit}, with their unsigned 32-bit arithmetic on the mean.
 */
final class AlacResidual {
    /**
     * The fixed-point shift of the mean.
     */
    private static final int QB_SHIFT = 9;

    /**
     * One in the mean's fixed point.
     */
    private static final int QB = 1 << QB_SHIFT;

    /**
     * The shift that tests for a low mean.
     */
    private static final int M_MUL_SHIFT = 2;

    /**
     * The shift of the zero-run parameter correction.
     */
    private static final int M_DEN_SHIFT = QB_SHIFT - M_MUL_SHIFT - 1;

    /**
     * The rounding offset of the zero-run parameter correction.
     */
    private static final int M_OFF = 1 << (M_DEN_SHIFT - 2);

    /**
     * The offset of the zero-run parameter.
     */
    private static final int BIT_OFF = 24;

    /**
     * The prefix length that escapes to a raw value.
     */
    private static final int MAX_PREFIX = 9;

    /**
     * The raw bits of an escaped zero-run length.
     */
    private static final int RUN_ESCAPE_BITS = 16;

    /**
     * The largest decoded value that updates the mean normally.
     */
    private static final int MAX_MEAN_VALUE = 0xFFFF;

    /**
     * The initial mean.
     */
    private final int initialMean;

    /**
     * The base adaptation rate.
     */
    private final int rate;

    /**
     * The largest Golomb parameter.
     */
    private final int maxParameter;

    /**
     * The mask of the zero-run parameter.
     */
    private final int runMask;

    /**
     * Creates a residual decoder from the stream's tuning parameters.
     *
     * @param mb the initial mean, {@code mb} of the configuration
     * @param pb the adaptation rate, {@code pb} of the configuration
     * @param kb the largest Golomb parameter, {@code kb} of the configuration, at most 31
     */
    AlacResidual(int mb, int pb, int kb) {
        this.initialMean = mb;
        this.rate = pb;
        this.maxParameter = kb;
        this.runMask = (int) ((1L << kb) - 1);
    }

    /**
     * Decodes {@code count} residual samples.
     *
     * @param bits the reader, positioned at the residual
     * @param out receives the residual
     * @param count the number of samples
     * @param maxBits the width of an escaped value, the channel's prediction size, at most 32
     * @param rateFactor the element's rate factor, scaling the adaptation rate by a quarter
     * @throws MediaException.Malformed if the residual runs past the packet or a zero run past
     * the frame
     */
    void decode(AlacBitReader bits, int[] out, int count, int maxBits, int rateFactor) {
        var pb = rate * rateFactor / 4;
        var mb = initialMean;
        var zmode = 0;
        var c = 0;
        while (c < count) {
            if (bits.exhausted()) {
                throw new MediaException.Malformed("ALAC residual runs past the end of the packet");
            }
            var m = mb >>> QB_SHIFT;
            var k = Math.min(31 - Integer.numberOfLeadingZeros(m + 3), maxParameter);
            m = (1 << k) - 1;
            var n = readValue(bits, m, k, maxBits);
            var decoded = n + zmode;
            var magnitude = (decoded + 1) >>> 1;
            out[c++] = (decoded & 1) != 0 ? -magnitude : magnitude;
            mb = pb * (n + zmode) + mb - ((pb * mb) >>> QB_SHIFT);
            if (Integer.compareUnsigned(n, MAX_MEAN_VALUE) > 0) {
                mb = MAX_MEAN_VALUE;
            }
            zmode = 0;
            if (Integer.compareUnsigned(mb << M_MUL_SHIFT, QB) < 0 && c < count) {
                zmode = 1;
                var runK = Integer.numberOfLeadingZeros(mb) - BIT_OFF + ((mb + M_OFF) >> M_DEN_SHIFT);
                var runM = ((1 << runK) - 1) & runMask;
                var run = readRun(bits, runM, runK);
                if (run > count - c) {
                    throw new MediaException.Malformed("ALAC zero run of " + run + " samples overflows the frame");
                }
                for (var j = 0; j < run; j++) {
                    out[c++] = 0;
                }
                if (run >= 65535) {
                    zmode = 0;
                }
                mb = 0;
            }
        }
    }

    /**
     * Reads one residual value.
     *
     * @param bits the reader
     * @param m the Golomb modulus minus one, {@code 2^k - 1}
     * @param k the Golomb parameter, at least one
     * @param maxBits the width of an escaped value
     * @return the value, unsigned
     */
    private static int readValue(AlacBitReader bits, int m, int k, int maxBits) {
        var window = bits.peek64();
        var prefix = Long.numberOfLeadingZeros(~window);
        if (prefix >= MAX_PREFIX) {
            bits.skip(MAX_PREFIX);
            return bits.read(maxBits);
        }
        if (k == 1) {
            bits.skip(prefix + 1);
            return prefix;
        }
        var v = (int) ((window << (prefix + 1)) >>> (64 - k));
        var result = prefix * m;
        if (v >= 2) {
            bits.skip(prefix + 1 + k);
            return result + v - 1;
        }
        bits.skip(prefix + k);
        return result;
    }

    /**
     * Reads one zero-run length.
     *
     * @param bits the reader
     * @param m the Golomb modulus minus one
     * @param k the Golomb parameter, at least one
     * @return the run length
     */
    private static int readRun(AlacBitReader bits, int m, int k) {
        var window = bits.peek64();
        var prefix = Long.numberOfLeadingZeros(~window);
        if (prefix >= MAX_PREFIX) {
            bits.skip(MAX_PREFIX);
            return bits.read(RUN_ESCAPE_BITS);
        }
        var v = (int) ((window << (prefix + 1)) >>> (64 - k));
        var result = prefix * m + v - 1;
        if (v < 2) {
            bits.skip(prefix + k);
            return result - (v - 1);
        }
        bits.skip(prefix + 1 + k);
        return result;
    }
}
