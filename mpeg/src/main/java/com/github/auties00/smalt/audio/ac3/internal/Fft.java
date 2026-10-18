package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Computes unnormalized complex discrete Fourier transforms of one power-of-two length with an
 * iterative radix-2 algorithm.
 *
 * <p>{@link #inverse(float[], float[])} computes {@code z[n] = sum Z[k] exp(+2 pi i k n / N)} and
 * {@link #forward(float[], float[])} the same sum with {@code exp(-2 PI i k n / N)}; neither
 * scales by {@code 1 / N}. Each butterfly stage runs on {@link Ac3Kernel#butterflies(float[],
 * float[], float[], float[], int, int)} with the stage's twiddle factors laid out contiguously. An
 * instance is immutable and thread-safe when its kernel is.
 */
public final class Fft {
    /**
     * The transform length.
     */
    private final int length;

    /**
     * The bit-reversed position of every index.
     */
    private final int[] reversed;

    /**
     * {@code cos(2 pi k / N)} for {@code k < N / 2}.
     */
    private final float[] cos;

    /**
     * {@code sin(2 pi k / N)} for {@code k < N / 2}.
     */
    private final float[] sin;

    /**
     * The real parts of the twiddle factors of each stage, by stage: {@code cos(2 pi k / size)}
     * for {@code k < size / 2}, where stage {@code s} has butterfly span {@code 2^s} and size
     * {@code 2^(s + 1)}.
     */
    private final float[][] stageCos;

    /**
     * The imaginary parts of the inverse twiddle factors of each stage: {@code sin(2 pi k / size)}.
     */
    private final float[][] stageSinInverse;

    /**
     * The imaginary parts of the forward twiddle factors of each stage: {@code -sin(2 pi k / size)}.
     */
    private final float[][] stageSinForward;

    /**
     * The real parts of the twiddle factors of the first three stages, each repeated over 8
     * entries as {@link Ac3Kernel#firstStages(float[], float[], float[], float[], int)} takes them.
     */
    private final float[] firstCos = new float[24];

    /**
     * The imaginary parts of the inverse twiddle factors of the first three stages.
     */
    private final float[] firstSinInverse = new float[24];

    /**
     * The imaginary parts of the forward twiddle factors of the first three stages.
     */
    private final float[] firstSinForward = new float[24];

    /**
     * The kernels the butterfly stages run on.
     */
    private final Ac3Kernel kernel;

    /**
     * Creates the transform of a length running on a kernel.
     *
     * @param length the transform length, a power of two of at least 2
     * @param kernel the kernels the butterfly stages run on
     * @throws IllegalArgumentException if {@code length} is not such a power of two
     */
    public Fft(int length, Ac3Kernel kernel) {
        if (length < 2 || Integer.bitCount(length) != 1) {
            throw new IllegalArgumentException("invalid FFT length " + length);
        }
        this.length = length;
        this.reversed = new int[length];
        var bits = Integer.numberOfTrailingZeros(length);
        for (var i = 0; i < length; i++) {
            reversed[i] = Integer.reverse(i) >>> (32 - bits);
        }
        this.cos = new float[length / 2];
        this.sin = new float[length / 2];
        for (var k = 0; k < length / 2; k++) {
            var angle = 2 * Math.PI * k / length;
            cos[k] = (float) Math.cos(angle);
            sin[k] = (float) Math.sin(angle);
        }
        var stages = bits;
        this.stageCos = new float[stages][];
        this.stageSinInverse = new float[stages][];
        this.stageSinForward = new float[stages][];
        for (var stage = 0; stage < stages; stage++) {
            var half = 1 << stage;
            var step = length / (2 * half);
            stageCos[stage] = new float[half];
            stageSinInverse[stage] = new float[half];
            stageSinForward[stage] = new float[half];
            for (var k = 0; k < half; k++) {
                stageCos[stage][k] = cos[k * step];
                stageSinInverse[stage][k] = sin[k * step];
                stageSinForward[stage][k] = -sin[k * step];
            }
        }
        for (var stage = 0; stage < Math.min(3, stages); stage++) {
            var half = 1 << stage;
            for (var lane = 0; lane < 8; lane++) {
                firstCos[8 * stage + lane] = stageCos[stage][lane % half];
                firstSinInverse[8 * stage + lane] = stageSinInverse[stage][lane % half];
                firstSinForward[8 * stage + lane] = stageSinForward[stage][lane % half];
            }
        }
        this.kernel = kernel;
    }

    /**
     * Returns the transform length.
     *
     * @return the length
     */
    public int length() {
        return length;
    }

    /**
     * Transforms in place with positive exponents.
     *
     * @param re the real parts, at least {@link #length()} long
     * @param im the imaginary parts, at least {@link #length()} long
     */
    public void inverse(float[] re, float[] im) {
        transform(re, im, stageSinInverse, firstSinInverse);
    }

    /**
     * Transforms in place with positive exponents an input already permuted to bit-reversed
     * order: the value of index {@code k} at the position whose bits reverse those of {@code k}.
     *
     * @param re the real parts, at least {@link #length()} long
     * @param im the imaginary parts, at least {@link #length()} long
     */
    public void inversePermuted(float[] re, float[] im) {
        stages(re, im, stageSinInverse, firstSinInverse);
    }

    /**
     * Transforms in place with negative exponents.
     *
     * @param re the real parts, at least {@link #length()} long
     * @param im the imaginary parts, at least {@link #length()} long
     */
    public void forward(float[] re, float[] im) {
        transform(re, im, stageSinForward, firstSinForward);
    }

    /**
     * Runs the bit-reversal permutation and the decimation-in-time butterflies.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param stageSine the imaginary parts of the twiddle factors of each stage, which select the
     * sign of the exponents
     * @param firstSine the imaginary parts of the twiddle factors of the first three stages
     */
    private void transform(float[] re, float[] im, float[][] stageSine, float[] firstSine) {
        var n = length;
        for (var i = 0; i < n; i++) {
            var j = reversed[i];
            if (j > i) {
                var t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        stages(re, im, stageSine, firstSine);
    }

    /**
     * Runs the butterfly stages on bit-reversed input: the first three in one pass when the length
     * allows it, the others two at a time and the last one alone when their number is odd.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param stageSine the imaginary parts of the twiddle factors of each stage
     * @param firstSine the imaginary parts of the twiddle factors of the first three stages
     */
    private void stages(float[] re, float[] im, float[][] stageSine, float[] firstSine) {
        var n = length;
        var stage = 0;
        if (n >= 8) {
            kernel.firstStages(re, im, firstCos, firstSine, n);
            stage = 3;
        }
        for (; stage + 1 < stageCos.length; stage += 2) {
            kernel.butterflyPair(re, im, stageCos[stage], stageSine[stage], stageCos[stage + 1], stageSine[stage + 1],
                    1 << stage, n);
        }
        if (stage < stageCos.length) {
            kernel.butterflies(re, im, stageCos[stage], stageSine[stage], 1 << stage, n);
        }
    }
}
