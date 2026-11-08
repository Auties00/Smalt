package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codes code-blocks of 62 to 74 magnitude bit-planes, the depths only a region-of-interest shift reaches, with
 * {@link WideHtEncoder} and decodes them with the block decoder's 128-bit path, checking every sample against the
 * reconstruction the passes define (as {@link HtBlockCoderTest} does for narrower blocks), against the 64-bit path
 * where both apply, and that the test encoder matches {@link HtEncoder} byte for byte where both apply.
 */
class HtWideBlockCoderTest {
    static Stream<Arguments> blocks() {
        int[][] sizes = {{1, 1}, {4, 4}, {5, 6}, {7, 9}, {16, 4}, {33, 17}, {64, 64}, {1024, 4}, {4, 1024}};
        var cases = Stream.<Arguments>builder();
        for (var bits : new int[]{62, 63, 64, 65, 70, 74}) {
            for (var size : sizes) {
                for (var density : new int[]{100, 20}) {
                    cases.add(Arguments.of(bits, size[0], size[1], density, (size[0] + bits) % 2 == 0));
                }
            }
        }
        return cases.build();
    }

    static Block randomBlock(Random random, int width, int height, int density, int bits) {
        var magnitudes = new BigInteger[width * height];
        var negative = new boolean[magnitudes.length];
        for (var i = 0; i < magnitudes.length; i++) {
            magnitudes[i] = BigInteger.ZERO;
            if (random.nextInt(100) < density) {
                // a region of interest leaves a mix of narrow background and full-depth samples
                var width0 = random.nextInt(3) == 0 ? 1 + random.nextInt(20) : bits - random.nextInt(4);
                magnitudes[i] = new BigInteger(Math.min(width0, bits), random);
                negative[i] = random.nextBoolean();
            }
        }
        return new Block(magnitudes, negative, width, height);
    }

    record Block(BigInteger[] magnitudes, boolean[] negative, int width, int height) {
    }

