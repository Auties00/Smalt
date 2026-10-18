package com.github.auties00.smalt.video.av1.internal;

import java.util.Arrays;

/**
 * Holds the above or left context of one 128-sample stretch of blocks: the per-4x4-column (or row)
 * values that symbol contexts and later blocks read.
 *
 * <p>Two-dimensional C arrays are flattened as {@code [index][32]}: {@link #ccoef}, {@link #ref} and
 * {@link #filter} hold their two rows at offsets 0 and 32.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation mirrors dav1d's {@code BlockContext} ({@code src/env.h}) and its
 * {@code reset_context} ({@code src/decode.c}).
 */
public final class BlockContext {
    /**
     * The luma intra mode, or the inter mode, of each column.
     */
    public final byte[] mode = new byte[32];

    /**
     * The luma coefficient context of each column: cumulative level in bits 0 to 5, DC sign class
     * in bits 6 and 7.
     */
    public final byte[] lcoef = new byte[32];

    /**
     * The coefficient contexts of the two chroma planes.
     */
    public final byte[] ccoef = new byte[64];

    /**
     * The temporal segment prediction flag of each column.
     */
    public final byte[] segPred = new byte[32];

    /**
     * The skip flag of each column.
     */
    public final byte[] skip = new byte[32];

    /**
     * The skip mode flag of each column.
     */
    public final byte[] skipMode = new byte[32];

    /**
     * The intra flag of each column.
     */
    public final byte[] intra = new byte[32];

    /**
     * The compound type of each column.
     */
    public final byte[] compType = new byte[32];

    /**
     * The two references of each column, {@code -1} for intra.
     */
    public final byte[] ref = new byte[64];

    /**
     * The interpolation filters of each column (vertical then horizontal), 3 when unset.
     */
    public final byte[] filter = new byte[64];

    /**
     * The intra transform size context of each column.
     */
    public final byte[] txIntra = new byte[32];

    /**
     * The transform size of each column.
     */
    public final byte[] tx = new byte[32];

    /**
     * The luma loop filter transform size of each column.
     */
    public final byte[] txLpfY = new byte[32];

    /**
     * The chroma loop filter transform size of each column.
     */
    public final byte[] txLpfUv = new byte[32];

    /**
     * The partition context of each 8x8 column.
     */
    public final byte[] partition = new byte[16];

    /**
     * The chroma intra mode of each column.
     */
    public final byte[] uvmode = new byte[32];

    /**
     * The luma palette size of each column.
     */
    public final byte[] palSz = new byte[32];

    /**
     * Constructs a zeroed context.
     */
    public BlockContext() {
    }

    /**
     * Returns the smooth-edge flag the luma mode of a neighbor implies ({@code sm_flag}): set when the
     * neighbor is intra coded with one of the three smooth modes.
     *
     * @param idx the column (or row) of the neighbor, from 0 to 31
     * @return {@link IpredDsp#ANGLE_SMOOTH_EDGE_FLAG} or 0
     */
    public int smFlag(int idx) {
        if (intra[idx] == 0) {
            return 0;
        }
        return smooth(mode[idx]);
    }

    /**
     * Returns the smooth-edge flag the chroma mode of a neighbor implies ({@code sm_uv_flag}): set when
     * its chroma mode is one of the three smooth modes.
     *
     * @param idx the column (or row) of the neighbor, from 0 to 31
     * @return {@link IpredDsp#ANGLE_SMOOTH_EDGE_FLAG} or 0
     */
    public int smUvFlag(int idx) {
        return smooth(uvmode[idx]);
    }

    private static int smooth(int m) {
        return m == IpredDsp.SMOOTH_PRED || m == IpredDsp.SMOOTH_H_PRED || m == IpredDsp.SMOOTH_V_PRED
                ? IpredDsp.ANGLE_SMOOTH_EDGE_FLAG : 0;
    }

    /**
     * Resets the context at the start of a tile row ({@code reset_context}).
     *
     * @param keyframe whether the frame is a key or intra-only frame
     */
    public void reset(boolean keyframe) {
        Arrays.fill(intra, (byte) (keyframe ? 1 : 0));
        Arrays.fill(uvmode, (byte) 0);
        if (keyframe) {
            Arrays.fill(mode, (byte) 0);
        }
        Arrays.fill(partition, (byte) 0);
        Arrays.fill(skip, (byte) 0);
        Arrays.fill(skipMode, (byte) 0);
        Arrays.fill(txLpfY, (byte) 2);
        Arrays.fill(txLpfUv, (byte) 1);
        Arrays.fill(txIntra, (byte) -1);
        Arrays.fill(tx, (byte) 4);
        if (!keyframe) {
            Arrays.fill(ref, (byte) -1);
            Arrays.fill(compType, (byte) 0);
            Arrays.fill(mode, (byte) 0);
        }
        Arrays.fill(lcoef, (byte) 0x40);
        Arrays.fill(ccoef, (byte) 0x40);
        Arrays.fill(filter, (byte) 3);
        Arrays.fill(segPred, (byte) 0);
        Arrays.fill(palSz, (byte) 0);
    }
}
