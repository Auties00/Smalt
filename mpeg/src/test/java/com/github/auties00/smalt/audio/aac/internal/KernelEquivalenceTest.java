package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the scalar and Vector API kernels on the same randomized inputs: element-wise kernels
 * must agree bit for bit, reductions to within float rounding.
 */
class KernelEquivalenceTest {
    private static final AacKernel SCALAR = AacKernels.scalar();

    private static final AacKernel VECTOR = AacKernels.vector();

    static float[] gaussian(Random random, int length, double scale) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = (float) (random.nextGaussian() * scale);
        }
        return values;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("FFT passes agree bit for bit")
    void fftPasses(int seed) {
        var random = new Random(seed);
        for (var n : new int[]{64, 512}) {
            var re = gaussian(random, n, 1);
            var im = gaussian(random, n, 1);
            for (var quarter : new int[]{8, 16, n / 8, n / 4}) {
                var twiddles = gaussian(random, 6 * quarter, 1);
                var a = re.clone();
                var b = im.clone();
                var c = re.clone();
                var d = im.clone();
                SCALAR.fftRadix4(a, b, n, quarter, twiddles, 0);
                VECTOR.fftRadix4(c, d, n, quarter, twiddles, 0);
                assertArrayEquals(a, c);
                assertArrayEquals(b, d);
                SCALAR.fftRadix2(a, b, n, quarter, twiddles, 3);
                VECTOR.fftRadix2(c, d, n, quarter, twiddles, 3);
                assertArrayEquals(a, c);
                assertArrayEquals(b, d);
            }
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("inverse quantization agrees bit for bit, including ragged run lengths")
    void dequantize(int seed) {
        var random = new Random(seed);
        var quant = new int[1024];
        for (var i = 0; i < quant.length; i++) {
            var magnitude = random.nextInt(8) == 0 ? random.nextInt(Dequantizer.POW43_SIZE) : random.nextInt(20);
            quant[i] = random.nextBoolean() ? -magnitude : magnitude;
        }
        for (var run : new int[][]{{0, 1024}, {4, 36}, {3, 17}, {1000, 1024}, {100, 100}}) {
            var a = new float[1024];
            var b = new float[1024];
            SCALAR.dequantize(quant.clone(), a, run[0], run[1], 0.37f);
            VECTOR.dequantize(quant.clone(), b, run[0], run[1], 0.37f);
            assertArrayEquals(a, b);
        }
    }

    @ParameterizedTest(name = "m = {0}")
    @ValueSource(ints = {1024, 960, 128, 120})
    @DisplayName("windowed overlap-add and folding agree bit for bit")
    void windowing(int m) {
        var random = new Random(m);
        var u = gaussian(random, m, 1);
        var window = gaussian(random, 2 * m, 1);
        var overlapA = gaussian(random, m, 1);
        var overlapB = overlapA.clone();
        var outA = new float[m + 5];
        var outB = new float[m + 5];
        SCALAR.overlapLong(u, m, window, overlapA, outA, 5);
        VECTOR.overlapLong(u, m, window, overlapB, outB, 5);
        assertArrayEquals(outA, outB);
        assertArrayEquals(overlapA, overlapB);
        var input = gaussian(random, 2 * m + 7, 1);
        var foldedA = new float[m];
        var foldedB = new float[m];
        SCALAR.fold(input, 7, window, foldedA, m);
        VECTOR.fold(input, 7, window, foldedB, m);
        assertArrayEquals(foldedA, foldedB);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("power law and quantization agree bit for bit")
    void quantization(int seed) {
        var random = new Random(seed);
        var spectrum = gaussian(random, 1024, 1e5);
        var magnitudeA = new float[1024];
        var power34A = new float[1024];
        var magnitudeB = new float[1024];
        var power34B = new float[1024];
        SCALAR.powerLaw(spectrum, magnitudeA, power34A, 3, 1021);
        VECTOR.powerLaw(spectrum, magnitudeB, power34B, 3, 1021);
        assertArrayEquals(magnitudeA, magnitudeB);
        assertArrayEquals(power34A, power34B);
        for (var scalefactor : new int[]{120, 150, 180}) {
            var quantA = new int[1024];
            var quantB = new int[1024];
            var step = Quantization.inverseStep(scalefactor);
            assertEquals(SCALAR.quantize(power34A, quantA, 5, 1019, step), VECTOR.quantize(power34A, quantB, 5, 1019, step));
            assertArrayEquals(quantA, quantB);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("distortion and energy reductions agree to within float rounding")
    void reductions(int seed) {
        var random = new Random(seed);
        var spectrum = gaussian(random, 1024, 1e5);
        var magnitude = new float[1024];
        var power34 = new float[1024];
        SCALAR.powerLaw(spectrum, magnitude, power34, 0, 1024);
        for (var scalefactor : new int[]{110, 140, 170}) {
            var scratchA = new int[1024];
            var scratchB = new int[1024];
            var a = SCALAR.distortion(magnitude, power34, scratchA, 7, 1003, Quantization.inverseStep(scalefactor),
                    Quantization.step(scalefactor));
            var b = VECTOR.distortion(magnitude, power34, scratchB, 7, 1003, Quantization.inverseStep(scalefactor),
                    Quantization.step(scalefactor));
            assertEquals(a, b, Math.abs(a) * 1e-5);
            assertEquals(Float.floatToRawIntBits(laneOrdered(magnitude, power34, 7, 1003, scalefactor)),
                    Float.floatToRawIntBits(b), "lane-ordered sum");
        }
        var energyA = SCALAR.energy(spectrum, 1, 1000);
        var energyB = VECTOR.energy(spectrum, 1, 1000);
        assertEquals(energyA, energyB, energyA * 1e-5);
        for (var length : new int[]{0, 4, 12, 20, 997}) {
            var dotA = SCALAR.dot(spectrum, 3, spectrum, 5, length);
            var dotB = VECTOR.dot(spectrum, 3, spectrum, 5, length);
            assertEquals(dotA, dotB, Math.max(1e-3, Math.abs(energyA) * 1e-5));
        }
        for (var run : new int[][]{{4, 8}, {4, 16}, {0, 12}, {100, 128}}) {
            var scratchA = new int[1024];
            var scratchB = new int[1024];
            var a = SCALAR.distortion(magnitude, power34, scratchA, run[0], run[1], Quantization.inverseStep(140), Quantization.step(140));
            var b = VECTOR.distortion(magnitude, power34, scratchB, run[0], run[1], Quantization.inverseStep(140), Quantization.step(140));
            assertEquals(a, b, Math.abs(a) * 1e-5);
            assertEquals(Float.floatToRawIntBits(laneOrdered(magnitude, power34, run[0], run[1], 140)),
                    Float.floatToRawIntBits(b), "lane-ordered sum");
            var quantA = new int[1024];
            var quantB = new int[1024];
            assertEquals(SCALAR.quantize(power34, quantA, run[0], run[1], Quantization.inverseStep(140)),
                    VECTOR.quantize(power34, quantB, run[0], run[1], Quantization.inverseStep(140)));
            assertArrayEquals(quantA, quantB);
        }
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {2048, 1920, 256, 240, 64, 34})
    @DisplayName("DCT-IV pre-twiddle and M/S reconstruction agree bit for bit")
    void twiddleAndStereo(int size) {
        var random = new Random(size);
        var input = gaussian(random, size + 9, 1);
        var cos = gaussian(random, size / 2, 1);
        var sin = gaussian(random, size / 2, 1);
        var reA = new float[size / 2];
        var imA = new float[size / 2];
        var reB = new float[size / 2];
        var imB = new float[size / 2];
        SCALAR.preTwiddle(input, 9, size, cos, sin, reA, imA);
        VECTOR.preTwiddle(input, 9, size, cos, sin, reB, imB);
        assertArrayEquals(reA, reB);
        assertArrayEquals(imA, imB);
        var left = gaussian(random, size, 1);
        var right = gaussian(random, size, 1);
        var leftB = left.clone();
        var rightB = right.clone();
        SCALAR.midSide(left, right, 3, size - 1);
        VECTOR.midSide(leftB, rightB, 3, size - 1);
        assertArrayEquals(left, leftB);
        assertArrayEquals(right, rightB);
    }

    // The vector distortion's exact float result: one partial sum per lane over whole vectors, lanes added in
    // order from zero, then a four-lane block when eight lanes are used, then the scalar remainder.
    private static float laneOrdered(float[] magnitude, float[] power34, int from, int to, int scalefactor) {
        var lanes = VectorAacKernel.LANES;
        var inverse = Quantization.inverseStep(scalefactor);
        var step = Quantization.step(scalefactor);
        var bound = from + (to - from) / lanes * lanes;
        var partial = new float[lanes];
        for (var i = from; i < bound; i++) {
            partial[(i - from) % lanes] += square(magnitude, power34, i, inverse, step);
        }
        var total = 0.0f;
        if (bound > from) {
            for (var lane = 0; lane < lanes; lane++) {
                total += partial[lane];
            }
        }
        var i = bound;
        if (lanes == 8 && to - i >= 4) {
            var block = 0.0f;
            for (var lane = 0; lane < 4; lane++) {
                block += square(magnitude, power34, i + lane, inverse, step);
            }
            total += block;
            i += 4;
        }
        var tail = 0.0f;
        for (; i < to; i++) {
            tail += square(magnitude, power34, i, inverse, step);
        }
        return total + tail;
    }

    private static float square(float[] magnitude, float[] power34, int i, float inverse, float step) {
        var q = Math.min((int) (power34[i] * inverse + Quantization.ROUNDING), Dequantizer.POW43_SIZE - 1);
        var error = magnitude[i] - Dequantizer.POW43[q] * step;
        return error * error;
    }
}
