package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that {@code Images.encode} with a lossless request codes the planar, gray and CMYK pictures the RGB
 * round trips of the integration tests do not reach in a color space that keeps every sample, so that the decoder
 * gives each one back exactly, at its own depth and subsampling.
 */
@DisplayName("Images.encode lossless JPEG")
class JpegLosslessImagesTest {
    private static final ImageDecoderOptions EXACT = ImageDecoderOptions.builder().highBitDepth(true)
            .keepChannels(true).build();

    static List<Arguments> pictures() {
        var random = new Random(20261022);
        var cases = new ArrayList<Arguments>();
        for (var format : new ChromaFormat[]{ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444,
                ChromaFormat.MONOCHROME}) {
            var yuv = YuvPicture.allocate(31, 17, format, false, ColorInfo.JPEG);
            random.nextBytes(yuv.y());
            if (format.hasChroma()) {
                random.nextBytes(yuv.u());
                random.nextBytes(yuv.v());
            }
            cases.add(Arguments.of("yuv8 " + format, yuv));
            for (var depth : new int[]{9, 10, 16}) {
                var deep = Yuv16Picture.allocate(23, 13, format, depth, false, ColorInfo.JPEG);
                fill(deep.y(), depth, random);
                if (format.hasChroma()) {
                    fill(deep.u(), depth, random);
                    fill(deep.v(), depth, random);
                }
                cases.add(Arguments.of("yuv" + depth + " " + format, deep));
            }
        }
        var cmyk = Channel8Picture.allocate(19, 11, PixelLayout.CMYK);
        random.nextBytes(cmyk.pixels());
        cases.add(Arguments.of("cmyk8", cmyk));
        var cmyk16 = Channel16Picture.allocate(19, 11, PixelLayout.CMYK);
        fill(cmyk16.pixels(), 16, random);
        cases.add(Arguments.of("cmyk16", cmyk16));
        return cases;
    }

    @Test
    @DisplayName("rejects a floating-point picture unless its samples are exactly 8-bit ones")
    void floatingPoint() {
        var exact = ChannelFloatPicture.allocate(13, 7, PixelLayout.RGB);
        var random = new Random(5);
        for (var i = 0; i < exact.pixels().length; i++) {
            exact.pixels()[i] = random.nextInt(256) / 255f;
        }
        var jpeg = Images.encode(exact, ImageFormat.JPEG, ImageEncoderOptions.lossless());
        var decoded = assertInstanceOf(RgbaPicture.class, JpegDecoder.decode(MediaSource.of(jpeg), EXACT).picture());
        var expected = new byte[13 * 7 * 4];
        for (var i = 0; i < 13 * 7; i++) {
            for (var c = 0; c < 3; c++) {
                expected[4 * i + c] = (byte) Math.round(exact.pixels()[3 * i + c] * 255);
            }
            expected[4 * i + 3] = (byte) 255;
        }
        assertArrayEquals(expected, decoded.pixels());
        exact.pixels()[4] = 0.5004f;
        assertThrows(MediaException.Unsupported.class, () -> Images.encode(exact, ImageFormat.JPEG,
                ImageEncoderOptions.lossless()));
    }

    private static void fill(short[] samples, int depth, Random random) {
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (short) random.nextInt(1 << depth);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pictures")
    @DisplayName("decodes to every sample of the picture")
    void exact(String name, Picture picture) {
        var jpeg = Images.encode(picture, ImageFormat.JPEG, ImageEncoderOptions.lossless());
        var decoded = JpegDecoder.decode(MediaSource.of(jpeg), EXACT).picture();
        assertEquals(picture.width(), decoded.width(), name);
        assertEquals(picture.height(), decoded.height(), name);
        switch (picture) {
            case YuvPicture yuv -> {
                var actual = assertInstanceOf(YuvPicture.class, decoded, name);
                var format = yuv.chromaFormat();
                for (var y = 0; y < yuv.height(); y++) {
                    for (var x = 0; x < yuv.width(); x++) {
                        assertEquals(yuv.y()[y * yuv.yStride() + x], actual.y()[y * actual.yStride() + x], name);
                        if (format.hasChroma()) {
                            var source = (y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX());
                            var target = (y >> actual.chromaFormat().shiftY()) * actual.uvStride()
                                    + (x >> actual.chromaFormat().shiftX());
                            assertEquals(yuv.u()[source], actual.u()[target], name + " Cb at " + x + "," + y);
                            assertEquals(yuv.v()[source], actual.v()[target], name + " Cr at " + x + "," + y);
                        }
                    }
                }
            }
            case Yuv16Picture yuv -> {
                var actual = assertInstanceOf(Yuv16Picture.class, decoded, name);
                assertEquals(yuv.bitDepth(), actual.bitDepth(), name);
                var format = yuv.chromaFormat();
                for (var y = 0; y < yuv.height(); y++) {
                    for (var x = 0; x < yuv.width(); x++) {
                        assertEquals(yuv.y()[y * yuv.yStride() + x], actual.y()[y * actual.yStride() + x], name);
                        if (format.hasChroma()) {
                            var source = (y >> format.shiftY()) * yuv.uvStride() + (x >> format.shiftX());
                            var target = (y >> actual.chromaFormat().shiftY()) * actual.uvStride()
                                    + (x >> actual.chromaFormat().shiftX());
                            assertEquals(yuv.u()[source], actual.u()[target], name + " Cb at " + x + "," + y);
                            assertEquals(yuv.v()[source], actual.v()[target], name + " Cr at " + x + "," + y);
                        }
                    }
                }
            }
            case Channel8Picture channels -> {
                var actual = assertInstanceOf(Channel8Picture.class, decoded, name);
                for (var y = 0; y < channels.height(); y++) {
                    for (var i = 0; i < 4 * channels.width(); i++) {
                        assertEquals(channels.pixels()[y * channels.stride() + i],
                                actual.pixels()[y * actual.stride() + i], name);
                    }
                }
            }
            case Channel16Picture channels -> {
                var actual = assertInstanceOf(Channel16Picture.class, decoded, name);
                for (var y = 0; y < channels.height(); y++) {
                    for (var i = 0; i < 4 * channels.width(); i++) {
                        assertEquals(channels.pixels()[y * channels.stride() + i],
                                actual.pixels()[y * actual.stride() + i], name);
                    }
                }
            }
            default -> throw new AssertionError(picture);
        }
    }
}
