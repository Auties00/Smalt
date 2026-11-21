/* Ports LibAtrac9 (C/src/structures.h) at commit efca2e3. Copyright 2018 Alex Barney; MIT License. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.atrac9;

/**
 * The decoding state of one channel of an ATRAC9 block: the values unpacked for the current frame, the scale factors
 * of the previous one, the band extension noise generator and the inverse MDCT overlap.
 *
 * <p>Instances are not thread-safe.
 */
final class Atrac9Channel {
    final Atrac9Block block;

    /**
     * The index of the channel in its block, 0 or 1.
     */
    final int index;

    /**
     * The output samples of the current frame, before rounding.
     */
    final double[] pcm = new double[256];

    final double[] spectra = new double[256];

    /**
     * The second half of the previous inverse transform, added to the next frame.
     */
    final double[] imdctPrevious = new double[256];

    /**
     * The quantization units whose spectrum this channel codes.
     */
    int codedQuantUnits;

    int scaleFactorCodingMode;

    final int[] scaleFactors = new int[31];

    final int[] scaleFactorsPrevious = new int[31];

    final int[] precisions = new int[30];

    final int[] precisionsFine = new int[30];

    final int[] precisionMask = new int[30];

    /**
     * The spectrum codebook set, 0 or 1, of each quantization unit.
     */
    final int[] codebookSet = new int[30];

    final int[] quantizedSpectra = new int[256];

    final int[] quantizedSpectraFine = new int[256];

    /**
     * The band extension mode, from 0 to 4.
     */
    int bexMode;

    int bexValueCount;

    final int[] bexValues = new int[4];

    /**
     * Whether the band extension noise generator has been seeded; it is seeded once, at its first use.
     */
    boolean rngInitialized;

    int rngA;

    int rngB;

    int rngC;

    int rngD;

    Atrac9Channel(Atrac9Block block, int index) {
        this.block = block;
        this.index = index;
    }

    /**
     * Seeds the band extension noise generator ({@code RngInit}).
     *
     * @param seed the 16-bit seed
     */
    void seedRng(int seed) {
        var start = 0x4D93 * (seed ^ (seed >> 14));
        rngA = (3 - start) & 0xFFFF;
        rngB = (2 - start) & 0xFFFF;
        rngC = (1 - start) & 0xFFFF;
        rngD = (0 - start) & 0xFFFF;
        rngInitialized = true;
    }

    /**
     * {@return the next 16-bit value of the band extension noise generator, an xorshift generator ({@code RngNext})}
     */
    int nextRng() {
        var t = (rngD ^ (rngD << 5)) & 0xFFFF;
        rngD = rngC;
        rngC = rngB;
        rngB = rngA;
        rngA = (t ^ rngA ^ ((t ^ (rngA >> 5)) >> 4)) & 0xFFFF;
        return rngA;
    }
}
