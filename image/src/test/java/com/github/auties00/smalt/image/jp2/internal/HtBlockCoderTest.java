package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codes random code-blocks with the high-throughput block encoder at every bit-plane, with and without the SigProp
 * and MagRef passes, decodes them with the block decoder and checks every sample against the reconstruction the
 * passes define: the cleanup pass's midpoint, the refined midpoint of the samples it found significant, and for the
 * SigProp pass only samples whose next bit is set, every one of them that has a significant neighbor in its stripe.
 * The distortion the encoder reports must equal the squared error reduction of that reconstruction.
 */
class HtBlockCoderTest {
    private static final int MAGNITUDE_BITS = 14;

    static Stream<Arguments> blocks() {
        int[][] sizes = {{1, 1}, {1, 7}, {7, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 6}, {7, 9}, {8, 8}, {9, 4}, {16, 4},
                {33, 17}, {64, 64}, {128, 32}, {32, 128}, {1024, 4}, {4, 1024}, {6, 5}, {10, 11}};
        var cases = Stream.<Arguments>builder();
        for (var size : sizes) {
            for (var density : new int[]{100, 30, 3}) {
                for (var causal : new boolean[]{false, true}) {
                    cases.add(Arguments.of(size[0], size[1], density, causal));
                }
            }
        }
        return cases.build();
    }

    @ParameterizedTest(name = "{0}x{1} density {2}% causal {3}")
    @MethodSource("blocks")
    @DisplayName("decodes every bit-plane and pass count to the reconstruction the passes define")
    void roundTrip(int width, int height, int density, boolean causal) {
        var random = new Random(width * 7919L + height * 31L + density + (causal ? 1 : 0));
        var samples = new int[width * height];
        for (var i = 0; i < samples.length; i++) {
            if (random.nextInt(100) < density) {
                var bits = 1 + random.nextInt(MAGNITUDE_BITS - 1);
                var magnitude = random.nextInt(1 << bits);
                samples[i] = random.nextBoolean() ? -magnitude : magnitude;
            }
        }
        var encoder = new HtEncoder();
        var decoder = new HtDecoder();
        var planes = encoder.load(samples, null, 0, width, width, height, 1.0f, true, 1.0, causal);
        for (var plane = 0; plane < planes; plane++) {
            for (var refine = 0; refine < (plane > 0 ? 2 : 1); refine++) {
                encoder.encode(plane, refine == 1);
                var cleanup = encoder.cleanupLength();
                var refinement = refine == 1 ? encoder.sigPropLength() + encoder.magRefLength() : 0;
                for (var passes = 1; passes <= (refinement > 0 ? 3 : 1); passes++) {
                    var buffer = decoder.buffer(cleanup + refinement);
                    System.arraycopy(encoder.bytes(), 0, buffer, 0, cleanup + refinement);
                    decoder.decode(cleanup, refinement, passes, MAGNITUDE_BITS, MAGNITUDE_BITS - plane, width, height,
                            causal);
                    check(samples, decoder.data(), width, height, plane, passes, encoder, refine == 1, causal);
                }
            }
        }
    }

    private static void check(int[] samples, int[] decoded, int width, int height, int plane, int passes,
                              HtEncoder encoder, boolean refined, boolean causal) {
        var where = " at plane " + plane + " with " + passes + " passes";
        var gain = 0.0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var magnitude = Math.abs(samples[i]);
                var mu = magnitude >> plane;
                var value = decoded[i];
                int expected;
                if (mu != 0) {
                    expected = passes == 3 ? ((2 * (magnitude >> (plane - 1)) + 1) << (plane - 1))
                            : (2 * mu + 1) << plane;
                } else if (passes > 1) {
                    var next = (magnitude >> (plane - 1)) & 1;
                    if (value != 0) {
                        assertEquals(1, next, "SigProp made sample " + i + " significant" + where);
                        expected = 3 << (plane - 1);
                    } else {
                        if (next == 1) {
                            assertTrue(!hasSignificantNeighbor(samples, width, height, x, y, plane, causal),
                                    "SigProp missed sample " + i + where);
                        }
                        expected = 0;
                    }
                } else {
                    expected = 0;
                }
                if (samples[i] < 0) {
                    expected = -expected;
                }
                assertEquals(expected, value, "sample " + i + where);
                var reconstruction = Math.abs(value) / 2;
                gain += (double) magnitude * magnitude
                        - (double) (magnitude - reconstruction) * (magnitude - reconstruction);
            }
        }
        if (passes == 1 || refined) {
            assertEquals(gain, encoder.distortion(passes == 1 ? 0 : passes - 1), 1e-6, "distortion" + where);
        }
    }

    // a neighbor in the same four-row stripe, whose membership neither context formation mode limits
    private static boolean hasSignificantNeighbor(int[] samples, int width, int height, int x, int y, int plane,
                                                  boolean causal) {
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                var nx = x + dx;
                var ny = y + dy;
                if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= width || ny >= height || ny / 4 != y / 4) {
                    continue;
                }
                if ((Math.abs(samples[ny * width + nx]) >> plane) != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("rejects a cleanup segment whose suffix length exceeds it")
    void badSuffix() {
        var decoder = new HtDecoder();
        var buffer = decoder.buffer(4);
        Arrays.fill(buffer, 0, 4, (byte) 0xFF);
        assertThrows(MediaException.Malformed.class, () -> decoder.decode(4, 0, 1, 10, 3, 8, 8, false));
    }

    @Test
    @DisplayName("decodes garbage segments without failing other than as malformed")
    void garbage() {
        var random = new Random(15444);
        var decoder = new HtDecoder();
        for (var trial = 0; trial < 2000; trial++) {
            var width = 1 + random.nextInt(64);
            var height = 1 + random.nextInt(Math.max(1, 4096 / width));
            height = Math.min(height, 64);
            var cleanup = 2 + random.nextInt(300);
            var refinement = random.nextInt(60);
            var buffer = decoder.buffer(cleanup + refinement);
            for (var i = 0; i < cleanup + refinement; i++) {
                buffer[i] = (byte) random.nextInt(256);
            }
            var passes = 1 + random.nextInt(3);
            try {
                decoder.decode(cleanup, refinement, passes, 20, 1 + random.nextInt(20), width, height,
                        random.nextBoolean());
            } catch (MediaException.Malformed expected) {
                // rejecting the data is the other allowed outcome
            }
        }
    }
}
