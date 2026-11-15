package com.github.auties00.smalt.audio.opus.internal.silk;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_DOWN_ORDER_FIR1;
import static com.github.auties00.smalt.audio.opus.internal.silk.ResamplerRom.RESAMPLER_DOWN_ORDER_FIR2;

/**
 * Implements {@link SilkKernels} with the Vector API; the output equals {@link ScalarSilkKernels} bit for bit.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd Simd}.
 * The interpolators vectorize across consecutive outputs when the filter phase is periodic within a batch and fall back
 * to the scalar kernels otherwise.
 */
final class VectorSilkKernels implements SilkKernels {
    /*
     * The interpolators evaluate fractional positions k * increment; for every rate pair the resampler uses, those
     * positions are periodic within a batch: after P outputs the input position advances by exactly A samples and the
     * filter phase repeats. The kernels detect that structure, split the input into A interleaved streams and compute
     * each output phase as a plain FIR over those streams. Results stay bit-identical to the scalar kernels: the
     * upsampler's products of two 16-bit values are exact in 32 bits; the downsampler's silk_SMLAWB terms are
     * computed as (x >> 16) * c + (((x & 0xFFFF) * c) >> 16), which equals the reference's 64-bit (x * c) >> 16
     * exactly; and the wrapping 32-bit sum of the terms is associative.
     */

    /**
     * The 256-bit integer species the kernels compute with.
     */
    private static final VectorSpecies<Integer> I8 = IntVector.SPECIES_256;

    /**
     * The 128-bit short species used to narrow eight results for contiguous stores.
     */
    private static final VectorSpecies<Short> S8 = ShortVector.SPECIES_128;

    /**
     * The number of lanes of {@link #I8}.
     */
    private static final int LANES = 8;

    /**
     * The exact class of the vectors of {@link #I8}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I8);

    /**
     * The exact class of the vectors of {@link #S8}.
     */
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(S8);

    /**
     * One in every lane.
     */
    private static final IntVector ONE = IntVector.broadcast(I8, 1);

    /**
     * The low 16 bits of every lane.
     */
    private static final IntVector LOW_16 = IntVector.broadcast(I8, 0xFFFF);

    /**
     * The smallest 16-bit sample in every lane.
     */
    private static final IntVector SHORT_MIN = IntVector.broadcast(I8, Short.MIN_VALUE);

    /**
     * The largest 16-bit sample in every lane.
     */
    private static final IntVector SHORT_MAX = IntVector.broadcast(I8, Short.MAX_VALUE);

    /**
     * The minimum number of outputs for which the polyphase path is worth its setup.
     */
    private static final int MIN_OUTPUTS = 32;

    /**
     * The largest period, in outputs, the periodicity search considers.
     */
    private static final int MAX_PERIOD = 4;

    /**
     * The scalar kernels used when a batch has no exploitable periodic structure.
     */
    private final ScalarSilkKernels scalar = new ScalarSilkKernels();

    /**
     * Creates the vector kernels.
     */
    private VectorSilkKernels() {
    }

    /**
     * Creates the vector kernels when {@link Simd#WIDE} holds, or the scalar kernels otherwise,
     * since the eight-lane vectors would not be backed by hardware.
     *
     * @return the kernels
     */
    static SilkKernels create() {
        if (!Simd.WIDE) {
            return new ScalarSilkKernels();
        }
        return new VectorSilkKernels();
    }

