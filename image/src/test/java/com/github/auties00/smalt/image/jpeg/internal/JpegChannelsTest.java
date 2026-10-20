package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegTransformOptions;
import com.github.auties00.smalt.image.jpeg.JpegTransforms;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the CMYK channel pictures of the JPEG decoder: the samples against the decoded component planes, the
 * Adobe inversion, the equality of their naive and color-managed conversions with the default decode paths,
 * lossless CMYK at 16 bits, and lossless transforms, which keep the coefficients and so the channel samples.
 */
@DisplayName("JPEG channel pictures")
class JpegChannelsTest {
    private static final ImageDecoderOptions KEEP = ImageDecoderOptions.builder().keepChannels(true).build();

    private static final int WIDTH = 45;

    private static final int HEIGHT = 29;

    static DecodedImage decode(byte[] jpeg, ImageDecoderOptions options) {
        return JpegDecoder.decode(MediaSource.of(jpeg), options);
    }

    static byte[] skia(String name, String sha256, String file) {
        try {
            return Files.readAllBytes(Conformance.fetch(name,
                    "https://raw.githubusercontent.com/google/skia/0c98530bf7d68e59147c2f1b06d8825c4302af8c/resources/images/"
                            + file, sha256));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] mandrill() {
        return skia("skia-image-mandrill_cmyk.jpg", "5a9f4a74035b1795575bad48bf2b54ed12987f2862feb4eb590badc51fdc4470",
                "mandrill_cmyk.jpg");
    }

    static byte[] cmyk() {
        return skia("skia-image-CMYK.jpg", "8d2abbcf9bd9460b54198e1939e518bd2ca92baee234a5767c86461dbe455ec1",
                "CMYK.jpg");
    }

    @Nested
    @DisplayName("synthetic streams")
    class Synthetic {
        @ParameterizedTest(name = "Adobe transform {0}")
        @ValueSource(ints = {-1, 0})
        @DisplayName("hold the decoded planes, inverted when an Adobe segment marks them inverted")
        void samples(int transform) {
            var writer = JpegStreamTest.fourComponents(new int[]{'C', 'M', 'Y', 'K'});
            writer.jfif = false;
            writer.adobeTransform = transform;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var planes = JpegStreamTest.planes(jpeg);
            var image = decode(jpeg, KEEP);
            var picture = assertInstanceOf(Channel8Picture.class, image.picture());
            assertEquals(PixelLayout.CMYK, picture.layout());
            assertEquals(WIDTH, picture.width());
            assertEquals(HEIGHT, picture.height());
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    for (var c = 0; c < 4; c++) {
                        var stored = planes.plane(c)[y * planes.stride(c) + x] & 0xFF;
                        var expected = transform >= 0 ? 255 - stored : stored;
                        assertEquals(expected, picture.pixels()[y * picture.stride() + 4 * x + c] & 0xFF);
                    }
                }
            }
            if (transform >= 0) {
                assertArrayEquals(JpegStreamTest.rgbaPixels(JpegStreamTest.decode(jpeg)),
                        ImageOps.toRgba(picture).pixels());
            }
            // without a CMYK profile, convertToSrgb still takes precedence and gives the default output
            var both = decode(jpeg, KEEP.toBuilder().convertToSrgb(true).build());
            assertFalse(both.picture() instanceof Channel8Picture);
            assertArrayEquals(JpegStreamTest.rgbaPixels(JpegStreamTest.decode(jpeg)),
                    JpegStreamTest.rgbaPixels(both.picture()));
        }

