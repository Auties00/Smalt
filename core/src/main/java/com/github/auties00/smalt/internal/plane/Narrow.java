package com.github.auties00.smalt.internal.plane;

import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts rows of 16-bit samples to 8 bits by rounding to nearest: each unsigned sample {@code v}
 * becomes {@code min((v + half) >> shift, 255)}, where {@code half} is {@code 1 << (shift - 1)}, or
 * zero when {@code shift} is zero.
 *
 * <p>The result is defined for every 16-bit input, so a sample above the range of its bit depth
 * saturates to 255 rather than wrapping. The kernels exist as a scalar reference,
 * {@link ScalarNarrow}, and with the Vector API, {@link VectorNarrow}, selected once per JVM as
 * {@link Simd} describes; both produce the same bytes. Implementations are stateless and
 * thread-safe. Arguments are not checked; values outside the documented ranges give unspecified
 * results.
 */
public abstract class Narrow {
    /**
     * The scalar kernels, which every JVM can run.
     */
    static final Narrow SCALAR = new ScalarNarrow();

    /**
     * The kernels every caller uses.
     */
    private static final Narrow SELECTED = Simd.ENABLED ? VectorNarrow.create() : SCALAR;

    /**
     * Constructs a kernel set.
     */
    Narrow() {
    }

    /**
     * {@return the kernels of this JVM: the Vector API ones when {@link Simd#ENABLED}, the scalar
     * reference otherwise}
     */
    public static Narrow select() {
        return SELECTED;
    }

    /**
     * Converts a rectangle of rows.
     *
     * @param src the 16-bit samples
     * @param srcOffset the index of the first sample of the first row
     * @param srcStride the source row stride, in samples
     * @param dst the 8-bit samples
     * @param dstOffset the index of the first sample of the first row
     * @param dstStride the destination row stride, in samples
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the bit depth minus 8, from 0 to 8
     */
    public abstract void narrow(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                                int width, int height, int shift);

    /**
     * Converts a plane with the selected kernels, splitting a large plane into bands of rows on
     * {@link ParallelBands} when {@code parallel} is {@code true}.
     *
     * <p>The output does not depend on {@code parallel} or on the band count.
     *
     * @param src the 16-bit samples
     * @param srcOffset the index of the first sample of the first row
     * @param srcStride the source row stride, in samples
     * @param dst the 8-bit samples
     * @param dstOffset the index of the first sample of the first row
     * @param dstStride the destination row stride, in samples
     * @param width the number of samples per row
     * @param height the number of rows
     * @param shift the bit depth minus 8, from 0 to 8
     * @param parallel whether the rows may be split across the pool, {@code false} to run on the
     * calling thread only
     */
    public static void plane(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                             int width, int height, int shift, boolean parallel) {
        var kernels = SELECTED;
        var bands = parallel ? ParallelBands.bands(height, (long) width * height) : 1;
        if (bands <= 1) {
            kernels.narrow(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height, shift);
            return;
        }
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            kernels.narrow(src, srcOffset + first * srcStride, srcStride, dst, dstOffset + first * dstStride,
                    dstStride, width, last - first, shift);
        });
    }
}
