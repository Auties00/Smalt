package com.github.auties00.smalt.image.pnm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raster.RasterFixtures;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Netpbm and float map codec: recognition, every variant and raster form written and read back, maxval
 * scaling, multi-image files, the PAM tuple types, float maps in both byte orders, ffmpeg reading Smalt's files and
 * Smalt reading ffmpeg's, and rejection of malformed headers and rasters.
 */
@DisplayName("Netpbm codec")
class PnmCodecTest {
    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] concat(byte[]... parts) {
        return RasterTesting.concat(parts);
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {
        @ParameterizedTest
        @ValueSource(strings = {"P1\n", "P2 ", "P3\t", "P4\n", "P5\r", "P6\n", "P7\n", "PF\n", "Pf\n", "PH\n",
                "Ph\n"})
        @DisplayName("recognizes every magic followed by white space")
        void magic(String magic) {
            var header = ascii(magic + "1 1 255\n");
            assertEquals(ImageFormat.PNM, ImageFormat.detect(header, header.length).orElseThrow());
        }

        @ParameterizedTest
        @ValueSource(strings = {"P8\n", "P6x", "PA\n", "Q6\n"})
        @DisplayName("rejects other magics")
        void notMagic(String magic) {
            var header = ascii(magic + "1 1 255\n");
            assertNotEquals(ImageFormat.PNM, ImageFormat.detect(header, header.length).orElse(null));
        }

        @ParameterizedTest
        @EnumSource(value = PnmVariant.class, names = {"PGM", "PPM", "PAM"})
        @DisplayName("is probed by the core prober as it decodes")
        void coreProbe(PnmVariant variant) {
            var picture = RasterFixtures.rgba(13, 7, 2, variant == PnmVariant.PAM);
            var file = PnmEncoder.encode(picture, PnmEncoderOptions.builder().variant(variant).build());
            var probed = Media.probe(MediaSource.of(file)).image().orElseThrow();
            assertEquals(13, probed.width());
            assertEquals(7, probed.height());
            assertEquals(1, probed.frameCount().orElseThrow());
        }

        @Test
        @DisplayName("is decodable and encodable through Images")
        void registered() {
            assertTrue(Images.isDecodable(ImageFormat.PNM));
            assertTrue(Images.isEncodable(ImageFormat.PNM));
            assertEquals("image/x-portable-anymap", ImageFormat.PNM.mimeType());
        }
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @Test
        @DisplayName("reads a plain bitmap with comments and samples without separators")
        void plainBitmap() {
            var image = PnmDecoder.decode(MediaSource.of(ascii("P1\n# comment\n3 2\n101\n0 1 0\n")));
            var rgba = RasterTesting.tight(image.picture());
            int[] expected = {0, 255, 0, 255, 0, 255};
            for (var index = 0; index < 6; index++) {
                assertEquals(expected[index], rgba[4 * index] & 0xFF, "pixel " + index);
            }
        }

        @Test
        @DisplayName("reads a raw bitmap with padded rows")
        void rawBitmap() {
            var data = concat(ascii("P4\n10 2\n"), new byte[]{(byte) 0b10000000, (byte) 0b01000000, 0, 0x40});
            var rgba = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(data)).picture());
            assertEquals(0, rgba[0] & 0xFF);
            assertEquals(255, rgba[4] & 0xFF);
            assertEquals(0, rgba[4 * 9] & 0xFF);
            assertEquals(0, rgba[4 * 19] & 0xFF);
            assertEquals(255, rgba[4 * 18] & 0xFF);
        }

        @ParameterizedTest
        @CsvSource({"1", "3", "15", "100", "254", "255"})
        @DisplayName("scales narrow maxvals to 8 bits with rounding")
        void narrowMaxval(int maxval) {
            var samples = new byte[maxval + 1];
            for (var value = 0; value <= maxval; value++) {
                samples[value] = (byte) value;
            }
            var data = concat(ascii("P5\n" + (maxval + 1) + " 1\n" + maxval + "\n"), samples);
            var rgba = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(data)).picture());
            for (var value = 0; value <= maxval; value++) {
                assertEquals((value * 255 + maxval / 2) / maxval, rgba[4 * value] & 0xFF, "value " + value);
            }
        }

        @ParameterizedTest
        @CsvSource({"256", "1023", "4095", "65535"})
        @DisplayName("keeps deep maxvals at 16 bits with high bit depth and reduces them otherwise")
        void wideMaxval(int maxval) {
            var count = 64;
            var raw = new byte[count * 2];
            for (var index = 0; index < count; index++) {
                var value = index * maxval / (count - 1);
                raw[2 * index] = (byte) (value >>> 8);
                raw[2 * index + 1] = (byte) value;
            }
            var data = concat(ascii("P5\n" + count + " 1\n" + maxval + "\n"), raw);
            var wide = assertInstanceOf(Rgba16Picture.class, PnmDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            var narrow = PnmDecoder.decode(MediaSource.of(data)).picture();
            for (var index = 0; index < count; index++) {
                var value = index * maxval / (count - 1);
                var expected = (int) ((value * 65535L + maxval / 2) / maxval);
                assertEquals(expected, wide.pixels()[4 * index] & 0xFFFF, "value " + value);
            }
            assertArrayEquals(RasterTesting.tight(ImageOps.toEightBit(wide)), RasterTesting.tight(narrow));
        }

        @Test
        @DisplayName("clamps samples above maxval")
        void aboveMaxval() {
            var data = concat(ascii("P5\n2 1\n100\n"), new byte[]{(byte) 200, 50});
            var rgba = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(data)).picture());
            assertEquals(255, rgba[0] & 0xFF);
            assertEquals(128, rgba[4] & 0xFF);
        }

        @Test
        @DisplayName("reads the PAM tuple types and keeps CMYK and extra channels with keepChannels")
        void pamTupleTypes() {
            var gray = concat(ascii("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 2\nMAXVAL 255\nTUPLTYPE GRAYSCALE_ALPHA\nENDHDR\n"),
                    new byte[]{10, 20, 30, 40});
            var image = PnmDecoder.decode(MediaSource.of(gray));
            assertTrue(image.picture().hasAlpha());
            var rgba = RasterTesting.tight(image.picture());
            assertArrayEquals(new byte[]{10, 10, 10, 20, 30, 30, 30, 40}, rgba);
            var cmyk = concat(ascii("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 4\nMAXVAL 255\nTUPLTYPE CMYK\nENDHDR\n"),
                    new byte[]{0, (byte) 255, 0, 0});
            var kept = assertInstanceOf(Channel8Picture.class, PnmDecoder.decode(MediaSource.of(cmyk),
                    ImageDecoderOptions.builder().keepChannels(true).build()).picture());
            assertEquals(PixelLayout.CMYK, kept.layout());
            var converted = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(cmyk)).picture());
            assertArrayEquals(new byte[]{(byte) 255, 0, (byte) 255, (byte) 255}, converted);
            var extra = concat(ascii("P7\nWIDTH 1\nHEIGHT 1\nDEPTH 5\nMAXVAL 255\nTUPLTYPE RGB\nENDHDR\n"),
                    new byte[]{1, 2, 3, 4, 5});
            var five = assertInstanceOf(Channel8Picture.class, PnmDecoder.decode(MediaSource.of(extra),
                    ImageDecoderOptions.builder().keepChannels(true).build()).picture());
            assertEquals(5, five.channelCount());
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, java.util.Arrays.copyOf(five.pixels(), 5));
        }

        @Test
        @DisplayName("reads black-and-white PAM with 0 black and 1 white")
        void pamBlackAndWhite() {
            var data = concat(ascii("P7\nWIDTH 2\nHEIGHT 1\nDEPTH 1\nMAXVAL 1\nTUPLTYPE BLACKANDWHITE\nENDHDR\n"),
                    new byte[]{0, 1});
            var rgba = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(data)).picture());
            assertEquals(0, rgba[0] & 0xFF);
            assertEquals(255, rgba[4] & 0xFF);
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("reads float maps of both byte orders bottom row first, keeping values with keepChannels")
        void floatMap(boolean little) {
            var values = new float[]{1.5f, -2f, 0.25f, 100f, Float.MIN_VALUE, 0f};
            var buffer = java.nio.ByteBuffer.allocate(24).order(little ? java.nio.ByteOrder.LITTLE_ENDIAN
                    : java.nio.ByteOrder.BIG_ENDIAN);
            for (var value : values) {
                buffer.putFloat(value);
            }
            var data = concat(ascii("Pf\n3 2\n" + (little ? "-1.0" : "1.0") + "\n"), buffer.array());
            var image = PnmDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().keepChannels(true)
                    .build());
            var floats = assertInstanceOf(ChannelFloatPicture.class, image.picture());
            assertArrayEquals(new float[]{100f, Float.MIN_VALUE, 0f, 1.5f, -2f, 0.25f}, floats.pixels());
            assertTrue(image.colorProfile().isPresent());
        }

        @Test
        @DisplayName("reads half float maps")
        void halfFloatMap() {
            var data = concat(ascii("PH\n1 1\n-1\n"), new byte[]{0x00, 0x3C, 0x00, 0x38, 0x00, (byte) 0xC0});
            var floats = assertInstanceOf(ChannelFloatPicture.class, PnmDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.builder().keepChannels(true).build()).picture());
            assertArrayEquals(new float[]{1f, 0.5f, -2f}, floats.pixels());
        }

        @Test
        @DisplayName("opens every image of a multi-image file as a page")
        void pages() {
            var first = ascii("P5\n1 1\n255\n\u0007");
            var second = ascii("P2\n2 1\n15\n15 0\n");
            var third = ascii("P6 1 1 255\n\u0001\u0002\u0003");
            var data = concat(first, ascii("\n"), second, third);
            try (var pages = PnmDecoder.openPages(MediaSource.of(data))) {
                assertEquals(3, pages.pageCount());
                assertEquals(2, pages.probe(1).width());
                assertEquals(255, RasterTesting.tight(pages.read(1).picture())[0] & 0xFF);
                assertArrayEquals(new byte[]{1, 2, 3, (byte) 255}, RasterTesting.tight(pages.read(2).picture()));
            }
            assertEquals(3, PnmDecoder.probe(MediaSource.of(data)).frameCount());
        }

        @ParameterizedTest
        @ValueSource(strings = {"P5\n2 2\n255\n\u0001\u0002\u0003", "P5\n0 2\n255\n", "P5\n2 2\n70000\n",
                "P5\n2 2", "P2\n2 1\n255\n1", "P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nENDHDR\n\u0000",
                "P7\nWIDTH 1\nHEIGHT 1\nDEPTH 1\nMAXVAL 255\n", "Pf\n1 1\n0\nxxxx", "P1\n2 1\n0 2\n",
                "P5\n99999999999 1\n255\n"})
        @DisplayName("rejects malformed headers and truncated rasters")
        void malformed(String text) {
            assertThrows(MediaException.Malformed.class, () -> PnmDecoder.decode(MediaSource.of(ascii(text))));
        }

        @Test
        @DisplayName("survives mutations with only media exceptions")
        void mutations() {
            var seeds = List.of(PnmEncoder.encode(RasterFixtures.rgba(13, 7, 1, false)),
                    PnmEncoder.encode(RasterFixtures.rgba(5, 9, 2, true)),
                    PnmEncoder.encode(RasterFixtures.rgba(9, 4, 3, false), PnmEncoderOptions.builder().plain(true)
                            .variant(PnmVariant.PGM).build()),
                    PnmEncoder.encode(RasterFixtures.floats(4, 3, PixelLayout.RGB, 4)));
            var stats = RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(300), 7,
                    source -> PnmDecoder.decode(source));
            assertTrue(stats.decoded() > 0);
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        @ParameterizedTest
        @EnumSource(value = PnmVariant.class, names = {"PGM", "PPM", "PAM"})
        @DisplayName("round trips 8 and 16-bit pictures in raw and plain form")
        void roundTrip(PnmVariant variant) {
            for (var plain : new boolean[]{false, true}) {
                if (plain && variant == PnmVariant.PAM) {
                    continue;
                }
                var narrow = RasterFixtures.rgba(17, 6, 5, variant == PnmVariant.PAM);
                var options = PnmEncoderOptions.builder().variant(variant).plain(plain).build();
                var decoded = PnmDecoder.decode(MediaSource.of(PnmEncoder.encode(narrow, options))).picture();
                var expected = variant == PnmVariant.PGM ? gray(narrow) : narrow;
                assertArrayEquals(RasterTesting.tight(expected), RasterTesting.tight(decoded), variant + " " + plain);
                var wide = RasterFixtures.rgba16(11, 5, 6, variant == PnmVariant.PAM);
                var file = PnmEncoder.encode(wide, options);
                var wideDecoded = assertInstanceOf(Rgba16Picture.class, PnmDecoder.decode(MediaSource.of(file),
                        ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
                if (variant == PnmVariant.PGM) {
                    var grayWide = PnmDecoder.decode(MediaSource.of(PnmEncoder.encode(
                            RasterFixtures.gray16(11, 5, 6), options)), ImageDecoderOptions.builder()
                            .highBitDepth(true).keepChannels(true).build()).picture();
                    var red = new short[55];
                    var samples = assertInstanceOf(Rgba16Picture.class, grayWide).pixels();
                    for (var index = 0; index < red.length; index++) {
                        red[index] = samples[4 * index];
                    }
                    assertArrayEquals(RasterFixtures.gray16(11, 5, 6).pixels(), red);
                } else {
                    assertArrayEquals(RasterFixtures.tight(wide), RasterFixtures.tight(wideDecoded));
                }
            }
        }

        @Test
        @DisplayName("writes a bitmap thresholded at luma 128")
        void bitmap() {
            var picture = RgbaPicture.allocate(3, 1, false);
            var pixels = picture.pixels();
            pixels[0] = pixels[1] = pixels[2] = (byte) 127;
            pixels[4] = pixels[5] = pixels[6] = (byte) 128;
            pixels[8] = (byte) 255;
            for (var plain : new boolean[]{false, true}) {
                var file = PnmEncoder.encode(picture, PnmEncoderOptions.builder().variant(PnmVariant.PBM).plain(plain)
                        .build());
                var rgba = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(file)).picture());
                assertEquals(0, rgba[0] & 0xFF);
                assertEquals(255, rgba[4] & 0xFF);
                assertEquals(0, rgba[8] & 0xFF, "luma of pure red is 76");
            }
        }

        @ParameterizedTest
        @CsvSource({"1", "7", "100", "300", "1000"})
        @DisplayName("scales samples to an explicit maxval")
        void explicitMaxval(int maxval) {
            var picture = RasterFixtures.rgba(8, 8, 9, false);
            var file = PnmEncoder.encode(picture, PnmEncoderOptions.builder().variant(PnmVariant.PPM).maxval(maxval)
                    .build());
            var text = new String(file, 0, 20, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("\n" + maxval + "\n"), text);
            var decoded = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(file)).picture());
            var original = RasterTesting.tight(picture);
            for (var index = 0; index < original.length; index++) {
                if (index % 4 == 3) {
                    continue;
                }
                var stored = ((original[index] & 0xFF) * 257L * maxval + 32767) / 65535;
                var expected = maxval > 255 ? (int) (((stored * 65535 + maxval / 2) / maxval * 255 + 32767) / 65535)
                        : (int) ((stored * 255 + maxval / 2) / maxval);
                assertEquals(expected, decoded[index] & 0xFF, "sample " + index);
            }
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("round trips float maps and half float maps")
        void floatRoundTrip(boolean little) {
            for (var layout : List.of(PixelLayout.GRAY, PixelLayout.RGB)) {
                var picture = RasterFixtures.floats(7, 5, layout, 11);
                var options = PnmEncoderOptions.builder().littleEndian(little).build();
                var decoded = assertInstanceOf(ChannelFloatPicture.class, PnmDecoder.decode(MediaSource.of(
                        PnmEncoder.encode(picture, options)), ImageDecoderOptions.builder().keepChannels(true)
                        .build()).picture());
                assertArrayEquals(picture.pixels(), decoded.pixels());
                var half = PnmDecoder.decode(MediaSource.of(PnmEncoder.encode(picture, options.toBuilder()
                        .variant(PnmVariant.PHM).build())), ImageDecoderOptions.builder().keepChannels(true).build());
                var halves = ((ChannelFloatPicture) half.picture()).pixels();
                for (var index = 0; index < halves.length; index++) {
                    assertEquals(Float.float16ToFloat(Float.floatToFloat16(picture.pixels()[index])), halves[index]);
                }
            }
        }

        @Test
        @DisplayName("chooses the variant from the picture")
        void automatic() {
            assertEquals('6', PnmEncoder.encode(RasterFixtures.rgba(2, 2, 1, false))[1]);
            assertEquals('7', PnmEncoder.encode(RasterFixtures.rgba(2, 2, 1, true))[1]);
            assertEquals('5', PnmEncoder.encode(Channel8Picture.allocate(2, 2, PixelLayout.GRAY))[1]);
            assertEquals('F', PnmEncoder.encode(ChannelFloatPicture.allocate(2, 2, PixelLayout.RGB))[1]);
            assertEquals('f', PnmEncoder.encode(ChannelFloatPicture.allocate(2, 2, PixelLayout.GRAY))[1]);
            assertEquals('7', PnmEncoder.encode(Channel16Picture.allocate(2, 2, PixelLayout.CMYK))[1]);
            assertThrows(IllegalArgumentException.class, () -> PnmEncoder.encode(Channel8Picture.allocate(2, 2,
                    PixelLayout.CMYK), PnmEncoderOptions.of(PnmVariant.PPM)));
            assertThrows(IllegalArgumentException.class, () -> PnmEncoderOptions.builder().maxval(0).build());
        }

        @Test
        @DisplayName("encodes through Images and probes through MediaProbe")
        void generic() {
            var picture = RasterFixtures.rgba(12, 9, 3, false);
            var file = Images.encode(picture, ImageFormat.PNM, ImageEncoderOptions.lossless());
            assertEquals(ImageFormat.PNM, Images.detect(MediaSource.of(file)).orElseThrow());
            assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(Images.decode(MediaSource.of(file))
                    .picture()));
            var info = Media.probe(MediaSource.of(file));
            assertEquals(ImageFormat.PNM, info.imageFormat().orElseThrow());
        }

        private static RgbaPicture gray(RgbaPicture picture) {
            var out = RgbaPicture.allocate(picture.width(), picture.height(), false);
            var in = picture.pixels();
            var pixels = out.pixels();
            for (var index = 0; index < in.length; index += 4) {
                var r = (in[index] & 0xFF) * 257L;
                var g = (in[index + 1] & 0xFF) * 257L;
                var b = (in[index + 2] & 0xFF) * 257L;
                var luma = (int) ((299 * r + 587 * g + 114 * b + 500) / 1000);
                var value = (byte) ((luma * 255 + 32767) / 65535);
                pixels[index] = pixels[index + 1] = pixels[index + 2] = value;
                pixels[index + 3] = (byte) 255;
            }
            return out;
        }
    }

    @Nested
    @DisplayName("ffmpeg")
    class Ffmpeg {
        @ParameterizedTest
        @CsvSource({"PGM,false,gray", "PGM,true,gray", "PPM,false,rgb24", "PPM,true,rgb24", "PAM,false,rgba",
                "PBM,false,monob", "PBM,true,monob"})
        @DisplayName("ffmpeg reads Smalt's 8-bit files to the same samples")
        void readsSmalt(PnmVariant variant, boolean plain, String format) {
            var picture = RasterFixtures.rgba(19, 7, 13, variant == PnmVariant.PAM);
            var file = PnmEncoder.encode(picture, PnmEncoderOptions.builder().variant(variant).plain(plain).build());
            var ours = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(file)).picture());
            var theirs = RasterFixtures.ffmpegDecode(file, "pnm", List.of("-f", "image2pipe", "-c:v", codec(variant,
                    plain)), "rgba");
            Tolerance.exact().assertWithin(variant + " " + plain, theirs, ours);
        }

        @ParameterizedTest
        @CsvSource({"PGM", "PPM", "PAM"})
        @DisplayName("ffmpeg reads Smalt's 16-bit files to the same samples")
        void readsSmaltWide(PnmVariant variant) {
            var picture = RasterFixtures.rgba16(9, 6, 17, variant == PnmVariant.PAM);
            var file = PnmEncoder.encode(picture, PnmEncoderOptions.of(variant));
            var ours = (Rgba16Picture) PnmDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder()
                    .highBitDepth(true).build()).picture();
            var theirs = RasterFixtures.shortsLe(RasterFixtures.ffmpegDecode(file, "pnm", List.of("-f",
                    "image2pipe", "-c:v", codec(variant, false)), "rgba64le"));
            Tolerance.exact().assertWithin(variant.name(), theirs, RasterFixtures.tight(ours), 16);
        }

        @ParameterizedTest
        @CsvSource({"pgm,gray,pgm", "pgm,gray16be,pgm", "ppm,rgb24,ppm", "ppm,rgb48be,ppm", "pam,rgba,pam",
                "pam,gray,pam", "pam,monob,pam", "pbm,monob,pbm", "pam,rgba64be,pam", "pam,ya8,pam"})
        @DisplayName("Smalt reads ffmpeg's files to the same samples")
        void readsFfmpeg(String extension, String pixelFormat, String codec) {
            var picture = RasterFixtures.rgba(23, 11, 19, true);
            var file = RasterFixtures.ffmpegEncode(RasterFixtures.raw(picture), "rgba", 23, 11, extension,
                    List.of("-c:v", codec, "-pix_fmt", pixelFormat));
            var wide = pixelFormat.contains("16") || pixelFormat.contains("48") || pixelFormat.contains("64");
            if (wide) {
                var ours = (Rgba16Picture) PnmDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder()
                        .highBitDepth(true).build()).picture();
                var theirs = RasterFixtures.shortsLe(RasterFixtures.ffmpegDecode(file, extension, "rgba64le"));
                Tolerance.exact().assertWithin(pixelFormat, theirs, RasterFixtures.tight(ours), 16);
            } else {
                var ours = RasterTesting.tight(PnmDecoder.decode(MediaSource.of(file)).picture());
                var theirs = RasterFixtures.ffmpegDecode(file, extension, "rgba");
                Tolerance.exact().assertWithin(pixelFormat, theirs, ours);
            }
        }

        @ParameterizedTest
        @CsvSource({"grayf32le,1", "gbrpf32le,3", "grayf32be,1", "gbrpf32be,3"})
        @DisplayName("Smalt and ffmpeg agree on float maps both write")
        void floatMaps(String pixelFormat, int channels) {
            var layout = channels == 1 ? PixelLayout.GRAY : PixelLayout.RGB;
            var picture = RasterFixtures.floats(9, 4, layout, 23);
            var ours = PnmEncoder.encode(picture, PnmEncoderOptions.builder().littleEndian(pixelFormat.endsWith("le"))
                    .build());
            var target = channels == 1 ? "grayf32le" : "gbrpf32le";
            var theirs = RasterFixtures.floatsLe(RasterFixtures.ffmpegDecode(ours, "pfm", target));
            var interleaved = channels == 1 ? theirs : RasterFixtures.interleaveGbr(theirs, 9, 4, 3);
            assertArrayEquals(picture.pixels(), interleaved, "ffmpeg reading Smalt's float map");
            var raw = new byte[picture.pixels().length * 4];
            java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    .put(channels == 1 ? picture.pixels() : planar(picture.pixels(), 9, 4));
            var file = RasterFixtures.ffmpegEncode(raw, target, 9, 4, "pfm", List.of("-c:v", "pfm", "-pix_fmt",
                    pixelFormat));
            var decoded = (ChannelFloatPicture) PnmDecoder.decode(MediaSource.of(file), ImageDecoderOptions
                    .builder().keepChannels(true).build()).picture();
            assertArrayEquals(picture.pixels(), decoded.pixels(), "Smalt reading ffmpeg's float map");
        }

        private static float[] planar(float[] interleaved, int width, int height) {
            var plane = width * height;
            var out = new float[plane * 3];
            for (var index = 0; index < plane; index++) {
                out[index] = interleaved[3 * index + 1];
                out[plane + index] = interleaved[3 * index + 2];
                out[2 * plane + index] = interleaved[3 * index];
            }
            return out;
        }

        private static String codec(PnmVariant variant, boolean plain) {
            return switch (variant) {
                case PBM -> "pbm";
                case PGM -> "pgm";
                case PPM -> "ppm";
                default -> "pam";
            };
        }
    }
}
