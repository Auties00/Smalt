package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the Vector API encoder kernel is bit-identical to the scalar reference, kernel by
 * kernel on random input and end to end through the encoder.
 */
class Mp3EncoderKernelTest {
    @BeforeEach
    void requireVectorModule() {
        Skip.unless(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "simd:disabled", "Vector API not available");
    }

    private static float[] random(SplittableRandom random, int length, float scale) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = (float) ((random.nextDouble() * 2 - 1) * scale);
        }
        return values;
    }

    @Test
    @DisplayName("the analysis window and matrixing are bit-identical on random input")
    void filterbank() {
        var random = new SplittableRandom(1);
        var scalar = new ScalarMp3EncoderKernel();
        var vector = VectorMp3EncoderKernel.create();
        for (var round = 0; round < 200; round++) {
            var input = random(random, 1088, round % 2 == 0 ? 1 : 3e4f);
            var start = 32 * (round % 18);
            var scalarSums = new float[64];
            var vectorSums = new float[64];
            scalar.window(input, start, Mp3AnalysisFilterbank.WINDOW, scalarSums);
            vector.window(input, start, Mp3AnalysisFilterbank.WINDOW, vectorSums);
            assertArrayEquals(scalarSums, vectorSums);
            var folded = random(random, 32, 2);
            var scalarOut = new float[32];
            var vectorOut = new float[32];
            scalar.matrix(folded, Mp3AnalysisFilterbank.MATRIX, scalarOut);
            vector.matrix(folded, Mp3AnalysisFilterbank.MATRIX, vectorOut);
            assertArrayEquals(scalarOut, vectorOut);
        }
    }

    @Test
    @DisplayName("the MDCT is bit-identical on random input, including the coefficients past the last whole vector")
    void mdct() {
        var random = new SplittableRandom(2);
        var scalar = new ScalarMp3EncoderKernel();
        var vector = VectorMp3EncoderKernel.create();
        for (var round = 0; round < 100; round++) {
            var input = random(random, 32 * 36, round % 2 == 0 ? 1 : 1e4f);
            var expected = new float[576];
            var actual = new float[576];
            scalar.mdct(input, Mp3HybridTransform.LONG_TABLE, expected);
            vector.mdct(input, Mp3HybridTransform.LONG_TABLE, actual);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    @DisplayName("quantization is bit-identical for every run alignment, ties and saturating values")
    void quantize() {
        var random = new SplittableRandom(3);
        var scalar = new ScalarMp3EncoderKernel();
        var vector = VectorMp3EncoderKernel.create();
        for (var round = 0; round < 500; round++) {
            var pow34 = random(random, 576, round % 3 == 0 ? 10 : 1e5f);
            for (var i = 0; i < 576; i++) {
                pow34[i] = Math.abs(pow34[i]);
            }
            pow34[random.nextInt(576)] = 0.5946f;
            pow34[random.nextInt(576)] = Float.MAX_VALUE;
            var from = random.nextInt(300);
            var to = from + random.nextInt(576 - from + 1);
            var scale = (float) Math.pow(2, random.nextDouble() * 20 - 10);
            var expected = new int[576];
            var actual = new int[576];
            assertEquals(scalar.quantize(pow34, from, to, scale, expected), vector.quantize(pow34, from, to, scale, actual));
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    @DisplayName("an encoder on the vector kernel writes the same frames as one on the scalar kernel")
    void encoder() {
        var input = Mp3Encoding.signal(Mp3Encoding.Shape.TRANSIENTS, 2, 44100, 1152 * 70, 47);
        for (var options : new Mp3EncoderOptions[]{Mp3EncoderOptions.defaults(), Mp3EncoderOptions.ofVariableBitrate(3)}) {
            var scalarFrames = new java.io.ByteArrayOutputStream();
            var vectorFrames = new java.io.ByteArrayOutputStream();
            encode(new Mp3FrameEncoder(options, new ScalarMp3EncoderKernel(), scalarFrames::writeBytes), input);
            encode(new Mp3FrameEncoder(options, VectorMp3EncoderKernel.create(), vectorFrames::writeBytes), input);
            assertArrayEquals(scalarFrames.toByteArray(), vectorFrames.toByteArray());
        }
    }

    private static void encode(Mp3FrameEncoder engine, float[][] input) {
        var granule = new float[input.length][576];
        for (var offset = 0; offset + 576 <= input[0].length; offset += 576) {
            for (var c = 0; c < input.length; c++) {
                System.arraycopy(input[c], offset, granule[c], 0, 576);
            }
            engine.push(granule, 0);
        }
        engine.finish();
    }
}
