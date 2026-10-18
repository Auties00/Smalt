package com.github.auties00.smalt.audio.voice.internal.aec3;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Creates the Vector API kernels of AEC3.
 *
 * <p>This is the only class of the package that mentions {@code jdk.incubator.vector} types; it
 * is loaded only when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is
 * {@code true}, so the package links on JVMs started without the incubator module.
 *
 * @implNote This implementation uses 256-bit vectors when the platform prefers vectors of at
 * least that width and 128-bit vectors otherwise. It calls only the per-shape {@code lanewise}
 * and {@code rearrange} operations with vector operands, casts every loaded vector through the
 * constant exact class of its species so that C2 knows the concrete class while it parses the
 * kernel, reduces lanes with fixed shuffles (never {@code reduceLanes}, whose intrinsic and
 * fallback add the lanes in different orders), keeps no vector across a loop back edge except
 * accumulators that start from zero, and keeps every kernel method small, so that no operation
 * is left on the allocating Java fallback.
 */
final class VectorAec3Kernels {
    /**
     * The eight-lane species.
     */
    private static final VectorSpecies<Float> WIDE = FloatVector.SPECIES_256;

    /**
     * The four-lane species.
     */
    private static final VectorSpecies<Float> NARROW = FloatVector.SPECIES_128;

    /**
     * Whether the platform's preferred vectors are at least 256 bits wide.
     */
    private static final boolean USE_WIDE = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256;

    /**
     * The exact class of the eight-lane vectors.
     */
    @SuppressWarnings("unchecked")
    private static final Class<FloatVector> WIDE_VECTOR = (Class<FloatVector>) WIDE.vectorType();

    /**
     * The exact class of the four-lane vectors.
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
     * Swaps adjacent lanes of a four-lane vector.
     */
    private static final VectorShuffle<Float> NARROW_SWAP_1 = VectorShuffle.fromValues(NARROW, 1, 0, 3, 2);

    /**
     * Swaps the halves of a four-lane vector.
     */
    private static final VectorShuffle<Float> NARROW_SWAP_2 = VectorShuffle.fromValues(NARROW, 2, 3, 0, 1);

    /**
     * The number of four-tap groups of a matched filter.
     */
    private static final int GROUPS = MatchedFilter.FILTER_LENGTH / 4;

    /**
     * The number of blocks of the accumulated-error running sums.
     */
    private static final int BLOCKS = 4;

    /**
     * The number of groups of each block of the accumulated-error running sums.
     */
    private static final int GROUPS_PER_BLOCK = GROUPS / BLOCKS;

    /**
     * Prevents instantiation.
     */
    private VectorAec3Kernels() {
        throw new AssertionError();
    }

    /**
     * Replaces the group sums of each block of {@link #GROUPS_PER_BLOCK} groups with their running
     * sums, in place; the four blocks are independent, so their additions overlap.
     *
     * @param groups the 128 group sums
     */
    private static void blockRunningSums(float[] groups) {
        var s0 = groups[0];
        var s1 = groups[GROUPS_PER_BLOCK];
        var s2 = groups[2 * GROUPS_PER_BLOCK];
        var s3 = groups[3 * GROUPS_PER_BLOCK];
        for (var j = 1; j < GROUPS_PER_BLOCK; j++) {
            s0 += groups[j];
            groups[j] = s0;
            s1 += groups[GROUPS_PER_BLOCK + j];
            groups[GROUPS_PER_BLOCK + j] = s1;
            s2 += groups[2 * GROUPS_PER_BLOCK + j];
            groups[2 * GROUPS_PER_BLOCK + j] = s2;
            s3 += groups[3 * GROUPS_PER_BLOCK + j];
            groups[3 * GROUPS_PER_BLOCK + j] = s3;
        }
    }

    /**
     * Returns the vector matched filter kernel for the platform's vector width.
     *
     * @return a new kernel
     */
    static MatchedFilterKernel matchedFilter() {
        return USE_WIDE ? new WideMatchedFilterKernel() : new NarrowMatchedFilterKernel();
    }

    /**
     * Returns the vector matched filter kernel of the given width, for tests of both widths.
     *
     * @param wide whether the eight-lane kernel is returned
     * @return a new kernel
     */
    static MatchedFilterKernel matchedFilter(boolean wide) {
        return wide ? new WideMatchedFilterKernel() : new NarrowMatchedFilterKernel();
    }

    /**
     * Returns the vector adaptive filter kernel for the platform's vector width.
     *
     * @return a new kernel
     */
    static AdaptiveFilterKernel adaptiveFilter() {
        return USE_WIDE ? new WideAdaptiveFilterKernel() : new NarrowAdaptiveFilterKernel();
    }

