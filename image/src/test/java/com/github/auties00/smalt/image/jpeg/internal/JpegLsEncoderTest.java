package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegLsEncoder;
import com.github.auties00.smalt.image.jpeg.JpegLsEncoderOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the public JPEG-LS encoder: the pictures it codes compared byte for byte with CharLS's encodings of the
 * same samples, round trips of subsampled Y'CbCr, restart intervals and rescaled precisions through
 * {@link JpegDecoder}, and the validation of its settings.
 */
@DisplayName("JPEG-LS encoder")
class JpegLsEncoderTest {
    static YuvPicture gray(int width, int height, int[] samples) {
        var picture = YuvPicture.allocate(width, height, ChromaFormat.MONOCHROME, false, ColorInfo.JPEG);
        for (var i = 0; i < samples.length; i++) {
            picture.y()[(i / width) * picture.yStride() + i % width] = (byte) samples[i];
        }
        return picture;
    }

    static Yuv16Picture gray16(int width, int height, int bits, int[] samples) {
        var picture = Yuv16Picture.allocate(width, height, ChromaFormat.MONOCHROME, bits, false, ColorInfo.JPEG);
        for (var i = 0; i < samples.length; i++) {
            picture.y()[(i / width) * picture.yStride() + i % width] = (short) samples[i];
        }
        return picture;
    }

