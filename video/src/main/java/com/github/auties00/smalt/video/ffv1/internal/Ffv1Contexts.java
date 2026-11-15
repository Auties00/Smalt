package com.github.auties00.smalt.video.ffv1.internal;

import java.util.Arrays;

/**
 * The adaptive context states of one slice position across frames: for each of the three plane groups (luma,
 * chroma, transparency) the range coder states or the Golomb Rice VLC states of every context, which a key frame
 * resets and the following frames keep adapting.
 *
 * <p>Instances are not thread-safe.
 */
public final class Ffv1Contexts {
    /**
     * The number of plane groups: luma (or Y of the RCT), chroma (Cb and Cr share their states) and transparency.
     */
    public static final int GROUPS = 3;

    final int[] quantSet = new int[GROUPS];

    final byte[][] states = new byte[GROUPS][];

    final int[][] drift = new int[GROUPS][];

    final int[][] errorSum = new int[GROUPS][];

    final int[][] bias = new int[GROUPS][];

    final int[][] count = new int[GROUPS][];

    /**
     * Whether the states hold the adaptation of a previous frame.
     */
    boolean valid;

    /**
     * Resets the states of every group to their initial values.
     *
     * @param parameters the stream parameters
     * @param quantIndices holds the quantization table set of each group
     * @param offset the index of the first group's set in {@code quantIndices}
     */
    public void reset(Ffv1Parameters parameters, int[] quantIndices, int offset) {
        for (var group = 0; group < GROUPS; group++) {
            var set = quantIndices[offset + group];
            quantSet[group] = set;
            var contexts = parameters.contextCounts[set];
            if (parameters.coderType == 0) {
                drift[group] = resize(drift[group], contexts);
                errorSum[group] = resize(errorSum[group], contexts);
                bias[group] = resize(bias[group], contexts);
                count[group] = resize(count[group], contexts);
                Arrays.fill(drift[group], 0, contexts, 0);
                Arrays.fill(errorSum[group], 0, contexts, 4);
                Arrays.fill(bias[group], 0, contexts, 0);
                Arrays.fill(count[group], 0, contexts, 1);
            } else {
                var size = contexts * Ffv1Tables.CONTEXT_SIZE;
                if (states[group] == null || states[group].length < size) {
                    states[group] = new byte[size];
                }
                var initial = parameters.initialStates == null ? null : parameters.initialStates[set];
                if (initial == null) {
                    Arrays.fill(states[group], 0, size, (byte) 128);
                } else {
                    System.arraycopy(initial, 0, states[group], 0, size);
                }
            }
        }
        valid = true;
    }

    /**
     * Returns whether the states can continue into a frame that is not a key frame.
     *
     * @param quantIndices holds the quantization table sets the frame's slice selects
     * @param offset the index of the first group's set in {@code quantIndices}
     * @return {@code true} if a previous frame left states for the same sets
     */
    public boolean continuable(int[] quantIndices, int offset) {
        return valid && Arrays.equals(quantSet, 0, GROUPS, quantIndices, offset, offset + GROUPS);
    }

    /**
     * Marks the states as unusable, after a slice failed to decode.
     */
    public void invalidate() {
        valid = false;
    }

    private static int[] resize(int[] array, int size) {
        return array == null || array.length < size ? new int[size] : array;
    }
}
