package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Decoder;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.HeifBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.github.auties00.smalt.testkit.HeifBuilder.essential;
import static com.github.auties00.smalt.testkit.HeifBuilder.plain;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes HEIF files with JPEG items (ISO/IEC 23008-12 Annex H) and JPEG 2000 items (ISO/IEC
 * 15444-16), whose payloads Smalt's own JPEG and JPEG 2000 encoders write, against the direct
 * decode of each payload. These tests run here because the HEIC decoder of {@code smalt-mpeg} finds
 * the codecs of {@code smalt-image} at run time.
 */
@DisplayName("HEIF with JPEG and JPEG 2000 images")
class HeicJpegJpeg2000Test {
    private static final ImageDecoderOptions WIDE = ImageDecoderOptions.builder().highBitDepth(true).build();

    private static RgbaPicture pattern(int width, int height, long seed, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        var random = new Random(seed);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + 4 * x;
                pixels[at] = (byte) (x * 7 + y);
                pixels[at + 1] = (byte) (y * 5 + random.nextInt(16));
                pixels[at + 2] = (byte) (128 + x - y);
                pixels[at + 3] = (byte) (alpha ? x * 9 + y * 3 : 255);
            }
        }
        return picture;
    }

    private static int[][] planes(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> {
                var out = new int[4][];
                out[0] = unpack(yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    out[1] = unpack(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    out[2] = unpack(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                if (yuv.hasAlpha()) {
                    out[3] = unpack(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
                yield out;
            }
            case Yuv16Picture yuv -> {
                var out = new int[4][];
                out[0] = unpack(yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                if (yuv.chromaFormat().hasChroma()) {
                    out[1] = unpack(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                    out[2] = unpack(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                }
                if (yuv.hasAlpha()) {
                    out[3] = unpack(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
                yield out;
            }
            default -> throw new AssertionError("not planar: " + picture);
        };
    }

    private static int[] unpack(byte[] plane, int stride, int width, int height) {
        var out = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out[y * width + x] = plane[y * stride + x] & 0xFF;
            }
        }
        return out;
    }

    private static int[] unpack(short[] plane, int stride, int width, int height) {
        var out = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out[y * width + x] = plane[y * stride + x] & 0xFFFF;
            }
        }
        return out;
    }

    private static void assertSamePlanes(Picture expected, Picture actual) {
        assertEquals(expected.width(), actual.width(), "width");
        assertEquals(expected.height(), actual.height(), "height");
        var want = planes(expected);
        var got = planes(actual);
        for (var plane = 0; plane < 4; plane++) {
            assertArrayEquals(want[plane], got[plane], "plane " + plane);
        }
    }

    private static HeifBuilder heif(String brand) {
        return new HeifBuilder().brands(brand, brand, "mif1", "miaf");
    }

    @Nested
    @DisplayName("JPEG items")
    class Jpeg {
        private static byte[] jpeg(Picture picture) {
            return JpegEncoder.encode(picture);
        }

        private static int startOfScan(byte[] jpeg) {
            for (var i = 2; i + 1 < jpeg.length; i++) {
                if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA) {
                    return i;
                }
            }
            throw new AssertionError("no SOS marker");
        }

        @Test
        @DisplayName("an item decodes to the planes of the JPEG decoder, with or without a jpgC property")
        void item() {
            var jpeg = jpeg(pattern(70, 45, 1, false));
            var expected = JpegDecoder.decode(MediaSource.of(jpeg)).picture();
            var whole = heif("jpeg");
            whole.primary(whole.item("jpeg", jpeg, plain(HeifBuilder.ispe(70, 45))));
            assertSamePlanes(expected, HeicDecoder.decode(MediaSource.of(whole.build())).picture());
            var split = startOfScan(jpeg);
            var configured = heif("jpeg");
            configured.primary(configured.item("jpeg", Arrays.copyOfRange(jpeg, split, jpeg.length),
                    plain(HeifBuilder.ispe(70, 45)), essential(HeifBuilder.box("jpgC", Arrays.copyOf(jpeg, split)))));
            assertSamePlanes(expected, HeicDecoder.decode(MediaSource.of(configured.build())).picture());
            var info = HeicDecoder.probe(MediaSource.of(configured.build()));
            assertEquals(70, info.width());
            assertEquals(45, info.height());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ChromaFormat.class, names = {"MONOCHROME", "YUV422", "YUV444"})
        @DisplayName("an item of another chroma format decodes to the planes of the JPEG decoder")
        void chromaFormats(ChromaFormat format) {
            var jpeg = JpegEncoder.encode(pattern(33, 21, 7, false), JpegEncoderOptions.builder().chromaFormat(format)
                    .build());
            var expected = JpegDecoder.decode(MediaSource.of(jpeg)).picture();
            var builder = heif("jpeg");
            builder.primary(builder.item("jpeg", jpeg, plain(HeifBuilder.ispe(33, 21))));
            var decoded = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(format, decoded.chromaFormat());
            assertSamePlanes(expected, decoded);
        }

        @Test
        @DisplayName("a grid of JPEG tiles is stitched from the tile decodes")
        void grid() {
            var tiles = new ArrayList<Picture>();
            var builder = heif("jpeg");
            var ids = new int[4];
            for (var i = 0; i < 4; i++) {
                var jpeg = jpeg(pattern(32, 32, 10 + i, false));
                tiles.add(JpegDecoder.decode(MediaSource.of(jpeg)).picture());
                ids[i] = builder.item("jpeg", jpeg, plain(HeifBuilder.ispe(32, 32)));
                builder.hidden(ids[i]);
            }
            var grid = builder.idatItem("grid", HeifBuilder.grid(2, 2, 64, 64), plain(HeifBuilder.ispe(64, 64)));
            builder.reference("dimg", grid, ids).primary(grid);
            var decoded = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            for (var i = 0; i < 4; i++) {
                var tile = (YuvPicture) tiles.get(i);
                var x0 = (i % 2) * 32;
                var y0 = (i / 2) * 32;
                for (var y = 0; y < 32; y++) {
                    for (var x = 0; x < 32; x++) {
                        assertEquals(tile.y()[y * tile.yStride() + x], decoded.y()[(y0 + y) * decoded.yStride() + x0 + x]);
                    }
                }
            }
        }

        @Test
        @DisplayName("the color properties of the item replace the JPEG color")
        void color() {
            var jpeg = jpeg(pattern(16, 16, 2, false));
            var builder = heif("jpeg");
            builder.primary(builder.item("jpeg", jpeg, plain(HeifBuilder.ispe(16, 16)),
                    plain(HeifBuilder.nclx(1, 1, 1, false))));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(MatrixCoefficients.BT709, picture.color().matrix());
            assertEquals(ColorRange.LIMITED, picture.color().range());
        }

        @Test
        @DisplayName("an item that holds no JPEG stream is malformed")
        void notJpeg() {
            var builder = heif("jpeg");
            builder.primary(builder.item("jpeg", new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, plain(HeifBuilder.ispe(8, 8))));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(builder.build())));
        }
    }

    @Nested
    @DisplayName("JPEG 2000 items")
    class Jpeg2000 {
        private static byte[] codestream(Picture picture, boolean transform) {
            return Jp2Encoder.encode(picture, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                    .colorTransform(transform).build());
        }

        private static byte[] j2kH(byte[]... boxes) {
            var out = new ByteArrayOutputStream();
            for (var box : boxes) {
                out.writeBytes(box);
            }
            return HeifBuilder.box("j2kH", out.toByteArray());
        }

        private static byte[] cdef(int... triples) {
            var out = new ByteArrayOutputStream();
            HeifBuilder.u16(out, triples.length / 3);
            for (var value : triples) {
                HeifBuilder.u16(out, value);
            }
            return HeifBuilder.box("cdef", out.toByteArray());
        }

        @Test
        @DisplayName("an RGB codestream with the component transform decodes to identity-matrix planes of its samples")
        void rgb() {
            var source = pattern(40, 30, 3, false);
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", codestream(source, true), plain(HeifBuilder.ispe(40, 30)),
                    essential(j2kH())));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            var rgba = ImageOps.toRgba(picture);
            for (var y = 0; y < 30; y++) {
                for (var x = 0; x < 40; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(source.pixels()[y * source.stride() + 4 * x + c], rgba.pixels()[y * rgba.stride() + 4 * x + c],
                                "sample " + c + " at " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("10-bit Y'CbCr 4:2:0 channels keep their depth and subsampling under an nclx property")
        void ycbcr() {
            var width = 36;
            var height = 22;
            var random = new Random(4);
            var channels = new ArrayList<Jp2Channel>();
            var expected = new int[3][];
            for (var c = 0; c < 3; c++) {
                var s = c == 0 ? 1 : 2;
                var w = (width + s - 1) / s;
                var h = (height + s - 1) / s;
                var samples = new int[w * h];
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = random.nextInt(1024);
                }
                expected[c] = samples;
                channels.add(new Jp2Channel(0, 0, w, h, s, s, 10, false, Jp2ChannelType.COLOR, c + 1, samples));
            }
            var image = new Jp2Image(0, 0, width, height, width, height, 0, channels, Jp2ColorSpace.UNSPECIFIED,
                    Optional.empty(), Optional.empty(), Optional.empty());
            var codestream = Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                    .colorTransform(false).build());
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", codestream, plain(HeifBuilder.ispe(width, height)), essential(j2kH()),
                    plain(HeifBuilder.nclx(1, 1, 1, false))));
            var bytes = builder.build();
            var picture = assertInstanceOf(Yuv16Picture.class, HeicDecoder.decode(MediaSource.of(bytes), WIDE).picture());
            assertEquals(10, picture.bitDepth());
            assertEquals(ChromaFormat.YUV420, picture.chromaFormat());
            assertEquals(MatrixCoefficients.BT709, picture.color().matrix());
            var got = planes(picture);
            for (var c = 0; c < 3; c++) {
                assertArrayEquals(expected[c], got[c], "channel " + c);
            }
            var narrow = HeicDecoder.decode(MediaSource.of(bytes)).picture();
            assertSamePlanes(ImageOps.toEightBit(picture), narrow);
        }

        @Test
        @DisplayName("an opacity channel declared by cdef becomes the alpha plane; premultiplied opacity is divided back")
        void alpha() {
            var source = pattern(24, 16, 5, true);
            var codestream = codestream(source, true);
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", codestream, plain(HeifBuilder.ispe(24, 16)),
                    essential(j2kH(cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 1, 0)))));
            var bytes = builder.build();
            assertTrue(HeicDecoder.probe(MediaSource.of(bytes)).hasAlpha());
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(bytes)).picture());
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 24; x++) {
                    assertEquals(source.pixels()[y * source.stride() + 4 * x + 3], picture.alpha()[y * picture.alphaStride() + x]);
                }
            }
            var premultiplied = heif("j2ki");
            premultiplied.primary(premultiplied.item("j2k1", codestream, plain(HeifBuilder.ispe(24, 16)),
                    essential(j2kH(cdef(0, 0, 1, 1, 0, 2, 2, 0, 3, 3, 2, 0)))));
            var divided = assertInstanceOf(RgbaPicture.class,
                    HeicDecoder.decode(MediaSource.of(premultiplied.build())).picture());
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 24; x++) {
                    var in = y * source.stride() + 4 * x;
                    var out = y * divided.stride() + 4 * x;
                    var a = source.pixels()[in + 3] & 0xFF;
                    assertEquals(a, divided.pixels()[out + 3] & 0xFF, "alpha at " + x + "," + y);
                    for (var c = 0; c < 3; c++) {
                        var stored = source.pixels()[in + c] & 0xFF;
                        var want = a == 0 ? 0 : a == 255 ? stored : Math.min(255, (stored * 510 + a) / (2 * a));
                        assertEquals(want, divided.pixels()[out + c] & 0xFF, "sample " + c + " at " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("a palette and its component mapping expand an index codestream into RGB")
        void palette() {
            var width = 18;
            var height = 10;
            var entries = 5;
            var colors = new int[entries][3];
            var random = new Random(9);
            for (var e = 0; e < entries; e++) {
                for (var c = 0; c < 3; c++) {
                    colors[e][c] = random.nextInt(256);
                }
            }
            var indices = new int[width * height];
            for (var i = 0; i < indices.length; i++) {
                indices[i] = (i * 3 + i / width) % entries;
            }
            var image = new Jp2Image(0, 0, width, height, width, height, 0,
                    List.of(new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, 1, indices)),
                    Jp2ColorSpace.GRAYSCALE, Optional.empty(), Optional.empty(), Optional.empty());
            var codestream = Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                    .build());
            var pclr = new ByteArrayOutputStream();
            HeifBuilder.u16(pclr, entries);
            pclr.write(3);
            for (var c = 0; c < 3; c++) {
                pclr.write(7);
            }
            for (var e = 0; e < entries; e++) {
                for (var c = 0; c < 3; c++) {
                    pclr.write(colors[e][c]);
                }
            }
            var cmap = new ByteArrayOutputStream();
            for (var c = 0; c < 3; c++) {
                HeifBuilder.u16(cmap, 0);
                cmap.write(1);
                cmap.write(c);
            }
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", codestream, plain(HeifBuilder.ispe(width, height)),
                    essential(j2kH(HeifBuilder.box("pclr", pclr.toByteArray()), HeifBuilder.box("cmap", cmap.toByteArray())))));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
            assertEquals(MatrixCoefficients.IDENTITY, picture.color().matrix());
            var rgba = ImageOps.toRgba(picture);
            for (var i = 0; i < indices.length; i++) {
                var at = (i / width) * rgba.stride() + 4 * (i % width);
                for (var c = 0; c < 3; c++) {
                    assertEquals(colors[indices[i]][c], rgba.pixels()[at + c] & 0xFF, "sample " + c + " of pixel " + i);
                }
            }
        }

        @Test
        @DisplayName("a gray codestream decodes to a monochrome picture equal to the JPEG 2000 channel")
        void gray() {
            var width = 20;
            var height = 12;
            var samples = new int[width * height];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (i * 37) & 0xFF;
            }
            var image = new Jp2Image(0, 0, width, height, width, height, 0,
                    List.of(new Jp2Channel(0, 0, width, height, 1, 1, 8, false, Jp2ChannelType.COLOR, 1, samples)),
                    Jp2ColorSpace.GRAYSCALE, Optional.empty(), Optional.empty(), Optional.empty());
            var codestream = Jp2Encoder.encode(image, Jp2EncoderOptions.lossless().toBuilder().format(Jp2Format.CODESTREAM)
                    .build());
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", codestream, plain(HeifBuilder.ispe(width, height)), essential(j2kH())));
            var picture = assertInstanceOf(YuvPicture.class, HeicDecoder.decode(MediaSource.of(builder.build())).picture());
            assertEquals(ChromaFormat.MONOCHROME, picture.chromaFormat());
            assertArrayEquals(samples, planes(picture)[0]);
            assertArrayEquals(Jp2Decoder.decodeChannels(MediaSource.of(codestream)).channels().getFirst().samples(),
                    planes(picture)[0]);
        }

        @Test
        @DisplayName("an item that holds no codestream is malformed")
        void notCodestream() {
            var builder = heif("j2ki");
            builder.primary(builder.item("j2k1", new byte[64], plain(HeifBuilder.ispe(8, 8)), essential(j2kH())));
            assertThrows(MediaException.Malformed.class, () -> HeicDecoder.decode(MediaSource.of(builder.build())));
        }
    }
}
