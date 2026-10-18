package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the encoder's forward MDCT followed by the decoder's synthesis filterbank
 * reconstructs the input across every window sequence transition and shape change.
 */
class FilterbankTest {
    @ParameterizedTest(name = "shape pattern {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("reconstructs the input through long, start, short and stop windows")
    void perfectReconstruction(int shapePattern) {
        int[] sequences = {0, 0, 1, 2, 2, 3, 0, 1, 2, 3, 1, 2, 3, 0, 0};
        var frames = sequences.length;
        var random = new Random(shapePattern);
        var input = new float[(frames + 1) * 1024];
        for (var i = 1024; i < frames * 1024; i++) {
            input[i] = (float) (random.nextGaussian() * 0.2);
        }
        for (var kernel : new AacKernel[]{AacKernels.scalar(), AacKernels.vector()}) {
            var forward = new ForwardMdct(kernel);
            var synthesis = new Synthesis(1024, kernel);
            var spectrum = new float[1024];
            var overlap = new float[1024];
            var output = new float[frames * 1024];
            var previousShape = 0;
            for (var frame = 0; frame < frames; frame++) {
                var shape = switch (shapePattern) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> frame % 2;
                    default -> sequences[frame] == 0 || sequences[frame] == 3 ? 1 : 0;
                };
                forward.transform(input, frame * 1024, sequences[frame], previousShape, shape, spectrum);
                synthesis.synthesize(spectrum, sequences[frame], previousShape, shape, overlap, output, frame * 1024);
                previousShape = shape;
            }
            double error = 0;
            double signal = 0;
            for (var i = 1024; i < (frames - 1) * 1024; i++) {
                var expected = input[i];
                var d = output[i + 1024 - 1024] - expected;
                signal += expected * expected;
                error += d * d;
            }
            var snr = 10 * Math.log10(signal / error);
            assertTrue(snr > 120, "reconstruction SNR " + snr);
        }
    }
}
