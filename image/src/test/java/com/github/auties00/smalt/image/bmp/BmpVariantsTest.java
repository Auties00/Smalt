package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.image.tiff.internal.CcittFaxEncoder;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the BMP variants beyond the common layouts, on hand-built files: 64-bit linear pixels, OS/2 Huffman 1D
 * (coded with the TIFF fax encoder) and RLE24, embedded JPEG and PNG files, CMYK pixels and color tables, OS/2
 * bitmap arrays and calibrated color spaces.
 */
class BmpVariantsTest {
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final ImageDecoderOptions SRGB = ImageDecoderOptions.builder().convertToSrgb(true).build();

    private static RgbaPicture decode(byte[] data) {
        return (RgbaPicture) BmpDecoder.decode(MediaSource.of(data)).picture();
    }

    private static int pixel(RgbaPicture picture, int x, int y) {
        var at = y * picture.stride() + x * 4;
        var p = picture.pixels();
        return (p[at] & 0xFF) << 24 | (p[at + 1] & 0xFF) << 16 | (p[at + 2] & 0xFF) << 8 | (p[at + 3] & 0xFF);
    }

    private static byte[] le(int... values) {
        var out = new byte[values.length * 2];
        for (var i = 0; i < values.length; i++) {
            out[i * 2] = (byte) values[i];
            out[i * 2 + 1] = (byte) (values[i] >>> 8);
        }
        return out;
    }

    @Nested
    @DisplayName("64-bit pixels")
    class SixtyFour {
        private static double encode(double linear) {
            var v = Math.clamp(linear, 0, 1);
            return v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        }

        @Test
        @DisplayName("encode linear s2.13 color with the sRGB curve, clamp it, and scale straight alpha")
        void values() {
            // blue, green, red, alpha per pixel; 8192 is 1.0, -4096 is -0.5 and 12000 is above 1
            var stored = le(4096, 8192, -4096 & 0xFFFF, 8192, 0, 1000, 12000, 4096, 2048, 2048, 2048, 0, 100, 200, 300,
                    16000);
            var data = BmpFiles.of(2, 2, 64).pixels(stored).bytes();
            var picture = decode(data);
            assertTrue(picture.hasAlpha());
            var bottomLeft = pixel(picture, 0, 1);
            assertEquals(0, bottomLeft >>> 24, "negative red clamps to 0");
            assertEquals(255, bottomLeft >>> 16 & 0xFF);
            assertEquals((int) Math.round(encode(0.5) * 255), bottomLeft >>> 8 & 0xFF);
            assertEquals(255, bottomLeft & 0xFF);
            var bottomRight = pixel(picture, 1, 1);
            assertEquals(255, bottomRight >>> 24, "red above 1 clamps to 255");
            assertEquals((int) Math.round(encode(1000 / 8192.0) * 255), bottomRight >>> 16 & 0xFF);
            assertEquals(128, bottomRight & 0xFF, "alpha 0.5");
            assertEquals(0, pixel(picture, 0, 0) & 0xFF);
            assertEquals(255, pixel(picture, 1, 0) & 0xFF, "alpha above 1 clamps");
            var wide = assertInstanceOf(Rgba16Picture.class, BmpDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            assertEquals(Math.round(encode(0.5) * 65535), wide.pixels()[2 * 4 + 2] & 0xFFFF);
            assertEquals(32768, wide.pixels()[2 * 4 + 3 + 4] & 0xFFFF);
        }

        @Test
        @DisplayName("ignore alpha that is zero everywhere and report 16-bit depth and alpha when probed")
        void opaque() {
            var data = BmpFiles.of(1, 1, 64).pixels(le(8192, 0, 0, 0)).bytes();
            var picture = decode(data);
            assertFalse(picture.hasAlpha());
            assertEquals(0x0000FFFF, pixel(picture, 0, 0));
            assertTrue(BmpDecoder.probe(MediaSource.of(data)).hasAlpha());
            var probed = Media.probe(MediaSource.of(data)).image().orElseThrow();
            assertEquals(16, probed.bitDepth().orElseThrow());
            assertTrue(probed.hasAlpha());
        }
    }

