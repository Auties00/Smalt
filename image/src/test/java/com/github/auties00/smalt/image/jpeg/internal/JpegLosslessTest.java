package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Decodes lossless (SOF3) JPEG files through the public decoder. The fixtures are libjpeg-turbo 3.1.3
 * {@code cjpeg -lossless} files of crops of testorig.jpg and testorig12.jpg; lossless-md5.csv holds the MD5 of
 * {@code djpeg}'s PNM payload for each, which is the samples as coded.
 */
class JpegLosslessTest {
    private static final String BASE = "/com/github/auties00/smalt/image/jpeg/";

    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    record Fixture(String file, int width, int height, JpegColorSpace space, int precision, String md5) {
        @Override
        public String toString() {
            return file;
        }
    }

    static List<Fixture> fixtures() {
        var fixtures = new ArrayList<Fixture>();
        try (var reader = new BufferedReader(new InputStreamReader(
                JpegLosslessTest.class.getResourceAsStream(BASE + "lossless-md5.csv"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var f = line.split(",");
                fixtures.add(new Fixture(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]),
                        JpegColorSpace.valueOf(f[3]), Integer.parseInt(f[4]), f[8]));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return fixtures;
    }

    static List<Fixture> wideFixtures() {
        return fixtures().stream().filter(fixture -> fixture.precision() > 8).toList();
    }

    static byte[] resource(String file) {
        try (var input = JpegLosslessTest.class.getResourceAsStream(BASE + "lossless/" + file)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static DecodedImage decode(byte[] jpeg, ImageDecoderOptions options) {
        return JpegDecoder.decode(MediaSource.of(jpeg), options);
    }

    // The samples at the coded precision, components interleaved; rescaled samples are mapped back after checking
    // that the forward rescale gives them.
    static int[] samples(Picture picture, int components, int precision) {
        var width = picture.width();
        var height = picture.height();
        var max = (1 << precision) - 1;
        var out = new int[width * height * components];
        var index = 0;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < components; c++) {
                    out[index++] = switch (picture) {
                        case YuvPicture yuv -> fromScaled(plane(yuv, c)[y * stride(yuv, c) + x] & 0xFF, 255, max);
                        case RgbaPicture rgba -> fromScaled(rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFF, 255, max);
                        case Yuv16Picture yuv -> {
                            var plane = c == 0 ? yuv.y() : c == 1 ? yuv.u() : yuv.v();
                            yield plane[y * (c == 0 ? yuv.yStride() : yuv.uvStride()) + x] & 0xFFFF;
                        }
                        case Rgba16Picture rgba -> fromScaled(rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFFFF,
                                65535, max);
                        default -> throw new AssertionError(picture);
                    };
                }
            }
        }
        return out;
    }

    static byte[] plane(YuvPicture picture, int component) {
        return component == 0 ? picture.y() : component == 1 ? picture.u() : picture.v();
    }

    static int stride(YuvPicture picture, int component) {
        return component == 0 ? picture.yStride() : picture.uvStride();
    }

    static int scale(int value, int max, int target) {
        return (int) ((value * 2L * target + max) / (2L * max));
    }

    static int fromScaled(int scaled, int target, int max) {
        if (max == target) {
            return scaled;
        }
        var value = (int) ((scaled * 2L * max + target) / (2L * target));
        assertEquals(scaled, scale(value, max, target), "rescaled sample");
        return value;
    }

    static String md5(int[] samples, int precision) {
        var out = new ByteArrayOutputStream();
        for (var sample : samples) {
            if (precision > 8) {
                out.write(sample >>> 8);
            }
            out.write(sample & 0xFF);
        }
        return DjpegConformanceTest.md5(out.toByteArray());
    }

    static int components(JpegColorSpace space) {
        return space == JpegColorSpace.GRAY ? 1 : 3;
    }

    @Nested
    @DisplayName("libjpeg-turbo fixtures")
    class Fixtures {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.internal.JpegLosslessTest#fixtures")
        @DisplayName("decode at their precision to the samples djpeg decodes")
        void samplesMatchDjpeg(Fixture fixture) {
            var image = decode(resource(fixture.file()), WIDE);
            var picture = image.picture();
            assertEquals(fixture.width(), picture.width());
            assertEquals(fixture.height(), picture.height());
            var wide = fixture.precision() > 8;
            switch (fixture.space()) {
                case GRAY, YCBCR -> {
                    var format = fixture.space() == JpegColorSpace.GRAY ? ChromaFormat.MONOCHROME : ChromaFormat.YUV444;
                    if (wide) {
                        var yuv = assertInstanceOf(Yuv16Picture.class, picture);
                        assertEquals(fixture.precision(), yuv.bitDepth());
                        assertEquals(format, yuv.chromaFormat());
                        assertEquals(ColorInfo.JPEG, yuv.color());
                    } else {
                        var yuv = assertInstanceOf(YuvPicture.class, picture);
                        assertEquals(format, yuv.chromaFormat());
                        assertEquals(ColorInfo.JPEG, yuv.color());
                    }
                }
                case RGB -> assertEquals(wide ? Rgba16Picture.class : RgbaPicture.class, picture.getClass());
                default -> throw new AssertionError(fixture.space());
            }
            var samples = samples(picture, components(fixture.space()), fixture.precision());
            assertEquals(fixture.md5(), md5(samples, fixture.precision()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.internal.JpegLosslessTest#wideFixtures")
        @DisplayName("reduce samples above 8 bits to round(v * 255 / max) by default")
        void reducedByDefault(Fixture fixture) {
            var jpeg = resource(fixture.file());
            var components = components(fixture.space());
            var exact = samples(decode(jpeg, WIDE).picture(), components, fixture.precision());
            var reduced = decode(jpeg, ImageDecoderOptions.defaults()).picture();
            assertEquals(fixture.space() == JpegColorSpace.RGB ? RgbaPicture.class : YuvPicture.class, reduced.getClass());
            var expected = Arrays.stream(exact).map(v -> scale(v, (1 << fixture.precision()) - 1, 255)).toArray();
            assertArrayEquals(expected, samples(reduced, components, 8));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.image.jpeg.internal.JpegLosslessTest#fixtures")
        @DisplayName("probe to their size")
        void probe(Fixture fixture) {
            var info = JpegDecoder.probe(MediaSource.of(resource(fixture.file())));
            assertEquals(fixture.width(), info.width());
            assertEquals(fixture.height(), info.height());
        }

        @Test
        @DisplayName("ignore the size hint")
        void fullSize() {
            var image = decode(resource("61x43-rgb-p8-psv1.jpg"), ImageDecoderOptions.builder().minSize(8, 8).build());
            assertEquals(61, image.picture().width());
            assertEquals(43, image.picture().height());
        }

        @Test
        @DisplayName("take the frame height from a DNL segment")
        void dnl() {
            var jpeg = resource("61x43-gray-p12-psv6.jpg");
            var sof = indexOf(jpeg, 0xC3);
            var patched = Arrays.copyOf(jpeg, jpeg.length + 6);
            patched[sof + 5] = 0;
            patched[sof + 6] = 0;
            var eoi = jpeg.length - 2;
            System.arraycopy(new byte[]{(byte) 0xFF, (byte) 0xDC, 0, 4, 0, 43, (byte) 0xFF, (byte) 0xD9}, 0, patched,
                    eoi, 8);
            var expected = samples(decode(jpeg, WIDE).picture(), 1, 12);
            var picture = decode(patched, WIDE).picture();
            assertEquals(43, picture.height());
            assertArrayEquals(expected, samples(picture, 1, 12));
            assertEquals(43, JpegDecoder.probe(MediaSource.of(patched)).height());
        }
    }

    static int indexOf(byte[] jpeg, int marker) {
        for (var i = 2; i + 1 < jpeg.length; i++) {
            if (jpeg[i] == (byte) 0xFF && (jpeg[i + 1] & 0xFF) == marker) {
                return i;
            }
        }
        throw new AssertionError("no marker " + Integer.toHexString(marker));
    }

    @Nested
    @DisplayName("four components")
    class FourComponents {
        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {8, 12})
        @DisplayName("decode as inverted CMYK to 8-bit RGBA")
        void cmyk(int precision) {
            var width = 19;
            var height = 7;
            var samples = LosslessJpegTest.noise(width * height * 4, precision, precision);
            var jpeg = Lj92Encoder.encode(samples, width, height, 4, precision, 4, 0, 0);
            var picture = assertInstanceOf(RgbaPicture.class, decode(jpeg, WIDE).picture());
            var max = (1 << precision) - 1;
            for (var i = 0; i < width * height; i++) {
                var k = scale(samples[4 * i + 3], max, 255);
                for (var c = 0; c < 3; c++) {
                    var expected = JpegOutput.divide255(scale(samples[4 * i + c], max, 255) * k);
                    assertEquals(expected, picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + c] & 0xFF);
                }
                assertEquals(255, picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + 3] & 0xFF);
            }
        }
    }

    @Nested
    @DisplayName("unsupported and invalid")
    class Rejected {
        @Test
        @DisplayName("subsampled components whose restart interval splits a row of units are unsupported")
        void subsampled() {
            var luma = LosslessJpegTest.noise(16 * 8, 8, 1);
            var chroma = LosslessJpegTest.noise(8 * 4, 8, 2);
            var jpeg = Lj92Encoder.encodeSampled(new int[][]{luma, chroma, chroma}, 16, 8,
                    new int[][]{{2, 2}, {1, 1}, {1, 1}}, 8, 1, 0, 0);
            var dri = new byte[]{(byte) 0xFF, (byte) 0xDD, 0, 4, 0, 3};
            var sos = indexOf(jpeg, 0xDA);
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, sos);
            out.writeBytes(dri);
            out.write(jpeg, sos, jpeg.length - sos);
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(out.toByteArray(), WIDE));
            assertEquals("jpeg lossless", exception.format());
        }

        @Test
        @DisplayName("a scan of fewer components than the frame is unsupported")
        void nonInterleaved() {
            var samples = LosslessJpegTest.noise(9 * 5 * 3, 8, 3);
            var jpeg = Lj92Encoder.encode(samples, 9, 5, 3, 8, 1, 0, 0);
            var sos = indexOf(jpeg, 0xDA);
            var out = new ByteArrayOutputStream();
            out.write(jpeg, 0, sos);
            out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xDA, 0, 8, 1, 1, 0});
            out.write(jpeg, sos + 11, jpeg.length - sos - 11);
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(out.toByteArray(), WIDE));
            assertEquals("jpeg lossless", exception.format());
        }

        @ParameterizedTest(name = "precision {0}")
        @ValueSource(ints = {0, 1, 17})
        @DisplayName("a precision outside 2 to 16 is malformed")
        void precision(int precision) {
            var jpeg = resource("61x43-gray-p8-psv1.jpg");
            jpeg[indexOf(jpeg, 0xC3) + 4] = (byte) precision;
            assertThrows(MediaException.Malformed.class, () -> decode(jpeg, WIDE));
        }

        @Test
        @DisplayName("the coefficient path rejects lossless frames")
        void dctOnly() {
            var jpeg = resource("61x43-gray-p8-psv1.jpg");
            var reader = new JpegReader(jpeg, 0, jpeg.length, com.github.auties00.smalt.MediaLimits.DEFAULT);
            assertEquals("jpeg lossless", assertThrows(MediaException.Unsupported.class,
                    reader::decodeCoefficients).format());
        }
    }
}
