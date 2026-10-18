package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameEncoder;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the VP8 key-frame encoder: byte identity with cwebp over sizes, contents and settings,
 * decodability by dwebp and ffmpeg, encoder/decoder drift, the rate-distortion curve, and edge
 * cases.
 */
@DisplayName("Vp8.encodeKeyFrame")
class Vp8KeyFrameEncoderTest {
    static final String TESTSRC = "testsrc2=size=320x240:rate=1";
    static final String MANDELBROT = "mandelbrot=size=320x240:rate=1";
    static final String CELLAUTO = "cellauto=size=320x240:rate=1:rule=30,format=yuv420p";
    static final String NOISE = "color=c=gray:size=320x240:rate=1,noise=alls=60:allf=u";

    static byte[] decodeOurs(byte[] vp8) {
        return Ivf.i420(Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT));
    }

    @Nested
    @DisplayName("is byte-identical to cwebp fed the same YUV")
    class Exact {
        static Stream<Arguments> contents() {
            var arguments = new ArrayList<Arguments>();
            for (var source : List.of(MANDELBROT, CELLAUTO, NOISE)) {
                for (var q : new int[]{10, 40, 70, 95}) {
                    for (var m : new int[]{0, 2, 4, 6}) {
                        arguments.add(Arguments.of(source, q, m));
                    }
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} q{1} m{2}")
        @MethodSource("contents")
        @DisplayName("photographic, synthetic and noise contents")
        void contents(String source, int quality, int method) {
            Vp8EncoderExactTest.assertSameAsCwebp(source, 320, 240, new Vp8KeyFrameSettings(quality, method, 4, 60, 0, 1, 50));
        }

        static Stream<Arguments> sizes() {
            return Stream.of(Arguments.of(1, 1), Arguments.of(2, 3), Arguments.of(15, 17), Arguments.of(33, 17),
                    Arguments.of(16, 16), Arguments.of(17, 1), Arguments.of(1, 40), Arguments.of(257, 129));
        }

        @ParameterizedTest(name = "{0}x{1}")
        @MethodSource("sizes")
        @DisplayName("odd and tiny sizes at methods 1, 4 and 6")
        void sizes(int width, int height) {
            for (var m : new int[]{1, 4, 6}) {
                Vp8EncoderExactTest.assertSameAsCwebp(TESTSRC, width, height, new Vp8KeyFrameSettings(75, m, 4, 60, 0, 1, 50));
            }
        }

        static Stream<Arguments> settings() {
            return Stream.of(
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 1, 60, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 2, 60, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 3, 60, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 1, 1, 60, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 0, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 100, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 60, 3, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 60, 7, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 60, 0, 1, 0)),
                    Arguments.of(new Vp8KeyFrameSettings(75, 4, 4, 60, 0, 1, 100)),
                    Arguments.of(new Vp8KeyFrameSettings(30, 6, 4, 80, 5, 1, 100)),
                    Arguments.of(new Vp8KeyFrameSettings(97.5f, 5, 2, 20, 1, 1, 70)),
                    Arguments.of(new Vp8KeyFrameSettings(0, 0, 4, 60, 0, 1, 50)),
                    Arguments.of(new Vp8KeyFrameSettings(100, 6, 4, 60, 0, 1, 50))
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("settings")
        @DisplayName("segment, filter, sharpness and noise shaping settings")
        void settings(Vp8KeyFrameSettings options) {
            Vp8EncoderExactTest.assertSameAsCwebp(MANDELBROT, 320, 240, options);
        }
    }

    static Stream<Arguments> decodable() {
        var arguments = new ArrayList<Arguments>();
        for (var parts : new int[]{1, 2, 4, 8}) {
            for (var m : new int[]{0, 2, 3, 6}) {
                arguments.add(Arguments.of(new Vp8KeyFrameSettings(60, m, 4, 60, 0, parts, 50)));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decodable")
    @DisplayName("output decodes identically with dwebp, ffmpeg and our decoder, for every partition count")
    void decodable(Vp8KeyFrameSettings options) throws IOException {
        var yuv = WebpTools.yuv(MANDELBROT, 320, 240);
        var vp8 = Vp8.encodeKeyFrame(Vp8EncoderExactTest.picture(yuv, 320, 240), options);
        var webp = Ivf.webp(vp8);
        var ours = decodeOurs(vp8);
        assertArrayEquals(WebpTools.dwebpYuv(webp), ours, "dwebp");
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Files.createTempFile(Path.of("target"), "vp8enc-", ".webp");
        try {
            Files.write(file, webp);
            Oracle.assertDecodesCleanly(file);
            var ffmpeg = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i", file.toString(),
                    "-f", "rawvideo", "-pix_fmt", "yuv420p", "-"));
            assertArrayEquals(ffmpeg, ours, "ffmpeg");
        } finally {
            Files.deleteIfExists(file);
        }
        assertTrue(Quality.psnr(yuv, ours) > 30, "reasonable quality");
    }

    @ParameterizedTest(name = "method {0}")
    @MethodSource("methods")
    @DisplayName("the decoder reproduces the encoder's reconstruction exactly without loop filter")
    void noDrift(int method) {
        var width = 97;
        var height = 45;
        var yuv = WebpTools.yuv(MANDELBROT, width, height);
        var picture = Vp8EncoderExactTest.picture(yuv, width, height);
        var encoder = new Vp8KeyFrameEncoder(picture, new Vp8KeyFrameSettings(50, method, 4, 0, 0, 2, 50));
        encoder.captureReconstruction();
        var vp8 = encoder.encode();
        var decoded = Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT);
        var recon = encoder.reconstruction();
        var mbW = (width + 15) / 16;
        var mbH = (height + 15) / 16;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(recon[y * mbW * 16 + x], decoded.y()[y * decoded.yStride() + x], "luma " + x + "," + y);
            }
        }
        var uBase = mbW * mbH * 256;
        var vBase = uBase + mbW * mbH * 64;
        for (var y = 0; y < (height + 1) / 2; y++) {
            for (var x = 0; x < (width + 1) / 2; x++) {
                assertEquals(recon[uBase + y * mbW * 8 + x], decoded.u()[y * decoded.uvStride() + x], "u " + x + "," + y);
                assertEquals(recon[vBase + y * mbW * 8 + x], decoded.v()[y * decoded.uvStride() + x], "v " + x + "," + y);
            }
        }
    }

    static Stream<Integer> methods() {
        return Stream.of(0, 1, 2, 3, 4, 5, 6);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("curveSources")
    @DisplayName("rate-distortion curve at method 4 matches cwebp within 0.3 dB and sizes within 5%")
    void curve(String source) {
        var yuv = WebpTools.yuv(source, 320, 240);
        var picture = Vp8EncoderExactTest.picture(yuv, 320, 240);
        var qualities = new int[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
        var ourSize = new double[qualities.length];
        var ourPsnr = new double[qualities.length];
        var refSize = new double[qualities.length];
        var refPsnr = new double[qualities.length];
        for (var i = 0; i < qualities.length; i++) {
            var vp8 = Vp8.encodeKeyFrame(picture, Vp8KeyFrameSettings.quality(qualities[i]));
            ourSize[i] = vp8.length;
            ourPsnr[i] = Quality.psnr(yuv, decodeOurs(vp8));
            var ref = WebpTools.cwebpYuv(yuv, 320, 240, List.of("-q", Integer.toString(qualities[i]), "-m", "4"));
            refSize[i] = Ivf.vp8Chunk(ref).length;
            refPsnr[i] = Quality.psnr(yuv, WebpTools.dwebpYuv(ref));
            assertTrue(Math.abs(ourSize[i] - refSize[i]) <= 0.05 * refSize[i], "size at q" + qualities[i]);
        }
        for (var i = 0; i < qualities.length; i++) {
            var interpolated = interpolate(refSize, refPsnr, ourSize[i]);
            if (!Double.isNaN(interpolated)) {
                assertTrue(ourPsnr[i] >= interpolated - 0.3, "psnr at q" + qualities[i] + ": " + ourPsnr[i] + " vs " + interpolated);
            }
        }
    }

    static Stream<String> curveSources() {
        return Stream.of(MANDELBROT, NOISE, TESTSRC);
    }

    static double interpolate(double[] xs, double[] ys, double x) {
        for (var i = 0; i + 1 < xs.length; i++) {
            var x0 = xs[i];
            var x1 = xs[i + 1];
            if (x >= Math.min(x0, x1) && x <= Math.max(x0, x1)) {
                if (x0 == x1) {
                    return Math.max(ys[i], ys[i + 1]);
                }
                return ys[i] + (ys[i + 1] - ys[i]) * (x - x0) / (x1 - x0);
            }
        }
        return Double.NaN;
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {
        static YuvPicture filled(int width, int height, int value, long seed) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var random = new Random(seed);
            for (var plane : new byte[][]{picture.y(), picture.u(), picture.v()}) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (byte) (value >= 0 ? value : random.nextInt(256));
                }
            }
            return picture;
        }

        static Stream<Arguments> extremes() {
            var arguments = new ArrayList<Arguments>();
            for (var value : new int[]{0, 255, 128, -1}) {
                for (var q : new int[]{0, 100}) {
                    for (var m : new int[]{0, 6}) {
                        arguments.add(Arguments.of(value, q, m));
                    }
                }
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "value {0} q{1} m{2}")
        @MethodSource("extremes")
        @DisplayName("flat and random pictures at extreme settings decode like dwebp")
        void extremes(int value, int quality, int method) {
            var picture = filled(67, 35, value, 1234);
            var vp8 = Vp8.encodeKeyFrame(picture, new Vp8KeyFrameSettings(quality, method, 4, 60, 0, 1, 50));
            assertArrayEquals(WebpTools.dwebpYuv(Ivf.webp(vp8)), decodeOurs(vp8));
        }

        @Test
        @DisplayName("a picture with padded strides")
        void strides() {
            var yuv = WebpTools.yuv(MANDELBROT, 50, 30);
            var tight = Vp8EncoderExactTest.picture(yuv, 50, 30);
            var y = new byte[64 * 30];
            var u = new byte[40 * 15];
            var v = new byte[40 * 15];
            for (var r = 0; r < 30; r++) {
                System.arraycopy(tight.y(), r * 50, y, r * 64, 50);
            }
            for (var r = 0; r < 15; r++) {
                System.arraycopy(tight.u(), r * 25, u, r * 40, 25);
                System.arraycopy(tight.v(), r * 25, v, r * 40, 25);
            }
            var padded = YuvPicture.builder().size(50, 30).chromaFormat(ChromaFormat.YUV420)
                    .color(ColorInfo.BT601_LIMITED)
                    .luma(y, 64)
                    .chroma(u, v, 40)
                    .build();
            var options = Vp8KeyFrameSettings.quality(80);
            assertArrayEquals(Vp8.encodeKeyFrame(tight, options), Vp8.encodeKeyFrame(padded, options));
        }

        @Test
        @DisplayName("a 16383 pixel wide strip")
        void wide() {
            var picture = filled(16383, 2, -1, 99);
            var vp8 = Vp8.encodeKeyFrame(picture, new Vp8KeyFrameSettings(50, 2, 4, 60, 0, 1, 50));
            var decoded = Vp8.decodeKeyFrame(vp8, 0, vp8.length, MediaLimits.DEFAULT);
            assertEquals(16383, decoded.width());
            assertEquals(2, decoded.height());
        }

        @Test
        @DisplayName("rejects pictures that are not 4:2:0 or too large")
        void rejects() {
            var p444 = YuvPicture.allocate(16, 16, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> Vp8.encodeKeyFrame(p444, Vp8KeyFrameSettings.quality(75)));
            var gray = YuvPicture.allocate(16, 16, ChromaFormat.MONOCHROME, false, ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> Vp8.encodeKeyFrame(gray, Vp8KeyFrameSettings.quality(75)));
            var huge = YuvPicture.allocate(16384, 1, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            assertThrows(IllegalArgumentException.class, () -> Vp8.encodeKeyFrame(huge, Vp8KeyFrameSettings.quality(75)));
        }
    }

    @Nested
    @DisplayName("Vp8KeyFrameSettings")
    class Options {
        @Test
        @DisplayName("quality() uses cwebp's defaults")
        void defaults() {
            assertEquals(new Vp8KeyFrameSettings(42, 4, 4, 60, 0, 1, 50), Vp8KeyFrameSettings.quality(42));
        }

        @Test
        @DisplayName("rejects out-of-range values")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> Vp8KeyFrameSettings.quality(-1));
            assertThrows(IllegalArgumentException.class, () -> Vp8KeyFrameSettings.quality(100.5f));
            assertThrows(IllegalArgumentException.class, () -> Vp8KeyFrameSettings.quality(Float.NaN));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 7, 4, 60, 0, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, -1, 4, 60, 0, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 0, 60, 0, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 5, 60, 0, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 4, 101, 0, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 4, 60, 8, 1, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 4, 60, 0, 3, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 4, 60, 0, 16, 50));
            assertThrows(IllegalArgumentException.class, () -> new Vp8KeyFrameSettings(50, 4, 4, 60, 0, 1, 101));
        }
    }
}