    /**
     * Returns the vector adaptive filter kernel of the given width, for tests of both widths.
     *
     * @param wide whether the eight-lane kernel is returned
     * @return a new kernel
     */
    static AdaptiveFilterKernel adaptiveFilter(boolean wide) {
        return wide ? new WideAdaptiveFilterKernel() : new NarrowAdaptiveFilterKernel();
    }

    /**
     * Implements {@link MatchedFilterKernel} with eight-lane vectors.
     *
     * <p>The arithmetic, which both vector widths and the scalar twin used in tests reproduce
     * exactly, splits the 512 taps into 16 chains by their index modulo 16. Each chain
     * accumulates its products {@code h[k] * x[k]} and its energies {@code x[k]^2} with fused
     * multiply-adds starting from zero; chains {@code c} and {@code c + 8} are added, and the
     * eight sums are combined pairwise, {@code ((c0 + c1) + (c2 + c3)) + ((c4 + c5) + (c6 + c7))}.
     * The adaptation is {@code h[k] = fma(x[k], alpha, h[k])}. With the accumulated error, the
     * filter output is instead built from the 128 four-tap group sums
     * {@code (p0 + p1) + (p2 + p3)} of the plain products: running sums within four blocks of 32
     * groups, which are independent and so run in parallel, the offset of each block being the
     * totals of the previous blocks added in order. The squared error of every group's offset
     * running sum is accumulated, and the output is the last block's offset plus its total. The
     * reference instead runs one sequential sum over all 128 groups, whose latency bounds it.
     */
    static final class WideMatchedFilterKernel implements MatchedFilterKernel {
        /**
         * The output and energy of the current capture sample: filter output at index 0,
         * render energy at index 1.
         */
        private final float[] sums = new float[2];

        /**
         * The group sums of the current capture sample, then their block running sums.
         */
        private final float[] groups = new float[GROUPS];

        /**
         * Creates the kernel.
         */
        WideMatchedFilterKernel() {
        }

        @Override
        public void core(int xStartIndex, float x2SumThreshold, float smoothing, float[] x, float[] y, float[] h,
                         int hOffset, boolean computeAccumulatedError, float[] accumulatedError, float[] result) {
            if (computeAccumulatedError) {
                Arrays.fill(accumulatedError, 0.0f);
            }
            var errorSum = 0.f;
            var updated = false;
            for (var i = 0; i < y.length; ++i) {
                var yi = y[i];
                if (computeAccumulatedError) {
                    accumulatedDot(x, xStartIndex, h, hOffset, yi, accumulatedError, groups, sums);
                } else {
                    dot(x, xStartIndex, h, hOffset, sums);
                }
                var s = sums[0];
                var x2Sum = sums[1];
                var e = yi - s;
                var saturation = yi >= 32000.f || yi <= -32000.f;
                errorSum += e * e;
                if (x2Sum > x2SumThreshold && !saturation) {
                    adapt(x, xStartIndex, h, hOffset, smoothing * e / x2Sum);
                    updated = true;
                }
                xStartIndex = xStartIndex > 0 ? xStartIndex - 1 : RenderDelayBuffer.LOW_RATE_SIZE - 1;
            }
            result[0] = errorSum;
            result[1] = updated ? 1 : 0;
        }

