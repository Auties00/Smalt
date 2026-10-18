package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests of the transform, quantization, color, Huffman and bit-level building blocks, and
 * equivalence of every Vector API kernel with its scalar reference on randomized input.
 */
class JpegKernelTest {
    @Nested
    @DisplayName("inverse DCT")
    class Idct {
        private final ScalarIdctKernel scalar = new ScalarIdctKernel();
        @Test
        @DisplayName("matches the ideal DCT to within one level on random data (IEEE 1180 style)")
        void accuracy() {
            var random = new Random(1180);
            var maxError = 0;
            for (var i = 0; i < 10_000; i++) {
                var samples = new int[64];
                for (var k = 0; k < 64; k++) {
                    samples[k] = random.nextInt(256) - 128;
                }
                var coefficients = new int[64];
                for (var v = 0; v < 8; v++) {
                    for (var u = 0; u < 8; u++) {
                        var sum = 0.0;
                        for (var y = 0; y < 8; y++) {
                            for (var x = 0; x < 8; x++) {
                                sum += samples[y * 8 + x] * Math.cos((2 * x + 1) * u * Math.PI / 16) * Math.cos((2 * y + 1) * v * Math.PI / 16);
                            }
                        }
                        var cu = u == 0 ? Math.sqrt(0.5) : 1;
                        var cv = v == 0 ? Math.sqrt(0.5) : 1;
                        coefficients[v * 8 + u] = (int) Math.round(sum * cu * cv / 4);
                    }
                }
                var output = new byte[64];
                scalar.idct8(coefficients, output, 0, 8);
                for (var k = 0; k < 64; k++) {
                    maxError = Math.max(maxError, Math.abs((output[k] & 0xFF) - 128 - Math.clamp(samples[k], -128, 127)));
                }
            }
            assertTrue(maxError <= 1, "max error " + maxError);
        }

        @Test
        @DisplayName("maps descaled outputs through libjpeg's range limit table")
        void limitTable() {
            var limit = ScalarIdctKernel.LIMIT;
            assertEquals(128, limit[0] & 0xFF);
            assertEquals(255, limit[127] & 0xFF);
            assertEquals(255, limit[511] & 0xFF);
            assertEquals(0, limit[512] & 0xFF);
            assertEquals(0, limit[895] & 0xFF);
            assertEquals(0, limit[896] & 0xFF);
            assertEquals(127, limit[1023] & 0xFF);
        }

        @Test
        @DisplayName("a DC-only block is flat, at every reduced size")
        void dcOnly() {
            for (var dc = -2048; dc <= 2048; dc += 7) {
                var expected = ReducedIdct.dcValue(dc);
                var block = new int[64];
                block[0] = dc;
                var output = new byte[64];
                scalar.idct8(block, output, 0, 8);
                for (var b : output) {
                    assertEquals(expected, b);
                }
                block[0] = dc;
                ReducedIdct.idct4(block, output, 0, 4);
                assertEquals(expected, output[5]);
                block[0] = dc;
                ReducedIdct.idct2(block, output, 0, 2);
                assertEquals(expected, output[3]);
            }
        }
    }

    @Nested
    @DisplayName("forward DCT and quantization")
    class Fdct {
        @Test
        @DisplayName("vector and scalar batch kernels agree with the per-block kernel on random, flat and extreme blocks")
        void equivalence() {
            var single = new ScalarFdctKernel();
            var kernels = new BatchFdctKernel[]{new ScalarBatchFdctKernel(), VectorKernels.vectorBatchFdct()};
            var random = new Random(5);
            var stride = 8 * BatchFdctKernel.LANES + 13;
            var scratch = new byte[BatchFdctKernel.SCRATCH_LENGTH];
            for (var quality : new int[]{1, 25, 50, 75, 92, 100}) {
                var divisors = new QuantDivisors(JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, quality));
                for (var i = 0; i < 1_500; i++) {
                    var samples = new byte[8 * stride + 5];
                    switch (i % 4) {
                        case 0 -> random.nextBytes(samples);
                        case 1 -> Arrays.fill(samples, (byte) random.nextInt(256));
                        case 2 -> {
                            for (var k = 0; k < samples.length; k++) {
                                samples[k] = (byte) (random.nextBoolean() ? 0 : 255);
                            }
                        }
                        default -> {
                            for (var k = 0; k < samples.length; k++) {
                                samples[k] = (byte) (k * 3 + random.nextInt(5));
                            }
                        }
                    }
                    var expected = new int[BatchFdctKernel.BATCH_LENGTH];
                    var expectedMasks = new long[BatchFdctKernel.LANES];
                    var block = new int[64];
                    for (var b = 0; b < BatchFdctKernel.LANES; b++) {
                        expectedMasks[b] = single.transform(samples, 5 + 8 * b, stride, divisors, block);
                        for (var k = 0; k < 64; k++) {
                            expected[k * BatchFdctKernel.LANES + b] = block[k];
                            assertEquals(block[k] != 0, (expectedMasks[b] >>> k & 1) == 1);
                        }
                    }
                    for (var kernel : kernels) {
                        var output = new int[BatchFdctKernel.BATCH_LENGTH];
                        var masks = new long[BatchFdctKernel.LANES];
                        kernel.transform(samples, 5, stride, divisors, output, masks, scratch);
                        assertArrayEquals(expected, output, kernel.getClass().getSimpleName());
                        assertArrayEquals(expectedMasks, masks, kernel.getClass().getSimpleName());
                    }
                }
            }
        }

