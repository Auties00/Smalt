package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the RGB that {@link ImageOps#toRgba} gives for the pictures {@link JpegDecoder} decodes, in their stored
 * subsampling, with libjpeg-turbo's {@code djpeg -dct int} output of the same files, at every size from 1x1 to 7x5:
 * the triangle filter of 4:2:0, 4:2:2 and 4:4:0 chroma, the replication libjpeg picks for horizontally halved chroma
 * planes at most 2 samples wide, and the edges.
 */
@DisplayName("JPEG chroma upsampling against djpeg")
class JpegChromaUpsamplingTest {
    private static final Tolerance DJPEG = Tolerance.exact();

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422", "YUV440"})
    @DisplayName("ImageOps.toRgba of the decoded planes equals djpeg's RGB at every small size")
    void smallSizes(ChromaFormat format) throws IOException {
        Oracle.assume(Oracle.Tool.DJPEG);
        var random = new SplittableRandom(format.ordinal());
        var directory = Scratch.directory("jpeg-chroma-upsampling");
        for (var width = 1; width <= 7; width++) {
            for (var height = 1; height <= 5; height++) {
                var source = YuvPicture.allocate(width, height, ChromaFormat.YUV444, false, ColorInfo.JPEG);
                for (var plane : new byte[][]{source.y(), source.u(), source.v()}) {
                    for (var i = 0; i < plane.length; i++) {
                        plane[i] = (byte) random.nextInt(256);
                    }
                }
                var jpeg = JpegEncoder.encode(source, JpegEncoderOptions.builder().quality(95).chromaFormat(format)
                        .build());
                var file = directory.resolve(format + "-" + width + "x" + height + ".jpg");
                Files.write(file, jpeg);
                var picture = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg)).picture();
                assertEquals(format, picture.chromaFormat());
                var rgba = ImageOps.toRgba(picture);
                var actual = new byte[3 * width * height];
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        System.arraycopy(rgba.pixels(), y * rgba.stride() + 4 * x, actual, 3 * (y * width + x), 3);
                    }
                }
                DJPEG.assertWithin(format + " " + width + "x" + height, djpegRgb(file, width, height), actual);
            }
        }
    }

    /** Returns the RGB samples libjpeg-turbo's djpeg decodes from a file, with its accurate integer IDCT. */
    private static byte[] djpegRgb(java.nio.file.Path file, int width, int height) {
        var ppm = Oracle.run(Oracle.Tool.DJPEG, Duration.ofMinutes(1), List.of("-dct", "int", "-pnm",
                file.toAbsolutePath().toString()));
        var header = ("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        if (!Arrays.equals(header, Arrays.copyOf(ppm, header.length))) {
            throw new UncheckedIOException(new IOException("unexpected djpeg header in " + file));
        }
        return Arrays.copyOfRange(ppm, header.length, ppm.length);
    }
}
