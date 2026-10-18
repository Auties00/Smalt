package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Holds one code-block of a tile being encoded: its coded bytes, its coding passes, the passes
 * each quality layer includes, and the packet header state of its precinct's packets.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_cblk_enc_t},
 * {@code opj_tcd_pass_t} and {@code opj_tcd_layer_t}, with the pass and layer fields held in
 * parallel arrays.
 */
final class EncodedBlock {
    /**
     * The left edge in sub-band coordinates.
     */
    final int x0;

    /**
     * The top edge in sub-band coordinates.
     */
    final int y0;

    /**
     * The right edge in sub-band coordinates (exclusive).
     */
    final int x1;

    /**
     * The bottom edge in sub-band coordinates (exclusive).
     */
    final int y1;

    /**
     * The number of magnitude bit-planes coded; zero or negative when the block is all zero.
     */
    int bitPlanes;

    /**
     * The number of coding passes.
     */
    int totalPasses;

    /**
     * The coded bytes of every pass.
     */
    byte[] data = new byte[0];

    /**
     * The cumulative byte count after each pass.
     */
    int[] passRate = new int[0];

    /**
     * The cumulative weighted distortion reduction after each pass.
     */
    double[] passDistortion = new double[0];

    /**
     * Whether each pass ends with a termination of the coder.
     */
    boolean[] passTerminated = new boolean[0];

    /**
     * The number of passes each layer adds.
     */
    int[] layerPasses = new int[0];

    /**
     * The number of bytes each layer adds.
     */
    int[] layerLength = new int[0];

    /**
     * The offset in {@link #data} of each layer's bytes.
     */
    int[] layerOffset = new int[0];

    /**
     * The distortion reduction each layer adds.
     */
    double[] layerDistortion = new double[0];

    /**
     * The number of passes the layers allocated so far include.
     */
    int passesInLayers;

    /**
     * The number of passes the packets written so far include.
     */
    int writtenPasses;

    /**
     * The number of bits of the next length indicator, before the pass count adjustment.
     */
    int lengthBits;

    /**
     * Constructs a code-block.
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge
     * @param y1 the bottom edge
     */
    EncodedBlock(int x0, int y0, int x1, int y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /**
     * Makes room for a number of passes.
     *
     * @param passes the largest number of passes
     */
    void ensurePasses(int passes) {
        if (passRate.length < passes) {
            passRate = Arrays.copyOf(passRate, passes);
            passDistortion = Arrays.copyOf(passDistortion, passes);
            passTerminated = Arrays.copyOf(passTerminated, passes);
        }
    }

    /**
     * Makes room for a number of layers.
     *
     * @param layers the number of layers
     */
    void ensureLayers(int layers) {
        if (layerPasses.length < layers) {
            layerPasses = new int[layers];
            layerLength = new int[layers];
            layerOffset = new int[layers];
            layerDistortion = new double[layers];
        }
    }

    /**
     * Returns the number of bytes of a pass.
     *
     * @param pass the pass
     * @return the bytes it adds to the previous passes
     */
    int passLength(int pass) {
        return passRate[pass] - (pass == 0 ? 0 : passRate[pass - 1]);
    }
}
