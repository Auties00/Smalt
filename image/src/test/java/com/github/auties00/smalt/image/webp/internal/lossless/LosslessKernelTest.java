package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of the lossless building blocks: the pixel kernels (the vector kernel against the
 * scalar reference on random data, and the scalar predictors against hand-computed values), the
 * two-level prefix code tables, prefix coding of lengths and distances, and the bit writer.
 */
class LosslessKernelTest {
    @Nested
    @DisplayName("vector kernel")
    class Vector {
        LosslessDsp vector;
        final ScalarLosslessDsp scalar = new ScalarLosslessDsp();
        final Random random = new Random(0xC0BA17);

        @BeforeEach
        void setUp() {
            Skip.unlessVector(Simd.ENABLED);
            vector = VectorLosslessDsp.create();
        }

        int[] randomPixels(int n) {
            var data = new int[n];
            for (var i = 0; i < n; i++) {
                data[i] = random.nextInt();
            }
            return data;
        }

        @Test
        @DisplayName("adds green like the scalar kernel, in place and out of place")
        void addGreen() {
            for (var count = 0; count < 70; count++) {
                var src = randomPixels(count + 5);
                var a = new int[count + 7];
                var b = new int[count + 7];
                scalar.addGreenToBlueAndRed(src, 5, a, 2, count);
                vector.addGreenToBlueAndRed(src, 5, b, 2, count);
                assertArrayEquals(a, b);
                var c = src.clone();
                var d = src.clone();
                scalar.addGreenToBlueAndRed(c, 3, c, 3, Math.max(0, count - 3));
                vector.addGreenToBlueAndRed(d, 3, d, 3, Math.max(0, count - 3));
                assertArrayEquals(c, d);
            }
        }

        @Test
        @DisplayName("subtracts green like the scalar kernel")
        void subtractGreen() {
            for (var count = 0; count < 70; count++) {
                var a = randomPixels(count + 4);
                var b = a.clone();
                scalar.subtractGreenFromBlueAndRed(a, 4 % (count + 1), count + 4 - 4 % (count + 1));
                vector.subtractGreenFromBlueAndRed(b, 4 % (count + 1), count + 4 - 4 % (count + 1));
                assertArrayEquals(a, b);
            }
        }

        @Test
        @DisplayName("applies both cross-color directions like the scalar kernel")
        void crossColor() {
            for (var count = 0; count < 70; count++) {
                var code = random.nextInt();
                var src = randomPixels(count + 1);
                var a = new int[count + 1];
                var b = new int[count + 1];
                scalar.transformColorInverse(code, src, 1, a, 0, count);
                vector.transformColorInverse(code, src, 1, b, 0, count);
                assertArrayEquals(a, b);
                var c = src.clone();
                var d = src.clone();
                scalar.transformColor(code, c, 1, count);
                vector.transformColor(code, d, 1, count);
                assertArrayEquals(c, d);
                scalar.transformColorInverse(code, c, 1, c, 1, count);
                assertArrayEquals(src, c, "forward then inverse is the identity");
            }
        }

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
        @DisplayName("adds predictions like the scalar kernel for every mode")
        void predictors(int mode) {
            for (var width = 2; width < 90; width += 7) {
                for (var start = 1; start < width; start += Math.max(1, width / 3)) {
                    var count = width - start;
                    var out1 = randomPixels(width * 3);
                    var out2 = out1.clone();
                    var in = randomPixels(width * 3);
                    var outPos = 2 * width + start;
                    scalar.predictorAdd(mode, in, outPos, out1, outPos, count, width);
                    vector.predictorAdd(mode, in, outPos, out2, outPos, count, width);
                    assertArrayEquals(out1, out2, "width " + width + " start " + start);
                    var inPlace1 = in.clone();
                    var inPlace2 = in.clone();
                    scalar.predictorAdd(mode, inPlace1, outPos, inPlace1, outPos, count, width);
                    vector.predictorAdd(mode, inPlace2, outPos, inPlace2, outPos, count, width);
                    assertArrayEquals(inPlace1, inPlace2, "in place, width " + width);
                }
            }
        }

        @Test
        @DisplayName("packs ARGB to RGBA like the scalar kernel")
        void argbToRgba() {
            for (var count = 0; count < 70; count++) {
                var src = randomPixels(count + 2);
                var a = new byte[count * 4 + 3];
                var b = new byte[count * 4 + 3];
                scalar.argbToRgba(src, 2, a, 3, count);
                vector.argbToRgba(src, 2, b, 3, count);
                assertArrayEquals(a, b);
            }
        }

