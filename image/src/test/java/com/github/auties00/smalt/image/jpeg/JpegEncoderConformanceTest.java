package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the encoder reproduces libjpeg's cjpeg byte for byte on the libjpeg-turbo and IJG
 * test images, using the MD5 sums of libjpeg-turbo's CMake test suite and a matrix of MD5 sums of
 * {@code cjpeg -dct int -baseline} (libjpeg-turbo 3.1.3) over crops, subsamplings, qualities,
 * Huffman optimization and restart intervals (cjpeg-md5.csv).
 */
class JpegEncoderConformanceTest {
    static List<Arguments> cjpegMatrix() {
        var cases = new ArrayList<Arguments>();
        try (var reader = new BufferedReader(new InputStreamReader(
                JpegEncoderConformanceTest.class.getResourceAsStream("cjpeg-md5.csv"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var f = line.split(",");
                cases.add(Arguments.of(Integer.parseInt(f[0]), Integer.parseInt(f[1]), ChromaFormat.valueOf(f[2]),
                        Integer.parseInt(f[3]), Boolean.parseBoolean(f[4]), Integer.parseInt(f[5]), f[6]));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return cases;
    }

    @ParameterizedTest(name = "{0}x{1} {2} q{3} optimize={4} restart={5}")
    @MethodSource("cjpegMatrix")
    @DisplayName("matches cjpeg -dct int byte for byte")
    void matchesCjpeg(int width, int height, ChromaFormat format, int quality, boolean optimize, int restart, String md5) {
        var ppm = parsePnm(JpegTestVectors.testorigPpm());
        var rgb = new byte[width * height * 3];
        for (var y = 0; y < height; y++) {
            System.arraycopy(ppm.pixels(), y * ppm.width() * 3, rgb, y * width * 3, width * 3);
        }
        var jpeg = JpegEncoder.encode(rgba(width, height, rgb), JpegEncoderOptions.builder().quality(quality)
                .chromaFormat(format).optimizeHuffman(optimize).restartInterval(restart).build());
        assertEquals(md5, md5(jpeg));
    }

    private static JpegEncoderOptions standard(ChromaFormat format) {
        return JpegEncoderOptions.builder().quality(75).chromaFormat(format).optimizeHuffman(false).build();
    }

    @Test
    @DisplayName("testorig.ppm at q75 4:2:0 equals libjpeg-turbo's testimgint.jpg (cjpeg -dct int)")
    void testimgint() {
        var ppm = parsePnm(JpegTestVectors.testorigPpm());
        var jpeg = JpegEncoder.encode(rgba(ppm.width(), ppm.height(), ppm.pixels()), standard(ChromaFormat.YUV420));
        assertEquals("9a68f56bc76e466aa7e52f415d0f4a5f", md5(jpeg));
        assertArrayEquals(JpegTestVectors.testimgint(), jpeg);
    }

    @Test
    @DisplayName("IJG testimg.ppm at q75 4:2:0 equals IJG's testimg.jpg")
    void ijgTestimg() {
        var ppm = parsePnm(JpegTestVectors.ijgTestimgPpm());
        var jpeg = JpegEncoder.encode(rgba(ppm.width(), ppm.height(), ppm.pixels()), standard(ChromaFormat.YUV420));
        assertArrayEquals(JpegTestVectors.ijgTestimgJpg(), jpeg);
    }

    @Test
    @DisplayName("grayscale q75 equals cjpeg -gray -dct int, and decodes to djpeg's reference")
    void gray() {
        var ppm = parsePnm(JpegTestVectors.testorigPpm());
        var jpeg = JpegEncoder.encode(rgba(ppm.width(), ppm.height(), ppm.pixels()), standard(ChromaFormat.MONOCHROME));
        assertEquals("72b51f894b8f4a10b3ee3066770aa38d", md5(jpeg));
        var decoded = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
        assertEquals("8d3596c56eace32f205deccc229aa5ed", md5(writePnm(decoded.width(), decoded.height(), 1, luma(decoded))));
    }

    @Test
    @DisplayName("4:4:4 q75 decodes to djpeg's reference with rows 1..6 skipped")
    void yuv444() {
        var ppm = parsePnm(JpegTestVectors.testorigPpm());
        var jpeg = JpegEncoder.encode(rgba(ppm.width(), ppm.height(), ppm.pixels()), standard(ChromaFormat.YUV444));
        var decoded = JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
        var rgb = toRgb(decoded, true);
        var rowBytes = decoded.width() * 3;
        var kept = new byte[rgb.length - 6 * rowBytes];
        System.arraycopy(rgb, 0, kept, 0, rowBytes);
        System.arraycopy(rgb, 7 * rowBytes, kept, rowBytes, rgb.length - 7 * rowBytes);
        assertEquals("5606f86874cf26b8fcee1117a0a436a6", md5(writePnm(decoded.width(), decoded.height() - 6, 3, kept)));
    }

    @Test
    @DisplayName("testorig.jpg decoded with rows 15..31 skipped matches djpeg -skip 15,31")
    void skip() {
        var decoded = JpegDecoder.decode(MediaSource.of(JpegTestVectors.testorig()), ImageDecoderOptions.defaults()).picture();
        var rgb = toRgb(decoded, true);
        var rowBytes = decoded.width() * 3;
        var kept = new byte[rgb.length - 17 * rowBytes];
        System.arraycopy(rgb, 0, kept, 0, 15 * rowBytes);
        System.arraycopy(rgb, 32 * rowBytes, kept, 15 * rowBytes, rgb.length - 32 * rowBytes);
        assertEquals("c4c65c1e43d7275cd50328a61e6534f0", md5(writePnm(decoded.width(), decoded.height() - 17, 3, kept)));
        assertFalse(Arrays.equals(rgb, kept));
    }
}