    @ParameterizedTest(name = "{0} bit-planes {1}x{2} density {3}% causal {4}")
    @MethodSource("blocks")
    @DisplayName("decodes 62 to 74 bit-plane blocks to the reconstruction the passes define")
    void roundTrip(int bits, int width, int height, int density, boolean causal) {
        var random = new Random(bits * 1_000_003L + width * 7919L + height * 31L + density);
        var block = randomBlock(random, width, height, density, bits);
        var decoder = new HtDecoder();
        for (var plane : new int[]{0, 1, 2, 7, 31, 32, 33, 40, 63, 64, bits - 2, bits - 1}) {
            if (plane >= bits) {
                continue;
            }
            for (var refine = 0; refine < (plane > 0 ? 2 : 1); refine++) {
                var encoder = new WideHtEncoder(block.magnitudes(), block.negative(), width, height, causal);
                encoder.encode(plane, refine == 1);
                var cleanup = encoder.cleanupBytes();
                var refinement = encoder.refinementBytes();
                for (var passes = 1; passes <= (refinement.length > 0 ? 3 : 1); passes++) {
                    load(decoder, cleanup, refinement);
                    decoder.decode(cleanup.length, refinement.length, passes, bits, bits - plane, width, height,
                            causal);
                    assertNotNull(decoder.highData());
                    check(block, decoded(decoder, width * height), plane, passes, causal);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}x{1}")
    @MethodSource("sizes")
    @DisplayName("the 128-bit path decodes a block as the 64-bit path does")
    void matchesSixtyFourBitPath(int width, int height) {
        var random = new Random(width * 131L + height);
        var block = randomBlock(random, width, height, 60, 59);
        var decoder = new HtDecoder();
        for (var plane : new int[]{0, 3, 20, 58}) {
            var encoder = new WideHtEncoder(block.magnitudes(), block.negative(), width, height, false);
            encoder.encode(plane, plane > 0);
            var cleanup = encoder.cleanupBytes();
            var refinement = encoder.refinementBytes();
            var passes = refinement.length > 0 ? 3 : 1;
            load(decoder, cleanup, refinement);
            decoder.decode(cleanup.length, refinement.length, passes, 61, 61 - plane, width, height, false);
            assertNull(decoder.highData());
            var narrow = Arrays.copyOf(decoder.wideData(), width * height);
            for (var bits = 62; bits <= HtDecoder.MAX_MAGNITUDE_BITS; bits += 6) {
                load(decoder, cleanup, refinement);
                decoder.decode(cleanup.length, refinement.length, passes, bits, bits - plane, width, height, false);
                var huge = decoded(decoder, width * height);
                for (var i = 0; i < narrow.length; i++) {
                    assertEquals(BigInteger.valueOf(narrow[i]), huge[i], "sample " + i + " at plane " + plane);
                }
            }
        }
    }

    static Stream<Arguments> sizes() {
        return Stream.of(Arguments.of(1, 1), Arguments.of(8, 8), Arguments.of(13, 7), Arguments.of(64, 64),
                Arguments.of(2, 1024));
    }

    @Test
    @DisplayName("the test encoder writes the bytes of the 32-bit encoder for narrow magnitudes")
    void matchesHtEncoder() {
        var random = new Random(15444_15);
        for (var trial = 0; trial < 40; trial++) {
            var width = 1 + random.nextInt(64);
            var height = 1 + random.nextInt(64);
            var causal = random.nextBoolean();
            var samples = new int[width * height];
            var magnitudes = new BigInteger[samples.length];
            var negative = new boolean[samples.length];
            for (var i = 0; i < samples.length; i++) {
                var magnitude = random.nextInt(4) == 0 ? 0 : random.nextInt(1 << (1 + random.nextInt(20)));
                negative[i] = magnitude != 0 && random.nextBoolean();
                samples[i] = negative[i] ? -magnitude : magnitude;
                magnitudes[i] = BigInteger.valueOf(magnitude);
            }
            var reference = new HtEncoder();
            var planes = reference.load(samples, null, 0, width, width, height, 1.0f, true, 1.0, causal);
            for (var plane = 0; plane < planes; plane++) {
                reference.encode(plane, plane > 0);
                var wide = new WideHtEncoder(magnitudes, negative, width, height, causal);
                wide.encode(plane, plane > 0);
                var cleanup = reference.cleanupLength();
                assertArrayEquals(Arrays.copyOf(reference.bytes(), cleanup), wide.cleanupBytes(),
                        "cleanup at plane " + plane);
                if (plane > 0) {
                    var length = reference.sigPropLength() + reference.magRefLength();
                    assertArrayEquals(Arrays.copyOfRange(reference.bytes(), cleanup, cleanup + length),
                            wide.refinementBytes(), "refinement at plane " + plane);
                }
            }
        }
    }

    @Test
    @DisplayName("rejects a block of more than 74 magnitude bit-planes as malformed")
    void tooDeep() {
        var block = randomBlock(new Random(7), 8, 8, 50, 20);
        var encoder = new WideHtEncoder(block.magnitudes(), block.negative(), 8, 8, false);
        encoder.encode(0, false);
        var decoder = new HtDecoder();
        load(decoder, encoder.cleanupBytes(), new byte[0]);
        assertThrows(MediaException.Malformed.class,
                () -> decoder.decode(encoder.cleanupBytes().length, 0, 1, 75, 75, 8, 8, false));
    }

    @Test
    @DisplayName("decodes garbage segments of deep blocks without failing other than as malformed")
    void garbage() {
        var random = new Random(15444_74);
        var decoder = new HtDecoder();
        for (var trial = 0; trial < 3000; trial++) {
            var width = 1 + random.nextInt(64);
            var height = Math.min(64, 1 + random.nextInt(Math.max(1, 4096 / width)));
            var cleanup = 2 + random.nextInt(600);
            var refinement = random.nextInt(80);
            var buffer = decoder.buffer(cleanup + refinement);
            for (var i = 0; i < cleanup + refinement; i++) {
                buffer[i] = (byte) random.nextInt(256);
            }
            var bits = 62 + random.nextInt(13);
            try {
                decoder.decode(cleanup, refinement, 1 + random.nextInt(3), bits, random.nextInt(bits + 1), width,
                        height, random.nextBoolean());
                var high = decoder.highData();
                for (var i = 0; i < width * height; i++) {
                    // no magnitude reaches bit 77, so bits 13 to 62 of every high word stay clear
                    assertEquals(0, high[i] & 0x7FFF_FFFF_FFFF_E000L, "sample " + i);
                }
            } catch (MediaException.Malformed expected) {
                // rejecting the data is the other allowed outcome
            }
        }
    }

    private static void load(HtDecoder decoder, byte[] cleanup, byte[] refinement) {
        var buffer = decoder.buffer(cleanup.length + refinement.length);
        System.arraycopy(cleanup, 0, buffer, 0, cleanup.length);
        System.arraycopy(refinement, 0, buffer, cleanup.length, refinement.length);
    }

    /** The signed values of the decoder's 128-bit sign-magnitude samples. */
    static BigInteger[] decoded(HtDecoder decoder, int size) {
        var high = decoder.highData();
        var low = decoder.wideData();
        var result = new BigInteger[size];
        for (var i = 0; i < size; i++) {
            var magnitude = BigInteger.valueOf(high[i] & Long.MAX_VALUE).shiftLeft(64)
                    .or(new BigInteger(Long.toUnsignedString(low[i])));
            result[i] = high[i] < 0 ? magnitude.negate() : magnitude;
        }
        return result;
    }

    private static void check(Block block, BigInteger[] decoded, int plane, int passes, boolean causal) {
        var where = " at plane " + plane + " with " + passes + " passes";
        var width = block.width();
        var height = block.height();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var magnitude = block.magnitudes()[i];
                var mu = magnitude.shiftRight(plane);
                var value = decoded[i];
                BigInteger expected;
                if (mu.signum() != 0) {
                    expected = passes == 3
                            ? magnitude.shiftRight(plane - 1).shiftLeft(1).add(BigInteger.ONE).shiftLeft(plane - 1)
                            : mu.shiftLeft(1).add(BigInteger.ONE).shiftLeft(plane);
                } else if (passes > 1) {
                    var next = magnitude.testBit(plane - 1);
                    if (value.signum() != 0) {
                        assertTrue(next, "SigProp made sample " + i + " significant" + where);
                        expected = BigInteger.valueOf(3).shiftLeft(plane - 1);
                    } else {
                        if (next) {
                            assertTrue(!hasSignificantNeighbor(block, x, y, plane),
                                    "SigProp missed sample " + i + where);
                        }
                        expected = BigInteger.ZERO;
                    }
                } else {
                    expected = BigInteger.ZERO;
                }
                if (block.negative()[i]) {
                    expected = expected.negate();
                }
                assertEquals(expected, value, "sample " + i + where);
            }
        }
    }

    // a neighbor in the same four-row stripe, whose membership neither context formation mode limits
    private static boolean hasSignificantNeighbor(Block block, int x, int y, int plane) {
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                var nx = x + dx;
                var ny = y + dy;
                if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= block.width() || ny >= block.height()
                        || ny / 4 != y / 4) {
                    continue;
                }
                if (block.magnitudes()[ny * block.width() + nx].shiftRight(plane).signum() != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
