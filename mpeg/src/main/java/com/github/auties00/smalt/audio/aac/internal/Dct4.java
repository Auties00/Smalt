package com.github.auties00.smalt.audio.aac.internal;

/**
 * Computes a scaled type-IV discrete cosine transform of one fixed length,
 * {@code out[m] = scale * sum in[k] cos(pi / M (m + 1/2)(k + 1/2))}, through a complex FFT of
 * half its length.
 *
 * <p>The DCT-IV is the core of both MDCT directions: the inverse MDCT of {@code M} spectral
 * lines is the DCT-IV of the lines unfolded to {@code 2M} samples, and the forward MDCT is the
 * DCT-IV of the {@code 2M} windowed samples folded to {@code M}. An instance holds scratch
 * memory and is not thread-safe.
 *
 * @implNote This implementation pairs input lines {@code 2k} and {@code M - 1 - 2k} into one
 * complex value, pre-twiddles by {@code exp(-i pi (k + 1/8) / M)}, runs an {@code M/2}-point
 * FFT, post-twiddles by the same factors and reads the even outputs from the real parts and the
 * odd outputs, mirrored, from the negated imaginary parts.
 */
public final class Dct4 {
    /**
     * The transform length {@code M}.
     */
    private final int size;

    /**
     * The kernel running the pre-twiddle.
     */
    private final AacKernel kernel;

    /**
     * The half-length FFT.
     */
    private final Fft fft;

    /**
     * The pre-twiddle cosines, scaled.
     */
    private final float[] preCos;

    /**
     * The pre-twiddle sines, scaled.
     */
    private final float[] preSin;

    /**
     * The post-twiddle cosines.
     */
    private final float[] postCos;

    /**
     * The post-twiddle sines.
     */
    private final float[] postSin;

    /**
     * The FFT output position of each frequency.
     */
    private final int[] positions;

    /**
     * The FFT buffer, real parts.
     */
    private final float[] re;

    /**
     * The FFT buffer, imaginary parts.
     */
    private final float[] im;

    /**
     * Plans a transform.
     *
     * @param size   the transform length: 16 times a power of two, or 960 or 120
     * @param scale  the factor applied to every output
     * @param kernel the kernel running the FFT passes
     * @throws IllegalArgumentException if the length is not supported
     */
    public Dct4(int size, double scale, AacKernel kernel) {
        this.size = size;
        this.kernel = kernel;
        var half = size / 2;
        this.fft = new Fft(half, kernel);
        this.preCos = new float[half];
        this.preSin = new float[half];
        this.postCos = new float[half];
        this.postSin = new float[half];
        for (var k = 0; k < half; k++) {
            var angle = Math.PI * (k + 0.125) / size;
            preCos[k] = (float) (Math.cos(angle) * scale);
            preSin[k] = (float) (Math.sin(angle) * scale);
            postCos[k] = (float) Math.cos(angle);
            postSin[k] = (float) Math.sin(angle);
        }
        this.positions = fft.positions();
        this.re = new float[half];
        this.im = new float[half];
    }

    /**
     * Returns the transform length.
     *
     * @return the length
     */
    public int size() {
        return size;
    }

    /**
     * Transforms {@link #size()} values.
     *
     * @param in        the input
     * @param inOffset  the index of the first input value
     * @param out       the output, which may be the input array only if the ranges do not
     *                  overlap
     * @param outOffset the index of the first output value
     */
    public void transform(float[] in, int inOffset, float[] out, int outOffset) {
        kernel.preTwiddle(in, inOffset, size, preCos, preSin, re, im);
        var half = size >> 1;
        fft.transform(re, im);
        var outLast = outOffset + size - 1;
        for (var j = 0; j < half; j++) {
            var p = positions[j];
            var zr = re[p];
            var zi = im[p];
            var c = postCos[j];
            var s = postSin[j];
            out[outOffset + 2 * j] = zr * c + zi * s;
            out[outLast - 2 * j] = zr * s - zi * c;
        }
    }
}
