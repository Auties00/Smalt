/* Ports libvpx (vp9/common/vp9_entropymode.h). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Counts the symbols a frame decodes, the {@code FRAME_COUNTS} of the specification, which
 * drive the backward adaptation of the probabilities at the end of the frame.
 *
 * <p>Every counter lives in the single array {@link #counts}, at the offset of its table plus
 * its index within the table, so that the counters of tiles decoded in parallel are summed
 * with one loop. Each offset constant names the dimensions of its table.
 *
 * @implNote This implementation follows the layout of libvpx's {@code FRAME_COUNTS}
 * ({@code vp9/common/vp9_entropymode.h}).
 */
final class Vp9FrameCounts {
    /**
     * The offset of the luma intra mode counts of inter frames, {@code [size group 4][mode 10]}.
     */
    public static final int Y_MODE = 0;

    /**
     * The offset of the chroma intra mode counts, {@code [luma mode 10][mode 10]}.
     */
    public static final int UV_MODE = Y_MODE + 4 * 10;

    /**
     * The offset of the partition counts, {@code [context 16][partition 4]}.
     */
    public static final int PARTITION = UV_MODE + 10 * 10;

    /**
     * The offset of the coefficient token counts,
     * {@code [transform size 4][plane type 2][reference 2][band 6][context 6][token 4]}, the
     * tokens being zero, one, two or more, and the end of block.
     */
    public static final int COEF = PARTITION + 16 * 4;

    /**
     * The offset of the counts of tokens coded with an end-of-block branch,
     * {@code [transform size 4][plane type 2][reference 2][band 6][context 6]}.
     */
    public static final int EOB_BRANCH = COEF + 4 * 2 * 2 * 6 * 6 * 4;

    /**
     * The offset of the switchable interpolation filter counts, {@code [context 4][filter 3]}.
     */
    public static final int SWITCHABLE_INTERP = EOB_BRANCH + 4 * 2 * 2 * 6 * 6;

    /**
     * The offset of the inter mode counts, {@code [context 7][mode 4]}, modes in the order
     * nearest, near, zero, new.
     */
    public static final int INTER_MODE = SWITCHABLE_INTERP + 4 * 3;

    /**
     * The offset of the intra or inter counts, {@code [context 4][inter 2]}.
     */
    public static final int INTRA_INTER = INTER_MODE + 7 * 4;

    /**
     * The offset of the compound prediction counts, {@code [context 5][compound 2]}.
     */
    public static final int COMP_INTER = INTRA_INTER + 4 * 2;

    /**
     * The offset of the single reference counts, {@code [context 5][node 2][bit 2]}.
     */
    public static final int SINGLE_REF = COMP_INTER + 5 * 2;

    /**
     * The offset of the compound reference counts, {@code [context 5][bit 2]}.
     */
    public static final int COMP_REF = SINGLE_REF + 5 * 2 * 2;

    /**
     * The offset of the transform size counts of blocks whose largest transform is 32x32,
     * {@code [context 2][size 4]}.
     */
    public static final int TX_32X32 = COMP_REF + 5 * 2;

    /**
     * The offset of the transform size counts of blocks whose largest transform is 16x16,
     * {@code [context 2][size 3]}.
     */
    public static final int TX_16X16 = TX_32X32 + 2 * 4;

    /**
     * The offset of the transform size counts of blocks whose largest transform is 8x8,
     * {@code [context 2][size 2]}.
     */
    public static final int TX_8X8 = TX_16X16 + 2 * 3;

    /**
     * The offset of the skip counts, {@code [context 3][skip 2]}.
     */
    public static final int SKIP = TX_8X8 + 2 * 2;

    /**
     * The offset of the motion vector joint counts, {@code [joint 4]}.
     */
    public static final int MV_JOINTS = SKIP + 3 * 2;

    /**
     * The offset of the motion vector component counts, {@code [component 2][]} with the
     * per-component layout given by the {@code MVC_} offsets and {@link #MVC_SIZE}.
     */
    public static final int MV_COMPS = MV_JOINTS + 4;

    /**
     * The offset within a component of the sign counts, {@code [sign 2]}.
     */
    public static final int MVC_SIGN = 0;

