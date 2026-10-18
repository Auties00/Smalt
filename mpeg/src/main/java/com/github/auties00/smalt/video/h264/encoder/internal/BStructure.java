package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * The coding order and reference roles of the B pictures between two anchor pictures.
 *
 * <p>Without a pyramid the B pictures follow the anchor in display order and none of them is
 * referenced. With a pyramid the interval between the two anchors is split recursively: when
 * an interval holds two or more pictures, the middle one is coded first as a B picture that later
 * pictures of the interval reference, and the two halves are split in turn; an interval of one
 * picture codes it as an unreferenced B picture.
 */
final class BStructure {
    /**
     * The display offsets from the previous anchor (1 for the first B picture) in coding order.
     */
    final int[] order;

    /**
     * For each entry of {@link #order}, whether later pictures reference the B picture.
     */
    final boolean[] reference;

    /**
     * The number of B pictures later pictures reference.
     */
    final int referenced;

    /**
     * The largest number of pictures that precede a picture in coding order and follow it in
     * display order, anchor included.
     */
    final int reorder;

    /**
     * Builds the structure of {@code count} consecutive B pictures.
     *
     * @param count the number of B pictures between two anchors
     * @param pyramid whether B pictures may be referenced
     */
    BStructure(int count, boolean pyramid) {
        var positions = new ArrayList<Integer>();
        var flags = new ArrayList<Boolean>();
        if (pyramid) {
            split(0, count + 1, positions, flags);
        } else {
            for (var i = 1; i <= count; i++) {
                positions.add(i);
                flags.add(false);
            }
        }
        this.order = new int[count];
        this.reference = new boolean[count];
        var refs = 0;
        for (var i = 0; i < count; i++) {
            order[i] = positions.get(i);
            reference[i] = flags.get(i);
            if (reference[i]) {
                refs++;
            }
        }
        this.referenced = refs;
        var worst = count > 0 ? 1 : 0;
        for (var i = 0; i < count; i++) {
            var ahead = 1;
            for (var j = 0; j < i; j++) {
                if (order[j] > order[i]) {
                    ahead++;
                }
            }
            worst = Math.max(worst, ahead);
        }
        this.reorder = worst;
    }

    /**
     * Splits the open interval between two coded positions.
     *
     * @param low the display position of the earlier bound
     * @param high the display position of the later bound
     * @param positions receives the positions in coding order
     * @param flags receives whether each position is referenced
     */
    private static void split(int low, int high, List<Integer> positions, List<Boolean> flags) {
        var inside = high - low - 1;
        if (inside <= 0) {
            return;
        }
        if (inside == 1) {
            positions.add(low + 1);
            flags.add(false);
            return;
        }
        var middle = (low + high) >> 1;
        positions.add(middle);
        flags.add(true);
        split(low, middle, positions, flags);
        split(middle, high, positions, flags);
    }
}
