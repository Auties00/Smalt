package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.github.auties00.smalt.image.jpeg.JpegTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the decoder against the libjpeg-turbo and IJG libjpeg 6b test images, bit for bit where
 * the reference output is known.
 */
class JpegConformanceTest {
    private static YuvPicture decodeYuv(byte[] jpeg) {
        return (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
    }

    @Nested
    @DisplayName("baseline")
    class Baseline {
        @Test
        @DisplayName("testorig.jpg decodes to IJG's djpeg -dct int reference (testimg.ppm) bit for bit")
        void testorig() {
            var picture = decodeYuv(JpegTestVectors.testorig());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            var reference = parsePnm(JpegTestVectors.ijgTestimgPpm());
            assertEquals(reference.width(), picture.width());
            assertEquals(reference.height(), picture.height());
            var rgb = toRgb(picture, true);
            assertEquals("dea1d7bbc37e39adf628342c86096641", md5(writePnm(picture.width(), picture.height(), 3, rgb)));
            assertArrayEquals(reference.pixels(), rgb);
        }

        @Test
        @DisplayName("testimgint.jpg decodes to the same coefficients as testimgari.jpg")
        void intVersusArithmetic() {
            var huffman = new JpegReader(JpegTestVectors.testimgint(), 0, JpegTestVectors.testimgint().length, MediaLimits.DEFAULT)
                    .decodeCoefficients();
            var arithmeticBytes = JpegTestVectors.testimgari();
            var arithmetic = new JpegReader(arithmeticBytes, 0, arithmeticBytes.length, MediaLimits.DEFAULT).decodeCoefficients();
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(huffman.coefficients(c), arithmetic.coefficients(c), "component " + c);
                assertArrayEquals(huffman.quantization(c), arithmetic.quantization(c));
            }
        }
    }

    @Nested
    @DisplayName("progressive")
    class Progressive {
        @Test
        @DisplayName("IJG testprog.jpg decodes to the same pixels as testorig.jpg (testimg.ppm)")
        void testprog() {
            var picture = decodeYuv(JpegTestVectors.ijgTestprog());
            var reference = parsePnm(JpegTestVectors.ijgTestimgPpm());
            assertArrayEquals(reference.pixels(), toRgb(picture, true));
        }

        @Test
        @DisplayName("IJG testprog.jpg holds exactly the coefficients of testorig.jpg")
        void coefficients() {
            var progressiveBytes = JpegTestVectors.ijgTestprog();
            var baselineBytes = JpegTestVectors.testorig();
            var progressive = new JpegReader(progressiveBytes, 0, progressiveBytes.length, MediaLimits.DEFAULT).decodeCoefficients();
            var baseline = new JpegReader(baselineBytes, 0, baselineBytes.length, MediaLimits.DEFAULT).decodeCoefficients();
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(baseline.coefficients(c), progressive.coefficients(c), "component " + c);
            }
        }
    }

    @Nested
    @DisplayName("12-bit")
    class TwelveBit {
        @ParameterizedTest(name = "scale 1/{0}")
        @CsvSource({
                "1, 70194fdcb73370ee7ba0db868d0c6fc8",
                "2, b419124dd5568b085787234866102866",
                "4, 35fd59d866e44659edfa3c18db2a3edb",
                "8, ccaed48ac0aedefda5d4abe4013f4ad7"
        })
        @DisplayName("testorig12.jpg decodes to libjpeg-turbo 3.1.3 djpeg -dct int -scale 1/N output bit for bit")
        void testorig12(int denominator, String md5) {
            var data = JpegTestVectors.testorig12();
            var options = ImageDecoderOptions.builder().highBitDepth(true)
                    .minSize(227 / denominator, 149 / denominator).build();
            var image = JpegDecoder.decode(MediaSource.of(data), options);
            var picture = (Yuv16Picture) image.picture();
            assertEquals(12, picture.bitDepth());
            assertEquals(227, image.sourceWidth());
            assertEquals(149, image.sourceHeight());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            if (denominator == 1) {
                assertEquals(md5, md5(toRgb12Pnm(picture)));
            }
            assertEquals(md5, md5(com.github.auties00.smalt.image.jpeg.internal.Djpeg12Support.emulate(data,
                    Integer.numberOfTrailingZeros(denominator))));
        }

        @Test
        @DisplayName("testorig12.jpg decodes by default to an 8-bit picture of its samples rounded to 8 bits")
        void eightBitByDefault() {
            var data = JpegTestVectors.testorig12();
            var narrow = decodeYuv(data);
            var wide = (Yuv16Picture) JpegDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            assertEquals(ChromaFormat.YUV420, narrow.chromaFormat());
            for (var y = 0; y < wide.height(); y++) {
                for (var x = 0; x < wide.width(); x++) {
                    var v = wide.y()[y * wide.yStride() + x];
                    assertEquals((v * 510 + 4095) / 8190, narrow.y()[y * narrow.yStride() + x] & 0xFF);
                }
            }
            for (var y = 0; y < wide.chromaHeight(); y++) {
                for (var x = 0; x < wide.chromaWidth(); x++) {
                    assertEquals((wide.u()[y * wide.uvStride() + x] * 510 + 4095) / 8190,
                            narrow.u()[y * narrow.uvStride() + x] & 0xFF);
                    assertEquals((wide.v()[y * wide.uvStride() + x] * 510 + 4095) / 8190,
                            narrow.v()[y * narrow.uvStride() + x] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("testorig12.jpg holds the coefficients of its progressive transcode by jpegtran")
        void progressiveCoefficients() {
            var data = JpegTestVectors.testorig12();
            var progressive = JpegTransforms.progressive(MediaSource.of(data));
            assertEquals(0xC2, progressive[findSof(progressive)] & 0xFF);
            assertEquals(12, progressive[findSof(progressive) + 3]);
            var a = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT).decodeCoefficients();
            var b = new JpegReader(progressive, 0, progressive.length, MediaLimits.DEFAULT).decodeCoefficients();
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(a.coefficients(c), b.coefficients(c), "component " + c);
                assertArrayEquals(a.quantization(c), b.quantization(c));
            }
        }
    }

    // the index of the marker code of the first SOF segment
    private static int findSof(byte[] jpeg) {
        var at = 2;
        while ((jpeg[at + 1] & 0xFF) < 0xC0 || (jpeg[at + 1] & 0xFF) > 0xCF || (jpeg[at + 1] & 0xFF) == 0xC4
                || (jpeg[at + 1] & 0xFF) == 0xCC) {
            at += 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
        }
        return at + 1;
    }

    private static byte[] toRgb12Pnm(Yuv16Picture picture) {
        return com.github.auties00.smalt.image.jpeg.internal.Djpeg12Support.djpegOutput(picture, true);
    }
}
