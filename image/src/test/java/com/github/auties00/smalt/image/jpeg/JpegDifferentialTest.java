package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes JPEG files made by ffmpeg's mjpeg encoder (every subsampling it supports, odd and
 * extreme sizes, quantizer scales 2 to 31, optimal and default Huffman tables, restart markers) and gray files made
 * by this module's encoder, and compares every plane with ffmpeg's decode. Sliced encodes carry
 * restart markers. ffmpeg's mjpeg IDCT
 * differs from libjpeg's by up to a few levels, hence the PSNR and maximum-difference bounds; the luma
 * plane also equals libjpeg-turbo's {@code djpeg -dct int -grayscale} output exactly, the decoder's
 * bit-exactness promise.
 */
class JpegDifferentialTest {
    /** ffmpeg's IDCT is not libjpeg's; T.81 leaves the inverse DCT's rounding to the decoder. */
    private static final Tolerance FFMPEG = Tolerance.psnr(45).and(Tolerance.maxAbs(3));

    /** The decoder ports libjpeg-turbo's accurate integer IDCT, so its planes equal djpeg's. */
    private static final Tolerance DJPEG = Tolerance.exact();

    static Stream<Arguments> ffmpegFiles() {
        var cases = new ArrayList<Arguments>();
        var sizes = new int[][]{{1, 1}, {1, 17}, {17, 1}, {33, 65}, {4097, 3}, {227, 149}, {640, 480}};
        for (var format : List.of("yuvj420p", "yuvj422p", "yuvj444p")) {
            for (var size : sizes) {
                for (var q : new int[]{2, 7, 16, 31}) {
                    cases.add(Arguments.of(format, size[0], size[1], q, "optimal"));
                }
            }
            cases.add(Arguments.of(format, 227, 149, 4, "default"));
            cases.add(Arguments.of(format, 227, 149, 5, "slices"));
            cases.add(Arguments.of(format, 640, 480, 3, "slices"));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}x{2} q:v {3} huffman {4}")
    @MethodSource("ffmpegFiles")
    @DisplayName("matches ffmpeg's decode of ffmpeg-encoded files")
    void ffmpegEncoded(String pixelFormat, int width, int height, int q, String huffman) throws IOException {
        var path = Fixtures.ffmpeg("jpg", List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height
                        + ":rate=1,format=rgb24,noise=alls=12:allf=u"),
                huffman.equals("slices")
                        ? List.of("-frames:v", "1", "-update", "1", "-pix_fmt", pixelFormat, "-c:v", "mjpeg", "-q:v", Integer.toString(q),
                        "-slices", "8", "-threads", "8")
                        : List.of("-frames:v", "1", "-update", "1", "-pix_fmt", pixelFormat, "-c:v", "mjpeg", "-q:v", Integer.toString(q),
                        "-huffman", huffman));
        if (huffman.equals("slices")) {
            var bytes = Files.readAllBytes(path);
            var restarts = 0;
            for (var i = 0; i + 1 < bytes.length; i++) {
                if (bytes[i] == (byte) 0xFF && (bytes[i + 1] & 0xF8) == 0xD0) {
                    restarts++;
                }
            }
            assertTrue(restarts > 0, "fixture has no restart markers");
        }
        var picture = (YuvPicture) JpegDecoder.decode(MediaSource.of(Files.readAllBytes(path)), ImageDecoderOptions.defaults()).picture();
        var expected = switch (pixelFormat) {
            case "yuvj420p" -> ChromaFormat.YUV420;
            case "yuvj422p" -> ChromaFormat.YUV422;
            default -> ChromaFormat.YUV444;
        };
        assertEquals(expected, picture.chromaFormat());
        assertEquals(width, picture.width());
        assertEquals(height, picture.height());
        var reference = Oracle.decodeVideo(path, pixelFormat, 1);
        comparePlanes(picture, reference);
        DJPEG.assertWithin("luma against djpeg", djpegLuma(path), luma(picture));
    }

    @ParameterizedTest(name = "{0}x{1} q{2}")
    @MethodSource("grayCases")
    @DisplayName("matches ffmpeg's decode of gray files")
    void gray(int width, int height, int quality) throws IOException {
        var source = Fixtures.ffmpeg("raw", List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=1,format=rgb24"),
                List.of("-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24"));
        var rgb = Files.readAllBytes(source);
        var jpeg = JpegEncoder.encode(rgba(width, height, rgb), JpegEncoderOptions.builder().quality(quality)
                .chromaFormat(ChromaFormat.MONOCHROME).build());
        var file = Files.createTempFile("gray-", ".jpg");
        try {
            Files.write(file, jpeg);
            var picture = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            comparePlanes(picture, Oracle.decodeVideo(file, "gray", 1));
            DJPEG.assertWithin("gray against djpeg", djpegLuma(file), luma(picture));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    static Stream<Arguments> grayCases() {
        return Stream.of(Arguments.of(1, 1, 90), Arguments.of(33, 65, 50), Arguments.of(227, 149, 92), Arguments.of(640, 480, 20));
    }

    private static void comparePlanes(YuvPicture picture, byte[] reference) {
        var planes = new ArrayList<byte[]>();
        planes.add(luma(picture));
        if (picture.chromaFormat().hasChroma()) {
            planes.add(cb(picture));
            planes.add(cr(picture));
        }
        var offset = 0;
        for (var i = 0; i < planes.size(); i++) {
            var plane = planes.get(i);
            var expected = java.util.Arrays.copyOfRange(reference, offset, offset + plane.length);
            offset += plane.length;
            FFMPEG.assertWithin("plane " + i, expected, plane);
        }
        assertEquals(reference.length, offset);
    }

    /** Returns the luma plane libjpeg-turbo's djpeg decodes, with its accurate integer IDCT, from a file. */
    static byte[] djpegLuma(Path file) {
        var pgm = Oracle.run(Oracle.Tool.DJPEG, Duration.ofMinutes(1), List.of("-dct", "int", "-grayscale", "-pnm",
                file.toAbsolutePath().toString()));
        var position = 0;
        var fields = new ArrayList<String>();
        while (fields.size() < 4) {
            while (Character.isWhitespace(pgm[position])) {
                position++;
            }
            if (pgm[position] == '#') {
                while (pgm[position] != '\n') {
                    position++;
                }
                continue;
            }
            var start = position;
            while (!Character.isWhitespace(pgm[position])) {
                position++;
            }
            fields.add(new String(pgm, start, position - start, java.nio.charset.StandardCharsets.US_ASCII));
        }
        assertEquals("P5", fields.get(0), "djpeg writes a binary PGM");
        assertEquals("255", fields.get(3), "djpeg writes 8-bit samples");
        var size = Integer.parseInt(fields.get(1)) * Integer.parseInt(fields.get(2));
        return java.util.Arrays.copyOfRange(pgm, position + 1, position + 1 + size);
    }
}
