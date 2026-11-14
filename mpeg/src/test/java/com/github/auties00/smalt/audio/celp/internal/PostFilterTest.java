package com.github.auties00.smalt.audio.celp.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the post-processor with the equations of ISO/IEC 14496-3 Annex 3.A evaluated directly over a whole signal:
 * {@code H_f(z) = A(z / gamma_n) / A(z / gamma_d)}, the pitch filter {@code (1 + gamma_p g_a z^-T) / (1 + gamma_p g_a)}
 * on the residual, {@code H_t(z) = 1 - gamma_t z^-1}, and the gain {@code g(n) = 0.95 g(n - 1) + 0.05 G} from
 * {@code g(-1) = 0}.
 */
@DisplayName("CELP post-processor")
class PostFilterTest {
    private static final int ORDER = 10;

    private static final int SUBFRAME = 40;

    private static final int SUBFRAMES = 12;

    /**
     * {@return the synthesis coefficients of a stable filter, from evenly spread LSPs}
     */
    private static double[] coefficients() {
        var lsp = new double[ORDER];
        for (var i = 0; i < ORDER; i++) {
            lsp[i] = (i + 1) / (double) (ORDER + 1) + 0.01 * ((i & 1) == 0 ? 1 : -1);
        }
        var a = new double[ORDER];
        Lsp.toLpc(lsp, Math.PI, ORDER, a, new double[4 * ORDER + 8]);
        return a;
    }

    private static double[] signal() {
        var random = new Random(3);
        var signal = new double[SUBFRAME * SUBFRAMES];
        for (var i = 0; i < signal.length; i++) {
            signal[i] = 1000 * random.nextGaussian() + 3000 * StrictMath.sin(i * 0.31);
        }
        return signal;
    }

    /**
     * Evaluates the equations of Annex 3.A.
     *
     * @param delay the pitch delay, or 0 to leave the pitch filter out
     */
    private static double[] expected(double[] x, double[] a, int delay) {
        var residual = new double[x.length];
        for (var n = 0; n < x.length; n++) {
            var r = x[n];
            var weight = 1.0;
            for (var k = 1; k <= ORDER; k++) {
                weight *= 0.65;
                r -= n - k >= 0 ? a[k - 1] * weight * x[n - k] : 0;
            }
            residual[n] = r;
        }
        var pitched = residual.clone();
        if (delay > 0) {
            for (var s = 0; s < SUBFRAMES; s++) {
                var start = s * SUBFRAME;
                var correlation = 0.0;
                var energy = 0.0;
                for (var n = start; n < start + SUBFRAME; n++) {
                    var past = n - delay >= 0 ? residual[n - delay] : 0;
                    correlation += residual[n] * past;
                    energy += past * past;
                }
                var ga = energy > 0 ? Math.min(Math.max(correlation / energy, 0), 1) : 0;
                for (var n = start; n < start + SUBFRAME; n++) {
                    var past = n - delay >= 0 ? residual[n - delay] : 0;
                    pitched[n] = (residual[n] + 0.5 * ga * past) / (1 + 0.5 * ga);
                }
            }
        }
        var formant = new double[x.length];
        for (var n = 0; n < x.length; n++) {
            var y = pitched[n];
            var weight = 1.0;
            for (var k = 1; k <= ORDER; k++) {
                weight *= 0.75;
                y += n - k >= 0 ? a[k - 1] * weight * formant[n - k] : 0;
            }
            formant[n] = y;
        }
        var output = new double[x.length];
        var gain = 0.0;
        for (var s = 0; s < SUBFRAMES; s++) {
            var start = s * SUBFRAME;
            var inputEnergy = 0.0;
            var outputEnergy = 0.0;
            for (var n = start; n < start + SUBFRAME; n++) {
                var tilted = formant[n] - 0.3 * (n > 0 ? formant[n - 1] : 0);
                output[n] = tilted;
                inputEnergy += x[n] * x[n];
                outputEnergy += tilted * tilted;
            }
            var target = Math.sqrt(inputEnergy / outputEnergy);
            for (var n = start; n < start + SUBFRAME; n++) {
                gain = 0.95 * gain + 0.05 * target;
                output[n] *= gain;
            }
        }
        return output;
    }

    private static double[] filtered(double[] x, double[] a, boolean pitch, int delay, double adaptiveGain) {
        var filter = new PostFilter(ORDER, SUBFRAME, pitch);
        var output = x.clone();
        for (var s = 0; s < SUBFRAMES; s++) {
            filter.apply(output, s * SUBFRAME, SUBFRAME, a, delay, adaptiveGain);
        }
        return output;
    }

    private static void assertClose(double[] expected, double[] actual) {
        for (var n = 0; n < expected.length; n++) {
            assertEquals(expected[n], actual[n], 1e-6 * (1 + Math.abs(expected[n])), "sample " + n);
        }
    }

    @Test
    @DisplayName("formant and tilt filters and the gain follow the equations, the gain starting from zero")
    void formantOnly() {
        var x = signal();
        var a = coefficients();
        assertClose(expected(x, a, 0), filtered(x, a, false, 30, 0.9));
    }

    @Test
    @DisplayName("the pitch filter follows its equation on the residual")
    void pitch() {
        var x = signal();
        var a = coefficients();
        assertClose(expected(x, a, 30), filtered(x, a, true, 30, 0.9));
    }

    @ParameterizedTest(name = "delay {0}, adaptive gain {1}")
    @CsvSource({"9, 0.9", "30, 0.4", "30, 0.1"})
    @DisplayName("the pitch filter stays off for a delay below 10 or an adaptive gain up to 0.4")
    void pitchOff(int delay, double adaptiveGain) {
        var x = signal();
        var a = coefficients();
        assertClose(expected(x, a, 0), filtered(x, a, true, delay, adaptiveGain));
    }
}
