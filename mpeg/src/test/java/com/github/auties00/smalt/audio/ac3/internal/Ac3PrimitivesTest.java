package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the building blocks the AC-3 decoder and encoder share: the filter bank's perfect
 * reconstruction across any sequence of long and short blocks, the CRC and its prefix inverse, and
 * the orthogonality of the adaptive hybrid transform's DCT.
 */
class Ac3PrimitivesTest {
    @Nested
    @DisplayName("filter bank")
    class FilterBank {
        @ParameterizedTest(name = "pattern {0}")
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("reconstructs the input through any long and short block sequence")
        void reconstruction(int pattern) {
            var random = new Random(pattern);
            var blocks = 48;
            var input = new float[256 * (blocks + 1)];
            for (var i = 0; i < input.length; i++) {
                input[i] = (float) (random.nextGaussian() * 0.1);
            }
            var forward = new Transform(Ac3Kernels.scalar());
            var inverse = new Transform(Ac3Kernels.scalar());
            var window = Ac3Tables.WINDOW;
            var past = new float[256];
            var delay = new float[256];
            var output = new float[input.length];
            var windowed = new float[512];
            var coefficients = new float[256];
            var synthesized = new float[512];
            for (var b = 0; b < blocks; b++) {
                var start = 256 * b;
                for (var n = 0; n < 256; n++) {
                    windowed[n] = past[n] * window[n];
                    windowed[256 + n] = input[start + n] * window[255 - n];
                }
                System.arraycopy(input, start, past, 0, 256);
                var shortBlocks = pattern == 1 || pattern == 2 && random.nextBoolean();
                forward.forward(windowed, shortBlocks, coefficients);
                inverse.inverse(coefficients, shortBlocks, 2, synthesized);
                for (var n = 0; n < 256; n++) {
                    output[start + n] = synthesized[n] + delay[n];
                    delay[n] = synthesized[256 + n];
                }
            }
            var signal = 0.0;
            var error = 0.0;
            for (var i = 512; i < 256 * blocks; i++) {
                var expected = input[i - 256];
                signal += expected * expected;
                error += (expected - output[i]) * (expected - output[i]);
            }
            var snr = 10 * Math.log10(signal / error);
            assertTrue(snr > 120, "reconstruction SNR " + snr);
        }
    }

    @Nested
    @DisplayName("CRC")
    class Crc {
        @Test
        @DisplayName("leaves a zero residue over data followed by its own CRC")
        void residue() {
            var random = new Random(7);
            var data = new byte[402];
            random.nextBytes(data);
            var crc = Ac3Crc.compute(data, 0, 400);
            data[400] = (byte) (crc >> 8);
            data[401] = (byte) crc;
            assertEquals(0, Ac3Crc.compute(data, 0, 402));
        }

        @Test
        @DisplayName("computes a leading CRC word that zeroes the residue of the words after it")
        void prefix() {
            var random = new Random(11);
            var data = new byte[300];
            random.nextBytes(data);
            data[0] = 0;
            data[1] = 0;
            var residue = Ac3Crc.compute(data, 0, data.length);
            var crc = Ac3Crc.prefix(residue, data.length);
            data[0] = (byte) (crc >> 8);
            data[1] = (byte) crc;
            assertEquals(0, Ac3Crc.compute(data, 0, data.length));
        }
    }

    @Nested
    @DisplayName("adaptive hybrid transform")
    class Hybrid {
        @Test
        @DisplayName("inverts a single coefficient into an orthogonal cosine of the expected energy")
        void orthogonality() {
            var basis = new float[6][6];
            for (var j = 0; j < 6; j++) {
                var in = new float[6];
                in[j] = 1;
                Aht.inverse(in, basis[j]);
            }
            for (var a = 0; a < 6; a++) {
                for (var b = 0; b < 6; b++) {
                    var dot = 0.0;
                    for (var m = 0; m < 6; m++) {
                        dot += basis[a][m] * basis[b][m];
                    }
                    if (a != b) {
                        assertEquals(0, dot, 1e-5, "basis " + a + " and " + b);
                    } else {
                        assertTrue(dot > 0.5, "basis " + a + " energy " + dot);
                    }
                }
            }
        }
    }
}
