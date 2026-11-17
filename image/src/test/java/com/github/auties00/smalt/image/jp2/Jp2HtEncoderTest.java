package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the high-throughput (HTJ2K) encoding of {@link Jp2Encoder}: lossless round trips through this decoder,
 * {@code opj_decompress} and OpenJPH's {@code ojph_expand} over the coding options, the JPH file and the
 * {@code CAP} marker, lossy rate control against the ISO/IEC 15444-1 coder, and the decoding of OpenJPH's
 * {@code ojph_compress} output.
 */
@DisplayName("HTJ2K encoder")
class Jp2HtEncoderTest {
    static Jp2EncoderOptions ht(Jp2EncoderOptions options) {
        return options.toBuilder().highThroughput(true).build();
    }

    static Stream<Arguments> variants() {
        var base = ht(Jp2EncoderOptions.defaults());
        var list = new ArrayList<Arguments>();
        list.add(Arguments.of("defaults", base));
        list.add(Arguments.of("codestream", base.toBuilder().format(Jp2Format.CODESTREAM).build()));
        list.add(Arguments.of("0 levels", base.toBuilder().decompositionLevels(0).build()));
        list.add(Arguments.of("8 levels", base.toBuilder().decompositionLevels(8).build()));
        list.add(Arguments.of("tiles 32x24", base.toBuilder().tileSize(32, 24).build()));
        list.add(Arguments.of("code-blocks 4x4", base.toBuilder().codeBlockSize(4, 4).build()));
        list.add(Arguments.of("code-blocks 16x8", base.toBuilder().codeBlockSize(16, 8).build()));
        list.add(Arguments.of("code-blocks 1024x4", base.toBuilder().codeBlockSize(1024, 4).build()));
        list.add(Arguments.of("code-blocks 4x1024", base.toBuilder().codeBlockSize(4, 1024).build()));
        list.add(Arguments.of("code-blocks 32x128", base.toBuilder().codeBlockSize(32, 128).build()));
        list.add(Arguments.of("precincts 32x32", base.toBuilder().precinctSize(32, 32).build()));
        for (var order : Jp2ProgressionOrder.values()) {
            list.add(Arguments.of("order " + order, base.toBuilder().progressionOrder(order).precinctSize(16, 16)
                    .build()));
        }
        list.add(Arguments.of("vertically causal", base.toBuilder()
                .codeBlockStyles(EnumSet.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).build()));
        list.add(Arguments.of("SOP and EPH", base.toBuilder().sopMarkers(true).ephMarkers(true).build()));
        list.add(Arguments.of("no color transform", base.toBuilder().colorTransform(false).build()));
        list.add(Arguments.of("layers 40,10,0", base.toBuilder().compressionRatios(40, 10, 0).build()));
        list.add(Arguments.of("PSNR layers 30,0", base.toBuilder().psnrTargets(30, 0).build()));
        return list.stream();
    }

