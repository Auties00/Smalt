package com.github.auties00.smalt.audio.opus.internal.celt;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API kernels are bit-identical to the scalar reference kernels on
 * randomized input, including the aliasing and near-tie cases where a careless vectorization
 * would differ.
 */
class CeltKernelsTest {
    private static final ScalarCeltKernels SCALAR = new ScalarCeltKernels();

    private static CeltKernels vector() {
        Skip.unlessVector(Simd.ENABLED);
        return VectorCeltKernels.create();
    }

    private static float[] gaussian(Random random, int n, float scale) {
        var out = new float[n];
        for (var i = 0; i < n; i++) {
            out[i] = (float) random.nextGaussian() * scale;
        }
        return out;
    }

    @ParameterizedTest(name = "len {0}, lags {1}")
    @CsvSource({"3,1", "4,4", "7,9", "240,97", "480,257", "960,289", "17,33", "1,64", "992,5", "1000,25", "480,11"})
    @DisplayName("pitchXcorr matches the scalar kernel bit for bit")
    void pitchXcorr(int len, int lags) {
        var kernels = vector();
        var random = new Random(len * 31L + lags);
        var x = gaussian(random, len + 3, 1000f);
        var y = gaussian(random, len + lags + 5, 1000f);
        var expected = new float[lags + 2];
        var actual = new float[lags + 2];
        SCALAR.pitchXcorr(x, 2, y, 3, expected, 1, len, lags);
        kernels.pitchXcorr(x, 2, y, 3, actual, 1, len, lags);
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "T {0}, N {1}, in place {2}")
    @CsvSource({"15,120,false", "15,120,true", "16,840,true", "17,840,true", "18,960,true", "24,960,true",
            "100,840,true", "1022,960,true", "15,7,true", "40,963,false"})
    @DisplayName("combFilterConst matches the scalar kernel, including the recursive in-place filter")
    void combFilterConst(int T, int N, boolean inPlace) {
        var kernels = vector();
        var random = new Random(T * 7L + N);
        var history = T + 3;
        var x = gaussian(random, history + N, 5000f);
        var expected = inPlace ? x.clone() : new float[history + N];
        var actual = inPlace ? x.clone() : new float[history + N];
        var xe = inPlace ? expected : x;
        var xa = inPlace ? actual : x;
        SCALAR.combFilterConst(expected, history, xe, history, T, N, -0.3f, 0.2f, -0.1f);
        kernels.combFilterConst(actual, history, xa, history, T, N, -0.3f, 0.2f, -0.1f);
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "shift {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("fftSoa matches the scalar split-layout FFT bit for bit")
    void fftSoa(int shift) {
        var kernels = vector();
        var st = CeltMode.MODE_48000_960.mdct.kfft[shift];
        var random = new Random(100 + shift);
        for (var trial = 0; trial < 5; trial++) {
            var data = gaussian(random, 2 * st.nfft + 7, 3000f);
            var expected = data.clone();
            var actual = data.clone();
            SCALAR.fftSoa(st, expected, 3, 3 + st.nfft);
            kernels.fftSoa(st, actual, 3, 3 + st.nfft);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    @DisplayName("pvqSearchBest returns the sequential scan's choice on random and tied candidates")
    void pvqSearchBest() {
        var kernels = vector();
        var random = new Random(9);
        for (var trial = 0; trial < 20000; trial++) {
            var n = 2 + random.nextInt(176);
            var x = new float[n + 3];
            var y = new float[n];
            var mode = trial % 4;
            for (var i = 0; i < n; i++) {
                x[i + 3] = switch (mode) {
                    case 0 -> Math.abs((float) random.nextGaussian());
                    case 1 -> 0.25f * (1 + random.nextInt(3));
                    case 2 -> i % 5 == 0 ? 0.5f : Math.abs((float) random.nextGaussian()) * 1e-3f;
                    default -> Math.nextUp(0.3f) * (random.nextInt(2) + 1);
                };
                y[i] = 2 * random.nextInt(mode == 1 ? 2 : 6);
            }
            var xy = mode == 1 ? 1.5f : (float) random.nextDouble() * 4;
            var yy = 1 + random.nextInt(40);
            assertEquals(SCALAR.pvqSearchBest(x, 3, y, n, xy, yy), kernels.pvqSearchBest(x, 3, y, n, xy, yy),
                    "trial " + trial);
        }
    }

    @Test
    @DisplayName("maxabs matches the scalar kernel in magnitude, NaN included")
    void maxabs() {
        var kernels = vector();
        var random = new Random(11);
        for (var trial = 0; trial < 2000; trial++) {
            var n = random.nextInt(2000);
            var x = gaussian(random, n + 5, (float) Math.pow(10, random.nextInt(8) - 3));
            if (trial % 7 == 0 && n > 0) {
                x[5 + random.nextInt(n)] = Float.NaN;
            }
            if (trial % 11 == 0) {
                java.util.Arrays.fill(x, trial % 2 == 0 ? -0f : 0f);
            }
            var expected = SCALAR.maxabs(x, 5, n);
            var actual = kernels.maxabs(x, 5, n);
            assertEquals(Math.abs(expected), Math.abs(actual), 0f, "trial " + trial);
        }
    }

    @ParameterizedTest(name = "len {0}, stride {1}")
    @CsvSource({"176,13", "176,1", "144,12", "96,9", "64,8", "32,5", "22,4", "100,20", "17,8"})
    @DisplayName("expRotation1 matches the scalar recursion bit for bit")
    void expRotation1(int len, int stride) {
        var kernels = vector();
        var random = new Random(len * 13L + stride);
        var x = gaussian(random, len + 4, 1f);
        var expected = x.clone();
        var actual = x.clone();
        SCALAR.expRotation1(expected, 2, len, stride, 0.83f, 0.55f);
        kernels.expRotation1(actual, 2, len, stride, 0.83f, 0.55f);
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "shift {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("opusFft through the split kernel matches the interleaved reference FFT")
    void opusFft(int shift) {
        var st = CeltMode.MODE_48000_960.mdct.kfft[shift];
        var random = new Random(shift + 40);
        var in = gaussian(random, 2 * st.nfft, 100f);
        var expected = new float[2 * st.nfft];
        for (var i = 0; i < st.nfft; i++) {
            var rev = st.bitrev[i] << 1;
            expected[rev] = st.scale * in[2 * i];
            expected[rev + 1] = st.scale * in[2 * i + 1];
        }
        KissFft.opusFftImpl(st, expected, 0);
        var actual = new float[2 * st.nfft];
        KissFft.opusFft(st, in, actual, new float[2 * st.nfft]);
        assertArrayEquals(expected, actual);
    }

    @ParameterizedTest(name = "N {0}, stride {1}")
    @CsvSource({"176,1", "176,8", "176,16", "96,32", "48,8", "36,4", "24,2", "64,64", "80,40"})
    @DisplayName("haar1 matches the scalar kernel bit for bit")
    void haar1(int n, int stride) {
        var kernels = vector();
        var random = new Random(n * 5L + stride);
        var x = gaussian(random, n + 3, 1f);
        var expected = x.clone();
        var actual = x.clone();
        SCALAR.haar1(expected, 3, n / stride, stride);
        kernels.haar1(actual, 3, n / stride, stride);
        assertArrayEquals(expected, actual);
    }
}
