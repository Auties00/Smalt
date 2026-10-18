package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks reduced-size decoding: the chosen scale, the output dimensions (rounded up), and the
 * fidelity of 1/2, 1/4 and 1/8 decodes against a box downscale of the full decode, for every
 * subsampling, odd sizes, progressive streams and non-YCbCr outputs.
 */
class ReducedSizeTest {
    private static byte[] source(int width, int height) throws IOException {
        var path = Fixtures.ffmpeg("raw", List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=1,format=rgb24,gblur=sigma=1.5"),
                List.of("-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24"));
        return Files.readAllBytes(path);
    }

    private static RgbaPicture rgba(int width, int height, byte[] rgb) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            picture.pixels()[4 * i] = rgb[3 * i];
            picture.pixels()[4 * i + 1] = rgb[3 * i + 1];
            picture.pixels()[4 * i + 2] = rgb[3 * i + 2];
            picture.pixels()[4 * i + 3] = (byte) 255;
        }
        return picture;
    }

    static Stream<Arguments> inputs() throws IOException {
        var cases = new ArrayList<Arguments>();
        for (var size : new int[][]{{640, 480}, {227, 149}, {97, 31}}) {
            var picture = rgba(size[0], size[1], source(size[0], size[1]));
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
                var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(92).chromaFormat(format)
                        .restartInterval(format == ChromaFormat.YUV422 ? 3 : 0).build());
                cases.add(Arguments.of(size[0] + "x" + size[1] + " " + format + " baseline", jpeg));
                if (size[0] == 227) {
                    var coefficients = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients();
                    var progressive = TestJpegWriter.of(coefficients)
                            .progressive(TestJpegWriter.simpleProgression(format.hasChroma() ? 3 : 1, format.hasChroma()));
                    cases.add(Arguments.of(size[0] + "x" + size[1] + " " + format + " progressive", progressive));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("1/2, 1/4 and 1/8 decodes have ceil dimensions and match a box downscale of the full decode")
    void matchesDownscale(String name, byte[] jpeg) {
        var full = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults());
        var fullPicture = (YuvPicture) full.picture();
        var width = fullPicture.width();
        var height = fullPicture.height();
        for (var shift = 1; shift <= 3; shift++) {
            var scale = 1 << shift;
            var expectedWidth = (width + scale - 1) / scale;
            var expectedHeight = (height + scale - 1) / scale;
            var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.ofMinimumSize(expectedWidth, expectedHeight));
            var reduced = (YuvPicture) decoded.picture();
            assertEquals(expectedWidth, reduced.width(), name + " 1/" + scale);
            assertEquals(expectedHeight, reduced.height(), name + " 1/" + scale);
            assertEquals(width, decoded.sourceWidth());
            assertEquals(height, decoded.sourceHeight());
            assertEquals(fullPicture.chromaFormat(), reduced.chromaFormat());
            var expected = box(fullPicture.y(), fullPicture.yStride(), width, height, scale);
            var actual = tight(reduced.y(), reduced.yStride(), reduced.width(), reduced.height());
            var psnr = Quality.psnr(expected, actual);
            assertTrue(psnr >= 35, name + " 1/" + scale + " luma PSNR " + psnr);
            if (fullPicture.chromaFormat().hasChroma()) {
                var chroma = box(fullPicture.u(), fullPicture.uvStride(), fullPicture.chromaWidth(), fullPicture.chromaHeight(), scale);
                var chromaActual = tight(reduced.u(), reduced.uvStride(), reduced.chromaWidth(), reduced.chromaHeight());
                assertTrue(Quality.psnr(chroma, chromaActual) >= 35, name + " 1/" + scale + " chroma");
            }
        }
    }

    @Test
    @DisplayName("picks the smallest scale that still covers the hint, and full size for a zero or oversized hint")
    void scaleChoice() throws IOException {
        var jpeg = JpegEncoder.encode(rgba(640, 480, source(640, 480)), JpegEncoderOptions.ofQuality(80));
        assertEquals(640, width(jpeg, 0, 0));
        assertEquals(640, width(jpeg, 641, 0));
        assertEquals(640, width(jpeg, 321, 1));
        assertEquals(320, width(jpeg, 320, 240));
        assertEquals(640, width(jpeg, 1, 241));
        assertEquals(320, width(jpeg, 1, 240));
        assertEquals(320, width(jpeg, 161, 0));
        assertEquals(160, width(jpeg, 160, 0));
        assertEquals(80, width(jpeg, 1, 1));
        assertEquals(80, width(jpeg, 80, 60));
        assertEquals(80, width(jpeg, 0, 60));
    }

    private static int width(byte[] jpeg, int minWidth, int minHeight) {
        return JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.ofMinimumSize(minWidth, minHeight)).picture().width();
    }

    @Test
    @DisplayName("reduces RGB, CMYK and upsampled layouts too")
    void otherOutputs() {
        var planes = new byte[][]{JpegStreamTest.plane(45, 29, 1), JpegStreamTest.plane(45, 29, 2), JpegStreamTest.plane(45, 29, 3),
                JpegStreamTest.plane(45, 29, 4)};
        var writer = TestJpegWriter.fromPlanes(45, 29, new int[]{1, 2, 3, 4}, new int[]{1, 1, 1, 1}, new int[]{1, 1, 1, 1}, planes, 90);
        writer.jfif = false;
        writer.adobeTransform = 0;
        var cmyk = writer.sequential(new int[]{0, 1, 2, 3});
        for (var shift = 0; shift <= 3; shift++) {
            var scale = 1 << shift;
            Picture picture = JpegDecoder.decode(MediaSource.of(cmyk), ImageDecoderOptions.ofMinimumSize((45 + scale - 1) / scale, (29 + scale - 1) / scale)).picture();
            assertInstanceOf(RgbaPicture.class, picture);
            assertEquals((45 + scale - 1) / scale, picture.width());
            assertEquals((29 + scale - 1) / scale, picture.height());
        }
        var odd = TestJpegWriter.fromPlanes(45, 29, new int[]{1, 2, 3}, new int[]{4, 1, 1}, new int[]{1, 1, 1},
                new byte[][]{JpegStreamTest.plane(45, 29, 1), JpegStreamTest.plane(12, 29, 2), JpegStreamTest.plane(12, 29, 3)}, 90)
                .sequential(new int[]{0, 1, 2});
        for (var shift = 0; shift <= 3; shift++) {
            var scale = 1 << shift;
            var picture = (YuvPicture) JpegDecoder.decode(MediaSource.of(odd), ImageDecoderOptions.ofMinimumSize((45 + scale - 1) / scale, 1)).picture();
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            assertEquals((45 + scale - 1) / scale, picture.width());
        }
    }

    /**
     * Averages {@code scale x scale} boxes of a plane, clipping boxes at the edges.
     */
    static byte[] box(byte[] plane, int stride, int width, int height, int scale) {
        var outWidth = (width + scale - 1) / scale;
        var outHeight = (height + scale - 1) / scale;
        var out = new byte[outWidth * outHeight];
        for (var y = 0; y < outHeight; y++) {
            for (var x = 0; x < outWidth; x++) {
                var sum = 0;
                var count = 0;
                for (var dy = 0; dy < scale && y * scale + dy < height; dy++) {
                    for (var dx = 0; dx < scale && x * scale + dx < width; dx++) {
                        sum += plane[(y * scale + dy) * stride + x * scale + dx] & 0xFF;
                        count++;
                    }
                }
                out[y * outWidth + x] = (byte) ((sum + count / 2) / count);
            }
        }
        return out;
    }

    static byte[] tight(byte[] plane, int stride, int width, int height) {
        var out = new byte[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return out;
    }
}
