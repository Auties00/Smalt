package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lossless and region of interest coding of channels deeper than 24 bits, up to the 38 bits a codestream declares:
/// round trips through the decoder, and through `opj_decompress` for the depths whose sub-bands stay within the 30
/// magnitude bit-planes OpenJPEG's block decoder holds.
@DisplayName("JPEG 2000 deep channels")
class Jp2DeepChannelTest {
    /// The content of a test channel.
    enum Content {
        /// A diagonal ramp over 80% of the range with noise of 1/64 of it.
        SMOOTH,
        /// Uniform noise over the whole range.
        NOISE,
        /// The two extreme values of the range in a checkerboard.
        EXTREMES,
        /// A smooth wave over 80% of the range, without noise.
        WAVE
    }

    /// Builds the samples of one channel; a channel of more than 31 bits, or a 32-bit unsigned one, is wide.
    static Jp2Channel channel(int width, int height, int dx, int dy, int precision, boolean signed, Content content,
                              long seed) {
        var count = width * height;
        var values = new long[count];
        var min = signed ? -(1L << (precision - 1)) : 0;
        var range = 1L << precision;
        var state = seed * 0x9E3779B97F4A7C15L + precision;
        for (var i = 0; i < count; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            var random = (state >>> 1) ^ (state >>> 29);
            var x = i % width;
            var y = i / width;
            var offset = switch (content) {
                case SMOOTH -> {
                    var ramp = (long) ((double) (x + y) / (width + height) * 0.8 * range);
                    yield Math.min(range - 1, ramp + Math.floorMod(random, Math.max(1, range / 64)));
                }
                case NOISE -> Math.floorMod(random, range);
                case EXTREMES -> (x + y) % 2 == 0 ? 0 : range - 1;
                case WAVE -> (long) ((0.5 + 0.4 * Math.sin(x * 0.19) * Math.cos(y * 0.13)) * range);
            };
            values[i] = min + offset;
        }
        if (precision > 31 || (precision == 32 && !signed)) {
            return new Jp2Channel(0, 0, width, height, dx, dy, precision, signed, Jp2ChannelType.COLOR, -1, null,
                    values);
        }
        var samples = new int[count];
        for (var i = 0; i < count; i++) {
            samples[i] = (int) values[i];
        }
        return new Jp2Channel(0, 0, width, height, dx, dy, precision, signed, Jp2ChannelType.COLOR, -1, samples);
    }

    static Jp2Image gray(int width, int height, int precision, boolean signed, Content content) {
        return image(width, height, List.of(channel(width, height, 1, 1, precision, signed, content, 1)),
                Jp2ColorSpace.GRAYSCALE);
    }

    static Jp2Image rgb(int width, int height, int precision, boolean signed, Content content) {
        var channels = new ArrayList<Jp2Channel>();
        for (var c = 0; c < 3; c++) {
            channels.add(channel(width, height, 1, 1, precision, signed, content, c + 1));
        }
        return image(width, height, channels, Jp2ColorSpace.SRGB);
    }

