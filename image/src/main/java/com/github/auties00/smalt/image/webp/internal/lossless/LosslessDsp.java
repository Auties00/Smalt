package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Provides the pixel kernels of the WebP lossless codec: the inverse transforms applied by the
 * decoder, their forward counterparts used by the encoder, the predictor residuals and entropy
 * estimates the encoder's searches score, and the ARGB to RGBA packing.
 *
 * <p>Pixels are packed ARGB {@code int}s ({@code 0xAARRGGBB}). Every method works on array ranges
 * and allows the source and destination to be the same array at the same position (in place).
 *
 * @implSpec
 * Implementations must produce exactly the output of {@link ScalarLosslessDsp}, the reference.
 */
public sealed interface LosslessDsp permits ScalarLosslessDsp, VectorLosslessDsp {
    /**
     * The implementation selected for this JVM: vectorized when {@link Simd#ENABLED}, scalar
     * otherwise.
     */
    LosslessDsp INSTANCE = Simd.ENABLED ? VectorLosslessDsp.create() : new ScalarLosslessDsp();

    /**
     * The length of the lane scratch the cross-color histogram kernels take: the {@code int} lanes of the widest
     * vector the Vector API allows (2048 bits, the SVE maximum).
     */
    int LANE_SCRATCH = 64;

    /**
     * Adds the green channel to the red and blue channels (the inverse subtract-green transform).
     *
     * @param src    the source pixels
     * @param srcPos the index of the first source pixel
     * @param dst    the destination pixels
     * @param dstPos the index of the first destination pixel
     * @param count  the number of pixels
     */
    void addGreenToBlueAndRed(int[] src, int srcPos, int[] dst, int dstPos, int count);

    /**
     * Subtracts the green channel from the red and blue channels (the forward subtract-green
     * transform), in place.
     *
     * @param data  the pixels
     * @param pos   the index of the first pixel
     * @param count the number of pixels
     */
    void subtractGreenFromBlueAndRed(int[] data, int pos, int count);

    /**
     * Applies the inverse cross-color transform with one set of multipliers.
     *
     * @param code   the multipliers packed as {@code red_to_blue << 16 | green_to_blue << 8 |
     *               green_to_red}
     * @param src    the source pixels
     * @param srcPos the index of the first source pixel
     * @param dst    the destination pixels
     * @param dstPos the index of the first destination pixel
     * @param count  the number of pixels
     */
    void transformColorInverse(int code, int[] src, int srcPos, int[] dst, int dstPos, int count);

    /**
     * Applies the forward cross-color transform with one set of multipliers, in place.
     *
     * @param code  the multipliers packed as {@code red_to_blue << 16 | green_to_blue << 8 |
     *              green_to_red}
     * @param data  the pixels
     * @param pos   the index of the first pixel
     * @param count the number of pixels
     */
    void transformColor(int code, int[] data, int pos, int count);

    /**
     * Adds a spatial prediction to residuals (the inverse predictor transform) for pixels of one
     * row that are not the first pixel of the row.
     *
     * <p>The row above the output starts {@code width} pixels before {@code outPos} in
     * {@code out}; the left neighbor of the first pixel is {@code out[outPos - 1]}. The top-right
     * neighbor of the last pixel of a row is the first pixel of the current row, as the
     * specification requires.
     *
     * @param mode   the predictor mode, from 0 to 15; 14 and 15 behave like 0
     * @param in     the residuals
     * @param inPos  the index of the first residual
     * @param out    the reconstructed pixels
     * @param outPos the index of the first output pixel, never the first pixel of a row
     * @param count  the number of pixels
     * @param width  the row stride of {@code out}
     */
    void predictorAdd(int mode, int[] in, int inPos, int[] out, int outPos, int count, int width);

    /**
     * Computes the residuals of a run of pixels that each have a left and an upper neighbor
     * under one spatial predictor: each output is the channel-wise difference, modulo 256, of
     * the pixel and its prediction from the left, top-left, top and top-right neighbors.
     *
     * <p>The pixel {@code current[curPos + i]} has its left neighbor at
     * {@code current[curPos + i - 1]} and its top-left, top and top-right neighbors at
     * {@code upper[upperPos + i - 1]}, {@code upper[upperPos + i]} and
     * {@code upper[upperPos + i + 1]}; the two arrays may be the same image with
     * {@code upperPos = curPos - width}. The output may not overlap the inputs.
     *
     * @param mode     the predictor mode, from 0 to 13
     * @param current  the pixels
     * @param curPos   the index of the first pixel, at least 1
     * @param upper    the row above
     * @param upperPos the index of the first pixel's top neighbor, at least 1
     * @param count    the number of pixels
     * @param out      the residuals
     * @param outPos   the index of the first residual
     */
    void predictorResiduals(int mode, int[] current, int curPos, int[] upper, int upperPos, int count, int[] out, int outPos);

