package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the encoder's forward MDCT followed by the decoder's synthesis filterbank
 * reconstructs the input across every window sequence transition and shape change.
 */
class FilterbankTest {
    @ParameterizedTest(name = "shape pattern {0}, {1}-sample frames")
    @CsvSource({"0, 1024", "1, 1024", "2, 1024", "3, 1024", "0, 960", "1, 960", "2, 960", "3, 960"})
    @DisplayName("reconstructs the input through long, start, short and stop windows")
    void perfectReconstruction(int shapePattern, int length) {
        int[] sequences = {0, 0, 1, 2, 2, 3, 0, 1, 2, 3, 1, 2, 3, 0, 0};
        var frames = sequences.length;
        var random = new Random(shapePattern);
        var input = new float[(frames + 1) * length];
        for (var i = length; i < frames * length; i++) {
            input[i] = (float) (random.nextGaussian() * 0.2);
        }
        for (var kernel : new AacKernel[]{AacKernels.scalar(), AacKernels.vector()}) {
            var forward = new ForwardMdct(length, kernel);
            var synthesis = new Synthesis(length, kernel);
            var spectrum = new float[length];
            var overlap = new float[length];
            var output = new float[frames * length];
            var previousShape = 0;
            for (var frame = 0; frame < frames; frame++) {
                var shape = switch (shapePattern) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> frame % 2;
                    default -> sequences[frame] == 0 || sequences[frame] == 3 ? 1 : 0;
                };
                forward.transform(input, frame * length, sequences[frame], previousShape, shape, spectrum);
                synthesis.synthesize(spectrum, sequences[frame], previousShape, shape, overlap, output, frame * length);
                previousShape = shape;
            }
            double error = 0;
            double signal = 0;
            for (var i = length; i < (frames - 1) * length; i++) {
                var expected = input[i];
                var d = output[i] - expected;
                signal += expected * expected;
                error += d * d;
            }
            var snr = 10 * Math.log10(signal / error);
            assertTrue(snr > 120, "reconstruction SNR " + snr);
        }
    }
}