    static Jp2Image image(int width, int height, List<Jp2Channel> channels, Jp2ColorSpace colorSpace) {
        return new Jp2Image(0, 0, width, height, width, height, 0, channels, colorSpace, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    static long[] samples(Jp2Channel channel) {
        if (channel.wide()) {
            return channel.wideSamples();
        }
        var result = new long[channel.samples().length];
        for (var i = 0; i < result.length; i++) {
            result[i] = channel.samples()[i];
        }
        return result;
    }

    /// Asserts that a file decodes to every sample of an image.
    static void assertLossless(Jp2Image expected, byte[] encoded) {
        var decoded = Jp2Decoder.decodeChannels(MediaSource.of(encoded));
        assertEquals(expected.channels().size(), decoded.channels().size());
        for (var c = 0; c < expected.channels().size(); c++) {
            var channel = decoded.channels().get(c);
            assertEquals(expected.channels().get(c).precision(), channel.precision(), "precision of channel " + c);
            assertEquals(expected.channels().get(c).signed(), channel.signed(), "signedness of channel " + c);
            assertArrayEquals(samples(expected.channels().get(c)), samples(channel), "channel " + c);
        }
    }

    static Jp2EncoderOptions.Builder lossless() {
        return Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM);
    }

    static Stream<Arguments> depths() {
        var list = Stream.<Arguments>builder();
        for (var precision = 25; precision <= 38; precision++) {
            for (var signed : new boolean[]{false, true}) {
                list.add(Arguments.of(precision, signed, Content.SMOOTH, 5));
                list.add(Arguments.of(precision, signed, Content.SMOOTH, 0));
                if (precision <= 34) {
                    list.add(Arguments.of(precision, signed, Content.NOISE, 3));
                    list.add(Arguments.of(precision, signed, Content.EXTREMES, 5));
                }
            }
        }
        return list.build();
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        @ParameterizedTest(name = "{0} bits signed={1} {2} levels={3}")
        @MethodSource("com.github.auties00.smalt.image.jp2.Jp2DeepChannelTest#depths")
        @DisplayName("a gray channel decodes to every sample")
        void gray(int precision, boolean signed, Content content, int levels) {
            var image = Jp2DeepChannelTest.gray(37, 29, precision, signed, content);
            assertLossless(image, Jp2Encoder.encode(image, lossless().decompositionLevels(levels).build()));
        }

        @ParameterizedTest(name = "{0} bits signed={1} {2}")
        @CsvSource({"24, false, NOISE", "25, false, SMOOTH", "27, true, NOISE", "28, false, SMOOTH",
                "30, true, EXTREMES", "31, false, NOISE", "32, false, SMOOTH", "32, true, NOISE", "33, false, NOISE",
                "36, true, SMOOTH", "38, false, SMOOTH", "38, true, SMOOTH"})
        @DisplayName("three channels through the reversible component transform decode to every sample")
        void colorTransform(int precision, boolean signed, Content content) {
            var image = rgb(40, 24, precision, signed, content);
            var encoded = Jp2Encoder.encode(image, Jp2EncoderOptions.lossless());
            assertLossless(image, encoded);
            assertLossless(image, Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder()
                    .colorTransform(false).build()));
        }

        static Stream<Arguments> styles() {
            var list = Stream.<Arguments>builder();
            for (var precision : new int[]{26, 32, 38}) {
                for (var style : Jp2CodeBlockStyle.values()) {
                    list.add(Arguments.of(precision, EnumSet.of(style)));
                }
                list.add(Arguments.of(precision, EnumSet.allOf(Jp2CodeBlockStyle.class)));
            }
            return list.build();
        }

        @ParameterizedTest(name = "{0} bits {1}")
        @MethodSource("styles")
        @DisplayName("every code-block style decodes to every sample")
        void styles(int precision, Set<Jp2CodeBlockStyle> styles) {
            var image = Jp2DeepChannelTest.gray(48, 40, precision, true, Content.SMOOTH);
            assertLossless(image, Jp2Encoder.encode(image, lossless().codeBlockSize(16, 16).codeBlockStyles(styles)
                    .build()));
        }

        @Test
        @DisplayName("subsampled channels at an origin over several tiles and layers decode to every sample")
        void tiled() {
            var x0 = 7L;
            var y0 = 3L;
            var width = 61;
            var height = 43;
            var channels = new ArrayList<Jp2Channel>();
            var precisions = new int[]{33, 29, 36};
            for (var c = 0; c < 3; c++) {
                var dx = c == 0 ? 1 : 2;
                var dy = c == 2 ? 2 : 1;
                var cx0 = Math.ceilDiv(x0, dx);
                var cy0 = Math.ceilDiv(y0, dy);
                var w = (int) (Math.ceilDiv(x0 + width, dx) - cx0);
                var h = (int) (Math.ceilDiv(y0 + height, dy) - cy0);
                var base = channel(w, h, dx, dy, precisions[c], c == 1, c == 2 ? Content.WAVE : Content.SMOOTH, c);
                channels.add(new Jp2Channel(cx0, cy0, w, h, dx, dy, base.precision(), base.signed(), base.type(), -1,
                        base.samples(), base.wideSamples()));
            }
            var image = new Jp2Image(x0, y0, width, height, width, height, 0, channels, Jp2ColorSpace.UNSPECIFIED,
                    Optional.empty(), Optional.empty(), Optional.empty());
            var options = lossless().tileOrigin(2, 1).tileSize(24, 20).compressionRatios(40, 10, 1).build();
            assertLossless(image, Jp2Encoder.encode(image, options));
        }

        @Test
        @DisplayName("a 38-bit channel whose coefficients outgrow the 37 bit-planes of seven guard bits is rejected")
        void beyondGuardBits() {
            var image = Jp2DeepChannelTest.gray(32, 32, 38, false, Content.EXTREMES);
            var failure = assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(image,
                    Jp2EncoderOptions.lossless()));
            assertTrue(failure.getMessage().contains("guard bits"), failure.getMessage());
        }

