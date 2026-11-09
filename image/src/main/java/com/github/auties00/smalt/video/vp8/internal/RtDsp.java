/* Ports libvpx 1.17.0 (vpx_dsp/variance.c, vpx_dsp/sad.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the pixel kernels of the real-time VP8 encoder: the block metrics of its mode
 * decision and motion search (sums of absolute differences, one block against several candidates
 * at once, variances and bilinear sub-pixel variances) and the transform, quantization and
 * reconstruction of its residual in groups of eight 4x4 blocks.
 *
 * <p>Every metric kernel addresses its blocks as a {@code byte[]} with an offset and a row
 * stride, so it reads source macroblocks and reference frames in place. Variance kernels return
 * the sum of squared differences and the sum of differences packed into one {@code long},
 * unpacked with {@link #sse(long)} and {@link #sum(long)}. The sub-pixel variances filter the
 * first block with the two-tap bilinear filters of {@link #BILINEAR_TAPS}, rounding each pass to
 * 7 bits, before measuring it against the second block, as libvpx's motion search does. The group
 * kernels work on eight blocks at once, one block per lane: their samples are interleaved by rows
 * of 32 bytes and their coefficients stored position by position (see {@link #forwardDct8}); they
 * compute the key-frame encoder's forward transform and quantizer and the decoder's inverse
 * transform exactly. {@link ScalarRtDsp} is the reference implementation; {@link VectorRtDsp}
 * uses the Vector API and returns identical results. Implementations are stateless and
 * thread-safe; kernels that need scratch memory receive it from the caller.
 */
public abstract sealed class RtDsp permits ScalarRtDsp, VectorRtDsp {
    /**
     * The minimum size of the scratch array of the sub-pixel variance kernels: 17 rows of 16
     * intermediate samples.
     */
    public static final int SCRATCH_SIZE = 17 * 16;

    /**
     * The size of one group of eight 4x4 blocks: the bytes of its interleaved samples (four rows
     * of 32 bytes) and the ints of its coefficients (16 positions of eight blocks).
     */
    public static final int GROUP = 128;

    /**
     * The distance between consecutive zigzag positions in a levels array: the level of zigzag
     * position {@code n} of block {@code b} is at {@code n * LEVEL_STRIDE + b}, for the 25 blocks
     * of a macroblock.
     */
    public static final int LEVEL_STRIDE = 32;

    /**
     * The zigzag scan order: the raster position of each scan index.
     */
    static final int[] ZIGZAG = {0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15};

    /**
     * The largest level the encoder codes.
     */
    static final int MAX_LEVEL = 2047;

    /**
     * The scalar implementation, which every JVM runs.
     *
     * <p>Call sites the encoder reaches only a few times per frame use it whatever
     * {@link #INSTANCE} is: a Vector API kernel called that rarely stays below the JIT compiler's
     * thresholds for the C2 tier in a busy JVM, and until C2 compiles it every vector operation runs
     * as an allocating method call.
     */
    public static final RtDsp SCALAR = new ScalarRtDsp();

    /**
     * The implementation selected by {@link Simd#WIDE}: the Vector API kernels, written for 256-bit
     * vectors, or {@link #SCALAR}.
     */
    public static final RtDsp INSTANCE = Simd.WIDE ? VectorRtDsp.create() : SCALAR;

    /**
     * Holds the two taps of each bilinear sub-pixel filter, indexed by the offset in 1/8 pixel,
     * libvpx's {@code bilinear_filters}.
     */
    static final int[] BILINEAR_TAPS = {128, 0, 112, 16, 96, 32, 80, 48, 64, 64, 48, 80, 32, 96, 16, 112};

    /**
     * Creates an implementation.
     */
    RtDsp() {
    }

    /**
     * Returns the sum of squared differences packed into a variance result.
     *
     * @param packed the result of a variance kernel
     * @return the sum of squared differences
     */
    public static int sse(long packed) {
        return (int) (packed >>> 32);
    }

    /**
     * Returns the sum of differences packed into a variance result.
     *
     * @param packed the result of a variance kernel
     * @return the sum of differences
     */
    public static int sum(long packed) {
        return (int) packed;
    }

