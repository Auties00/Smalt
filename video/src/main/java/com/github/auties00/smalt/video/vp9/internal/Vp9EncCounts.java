package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Counts the symbols an encoder codes in a frame, from which the compressed header chooses its
 * probability updates.
 *
 * <p>Every count lives in one flat array at the offset the constants of this class name; each
 * worker of a frame counts into its own instance, and the frame adds them up, so the totals do
 * not depend on the threading.
 *
 * @implNote This implementation follows the layout of libvpx's {@code FRAME_COUNTS} and the
 *           full-token coefficient counts of {@code RD_COUNTS} ({@code vp9/common/vp9_entropymode.h},
 *           {@code vp9/encoder/vp9_encoder.h}, BSD-3).
 */
final class Vp9EncCounts {
    /**
     * The token counts of the coefficients, {@code [tx size 4][plane type 2][reference 2][band 6]
     * [context 6][token 12]}.
     */
    static final int COEF = 0;

    /**
     * The number of times the end-of-block token could have been coded,
     * {@code [tx size 4][plane type 2][reference 2][band 6][context 6]}.
     */
    static final int EOB_BRANCH = COEF + 4 * 2 * 2 * 6 * 6 * 12;

    /**
     * The partition counts, {@code [context 16][partition 4]}.
     */
    static final int PARTITION = EOB_BRANCH + 4 * 2 * 2 * 6 * 6;

    /**
     * The skip flag counts, {@code [context 3][flag 2]}.
     */
    static final int SKIP = PARTITION + 16 * 4;

    /**
     * The intra/inter counts, {@code [context 4][is inter 2]}.
     */
    static final int INTRA_INTER = SKIP + 3 * 2;

    /**
     * The single/compound counts, {@code [context 5][is compound 2]}.
     */
    static final int COMP_INTER = INTRA_INTER + 4 * 2;

    /**
     * The single reference counts, {@code [context 5][node 2][bit 2]}.
     */
    static final int SINGLE_REF = COMP_INTER + 5 * 2;

    /**
     * The compound reference counts, {@code [context 5][bit 2]}.
     */
    static final int COMP_REF = SINGLE_REF + 5 * 2 * 2;

    /**
     * The inter mode counts, {@code [context 7][mode offset 4]}, indexed by the mode minus
     * {@code NEARESTMV}.
     */
    static final int INTER_MODE = COMP_REF + 5 * 2;

    /**
     * The interpolation filter counts, {@code [context 4][filter 3]}.
     */
    static final int SWITCHABLE_INTERP = INTER_MODE + 7 * 4;

    /**
     * The luma intra mode counts of inter frames, {@code [size group 4][mode 10]}.
     */
    static final int Y_MODE = SWITCHABLE_INTERP + 4 * 3;

    /**
     * The 8x8 transform size counts, {@code [context 2][size 2]}.
     */
    static final int TX_8X8 = Y_MODE + 4 * 10;

    /**
     * The 16x16 transform size counts, {@code [context 2][size 3]}.
     */
    static final int TX_16X16 = TX_8X8 + 2 * 2;

    /**
     * The 32x32 transform size counts, {@code [context 2][size 4]}.
     */
    static final int TX_32X32 = TX_16X16 + 2 * 3;

    /**
     * The number of blocks coded with each transform size, luma and chroma, {@code [size 4]}.
     */
    static final int TX_TOTALS = TX_32X32 + 2 * 4;

    /**
     * The motion vector joint counts, {@code [joint 4]}.
     */
    static final int MV_JOINTS = TX_TOTALS + 4;

    /**
     * The motion vector component counts, {@code [component 2][MV_COMPONENT_SIZE]}; see the
     * {@code MV_*} offsets for the layout of one component.
     */
    static final int MV_COMPS = MV_JOINTS + 4;

    /**
     * The sign counts of a component, {@code [sign 2]}.
     */
    static final int MV_SIGN = 0;

    /**
     * The class counts of a component, {@code [class 11]}.
     */
    static final int MV_CLASSES = MV_SIGN + 2;

    /**
     * The class 0 integer counts of a component, {@code [bit 2]}.
     */
    static final int MV_CLASS0 = MV_CLASSES + 11;

    /**
     * The integer bit counts of a component, {@code [bit 10][value 2]}.
     */
    static final int MV_BITS = MV_CLASS0 + 2;

    /**
     * The class 0 fractional counts of a component, {@code [integer 2][fraction 4]}.
     */
    static final int MV_CLASS0_FP = MV_BITS + 10 * 2;

    /**
     * The fractional counts of a component, {@code [fraction 4]}.
     */
    static final int MV_FP = MV_CLASS0_FP + 2 * 4;

    /**
     * The class 0 high precision counts of a component, {@code [bit 2]}.
     */
    static final int MV_CLASS0_HP = MV_FP + 4;

    /**
     * The high precision counts of a component, {@code [bit 2]}.
     */
    static final int MV_HP = MV_CLASS0_HP + 2;

    /**
     * The number of counts of one motion vector component.
     */
    static final int MV_COMPONENT_SIZE = MV_HP + 2;

    /**
     * The number of counts.
     */
    static final int SIZE = MV_COMPS + 2 * MV_COMPONENT_SIZE;

    /**
     * The counts.
     */
    final int[] counts = new int[SIZE];

    /**
     * Creates zero counts.
     */
    Vp9EncCounts() {
    }

    /**
     * Resets every count to zero.
     */
    void clear() {
        Arrays.fill(counts, 0);
    }

    /**
     * Adds another set of counts to this one.
     *
     * @param other the counts to add
     */
    void add(Vp9EncCounts other) {
        var a = counts;
        var b = other.counts;
        for (var i = 0; i < SIZE; i++) {
            a[i] += b[i];
        }
    }

    /**
     * Counts a coded motion vector difference with high precision counts always kept
     * ({@code vp9_inc_mv}).
     *
     * @param row the row difference, in 1/8 pixel
     * @param col the column difference, in 1/8 pixel
     */
    void countMv(int row, int col) {
        var joint = (row != 0 ? 2 : 0) | (col != 0 ? 1 : 0);
        counts[MV_JOINTS + joint]++;
        if (row != 0) {
            countComponent(row, MV_COMPS);
        }
        if (col != 0) {
            countComponent(col, MV_COMPS + MV_COMPONENT_SIZE);
        }
    }

    /**
     * Counts one nonzero component ({@code inc_mv_component}).
     *
     * @param v    the component
     * @param base the offset of the component's counts
     */
    private void countComponent(int v, int base) {
        var c = counts;
        var s = v < 0 ? 1 : 0;
        c[base + MV_SIGN + s]++;
        var z = (s != 0 ? -v : v) - 1;
        var mvClass = Vp9EncMv.mvClass(z);
        var o = z - Vp9EncMv.classBase(mvClass);
        c[base + MV_CLASSES + mvClass]++;
        var d = o >> 3;
        var f = (o >> 1) & 3;
        var e = o & 1;
        if (mvClass == 0) {
            c[base + MV_CLASS0 + d]++;
            c[base + MV_CLASS0_FP + d * 4 + f]++;
            c[base + MV_CLASS0_HP + e]++;
        } else {
            var bits = mvClass;
            for (var i = 0; i < bits; i++) {
                c[base + MV_BITS + i * 2 + ((d >> i) & 1)]++;
            }
            c[base + MV_FP + f]++;
            c[base + MV_HP + e]++;
        }
    }
}