        @Test
        @DisplayName("computes combined Shannon entropy like the scalar kernel on sparse and dense histograms")
        void combinedEntropy() {
            for (var density : new int[]{0, 1, 8, 64, 256}) {
                for (var round = 0; round < 20; round++) {
                    var x = new int[256 + 5];
                    var y = new int[256 + 9];
                    for (var i = 0; i < density; i++) {
                        x[5 + random.nextInt(256)] += random.nextInt(round < 10 ? 300 : 100_000);
                        y[9 + random.nextInt(256)] += random.nextInt(round < 10 ? 300 : 100_000);
                    }
                    assertEquals(scalar.combinedShannonEntropy(x, 5, y, 9), vector.combinedShannonEntropy(x, 5, y, 9));
                }
            }
        }

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13})
        @DisplayName("computes predictor residuals like the scalar kernel for every mode")
        void residuals(int mode) {
            for (var width = 2; width < 90; width += 5) {
                for (var start = 1; start < width; start += Math.max(1, width / 4)) {
                    var count = width - start;
                    var image = random.nextBoolean() ? randomPixels(width * 3) : smoothPixels(width * 3);
                    var a = new int[count + 3];
                    var b = new int[count + 3];
                    var pos = 2 * width + start;
                    scalar.predictorResiduals(mode, image, pos, image, pos - width, count, a, 3);
                    vector.predictorResiduals(mode, image, pos, image, pos - width, count, b, 3);
                    assertArrayEquals(a, b, "width " + width + " start " + start);
                    var upper = randomPixels(width + 2);
                    scalar.predictorResiduals(mode, image, pos, upper, 1, count, a, 0);
                    vector.predictorResiduals(mode, image, pos, upper, 1, count, b, 0);
                    assertArrayEquals(a, b, "separate rows, width " + width);
                }
            }
        }

        @ParameterizedTest(name = "mode {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13})
        @DisplayName("computes the residuals the per-pixel predictor defines")
        void residualsMatchPredict(int mode) {
            var width = 37;
            var image = smoothPixels(width * 4);
            var out = new int[width];
            for (var kernel : new LosslessDsp[]{scalar, vector}) {
                kernel.predictorResiduals(mode, image, 2 * width + 1, image, width + 1, width - 1, out, 0);
                for (var i = 0; i < width - 1; i++) {
                    var p = 2 * width + 1 + i;
                    var expected = ScalarLosslessDsp.subPixels(image[p], ScalarLosslessDsp.predict(mode, image[p - 1], image, p - width));
                    assertEquals(expected, out[i], "pixel " + i);
                }
            }
        }

        int[] smoothPixels(int n) {
            var data = new int[n];
            var base = random.nextInt();
            for (var i = 0; i < n; i++) {
                var jitter = random.nextInt(4) == 0 ? random.nextInt() & 0x0F0F0F0F : 0;
                data[i] = base + jitter + (i % 3) * 0x01010101 * (random.nextInt(3) - 1);
            }
            return data;
        }

        @Test
        @DisplayName("computes combined Shannon entropy from precomputed totals like the full scan")
        void combinedEntropyWithTotals() {
            for (var density : new int[]{0, 1, 8, 64, 256}) {
                for (var round = 0; round < 20; round++) {
                    var x = new int[256 + 5];
                    var y = new int[256 + 9];
                    for (var i = 0; i < density; i++) {
                        x[5 + random.nextInt(256)] += random.nextInt(round < 10 ? 300 : 100_000);
                        y[9 + random.nextInt(256)] += random.nextInt(round < 10 ? 300 : 100_000);
                    }
                    var sumY = 0;
                    long entropyY = 0;
                    for (var i = 0; i < 256; i++) {
                        sumY += y[9 + i];
                        entropyY += EntropyMath.fastSLog2(y[9 + i]);
                    }
                    var expected = scalar.combinedShannonEntropy(x, 5, y, 9);
                    assertEquals(expected, scalar.combinedShannonEntropy(x, 5, y, 9, sumY, entropyY));
                    assertEquals(expected, vector.combinedShannonEntropy(x, 5, y, 9, sumY, entropyY));
                }
            }
        }

        @Test
        @DisplayName("collects streak statistics like the scalar kernel on single and summed populations")
        void streaks() {
            for (var length : new int[]{1, 7, 8, 9, 40, 256, 280, 1304}) {
                for (var round = 0; round < 40; round++) {
                    var x = new int[length];
                    var y = new int[length];
                    var density = round % 4 == 0 ? length : 1 + random.nextInt(Math.max(1, length / 8));
                    for (var i = 0; i < density; i++) {
                        var at = random.nextInt(length);
                        var run = random.nextInt(6) + 1;
                        var value = random.nextInt(round < 20 ? 300 : 200_000);
                        for (var k = at; k < Math.min(length, at + run); k++) {
                            x[k] = value;
                            if (random.nextBoolean()) {
                                y[k] = value / 2;
                            }
                        }
                    }
                    for (var summed : new boolean[]{false, true}) {
                        var expected = new EntropyMath.Stats();
                        var actual = new EntropyMath.Stats();
                        scalar.entropyUnrefined(x, summed ? y : null, length, expected);
                        vector.entropyUnrefined(x, summed ? y : null, length, actual);
                        var label = "length " + length + " round " + round + " summed " + summed;
                        assertEquals(expected.entropy, actual.entropy, label);
                        assertEquals(expected.sum, actual.sum, label);
                        assertEquals(expected.nonzeros, actual.nonzeros, label);
                        assertEquals(expected.maxVal, actual.maxVal, label);
                        assertEquals(expected.nonzeroCode, actual.nonzeroCode, label);
                        assertEquals(expected.counts0, actual.counts0, label);
                        assertEquals(expected.counts1, actual.counts1, label);
                        assertEquals(expected.streaks00, actual.streaks00, label);
                        assertEquals(expected.streaks01, actual.streaks01, label);
                        assertEquals(expected.streaks10, actual.streaks10, label);
                        assertEquals(expected.streaks11, actual.streaks11, label);
                    }
                }
            }
        }

        @Test
        @DisplayName("collects cross-color red and blue histograms like the scalar kernel for every multiplier")
        void collectTransforms() {
            var argb = randomPixels(40 * 33);
            for (var multiplier = -128; multiplier < 128; multiplier += 7) {
                for (var width : new int[]{1, 7, 8, 17, 32}) {
                    var a = new int[256];
                    var b = new int[256];
                    scalar.collectColorRedTransforms(argb, 41, 40, width, 5, multiplier, a);
                    vector.collectColorRedTransforms(argb, 41, 40, width, 5, multiplier, b);
                    assertArrayEquals(a, b, "red " + multiplier + " width " + width);
                    var other = -multiplier / 2 + 3;
                    scalar.collectColorBlueTransforms(argb, 3, 40, width, 32, multiplier, other, a);
                    vector.collectColorBlueTransforms(argb, 3, 40, width, 32, multiplier, other, b);
                    assertArrayEquals(a, b, "blue " + multiplier + " width " + width);
                }
            }
        }
    }

    @Nested
    @DisplayName("scalar predictors")
    class Predictors {
        @Test
        @DisplayName("average, select and clamp channel by channel")
        void arithmetic() {
            assertEquals(0x80808080, ScalarLosslessDsp.average2(0xFFFFFFFF, 0x01010101));
            assertEquals(0x00000000, ScalarLosslessDsp.average2(0x00000000, 0x01010101));
            assertEquals(0x01FF00FE, ScalarLosslessDsp.addPixels(0x02FE01FF, 0xFF01FFFF));
            assertEquals(0xFF020103, ScalarLosslessDsp.subPixels(0x02FE01FF, 0x03FC00FC));
            assertEquals(0xFF00FF80, ScalarLosslessDsp.clampedAddSubtractFull(0xFF00F080, 0xFF10FF40, 0x00200040));
            // select(T, L, TL): with T == TL the gradient estimate equals L, so L wins
            assertEquals(0x20202020, ScalarLosslessDsp.select(0x10101010, 0x20202020, 0x10101010));
            // with L == TL the estimate equals T, so T wins
            assertEquals(0x10101010, ScalarLosslessDsp.select(0x10101010, 0x20202020, 0x20202020));
        }

        @ParameterizedTest(name = "mode {0}")
        @CsvSource({"0,ff000000", "1,0a0b0c0d", "2,01020304", "3,05060708", "4,090a0b0c", "8,05060708", "9,03040506"})
        @DisplayName("predict from the documented neighbors")
        void neighbors(int mode, String expectedHex) {
            // top row: TL=090a0b0c, T=01020304, TR=05060708; left = 0a0b0c0d
            var top = new int[]{0x090A0B0C, 0x01020304, 0x05060708};
            var expected = Integer.parseUnsignedInt(expectedHex, 16);
            assertEquals(expected, ScalarLosslessDsp.predict(mode, 0x0A0B0C0D, top, 1));
        }
    }

    @Nested
    @DisplayName("prefix code tables")
    class Tables {
        @Test
        @DisplayName("map every code of a canonical code to its symbol and length")
        void canonical() {
            // lengths: A=1 (0), B=2 (10), C=3 (110), D=3 (111), bit-reversed in the table index
            var lengths = new int[]{1, 2, 3, 3};
            var table = new int[256];
            assertEquals(256, HuffmanTables.build(table, 0, 8, lengths, 4, new int[4]));
            for (var bits = 0; bits < 256; bits++) {
                var entry = table[bits];
                int symbol;
                int len;
                if ((bits & 1) == 0) {
                    symbol = 0;
                    len = 1;
                } else if ((bits & 2) == 0) {
                    symbol = 1;
                    len = 2;
                } else if ((bits & 4) == 0) {
                    symbol = 2;
                    len = 3;
                } else {
                    symbol = 3;
                    len = 3;
                }
                assertEquals(symbol, entry & 0xFFFF, "index " + bits);
                assertEquals(len, entry >>> 16, "index " + bits);
            }
        }

        @Test
        @DisplayName("builds second-level tables for codes longer than the root")
        void secondLevel() {
            var lengths = new int[12];
            for (var i = 0; i < 11; i++) {
                lengths[i] = i + 1;
            }
            lengths[11] = 11;
            var size = HuffmanTables.build(null, 0, 8, lengths, 12, null);
            assertTrue(size > 256);
            var table = new int[size];
            assertEquals(size, HuffmanTables.build(table, 0, 8, lengths, 12, new int[12]));
            // the all-ones prefix of 8 bits points to a second-level table of 3 index bits
            var root = table[0xFF];
            assertEquals(8 + 3, root >>> 16);
        }

        @Test
        @DisplayName("accepts a single used symbol as a zero-length code")
        void single() {
            var lengths = new int[]{0, 0, 5, 0};
            var table = new int[256];
            assertEquals(256, HuffmanTables.build(table, 0, 8, lengths, 4, new int[4]));
            for (var entry : table) {
                assertEquals(2, entry & 0xFFFF);
                assertEquals(0, entry >>> 16);
            }
        }

        @Test
        @DisplayName("rejects empty, over-subscribed, incomplete and over-long codes")
        void invalid() {
            assertEquals(0, HuffmanTables.build(null, 0, 8, new int[]{0, 0, 0}, 3, null));
            assertEquals(0, HuffmanTables.build(null, 0, 8, new int[]{1, 1, 1}, 3, null));
            assertEquals(0, HuffmanTables.build(null, 0, 8, new int[]{1, 2}, 2, null));
            assertEquals(0, HuffmanTables.build(null, 0, 8, new int[]{16, 1}, 2, null));
        }
    }

    @Nested
    @DisplayName("prefix coding")
    class PrefixCoding {
        @Test
        @DisplayName("round-trips every length and distance through prefix symbol and extra bits")
        void roundTrip() {
            for (var value = 1; value < 1 << 20; value = value < 4096 ? value + 1 : value + 997) {
                var code = Prefix.code(value);
                var extraBits = Prefix.extraBits(value);
                var extra = Prefix.extraValue(value);
                int decoded;
                if (code < 4) {
                    decoded = code + 1;
                } else {
                    var eb = (code - 2) >> 1;
                    assertEquals(eb, extraBits);
                    decoded = ((2 + (code & 1)) << eb) + extra + 1;
                }
                assertEquals(value, decoded, "value " + value);
            }
        }

        @Test
        @DisplayName("maps short 2D offsets to plane codes and back")
        void planeCodes() {
            var width = 50;
            for (var code = 1; code <= 120; code++) {
                var dist = Vp8lDecoder.planeCodeToDistance(width, code);
                assertEquals(code, Prefix.distanceToPlaneCode(width, dist), "plane code " + code);
            }
            assertEquals(1000 + 120, Prefix.distanceToPlaneCode(width, 1000));
            assertEquals(1000, Vp8lDecoder.planeCodeToDistance(width, 1000 + 120));
        }
    }

    @Nested
    @DisplayName("bit writer")
    class Writer {
        @Test
        @DisplayName("writes fields least-significant bit first, readable by the decoder's reader")
        void roundTrip() {
            var random = new Random(5);
            var writer = new Vp8lBitWriter(4);
            var widths = new int[3000];
            var values = new int[3000];
            for (var i = 0; i < widths.length; i++) {
                widths[i] = random.nextInt(25);
                values[i] = random.nextInt() & ((1 << widths[i]) - 1);
                writer.putBits(values[i], widths[i]);
            }
            var bytes = writer.toByteArray();
            assertEquals(bytes.length, writer.numBytes());
            var reader = new Vp8lBitReader(bytes, 0, bytes.length);
            for (var i = 0; i < widths.length; i++) {
                assertEquals(values[i], reader.read(widths[i]), "field " + i);
            }
            assertFalse(reader.eos());
        }

        @Test
        @DisplayName("rewinds to a mark and swaps content")
        void markAndSwap() {
            var a = new Vp8lBitWriter(4);
            a.putBits(0x5, 3);
            var mark = a.mark();
            a.putBits(0x3FF, 10);
            a.reset(mark);
            assertEquals(1, a.numBytes());
            var b = new Vp8lBitWriter(4);
            b.putBits(0xABCD, 16);
            a.swap(b);
            assertEquals(2, a.numBytes());
            assertEquals(1, b.numBytes());
        }
    }
}