    /**
     * Creates the vector kernels unconditionally, for equivalence tests.
     *
     * @return the kernels
     */
    static SilkKernels createForTest() {
        return new VectorSilkKernels();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vector class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Returns the filter phase of the upsampling interpolator at a position
     * ({@code silk_SMULWB(index_Q16 & 0xFFFF, 12)}).
     *
     * @param index the position in Q16
     * @return the phase, in {@code [0, 11]}
     */
    private static int phaseOf(long index) {
        return (int) (((index & 0xFFFF) * 12) >> 16);
    }

    /**
     * Returns whether the upsampler positions form a period of {@code P} outputs advancing by the
     * integer part of {@code P * increment} with a constant phase per output class over the whole
     * batch.
     *
     * @param P         the period in outputs
     * @param increment the position increment in Q16
     * @param n         the number of outputs
     * @param fir12     whether the phase is the 12-phase table index (upsampler) rather than
     *                  a whole-sample decimation
     * @return whether the structure holds for every output
     */
    private static boolean periodic(int P, int increment, int n, boolean fir12) {
        var step = (long) P * increment;
        var A = step >> 16;
        var D = step & 0xFFFF;
        if (A == 0) {
            return false;
        }
        for (var phi = 0; phi < P && phi < n; phi++) {
            long first = (long) phi * increment;
            var count = (n - phi + P - 1) / P;
            var last = first + (count - 1) * step;
            if ((first & 0xFFFF) + (count - 1) * D >= 0x10000) {
                return false;
            }
            if (fir12 ? phaseOf(first) != phaseOf(last) : (first & 0xFFFF) != 0 || D != 0) {
                return false;
            }
            if ((last >> 16) != (first >> 16) + A * (count - 1)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int iirFirInterpol(short[] out, int outOff, short[] buf, int max_index_Q16, int index_increment_Q16,
                              int[] scratch) {
        var n = (int) (((long) max_index_Q16 + index_increment_Q16 - 1) / index_increment_Q16);
        if (n >= MIN_OUTPUTS) {
            for (var P = 1; P <= MAX_PERIOD; P++) {
                if (periodic(P, index_increment_Q16, n, true)) {
                    return iirFirPolyphase(out, outOff, buf, index_increment_Q16, n, P, scratch);
                }
            }
        }
        return scalar.iirFirInterpol(out, outOff, buf, max_index_Q16, index_increment_Q16, scratch);
    }

    /**
     * Computes the upsampling interpolation as {@code P} phase-constant FIRs over {@code A}
     * interleaved input streams.
     *
     * @param out       receives the output signal
     * @param outOff    the index of the first output sample
     * @param buf       the upsampled signal with its 8 samples of history
     * @param increment the position increment in Q16
     * @param n         the number of outputs
     * @param P         the period in outputs
     * @param scratch   the work array
     * @return the index after the last output sample written
     */
    private static int iirFirPolyphase(short[] out, int outOff, short[] buf, int increment, int n, int P,
                                       int[] scratch) {
        var A = (int) (((long) P * increment) >> 16);
        var lastPos = (int) ((((long) n - 1) * increment) >> 16);
        var needed = lastPos + 8;
        var streamLength = (needed + A - 1) / A + 2 * LANES;
        for (var r = 0; r < A; r++) {
            var base = r * streamLength;
            var m = 0;
            for (var i = r; i < needed; i += A) {
                scratch[base + m++] = buf[i];
            }
        }
        var tmp = A * streamLength;
        var rows = ScalarSilkKernels.FRAC_FIR_12_ROWS;
        for (var phi = 0; phi < P && phi < n; phi++) {
            long first = (long) phi * increment;
            var p0 = (int) (first >> 16);
            var row = phaseOf(first) << 3;
            var count = (n - phi + P - 1) / P;
            var c0 = IntVector.broadcast(I8, rows[row]);
            var c1 = IntVector.broadcast(I8, rows[row + 1]);
            var c2 = IntVector.broadcast(I8, rows[row + 2]);
            var c3 = IntVector.broadcast(I8, rows[row + 3]);
            var c4 = IntVector.broadcast(I8, rows[row + 4]);
            var c5 = IntVector.broadcast(I8, rows[row + 5]);
            var c6 = IntVector.broadcast(I8, rows[row + 6]);
            var c7 = IntVector.broadcast(I8, rows[row + 7]);
            var s0 = streamOffset(p0, A, streamLength);
            var s1 = streamOffset(p0 + 1, A, streamLength);
            var s2 = streamOffset(p0 + 2, A, streamLength);
            var s3 = streamOffset(p0 + 3, A, streamLength);
            var s4 = streamOffset(p0 + 4, A, streamLength);
            var s5 = streamOffset(p0 + 5, A, streamLength);
            var s6 = streamOffset(p0 + 6, A, streamLength);
            var s7 = streamOffset(p0 + 7, A, streamLength);
            for (var j = 0; j < count; j += LANES) {
                var acc = INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s0 + j)).lanewise(VectorOperators.MUL, c0);
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s1 + j))
                        .lanewise(VectorOperators.MUL, c1));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s2 + j))
                        .lanewise(VectorOperators.MUL, c2));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s3 + j))
                        .lanewise(VectorOperators.MUL, c3));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s4 + j))
                        .lanewise(VectorOperators.MUL, c4));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s5 + j))
                        .lanewise(VectorOperators.MUL, c5));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s6 + j))
                        .lanewise(VectorOperators.MUL, c6));
                acc = acc.lanewise(VectorOperators.ADD, INT_VECTOR.cast(IntVector.fromArray(I8, scratch, s7 + j))
                        .lanewise(VectorOperators.MUL, c7));
                var rounded = acc.lanewise(VectorOperators.ASHR, 14).lanewise(VectorOperators.ADD, ONE)
                        .lanewise(VectorOperators.ASHR, 1);
                var clamped = rounded.lanewise(VectorOperators.MAX, SHORT_MIN).lanewise(VectorOperators.MIN, SHORT_MAX);
                clamped.intoArray(scratch, tmp);
                var lanes = Math.min(LANES, count - j);
                var o = outOff + phi + P * j;
                for (var l = 0; l < lanes; l++) {
                    out[o + P * l] = (short) scratch[tmp + l];
                }
            }
        }
        return outOff + n;
    }

    /**
     * Returns the index in the stream array of the sample at a buffer position.
     *
     * @param position     the position in the interleaved buffer
     * @param A            the number of streams
     * @param streamLength the length reserved for each stream
     * @return the index of the first sample, for output zero, of the stream holding the position
     */
    private static int streamOffset(int position, int A, int streamLength) {
        return (position % A) * streamLength + position / A;
    }

    @Override
    public int downFirInterpol(short[] out, int outOff, int[] buf, short[] coefs, int coefsOff, int FIR_Order,
                               int FIR_Fracs, int max_index_Q16, int index_increment_Q16, int[] scratch) {
        var n = (int) (((long) max_index_Q16 + index_increment_Q16 - 1) / index_increment_Q16);
        if ((FIR_Order == RESAMPLER_DOWN_ORDER_FIR1 || FIR_Order == RESAMPLER_DOWN_ORDER_FIR2) && n >= MIN_OUTPUTS
                && periodic(1, index_increment_Q16, n, false)) {
            return downFirPolyphase(out, outOff, buf, coefs, coefsOff, FIR_Order, index_increment_Q16 >> 16, n,
                    scratch);
        }
        return scalar.downFirInterpol(out, outOff, buf, coefs, coefsOff, FIR_Order, FIR_Fracs, max_index_Q16,
                index_increment_Q16, scratch);
    }

    /**
     * Computes an integer-ratio symmetric decimation FIR over {@code A} interleaved input streams,
     * vectorized across consecutive outputs.
     *
     * @param out       receives the output signal
     * @param outOff    the index of the first output sample
     * @param buf       the filtered signal with its history
     * @param coefs     the array holding the FIR coefficients
     * @param coefsOff  the index of the first FIR coefficient
     * @param order     the FIR order, 24 or 36
     * @param A         the decimation factor
     * @param n         the number of outputs
     * @param scratch   the work array
     * @return the index after the last output sample written
     */
    private static int downFirPolyphase(short[] out, int outOff, int[] buf, short[] coefs, int coefsOff, int order,
                                        int A, int n, int[] scratch) {
        var needed = (n - 1) * A + order;
        var streamLength = (needed + A - 1) / A + 2 * LANES;
        for (var r = 0; r < A; r++) {
            var base = r * streamLength;
            var m = 0;
            for (var i = r; i < needed; i += A) {
                scratch[base + m++] = buf[i];
            }
        }
        var tmp = A * streamLength;
        var half = order / 2;
        var offsets = tmp + LANES;
        for (var i = 0; i < half; i++) {
            scratch[offsets + 2 * i] = streamOffset(i, A, streamLength);
            scratch[offsets + 2 * i + 1] = streamOffset(order - 1 - i, A, streamLength);
            scratch[offsets + 2 * half + i] = coefs[coefsOff + i];
        }
        for (var j = 0; j < n; j += LANES) {
            var acc = IntVector.zero(I8);
            for (var i = 0; i < half; i++) {
                var x = INT_VECTOR.cast(IntVector.fromArray(I8, scratch, scratch[offsets + 2 * i] + j))
                        .lanewise(VectorOperators.ADD, IntVector.fromArray(I8, scratch, scratch[offsets + 2 * i + 1] + j));
                var c = IntVector.broadcast(I8, scratch[offsets + 2 * half + i]);
                var high = x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c);
                var low = x.lanewise(VectorOperators.AND, LOW_16).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16);
                acc = INT_VECTOR.cast(acc).lanewise(VectorOperators.ADD, high).lanewise(VectorOperators.ADD, low);
            }
            var rounded = INT_VECTOR.cast(acc).lanewise(VectorOperators.ASHR, 5).lanewise(VectorOperators.ADD, ONE)
                    .lanewise(VectorOperators.ASHR, 1);
            var clamped = rounded.lanewise(VectorOperators.MAX, SHORT_MIN).lanewise(VectorOperators.MIN, SHORT_MAX);
            if (j + LANES <= n) {
                SHORT_VECTOR.cast(clamped.convertShape(VectorOperators.I2S, S8, 0)).intoArray(out, outOff + j);
            } else {
                clamped.intoArray(scratch, tmp);
                for (var l = 0; l < n - j; l++) {
                    out[outOff + j + l] = (short) scratch[tmp + l];
                }
            }
        }
        return outOff + n;
    }

    @Override
    public void nsqDotProducts(int[] dd, int stride, int lpcOffset, int[] lpcCoef, int arOffset, int[] arCoef,
                               int[] out) {
        for (var k = 0; k < 4; k++) {
            nsqDots(dd, k * stride, lpcOffset, lpcCoef, arOffset, arCoef, out, k);
        }
    }

    /**
     * Computes the prediction and shaping sums of one state for {@link #nsqDotProducts}.
     *
     * <p>Each {@code silk_SMULWB} term is formed as {@code (x >> 16) * c + (((x & 0xFFFF) * c) >> 16)}
     * in 32-bit lanes, which equals the reference's {@code (x * c) >> 16} exactly for a 16-bit
     * {@code c}; the lanes of the wrapping sums are added in any order.
     *
     * @param dd        the state blocks
     * @param base      the start of the state's block
     * @param lpcOffset the offset in the block of the 16 prediction-history values
     * @param lpcCoef   the 16 prediction coefficients
     * @param arOffset  the offset in the block of the 24 shaping-filter states
     * @param arCoef    the 24 shaping coefficients
     * @param out       receives the sums at {@code k} and {@code 4 + k}
     * @param k         the state
     */
    private static void nsqDots(int[] dd, int base, int lpcOffset, int[] lpcCoef, int arOffset, int[] arCoef,
                                int[] out, int k) {
        var low = IntVector.broadcast(I8, 0xFFFF);
        var x = INT_VECTOR.cast(IntVector.fromArray(I8, dd, base + lpcOffset));
        var c = IntVector.fromArray(I8, lpcCoef, 0);
        var lpc = x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c)
                .lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16));
        x = INT_VECTOR.cast(IntVector.fromArray(I8, dd, base + lpcOffset + LANES));
        c = IntVector.fromArray(I8, lpcCoef, LANES);
        lpc = lpc.lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c))
                .lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16));
        x = INT_VECTOR.cast(IntVector.fromArray(I8, dd, base + arOffset));
        c = IntVector.fromArray(I8, arCoef, 0);
        var ar = x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c)
                .lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16));
        x = INT_VECTOR.cast(IntVector.fromArray(I8, dd, base + arOffset + LANES));
        c = IntVector.fromArray(I8, arCoef, LANES);
        ar = ar.lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c))
                .lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16));
        x = INT_VECTOR.cast(IntVector.fromArray(I8, dd, base + arOffset + 2 * LANES));
        c = IntVector.fromArray(I8, arCoef, 2 * LANES);
        ar = ar.lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.ASHR, 16).lanewise(VectorOperators.MUL, c))
                .lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.AND, low).lanewise(VectorOperators.MUL, c)
                        .lanewise(VectorOperators.ASHR, 16));
        out[k] = lpc.reduceLanes(VectorOperators.ADD);
        out[4 + k] = ar.reduceLanes(VectorOperators.ADD);
    }

    @Override
    public void lpcAnalysisFilter(short[] out, int outOff, short[] in, int inOff, short[] B, int BOff, int len, int d) {
        var ix = d;
        for (; ix + LANES <= len; ix += LANES) {
            var base = inOff + ix - 1;
            var acc = IntVector.zero(I8);
            for (var j = 0; j < d; j++) {
                var x = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(S8, in, base - j))
                        .convertShape(VectorOperators.S2I, I8, 0));
                acc = INT_VECTOR.cast(acc).lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.MUL, IntVector.broadcast(I8, B[BOff + j])));
            }
            var current = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(S8, in, base + 1))
                    .convertShape(VectorOperators.S2I, I8, 0));
            var residual = current.lanewise(VectorOperators.LSHL, 12).lanewise(VectorOperators.SUB, acc);
            var rounded = residual.lanewise(VectorOperators.ASHR, 11).lanewise(VectorOperators.ADD, ONE)
                    .lanewise(VectorOperators.ASHR, 1);
            var clamped = rounded.lanewise(VectorOperators.MAX, SHORT_MIN).lanewise(VectorOperators.MIN, SHORT_MAX);
            SHORT_VECTOR.cast(clamped.convertShape(VectorOperators.I2S, S8, 0)).intoArray(out, outOff + ix);
        }
        if (ix < len) {
            scalar.lpcAnalysisFilterTail(out, outOff, in, inOff, B, BOff, ix, len, d);
        }
    }
}
