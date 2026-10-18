package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the JPEG 2000 encoder: lossless round trips through this decoder and
 * {@code opj_decompress}, byte identity with {@code opj_compress} over its coding options, and the
 * size and quality of lossy layers.
 */
@DisplayName("JPEG 2000 encoder")
class Jp2EncoderTest {
    private static final String COMMENT = "Smalt test";

    /** A deterministic photo-like picture: gradients, ripples, edges and noise. */
    static RgbaPicture picture(int width, int height, boolean alpha, long seed) {
        var pixels = new byte[width * height * 4];
        var state = seed;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                state = state * 6364136223846793005L + 1442695040888963407L;
                var noise = (int) ((state >>> 59) & 7) - 3;
                var r = 128 + 100 * Math.sin(x / 13.0) * Math.cos(y / 17.0) + noise;
                var g = (x * 255.0 / Math.max(1, width - 1)) * 0.7 + ((x / 16 + y / 16) % 2 == 0 ? 40 : 0) + noise;
                var b = (y * 255.0 / Math.max(1, height - 1)) * 0.8 + 30 * Math.sin((x + y) / 5.0) + noise;
                var i = 4 * (y * width + x);
                pixels[i] = (byte) Math.clamp((int) r, 0, 255);
                pixels[i + 1] = (byte) Math.clamp((int) g, 0, 255);
                pixels[i + 2] = (byte) Math.clamp((int) b, 0, 255);
                pixels[i + 3] = (byte) (alpha ? (x * 7 + y * 3) & 0xFF : 255);
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    static byte[] ppm(RgbaPicture picture, boolean gray) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(((gray ? "P5\n" : "P6\n") + picture.width() + " " + picture.height() + "\n255\n").getBytes());
        var pixels = picture.pixels();
        for (var i = 0; i < picture.width() * picture.height(); i++) {
            out.write(pixels[4 * i]);
            if (!gray) {
                out.write(pixels[4 * i + 1]);
                out.write(pixels[4 * i + 2]);
            }
        }
        return out.toByteArray();
    }

    static RgbaPicture grayOf(RgbaPicture picture) {
        var pixels = picture.pixels().clone();
        for (var i = 0; i < pixels.length; i += 4) {
            pixels[i + 1] = pixels[i];
            pixels[i + 2] = pixels[i];
        }
        return RgbaPicture.of(picture.width(), picture.height(), picture.width() * 4, pixels, false);
    }

    @Nested
    @DisplayName("lossless round trips")
    class Lossless {
        @ParameterizedTest(name = "{0}x{1} alpha={2}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2EncoderTest#sizes")
        @DisplayName("decode to the same pixels")
        void same(int width, int height, boolean alpha) {
            var picture = picture(width, height, alpha, width * 31L + height);
            var encoded = Jp2Encoder.encode(picture);
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels());
            assertEquals(alpha, decoded.hasAlpha());
        }

        @Test
        @DisplayName("16-bit pictures keep every bit")
        void sixteenBit() {
            var pixels = new short[64 * 48 * 4];
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = (short) (i * 2654435761L >>> 16);
            }
            var picture = Rgba16Picture.of(64, 48, 64 * 4, pixels, true);
            var decoded = Jp2Decoder.decode(MediaSource.of(Jp2Encoder.encode(picture)),
                    com.github.auties00.smalt.image.ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertArrayEquals(pixels, ((Rgba16Picture) decoded).pixels());
        }

