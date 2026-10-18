package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the CDEF kernels with the Vector API, producing output identical to
 * {@link ScalarCdefDsp}.
 *
 * <p>The filter processes a row of the block per iteration, 8 or 4 samples in 32-bit lanes, reading
 * every tap of the row from the padded block copy that {@link ScalarCdefDsp#padding} builds. The
 * combined primary and secondary filter runs in two passes, the secondary taps first, keeping each
 * kernel small enough for the JIT compiler. 32-bit
 * lanes keep the difference between a tap and the sample exact for the missing-sample marker,
 * whose constrained contribution is then zero, and the clamping range ignores the marker as the
 * scalar filter does: the lower bound takes the minimum of the taps read as unsigned 16-bit values,
 * the upper bound the maximum of the signed values. The padded copy of a block whose four edges
 * have neighbors is built with vector row moves, other blocks use {@link ScalarCdefDsp#padding}.
 * The direction search accumulates the partial sums of every direction class in registers: each
 * row of the block, its reverse and its pair sums are rotated by the class's first bin of the row
 * and split by lane masks into the bins below 8 and from 8 on; the costs are then evaluated by
 * {@link ScalarCdefDsp}. A vector carried around a loop, such as an accumulator, is cast to its
 * exact class wherever it receives a call, since the compiler types it with its abstract class
 * there.
 *
 * <p>Every Vector API operation goes through the per-shape methods, no method takes or returns a
 * vector, and every loaded or converted vector, and every vector stored or shifted by a scalar, is
 * first cast through the {@code static final} class of its species; see {@link VectorMcDsp}.
 *
 * <p>Generated from {@code tools/codegen/av1}; edit the template.
 *
 * @implNote This implementation follows the structure of dav1d's {@code cdef_filter_8x8} and
 *           {@code cdef_filter_4x8} x86 kernels ({@code src/x86/cdef16_avx2.asm}), which also
 *           filter a block row by row from a padded copy.
 */
public final class VectorCdefDsp extends CdefDsp {
    /**
     * The 8-lane int species of 8-wide blocks.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of 4-wide blocks.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane short species of 8-wide rows.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 4-lane short species of 4-wide rows.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #I128} vectors.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #S64} vectors.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * The offset in the scratch buffer of the per-row partial results of the combined filter: the
     * secondary contributions, the lower and the upper bound of the secondary taps, 8 entries each
     * per row.
     */
    private static final int PARTIAL = 144;

    /**
     * The masks selecting, for a rotation by {@code o} lanes, the lanes from {@code o} on (entries
     * {@code 8 * o} to {@code 8 * o + 7}) and the lanes below {@code o} (entries {@code 64 + 8 * o}
     * on): -1 for a selected lane, 0 otherwise.
     */
    private static final int[] LOW_LANES = lowLanes();

    /**
     * The shuffles rotating 8 lanes up by 0 to 7 lanes: lane {@code j} of rotation {@code o} takes
     * lane {@code (j - o) & 7}.
     */
    private static final VectorShuffle<Integer>[] ROTATE = rotations(false);

    /**
     * The shuffles reversing 8 lanes and rotating them up by 0 to 7 lanes: lane {@code j} of
     * rotation {@code o} takes lane {@code 7 - ((j - o) & 7)}.
     */
    private static final VectorShuffle<Integer>[] REVERSE_ROTATE = rotations(true);

    /**
     * Gathers the even lanes into the first four lanes.
     */
    private static final VectorShuffle<Integer> EVENS = VectorShuffle.fromValues(I256, 0, 2, 4, 6, 0, 2, 4, 6);

    /**
     * Gathers the odd lanes into the first four lanes.
     */
    private static final VectorShuffle<Integer> ODDS = VectorShuffle.fromValues(I256, 1, 3, 5, 7, 1, 3, 5, 7);

    /**
     * Reverses the first four lanes and keeps the others.
     */
    private static final VectorShuffle<Integer> REVERSE_PAIRS = VectorShuffle.fromValues(I256, 3, 2, 1, 0, 4, 5, 6,
            7);

    /**
     * Selects the last four lanes.
     */
    private static final VectorMask<Integer> UPPER_HALF = VectorMask.fromLong(I256, 0xF0L);

    /**
     * Builds {@link #LOW_LANES}.
     *
     * @return the masks
     */
    private static int[] lowLanes() {
        var masks = new int[128];
        for (var o = 0; o < 8; o++) {
            for (var j = 0; j < 8; j++) {
                masks[8 * o + j] = j >= o ? -1 : 0;
                masks[64 + 8 * o + j] = j >= o ? 0 : -1;
            }
        }
        return masks;
    }

    /**
     * Builds {@link #ROTATE} or {@link #REVERSE_ROTATE}.
     *
     * @param reverse whether the shuffles reverse the lanes before rotating them
     * @return the 8 shuffles
     */
    @SuppressWarnings("unchecked")
    private static VectorShuffle<Integer>[] rotations(boolean reverse) {
        var shuffles = (VectorShuffle<Integer>[]) new VectorShuffle<?>[8];
        for (var o = 0; o < 8; o++) {
            var indices = new int[8];
            for (var j = 0; j < 8; j++) {
                indices[j] = reverse ? 7 - ((j - o) & 7) : (j - o) & 7;
            }
            shuffles[o] = VectorShuffle.fromArray(I256, indices, 0);
        }
        return shuffles;
    }

    /**
     * Constructs the kernels.
     */
    private VectorCdefDsp() {
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static CdefDsp create() {
        return new VectorCdefDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the padded copy of an 8-wide block whose four edges have neighbors, as
     * {@link ScalarCdefDsp#padding} does: the two rows above from {@code top}, every block row from
     * {@code left} and the plane, and the two rows below from {@code bottom}, each row moved with
     * two overlapping vectors.
     *
     * @param tmp       the padded copy, {@link CdefDsp#TMP_STRIDE} entries per row
     * @param src       the plane
     * @param srcOff    the index of the block's top-left sample
     * @param stride    the plane's row stride, also the row stride of {@code top} and {@code bottom}
     * @param left      the two samples left of every block row
     * @param leftOff   the index of the first row's left samples
     * @param top       the rows above the block
     * @param topOff    the index of the sample above the block's first column, two rows up
     * @param bottom    the rows below the block
     * @param bottomOff the index of the sample below the block's first column
     * @param h         the block height, 4 or 8
     */
    private static void padding8(short[] tmp, short[] src, int srcOff, int stride, short[] left, int leftOff,
                                 short[] top, int topOff, short[] bottom, int bottomOff, int h) {
        var origin = ScalarCdefDsp.TMP_ORIGIN;
        for (var y = 0; y < 2; y++) {
            var from = topOff + y * stride - 2;
            var to = origin + (y - 2) * TMP_STRIDE - 2;
            SHORT128.cast(ShortVector.fromArray(S128, top, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, top, from + 8)).intoArray(tmp, to + 8);
        }
        for (var y = 0; y < h; y++) {
            var from = srcOff + y * stride;
            var to = origin + y * TMP_STRIDE;
            SHORT128.cast(ShortVector.fromArray(S128, src, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, src, from + 6)).intoArray(tmp, to + 6);
            tmp[to - 2] = left[leftOff + 2 * y];
            tmp[to - 1] = left[leftOff + 2 * y + 1];
        }
        for (var y = 0; y < 2; y++) {
            var from = bottomOff + y * stride - 2;
            var to = origin + (h + y) * TMP_STRIDE - 2;
            SHORT128.cast(ShortVector.fromArray(S128, bottom, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, bottom, from + 8)).intoArray(tmp, to + 8);
        }
    }

    /**
     * Builds the padded copy of a 4-wide block whose four edges have neighbors, as
     * {@link #padding8} does for 8-wide blocks.
     *
     * @param tmp       the padded copy, {@link CdefDsp#TMP_STRIDE} entries per row
     * @param src       the plane
     * @param srcOff    the index of the block's top-left sample
     * @param stride    the plane's row stride, also the row stride of {@code top} and {@code bottom}
     * @param left      the two samples left of every block row
     * @param leftOff   the index of the first row's left samples
     * @param top       the rows above the block
     * @param topOff    the index of the sample above the block's first column, two rows up
     * @param bottom    the rows below the block
     * @param bottomOff the index of the sample below the block's first column
     * @param h         the block height, 4 or 8
     */
    private static void padding4(short[] tmp, short[] src, int srcOff, int stride, short[] left, int leftOff,
                                 short[] top, int topOff, short[] bottom, int bottomOff, int h) {
        var origin = ScalarCdefDsp.TMP_ORIGIN;
        for (var y = 0; y < 2; y++) {
            SHORT128.cast(ShortVector.fromArray(S128, top, topOff + y * stride - 2))
                    .intoArray(tmp, origin + (y - 2) * TMP_STRIDE - 2);
        }
        for (var y = 0; y < h; y++) {
            var from = srcOff + y * stride;
            var to = origin + y * TMP_STRIDE;
            SHORT64.cast(ShortVector.fromArray(S64, src, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, src, from + 2)).intoArray(tmp, to + 2);
            tmp[to - 2] = left[leftOff + 2 * y];
            tmp[to - 1] = left[leftOff + 2 * y + 1];
        }
        for (var y = 0; y < 2; y++) {
            SHORT128.cast(ShortVector.fromArray(S128, bottom, bottomOff + y * stride - 2))
                    .intoArray(tmp, origin + (h + y) * TMP_STRIDE - 2);
        }
    }

    @Override
    public int dir(short[] src, int srcOff, int stride, int[] variance, int bitdepthMax) {
        var shift = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        dirDiagonals(src, srcOff, stride, variance, shift);
        dirAlternates(src, srcOff, stride, variance, shift);
        return ScalarCdefDsp.finish(variance);
    }

    /**
     * Accumulates the two diagonal and the first two alternate partial sums of the direction
     * search, whose bins advance by one per row: each row of the block, scaled to 8 bits and
     * centered on 0, is rotated by its row index (reversed for the second class of each pair) and
     * split into the bins below and from 8 on.
     *
     * @param src      the plane
     * @param srcOff   the index of the block's top-left sample
     * @param stride   the plane's row stride
     * @param variance the direction search scratch, receiving the partial sums
     * @param shift    the bit depth minus 8
     */
    private static void dirDiagonals(short[] src, int srcOff, int stride, int[] variance, int shift) {
        var center = IntVector.broadcast(I256, 128);
        var zero = IntVector.zero(I256);
        var diagLow = IntVector.zero(I256);
        var diagHigh = IntVector.zero(I256);
        var antiLow = IntVector.zero(I256);
        var antiHigh = IntVector.zero(I256);
        var altLow = IntVector.zero(I256);
        var altHigh = IntVector.zero(I256);
        var revLow = IntVector.zero(I256);
        var revHigh = IntVector.zero(I256);
        for (var y = 0; y < 8; y++) {
            var px = INT256.cast(INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, srcOff + y * stride))
                    .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.SUB, INT256.cast(center)));
            var pairs = INT256.cast(INT256.cast(px.rearrange(EVENS)).lanewise(VectorOperators.ADD,
                    INT256.cast(px.rearrange(ODDS))).blend(INT256.cast(zero), UPPER_HALF));
            var low = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * y));
            var high = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * y));
            var rotate = ROTATE[y];
            var reverse = REVERSE_ROTATE[y];
            var diag = INT256.cast(px.rearrange(rotate));
            var anti = INT256.cast(px.rearrange(reverse));
            var alt = INT256.cast(pairs.rearrange(rotate));
            var rev = INT256.cast(INT256.cast(pairs.rearrange(REVERSE_PAIRS)).rearrange(rotate));
            diagLow = INT256.cast(diagLow).lanewise(VectorOperators.ADD, diag.lanewise(VectorOperators.AND, low));
            diagHigh = INT256.cast(diagHigh).lanewise(VectorOperators.ADD, diag.lanewise(VectorOperators.AND, high));
            antiLow = INT256.cast(antiLow).lanewise(VectorOperators.ADD, anti.lanewise(VectorOperators.AND, low));
            antiHigh = INT256.cast(antiHigh).lanewise(VectorOperators.ADD, anti.lanewise(VectorOperators.AND, high));
            altLow = INT256.cast(altLow).lanewise(VectorOperators.ADD, alt.lanewise(VectorOperators.AND, low));
            altHigh = INT256.cast(altHigh).lanewise(VectorOperators.ADD, alt.lanewise(VectorOperators.AND, high));
            revLow = INT256.cast(revLow).lanewise(VectorOperators.ADD, rev.lanewise(VectorOperators.AND, low));
            revHigh = INT256.cast(revHigh).lanewise(VectorOperators.ADD, rev.lanewise(VectorOperators.AND, high));
        }
        INT256.cast(diagLow).intoArray(variance, 1 + ScalarCdefDsp.DIAG);
        INT256.cast(diagHigh).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 8);
        INT256.cast(antiLow).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 15);
        INT256.cast(antiHigh).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 23);
        INT256.cast(altLow).intoArray(variance, 1 + ScalarCdefDsp.ALT);
        INT256.cast(altHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 8);
        INT256.cast(revLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 11);
        INT256.cast(revHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 19);
    }

    /**
     * Accumulates the last two alternate partial sums of the direction search, whose bins move by
     * one every two rows, and the horizontal and vertical partial sums: each row is rotated by its
     * first bin and split into the bins below and from 8 on, its sum is a horizontal partial sum and
     * the column sums are the vertical ones. Runs after {@link #dirDiagonals}, whose last stores
     * spill into the bins this method stores.
     *
     * @param src      the plane
     * @param srcOff   the index of the block's top-left sample
     * @param stride   the plane's row stride
     * @param variance the direction search scratch, receiving the partial sums
     * @param shift    the bit depth minus 8
     */
    private static void dirAlternates(short[] src, int srcOff, int stride, int[] variance, int shift) {
        var center = IntVector.broadcast(I256, 128);
        var columns = IntVector.zero(I256);
        var downLow = IntVector.zero(I256);
        var downHigh = IntVector.zero(I256);
        var upLow = IntVector.zero(I256);
        var upHigh = IntVector.zero(I256);
        for (var y = 0; y < 8; y++) {
            var px = INT256.cast(INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, srcOff + y * stride))
                    .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.SUB, INT256.cast(center)));
            columns = INT256.cast(columns).lanewise(VectorOperators.ADD, px);
            variance[1 + ScalarCdefDsp.HV + y] = px.reduceLanes(VectorOperators.ADD);
            var down = 3 - (y >> 1);
            var up = y >> 1;
            var downRotated = INT256.cast(px.rearrange(ROTATE[down]));
            var upRotated = INT256.cast(px.rearrange(ROTATE[up]));
            var downLowLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * down));
            var downHighLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * down));
            var upLowLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * up));
            var upHighLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * up));
            downLow = INT256.cast(downLow).lanewise(VectorOperators.ADD,
                    downRotated.lanewise(VectorOperators.AND, downLowLanes));
            downHigh = INT256.cast(downHigh).lanewise(VectorOperators.ADD,
                    downRotated.lanewise(VectorOperators.AND, downHighLanes));
            upLow = INT256.cast(upLow).lanewise(VectorOperators.ADD,
                    upRotated.lanewise(VectorOperators.AND, upLowLanes));
            upHigh = INT256.cast(upHigh).lanewise(VectorOperators.ADD,
                    upRotated.lanewise(VectorOperators.AND, upHighLanes));
        }
        INT256.cast(columns).intoArray(variance, 1 + ScalarCdefDsp.HV + 8);
        INT256.cast(downLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 22);
        INT256.cast(downHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 30);
        INT256.cast(upLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 33);
        INT256.cast(upHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 41);
    }

    @Override
    public void filter(int layoutIndex, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                       short[] top, int topOff, short[] bottom, int bottomOff, int priStrength,
                       int secStrength, int dir, int damping, int edges, short[] scratch, int bitdepthMax) {
        var w = layoutIndex == LAYOUT_8X8 ? 8 : 4;
        var h = layoutIndex == LAYOUT_4X4 ? 4 : 8;
        if (edges == (HAVE_LEFT | HAVE_RIGHT | HAVE_TOP | HAVE_BOTTOM)) {
            if (w == 8) {
                padding8(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, h);
            } else {
                padding4(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, h);
            }
        } else {
            ScalarCdefDsp.padding(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, w,
                    h, edges);
        }
        var dirs = (dir + 2) * 2;
        var dirsPlus = (dir + 4) * 2;
        var dirsMinus = dir * 2;
        if (priStrength != 0) {
            var bitdepthMin8 = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
            var priTap = 4 - ((priStrength >> bitdepthMin8) & 1);
            var priShift = Math.max(0, damping - ScalarCdefDsp.ulog2(priStrength));
            var farTap = (priTap & 3) | 2;
            if (secStrength != 0) {
                var secShift = damping - ScalarCdefDsp.ulog2(secStrength);
                if (w == 8) {
                    secondaryPart8(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                            DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
                    primaryFinish8(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap,
                            DIRECTIONS[dirs], DIRECTIONS[dirs + 1]);
                } else {
                    secondaryPart4(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                            DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
                    primaryFinish4(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap,
                            DIRECTIONS[dirs], DIRECTIONS[dirs + 1]);
                }
            } else if (w == 8) {
                primary8(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap, DIRECTIONS[dirs],
                        DIRECTIONS[dirs + 1]);
            } else {
                primary4(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap, DIRECTIONS[dirs],
                        DIRECTIONS[dirs + 1]);
            }
        } else {
            var secShift = damping - ScalarCdefDsp.ulog2(secStrength);
            if (w == 8) {
                secondary8(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                        DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
            } else {
                secondary4(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                        DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
            }
        }
    }

    /**
     * Computes the secondary contributions of a block 8 samples wide and the bounds of its
     * secondary taps, storing them per row at {@link #PARTIAL} in the scratch buffer.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param secStrength the secondary strength
     * @param secShift the secondary damping shift
     * @param so0 the offset of the nearer secondary taps of the first secondary direction
     * @param so1 the offset of the farther secondary taps of the first secondary direction
     * @param so2 the offset of the nearer secondary taps of the second secondary direction
     * @param so3 the offset of the farther secondary taps of the second secondary direction
     */
    private static void secondaryPart8(short[] dst, int dstOff, int stride, short[] tmp, int h, int secStrength,
            int secShift, int so0, int so1, int so2, int so3) {
        var zero = IntVector.zero(I256);
        var unsigned = IntVector.broadcast(I256, 0xFFFF);
        var eight = IntVector.broadcast(I256, 8);
        var secThreshold = IntVector.broadcast(I256, secStrength);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, row))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var ds0 = s0.lanewise(VectorOperators.SUB, px);
            var as0 = INT256.cast(ds0.lanewise(VectorOperators.ABS));
            var gs0 = INT256.cast(ds0).lanewise(VectorOperators.ASHR, 31);
            var cs0 = as0.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as0.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs0)
                            .lanewise(VectorOperators.SUB, gs0);
            var ds1 = s1.lanewise(VectorOperators.SUB, px);
            var as1 = INT256.cast(ds1.lanewise(VectorOperators.ABS));
            var gs1 = INT256.cast(ds1).lanewise(VectorOperators.ASHR, 31);
            var cs1 = as1.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as1.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs1)
                            .lanewise(VectorOperators.SUB, gs1);
            var ds2 = s2.lanewise(VectorOperators.SUB, px);
            var as2 = INT256.cast(ds2.lanewise(VectorOperators.ABS));
            var gs2 = INT256.cast(ds2).lanewise(VectorOperators.ASHR, 31);
            var cs2 = as2.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as2.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs2)
                            .lanewise(VectorOperators.SUB, gs2);
            var ds3 = s3.lanewise(VectorOperators.SUB, px);
            var as3 = INT256.cast(ds3.lanewise(VectorOperators.ABS));
            var gs3 = INT256.cast(ds3).lanewise(VectorOperators.ASHR, 31);
            var cs3 = as3.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as3.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs3)
                            .lanewise(VectorOperators.SUB, gs3);
            var ds4 = s4.lanewise(VectorOperators.SUB, px);
            var as4 = INT256.cast(ds4.lanewise(VectorOperators.ABS));
            var gs4 = INT256.cast(ds4).lanewise(VectorOperators.ASHR, 31);
            var cs4 = as4.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as4.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs4)
                            .lanewise(VectorOperators.SUB, gs4);
            var ds5 = s5.lanewise(VectorOperators.SUB, px);
            var as5 = INT256.cast(ds5.lanewise(VectorOperators.ABS));
            var gs5 = INT256.cast(ds5).lanewise(VectorOperators.ASHR, 31);
            var cs5 = as5.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as5.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs5)
                            .lanewise(VectorOperators.SUB, gs5);
            var ds6 = s6.lanewise(VectorOperators.SUB, px);
            var as6 = INT256.cast(ds6.lanewise(VectorOperators.ABS));
            var gs6 = INT256.cast(ds6).lanewise(VectorOperators.ASHR, 31);
            var cs6 = as6.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as6.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs6)
                            .lanewise(VectorOperators.SUB, gs6);
            var ds7 = s7.lanewise(VectorOperators.SUB, px);
            var as7 = INT256.cast(ds7.lanewise(VectorOperators.ABS));
            var gs7 = INT256.cast(ds7).lanewise(VectorOperators.ASHR, 31);
            var cs7 = as7.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as7.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs7)
                            .lanewise(VectorOperators.SUB, gs7);
            var sum = cs0.lanewise(VectorOperators.ADD, cs1).lanewise(VectorOperators.ADD, cs2)
                    .lanewise(VectorOperators.ADD, cs3)
                    .lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, cs4.lanewise(VectorOperators.ADD, cs5)
                            .lanewise(VectorOperators.ADD, cs6).lanewise(VectorOperators.ADD, cs7));
            var part = PARTIAL + y * 24;
            var low = px
                    .lanewise(VectorOperators.MIN, s0.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s1.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s2.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s3.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s4.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s5.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s6.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s7.lanewise(VectorOperators.AND, INT256.cast(unsigned)));
            var high = px
                    .lanewise(VectorOperators.MAX, s0)
                    .lanewise(VectorOperators.MAX, s1)
                    .lanewise(VectorOperators.MAX, s2)
                    .lanewise(VectorOperators.MAX, s3)
                    .lanewise(VectorOperators.MAX, s4)
                    .lanewise(VectorOperators.MAX, s5)
                    .lanewise(VectorOperators.MAX, s6)
                    .lanewise(VectorOperators.MAX, s7);
            SHORT128.cast(sum.convertShape(VectorOperators.I2S, S128, 0)).intoArray(tmp, part);
            SHORT128.cast(low.convertShape(VectorOperators.I2S, S128, 0)).intoArray(tmp, part + 8);
            SHORT128.cast(high.convertShape(VectorOperators.I2S, S128, 0)).intoArray(tmp, part + 16);
        }
    }

    /**
     * Computes the secondary contributions of a block 4 samples wide and the bounds of its
     * secondary taps, storing them per row at {@link #PARTIAL} in the scratch buffer.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param secStrength the secondary strength
     * @param secShift the secondary damping shift
     * @param so0 the offset of the nearer secondary taps of the first secondary direction
     * @param so1 the offset of the farther secondary taps of the first secondary direction
     * @param so2 the offset of the nearer secondary taps of the second secondary direction
     * @param so3 the offset of the farther secondary taps of the second secondary direction
     */
    private static void secondaryPart4(short[] dst, int dstOff, int stride, short[] tmp, int h, int secStrength,
            int secShift, int so0, int so1, int so2, int so3) {
        var zero = IntVector.zero(I128);
        var unsigned = IntVector.broadcast(I128, 0xFFFF);
        var eight = IntVector.broadcast(I128, 8);
        var secThreshold = IntVector.broadcast(I128, secStrength);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, dst, row))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so2))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so2))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s4 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s5 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s6 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so3))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s7 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so3))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var ds0 = s0.lanewise(VectorOperators.SUB, px);
            var as0 = INT128.cast(ds0.lanewise(VectorOperators.ABS));
            var gs0 = INT128.cast(ds0).lanewise(VectorOperators.ASHR, 31);
            var cs0 = as0.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as0.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs0)
                            .lanewise(VectorOperators.SUB, gs0);
            var ds1 = s1.lanewise(VectorOperators.SUB, px);
            var as1 = INT128.cast(ds1.lanewise(VectorOperators.ABS));
            var gs1 = INT128.cast(ds1).lanewise(VectorOperators.ASHR, 31);
            var cs1 = as1.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as1.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs1)
                            .lanewise(VectorOperators.SUB, gs1);
            var ds2 = s2.lanewise(VectorOperators.SUB, px);
            var as2 = INT128.cast(ds2.lanewise(VectorOperators.ABS));
            var gs2 = INT128.cast(ds2).lanewise(VectorOperators.ASHR, 31);
            var cs2 = as2.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as2.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs2)
                            .lanewise(VectorOperators.SUB, gs2);
            var ds3 = s3.lanewise(VectorOperators.SUB, px);
            var as3 = INT128.cast(ds3.lanewise(VectorOperators.ABS));
            var gs3 = INT128.cast(ds3).lanewise(VectorOperators.ASHR, 31);
            var cs3 = as3.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as3.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs3)
                            .lanewise(VectorOperators.SUB, gs3);
            var ds4 = s4.lanewise(VectorOperators.SUB, px);
            var as4 = INT128.cast(ds4.lanewise(VectorOperators.ABS));
            var gs4 = INT128.cast(ds4).lanewise(VectorOperators.ASHR, 31);
            var cs4 = as4.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as4.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs4)
                            .lanewise(VectorOperators.SUB, gs4);
            var ds5 = s5.lanewise(VectorOperators.SUB, px);
            var as5 = INT128.cast(ds5.lanewise(VectorOperators.ABS));
            var gs5 = INT128.cast(ds5).lanewise(VectorOperators.ASHR, 31);
            var cs5 = as5.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as5.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs5)
                            .lanewise(VectorOperators.SUB, gs5);
            var ds6 = s6.lanewise(VectorOperators.SUB, px);
            var as6 = INT128.cast(ds6.lanewise(VectorOperators.ABS));
            var gs6 = INT128.cast(ds6).lanewise(VectorOperators.ASHR, 31);
            var cs6 = as6.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as6.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs6)
                            .lanewise(VectorOperators.SUB, gs6);
            var ds7 = s7.lanewise(VectorOperators.SUB, px);
            var as7 = INT128.cast(ds7.lanewise(VectorOperators.ABS));
            var gs7 = INT128.cast(ds7).lanewise(VectorOperators.ASHR, 31);
            var cs7 = as7.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as7.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs7)
                            .lanewise(VectorOperators.SUB, gs7);
            var sum = cs0.lanewise(VectorOperators.ADD, cs1).lanewise(VectorOperators.ADD, cs2)
                    .lanewise(VectorOperators.ADD, cs3)
                    .lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, cs4.lanewise(VectorOperators.ADD, cs5)
                            .lanewise(VectorOperators.ADD, cs6).lanewise(VectorOperators.ADD, cs7));
            var part = PARTIAL + y * 24;
            var low = px
                    .lanewise(VectorOperators.MIN, s0.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s1.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s2.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s3.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s4.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s5.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s6.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                    .lanewise(VectorOperators.MIN, s7.lanewise(VectorOperators.AND, INT128.cast(unsigned)));
            var high = px
                    .lanewise(VectorOperators.MAX, s0)
                    .lanewise(VectorOperators.MAX, s1)
                    .lanewise(VectorOperators.MAX, s2)
                    .lanewise(VectorOperators.MAX, s3)
                    .lanewise(VectorOperators.MAX, s4)
                    .lanewise(VectorOperators.MAX, s5)
                    .lanewise(VectorOperators.MAX, s6)
                    .lanewise(VectorOperators.MAX, s7);
            SHORT64.cast(sum.convertShape(VectorOperators.I2S, S64, 0)).intoArray(tmp, part);
            SHORT64.cast(low.convertShape(VectorOperators.I2S, S64, 0)).intoArray(tmp, part + 8);
            SHORT64.cast(high.convertShape(VectorOperators.I2S, S64, 0)).intoArray(tmp, part + 16);
        }
    }

    /**
     * Applies the primary and secondary CDEF filter to a block 8 samples wide, adding the
     * primary contributions to the partial results {@link #secondaryPart8} stored.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param priStrength the primary strength
     * @param priShift the primary damping shift
     * @param tap0 the weight of the nearer primary taps
     * @param tap1 the weight of the farther primary taps
     * @param po0 the offset of the nearer primary taps in the padded copy
     * @param po1 the offset of the farther primary taps in the padded copy
     */
    private static void primaryFinish8(short[] dst, int dstOff, int stride, short[] tmp, int h, int priStrength,
            int priShift, int tap0, int tap1, int po0, int po1) {
        var zero = IntVector.zero(I256);
        var unsigned = IntVector.broadcast(I256, 0xFFFF);
        var eight = IntVector.broadcast(I256, 8);
        var priThreshold = IntVector.broadcast(I256, priStrength);
        var near = IntVector.broadcast(I256, tap0);
        var far = IntVector.broadcast(I256, tap1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, row))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + po0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - po0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + po1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - po1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var dp0 = p0.lanewise(VectorOperators.SUB, px);
            var ap0 = INT256.cast(dp0.lanewise(VectorOperators.ABS));
            var gp0 = INT256.cast(dp0).lanewise(VectorOperators.ASHR, 31);
            var cp0 = ap0.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap0.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp0)
                            .lanewise(VectorOperators.SUB, gp0);
            var dp1 = p1.lanewise(VectorOperators.SUB, px);
            var ap1 = INT256.cast(dp1.lanewise(VectorOperators.ABS));
            var gp1 = INT256.cast(dp1).lanewise(VectorOperators.ASHR, 31);
            var cp1 = ap1.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap1.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp1)
                            .lanewise(VectorOperators.SUB, gp1);
            var dp2 = p2.lanewise(VectorOperators.SUB, px);
            var ap2 = INT256.cast(dp2.lanewise(VectorOperators.ABS));
            var gp2 = INT256.cast(dp2).lanewise(VectorOperators.ASHR, 31);
            var cp2 = ap2.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap2.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp2)
                            .lanewise(VectorOperators.SUB, gp2);
            var dp3 = p3.lanewise(VectorOperators.SUB, px);
            var ap3 = INT256.cast(dp3.lanewise(VectorOperators.ABS));
            var gp3 = INT256.cast(dp3).lanewise(VectorOperators.ASHR, 31);
            var cp3 = ap3.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap3.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp3)
                            .lanewise(VectorOperators.SUB, gp3);
            var sum = cp0.lanewise(VectorOperators.ADD, cp1).lanewise(VectorOperators.MUL, INT256.cast(near))
                    .lanewise(VectorOperators.ADD, cp2.lanewise(VectorOperators.ADD, cp3)
                            .lanewise(VectorOperators.MUL, INT256.cast(far)));
            var part = PARTIAL + y * 24;
            sum = sum.lanewise(VectorOperators.ADD, INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, part))
                    .convertShape(VectorOperators.S2I, I256, 0)));
            var sign = INT256.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT256.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT256.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            var low = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, part + 8))
                    .convertShape(VectorOperators.ZERO_EXTEND_S2I, I256, 0))
                    .lanewise(VectorOperators.MIN, p0.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p1.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p2.lanewise(VectorOperators.AND, INT256.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p3.lanewise(VectorOperators.AND, INT256.cast(unsigned)));
            var high = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, part + 16))
                    .convertShape(VectorOperators.S2I, I256, 0))
                    .lanewise(VectorOperators.MAX, p0).lanewise(VectorOperators.MAX, p1)
                            .lanewise(VectorOperators.MAX, p2).lanewise(VectorOperators.MAX, p3);
            value = value.lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high);
            SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row);
        }
    }

    /**
     * Applies the primary and secondary CDEF filter to a block 4 samples wide, adding the
     * primary contributions to the partial results {@link #secondaryPart4} stored.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param priStrength the primary strength
     * @param priShift the primary damping shift
     * @param tap0 the weight of the nearer primary taps
     * @param tap1 the weight of the farther primary taps
     * @param po0 the offset of the nearer primary taps in the padded copy
     * @param po1 the offset of the farther primary taps in the padded copy
     */
    private static void primaryFinish4(short[] dst, int dstOff, int stride, short[] tmp, int h, int priStrength,
            int priShift, int tap0, int tap1, int po0, int po1) {
        var zero = IntVector.zero(I128);
        var unsigned = IntVector.broadcast(I128, 0xFFFF);
        var eight = IntVector.broadcast(I128, 8);
        var priThreshold = IntVector.broadcast(I128, priStrength);
        var near = IntVector.broadcast(I128, tap0);
        var far = IntVector.broadcast(I128, tap1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, dst, row))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + po0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - po0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + po1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - po1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var dp0 = p0.lanewise(VectorOperators.SUB, px);
            var ap0 = INT128.cast(dp0.lanewise(VectorOperators.ABS));
            var gp0 = INT128.cast(dp0).lanewise(VectorOperators.ASHR, 31);
            var cp0 = ap0.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap0.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp0)
                            .lanewise(VectorOperators.SUB, gp0);
            var dp1 = p1.lanewise(VectorOperators.SUB, px);
            var ap1 = INT128.cast(dp1.lanewise(VectorOperators.ABS));
            var gp1 = INT128.cast(dp1).lanewise(VectorOperators.ASHR, 31);
            var cp1 = ap1.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap1.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp1)
                            .lanewise(VectorOperators.SUB, gp1);
            var dp2 = p2.lanewise(VectorOperators.SUB, px);
            var ap2 = INT128.cast(dp2.lanewise(VectorOperators.ABS));
            var gp2 = INT128.cast(dp2).lanewise(VectorOperators.ASHR, 31);
            var cp2 = ap2.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap2.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp2)
                            .lanewise(VectorOperators.SUB, gp2);
            var dp3 = p3.lanewise(VectorOperators.SUB, px);
            var ap3 = INT128.cast(dp3.lanewise(VectorOperators.ABS));
            var gp3 = INT128.cast(dp3).lanewise(VectorOperators.ASHR, 31);
            var cp3 = ap3.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap3.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp3)
                            .lanewise(VectorOperators.SUB, gp3);
            var sum = cp0.lanewise(VectorOperators.ADD, cp1).lanewise(VectorOperators.MUL, INT128.cast(near))
                    .lanewise(VectorOperators.ADD, cp2.lanewise(VectorOperators.ADD, cp3)
                            .lanewise(VectorOperators.MUL, INT128.cast(far)));
            var part = PARTIAL + y * 24;
            sum = sum.lanewise(VectorOperators.ADD, INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, part))
                    .convertShape(VectorOperators.S2I, I128, 0)));
            var sign = INT128.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT128.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT128.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            var low = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, part + 8))
                    .convertShape(VectorOperators.ZERO_EXTEND_S2I, I128, 0))
                    .lanewise(VectorOperators.MIN, p0.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p1.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p2.lanewise(VectorOperators.AND, INT128.cast(unsigned)))
                            .lanewise(VectorOperators.MIN, p3.lanewise(VectorOperators.AND, INT128.cast(unsigned)));
            var high = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, part + 16))
                    .convertShape(VectorOperators.S2I, I128, 0))
                    .lanewise(VectorOperators.MAX, p0).lanewise(VectorOperators.MAX, p1)
                            .lanewise(VectorOperators.MAX, p2).lanewise(VectorOperators.MAX, p3);
            value = value.lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high);
            SHORT64.cast(value.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row);
        }
    }

    /**
     * Applies the primary CDEF filter to a block 8 samples wide, one row per iteration.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param priStrength the primary strength
     * @param priShift the primary damping shift
     * @param tap0 the weight of the nearer primary taps
     * @param tap1 the weight of the farther primary taps
     * @param po0 the offset of the nearer primary taps in the padded copy
     * @param po1 the offset of the farther primary taps in the padded copy
     */
    private static void primary8(short[] dst, int dstOff, int stride, short[] tmp, int h, int priStrength, int priShift,
            int tap0, int tap1, int po0, int po1) {
        var zero = IntVector.zero(I256);
        var unsigned = IntVector.broadcast(I256, 0xFFFF);
        var eight = IntVector.broadcast(I256, 8);
        var priThreshold = IntVector.broadcast(I256, priStrength);
        var near = IntVector.broadcast(I256, tap0);
        var far = IntVector.broadcast(I256, tap1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, row))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + po0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - po0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + po1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var p3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - po1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var dp0 = p0.lanewise(VectorOperators.SUB, px);
            var ap0 = INT256.cast(dp0.lanewise(VectorOperators.ABS));
            var gp0 = INT256.cast(dp0).lanewise(VectorOperators.ASHR, 31);
            var cp0 = ap0.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap0.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp0)
                            .lanewise(VectorOperators.SUB, gp0);
            var dp1 = p1.lanewise(VectorOperators.SUB, px);
            var ap1 = INT256.cast(dp1.lanewise(VectorOperators.ABS));
            var gp1 = INT256.cast(dp1).lanewise(VectorOperators.ASHR, 31);
            var cp1 = ap1.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap1.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp1)
                            .lanewise(VectorOperators.SUB, gp1);
            var dp2 = p2.lanewise(VectorOperators.SUB, px);
            var ap2 = INT256.cast(dp2.lanewise(VectorOperators.ABS));
            var gp2 = INT256.cast(dp2).lanewise(VectorOperators.ASHR, 31);
            var cp2 = ap2.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap2.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp2)
                            .lanewise(VectorOperators.SUB, gp2);
            var dp3 = p3.lanewise(VectorOperators.SUB, px);
            var ap3 = INT256.cast(dp3.lanewise(VectorOperators.ABS));
            var gp3 = INT256.cast(dp3).lanewise(VectorOperators.ASHR, 31);
            var cp3 = ap3.lanewise(VectorOperators.MIN, INT256.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap3.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gp3)
                            .lanewise(VectorOperators.SUB, gp3);
            var sum = cp0.lanewise(VectorOperators.ADD, cp1).lanewise(VectorOperators.MUL, INT256.cast(near))
                    .lanewise(VectorOperators.ADD, cp2.lanewise(VectorOperators.ADD, cp3)
                            .lanewise(VectorOperators.MUL, INT256.cast(far)));
            var sign = INT256.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT256.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT256.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row);
        }
    }

    /**
     * Applies the primary CDEF filter to a block 4 samples wide, one row per iteration.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param priStrength the primary strength
     * @param priShift the primary damping shift
     * @param tap0 the weight of the nearer primary taps
     * @param tap1 the weight of the farther primary taps
     * @param po0 the offset of the nearer primary taps in the padded copy
     * @param po1 the offset of the farther primary taps in the padded copy
     */
    private static void primary4(short[] dst, int dstOff, int stride, short[] tmp, int h, int priStrength, int priShift,
            int tap0, int tap1, int po0, int po1) {
        var zero = IntVector.zero(I128);
        var unsigned = IntVector.broadcast(I128, 0xFFFF);
        var eight = IntVector.broadcast(I128, 8);
        var priThreshold = IntVector.broadcast(I128, priStrength);
        var near = IntVector.broadcast(I128, tap0);
        var far = IntVector.broadcast(I128, tap1);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, dst, row))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + po0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - po0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + po1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var p3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - po1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var dp0 = p0.lanewise(VectorOperators.SUB, px);
            var ap0 = INT128.cast(dp0.lanewise(VectorOperators.ABS));
            var gp0 = INT128.cast(dp0).lanewise(VectorOperators.ASHR, 31);
            var cp0 = ap0.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap0.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp0)
                            .lanewise(VectorOperators.SUB, gp0);
            var dp1 = p1.lanewise(VectorOperators.SUB, px);
            var ap1 = INT128.cast(dp1.lanewise(VectorOperators.ABS));
            var gp1 = INT128.cast(dp1).lanewise(VectorOperators.ASHR, 31);
            var cp1 = ap1.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap1.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp1)
                            .lanewise(VectorOperators.SUB, gp1);
            var dp2 = p2.lanewise(VectorOperators.SUB, px);
            var ap2 = INT128.cast(dp2.lanewise(VectorOperators.ABS));
            var gp2 = INT128.cast(dp2).lanewise(VectorOperators.ASHR, 31);
            var cp2 = ap2.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap2.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp2)
                            .lanewise(VectorOperators.SUB, gp2);
            var dp3 = p3.lanewise(VectorOperators.SUB, px);
            var ap3 = INT128.cast(dp3.lanewise(VectorOperators.ABS));
            var gp3 = INT128.cast(dp3).lanewise(VectorOperators.ASHR, 31);
            var cp3 = ap3.lanewise(VectorOperators.MIN, INT128.cast(priThreshold)
                    .lanewise(VectorOperators.SUB, ap3.lanewise(VectorOperators.ASHR, priShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gp3)
                            .lanewise(VectorOperators.SUB, gp3);
            var sum = cp0.lanewise(VectorOperators.ADD, cp1).lanewise(VectorOperators.MUL, INT128.cast(near))
                    .lanewise(VectorOperators.ADD, cp2.lanewise(VectorOperators.ADD, cp3)
                            .lanewise(VectorOperators.MUL, INT128.cast(far)));
            var sign = INT128.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT128.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT128.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            SHORT64.cast(value.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row);
        }
    }

    /**
     * Applies the secondary CDEF filter to a block 8 samples wide, one row per iteration.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param secStrength the secondary strength
     * @param secShift the secondary damping shift
     * @param so0 the offset of the nearer secondary taps of the first secondary direction
     * @param so1 the offset of the farther secondary taps of the first secondary direction
     * @param so2 the offset of the nearer secondary taps of the second secondary direction
     * @param so3 the offset of the farther secondary taps of the second secondary direction
     */
    private static void secondary8(short[] dst, int dstOff, int stride, short[] tmp, int h, int secStrength,
            int secShift, int so0, int so1, int so2, int so3) {
        var zero = IntVector.zero(I256);
        var unsigned = IntVector.broadcast(I256, 0xFFFF);
        var eight = IntVector.broadcast(I256, 8);
        var secThreshold = IntVector.broadcast(I256, secStrength);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, dst, row))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s0 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s1 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so0))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s2 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s3 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so2))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s4 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s5 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so1))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s6 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t + so3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var s7 = INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, tmp, t - so3))
                    .convertShape(VectorOperators.S2I, I256, 0));
            var ds0 = s0.lanewise(VectorOperators.SUB, px);
            var as0 = INT256.cast(ds0.lanewise(VectorOperators.ABS));
            var gs0 = INT256.cast(ds0).lanewise(VectorOperators.ASHR, 31);
            var cs0 = as0.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as0.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs0)
                            .lanewise(VectorOperators.SUB, gs0);
            var ds1 = s1.lanewise(VectorOperators.SUB, px);
            var as1 = INT256.cast(ds1.lanewise(VectorOperators.ABS));
            var gs1 = INT256.cast(ds1).lanewise(VectorOperators.ASHR, 31);
            var cs1 = as1.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as1.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs1)
                            .lanewise(VectorOperators.SUB, gs1);
            var ds2 = s2.lanewise(VectorOperators.SUB, px);
            var as2 = INT256.cast(ds2.lanewise(VectorOperators.ABS));
            var gs2 = INT256.cast(ds2).lanewise(VectorOperators.ASHR, 31);
            var cs2 = as2.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as2.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs2)
                            .lanewise(VectorOperators.SUB, gs2);
            var ds3 = s3.lanewise(VectorOperators.SUB, px);
            var as3 = INT256.cast(ds3.lanewise(VectorOperators.ABS));
            var gs3 = INT256.cast(ds3).lanewise(VectorOperators.ASHR, 31);
            var cs3 = as3.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as3.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs3)
                            .lanewise(VectorOperators.SUB, gs3);
            var ds4 = s4.lanewise(VectorOperators.SUB, px);
            var as4 = INT256.cast(ds4.lanewise(VectorOperators.ABS));
            var gs4 = INT256.cast(ds4).lanewise(VectorOperators.ASHR, 31);
            var cs4 = as4.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as4.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs4)
                            .lanewise(VectorOperators.SUB, gs4);
            var ds5 = s5.lanewise(VectorOperators.SUB, px);
            var as5 = INT256.cast(ds5.lanewise(VectorOperators.ABS));
            var gs5 = INT256.cast(ds5).lanewise(VectorOperators.ASHR, 31);
            var cs5 = as5.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as5.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs5)
                            .lanewise(VectorOperators.SUB, gs5);
            var ds6 = s6.lanewise(VectorOperators.SUB, px);
            var as6 = INT256.cast(ds6.lanewise(VectorOperators.ABS));
            var gs6 = INT256.cast(ds6).lanewise(VectorOperators.ASHR, 31);
            var cs6 = as6.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as6.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs6)
                            .lanewise(VectorOperators.SUB, gs6);
            var ds7 = s7.lanewise(VectorOperators.SUB, px);
            var as7 = INT256.cast(ds7.lanewise(VectorOperators.ABS));
            var gs7 = INT256.cast(ds7).lanewise(VectorOperators.ASHR, 31);
            var cs7 = as7.lanewise(VectorOperators.MIN, INT256.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as7.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT256.cast(zero))).lanewise(VectorOperators.XOR, gs7)
                            .lanewise(VectorOperators.SUB, gs7);
            var sum = cs0.lanewise(VectorOperators.ADD, cs1).lanewise(VectorOperators.ADD, cs2)
                    .lanewise(VectorOperators.ADD, cs3)
                    .lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, cs4.lanewise(VectorOperators.ADD, cs5)
                            .lanewise(VectorOperators.ADD, cs6).lanewise(VectorOperators.ADD, cs7));
            var sign = INT256.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT256.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT256.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            SHORT128.cast(value.convertShape(VectorOperators.I2S, S128, 0)).intoArray(dst, row);
        }
    }

    /**
     * Applies the secondary CDEF filter to a block 4 samples wide, one row per iteration.
     *
     * @param dst    the plane holding the block
     * @param dstOff the index of the block's top-left sample
     * @param stride the row stride
     * @param tmp    the padded block copy
     * @param h      the block height
     * @param secStrength the secondary strength
     * @param secShift the secondary damping shift
     * @param so0 the offset of the nearer secondary taps of the first secondary direction
     * @param so1 the offset of the farther secondary taps of the first secondary direction
     * @param so2 the offset of the nearer secondary taps of the second secondary direction
     * @param so3 the offset of the farther secondary taps of the second secondary direction
     */
    private static void secondary4(short[] dst, int dstOff, int stride, short[] tmp, int h, int secStrength,
            int secShift, int so0, int so1, int so2, int so3) {
        var zero = IntVector.zero(I128);
        var unsigned = IntVector.broadcast(I128, 0xFFFF);
        var eight = IntVector.broadcast(I128, 8);
        var secThreshold = IntVector.broadcast(I128, secStrength);
        for (var y = 0; y < h; y++) {
            var row = dstOff + y * stride;
            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;
            var px = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, dst, row))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s0 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s1 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so0))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s2 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so2))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s3 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so2))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s4 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s5 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so1))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s6 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t + so3))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var s7 = INT128.cast(SHORT64.cast(ShortVector.fromArray(S64, tmp, t - so3))
                    .convertShape(VectorOperators.S2I, I128, 0));
            var ds0 = s0.lanewise(VectorOperators.SUB, px);
            var as0 = INT128.cast(ds0.lanewise(VectorOperators.ABS));
            var gs0 = INT128.cast(ds0).lanewise(VectorOperators.ASHR, 31);
            var cs0 = as0.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as0.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs0)
                            .lanewise(VectorOperators.SUB, gs0);
            var ds1 = s1.lanewise(VectorOperators.SUB, px);
            var as1 = INT128.cast(ds1.lanewise(VectorOperators.ABS));
            var gs1 = INT128.cast(ds1).lanewise(VectorOperators.ASHR, 31);
            var cs1 = as1.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as1.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs1)
                            .lanewise(VectorOperators.SUB, gs1);
            var ds2 = s2.lanewise(VectorOperators.SUB, px);
            var as2 = INT128.cast(ds2.lanewise(VectorOperators.ABS));
            var gs2 = INT128.cast(ds2).lanewise(VectorOperators.ASHR, 31);
            var cs2 = as2.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as2.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs2)
                            .lanewise(VectorOperators.SUB, gs2);
            var ds3 = s3.lanewise(VectorOperators.SUB, px);
            var as3 = INT128.cast(ds3.lanewise(VectorOperators.ABS));
            var gs3 = INT128.cast(ds3).lanewise(VectorOperators.ASHR, 31);
            var cs3 = as3.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as3.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs3)
                            .lanewise(VectorOperators.SUB, gs3);
            var ds4 = s4.lanewise(VectorOperators.SUB, px);
            var as4 = INT128.cast(ds4.lanewise(VectorOperators.ABS));
            var gs4 = INT128.cast(ds4).lanewise(VectorOperators.ASHR, 31);
            var cs4 = as4.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as4.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs4)
                            .lanewise(VectorOperators.SUB, gs4);
            var ds5 = s5.lanewise(VectorOperators.SUB, px);
            var as5 = INT128.cast(ds5.lanewise(VectorOperators.ABS));
            var gs5 = INT128.cast(ds5).lanewise(VectorOperators.ASHR, 31);
            var cs5 = as5.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as5.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs5)
                            .lanewise(VectorOperators.SUB, gs5);
            var ds6 = s6.lanewise(VectorOperators.SUB, px);
            var as6 = INT128.cast(ds6.lanewise(VectorOperators.ABS));
            var gs6 = INT128.cast(ds6).lanewise(VectorOperators.ASHR, 31);
            var cs6 = as6.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as6.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs6)
                            .lanewise(VectorOperators.SUB, gs6);
            var ds7 = s7.lanewise(VectorOperators.SUB, px);
            var as7 = INT128.cast(ds7.lanewise(VectorOperators.ABS));
            var gs7 = INT128.cast(ds7).lanewise(VectorOperators.ASHR, 31);
            var cs7 = as7.lanewise(VectorOperators.MIN, INT128.cast(secThreshold)
                    .lanewise(VectorOperators.SUB, as7.lanewise(VectorOperators.ASHR, secShift))
                    .lanewise(VectorOperators.MAX, INT128.cast(zero))).lanewise(VectorOperators.XOR, gs7)
                            .lanewise(VectorOperators.SUB, gs7);
            var sum = cs0.lanewise(VectorOperators.ADD, cs1).lanewise(VectorOperators.ADD, cs2)
                    .lanewise(VectorOperators.ADD, cs3)
                    .lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.ADD, cs4.lanewise(VectorOperators.ADD, cs5)
                            .lanewise(VectorOperators.ADD, cs6).lanewise(VectorOperators.ADD, cs7));
            var sign = INT128.cast(sum).lanewise(VectorOperators.ASHR, 31);
            var value = px.lanewise(VectorOperators.ADD, INT128.cast(sum.lanewise(VectorOperators.ADD, sign)
                    .lanewise(VectorOperators.ADD, INT128.cast(eight))).lanewise(VectorOperators.ASHR, 4));
            SHORT64.cast(value.convertShape(VectorOperators.I2S, S64, 0)).intoArray(dst, row);
        }
    }
}