    @Nested
    @DisplayName("lossless round trips")
    class Lossless {
        @ParameterizedTest(name = "{0}x{1} alpha={2}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2EncoderTest#sizes")
        @DisplayName("decode to the same pixels")
        void same(int width, int height, boolean alpha) {
            var picture = Jp2EncoderTest.picture(width, height, alpha, width * 31L + height);
            var encoded = Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.lossless()));
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels());
            assertEquals(alpha, decoded.hasAlpha());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtEncoderTest#variants")
        @DisplayName("decode to the same pixels under every coding option")
        void options(String name, Jp2EncoderOptions options) {
            var picture = Jp2EncoderTest.picture(97, 71, true, 41);
            var encoded = Jp2Encoder.encode(picture, options);
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels(), name);
        }

        @Test
        @DisplayName("16-bit pictures keep every bit")
        void sixteenBit() {
            var pixels = new short[64 * 48 * 4];
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = (short) (i * 2654435761L >>> 16);
            }
            var picture = Rgba16Picture.of(64, 48, 64 * 4, pixels, true);
            var decoded = Jp2Decoder.decode(MediaSource.of(Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.lossless()))),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertArrayEquals(pixels, ((Rgba16Picture) decoded).pixels());
        }

        @ParameterizedTest(name = "{0} bits signed={1}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtEncoderTest#precisions")
        @DisplayName("channels of any precision, signed or subsampled, keep every sample")
        void channels(int precision, boolean signed) {
            var image = image(precision, signed, 45, 38);
            var encoded = Jp2Encoder.encode(image, ht(Jp2EncoderOptions.lossless()));
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            assertEquals(image.channels().size(), decoded.channels().size());
            for (var c = 0; c < image.channels().size(); c++) {
                assertArrayEquals(image.channels().get(c).samples(), decoded.channels().get(c).samples(),
                        "channel " + c);
            }
        }
    }

    @Nested
    @DisplayName("deep channels")
    class Deep {
        @ParameterizedTest(name = "{0} bits signed={1} levels={2}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtEncoderTest#extremes")
        @DisplayName("channels alternating between their extreme values keep every sample")
        void extremes(int precision, boolean signed, int levels) {
            var width = 37;
            var height = 29;
            var samples = new int[width * height];
            var min = signed ? -(1L << (precision - 1)) : 0;
            var max = signed ? (1L << (precision - 1)) - 1 : (1L << precision) - 1;
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (int) ((i + i / width) % 2 == 0 ? min : max);
            }
            var image = new Jp2Image(0, 0, width, height, width, height, 0, List.of(new Jp2Channel(0, 0, width, height,
                    1, 1, precision, signed, Jp2ChannelType.UNSPECIFIED, -1, samples)), Jp2ColorSpace.UNSPECIFIED,
                    Optional.empty(), Optional.empty(), Optional.empty());
            var options = ht(Jp2EncoderOptions.lossless().toBuilder().decompositionLevels(levels).build());
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(image, options)));
            assertArrayEquals(samples, decoded.channels().getFirst().samples());
        }

        @ParameterizedTest(name = "{0} bits levels={1}")
        @org.junit.jupiter.params.provider.CsvSource({"28, 5", "31, 5", "30, 0", "31, 0"})
        @DisplayName("channels needing more than 30 magnitude bit-planes are rejected before anything is coded")
        void tooDeep(int precision, int levels) {
            var image = image(precision, false, 20, 12);
            var options = ht(Jp2EncoderOptions.lossless().toBuilder().decompositionLevels(levels).build());
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(image, options));
        }
    }

    static Stream<Arguments> extremes() {
        return Stream.of(Arguments.of(24, true, 5), Arguments.of(25, false, 5), Arguments.of(26, true, 5),
                Arguments.of(27, false, 5), Arguments.of(27, true, 5), Arguments.of(29, false, 0),
                Arguments.of(29, true, 0), Arguments.of(28, true, 0));
    }

    static Stream<Arguments> precisions() {
        return Stream.of(Arguments.of(1, false), Arguments.of(4, true), Arguments.of(8, false), Arguments.of(12, true),
                Arguments.of(16, false), Arguments.of(16, true), Arguments.of(20, false), Arguments.of(24, true),
                Arguments.of(25, false), Arguments.of(26, true), Arguments.of(27, false), Arguments.of(27, true));
    }

    /** Builds an image of three channels, the last two subsampled, with noise over a ramp. */
    static Jp2Image image(int precision, boolean signed, int width, int height) {
        var channels = new ArrayList<Jp2Channel>();
        var state = (long) precision * 1000003 + (signed ? 7 : 0);
        for (var c = 0; c < 3; c++) {
            var dx = c == 0 ? 1 : 2;
            var dy = c == 2 ? 2 : 1;
            var w = Math.ceilDiv(width, dx);
            var h = Math.ceilDiv(height, dy);
            var samples = new int[w * h];
            var min = signed ? -(1L << (precision - 1)) : 0;
            var range = 1L << precision;
            for (var i = 0; i < samples.length; i++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                var ramp = (long) ((double) (i % w) / w * range * 0.8);
                var noise = (state >>> 40) % Math.max(1, range / 8);
                samples[i] = (int) (min + Math.min(range - 1, ramp + noise));
            }
            channels.add(new Jp2Channel(0, 0, w, h, dx, dy, precision, signed, Jp2ChannelType.UNSPECIFIED, -1,
                    samples));
        }
        return new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.UNSPECIFIED,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    @Nested
    @DisplayName("file and codestream syntax")
    class Syntax {
        @Test
        @DisplayName("a JP2-form file is a JPH file whose codestream declares ISO/IEC 15444-15")
        void jph() {
            var encoded = Jp2Encoder.encode(Jp2EncoderTest.picture(40, 30, false, 3), ht(Jp2EncoderOptions.lossless()));
            assertEquals("ftyp", new String(encoded, 16, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            assertEquals("jph ", new String(encoded, 20, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            assertEquals("jph ", new String(encoded, 28, 4, java.nio.charset.StandardCharsets.ISO_8859_1));
            var codestream = Jp2HtOracleTest.contiguousCodestream(encoded);
            assertEquals(0x4000, u16(codestream, 6), "Rsiz");
            var cap = 4 + u16(codestream, 4);
            assertEquals(0xFF50, u16(codestream, cap), "CAP marker");
            assertEquals(0x00020000, (u16(codestream, cap + 4) << 16) | u16(codestream, cap + 6), "Pcap");
            var ccap = u16(codestream, cap + 8);
            assertEquals(0, ccap & 0xFFE0, "Ccap15 sets beyond the magnitude bound of a reversible codestream");
            var cod = cap + 2 + u16(codestream, cap + 2);
            assertEquals(0xFF52, u16(codestream, cod), "COD marker");
            assertEquals(0x40, codestream[cod + 12] & 0xFF, "code-block style");
        }

        @Test
        @DisplayName("an irreversible codestream sets bit 5 of Ccap15 and the causal style keeps bit 3")
        void irreversible() {
            var options = ht(Jp2EncoderOptions.lossy(10)).toBuilder().format(Jp2Format.CODESTREAM)
                    .codeBlockStyles(Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).build();
            var codestream = Jp2Encoder.encode(Jp2EncoderTest.picture(40, 30, false, 3), options);
            var cap = 4 + u16(codestream, 4);
            assertEquals(0x20, u16(codestream, cap + 8) & 0xFFE0);
            var cod = cap + 2 + u16(codestream, cap + 2);
            assertEquals(0x48, codestream[cod + 12] & 0xFF);
        }

        @Test
        @DisplayName("styles other than vertically causal contexts are rejected")
        void styles() {
            for (var style : Jp2CodeBlockStyle.values()) {
                var builder = Jp2EncoderOptions.builder().highThroughput(true).codeBlockStyles(Set.of(style));
                if (style == Jp2CodeBlockStyle.VERTICALLY_CAUSAL) {
                    assertTrue(builder.build().highThroughput());
                } else {
                    assertThrows(IllegalArgumentException.class, builder::build, style.toString());
                }
            }
            var options = ht(Jp2EncoderOptions.defaults());
            assertNotEquals(Jp2EncoderOptions.defaults(), options);
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertTrue(options.toString().contains("highThroughput=true"));
        }

        @Test
        @DisplayName("the CAP segment may be absent, a CPF segment is skipped, and a truncated CAP is malformed")
        void capabilities() {
            var codestream = Jp2Encoder.encode(Jp2EncoderTest.picture(40, 30, false, 3),
                    ht(Jp2EncoderOptions.lossless()).toBuilder().format(Jp2Format.CODESTREAM).build());
            var expected = Jp2Decoder.decodeChannels(MediaSource.of(codestream));
            var cap = 4 + u16(codestream, 4);
            var capEnd = cap + 2 + u16(codestream, cap + 2);
            var withoutCap = new byte[codestream.length - (capEnd - cap)];
            System.arraycopy(codestream, 0, withoutCap, 0, cap);
            System.arraycopy(codestream, capEnd, withoutCap, cap, codestream.length - capEnd);
            assertSame(expected, Jp2Decoder.decodeChannels(MediaSource.of(withoutCap)));
            // CPF (ISO/IEC 15444-15 A.6) with one Pcpf field
            byte[] cpf = {(byte) 0xFF, 0x59, 0, 4, 0, 1};
            var withCpf = new byte[codestream.length + cpf.length];
            System.arraycopy(codestream, 0, withCpf, 0, capEnd);
            System.arraycopy(cpf, 0, withCpf, capEnd, cpf.length);
            System.arraycopy(codestream, capEnd, withCpf, capEnd + cpf.length, codestream.length - capEnd);
            assertSame(expected, Jp2Decoder.decodeChannels(MediaSource.of(withCpf)));
            // Lcap of 6 leaves no room for the Ccap15 field its Pcap declares
            var truncated = new byte[codestream.length - 2];
            System.arraycopy(codestream, 0, truncated, 0, capEnd - 2);
            System.arraycopy(codestream, capEnd, truncated, capEnd - 2, codestream.length - capEnd);
            truncated[cap + 3] = 6;
            assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                    () -> Jp2Decoder.decodeChannels(MediaSource.of(truncated)));
        }

        private static void assertSame(Jp2Image expected, Jp2Image actual) {
            assertEquals(expected.channels().size(), actual.channels().size());
            for (var c = 0; c < expected.channels().size(); c++) {
                assertArrayEquals(expected.channels().get(c).samples(), actual.channels().get(c).samples());
            }
        }

        private static int u16(byte[] data, int position) {
            return ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
        }
    }

    @Nested
    @DisplayName("parameterized color specifications of JPH files")
    class ParameterizedColor {
        static byte[] parameterized(int primaries, int transfer, int matrix, boolean fullRange) {
            return new byte[]{5, 0, 0, (byte) (primaries >> 8), (byte) primaries, (byte) (transfer >> 8),
                    (byte) transfer, (byte) (matrix >> 8), (byte) matrix, (byte) (fullRange ? 0x80 : 0)};
        }

        static byte[] enumerated(int space) {
            return new byte[]{1, 0, 0, 0, 0, 0, (byte) space};
        }

        /** Rebuilds a JPH file of Smalt's layout with its color specification boxes replaced. */
        static byte[] withColors(byte[] file, byte[]... colors) {
            var out = new java.io.ByteArrayOutputStream();
            var position = 0;
            while (position < file.length) {
                var length = (int) u32(file, position);
                var type = new String(file, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (!type.equals("jp2h")) {
                    out.write(file, position, length);
                    position += length;
                    continue;
                }
                var header = new java.io.ByteArrayOutputStream();
                for (var inner = position + 8; inner < position + length; ) {
                    var size = (int) u32(file, inner);
                    var innerType = new String(file, inner + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (innerType.equals("ihdr")) {
                        header.write(file, inner, size);
                        for (var color : colors) {
                            box(header, "colr", color);
                        }
                    } else if (!innerType.equals("colr")) {
                        header.write(file, inner, size);
                    }
                    inner += size;
                }
                box(out, "jp2h", header.toByteArray());
                position += length;
            }
            return out.toByteArray();
        }

        static void box(java.io.ByteArrayOutputStream out, String type, byte[] content) {
            var size = content.length + 8;
            out.write(size >>> 24);
            out.write(size >>> 16);
            out.write(size >>> 8);
            out.write(size);
            out.writeBytes(type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            out.writeBytes(content);
        }

        static long u32(byte[] data, int position) {
            return ((data[position] & 0xFFL) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
        }

        static byte[] file() {
            return Jp2Encoder.encode(Jp2EncoderTest.picture(40, 30, false, 3), ht(Jp2EncoderOptions.lossless()));
        }

        @Test
        @DisplayName("full-range sRGB code points read as the enumerated sRGB space")
        void srgb() {
            var image = Jp2Decoder.decodeChannels(MediaSource.of(withColors(file(), parameterized(1, 13, 0, true))));
            assertEquals(Jp2ColorSpace.SRGB, image.colorSpace());
            assertTrue(image.iccProfile().isEmpty());
        }

        @Test
        @DisplayName("other full-range R'G'B' code points read as the ICC profile they describe")
        void profile() {
            var file = withColors(file(), parameterized(9, 16, 0, true));
            var image = Jp2Decoder.decodeChannels(MediaSource.of(file));
            assertEquals(Jp2ColorSpace.ICC, image.colorSpace());
            var expected = com.github.auties00.smalt.image.color.ColorProfile.of(new com.github.auties00.smalt.image
                    .ColorInfo(com.github.auties00.smalt.image.MatrixCoefficients.IDENTITY,
                    com.github.auties00.smalt.image.ColorRange.FULL, com.github.auties00.smalt.image.ColorPrimaries.BT2020,
                    com.github.auties00.smalt.image.TransferCharacteristics.PQ));
            assertArrayEquals(expected.toByteArray(), image.iccProfile().orElseThrow());
            var picture = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(file)).picture();
            assertEquals(40, picture.width());
        }

        @ParameterizedTest(name = "primaries {0} transfer {1} matrix {2} full {3}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2HtEncoderTest$ParameterizedColor#ignored")
        @DisplayName("a specification it cannot apply is skipped for the next one")
        void skipped(int primaries, int transfer, int matrix, boolean fullRange) {
            var image = Jp2Decoder.decodeChannels(MediaSource.of(withColors(file(),
                    parameterized(primaries, transfer, matrix, fullRange), enumerated(17))));
            assertEquals(Jp2ColorSpace.GRAYSCALE, image.colorSpace());
            var alone = Jp2Decoder.decodeChannels(MediaSource.of(withColors(file(),
                    parameterized(primaries, transfer, matrix, fullRange))));
            assertEquals(Jp2ColorSpace.UNSPECIFIED, alone.colorSpace());
            assertTrue(alone.iccProfile().isEmpty());
        }

        static Stream<Arguments> ignored() {
            return Stream.of(Arguments.of(1, 13, 0, false), Arguments.of(1, 13, 1, true), Arguments.of(2, 13, 0, true),
                    Arguments.of(1, 2, 0, true), Arguments.of(200, 13, 0, true), Arguments.of(1, 200, 0, true));
        }

        @Test
        @DisplayName("a specification of a gray image is ignored")
        void gray() {
            var picture = Jp2EncoderTest.grayOf(Jp2EncoderTest.picture(40, 30, false, 3));
            var file = withColors(Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.lossless())),
                    parameterized(1, 13, 0, true));
            assertEquals(Jp2ColorSpace.UNSPECIFIED, Jp2Decoder.decodeChannels(MediaSource.of(file)).colorSpace());
        }

        @Test
        @DisplayName("a truncated specification is malformed")
        void truncated() {
            var file = withColors(file(), Arrays.copyOf(parameterized(1, 13, 0, true), 9));
            assertThrows(com.github.auties00.smalt.MediaException.Malformed.class,
                    () -> Jp2Decoder.decodeChannels(MediaSource.of(file)));
        }
    }

    @Nested
    @DisplayName("lossy coding")
    class Lossy {
        @ParameterizedTest(name = "ratio {0}")
        @ValueSource(doubles = {4, 10, 30, 80})
        @DisplayName("meets the compression ratio and decodes cleanly")
        void ratio(double ratio) {
            var picture = Jp2EncoderTest.picture(256, 192, false, 3);
            var options = Jp2EncoderOptions.lossy(ratio).toBuilder().format(Jp2Format.CODESTREAM).build();
            var encoded = Jp2Encoder.encode(picture, ht(options));
            var raw = 256 * 192 * 3;
            assertTrue(encoded.length <= raw / ratio * 1.02 + 200, "size " + encoded.length);
            assertTrue(encoded.length >= raw / ratio * 0.9, "size " + encoded.length);
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            var psnr = Jp2EncoderTest.psnr(picture, decoded);
            assertTrue(psnr > (ratio <= 10 ? 38 : 29), "PSNR " + psnr);
        }

        @ParameterizedTest(name = "{0} dB")
        @ValueSource(doubles = {30, 36, 42})
        @DisplayName("reaches the PSNR target")
        void psnrTarget(double target) {
            var picture = Jp2EncoderTest.picture(200, 150, false, 11);
            var encoded = Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.ofPsnr(target)));
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            var psnr = Jp2EncoderTest.psnr(picture, decoded);
            assertTrue(psnr > target - 1.5, "PSNR " + psnr);
        }

        @Test
        @DisplayName("quality layers refine the image progressively")
        void layers() {
            var picture = Jp2EncoderTest.picture(160, 120, false, 13);
            var encoded = Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.lossy(50).toBuilder()
                    .compressionRatios(50, 20, 8).build()));
            var previous = 0.0;
            for (var layers = 1; layers <= 3; layers++) {
                var image = Jp2Decoder.decodeChannels(MediaSource.of(encoded),
                        Jp2DecoderOptions.builder().qualityLayers(layers).build());
                var error = 0.0;
                for (var c = 0; c < 3; c++) {
                    error += Jp2Testing.mse(Jp2Testing.channel(picture, c), image.channels().get(c).samples());
                }
                var psnr = 10 * Math.log10(255.0 * 255.0 / (error / 3));
                assertTrue(psnr > previous, "layer " + layers + " PSNR " + psnr);
                previous = psnr;
            }
        }

        @Test
        @DisplayName("a reversible lossy layer decodes cleanly and a lossless last layer is exact")
        void reversible() {
            var picture = Jp2EncoderTest.picture(120, 90, false, 17);
            var options = ht(Jp2EncoderOptions.builder().compressionRatios(20).build());
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(Jp2Encoder.encode(picture, options)))
                    .picture();
            assertTrue(Jp2EncoderTest.psnr(picture, decoded) > 28);
            var layered = ht(Jp2EncoderOptions.builder().compressionRatios(20, 0).build());
            var exact = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(Jp2Encoder.encode(picture, layered))).picture();
            assertArrayEquals(picture.pixels(), exact.pixels());
        }
    }

    @Nested
    @DisplayName("OpenJPEG and OpenJPH decode the output")
    class Oracles {
        static Stream<Arguments> codestreams() {
            var base = ht(Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).build());
            var list = new ArrayList<Arguments>();
            list.add(Arguments.of("lossless", base, false));
            list.add(Arguments.of("lossless gray", base, true));
            list.add(Arguments.of("lossless causal 32x32", base.toBuilder().codeBlockSize(32, 32)
                    .codeBlockStyles(Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL)).build(), false));
            list.add(Arguments.of("lossless tiles", base.toBuilder().tileSize(64, 48).build(), false));
            list.add(Arguments.of("lossless RPCL precincts", base.toBuilder()
                    .progressionOrder(Jp2ProgressionOrder.RPCL).precinctSize(32, 32).build(), false));
            list.add(Arguments.of("9-7 ratio 6", base.toBuilder().reversible(false).compressionRatios(6).build(),
                    false));
            list.add(Arguments.of("9-7 ratio 25 gray", base.toBuilder().reversible(false).compressionRatios(25).build(),
                    true));
            list.add(Arguments.of("9-7 PSNR 38", base.toBuilder().reversible(false).psnrTargets(38).build(), false));
            list.add(Arguments.of("5-3 ratio 12", base.toBuilder().compressionRatios(12).build(), false));
            return list.stream();
        }

        // OpenJPH decodes single-layer codestreams only, and OpenJPEG 2.5.3 misreads the codeword lengths of an HT
        // code-block whose refinement passes come in a later packet than its cleanup pass, which a lossy last layer
        // gives; the layers here end lossless or at full precision, so each block is in one packet
        static Stream<Arguments> layered() {
            var base = ht(Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).build());
            var list = new ArrayList<Arguments>();
            list.add(Arguments.of("5-3 layers 40,10,0", base.toBuilder().compressionRatios(40, 10, 0).build(), false));
            list.add(Arguments.of("9-7 layers 80,30,0 causal SOP EPH", base.toBuilder().reversible(false)
                    .compressionRatios(80, 30, 0).codeBlockStyles(Set.of(Jp2CodeBlockStyle.VERTICALLY_CAUSAL))
                    .sopMarkers(true).ephMarkers(true).build(), false));
            list.add(Arguments.of("9-7 PSNR layers 28,34,0 gray", base.toBuilder().reversible(false)
                    .psnrTargets(28, 34, 0).build(), true));
            list.add(Arguments.of("5-3 layers 60,20,0 RLCP precincts", base.toBuilder().compressionRatios(60, 20, 0)
                    .progressionOrder(Jp2ProgressionOrder.RLCP).precinctSize(32, 32).build(), false));
            return Stream.concat(codestreams(), list.stream());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layered")
        @DisplayName("opj_decompress decodes the samples this decoder does")
        void openJpeg(String name, Jp2EncoderOptions options, boolean gray) {
            OpenJpeg.assume("opj_decompress");
            var picture = picture(gray);
            var encoded = Jp2Encoder.encode(picture, options);
            var layers = Math.max(options.compressionRatios().length, options.psnrTargets().length);
            for (var layer = layers > 1 ? 1 : 0; layer <= (layers > 1 ? layers : 0); layer++) {
                var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded), layer == 0 ? Jp2DecoderOptions.defaults()
                        : Jp2DecoderOptions.builder().qualityLayers(layer).build());
                var planes = OpenJpeg.decode(encoded, "j2k", 0, layer);
                var where = name + (layer == 0 ? "" : " layer " + layer);
                assertNotNull(planes, "opj_decompress rejected " + where);
                assertEquals(decoded.channels().size(), planes.length);
                for (var c = 0; c < planes.length; c++) {
                    assertArrayEquals(planes[c].samples(), decoded.channels().get(c).samples(), where + " channel " + c);
                    if (options.isLossless() && (layer == 0 || layer == layers)) {
                        assertArrayEquals(Jp2Testing.channel(picture, c), planes[c].samples(), where + " channel " + c);
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("codestreams")
        @DisplayName("ojph_expand decodes the samples this decoder does")
        void openJph(String name, Jp2EncoderOptions options, boolean gray) {
            OpenJpeg.assume("ojph_expand");
            var picture = picture(gray);
            var encoded = Jp2Encoder.encode(picture, options);
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            var planes = OpenJph.expand(encoded, gray ? 1 : 3);
            assertNotNull(planes, "ojph_expand rejected " + name);
            for (var c = 0; c < planes.length; c++) {
                var expected = decoded.channels().get(c).samples();
                if (options.isReversible()) {
                    assertArrayEquals(expected, planes[c].samples(), name + " channel " + c);
                } else {
                    // OpenJPH's irreversible wavelet rounds its own way
                    assertTrue(Jp2Testing.peak(expected, planes[c].samples()) <= 2, name + " channel " + c);
                }
            }
        }

        static RgbaPicture picture(boolean gray) {
            var picture = Jp2EncoderTest.picture(173, 117, false, 23);
            return gray ? Jp2EncoderTest.grayOf(picture) : picture;
        }

        @ParameterizedTest(name = "ratio {0}")
        @ValueSource(doubles = {6, 10, 30})
        @DisplayName("rate allocation reaches at least the PSNR of uniform quantization at the same size")
        void rateAllocation(double ratio) {
            OpenJpeg.assume("ojph_compress");
            var picture = Jp2EncoderTest.picture(256, 192, false, 3);
            var encoded = Jp2Encoder.encode(picture, ht(Jp2EncoderOptions.lossy(ratio).toBuilder()
                    .format(Jp2Format.CODESTREAM).build()));
            var psnr = Jp2EncoderTest.psnr(picture, (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture());
            // OpenJPH quantizes every sub-band with one step size and codes every bit-plane left
            var ppm = Jp2EncoderTest.ppm(picture, false);
            double[] steps = {0.002, 0.004, 0.008, 0.016, 0.03, 0.06, 0.12, 0.24};
            var sizes = new int[steps.length];
            var qualities = new double[steps.length];
            for (var i = 0; i < steps.length; i++) {
                var codestream = OpenJph.compress(ppm, false, "-qstep", String.valueOf(steps[i]));
                sizes[i] = codestream.length;
                qualities[i] = Jp2EncoderTest.psnr(picture,
                        (RgbaPicture) Jp2Decoder.decode(MediaSource.of(codestream)).picture());
            }
            for (var i = 0; i + 1 < steps.length; i++) {
                if (sizes[i] >= encoded.length && sizes[i + 1] <= encoded.length) {
                    var weight = (double) (encoded.length - sizes[i + 1]) / (sizes[i] - sizes[i + 1]);
                    var uniform = qualities[i + 1] + weight * (qualities[i] - qualities[i + 1]);
                    assertTrue(psnr > uniform - 0.5, "PSNR " + psnr + " against " + uniform + " at "
                            + encoded.length + " bytes");
                    return;
                }
            }
            throw new AssertionError(encoded.length + " bytes are outside the sizes of the uniform steps");
        }
    }

    @Nested
    @DisplayName("ojph_compress output")
    class OpenJphOutput {
        static Stream<Arguments> settings() {
            return Stream.of(Arguments.of("reversible", List.of("-reversible", "true"), false),
                    Arguments.of("reversible gray", List.of("-reversible", "true"), true),
                    Arguments.of("reversible 3 levels 32x32", List.of("-reversible", "true", "-num_decomps", "3",
                            "-block_size", "{32,32}"), false),
                    Arguments.of("irreversible qstep 0.01", List.of("-qstep", "0.01"), false),
                    Arguments.of("irreversible qstep 0.05 gray", List.of("-qstep", "0.05"), true),
                    Arguments.of("RPCL precincts", List.of("-reversible", "true", "-prog_order", "RPCL", "-precincts",
                            "{32,32},{64,64}"), false));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("settings")
        @DisplayName("decodes bit-exact with opj_decompress, losslessly when reversible, close to ojph_expand")
        void decodes(String name, List<String> arguments, boolean gray) {
            var picture = Oracles.picture(gray);
            var codestream = OpenJph.compress(Jp2EncoderTest.ppm(picture, gray), gray, arguments.toArray(String[]::new));
            var decoded = Jp2Decoder.decodeChannels(MediaSource.of(codestream));
            var reversible = arguments.contains("-reversible");
            if (reversible) {
                for (var c = 0; c < decoded.channels().size(); c++) {
                    assertArrayEquals(Jp2Testing.channel(picture, c), decoded.channels().get(c).samples(),
                            name + " channel " + c);
                }
            }
            var expanded = OpenJph.expand(codestream, gray ? 1 : 3);
            assertNotNull(expanded, name);
            for (var c = 0; c < expanded.length; c++) {
                var samples = decoded.channels().get(c).samples();
                if (reversible) {
                    assertArrayEquals(expanded[c].samples(), samples, name + " channel " + c);
                } else {
                    assertTrue(Jp2Testing.peak(expanded[c].samples(), samples) <= 2, name + " channel " + c);
                }
            }
            if (OpenJpeg.find("opj_decompress").isPresent()) {
                var planes = OpenJpeg.decode(codestream, "j2k", 0, 0);
                assertNotNull(planes, name);
                for (var c = 0; c < planes.length; c++) {
                    assertArrayEquals(planes[c].samples(), decoded.channels().get(c).samples(), name + " channel " + c);
                }
            }
        }
    }
}