        @Test
        @DisplayName("the reciprocal quantizer equals rounded division for every table value and DCT output")
        void reciprocals() {
            for (var q = 1; q <= 255; q++) {
                var table = new int[64];
                Arrays.fill(table, q);
                var divisors = new QuantDivisors(table);
                var divisor = q * 8;
                for (var x = -9000; x <= 9000; x++) {
                    var magnitude = Math.abs(x);
                    var expected = (magnitude + divisor / 2) / divisor;
                    assertEquals(x < 0 ? -expected : expected, divisors.quantize(x, 17), "q " + q + " x " + x);
                }
            }
        }

        @Test
        @DisplayName("scales the Annex K tables by quality as libjpeg does")
        void qualityScaling() {
            assertArrayEquals(JpegTables.STD_LUMINANCE_QUANT, JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 50));
            var best = JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 100);
            assertTrue(Arrays.stream(best).allMatch(v -> v == 1));
            var worst = JpegWriter.scaledTable(JpegTables.STD_CHROMINANCE_QUANT, 1);
            assertTrue(Arrays.stream(worst).allMatch(v -> v >= 1 && v <= 255));
            assertEquals(8, JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 75)[0]);
            assertEquals(3, JpegWriter.scaledTable(JpegTables.STD_LUMINANCE_QUANT, 92)[0]);
        }
    }

    @Nested
    @DisplayName("color")
    class Color {
        @Test
        @DisplayName("vector and scalar RGBA conversions agree for every row length")
        void equivalence() {
            var scalar = new ScalarColorKernel();
            var vector = VectorKernels.vectorColor();
            var random = new Random(3);
            for (var length = 0; length < 70; length++) {
                var rgba = new byte[length * 4 + 12];
                random.nextBytes(rgba);
                byte[][] a = {new byte[length], new byte[length], new byte[length], new byte[length]};
                byte[][] b = {new byte[length], new byte[length], new byte[length], new byte[length]};
                scalar.toYcc(rgba, 4, length, a[0], a[1], a[2]);
                vector.toYcc(rgba, 4, length, b[0], b[1], b[2]);
                scalar.toGray(rgba, 8, length, a[3]);
                vector.toGray(rgba, 8, length, b[3]);
                for (var p = 0; p < 4; p++) {
                    assertArrayEquals(a[p], b[p], "length " + length);
                }
            }
        }

        @Test
        @DisplayName("maps primaries and gray exactly as libjpeg's tables")
        void reference() {
            var y = new byte[4];
            var cb = new byte[4];
            var cr = new byte[4];
            new ScalarColorKernel().toYcc(new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255, 0, 0, 0, 0, (byte) 255, 0, (byte) 128, (byte) 128, (byte) 128, 0},
                    0, 4, y, cb, cr);
            assertArrayEquals(new byte[]{76, (byte) 150, 29, (byte) 128}, y);
            assertArrayEquals(new byte[]{85, 44, (byte) 255, (byte) 128}, cb);
            assertArrayEquals(new byte[]{(byte) 255, 21, 107, (byte) 128}, cr);
        }

        @Test
        @DisplayName("divides products by 255 with correct rounding")
        void divide255() {
            for (var product = 0; product <= 255 * 255; product++) {
                assertEquals((product + 127) / 255, JpegOutput.divide255(product));
            }
        }
    }

    @Nested
    @DisplayName("Huffman")
    class Huffman {
        @Test
        @DisplayName("assigns the canonical codes of Annex C to the standard tables")
        void canonicalCodes() {
            var table = new HuffmanEncodingTable(HuffmanSpec.STD_DC_LUMINANCE);
            assertEquals(0b00, table.codes[0]);
            assertEquals(2, table.lengths[0]);
            assertEquals(0b010, table.codes[1]);
            assertEquals(3, table.lengths[1]);
            assertEquals(0b111111110, table.codes[11]);
            assertEquals(9, table.lengths[11]);
            var ac = new HuffmanEncodingTable(HuffmanSpec.STD_AC_LUMINANCE);
            assertEquals(0b1010, ac.codes[0x00]);
            assertEquals(0b11111111001, ac.codes[0xF0]);
            assertEquals(16, ac.lengths[0xFA]);
        }

        @Test
        @DisplayName("decodes every code of every standard table, with and without the lookahead")
        void decodesEveryCode() {
            for (var spec : new HuffmanSpec[]{HuffmanSpec.STD_DC_LUMINANCE, HuffmanSpec.STD_DC_CHROMINANCE,
                    HuffmanSpec.STD_AC_LUMINANCE, HuffmanSpec.STD_AC_CHROMINANCE}) {
                var dc = spec == HuffmanSpec.STD_DC_LUMINANCE || spec == HuffmanSpec.STD_DC_CHROMINANCE;
                var decoding = new HuffmanDecodingTable(spec, dc);
                var encoding = new HuffmanEncodingTable(spec);
                for (var symbol = 0; symbol < 256; symbol++) {
                    if (encoding.lengths[symbol] == 0) {
                        continue;
                    }
                    var cache = ((long) encoding.codes[symbol]) << (64 - encoding.lengths[symbol]);
                    var entry = decoding.decode(cache);
                    assertEquals(encoding.lengths[symbol], entry >>> 8);
                    assertEquals(symbol, entry & 0xFF);
                    var slow = decoding.decodeSlow(cache);
                    if (encoding.lengths[symbol] > HuffmanDecodingTable.LOOKAHEAD) {
                        assertEquals(entry, slow);
                    }
                }
                var invalid = decoding.decode(-1L);
                assertEquals(HuffmanDecodingTable.INVALID_LENGTH, invalid >>> 8);
                assertEquals(0, invalid & 0xFF);
            }
        }

        @Test
        @DisplayName("fast entries agree with symbol decoding followed by magnitude extension")
        void fastEntries() {
            var decoding = new HuffmanDecodingTable(HuffmanSpec.STD_AC_LUMINANCE, false);
            for (var bits = 0; bits < 1 << HuffmanDecodingTable.LOOKAHEAD; bits++) {
                var fast = decoding.fast[bits];
                if (fast == 0) {
                    continue;
                }
                var cache = ((long) bits) << (64 - HuffmanDecodingTable.LOOKAHEAD);
                var entry = decoding.decode(cache);
                var length = entry >>> 8;
                var size = entry & 15;
                var magnitude = (int) ((cache << length) >>> (64 - size));
                assertEquals(HuffmanDecodingTable.extend(magnitude, size), fast >> 16);
                assertEquals((entry >>> 4) & 15, (fast >>> 4) & 15);
                assertEquals(length + size, fast & 15);
            }
        }

        @Test
        @DisplayName("rejects oversubscribed code lengths and DC symbols above 15")
        void validation() {
            var bits = new int[17];
            bits[1] = 3;
            assertThrows(MediaException.Malformed.class, () -> new HuffmanDecodingTable(new HuffmanSpec(bits, new int[]{0, 1, 2}), false));
            var full = new int[17];
            full[1] = 2;
            assertThrows(MediaException.Malformed.class, () -> new HuffmanDecodingTable(new HuffmanSpec(full, new int[]{0, 1}), false));
            var dc = new int[17];
            dc[2] = 1;
            assertThrows(MediaException.Malformed.class, () -> new HuffmanDecodingTable(new HuffmanSpec(dc, new int[]{16}), true));
            assertDoesNotThrow(() -> new HuffmanDecodingTable(new HuffmanSpec(dc, new int[]{16}), false));
            assertThrows(MediaException.Malformed.class, () -> new HuffmanSpec(new int[17], new int[]{1}));
        }

        @ParameterizedTest(name = "distribution {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4})
        @DisplayName("builds optimal tables within 16 bits, without the all-ones code, never worse than Annex K")
        void optimalTables(int distribution) {
            var random = new Random(distribution);
            var frequencies = new long[256];
            switch (distribution) {
                case 0 -> frequencies[42] = 1000;
                case 1 -> {
                    long a = 1;
                    long b = 1;
                    for (var i = 0; i < 40; i++) {
                        frequencies[i] = a;
                        var next = a + b;
                        a = b;
                        b = next;
                    }
                }
                case 2 -> {
                    for (var i = 0; i < 256; i++) {
                        frequencies[i] = 1;
                    }
                }
                case 3 -> {
                    for (var symbol : JpegTables.STD_AC_LUMINANCE_VALUES) {
                        frequencies[symbol] = 1 + random.nextInt(100_000) / (1 + (symbol & 15) * (symbol & 15));
                    }
                }
                default -> {
                    for (var i = 0; i < 12; i++) {
                        frequencies[i] = random.nextInt(1000) + 1;
                    }
                }
            }
            var spec = HuffmanEncodingTable.optimal(frequencies);
            var table = new HuffmanEncodingTable(spec);
            var kraft = 0.0;
            var cost = 0L;
            for (var symbol = 0; symbol < 256; symbol++) {
                if (frequencies[symbol] != 0) {
                    assertTrue(table.lengths[symbol] >= 1 && table.lengths[symbol] <= 16, "symbol " + symbol);
                    assertNotEquals((1 << table.lengths[symbol]) - 1, table.codes[symbol]);
                    kraft += Math.pow(2, -table.lengths[symbol]);
                    cost += frequencies[symbol] * table.lengths[symbol];
                } else {
                    assertEquals(0, table.lengths[symbol]);
                }
            }
            assertTrue(kraft < 1.0);
            if (distribution == 3) {
                var standard = new HuffmanEncodingTable(HuffmanSpec.STD_AC_LUMINANCE);
                var standardCost = 0L;
                for (var symbol = 0; symbol < 256; symbol++) {
                    standardCost += frequencies[symbol] * standard.lengths[symbol];
                }
                assertTrue(cost <= standardCost);
            }
            assertDoesNotThrow(() -> new HuffmanDecodingTable(spec, false));
        }
    }

    @Nested
    @DisplayName("bit level")
    class Bits {
        @Test
        @DisplayName("stuffs 0xFF bytes and pads the last byte with ones")
        void writer() {
            var writer = new JpegBitWriter(4);
            writer.put(0xFF, 8);
            writer.put(0x3, 2);
            writer.put(0x3FFF_FFFF, 30);
            writer.put(0, 1);
            writer.flushBits();
            assertArrayEquals(new byte[]{(byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0x7F},
                    writer.toByteArray());
            var random = new Random(2);
            var reference = new java.io.ByteArrayOutputStream();
            var bitsWritten = new StringBuilder();
            var large = new JpegBitWriter(1);
            for (var i = 0; i < 10_000; i++) {
                var length = random.nextInt(28);
                var value = length == 0 ? 0 : random.nextInt() >>> (32 - length);
                if (random.nextInt(10) == 0) {
                    value = (1 << length) - 1;
                }
                large.put(value, length);
                for (var b = length - 1; b >= 0; b--) {
                    bitsWritten.append((value >>> b) & 1);
                }
            }
            large.flushBits();
            while (bitsWritten.length() % 8 != 0) {
                bitsWritten.append('1');
            }
            for (var i = 0; i < bitsWritten.length(); i += 8) {
                var b = Integer.parseInt(bitsWritten.substring(i, i + 8), 2);
                reference.write(b);
                if (b == 0xFF) {
                    reference.write(0);
                }
            }
            assertArrayEquals(reference.toByteArray(), large.toByteArray());
        }

        @Test
        @DisplayName("unstuffs entropy data, drops fill bytes, and splits it at restart and invalid markers")
        void segments() {
            var segments = new EntropySegments(MediaLimits.DEFAULT);
            byte[] data = {1, (byte) 0xFF, 0, 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xD0, 3, 4, (byte) 0xFF, 0x05, 6,
                    (byte) 0xFF, (byte) 0xD1, (byte) 0xFF, (byte) 0xD9, 9, 9};
            segments.load(data, 0, data.length);
            assertEquals(4, segments.count());
            assertArrayEquals(new byte[]{1, (byte) 0xFF, 2}, Arrays.copyOfRange(segments.data(), segments.start(0), segments.end(0)));
            assertEquals(0xD0, segments.marker(0));
            assertArrayEquals(new byte[]{3, 4}, Arrays.copyOfRange(segments.data(), segments.start(1), segments.end(1)));
            assertEquals(0x05, segments.marker(1));
            assertArrayEquals(new byte[]{6}, Arrays.copyOfRange(segments.data(), segments.start(2), segments.end(2)));
            assertEquals(0xD1, segments.marker(2));
            assertEquals(0, segments.end(3) - segments.start(3));
            assertEquals(0xD9, segments.marker(3));
            assertEquals(14, segments.scanEnd());
            var noEnd = new byte[]{1, 2, 3};
            segments.load(noEnd, 0, noEnd.length);
            assertEquals(EntropySegments.END_OF_INPUT, segments.marker(0));
            assertEquals(3, segments.scanEnd());
        }

        @ParameterizedTest(name = "seed {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6})
        @DisplayName("extracts multi-megabyte scans, split into chunks, exactly like a byte-at-a-time pass")
        void chunkedSegments(int seed) {
            var random = new Random(seed);
            var input = new byte[3_000_000 + random.nextInt(1_000_000)];
            var rate = new double[]{0.001, 0.02, 0.2}[seed % 3];
            for (var i = 0; i < input.length; i++) {
                if (random.nextDouble() >= rate || i + 1 >= input.length) {
                    input[i] = (byte) random.nextInt(255);
                    continue;
                }
                input[i] = (byte) 0xFF;
                var kind = random.nextInt(10);
                if (kind < 6) {
                    input[++i] = 0;
                } else if (kind < 8) {
                    input[++i] = (byte) (0xD0 + random.nextInt(8));
                } else if (kind < 9) {
                    input[++i] = (byte) (1 + random.nextInt(0xBF));
                } else if (i + 2 < input.length) {
                    input[++i] = (byte) 0xFF;
                    input[++i] = 0;
                }
            }
            // fill-byte runs around every multiple of 64 KiB, where chunk boundaries may fall
            for (var at = 1 << 16; at + 64 < input.length; at += 1 << 16) {
                var run = 1 + random.nextInt(random.nextInt(8) == 0 ? 20_000 : 12);
                var start = at - random.nextInt(8);
                for (var k = 0; k < run && start + k + 1 < input.length; k++) {
                    input[start + k] = (byte) 0xFF;
                    input[start + k + 1] = 0;
                }
            }
            var scanEnds = new int[]{40_000, 1_200_000 + random.nextInt(100_000), 2_900_000};
            for (var end : scanEnds) {
                input[end] = (byte) 0xFF;
                input[end + 1] = (byte) 0xDA;
                input[end + 2] = 7;
            }
            if (seed % 2 == 0) {
                input[input.length - 1] = (byte) 0xFF;
            }
            var segments = new EntropySegments(MediaLimits.DEFAULT);
            var from = 3;
            for (var scan = 0; scan <= scanEnds.length; scan++) {
                segments.load(input, from, input.length);
                var expected = new java.io.ByteArrayOutputStream();
                var segment = 0;
                var i = from;
                while (true) {
                    var ended = i >= input.length || (input[i] == (byte) 0xFF && i + 1 >= input.length);
                    var next = ended || input[i] != (byte) 0xFF ? -2 : input[i + 1] & 0xFF;
                    if (next == -2 && !ended) {
                        expected.write(input[i++]);
                    } else if (next == 0) {
                        expected.write(0xFF);
                        i += 2;
                    } else if (next == 0xFF) {
                        i++;
                    } else {
                        var restart = !ended && (next < 0xC0 || (next >= 0xD0 && next <= 0xD7));
                        var marker = ended ? EntropySegments.END_OF_INPUT : next;
                        assertArrayEquals(expected.toByteArray(), Arrays.copyOfRange(segments.data(), segments.start(segment),
                                segments.end(segment)), "scan " + scan + " segment " + segment);
                        assertEquals(marker, segments.marker(segment), "scan " + scan + " segment " + segment);
                        segment++;
                        expected.reset();
                        if (restart) {
                            i += 2;
                            continue;
                        }
                        assertEquals(segment, segments.count(), "scan " + scan);
                        assertEquals(ended ? input.length : i, segments.scanEnd(), "scan " + scan);
                        break;
                    }
                }
                from = segments.scanEnd() + 2;
            }
        }

        @Test
        @DisplayName("resynchronizes on restart markers following libjpeg's decision table")
        void resync() {
            var segments = new EntropySegments(MediaLimits.DEFAULT);
            byte[] data = {1, (byte) 0xFF, (byte) 0xD0, 2, (byte) 0xFF, (byte) 0xD2, 3, (byte) 0xFF, (byte) 0xD7, 4,
                    (byte) 0xFF, 0x01, 5, (byte) 0xFF, (byte) 0xD6, 6, (byte) 0xFF, (byte) 0xD9};
            segments.load(data, 0, data.length);
            assertEquals(1, segments.resync(0, 0));
            assertEquals(-1 - 1, segments.resync(1, 1));
            assertEquals(2, segments.resync(1, 2));
            assertEquals(-1 - 2, segments.resync(2, 5));
            assertEquals(5, segments.resync(2, 1));
            assertEquals(5, segments.resync(4, 6));
            assertEquals(-1 - 5, segments.resync(5, 0));
        }
    }
}
