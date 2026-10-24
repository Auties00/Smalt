package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions.ColorSpace;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the files of the encoder modes that neither cjpeg nor TurboJPEG can write with independent decoders:
 * ffmpeg's lossless JPEG decoder must give back the exact planes of subsampled lossless YCbCr and of gray
 * files, and libjpeg-turbo's djpeg must decode CMYK and YCCK files made from RGB pictures, in every coding mode,
 * to exactly the pixels Smalt's decoder gives.
 */
@DisplayName("JpegEncoder output in other decoders")
class JpegEncoderInteropTest {
    static List<Arguments> planarCases() {
        var cases = new ArrayList<Arguments>();
        // ffmpeg takes every lossless frame of three components sampled 1x1 for the RGB of its own reversible
        // color transform, so 4:4:4 Y'CbCr is checked with Smalt's decoder only (JpegEncodingTest).
        for (var format : new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.MONOCHROME}) {
            for (var size : new int[][]{{16, 16}, {33, 19}, {7, 5}}) {
                for (var predictor : new int[]{1, 4, 7}) {
                    cases.add(Arguments.of(format, size[0], size[1], predictor));
                }
            }
        }
        return cases;
    }

    @Nested
    @DisplayName("in ffmpeg")
    class Ffmpeg {
        @ParameterizedTest(name = "{0} {1}x{2} predictor {3}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegEncoderInteropTest#planarCases")
        @DisplayName("lossless planar pictures decode to their planes")
        void lossless(ChromaFormat format, int width, int height, int predictor) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var random = new Random(width * 131L + height * 7L + predictor + format.ordinal());
            var picture = YuvPicture.allocate(width, height, format, false, ColorInfo.JPEG);
            random.nextBytes(picture.y());
            if (format.hasChroma()) {
                random.nextBytes(picture.u());
                random.nextBytes(picture.v());
            }
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.ofLossless().toBuilder()
                    .colorSpace(ColorSpace.YCBCR).chromaFormat(format).predictor(predictor).build());
            var path = Scratch.directory("jpeg-encoder-interop").resolve("ycc-" + format + "-" + width + "x" + height
                    + "-" + predictor + ".jpg");
            Files.write(path, jpeg);
            var pixelFormat = switch (format) {
                case YUV420 -> "yuvj420p";
                case YUV422 -> "yuvj422p";
                default -> "gray";
            };
            var raw = Oracle.decodeVideo(path, pixelFormat, 1);
            var expected = JpegTestSupport.planar(picture);
            assertArrayEquals(expected, Arrays.copyOf(raw, expected.length));
            assertEquals(expected.length, raw.length);
        }

        @ParameterizedTest(name = "predictor {0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegEncoderInteropTest#predictors")
        @DisplayName("16-bit gray pictures decode to their samples")
        void deepGray(int predictor) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var random = new Random(predictor);
            var picture = Yuv16Picture.allocate(29, 13, ChromaFormat.MONOCHROME, 16, false, ColorInfo.JPEG);
            for (var i = 0; i < picture.y().length; i++) {
                picture.y()[i] = (short) random.nextInt(65536);
            }
            var jpeg = JpegEncoder.encode(picture, JpegEncoderOptions.ofLossless().toBuilder()
                    .colorSpace(ColorSpace.YCBCR).chromaFormat(ChromaFormat.MONOCHROME).predictor(predictor).build());
            var path = Scratch.directory("jpeg-encoder-interop").resolve("gray16-" + predictor + ".jpg");
            Files.write(path, jpeg);
            var raw = Oracle.decodeVideo(path, "gray16le", 1);
            assertEquals(29 * 13 * 2, raw.length);
            for (var y = 0; y < 13; y++) {
                for (var x = 0; x < 29; x++) {
                    var index = 2 * (y * 29 + x);
                    var sample = (raw[index] & 0xFF) | ((raw[index + 1] & 0xFF) << 8);
                    assertEquals(picture.y()[y * picture.yStride() + x] & 0xFFFF, sample, "sample " + x + "," + y);
                }
            }
        }
    }

    static List<Integer> predictors() {
        return List.of(1, 2, 3, 4, 5, 6, 7);
    }

    static List<Arguments> inkCases() {
        var cases = new ArrayList<Arguments>();
        var modes = List.of(
                JpegEncoderOptions.defaults(),
                JpegEncoderOptions.ofProgressive(80),
                JpegEncoderOptions.builder().arithmetic(true).restartInterval(3).build(),
                JpegEncoderOptions.builder().arithmetic(true).progressive(true).build(),
                JpegEncoderOptions.builder().samplingFactors(JpegEncoderOptions.Sampling.of(1, 2)).build(),
                JpegEncoderOptions.builder().chromaFormat(ChromaFormat.YUV422).quality(95).build());
        for (var space : new ColorSpace[]{ColorSpace.CMYK, ColorSpace.YCCK}) {
            for (var mode : modes) {
                cases.add(Arguments.of(space, mode.toBuilder().colorSpace(space).build()));
            }
        }
        return cases;
    }

    @Nested
    @DisplayName("in djpeg")
    class Djpeg {
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.JpegEncoderInteropTest#inkCases")
        @DisplayName("CMYK and YCCK files decode to the pixels Smalt decodes")
        void inks(ColorSpace space, JpegEncoderOptions options) throws IOException {
            Oracle.assume(Oracle.Tool.DJPEG);
            var random = new Random(options.hashCode());
            var rgba = RgbaPicture.allocate(37, 21, false);
            for (var i = 0; i < rgba.pixels().length; i++) {
                rgba.pixels()[i] = (byte) ((i % 4 == 3) ? 255 : (i * 3 + random.nextInt(40)));
            }
            var inks = Channel8Picture.allocate(37, 21, PixelLayout.CMYK);
            random.nextBytes(inks.pixels());
            for (var picture : List.of(rgba, inks)) {
                var jpeg = JpegEncoder.encode(picture, options);
                var path = Scratch.directory("jpeg-encoder-interop").resolve(space + "-" + Integer.toHexString(
                        options.hashCode()) + "-" + picture.getClass().getSimpleName() + ".jpg");
                Files.write(path, jpeg);
                var ppm = Oracle.run(Oracle.Tool.DJPEG, Duration.ofMinutes(1), List.of("-dct", "int", "-pnm",
                        path.toString()));
                var decoded = (RgbaPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults())
                        .picture();
                var expected = JpegTestSupport.writePnm(37, 21, 3, JpegTestSupport.toRgb(decoded, true));
                assertArrayEquals(expected, ppm, space + " from " + picture.getClass().getSimpleName());
            }
        }
    }
}