        @Test
        @DisplayName("convert YCCK to CMYK as libjpeg-turbo does and to RGBA as the default decode does")
        void ycck() {
            var writer = JpegStreamTest.fourComponents(new int[]{1, 2, 3, 4});
            writer.jfif = false;
            writer.adobeTransform = 2;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var planes = JpegStreamTest.planes(jpeg);
            var picture = (Channel8Picture) decode(jpeg, KEEP).picture();
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var rgb = JpegOutput.yccToRgb(planes.plane(0)[y * planes.stride(0) + x] & 0xFF,
                            planes.plane(1)[y * planes.stride(1) + x] & 0xFF,
                            planes.plane(2)[y * planes.stride(2) + x] & 0xFF);
                    var at = y * picture.stride() + 4 * x;
                    assertEquals(rgb >>> 16, picture.pixels()[at] & 0xFF);
                    assertEquals((rgb >>> 8) & 0xFF, picture.pixels()[at + 1] & 0xFF);
                    assertEquals(rgb & 0xFF, picture.pixels()[at + 2] & 0xFF);
                    assertEquals(255 - (planes.plane(3)[y * planes.stride(3) + x] & 0xFF), picture.pixels()[at + 3] & 0xFF);
                }
            }
            assertArrayEquals(JpegStreamTest.rgbaPixels(JpegStreamTest.decode(jpeg)), ImageOps.toRgba(picture).pixels());
        }

        @ParameterizedTest(name = "1/{0}")
        @ValueSource(ints = {1, 2, 4, 8})
        @DisplayName("decode subsampled components at reduced sizes like the default output")
        void subsampledReduced(int scale) {
            var planes = new byte[4][];
            int[] h = {2, 1, 1, 2};
            int[] v = {2, 1, 1, 2};
            for (var c = 0; c < 4; c++) {
                planes[c] = JpegStreamTest.plane(JpegFrame.ceilDiv((long) WIDTH * h[c], 2),
                        JpegFrame.ceilDiv((long) HEIGHT * v[c], 2), c);
            }
            var writer = TestJpegWriter.fromPlanes(WIDTH, HEIGHT, new int[]{1, 2, 3, 4}, h, v, planes, 90);
            writer.jfif = false;
            writer.adobeTransform = 0;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var size = ImageDecoderOptions.builder().minSize((WIDTH + scale - 1) / scale, (HEIGHT + scale - 1) / scale);
            var plain = (RgbaPicture) decode(jpeg, size.build()).picture();
            var kept = (Channel8Picture) decode(jpeg, size.keepChannels(true).build()).picture();
            assertEquals(plain.width(), kept.width());
            assertEquals(plain.height(), kept.height());
            assertArrayEquals(JpegStreamTest.rgbaPixels(plain), ImageOps.toRgba(kept).pixels());
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 12, 16})
        @DisplayName("decode lossless CMYK at 16 bits with high bit depth, and reduced otherwise")
        void lossless(int precision) {
            var width = 19;
            var height = 7;
            var samples = LosslessJpegTest.noise(width * height * 4, precision, precision);
            var jpeg = Lj92Encoder.encode(samples, width, height, 4, precision, 4, 0, 0);
            var max = (1 << precision) - 1;
            var wide = decode(jpeg, KEEP.toBuilder().highBitDepth(true).build()).picture();
            var narrow = assertInstanceOf(Channel8Picture.class, decode(jpeg, KEEP).picture());
            if (precision > 8) {
                var deep = assertInstanceOf(Channel16Picture.class, wide);
                assertEquals(PixelLayout.CMYK, deep.layout());
                for (var i = 0; i < samples.length; i++) {
                    assertEquals(Math.round(samples[i] * 65535.0 / max), deep.pixels()[i] & 0xFFFF);
                }
            } else {
                assertInstanceOf(Channel8Picture.class, wide);
            }
            for (var i = 0; i < samples.length; i++) {
                assertEquals(Math.round(samples[i] * 255.0 / max), narrow.pixels()[i] & 0xFF, "sample " + i);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("lossless transforms of an unsubsampled stream keep the coefficients: mirrors exactly, quarter"
                + " turns within the IDCT's rounding")
        void transforms(Orientation orientation) {
            var planes = new byte[4][];
            for (var c = 0; c < 4; c++) {
                planes[c] = JpegStreamTest.plane(48, 32, c + 20);
            }
            var writer = TestJpegWriter.fromPlanes(48, 32, new int[]{'C', 'M', 'Y', 'K'}, new int[]{1, 1, 1, 1},
                    new int[]{1, 1, 1, 1}, planes, 85);
            writer.jfif = false;
            writer.adobeTransform = 0;
            var jpeg = writer.sequential(new int[]{0, 1, 2, 3});
            var original = (Channel8Picture) decode(jpeg, KEEP).picture();
            var transformed = JpegTransforms.transform(MediaSource.of(jpeg), JpegTransformOptions.of(orientation));
            var picture = (Channel8Picture) decode(transformed, KEEP).picture();
            var expected = (Channel8Picture) ImageOps.orient(original, orientation);
            if (!orientation.swapsDimensions()) {
                assertArrayEquals(expected.pixels(), picture.pixels());
                return;
            }
            for (var i = 0; i < expected.pixels().length; i++) {
                assertEquals(expected.pixels()[i] & 0xFF, picture.pixels()[i] & 0xFF, 1, "sample " + i);
            }
        }

        @Test
        @DisplayName("leave gray, YCbCr and RGB images in their usual pictures")
        void otherColorSpaces() {
            var gray = TestJpegWriter.fromPlanes(16, 16, new int[]{1}, new int[]{1}, new int[]{1},
                    new byte[][]{JpegStreamTest.plane(16, 16, 3)}, 90).sequential(new int[]{0});
            assertFalse(decode(gray, KEEP).picture() instanceof Channel8Picture);
        }
    }

    @Nested
    @DisplayName("CMYK files")
    class RealFiles {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mandrill", "cmyk"})
        @DisplayName("convert to exactly the default naive and color-managed outputs")
        void conversions(String name) {
            var jpeg = name.equals("mandrill") ? mandrill() : cmyk();
            var plain = decode(jpeg, ImageDecoderOptions.defaults());
            var kept = decode(jpeg, KEEP);
            var picture = assertInstanceOf(Channel8Picture.class, kept.picture());
            assertArrayEquals(JpegStreamTest.rgbaPixels(plain.picture()), ImageOps.toRgba(picture).pixels());
            assertEquals(plain.iccProfile().isPresent(), kept.iccProfile().isPresent());
            if (kept.colorProfile().isPresent()) {
                assertEquals(ProfileColorSpace.CMYK, kept.colorProfile().get().colorSpace());
                var managed = decode(jpeg, ImageDecoderOptions.builder().convertToSrgb(true).build());
                var converted = ImageOps.toSrgb(kept);
                assertEquals(managed.colorProfile(), converted.colorProfile());
                assertArrayEquals(JpegStreamTest.rgbaPixels(managed.picture()),
                        JpegStreamTest.rgbaPixels(converted.picture()));
                var both = decode(jpeg, KEEP.toBuilder().convertToSrgb(true).build());
                assertArrayEquals(JpegStreamTest.rgbaPixels(managed.picture()),
                        JpegStreamTest.rgbaPixels(both.picture()), "convertToSrgb takes precedence");
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Orientation.class)
        @DisplayName("lossless transforms of a subsampled file keep the ink samples within the upsampling rounding")
        void transforms(Orientation orientation) {
            var jpeg = mandrill();
            var original = (Channel8Picture) decode(jpeg, KEEP).picture();
            var transformed = JpegTransforms.transform(MediaSource.of(jpeg), JpegTransformOptions.of(orientation));
            var picture = (Channel8Picture) decode(transformed, KEEP).picture();
            var expected = (Channel8Picture) ImageOps.orient(original, orientation);
            assertEquals(PixelLayout.CMYK, picture.layout());
            assertEquals(expected.width(), picture.width());
            var differences = 0;
            for (var i = 0; i < expected.pixels().length; i++) {
                var difference = Math.abs((expected.pixels()[i] & 0xFF) - (picture.pixels()[i] & 0xFF));
                // the triangle upsampling of the subsampled components rounds differently once the axes swap
                assertTrue(difference <= (orientation.swapsDimensions() ? 4 : 2), "sample " + i + " differs by "
                        + difference);
                differences += difference;
            }
            assertTrue(differences * 20 < expected.pixels().length, differences + " differences");
        }
    }
}