    /**
     * Converts ARGB pixels to packed RGBA bytes.
     *
     * @param src    the ARGB pixels
     * @param srcPos the index of the first pixel
     * @param dst    the RGBA bytes
     * @param dstPos the index of the first byte written
     * @param count  the number of pixels
     */
    void argbToRgba(int[] src, int srcPos, byte[] dst, int dstPos, int count);

    /**
     * Returns the entropy of a 256-symbol distribution {@code X} plus the entropy of the sum
     * {@code X + Y}, the cost the encoder's transform searches minimize.
     *
     * @param x    the first counts
     * @param xOff the index of the first count of {@code x}
     * @param y    the second counts
     * @param yOff the index of the first count of {@code y}
     * @return the fixed-point entropy, as {@link EntropyMath#combinedShannonEntropy} computes it
     */
    long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff);

    /**
     * Returns {@link #combinedShannonEntropy(int[], int, int[], int)} given the sum of
     * {@code Y} and the sum of {@link EntropyMath#fastSLog2} over {@code Y}, visiting only the
     * nonzero counts of {@code X}.
     *
     * <p>The symbols where {@code X} is zero contribute {@code fastSLog2(Y[i])} to the result;
     * starting from their total over every symbol and correcting the symbols where {@code X} is
     * nonzero gives the same integer, while a search scoring many distributions against one
     * {@code Y} computes the {@code Y} terms once.
     *
     * @param x        the first counts
     * @param xOff     the index of the first count of {@code x}
     * @param y        the second counts
     * @param yOff     the index of the first count of {@code y}
     * @param sumY     the sum of the 256 counts of {@code y}
     * @param entropyY the sum of {@link EntropyMath#fastSLog2} over the 256 counts of {@code y}
     * @return the fixed-point entropy, equal to the four-argument overload's
     */
    long combinedShannonEntropy(int[] x, int xOff, int[] y, int yOff, int sumY, long entropyY);

    /**
     * Collects the streak statistics of a population, or of the sum of two populations, as
     * {@link EntropyMath#entropyUnrefined} does: every run of equal counts is reported to
     * {@link EntropyMath.Stats#streak} in increasing order, then the entropy is finalized.
     *
     * @param x      the first counts
     * @param y      the second counts, or {@code null} for {@code x} alone
     * @param length the number of symbols, at least 1
     * @param stats  the statistics, reset first
     */
    void entropyUnrefined(int[] x, int[] y, int length, EntropyMath.Stats stats);

    /**
     * Accumulates the histogram of the red channel of a tile after the forward cross-color
     * transform with one green-to-red multiplier.
     *
     * @param argb       the image
     * @param pos        the index of the tile's first pixel
     * @param stride     the image width
     * @param tileWidth  the tile width
     * @param tileHeight the tile height
     * @param greenToRed the multiplier, a signed 8-bit value
     * @param histo      the 256-entry histogram to increment
     * @param scratch    a scratch of at least {@link #LANE_SCRATCH} entries, whose contents are unspecified on return
     */
    void collectColorRedTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToRed,
                                   int[] histo, int[] scratch);

    /**
     * Accumulates the histogram of the blue channel of a tile after the forward cross-color
     * transform with one pair of green-to-blue and red-to-blue multipliers.
     *
     * @param argb        the image
     * @param pos         the index of the tile's first pixel
     * @param stride      the image width
     * @param tileWidth   the tile width
     * @param tileHeight  the tile height
     * @param greenToBlue the green-to-blue multiplier, a signed 8-bit value
     * @param redToBlue   the red-to-blue multiplier, a signed 8-bit value
     * @param histo       the 256-entry histogram to increment
     * @param scratch     a scratch of at least {@link #LANE_SCRATCH} entries, whose contents are unspecified on
     *                    return
     */
    void collectColorBlueTransforms(int[] argb, int pos, int stride, int tileWidth, int tileHeight, int greenToBlue,
                                    int redToBlue, int[] histo, int[] scratch);
}
