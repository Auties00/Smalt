package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.image.jpeg.JpegTransforms;
import com.github.auties00.smalt.image.jpeg.MjpegDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the lossless JPEG variants beyond one interleaved Huffman scan of 1x1 components, and the frames of two
 * or more than four components: the ITU-T T.83 compliance streams O1, O2 (Huffman) and P1, P2 (arithmetic), which
 * interleave subsampled components or code one component per scan, decoded against their reference samples and
 * re-encoded byte for byte by the test encoder, which then covers what the compliance streams do not (restart
 * intervals that split rows, more scans, more components, every predictor, precision and conditioning bound).
 */
@DisplayName("lossless JPEG variants")
class LosslessVariantsTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final ImageDecoderOptions WIDE_KEEP = WIDE.toBuilder().keepChannels(true).build();

    static int[][] decodeComponents(byte[] jpeg) {
        return decodeComponents(new LosslessJpeg(jpeg, 0, jpeg.length, 4, 0, MediaLimits.DEFAULT));
    }

    static int[][] decodeComponents(LosslessJpeg scan) {
        var out = new int[scan.components()][];
        var rows = new int[scan.components()];
        for (var c = 0; c < out.length; c++) {
            out[c] = new int[scan.componentWidth(c) * scan.componentHeight(c)];
        }
        scan.decodeComponents((c, row, samples) -> {
            assertEquals(rows[c]++, row, "row order of component " + c);
            var width = scan.componentWidth(c);
            System.arraycopy(samples, 0, out[c], row * width, width);
        });
        for (var c = 0; c < out.length; c++) {
            assertEquals(scan.componentHeight(c), rows[c], "rows of component " + c);
        }
        return out;
    }

    static int[][] planes(int width, int height, int[][] factors, int precision, long seed) {
        var planes = new int[factors.length][];
        for (var c = 0; c < factors.length; c++) {
            planes[c] = LosslessJpegTest.noise(Lj92Encoder.componentWidth(width, factors, c)
                    * Lj92Encoder.componentHeight(height, factors, c), precision, seed + 31L * c);
        }
        return planes;
    }

    static int[][] masked(int[][] planes, int transform) {
        var mask = -(1 << transform);
        var out = new int[planes.length][];
        for (var c = 0; c < planes.length; c++) {
            out[c] = Arrays.stream(planes[c]).map(v -> v & mask).toArray();
        }
        return out;
    }

    static int[][] ones(int count) {
        var factors = new int[count][];
        for (var c = 0; c < count; c++) {
            factors[c] = new int[]{1, 1};
        }
        return factors;
    }

    static int[] range(int count) {
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = i;
        }
        return out;
    }

    // The sample of a component under pixel (x, y) when the component is replicated up to the frame size.
    static int replicated(int[] plane, int width, int height, int[][] factors, int c, int x, int y) {
        var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
        var vMax = Arrays.stream(factors).mapToInt(f -> f[1]).max().orElseThrow();
        var cw = Lj92Encoder.componentWidth(width, factors, c);
        return plane[(y * factors[c][1] / vMax) * cw + x * factors[c][0] / hMax];
    }

    static byte[] withIcc(byte[] jpeg, byte[] icc) {
        var out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xD8);
        var length = 2 + 14 + icc.length;
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE2, (byte) (length >> 8), (byte) length});
        out.writeBytes("ICC_PROFILE\0".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        out.write(1);
        out.writeBytes(icc);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static byte[] withAdobe(byte[] jpeg) {
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xEE, 0, 14, 'A', 'd', 'o', 'b', 'e', 0, 100, 0, 0, 0, 0, 0});
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static DecodedImage decode(byte[] jpeg, ImageDecoderOptions options) {
        return JpegDecoder.decode(MediaSource.of(jpeg), options);
    }

    @Nested
    @DisplayName("ITU-T T.83 compliance streams")
    class Compliance {
        // Components A to D of every T.83 stream: identifiers in frame order and sampling factors of 255x257 samples
        private static final int[][] FACTORS = {{1, 1}, {1, 2}, {3, 1}, {1, 4}};

        static byte[] entry(String name) {
            var archive = Conformance.fetchListed("itu-t83-data-files.zip");
            try (var zip = new ZipFile(archive.toFile())) {
                var entry = zip.getEntry(name);
                try (var input = zip.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        // The reference samples of components A to D: T83_encoder_8 for the 8-bit streams, and the 16-bit
        // big-endian files of the process directory for the 16-bit ones.
        static int[][] reference(String directory, int precision) {
            var out = new int[4][];
            for (var c = 0; c < 4; c++) {
                var letter = (char) ('A' + c);
                if (precision == 8) {
                    var data = entry("T83_encoder_8/" + letter + "8.SRC");
                    out[c] = new int[data.length];
                    for (var i = 0; i < data.length; i++) {
                        out[c][i] = data[i] & 0xFF;
                    }
                } else {
                    var data = entry(directory + "/" + letter + "16.SRC");
                    out[c] = new int[data.length / 2];
                    for (var i = 0; i < out[c].length; i++) {
                        out[c][i] = ((data[2 * i] & 0xFF) << 8) | (data[2 * i + 1] & 0xFF);
                    }
                }
            }
            return out;
        }

        static Stream<Arguments> streams() {
            return Stream.of(Arguments.of("T83_process14", "O1", 8), Arguments.of("T83_process14", "O2", 16),
                    Arguments.of("T83_process15", "P1", 8), Arguments.of("T83_process15", "P2", 16));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("streams")
        @DisplayName("decode to their reference samples, every component at its own size")
        void samples(String directory, String stream, int precision) {
            var jpeg = entry(directory + "/" + stream + ".JPG");
            var expected = reference(directory, precision);
            var decoded = decodeComponents(jpeg);
            assertEquals(4, decoded.length);
            for (var c = 0; c < 4; c++) {
                assertEquals(expected[c].length, decoded[c].length, "size of component " + c);
                assertArrayEquals(expected[c], decoded[c], stream + " component " + c);
            }
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("streams")
        @DisplayName("decode through JpegDecoder to CMYK channels with the components replicated to the frame size")
        void picture(String directory, String stream, int precision) {
            var jpeg = entry(directory + "/" + stream + ".JPG");
            var expected = reference(directory, precision);
            var options = ImageDecoderOptions.builder().highBitDepth(true).keepChannels(true).build();
            var picture = decode(jpeg, options).picture();
            assertEquals(255, picture.width());
            assertEquals(257, picture.height());
            for (var y = 0; y < 257; y++) {
                for (var x = 0; x < 255; x++) {
                    for (var c = 0; c < 4; c++) {
                        var value = replicated(expected[c], 255, 257, FACTORS, c, x, y);
                        var actual = switch (picture) {
                            case Channel8Picture channels -> channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFF;
                            case Channel16Picture channels ->
                                    channels.pixels()[y * channels.stride() + 4 * x + c] & 0xFFFF;
                            default -> throw new AssertionError(picture);
                        };
                        assertEquals(value, actual, stream + " pixel " + x + "," + y + " channel " + c);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("streams")
        @DisplayName("are what the test encoder writes for their samples and parameters, entropy-coded byte for byte")
        void reencoded(String directory, String stream, int precision) {
            var jpeg = entry(directory + "/" + stream + ".JPG");
            var arithmetic = stream.startsWith("P");
            var scans = switch (stream) {
                case "O1" -> List.of(new Lj92Encoder.ScanSpec(range(4), new int[]{0, 1, 0, 1}, 1, 0, 85));
                case "O2" -> List.of(new Lj92Encoder.ScanSpec(new int[]{0}, new int[]{0}, 7, 0, 170),
                        new Lj92Encoder.ScanSpec(new int[]{1}, new int[]{1}, 7, 0, 0),
                        new Lj92Encoder.ScanSpec(new int[]{2}, new int[]{0}, 1, 0, 255),
                        new Lj92Encoder.ScanSpec(new int[]{3}, new int[]{1}, 7, 0, 0));
                case "P1" -> List.of(new Lj92Encoder.ScanSpec(range(4), range(4), 1, 0, 85));
                default -> List.of(new Lj92Encoder.ScanSpec(new int[]{0}, new int[]{0}, 1, 0, 170),
                        new Lj92Encoder.ScanSpec(new int[]{1}, new int[]{1}, 2, 0, 0),
                        new Lj92Encoder.ScanSpec(new int[]{2}, new int[]{2}, 4, 0, 0),
                        new Lj92Encoder.ScanSpec(new int[]{3}, new int[]{3}, 7, 0, 0));
            };
            var tables = tablesOf(jpeg);
            var encoded = Lj92Encoder.encodeFrame(reference(directory, precision), 255, 257, FACTORS, precision,
                    arithmetic, scans, tables);
            var expectedScans = entropyCoded(jpeg);
            var actualScans = entropyCoded(encoded);
            assertEquals(expectedScans.size(), actualScans.size());
            for (var s = 0; s < expectedScans.size(); s++) {
                assertArrayEquals(expectedScans.get(s), actualScans.get(s), stream + " scan " + s);
            }
            for (var c = 0; c < 4; c++) {
                assertArrayEquals(decodeComponents(jpeg)[c], decodeComponents(encoded)[c]);
            }
        }

        // The DHT tables, DAC values and component identifiers of a stream.
        static Lj92Encoder.Tables tablesOf(byte[] jpeg) {
            var huffman = new int[4][];
            int[] conditioning = null;
            var ids = new int[4];
            var at = 2;
            while (at + 4 <= jpeg.length) {
                if ((jpeg[at] & 0xFF) != 0xFF) {
                    at++;
                    continue;
                }
                var marker = jpeg[at + 1] & 0xFF;
                if (marker == 0xDA) {
                    break;
                }
                var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
                var body = at + 4;
                var end = at + 2 + length;
                switch (marker) {
                    case 0xC4 -> {
                        var p = body;
                        while (p < end) {
                            var slot = jpeg[p] & 15;
                            var total = 0;
                            for (var i = 1; i <= 16; i++) {
                                total += jpeg[p + i] & 0xFF;
                            }
                            var table = new int[16 + total];
                            for (var i = 0; i < 16 + total; i++) {
                                table[i] = jpeg[p + 1 + i] & 0xFF;
                            }
                            huffman[slot] = table;
                            p += 17 + total;
                        }
                    }
                    case 0xCC -> {
                        conditioning = new int[]{-1, -1, -1, -1};
                        for (var p = body; p < end; p += 2) {
                            conditioning[jpeg[p] & 15] = jpeg[p + 1] & 0xFF;
                        }
                    }
                    case 0xC3, 0xCB -> {
                        for (var c = 0; c < 4; c++) {
                            ids[c] = jpeg[body + 6 + 3 * c] & 0xFF;
                        }
                    }
                    default -> {
                    }
                }
                at = end;
            }
            return new Lj92Encoder.Tables(huffman, conditioning, ids);
        }

        // The entropy-coded data of every scan, restart markers included.
        static List<byte[]> entropyCoded(byte[] jpeg) {
            var scans = new ArrayList<byte[]>();
            var at = 2;
            while (at + 1 < jpeg.length) {
                if ((jpeg[at] & 0xFF) != 0xFF || (jpeg[at + 1] & 0xFF) == 0xFF) {
                    at++;
                    continue;
                }
                var marker = jpeg[at + 1] & 0xFF;
                if (marker == 0xD8 || marker == 0xD9 || marker == 0x00) {
                    at += 2;
                    continue;
                }
                var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
                at += 2 + length;
                if (marker != 0xDA) {
                    continue;
                }
                var start = at;
                while (at + 1 < jpeg.length) {
                    var next = jpeg[at + 1] & 0xFF;
                    if ((jpeg[at] & 0xFF) == 0xFF && next != 0 && (next < 0xD0 || next > 0xD7)) {
                        break;
                    }
                    at++;
                }
                scans.add(Arrays.copyOfRange(jpeg, start, at));
            }
            return scans;
        }

        @Test
        @DisplayName("B2 (with the tables of B1) decodes its 255 components, the first four as interleaved A1 codes them")
        void twoHundredFiftyFiveComponents() {
            var tables = entry("T83_process1/B1.JPG");
            var image = entry("T83_process1/B2.JPG");
            var jpeg = new byte[tables.length - 2 + image.length - 2];
            System.arraycopy(tables, 0, jpeg, 0, tables.length - 2);
            System.arraycopy(image, 2, jpeg, tables.length - 2, image.length - 2);
            var limits = MediaLimits.builder().maxChannels(255).build();
            var coefficients = new JpegReader(jpeg, 0, jpeg.length, limits).decodeCoefficients();
            var a1 = entry("T83_process1/A1.JPG");
            var interleaved = new JpegReader(a1, 0, a1.length, MediaLimits.DEFAULT).decodeCoefficients();
            for (var c = 0; c < 4; c++) {
                // the reference holds the blocks of the component's own grid in raster order as 16-bit big-endian
                // coefficients in zigzag order, within one of what the streams code
                var reference = entry("T83_process1/DREF_" + (char) ('A' + c) + "8.DCT");
                var component = coefficients.frame().components[c];
                var blocks = coefficients.coefficients(c);
                var expected = interleaved.coefficients(c);
                assertEquals(expected.length, blocks.length);
                assertEquals(reference.length, component.blocksWide * component.blocksHigh * 128,
                        "blocks of component " + c);
                for (var block = 0; block < component.blocksWide * component.blocksHigh; block++) {
                    var stored = (block / component.blocksWide) * component.paddedBlocksWide + block % component.blocksWide;
                    for (var k = 0; k < 64; k++) {
                        var at = 2 * (block * 64 + k);
                        var value = (short) (((reference[at] & 0xFF) << 8) | (reference[at + 1] & 0xFF));
                        var index = stored * 64 + JpegTables.ZIGZAG[k];
                        assertEquals(expected[index], blocks[index], "component " + c + " block " + block + " k " + k);
                        assertTrue(Math.abs(value - blocks[index]) <= 1, "component " + c + " block " + block + " k " + k);
                    }
                }
            }
            var options = ImageDecoderOptions.builder().limits(limits).keepChannels(true).build();
            var decoded = decode(jpeg, options);
            var picture = assertInstanceOf(Channel8Picture.class, decoded.picture());
            assertEquals(255, picture.channelCount());
            assertEquals(JpegOutput.rawLayout(255), picture.layout());
            var planes = new JpegReader(jpeg, 0, jpeg.length, limits).decodePlanes(0);
            // the factors are integral fractions of the maxima of 3x4 that no triangle filter handles: every pixel
            // of a channel replicates a sample of its component
            for (var c = 0; c < 255; c += 3) {
                var component = planes.frame().components[c];
                for (var y = 0; y < 257; y += 7) {
                    for (var x = 0; x < 255; x += 5) {
                        assertEquals(planes.plane(c)[(y * component.v / 4) * planes.stride(c) + x * component.h / 3],
                                picture.pixels()[y * picture.stride() + 255 * x + c], "channel " + c);
                    }
                }
            }
            assertEquals("channel count 255 exceeds the 64 channel limit",
                    assertThrows(MediaException.LimitExceeded.class,
                            () -> decode(jpeg, ImageDecoderOptions.defaults())).getMessage());
        }
    }

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of("1x1", new int[][]{{1, 1}, {1, 1}, {1, 1}}),
                Arguments.of("4:2:0", new int[][]{{2, 2}, {1, 1}, {1, 1}}),
                Arguments.of("4:1:1", new int[][]{{4, 1}, {1, 1}, {1, 1}}),
                Arguments.of("T.83", new int[][]{{1, 1}, {1, 2}, {3, 1}, {1, 4}}),
                Arguments.of("larger chroma, two components", new int[][]{{1, 1}, {2, 2}}),
                Arguments.of("one subsampled component", new int[][]{{3, 2}}));
    }

    static Stream<Arguments> arithmeticSettings() {
        var arguments = Stream.<Arguments>builder();
        var sizes = new int[][]{{37, 29}, {1, 1}, {64, 3}, {5, 17}};
        var index = 0;
        for (var layout : layouts().toList()) {
            var name = (String) layout.get()[0];
            var factors = (int[][]) layout.get()[1];
            for (var predictor = 1; predictor <= 7; predictor++) {
                var size = sizes[index++ % sizes.length];
                arguments.add(Arguments.of(name, factors, predictor, size[0], size[1], 8, 0, 0, 0x10));
            }
            arguments.add(Arguments.of(name, factors, 4, 37, 29, 12, 1, 0, 0x32));
            arguments.add(Arguments.of(name, factors, 7, 23, 19, 16, 3, 3, 0xF0));
            arguments.add(Arguments.of(name, factors, 6, 31, 21, 2, 1, 0, 0x00));
            arguments.add(Arguments.of(name, factors, 5, 30, 20, 10, 7, 2, 0xFF));
        }
        return arguments.build();
    }

    @Nested
    @DisplayName("arithmetic coding (SOF11)")
    class Arithmetic {
        @ParameterizedTest(name = "{0}, predictor {2}, {3}x{4}, {5} bits, restart every {6} MCUs, Pt {7}, DAC 0x{8}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.internal.LosslessVariantsTest#arithmeticSettings")
        @DisplayName("decodes every layout, predictor, precision, restart interval, point transform and bound exactly")
        void roundTrip(String name, int[][] factors, int predictor, int width, int height, int precision, int restart,
                       int transform, int conditioning) {
            var source = masked(planes(width, height, factors, precision, predictor * 131L + width), transform);
            var tables = Lj92Encoder.Tables.defaults(factors.length);
            var dac = new int[]{conditioning, 0x10, conditioning, -1};
            tables = new Lj92Encoder.Tables(tables.huffman(), dac, tables.ids());
            var slots = new int[factors.length];
            for (var k = 0; k < slots.length; k++) {
                slots[k] = k % 3;
            }
            var jpeg = Lj92Encoder.encodeFrame(source, width, height, factors, precision, true,
                    List.of(new Lj92Encoder.ScanSpec(range(factors.length), slots, predictor, transform, restart)),
                    tables);
            var decoded = decodeComponents(jpeg);
            for (var c = 0; c < factors.length; c++) {
                assertArrayEquals(source[c], decoded[c], "component " + c);
            }
        }

        @Test
        @DisplayName("decodes extreme differences of 16-bit samples, the -32768 and 32768 of the modulo arithmetic")
        void extremes() {
            var width = 16;
            var samples = new int[width * 4];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (i % 3 == 0) ? 0 : (i % 3 == 1) ? 65535 : 32768;
            }
            for (var predictor = 1; predictor <= 7; predictor++) {
                var jpeg = Lj92Encoder.encodeFrame(new int[][]{samples}, width, 4, ones(1), 16, true,
                        List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, predictor, 0, 0)));
                assertArrayEquals(samples, decodeComponents(jpeg)[0], "predictor " + predictor);
            }
        }

        @Test
        @DisplayName("decodes through JpegDecoder and streams no rows, decode() matching decodeComponents()")
        void decoder() {
            var factors = ones(3);
            var source = planes(33, 21, factors, 12, 77);
            var jpeg = Lj92Encoder.encodeFrame(source, 33, 21, factors, 12, true,
                    List.of(Lj92Encoder.ScanSpec.of(range(3), 4, 0, 33)));
            var scan = new LosslessJpeg(jpeg, 0, jpeg.length);
            assertTrue(scan.arithmetic());
            assertFalse(scan.streamsRows());
            var rows = new int[21][];
            scan.decode((row, samples) -> rows[row] = samples.clone());
            for (var y = 0; y < 21; y++) {
                for (var x = 0; x < 33; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(source[c][y * 33 + x], rows[y][3 * x + c]);
                    }
                }
            }
            var picture = assertInstanceOf(Yuv16Picture.class, decode(LosslessSubsampledTest.withJfif(jpeg), WIDE)
                    .picture());
            assertEquals(12, picture.bitDepth());
            assertArrayEquals(Arrays.stream(source).flatMapToInt(Arrays::stream).toArray(),
                    interleavedToPlanar(JpegLosslessTest.samples(picture, 3, 12), 33 * 21, 3));
            assertEquals(33, JpegDecoder.probe(MediaSource.of(jpeg)).width());
        }

        @Test
        @DisplayName("a truncated stream decodes every row, and random damage raises nothing but MediaException")
        void damaged() {
            var factors = new int[][]{{2, 2}, {1, 1}, {1, 1}};
            var source = planes(40, 30, factors, 8, 9);
            var jpeg = LosslessSubsampledTest.withJfif(Lj92Encoder.encodeFrame(source, 40, 30, factors, 8, true,
                    List.of(Lj92Encoder.ScanSpec.of(range(3), 1, 0, 20))));
            for (var cut = jpeg.length / 3; cut < jpeg.length; cut += jpeg.length / 7) {
                var truncated = Arrays.copyOf(jpeg, cut);
                var rows = new int[3];
                new LosslessJpeg(truncated, 0, truncated.length, 4, 0, MediaLimits.DEFAULT)
                        .decodeComponents((c, row, samples) -> rows[c]++);
                assertArrayEquals(new int[]{30, 15, 15}, rows);
            }
            var random = new Random(7);
            for (var trial = 0; trial < 400; trial++) {
                var damaged = jpeg.clone();
                for (var k = 0; k < 1 + random.nextInt(6); k++) {
                    damaged[random.nextInt(damaged.length)] = (byte) random.nextInt(256);
                }
                var input = Arrays.copyOf(damaged, random.nextBoolean() ? damaged.length : random.nextInt(damaged.length));
                try {
                    decode(input, ImageDecoderOptions.defaults());
                } catch (MediaException expected) {
                    assertFalse(expected.getMessage() == null || expected.getMessage().isEmpty());
                }
            }
        }
    }

    static int[] interleavedToPlanar(int[] samples, int pixels, int components) {
        var out = new int[pixels * components];
        for (var i = 0; i < pixels; i++) {
            for (var c = 0; c < components; c++) {
                out[c * pixels + i] = samples[i * components + c];
            }
        }
        return out;
    }

    @Nested
    @DisplayName("frames of several scans")
    class MultiScan {
        static Stream<Arguments> groupings() {
            return Stream.of(
                    Arguments.of("one scan per component", List.of(new int[]{0}, new int[]{1}, new int[]{2})),
                    Arguments.of("two then one", List.of(new int[]{0, 1}, new int[]{2})),
                    Arguments.of("out of frame order", List.of(new int[]{2}, new int[]{1, 0})),
                    Arguments.of("interleaved out of order", List.of(new int[]{2, 0, 1})));
        }

        @ParameterizedTest(name = "{0}, {2}, arithmetic {3}")
        @MethodSource("settings")
        @DisplayName("decode exactly with their own predictor, point transform and restart interval per scan")
        void roundTrip(String name, List<int[]> groups, String layout, boolean arithmetic, int[][] factors) {
            var width = 29;
            var height = 23;
            var precision = arithmetic ? 12 : 16;
            var source = planes(width, height, factors, precision, name.hashCode());
            var scans = new ArrayList<Lj92Encoder.ScanSpec>();
            var expected = new int[factors.length][];
            for (var s = 0; s < groups.size(); s++) {
                var group = groups.get(s);
                var transform = s % 3;
                var slots = new int[group.length];
                Arrays.fill(slots, s % 4);
                scans.add(new Lj92Encoder.ScanSpec(group, slots, 1 + (3 * s + 2) % 7, transform, s == 1 ? 0 : 5 + s));
                for (var c : group) {
                    var mask = -(1 << transform);
                    expected[c] = Arrays.stream(source[c]).map(v -> v & mask).toArray();
                }
            }
            var jpeg = Lj92Encoder.encodeFrame(source, width, height, factors, precision, arithmetic, scans);
            var decoded = decodeComponents(jpeg);
            for (var c = 0; c < factors.length; c++) {
                assertArrayEquals(expected[c], decoded[c], "component " + c);
            }
            var scan = new LosslessJpeg(jpeg, 0, jpeg.length, 4, 0, MediaLimits.DEFAULT);
            var single = groups.size() == 1 && !arithmetic && layout.equals("1x1");
            assertEquals(single, scan.streamsRows());
            if (layout.equals("1x1")) {
                var rows = new int[height][];
                scan.decode((row, samples) -> rows[row] = samples.clone());
                for (var i = 0; i < width * height; i++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(expected[c][i], rows[i / width][3 * (i % width) + c], "decode() component " + c);
                    }
                }
            } else {
                assertThrows(IllegalStateException.class, () -> scan.decode((row, samples) -> { }));
            }
            // JpegDecoder keeps the coded precision: 4:2:0 in its own layout, the others replicated to 4:4:4
            var picture = assertInstanceOf(Yuv16Picture.class, decode(LosslessSubsampledTest.withJfif(jpeg), WIDE)
                    .picture());
            assertEquals(precision, picture.bitDepth());
            var direct = layout.equals("4:2:0");
            assertEquals(direct ? ChromaFormat.YUV420 : ChromaFormat.YUV444, picture.chromaFormat());
            for (var c = 0; c < 3; c++) {
                var plane = c == 0 ? picture.y() : c == 1 ? picture.u() : picture.v();
                var stride = c == 0 ? picture.yStride() : picture.uvStride();
                var columns = direct ? Lj92Encoder.componentWidth(width, factors, c) : width;
                var rows = direct ? Lj92Encoder.componentHeight(height, factors, c) : height;
                for (var y = 0; y < rows; y++) {
                    for (var x = 0; x < columns; x++) {
                        var value = direct ? expected[c][y * columns + x]
                                : replicated(expected[c], width, height, factors, c, x, y);
                        assertEquals(value, plane[y * stride + x] & 0xFFFF, "picture " + x + "," + y + " component " + c);
                    }
                }
            }
        }

        static Stream<Arguments> settings() {
            var arguments = Stream.<Arguments>builder();
            for (var grouping : groupings().toList()) {
                for (var arithmetic : new boolean[]{false, true}) {
                    arguments.add(Arguments.of(grouping.get()[0], grouping.get()[1], "1x1", arithmetic, ones(3)));
                    arguments.add(Arguments.of(grouping.get()[0], grouping.get()[1], "4:2:0", arithmetic,
                            new int[][]{{2, 2}, {1, 1}, {1, 1}}));
                    arguments.add(Arguments.of(grouping.get()[0], grouping.get()[1], "3x2, 1x4, 2x1", arithmetic,
                            new int[][]{{3, 2}, {1, 4}, {2, 1}}));
                }
            }
            return arguments.build();
        }

        @Test
        @DisplayName("take tables redefined between scans")
        void redefinedTables() {
            var factors = ones(3);
            var source = planes(20, 12, factors, 8, 3);
            var huffman = Lj92Encoder.Tables.defaults(3);
            // a second table: two codes of 2 bits, one of each length from 3 to 15 bits and two of 16 bits
            var other = new int[16 + 17];
            for (var category = 0; category <= 16; category++) {
                other[16 + category] = category;
            }
            other[1] = 2;
            for (var length = 3; length <= 15; length++) {
                other[length - 1] = 1;
            }
            other[15] = 2;
            var first = Lj92Encoder.encodeFrame(source, 20, 12, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 1, 0, 0)), huffman);
            var second = Lj92Encoder.encodeFrame(source, 20, 12, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(new int[]{1, 2}, 6, 1, 3)),
                    new Lj92Encoder.Tables(new int[][]{other, other, other, other}, null, huffman.ids()));
            // splice: the frame and first scan of one stream, then the DHT, DRI and scan of the other
            var secondTables = indexOf(second, 0xC4);
            var jpeg = new ByteArrayOutputStream();
            jpeg.write(first, 0, first.length - 2);
            jpeg.write(second, secondTables, second.length - secondTables);
            var decoded = decodeComponents(jpeg.toByteArray());
            assertArrayEquals(source[0], decoded[0]);
            var expected = masked(source, 1);
            assertArrayEquals(expected[1], decoded[1]);
            assertArrayEquals(expected[2], decoded[2]);
            var picture = decode(LosslessSubsampledTest.withJfif(jpeg.toByteArray()), ImageDecoderOptions.defaults())
                    .picture();
            var samples = JpegLosslessTest.samples(picture, 3, 8);
            for (var i = 0; i < 20 * 12; i++) {
                assertEquals(source[0][i], samples[3 * i]);
                assertEquals(expected[2][i], samples[3 * i + 2]);
            }
        }

        static int indexOf(byte[] jpeg, int marker) {
            for (var i = 2; i + 1 < jpeg.length; i++) {
                if (jpeg[i] == (byte) 0xFF && (jpeg[i + 1] & 0xFF) == marker) {
                    return i;
                }
            }
            throw new AssertionError("no marker " + Integer.toHexString(marker));
        }

        @Test
        @DisplayName("decode a component no scan codes, or one after a damaged scan header, as zeros")
        void missingScans() {
            var factors = ones(3);
            var source = planes(17, 9, factors, 8, 5);
            var jpeg = Lj92Encoder.encodeFrame(source, 17, 9, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{1}, 1, 0, 0),
                            Lj92Encoder.ScanSpec.of(new int[]{2}, 1, 0, 0)));
            var sos = new ArrayList<Integer>();
            for (var i = 2; i + 1 < jpeg.length; i++) {
                if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xDA) {
                    sos.add(i);
                }
            }
            assertEquals(3, sos.size());
            var zeros = new int[17 * 9];
            // the stream cut before the third scan
            var cut = Arrays.copyOf(jpeg, sos.get(2));
            var decoded = decodeComponents(cut);
            assertArrayEquals(source[0], decoded[0]);
            assertArrayEquals(source[1], decoded[1]);
            assertArrayEquals(zeros, decoded[2]);
            // the second scan naming the first component again
            var repeated = jpeg.clone();
            repeated[sos.get(1) + 5] = 1;
            decoded = decodeComponents(repeated);
            assertArrayEquals(source[0], decoded[0]);
            assertArrayEquals(zeros, decoded[1]);
            assertArrayEquals(zeros, decoded[2]);
            // a third scan header with an invalid predictor
            var invalid = jpeg.clone();
            invalid[sos.get(2) + 7] = 9;
            decoded = decodeComponents(invalid);
            assertArrayEquals(source[1], decoded[1]);
            assertArrayEquals(zeros, decoded[2]);
            var picture = decode(LosslessSubsampledTest.withJfif(cut), ImageDecoderOptions.defaults()).picture();
            assertEquals(17, picture.width());
            // a second scan header of no bytes, ending the input
            var empty = Arrays.copyOf(jpeg, sos.get(1) + 4);
            empty[sos.get(1) + 2] = 0;
            empty[sos.get(1) + 3] = 2;
            decoded = decodeComponents(empty);
            assertArrayEquals(source[0], decoded[0]);
            assertArrayEquals(zeros, decoded[1]);
            // the same as the first scan header
            var first = Arrays.copyOf(jpeg, sos.get(0) + 4);
            first[sos.get(0) + 2] = 0;
            first[sos.get(0) + 3] = 2;
            assertThrows(MediaException.Malformed.class, () -> new LosslessJpeg(first, 0, first.length, 4, 0,
                    MediaLimits.DEFAULT));
        }

        @Test
        @DisplayName("take the frame height from the DNL segment after the first scan")
        void dnl() {
            var factors = new int[][]{{2, 1}, {1, 1}, {1, 1}};
            var source = planes(26, 14, factors, 8, 11);
            var jpeg = Lj92Encoder.encodeFrame(source, 26, 14, factors, 8, true,
                    List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 2, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{1, 2}, 3, 0, 0)));
            var sof = indexOf(jpeg, 0xCB);
            var secondScan = -1;
            for (var i = jpeg.length - 3; i > sof; i--) {
                if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xDA) {
                    secondScan = i;
                    break;
                }
            }
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, secondScan);
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDC, 0, 4, 0, 14});
            out.write(jpeg, secondScan, jpeg.length - secondScan);
            var patched = LosslessSubsampledTest.withJfif(out.toByteArray());
            var at = indexOf(patched, 0xCB);
            patched[at + 5] = 0;
            patched[at + 6] = 0;
            var picture = decode(patched, ImageDecoderOptions.defaults()).picture();
            assertEquals(14, picture.height());
            var scan = new JpegReader(patched, 0, patched.length, MediaLimits.DEFAULT).openLossless();
            assertEquals(14, scan.height());
            var decoded = decodeComponents(scan);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(source[c], decoded[c], "component " + c);
            }
        }
    }

    @Nested
    @DisplayName("restart intervals that split rows")
    class PartialRows {
        @ParameterizedTest(name = "restart every {0} MCUs")
        @ValueSource(ints = {1, 2, 5, 16, 33, 45})
        @DisplayName("decode the same through the streaming and the component paths, Huffman and arithmetic")
        void interleaved(int restart) {
            var factors = ones(3);
            var source = planes(33, 13, factors, 10, restart);
            for (var predictor = 1; predictor <= 7; predictor++) {
                for (var arithmetic : new boolean[]{false, true}) {
                    var jpeg = Lj92Encoder.encodeFrame(source, 33, 13, factors, 10, arithmetic,
                            List.of(Lj92Encoder.ScanSpec.of(range(3), predictor, 0, restart)));
                    var decoded = decodeComponents(jpeg);
                    for (var c = 0; c < 3; c++) {
                        assertArrayEquals(source[c], decoded[c], "component " + c);
                    }
                    var scan = new LosslessJpeg(jpeg, 0, jpeg.length);
                    assertEquals(!arithmetic, scan.streamsRows());
                    var rows = new int[13][];
                    scan.decode((row, samples) -> rows[row] = samples.clone());
                    for (var i = 0; i < 33 * 13; i++) {
                        for (var c = 0; c < 3; c++) {
                            assertEquals(source[c][i], rows[i / 33][3 * (i % 33) + c]);
                        }
                    }
                }
            }
            // the streaming path agrees with the encoder of the 1x1 frames
            var samples = LosslessJpegTest.noise(33 * 13 * 3, 10, restart);
            var legacy = Lj92Encoder.encode(samples, 33, 13, 3, 10, 5, restart, 0);
            assertArrayEquals(samples, LosslessJpegTest.decode(legacy));
        }

        @ParameterizedTest(name = "restart every {0} MCUs")
        @ValueSource(ints = {1, 4, 7, 9, 13})
        @DisplayName("decode subsampled and non-interleaved scans exactly")
        void subsampled(int restart) {
            var factors = new int[][]{{2, 2}, {1, 2}, {3, 1}};
            var source = planes(31, 22, factors, 8, restart);
            for (var arithmetic : new boolean[]{false, true}) {
                for (var predictor : new int[]{1, 4, 7}) {
                    var jpeg = Lj92Encoder.encodeFrame(source, 31, 22, factors, 8, arithmetic,
                            List.of(Lj92Encoder.ScanSpec.of(new int[]{0, 1}, predictor, 0, restart),
                                    Lj92Encoder.ScanSpec.of(new int[]{2}, predictor, 0, restart * 3)));
                    var decoded = decodeComponents(jpeg);
                    for (var c = 0; c < 3; c++) {
                        assertArrayEquals(source[c], decoded[c], "arithmetic " + arithmetic + ", component " + c);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("frames of two or more than four components")
    class Channels {
        @ParameterizedTest(name = "{0} components")
        @ValueSource(ints = {2, 5, 7})
        @DisplayName("lossless frames decode to raw channels, exactly at 8 bits and rescaled from 12 bits")
        void lossless(int count) {
            var factors = ones(count);
            factors[count - 1] = new int[]{2, 2};
            for (var precision : new int[]{8, 12}) {
                var source = planes(19, 11, factors, precision, count);
                var scans = new ArrayList<Lj92Encoder.ScanSpec>();
                scans.add(Lj92Encoder.ScanSpec.of(range(Math.min(count, 4)), 6, 0, 0));
                if (count > 4) {
                    scans.add(Lj92Encoder.ScanSpec.of(Arrays.copyOfRange(range(count), 4, count), 2, 0, 0));
                }
                var jpeg = Lj92Encoder.encodeFrame(source, 19, 11, factors, precision, false, scans);
                var max = (1 << precision) - 1;
                var narrow = assertInstanceOf(Channel8Picture.class, decode(jpeg, KEEP).picture());
                assertEquals(JpegOutput.rawLayout(count), narrow.layout());
                var wide = decode(jpeg, WIDE_KEEP).picture();
                // without keepChannels, the first channel as gray
                var gray = assertInstanceOf(RgbaPicture.class, decode(jpeg, ImageDecoderOptions.defaults()).picture());
                assertArrayEquals(((RgbaPicture) ImageOps.toRgba(narrow)).pixels(), gray.pixels());
                if (precision > 8) {
                    assertInstanceOf(com.github.auties00.smalt.image.Rgba16Picture.class, decode(jpeg, WIDE).picture());
                }
                if (precision == 8) {
                    assertInstanceOf(Channel8Picture.class, wide);
                } else {
                    assertInstanceOf(Channel16Picture.class, wide);
                }
                for (var y = 0; y < 11; y++) {
                    for (var x = 0; x < 19; x++) {
                        for (var c = 0; c < count; c++) {
                            var value = replicated(source[c], 19, 11, factors, c, x, y);
                            assertEquals(JpegLosslessTest.scale(value, max, 255),
                                    narrow.pixels()[y * narrow.stride() + count * x + c] & 0xFF);
                            if (wide instanceof Channel16Picture sixteen) {
                                assertEquals(JpegLosslessTest.scale(value, max, 65535),
                                        sixteen.pixels()[y * sixteen.stride() + count * x + c] & 0xFFFF);
                            }
                        }
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0} components")
        @ValueSource(ints = {2, 5, 6})
        @DisplayName("DCT frames decode to raw channels holding the decoded component planes")
        void dct(int count) {
            var ids = new int[count];
            var h = new int[count];
            var v = new int[count];
            var source = new byte[count][];
            for (var c = 0; c < count; c++) {
                ids[c] = c + 1;
                h[c] = c == 0 ? 2 : 1;
                v[c] = c == 0 ? 2 : 1;
                source[c] = JpegStreamTest.plane(c == 0 ? 32 : 16, c == 0 ? 24 : 12, c);
            }
            var writer = TestJpegWriter.fromPlanes(32, 24, ids, h, v, source, 90);
            var scans = new ArrayList<int[]>();
            for (var c = 0; c < count; c++) {
                scans.add(new int[]{c});
            }
            var jpeg = writer.sequential(scans.toArray(int[][]::new));
            var picture = assertInstanceOf(Channel8Picture.class, decode(jpeg, KEEP).picture());
            assertEquals(JpegOutput.rawLayout(count), picture.layout());
            var planes = JpegStreamTest.planes(jpeg);
            for (var y = 0; y < 24; y++) {
                for (var x = 0; x < 32; x++) {
                    assertEquals(planes.plane(0)[y * planes.stride(0) + x], picture.pixels()[y * picture.stride() + count * x]);
                }
            }
            // a channel subsampled 2x2 is upsampled with the triangle filter, as in 4:2:0 Y'CbCr
            var expected = Upsampler.upsample(planes.plane(1), planes.stride(1), 16, 12, 1, 1, 2, 2, 32, 24, true);
            for (var i = 0; i < 32 * 24; i++) {
                assertEquals(expected[i], picture.pixels()[i * count + 1]);
            }
            var rgba = assertInstanceOf(RgbaPicture.class, ImageOps.toRgba(picture));
            assertEquals(picture.pixels()[0], rgba.pixels()[0]);
            var plain = assertInstanceOf(RgbaPicture.class, decode(jpeg, ImageDecoderOptions.defaults()).picture());
            assertArrayEquals(rgba.pixels(), plain.pixels());
            assertEquals("jpeg with " + count + " components", assertThrows(MediaException.Unsupported.class,
                    () -> JpegTransforms.transform(MediaSource.of(jpeg), JpegTransformOptions.defaults())).format());
        }

        @Test
        @DisplayName("12-bit DCT frames of five components keep 16 bits with highBitDepth")
        void twelveBit() {
            var count = 5;
            var ids = new int[count];
            var source = new byte[count][];
            for (var c = 0; c < count; c++) {
                ids[c] = c + 1;
                source[c] = JpegStreamTest.plane(16, 8, c);
            }
            var ones = new int[count];
            Arrays.fill(ones, 1);
            var writer = TestJpegWriter.fromPlanes(16, 8, ids, ones, ones, source, 90);
            writer.precision = 12;
            writer.sofMarker = 0xC1;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3}, new int[]{4});
            var picture = assertInstanceOf(Channel16Picture.class, decode(jpeg, WIDE_KEEP).picture());
            var planes = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeWidePlanes(0);
            for (var c = 0; c < count; c++) {
                for (var i = 0; i < 16 * 8; i++) {
                    var value = planes.widePlane(c)[(i / 16) * planes.stride(c) + i % 16];
                    assertEquals(JpegLosslessTest.scale(value, 4095, 65535), picture.pixels()[i * count + c] & 0xFFFF);
                }
            }
            assertInstanceOf(Channel8Picture.class, decode(jpeg, KEEP).picture());
            assertInstanceOf(com.github.auties00.smalt.image.Rgba16Picture.class, decode(jpeg, WIDE).picture());
        }

        @Test
        @DisplayName("an N-color profile of every component makes the channels its inks and is reported")
        void inkProfile() throws IOException {
            byte[] icc;
            try (var input = LosslessVariantsTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/image/tiff/inks/ink5.icc")) {
                icc = input.readAllBytes();
            }
            var profile = ColorProfile.parse(icc);
            assertEquals(5, profile.channels());
            var factors = ones(5);
            var source = planes(9, 7, factors, 8, 1);
            var jpeg = withIcc(Lj92Encoder.encodeFrame(source, 9, 7, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(range(4), 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{4}, 1, 0, 0))),
                    icc);
            var image = decode(jpeg, KEEP);
            var picture = assertInstanceOf(Channel8Picture.class, image.picture());
            assertEquals(ColorModel.INK, picture.layout().colorModel());
            assertEquals(5, picture.layout().colorChannels());
            assertEquals(profile.channels(), image.colorProfile().orElseThrow().channels());
            for (var i = 0; i < 9 * 7; i++) {
                for (var c = 0; c < 5; c++) {
                    assertEquals(source[c][i], picture.pixels()[i * 5 + c] & 0xFF);
                }
            }
            var converted = decode(jpeg, ImageDecoderOptions.builder().convertToSrgb(true).build());
            assertInstanceOf(RgbaPicture.class, converted.picture());
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            // inks have no RGBA form but through their profile, which the default decode applies
            var plain = decode(jpeg, ImageDecoderOptions.defaults());
            assertArrayEquals(((RgbaPicture) converted.picture()).pixels(), ((RgbaPicture) plain.picture()).pixels());
        }

        @Test
        @DisplayName("an RGB or gray profile names the leading channels and leaves the others unspecified")
        void colorProfiles() {
            var factors = ones(5);
            var source = planes(6, 4, factors, 8, 2);
            var plain = Lj92Encoder.encodeFrame(source, 6, 4, factors, 8, true,
                    List.of(Lj92Encoder.ScanSpec.of(range(4), 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{4}, 1, 0, 0)));
            var rgb = decode(withIcc(plain, ColorProfile.srgb().toByteArray()), KEEP);
            var layout = ((Channel8Picture) rgb.picture()).layout();
            assertEquals(PixelLayout.RGB.withChannel(Channel.of(ChannelType.UNSPECIFIED))
                    .withChannel(Channel.of(ChannelType.UNSPECIFIED)), layout);
            assertTrue(rgb.colorProfile().orElseThrow().isSrgb());
            var gray = decode(withIcc(plain, ColorProfile.grayGamma22().toByteArray()), KEEP);
            assertEquals(JpegOutput.rawLayout(5), ((Channel8Picture) gray.picture()).layout());
            assertEquals(1, gray.colorProfile().orElseThrow().channels());
            var none = decode(plain, KEEP);
            assertTrue(none.colorProfile().isEmpty());
            var two = Lj92Encoder.encodeFrame(Arrays.copyOf(source, 2), 6, 4, ones(2), 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(range(2), 1, 0, 0)));
            var mismatched = decode(withIcc(two, ColorProfile.srgb().toByteArray()), KEEP);
            assertEquals(JpegOutput.rawLayout(2), ((Channel8Picture) mismatched.picture()).layout());
            assertTrue(mismatched.colorProfile().isEmpty());
            var srgb = decode(plain, ImageDecoderOptions.builder().convertToSrgb(true).build());
            var rgba = assertInstanceOf(RgbaPicture.class, srgb.picture());
            assertEquals(source[0][0], rgba.pixels()[0] & 0xFF);
            assertEquals(source[0][0], rgba.pixels()[2] & 0xFF);
        }

        @Test
        @DisplayName("a CMYK profile with an Adobe segment makes the leading four channels inverted inks")
        void cmykProfile() {
            // the sRGB profile relabeled as an output profile of CMYK data: the layout reads only the header
            var cmyk = ColorProfile.srgb().toByteArray();
            System.arraycopy("prtrCMYK".getBytes(StandardCharsets.US_ASCII), 0, cmyk, 12, 8);
            Arrays.fill(cmyk, 84, 100, (byte) 0);
            assertEquals(4, ColorProfile.parse(cmyk).channels());
            var factors = ones(6);
            var source = planes(5, 3, factors, 8, 4);
            var jpeg = withAdobe(withIcc(Lj92Encoder.encodeFrame(source, 5, 3, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(range(4), 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{4, 5}, 1, 0, 0))),
                    cmyk));
            var image = decode(jpeg, KEEP);
            var picture = assertInstanceOf(Channel8Picture.class, image.picture());
            assertEquals(PixelLayout.CMYK.withChannel(Channel.of(ChannelType.UNSPECIFIED))
                    .withChannel(Channel.of(ChannelType.UNSPECIFIED)), picture.layout());
            for (var i = 0; i < 15; i++) {
                for (var c = 0; c < 6; c++) {
                    var stored = source[c][i];
                    assertEquals(c < 4 ? 255 - stored : stored, picture.pixels()[i * 6 + c] & 0xFF);
                }
            }
            assertEquals(4, image.colorProfile().orElseThrow().channels());
            var plain = decode(jpeg, ImageDecoderOptions.defaults());
            assertArrayEquals(((RgbaPicture) ImageOps.toRgba(picture)).pixels(), ((RgbaPicture) plain.picture()).pixels());
            assertTrue(plain.colorProfile().isEmpty());
        }

        @Test
        @DisplayName("inks whose profile does not convert to sRGB are kept as channels and otherwise rejected")
        void unconvertibleInks() {
            // the sRGB profile relabeled as a device link from five colorants, a class that converts nothing to sRGB
            var inks = ColorProfile.srgb().toByteArray();
            System.arraycopy("link5CLR".getBytes(StandardCharsets.US_ASCII), 0, inks, 12, 8);
            Arrays.fill(inks, 84, 100, (byte) 0);
            assertEquals(5, ColorProfile.parse(inks).channels());
            var factors = ones(5);
            var source = planes(7, 5, factors, 8, 6);
            var jpeg = withIcc(Lj92Encoder.encodeFrame(source, 7, 5, factors, 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(range(4), 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{4}, 1, 0, 0))),
                    inks);
            var picture = assertInstanceOf(Channel8Picture.class, decode(jpeg, KEEP).picture());
            assertEquals(ColorModel.INK, picture.layout().colorModel());
            for (var options : List.of(ImageDecoderOptions.defaults(), ImageDecoderOptions.builder().convertToSrgb(true)
                    .build(), KEEP.toBuilder().convertToSrgb(true).build())) {
                assertEquals("jpeg ink set", assertThrows(MediaException.Unsupported.class, () -> decode(jpeg, options))
                        .format());
            }
        }

        @Test
        @DisplayName("Motion-JPEG decodes arithmetic multi-scan lossless frames and refuses five components")
        void motionJpeg() {
            var factors = new int[][]{{2, 1}, {1, 1}, {1, 1}};
            var source = planes(26, 14, factors, 8, 21);
            var jpeg = LosslessSubsampledTest.withJfif(Lj92Encoder.encodeFrame(source, 26, 14, factors, 8, true,
                    List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 2, 0, 3),
                            Lj92Encoder.ScanSpec.of(new int[]{2, 1}, 5, 0, 0))));
            var frame = assertInstanceOf(YuvPicture.class, new MjpegDecoder().decodeFrame(jpeg, 0, jpeg.length));
            assertEquals(ChromaFormat.YUV422, frame.chromaFormat());
            for (var c = 0; c < 3; c++) {
                var plane = c == 0 ? frame.y() : c == 1 ? frame.u() : frame.v();
                var stride = c == 0 ? frame.yStride() : frame.uvStride();
                var columns = Lj92Encoder.componentWidth(26, factors, c);
                for (var y = 0; y < 14; y++) {
                    for (var x = 0; x < columns; x++) {
                        assertEquals(source[c][y * columns + x], plane[y * stride + x] & 0xFF,
                                "frame " + x + "," + y + " component " + c);
                    }
                }
            }
            var five = Lj92Encoder.encodeFrame(planes(6, 4, ones(5), 8, 1), 6, 4, ones(5), 8, false,
                    List.of(Lj92Encoder.ScanSpec.of(range(4), 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{4}, 1, 0, 0)));
            assertEquals("jpeg with 5 components", assertThrows(MediaException.Unsupported.class,
                    () -> new MjpegDecoder().decodeFrame(five, 0, five.length)).format());
        }
    }

    @Nested
    @DisplayName("against libjpeg-turbo and ffmpeg")
    class Oracles {
        private static final Duration TIMEOUT = Duration.ofSeconds(60);

        // A PNM file: P5 for one component, P6 for three, two big-endian bytes per sample above a maxval of 255.
        static byte[] pnm(int[][] planes, int width, int height, int precision) {
            var out = new ByteArrayOutputStream();
            var header = (planes.length == 1 ? "P5" : "P6") + "\n" + width + " " + height + "\n" + ((1 << precision) - 1)
                    + "\n";
            out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
            for (var i = 0; i < width * height; i++) {
                for (var plane : planes) {
                    if (precision > 8) {
                        out.write(plane[i] >> 8);
                    }
                    out.write(plane[i]);
                }
            }
            return out.toByteArray();
        }

        // The samples of a PNM file, interleaved, after checking its size.
        static int[] samples(byte[] pnm, int width, int height, int components) {
            var at = 0;
            var fields = new String[4];
            for (var field = 0; field < 4; field++) {
                while (Character.isWhitespace(pnm[at])) {
                    at++;
                }
                var start = at;
                while (!Character.isWhitespace(pnm[at])) {
                    at++;
                }
                fields[field] = new String(pnm, start, at - start, StandardCharsets.US_ASCII);
            }
            at++;
            assertEquals(components == 1 ? "P5" : "P6", fields[0]);
            assertEquals(width, Integer.parseInt(fields[1]));
            assertEquals(height, Integer.parseInt(fields[2]));
            var wide = Integer.parseInt(fields[3]) > 255;
            var out = new int[width * height * components];
            for (var i = 0; i < out.length; i++) {
                out[i] = wide ? ((pnm[at] & 0xFF) << 8) | (pnm[at + 1] & 0xFF) : pnm[at] & 0xFF;
                at += wide ? 2 : 1;
            }
            return out;
        }

        static Stream<Arguments> cjpegSettings() {
            var arguments = Stream.<Arguments>builder();
            var scripts = new String[]{null, "0: %1$d 0 0 %2$d;\n1: %1$d 0 0 %2$d;\n2: %1$d 0 0 %2$d;\n",
                    "0,1: %1$d 0 0 %2$d;\n2: %3$d 0 0 0;\n", "2: %3$d 0 0 0;\n0: %1$d 0 0 %2$d;\n1: %3$d 0 0 %2$d;\n"};
            var index = 0;
            for (var precision : new int[]{8, 12, 16}) {
                for (var predictor = 1; predictor <= 7; predictor++) {
                    var transform = predictor % 3 == 0 ? 2 : 0;
                    var restart = predictor % 4;
                    arguments.add(Arguments.of(1, precision, predictor, transform, restart, null));
                    arguments.add(Arguments.of(3, precision, predictor, transform, restart, scripts[index++ % 4]));
                }
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0} components, {1} bits, psv {2}, Pt {3}, restart {4} rows, script {5}")
        @MethodSource("cjpegSettings")
        @DisplayName("cjpeg -lossless files, with scan scripts, decode to the samples djpeg decodes")
        void cjpeg(int components, int precision, int predictor, int transform, int restart, String script)
                throws IOException {
            Oracle.assume(Oracle.Tool.CJPEG);
            Oracle.assume(Oracle.Tool.DJPEG);
            var width = 45;
            var height = 31;
            var source = planes(width, height, ones(components), precision, predictor * 7L + components);
            var arguments = new ArrayList<>(List.of("-precision", Integer.toString(precision), "-lossless",
                    predictor + "," + transform));
            if (restart > 0) {
                arguments.addAll(List.of("-restart", Integer.toString(restart)));
            }
            if (script != null) {
                var file = Scratch.directory("lossless-variants").resolve("scans-" + predictor + "-" + precision + ".txt");
                Scratch.share(file, String.format(script, predictor, transform, 8 - predictor)
                        .getBytes(StandardCharsets.US_ASCII));
                arguments.addAll(List.of("-scans", file.toAbsolutePath().toString()));
            }
            var jpeg = Oracle.run(Oracle.Tool.CJPEG, TIMEOUT, pnm(source, width, height, precision), arguments);
            var expected = samples(Oracle.run(Oracle.Tool.DJPEG, TIMEOUT, jpeg, List.of("-pnm")), width, height,
                    components);
            var decoded = decodeComponents(jpeg);
            for (var i = 0; i < width * height; i++) {
                for (var c = 0; c < components; c++) {
                    assertEquals(expected[i * components + c], decoded[c][i], "sample " + i + " of component " + c);
                }
            }
            var picture = decode(jpeg, precision > 8 ? WIDE : ImageDecoderOptions.defaults()).picture();
            assertArrayEquals(expected, JpegLosslessTest.samples(picture, components, precision));
        }

        static Stream<Arguments> encoderSettings() {
            var arguments = Stream.<Arguments>builder();
            var layouts = new int[][][]{{{2, 2}, {1, 1}, {1, 1}}, {{2, 1}, {1, 1}, {1, 1}}, {{1, 2}, {1, 1}, {1, 1}},
                    {{4, 1}, {1, 1}, {1, 1}}, {{1, 1}, {2, 2}, {1, 1}}, {{2, 2}, {2, 1}, {1, 2}}, {{3, 1}, {1, 1}, {1, 1}}};
            var groupings = List.of(List.of(new int[]{0, 1, 2}), List.of(new int[]{0}, new int[]{1}, new int[]{2}),
                    List.of(new int[]{1, 2}, new int[]{0}));
            var index = 0;
            for (var factors : layouts) {
                for (var grouping : groupings) {
                    var predictor = 1 + index % 7;
                    arguments.add(Arguments.of(Arrays.deepToString(factors), factors, grouping, predictor, index % 3,
                            index % 2 == 0 ? 8 : 12, index % 4 == 1));
                    index++;
                }
            }
            return arguments.build();
        }

        @ParameterizedTest(name = "{0}, scans {2}, psv {3}, Pt {4}, {5} bits, restarts {6}")
        @MethodSource("encoderSettings")
        @DisplayName("subsampled and multi-scan streams of the test encoder decode as djpeg replicates them")
        void djpeg(String name, int[][] factors, List<int[]> grouping, int predictor, int transform, int precision,
                   boolean restarts) {
            Oracle.assume(Oracle.Tool.DJPEG);
            var width = 37;
            var height = 26;
            var source = masked(planes(width, height, factors, precision, name.hashCode()), transform);
            var hMax = Arrays.stream(factors).mapToInt(f -> f[0]).max().orElseThrow();
            var scans = new ArrayList<Lj92Encoder.ScanSpec>();
            for (var group : grouping) {
                // T.81 lossless restart intervals are whole rows of MCUs, here two
                var mcuRow = group.length > 1 ? (width + hMax - 1) / hMax
                        : Lj92Encoder.componentWidth(width, factors, group[0]);
                scans.add(Lj92Encoder.ScanSpec.of(group, predictor, transform, restarts ? 2 * mcuRow : 0));
            }
            var jpeg = Lj92Encoder.encodeFrame(source, width, height, factors, precision, false, scans);
            var expected = samples(Oracle.run(Oracle.Tool.DJPEG, TIMEOUT, jpeg, List.of("-pnm")), width, height, 3);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(replicated(source[c], width, height, factors, c, x, y),
                                expected[(y * width + x) * 3 + c], "djpeg pixel " + x + "," + y + " component " + c);
                    }
                }
            }
            var decoded = decodeComponents(jpeg);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(source[c], decoded[c], "component " + c);
            }
            var picture = decode(jpeg, precision > 8 ? WIDE : ImageDecoderOptions.defaults()).picture();
            var full = switch (picture) {
                case Yuv16Picture yuv -> yuv.chromaFormat() == ChromaFormat.YUV444;
                case YuvPicture yuv -> yuv.chromaFormat() == ChromaFormat.YUV444;
                default -> false;
            };
            if (full) {
                assertArrayEquals(expected, JpegLosslessTest.samples(picture, 3, precision));
            }
        }

        @ParameterizedTest(name = "{0}, pred {1}")
        @MethodSource("ffmpegSettings")
        @DisplayName("ffmpeg's ljpeg encoder's subsampled frames decode to the planes it was given")
        void ffmpeg(String pixelFormat, int prediction, int[][] factors) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var width = 38;
            var height = 22;
            var source = planes(width, height, factors, 8, prediction + pixelFormat.length());
            var raw = new ByteArrayOutputStream();
            for (var plane : source) {
                for (var value : plane) {
                    raw.write(value);
                }
            }
            var jpeg = Oracle.run(Oracle.Tool.FFMPEG, TIMEOUT, raw.toByteArray(), List.of("-hide_banner", "-loglevel",
                    "error", "-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", width + "x" + height, "-i", "-",
                    "-frames:v", "1", "-c:v", "ljpeg", "-pred", Integer.toString(prediction), "-f", "image2pipe", "-"));
            var decoded = decodeComponents(jpeg);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(source[c], decoded[c], "component " + c);
            }
        }

        static Stream<Arguments> ffmpegSettings() {
            var arguments = Stream.<Arguments>builder();
            for (var prediction = 1; prediction <= 3; prediction++) {
                arguments.add(Arguments.of("yuvj420p", prediction, new int[][]{{2, 2}, {1, 1}, {1, 1}}));
                arguments.add(Arguments.of("yuvj422p", prediction, new int[][]{{2, 1}, {1, 1}, {1, 1}}));
                arguments.add(Arguments.of("yuvj444p", prediction, ones(3)));
            }
            return arguments.build();
        }
    }

    @Nested
    @DisplayName("committed vectors")
    class Committed {
        static byte[] vector(String name) {
            try (var input = LosslessVariantsTest.class.getResourceAsStream(
                    "/com/github/auties00/smalt/image/jpeg/lossless/variants/" + name)) {
                return input.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Test
        @DisplayName("are the files variants.sha256 pins")
        void pinned() throws java.security.NoSuchAlgorithmException {
            var lines = new String(vector("variants.sha256"), StandardCharsets.US_ASCII).lines()
                    .filter(line -> !line.isBlank()).toList();
            assertEquals(5, lines.size());
            for (var line : lines) {
                var parts = line.split("\\s+");
                var digest = java.security.MessageDigest.getInstance("SHA-256").digest(vector(parts[1]));
                assertEquals(parts[0], java.util.HexFormat.of().formatHex(digest), parts[1]);
            }
        }

        @Test
        @DisplayName("decode to the samples they were written from")
        void samples() {
            var subsampled = new int[][]{{2, 2}, {1, 1}, {1, 1}};
            var decoded = decodeComponents(vector("37x29-ycbcr420-p8-sof11-psv4-r19.jpg"));
            var source = planes(37, 29, subsampled, 8, 1);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(source[c], decoded[c]);
            }
            var horizontal = new int[][]{{2, 1}, {1, 1}, {1, 1}};
            decoded = decodeComponents(vector("37x29-ycbcr422-p12-3scans.jpg"));
            source = planes(37, 29, horizontal, 12, 2);
            assertArrayEquals(masked(source, 1)[0], decoded[0]);
            assertArrayEquals(source[1], decoded[1]);
            assertArrayEquals(source[2], decoded[2]);
            decoded = decodeComponents(vector("23x17-5ch-p8-sof11-2scans.jpg"));
            source = planes(23, 17, ones(5), 8, 3);
            for (var c = 0; c < 5; c++) {
                assertArrayEquals(source[c], decoded[c]);
            }
            decoded = decodeComponents(vector("45x31-rgb-p12-psv5-cjpeg-scans.jpg"));
            source = planes(45, 31, ones(3), 12, 4);
            assertArrayEquals(masked(source, 1)[0], decoded[0]);
            assertArrayEquals(masked(source, 1)[1], decoded[1]);
            assertArrayEquals(source[2], decoded[2]);
            var dct = vector("23x17-5ch-baseline.jpg");
            var planes = JpegStreamTest.planes(dct);
            var picture = assertInstanceOf(Channel8Picture.class, decode(dct, KEEP).picture());
            for (var c = 0; c < 5; c++) {
                for (var i = 0; i < 23 * 17; i++) {
                    assertEquals(planes.plane(c)[(i / 23) * planes.stride(c) + i % 23], picture.pixels()[i * 5 + c]);
                }
            }
        }
    }

    @Test
    @DisplayName("Canon's unit decoder refuses arithmetic and multi-scan frames")
    void canonUnits() {
        var factors = new int[][]{{2, 1}, {1, 1}, {1, 1}};
        var source = planes(8, 2, factors, 12, 1);
        var arithmetic = Lj92Encoder.encodeFrame(source, 8, 2, factors, 12, true,
                List.of(Lj92Encoder.ScanSpec.of(range(3), 1, 0, 0)));
        var multi = Lj92Encoder.encodeFrame(source, 8, 2, factors, 12, false,
                List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 1, 0, 0), Lj92Encoder.ScanSpec.of(new int[]{1, 2}, 1, 0, 0)));
        for (var jpeg : List.of(arithmetic, multi)) {
            var scan = new LosslessJpeg(jpeg, 0, jpeg.length, true);
            assertEquals("jpeg lossless", assertThrows(MediaException.Unsupported.class,
                    () -> scan.decodeUnits((row, samples) -> { })).format());
        }
    }

    @Test
    @DisplayName("differential lossless frames, which belong to hierarchical coding, stay unsupported")
    void differential() {
        var jpeg = Lj92Encoder.encodeFrame(planes(4, 4, ones(1), 8, 1), 4, 4, ones(1), 8, false,
                List.of(Lj92Encoder.ScanSpec.of(new int[]{0}, 1, 0, 0)));
        for (var marker : new int[]{0xC7, 0xCF}) {
            var patched = jpeg.clone();
            patched[MultiScan.indexOf(patched, 0xC3) + 1] = (byte) marker;
            assertEquals("jpeg lossless", assertThrows(MediaException.Unsupported.class,
                    () -> decode(patched, ImageDecoderOptions.defaults())).format());
        }
    }
}