        @Test
        @DisplayName("gray pictures are coded as one component")
        void gray() {
            var picture = grayOf(picture(70, 50, false, 7));
            var encoded = Jp2Encoder.encode(picture);
            var image = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
            assertEquals(1, image.channels().size());
            assertEquals(Jp2ColorSpace.GRAYSCALE, image.colorSpace());
            assertArrayEquals(picture.pixels(), ((RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture()).pixels());
        }

        @Test
        @DisplayName("signed 12-bit subsampled channels round trip")
        void channels() {
            var width = 61;
            var height = 37;
            var luma = new int[width * height];
            for (var i = 0; i < luma.length; i++) {
                luma[i] = (int) ((i * 2654435761L) % 4096) - 2048;
            }
            var chroma = new int[31 * 19];
            for (var i = 0; i < chroma.length; i++) {
                chroma[i] = (i * 37) % 4096;
            }
            var image = new Jp2Image(0, 0, width, height, width, height, 0, List.of(
                    new Jp2Channel(0, 0, width, height, 1, 1, 12, true, Jp2ChannelType.COLOR, 1, luma),
                    new Jp2Channel(0, 0, 31, 19, 2, 2, 12, false, Jp2ChannelType.COLOR, 2, chroma)),
                    Jp2ColorSpace.UNSPECIFIED, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty());
            for (var format : Jp2Format.values()) {
                var decoded = Jp2Decoder.decodeChannels(MediaSource.of(
                        Jp2Encoder.encode(image, Jp2EncoderOptions.builder().format(format).build())));
                assertArrayEquals(luma, decoded.channels().get(0).samples());
                assertArrayEquals(chroma, decoded.channels().get(1).samples());
                assertTrue(decoded.channels().get(0).signed());
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2EncoderTest#variants")
        @DisplayName("every coding option decodes losslessly, here and in opj_decompress")
        void variants(String name, Jp2EncoderOptions options) {
            var picture = picture(97, 75, false, 99);
            var encoded = Jp2Encoder.encode(picture, options);
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels(), name);
            if (OpenJpeg.find("opj_decompress").isPresent()) {
                var planes = OpenJpeg.decode(encoded, options.format() == Jp2Format.JP2 ? "jp2" : "j2k", 0, 0);
                assertNotNull(planes, "opj_decompress rejected " + name);
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(Jp2Testing.channel(picture, c), planes[c].samples(), name + " channel " + c);
                }
            }
        }
    }

    static Stream<Arguments> sizes() {
        return Stream.of(Arguments.of(1, 1, false), Arguments.of(1, 7, false), Arguments.of(5, 1, true),
                Arguments.of(17, 3, false), Arguments.of(64, 64, false), Arguments.of(65, 63, true),
                Arguments.of(129, 97, false), Arguments.of(300, 7, true));
    }

    static Stream<Arguments> variants() {
        var base = Jp2EncoderOptions.defaults();
        var list = new ArrayList<Arguments>();
        list.add(Arguments.of("defaults", base));
        list.add(Arguments.of("codestream", base.toBuilder().format(Jp2Format.CODESTREAM).build()));
        list.add(Arguments.of("0 levels", base.toBuilder().decompositionLevels(0).build()));
        list.add(Arguments.of("8 levels", base.toBuilder().decompositionLevels(8).build()));
        list.add(Arguments.of("tiles 32x24", base.toBuilder().tileSize(32, 24).build()));
        list.add(Arguments.of("code-blocks 16x8", base.toBuilder().codeBlockSize(16, 8).build()));
        list.add(Arguments.of("precincts 32x32", base.toBuilder().precinctSize(32, 32).build()));
        for (var order : Jp2ProgressionOrder.values()) {
            list.add(Arguments.of("order " + order, base.toBuilder().progressionOrder(order).precinctSize(16, 16)
                    .build()));
        }
        for (var style : Jp2CodeBlockStyle.values()) {
            list.add(Arguments.of("style " + style, base.toBuilder().codeBlockStyles(EnumSet.of(style)).build()));
        }
        list.add(Arguments.of("all styles", base.toBuilder().codeBlockStyles(EnumSet.allOf(Jp2CodeBlockStyle.class))
                .build()));
        list.add(Arguments.of("SOP and EPH", base.toBuilder().sopMarkers(true).ephMarkers(true).build()));
        list.add(Arguments.of("no color transform", base.toBuilder().colorTransform(false).build()));
        list.add(Arguments.of("layers 40,10,0", base.toBuilder().compressionRatios(40, 10, 0).build()));
        return list.stream();
    }