        /**
         * Computes the filter output and the render energy of one capture sample.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @param h       the taps
         * @param hOffset the index of the first tap
         * @param out     receives the output at index 0 and the energy at index 1
         */
        private static void dot(float[] x, int xOffset, float[] h, int hOffset, float[] out) {
            var s0 = FloatVector.zero(WIDE);
            var s1 = FloatVector.zero(WIDE);
            var e0 = FloatVector.zero(WIDE);
            var e1 = FloatVector.zero(WIDE);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                var x0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k));
                var x1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k + 8));
                var h0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset + k));
                var h1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset + k + 8));
                s0 = h0.lanewise(VectorOperators.FMA, x0, s0);
                s1 = h1.lanewise(VectorOperators.FMA, x1, s1);
                e0 = x0.lanewise(VectorOperators.FMA, x0, e0);
                e1 = x1.lanewise(VectorOperators.FMA, x1, e1);
            }
            var s = WIDE_VECTOR.cast(s0).lanewise(VectorOperators.ADD, s1);
            var sPairs = s.lanewise(VectorOperators.ADD, s.rearrange(SWAP_1));
            var sQuads = sPairs.lanewise(VectorOperators.ADD, sPairs.rearrange(SWAP_2));
            out[0] = sQuads.lanewise(VectorOperators.ADD, sQuads.rearrange(SWAP_4)).lane(0);
            var e = WIDE_VECTOR.cast(e0).lanewise(VectorOperators.ADD, e1);
            var ePairs = e.lanewise(VectorOperators.ADD, e.rearrange(SWAP_1));
            var eQuads = ePairs.lanewise(VectorOperators.ADD, ePairs.rearrange(SWAP_2));
            out[1] = eQuads.lanewise(VectorOperators.ADD, eQuads.rearrange(SWAP_4)).lane(0);
        }

        /**
         * Computes the filter output from block running sums of four-tap groups, accumulating the
         * squared error after each group, and the render energy, of one capture sample.
         *
         * @param x                the render ring
         * @param xOffset          the index of the first tap's render sample
         * @param h                the taps
         * @param hOffset          the index of the first tap
         * @param yi               the capture sample
         * @param accumulatedError the accumulated error, 128 entries
         * @param groups           scratch for the 128 group sums
         * @param out              receives the output at index 0 and the energy at index 1
         */
        private static void accumulatedDot(float[] x, int xOffset, float[] h, int hOffset, float yi,
                                           float[] accumulatedError, float[] groups, float[] out) {
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                groupSums(x, xOffset + k, h, hOffset + k, groups, k >> 2);
            }
            blockRunningSums(groups);
            out[0] = accumulateErrors(yi, groups, accumulatedError);
            out[1] = energy(x, xOffset);
        }

        /**
         * Computes the four group sums of 16 taps.
         *
         * @param x           the render ring
         * @param xOffset     the index of the first tap's render sample
         * @param h           the taps
         * @param hOffset     the index of the first tap
         * @param groups      receives the group sums
         * @param groupOffset the index of the first group sum
         */
        private static void groupSums(float[] x, int xOffset, float[] h, int hOffset, float[] groups, int groupOffset) {
            var x0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset));
            var x1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + 8));
            var p0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset)).lanewise(VectorOperators.MUL, x0);
            var p1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset + 8)).lanewise(VectorOperators.MUL, x1);
            var q0 = p0.lanewise(VectorOperators.ADD, p0.rearrange(SWAP_1));
            var q1 = p1.lanewise(VectorOperators.ADD, p1.rearrange(SWAP_1));
            var g0 = q0.lanewise(VectorOperators.ADD, q0.rearrange(SWAP_2));
            var g1 = q1.lanewise(VectorOperators.ADD, q1.rearrange(SWAP_2));
            groups[groupOffset] = g0.lane(0);
            groups[groupOffset + 1] = g0.lane(4);
            groups[groupOffset + 2] = g1.lane(0);
            groups[groupOffset + 3] = g1.lane(4);
        }

        /**
         * Offsets the block running sums, accumulates the squared error of each against the
         * capture sample, and returns the filter output.
         *
         * @param yi               the capture sample
         * @param sums             the block running sums of the 128 groups
         * @param accumulatedError the accumulated error, 128 entries
         * @return the last block's offset plus its total
         */
        private static float accumulateErrors(float yi, float[] sums, float[] accumulatedError) {
            var o1 = sums[GROUPS_PER_BLOCK - 1];
            var o2 = o1 + sums[2 * GROUPS_PER_BLOCK - 1];
            var o3 = o2 + sums[3 * GROUPS_PER_BLOCK - 1];
            var y = FloatVector.broadcast(WIDE, yi);
            for (var b = 0; b < BLOCKS; b++) {
                var o = FloatVector.broadcast(WIDE, b == 0 ? 0.f : b == 1 ? o1 : b == 2 ? o2 : o3);
                for (var j = b * GROUPS_PER_BLOCK; j < (b + 1) * GROUPS_PER_BLOCK; j += 8) {
                    var d = WIDE_VECTOR.cast(y).lanewise(VectorOperators.SUB,
                            WIDE_VECTOR.cast(o).lanewise(VectorOperators.ADD, WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, sums, j))));
                    WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, accumulatedError, j))
                            .lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, d))
                            .intoArray(accumulatedError, j);
                }
            }
            return o3 + sums[GROUPS - 1];
        }

        /**
         * Computes the render energy of one capture sample with the chains of
         * {@link #dot(float[], int, float[], int, float[])}.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @return the energy
         */
        private static float energy(float[] x, int xOffset) {
            var e0 = FloatVector.zero(WIDE);
            var e1 = FloatVector.zero(WIDE);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                var x0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k));
                var x1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k + 8));
                e0 = x0.lanewise(VectorOperators.FMA, x0, e0);
                e1 = x1.lanewise(VectorOperators.FMA, x1, e1);
            }
            var e = WIDE_VECTOR.cast(e0).lanewise(VectorOperators.ADD, e1);
            var ePairs = e.lanewise(VectorOperators.ADD, e.rearrange(SWAP_1));
            var eQuads = ePairs.lanewise(VectorOperators.ADD, ePairs.rearrange(SWAP_2));
            return eQuads.lanewise(VectorOperators.ADD, eQuads.rearrange(SWAP_4)).lane(0);
        }

        /**
         * Adds a scaled render window to the taps.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @param h       the taps
         * @param hOffset the index of the first tap
         * @param alpha   the NLMS step
         */
        private static void adapt(float[] x, int xOffset, float[] h, int hOffset, float alpha) {
            var a = FloatVector.broadcast(WIDE, alpha);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                var x0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k));
                var x1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, x, xOffset + k + 8));
                var h0 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset + k));
                var h1 = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h, hOffset + k + 8));
                x0.lanewise(VectorOperators.FMA, a, h0).intoArray(h, hOffset + k);
                x1.lanewise(VectorOperators.FMA, a, h1).intoArray(h, hOffset + k + 8);
            }
        }
    }

    /**
     * Implements {@link MatchedFilterKernel} with four-lane vectors, with exactly the arithmetic
     * of {@link WideMatchedFilterKernel}: four accumulators hold chains 0-3, 4-7, 8-11 and 12-15.
     */
    static final class NarrowMatchedFilterKernel implements MatchedFilterKernel {
        /**
         * The output and energy of the current capture sample.
         */
        private final float[] sums = new float[2];

        /**
         * The group sums of the current capture sample, then their block running sums.
         */
        private final float[] groups = new float[GROUPS];

        /**
         * Creates the kernel.
         */
        NarrowMatchedFilterKernel() {
        }

        @Override
        public void core(int xStartIndex, float x2SumThreshold, float smoothing, float[] x, float[] y, float[] h,
                         int hOffset, boolean computeAccumulatedError, float[] accumulatedError, float[] result) {
            if (computeAccumulatedError) {
                Arrays.fill(accumulatedError, 0.0f);
            }
            var errorSum = 0.f;
            var updated = false;
            for (var i = 0; i < y.length; ++i) {
                var yi = y[i];
                if (computeAccumulatedError) {
                    accumulatedDot(x, xStartIndex, h, hOffset, yi, accumulatedError, groups, sums);
                } else {
                    dot(x, xStartIndex, h, hOffset, sums);
                }
                var s = sums[0];
                var x2Sum = sums[1];
                var e = yi - s;
                var saturation = yi >= 32000.f || yi <= -32000.f;
                errorSum += e * e;
                if (x2Sum > x2SumThreshold && !saturation) {
                    adapt(x, xStartIndex, h, hOffset, smoothing * e / x2Sum);
                    updated = true;
                }
                xStartIndex = xStartIndex > 0 ? xStartIndex - 1 : RenderDelayBuffer.LOW_RATE_SIZE - 1;
            }
            result[0] = errorSum;
            result[1] = updated ? 1 : 0;
        }

        /**
         * Computes the filter output and the render energy of one capture sample.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @param h       the taps
         * @param hOffset the index of the first tap
         * @param out     receives the output at index 0 and the energy at index 1
         */
        private static void dot(float[] x, int xOffset, float[] h, int hOffset, float[] out) {
            var s0 = FloatVector.zero(NARROW);
            var s1 = FloatVector.zero(NARROW);
            var s2 = FloatVector.zero(NARROW);
            var s3 = FloatVector.zero(NARROW);
            var e0 = FloatVector.zero(NARROW);
            var e1 = FloatVector.zero(NARROW);
            var e2 = FloatVector.zero(NARROW);
            var e3 = FloatVector.zero(NARROW);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                var x0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k));
                var x1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 4));
                var x2 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 8));
                var x3 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 12));
                s0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k)).lanewise(VectorOperators.FMA, x0, s0);
                s1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k + 4)).lanewise(VectorOperators.FMA, x1, s1);
                s2 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k + 8)).lanewise(VectorOperators.FMA, x2, s2);
                s3 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k + 12)).lanewise(VectorOperators.FMA, x3, s3);
                e0 = x0.lanewise(VectorOperators.FMA, x0, e0);
                e1 = x1.lanewise(VectorOperators.FMA, x1, e1);
                e2 = x2.lanewise(VectorOperators.FMA, x2, e2);
                e3 = x3.lanewise(VectorOperators.FMA, x3, e3);
            }
            var sLow = NARROW_VECTOR.cast(s0).lanewise(VectorOperators.ADD, s2);
            var sHigh = NARROW_VECTOR.cast(s1).lanewise(VectorOperators.ADD, s3);
            var sLowPairs = sLow.lanewise(VectorOperators.ADD, sLow.rearrange(NARROW_SWAP_1));
            var sHighPairs = sHigh.lanewise(VectorOperators.ADD, sHigh.rearrange(NARROW_SWAP_1));
            out[0] = sLowPairs.lanewise(VectorOperators.ADD, sLowPairs.rearrange(NARROW_SWAP_2)).lane(0)
                    + sHighPairs.lanewise(VectorOperators.ADD, sHighPairs.rearrange(NARROW_SWAP_2)).lane(0);
            var eLow = NARROW_VECTOR.cast(e0).lanewise(VectorOperators.ADD, e2);
            var eHigh = NARROW_VECTOR.cast(e1).lanewise(VectorOperators.ADD, e3);
            var eLowPairs = eLow.lanewise(VectorOperators.ADD, eLow.rearrange(NARROW_SWAP_1));
            var eHighPairs = eHigh.lanewise(VectorOperators.ADD, eHigh.rearrange(NARROW_SWAP_1));
            out[1] = eLowPairs.lanewise(VectorOperators.ADD, eLowPairs.rearrange(NARROW_SWAP_2)).lane(0)
                    + eHighPairs.lanewise(VectorOperators.ADD, eHighPairs.rearrange(NARROW_SWAP_2)).lane(0);
        }

        /**
         * Computes the filter output from block running sums of four-tap groups, accumulating the
         * squared error after each group, and the render energy, of one capture sample.
         *
         * @param x                the render ring
         * @param xOffset          the index of the first tap's render sample
         * @param h                the taps
         * @param hOffset          the index of the first tap
         * @param yi               the capture sample
         * @param accumulatedError the accumulated error, 128 entries
         * @param groups           scratch for the 128 group sums
         * @param out              receives the output at index 0 and the energy at index 1
         */
        private static void accumulatedDot(float[] x, int xOffset, float[] h, int hOffset, float yi,
                                           float[] accumulatedError, float[] groups, float[] out) {
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 8) {
                groupSums(x, xOffset + k, h, hOffset + k, groups, k >> 2);
            }
            blockRunningSums(groups);
            out[0] = accumulateErrors(yi, groups, accumulatedError);
            out[1] = energy(x, xOffset);
        }

        /**
         * Computes the two group sums of 8 taps.
         *
         * @param x           the render ring
         * @param xOffset     the index of the first tap's render sample
         * @param h           the taps
         * @param hOffset     the index of the first tap
         * @param groups      receives the group sums
         * @param groupOffset the index of the first group sum
         */
        private static void groupSums(float[] x, int xOffset, float[] h, int hOffset, float[] groups, int groupOffset) {
            var p0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset))
                    .lanewise(VectorOperators.MUL, NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset)));
            var p1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + 4))
                    .lanewise(VectorOperators.MUL, NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + 4)));
            var q0 = p0.lanewise(VectorOperators.ADD, p0.rearrange(NARROW_SWAP_1));
            var q1 = p1.lanewise(VectorOperators.ADD, p1.rearrange(NARROW_SWAP_1));
            groups[groupOffset] = q0.lanewise(VectorOperators.ADD, q0.rearrange(NARROW_SWAP_2)).lane(0);
            groups[groupOffset + 1] = q1.lanewise(VectorOperators.ADD, q1.rearrange(NARROW_SWAP_2)).lane(0);
        }

        /**
         * Offsets the block running sums, accumulates the squared error of each against the
         * capture sample, and returns the filter output.
         *
         * @param yi               the capture sample
         * @param sums             the block running sums of the 128 groups
         * @param accumulatedError the accumulated error, 128 entries
         * @return the last block's offset plus its total
         */
        private static float accumulateErrors(float yi, float[] sums, float[] accumulatedError) {
            var o1 = sums[GROUPS_PER_BLOCK - 1];
            var o2 = o1 + sums[2 * GROUPS_PER_BLOCK - 1];
            var o3 = o2 + sums[3 * GROUPS_PER_BLOCK - 1];
            var y = FloatVector.broadcast(NARROW, yi);
            for (var b = 0; b < BLOCKS; b++) {
                var o = FloatVector.broadcast(NARROW, b == 0 ? 0.f : b == 1 ? o1 : b == 2 ? o2 : o3);
                for (var j = b * GROUPS_PER_BLOCK; j < (b + 1) * GROUPS_PER_BLOCK; j += 4) {
                    var d = NARROW_VECTOR.cast(y).lanewise(VectorOperators.SUB,
                            NARROW_VECTOR.cast(o).lanewise(VectorOperators.ADD, NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, sums, j))));
                    NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, accumulatedError, j))
                            .lanewise(VectorOperators.ADD, d.lanewise(VectorOperators.MUL, d))
                            .intoArray(accumulatedError, j);
                }
            }
            return o3 + sums[GROUPS - 1];
        }

        /**
         * Computes the render energy of one capture sample with the chains of
         * {@link #dot(float[], int, float[], int, float[])}.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @return the energy
         */
        private static float energy(float[] x, int xOffset) {
            var e0 = FloatVector.zero(NARROW);
            var e1 = FloatVector.zero(NARROW);
            var e2 = FloatVector.zero(NARROW);
            var e3 = FloatVector.zero(NARROW);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 16) {
                var x0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k));
                var x1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 4));
                var x2 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 8));
                var x3 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 12));
                e0 = x0.lanewise(VectorOperators.FMA, x0, e0);
                e1 = x1.lanewise(VectorOperators.FMA, x1, e1);
                e2 = x2.lanewise(VectorOperators.FMA, x2, e2);
                e3 = x3.lanewise(VectorOperators.FMA, x3, e3);
            }
            var eLow = NARROW_VECTOR.cast(e0).lanewise(VectorOperators.ADD, e2);
            var eHigh = NARROW_VECTOR.cast(e1).lanewise(VectorOperators.ADD, e3);
            var eLowPairs = eLow.lanewise(VectorOperators.ADD, eLow.rearrange(NARROW_SWAP_1));
            var eHighPairs = eHigh.lanewise(VectorOperators.ADD, eHigh.rearrange(NARROW_SWAP_1));
            return eLowPairs.lanewise(VectorOperators.ADD, eLowPairs.rearrange(NARROW_SWAP_2)).lane(0)
                    + eHighPairs.lanewise(VectorOperators.ADD, eHighPairs.rearrange(NARROW_SWAP_2)).lane(0);
        }

        /**
         * Adds a scaled render window to the taps.
         *
         * @param x       the render ring
         * @param xOffset the index of the first tap's render sample
         * @param h       the taps
         * @param hOffset the index of the first tap
         * @param alpha   the NLMS step
         */
        private static void adapt(float[] x, int xOffset, float[] h, int hOffset, float alpha) {
            var a = FloatVector.broadcast(NARROW, alpha);
            for (var k = 0; k < MatchedFilter.FILTER_LENGTH; k += 8) {
                var x0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k));
                var x1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, x, xOffset + k + 4));
                var h0 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k));
                var h1 = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h, hOffset + k + 4));
                x0.lanewise(VectorOperators.FMA, a, h0).intoArray(h, hOffset + k);
                x1.lanewise(VectorOperators.FMA, a, h1).intoArray(h, hOffset + k + 4);
            }
        }
    }

    /**
     * Implements {@link AdaptiveFilterKernel} with eight-lane vectors over bins 0 to 63 and the
     * scalar arithmetic for bin 64; every bin is computed with the operations of
     * {@link ScalarAdaptiveFilterKernel}, so the results are identical.
     */
    static final class WideAdaptiveFilterKernel implements AdaptiveFilterKernel {
        /**
         * Creates the kernel.
         */
        WideAdaptiveFilterKernel() {
        }

        @Override
        public void apply(float[] xRe, float[] xIm, int position, int numPartitions, float[] hRe, float[] hIm, float[] sRe,
                          float[] sIm) {
            Arrays.fill(sRe, 0.f);
            Arrays.fill(sIm, 0.f);
            var index = position;
            for (var p = 0; p < numPartitions; ++p) {
                applyPartition(xRe, xIm, index * FFT_LENGTH_BY_2_PLUS_1, hRe, hIm, p * FFT_LENGTH_BY_2_PLUS_1, sRe, sIm);
                index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
            }
        }

        /**
         * Adds the product of one render spectrum and one partition to the output.
         *
         * @param xRe the real parts of the render ring
         * @param xIm the imaginary parts of the render ring
         * @param xo  the start of the render spectrum
         * @param hRe the real parts of the partitions
         * @param hIm the imaginary parts of the partitions
         * @param ho  the start of the partition
         * @param sRe the real parts of the output
         * @param sIm the imaginary parts of the output
         */
        private static void applyPartition(float[] xRe, float[] xIm, int xo, float[] hRe, float[] hIm, int ho, float[] sRe,
                                           float[] sIm) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 8) {
                var xr = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, xRe, xo + k));
                var xi = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, xIm, xo + k));
                var hr = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hRe, ho + k));
                var hi = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hIm, ho + k));
                WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, sRe, k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, hr).lanewise(VectorOperators.SUB, xi.lanewise(VectorOperators.MUL, hi)))
                        .intoArray(sRe, k);
                WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, sIm, k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, hi).lanewise(VectorOperators.ADD, xi.lanewise(VectorOperators.MUL, hr)))
                        .intoArray(sIm, k);
            }
            var k = FFT_LENGTH_BY_2;
            sRe[k] += xRe[xo + k] * hRe[ho + k] - xIm[xo + k] * hIm[ho + k];
            sIm[k] += xRe[xo + k] * hIm[ho + k] + xIm[xo + k] * hRe[ho + k];
        }

        @Override
        public void adapt(float[] xRe, float[] xIm, int position, int numPartitions, float[] gRe, float[] gIm, float[] hRe,
                          float[] hIm) {
            var index = position;
            for (var p = 0; p < numPartitions; ++p) {
                adaptPartition(xRe, xIm, index * FFT_LENGTH_BY_2_PLUS_1, gRe, gIm, hRe, hIm, p * FFT_LENGTH_BY_2_PLUS_1);
                index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
            }
        }

        /**
         * Adds the update of one partition.
         *
         * @param xRe the real parts of the render ring
         * @param xIm the imaginary parts of the render ring
         * @param xo  the start of the render spectrum
         * @param gRe the real parts of the gain
         * @param gIm the imaginary parts of the gain
         * @param hRe the real parts of the partitions
         * @param hIm the imaginary parts of the partitions
         * @param ho  the start of the partition
         */
        private static void adaptPartition(float[] xRe, float[] xIm, int xo, float[] gRe, float[] gIm, float[] hRe,
                                           float[] hIm, int ho) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 8) {
                var xr = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, xRe, xo + k));
                var xi = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, xIm, xo + k));
                var gr = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, gRe, k));
                var gi = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, gIm, k));
                WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hRe, ho + k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, gr).lanewise(VectorOperators.ADD, xi.lanewise(VectorOperators.MUL, gi)))
                        .intoArray(hRe, ho + k);
                WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hIm, ho + k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, gi).lanewise(VectorOperators.SUB, xi.lanewise(VectorOperators.MUL, gr)))
                        .intoArray(hIm, ho + k);
            }
            var k = FFT_LENGTH_BY_2;
            hRe[ho + k] += xRe[xo + k] * gRe[k] + xIm[xo + k] * gIm[k];
            hIm[ho + k] += xRe[xo + k] * gIm[k] - xIm[xo + k] * gRe[k];
        }

        @Override
        public void frequencyResponse(int numPartitions, float[] hRe, float[] hIm, float[] h2) {
            for (var p = 0; p < numPartitions; ++p) {
                var o = p * FFT_LENGTH_BY_2_PLUS_1;
                for (var j = 0; j < FFT_LENGTH_BY_2; j += 8) {
                    var zero = WIDE_VECTOR.cast(FloatVector.zero(WIDE));
                    var re = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hRe, o + j));
                    var im = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, hIm, o + j));
                    var tmp = re.lanewise(VectorOperators.MUL, re).lanewise(VectorOperators.ADD, im.lanewise(VectorOperators.MUL, im));
                    zero.blend(tmp, zero.compare(VectorOperators.LT, tmp)).intoArray(h2, o + j);
                }
                var j = FFT_LENGTH_BY_2;
                var tmp = hRe[o + j] * hRe[o + j] + hIm[o + j] * hIm[o + j];
                h2[o + j] = 0.f < tmp ? tmp : 0.f;
            }
        }

        @Override
        public void erl(int numPartitions, float[] h2, float[] erl) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 8) {
                var sum = FloatVector.zero(WIDE);
                for (var p = 0; p < numPartitions; ++p) {
                    sum = WIDE_VECTOR.cast(FloatVector.fromArray(WIDE, h2, p * FFT_LENGTH_BY_2_PLUS_1 + k)).lanewise(VectorOperators.ADD, sum);
                }
                WIDE_VECTOR.cast(sum).intoArray(erl, k);
            }
            var last = 0.f;
            for (var p = 0; p < numPartitions; ++p) {
                last = h2[p * FFT_LENGTH_BY_2_PLUS_1 + FFT_LENGTH_BY_2] + last;
            }
            erl[FFT_LENGTH_BY_2] = last;
        }
    }

    /**
     * Implements {@link AdaptiveFilterKernel} with four-lane vectors; bit-identical to
     * {@link ScalarAdaptiveFilterKernel}.
     */
    static final class NarrowAdaptiveFilterKernel implements AdaptiveFilterKernel {
        /**
         * Creates the kernel.
         */
        NarrowAdaptiveFilterKernel() {
        }

        @Override
        public void apply(float[] xRe, float[] xIm, int position, int numPartitions, float[] hRe, float[] hIm, float[] sRe,
                          float[] sIm) {
            Arrays.fill(sRe, 0.f);
            Arrays.fill(sIm, 0.f);
            var index = position;
            for (var p = 0; p < numPartitions; ++p) {
                applyPartition(xRe, xIm, index * FFT_LENGTH_BY_2_PLUS_1, hRe, hIm, p * FFT_LENGTH_BY_2_PLUS_1, sRe, sIm);
                index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
            }
        }

        /**
         * Adds the product of one render spectrum and one partition to the output.
         *
         * @param xRe the real parts of the render ring
         * @param xIm the imaginary parts of the render ring
         * @param xo  the start of the render spectrum
         * @param hRe the real parts of the partitions
         * @param hIm the imaginary parts of the partitions
         * @param ho  the start of the partition
         * @param sRe the real parts of the output
         * @param sIm the imaginary parts of the output
         */
        private static void applyPartition(float[] xRe, float[] xIm, int xo, float[] hRe, float[] hIm, int ho, float[] sRe,
                                           float[] sIm) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 4) {
                var xr = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, xRe, xo + k));
                var xi = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, xIm, xo + k));
                var hr = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hRe, ho + k));
                var hi = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hIm, ho + k));
                NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, sRe, k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, hr).lanewise(VectorOperators.SUB, xi.lanewise(VectorOperators.MUL, hi)))
                        .intoArray(sRe, k);
                NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, sIm, k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, hi).lanewise(VectorOperators.ADD, xi.lanewise(VectorOperators.MUL, hr)))
                        .intoArray(sIm, k);
            }
            var k = FFT_LENGTH_BY_2;
            sRe[k] += xRe[xo + k] * hRe[ho + k] - xIm[xo + k] * hIm[ho + k];
            sIm[k] += xRe[xo + k] * hIm[ho + k] + xIm[xo + k] * hRe[ho + k];
        }

        @Override
        public void adapt(float[] xRe, float[] xIm, int position, int numPartitions, float[] gRe, float[] gIm, float[] hRe,
                          float[] hIm) {
            var index = position;
            for (var p = 0; p < numPartitions; ++p) {
                adaptPartition(xRe, xIm, index * FFT_LENGTH_BY_2_PLUS_1, gRe, gIm, hRe, hIm, p * FFT_LENGTH_BY_2_PLUS_1);
                index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
            }
        }

        /**
         * Adds the update of one partition.
         *
         * @param xRe the real parts of the render ring
         * @param xIm the imaginary parts of the render ring
         * @param xo  the start of the render spectrum
         * @param gRe the real parts of the gain
         * @param gIm the imaginary parts of the gain
         * @param hRe the real parts of the partitions
         * @param hIm the imaginary parts of the partitions
         * @param ho  the start of the partition
         */
        private static void adaptPartition(float[] xRe, float[] xIm, int xo, float[] gRe, float[] gIm, float[] hRe,
                                           float[] hIm, int ho) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 4) {
                var xr = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, xRe, xo + k));
                var xi = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, xIm, xo + k));
                var gr = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, gRe, k));
                var gi = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, gIm, k));
                NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hRe, ho + k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, gr).lanewise(VectorOperators.ADD, xi.lanewise(VectorOperators.MUL, gi)))
                        .intoArray(hRe, ho + k);
                NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hIm, ho + k)).lanewise(VectorOperators.ADD,
                                xr.lanewise(VectorOperators.MUL, gi).lanewise(VectorOperators.SUB, xi.lanewise(VectorOperators.MUL, gr)))
                        .intoArray(hIm, ho + k);
            }
            var k = FFT_LENGTH_BY_2;
            hRe[ho + k] += xRe[xo + k] * gRe[k] + xIm[xo + k] * gIm[k];
            hIm[ho + k] += xRe[xo + k] * gIm[k] - xIm[xo + k] * gRe[k];
        }

        @Override
        public void frequencyResponse(int numPartitions, float[] hRe, float[] hIm, float[] h2) {
            for (var p = 0; p < numPartitions; ++p) {
                var o = p * FFT_LENGTH_BY_2_PLUS_1;
                for (var j = 0; j < FFT_LENGTH_BY_2; j += 4) {
                    var zero = NARROW_VECTOR.cast(FloatVector.zero(NARROW));
                    var re = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hRe, o + j));
                    var im = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, hIm, o + j));
                    var tmp = re.lanewise(VectorOperators.MUL, re).lanewise(VectorOperators.ADD, im.lanewise(VectorOperators.MUL, im));
                    zero.blend(tmp, zero.compare(VectorOperators.LT, tmp)).intoArray(h2, o + j);
                }
                var j = FFT_LENGTH_BY_2;
                var tmp = hRe[o + j] * hRe[o + j] + hIm[o + j] * hIm[o + j];
                h2[o + j] = 0.f < tmp ? tmp : 0.f;
            }
        }

        @Override
        public void erl(int numPartitions, float[] h2, float[] erl) {
            for (var k = 0; k < FFT_LENGTH_BY_2; k += 4) {
                var sum = FloatVector.zero(NARROW);
                for (var p = 0; p < numPartitions; ++p) {
                    sum = NARROW_VECTOR.cast(FloatVector.fromArray(NARROW, h2, p * FFT_LENGTH_BY_2_PLUS_1 + k)).lanewise(VectorOperators.ADD, sum);
                }
                NARROW_VECTOR.cast(sum).intoArray(erl, k);
            }
            var last = 0.f;
            for (var p = 0; p < numPartitions; ++p) {
                last = h2[p * FFT_LENGTH_BY_2_PLUS_1 + FFT_LENGTH_BY_2] + last;
            }
            erl[FFT_LENGTH_BY_2] = last;
        }
    }
}