    static RgbaPicture rgb(int width, int height, int[][] planes) {
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            for (var c = 0; c < 3; c++) {
                picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + c] = (byte) planes[c][i];
            }
            picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + 3] = (byte) 0xFF;
        }
        return picture;
    }

    static Rgba16Picture rgb16(int width, int height, int[][] planes, int bits) {
        var picture = Rgba16Picture.allocate(width, height, false);
        var max = (1L << bits) - 1;
        for (var i = 0; i < width * height; i++) {
            for (var c = 0; c < 3; c++) {
                var value = (planes[c][i] * 65535L * 2 + max) / (2 * max);
                picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + c] = (short) value;
            }
            picture.pixels()[(i / width) * picture.stride() + 4 * (i % width) + 3] = (short) 0xFFFF;
        }
        return picture;
    }

    static JpegLsVectors.CharLsCase vector(String name) {
        return JpegLsVectors.charls().stream().filter(vector -> vector.name().equals(name)).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("CharLS vectors")
    class CharLs {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"gray8", "gray8-near1", "gray8-near3", "gray8-1x1", "gray8-1x37", "gray8-37x1",
                "gray8-presets", "gray8-presets-near2", "gray10", "gray12", "gray12-near2", "gray16", "gray16-near7",
                "gray12-reset3", "gray2", "gray5", "gray5-near1", "rgb8-ilv0", "rgb8-ilv1", "rgb8-ilv2",
                "rgb8-ilv1-near2", "rgb8-ilv2-near5", "rgb8-ilv1-presets", "rgb8-hp1-ilv1", "rgb8-hp2-ilv2",
                "rgb8-hp3-ilv1", "rgb8-spiff", "rgb16-ilv2", "rgb16-hp3-ilv2", "rgb10-ilv1"})
        @DisplayName("are what the encoder writes for the picture holding their samples")
        void bytes(String name) {
            var vector = vector(name);
            var planes = JpegLsVectors.synthetic(vector.width(), vector.height(), vector.components(), vector.bits(),
                    vector.seed(), null);
            Picture picture;
            if (vector.components() == 1) {
                picture = vector.bits() <= 8 ? gray(vector.width(), vector.height(), planes[0])
                        : gray16(vector.width(), vector.height(), vector.bits(), planes[0]);
            } else {
                picture = vector.bits() == 8 ? rgb(vector.width(), vector.height(), planes)
                        : rgb16(vector.width(), vector.height(), planes, vector.bits());
            }
            var options = JpegLsEncoderOptions.builder()
                    .nearLossless(vector.near())
                    .interleave(JpegLsEncoderOptions.Interleave.values()[vector.interleave()])
                    .precision(vector.bits())
                    .colorTransform(JpegLsEncoderOptions.ColorTransform.values()[vector.transform()])
                    .spiffHeader(!vector.spiff().equals("-"));
            if (vector.t1() != 0 || vector.reset() != 0) {
                options.presetParameters(new JpegLsEncoderOptions.PresetParameters(vector.t1(), vector.t2(),
                        vector.t3(), vector.reset()));
            }
            var expected = JpegLsVectors.resource("charls/" + name + ".jls");
            if (vector.components() == 1 && vector.bits() < 8) {
                // the samples of an 8-bit picture are rescaled to the precision; code them from a picture at it
                var stream = new JpegLsWriter.Stream(vector.width(), vector.height(), vector.bits(), new int[]{1},
                        new int[]{1}, planes, 0, vector.near(), new int[4], 0, 0, -1, 0);
                assertArrayEquals(expected, JpegLsWriter.encode(stream), name);
                return;
            }
            assertArrayEquals(expected, JpegLsEncoder.encode(picture, options.build()), name);
        }
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422", "YUV444"})
        @DisplayName("code full-range BT.601 Y'CbCr in its stored subsampling with a SPIFF header")
        void ycbcr(ChromaFormat format) {
            var picture = YuvPicture.allocate(45, 33, format, false, ColorInfo.JPEG);
            var random = new Random(format.ordinal());
            for (var plane : new byte[][]{picture.y(), picture.u(), picture.v()}) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (byte) (i % 97 < 40 ? 128 : random.nextInt(256));
                }
            }
            for (var interleave : new JpegLsEncoderOptions.Interleave[]{JpegLsEncoderOptions.Interleave.NONE,
                    JpegLsEncoderOptions.Interleave.LINE}) {
                for (var restart : new int[]{0, 2}) {
                    var jls = JpegLsEncoder.encode(picture, JpegLsEncoderOptions.builder().interleave(interleave)
                            .restartInterval(restart).build());
                    var decoded = assertInstanceOf(YuvPicture.class, JpegDecoder.decode(MediaSource.of(jls)).picture());
                    assertEquals(format, decoded.chromaFormat());
                    for (var y = 0; y < 33; y++) {
                        for (var x = 0; x < 45; x++) {
                            assertEquals(picture.y()[y * picture.yStride() + x], decoded.y()[y * decoded.yStride() + x]);
                        }
                    }
                    for (var y = 0; y < picture.chromaHeight(); y++) {
                        for (var x = 0; x < picture.chromaWidth(); x++) {
                            assertEquals(picture.u()[y * picture.uvStride() + x],
                                    decoded.u()[y * decoded.uvStride() + x]);
                            assertEquals(picture.v()[y * picture.uvStride() + x],
                                    decoded.v()[y * decoded.uvStride() + x]);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("keep 10-bit samples, and rescale 8-bit ones to 12 bits")
        void precisions() {
            var source = JpegLsVectors.synthetic(31, 23, 1, 10, 5, null)[0];
            var jls = JpegLsEncoder.encode(gray16(31, 23, 10, source), JpegLsEncoderOptions.ofNearLossless(2));
            var wide = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(jls),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            assertEquals(10, wide.bitDepth());
            var decoded = new int[source.length];
            for (var i = 0; i < source.length; i++) {
                decoded[i] = wide.y()[(i / 31) * wide.yStride() + i % 31];
            }
            Tolerance.maxAbs(2).assertWithin("10-bit near-lossless", source, decoded);
            var eight = JpegLsVectors.synthetic(31, 23, 1, 8, 6, null)[0];
            var twelve = JpegLsEncoder.encode(gray(31, 23, eight), JpegLsEncoderOptions.builder().precision(12).build());
            var deep = assertInstanceOf(Yuv16Picture.class, JpegDecoder.decode(MediaSource.of(twelve),
                    ImageDecoderOptions.builder().highBitDepth(true).build()).picture());
            for (var i = 0; i < eight.length; i++) {
                assertEquals((eight[i] * 4095 * 2 + 255) / 510, deep.y()[(i / 31) * deep.yStride() + i % 31]);
            }
        }

        @Test
        @DisplayName("convert other planar pictures to RGB, and drop the alpha channel")
        void conversions() {
            var video = YuvPicture.allocate(16, 8, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            java.util.Arrays.fill(video.y(), (byte) 120);
            java.util.Arrays.fill(video.u(), (byte) 100);
            java.util.Arrays.fill(video.v(), (byte) 160);
            var decoded = assertInstanceOf(RgbaPicture.class, JpegDecoder.decode(MediaSource.of(
                    JpegLsEncoder.encode(video))).picture());
            var expected = com.github.auties00.smalt.image.ops.ImageOps.toRgba(video);
            for (var i = 0; i < 16 * 8; i++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(expected.pixels()[(i / 16) * expected.stride() + 4 * (i % 16) + c],
                            decoded.pixels()[(i / 16) * decoded.stride() + 4 * (i % 16) + c]);
                }
            }
            var alpha = RgbaPicture.allocate(4, 4, true);
            java.util.Arrays.fill(alpha.pixels(), (byte) 7);
            var opaque = RgbaPicture.allocate(4, 4, false);
            for (var i = 0; i < 16; i++) {
                for (var c = 0; c < 3; c++) {
                    opaque.pixels()[4 * i + c] = 7;
                }
            }
            assertArrayEquals(JpegLsEncoder.encode(opaque), JpegLsEncoder.encode(alpha));
        }

        @Test
        @DisplayName("write to a sink the bytes encode returns")
        void sink() {
            var picture = gray(9, 7, JpegLsVectors.synthetic(9, 7, 1, 8, 3, null)[0]);
            var out = new ByteArrayOutputStream();
            JpegLsEncoder.encode(picture, JpegLsEncoderOptions.defaults(), MediaSink.of(out));
            assertArrayEquals(JpegLsEncoder.encode(picture), out.toByteArray());
        }
    }

    @Nested
    @DisplayName("settings")
    class Settings {
        @Test
        @DisplayName("reject settings that do not fit the picture")
        void picture() {
            var gray = gray(8, 8, new int[64]);
            assertThrows(IllegalArgumentException.class,
                    () -> JpegLsEncoder.encode(gray, JpegLsEncoderOptions.builder().precision(2).nearLossless(2).build()));
            var subsampled = YuvPicture.allocate(8, 8, ChromaFormat.YUV420, false, ColorInfo.JPEG);
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(subsampled,
                    JpegLsEncoderOptions.builder().interleave(JpegLsEncoderOptions.Interleave.SAMPLE).build()));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(gray,
                    JpegLsEncoderOptions.builder().colorTransform(JpegLsEncoderOptions.ColorTransform.HP1).build()));
            var rgb = RgbaPicture.allocate(8, 8, false);
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(rgb, JpegLsEncoderOptions.builder()
                    .colorTransform(JpegLsEncoderOptions.ColorTransform.HP2).nearLossless(1).build()));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(rgb, JpegLsEncoderOptions.builder()
                    .colorTransform(JpegLsEncoderOptions.ColorTransform.HP2).precision(10).build()));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(gray, JpegLsEncoderOptions.builder()
                    .nearLossless(3).presetParameters(new JpegLsEncoderOptions.PresetParameters(2, 0, 0, 0)).build()));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoder.encode(gray, JpegLsEncoderOptions.builder()
                    .presetParameters(new JpegLsEncoderOptions.PresetParameters(0, 0, 0, 2)).build()));
        }

        @Test
        @DisplayName("check their own ranges and round trip through toBuilder")
        void builder() {
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoderOptions.builder().nearLossless(256));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoderOptions.builder().precision(1));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoderOptions.builder().precision(17));
            assertThrows(IllegalArgumentException.class, () -> JpegLsEncoderOptions.builder().restartInterval(-1));
            assertThrows(IllegalArgumentException.class, () -> new JpegLsEncoderOptions.PresetParameters(70000, 0, 0, 0));
            assertThrows(NullPointerException.class, () -> JpegLsEncoderOptions.builder().interleave(null));
            var options = JpegLsEncoderOptions.builder().nearLossless(3).interleave(JpegLsEncoderOptions.Interleave.SAMPLE)
                    .precision(12).presetParameters(new JpegLsEncoderOptions.PresetParameters(4, 9, 22, 100))
                    .colorTransform(JpegLsEncoderOptions.ColorTransform.HP3).restartInterval(5).spiffHeader(true)
                    .build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().automaticInterleave().build());
            assertEquals(JpegLsEncoderOptions.defaults(), options.toBuilder().nearLossless(0).automaticInterleave()
                    .automaticPrecision().defaultPresetParameters()
                    .colorTransform(JpegLsEncoderOptions.ColorTransform.NONE).restartInterval(0).spiffHeader(false)
                    .build());
            assertEquals(3, JpegLsEncoderOptions.ofNearLossless(3).nearLossless());
            assertEquals("JpegLsEncoderOptions[nearLossless=0, interleave=automatic, precision=automatic,"
                    + " colorTransform=NONE]", JpegLsEncoderOptions.defaults().toString());
        }
    }

    @Test
    @DisplayName("decodes the encoder's oversize and restart streams, and rejects JPEG-LS part 2 frames")
    void unsupported() {
        var jls = JpegLsEncoder.encode(gray(9, 7, new int[63]));
        var extended = jls.clone();
        extended[JpegLsWriterTest.indexOf(jls, 0xF7) + 1] = (byte) 0xF9;
        assertEquals("jpeg-ls extensions", assertThrows(MediaException.Unsupported.class,
                () -> JpegDecoder.decode(MediaSource.of(extended))).format());
    }
}
