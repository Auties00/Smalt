package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the mode information of the block being decoded.
 *
 * <p>Motion vectors are packed as {@link RefMvs} describes.
 *
 * @implNote This implementation mirrors dav1d's {@code Av1Block} ({@code src/levels.h}) without its
 *           unions: intra and inter fields are separate.
 */
public final class Av1Block {
    /**
     * The block level ({@code BlockLevel}).
     */
    public int bl;

    /**
     * The block size ({@code BlockSize}).
     */
    public int bs;

    /**
     * The partition the block came from ({@code BlockPartition}).
     */
    public int bp;

    /**
     * Whether the block is intra coded (including intra block copy in intra frames).
     */
    public boolean intra;

    /**
     * The segment identifier.
     */
    public int segId;

    /**
     * Whether the block uses skip mode.
     */
    public boolean skipMode;

    /**
     * Whether the block has no residual.
     */
    public boolean skip;

    /**
     * The chroma transform size ({@code RectTxfmSize}).
     */
    public int uvtx;

    /**
     * The luma intra mode.
     */
    public int yMode;

    /**
     * The chroma intra mode.
     */
    public int uvMode;

    /**
     * The luma transform size of an intra block.
     */
    public int tx;

    /**
     * The luma ({@code [0]}) and chroma ({@code [1]}) palette sizes.
     */
    public final int[] palSz = new int[2];

    /**
     * The luma angle delta, or the filter intra mode when {@link #yMode} is the filter mode.
     */
    public int yAngle;

    /**
     * The chroma angle delta.
     */
    public int uvAngle;

    /**
     * The chroma-from-luma scaling factors of the two chroma planes.
     */
    public final int[] cflAlpha = new int[2];

    /**
     * The motion vectors of the two references.
     */
    public final int[] mv = new int[2];

    /**
     * The wedge index.
     */
    public int wedgeIdx;

    /**
     * The mask sign of masked compound prediction.
     */
    public int maskSign;

    /**
     * The inter-intra mode.
     */
    public int interintraMode;

    /**
     * The compound type.
     */
    public int compType;

    /**
     * The inter mode ({@code InterPredMode} or {@code CompInterPredMode}).
     */
    public int interMode;

    /**
     * The motion mode ({@code MotionMode}).
     */
    public int motionMode;

    /**
     * The index of the selected reference motion vector candidate.
     */
    public int drlIdx;

    /**
     * The two references, the second {@code -1} for single prediction.
     */
    public final int[] ref = new int[2];

    /**
     * The largest luma transform size of an inter block.
     */
    public int maxYtx;

    /**
     * The 2-D interpolation filter ({@code Filter2d}).
     */
    public int filter2d;

    /**
     * The inter-intra type ({@code InterIntraType}).
     */
    public int interintraType;

    /**
     * The transform split flags of the first depth.
     */
    public int txSplit0;

    /**
     * The transform split flags of the second depth.
     */
    public int txSplit1;

    /**
     * Creates an empty block.
     */
    public Av1Block() {
    }
}