    @Nested
    @DisplayName("byte identity with opj_compress")
    class Identity {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2EncoderTest#opjVariants")
        @DisplayName("lossless codestreams are byte-identical")
        void identical(String name, List<String> arguments, Jp2EncoderOptions options, boolean gray) {
            OpenJpeg.assume("opj_compress");
            var picture = picture(131, 87, false, 5);
            if (gray) {
                picture = grayOf(picture);
            }
            try {
                var input = OpenJpeg.temp(gray ? "in.pgm" : "in.ppm");
                Files.write(input, ppm(picture, gray));
                var extension = options.format() == Jp2Format.JP2 ? ".jp2" : ".j2k";
                var output = OpenJpeg.temp("out" + extension);
                var command = new ArrayList<>(List.of("-i", input.toString(), "-o", output.toString(), "-C", COMMENT));
                command.addAll(arguments);
                assertNotNull(OpenJpeg.run("opj_compress", command), "opj_compress failed");
                var expected = Files.readAllBytes(output);
                var actual = Jp2Encoder.encode(picture, options.toBuilder().comment(COMMENT).build());
                assertEquals(expected.length, actual.length, "length");
                assertArrayEquals(expected, actual);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    static Stream<Arguments> opjVariants() {
        var base = Jp2EncoderOptions.builder().format(Jp2Format.CODESTREAM).build();
        var list = new ArrayList<Arguments>();
        list.add(Arguments.of("defaults", List.of(), base, false));
        list.add(Arguments.of("gray", List.of(), base, true));
        list.add(Arguments.of("jp2", List.of(), Jp2EncoderOptions.defaults(), false));
        list.add(Arguments.of("jp2 gray", List.of(), Jp2EncoderOptions.defaults(), true));
        list.add(Arguments.of("-n 3", List.of("-n", "3"), base.toBuilder().decompositionLevels(2).build(), false));
        list.add(Arguments.of("-b 16,32", List.of("-b", "16,32"), base.toBuilder().codeBlockSize(16, 32)
                .build(), false));
        list.add(Arguments.of("-t 50,40", List.of("-t", "50,40"), base.toBuilder().tileSize(50, 40).build(), false));
        list.add(Arguments.of("-c [64,64]", List.of("-c", "[64,64]"), base.toBuilder().precinctSize(64, 64)
                .build(), false));
        list.add(Arguments.of("-SOP -EPH", List.of("-SOP", "-EPH"), base.toBuilder().sopMarkers(true).ephMarkers(true)
                .build(), false));
        list.add(Arguments.of("-mct 0", List.of("-mct", "0"), base.toBuilder().colorTransform(false).build(), false));
        for (var order : List.of("RLCP", "RPCL", "PCRL", "CPRL")) {
            list.add(Arguments.of("-p " + order, List.of("-p", order, "-c", "[32,32]"),
                    base.toBuilder().progressionOrder(Jp2ProgressionOrder.valueOf(order)).precinctSize(32, 32)
                            .build(), false));
        }
        var styles = Jp2CodeBlockStyle.values();
        for (var i = 0; i < styles.length; i++) {
            list.add(Arguments.of("-M " + (1 << i), List.of("-M", String.valueOf(1 << i)),
                    base.toBuilder().codeBlockStyles(Set.of(styles[i])).build(), false));
        }
        for (var i = 0; i < styles.length; i++) {
            for (var j = i + 1; j < styles.length; j++) {
                list.add(Arguments.of("-M " + ((1 << i) | (1 << j)), List.of("-M", String.valueOf((1 << i) | (1 << j))),
                        base.toBuilder().codeBlockStyles(Set.of(styles[i], styles[j])).build(), false));
            }
        }
        list.add(Arguments.of("-M 63", List.of("-M", "63"),
                base.toBuilder().codeBlockStyles(EnumSet.allOf(Jp2CodeBlockStyle.class)).build(), false));
        return list.stream();
    }

    @Nested
    @DisplayName("lossy coding")
    class Lossy {
        @ParameterizedTest(name = "ratio {0}")
        @ValueSource(doubles = {4, 10, 30, 80})
        @DisplayName("meets the compression ratio and decodes cleanly")
        void ratio(double ratio) {
            var picture = picture(256, 192, false, 3);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(ratio).toBuilder()
                    .format(Jp2Format.CODESTREAM).build());
            var raw = 256 * 192 * 3;
            assertTrue(encoded.length <= raw / ratio * 1.02 + 200, "size " + encoded.length);
            assertTrue(encoded.length >= raw / ratio * 0.7, "size " + encoded.length);
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            var psnr = psnr(picture, decoded);
            assertTrue(psnr > (ratio <= 10 ? 30 : 20), "PSNR " + psnr);
            if (OpenJpeg.find("opj_decompress").isPresent()) {
                var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
                assertNotNull(planes);
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(Jp2Testing.channel(decoded, c), planes[c].samples());
                }
            }
        }

        @ParameterizedTest(name = "{0} dB")
        @ValueSource(doubles = {30, 36, 42})
        @DisplayName("reaches the PSNR target")
        void psnrTarget(double target) {
            var picture = picture(200, 150, false, 11);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.ofPsnr(target));
            var decoded = (RgbaPicture) Jp2Decoder.decode(MediaSource.of(encoded)).picture();
            var psnr = psnr(picture, decoded);
            assertTrue(psnr > target - 1.5, "PSNR " + psnr);
        }

