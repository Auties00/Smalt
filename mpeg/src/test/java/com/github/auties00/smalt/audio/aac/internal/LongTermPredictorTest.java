package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the forward MDCT of long term prediction: against the direct definition, against the
 * encoder's transform, and as the exact inverse of the decoder's synthesis filterbank, which the
 * predicted spectrum must match for the prediction to cancel.
 */
class LongTermPredictorTest {
    @ParameterizedTest(name = "{0} lines")
    @ValueSource(ints = {1024, 960})
    @DisplayName("equals the direct MDCT definition")
    void definition(int length) {
        var random = new Random(length);
        var z = new float[2 * length];
        for (var i = 0; i < z.length; i++) {
            z[i] = (float) random.nextGaussian() * 0.1f;
        }
        var spectrum = new float[length];
        new LongTermPredictor(length, AacKernels.scalar()).forwardMdct(z, spectrum);
        double error = 0;
        double energy = 0;
        for (var k = 0; k < length; k++) {
            double sum = 0;
            for (var n = 0; n < 2 * length; n++) {
                sum += z[n] * Math.cos(Math.PI / length * (n + 0.5 + length / 2.0) * (k + 0.5));
            }
            var expected = 2 * 32768.0 * sum;
            error += (expected - spectrum[k]) * (expected - spectrum[k]);
            energy += expected * expected;
        }
        assertTrue(10 * Math.log10(energy / error) > 100, "SNR against the definition");
    }

    @ParameterizedTest(name = "{0} lines")
    @ValueSource(ints = {1024, 960})
    @DisplayName("is inverted by the synthesis filterbank across window sequences and shapes")
    void inverse(int length) {
        int[] sequences = {0, 0, 1, 2, 3, 0, 1, 2, 2, 3, 1, 3, 0};
        int[] shapes = {0, 1, 1, 0, 1, 0, 0, 1, 0, 0, 1, 1, 0};
        var frames = sequences.length;
        var random = new Random(7);
        var input = new float[(frames + 1) * length];
        for (var i = length; i < frames * length; i++) {
            input[i] = (float) (random.nextGaussian() * 0.2);
        }
        for (var kernel : new AacKernel[]{AacKernels.scalar(), AacKernels.vector()}) {
            var predictor = new LongTermPredictor(length, kernel);
            var synthesis = new Synthesis(length, kernel);
            var windowed = new float[2 * length];
            var spectrum = new float[length];
            var overlap = new float[length];
            var output = new float[frames * length];
            var previousShape = 0;
            for (var frame = 0; frame < frames; frame++) {
                var sequence = sequences[frame];
                if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
                    shortSpectrum(input, frame * length, length, previousShape, shapes[frame], spectrum);
                } else {
                    var window = synthesis.composite(sequence, previousShape, shapes[frame]);
                    for (var i = 0; i < 2 * length; i++) {
                        windowed[i] = input[frame * length + i] * window[i];
                    }
                    predictor.forwardMdct(windowed, spectrum);
                }
                synthesis.synthesize(spectrum, sequence, previousShape, shapes[frame], overlap, output, frame * length);
                previousShape = shapes[frame];
            }
            double error = 0;
            double signal = 0;
            for (var i = length; i < (frames - 1) * length; i++) {
                var d = output[i] - input[i];
                signal += input[i] * input[i];
                error += d * d;
            }
            assertTrue(10 * Math.log10(signal / error) > 100, "reconstruction SNR");
        }
    }

    @Test
    @DisplayName("matches the encoder's forward transform for 1024-line frames")
    void encoderTransform() {
        var random = new Random(3);
        var input = new float[2048];
        for (var i = 0; i < input.length; i++) {
            input[i] = (float) random.nextGaussian() * 0.3f;
        }
        var kernel = AacKernels.scalar();
        var synthesis = new Synthesis(1024, kernel);
        var forward = new ForwardMdct(kernel);
        var predictor = new LongTermPredictor(1024, kernel);
        for (var sequence : new int[]{ChannelStream.ONLY_LONG_SEQUENCE, ChannelStream.LONG_START_SEQUENCE,
                ChannelStream.LONG_STOP_SEQUENCE}) {
            var expected = new float[1024];
            forward.transform(input, 0, sequence, 1, 0, expected);
            var window = synthesis.composite(sequence, 1, 0);
            var windowed = new float[2048];
            for (var i = 0; i < 2048; i++) {
                windowed[i] = input[i] * window[i];
            }
            var actual = new float[1024];
            predictor.forwardMdct(windowed, actual);
            double error = 0;
            double energy = 0;
            for (var k = 0; k < 1024; k++) {
                error += (expected[k] - actual[k]) * (expected[k] - actual[k]);
                energy += expected[k] * expected[k];
            }
            assertTrue(10 * Math.log10(energy / error) > 100, "SNR against the encoder, sequence " + sequence);
        }
    }

    /**
     * Computes the eight short-window spectra of a frame with the direct MDCT definition, the
     * windows placed as the synthesis filterbank places them.
     */
    private static void shortSpectrum(float[] input, int offset, int length, int previousShape, int shape, float[] spectrum) {
        var m = length / 8;
        var start = (length - m) / 2;
        var rising0 = Windows.of(previousShape, 2 * m);
        var rising = Windows.of(shape, 2 * m);
        for (var window = 0; window < 8; window++) {
            var base = offset + start + window * m;
            var up = window == 0 ? rising0 : rising;
            for (var k = 0; k < m; k++) {
                double sum = 0;
                for (var n = 0; n < 2 * m; n++) {
                    var w = n < m ? up[n] : rising[n];
                    sum += input[base + n] * w * Math.cos(Math.PI / m * (n + 0.5 + m / 2.0) * (k + 0.5));
                }
                spectrum[window * m + k] = (float) (2 * 32768.0 * sum);
            }
        }
    }
}
