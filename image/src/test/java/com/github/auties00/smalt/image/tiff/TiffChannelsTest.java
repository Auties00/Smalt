package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers channel pictures through TIFF: pages written by the encoder and decoded back sample for sample in every
 * compression, predictor, tiling and byte order; independently built files of every separated, floating-point
 * and extra-sample layout; the equality of their RGBA conversion with the default decode; and libtiff's
 * {@code tiffcp} and ffmpeg as black-box readers of the encoder's output.
 */
@DisplayName("TIFF channel pictures")
class TiffChannelsTest {
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final ImageDecoderOptions KEEP_WIDE = KEEP.toBuilder().highBitDepth(true).build();

    private static final PixelLayout INKS = PixelLayout.ofInks(List.of("Orange", "", "PANTONE 300 C"))
            .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));

    private static final PixelLayout RGB_EXTRAS = PixelLayout.RGB.withChannel(Channel.of(ChannelType.ALPHA))
            .withChannel(Channel.of(ChannelType.UNSPECIFIED));

    // extra samples whose alpha is not the first one, or that hold no alpha at all
    private static final List<PixelLayout> LATE_EXTRAS = List.of(
            PixelLayout.RGB.withChannel(Channel.of(ChannelType.UNSPECIFIED)),
            PixelLayout.RGB.withChannel(Channel.of(ChannelType.UNSPECIFIED)).withChannel(Channel.of(ChannelType.ALPHA)),
            PixelLayout.CMYK.withChannel(Channel.of(ChannelType.UNSPECIFIED))
                    .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA)),
            PixelLayout.GRAY.withChannel(Channel.of(ChannelType.UNSPECIFIED)).withChannel(Channel.of(ChannelType.ALPHA)),
            PixelLayout.ofInks(List.of("A", "B")).withChannel(Channel.of(ChannelType.UNSPECIFIED))
                    .withChannel(Channel.of(ChannelType.ALPHA)));

    static ChannelPicture random(SplittableRandom random, int kind, int width, int height, PixelLayout layout) {
        var count = width * height * layout.count();
        return switch (kind) {
            case 8 -> {
                var pixels = new byte[count];
                random.nextBytes(pixels);
                yield Channel8Picture.of(width, height, width * layout.count(), pixels, layout);
            }
            case 16 -> {
                var pixels = new short[count];
                for (var i = 0; i < count; i++) {
                    pixels[i] = (short) random.nextInt(65536);
                }
                yield Channel16Picture.of(width, height, width * layout.count(), pixels, layout);
            }
            default -> {
                var pixels = new float[count];
                for (var i = 0; i < count; i++) {
                    pixels[i] = switch (random.nextInt(12)) {
                        case 0 -> (float) (random.nextDouble() * 1000);
                        case 1 -> (float) -random.nextDouble();
                        case 2 -> Float.POSITIVE_INFINITY;
                        case 3 -> -0.0F;
                        default -> (float) random.nextDouble();
                    };
                }
                yield ChannelFloatPicture.of(width, height, width * layout.count(), pixels, layout);
            }
        };
    }

    static DecodedImage decode(byte[] file, ImageDecoderOptions options) {
        return TiffDecoder.decode(MediaSource.of(file), options);
    }

    /** The samples of a picture as a comparable string, with floats by their bits. */
    static String samples(ChannelPicture picture) {
        var row = picture.width() * picture.channelCount();
        var out = new StringBuilder();
        for (var y = 0; y < picture.height(); y++) {
            for (var i = 0; i < row; i++) {
                var at = y * picture.stride() + i;
                out.append(switch (picture) {
                    case Channel8Picture eight -> eight.pixels()[at] & 0xFF;
                    case Channel16Picture sixteen -> sixteen.pixels()[at] & 0xFFFF;
                    case ChannelFloatPicture floating -> Integer.toHexString(Float.floatToRawIntBits(floating.pixels()[at]));
                }).append(' ');
            }
        }
        return out.toString();
    }

    static void assertSameSamples(ChannelPicture expected, Picture actual) {
        assertEquals(expected.getClass(), actual.getClass());
        var channels = (ChannelPicture) actual;
        assertEquals(expected.layout(), channels.layout());
        assertEquals(expected.width(), channels.width());
        assertEquals(expected.height(), channels.height());
        assertEquals(samples(expected), samples(channels));
    }

    static Stream<Arguments> encoderOptions() {
        var options = new ArrayList<Arguments>();
        for (var compression : new TiffCompression[]{TiffCompression.NONE, TiffCompression.PACKBITS,
                TiffCompression.LZW, TiffCompression.DEFLATE}) {
            for (var predictor : new TiffPredictor[]{TiffPredictor.NONE, TiffPredictor.HORIZONTAL}) {
                options.add(Arguments.of(TiffEncoderOptions.builder().compression(compression).predictor(predictor)
                        .build(), compression + " " + predictor));
            }
        }
        options.add(Arguments.of(TiffEncoderOptions.builder().tiles(16, 32).build(), "tiles"));
        options.add(Arguments.of(TiffEncoderOptions.builder().bigEndian(true).strips(3).build(), "big-endian strips"));
        options.add(Arguments.of(TiffEncoderOptions.builder().bigTiff(true).compression(TiffCompression.DEFLATE)
                .build(), "BigTIFF"));
        return options.stream();
    }

    @Nested
    @DisplayName("round trips through the encoder")
    class RoundTrips {
        @ParameterizedTest(name = "{1}")
        @MethodSource("com.github.auties00.smalt.image.tiff.TiffChannelsTest#encoderOptions")
        @DisplayName("keep every layout and sample type exactly")
        void layouts(TiffEncoderOptions options, String name) {
            var random = new SplittableRandom(name.hashCode());
            var layouts = new ArrayList<>(List.of(PixelLayout.CMYK, PixelLayout.CMYKA, INKS, RGB_EXTRAS,
                    PixelLayout.GRAY, PixelLayout.RGB, PixelLayout.GRAY_ALPHA));
            layouts.addAll(LATE_EXTRAS);
            for (var layout : layouts) {
                for (var kind : new int[]{8, 16, 32}) {
                    var picture = random(random, kind, 37, 21, layout);
                    var file = TiffEncoder.encode(picture, options);
                    var decoded = decode(file, KEEP_WIDE);
                    if (kind == 32 || layout.colorModel() != com.github.auties00.smalt.image.ColorModel.GRAY
                            && layout.colorModel() != com.github.auties00.smalt.image.ColorModel.RGB
                            || layout.count() > layout.colorChannels() + (layout.hasAlpha() ? 1 : 0)) {
                        assertSameSamples(picture, decoded.picture());
                    } else {
                        // gray and RGB integer pages with at most an alpha decode to their usual RGBA pictures
                        var expected = kind == 8 ? ImageOps.toRgba(picture) : ImageOps.toRgba16(picture);
                        assertEquals(Arrays.toString(packed(expected)), Arrays.toString(packed(decoded.picture())),
                                layout + " " + kind);
                    }
                }
            }
        }

        @Test
        @DisplayName("write extra channels without a TIFF meaning as unspecified samples, keeping their values")
        void unspecifiedExtras() {
            var random = new SplittableRandom(4);
            var depth = PixelLayout.RGB.withChannel(Channel.of(ChannelType.DEPTH))
                    .withChannel(Channel.ofSpot("Gold"));
            var picture = random(random, 16, 7, 5, depth);
            var decoded = (ChannelPicture) decode(TiffEncoder.encode(picture), KEEP_WIDE).picture();
            assertEquals(PixelLayout.RGB.withChannel(Channel.of(ChannelType.UNSPECIFIED))
                    .withChannel(Channel.of(ChannelType.UNSPECIFIED)), decoded.layout());
            assertEquals(samples(picture), samples(decoded));
            // the default decode keeps reading an unspecified first extra sample of RGB as associated alpha
            var single = random(random, 8, 7, 5, LATE_EXTRAS.getFirst());
            var file = TiffEncoder.encode(single);
            assertTrue(decode(file, ImageDecoderOptions.defaults()).picture().hasAlpha());
            assertSameSamples(single, decode(file, KEEP).picture());
        }

        @Test
        @DisplayName("write the separated, ink and floating-point tags the TIFF specification defines")
        void tags() throws Exception {
            var file = TiffEncoder.encode(random(new SplittableRandom(1), 32, 4, 4, INKS), TiffEncoderOptions.defaults());
            try (var pages = TiffDecoder.openPages(MediaSource.of(file))) {
                var page = pages.page(0);
                assertEquals(TiffPhotometric.SEPARATED, page.photometric());
            }
            var directory = com.github.auties00.smalt.image.tiff.internal.TiffFile
                    .open(MediaSource.of(file), com.github.auties00.smalt.MediaLimits.DEFAULT).pages().getFirst();
            assertEquals(2, directory.longValue(332, 0));
            assertEquals(3, directory.longValue(334, 0));
            assertEquals("Orange", directory.string(333));
            assertArrayEquals("Orange\0\0PANTONE 300 C\0".getBytes(), directory.bytes(333));
            assertArrayEquals(new long[]{3, 3, 3, 3}, directory.longs(339));
            assertArrayEquals(new long[]{1}, directory.longs(338));
            assertArrayEquals(new long[]{32, 32, 32, 32}, directory.longs(258));
            assertFalse(directory.has(317), "no predictor on floating-point samples");
        }

        @Test
        @DisplayName("convert the samples first with an explicit depth")
        void depths() {
            var picture = random(new SplittableRandom(2), 32, 9, 9, PixelLayout.CMYK);
            var eight = decode(TiffEncoder.encode(picture, TiffEncoderOptions.builder()
                    .depth(TiffEncoderOptions.Depth.EIGHT).build()), KEEP_WIDE).picture();
            assertSameSamples(ImageOps.toChannel8(picture), eight);
            var sixteen = decode(TiffEncoder.encode(picture, TiffEncoderOptions.builder()
                    .depth(TiffEncoderOptions.Depth.SIXTEEN).build()), KEEP_WIDE).picture();
            assertSameSamples(ImageOps.toChannel16(picture), sixteen);
            var reduced = decode(TiffEncoder.encode(ImageOps.toChannel16(picture)), KEEP).picture();
            assertSameSamples(ImageOps.toChannel8(ImageOps.toChannel16(picture)), reduced);
        }

        @Test
        @DisplayName("keep a multi-page file of mixed channel pictures")
        void pages() {
            var random = new SplittableRandom(3);
            var pictures = List.of(random(random, 8, 5, 5, PixelLayout.CMYK), random(random, 32, 6, 3, PixelLayout.RGB),
                    random(random, 16, 2, 7, INKS));
            var file = TiffEncoder.encode(pictures);
            try (var pages = TiffDecoder.openPages(MediaSource.of(file), KEEP_WIDE)) {
                for (var i = 0; i < pictures.size(); i++) {
                    assertSameSamples(pictures.get(i), pages.read(i).picture());
                }
            }
        }
    }

    @Nested
    @DisplayName("independently built files")
    class Built {
        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {1, 2, 4, 8, 12, 16, 32})
        @DisplayName("decode CMYK samples scaled as the class documents, contiguous and planar")
        void cmyk(int bits) {
            var max = (1L << bits) - 1;
            var values = TiffFiles.pattern(13, 7, 4, max, 40 + bits);
            for (var planar : new boolean[]{false, true}) {
                var builder = TiffFiles.of(13, 7, 4, bits).photometric(5);
                var file = planar ? builder.planar().data(TiffFiles.packPlanar(values, 13, 7, 4, bits, true)).bytes()
                        : builder.data(TiffFiles.pack(values, 13, 7, 4, bits, true)).bytes();
                var wide = bits > 8;
                var picture = (ChannelPicture) decode(file, KEEP_WIDE).picture();
                assertEquals(PixelLayout.CMYK, picture.layout());
                assertEquals(wide ? Channel16Picture.class : Channel8Picture.class, picture.getClass());
                for (var i = 0; i < values.length; i++) {
                    var expected = wide ? (values[i] * 65535 + (max >> 1)) / max : (values[i] * 255 + (max >> 1)) / max;
                    assertEquals(expected, value(picture, i), "sample " + i);
                }
                var plain = decode(file, wide ? ImageDecoderOptions.builder().highBitDepth(true).build()
                        : ImageDecoderOptions.defaults()).picture();
                var converted = wide ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
                assertEquals(Arrays.toString(packed(plain)), Arrays.toString(packed(converted)));
                assertEquals(Arrays.toString(packed(decode(file, ImageDecoderOptions.defaults()).picture())),
                        Arrays.toString(packed(ImageOps.toRgba(picture))), "8-bit conversion of the 16-bit picture");
            }
        }

        @Test
        @DisplayName("decode associated-alpha CMYK by dividing the inks first, as the channel conversion does")
        void premultipliedCmyk() {
            var values = TiffFiles.pattern(11, 6, 5, 255, 50);
            for (var i = 0; i < 11 * 6; i++) {
                for (var c = 0; c < 4; c++) {
                    values[i * 5 + c] = values[i * 5 + c] * values[i * 5 + 4] / 255;
                }
            }
            var file = TiffFiles.of(11, 6, 5, 8).photometric(5).extra(1)
                    .data(TiffFiles.pack(values, 11, 6, 5, 8, true)).bytes();
            var picture = (Channel8Picture) decode(file, KEEP).picture();
            assertEquals(PixelLayout.CMYK.withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA)), picture.layout());
            assertArrayEquals(((RgbaPicture) decode(file, ImageDecoderOptions.defaults()).picture()).pixels(),
                    ImageOps.toRgba(picture).pixels());
        }

        @Test
        @DisplayName("decode ink sets with names, counting inks from the extra samples when NumberOfInks is absent")
        void inks() {
            var values = TiffFiles.pattern(9, 5, 3, 65535, 60);
            var names = "Red\0Lime\0".getBytes();
            var file = TiffFiles.of(9, 5, 3, 16).photometric(5).tag(332, 2).extra(0).undefined(333, names)
                    .data(TiffFiles.pack(values, 9, 5, 3, 16, false)).bigEndian().bytes();
            var picture = (Channel16Picture) decode(file, KEEP_WIDE).picture();
            var layout = picture.layout();
            assertEquals(com.github.auties00.smalt.image.ColorModel.INK, layout.colorModel());
            assertEquals(2, layout.colorChannels());
            assertEquals("Red", layout.channel(0).name());
            assertEquals("Lime", layout.channel(1).name());
            assertEquals(ChannelType.UNSPECIFIED, layout.channel(2).type());
            for (var i = 0; i < values.length; i++) {
                assertEquals(values[i], value(picture, i));
            }
            var counted = TiffFiles.of(9, 5, 3, 16).photometric(5).tag(332, 2).tag(334, 3)
                    .data(TiffFiles.pack(values, 9, 5, 3, 16, true)).bytes();
            var three = (ChannelPicture) decode(counted, KEEP_WIDE).picture();
            assertEquals(3, three.layout().colorChannels());
            assertEquals("", three.layout().channel(2).name());
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class,
                    () -> decode(counted, ImageDecoderOptions.defaults()));
            var invalid = TiffFiles.of(9, 5, 3, 16).photometric(5).tag(332, 2).tag(334, 4)
                    .data(TiffFiles.pack(values, 9, 5, 3, 16, true)).bytes();
            assertThrows(com.github.auties00.smalt.MediaException.Malformed.class, () -> decode(invalid, KEEP_WIDE));
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {16, 32, 64})
        @DisplayName("decode floating-point gray and RGB samples unscaled, and convert them like the default decode")
        void floats(int bits) {
            for (var samples : new int[]{1, 3, 4}) {
                var random = new SplittableRandom(bits * 10 + samples);
                var reals = new double[13 * 5 * samples];
                var raw = new long[reals.length];
                for (var i = 0; i < reals.length; i++) {
                    reals[i] = random.nextDouble() * 3 - 1;
                    raw[i] = switch (bits) {
                        case 16 -> Float.floatToFloat16((float) reals[i]) & 0xFFFFL;
                        case 32 -> Float.floatToRawIntBits((float) reals[i]) & 0xFFFFFFFFL;
                        default -> Double.doubleToRawLongBits(reals[i]);
                    };
                }
                var file = TiffFiles.of(13, 5, samples, bits).format(3).extra(samples == 4 ? new int[]{2} : new int[0])
                        .data(TiffFiles.pack(raw, 13, 5, samples, bits, true)).bytes();
                var picture = assertInstanceOf(ChannelFloatPicture.class, decode(file, KEEP).picture());
                assertEquals(samples == 1 ? PixelLayout.GRAY : samples == 3 ? PixelLayout.RGB : PixelLayout.RGBA,
                        picture.layout());
                for (var i = 0; i < reals.length; i++) {
                    var expected = switch (bits) {
                        case 16 -> Float.float16ToFloat((short) raw[i]);
                        case 32 -> (float) reals[i];
                        default -> (float) reals[i];
                    };
                    assertEquals(expected, picture.pixels()[i], "sample " + i);
                }
                if (bits != 64) {
                    var wide = decode(file, ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
                    assertEquals(Arrays.toString(packed(wide)), Arrays.toString(packed(ImageOps.toRgba16(picture))));
                    assertEquals(Arrays.toString(packed(decode(file, ImageDecoderOptions.defaults()).picture())),
                            Arrays.toString(packed(ImageOps.toRgba(picture))));
                }
            }
        }

        @Test
        @DisplayName("invert white-is-zero gray, integer and floating point")
        void whiteIsZero() {
            var values = TiffFiles.pattern(7, 3, 2, 255, 70);
            var file = TiffFiles.of(7, 3, 2, 8).photometric(0).extra(0)
                    .data(TiffFiles.pack(values, 7, 3, 2, 8, true)).bytes();
            var picture = (Channel8Picture) decode(file, KEEP).picture();
            assertEquals(PixelLayout.GRAY.withChannel(Channel.of(ChannelType.UNSPECIFIED)), picture.layout());
            for (var i = 0; i < values.length; i++) {
                assertEquals(i % 2 == 0 ? 255 - values[i] : values[i], value(picture, i));
            }
            var raw = new long[]{Float.floatToRawIntBits(0.25F) & 0xFFFFFFFFL, Float.floatToRawIntBits(2.0F) & 0xFFFFFFFFL};
            var floating = TiffFiles.of(2, 1, 1, 32).photometric(0).format(3)
                    .data(TiffFiles.pack(raw, 2, 1, 1, 32, true)).bytes();
            var gray = (ChannelFloatPicture) decode(floating, KEEP).picture();
            assertEquals(0.75F, gray.pixels()[0]);
            assertEquals(-1.0F, gray.pixels()[1]);
        }

        @Test
        @DisplayName("keep extra samples of gray and RGB pages, and decode other pages as usual")
        void extraSamples() {
            var values = TiffFiles.pattern(8, 4, 5, 65535, 80);
            var file = TiffFiles.of(8, 4, 5, 16).extra(2, 0).data(TiffFiles.pack(values, 8, 4, 5, 16, true)).bytes();
            var picture = (ChannelPicture) decode(file, KEEP_WIDE).picture();
            assertEquals(RGB_EXTRAS, picture.layout());
            for (var i = 0; i < values.length; i++) {
                assertEquals(values[i], value(picture, i));
            }
            assertEquals(Arrays.toString(packed(decode(file, ImageDecoderOptions.builder().highBitDepth(true).build())
                    .picture())), Arrays.toString(packed(ImageOps.toRgba16(picture))));
            var plain = TiffFiles.of(8, 4, 4, 8).extra(2).data(TiffFiles.pack(TiffFiles.pattern(8, 4, 4, 255, 81), 8, 4,
                    4, 8, true)).bytes();
            assertInstanceOf(RgbaPicture.class, decode(plain, KEEP).picture());
            var palette = TiffFiles.of(4, 4, 1, 8).photometric(3).colorMap(new int[768])
                    .data(new byte[16]).bytes();
            assertInstanceOf(RgbaPicture.class, decode(palette, KEEP).picture());
        }

        @Test
        @DisplayName("report the embedded profile when it describes the color channels")
        void profiles() throws IOException {
            var cmykProfile = Files.readAllBytes(Conformance.fetch("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc",
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/icc_profiles/misc/US_Web_Coated_SWOP_CMYK.icc",
                    "4855b8fabb96bdc6495d45d089bb8c8efb1ae18389e0dc9e75a5f701a9c0b662"));
            var values = TiffFiles.pattern(6, 6, 4, 255, 90);
            var file = TiffFiles.of(6, 6, 4, 8).photometric(5).undefined(34675, cmykProfile)
                    .data(TiffFiles.pack(values, 6, 6, 4, 8, true)).bytes();
            var kept = decode(file, KEEP);
            assertEquals(com.github.auties00.smalt.image.color.ProfileColorSpace.CMYK,
                    kept.colorProfile().orElseThrow().colorSpace());
            var managed = decode(file, ImageDecoderOptions.builder().convertToSrgb(true).build());
            var converted = ImageOps.toSrgb(kept);
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertArrayEquals(((RgbaPicture) managed.picture()).pixels(), ((RgbaPicture) converted.picture()).pixels());
        }
    }

    @Nested
    @DisplayName("libtiff and ffmpeg")
    class Oracles {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"-c none", "-c lzw", "-c zip -p separate", "-c packbits -t -w 16 -l 16", "-c lzw -B",
                "-c zip:p9 -t -w 32 -l 16"})
        @DisplayName("tiffcp rewrites the encoder's channel pages into files that decode to the same samples")
        void tiffcp(String arguments) {
            LibTiff.assume("tiffcp");
            var random = new SplittableRandom(arguments.hashCode());
            var layouts = new ArrayList<>(List.of(PixelLayout.CMYK, PixelLayout.CMYKA, INKS, RGB_EXTRAS));
            layouts.addAll(LATE_EXTRAS);
            for (var layout : layouts) {
                // tiffcp separates the planes only of 8-bit samples
                for (var kind : arguments.contains("separate") ? new int[]{8} : new int[]{8, 16, 32}) {
                    var picture = random(random, kind, 33, 19, layout);
                    var copied = LibTiff.tiffcp(TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed()),
                            List.of(arguments.split(" ")));
                    assertSameSamples(picture, decode(copied, KEEP_WIDE).picture());
                }
            }
            if (!arguments.contains("separate")) {
                for (var layout : List.of(PixelLayout.GRAY, PixelLayout.RGB, PixelLayout.GRAY_ALPHA)) {
                    var picture = random(random, 32, 21, 9, layout);
                    var copied = LibTiff.tiffcp(TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed()),
                            List.of(arguments.split(" ")));
                    assertSameSamples(picture, decode(copied, KEEP).picture());
                }
            }
        }

        @Test
        @DisplayName("libtiff reads the encoder's CMYK pages to the same naive RGBA, within its truncation")
        void tiff2rgba() {
            LibTiff.assume("tiff2rgba");
            var picture = (Channel8Picture) random(new SplittableRandom(5), 8, 24, 16, PixelLayout.CMYK);
            var file = TiffEncoder.encode(picture);
            var expected = LibTiff.rgba(file);
            assertNotNull(expected);
            var actual = ImageOps.toRgba(picture).pixels();
            for (var i = 0; i < actual.length; i++) {
                // libtiff truncates (255 - K) * (255 - C) / 255, Smalt rounds
                var difference = (actual[i] & 0xFF) - (expected[i] & 0xFF);
                assertTrue(difference == 0 || difference == 1, "sample " + i + ": " + difference);
            }
        }

        @Test
        @DisplayName("ffmpeg reads the encoder's floating-point RGB and gray pages to the same floats")
        void ffmpeg() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var random = new SplittableRandom(6);
            // ffmpeg reads floating-point TIFF only as RGB and RGBA
            for (var layout : List.of(PixelLayout.RGB, PixelLayout.RGBA)) {
                var picture = (ChannelFloatPicture) random(random, 32, 17, 11, layout);
                var file = TiffEncoder.encode(picture, TiffEncoderOptions.uncompressed());
                var format = switch (layout.count()) {
                    case 1 -> "grayf32le";
                    case 3 -> "rgbf32le";
                    default -> "rgbaf32le";
                };
                var input = LibTiff.write("float.tif", file);
                var raw = ByteBuffer.wrap(Oracle.decodeVideo(input, format, 1)).order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer();
                assertEquals(picture.pixels().length, raw.remaining());
                for (var i = 0; i < picture.pixels().length; i++) {
                    assertEquals(Float.floatToRawIntBits(picture.pixels()[i]), Float.floatToRawIntBits(raw.get(i)),
                            layout + " sample " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("mutated and truncated channel pages decode or raise MediaException, quickly")
    void robustness() {
        var random = new SplittableRandom(7);
        var seeds = List.of(
                TiffEncoder.encode(random(random, 8, 23, 17, PixelLayout.CMYKA), TiffEncoderOptions.defaults()),
                TiffEncoder.encode(random(random, 16, 19, 13, INKS), TiffEncoderOptions.deflate().toBuilder()
                        .tiles(16, 16).bigEndian(true).build()),
                TiffEncoder.encode(random(random, 32, 17, 11, RGB_EXTRAS), TiffEncoderOptions.builder().strips(4)
                        .build()),
                TiffEncoder.encode(random(random, 8, 15, 9, LATE_EXTRAS.getLast()), TiffEncoderOptions.builder()
                        .compression(TiffCompression.PACKBITS).build()));
        var limited = KEEP_WIDE.toBuilder().limits(com.github.auties00.smalt.MediaLimits.builder().maxBytes(64 << 20)
                .maxPixels(4 << 20).build()).build();
        var stats = assertTimeoutPreemptively(java.time.Duration.ofSeconds(
                com.github.auties00.smalt.testkit.image.RasterTesting.mutationSeconds() * 3), () ->
                com.github.auties00.smalt.testkit.image.RasterTesting.mutate(seeds,
                        com.github.auties00.smalt.testkit.image.RasterTesting.mutationsPerSeed(150), 0x7172F,
                        source -> {
                            var picture = TiffDecoder.decode(source, limited).picture();
                            if (picture instanceof ChannelPicture channels) {
                                assertTrue(channels.stride() >= channels.width() * channels.channelCount());
                            }
                        }));
        assertTrue(stats.decoded() > 0 && stats.rejected() > 0, stats.toString());
        var file = seeds.getFirst();
        for (var length = 0; length < file.length; length++) {
            try {
                decode(Arrays.copyOf(file, length), KEEP_WIDE);
            } catch (com.github.auties00.smalt.MediaException expected) {
                // rejected
            }
        }
    }

    @Test
    @DisplayName("16-bit separated pages of the libtiff test images convert to exactly the default output")
    void flowerSeparated() {
        var pics = LibTiff.pics();
        for (var name : List.of("flower-separated-contig-16.tif", "flower-separated-planar-16.tif",
                "flower-separated-contig-08.tif", "flower-separated-planar-08.tif")) {
            var file = pics.get(name);
            assumeTrueNotNull(file, name);
            var picture = (ChannelPicture) decode(file, KEEP_WIDE).picture();
            assertEquals(PixelLayout.CMYK, picture.layout());
            var wide = decode(file, ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            var narrow = decode(file, ImageDecoderOptions.defaults()).picture();
            if (picture instanceof Channel16Picture) {
                assertEquals(Arrays.toString(packed(wide)), Arrays.toString(packed(ImageOps.toRgba16(picture))));
            }
            assertEquals(Arrays.toString(packed(narrow)), Arrays.toString(packed(ImageOps.toRgba(picture))), name);
        }
        assertEquals(samples((ChannelPicture) decode(pics.get("flower-separated-contig-16.tif"), KEEP_WIDE).picture()),
                samples((ChannelPicture) decode(pics.get("flower-separated-planar-16.tif"), KEEP_WIDE).picture()));
    }

    private static void assumeTrueNotNull(byte[] file, String name) {
        Skip.unless(file != null, "data:libtiff-images", name + " is not in the libtiff test images");
    }

    private static long value(ChannelPicture picture, int index) {
        var row = picture.width() * picture.channelCount();
        var at = (index / row) * picture.stride() + index % row;
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels()[at] & 0xFF;
            case Channel16Picture sixteen -> sixteen.pixels()[at] & 0xFFFF;
            case ChannelFloatPicture floating -> (long) floating.pixels()[at];
        };
    }

    private static int[] packed(Picture picture) {
        return switch (picture) {
            case RgbaPicture rgba -> {
                var out = new int[rgba.width() * rgba.height() * 4];
                for (var y = 0; y < rgba.height(); y++) {
                    for (var i = 0; i < rgba.width() * 4; i++) {
                        out[y * rgba.width() * 4 + i] = rgba.pixels()[y * rgba.stride() + i] & 0xFF;
                    }
                }
                yield out;
            }
            case Rgba16Picture rgba -> {
                var out = new int[rgba.width() * rgba.height() * 4];
                for (var y = 0; y < rgba.height(); y++) {
                    for (var i = 0; i < rgba.width() * 4; i++) {
                        out[y * rgba.width() * 4 + i] = rgba.pixels()[y * rgba.stride() + i] & 0xFFFF;
                    }
                }
                yield out;
            }
            default -> throw new AssertionError("not packed: " + picture);
        };
    }
}
