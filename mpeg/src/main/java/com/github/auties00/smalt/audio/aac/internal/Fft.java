package com.github.auties00.smalt.audio.aac.internal;

/**
 * Computes forward complex FFTs, {@code X[k] = sum x[j] exp(-2 pi i j k / n)}, of one fixed
 * length on split real and imaginary arrays.
 *
 * <p>Power-of-two lengths from 8 up use a decimation-in-frequency algorithm built from
 * radix-2^2 passes (vectorized by {@link AacKernel}), at most one radix-2 stage, and an unrolled
 * 8-point tail; they leave frequency {@code k} at position {@link #position(int)}, a bit
 * reversal. Other lengths whose factors are 2, 3 and 5 (the 480- and 60-point transforms of
 * 960-sample frames) use a recursive mixed-radix decimation-in-time algorithm that leaves the
 * output in natural order. An instance holds scratch memory and is not thread-safe.
 */
public final class Fft {
    /**
     * The transform length.
     */
    private final int size;

    /**
     * The kernel running the vectorizable passes.
     */
    private final AacKernel kernel;

    /**
     * Whether the length is a power of two.
     */
    private final boolean powerOfTwo;

    /**
     * The quarter block length of each radix-2^2 pass, in execution order.
     */
    private final int[] quarters;

    /**
     * The twiddle offset of each radix-2^2 pass.
     */
    private final int[] quarterOffsets;

    /**
     * The half block length of the radix-2 stage, or 0 when there is none.
     */
    private final int half;

    /**
     * The twiddle offset of the radix-2 stage.
     */
    private final int halfOffset;

    /**
     * The twiddle factors of every pass.
     */
    private final float[] twiddles;

    /**
     * The position of each frequency in the output.
     */
    private final int[] positions;

    /**
     * The real parts of {@code exp(-2 pi i k / n)}, for the mixed-radix algorithm.
     */
    private final float[] rootsRe;

    /**
     * The imaginary parts of {@code exp(-2 pi i k / n)}, for the mixed-radix algorithm.
     */
    private final float[] rootsIm;

    /**
     * The mixed-radix input copy, real parts.
     */
    private final float[] scratchRe;

    /**
     * The mixed-radix input copy, imaginary parts.
     */
    private final float[] scratchIm;

    /**
     * The twiddled inputs of one mixed-radix butterfly, real parts.
     */
    private final float[] butterflyRe = new float[5];

    /**
     * The twiddled inputs of one mixed-radix butterfly, imaginary parts.
     */
    private final float[] butterflyIm = new float[5];