    /**
     * The offset within a component of the class counts, {@code [class 11]}.
     */
    public static final int MVC_CLASSES = MVC_SIGN + 2;

    /**
     * The offset within a component of the class 0 integer counts, {@code [integer 2]}.
     */
    public static final int MVC_CLASS0 = MVC_CLASSES + 11;

    /**
     * The offset within a component of the integer bit counts, {@code [bit 10][value 2]}.
     */
    public static final int MVC_BITS = MVC_CLASS0 + 2;

    /**
     * The offset within a component of the class 0 fraction counts,
     * {@code [integer 2][fraction 4]}.
     */
    public static final int MVC_CLASS0_FP = MVC_BITS + 10 * 2;

    /**
     * The offset within a component of the fraction counts, {@code [fraction 4]}.
     */
    public static final int MVC_FP = MVC_CLASS0_FP + 2 * 4;

    /**
     * The offset within a component of the class 0 high precision counts, {@code [bit 2]}.
     */
    public static final int MVC_CLASS0_HP = MVC_FP + 4;

    /**
     * The offset within a component of the high precision counts, {@code [bit 2]}.
     */
    public static final int MVC_HP = MVC_CLASS0_HP + 2;

    /**
     * The number of counters of one motion vector component.
     */
    public static final int MVC_SIZE = MVC_HP + 2;

    /**
     * The number of counters.
     */
    public static final int SIZE = MV_COMPS + 2 * MVC_SIZE;

    /**
     * The counters.
     */
    public final int[] counts = new int[SIZE];

    /**
     * Creates zeroed counters.
     */
    public Vp9FrameCounts() {
    }

    /**
     * Zeroes every counter.
     */
    public void clear() {
        Arrays.fill(counts, 0);
    }

    /**
     * Adds another set of counters to this one.
     *
     * @param other the counters to add
     */
    public void add(Vp9FrameCounts other) {
        var source = other.counts;
        var target = counts;
        for (var i = 0; i < SIZE; i++) {
            target[i] += source[i];
        }
    }

    /**
     * Counts one decoded motion vector difference ({@code vp9_inc_mv}).
     *
     * @param row the vertical component of the difference
     * @param col the horizontal component of the difference
     */
    public void countMv(int row, int col) {
        var joint = (row == 0 ? 0 : 2) + (col == 0 ? 0 : 1);
        counts[MV_JOINTS + joint]++;
        if (row != 0) {
            countComponent(row, MV_COMPS);
        }
        if (col != 0) {
            countComponent(col, MV_COMPS + MVC_SIZE);
        }
    }

    /**
     * Counts one nonzero motion vector component ({@code inc_mv_component}).
     *
     * @param value the component, nonzero
     * @param base the offset of the component's counters
     */
    private void countComponent(int value, int base) {
        var c = counts;
        var sign = value < 0 ? 1 : 0;
        c[base + MVC_SIGN + sign]++;
        var magnitude = (sign != 0 ? -value : value) - 1;
        var mvClass = mvClass(magnitude);
        c[base + MVC_CLASSES + mvClass]++;
        var offset = magnitude - (mvClass == 0 ? 0 : 2 << (mvClass + 2));
        var integer = offset >> 3;
        var fraction = (offset >> 1) & 3;
        var high = offset & 1;
        if (mvClass == 0) {
            c[base + MVC_CLASS0 + integer]++;
            c[base + MVC_CLASS0_FP + integer * 4 + fraction]++;
            c[base + MVC_CLASS0_HP + high]++;
        } else {
            var bits = mvClass;
            for (var i = 0; i < bits; i++) {
                c[base + MVC_BITS + i * 2 + ((integer >> i) & 1)]++;
            }
            c[base + MVC_FP + fraction]++;
            c[base + MVC_HP + high]++;
        }
    }

    /**
     * Returns the class of a motion vector component magnitude minus one
     * ({@code vp9_get_mv_class}).
     *
     * @param magnitude the magnitude minus one, non-negative
     * @return the class, from 0 to 10
     */
    static int mvClass(int magnitude) {
        if (magnitude >= 2 * 4096) {
            return 10;
        }
        var units = magnitude >> 3;
        return units == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(units);
    }
}
