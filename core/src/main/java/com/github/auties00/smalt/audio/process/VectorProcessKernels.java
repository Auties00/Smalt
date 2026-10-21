package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.internal.simd.Simd;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Creates the Vector API implementations of the audio processing kernels.
 *
 * <p>This is the only class of the package that mentions {@code jdk.incubator.vector} types, and
 * it is loaded only when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is
 * {@code true}, so the package links on JVMs started without the incubator module.
 */
final class VectorProcessKernels {
    /**
     * The float species used by the mixing kernel: the platform's preferred shape.
     */
    private static final VectorSpecies<Float> PREFERRED = FloatVector.SPECIES_PREFERRED;

    /**
     * Prevents instantiation.
     */
    private VectorProcessKernels() {
        throw new AssertionError();
    }

    /**
     * Returns the vector FIR kernel.
     *
     * @return a new kernel
     */
    static FirKernel fir() {
        return new VectorFirKernel();
    }

    /**
     * Returns the vector mixing kernel.
     *
     * @return a new kernel
     */
    static MixKernel mix() {
        return new VectorMixKernel();
    }

    /**
     * Implements {@link FirKernel} with one lane per accumulation chain.
     *
     * <p>On platforms with 256-bit vectors one eight-lane accumulator holds the eight chains of an
     * output; elsewhere two four-lane accumulators hold chains 0 to 3 and 4 to 7. The chains are
     * combined with fixed lane swaps, which reproduces the pairwise sum the interface defines, so
     * the results are bit-identical to {@link ScalarFirKernel}.
     *
     * @implNote This implementation unrolls the tap loop four times and keeps each output loop in
     * its own small method, which lets the JIT inline every Vector API call; a loop of single
     * vector steps, or one large method holding every path, runs about 1.5 times slower. It uses
     * only the per-shape {@code lanewise} and {@code rearrange} operations and reduces each
     * accumulator in the method that built it: a vector returned to a caller that the JIT did not
     * inline was boxed, and in some JVM runs every output allocated. The reduction casts each
     * accumulator through the constant exact class of its species, since a vector leaving a loop
     * reaches the compiler with only its declared abstract type.
     */
    static final class VectorFirKernel implements FirKernel {
        /**
         * The eight-lane species.
         */
        private static final VectorSpecies<Float> WIDE = FloatVector.SPECIES_256;

        /**
         * The four-lane species.
         */
        private static final VectorSpecies<Float> NARROW = FloatVector.SPECIES_128;

        /**
         * The exact class of the vectors of {@link #WIDE}.
         */
        @SuppressWarnings("unchecked")
        private static final Class<FloatVector> WIDE_VECTOR = (Class<FloatVector>) WIDE.vectorType();

        /**
         * The exact class of the vectors of {@link #NARROW}.
         */
        @SuppressWarnings("unchecked")
        private static final Class<FloatVector> NARROW_VECTOR = (Class<FloatVector>) NARROW.vectorType();

        /**
         * Swaps adjacent lanes of an eight-lane vector.
         */
        private static final VectorShuffle<Float> SWAP_1 = VectorShuffle.fromValues(WIDE, 1, 0, 3, 2, 5, 4, 7, 6);

        /**
         * Swaps adjacent lane pairs of an eight-lane vector.
         */
        private static final VectorShuffle<Float> SWAP_2 = VectorShuffle.fromValues(WIDE, 2, 3, 0, 1, 6, 7, 4, 5);

        /**
         * Swaps the halves of an eight-lane vector.
         */
        private static final VectorShuffle<Float> SWAP_4 = VectorShuffle.fromValues(WIDE, 4, 5, 6, 7, 0, 1, 2, 3);

        /**
         * Selects the odd lanes of an eight-lane vector.
         */
        private static final VectorMask<Float> ODD = VectorMask.fromLong(WIDE, 0xAA);

        /**
         * Swaps adjacent lanes of a four-lane vector.
         */
        private static final VectorShuffle<Float> NARROW_SWAP_1 = VectorShuffle.fromValues(NARROW, 1, 0, 3, 2);

