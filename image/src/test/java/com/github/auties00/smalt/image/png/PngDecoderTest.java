package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers PNG decoding beyond PngSuite: a test-side reference writer that produces every color
 * type, bit depth, interlacing and filter combination from known samples (the expected pixels
 * follow the specification's conversion rules), chunk-structure rules, CRC handling, metadata,
 * limits, truncation, ffmpeg-written images, and a deterministic mutation run.
 */
class PngDecoderTest {
    private static final ImageDecoderOptions OPTIONS = ImageDecoderOptions.defaults();

    private static RgbaPicture decode(byte[] data) {
        return (RgbaPicture) PngDecoder.decode(MediaSource.of(data), OPTIONS).picture();
    }

    private static byte[] solidRgba(int width, int height) {
        var pixels = new byte[width * height * 4];
        new Random(width * 31L + height).nextBytes(pixels);
        return pixels;
    }

    /** Writes PNGs from raw samples with random filters and IDAT splits; computes the expected RGBA. */
    static final class ReferenceImage {
        final int width;
        final int height;
        final int colorType;
        final int depth;
        final boolean interlaced;
        final int[] samples;
        final int[] palette;
        final int[] key;
        final byte[] paletteAlpha;

        ReferenceImage(int width, int height, int colorType, int depth, boolean interlaced, boolean transparency, long seed) {
            this.width = width;
            this.height = height;
            this.colorType = colorType;
            this.depth = depth;
            this.interlaced = interlaced;
            var random = new Random(seed);
            var channels = switch (colorType) {
                case 2 -> 3;
                case 4 -> 2;
                case 6 -> 4;
                default -> 1;
            };
            var maximum = (1 << depth) - 1;
            if (colorType == 3) {
                var entries = 1 + random.nextInt(1 << depth);
                palette = new int[entries];
                for (var i = 0; i < entries; i++) {
                    palette[i] = random.nextInt(1 << 24);
                }
                paletteAlpha = transparency ? new byte[1 + random.nextInt(entries)] : null;
                if (paletteAlpha != null) {
                    random.nextBytes(paletteAlpha);
                }
                maximum = entries - 1;
            } else {
                palette = null;
                paletteAlpha = null;
            }
            samples = new int[width * height * channels];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = random.nextInt(maximum + 1);
            }
            if (transparency && (colorType == 0 || colorType == 2)) {
                key = new int[channels];
                // reuse the first pixel as key so that it actually occurs
                System.arraycopy(samples, 0, key, 0, channels);
            } else {
                key = null;
            }
        }

        int channels() {
            return switch (colorType) {
                case 2 -> 3;
                case 4 -> 2;
                case 6 -> 4;
                default -> 1;
            };
        }

        static int to8(int value, int depth) {
            return depth == 16 ? (value * 255 + 32767) / 65535 : value * 255 / ((1 << depth) - 1);
        }

        byte[] expected() {
            var out = new byte[width * height * 4];
            var channels = channels();
            for (var p = 0; p < width * height; p++) {
                var s = p * channels;
                int r, g, b, a;
                switch (colorType) {
                    case 0 -> {
                        r = g = b = to8(samples[s], depth);
                        a = key != null && samples[s] == key[0] ? 0 : 255;
                    }
                    case 2 -> {
                        r = to8(samples[s], depth);
                        g = to8(samples[s + 1], depth);
                        b = to8(samples[s + 2], depth);
                        a = key != null && samples[s] == key[0] && samples[s + 1] == key[1] && samples[s + 2] == key[2] ? 0 : 255;
                    }
                    case 3 -> {
                        var entry = palette[samples[s]];
                        r = entry >>> 16;
                        g = (entry >>> 8) & 0xFF;
                        b = entry & 0xFF;
                        a = paletteAlpha != null && samples[s] < paletteAlpha.length ? paletteAlpha[samples[s]] & 0xFF : 255;
                    }
                    case 4 -> {
                        r = g = b = to8(samples[s], depth);
                        a = to8(samples[s + 1], depth);
                    }
                    default -> {
                        r = to8(samples[s], depth);
                        g = to8(samples[s + 1], depth);
                        b = to8(samples[s + 2], depth);
                        a = to8(samples[s + 3], depth);
                    }
                }
                out[p * 4] = (byte) r;
                out[p * 4 + 1] = (byte) g;
                out[p * 4 + 2] = (byte) b;
                out[p * 4 + 3] = (byte) a;
            }
            return out;
        }

        byte[] scanlines(Random random) {
            var out = new ByteArrayOutputStream();
            if (!interlaced) {
                writePass(out, 0, 0, 1, 1, random);
            } else {
                int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
                for (var pass : passes) {
                    writePass(out, pass[0], pass[1], pass[2], pass[3], random);
                }
            }
            return out.toByteArray();
        }

        private void writePass(ByteArrayOutputStream out, int x0, int y0, int dx, int dy, Random random) {
            var passWidth = (width - x0 + dx - 1) / dx;
            var passHeight = (height - y0 + dy - 1) / dy;
            if (passWidth <= 0 || passHeight <= 0) {
                return;
            }
            var channels = channels();
            var bitsPerPixel = channels * depth;
            var rowBytes = (passWidth * bitsPerPixel + 7) / 8;
            var distance = Math.max(1, bitsPerPixel / 8);
            var prior = new byte[rowBytes];
            for (var r = 0; r < passHeight; r++) {
                var y = y0 + r * dy;
                var row = new byte[rowBytes];
                var bit = 0;
                for (var i = 0; i < passWidth; i++) {
                    var x = x0 + i * dx;
                    for (var c = 0; c < channels; c++) {
                        var value = samples[(y * width + x) * channels + c];
                        if (depth == 16) {
                            row[bit / 8] = (byte) (value >>> 8);
                            row[bit / 8 + 1] = (byte) value;
                        } else if (depth == 8) {
                            row[bit / 8] = (byte) value;
                        } else {
                            row[bit / 8] |= (byte) (value << (8 - depth - bit % 8));
                        }
                        bit += depth;
                    }
                }
                var type = random.nextInt(5);
                var filtered = new byte[rowBytes];
                for (var i = 0; i < rowBytes; i++) {
                    var a = i >= distance ? row[i - distance] & 0xFF : 0;
                    var b = prior[i] & 0xFF;
                    var c = i >= distance ? prior[i - distance] & 0xFF : 0;
                    var predictor = switch (type) {
                        case 1 -> a;
                        case 2 -> b;
                        case 3 -> (a + b) / 2;
                        case 4 -> ScalarPngFilterKernel.paeth(a, b, c);
                        default -> 0;
                    };
                    filtered[i] = (byte) (row[i] - predictor);
                }
                out.write(type);
                out.writeBytes(filtered);
                prior = row;
            }
        }

        byte[] png(long seed) {
            var random = new Random(seed);
            var files = PngFiles.create().ihdr(width, height, depth, colorType, interlaced ? 1 : 0);
            if (palette != null) {
                var plte = new byte[palette.length * 3];
                for (var i = 0; i < palette.length; i++) {
                    plte[i * 3] = (byte) (palette[i] >>> 16);
                    plte[i * 3 + 1] = (byte) (palette[i] >>> 8);
                    plte[i * 3 + 2] = (byte) palette[i];
                }
                files.chunk("PLTE", plte);
                if (paletteAlpha != null) {
                    files.chunk("tRNS", paletteAlpha);
                }
            }
            if (key != null) {
                var trns = new byte[key.length * 2];
                for (var i = 0; i < key.length; i++) {
                    trns[i * 2] = (byte) (key[i] >>> 8);
                    trns[i * 2 + 1] = (byte) key[i];
                }
                files.chunk("tRNS", trns);
            }
            var deflater = new Deflater(random.nextInt(10));
            deflater.setInput(scanlines(random));
            deflater.finish();
            var zlib = new ByteArrayOutputStream();
            var buffer = new byte[1024];
            while (!deflater.finished()) {
                zlib.write(buffer, 0, deflater.deflate(buffer));
            }
            deflater.end();
            var stream = zlib.toByteArray();
            var offset = 0;
            while (offset < stream.length) {
                var size = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(Math.max(1, stream.length - offset));
                files.idat(Arrays.copyOfRange(stream, offset, Math.min(stream.length, offset + size)));
                offset += size;
            }
            return files.iend().bytes();
        }
    }

    static Stream<Arguments> formats() {
        var list = new ArrayList<Arguments>();
        int[][] combos = {{0, 1}, {0, 2}, {0, 4}, {0, 8}, {0, 16}, {2, 8}, {2, 16}, {3, 1}, {3, 2}, {3, 4}, {3, 8},
                {4, 8}, {4, 16}, {6, 8}, {6, 16}};
        for (var combo : combos) {
            for (var interlaced : new boolean[]{false, true}) {
                list.add(Arguments.of(combo[0], combo[1], interlaced));
            }
        }
        return list.stream();
    }

    @Nested
    @DisplayName("reference writer")
    class Reference {
        @ParameterizedTest(name = "color type {0} depth {1} interlaced {2}")
        @MethodSource("com.github.auties00.smalt.image.png.PngDecoderTest#formats")
        @DisplayName("decodes every size from 1x1 to 19x17 exactly, with and without tRNS")
        void everyFormat(int colorType, int depth, boolean interlaced) {
            var sizes = new int[][]{{1, 1}, {1, 9}, {9, 1}, {2, 2}, {3, 5}, {7, 7}, {8, 8}, {9, 9}, {13, 4}, {19, 17}};
            var seed = 0L;
            for (var size : sizes) {
                for (var transparency : new boolean[]{false, true}) {
                    var image = new ReferenceImage(size[0], size[1], colorType, depth, interlaced, transparency, seed++);
                    var png = image.png(seed * 7919);
                    var picture = decode(png);
                    RasterTesting.assertSameRgba(image.expected(), RasterTesting.tight(picture), size[0],
                            size[0] + "x" + size[1] + " tRNS=" + transparency);
                    var alpha = colorType == 4 || colorType == 6 || (transparency && colorType != 4 && colorType != 6);
                    assertEquals(alpha, picture.hasAlpha(), "hasAlpha");
                }
            }
        }

        @ParameterizedTest(name = "color type {0} depth {1} interlaced {2}")
        @MethodSource("com.github.auties00.smalt.image.png.PngDecoderTest#formats")
        @DisplayName("decodes identically through the scalar and the vector filter kernels")
        void scalarKernel(int colorType, int depth, boolean interlaced) {
            var image = new ReferenceImage(61, 23, colorType, depth, interlaced, true, colorType * 100L + depth);
            var png = image.png(9);
            for (var kernel : List.of(new ScalarPngFilterKernel(), new VectorPngFilterKernel())) {
                var reader = new PngReader(MediaSource.of(png), MediaLimits.DEFAULT, false);
                reader.readPrelude();
                var header = reader.header();
                var pixels = new byte[61 * 23 * 4];
                try (var decoder = new PngPixelDecoder(header,
                        PngRowConverter.of(header, reader.palette(), reader.paletteSize(), reader.transparencyKey()), kernel)) {
                    decoder.decode(reader, 61, 23, pixels, 0, 61 * 4);
                }
                RasterTesting.assertSameRgba(image.expected(), pixels, 61, kernel.getClass().getSimpleName());
            }
        }

        @ParameterizedTest(name = "color type {0} depth {1} interlaced {2}")
        @CsvSource({"6, 8, false", "6, 8, true", "2, 8, false", "0, 8, false", "3, 8, false", "6, 16, false"})
        @DisplayName("decodes images of several staging batches, inflated one batch ahead")
        void severalBatches(int colorType, int depth, boolean interlaced) {
            var image = new ReferenceImage(1100, 1000, colorType, depth, interlaced, true, colorType * 31L + depth);
            var picture = decode(image.png(11));
            RasterTesting.assertSameRgba(image.expected(), RasterTesting.tight(picture), 1100, "1100x1000");
        }

        @ParameterizedTest(name = "bad filter on row {0}, stream cut at {1} percent")
        @CsvSource({"10, 60", "10, 45", "10, 30", "300, 80", "0, 99"})
        @DisplayName("reports a bad filter type before the truncation of a later batch")
        void failureOrder(int badRow, int percent) {
            var image = new ReferenceImage(1024, 700, 6, 8, false, false, 77);
            var scanlines = image.scanlines(new Random(5));
            scanlines[badRow * (1024 * 4 + 1)] = 7;
            var zlib = PngFiles.zlib(scanlines);
            var cut = Arrays.copyOf(zlib, (int) (zlib.length * (long) percent / 100));
            var png = PngFiles.create().ihdr(1024, 700, 8, 6, 0).idat(cut).iend().bytes();
            var failure = assertThrows(MediaException.Malformed.class, () -> decode(png));
            assertTrue(failure.getMessage().contains("filter type"), failure.getMessage());
            var intact = PngFiles.create().ihdr(1024, 700, 8, 6, 0)
                    .idat(Arrays.copyOf(PngFiles.zlib(image.scanlines(new Random(5))), zlib.length * 3 / 4))
                    .iend().bytes();
            var truncated = assertThrows(MediaException.Malformed.class, () -> decode(intact));
            assertTrue(truncated.getMessage().contains("truncated"), truncated.getMessage());
        }

        @Test
        @DisplayName("decodes a wide image whose rows exceed the staging buffer")
        void wideRows() {
            var image = new ReferenceImage(70_000, 3, 6, 16, false, false, 42);
            var options = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(100_000)
                    .maxPixels(1_000_000).maxFrames(1).maxChannels(1).maxBytes(1 << 30).build()).build();
            var picture = (RgbaPicture) PngDecoder.decode(MediaSource.of(image.png(1)), options).picture();
            RasterTesting.assertSameRgba(image.expected(), RasterTesting.tight(picture), 70_000, "wide");
        }
    }

    @Nested
    @DisplayName("conversion")
    class Conversion {
        @Test
        @DisplayName("reduces every 16-bit value with (v * 255 + 32767) / 65535")
        void reduce16() {
            for (var value = 0; value < 65536; value++) {
                assertEquals((value * 255 + 32767) / 65535, PngRowConverter.reduce16(value), "value " + value);
            }
        }

        @Test
        @DisplayName("rejects a palette index past a short palette, as libpng does")
        void paletteOverflow() {
            var eightBit = PngFiles.create().ihdr(2, 1, 8, 3, 0).chunk("PLTE", new byte[]{1, 2, 3, 4, 5, 6})
                    .idat(PngFiles.zlib(new byte[]{0, 1, 2})).iend().bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(eightBit));
            var oneBit = PngFiles.create().ihdr(8, 1, 1, 3, 0).chunk("PLTE", new byte[]{1, 2, 3})
                    .idat(PngFiles.zlib(new byte[]{0, 0x01})).iend().bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(oneBit));
            var valid = PngFiles.create().ihdr(8, 1, 1, 3, 0).chunk("PLTE", new byte[]{1, 2, 3})
                    .idat(PngFiles.zlib(new byte[]{0, 0})).iend().bytes();
            assertEquals(1, decode(valid).pixels()[0]);
        }

        @Test
        @DisplayName("truncates a palette longer than the bit depth can index")
        void longPalette() {
            var plte = new byte[3 * 5];
            for (var i = 0; i < plte.length; i++) {
                plte[i] = (byte) (i + 1);
            }
            var data = PngFiles.create().ihdr(2, 1, 2, 3, 0).chunk("PLTE", plte)
                    .idat(PngFiles.zlib(new byte[]{0, (byte) 0b1100_0000})).iend().bytes();
            var pixels = decode(data).pixels();
            assertArrayEquals(new byte[]{10, 11, 12, (byte) 255, 1, 2, 3, (byte) 255}, pixels);
        }

        @Test
        @DisplayName("ignores tRNS that is longer than the palette, misplaced, or wrong for the color type")
        void invalidTransparency() {
            var longer = PngFiles.create().ihdr(1, 1, 8, 3, 0).chunk("PLTE", new byte[]{1, 2, 3})
                    .chunk("tRNS", new byte[]{0, 0}).idat(PngFiles.zlib(new byte[]{0, 0})).iend().bytes();
            assertFalse(decode(longer).hasAlpha());
            var beforePalette = PngFiles.create().ihdr(1, 1, 8, 3, 0).chunk("tRNS", new byte[]{0})
                    .chunk("PLTE", new byte[]{1, 2, 3}).idat(PngFiles.zlib(new byte[]{0, 0})).iend().bytes();
            assertEquals((byte) 255, decode(beforePalette).pixels()[3]);
            var withAlpha = PngFiles.rgbaWithChunk("tRNS", new byte[]{0, 0, 0, 0, 0, 0});
            assertTrue(decode(withAlpha).hasAlpha());
            var grayOutOfRange = PngFiles.create().ihdr(1, 1, 4, 0, 0).chunk("tRNS", new byte[]{0, 16})
                    .idat(PngFiles.zlib(new byte[]{0, 0})).iend().bytes();
            assertFalse(decode(grayOutOfRange).hasAlpha());
        }
    }

    @Nested
    @DisplayName("chunk structure")
    class Structure {
        @Test
        @DisplayName("rejects empty, short and non-PNG inputs")
        void notPng() {
            assertThrows(MediaException.Malformed.class, () -> decode(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> decode(Arrays.copyOf(PngFiles.SIGNATURE, 7)));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.SIGNATURE));
            assertThrows(MediaException.Malformed.class, () -> decode("GIF89a.....".getBytes()));
            assertThrows(MediaException.Malformed.class, () -> PngDecoder.probe(MediaSource.of(new byte[0])));
        }

        @ParameterizedTest(name = "color type {0} depth {1}")
        @CsvSource({"0,3", "0,32", "2,4", "2,1", "3,16", "4,4", "6,2", "1,8", "5,8", "7,8", "255,8"})
        @DisplayName("rejects invalid color type and bit depth combinations")
        void invalidHeader(int colorType, int depth) {
            var data = PngFiles.create().ihdr(1, 1, depth, colorType, 0).idat(PngFiles.zlib(new byte[]{0, 0})).iend().bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        @ParameterizedTest(name = "byte {0} = {1}")
        @CsvSource({"10, 1", "11, 64", "12, 2"})
        @DisplayName("rejects invalid compression, filter and interlace methods")
        void invalidMethods(int index, int value) {
            var ihdr = new byte[]{0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0};
            ihdr[index] = (byte) value;
            var data = PngFiles.create().chunk("IHDR", ihdr).idat(PngFiles.zlib(new byte[5])).iend().bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"0, 1", "1, 0", "-2147483648, 1", "-1, 1"})
        @DisplayName("rejects zero and out-of-range dimensions")
        void invalidSize(int width, int height) {
            var data = PngFiles.create().ihdr(width, height, 8, 6, 0).idat(PngFiles.zlib(new byte[5])).iend().bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(data));
        }

        @Test
        @DisplayName("requires IHDR first, exactly once, with length 13")
        void ihdrRules() {
            var idat = PngFiles.zlib(new byte[5]);
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().chunk("tEXt", PngFiles.ascii("a\0b"))
                    .ihdr(1, 1, 8, 6, 0).idat(idat).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .ihdr(1, 1, 8, 6, 0).idat(idat).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create()
                    .chunk("IHDR", new byte[14]).idat(idat).iend().bytes()));
        }

        @Test
        @DisplayName("requires PLTE for indexed color, rejects a second PLTE, ignores PLTE in grayscale")
        void paletteRules() {
            var idat = PngFiles.zlib(new byte[2]);
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 3, 0)
                    .idat(idat).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 3, 0)
                    .chunk("PLTE", new byte[3]).chunk("PLTE", new byte[3]).idat(idat).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 3, 0)
                    .chunk("PLTE", new byte[4]).idat(idat).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 3, 0)
                    .chunk("PLTE", new byte[0]).idat(idat).iend().bytes()));
            var gray = PngFiles.create().ihdr(1, 1, 8, 0, 0).chunk("PLTE", new byte[4])
                    .idat(PngFiles.zlib(new byte[]{0, 77})).iend().bytes();
            assertEquals(77, decode(gray).pixels()[0]);
        }

        @Test
        @DisplayName("rejects critical chunk CRC mismatches and skips ancillary ones")
        void crc() {
            var idat = PngFiles.zlib(new byte[]{0, 1, 2, 3, 4});
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .chunk("IDAT", idat, true).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 3, 0)
                    .chunk("PLTE", new byte[3], true).idat(PngFiles.zlib(new byte[2])).iend().bytes()));
            var textCrc = PngFiles.create().ihdr(1, 1, 8, 6, 0).chunk("tEXt", PngFiles.ascii("k\0v"), true)
                    .idat(idat).iend().bytes();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, decode(textCrc).pixels());
            var transparencyCrc = PngFiles.create().ihdr(1, 1, 8, 2, 0).chunk("tRNS", new byte[6], true)
                    .idat(PngFiles.zlib(new byte[4])).iend().bytes();
            assertFalse(decode(transparencyCrc).hasAlpha());
        }

        @Test
        @DisplayName("reports unknown critical chunks as unsupported and skips unknown ancillary ones")
        void unknownChunks() {
            var idat = PngFiles.zlib(new byte[]{0, 1, 2, 3, 4});
            var critical = PngFiles.create().ihdr(1, 1, 8, 6, 0).chunk("CgBI", new byte[4]).idat(idat).iend().bytes();
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(critical));
            assertEquals("png CgBI", exception.format());
            var ancillary = PngFiles.create().ihdr(1, 1, 8, 6, 0).chunk("prVt", new byte[100]).idat(idat).iend().bytes();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, decode(ancillary).pixels());
        }

        @Test
        @DisplayName("rejects invalid chunk types and lengths")
        void invalidChunks() {
            var idat = PngFiles.zlib(new byte[5]);
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .chunk("t3Xt", new byte[1]).idat(idat).iend().bytes()));
            var hugeLength = PngFiles.create().ihdr(1, 1, 8, 6, 0).raw(new byte[]{(byte) 0x80, 0, 0, 0, 't', 'E', 'X', 't'}).bytes();
            assertThrows(MediaException.Malformed.class, () -> decode(hugeLength));
        }

        @Test
        @DisplayName("rejects image data that ends early, is corrupt, uses a dictionary or a bad filter")
        void imageData() {
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(2, 2, 8, 6, 0)
                    .idat(PngFiles.zlib(new byte[9])).iend().bytes()));
            var corrupt = PngFiles.zlib(new byte[18]);
            corrupt[2] ^= (byte) 0xC0;
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(2, 2, 8, 6, 0)
                    .idat(corrupt).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .idat(new byte[]{0x78, (byte) 0xBB, 0, 0, 0, 1, 0}).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .idat(PngFiles.zlib(new byte[]{5, 1, 2, 3, 4})).iend().bytes()));
            assertThrows(MediaException.Malformed.class, () -> decode(PngFiles.create().ihdr(1, 1, 8, 6, 0)
                    .chunk("tEXt", PngFiles.ascii("a\0b")).iend().bytes()));
        }

        @Test
        @DisplayName("ignores data after the image and a missing IEND")
        void trailingData() {
            var extra = PngFiles.create().ihdr(1, 1, 8, 6, 0).idat(PngFiles.zlib(new byte[]{0, 1, 2, 3, 4, 0, 9, 9, 9, 9}))
                    .iend().bytes();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, decode(extra).pixels());
            var noEnd = PngFiles.create().ihdr(1, 1, 8, 6, 0).idat(PngFiles.zlib(new byte[]{0, 1, 2, 3, 4})).bytes();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, decode(noEnd).pixels());
        }

        @Test
        @DisplayName("rejects truncation at every offset before the end of the image data")
        void truncation() {
            var image = new ReferenceImage(13, 11, 6, 8, true, false, 5);
            var full = PngFiles.create().ihdr(13, 11, 8, 6, 1).idat(PngFiles.zlib(image.scanlines(new Random(3)))).iend().bytes();
            var imageEnd = full.length - 12;
            for (var length = 0; length < imageEnd; length++) {
                var prefix = Arrays.copyOf(full, length);
                assertThrows(MediaException.Malformed.class, () -> decode(prefix), "prefix " + length);
            }
            for (var length = imageEnd; length <= full.length; length++) {
                decode(Arrays.copyOf(full, length));
            }
        }

        @Test
        @DisplayName("reads through file and channel sources as through arrays")
        void sources() throws Exception {
            var data = PngFiles.rgba(3, 2, solidRgba(3, 2));
            var path = RasterTesting.write("source-test.png", data);
            try (var source = MediaSource.of(path)) {
                assertArrayEquals(solidRgba(3, 2), RasterTesting.tight((RgbaPicture) PngDecoder.decode(source, OPTIONS).picture()));
            }
            try (var channel = Files.newByteChannel(path); var source = MediaSource.of((SeekableByteChannel) channel)) {
                assertEquals(3, PngDecoder.probe(source).width());
            }
            var padded = new byte[data.length + 10];
            System.arraycopy(data, 0, padded, 5, data.length);
            assertEquals(2, PngDecoder.probe(MediaSource.of(padded, 5, data.length)).height());
            assertThrows(NullPointerException.class, () -> PngDecoder.decode(null, OPTIONS));
            assertThrows(NullPointerException.class, () -> PngDecoder.decode(MediaSource.of(data), null));
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {
        private static byte[] tiff(boolean little, int orientation, int type) {
            var out = new byte[26];
            var buffer = ByteBuffer.wrap(out).order(little ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
            out[0] = out[1] = (byte) (little ? 'I' : 'M');
            buffer.putShort(2, (short) 42);
            buffer.putInt(4, 8);
            buffer.putShort(8, (short) 1);
            buffer.putShort(10, (short) 0x0112);
            buffer.putShort(12, (short) type);
            buffer.putInt(14, 1);
            if (type == 3) {
                buffer.putShort(18, (short) orientation);
            } else {
                buffer.putInt(18, orientation);
            }
            buffer.putInt(22, 0);
            return out;
        }

        @ParameterizedTest(name = "little-endian={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("reads the eXIf orientation in either byte order")
        void orientation(boolean little) {
            var exif = tiff(little, 6, 3);
            var data = PngFiles.rgbaWithChunk("eXIf", exif);
            var image = PngDecoder.decode(MediaSource.of(data), OPTIONS);
            assertEquals(Orientation.ROTATE_90, image.orientation());
            assertArrayEquals(exif, image.exif().orElseThrow());
            assertEquals(Orientation.ROTATE_90, PngDecoder.probe(MediaSource.of(data)).orientation());
            assertEquals(1, PngDecoder.probe(MediaSource.of(data)).displayWidth());
        }

        @Test
        @DisplayName("strips an Exif identifier prefix and accepts a LONG orientation")
        void prefixedExif() {
            var body = tiff(true, 8, 4);
            var prefixed = RasterTesting.concat(new byte[]{'E', 'x', 'i', 'f', 0, 0}, body);
            var image = PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("eXIf", prefixed)), OPTIONS);
            assertEquals(Orientation.ROTATE_270, image.orientation());
            assertArrayEquals(body, image.exif().orElseThrow());
        }

        @Test
        @DisplayName("ignores invalid Exif blocks and eXIf after the image data")
        void invalidExif() {
            var garbage = PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("eXIf", new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9})), OPTIONS);
            assertTrue(garbage.exif().isEmpty());
            assertEquals(Orientation.NORMAL, garbage.orientation());
            var late = PngFiles.create().ihdr(1, 1, 8, 6, 0).idat(PngFiles.zlib(new byte[5]))
                    .chunk("eXIf", tiff(true, 3, 3)).iend().bytes();
            assertEquals(Orientation.NORMAL, PngDecoder.decode(MediaSource.of(late), OPTIONS).orientation());
            var badOffset = tiff(true, 6, 3);
            badOffset[4] = 100;
            assertEquals(Orientation.NORMAL, PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("eXIf", badOffset)), OPTIONS).orientation());
        }

        @Test
        @DisplayName("inflates the iCCP profile and ignores a corrupt or misplaced one")
        void iccProfile() {
            var profile = new byte[3000];
            new Random(3).nextBytes(profile);
            var iccp = RasterTesting.concat(PngFiles.ascii("ICC Profile\0\0"), PngFiles.zlib(profile));
            var image = PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("iCCP", iccp)), OPTIONS);
            assertArrayEquals(profile, image.iccProfile().orElseThrow());
            var corrupt = iccp.clone();
            corrupt[corrupt.length - 10] ^= 0x7F;
            assertTrue(PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("iCCP", corrupt)), OPTIONS).iccProfile().isEmpty());
            var noName = RasterTesting.concat(new byte[]{0, 0}, PngFiles.zlib(profile));
            assertTrue(PngDecoder.decode(MediaSource.of(PngFiles.rgbaWithChunk("iCCP", noName)), OPTIONS).iccProfile().isEmpty());
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("checks the declared size against the limits before allocating")
        void declaredSize() {
            var bomb = PngFiles.create().ihdr(100_000, 100_000, 8, 6, 0).idat(PngFiles.zlib(new byte[100])).iend().bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> decode(bomb));
            var small = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(64).maxPixels(1000)
                    .maxFrames(10).maxChannels(2).maxBytes(1 << 20).build()).build();
            var image = PngFiles.rgba(40, 40, solidRgba(40, 40));
            assertThrows(MediaException.LimitExceeded.class, () -> PngDecoder.decode(MediaSource.of(image), small));
            assertEquals(40, PngDecoder.probe(MediaSource.of(image)).width());
        }

        @Test
        @DisplayName("never inflates past the declared scanlines of a decompression bomb")
        void bomb() {
            var raw = new byte[64 << 20];
            var data = PngFiles.create().ihdr(16, 16, 8, 6, 0).idat(PngFiles.zlib(raw)).iend().bytes();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertEquals(16, decode(data).width()));
        }
    }

    @Nested
    @DisplayName("ffmpeg-written images")
    class FfmpegWritten {
        @ParameterizedTest(name = "{0} pred={1}")
        @CsvSource({"rgb24,none", "rgb24,paeth", "rgba,mixed", "rgba,avg", "rgb48be,mixed", "rgba64be,sub", "pal8,none",
                "gray,up", "ya8,paeth", "gray16be,mixed", "ya16be,avg", "monob,none"})
        @DisplayName("decodes every pixel format bit-exactly against ffmpeg at an odd size")
        void pixelFormats(String format, String pred) {
            var file = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=160x90:rate=1"),
                    List.of("-frames:v", "1", "-vf", "format=rgba,crop=157:87:1:1,format=" + format, "-pred", pred));
            var picture = decode(Fixtures.read(file));
            var wide = format.contains("48") || format.contains("64") || format.contains("16");
            var expected = wide ? RasterTesting.ffmpegReduced16(file) : RasterTesting.ffmpegFrames(file, "rgba");
            RasterTesting.assertSameRgba(expected, RasterTesting.tight(picture), 157, format);
        }
    }

    @Test
    @DisplayName("survives a deterministic mutation run: every variant decodes or raises a MediaException")
    void mutations() {
        var seeds = List.of(
                new ReferenceImage(23, 17, 6, 8, false, false, 1).png(1),
                new ReferenceImage(17, 23, 3, 4, true, true, 2).png(2),
                new ReferenceImage(9, 9, 0, 16, true, true, 3).png(3),
                PngFiles.create().ihdr(8, 8, 8, 6, 0).actl(2, 0).fctl(0, 8, 8, 0, 0, 1, 10, 0, 0)
                        .idat(PngFiles.zlib(PngFiles.unfiltered(solidRgba(8, 8), 32)))
                        .fctl(1, 4, 4, 2, 2, 1, 10, 2, 1).fdat(2, PngFiles.zlib(PngFiles.unfiltered(solidRgba(4, 4), 16)))
                        .iend().bytes());
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () -> RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(150), 0xC0BA17L, source -> {
            PngDecoder.decode(source, OPTIONS);
            PngDecoder.probe(source);
            try (var reader = PngDecoder.openAnimation(source, OPTIONS)) {
                for (var current = reader.read(); current != null; current = reader.read()) {
                    current.picture();
                }
            }
        }));
        assertEquals(seeds.size() * RasterTesting.mutationsPerSeed(150), stats.variants());
        System.out.println("PNG mutations: " + stats);
        var repaired = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () -> RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(150), 0xC0BA18L,
                PngDecoderTest::repairCrcs, source -> {
                    PngDecoder.decode(source, OPTIONS);
                    try (var reader = PngDecoder.openAnimation(source, OPTIONS)) {
                        for (var current = reader.read(); current != null; current = reader.read()) {
                            current.picture();
                        }
                    }
                }));
        System.out.println("PNG mutations with CRCs repaired: " + repaired);
    }

    /** Recomputes the CRC of every chunk that fits, so mutations reach the chunk contents. */
    static byte[] repairCrcs(byte[] data) {
        var offset = 8;
        while (offset + 12 <= data.length) {
            var length = ((data[offset] & 0xFFL) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
            if (offset + 12 + length > data.length) {
                break;
            }
            var crc = new java.util.zip.CRC32();
            crc.update(data, offset + 4, (int) length + 4);
            var value = (int) crc.getValue();
            var at = offset + 8 + (int) length;
            data[at] = (byte) (value >>> 24);
            data[at + 1] = (byte) (value >>> 16);
            data[at + 2] = (byte) (value >>> 8);
            data[at + 3] = (byte) value;
            offset = at + 4;
        }
        return data;
    }
}
