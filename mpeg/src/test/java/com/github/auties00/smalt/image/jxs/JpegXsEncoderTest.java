package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jxs.internal.JxsHeader;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the JPEG XS encoder: lossless round trips of every component layout through the decoder, lossy rate and
 * quality, the parameters the codestream declares and the validation of the options.
 */
@DisplayName("JPEG XS encoder")
class JpegXsEncoderTest {
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    private static JpegXsImage decode(byte[] data) {
        return JpegXsDecoder.decodeImage(MediaSource.of(data), LIMITS);
    }

    private static void assertSameComponents(JpegXsImage expected, JpegXsImage actual) {
        assertEquals(expected.components().size(), actual.components().size(), "component count");
        for (var c = 0; c < expected.components().size(); c++) {
            var a = expected.components().get(c);
            var b = actual.components().get(c);
            assertEquals(a.width(), b.width(), "width of component " + c);
            assertEquals(a.height(), b.height(), "height of component " + c);
            assertEquals(a.precision(), b.precision(), "precision of component " + c);
            assertArrayEquals(a.samples(), b.samples(), "samples of component " + c);
        }
    }

    @Nested
    @DisplayName("lossless coding")
    class Lossless {
        @ParameterizedTest(name = "{0}x{1}, {2} bits, sampling {3}")
        @CsvSource({
                "64, 48, 8, 11", "97, 33, 8, 11", "128, 64, 10, 11-21-21", "130, 66, 12, 11-22-22",
                "256, 40, 12, 11-11-11", "200, 64, 16, 11-11-11", "96, 64, 8, 11-21-21-11", "64, 64, 14, 11-11-11-11",
                "48, 32, 12, 11-11-11-11-11"
        })
        @DisplayName("reproduces the components exactly")
        void components(int width, int height, int precision, String sampling) {
            var factors = sampling.split("-");
            var sx = new int[factors.length];
            var sy = new int[factors.length];
            for (var c = 0; c < factors.length; c++) {
                sx[c] = factors[c].charAt(0) - '0';
                sy[c] = factors[c].charAt(1) - '0';
            }
            var image = JxsSamples.image(width, height, precision, sx, sy, width * 31L + height);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.forLossless());
            assertSameComponents(image, decode(data));
        }