        /**
         * Swaps the halves of a four-lane vector.
         */
        private static final VectorShuffle<Float> NARROW_SWAP_2 = VectorShuffle.fromValues(NARROW, 2, 3, 0, 1);

        /**
         * Constructs the kernel.
         */
        VectorFirKernel() {
        }

        @Override
        public float dot(float[] x, int xOffset, float[] h, int hOffset, int taps) {
            return Simd.WIDE ? dotWide(x, xOffset, h, hOffset, taps) : dotNarrow(x, xOffset, h, hOffset, taps);
        }

        /**
         * Computes one output with eight-lane vectors: the eight chains in one accumulator,
         * combined by swapping adjacent lanes, then pairs, then quadruples.
         *
         * @param x       the signal
         * @param xOffset the index of the first signal sample
         * @param h       the coefficients
         * @param hOffset the index of the first coefficient
         * @param taps    the number of products; a multiple of {@value #CHAINS}
         * @return the dot product
         */
        private static float dotWide(float[] x, int xOffset, float[] h, int hOffset, int taps) {
            var acc = FloatVector.zero(WIDE);
            var k = 0;
            for (; k + 4 * CHAINS <= taps; k += 4 * CHAINS) {
                acc = FloatVector.fromArray(WIDE, x, xOffset + k)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(WIDE, h, hOffset + k), acc);
                acc = FloatVector.fromArray(WIDE, x, xOffset + k + 8)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(WIDE, h, hOffset + k + 8), acc);
                acc = FloatVector.fromArray(WIDE, x, xOffset + k + 16)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(WIDE, h, hOffset + k + 16), acc);
                acc = FloatVector.fromArray(WIDE, x, xOffset + k + 24)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(WIDE, h, hOffset + k + 24), acc);
            }
            for (; k < taps; k += CHAINS) {
                acc = FloatVector.fromArray(WIDE, x, xOffset + k)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(WIDE, h, hOffset + k), acc);
            }
            var sum = WIDE_VECTOR.cast(acc);
            var pairs = sum.lanewise(VectorOperators.ADD, sum.rearrange(SWAP_1));
            var quads = pairs.lanewise(VectorOperators.ADD, pairs.rearrange(SWAP_2));
            return quads.lanewise(VectorOperators.ADD, quads.rearrange(SWAP_4)).lane(0);
        }

        /**
         * Computes a dot product with two four-lane accumulators holding chains 0 to 3 and 4 to 7.
         *
         * @param x       the signal
         * @param xOffset the index of the first signal sample
         * @param h       the coefficients
         * @param hOffset the index of the first coefficient
         * @param taps    the number of products; a multiple of {@value #CHAINS}
         * @return the dot product
         */
        private static float dotNarrow(float[] x, int xOffset, float[] h, int hOffset, int taps) {
            var low = FloatVector.zero(NARROW);
            var high = FloatVector.zero(NARROW);
            for (var k = 0; k < taps; k += CHAINS) {
                low = FloatVector.fromArray(NARROW, x, xOffset + k)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(NARROW, h, hOffset + k), low);
                high = FloatVector.fromArray(NARROW, x, xOffset + k + 4)
                        .lanewise(VectorOperators.FMA, FloatVector.fromArray(NARROW, h, hOffset + k + 4), high);
            }
            var lowSum = NARROW_VECTOR.cast(low);
            var highSum = NARROW_VECTOR.cast(high);
            var lowPairs = lowSum.lanewise(VectorOperators.ADD, lowSum.rearrange(NARROW_SWAP_1));
            var highPairs = highSum.lanewise(VectorOperators.ADD, highSum.rearrange(NARROW_SWAP_1));
            return lowPairs.lanewise(VectorOperators.ADD, lowPairs.rearrange(NARROW_SWAP_2)).lane(0)
                    + highPairs.lanewise(VectorOperators.ADD, highPairs.rearrange(NARROW_SWAP_2)).lane(0);
        }

        @Override
        public void run(float[] x, int base, int phase, int step, int remainder, int up, float[] table, int taps,
                        float[] out, int offset, int count) {
            if (Simd.WIDE) {
                runWide(x, base, phase, step, remainder, up, table, taps, out, offset, count);
            } else {
                runNarrow(x, base, phase, step, remainder, up, table, taps, out, offset, count);
            }
        }

        @Override
        public void runPair(float[] x0, float[] x1, int base, int phase, int step, int remainder, int up, float[] table,
                            int taps, float[] out0, float[] out1, int offset, int count) {
            if (Simd.WIDE) {
                runWidePair(x0, x1, base, phase, step, remainder, up, table, taps, out0, out1, offset, count);
            } else {
                runNarrow(x0, base, phase, step, remainder, up, table, taps, out0, offset, count);
                runNarrow(x1, base, phase, step, remainder, up, table, taps, out1, offset, count);
            }
        }

        /**
         * Computes outputs of two channels one position at a time with eight-lane vectors, loading
         * each coefficient vector once for both channels.
         *
         * @param x0        the first channel's signal
         * @param x1        the second channel's signal
         * @param base      the signal index of the first tap of the first output
         * @param phase     the phase of the first output
         * @param step      the whole part of the input advance per output
         * @param remainder the fractional part of the input advance per output
         * @param up        the number of phases
         * @param table     the coefficients
         * @param taps      the number of taps
         * @param out0      receives the first channel's outputs
         * @param out1      receives the second channel's outputs
         * @param offset    the index in both outputs of the first output
         * @param count     the number of outputs per channel
         */
        private static void runWidePair(float[] x0, float[] x1, int base, int phase, int step, int remainder, int up,
                                        float[] table, int taps, float[] out0, float[] out1, int offset, int count) {
            for (var i = 0; i < count; i++) {
                pairChains(x0, x1, base, table, phase * taps, taps, out0, out1, offset + i);
                base += step;
                phase += remainder;
                if (phase >= up) {
                    phase -= up;
                    base++;
                }
            }
        }

        /**
         * Computes one output of each of two channels sharing the coefficient loads.
         *
         * @param x0      the first channel's signal
         * @param x1      the second channel's signal
         * @param xOffset the index of the first signal sample
         * @param h       the coefficients
         * @param hOffset the index of the first coefficient
         * @param taps    the number of taps; a multiple of {@value #CHAINS}
         * @param out0    receives the first channel's output
         * @param out1    receives the second channel's output
         * @param target  the index of both outputs
         */
        private static void pairChains(float[] x0, float[] x1, int xOffset, float[] h, int hOffset, int taps,
                                       float[] out0, float[] out1, int target) {
            var a0 = FloatVector.zero(WIDE);
            var a1 = FloatVector.zero(WIDE);
            for (var k = 0; k < taps; k += CHAINS) {
                var c = FloatVector.fromArray(WIDE, h, hOffset + k);
                a0 = FloatVector.fromArray(WIDE, x0, xOffset + k).lanewise(VectorOperators.FMA, c, a0);
                a1 = FloatVector.fromArray(WIDE, x1, xOffset + k).lanewise(VectorOperators.FMA, c, a1);
            }
            // Even lanes carry the pair sums of a0 and odd lanes those of a1, so one reduction serves
            // both outputs with the same additions as two separate ones.
            var sum0 = WIDE_VECTOR.cast(a0);
            var sum1 = WIDE_VECTOR.cast(a1);
            var pairs = sum0.lanewise(VectorOperators.ADD, sum0.rearrange(SWAP_1))
                    .blend(sum1.lanewise(VectorOperators.ADD, sum1.rearrange(SWAP_1)), ODD);
            var quads = pairs.lanewise(VectorOperators.ADD, pairs.rearrange(SWAP_2));
            var sums = quads.lanewise(VectorOperators.ADD, quads.rearrange(SWAP_4));
            out0[target] = sums.lane(0);
            out1[target] = sums.lane(1);
        }

        /**
         * Computes outputs one at a time with eight-lane vectors.
         *
         * @param x         the signal
         * @param base      the signal index of the first tap of the first output
         * @param phase     the phase of the first output
         * @param step      the whole part of the input advance per output
         * @param remainder the fractional part of the input advance per output
         * @param up        the number of phases
         * @param table     the coefficients
         * @param taps      the number of taps
         * @param out       receives the outputs
         * @param offset    the index in {@code out} of the first output
         * @param count     the number of outputs
         */
        private static void runWide(float[] x, int base, int phase, int step, int remainder, int up, float[] table,
                                      int taps, float[] out, int offset, int count) {
            for (var i = 0; i < count; i++) {
                out[offset + i] = dotWide(x, base, table, phase * taps, taps);
                base += step;
                phase += remainder;
                if (phase >= up) {
                    phase -= up;
                    base++;
                }
            }
        }

        /**
         * Computes outputs one at a time with four-lane vectors.
         *
         * @param x         the signal
         * @param base      the signal index of the first tap of the first output
         * @param phase     the phase of the first output
         * @param step      the whole part of the input advance per output
         * @param remainder the fractional part of the input advance per output
         * @param up        the number of phases
         * @param table     the coefficients
         * @param taps      the number of taps
         * @param out       receives the outputs
         * @param offset    the index in {@code out} of the first output
         * @param count     the number of outputs
         */
        private static void runNarrow(float[] x, int base, int phase, int step, int remainder, int up, float[] table,
                                      int taps, float[] out, int offset, int count) {
            for (var i = 0; i < count; i++) {
                out[offset + i] = dotNarrow(x, base, table, phase * taps, taps);
                base += step;
                phase += remainder;
                if (phase >= up) {
                    phase -= up;
                    base++;
                }
            }
        }
    }

    /**
     * Implements {@link MixKernel} over the preferred float species with a separate
     * multiplication and addition per term, which keeps it bit-identical to
     * {@link ScalarMixKernel}.
     */
    static final class VectorMixKernel implements MixKernel {
        /**
         * Constructs the kernel.
         */
        VectorMixKernel() {
        }

        /**
         * Mixes the whole vectors of two terms, the common stereo-to-mono case.
         *
         * @param a     the first input
         * @param b     the second input
         * @param ga    the weight of the first input
         * @param gb    the weight of the second input
         * @param out   the output
         * @param bound the number of samples, a multiple of the vector length
         * @return {@code bound}
         */
        private static int mixTwo(float[] a, float[] b, float ga, float gb, float[] out, int bound) {
            var wa = FloatVector.broadcast(PREFERRED, ga);
            var wb = FloatVector.broadcast(PREFERRED, gb);
            var i = 0;
            for (; i < bound; i += PREFERRED.length()) {
                FloatVector.fromArray(PREFERRED, a, i).lanewise(VectorOperators.MUL, wa)
                        .lanewise(VectorOperators.ADD, FloatVector.fromArray(PREFERRED, b, i).lanewise(VectorOperators.MUL, wb))
                        .intoArray(out, i);
            }
            return i;
        }

        /**
         * Mixes one vector of samples of every term at {@code i}.
         *
         * @param inputs the input planes
         * @param gains  the weights
         * @param count  the number of terms
         * @param out    the output
         * @param i      the index of the first sample
         */
        private static void mixTerms(float[][] inputs, float[] gains, int count, float[] out, int i) {
            FloatVector.fromArray(PREFERRED, inputs[0], i).lanewise(VectorOperators.MUL, FloatVector.broadcast(PREFERRED, gains[0]))
                    .intoArray(out, i);
            for (var j = 1; j < count; j++) {
                FloatVector.fromArray(PREFERRED, out, i).lanewise(VectorOperators.ADD, FloatVector.fromArray(PREFERRED, inputs[j], i)
                        .lanewise(VectorOperators.MUL, FloatVector.broadcast(PREFERRED, gains[j]))).intoArray(out, i);
            }
        }

        @Override
        public void mix(float[][] inputs, float[] gains, int count, float[] out, int frames) {
            var bound = PREFERRED.loopBound(frames);
            var i = 0;
            if (count == 2) {
                i = mixTwo(inputs[0], inputs[1], gains[0], gains[1], out, bound);
            } else {
                for (; i < bound; i += PREFERRED.length()) {
                    mixTerms(inputs, gains, count, out, i);
                }
            }
            for (; i < frames; i++) {
                var value = gains[0] * inputs[0][i];
                for (var j = 1; j < count; j++) {
                    value = value + gains[j] * inputs[j][i];
                }
                out[i] = value;
            }
        }
    }
}
