package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
    @DisplayName("unsupported")
    class Unsupported {
        @Test
        @DisplayName("testorig12.jpg is rejected as 12-bit precision")
        void twelveBit() {
            var exception = assertThrows(MediaException.Unsupported.class, () -> decodeYuv(JpegTestVectors.testorig12()));
            assertEquals("jpeg 12-bit precision", exception.format());
        }
    }
}
