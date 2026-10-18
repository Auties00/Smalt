package com.github.auties00.smalt.audio.voice.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The fft4g real transform against a naive DFT, and its round trip.
 */
@DisplayName("Ooura real FFT")
class OouraRealFftTest {
    @ParameterizedTest(name = "n={0}")
    @ValueSource(ints = {8, 16, 64, 128, 256, 512})
    @DisplayName("matches the DFT X[k] = sum x[j] exp(2 pi i j k / n) with the documented packing")
    void forward(int n) {
        var random = new SplittableRandom(n);
        var x = new float[n];
        for (var i = 0; i < n; i++) {
            x[i] = (float) random.nextGaussian();
        }
        var a = x.clone();
        new OouraRealFft(n).forward(a);
        for (var k = 0; k <= n / 2; k++) {
            double re = 0;
            double im = 0;
            for (var j = 0; j < n; j++) {
                re += x[j] * Math.cos(2 * Math.PI * j * k / n);
                im += x[j] * Math.sin(2 * Math.PI * j * k / n);
            }
            var tolerance = 1e-4 * n;
            if (k == 0) {
                assertEquals(re, a[0], tolerance);
            } else if (k == n / 2) {
                assertEquals(re, a[1], tolerance);
            } else {
                assertEquals(re, a[2 * k], tolerance, "re " + k);
                assertEquals(im, a[2 * k + 1], tolerance, "im " + k);
            }
        }
    }

    @ParameterizedTest(name = "n={0}")
    @ValueSource(ints = {8, 64, 256})
    @DisplayName("the inverse returns n / 2 times the signal")
    void roundTrip(int n) {
        var random = new SplittableRandom(n + 1);
        var x = new float[n];
        for (var i = 0; i < n; i++) {
            x[i] = (float) random.nextGaussian();
        }
        var fft = new OouraRealFft(n);
        var a = x.clone();
        fft.forward(a);
        fft.inverse(a);
        for (var i = 0; i < n; i++) {
            assertEquals(x[i], a[i] * 2.f / n, 1e-5);
        }
    }

    @ParameterizedTest(name = "n={0}")
    @ValueSource(ints = {0, 4, 100, -8})
    @DisplayName("rejects lengths that are not powers of two of at least 8")
    void rejects(int n) {
        assertThrows(IllegalArgumentException.class, () -> new OouraRealFft(n));
    }
}