    @Nested
    @DisplayName("OS/2 Huffman 1D")
    class Huffman {
        private static byte[] file(int width, int height, byte[] rows, boolean fillBits, int cut) {
            var rowBytes = (width + 7) / 8;
            var coded = new CcittFaxEncoder(CcittFaxEncoder.T4, false, fillBits, 2).encode(rows, 0, rowBytes, width, height);
            return BmpFiles.of(width, height, 1).header(64).compression(3).palette(new int[]{0xFFFFFF, 0x102030}, 2)
                    .pixels(Arrays.copyOf(coded, coded.length - cut)).bytes();
        }

        @Test
        @DisplayName("decode the modified Huffman rows bottom-up, white runs being color table index 0")
        void rows() {
            for (var fillBits : new boolean[]{false, true}) {
                var width = 37;
                var height = 13;
                var rowBytes = (width + 7) / 8;
                var random = new Random(fillBits ? 2 : 1);
                var rows = new byte[rowBytes * height];
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var black = y % 4 == 0 ? random.nextBoolean() : (x / (y + 1)) % 2 == 1;
                        if (black) {
                            rows[y * rowBytes + x / 8] |= (byte) (0x80 >>> (x % 8));
                        }
                    }
                }
                var picture = decode(file(width, height, rows, fillBits, 0));
                assertFalse(picture.hasAlpha());
                for (var stored = 0; stored < height; stored++) {
                    for (var x = 0; x < width; x++) {
                        var black = (rows[stored * rowBytes + x / 8] & (0x80 >>> (x % 8))) != 0;
                        assertEquals(black ? 0x102030FF : 0xFFFFFFFF, pixel(picture, x, height - 1 - stored),
                                "fill bits " + fillBits + " row " + stored + " x " + x);
                    }
                }
            }
        }