        @Test
        @DisplayName("quality layers refine the image progressively")
        void layers() {
            var picture = picture(160, 120, false, 13);
            var encoded = Jp2Encoder.encode(picture, Jp2EncoderOptions.lossy(50).toBuilder()
                    .compressionRatios(50, 20, 8).build());
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
    }

    @Nested
    @DisplayName("parameters a codestream cannot carry")
    class Bounds {
        private static Jp2Image channels(int count) {
            var list = new ArrayList<Jp2Channel>();
            for (var c = 0; c < count; c++) {
                list.add(new Jp2Channel(0, 0, 1, 1, 1, 1, 8, false, Jp2ChannelType.UNSPECIFIED, -1, new int[]{c & 0xFF}));
            }
            return new Jp2Image(0, 0, 1, 1, 1, 1, 0, list, Jp2ColorSpace.UNSPECIFIED, java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.empty());
        }

        @Test
        @DisplayName("more than 65535 tiles are rejected")
        void tiles() {
            var options = Jp2EncoderOptions.builder().tileSize(1, 1).decompositionLevels(0).build();
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(picture(256, 257, false, 1), options));
        }

        @Test
        @DisplayName("65535 tiles still encode")
        void maxTiles() {
            var options = Jp2EncoderOptions.builder().tileSize(1, 1).decompositionLevels(0).build();
            var encoded = Jp2Encoder.encode(picture(255, 257, false, 1), options);
            assertEquals(255, Jp2Decoder.probe(MediaSource.of(encoded)).width());
        }

        @Test
        @DisplayName("more than 16384 components are rejected")
        void components() {
            var limits = MediaLimits.builder().maxChannels(1 << 20).build();
            var options = Jp2EncoderOptions.builder().limits(limits).build();
            assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(channels(16385), options));
        }

        @Test
        @DisplayName("components beyond the channel limit are LimitExceeded")
        void channelLimit() {
            var options = Jp2EncoderOptions.builder().limits(MediaLimits.builder().maxChannels(2).build()).build();
            assertThrows(MediaException.LimitExceeded.class, () -> Jp2Encoder.encode(channels(3), options));
        }

        @Test
        @DisplayName("a comment that is not Latin-1 or too long for a COM segment is rejected")
        void comment() {
            var builder = Jp2EncoderOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.comment("\u0100").build());
            assertThrows(IllegalArgumentException.class, () -> builder.comment("x".repeat(65532)).build());
            var longest = "x".repeat(65531);
            var encoded = Jp2Encoder.encode(picture(8, 8, false, 1), builder.comment(longest).build());
            assertEquals(8, Jp2Decoder.probe(MediaSource.of(encoded)).width());
        }
    }

    static double psnr(RgbaPicture expected, RgbaPicture actual) {
        var error = 0.0;
        for (var c = 0; c < 3; c++) {
            error += Jp2Testing.mse(Jp2Testing.channel(expected, c), Jp2Testing.channel(actual, c));
        }
        return 10 * Math.log10(255.0 * 255.0 / (error / 3));
    }
}
