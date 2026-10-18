package com.github.auties00.smalt.audio.vorbis;

/**
 * Holds the precomputed tables of the inverse MDCT of one Vorbis block size and of the window
 * slope that laps blocks of that size.
 *
 * <p>The inverse MDCT of {@code n} samples from {@code n/2} coefficients is
 * {@snippet :
 * y[i] = sum(k = 0 .. n/2 - 1) X[k] * cos(2 * PI / n * (i + 1/2 + n/4) * (k + 1/2))
 * }
 * with no normalization, as the reference decoder computes it. Its {@code n} outputs are a
 * symmetric unfolding of the {@code n/2}-point DCT-IV {@code u} of the coefficients:
 * {@snippet :
 * y[i]         =  u[n/4 + i]          0    <= i < n/4
 * y[i]         = -u[3n/4 - 1 - i]     n/4  <= i < n/2
 * y[n/2 + j]   = -u[n/4 - 1 - j]      0    <= j < n/4
 * y[n/2 + j]   = -u[j - n/4]          n/4  <= j < n/2
 * }
 * so a {@link VorbisKernel} computes {@code u} with an {@code n/4}-point complex FFT between a
 * pre- and a post-rotation: pack {@code z[p] = X[2p] + i X[n/2 - 1 - 2p]}, rotate by
 * {@code exp(-i PI (p + 1/8) / (n/2))}, transform, rotate the result by the same factor, and
 * read {@code u[2q]} and {@code -u[n/2 - 1 - 2q]} from its real and imaginary parts, writing each
 * {@code u} value straight to its two places in {@code y}.
 *
 * <p>The FFT is a decimation-in-frequency transform in one canonical operation order, which every
 * kernel follows exactly so that the output does not depend on the kernel or on the vector width:
 * one radix-2 stage when {@code log2(n/4)} is odd, then radix-4 stages down to butterflies of four
 * adjacent points. Every butterfly output but the first is multiplied by its twiddle, one included,
 * except in the last stage, whose twiddles are all one; each part of a complex product is the
 * rounded sum or difference of two rounded products, never a fused multiply-add. The output is left
 * in bit-reversed order, which the post-rotation undoes through {@link #bitReverse}.
 */
final class Imdct {
    /**
     * The block size {@code n}.
     */
    final int size;

    /**
     * The number of coefficients and of DCT-IV outputs, {@code n/2}.
     */
    final int half;

    /**
     * The FFT size, {@code n/4}.
     */
    final int quarter;

    /**
     * The cosine of the pre- and post-rotation angle {@code PI (p + 1/8) / (n/2)} for each FFT
     * index {@code p}.
     */
    final float[] rotationCos;

    /**
     * The sine of the pre- and post-rotation angle for each FFT index.
     */
    final float[] rotationSin;

    /**
     * The bit-reversed index of each FFT output, over {@code log2(n/4)} bits.
     */
    final int[] bitReverse;

    /**
     * Whether the FFT starts with a radix-2 stage.
     */
    final boolean radix2;

    /**
     * The twiddles of the radix-2 stage: {@code n/8} cosines followed by {@code n/8} sines of
     * {@code -2 PI k / (n/4)}, or an empty array without that stage.
     */
    final float[] radix2Twiddles;

    /**
     * The twiddles of every radix-4 stage but the last in execution order, each in the layout of
     * {@link #radix4Twiddles(int)}.
     */
    final float[] radix4Twiddles;

    /**
     * The twiddles of the radix-4 stage whose butterflies are 4 points apart, the last stage with
     * twiddles in every block size.
     */
    static final float[] RADIX4_16 = radix4Twiddles(16);

    /**
     * The window slope of blocks of this size: {@code n/2} rising values
     * {@code sin(PI/2 sin^2((k + 1/2) / (n/2) PI/2))} (Vorbis I section 4.3.1).
     */
    final float[] slope;

    /**
     * The window slope of blocks of this size in falling order: {@code slopeDown[k] =
     * slope[n/2 - 1 - k]}.
     */
    final float[] slopeDown;

    /**
     * Precomputes the tables of one block size.
     *
     * @param size the block size, a power of two from 64 to 8192
     */
    Imdct(int size) {
        this.size = size;
        this.half = size / 2;
        this.quarter = size / 4;
        this.rotationCos = new float[quarter];
        this.rotationSin = new float[quarter];
        for (var p = 0; p < quarter; p++) {
            var angle = Math.PI * (p + 0.125) / half;
            rotationCos[p] = (float) Math.cos(angle);
            rotationSin[p] = (float) Math.sin(angle);
        }
        var bits = Integer.numberOfTrailingZeros(quarter);
        this.bitReverse = new int[quarter];
        for (var i = 0; i < quarter; i++) {
            bitReverse[i] = Integer.reverse(i) >>> (32 - bits);
        }
        this.radix2 = (bits & 1) != 0;
        if (radix2) {
            var h = quarter / 2;
            radix2Twiddles = new float[2 * h];
            for (var k = 0; k < h; k++) {
                var angle = -2 * Math.PI * k / quarter;
                radix2Twiddles[k] = (float) Math.cos(angle);
                radix2Twiddles[h + k] = (float) Math.sin(angle);
            }
        } else {
            radix2Twiddles = new float[0];
        }
        var total = 0;
        for (var length = radix2 ? quarter / 2 : quarter; length >= 16; length >>= 2) {
            total += 6 * (length / 4);
        }
        this.radix4Twiddles = new float[total];
        var offset = 0;
        for (var length = radix2 ? quarter / 2 : quarter; length >= 16; length >>= 2) {
            var stage = radix4Twiddles(length);
            System.arraycopy(stage, 0, radix4Twiddles, offset, stage.length);
            offset += stage.length;
        }
        this.slope = new float[half];
        for (var k = 0; k < half; k++) {
            var inner = Math.sin((k + 0.5) / half * Math.PI / 2);
            slope[k] = (float) Math.sin(Math.PI / 2 * inner * inner);
        }
        this.slopeDown = new float[half];
        for (var k = 0; k < half; k++) {
            slopeDown[k] = slope[half - 1 - k];
        }
    }

    /**
     * Computes the twiddles of a radix-4 stage: for butterflies {@code s = length/4} points apart,
     * six runs of {@code s} values, the real and imaginary parts of {@code w^k}, {@code w^2k} and
     * {@code w^3k} with {@code w = exp(-2 PI i / length)}.
     *
     * @param length the span of one butterfly group, a power of four of at least 16
     * @return the {@code 6 s} twiddles
     */
    static float[] radix4Twiddles(int length) {
        var s = length / 4;
        var values = new float[6 * s];
        for (var k = 0; k < s; k++) {
            for (var m = 1; m <= 3; m++) {
                var angle = -2 * Math.PI * m * k / length;
                values[(2 * m - 2) * s + k] = (float) Math.cos(angle);
                values[(2 * m - 1) * s + k] = (float) Math.sin(angle);
            }
        }
        return values;
    }
}
