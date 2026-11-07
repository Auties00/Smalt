package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2DecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HTJ2K codestreams whose maximum shift region of interest gives their code-blocks 62 to 74 magnitude
 * bit-planes, written by {@link HtRoiWriter}: lossless ones must give back the samples, those without a wavelet
 * transform also when truncated to a bit-plane with refinement passes, which the test reconstructs as the passes
 * and ISO/IEC 15444-1 H.1 define; a shift beyond the HTJ2K bound and damaged codestreams fail only as malformed.
 */
class Jp2HtRoiTest {
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    private static long[] decode(byte[] codestream) {
        var channel = Jp2Decoder.decodeChannels(MediaSource.of(codestream),
                Jp2DecoderOptions.builder().limits(LIMITS).build()).channels().getFirst();
        assertTrue(channel.wide());
        assertEquals(HtRoiWriter.PRECISION, channel.precision());
        return channel.wideSamples();
    }

    /** Samples of the image, inside the rectangle at full depth and outside it below {@code 2^(shift - 2)}. */
    private static long[] samples(Random random, int width, int height, int shift, int depth) {
        var samples = new long[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var inside = inside(x, y, width, height);
                var bits = inside ? depth : Math.min(depth, shift - 2);
                var magnitude = random.nextLong() >>> (64 - bits);
                samples[y * width + x] = random.nextBoolean() ? -magnitude : magnitude;
            }
        }
        // the extremes of the 38-bit range inside the region
        samples[(height / 2) * width + width / 2] = (1L << 37) - 1;
        samples[(height / 2) * width + width / 2 + 1] = -((1L << 37) - 1);
        return samples;
    }

    private static boolean inside(int x, int y, int width, int height) {
        return x >= width / 4 && x < width * 3 / 4 && y >= height / 4 && y < height * 3 / 4;
    }

    @ParameterizedTest(name = "shift {0}")
    @ValueSource(ints = {24, 25, 26, 30, 33, 36, 37})
    @DisplayName("a lossless codestream without a wavelet transform decodes to its samples")
    void losslessNoTransform(int shift) {
        var random = new Random(shift);
        var width = 70;
        var height = 45;
        var samples = samples(random, width, height, shift, 37);
        var writer = new HtRoiWriter(width, height);
        writer.shift = shift;
        var codestream = writer.write(samples.clone(), index -> inside(index % width, index / width, width, height));
        assertArrayEquals(samples, decode(codestream));
    }

    @ParameterizedTest(name = "causal {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("a lossless two-level 5-3 codestream at the largest shift decodes to its samples")
    void losslessWavelet(boolean causal) {
        var random = new Random(causal ? 2 : 1);
        var width = 77;
        var height = 52;
        var samples = new long[width * height];
        for (var i = 0; i < samples.length; i++) {
            var magnitude = random.nextLong() >>> (64 - 33);
            samples[i] = random.nextBoolean() ? -magnitude : magnitude;
        }
        var coefficients = samples.clone();
        Part2Forward.decompose(coefficients, width, 0, 0, 0, 0, width, height, Part2Forward.Tree.dyadic(2), null);
        var writer = new HtRoiWriter(width, height);
        writer.levels = 2;
        writer.codeBlock = 4;
        writer.causal = causal;
        writer.shift = 37;
        // a region of every sub-band in the coefficient domain: the high half of the buffer's columns
        var codestream = writer.write(coefficients, index -> (index % width) * 2 >= width || index % 7 == 0);
        assertArrayEquals(samples, decode(codestream));
    }

    @ParameterizedTest(name = "plane {0}")
    @ValueSource(ints = {1, 5, 20, 30, 40, 47, 60})
    @DisplayName("a codestream truncated to a bit-plane with refinement passes reconstructs the defined values")
    void truncated(int plane) {
        var random = new Random(plane);
        var width = 40;
        var height = 33;
        var shift = 37;
        var samples = samples(random, width, height, shift, 37);
        var writer = new HtRoiWriter(width, height);
        writer.shift = shift;
        writer.plane = plane;
        writer.refine = true;
        var codestream = writer.write(samples.clone(), index -> inside(index % width, index / width, width, height));
        var decoded = decode(codestream);
        for (var i = 0; i < samples.length; i++) {
            var magnitude = BigInteger.valueOf(samples[i]).abs();
            if (inside(i % width, i / width, width, height)) {
                magnitude = magnitude.shiftLeft(shift);
            }
            // the MagRef pass refines every sample the cleanup pass found significant, by the bit below its plane,
            // and the decoder keeps one fractional bit: twice the value, plus the midpoint of what is missing
            BigInteger twice;
            var mu = magnitude.shiftRight(plane);
            if (mu.signum() != 0) {
                twice = magnitude.shiftRight(plane - 1).shiftLeft(1).add(BigInteger.ONE).shiftLeft(plane - 1);
            } else {
                // only samples whose next bit is set can become significant in the SigProp pass, which this one
                // does exactly when the decoder reports it
                twice = decoded[i] == 0 ? BigInteger.ZERO : BigInteger.valueOf(3).shiftLeft(plane - 1);
                if (decoded[i] != 0) {
                    assertTrue(magnitude.testBit(plane - 1), "sample " + i + " made significant");
                }
            }
            if (twice.bitLength() > shift) {
                twice = twice.shiftRight(shift);
            }
            var expected = twice.shiftRight(1).longValueExact();
            assertEquals(samples[i] < 0 ? -expected : expected, decoded[i], "sample " + i);
        }
    }

    @ParameterizedTest(name = "shift {0}, {1} levels")
    @CsvSource({"25, 0", "31, 0", "37, 0", "37, 1"})
    @DisplayName("a lossless codestream with an arbitrary rectangular region decodes to its samples")
    void arbitraryRegion(int shift, int levels) {
        var random = new Random(shift * 10L + levels);
        var width = 70;
        var height = 45;
        var samples = samples(random, width, height, shift, levels == 0 ? 37 : 34);
        if (levels > 0) {
            // one level of the 5-3 wavelet keeps 34-bit samples below the 37 bit-planes of the sub-bands
            samples[(height / 2) * width + width / 2] = (1L << 34) - 1;
            samples[(height / 2) * width + width / 2 + 1] = -((1L << 34) - 1);
        }
        var coefficients = samples.clone();
        var tree = Part2Forward.Tree.dyadic(levels);
        if (levels > 0) {
            Part2Forward.decompose(coefficients, width, 0, 0, 0, 0, width, height, tree, null);
        }
        // the rectangle of inside(), carried to the coefficients the 5-3 synthesis of its samples needs (L.2.1)
        var mask = new boolean[width * height];
        for (var i = 0; i < mask.length; i++) {
            mask[i] = inside(i % width, i / width, width, height);
        }
        Part2Forward.decomposeMask(mask, width, 0, 0, 0, 0, width, height, tree, Part2Forward.LE_GALL);
        var scales = new int[mask.length];
        for (var i = 0; i < mask.length; i++) {
            scales[i] = mask[i] ? shift : 0;
        }
        var writer = new HtRoiWriter(width, height);
        writer.levels = levels;
        writer.regionScales = scales;
        writer.regionSegment = Part2Segments.rgn(0, 1, shift, width / 4, height / 4, width * 3 / 4 - width / 4,
                height * 3 / 4 - height / 4);
        assertArrayEquals(samples, decode(writer.write(coefficients, index -> false)));
    }

    @ParameterizedTest(name = "shift {0} over {1} guard bits and exponent {2}")
    @CsvSource({"64, 2, 9", "66, 2, 7", "70, 1, 4"})
    @DisplayName("a maximum shift of 64 or more over shallow sub-bands decodes to its samples")
    void shiftAboveSixtyThree(int shift, int guardBits, int exponent) {
        var random = new Random(shift);
        var width = 37;
        var height = 29;
        var depth = guardBits + exponent - 1;
        var samples = new long[width * height];
        for (var i = 0; i < samples.length; i++) {
            var magnitude = random.nextLong() >>> (64 - depth);
            samples[i] = random.nextBoolean() ? -magnitude : magnitude;
        }
        var writer = new HtRoiWriter(width, height);
        writer.guardBits = guardBits;
        writer.exponent = exponent;
        writer.shift = shift;
        writer.codeBlock = 3;
        var codestream = writer.write(samples.clone(), index -> inside(index % width, index / width, width, height));
        assertArrayEquals(samples, decode(codestream));
    }

    @Test
    @DisplayName("a codestream decodes as the same coefficients coded without a region of interest")
    void matchesUnshifted() {
        var random = new Random(37);
        var width = 64;
        var height = 64;
        // one level of the 5-3 wavelet keeps these below 2^36, under the shift as background
        var samples = samples(random, width, height, 37, 34);
        samples[(height / 2) * width + width / 2] = (1L << 34) - 1;
        samples[(height / 2) * width + width / 2 + 1] = -((1L << 34) - 1);
        var shifted = new HtRoiWriter(width, height);
        shifted.shift = 37;
        shifted.levels = 1;
        var coefficients = samples.clone();
        Part2Forward.decompose(coefficients, width, 0, 0, 0, 0, width, height, Part2Forward.Tree.dyadic(1), null);
        var plain = new HtRoiWriter(width, height);
        plain.levels = 1;
        var expected = decode(plain.write(coefficients.clone(), index -> false));
        assertArrayEquals(samples, expected);
        assertArrayEquals(expected, decode(shifted.write(coefficients.clone(), index -> index % 3 != 0)));
    }

    @Test
    @DisplayName("rejects a region of interest shift beyond the HTJ2K bound as malformed")
    void shiftBeyondBound() {
        var writer = new HtRoiWriter(16, 16);
        writer.shift = 38;
        var samples = new long[256];
        samples[5] = 1;
        samples[200] = -12345;
        var codestream = writer.write(samples, index -> index == 5);
        assertThrows(MediaException.Malformed.class, () -> decode(codestream));
    }

    @Test
    @DisplayName("damaged codestreams of deep code-blocks fail only as malformed")
    void damaged() {
        var random = new Random(74);
        var writer = new HtRoiWriter(48, 40);
        writer.shift = 37;
        writer.plane = 3;
        writer.refine = true;
        var samples = samples(random, 48, 40, 37, 37);
        var codestream = writer.write(samples, index -> inside(index % 48, index / 48, 48, 40));
        var body = indexOf(codestream);
        for (var trial = 0; trial < 400; trial++) {
            var damaged = codestream.clone();
            if (trial % 4 == 0) {
                damaged = java.util.Arrays.copyOf(damaged, body + random.nextInt(damaged.length - body));
            } else {
                for (var flips = 0; flips < 1 + random.nextInt(8); flips++) {
                    var at = body + random.nextInt(damaged.length - body - 2);
                    damaged[at] = (byte) random.nextInt(256);
                }
            }
            try {
                Jp2Decoder.decodeChannels(MediaSource.of(damaged), Jp2DecoderOptions.builder().limits(LIMITS).build());
            } catch (MediaException.Malformed expected) {
                // rejecting the data is the other allowed outcome
            }
        }
    }

    /** The index of the first byte after the {@code SOD} marker. */
    private static int indexOf(byte[] codestream) {
        for (var i = 0; i + 1 < codestream.length; i++) {
            if ((codestream[i] & 0xFF) == 0xFF && (codestream[i + 1] & 0xFF) == 0x93) {
                return i + 2;
            }
        }
        throw new AssertionError("no SOD marker");
    }
}
