package com.github.auties00.smalt.audio.aac.internal.sbr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the QMF banks against direct evaluations of their defining sums and checks that the
 * analysis followed by either synthesis reconstructs the input.
 */
class QmfTest {
    private static float[] tones(int length) {
        var input = new float[length];
        for (var i = 0; i < input.length; i++) {
            input[i] = (float) (0.2 * Math.sin(2 * Math.PI * 700.0 / 24000 * i)
                    + 0.2 * Math.sin(2 * Math.PI * 1300.0 / 24000 * i + 1)
                    + 0.2 * Math.sin(2 * Math.PI * 3100.0 / 24000 * i + 2)
                    + 0.2 * Math.sin(2 * Math.PI * 5900.0 / 24000 * i + 3));
        }
        return input;
    }

    @Test
    @DisplayName("analysis matches the direct sum of ISO/IEC 14496-3 on random input")
    void analysisMatchesDefinition() {
        var random = new Random(1);
        var frames = 3;
        var input = new float[1024 * frames];
        for (var i = 0; i < input.length; i++) {
            input[i] = random.nextFloat() * 2 - 1;
        }
        var analysis = new QmfAnalysis(SbrKernels.scalar());
        var re = new float[32 * 32];
        var im = new float[32 * 32];
        var frame = new float[1024];
        double worst = 0;
        for (var f = 0; f < frames; f++) {
            System.arraycopy(input, 1024 * f, frame, 0, 1024);
            analysis.analyze(frame, 32, re, im, 0);
            for (var slot = 0; slot < 32; slot++) {
                var newest = 1024 * f + 32 * slot + 31;
                var u = new double[64];
                for (var n = 0; n < 64; n++) {
                    for (var j = 0; j < 5; j++) {
                        var index = newest - n - 64 * j;
                        var x = index >= 0 ? input[index] * 32768.0 : 0;
                        u[n] += x * SbrTables.QMF_WINDOW[2 * (n + 64 * j)];
                    }
                }
                for (var k = 0; k < 32; k++) {
                    double sumRe = 0;
                    double sumIm = 0;
                    for (var n = 0; n < 64; n++) {
                        var angle = Math.PI * (k + 0.5) * (2 * n - 0.5) / 64;
                        sumRe += 2 * u[n] * Math.cos(angle);
                        sumIm += 2 * u[n] * Math.sin(angle);
                    }
                    worst = Math.max(worst, Math.abs(sumRe - re[32 * slot + k]));
                    worst = Math.max(worst, Math.abs(sumIm - im[32 * slot + k]));
                }
            }
        }
        assertTrue(worst < 0.5, "largest deviation " + worst + " on a 16-bit scale");
    }

    @ParameterizedTest(name = "{0} bands")
    @ValueSource(ints = {64, 32})
    @DisplayName("synthesis matches the direct sum of ISO/IEC 14496-3 on random input")
    void synthesisMatchesDefinition(int bands) {
        var random = new Random(bands);
        var slots = 64;
        var re = new float[slots * 64];
        var im = new float[slots * 64];
        for (var i = 0; i < re.length; i++) {
            re[i] = (random.nextFloat() * 2 - 1) * 1000;
            im[i] = (random.nextFloat() * 2 - 1) * 1000;
        }
        var synthesis = new QmfSynthesis(bands, SbrKernels.scalar());
        var out = new float[slots * bands];
        var frameRe = new float[32 * 64];
        var frameIm = new float[32 * 64];
        var frameOut = new float[32 * bands];
        for (var f = 0; f < slots / 32; f++) {
            System.arraycopy(re, f * 32 * 64, frameRe, 0, frameRe.length);
            System.arraycopy(im, f * 32 * 64, frameIm, 0, frameIm.length);
            synthesis.synthesize(frameRe, frameIm, 32, frameOut, 0);
            System.arraycopy(frameOut, 0, out, f * 32 * bands, frameOut.length);
        }
        var offset = bands == 64 ? 255.0 : 127.5;
        var v = new double[20 * bands];
        double worst = 0;
        for (var slot = 0; slot < slots; slot++) {
            System.arraycopy(v, 0, v, 2 * bands, 18 * bands);
            for (var n = 0; n < 2 * bands; n++) {
                double sum = 0;
                for (var k = 0; k < bands; k++) {
                    var angle = Math.PI * (k + 0.5) * (2 * n - offset) / (2 * bands);
                    sum += re[64 * slot + k] * Math.cos(angle) - im[64 * slot + k] * Math.sin(angle);
                }
                v[n] = sum / 64;
            }
            var step = 64 / bands;
            for (var j = 0; j < bands; j++) {
                double sum = 0;
                for (var i = 0; i < 5; i++) {
                    sum += v[4 * bands * i + j] * SbrTables.QMF_WINDOW[step * (2 * bands * i + j)];
                    sum += v[4 * bands * i + 3 * bands + j] * SbrTables.QMF_WINDOW[step * (2 * bands * i + bands + j)];
                }
                worst = Math.max(worst, Math.abs(sum / 32768 - out[bands * slot + j]));
            }
        }
        assertTrue(worst < 1e-5, "largest deviation " + worst);
    }

