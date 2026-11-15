package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import com.github.auties00.smalt.testkit.image.SyntheticProfiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Decodes HEIF files whose JPEG 2000 items (ISO/IEC 15444-16) hold signed samples, samples deeper than 16 bits or
 * CMYK inks, with codestreams written by Smalt's JPEG 2000 encoder and by OpenJPEG's {@code opj_compress}, against
 * the channels {@link Jp2Decoder#decodeChannels(MediaSource)} decodes from the bare codestream.
 */
@DisplayName("HEIF JPEG 2000 items with signed, deep or CMYK channels")
class HeicJpeg2000ChannelsTest {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static final ImageDecoderOptions CHANNELS = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final ImageDecoderOptions WIDE_CHANNELS = ImageDecoderOptions.builder().keepChannels(true)
            .highBitDepth(true).build();

    private static Jp2Channel channel(int width, int height, int subsampling, int precision, boolean signed,
                                      Jp2ChannelType type, int association, long seed) {
        var w = (width + subsampling - 1) / subsampling;
        var h = (height + subsampling - 1) / subsampling;
        var random = new Random(seed);
        var samples = new int[w * h];
        var span = 1L << precision;
        var min = signed ? -(span >> 1) : 0;
        for (var i = 0; i < samples.length; i++) {
            // the extremes first, then a ramp mixed with noise
            var value = i == 0 ? min : i == 1 ? min + span - 1 : min + Math.floorMod(i * 7919L
                    + random.nextLong(span), span);
            samples[i] = (int) value;
        }
        return new Jp2Channel(0, 0, w, h, subsampling, subsampling, precision, signed, type, association, samples);
    }

    private static Jp2Image image(int width, int height, Jp2ColorSpace space, Jp2Channel... channels) {
        return new Jp2Image(0, 0, width, height, width, height, 0, List.of(channels), space, Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    private static byte[] codestream(Jp2Image image) {
        return Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                .colorTransform(false).build());
    }

    private static byte[] jp2(Jp2Image image) {
        return Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.JP2)
                .colorTransform(false).build());
    }

    private static byte[] j2kH(byte[]... boxes) {
        var out = new ByteArrayOutputStream();
        for (var box : boxes) {
            out.writeBytes(box);
        }
        return HeifBuilder.box("j2kH", out.toByteArray());
    }

    private static byte[] colr(int enumerated) {
        return HeifBuilder.box("colr", new byte[]{1, 0, 0, (byte) (enumerated >>> 24), (byte) (enumerated >>> 16),
                (byte) (enumerated >>> 8), (byte) enumerated});
    }

    private static byte[] cdef(int... triples) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u16(out, triples.length / 3);
        for (var value : triples) {
            HeifBuilder.u16(out, value);
        }
        return HeifBuilder.box("cdef", out.toByteArray());
    }

    private static byte[] heif(byte[] codestream, int width, int height, HeifBuilder.Property... extra) {
        var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
        var properties = new ArrayList<HeifBuilder.Property>();
        properties.add(plain(HeifBuilder.ispe(width, height)));
        properties.addAll(Arrays.asList(extra));
        builder.primary(builder.item("j2k1", codestream, properties.toArray(HeifBuilder.Property[]::new)));
        return builder.build();
    }

    private static Picture decode(byte[] file, ImageDecoderOptions options) {
        return HeicDecoder.decode(MediaSource.of(file), options).picture();
    }

    private static int[] reference(byte[] codestream, int channel) {
        return Jp2Decoder.decodeChannels(MediaSource.of(codestream)).channels().get(channel).samples();
    }

    private static int[] luma(Yuv16Picture picture) {
        var out = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out[y * picture.width() + x] = picture.y()[y * picture.yStride() + x] & 0xFFFF;
            }
        }
        return out;
    }

    private static int[] channelOf(ChannelPicture picture, int channel) {
        var count = picture.channelCount();
        var out = new int[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                var at = y * picture.stride() + x * count + channel;
                out[y * picture.width() + x] = switch (picture) {
                    case Channel8Picture eight -> eight.pixels()[at] & 0xFF;
                    case Channel16Picture sixteen -> sixteen.pixels()[at] & 0xFFFF;
                    case ChannelFloatPicture floats -> throw new AssertionError("floating point: " + floats);
                };
            }
        }
        return out;
    }

    private static long[] quantized(ChannelFloatPicture picture, int channel, int precision) {
        var count = picture.channelCount();
        var max = (1L << precision) - 1;
        var out = new long[picture.width() * picture.height()];
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                out[y * picture.width() + x] = Math.round(
                        (double) picture.pixels()[y * picture.stride() + x * count + channel] * max);
            }
        }
        return out;
    }

    private static long[] offset(int[] samples, int precision, boolean signed) {
        var out = new long[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = samples[i] + (signed ? 1L << (precision - 1) : 0);
        }
        return out;
    }

    private static int[] scaled(int[] samples, int precision, int target) {
        var max = (1L << precision) - 1;
        var out = new int[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (int) ((samples[i] * (long) target + max / 2) / max);
        }
        return out;
    }

    @Nested
    @DisplayName("signed samples")
    class Signed {
        @Test
        @DisplayName("a signed 12-bit gray channel decodes to luma offset by 2048, bit-exact with the bare codestream")
        void gray() {
            var image = image(37, 21, Jp2ColorSpace.GRAYSCALE, channel(37, 21, 1, 12, true, Jp2ChannelType.COLOR,
                    1, 1));
            var codestream = codestream(image);
            var picture = assertInstanceOf(Yuv16Picture.class, decode(heif(codestream, 37, 21), WIDE));
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            assertEquals(12, picture.bitDepth());
            var want = offset(reference(codestream, 0), 12, true);
            var got = luma(picture);
            for (var i = 0; i < got.length; i++) {
                assertEquals(want[i], got[i], "sample " + i);
            }
        }

        @Test
        @DisplayName("signed 12-bit RGB channels decode to identity-matrix planes of the offset samples")
        void rgb() {
            var image = image(24, 18, Jp2ColorSpace.SRGB, channel(24, 18, 1, 12, true, Jp2ChannelType.COLOR, 1, 2),
                    channel(24, 18, 1, 12, true, Jp2ChannelType.COLOR, 2, 3),
                    channel(24, 18, 1, 12, true, Jp2ChannelType.COLOR, 3, 4));
            var codestream = codestream(image);
            var picture = assertInstanceOf(Yuv16Picture.class, decode(heif(codestream, 24, 18,
                    essential(j2kH(colr(16)))), WIDE));
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            assertEquals(12, picture.bitDepth());
            // identity-matrix planes hold green, blue and red
            var planes = new short[][]{picture.v(), picture.y(), picture.u()};
            var strides = new int[]{picture.uvStride(), picture.yStride(), picture.uvStride()};
            for (var c = 0; c < 3; c++) {
                var want = offset(reference(codestream, c), 12, true);
                for (var i = 0; i < want.length; i++) {
                    assertEquals(want[i], planes[c][(i / 24) * strides[c] + i % 24] & 0xFFFF,
                            "channel " + c + " sample " + i);
                }
            }
        }

        @Test
        @DisplayName("signed chroma of a 4:2:0 Y'CbCr item is offset into the unsigned range")
        void chroma() {
            var image = image(30, 20, Jp2ColorSpace.UNSPECIFIED,
                    channel(30, 20, 1, 10, false, Jp2ChannelType.COLOR, 1, 5),
                    channel(30, 20, 2, 10, true, Jp2ChannelType.COLOR, 2, 6),
                    channel(30, 20, 2, 10, true, Jp2ChannelType.COLOR, 3, 7));
            var codestream = codestream(image);
            var picture = assertInstanceOf(Yuv16Picture.class, decode(heif(codestream, 30, 20,
                    plain(HeifBuilder.nclx(1, 1, 1, false))), WIDE));
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            var cb = offset(reference(codestream, 1), 10, true);
            for (var y = 0; y < 10; y++) {
                for (var x = 0; x < 15; x++) {
                    assertEquals(cb[y * 15 + x], picture.u()[y * picture.uvStride() + x] & 0xFFFF);
                }
            }
        }
    }

    @Nested
    @DisplayName("samples deeper than 16 bits")
    class Deep {
        @Test
        @DisplayName("a 20-bit gray channel decodes to a floating-point gray picture that quantizes back exactly")
        void gray() {
            var image = image(33, 17, Jp2ColorSpace.GRAYSCALE, channel(33, 17, 1, 20, false, Jp2ChannelType.COLOR,
                    1, 8));
            var codestream = codestream(image);
            var file = heif(codestream, 33, 17);
            var picture = assertInstanceOf(ChannelFloatPicture.class, decode(file, CHANNELS));
            assertEquals(PixelLayout.GRAY, picture.layout());
            assertArrayEquals(offset(reference(codestream, 0), 20, false), quantized(picture, 0, 20));
            assertArrayEquals(ImageOps.toRgba(picture).pixels(), assertInstanceOf(RgbaPicture.class,
                    decode(file, ImageDecoderOptions.defaults())).pixels());
            assertArrayEquals(ImageOps.toRgba16(picture).pixels(), assertInstanceOf(Rgba16Picture.class,
                    decode(file, WIDE)).pixels());
            var info = HeicDecoder.probe(MediaSource.of(file));
            assertEquals(33, info.width());
            assertEquals(17, info.height());
        }

        @Test
        @DisplayName("signed 20-bit RGB with a 20-bit opacity channel decodes to floating-point RGBA")
        void rgbAlpha() {
            var image = image(19, 23, Jp2ColorSpace.SRGB, channel(19, 23, 1, 20, true, Jp2ChannelType.COLOR, 1, 9),
                    channel(19, 23, 1, 20, true, Jp2ChannelType.COLOR, 2, 10),
                    channel(19, 23, 1, 20, true, Jp2ChannelType.COLOR, 3, 11),
                    channel(19, 23, 1, 20, false, Jp2ChannelType.OPACITY, 0, 12));
            var codestream = codestream(image);
            var file = heif(codestream, 19, 23, essential(j2kH(colr(16), cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 1, 0))));
            var picture = assertInstanceOf(ChannelFloatPicture.class, decode(file, WIDE_CHANNELS));
            assertEquals(PixelLayout.RGBA, picture.layout());
            for (var c = 0; c < 4; c++) {
                assertArrayEquals(offset(reference(codestream, c), 20, c < 3), quantized(picture, c, 20),
                        "channel " + c);
            }
        }

        @Test
        @DisplayName("31-bit samples keep the precision a 32-bit float holds")
        void thirtyOne() {
            var image = image(16, 9, Jp2ColorSpace.GRAYSCALE, channel(16, 9, 1, 31, false, Jp2ChannelType.COLOR, 1,
                    13));
            var codestream = codestream(image);
            var picture = assertInstanceOf(ChannelFloatPicture.class, decode(heif(codestream, 16, 9), CHANNELS));
            var want = reference(codestream, 0);
            var max = (double) ((1L << 31) - 1);
            for (var i = 0; i < want.length; i++) {
                assertEquals((float) (want[i] / max), picture.pixels()[i], "sample " + i);
            }
        }

        @Test
        @DisplayName("24-bit limited-range BT.709 Y'CbCr converts to the R'G'B' signals of ITU-T H.273")
        void ycbcr() {
            var width = 22;
            var height = 14;
            var image = image(width, height, Jp2ColorSpace.UNSPECIFIED,
                    channel(width, height, 1, 24, false, Jp2ChannelType.COLOR, 1, 14),
                    channel(width, height, 2, 24, false, Jp2ChannelType.COLOR, 2, 15),
                    channel(width, height, 2, 24, false, Jp2ChannelType.COLOR, 3, 16));
            var codestream = codestream(image);
            var picture = assertInstanceOf(ChannelFloatPicture.class, decode(heif(codestream, width, height,
                    plain(HeifBuilder.nclx(1, 1, 1, false))), CHANNELS));
            assertEquals(PixelLayout.RGB, picture.layout());
            var luma = reference(codestream, 0);
            var cb = reference(codestream, 1);
            var cr = reference(codestream, 2);
            var unit = Math.scalb(1.0, 16);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var chroma = (y / 2) * ((width + 1) / 2) + x / 2;
                    var yy = (luma[y * width + x] - 16 * unit) / (219 * unit);
                    var pb = (cb[chroma] - 128 * unit) / (224 * unit);
                    var pr = (cr[chroma] - 128 * unit) / (224 * unit);
                    var r = yy + 2 * (1 - 0.2126) * pr;
                    var b = yy + 2 * (1 - 0.0722) * pb;
                    var g = (yy - 0.2126 * r - 0.0722 * b) / 0.7152;
                    var at = y * picture.stride() + 3 * x;
                    assertEquals(r, picture.pixels()[at], 1e-5, "red at " + x + "," + y);
                    assertEquals(g, picture.pixels()[at + 1], 1e-5, "green at " + x + "," + y);
                    assertEquals(b, picture.pixels()[at + 2], 1e-5, "blue at " + x + "," + y);
                }
            }
        }

        @Test
        @DisplayName("an image sequence view of a 20-bit item reads 16-bit RGBA frames")
        void animation() throws Exception {
            var image = image(12, 10, Jp2ColorSpace.GRAYSCALE, channel(12, 10, 1, 20, false, Jp2ChannelType.COLOR,
                    1, 17));
            var file = heif(codestream(image), 12, 10);
            var still = decode(file, CHANNELS);
            try (var reader = HeicDecoder.openAnimation(MediaSource.of(file), WIDE)) {
                var frame = reader.read();
                assertArrayEquals(ImageOps.toRgba16(still).pixels(),
                        assertInstanceOf(Rgba16Picture.class, frame.picture()).pixels());
            }
        }
    }

    @Nested
    @DisplayName("CMYK inks")
    class Cmyk {
        private static Jp2Image inks(int width, int height, int precision, long seed, boolean opacity) {
            var channels = new ArrayList<Jp2Channel>();
            for (var c = 0; c < 4; c++) {
                channels.add(channel(width, height, 1, precision, false, Jp2ChannelType.COLOR, c + 1, seed + c));
            }
            if (opacity) {
                channels.add(channel(width, height, 1, precision, false, Jp2ChannelType.OPACITY, 0, seed + 4));
            }
            return new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.CMYK,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        @Test
        @DisplayName("an 8-bit item declared CMYK decodes to the inks, as the JPEG 2000 decoder decodes the JP2 file")
        void eightBit() {
            var image = inks(29, 19, 8, 20, false);
            var codestream = codestream(image);
            var file = heif(codestream, 29, 19, essential(j2kH(colr(12))));
            var picture = assertInstanceOf(Channel8Picture.class, decode(file, CHANNELS));
            assertEquals(PixelLayout.CMYK, picture.layout());
            for (var c = 0; c < 4; c++) {
                assertArrayEquals(reference(codestream, c), channelOf(picture, c), "ink " + c);
            }
            var direct = assertInstanceOf(Channel8Picture.class,
                    Jp2Decoder.decode(MediaSource.of(jp2(image)), CHANNELS).picture());
            assertArrayEquals(direct.pixels(), picture.pixels());
            var rgba = assertInstanceOf(RgbaPicture.class, decode(file, ImageDecoderOptions.defaults()));
            assertArrayEquals(ImageOps.toRgba(picture).pixels(), rgba.pixels());
        }

        @Test
        @DisplayName("12-bit inks with an opacity channel keep 16 bits under highBitDepth, as the JP2 decode does")
        void twelveBitAlpha() {
            var image = inks(21, 13, 12, 30, true);
            var codestream = codestream(image);
            var file = heif(codestream, 21, 13, essential(j2kH(colr(12), cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0, 4,
                    4, 1, 0))));
            var picture = assertInstanceOf(Channel16Picture.class, decode(file, WIDE_CHANNELS));
            assertEquals(PixelLayout.CMYKA, picture.layout());
            for (var c = 0; c < 5; c++) {
                assertArrayEquals(scaled(reference(codestream, c), 12, 65535), channelOf(picture, c), "channel " + c);
            }
            var direct = assertInstanceOf(Channel16Picture.class,
                    Jp2Decoder.decode(MediaSource.of(jp2(image)), WIDE_CHANNELS).picture());
            assertArrayEquals(direct.pixels(), picture.pixels());
            var narrow = assertInstanceOf(Channel8Picture.class, decode(file, CHANNELS));
            for (var c = 0; c < 5; c++) {
                assertArrayEquals(scaled(reference(codestream, c), 12, 255), channelOf(narrow, c), "channel " + c);
            }
            assertArrayEquals(ImageOps.toRgba16(picture).pixels(),
                    assertInstanceOf(Rgba16Picture.class, decode(file, WIDE)).pixels());
            assertTrue(HeicDecoder.probe(MediaSource.of(file)).hasAlpha());
        }

        @Test
        @DisplayName("premultiplied opacity stays a premultiplied alpha channel")
        void premultiplied() {
            var image = inks(10, 8, 8, 40, true);
            var file = heif(codestream(image), 10, 8, essential(j2kH(cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0, 4, 4,
                    2, 0))));
            var picture = assertInstanceOf(Channel8Picture.class, decode(file, CHANNELS));
            assertEquals(ChannelType.PREMULTIPLIED_ALPHA, picture.layout().channel(4).type());
            assertArrayEquals(ImageOps.toRgba(picture).pixels(),
                    assertInstanceOf(RgbaPicture.class, decode(file, ImageDecoderOptions.defaults())).pixels());
        }

        @Test
        @DisplayName("four color channels named by cdef alone, or by a CMYK ICC profile, are inks")
        void declaredOtherwise() {
            var image = inks(14, 6, 8, 50, false);
            var codestream = codestream(image);
            var byDefinition = heif(codestream, 14, 6, essential(j2kH(cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 0, 4))));
            var profile = new byte[132];
            profile[3] = (byte) 132;
            System.arraycopy("CMYK".getBytes(StandardCharsets.ISO_8859_1), 0, profile, 16, 4);
            System.arraycopy("acsp".getBytes(StandardCharsets.ISO_8859_1), 0, profile, 36, 4);
            var byProfile = heif(codestream, 14, 6, essential(j2kH()), plain(HeifBuilder.icc(profile)));
            for (var file : List.of(byDefinition, byProfile)) {
                var picture = assertInstanceOf(Channel8Picture.class, decode(file, CHANNELS));
                assertEquals(PixelLayout.CMYK, picture.layout());
                for (var c = 0; c < 4; c++) {
                    assertArrayEquals(reference(codestream, c), channelOf(picture, c), "ink " + c);
                }
            }
            var undeclared = heif(codestream, 14, 6, essential(j2kH()));
            assertInstanceOf(com.github.auties00.smalt.image.YuvPicture.class, decode(undeclared, CHANNELS));
        }

        @Test
        @DisplayName("YCCK with neutral chroma decodes to inks that complement the luma")
        void ycck() {
            var width = 12;
            var height = 7;
            var luma = channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 1, 60);
            var neutral = new int[width * height];
            Arrays.fill(neutral, 128);
            var black = channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 4, 61);
            var image = image(width, height, Jp2ColorSpace.YCCK, luma,
                    new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, 2, neutral),
                    new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, 3, neutral.clone()),
                    black);
            var picture = assertInstanceOf(Channel8Picture.class, decode(heif(codestream(image), width, height,
                    essential(j2kH(colr(13)))), CHANNELS));
            assertEquals(PixelLayout.CMYK, picture.layout());
            for (var c = 0; c < 3; c++) {
                var ink = channelOf(picture, c);
                for (var i = 0; i < ink.length; i++) {
                    assertEquals(255 - luma.samples()[i], ink[i], "ink " + c + " sample " + i);
                }
            }
            assertArrayEquals(black.samples(), channelOf(picture, 3));
        }

        @Test
        @DisplayName("a grid of CMYK tiles is stitched into one CMYK picture")
        void grid() {
            var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
            var ids = new int[4];
            var tiles = new ArrayList<byte[]>();
            for (var i = 0; i < 4; i++) {
                var codestream = codestream(inks(16, 16, 8, 70 + 10 * i, false));
                tiles.add(codestream);
                ids[i] = builder.item("j2k1", codestream, plain(HeifBuilder.ispe(16, 16)), essential(j2kH(colr(12))));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 30, 28), plain(HeifBuilder.ispe(30, 28)));
            builder.reference("dimg", grid, ids).primary(grid);
            var picture = assertInstanceOf(Channel8Picture.class, decode(builder.build(), CHANNELS));
            assertEquals(30, picture.width());
            assertEquals(28, picture.height());
            for (var i = 0; i < 4; i++) {
                for (var c = 0; c < 4; c++) {
                    var want = reference(tiles.get(i), c);
                    var got = channelOf(picture, c);
                    var x0 = (i % 2) * 16;
                    var y0 = (i / 2) * 16;
                    for (var y = 0; y < 16 && y0 + y < 28; y++) {
                        for (var x = 0; x < 16 && x0 + x < 30; x++) {
                            assertEquals(want[y * 16 + x], got[(y0 + y) * 30 + x0 + x],
                                    "tile " + i + " ink " + c + " at " + x + "," + y);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("an alpha auxiliary image becomes the alpha channel of the inks")
        void auxiliaryAlpha() {
            var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
            var color = builder.item("j2k1", codestream(inks(18, 11, 8, 80, false)), plain(HeifBuilder.ispe(18, 11)),
                    essential(j2kH(colr(12))));
            var mask = channel(18, 11, 1, 8, false, Jp2ChannelType.COLOR, 1, 85);
            var alpha = builder.item("j2k1", codestream(image(18, 11, Jp2ColorSpace.GRAYSCALE, mask)),
                    plain(HeifBuilder.ispe(18, 11)), essential(j2kH()), essential(HeifBuilder.alpha()));
            builder.reference("auxl", alpha, color).hidden(alpha).primary(color);
            var picture = assertInstanceOf(Channel8Picture.class, decode(builder.build(), CHANNELS));
            assertEquals(PixelLayout.CMYKA, picture.layout());
            assertArrayEquals(mask.samples(), channelOf(picture, 4));
        }

        @Test
        @DisplayName("an overlay of CMYK inputs composes their RGBA conversion")
        void overlay() {
            var builder = new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
            var codestream = codestream(inks(8, 8, 8, 90, false));
            var input = builder.item("j2k1", codestream, plain(HeifBuilder.ispe(8, 8)), essential(j2kH(colr(12))));
            builder.hidden(input);
            var overlay = builder.idatItem("iovl", HeifBuilder.overlay(false, new int[]{0, 0, 0, 65535}, 8, 8,
                    new int[]{0, 0}), plain(HeifBuilder.ispe(8, 8)));
            builder.reference("dimg", overlay, input).primary(overlay);
            var picture = assertInstanceOf(RgbaPicture.class, decode(builder.build(), ImageDecoderOptions.defaults()));
            var direct = ImageOps.toRgba(decode(heif(codestream, 8, 8, essential(j2kH(colr(12)))), CHANNELS));
            assertArrayEquals(direct.pixels(), picture.pixels());
        }

        @Test
        @DisplayName("every truncation of a CMYK item decodes or fails with a MediaException")
        void truncated() {
            var codestream = codestream(inks(20, 20, 8, 99, true));
            for (var length = 0; length < codestream.length; length += Math.max(1, codestream.length / 40)) {
                var file = heif(Arrays.copyOf(codestream, length), 20, 20, essential(j2kH(colr(12))));
                try {
                    decode(file, CHANNELS);
                } catch (MediaException expected) {
                    // a clean rejection
                } catch (RuntimeException exception) {
                    fail("length " + length + " threw " + exception, exception);
                }
            }
        }

        @Test
        @DisplayName("a CMYK item reads alike under every I/O behavior of its source")
        void behavior() {
            var file = heif(codestream(inks(24, 16, 8, 101, true)), 24, 16, essential(j2kH(colr(12), cdef(0, 0, 1,
                    1, 0, 2, 2, 0, 3, 3, 0, 4, 4, 1, 0))));
            ImageDecoderAsserts.of("heic/j2k-cmyk-alpha", file).run();
        }
    }

    @Nested
    @DisplayName("OpenJPEG codestreams")
    class OpenJpeg {
        private static Path directory() {
            return Scratch.directory("heic-j2k-channels");
        }

        private static byte[] compress(String name, byte[] input, String... arguments) {
            Oracle.assume(Oracle.Tool.OPJ_COMPRESS);
            var in = directory().resolve(name);
            var out = directory().resolve(name + ".j2k");
            try {
                Files.write(in, input);
                var command = new ArrayList<>(List.of("-i", in.toString(), "-o", out.toString(), "-n", "3"));
                command.addAll(List.of(arguments));
                Oracle.run(Oracle.Tool.OPJ_COMPRESS, Duration.ofMinutes(1), command);
                return Files.readAllBytes(out);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static byte[] pgx(int width, int height, int precision, boolean signed, int[] samples) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(("PG ML " + (signed ? "-" : "+") + precision + " " + width + " " + height + "\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            var bytes = precision <= 8 ? 1 : precision <= 16 ? 2 : 4;
            for (var sample : samples) {
                for (var b = bytes - 1; b >= 0; b--) {
                    out.write(sample >>> (8 * b));
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("a signed PGX coded by opj_compress decodes to luma offset by half its range")
        void signed() {
            var samples = channel(40, 24, 1, 12, true, Jp2ChannelType.COLOR, 1, 200).samples();
            var codestream = compress("signed12.pgx", pgx(40, 24, 12, true, samples));
            var picture = assertInstanceOf(Yuv16Picture.class, decode(heif(codestream, 40, 24), WIDE));
            var channel = Jp2Decoder.decodeChannels(MediaSource.of(codestream)).channels().getFirst();
            // opj_compress may code the PGX samples at a lower precision; the item must follow the codestream
            assertTrue(channel.signed());
            assertEquals(channel.precision(), picture.bitDepth());
            var want = offset(channel.samples(), channel.precision(), true);
            var got = luma(picture);
            for (var i = 0; i < got.length; i++) {
                assertEquals(want[i], got[i], "sample " + i);
            }
        }

        @Test
        @DisplayName("a 20-bit PGX coded by opj_compress decodes to floats that quantize back to its samples")
        void twentyBit() {
            var samples = channel(36, 20, 1, 20, false, Jp2ChannelType.COLOR, 1, 201).samples();
            var codestream = compress("deep20.pgx", pgx(36, 20, 20, false, samples));
            var picture = assertInstanceOf(ChannelFloatPicture.class, decode(heif(codestream, 36, 20), CHANNELS));
            assertArrayEquals(offset(reference(codestream, 0), 20, false), quantized(picture, 0, 20));
            assertArrayEquals(offset(samples, 20, false), quantized(picture, 0, 20));
        }

        @Test
        @DisplayName("four 8-bit components coded by opj_compress decode to the inks of the bare codestream")
        void cmyk() {
            var width = 32;
            var height = 24;
            var raw = new byte[4 * width * height];
            var random = new Random(202);
            random.nextBytes(raw);
            var codestream = compress("cmyk.raw", raw, "-F", width + "," + height + ",4,8,u");
            var picture = assertInstanceOf(Channel8Picture.class, decode(heif(codestream, width, height,
                    essential(j2kH(colr(12)))), CHANNELS));
            for (var c = 0; c < 4; c++) {
                var want = reference(codestream, c);
                assertArrayEquals(want, channelOf(picture, c), "ink " + c);
                for (var i = 0; i < want.length; i++) {
                    assertEquals(raw[c * width * height + i] & 0xFF, want[i], "raw ink " + c + " sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("grids, alpha images, regions and color management")
    class Composition {
        private static Jp2Image inks(int width, int height, int precision, long seed) {
            var channels = new ArrayList<Jp2Channel>();
            for (var c = 0; c < 4; c++) {
                channels.add(channel(width, height, 1, precision, false, Jp2ChannelType.COLOR, c + 1, seed + c));
            }
            return new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.CMYK,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        private static HeifBuilder builder() {
            return new HeifBuilder().brands("j2ki", "j2ki", "mif1", "miaf");
        }

        private static void assertAlpha(int[] samples, int precision, Channel8Picture picture) {
            var max = (double) ((1L << precision) - 1);
            var alpha = channelOf(picture, 4);
            for (var i = 0; i < alpha.length; i++) {
                assertEquals(Math.round(samples[i] * 255 / max), alpha[i], 1, "alpha sample " + i);
            }
        }

        @Test
        @DisplayName("a grid of 8-bit and 12-bit CMYK tiles keeps 16 bits for every tile under highBitDepth")
        void widenedGrid() {
            var builder = builder();
            var ids = new int[4];
            var tiles = new ArrayList<byte[]>();
            var precisions = new int[]{8, 12, 8, 8};
            for (var i = 0; i < 4; i++) {
                var codestream = codestream(inks(16, 16, precisions[i], 400 + 10 * i));
                tiles.add(codestream);
                ids[i] = builder.item("j2k1", codestream, plain(HeifBuilder.ispe(16, 16)), essential(j2kH(colr(12))));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 32, 32), plain(HeifBuilder.ispe(32, 32)));
            builder.reference("dimg", grid, ids).primary(grid);
            var picture = assertInstanceOf(Channel16Picture.class, decode(builder.build(), WIDE_CHANNELS));
            assertEquals(PixelLayout.CMYK, picture.layout());
            for (var i = 0; i < 4; i++) {
                var x0 = (i % 2) * 16;
                var y0 = (i / 2) * 16;
                for (var c = 0; c < 4; c++) {
                    var want = scaled(reference(tiles.get(i), c), precisions[i], 65535);
                    var got = channelOf(picture, c);
                    for (var y = 0; y < 16; y++) {
                        for (var x = 0; x < 16; x++) {
                            assertEquals(want[y * 16 + x], got[(y0 + y) * 32 + x0 + x],
                                    "tile " + i + " ink " + c + " at " + x + "," + y);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("a grid that mixes planar and CMYK tiles is malformed")
        void mixedGrid() {
            var builder = builder();
            var gray = builder.item("j2k1", codestream(image(16, 16, Jp2ColorSpace.GRAYSCALE,
                    channel(16, 16, 1, 8, false, Jp2ChannelType.COLOR, 1, 410))), plain(HeifBuilder.ispe(16, 16)));
            var cmyk = builder.item("j2k1", codestream(inks(16, 16, 8, 411)), plain(HeifBuilder.ispe(16, 16)),
                    essential(j2kH(colr(12))));
            builder.hidden(gray).hidden(cmyk);
            var grid = builder.idatItem("grid", HeifBuilder.grid(1, 2, 32, 16), plain(HeifBuilder.ispe(32, 16)));
            builder.reference("dimg", grid, gray, cmyk).primary(grid);
            var file = builder.build();
            assertThrows(MediaException.Malformed.class, () -> decode(file, CHANNELS));
        }

        @Test
        @DisplayName("a 20-bit alpha auxiliary image becomes the alpha channel of 8-bit inks")
        void deepAlpha() {
            var builder = builder();
            var color = builder.item("j2k1", codestream(inks(18, 11, 8, 420)), plain(HeifBuilder.ispe(18, 11)),
                    essential(j2kH(colr(12))));
            var mask = channel(18, 11, 1, 20, false, Jp2ChannelType.COLOR, 1, 421);
            var alpha = builder.item("j2k1", codestream(image(18, 11, Jp2ColorSpace.GRAYSCALE, mask)),
                    plain(HeifBuilder.ispe(18, 11)), essential(HeifBuilder.alpha()));
            builder.reference("auxl", alpha, color).hidden(alpha).primary(color);
            var picture = assertInstanceOf(Channel8Picture.class, decode(builder.build(), CHANNELS));
            assertEquals(PixelLayout.CMYKA, picture.layout());
            assertAlpha(mask.samples(), 20, picture);
        }

        @Test
        @DisplayName("an alpha auxiliary image that is a grid of 20-bit tiles becomes the alpha channel")
        void deepAlphaGrid() {
            var builder = builder();
            var color = builder.item("j2k1", codestream(inks(18, 11, 8, 430)), plain(HeifBuilder.ispe(18, 11)),
                    essential(j2kH(colr(12))));
            var mask = channel(18, 11, 1, 20, false, Jp2ChannelType.COLOR, 1, 431);
            var tile = builder.item("j2k1", codestream(image(18, 11, Jp2ColorSpace.GRAYSCALE, mask)),
                    plain(HeifBuilder.ispe(18, 11)));
            builder.hidden(tile);
            var alpha = builder.idatItem("grid", HeifBuilder.grid(1, 1, 18, 11), plain(HeifBuilder.ispe(18, 11)),
                    essential(HeifBuilder.alpha()));
            builder.reference("dimg", alpha, tile).reference("auxl", alpha, color).hidden(alpha).primary(color);
            var picture = assertInstanceOf(Channel8Picture.class, decode(builder.build(), CHANNELS));
            assertEquals(PixelLayout.CMYKA, picture.layout());
            assertAlpha(mask.samples(), 20, picture);
        }

        @Test
        @DisplayName("a region of a CMYK item, and of a grid of CMYK tiles, is the crop of the whole picture")
        void region() {
            var file = heif(codestream(inks(29, 19, 8, 440)), 29, 19, essential(j2kH(colr(12))));
            var region = new Region(3, 4, 10, 7);
            var whole = decode(file, CHANNELS);
            var part = assertInstanceOf(Channel8Picture.class, decode(file, CHANNELS.toBuilder().region(region)
                    .build()));
            assertArrayEquals(ImageOps.toChannel8(ImageOps.crop(whole, region)).pixels(), part.pixels());
            var builder = builder();
            var ids = new int[4];
            for (var i = 0; i < 4; i++) {
                ids[i] = builder.item("j2k1", codestream(inks(16, 16, 8, 450 + 10 * i)),
                        plain(HeifBuilder.ispe(16, 16)), essential(j2kH(colr(12))));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 30, 28), plain(HeifBuilder.ispe(30, 28)));
            builder.reference("dimg", grid, ids).primary(grid);
            var gridFile = builder.build();
            var across = new Region(9, 11, 15, 12);
            var gridWhole = decode(gridFile, CHANNELS);
            var gridPart = assertInstanceOf(Channel8Picture.class, decode(gridFile, CHANNELS.toBuilder()
                    .region(across).build()));
            assertArrayEquals(ImageOps.toChannel8(ImageOps.crop(gridWhole, across)).pixels(), gridPart.pixels());
        }

        @Test
        @DisplayName("YCCK inks equal the CMYK inks the JPEG 2000 decoder derives from the same YCCK channels")
        void ycckChroma() {
            var width = 20;
            var height = 13;
            var image = image(width, height, Jp2ColorSpace.YCCK,
                    channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 1, 460),
                    channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 2, 461),
                    channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 3, 462),
                    channel(width, height, 1, 8, false, Jp2ChannelType.COLOR, 4, 463));
            var picture = assertInstanceOf(Channel8Picture.class, decode(heif(codestream(image), width, height,
                    essential(j2kH(colr(13)))), CHANNELS));
            var inks = new Jp2Channel[4];
            for (var c = 0; c < 4; c++) {
                inks[c] = new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, c + 1,
                        channelOf(picture, c));
            }
            var distinct = false;
            for (var i = 0; i < width * height; i++) {
                distinct |= inks[0].samples()[i] != inks[1].samples()[i]
                        || inks[1].samples()[i] != inks[2].samples()[i];
            }
            assertTrue(distinct, "the chroma must make the inks differ");
            // the JP2 decoder converts YCCK to these inks before the RGBA conversion it applies to CMYK inks
            var fromYcck = Jp2Decoder.decode(MediaSource.of(jp2(image)), ImageDecoderOptions.defaults()).picture();
            var fromInks = Jp2Decoder.decode(MediaSource.of(jp2(image(width, height, Jp2ColorSpace.CMYK, inks))),
                    ImageDecoderOptions.defaults()).picture();
            assertArrayEquals(assertInstanceOf(RgbaPicture.class, fromYcck).pixels(),
                    assertInstanceOf(RgbaPicture.class, fromInks).pixels());
        }

        @Test
        @DisplayName("CMYK inks with a CMYK ICC profile convert through it under convertToSrgb, naively otherwise")
        void colorManaged() {
            var profileBytes = SyntheticProfiles.cmyk();
            var profile = ColorProfile.parse(profileBytes);
            var file = heif(codestream(inks(24, 12, 8, 470)), 24, 12, essential(j2kH(colr(12))),
                    plain(HeifBuilder.icc(profileBytes)));
            var kept = HeicDecoder.decode(MediaSource.of(file), CHANNELS);
            assertEquals(ProfileColorSpace.CMYK, kept.colorProfile().orElseThrow().colorSpace());
            var inks = assertInstanceOf(Channel8Picture.class, kept.picture());
            var managed = HeicDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(true)
                    .build());
            var expected = ImageOps.toRgba(ImageOps.convertColor(inks, profile, ColorProfile.srgb()));
            assertArrayEquals(expected.pixels(), assertInstanceOf(RgbaPicture.class, managed.picture()).pixels());
            var naive = HeicDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
            assertArrayEquals(ImageOps.toRgba(inks).pixels(),
                    assertInstanceOf(RgbaPicture.class, naive.picture()).pixels());
            assertTrue(naive.colorProfile().isEmpty());
            assertTrue(!Arrays.equals(expected.pixels(), ImageOps.toRgba(inks).pixels()),
                    "the profile must change the conversion");
        }
    }

    @Test
    @DisplayName("an item of two color channels stays unsupported")
    void twoColors() {
        var image = image(8, 8, Jp2ColorSpace.UNSPECIFIED, channel(8, 8, 1, 8, false, Jp2ChannelType.COLOR, 1, 300),
                channel(8, 8, 1, 8, false, Jp2ChannelType.COLOR, 2, 301));
        var file = heif(codestream(image), 8, 8, essential(j2kH(cdef(0, 0, 1, 1, 0, 2))));
        assertThrows(MediaException.Unsupported.class, () -> decode(file, CHANNELS));
    }
}
