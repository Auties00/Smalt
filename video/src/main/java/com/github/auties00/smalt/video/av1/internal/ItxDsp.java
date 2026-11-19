package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Defines the AV1 inverse transform kernels: the two-dimensional inverse transform of a block of
 * dequantized coefficients, added to the prediction with clipping.
 *
 * <p>Every kernel exists as a scalar reference implementation, {@link ScalarItxDsp}, and with the
 * Vector API, {@link VectorItxDsp}, which covers the transforms of at most 16 points.
 * {@link #select()} selects the one of each frame. Implementations are stateless and
 * thread-safe; callers pass the scratch buffers a kernel needs.
 *
 * <p>Transform sizes are dav1d's rectangular transform sizes ({@code RectTxfmSize}, in the order
 * {@code TX_4X4, TX_8X8, TX_16X16, TX_32X32, TX_64X64, RTX_4X8, RTX_8X4, RTX_8X16, RTX_16X8,
 * RTX_16X32, RTX_32X16, RTX_32X64, RTX_64X32, RTX_4X16, RTX_16X4, RTX_8X32, RTX_32X8, RTX_16X64,
 * RTX_64X16}, width first) and transform types are dav1d's {@code TxfmType} values, 0 to 15, plus
 * {@link #WHT_WHT} for the lossless Walsh-Hadamard transform. Only the size and type pairs an AV1
 * stream can code are accepted; see {@link #isSupported(int, int)}.
 *
 * <p>Samples are {@code short} values from 0 to the bit depth maximum. Coefficients are stored the
 * way dav1d stores them: the coefficient of row {@code y} and column {@code x} of the coded area,
 * which is at most 32x32 (64-point transforms code only their first 32 rows and columns), lives at
 * index {@code y + x * sh} from the block's first coefficient, where {@code sh} is the coded
 * height.
 *
 * @implNote This implementation follows the structure of dav1d's {@code Dav1dInvTxfmDSPContext}
 *           ({@code src/itx.h}).
 */
public abstract class ItxDsp {
    /**
     * The scalar kernels, which every JVM runs.
     */
    static final ItxDsp SCALAR = new ScalarItxDsp();

    /**
     * The implementation every frame uses: {@link VectorItxDsp}, written for 256-bit vectors, when
     * {@link Simd#WIDE}, {@link #SCALAR} otherwise.
     */
    private static final ItxDsp FIXED = Simd.WIDE ? VectorItxDsp.create() : SCALAR;

    /**
     * The minimum length of the {@code int} scratch buffer {@link #itxfmAdd} needs: one 64x64 block
     * of intermediate values.
     */
    public static final int SCRATCH_INTS = 64 * 64;

    /**
     * The transform type of the lossless 4x4 Walsh-Hadamard transform (dav1d {@code WHT_WHT}).
     */
    public static final int WHT_WHT = 16;

    /**
     * The number of rectangular transform sizes (dav1d {@code N_RECT_TX_SIZES}).
     */
    public static final int TX_SIZES = 19;

    /**
     * For each rectangular transform size, the bit set of the transform types it supports, bit
     * {@code t} standing for type {@code t}.
     */
    private static final int[] SUPPORTED_TYPES = {
            0x1FFFF, 0xFFFF, 0x0FFF, 0x0201, 0x0001,
            0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF, 0x0201, 0x0201, 0x0001, 0x0001,
            0xFFFF, 0xFFFF, 0x0201, 0x0201, 0x0001, 0x0001
    };

    /**
     * Constructs a kernel set.
     */
    protected ItxDsp() {
    }

    /**
     * Returns the kernels one frame is reconstructed with.
     *
     * <p>This is the Vector API implementation when {@link Simd#ENABLED} and {@link #SCALAR} when
     * not. Every implementation produces the same samples, so the output never depends on the
     * selection.
     *
     * @return the kernels
     */
    public static ItxDsp select() {
        return FIXED;
    }

    /**
     * Returns whether a transform size and type pair can occur in an AV1 stream and is accepted by
     * {@link #itxfmAdd}.
     *
     * <p>Every size whose dimensions are both at most 16, except 16x16, accepts all 16 types;
     * 16x16 accepts the 12 types without a one-dimensional ADST or flipped ADST;
     * sizes with a 32-point dimension accept {@code DCT_DCT} and {@code IDTX}; sizes with a 64-point
     * dimension accept only {@code DCT_DCT}; {@link #WHT_WHT} is accepted only at 4x4.
     *
     * @param txSize the rectangular transform size
     * @param txType the transform type
     * @return {@code true} when the pair is supported
     */
    public static boolean isSupported(int txSize, int txType) {
        return txSize >= 0 && txSize < TX_SIZES && txType >= 0 && txType <= WHT_WHT
                && (SUPPORTED_TYPES[txSize] >>> txType & 1) != 0;
    }

    /**
     * Inverse transforms a block of coefficients and adds the residual to the destination, clipping
     * every sample to {@code 0..bitdepthMax}.
     *
     * <p>{@code eob} is the scan position of the last coefficient the block codes, from zero to the
     * coded area minus one; a {@code DCT_DCT} block with {@code eob} zero takes the DC-only path.
     * Every coefficient the kernel reads is reset to zero, so the coefficient buffer is all-zero
     * after the call provided only the coded area held non-zero values. The kernel reads and
     * writes the destination only inside the {@code w x h} block.
     *
     * @implSpec
     * Implementations must produce exactly the samples of dav1d's C kernels: the 8-bit template
     * when {@code bitdepthMax} is 255, the high bit depth template otherwise.
     *
     * @param txSize      the rectangular transform size
     * @param txType      the transform type, or {@link #WHT_WHT}
     * @param dst         the destination plane
     * @param dstOff      the index of the block's top-left sample
     * @param stride      the destination row stride, in samples
     * @param coef        the coefficient buffer
     * @param coefOff     the index of the block's first coefficient
     * @param eob         the scan position of the last coded coefficient
     * @param scratch     a scratch buffer of at least {@link #SCRATCH_INTS} entries
     * @param bitdepthMax the largest sample value: 255, 1023 or 4095
     * @throws IllegalArgumentException if the size and type pair is not supported
     */
    public abstract void itxfmAdd(int txSize, int txType, short[] dst, int dstOff, int stride, int[] coef,
                                  int coefOff, int eob, int[] scratch, int bitdepthMax);
}
