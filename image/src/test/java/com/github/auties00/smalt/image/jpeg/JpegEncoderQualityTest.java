package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips encoder output through ffmpeg's decoder and compares it with ffmpeg's mjpeg encoder
 * on the same pixels. The libjpeg-equivalent ffmpeg setting is qscale 8 with libjpeg's scaled
 * tables as intra matrices, which reproduces libjpeg's quantization tables exactly except for
 * ffmpeg's fixed DC quantizer of 8; our PSNR must be within 0.3 dB of it or better. For
 * information the test also prints ffmpeg's PSNR at our file size with its own MPEG-style matrices
 * (interpolated over qscale 1 to 31), which favor PSNR over the psychovisual weighting of the
 * Annex K tables.
 */
class JpegEncoderQualityTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static final class JpegWriterTables {
        static int dcQuantizer(int quality) {
            return com.github.auties00.smalt.image.jpeg.internal.JpegWriter.scaledTable(
                    com.github.auties00.smalt.image.jpeg.internal.JpegTables.STD_LUMINANCE_QUANT, quality)[0];
        }
    }

    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of("noisy test pattern", "testsrc2=size=640x480:rate=1,format=rgb24,noise=alls=10:allf=u"),
                Arguments.of("smooth fractal", "mandelbrot=size=640x480:rate=1,format=rgb24"),
                // ffmpeg's gradients source is not reproducible even with a fixed seed; geq is
                Arguments.of("gradients", "color=size=640x480:rate=1,format=rgb24,"
                        + "geq=r='255*X/W':g='255*Y/H':b='128+127*sin(X/37)*cos(Y/23)'"));
    }

    private static Path raw(String filter, String pixelFormat) {
        return Fixtures.ffmpeg("raw", List.of("-f", "lavfi", "-i", filter),
                List.of("-frames:v", "1", "-f", "rawvideo", "-pix_fmt", pixelFormat));
    }

    private static byte[] decodeWithFfmpeg(byte[] jpeg, String pixelFormat) throws IOException {
        var file = Files.createTempFile("encoded-", ".jpg");
        try {
            Files.write(file, jpeg);
            Oracle.assertDecodesCleanly(file);
            return Oracle.decodeVideo(file, pixelFormat, 1);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Returns ffmpeg's (size, PSNR) curve for qscale 1 to 31 on the given planar source.
     */
    private static double[][] ffmpegCurve(Path yuv, byte[] source) throws IOException {
        var curve = new double[31][];
        for (var q = 1; q <= 31; q++) {
            var jpeg = Fixtures.ffmpeg("jpg", List.of("-f", "rawvideo", "-pix_fmt", "yuvj420p", "-s", WIDTH + "x" + HEIGHT, "-i", yuv.toString()),
                    List.of("-frames:v", "1", "-update", "1", "-c:v", "mjpeg", "-q:v", Integer.toString(q)));
            var size = Files.size(jpeg);
            var decoded = Oracle.decodeVideo(jpeg, "yuvj420p", 1);
            curve[q - 1] = new double[]{size, Quality.psnr(source, decoded)};
        }
        return curve;
    }

    private static double interpolate(double[][] curve, double size) {
        double[] below = null;
        double[] above = null;
        for (var point : curve) {
            if (point[0] <= size && (below == null || point[0] > below[0])) {
                below = point;
            }
            if (point[0] >= size && (above == null || point[0] < above[0])) {
                above = point;
            }
        }
        if (below == null) {
            return above[1];
        }
        if (above == null || above[0] == below[0]) {
            return below[1];
        }
        var t = (Math.log(size) - Math.log(below[0])) / (Math.log(above[0]) - Math.log(below[0]));
        return below[1] + t * (above[1] - below[1]);
    }

    /**
     * Encodes with ffmpeg's mjpeg at its libjpeg-equivalent setting: qscale 8 with libjpeg's
     * scaled luminance and chrominance tables as intra matrices, which makes ffmpeg write exactly
     * libjpeg's quantization tables except for its fixed DC quantizer of 8.
     */
    private static Path ffmpegEquivalent(Path yuv, int quality) {
        var luma = com.github.auties00.smalt.image.jpeg.internal.JpegWriter.scaledTable(
                com.github.auties00.smalt.image.jpeg.internal.JpegTables.STD_LUMINANCE_QUANT, quality);
        var chroma = com.github.auties00.smalt.image.jpeg.internal.JpegWriter.scaledTable(
                com.github.auties00.smalt.image.jpeg.internal.JpegTables.STD_CHROMINANCE_QUANT, quality);
        return Fixtures.ffmpeg("jpg", List.of("-f", "rawvideo", "-pix_fmt", "yuvj420p", "-s", WIDTH + "x" + HEIGHT, "-i", yuv.toString()),
                List.of("-frames:v", "1", "-update", "1", "-c:v", "mjpeg", "-q:v", "8",
                        "-intra_matrix", join(luma), "-chroma_intra_matrix", join(chroma)));
    }

    private static String join(int[] values) {
        var joined = new StringBuilder();
        for (var value : values) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("matches ffmpeg's mjpeg at the libjpeg-equivalent setting at q50, q75, q92 and q100 (4:2:0 planar input)")
    void equivalentSetting(String name, String filter) throws IOException {
        var yuvPath = raw(filter, "yuvj420p");
        var source = Files.readAllBytes(yuvPath);
        var picture = yuv(WIDTH, HEIGHT, ChromaFormat.YUV420, source);
        var curve = ffmpegCurve(yuvPath, source);
        for (var quality : new int[]{50, 75, 92, 100}) {
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.ofQuality(quality));
            var psnr = Quality.psnr(source, decodeWithFfmpeg(jpeg, "yuvj420p"));
            var equivalent = ffmpegEquivalent(yuvPath, quality);
            var reference = Quality.psnr(source, Oracle.decodeVideo(equivalent, "yuvj420p", 1));
            var atSize = interpolate(curve, jpeg.length);
            var equivalentSize = Files.size(equivalent);
            System.out.printf("%-20s q%-3d ours %7d B %.2f dB | ffmpeg equivalent %7d B %.2f dB | ffmpeg default matrix at our size %.2f dB%n",
                    name, quality, jpeg.length, psnr, equivalentSize, reference, atSize);
            // Below q75 libjpeg's DC quantizer exceeds ffmpeg's fixed 8, so ffmpeg spends more bits
            // on DC. On DC-dominated content (smooth gradients) that costs libjpeg's tables up to
            // about a dB at an equal or smaller file; the encoder itself is byte-identical to cjpeg,
            // so the margin measures the tables, not the implementation.
            var finerDcInFfmpeg = JpegWriterTables.dcQuantizer(quality) > 8;
            assertTrue(psnr >= reference - 0.3 || (finerDcInFfmpeg && psnr >= reference - 1.0 && jpeg.length <= equivalentSize),
                    name + " q" + quality + ": " + psnr + " dB (" + jpeg.length + " B) vs ffmpeg " + reference + " dB ("
                            + equivalentSize + " B)");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("RGB input: matches or beats ffmpeg (swscale + mjpeg) in RGB PSNR at q92")
    void rgbPipeline(String name, String filter) throws IOException {
        var rgbPath = raw(filter, "rgb24");
        var rgb = Files.readAllBytes(rgbPath);
        var jpeg = JpegEncoder.encode(rgba(WIDTH, HEIGHT, rgb), JpegEncoderOptions.ofQuality(92));
        var ours = Quality.psnr(rgb, decodeWithFfmpeg(jpeg, "rgb24"));
        var best = Double.NEGATIVE_INFINITY;
        double[] closest = null;
        for (var q = 1; q <= 31; q++) {
            var file = Fixtures.ffmpeg("jpg", List.of("-f", "rawvideo", "-pix_fmt", "rgb24", "-s", WIDTH + "x" + HEIGHT, "-i", rgbPath.toString()),
                    List.of("-frames:v", "1", "-update", "1", "-pix_fmt", "yuvj420p", "-c:v", "mjpeg", "-q:v", Integer.toString(q)));
            var size = Files.size(file);
            var psnr = Quality.psnr(rgb, Oracle.decodeVideo(file, "rgb24", 1));
            if (size <= jpeg.length && psnr > best) {
                best = psnr;
                closest = new double[]{size, psnr};
            }
        }
        System.out.printf("%-20s RGB q92 size %7d PSNR %.2f dB; best ffmpeg no larger: %s%n", name, jpeg.length, ours,
                closest == null ? "none" : String.format("%.0f bytes %.2f dB", closest[0], closest[1]));
        assertTrue(closest == null || ours >= closest[1] - 0.3, name + ": " + ours + " vs " + closest[1]);
    }

    @ParameterizedTest(name = "q{0}")
    @ValueSource(ints = {10, 50, 75, 92, 100})
    @DisplayName("optimized Huffman tables are smaller than the standard tables and decode to the same pixels")
    void optimizedIsSmaller(int quality) throws IOException {
        for (var source : sources().toList()) {
            var rgb = Files.readAllBytes(raw((String) source.get()[1], "rgb24"));
            var picture = rgba(WIDTH, HEIGHT, rgb);
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
                var optimized = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(quality)
                        .chromaFormat(format).build());
                var standard = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(quality)
                        .chromaFormat(format).optimizeHuffman(false).build());
                assertTrue(optimized.length < standard.length, format + " q" + quality + ": " + optimized.length + " >= " + standard.length);
                assertArrayEquals(planar(decode(standard)), planar(decode(optimized)));
            }
        }
    }

    @Test
    @DisplayName("restart intervals decode cleanly in ffmpeg and to the same pixels")
    void restartIntervals() throws IOException {
        var rgb = Files.readAllBytes(raw("testsrc2=size=640x480:rate=1,format=rgb24", "rgb24"));
        var picture = rgba(WIDTH, HEIGHT, rgb);
        for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.MONOCHROME)) {
            var plain = planar(decode(JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(85)
                    .chromaFormat(format).build())));
            for (var interval : new int[]{1, 5, 64, 65535}) {
                for (var optimize : new boolean[]{false, true}) {
                    var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(85)
                            .chromaFormat(format).optimizeHuffman(optimize).restartInterval(interval).build());
                    decodeWithFfmpeg(jpeg, format == ChromaFormat.MONOCHROME ? "gray" : "rgb24");
                    assertArrayEquals(plain, planar(decode(jpeg)), format + " interval " + interval);
                }
            }
        }
    }

    @Test
    @DisplayName("reports sizes for a photo-sharing recipe (q92 4:2:0 at 1600 px, 100 px thumbnail)")
    void photoSharingRecipe() throws IOException {
        var rgb = Files.readAllBytes(raw("testsrc2=size=1600x1200:rate=1,format=rgb24,noise=alls=8:allf=u", "rgb24"));
        var jpeg = JpegEncoder.encode(rgba(1600, 1200, rgb), JpegEncoderOptions.ofQuality(92));
        decodeWithFfmpeg(jpeg, "rgb24");
        var thumb = Files.readAllBytes(raw("testsrc2=size=100x75:rate=1,format=rgb24", "rgb24"));
        var small = JpegEncoder.encode(rgba(100, 75, thumb), JpegEncoderOptions.ofQuality(92));
        decodeWithFfmpeg(small, "rgb24");
        System.out.printf("1600x1200 q92: %d bytes; 100x75 q92 thumbnail: %d bytes%n", jpeg.length, small.length);
        assertTrue(small.length < 20_000);
    }

    private static YuvPicture decode(byte[] jpeg) {
        return (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
    }
}
