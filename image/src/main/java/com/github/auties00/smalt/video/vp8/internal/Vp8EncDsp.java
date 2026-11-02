package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the pixel kernels of the VP8 key-frame encoder: forward and inverse transforms,
 * quantization, and distortion metrics.
 *
 * <p>Every kernel works on the encoder's work buffers, whose rows are {@link #BPS} bytes apart.
 * {@link ScalarVp8EncDsp} is the reference; {@link VectorVp8EncDsp} uses the Vector API and
 * produces identical results. Implementations are stateless and thread-safe.
 */
public abstract sealed class Vp8EncDsp permits ScalarVp8EncDsp, VectorVp8EncDsp {
    /**
     * The row stride of every encoder work buffer.
     */
    public static final int BPS = 32;

    /**
     * The scalar implementation, which every JVM runs.
     */
    public static final Vp8EncDsp SCALAR = new ScalarVp8EncDsp();

    /**
     * The implementation selected by {@link Simd#WIDE}: the Vector API kernels, written for 256-bit
     * vectors, or {@link #SCALAR}.
     */
    public static final Vp8EncDsp INSTANCE = Simd.WIDE ? VectorVp8EncDsp.create() : SCALAR;

    /**
     * Creates an implementation.
     */
    Vp8EncDsp() {
    }

    /**
     * Computes the forward DCT of the difference between a 4x4 source block and its prediction.
     *
     * @param src    the source samples
     * @param srcOff the index of the source block
     * @param ref    the prediction samples
     * @param refOff the index of the prediction block
     * @param out    the coefficients, in raster order
     * @param outOff the index of the first coefficient
     */
    public abstract void fTransform(byte[] src, int srcOff, byte[] ref, int refOff, short[] out, int outOff);

    /**
     * Adds the inverse DCT of 16 coefficients to a 4x4 prediction and stores the clamped result.
     *
     * @param ref    the prediction samples
     * @param refOff the index of the prediction block
     * @param in     the dequantized coefficients, in raster order
     * @param inOff  the index of the first coefficient
     * @param dst    the reconstruction samples
     * @param dstOff the index of the reconstructed block
     */
    public abstract void iTransform(byte[] ref, int refOff, short[] in, int inOff, byte[] dst, int dstOff);

    /**
     * Returns the sum of squared differences of two blocks.
     *
     * @param a      the first samples
     * @param aOff   the index of the first block
     * @param b      the second samples
     * @param bOff   the index of the second block
     * @param width  the block width: 4, 8, or 16
     * @param height the block height
     * @return the sum of squared differences
     */
    public abstract int sse(byte[] a, int aOff, byte[] b, int bOff, int width, int height);

    /**
     * Returns the weighted spectral distortion of two 4x4 blocks: the absolute difference of
     * their weighted Hadamard-transform magnitudes, shifted right by 5.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block
     * @param b       the second samples
     * @param bOff    the index of the second block
     * @param weights the 16 frequency weights
     * @return the distortion
     */
    public abstract int tDisto4x4(byte[] a, int aOff, byte[] b, int bOff, int[] weights);

    /**
     * Quantizes 16 coefficients in zigzag order, replacing each coefficient with its dequantized
     * value.
     *
     * @param in     the coefficients in raster order, overwritten with their reconstruction
     * @param inOff  the index of the first coefficient
     * @param out    the quantized levels in zigzag order
     * @param outOff the index of the first level
     * @param m      the quantization matrix
     * @return {@code 1} when any level is nonzero, {@code 0} otherwise
     */
    public abstract int quantizeBlock(short[] in, int inOff, short[] out, int outOff, QuantMatrix m);

    /**
     * Returns the weighted sum of the absolute Hadamard-transform coefficients of a 4x4 block,
     * the per-block term whose difference {@link #tDisto4x4} measures.
     *
     * @param a       the samples
     * @param aOff    the index of the block
     * @param weights the 16 frequency weights
     * @return the weighted sum, below {@code 2^31}
     */
    public abstract int spectralSum(byte[] a, int aOff, int[] weights);

    /**
     * Codes one 4x4 candidate in a single pass: transforms the difference between the source and
     * the prediction, quantizes it, reconstructs the block, and measures the reconstruction.
     *
     * <p>The levels and the reconstruction are exactly those of {@link #fTransform} followed by
     * {@link #quantizeBlock} and {@link #iTransform}; the returned measures are {@link #sse} of
     * the source and the reconstruction and {@link #spectralSum} of the reconstruction.
     *
     * @param src       the source samples
     * @param srcOff    the index of the source block
     * @param pred      the prediction samples
     * @param predOff   the index of the prediction block
     * @param m         the quantization matrix
     * @param levels    the quantized levels destination, in zigzag order
     * @param levelsOff the index of the first level
     * @param recon     the reconstruction destination
     * @param reconOff  the index of the reconstructed block
     * @param weights   the 16 frequency weights of the spectral sum
     * @param scratch   16 coefficients of scratch space
     * @return {@code (spectralSum << 32) | (sse << 1) | nonzero}, where {@code nonzero} is
     *         {@code 1} when a level is nonzero
     */
    public abstract long evaluateBlock(byte[] src, int srcOff, byte[] pred, int predOff, QuantMatrix m,
                                       short[] levels, int levelsOff, byte[] recon, int reconOff, int[] weights,
                                       short[] scratch);

    /**
     * Returns the weighted spectral distortion of two 16x16 blocks, the sum of
     * {@link #tDisto4x4} over their 4x4 blocks.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block
     * @param b       the second samples
     * @param bOff    the index of the second block
     * @param weights the 16 frequency weights
     * @return the distortion
     */
    public int tDisto16x16(byte[] a, int aOff, byte[] b, int bOff, int[] weights) {
        var d = 0;
        for (var y = 0; y < 16 * BPS; y += 4 * BPS) {
            for (var x = 0; x < 16; x += 4) {
                d += tDisto4x4(a, aOff + x + y, b, bOff + x + y, weights);
            }
        }
        return d;
    }

    /**
     * Returns the index of the last nonzero level of a block.
     *
     * @param levels the quantized levels
     * @param off the index of the first of the 16 levels
     * @return the index within the block, or {@code -1} when every level is zero
     */
    public abstract int lastNonZero(short[] levels, int off);

    /**
     * Counts the nonzero levels of consecutive blocks, not counting the first level of each block,
     * and stops counting once the count exceeds a limit.
     *
     * @param levels the quantized levels
     * @param off the index of the first level of the first block
     * @param blocks the number of 16-level blocks
     * @param limit the count above which counting may stop
     * @return the number of nonzero levels at block positions 1 to 15 if it is at most
     *         {@code limit}, otherwise a number above {@code limit}
     */
    public abstract int countNonZeroAc(short[] levels, int off, int blocks, int limit);
}
