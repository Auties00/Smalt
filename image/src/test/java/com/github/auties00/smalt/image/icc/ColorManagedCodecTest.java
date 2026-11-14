package com.github.auties00.smalt.image.icc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Color management in the codecs: decoders report the profile an image declares and convert to
 * sRGB on request, matching Little CMS 2.17 applied to the libjpeg-turbo, libpng and libwebp
 * decodes recorded in {@code images.tsv}; PNG color chunks; encoders embedding a profile.
 */
class ColorManagedCodecTest {
    static List<String[]> rows() {
        try (var in = ColorManagedCodecTest.class.getResourceAsStream("images.tsv")) {
            var rows = new ArrayList<String[]>();
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\r?\n")) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    rows.add(line.split("\t"));
                }
            }
            return rows;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Arguments> images() {
        return rows().stream().map(row -> Arguments.of(row[0], row[1], row[2], row[5]));
    }

    static byte[] fetch(String name, String sha256, String url) {
        try {
            return Files.readAllBytes(Conformance.fetch(name, url, sha256));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] rgb(Picture picture) {
        var rgba = ImageOps.toRgba(ImageOps.toEightBit(picture));
        var out = new byte[rgba.width() * rgba.height() * 3];
        for (var y = 0; y < rgba.height(); y++) {
            for (var x = 0; x < rgba.width(); x++) {
                System.arraycopy(rgba.pixels(), y * rgba.stride() + x * 4, out, (y * rgba.width() + x) * 3, 3);
            }
        }
        return out;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static DecodedImage decode(byte[] file, boolean convert) {
        return Images.decode(MediaSource.of(file), ImageDecoderOptions.builder().convertToSrgb(convert).build());
    }

    @Nested
    @DisplayName("decoding")
    class Decoding {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.icc.ColorManagedCodecTest#images")
        @DisplayName("converts to sRGB as Little CMS does")
        void convertsLikeLittleCms(String name, String sha256, String url, String digest) {
            var file = fetch(name, sha256, url);
            var plain = decode(file, false);
            var converted = decode(file, true);
            if (digest.equals("-")) {
                assertArrayEquals(rgb(plain.picture()), rgb(converted.picture()), "no profile, no change");
                return;
            }
            assertTrue(plain.iccProfile().isPresent());
            if (plain.colorProfile().map(ColorProfile::isSrgb).orElse(false)) {
                assertSame(plain.picture().getClass(), converted.picture().getClass());
                assertArrayEquals(rgb(plain.picture()), rgb(converted.picture()), "an sRGB profile is not applied");
                return;
            }
            assertTrue(converted.iccProfile().isEmpty());
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertInstanceOf(RgbaPicture.class, converted.picture());
            if (plain.colorProfile().isPresent()) {
                var expected = ImageOps.convertColor(plain.picture(), plain.colorProfile().get(), ColorProfile.srgb());
                assertArrayEquals(rgb(expected), rgb(converted.picture()), "decoder conversion equals ImageOps");
            }
            if (!name.contains("lossy")) {
                assertEquals(digest, sha256(rgb(converted.picture())), "Little CMS output");
            }
        }

        @Test
        @DisplayName("reports a CMYK JPEG's profile only through its bytes, since the default pixels are naive RGB")
        void cmykProfileNotReported() {
            var row = rows().stream().filter(r -> r[0].equals("skia-image-mandrill_cmyk.jpg")).findFirst().orElseThrow();
            var plain = decode(fetch(row[0], row[1], row[2]), false);
            assertTrue(plain.iccProfile().isPresent());
            assertTrue(plain.colorProfile().isEmpty());
            assertEquals(ProfileColorSpace.CMYK, ColorProfile.parse(plain.iccProfile().get()).colorSpace());
        }

        @Test
        @DisplayName("reports a Display P3 JPEG's profile and keeps its planar picture by default")
        void displayP3() {
            var row = rows().stream().filter(r -> r[0].equals("skia-image-gradient_displayp3.jpeg")).findFirst().orElseThrow();
            var plain = decode(fetch(row[0], row[1], row[2]), false);
            assertInstanceOf(YuvPicture.class, plain.picture());
            var profile = plain.colorProfile().orElseThrow();
            assertEquals(ProfileColorSpace.RGB, profile.colorSpace());
            assertFalse(profile.isSrgb());
        }
    }

    @Nested
    @DisplayName("PNG color chunks")
    class PngChunks {
        static byte[] png() {
            var picture = RgbaPicture.allocate(4, 2, false);
            for (var i = 0; i < picture.pixels().length; i++) {
                picture.pixels()[i] = (byte) (i * 29);
            }
            return PngEncoder.encode(picture);
        }

        static byte[] withChunk(byte[] png, String type, byte[] data) {
            var insert = 8 + 25;
            var out = new byte[png.length + 12 + data.length];
            System.arraycopy(png, 0, out, 0, insert);
            putInt(out, insert, data.length);
            var typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(typeBytes, 0, out, insert + 4, 4);
            System.arraycopy(data, 0, out, insert + 8, data.length);
            var crc = new CRC32();
            crc.update(out, insert + 4, 4 + data.length);
            putInt(out, insert + 8 + data.length, (int) crc.getValue());
            System.arraycopy(png, insert, out, insert + 12 + data.length, png.length - insert);
            return out;
        }

        static void putInt(byte[] out, int at, int value) {
            out[at] = (byte) (value >>> 24);
            out[at + 1] = (byte) (value >>> 16);
            out[at + 2] = (byte) (value >>> 8);
            out[at + 3] = (byte) value;
        }

        static byte[] intBytes(int... values) {
            var out = new byte[values.length * 4];
            for (var i = 0; i < values.length; i++) {
                putInt(out, i * 4, values[i]);
            }
            return out;
        }

        @Test
        @DisplayName("a gAMA of 1/2.2 or an sRGB chunk declares sRGB")
        void srgbLike() {
            assertTrue(decode(withChunk(png(), "gAMA", intBytes(45455)), false).colorProfile().isEmpty());
            assertTrue(decode(withChunk(png(), "sRGB", new byte[]{0}), false).colorProfile().isEmpty());
            assertTrue(decode(png(), false).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("a linear gAMA becomes a gamma 1.0 profile and conversion brightens mid-tones")
        void linearGamma() {
            var file = withChunk(png(), "gAMA", intBytes(100000));
            var plain = decode(file, false);
            var profile = plain.colorProfile().orElseThrow();
            assertFalse(profile.isSrgb());
            var converted = decode(file, true);
            assertNotEquals(sha256(rgb(plain.picture())), sha256(rgb(converted.picture())));
            var mid = (RgbaPicture) ImageOps.convertColor(RgbaPicture.of(1, 1, 4, new byte[]{(byte) 50, 50, 50,
                    (byte) 255}, false),
                    profile, ColorProfile.srgb());
            assertTrue((mid.pixels()[0] & 0xFF) > 100, "linear 50/255 is sRGB " + (mid.pixels()[0] & 0xFF));
        }

        @Test
        @DisplayName("cHRM primaries with a gAMA build an RGB profile")
        void chromaticities() {
            var file = withChunk(withChunk(png(), "gAMA", intBytes(45455)), "cHRM",
                    intBytes(31270, 32900, 68000, 32000, 26500, 69000, 15000, 6000));
            var profile = decode(file, false).colorProfile().orElseThrow();
            assertFalse(profile.isSrgb());
            assertEquals(ProfileColorSpace.RGB, profile.colorSpace());
        }

        @Test
        @DisplayName("a cICP chunk takes precedence and can declare high dynamic range")
        void cicp() {
            var file = withChunk(withChunk(png(), "sRGB", new byte[]{0}), "cICP", new byte[]{9, 16, 0, 1});
            var profile = decode(file, false).colorProfile().orElseThrow();
            assertTrue(profile.isHighDynamicRange());
            var bt709 = withChunk(png(), "cICP", new byte[]{1, 13, 0, 1});
            assertTrue(decode(bt709, false).colorProfile().isEmpty());
        }

        @Test
        @DisplayName("a zero gAMA is ignored")
        void zeroGamma() {
            assertTrue(decode(withChunk(png(), "gAMA", intBytes(0)), false).colorProfile().isEmpty());
        }
    }

    @Nested
    @DisplayName("encoding")
    class Encoding {
        static RgbaPicture picture() {
            var picture = RgbaPicture.allocate(37, 23, false);
            var pixels = picture.pixels();
            for (var i = 0; i < pixels.length; i++) {
                pixels[i] = (byte) ((i % 4 == 3) ? 255 : (i * 7 + i / 97));
            }
            return picture;
        }

        @Test
        @DisplayName("JPEG, PNG and WebP embed a profile that decoders read back")
        void embeds() {
            var profile = ColorProfile.displayP3();
            var files = List.of(
                    JpegEncoder.encode(picture(), JpegEncoderOptions.builder().colorProfile(profile).build()),
                    PngEncoder.encode(picture(), PngEncoderOptions.builder().colorProfile(profile).build()),
                    WebpEncoder.encode(picture(), WebpEncoderOptions.lossless().toBuilder().colorProfile(profile)
                            .build()),
                    WebpEncoder.encode(picture(), WebpEncoderOptions.lossy(80).toBuilder().colorProfile(profile)
                            .build()));
            for (var file : files) {
                var decoded = decode(file, false);
                assertArrayEquals(profile.toByteArray(), decoded.iccProfile().orElseThrow());
                assertEquals(profile, decoded.colorProfile().orElseThrow());
            }
        }

        @Test
        @DisplayName("without a profile the output is byte-identical to the default")
        void unchangedByDefault() {
            assertArrayEquals(JpegEncoder.encode(picture()),
                    JpegEncoder.encode(picture(), JpegEncoderOptions.builder().colorProfile(ColorProfile.srgb())
                            .colorProfile(null).build()));
            assertArrayEquals(PngEncoder.encode(picture()), PngEncoder.encode(picture(), PngEncoderOptions.defaults()));
            assertArrayEquals(WebpEncoder.encode(picture()), WebpEncoder.encode(picture(), WebpEncoderOptions.defaults()));
        }

        @Test
        @DisplayName("a JPEG splits a large profile over several APP2 segments")
        void largeProfileJpeg() {
            // US Web Coated (SWOP), 557168 bytes: nine APP2 segments
            var row = fetch("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc",
                    "4855b8fabb96bdc6495d45d089bb8c8efb1ae18389e0dc9e75a5f701a9c0b662",
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/icc_profiles/misc/US_Web_Coated_SWOP_CMYK.icc");
            var profile = ColorProfile.parse(row);
            var file = JpegEncoder.encode(picture(), JpegEncoderOptions.builder().colorProfile(profile).build());
            assertTrue(file.length > row.length);
            assertArrayEquals(row, decode(file, false).iccProfile().orElseThrow());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"PQ", "HLG"})
        @DisplayName("JPEG, PNG, WebP, TIFF and JPEG 2000 embed an HDR profile in its ICC form that decoders read back")
        void hdrEmbeds(String transfer) {
            var hdr = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                    TransferCharacteristics.valueOf(transfer)));
            var files = List.of(
                    JpegEncoder.encode(picture(), JpegEncoderOptions.builder().colorProfile(hdr).build()),
                    PngEncoder.encode(picture(), PngEncoderOptions.builder().colorProfile(hdr).build()),
                    WebpEncoder.encode(picture(), WebpEncoderOptions.lossless().toBuilder().colorProfile(hdr).build()),
                    WebpEncoder.encode(picture(), WebpEncoderOptions.lossy(80).toBuilder().colorProfile(hdr).build()),
                    TiffEncoder.encode(picture(), TiffEncoderOptions.builder().colorProfile(hdr).build()),
                    Jp2Encoder.encode(picture(), Jp2EncoderOptions.builder().colorProfile(hdr).build()));
            for (var file : files) {
                var decoded = decode(file, false);
                assertArrayEquals(hdr.toByteArray(), decoded.iccProfile().orElseThrow());
                var read = decoded.colorProfile().orElseThrow();
                assertTrue(read.isHighDynamicRange());
                assertEquals(hdr, read);
                // converting to sRGB tone-maps through the code points, as converting the profile itself does
                var expected = ImageOps.convertColor(decoded.picture(), hdr, ColorProfile.srgb());
                assertArrayEquals(rgb(expected), rgb(decode(file, true).picture()));
            }
        }

        @Test
        @DisplayName("a JPEG or a PNG iCCP holding a cicp-tagged PQ profile decodes as HDR")
        void hdrForeignProfile() {
            var skia = fetch("skia-icc-misc-Rec2020_PQ_cicp.icc",
                    "fb590ac84cc567c2486fd04c10a074ce08806be07a88a1fc137512b67f29678a",
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/icc_profiles/misc/Rec2020_PQ_cicp.icc");
            var profile = ColorProfile.parse(skia);
            for (var file : List.of(JpegEncoder.encode(picture(), JpegEncoderOptions.builder().colorProfile(profile).build()),
                    PngEncoder.encode(picture(), PngEncoderOptions.builder().colorProfile(profile).build()))) {
                var read = decode(file, false).colorProfile().orElseThrow();
                assertTrue(read.isHighDynamicRange());
                assertEquals(TransferCharacteristics.PQ, read.colorInfo().orElseThrow().transfer());
                assertEquals(ColorPrimaries.BT2020, read.colorInfo().orElseThrow().primaries());
            }
            // without the cICP chunk, the iCCP profile alone declares high dynamic range
            var hlg = ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED,
                    ColorPrimaries.BT2020, TransferCharacteristics.HLG)).toByteArray();
            var compressed = new java.io.ByteArrayOutputStream();
            try (var deflater = new java.util.zip.DeflaterOutputStream(compressed)) {
                deflater.write(hlg);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            var iccp = new java.io.ByteArrayOutputStream();
            iccp.writeBytes("HLG".getBytes(StandardCharsets.ISO_8859_1));
            iccp.write(0);
            iccp.write(0);
            iccp.writeBytes(compressed.toByteArray());
            var only = PngChunks.withChunk(PngEncoder.encode(picture()), "iCCP", iccp.toByteArray());
            assertEquals(TransferCharacteristics.HLG,
                    decode(only, false).colorProfile().orElseThrow().colorInfo().orElseThrow().transfer());
        }
    }
}