    /**
     * Returns the variance of a 16x16 difference block, libvpx's {@code vpx_variance16x16}:
     * {@code sse - sum^2 / 256}.
     *
     * @param packed the result of {@link #variance16} or {@link #subpelVariance16}
     * @return the variance
     */
    public static int variance256(long packed) {
        var sum = (long) sum(packed);
        return (int) (sse(packed) - sum * sum / 256);
    }

    /**
     * Packs a sum of squared differences and a sum of differences.
     *
     * @param sse the sum of squared differences
     * @param sum the sum of differences
     * @return the packed result
     */
    static long pack(int sse, int sum) {
        return ((long) sse << 32) | (sum & 0xFFFFFFFFL);
    }

    /**
     * Returns the sum of absolute differences of two 16x16 blocks, libvpx's
     * {@code vpx_sad16x16}.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the sum of absolute differences
     */
    public abstract int sad16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride);

    /**
     * Returns the sum of the squares of the samples of a 16x16 block and stores the sum of each
     * of its columns.
     *
     * @param a       the samples
     * @param aOff    the index of the block's top-left sample
     * @param aStride the block's row stride
     * @param columns receives the 16 column sums
     * @return the sum of the squared samples
     */
    public abstract int blockStats16(byte[] a, int aOff, int aStride, int[] columns);

    /**
     * Returns the sums of squared differences between the two 8x8 chroma blocks of a macroblock
     * and their four whole-block intra predictions, libvpx's {@code pick_intra_mbuv_mode}
     * errors.
     *
     * <p>Row {@code r} of the source holds the eight U samples at {@code srcOff + r * srcStride}
     * followed by the eight V samples. {@code edges} holds the row above (eight U then eight V
     * samples) at 0, the column to the left (eight U then eight V samples) at 16, and the U and V
     * corners above-left at 32 and 33. The predictions are the constant {@code dcU} and
     * {@code dcV}, the row above, the column to the left, and
     * {@code clamp(left + above - corner, 0, 255)}.
     *
     * @param src       the source samples
     * @param srcOff    the index of the first U sample
     * @param srcStride the source row stride
     * @param edges     the edge samples
     * @param dcU       the U DC prediction
     * @param dcV       the V DC prediction
     * @param out       receives the DC, vertical, horizontal and TrueMotion errors at 0 to 3
     */
    public abstract void chromaIntraErrors(byte[] src, int srcOff, int srcStride, byte[] edges, int dcU, int dcV,
                                           int[] out);

    /**
     * Returns the sums of absolute differences of one 16x16 block and three blocks of a second
     * plane, as three {@link #sad16} calls would.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff0   the index of the first candidate's top-left sample
     * @param bOff1   the index of the second candidate's top-left sample
     * @param bOff2   the index of the third candidate's top-left sample
     * @param bStride the second plane's row stride
     * @param out     receives the three sums
     * @param outOff  the index of the first sum
     */
    public abstract void sad16x3(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2, int bStride,
                                 int[] out, int outOff);

    /**
     * Returns the sums of absolute differences of one 16x16 block and four blocks of a second
     * plane, as four {@link #sad16} calls would, libvpx's {@code vpx_sad16x16x4d}.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff0   the index of the first candidate's top-left sample
     * @param bOff1   the index of the second candidate's top-left sample
     * @param bOff2   the index of the third candidate's top-left sample
     * @param bOff3   the index of the fourth candidate's top-left sample
     * @param bStride the second plane's row stride
     * @param out     receives the four sums
     * @param outOff  the index of the first sum
     */
    public abstract void sad16x4(byte[] a, int aOff, int aStride, byte[] b, int bOff0, int bOff1, int bOff2, int bOff3,
                                 int bStride, int[] out, int outOff);

    /**
     * Returns the sum of squared differences and the sum of differences {@code a - b} of two
     * 16x16 blocks.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the packed sums, see {@link #sse(long)} and {@link #sum(long)}
     */
    public abstract long variance16(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride);

    /**
     * Filters a 16x16 block of a reference with the bilinear sub-pixel filters and returns the
     * sums of its differences with a second block, libvpx's {@code vpx_sub_pixel_variance16x16}.
     *
     * <p>The horizontal pass filters 17 rows starting at the block, the vertical pass the 16
     * rows of its output; each pass computes {@code (s0 * t0 + s1 * t1 + 64) >> 7}. A zero offset
     * selects the identity filter of its pass. The block and one extra column and row of the
     * reference are read.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param refStride the reference row stride
     * @param xOffset   the horizontal offset in 1/8 pixel, {@code [0, 7]}
     * @param yOffset   the vertical offset in 1/8 pixel, {@code [0, 7]}
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @param scratch   a scratch array of at least {@link #SCRATCH_SIZE} elements
     * @return the packed sums of the filtered block minus the second block
     */
    public abstract long subpelVariance16(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] b,
                                          int bOff, int bStride, short[] scratch);

    /**
     * Applies the horizontal bilinear pass of {@link #subpelVariance16} to the 17 rows of a
     * reference block, writing them to a scratch array 16 shorts apart.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param refStride the reference row stride
     * @param xOffset   the horizontal offset in 1/8 pixel, {@code [1, 7]}
     * @param scratch   receives the filtered rows, at least {@link #SCRATCH_SIZE} elements
     */
    public abstract void horizontalPass16(byte[] ref, int refOff, int refStride, int xOffset, short[] scratch);

    /**
     * Applies the vertical bilinear pass of {@link #subpelVariance16} to the rows
     * {@link #horizontalPass16} filtered and returns the sums of the differences of the 16x16
     * result with a second block: with the same arguments the two passes return what
     * {@link #subpelVariance16} returns, so one horizontal pass serves every vertical offset.
     *
     * @param scratch the horizontally filtered rows
     * @param yOffset the vertical offset in 1/8 pixel, {@code [1, 7]}
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the packed sums of the filtered block minus the second block
     */
    public abstract long verticalPassVariance16(short[] scratch, int yOffset, byte[] b, int bOff, int bStride);

    /**
     * Returns the sums of the two horizontal half-pixel predictions on either side of a 16x16
     * reference block: {@code out[0]} receives
     * {@code subpelVariance16(ref, refOff - 1, refStride, 4, 0, ...)} and {@code out[1]}
     * {@code subpelVariance16(ref, refOff, refStride, 4, 0, ...)}.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param refStride the reference row stride
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @param out       receives the two packed sums, see {@link #sse(long)} and {@link #sum(long)}
     */
    public abstract void halfpelHorizontalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                               long[] out);

    /**
     * Returns the sums of the two vertical half-pixel predictions above and below the rows of a
     * 16x16 reference block: {@code out[0]} receives
     * {@code subpelVariance16(ref, refOff - refStride, refStride, 0, 4, ...)} and {@code out[1]}
     * {@code subpelVariance16(ref, refOff, refStride, 0, 4, ...)}.
     *
     * @param ref       the reference samples
     * @param refOff    the index of the reference block's top-left sample
     * @param refStride the reference row stride
     * @param b         the second samples
     * @param bOff      the index of the second block's top-left sample
     * @param bStride   the second block's row stride
     * @param out       receives the two packed sums, see {@link #sse(long)} and {@link #sum(long)}
     */
    public abstract void halfpelVerticalPair(byte[] ref, int refOff, int refStride, byte[] b, int bOff, int bStride,
                                             long[] out);

    /**
     * Copies the samples of a macroblock into the three interleaved groups of eight 4x4 blocks
     * the group kernels read: luma rows {@code 8h + r} and {@code 8h + 4 + r} become row
     * {@code r} of group {@code h}, and row {@code r} of group 2 holds U rows {@code r} and
     * {@code r + 4}, then V rows {@code r} and {@code r + 4}.
     *
     * @param y        the luma plane
     * @param yOff     the index of the macroblock's top-left luma sample
     * @param yStride  the luma row stride
     * @param u        the U plane
     * @param v        the V plane
     * @param uvOff    the index of the macroblock's top-left chroma sample
     * @param uvStride the chroma row stride
     * @param groups   receives the three groups, {@code 3 * GROUP} bytes
     */
    public abstract void groupMacroblock(byte[] y, int yOff, int yStride, byte[] u, byte[] v, int uvOff, int uvStride,
                                         byte[] groups);

    /**
     * Computes the forward DCT of the differences between the samples and the predictions of a
     * group of eight 4x4 blocks.
     *
     * <p>Both inputs hold the group interleaved, {@link #GROUP} bytes: row {@code r} of the
     * group is the 32 bytes at {@code 32 * r}, and the four bytes at {@code 32 * r + 4 * j} are
     * row {@code r} of block {@code j}. The coefficient of raster position {@code p} of block
     * {@code j} is written at {@code coefOff + 8 * p + j}. Each block's coefficients equal those of
     * {@link Vp8EncDsp#fTransform} on the same samples. The largest coefficient magnitudes are
     * returned, so the caller can tell a group that quantizes to zero without quantizing it.
     *
     * @param src     the interleaved source samples
     * @param srcOff  the index of the source group
     * @param pred    the interleaved prediction samples
     * @param predOff the index of the prediction group
     * @param coef    receives the coefficients
     * @param coefOff the index of the first coefficient
     * @return the largest DC magnitude of the group in bits 16 to 31 and the largest AC magnitude
     *         (positions 1 to 15) in bits 0 to 15
     */
    public abstract int forwardDct8(byte[] src, int srcOff, byte[] pred, int predOff, int[] coef, int coefOff);

    /**
     * Quantizes the coefficients of a group of eight 4x4 blocks, replacing them with their
     * dequantized values, and finds each block's end of block.
     *
     * <p>The coefficients are laid out as {@link #forwardDct8} writes them. Position 0 uses the
     * matrix's DC step and every other position its AC step, so the matrix must hold one AC step,
     * reciprocal and bias for positions 1 to 15 and no sharpening, as the real-time encoder's
     * matrices do; each level is then the one {@link Vp8EncDsp#quantizeBlock} computes. Zigzag
     * positions below {@code first} quantize to zero. The level of zigzag position {@code n} of
     * block {@code j} is written at {@code levelsOff + n * LEVEL_STRIDE + j}; each dequantized
     * value is {@code (short) (level * step)}. The end of block of block {@code j}, one past its
     * last nonzero zigzag position or {@code 0}, is written at {@code eobOff + j}.
     *
     * @param coef      the coefficients, overwritten with their dequantized values
     * @param coefOff   the index of the first coefficient
     * @param first     the first zigzag position coded: {@code 1} for luma blocks with a Y2
     *                  block, {@code 0} otherwise
     * @param m         the quantization matrix
     * @param levels    receives the levels
     * @param levelsOff the index of the group's first level
     * @param eob       receives the ends of block
     * @param eobOff    the index of the group's first end of block
     */
    abstract void quantize8(int[] coef, int coefOff, int first, QuantMatrix m, short[] levels, int levelsOff,
                                   int[] eob, int eobOff);

    /**
     * Adds the inverse DCT of the dequantized coefficients of a group of eight 4x4 blocks to
     * their prediction and stores the clamped reconstruction, interleaved like the prediction.
     *
     * <p>Each block is reconstructed exactly as {@link Vp8Dsp#idctAdd} reconstructs it from the
     * same coefficients, including the 16-bit wrap of the first pass; a block whose only nonzero
     * coefficient is the DC therefore gets the result of {@link Vp8Dsp#dcOnlyAdd}, and a block of
     * zeros its prediction.
     *
     * @param coef    the dequantized coefficients, laid out as {@link #forwardDct8} writes them;
     *                overwritten
     * @param coefOff the index of the first coefficient
     * @param pred    the interleaved prediction samples
     * @param predOff the index of the prediction group
     * @param out     receives the interleaved reconstruction
     * @param outOff  the index of the reconstruction group
     */
    public abstract void inverseDctAdd8(int[] coef, int coefOff, byte[] pred, int predOff, byte[] out, int outOff);

    /**
     * Returns the sum of squared differences of two 4x4 blocks, libvpx's
     * {@code vpx_get4x4sse_cs}.
     *
     * @param a       the first samples
     * @param aOff    the index of the first block's top-left sample
     * @param aStride the first block's row stride
     * @param b       the second samples
     * @param bOff    the index of the second block's top-left sample
     * @param bStride the second block's row stride
     * @return the sum of squared differences
     */
    public int sse4x4(byte[] a, int aOff, int aStride, byte[] b, int bOff, int bStride) {
        var sse = 0;
        for (var r = 0; r < 4; r++, aOff += aStride, bOff += bStride) {
            for (var c = 0; c < 4; c++) {
                var d = (a[aOff + c] & 0xFF) - (b[bOff + c] & 0xFF);
                sse += d * d;
            }
        }
        return sse;
    }
}