    /**
     * Plans a transform.
     *
     * @param size   the transform length: a power of two of at least 8, or a product of 2, 3
     *               and 5 with at least one factor 2
     * @param kernel the kernel running the vectorizable passes
     * @throws IllegalArgumentException if the length is not supported
     */
    public Fft(int size, AacKernel kernel) {
        this.size = size;
        this.kernel = kernel;
        this.powerOfTwo = Integer.bitCount(size) == 1;
        if (powerOfTwo) {
            if (size < 8) {
                throw new IllegalArgumentException("FFT length " + size + " is too short");
            }
            var stages = Integer.numberOfTrailingZeros(size);
            var passes = 0;
            while (stages - 2 * passes >= 5) {
                passes++;
            }
            quarters = new int[passes];
            quarterOffsets = new int[passes];
            var remaining = stages;
            var tableSize = 0;
            for (var pass = 0; pass < passes; pass++) {
                quarters[pass] = 1 << (remaining - 2);
                quarterOffsets[pass] = tableSize;
                tableSize += 6 * quarters[pass];
                remaining -= 2;
            }
            if (remaining == 4) {
                half = 8;
                halfOffset = tableSize;
                tableSize += 2 * half;
            } else {
                half = 0;
                halfOffset = 0;
            }
            twiddles = new float[tableSize];
            for (var pass = 0; pass < passes; pass++) {
                var quarter = quarters[pass];
                var offset = quarterOffsets[pass];
                for (var j = 0; j < quarter; j++) {
                    for (var k = 1; k <= 3; k++) {
                        var angle = -2 * Math.PI * k * j / (4.0 * quarter);
                        twiddles[offset + (k - 1) * 2 * quarter + j] = (float) StrictMath.cos(angle);
                        twiddles[offset + (k - 1) * 2 * quarter + quarter + j] = (float) StrictMath.sin(angle);
                    }
                }
            }
            for (var j = 0; j < half; j++) {
                var angle = -2 * Math.PI * j / (2.0 * half);
                twiddles[halfOffset + j] = (float) StrictMath.cos(angle);
                twiddles[halfOffset + half + j] = (float) StrictMath.sin(angle);
            }
            positions = new int[size];
            for (var k = 0; k < size; k++) {
                positions[k] = Integer.reverse(k) >>> (32 - stages);
            }
            rootsRe = null;
            rootsIm = null;
            scratchRe = null;
            scratchIm = null;
        } else {
            var rest = size;
            for (var factor : new int[]{2, 3, 5}) {
                while (rest % factor == 0) {
                    rest /= factor;
                }
            }
            if (rest != 1 || size % 2 != 0) {
                throw new IllegalArgumentException("unsupported FFT length " + size);
            }
            quarters = new int[0];
            quarterOffsets = new int[0];
            half = 0;
            halfOffset = 0;
            twiddles = new float[0];
            positions = new int[size];
            for (var k = 0; k < size; k++) {
                positions[k] = k;
            }
            rootsRe = new float[size];
            rootsIm = new float[size];
            for (var k = 0; k < size; k++) {
                var angle = -2 * Math.PI * k / size;
                rootsRe[k] = (float) StrictMath.cos(angle);
                rootsIm[k] = (float) StrictMath.sin(angle);
            }
            scratchRe = new float[size];
            scratchIm = new float[size];
        }
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
     * Returns where the transform leaves a frequency.
     *
     * @param frequency the frequency index
     * @return its position in the output arrays
     */
    public int position(int frequency) {
        return positions[frequency];
    }

    /**
     * Returns the output position of every frequency.
     *
     * @return the positions; shared and not to be modified
     */
    public int[] positions() {
        return positions;
    }

    /**
     * Transforms {@link #size()} points in place.
     *
     * @param re the real parts
     * @param im the imaginary parts
     */
    public void transform(float[] re, float[] im) {
        if (!powerOfTwo) {
            System.arraycopy(re, 0, scratchRe, 0, size);
            System.arraycopy(im, 0, scratchIm, 0, size);
            mixedRadix(scratchRe, scratchIm, 0, 1, size, re, im, 0);
            return;
        }
        for (var pass = 0; pass < quarters.length; pass++) {
            kernel.fftRadix4(re, im, size, quarters[pass], twiddles, quarterOffsets[pass]);
        }
        if (half != 0) {
            kernel.fftRadix2(re, im, size, half, twiddles, halfOffset);
        }
        tail8(re, im);
    }

    /**
     * Runs the last three decimation-in-frequency stages on every block of eight points.
     *
     * @param re the real parts
     * @param im the imaginary parts
     */
    private void tail8(float[] re, float[] im) {
        final var r = (float) Math.sqrt(0.5);
        for (var b = 0; b < size; b += 8) {
            var x0r = re[b];
            var x0i = im[b];
            var x1r = re[b + 1];
            var x1i = im[b + 1];
            var x2r = re[b + 2];
            var x2i = im[b + 2];
            var x3r = re[b + 3];
            var x3i = im[b + 3];
            var x4r = re[b + 4];
            var x4i = im[b + 4];
            var x5r = re[b + 5];
            var x5i = im[b + 5];
            var x6r = re[b + 6];
            var x6i = im[b + 6];
            var x7r = re[b + 7];
            var x7i = im[b + 7];
            var a0r = x0r + x4r;
            var a0i = x0i + x4i;
            var a1r = x1r + x5r;
            var a1i = x1i + x5i;
            var a2r = x2r + x6r;
            var a2i = x2i + x6i;
            var a3r = x3r + x7r;
            var a3i = x3i + x7i;
            var a4r = x0r - x4r;
            var a4i = x0i - x4i;
            var tr = x1r - x5r;
            var ti = x1i - x5i;
            var a5r = (tr + ti) * r;
            var a5i = (ti - tr) * r;
            var a6r = x2i - x6i;
            var a6i = x6r - x2r;
            tr = x3r - x7r;
            ti = x3i - x7i;
            var a7r = (ti - tr) * r;
            var a7i = -(tr + ti) * r;
            var b0r = a0r + a2r;
            var b0i = a0i + a2i;
            var b1r = a1r + a3r;
            var b1i = a1i + a3i;
            var b2r = a0r - a2r;
            var b2i = a0i - a2i;
            var b3r = a1i - a3i;
            var b3i = a3r - a1r;
            var b4r = a4r + a6r;
            var b4i = a4i + a6i;
            var b5r = a5r + a7r;
            var b5i = a5i + a7i;
            var b6r = a4r - a6r;
            var b6i = a4i - a6i;
            var b7r = a5i - a7i;
            var b7i = a7r - a5r;
            re[b] = b0r + b1r;
            im[b] = b0i + b1i;
            re[b + 1] = b0r - b1r;
            im[b + 1] = b0i - b1i;
            re[b + 2] = b2r + b3r;
            im[b + 2] = b2i + b3i;
            re[b + 3] = b2r - b3r;
            im[b + 3] = b2i - b3i;
            re[b + 4] = b4r + b5r;
            im[b + 4] = b4i + b5i;
            re[b + 5] = b4r - b5r;
            im[b + 5] = b4i - b5i;
            re[b + 6] = b6r + b7r;
            im[b + 6] = b6i + b7i;
            re[b + 7] = b6r - b7r;
            im[b + 7] = b6i - b7i;
        }
    }

    /**
     * Computes an {@code n}-point DFT of the strided input by recursive mixed-radix
     * decimation in time.
     *
     * @param inRe      the input real parts
     * @param inIm      the input imaginary parts
     * @param inOffset  the index of the first input point
     * @param stride    the distance between input points
     * @param n         the sub-transform length
     * @param outRe     the output real parts
     * @param outIm     the output imaginary parts
     * @param outOffset the index of the first output point
     */
    private void mixedRadix(float[] inRe, float[] inIm, int inOffset, int stride, int n,
                            float[] outRe, float[] outIm, int outOffset) {
        if (n == 1) {
            outRe[outOffset] = inRe[inOffset];
            outIm[outOffset] = inIm[inOffset];
            return;
        }
        var radix = n % 4 == 0 ? 4 : n % 2 == 0 ? 2 : n % 3 == 0 ? 3 : 5;
        var m = n / radix;
        for (var s = 0; s < radix; s++) {
            mixedRadix(inRe, inIm, inOffset + s * stride, stride * radix, m, outRe, outIm, outOffset + s * m);
        }
        var rootStep = size / n;
        var tRe = butterflyRe;
        var tIm = butterflyIm;
        for (var k = 0; k < m; k++) {
            for (var s = 0; s < radix; s++) {
                var index = outOffset + s * m + k;
                var root = (s * k * rootStep) % size;
                var xr = outRe[index];
                var xi = outIm[index];
                tRe[s] = xr * rootsRe[root] - xi * rootsIm[root];
                tIm[s] = xr * rootsIm[root] + xi * rootsRe[root];
            }
            for (var q = 0; q < radix; q++) {
                var sumRe = 0.0f;
                var sumIm = 0.0f;
                for (var s = 0; s < radix; s++) {
                    var root = (s * q * m * rootStep) % size;
                    sumRe += tRe[s] * rootsRe[root] - tIm[s] * rootsIm[root];
                    sumIm += tRe[s] * rootsIm[root] + tIm[s] * rootsRe[root];
                }
                outRe[outOffset + q * m + k] = sumRe;
                outIm[outOffset + q * m + k] = sumIm;
            }
        }
    }
}
