package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Random;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the encoder API: option validation, the output marker layout, every planar input path
 * (other layouts, limited range, other matrices), strides, alpha handling, and tiny sizes.
 */
class JpegEncoderTest {
    private static RgbaPicture smooth(int width, int height, int seed) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, true);
        var phase = random.nextDouble() * 6;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                picture.pixels()[i] = (byte) (128 + 100 * Math.sin(x * 0.05 + phase));
                picture.pixels()[i + 1] = (byte) (128 + 100 * Math.cos(y * 0.07));
                picture.pixels()[i + 2] = (byte) ((x + y) * 2);
                picture.pixels()[i + 3] = (byte) random.nextInt(256);
            }
        }
        return picture;
    }

    private static YuvPicture decode(byte[] jpeg) {
        return (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("validates quality, restart interval and subsampling")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.ofQuality(-1));
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.ofQuality(101));
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().restartInterval(-1)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> JpegEncoderOptions.builder().restartInterval(65536).build());
            assertThrows(NullPointerException.class, () -> JpegEncoderOptions.builder().chromaFormat(null).build());
            assertEquals(JpegEncoderOptions.builder().quality(92).chromaFormat(ChromaFormat.YUV420)
                    .build(), JpegEncoderOptions.ofQuality(92));
            assertThrows(NullPointerException.class, () -> JpegEncoder.encode(null, JpegEncoderOptions.ofQuality(90)));
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(RgbaPicture.allocate(65536, 1,
                    false), JpegEncoderOptions.ofQuality(90)));
        }

        @Test
        @DisplayName("an ICC profile that needs more than 255 APP2 segments is LimitExceeded")
        void oversizedProfile() {
            var options = JpegEncoderOptions.builder().colorProfile(paddedProfile(255 * 65519 + 1)).build();
            assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class,
                    () -> JpegEncoder.encode(RgbaPicture.allocate(8, 8, false), options));
        }

        @Test
        @DisplayName("an ICC profile of exactly 255 APP2 segments round trips")
        void largestProfile() {
            var profile = paddedProfile(255 * 65519);
            var file = JpegEncoder.encode(RgbaPicture.allocate(8, 8, false), JpegEncoderOptions.builder()
                    .colorProfile(profile).build());
            assertArrayEquals(profile.toByteArray(), JpegDecoder.decode(MediaSource.of(file)).iccProfile()
                    .orElseThrow());
        }
    }

    /** An sRGB profile padded with unused bytes to a length, its header size field patched. */
    static com.github.auties00.smalt.image.color.ColorProfile paddedProfile(int length) {
        var srgb = com.github.auties00.smalt.image.color.ColorProfile.srgb().toByteArray();
        var bytes = java.util.Arrays.copyOf(srgb, length);
        java.nio.ByteBuffer.wrap(bytes).putInt(0, length);
        return com.github.auties00.smalt.image.color.ColorProfile.parse(bytes);
    }

    @Nested
    @DisplayName("output")
    class Output {
        @ParameterizedTest
        @EnumSource(ChromaFormat.class)
        @DisplayName("has JFIF 1.01 first and only DQT, SOF0, DHT, DRI, SOS and EOI after it")
        void markerLayout(ChromaFormat format) {
            for (var restart : new int[]{0, 2}) {
                for (var optimize : new boolean[]{false, true}) {
                    var jpeg = JpegEncoder.encode(smooth(50, 33, 1), JpegEncoderOptions.builder().quality(80)
                            .chromaFormat(format).optimizeHuffman(optimize).restartInterval(restart).build());
                    assertEquals(0xFF, jpeg[0] & 0xFF);
                    assertEquals(0xD8, jpeg[1] & 0xFF);
                    assertEquals(0xE0, jpeg[3] & 0xFF);
                    assertArrayEquals(new byte[]{'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0}, java.util.Arrays.copyOfRange(jpeg, 6, 20));
                    var at = 20;
                    var sawSof = false;
                    while (true) {
                        assertEquals(0xFF, jpeg[at] & 0xFF);
                        var marker = jpeg[at + 1] & 0xFF;
                        assertTrue(marker == 0xDB || marker == 0xC0 || marker == 0xC4 || marker == 0xDD || marker == 0xDA,
                                "marker " + Integer.toHexString(marker));
                        sawSof |= marker == 0xC0;
                        var length = ((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF);
                        at += 2 + length;
                        if (marker == 0xDA) {
                            break;
                        }
                    }
                    assertTrue(sawSof);
                    for (var i = at; i < jpeg.length - 2; i++) {
                        if (jpeg[i] == (byte) 0xFF) {
                            var next = jpeg[i + 1] & 0xFF;
                            assertTrue(next == 0 || (next >= 0xD0 && next <= 0xD7), "marker in scan " + Integer.toHexString(next));
                            i++;
                        }
                    }
                    assertEquals(0xFF, jpeg[jpeg.length - 2] & 0xFF);
                    assertEquals(0xD9, jpeg[jpeg.length - 1] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("ignores alpha and honors strides")
        void alphaAndStride() {
            var picture = smooth(40, 30, 2);
            var opaque = RgbaPicture.allocate(40, 30, false);
            var padded = new byte[30 * 200];
            for (var y = 0; y < 30; y++) {
                for (var x = 0; x < 40; x++) {
                    var i = (y * 40 + x) * 4;
                    System.arraycopy(picture.pixels(), i, opaque.pixels(), i, 3);
                    opaque.pixels()[i + 3] = (byte) 255;
                    System.arraycopy(picture.pixels(), i, padded, y * 200 + x * 4, 4);
                }
            }
            var strided = RgbaPicture.of(40, 30, 200, padded, true);
            var expected = JpegEncoder.encode(opaque, JpegEncoderOptions.ofQuality(85));
            assertArrayEquals(expected, JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(85)));
            assertArrayEquals(expected, JpegEncoder.encode(strided, JpegEncoderOptions.ofQuality(85)));
        }

        @Test
        @DisplayName("tiny and thin pictures in every layout decode cleanly in ffmpeg")
        void tinySizes() throws IOException {
            for (var size : new int[][]{{1, 1}, {1, 17}, {17, 1}, {2, 2}, {9, 9}, {15, 17}}) {
                for (var format : ChromaFormat.values()) {
                    var jpeg = JpegEncoder.encode(smooth(size[0], size[1], 3), JpegEncoderOptions.builder().quality(90)
                            .chromaFormat(format).restartInterval(1).build());
                    var decoded = decode(jpeg);
                    assertEquals(size[0], decoded.width());
                    assertEquals(size[1], decoded.height());
                    if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                        var file = Files.createTempFile("tiny-", ".jpg");
                        try {
                            Files.write(file, jpeg);
                            Oracle.assertDecodesCleanly(file);
                        } finally {
                            Files.deleteIfExists(file);
                        }
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("planar input")
    class Planar {
        @Test
        @DisplayName("codes a full-range BT.601 picture in the target layout as is")
        void directPath() {
            var source = decode(JpegEncoder.encode(smooth(96, 64, 4), JpegEncoderOptions.ofQuality(90)));
            var reencoded = decode(JpegEncoder.encode(source, JpegEncoderOptions.ofQuality(100)));
            assertEquals(ChromaFormat.YUV420, reencoded.chromaFormat());
            assertTrue(Quality.psnr(planar(source), planar(reencoded)) > 45);
        }

        @ParameterizedTest
        @EnumSource(ChromaFormat.class)
        @DisplayName("resamples any layout to any other")
        void anyLayout(ChromaFormat source) {
            var picture = decode(JpegEncoder.encode(smooth(96, 64, 5), JpegEncoderOptions.builder().quality(95)
                    .chromaFormat(source).build()));
            var reference = decode(JpegEncoder.encode(smooth(96, 64, 5), JpegEncoderOptions.builder().quality(95)
                    .chromaFormat(ChromaFormat.YUV444).build()));
            for (var target : ChromaFormat.values()) {
                var result = decode(JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(95)
                        .chromaFormat(target).build()));
                assertEquals(target, result.chromaFormat());
                assertTrue(Quality.psnr(luma(reference), luma(result)) > 35, source + " -> " + target);
                if (target.hasChroma() && source.hasChroma()) {
                    var expected = JpegTestSupport.toRgb(reference, true);
                    assertTrue(Quality.psnr(expected, JpegTestSupport.toRgb(result, true)) > 30, source + " -> " + target);
                }
                if (target.hasChroma() && !source.hasChroma()) {
                    assertTrue(java.util.Arrays.stream(box(cb(result))).allMatch(v -> Math.abs(v - 128) <= 1));
                }
            }
        }

        private static int[] box(byte[] plane) {
            var values = new int[plane.length];
            for (var i = 0; i < plane.length; i++) {
                values[i] = plane[i] & 0xFF;
            }
            return values;
        }

        @Test
        @DisplayName("expands limited-range BT.601 to full range")
        void limitedRange() {
            var width = 32;
            var height = 16;
            var limited = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            java.util.Arrays.fill(limited.y(), (byte) 16);
            java.util.Arrays.fill(limited.y(), 0, width * height / 2, (byte) 235);
            var result = decode(JpegEncoder.encode(limited, JpegEncoderOptions.ofQuality(100)));
            assertEquals(255, result.y()[0] & 0xFF, 1);
            assertEquals(0, result.y()[(height - 1) * result.yStride()] & 0xFF, 1);
            assertEquals(128, result.u()[0] & 0xFF, 1);
        }

        @Test
        @DisplayName("converts BT.709 limited-range pictures through RGB")
        void bt709() {
            var rgb = smooth(64, 48, 6);
            var picture = YuvPicture.allocate(64, 48, ChromaFormat.YUV444, false, ColorInfo.BT709_LIMITED);
            for (var i = 0; i < 64 * 48; i++) {
                var r = (rgb.pixels()[4 * i] & 0xFF) / 255.0;
                var g = (rgb.pixels()[4 * i + 1] & 0xFF) / 255.0;
                var b = (rgb.pixels()[4 * i + 2] & 0xFF) / 255.0;
                var y = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                picture.y()[i] = (byte) Math.round(16 + 219 * y);
                picture.u()[i] = (byte) Math.round(128 + 224 * (b - y) / 1.8556);
                picture.v()[i] = (byte) Math.round(128 + 224 * (r - y) / 1.5748);
            }
            var viaRgb = JpegTestSupport.toRgb(decode(JpegEncoder.encode(picture, JpegEncoderOptions.builder()
                    .quality(98).chromaFormat(ChromaFormat.YUV444).build())), true);
            var expected = new byte[64 * 48 * 3];
            for (var i = 0; i < 64 * 48; i++) {
                System.arraycopy(rgb.pixels(), 4 * i, expected, 3 * i, 3);
            }
            assertTrue(Quality.psnr(expected, viaRgb) > 35);
        }

        @Test
        @DisplayName("rejects matrices without a conversion to BT.601")
        void unsupportedMatrix() {
            var picture = YuvPicture.allocate(16, 16, ChromaFormat.YUV420, false,
                    ColorInfo.BT709_LIMITED.withMatrix(MatrixCoefficients.ICTCP));
            assertThrows(IllegalArgumentException.class, () -> JpegEncoder.encode(picture,
                    JpegEncoderOptions.ofQuality(90)));
            var identity = YuvPicture.allocate(16, 16, ChromaFormat.YUV444, false,
                    ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL));
            assertDoesNotThrow(() -> JpegEncoder.encode(identity, JpegEncoderOptions.ofQuality(90)));
        }

        @Test
        @DisplayName("codes the planes the decoder hands out, whose strides exceed the width")
        void paddedPlanes() {
            var decoded = decode(JpegEncoder.encode(smooth(45, 29, 7), JpegEncoderOptions.ofQuality(90)));
            assertTrue(decoded.yStride() > decoded.width());
            var tight = JpegTestSupport.yuv(45, 29, ChromaFormat.YUV420, planar(decoded));
            assertArrayEquals(JpegEncoder.encode(tight, JpegEncoderOptions.ofQuality(90)), JpegEncoder.encode(decoded,
                    JpegEncoderOptions.ofQuality(90)));
        }
    }
}
