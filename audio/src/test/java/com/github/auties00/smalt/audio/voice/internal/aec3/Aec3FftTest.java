package com.github.auties00.smalt.audio.voice.internal.aec3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The 128-point AEC3 transform against a naive DFT, its round trip, and its windowings.
 */
@DisplayName("AEC3 FFT")
class Aec3FftTest {
    @Test
    @DisplayName("matches the DFT and the inverse returns 64 times the signal")
    void forwardAndInverse() {
        var random = new SplittableRandom(9);
        for (var trial = 0; trial < 20; trial++) {
            var x = new float[128];
            for (var i = 0; i < 128; i++) {
                x[i] = (float) random.nextGaussian();
            }
            var a = x.clone();
            var data = new FftData();
            Aec3Fft.fft(a, data.re, data.im, 0);
            for (var k = 0; k <= 64; k++) {
                double re = 0;
                double im = 0;
                for (var j = 0; j < 128; j++) {
                    re += x[j] * Math.cos(2 * Math.PI * j * k / 128);
                    im += x[j] * Math.sin(2 * Math.PI * j * k / 128);
                }
                assertEquals(re, data.re[k], 1e-3, "re " + k);
                assertEquals(k == 0 || k == 64 ? 0 : im, data.im[k], 1e-3, "im " + k);
            }
            var back = new float[128];
            Aec3Fft.ifft(data.re, data.im, 0, back);
            for (var i = 0; i < 128; i++) {
                assertEquals(x[i], back[i] / 64, 1e-5);
            }
        }
    }

    @Test
    @DisplayName("the windowed padded transform windows old and new samples and keeps the new ones")
    void windowedPaddedFft() {
        var fft = new Aec3Fft();
        var old = new float[64];
        var v = new float[64];
        for (var i = 0; i < 64; i++) {
            old[i] = i;
            v[i] = 64 - i;
        }
        var expected = new float[128];
        for (var i = 0; i < 64; i++) {
            expected[i] = old[i] * Aec3Fft.SQRT_HANNING_128[i];
            expected[64 + i] = v[i] * Aec3Fft.SQRT_HANNING_128[64 + i];
        }
        var reference = new FftData();
        Aec3Fft.fft(expected, reference.re, reference.im, 0);
        var out = new FftData();
        fft.windowedPaddedFft(v, 0, old, out);
        for (var k = 0; k < 65; k++) {
            assertEquals(reference.re[k], out.re[k]);
            assertEquals(reference.im[k], out.im[k]);
        }
        for (var i = 0; i < 64; i++) {
            assertEquals(v[i], old[i]);
        }
    }
}