        @ParameterizedTest(name = "{0} bits signed={1} sample {2}")
        @CsvSource({"36, false, 68719476736", "36, false, -1", "38, true, 137438953472", "38, true, -137438953473",
                "8, false, 256"})
        @DisplayName("a wide sample outside its channel's precision is rejected")
        void wideOutOfRange(int precision, boolean signed, long sample) {
            var values = new long[16];
            values[5] = sample;
            var image = image(4, 4, List.of(new Jp2Channel(0, 0, 4, 4, 1, 1, precision, signed,
                    Jp2ChannelType.COLOR, -1, null, values)), Jp2ColorSpace.GRAYSCALE);
            var failure = assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(image, lossless()
                    .build()));
            assertTrue(failure.getMessage().contains("exceeds its precision"), failure.getMessage());
        }

        @ParameterizedTest(name = "{0} bits lossless={1}")
        @CsvSource({"8, true", "20, true", "29, true", "31, true", "12, false", "24, false"})
        @DisplayName("narrow color channels held in wide samples code as their 32-bit form does")
        void wideNarrowChannel(int precision, boolean lossless) {
            var narrow = rgb(37, 29, precision, true, Content.NOISE);
            var channels = new ArrayList<Jp2Channel>();
            for (var c = 0; c < 3; c++) {
                // the second channel stays in 32-bit samples, so one component transform mixes both forms
                channels.add(c == 1 ? narrow.channels().get(c) : new Jp2Channel(0, 0, 37, 29, 1, 1, precision, true,
                        Jp2ChannelType.COLOR, -1, null, samples(narrow.channels().get(c))));
            }
            var wide = image(37, 29, channels, Jp2ColorSpace.SRGB);
            var options = (lossless ? Jp2EncoderOptions.lossless() : Jp2EncoderOptions.lossy(6)).toBuilder()
                    .format(Jp2Format.CODESTREAM).build();
            assertArrayEquals(Jp2Encoder.encode(narrow, options), Jp2Encoder.encode(wide, options));
        }

        @Test
        @DisplayName("a deep channel declares its guard bits and capped exponents in QCD")
        void quantization() {
            var encoded = Jp2Encoder.encode(Jp2DeepChannelTest.gray(16, 16, 33, false, Content.SMOOTH),
                    lossless().decompositionLevels(2).build());
            var qcd = find(encoded, 0xFF5C);
            // 33 bits need an HH exponent of 35: capped at 31, with the four bits made up by guard bits
            assertEquals(6, (encoded[qcd + 4] & 0xFF) >>> 5, "guard bits");
            for (var b = 0; b < 7; b++) {
                var exponent = (encoded[qcd + 5 + b] & 0xFF) >>> 3;
                assertTrue(exponent <= 31, "exponent " + exponent);
            }
            assertEquals(31, (encoded[qcd + 5 + 3] & 0xFF) >>> 3, "HH exponent");
        }

        @Test
        @DisplayName("channels whose capped exponents agree but whose guard bits differ each declare their own")
        void guardBitsOnly() {
            var channels = List.of(channel(24, 20, 1, 1, 33, false, Content.WAVE, 1),
                    channel(24, 20, 1, 1, 36, false, Content.WAVE, 2));
            var image = image(24, 20, channels, Jp2ColorSpace.UNSPECIFIED);
            var encoded = Jp2Encoder.encode(image, lossless().decompositionLevels(1).build());
            var qcc = find(encoded, 0xFF5D);
            assertTrue(qcc > 0, "QCC segment");
            assertEquals(7, (encoded[qcc + 5] & 0xFF) >>> 5, "guard bits of the 36-bit channel");
            assertLossless(image, encoded);
        }

        @ParameterizedTest(name = "{0} bits signed={1}")
        @CsvSource({"25, false", "26, true", "27, false", "28, true", "29, false", "31, false", "32, true",
                "32, false", "36, true"})
        @DisplayName("opj_decompress decodes every sample of the depths its block decoder holds")
        void openJpeg(int precision, boolean signed) {
            var image = Jp2DeepChannelTest.gray(37, 29, precision, signed, Content.SMOOTH);
            var encoded = Jp2Encoder.encode(image, lossless().build());
            var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
            // OpenJPEG's block decoder holds 30 magnitude bit-planes: precisions up to 27 with the default levels
            if (precision <= 27) {
                assertNotNull(planes, "opj_decompress rejected a " + precision + "-bit file");
            }
            if (planes != null) {
                var expected = samples(image.channels().getFirst());
                var narrow = new int[expected.length];
                for (var i = 0; i < narrow.length; i++) {
                    narrow[i] = (int) expected[i];
                }
                Tolerance.exact().assertWithin("opj_decompress", narrow, planes[0].samples());
            }
        }

        @ParameterizedTest(name = "{0} bits signed={1} {2}")
        @CsvSource({"27, false, NOISE", "27, true, NOISE", "27, false, SMOOTH", "26, true, NOISE"})
        @DisplayName("high-throughput color through the 64-bit component transform decodes to every sample")
        void highThroughputColor(int precision, boolean signed, Content content) {
            var image = rgb(40, 24, precision, signed, content);
            var encoded = Jp2Encoder.encode(image, lossless().highThroughput(true).build());
            assertLossless(image, encoded);
            if (precision <= 26) {
                var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
                if (planes != null) {
                    for (var c = 0; c < 3; c++) {
                        Tolerance.exact().assertWithin("opj channel " + c, image.channels().get(c).samples(),
                                planes[c].samples());
                    }
                }
            }
        }

        @Test
        @DisplayName("opj_decompress decodes every sample of 24-bit color through the component transform")
        void openJpegColor() {
            var image = rgb(40, 24, 24, false, Content.NOISE);
            var encoded = Jp2Encoder.encode(image, lossless().build());
            var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
            assertNotNull(planes);
            for (var c = 0; c < 3; c++) {
                Tolerance.exact().assertWithin("opj channel " + c, image.channels().get(c).samples(),
                        planes[c].samples());
            }
        }
    }

    @Nested
    @DisplayName("regions of interest")
    class Regions {
        @ParameterizedTest(name = "{0} bits {1} levels={2}")
        @CsvSource({"12, NOISE, 5", "16, NOISE, 5", "16, SMOOTH, 1", "20, NOISE, 3", "24, NOISE, 5",
                "26, SMOOTH, 5", "26, NOISE, 0", "24, EXTREMES, 2"})
        @DisplayName("a lossless rectangle over a deep channel decodes to every sample")
        void rectangle(int precision, Content content, int levels) {
            var image = Jp2DeepChannelTest.gray(64, 48, precision, false, content);
            var encoded = Jp2Encoder.encode(image, lossless().decompositionLevels(levels).codeBlockSize(16, 16)
                    .regionOfInterest(new Jp2RegionOfInterest.Rectangle(9, 13, 23, 17)).build());
            assertTrue(find(encoded, 0xFF5E) > 0, "RGN segment");
            assertLossless(image, encoded);
        }

        @Test
        @DisplayName("a lossless rectangle over 16-bit color decodes to every sample, and opj_decompress agrees")
        void color() {
            var image = rgb(48, 40, 16, false, Content.NOISE);
            var encoded = Jp2Encoder.encode(image, lossless().regionOfInterest(
                    new Jp2RegionOfInterest.Rectangle(5, 7, 20, 18)).build());
            assertLossless(image, encoded);
            var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
            // with the scaling a 16-bit region needs more than the 30 bit-planes OpenJPEG holds; never a wrong sample
            if (planes != null) {
                for (var c = 0; c < 3; c++) {
                    Tolerance.exact().assertWithin("opj channel " + c, image.channels().get(c).samples(),
                            planes[c].samples());
                }
            }
        }

        @Test
        @DisplayName("a 12-bit lossless rectangle decodes in opj_decompress to every sample")
        void openJpeg() {
            var image = Jp2DeepChannelTest.gray(64, 48, 12, false, Content.NOISE);
            var encoded = Jp2Encoder.encode(image, lossless().regionOfInterest(
                    new Jp2RegionOfInterest.Rectangle(9, 13, 23, 17)).build());
            var planes = OpenJpeg.decode(encoded, "j2k", 0, 0);
            assertNotNull(planes);
            Tolerance.exact().assertWithin("opj_decompress", image.channels().getFirst().samples(),
                    planes[0].samples());
        }

        @Test
        @DisplayName("a whole-component region over a 30-bit channel decodes to every sample")
        void component() {
            var image = rgb(32, 24, 30, false, Content.SMOOTH);
            var encoded = Jp2Encoder.encode(image, lossless().colorTransform(false)
                    .regionOfInterest(new Jp2RegionOfInterest.Component(1, 20)).build());
            assertLossless(image, encoded);
        }

        @ParameterizedTest(name = "{0} bits")
        @CsvSource({"16", "24"})
        @DisplayName("a lossy rectangle over a deep channel is coded more precisely than without a region")
        void lossy(int precision) {
            var image = Jp2DeepChannelTest.gray(96, 80, precision, false, Content.NOISE);
            var base = Jp2EncoderOptions.lossy(8).toBuilder().format(Jp2Format.CODESTREAM);
            var plain = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(image, base.build())));
            var region = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(image, base.regionOfInterest(
                    new Jp2RegionOfInterest.Rectangle(24, 16, 32, 40)).build())));
            var inside = error(image, region, 24, 16, 32, 40);
            assertTrue(inside < error(image, plain, 24, 16, 32, 40) / 2, "region error " + inside);
            assertTrue(error(image, region, 0, 64, 96, 16) > error(image, plain, 0, 64, 96, 16), "background error");
        }

        @Test
        @DisplayName("a rectangle that scales coefficients beyond 55 magnitude bit-planes is rejected")
        void tooDeep() {
            var image = Jp2DeepChannelTest.gray(32, 32, 31, false, Content.NOISE);
            var failure = assertThrows(IllegalArgumentException.class, () -> Jp2Encoder.encode(image, lossless()
                    .regionOfInterest(new Jp2RegionOfInterest.Rectangle(4, 4, 8, 8)).build()));
            assertTrue(failure.getMessage().contains("55"), failure.getMessage());
        }

        static double error(Jp2Image expected, Jp2Image actual, int x, int y, int width, int height) {
            var total = 0.0;
            var reference = expected.channels().getFirst();
            var decoded = actual.channels().getFirst();
            for (var row = y; row < y + height; row++) {
                for (var column = x; column < x + width; column++) {
                    var d = (double) (reference.sample(column, row) - decoded.sample(column, row));
                    total += d * d;
                }
            }
            return total / (width * height);
        }
    }

    @ParameterizedTest(name = "{0} bits")
    @CsvSource({"26", "30", "33", "38"})
    @DisplayName("a lossy deep channel decodes above 40 dB PSNR at a compression ratio of 4")
    void lossy(int precision) {
        var image = gray(64, 48, precision, false, Content.WAVE);
        var decoded = Jp2Decoder.decodeChannels(MediaSource.of(Jp2Encoder.encode(image, Jp2EncoderOptions.lossy(4))));
        var reference = image.channels().getFirst();
        var channel = decoded.channels().getFirst();
        var total = 0.0;
        for (var y = 0; y < 48; y++) {
            for (var x = 0; x < 64; x++) {
                var d = (double) (reference.sample(x, y) - channel.sample(x, y));
                total += d * d;
            }
        }
        var peak = Math.scalb(1.0, precision) - 1;
        var psnr = 10 * Math.log10(peak * peak / Math.max(total / (64 * 48), 1e-300));
        assertTrue(psnr > 40, "PSNR " + psnr);
    }

    /// Returns the offset of the first segment of a marker in the headers before the first tile-part's data, or -1.
    static int find(byte[] codestream, int marker) {
        for (var i = 0; i + 1 < codestream.length; i++) {
            if ((codestream[i] & 0xFF) == 0xFF && (codestream[i + 1] & 0xFF) == 0x93) {
                return -1;
            }
            if ((codestream[i] & 0xFF) == marker >>> 8 && (codestream[i + 1] & 0xFF) == (marker & 0xFF)) {
                return i;
            }
        }
        return -1;
    }
}