        @Test
        @DisplayName("leave the rows of truncated data transparent")
        void truncated() {
            var rows = new byte[5 * 40];
            for (var i = 0; i < rows.length; i += 3) {
                rows[i] = (byte) 0xF0;
            }
            var picture = decode(file(40, 40, rows, false, 60));
            assertTrue(picture.hasAlpha());
            assertEquals(0xFFFFFFFF, pixel(picture, 4, 39));
            assertEquals(0, pixel(picture, 4, 0));
        }
    }

    @Nested
    @DisplayName("OS/2 RLE24")
    class Rle24 {
        @Test
        @DisplayName("decode encoded and padded absolute runs, deltas, end of line and end of bitmap")
        void codes() {
            var stream = new byte[]{
                    3, 0x30, 0x20, 0x10,
                    0, 3, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0,
                    0, 0,
                    0, 2, 1, 0,
                    9, (byte) 0xAA, (byte) 0xBB, (byte) 0xCC,
                    0, 1};
            for (var header : new int[]{40, 64}) {
                var picture = decode(BmpFiles.of(6, 2, 24).header(header).compression(4).pixels(stream).bytes());
                assertTrue(picture.hasAlpha());
                assertEquals(0x102030FF, pixel(picture, 0, 1));
                assertEquals(0x102030FF, pixel(picture, 2, 1));
                assertEquals(0x030201FF, pixel(picture, 3, 1));
                assertEquals(0x090807FF, pixel(picture, 5, 1));
                assertEquals(0, pixel(picture, 0, 0), "skipped by the delta");
                assertEquals(0xCCBBAAFF, pixel(picture, 1, 0));
                assertEquals(0xCCBBAAFF, pixel(picture, 5, 0), "the run is clipped at the right edge");
                assertTrue(BmpDecoder.probe(MediaSource.of(BmpFiles.of(6, 2, 24).header(header).compression(4)
                        .pixels(stream).bytes())).hasAlpha());
            }
            var cut = decode(BmpFiles.of(6, 2, 24).header(64).compression(4).pixels(Arrays.copyOf(stream, 9)).bytes());
            assertEquals(0x102030FF, pixel(cut, 2, 1));
            assertEquals(0x030201FF, pixel(cut, 3, 1));
            assertEquals(0, pixel(cut, 4, 1), "an absolute run ending early stops decoding");
        }

        @Test
        @DisplayName("reject top-down RLE24 bitmaps")
        void topDown() {
            assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, -2, 24).header(64).compression(4)
                    .pixels(new byte[]{0, 1}).bytes()));
        }
    }

    @Nested
    @DisplayName("embedded JPEG and PNG files")
    class Embedded {
        private static byte[] file(int compression, byte[] payload, int width, int height) {
            return BmpFiles.of(width, height, 0).header(124).compression(compression).pixels(payload).bytes();
        }

        @Test
        @DisplayName("decode an embedded PNG file as PngDecoder does, alpha included")
        void png() {
            var source = BmpEncoderTest.picture(23, 17, 0, 4, true);
            var png = PngEncoder.encode(source);
            var data = file(5, png, 23, 17);
            var decoded = BmpDecoder.decode(MediaSource.of(data));
            assertEquals(ImageFormat.BMP, decoded.format());
            RasterTesting.assertSameRgba(RasterTesting.tight(source), RasterTesting.tight(decoded.picture()), 23, "png");
            var info = BmpDecoder.probe(MediaSource.of(data));
            assertTrue(info.hasAlpha());
            assertEquals(23, info.width());
            assertEquals(17, info.height());
            var trailing = RasterTesting.concat(data, new byte[100]);
            ByteArrays.put32le(trailing, 2, trailing.length);
            assertArrayEquals(RasterTesting.tight(decoded.picture()), RasterTesting.tight(decode(trailing)),
                    "the image size field bounds the embedded file");
        }

        @Test
        @DisplayName("decode an embedded JPEG file to RGBA as JpegDecoder and the conversion to RGBA do")
        void jpeg() {
            var source = BmpEncoderTest.picture(31, 9, 12, 5, false);
            var jpeg = JpegEncoder.encode(source, JpegEncoderOptions.ofQuality(90));
            var decoded = decode(file(4, jpeg, 31, 9));
            var expected = ImageOps.toRgba(JpegDecoder.decode(MediaSource.of(jpeg)).picture());
            RasterTesting.assertSameRgba(RasterTesting.tight(expected), RasterTesting.tight(decoded), 31, "jpeg");
            var hint = ImageDecoderOptions.builder().minSize(8, 3).build();
            var reduced = BmpDecoder.decode(MediaSource.of(file(4, jpeg, 31, 9)), hint);
            var reference = JpegDecoder.decode(MediaSource.of(jpeg), hint);
            assertTrue(reduced.picture().width() < 31, "the size hint reaches the JPEG decoder");
            assertEquals(31, reduced.sourceWidth());
            RasterTesting.assertSameRgba(RasterTesting.tight(ImageOps.toRgba(reference.picture())),
                    RasterTesting.tight(reduced.picture()), reduced.picture().width(), "reduced jpeg");
        }

        @Test
        @DisplayName("prefer the embedded file's own profile over the header's, and use the header's otherwise")
        void profiles() {
            var source = BmpEncoderTest.picture(4, 4, 0, 6, false);
            var plain = PngEncoder.encode(source);
            var tagged = PngEncoder.encode(source, PngEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build());
            var p3 = ColorProfile.displayP3().toByteArray();
            var fromHeader = BmpDecoder.decode(MediaSource.of(BmpFiles.of(4, 4, 0).header(124).compression(5).pixels(plain)
                    .profile(p3).bytes()));
            assertEquals(ColorProfile.displayP3(), fromHeader.colorProfile().orElseThrow());
            assertArrayEquals(p3, fromHeader.iccProfile().orElseThrow());
            var own = BmpDecoder.decode(MediaSource.of(BmpFiles.of(4, 4, 0).header(124).compression(5).pixels(tagged)
                    .profile(p3).bytes()));
            assertEquals(ColorProfile.adobeRgb(), own.colorProfile().orElseThrow());
            var converted = BmpDecoder.decode(MediaSource.of(BmpFiles.of(4, 4, 0).header(124).compression(5).pixels(plain)
                    .profile(p3).bytes()), SRGB);
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertArrayEquals(RasterTesting.tight(ImageOps.toSrgb(fromHeader).picture()),
                    RasterTesting.tight(converted.picture()));
        }

        @Test
        @DisplayName("reject a bitmap whose embedded file is missing or damaged")
        void missing() {
            var header = file(5, new byte[0], 4, 4);
            assertThrows(MediaException.Malformed.class, () -> decode(header));
            assertThrows(MediaException.class, () -> decode(file(4, new byte[]{(byte) 0xFF, (byte) 0xD8, 0, 0}, 4, 4)));
        }
    }

    @Nested
    @DisplayName("CMYK")
    class Cmyk {
        // the CMYK pixels of a 3x2 picture, cyan, magenta, yellow, black, top row first
        private static final int[][] INKS = {{0, 0, 0, 0}, {255, 0, 0, 0}, {0, 128, 64, 32}, {10, 20, 30, 40},
                {0, 0, 0, 255}, {200, 100, 50, 25}};

        private static byte[] stored32() {
            var out = new byte[2 * 12];
            for (var i = 0; i < 6; i++) {
                var stored = i < 3 ? 1 : 0;
                var at = stored * 12 + (i % 3) * 4;
                out[at] = (byte) INKS[i][3];
                out[at + 1] = (byte) INKS[i][2];
                out[at + 2] = (byte) INKS[i][1];
                out[at + 3] = (byte) INKS[i][0];
            }
            return out;
        }

        private static byte[] expectedInks() {
            var out = new byte[24];
            for (var i = 0; i < 6; i++) {
                for (var c = 0; c < 4; c++) {
                    out[i * 4 + c] = (byte) INKS[i][c];
                }
            }
            return out;
        }

        // color table entries packed as the file stores them: black in the low byte, then yellow, magenta, cyan
        private static int[] table() {
            var table = new int[6];
            for (var i = 0; i < 6; i++) {
                table[i] = INKS[i][3] | INKS[i][2] << 8 | INKS[i][1] << 16;
            }
            return table;
        }

        @Test
        @DisplayName("keep 32-bit CMYK pixels as a CMYK channel picture, or convert them naively")
        void pixels() {
            var data = BmpFiles.of(3, 2, 32).compression(11).pixels(stored32()).bytes();
            var kept = assertInstanceOf(Channel8Picture.class, BmpDecoder.decode(MediaSource.of(data), KEEP).picture());
            assertEquals(PixelLayout.CMYK, kept.layout());
            assertArrayEquals(expectedInks(), Arrays.copyOf(kept.pixels(), 24));
            var expected = ImageOps.toRgba(Channel8Picture.of(3, 2, 12, expectedInks(), PixelLayout.CMYK));
            var naive = decode(data);
            RasterTesting.assertSameRgba(RasterTesting.tight(expected), RasterTesting.tight(naive), 3, "naive");
            assertFalse(naive.hasAlpha());
            assertFalse(BmpDecoder.probe(MediaSource.of(data)).hasAlpha());
        }

        @Test
        @DisplayName("look indices up in a CMYK color table, run-length encoded or not")
        void tables() {
            // the color table holds the cyan in its fourth byte, which BmpFiles writes as 0x5A: patch it in
            var plain = BmpFiles.of(3, 2, 8).compression(11).palette(table(), 6)
                    .pixels(new byte[]{3, 4, 5, 0, 0, 1, 2, 0}).bytes();
            var rle8 = BmpFiles.of(3, 2, 8).compression(12).palette(table(), 6)
                    .pixels(new byte[]{0, 3, 3, 4, 5, 0, 0, 0, 1, 0, 1, 1, 1, 2, 0, 1}).bytes();
            var rle4 = BmpFiles.of(3, 2, 4).compression(13).palette(table(), 6)
                    .pixels(new byte[]{0, 3, 0x34, 0x50, 0, 0, 2, 0x01, 1, 0x20, 0, 1}).bytes();
            for (var data : new byte[][]{plain, rle8, rle4}) {
                for (var i = 0; i < 6; i++) {
                    data[54 + i * 4 + 3] = (byte) INKS[i][0];
                }
                var kept = assertInstanceOf(Channel8Picture.class, BmpDecoder.decode(MediaSource.of(data), KEEP).picture());
                assertArrayEquals(expectedInks(), Arrays.copyOf(kept.pixels(), 24));
            }
            var skipped = BmpFiles.of(3, 1, 8).compression(12).palette(table(), 6).pixels(new byte[]{1, 4, 0, 1}).bytes();
            var kept = (Channel8Picture) BmpDecoder.decode(MediaSource.of(skipped), KEEP).picture();
            assertArrayEquals(new byte[]{0, 0, 0, 0}, Arrays.copyOfRange(kept.pixels(), 4, 8), "a skipped pixel has no ink");
            assertFalse(decode(skipped).hasAlpha());
        }

        @Test
        @DisplayName("convert through an embedded CMYK profile on request and report it with the kept channels")
        void profile() throws IOException {
            var swop = Files.readAllBytes(Conformance.fetchListed("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc"));
            var data = BmpFiles.of(3, 2, 32).header(124).compression(11).pixels(stored32()).profile(swop).bytes();
            var kept = BmpDecoder.decode(MediaSource.of(data), KEEP);
            assertEquals(ProfileColorSpace.CMYK, kept.colorProfile().orElseThrow().colorSpace());
            var converted = BmpDecoder.decode(MediaSource.of(data), SRGB);
            assertArrayEquals(RasterTesting.tight(ImageOps.toSrgb(kept).picture()), RasterTesting.tight(converted.picture()));
            var naive = BmpDecoder.decode(MediaSource.of(data));
            assertTrue(naive.colorProfile().isEmpty(), "the CMYK profile does not describe the naive conversion");
            assertArrayEquals(swop, naive.iccProfile().orElseThrow());
        }

        @Test
        @DisplayName("reject CMYK with 16 or 24 bits per pixel")
        void invalid() {
            for (var bits : new int[]{16, 24}) {
                assertThrows(MediaException.Malformed.class, () -> decode(BmpFiles.of(2, 2, bits).compression(11)
                        .pixels(new byte[32]).bytes()));
            }
        }
    }

    @Nested
    @DisplayName("OS/2 bitmap arrays")
    class BitmapArrays {
        // links bitmaps (or other entries) into an array, moving each bitmap's file-relative pixel data offset
        static byte[] array(byte[]... entries) {
            var out = new ByteArrayOutputStream();
            for (var i = 0; i < entries.length; i++) {
                var start = out.size();
                var entry = entries[i].clone();
                if (entry[0] == 'B' && entry[1] == 'M') {
                    ByteArrays.put32le(entry, 10, ByteArrays.i32le(entry, 10) + start + 14);
                }
                var header = new byte[14];
                header[0] = 'B';
                header[1] = 'A';
                ByteArrays.put32le(header, 2, 14 + 14 + ByteArrays.i32le(entry, 14));
                ByteArrays.put32le(header, 6, i == entries.length - 1 ? 0 : start + 14 + entry.length);
                out.writeBytes(header);
                out.writeBytes(entry);
            }
            return out.toByteArray();
        }

        private static final byte[] ICON = {'C', 'I', 26, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 12, 0, 0, 0, 4, 0, 4, 0, 1, 0,
                1, 0};

        @Test
        @DisplayName("decode the largest bitmap, or the smallest the size hint accepts, and open every bitmap as a page")
        void choose() {
            var small = BmpEncoderTest.picture(8, 4, 0, 1, false);
            var large = BmpEncoderTest.picture(16, 8, 200, 2, false);
            var data = array(BmpEncoder.encode(small), ICON, BmpEncoder.encode(large));
            assertEquals(ImageFormat.BMP, ImageFormat.detect(data, data.length).orElseThrow());
            var decoded = decode(data);
            RasterTesting.assertSameRgba(RasterTesting.tight(large), RasterTesting.tight(decoded), 16, "largest");
            var hinted = BmpDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().minSize(5, 3).build());
            RasterTesting.assertSameRgba(RasterTesting.tight(small), RasterTesting.tight(hinted.picture()), 8, "hint");
            var info = BmpDecoder.probe(MediaSource.of(data));
            assertEquals(16, info.width());
            assertEquals(2, info.frameCount());
            try (var pages = Images.openPages(MediaSource.of(data))) {
                assertEquals(2, pages.pageCount());
                assertEquals(8, pages.probe(0).width());
                RasterTesting.assertSameRgba(RasterTesting.tight(small), RasterTesting.tight(pages.read(0).picture()), 8,
                        "page 0");
                RasterTesting.assertSameRgba(RasterTesting.tight(large), RasterTesting.tight(pages.read(1).picture()), 16,
                        "page 1");
                assertThrows(IndexOutOfBoundsException.class, () -> pages.read(2));
            }
            assertEquals(16, Images.probe(MediaSource.of(data)).width());
            // the probe of the core module, which reads the headers without this artifact, agrees
            var probed = Media.probe(MediaSource.of(data)).image().orElseThrow();
            assertEquals(16, probed.width());
            assertEquals(8, probed.height());
            assertEquals(2, probed.frameCount().orElseThrow());
        }

        @Test
        @DisplayName("reject arrays without bitmaps, with chains that loop, or with more bitmaps than the limit")
        void invalid() {
            var icons = array(ICON, ICON);
            var unsupported = assertThrows(MediaException.Unsupported.class, () -> decode(icons));
            assertEquals("bmp os/2 icon", unsupported.format());
            var cycle = array(BmpEncoder.encode(BmpEncoderTest.picture(2, 2, 0, 1, false)),
                    BmpEncoder.encode(BmpEncoderTest.picture(2, 2, 0, 2, false)));
            var second = ByteArrays.i32le(cycle, 6);
            ByteArrays.put32le(cycle, second + 6, 0);
            assertEquals(2, decode(cycle).width());
            ByteArrays.put32le(cycle, second + 6, second);
            assertThrows(MediaException.Malformed.class, () -> decode(cycle));
            var limited = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxFrames(1).build()).build();
            var two = array(BmpEncoder.encode(BmpEncoderTest.picture(2, 2, 0, 1, false)),
                    BmpEncoder.encode(BmpEncoderTest.picture(2, 2, 0, 2, false)));
            assertThrows(MediaException.LimitExceeded.class, () -> BmpDecoder.decode(MediaSource.of(two), limited));
        }
    }

    @Nested
    @DisplayName("color spaces")
    class ColorSpaces {
        private static int fixed30(double value) {
            return (int) Math.round(value * (1 << 30));
        }

        private static int fixed16(double value) {
            return (int) Math.round(value * 65536);
        }

        private static Chromaticity xy(int[] endpoints, int channel) {
            var x = endpoints[channel * 3] / (double) (1 << 30);
            var y = endpoints[channel * 3 + 1] / (double) (1 << 30);
            var z = endpoints[channel * 3 + 2] / (double) (1 << 30);
            return new Chromaticity(x / (x + y + z), y / (x + y + z));
        }

        @Test
        @DisplayName("build the profile of tristimulus endpoints, whose sum is the white, with a gamma per channel")
        void tristimulus() {
            // the D65 tristimulus values of the BT.709 primaries
            var endpoints = new int[]{fixed30(0.4124), fixed30(0.2126), fixed30(0.0193), fixed30(0.3576), fixed30(0.7152),
                    fixed30(0.1192), fixed30(0.1805), fixed30(0.0722), fixed30(0.9505)};
            var gammas = new int[]{fixed16(2.2), fixed16(1.8), fixed16(2.4)};
            var data = BmpFiles.of(2, 2, 24).header(108).colorSpace(0, endpoints, gammas).pixels(new byte[16]).bytes();
            var profile = BmpDecoder.decode(MediaSource.of(data)).colorProfile().orElseThrow();
            var x = (endpoints[0] + endpoints[3] + endpoints[6]) / (double) (1 << 30);
            var y = (endpoints[1] + endpoints[4] + endpoints[7]) / (double) (1 << 30);
            var z = (endpoints[2] + endpoints[5] + endpoints[8]) / (double) (1 << 30);
            var expected = ColorProfile.rgb(xy(endpoints, 0), xy(endpoints, 1), xy(endpoints, 2),
                    new Chromaticity(x / (x + y + z), y / (x + y + z)), gammas[0] / 65536.0, gammas[1] / 65536.0,
                    gammas[2] / 65536.0);
            assertEquals(expected, profile);
            assertTrue(Math.abs(x / (x + y + z) - 0.3127) < 0.001, "the white of these endpoints is D65");
        }

        @Test
        @DisplayName("take endpoints that each sum to 1 as chromaticities with a D65 white, and convert through them")
        void chromaticities() {
            var endpoints = new int[]{fixed30(0.64), fixed30(0.33), fixed30(0.03), fixed30(0.30), fixed30(0.60), fixed30(0.10),
                    fixed30(0.15), fixed30(0.06), fixed30(0.79)};
            var gammas = new int[]{fixed16(1), fixed16(1), fixed16(1)};
            var pixels = new byte[]{(byte) 128, (byte) 128, (byte) 128, 0};
            var data = BmpFiles.of(1, 1, 24).header(124).colorSpace(0, endpoints, gammas).pixels(pixels).bytes();
            var decoded = BmpDecoder.decode(MediaSource.of(data));
            var expected = ColorProfile.rgb(xy(endpoints, 0), xy(endpoints, 1), xy(endpoints, 2),
                    new Chromaticity(0.3127, 0.3290), 1, 1, 1);
            assertEquals(expected, decoded.colorProfile().orElseThrow());
            assertTrue(decoded.iccProfile().isEmpty());
            var converted = (RgbaPicture) BmpDecoder.decode(MediaSource.of(data), SRGB).picture();
            // linear 128/255 encodes to sRGB 188
            assertTrue(Math.abs((converted.pixels()[0] & 0xFF) - 188) <= 1, "converted " + (converted.pixels()[0] & 0xFF));
        }

        @Test
        @DisplayName("ignore zero, negative or degenerate calibrations, linked profiles and the sRGB and system spaces")
        void ignored() {
            var good = new int[]{fixed30(0.64), fixed30(0.33), fixed30(0.03), fixed30(0.30), fixed30(0.60), fixed30(0.10),
                    fixed30(0.15), fixed30(0.06), fixed30(0.79)};
            var ones = new int[]{fixed16(2.2), fixed16(2.2), fixed16(2.2)};
            var negative = good.clone();
            negative[4] = -negative[4];
            var degenerate = new int[]{fixed30(0.3), fixed30(0.3), fixed30(0.4), fixed30(0.3), fixed30(0.3), fixed30(0.4),
                    fixed30(0.3), fixed30(0.3), fixed30(0.4)};
            for (var spec : new Object[][]{{0, new int[9], ones}, {0, good, new int[3]}, {0, negative, ones},
                    {0, degenerate, ones}, {0x73524742, good, ones}, {0x57696E20, good, ones}}) {
                var data = BmpFiles.of(2, 2, 24).header(108).colorSpace((int) spec[0], (int[]) spec[1], (int[]) spec[2])
                        .pixels(new byte[16]).bytes();
                assertTrue(BmpDecoder.decode(MediaSource.of(data)).colorProfile().isEmpty(), Arrays.deepToString(spec));
            }
            var linked = BmpFiles.of(2, 2, 24).header(124).pixels(new byte[16]).profile("C:\\x.icc".getBytes()).bytes();
            ByteArrays.put32le(linked, 14 + 56, 0x4C494E4B);
            var decoded = BmpDecoder.decode(MediaSource.of(linked));
            assertTrue(decoded.iccProfile().isEmpty());
            assertTrue(decoded.colorProfile().isEmpty());
        }
    }
}
