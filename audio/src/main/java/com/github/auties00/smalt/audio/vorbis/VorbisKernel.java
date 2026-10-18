package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Implements the DSP kernels of Vorbis synthesis: the inverse MDCT, inverse channel coupling and
 * the windowed overlap-add primitives.
 *
 * <p>Implementations compute every result bit for bit as {@link ScalarVorbisKernel}, the reference
 * implementation, does: the inverse MDCT follows the canonical operation order of {@link Imdct}, so
 * decoded output does not depend on the kernel or on the vector width.
 */
sealed abstract class VorbisKernel permits ScalarVorbisKernel, VectorVorbisKernel {
    /**
     * The kernel used by decoders: the vector kernel when {@link Simd#ENABLED}, the scalar one
     * otherwise.
     */
    static final VorbisKernel INSTANCE = Simd.ENABLED ? VectorVorbisKernel.create() : new ScalarVorbisKernel();

    /**
     * Constructs a kernel.
     */
    VorbisKernel() {
    }

    /**
     * Computes the unnormalized inverse MDCT of one block in the canonical order of {@link Imdct}.
     *
     * @param plan the tables of the block size
     * @param spectrum the {@code n/2} coefficients; clobbered
     * @param output receives the {@code n} time-domain samples
     * @param re the FFT scratch for real parts, at least {@code n/4} long
     * @param im the FFT scratch for imaginary parts, at least {@code n/4} long
     */
    abstract void imdct(Imdct plan, float[] spectrum, float[] output, float[] re, float[] im);

    /**
     * Converts a square polar magnitude/angle vector pair back to two channel vectors in place
     * (Vorbis I section 4.3.5).
     *
     * @param magnitude the magnitude vector, replaced by the first channel
     * @param angle the angle vector, replaced by the second channel
     * @param count the number of elements
     */
    abstract void inverseCoupling(float[] magnitude, float[] angle, int count);

    /**
     * Adds the element-wise product of two runs to a third: {@code dst[d + k] += a[i + k] *
     * b[j + k]}.
     *
     * @param a the first factor
     * @param i the first factor offset
     * @param b the second factor
     * @param j the second factor offset
     * @param dst the destination
     * @param d the destination offset
     * @param count the number of elements
     */
    abstract void multiplyAdd(float[] a, int i, float[] b, int j, float[] dst, int d, int count);

    /**
     * Stores the element-wise product of two runs: {@code dst[d + k] = a[i + k] * b[j + k]}.
     *
     * @param a the first factor
     * @param i the first factor offset
     * @param b the second factor
     * @param j the second factor offset
     * @param dst the destination
     * @param d the destination offset
     * @param count the number of elements
     */
    abstract void multiply(float[] a, int i, float[] b, int j, float[] dst, int d, int count);

    /**
     * Adds one run to another: {@code dst[d + k] += a[i + k]}.
     *
     * @param a the addend
     * @param i the addend offset
     * @param dst the destination
     * @param d the destination offset
     * @param count the number of elements
     */
    abstract void add(float[] a, int i, float[] dst, int d, int count);
}