        @ParameterizedTest
        @EnumSource(JpegXsImage.CfaPattern.class)
        @DisplayName("reproduces a Bayer mosaic of every pattern exactly")
        void bayer(JpegXsImage.CfaPattern pattern) {
            var image = JxsSamples.bayer(120, 70, 12, pattern, pattern.ordinal());
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.forLossless());
            var decoded = decode(data);
            assertEquals(Optional.of(pattern), decoded.cfaPattern());
            assertArrayEquals(image.mosaic().orElseThrow(), decoded.mosaic().orElseThrow());
        }

        @Test
        @DisplayName("reproduces an RGBA picture through the color transform")
        void rgba() {
            var picture = JxsSamples.rgba(77, 41, true, 3);
            var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.forLossless());
            var decoded = JpegXsDecoder.decode(MediaSource.of(data));
            var out = assertInstanceOf(RgbaPicture.class, decoded.picture());
            assertArrayEquals(picture.pixels(), out.pixels());
            assertEquals(JpegXsImage.ColorTransform.RCT, decode(data).colorTransform());
        }

        @Test
        @DisplayName("reproduces a 16-bit RGB picture with MLS.16")
        void rgba16() {
            var picture = JxsSamples.rgba16(64, 40, 5);
            var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.forLossless());
            assertEquals(Optional.of(JpegXsProfile.MLS_16), decode(data).profile());
            var decoded = JpegXsDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults().toBuilder()
                    .highBitDepth(true).build());
            assertArrayEquals(picture.pixels(), assertInstanceOf(Rgba16Picture.class, decoded.picture()).pixels());
        }

        @ParameterizedTest
        @EnumSource(value = ChromaFormat.class, names = {"MONOCHROME", "YUV420", "YUV422", "YUV444"})
        @DisplayName("reproduces Y'CbCr pictures with their chroma format")
        void yuv(ChromaFormat format) {
            var picture = JxsSamples.yuv(64, 48, format, format == ChromaFormat.YUV422, 7);
            var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.forLossless());
            var out = assertInstanceOf(YuvPicture.class, JpegXsDecoder.decode(MediaSource.of(data)).picture());
            assertEquals(format, out.chromaFormat());
            assertArrayEquals(picture.y(), out.y());
            if (format != ChromaFormat.MONOCHROME) {
                assertArrayEquals(picture.u(), out.u());
                assertArrayEquals(picture.v(), out.v());
            }
            if (picture.hasAlpha()) {
                assertArrayEquals(picture.alpha(), out.alpha());
            }
        }

        @Test
        @DisplayName("reproduces a 10-bit Y'CbCr picture in a 16-bit picture")
        void yuv16() {
            var picture = JxsSamples.yuv16(96, 32, ChromaFormat.YUV422, 10, 9);
            var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.forLossless());
            var out = assertInstanceOf(Yuv16Picture.class, JpegXsDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.defaults().toBuilder().highBitDepth(true).build()).picture());
            assertEquals(10, out.bitDepth());
            assertArrayEquals(picture.y(), out.y());
            assertArrayEquals(picture.u(), out.u());
            assertArrayEquals(picture.v(), out.v());
        }

        @Test
        @DisplayName("rescales an alpha component of lower precision than the color to the picture's full range")
        void alphaPrecision() {
            var color = JxsSamples.image(48, 16, 10, new int[]{1, 1, 1}, new int[]{1, 1, 1}, 13);
            var opaque = new short[48 * 16];
            java.util.Arrays.fill(opaque, (short) 255);
            opaque[0] = 0;
            var components = new java.util.ArrayList<>(color.components());
            components.add(new JpegXsImage.Component(48, 16, 8, 1, 1, 0, 0, opaque));
            var image = new JpegXsImage(48, 16, components, JpegXsImage.ColorTransform.NONE, Optional.empty(), 0, 0,
                    Optional.empty(), Optional.empty());
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.forLossless());
            assertSameComponents(image, decode(data));
            var out = assertInstanceOf(Yuv16Picture.class, JpegXsDecoder.decode(MediaSource.of(data),
                    ImageDecoderOptions.defaults().toBuilder().highBitDepth(true).build()).picture());
            assertEquals(10, out.bitDepth());
            var alpha = out.alpha();
            assertEquals(0, alpha[0]);
            assertEquals(1023, alpha[1]);
            assertEquals(1023, alpha[alpha.length - 1]);
        }

        @ParameterizedTest(name = "Cw {0}, {1} horizontal and {2} vertical levels, slices of {3} lines")
        @CsvSource({"1, 3, 1, 4", "2, 4, 2, 8", "1, 1, 0, 1", "3, 2, 2, 12"})
        @DisplayName("reproduces the components with narrow precincts, every decomposition and short slices")
        void layouts(int cw, int nlx, int nly, int slice) {
            var image = JxsSamples.image(330, 45, 10, new int[]{1, 2, 2}, new int[]{1, 1, 1}, cw * 7L + nlx);
            var options = JpegXsEncoderOptions.forLossless().toBuilder().precinctWidth(cw).horizontalLevels(nlx)
                    .verticalLevels(nly).sliceHeight(slice).build();
            assertSameComponents(image, decode(JpegXsEncoder.encode(image, options)));
        }
    }

    @Nested
    @DisplayName("lossy coding")
    class Lossy {
        @ParameterizedTest(name = "{0} bits per pixel")
        @CsvSource({"1.5", "3", "6"})
        @DisplayName("fits the budget and keeps the picture recognizable")
        void rate(double bitsPerPixel) {
            var image = JxsSamples.image(256, 128, 10, new int[]{1, 2, 2}, new int[]{1, 1, 1}, 11);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(bitsPerPixel).toBuilder()
                    .fileFormat(false).build());
            assertTrue(data.length <= Math.ceil(bitsPerPixel * 256 * 128 / 8), data.length + " bytes");
            var psnr = JxsSamples.psnr(image.components(), decode(data).components());
            assertTrue(psnr > 25 + 3 * bitsPerPixel, "PSNR " + psnr);
        }

        @Test
        @DisplayName("gains quality with every increase of the rate")
        void monotonic() {
            var image = JxsSamples.image(192, 96, 8, new int[]{1, 1, 1}, new int[]{1, 1, 1}, 13);
            var previous = 0.0;
            for (var bpp : new double[]{1, 2, 4, 8, 12}) {
                var psnr = JxsSamples.psnr(image.components(),
                        decode(JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(bpp))).components());
                assertTrue(psnr > previous, bpp + " bpp: " + psnr + " after " + previous);
                previous = psnr;
            }
        }

        @ParameterizedTest
        @EnumSource(JpegXsEncoderOptions.Quantizer.class)
        @DisplayName("codes with either quantizer")
        void quantizers(JpegXsEncoderOptions.Quantizer quantizer) {
            var image = JxsSamples.image(128, 64, 12, new int[]{1, 2, 2}, new int[]{1, 2, 2}, 17);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(4).toBuilder()
                    .quantizer(quantizer).build());
            assertTrue(JxsSamples.psnr(image.components(), decode(data).components()) > 35);
        }

        @Test
        @DisplayName("codes a Bayer mosaic through the Star-Tetrix transform")
        void bayer() {
            var image = JxsSamples.bayer(240, 160, 12, JpegXsImage.CfaPattern.GRBG, 19);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(12));
            var decoded = decode(data);
            assertEquals(JpegXsImage.ColorTransform.STAR_TETRIX, decoded.colorTransform());
            assertEquals(Optional.of(JpegXsProfile.MAIN_BAYER), decoded.profile());
            assertTrue(JxsSamples.psnr(image.components(), decoded.components()) > 35);
        }
    }

    @Nested
    @DisplayName("declared parameters")
    class Declared {
        @ParameterizedTest
        @EnumSource(value = JpegXsProfile.class, names = {"LIGHT_422_10", "LIGHT_444_12", "LIGHT_SUBLINE_422_10",
                "MAIN_422_10", "MAIN_444_12", "HIGH_444_12", "CHIGH_444_12", "UNRESTRICTED"})
        @DisplayName("writes the requested profile and its decomposition limit")
        void profiles(JpegXsProfile profile) {
            var sx = profile.name().contains("422") ? new int[]{1, 2, 2} : new int[]{1, 1, 1};
            var image = JxsSamples.image(2560, 32, 10, sx, new int[]{1, 1, 1}, 23);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(4).toBuilder().profile(profile)
                    .fileFormat(false).build());
            var header = JxsHeader.parse(data, 0, data.length);
            assertEquals(profile.code(), header.profile);
            var limit = switch (profile) {
                case LIGHT_SUBLINE_422_10 -> 0;
                case LIGHT_422_10, LIGHT_444_12, MAIN_422_10, MAIN_444_12 -> 1;
                default -> 2;
            };
            assertEquals(limit, header.verticalLevels);
            if (profile == JpegXsProfile.LIGHT_SUBLINE_422_10) {
                assertTrue(header.precinctWidth > 0, "light-subline limits the precinct width");
            }
            assertTrue(JxsSamples.psnr(image.components(), decode(data).components()) > 30);
        }

        @Test
        @DisplayName("writes a JXS file that the format detection and the probe recognize")
        void file() {
            var picture = JxsSamples.yuv(80, 40, ChromaFormat.YUV422, false, 29);
            var data = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.defaults());
            assertEquals(Optional.of(ImageFormat.JPEG_XS), ImageFormat.detect(data, data.length));
            var info = JpegXsDecoder.probe(MediaSource.of(data));
            assertEquals(80, info.width());
            assertEquals(40, info.height());
            var image = decode(data);
            assertEquals(picture.color(), image.color().orElseThrow());
            var raw = JpegXsEncoder.encode(picture, JpegXsEncoderOptions.defaults().toBuilder().fileFormat(false)
                    .build());
            assertTrue(JxsHeader.isCodestream(raw, 0, raw.length));
            assertEquals(Optional.of(ImageFormat.JPEG_XS), ImageFormat.detect(raw, raw.length));
        }

        @Test
        @DisplayName("declares the capabilities the codestream uses")
        void capabilities() {
            var bayer = JpegXsEncoder.encode(JxsSamples.bayer(64, 32, 12, JpegXsImage.CfaPattern.RGGB, 1),
                    JpegXsEncoderOptions.forLossless().toBuilder().fileFormat(false).build());
            var header = JxsHeader.parse(bayer, 0, bayer.length);
            assertEquals(3, header.colorTransform);
            assertTrue((header.capabilities & 1L << 1) != 0, "Star-Tetrix capability");
            assertTrue((header.capabilities & 1L << 5) != 0, "Sd capability");
            assertTrue((header.capabilities & 1L << 6) != 0, "MLS capability");
            assertEquals(1, header.suppressedComponents);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {
        @Test
        @DisplayName("rejects invalid settings")
        void options() {
            var builder = JpegXsEncoderOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.bitsPerPixel(0));
            assertThrows(IllegalArgumentException.class, () -> builder.bitsPerPixel(Double.NaN));
            assertThrows(IllegalArgumentException.class, () -> builder.horizontalLevels(0));
            assertThrows(IllegalArgumentException.class, () -> builder.horizontalLevels(9));
            assertThrows(IllegalArgumentException.class, () -> builder.verticalLevels(3));
            assertThrows(IllegalArgumentException.class, () -> builder.sliceHeight(0));
            assertThrows(IllegalArgumentException.class, () -> builder.precinctWidth(-1));
            assertThrows(IllegalArgumentException.class, () -> builder.profile(JpegXsProfile.TDC_444_12));
            assertEquals(JpegXsEncoderOptions.defaults(), JpegXsEncoderOptions.defaults().toBuilder().build());
        }

        @Test
        @DisplayName("rejects pictures the requested profile cannot carry")
        void profileLimits() {
            var image = JxsSamples.image(64, 32, 12, new int[]{1, 2, 2}, new int[]{1, 1, 1}, 31);
            assertThrows(MediaException.Unsupported.class, () -> JpegXsEncoder.encode(image,
                    JpegXsEncoderOptions.defaults().toBuilder().profile(JpegXsProfile.MAIN_422_10).build()));
            assertThrows(MediaException.Unsupported.class, () -> JpegXsEncoder.encode(image,
                    JpegXsEncoderOptions.defaults().toBuilder().profile(JpegXsProfile.MAIN_BAYER).build()));
        }

        @Test
        @DisplayName("rejects components whose size does not match their sampling")
        void sampling() {
            var good = JxsSamples.image(64, 32, 8, new int[]{1, 2, 2}, new int[]{1, 1, 1}, 37);
            var chroma = good.components().get(1);
            var mislabeled = new JpegXsImage.Component(chroma.width(), chroma.height(), 8, 1, 1, 0, 0,
                    chroma.samples());
            var bad = new JpegXsImage(64, 32, java.util.List.of(good.components().get(0), mislabeled,
                    good.components().get(2)), JpegXsImage.ColorTransform.NONE, Optional.empty(), 0, 0,
                    Optional.empty(), Optional.empty());
            assertThrows(IllegalArgumentException.class, () -> JpegXsEncoder.encode(bad,
                    JpegXsEncoderOptions.defaults()));
            var odd = JxsSamples.image(65, 32, 8, new int[]{1, 2, 2}, new int[]{1, 1, 1}, 41);
            assertThrows(IllegalArgumentException.class, () -> JpegXsEncoder.encode(odd,
                    JpegXsEncoderOptions.defaults()));
            assertThrows(IllegalArgumentException.class, () -> JpegXsEncoder.bayer(new short[15], 5, 3, 12,
                    JpegXsImage.CfaPattern.RGGB));
        }
    }
}
