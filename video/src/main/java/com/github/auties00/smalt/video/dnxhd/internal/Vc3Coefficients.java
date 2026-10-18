package com.github.auties00.smalt.video.dnxhd.internal;

import com.github.auties00.smalt.video.intra.internal.BlockKernel;

/**
 * Holds the DCT coefficients of every macroblock of a VC-3 coding unit being encoded, in batches
 * of {@link BlockKernel#LANES} blocks, together with the coding parameters every scan line shares:
 * either the video macroblocks or the DCT-coded alpha macroblocks of the coding unit, and for
 * compression ID 1260 the macroblock mode (frame or field) chosen for each macroblock.
 *
 * <p>Each scan line is written by one thread and read by the same or another thread after the
 * transform pass has completed.
 */
public final class Vc3Coefficients {
    /**
     * The compression ID.
     */
    private final Vc3Compression compression;

    /**
     * The macroblock layout.
     */
    private final Vc3Layout layout;

    /**
     * The sample bit depth.
     */
    private final int depth;

    /**
     * The coding unit width in macroblocks.
     */
    private final int widthInMb;

    /**
     * The coding unit height in macroblocks.
     */
    private final int heightInMb;

    /**
     * Whether the store holds alpha macroblocks.
     */
    private final boolean alpha;

    /**
     * The quantization weights of the first component.
     */
    private final int[] lumaWeights;

    /**
     * The quantization weights of the other components.
     */
    private final int[] chromaWeights;

    /**
     * The batches, macroblock by macroblock, scan line by scan line.
     */
    private final float[][] batches;

    /**
     * Whether each macroblock is coded in field macroblock mode, scan line by scan line, or
     * {@code null} when the compression ID has no such mode.
     */
    private final boolean[] fieldModes;

    /**
     * The frame row of the coding unit's first row.
     */
    private int rowOffset;

    /**
     * The distance in frame rows between coding unit rows.
     */
    private int rowStep = 1;

    /**
     * Allocates a store.
     *
     * @param compression the compression ID
     * @param layout the macroblock layout
     * @param depth the sample bit depth
     * @param widthInMb the width in macroblocks
     * @param heightInMb the height in macroblocks
     * @param alpha whether the store holds alpha macroblocks, whose layout is
     * {@link Vc3Layout#ALPHA} and whose weights are the alpha weights of the
     * compression ID
     */
    public Vc3Coefficients(Vc3Compression compression, Vc3Layout layout, int depth, int widthInMb, int heightInMb,
                           boolean alpha) {
        this.compression = compression;
        this.layout = layout;
        this.depth = depth;
        this.widthInMb = widthInMb;
        this.heightInMb = heightInMb;
        this.alpha = alpha;
        this.lumaWeights = Vc3Tables.weights(alpha ? compression.alphaWeights() : compression.weights(), false);
        this.chromaWeights = compression.chroma444() || alpha ? lumaWeights
                : Vc3Tables.weights(compression.weights(), true);
        this.batches = new float[widthInMb * heightInMb * batchesPerMacroblock()][BlockKernel.BATCH_LENGTH];
        this.fieldModes = compression.cid() == 1260 ? new boolean[widthInMb * heightInMb] : null;
    }

    /**
     * Returns the number of batches of a macroblock.
     *
     * @return 1 or 2
     */
    private int batchesPerMacroblock() {
        return (layout.blocks() + BlockKernel.LANES - 1) / BlockKernel.LANES;
    }

    /**
     * Returns one batch of a macroblock.
     *
     * @param row the scan line
     * @param mb the macroblock
     * @param batch the batch, 0 or 1
     * @return the batch, shared
     */
    public float[] batch(int row, int mb, int batch) {
        return batches[(row * widthInMb + mb) * batchesPerMacroblock() + batch];
    }

    /**
     * Returns whether the compression ID chooses between frame and field macroblock mode for every
     * macroblock (ID 1260, ST 2019-1 clause 6.3).
     *
     * @return {@code true} for adaptive macroblocks
     */
    public boolean adaptive() {
        return fieldModes != null;
    }

    /**
     * Returns whether a macroblock is coded in field macroblock mode.
     *
     * @param row the scan line
     * @param mb the macroblock
     * @return {@code true} for field mode, {@code false} for frame mode or a non-adaptive ID
     */
    public boolean fieldMode(int row, int mb) {
        return fieldModes != null && fieldModes[row * widthInMb + mb];
    }

    /**
     * Records the mode of a macroblock of an adaptive compression ID.
     *
     * @param row the scan line
     * @param mb the macroblock
     * @param field whether the macroblock is coded in field macroblock mode
     */
    public void fieldMode(int row, int mb, boolean field) {
        fieldModes[row * widthInMb + mb] = field;
    }

    /**
     * Returns whether the store holds alpha macroblocks.
     *
     * @return {@code true} for alpha
     */
    public boolean alpha() {
        return alpha;
    }

    /**
     * Selects the frame rows the coding unit covers.
     *
     * @param rowOffset the frame row of the first row
     * @param rowStep the distance in frame rows between rows
     */
    public void field(int rowOffset, int rowStep) {
        this.rowOffset = rowOffset;
        this.rowStep = rowStep;
    }

    /**
     * {@return the compression ID whose tables and weights the coefficients use}
     */
    public Vc3Compression compression() {
        return compression;
    }

    /**
     * Returns the macroblock layout.
     *
     * @return the layout
     */
    public Vc3Layout layout() {
        return layout;
    }

    /**
     * Returns the sample bit depth.
     *
     * @return the depth
     */
    public int depth() {
        return depth;
    }

    /**
     * Returns the width in macroblocks.
     *
     * @return the width
     */
    public int widthInMb() {
        return widthInMb;
    }

    /**
     * Returns the height in macroblocks.
     *
     * @return the height
     */
    public int heightInMb() {
        return heightInMb;
    }

    /**
     * Returns the quantization weights of the first component.
     *
     * @return the weights, shared
     */
    public int[] lumaWeights() {
        return lumaWeights;
    }

    /**
     * Returns the quantization weights of the other components.
     *
     * @return the weights, shared
     */
    public int[] chromaWeights() {
        return chromaWeights;
    }

    /**
     * Returns the frame row of the coding unit's first row.
     *
     * @return 1 for a second field, 0 otherwise
     */
    public int rowOffset() {
        return rowOffset;
    }

    /**
     * Returns the distance in frame rows between coding unit rows.
     *
     * @return 2 for a field, 1 otherwise
     */
    public int rowStep() {
        return rowStep;
    }
}