    @ParameterizedTest(name = "{0} bands")
    @ValueSource(ints = {64, 32})
    @DisplayName("analysis followed by synthesis reconstructs the input with the filterbank delay")
    void roundTrip(int bands) {
        var frames = 8;
        var input = tones(1024 * frames);
        var analysis = new QmfAnalysis(SbrKernels.scalar());
        var synthesis = new QmfSynthesis(bands, SbrKernels.scalar());
        var outLength = 32 * bands;
        var output = new float[outLength * frames];
        var lowRe = new float[32 * 32];
        var lowIm = new float[32 * 32];
        var re = new float[32 * 64];
        var im = new float[32 * 64];
        var frame = new float[1024];
        var out = new float[outLength];
        for (var f = 0; f < frames; f++) {
            System.arraycopy(input, f * 1024, frame, 0, 1024);
            analysis.analyze(frame, 32, lowRe, lowIm, 0);
            for (var s = 0; s < 32; s++) {
                System.arraycopy(lowRe, s * 32, re, s * 64, 32);
                System.arraycopy(lowIm, s * 32, im, s * 64, 32);
            }
            synthesis.synthesize(re, im, 32, out, 0);
            System.arraycopy(out, 0, output, f * outLength, outLength);
        }
        var ratio = bands / 32;
        var delay = bands == 64 ? 578 : 289;
        double error = 0;
        double energy = 0;
        for (var i = 3000; i < 6000; i++) {
            var d = output[i * ratio + delay] - input[i];
            error += d * d;
            energy += input[i] * input[i];
        }
        var snr = 10 * Math.log10(energy / error);
        assertTrue(snr > 55, "reconstruction SNR " + snr + " dB");
    }

    @Test
    @DisplayName("the low delay analysis followed by the 32-band low delay synthesis reconstructs the input")
    void lowDelayRoundTrip() {
        // the low delay banks pair their own rotations; any other pairing of the CLD prototype aliases
        var bands = 32;
        var frames = 8;
        var input = tones(1024 * frames);
        var analysis = new QmfAnalysis(SbrKernels.scalar(), true);
        var synthesis = new QmfSynthesis(bands, SbrKernels.scalar(), true);
        var outLength = 32 * bands;
        var output = new float[outLength * frames];
        var lowRe = new float[32 * 32];
        var lowIm = new float[32 * 32];
        var re = new float[32 * 64];
        var im = new float[32 * 64];
        var frame = new float[1024];
        var out = new float[outLength];
        for (var f = 0; f < frames; f++) {
            System.arraycopy(input, f * 1024, frame, 0, 1024);
            analysis.analyze(frame, 32, lowRe, lowIm, 0);
            for (var s = 0; s < 32; s++) {
                System.arraycopy(lowRe, s * 32, re, s * 64, 32);
                System.arraycopy(lowIm, s * 32, im, s * 64, 32);
            }
            synthesis.synthesize(re, im, 32, out, 0);
            System.arraycopy(out, 0, output, f * outLength, outLength);
        }
        var ratio = bands / 32;
        var best = Double.NEGATIVE_INFINITY;
        var bestDelay = -1;
        for (var delay = 0; delay < 1200; delay++) {
            double error = 0;
            double energy = 0;
            for (var i = 3000; i < 6000; i++) {
                var d = output[i * ratio + delay] - input[i];
                error += d * d;
                energy += input[i] * input[i];
            }
            var snr = 10 * Math.log10(energy / error);
            if (snr > best) {
                best = snr;
                bestDelay = delay;
            }
        }
        assertTrue(best > 50, "reconstruction SNR " + best + " dB at delay " + bestDelay);
        // the CLD prototype has a much shorter delay than the 640-tap prototype of the standard bank
        assertTrue(bestDelay < 289, "delay " + bestDelay);
    }
}
