package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the FFT and DCT-IV against direct O(n^2) evaluations, for both kernels.
 */
class TransformTest {
    static double maxRelativeError(double[] expected, float[] actual) {
        double peak = 0;
        double error = 0;
        for (var i = 0; i < expected.length; i++) {
            peak = Math.max(peak, Math.abs(expected[i]));
            error = Math.max(error, Math.abs(expected[i] - actual[i]));
        }
        return error / peak;
    }

    @Nested
    @DisplayName("FFT")
    class FftTests {
        @ParameterizedTest(name = "n = {0}")
        @ValueSource(ints = {8, 16, 32, 64, 128, 256, 512, 1024, 60, 480, 30, 12})
        @DisplayName("matches the direct DFT with both kernels")
        void direct(int n) {
            var random = new Random(n);
            var re = new float[n];
            var im = new float[n];
            for (var i = 0; i < n; i++) {
                re[i] = (float) random.nextGaussian();
                im[i] = (float) random.nextGaussian();
            }
            var expectedRe = new double[n];
            var expectedIm = new double[n];
            for (var k = 0; k < n; k++) {
                for (var j = 0; j < n; j++) {
                    var angle = -2 * Math.PI * ((long) j * k % n) / n;
                    expectedRe[k] += re[j] * Math.cos(angle) - im[j] * Math.sin(angle);
                    expectedIm[k] += re[j] * Math.sin(angle) + im[j] * Math.cos(angle);
                }
            }
            for (var kernel : new AacKernel[]{AacKernels.scalar(), AacKernels.vector()}) {
                var fft = new Fft(n, kernel);
                var outRe = re.clone();
                var outIm = im.clone();
                fft.transform(outRe, outIm);
                var natRe = new float[n];
                var natIm = new float[n];
                for (var k = 0; k < n; k++) {
                    natRe[k] = outRe[fft.position(k)];
                    natIm[k] = outIm[fft.position(k)];
                }
                assertTrue(maxRelativeError(expectedRe, natRe) < 1e-5, kernel + " re");
                assertTrue(maxRelativeError(expectedIm, natIm) < 1e-5, kernel + " im");
            }
        }
    }

    @Nested
    @DisplayName("DCT-IV")
    class Dct4Tests {
        @ParameterizedTest(name = "M = {0}")
        @ValueSource(ints = {1024, 128, 960, 120, 256, 64})
        @DisplayName("matches the direct definition with both kernels")
        void direct(int m) {
            var random = new Random(m);
            var in = new float[m];
            for (var i = 0; i < m; i++) {
                in[i] = (float) random.nextGaussian();
            }
            var expected = new double[m];
            for (var o = 0; o < m; o++) {
                for (var k = 0; k < m; k++) {
                    expected[o] += 0.5 * in[k] * Math.cos(Math.PI / m * (o + 0.5) * (k + 0.5));
                }
            }
            for (var kernel : new AacKernel[]{AacKernels.scalar(), AacKernels.vector()}) {
                var out = new float[m + 3];
                new Dct4(m, 0.5, kernel).transform(in, 0, out, 3);
                var shifted = new float[m];
                System.arraycopy(out, 3, shifted, 0, m);
                assertTrue(maxRelativeError(expected, shifted) < 1e-5, "error " + maxRelativeError(expected, shifted));
            }
        }
    }
}
