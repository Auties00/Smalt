package com.github.auties00.smalt.video.vp9;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the picture formats and color descriptions of the public VP9 encoder: the options that
 * select a profile, the conversion of pictures to the coded format and color, the key frame header
 * fields, and the decoder handing the format and color back.
 */
@DisplayName("VP9 encoder formats and colors")
class Vp9EncoderFormatTest {
    static final int WIDTH = 96;

    static final int HEIGHT = 64;

    static List<Packet> encode(Vp9EncoderOptions options, List<? extends Picture> pictures) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Vp9Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < pictures.size(); i++) {
                encoder.encode(new VideoFrame(pictures.get(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    static List<Picture> decode(List<Packet> packets, boolean highBitDepth) {
        var track = VideoTrack.builder().id(1).codec(Codec.VP9).timeBase(new Rational(1, 90_000)).width(WIDTH)
                .height(HEIGHT).build();
        var options = highBitDepth ? VideoDecoderOptions.builder().highBitDepth(true).build()
                : VideoDecoderOptions.defaults();
        var out = new ArrayList<Picture>();
        try (var decoder = new Vp9Decoder(track, options)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> out.add(copy(frame.picture())));
            }
        }
        return out;
    }

    static Picture copy(Picture picture) {
        if (picture instanceof Yuv16Picture yuv) {
            var copy = Yuv16Picture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), yuv.bitDepth(), false,
                    yuv.color());
            for (var row = 0; row < yuv.height(); row++) {
                System.arraycopy(yuv.y(), row * yuv.yStride(), copy.y(), row * copy.yStride(), yuv.width());
            }
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                System.arraycopy(yuv.u(), row * yuv.uvStride(), copy.u(), row * copy.uvStride(), yuv.chromaWidth());
                System.arraycopy(yuv.v(), row * yuv.uvStride(), copy.v(), row * copy.uvStride(), yuv.chromaWidth());
            }
            return copy;
        }
        if (picture instanceof YuvPicture yuv) {
            var copy = YuvPicture.allocate(yuv.width(), yuv.height(), yuv.chromaFormat(), false, yuv.color());
            for (var row = 0; row < yuv.height(); row++) {
                System.arraycopy(yuv.y(), row * yuv.yStride(), copy.y(), row * copy.yStride(), yuv.width());
            }
            for (var row = 0; row < yuv.chromaHeight(); row++) {
                System.arraycopy(yuv.u(), row * yuv.uvStride(), copy.u(), row * copy.uvStride(), yuv.chromaWidth());
                System.arraycopy(yuv.v(), row * yuv.uvStride(), copy.v(), row * copy.uvStride(), yuv.chromaWidth());
            }
            return copy;
        }
        throw new AssertionError("unexpected picture " + picture);
    }

    static double psnr(YuvPicture a, YuvPicture b) {
        long sse = 0;
        long n = 0;
        for (var row = 0; row < a.height(); row++) {
            for (var column = 0; column < a.width(); column++) {
                var d = (a.y()[row * a.yStride() + column] & 0xFF) - (b.y()[row * b.yStride() + column] & 0xFF);
                sse += (long) d * d;
                n++;
            }
        }
        return sse == 0 ? 99 : 10 * Math.log10(255.0 * 255 * n / sse);
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the picture format decides the profile")
        void profile() {
            assertEquals(0, Vp9EncoderOptions.defaults().profile());
            assertEquals(Vp9Subsampling.YUV420, Vp9EncoderOptions.defaults().subsampling());
            assertEquals(8, Vp9EncoderOptions.defaults().bitDepth());
            assertEquals(ColorInfo.BT601_LIMITED, Vp9EncoderOptions.defaults().color());
            var builder = Vp9EncoderOptions.builder();
            assertEquals(1, builder.pictureFormat(Vp9Subsampling.YUV422, 8).build().profile());
            assertEquals(1, builder.pictureFormat(Vp9Subsampling.YUV440, 8).build().profile());
            assertEquals(1, builder.pictureFormat(Vp9Subsampling.YUV444, 8).build().profile());
            assertEquals(2, builder.pictureFormat(Vp9Subsampling.YUV420, 10).build().profile());
            assertEquals(2, builder.pictureFormat(Vp9Subsampling.YUV420, 12).build().profile());
            assertEquals(3, builder.pictureFormat(Vp9Subsampling.YUV444, 12).build().profile());
            assertEquals(Vp9Subsampling.YUV422, Vp9Subsampling.of(ChromaFormat.YUV422));
            assertThrows(IllegalArgumentException.class, () -> Vp9Subsampling.of(ChromaFormat.MONOCHROME));
        }

        @Test
        @DisplayName("build() rejects a bit depth VP9 cannot code, unsignalled matrices and partial sRGB")
        void validation() {
            assertThrows(IllegalArgumentException.class,
                    () -> Vp9EncoderOptions.builder().pictureFormat(Vp9Subsampling.YUV420, 9).build());
            assertThrows(IllegalArgumentException.class, () -> Vp9EncoderOptions.builder()
                    .color(ColorInfo.BT601_LIMITED.withMatrix(MatrixCoefficients.YCGCO)).build());
            var srgb = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                    TransferCharacteristics.SRGB);
            assertThrows(IllegalArgumentException.class, () -> Vp9EncoderOptions.builder().color(srgb).build());
            assertThrows(IllegalArgumentException.class, () -> Vp9EncoderOptions.builder().color(srgb)
                    .pictureFormat(Vp9Subsampling.YUV444, 8).build().toBuilder()
                    .color(srgb.withRange(ColorRange.LIMITED)).build());
            Vp9EncoderOptions.builder().color(srgb).pictureFormat(Vp9Subsampling.YUV444, 8).build();
            assertThrows(NullPointerException.class, () -> Vp9EncoderOptions.builder().color(null));
            assertThrows(NullPointerException.class, () -> Vp9EncoderOptions.builder().pictureFormat(null, 8));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Vp9Subsampling.class)
    @DisplayName("8-bit pictures of every subsampling code in profile 0 or 1 and decode in their format")
    void subsamplings(Vp9Subsampling subsampling) {
        var format = subsampling == Vp9Subsampling.YUV440 ? ChromaFormat.YUV444 : subsampling == Vp9Subsampling.YUV422
                ? ChromaFormat.YUV422 : subsampling == Vp9Subsampling.YUV444 ? ChromaFormat.YUV444 : ChromaFormat.YUV420;
        var pictures = Vp9TestVideo.synthesize(WIDTH, HEIGHT, 6, format);
        var options = Vp9EncoderOptions.builder().pictureFormat(subsampling, 8).bitrate(2_000_000).build();
        var packets = encode(options, pictures);
        assertEquals(6, packets.size());
        var header = VideoConfigs.vp9KeyFrame(packets.getFirst().data());
        assertNotNull(header);
        assertEquals(options.profile(), header.profile());
        assertEquals(subsampling.shiftX(), header.subsamplingX());
        assertEquals(subsampling.shiftY(), header.subsamplingY());
        assertEquals(3, header.colorSpace());
        if (subsampling == Vp9Subsampling.YUV440) {
            return;
        }
        var decoded = decode(packets, false);
        assertEquals(6, decoded.size());
        for (var i = 0; i < 6; i++) {
            var picture = assertInstanceOf(YuvPicture.class, decoded.get(i));
            assertEquals(format, picture.chromaFormat());
            assertTrue(psnr(pictures.get(i), picture) > 30, "frame " + i + " psnr " + psnr(pictures.get(i), picture));
        }
    }

    @Test
    @DisplayName("a 4:2:0 picture coded as 4:4:4 BT.709 is converted and signals BT.709")
    void conversion() {
        var pictures = Vp9TestVideo.synthesize(WIDTH, HEIGHT, 4);
        var options = Vp9EncoderOptions.builder().pictureFormat(Vp9Subsampling.YUV444, 8)
                .color(ColorInfo.BT709_LIMITED).bitrate(2_000_000).build();
        var packets = encode(options, pictures);
        var header = VideoConfigs.vp9KeyFrame(packets.getFirst().data());
        assertNotNull(header);
        assertEquals(2, header.colorSpace());
        assertEquals(false, header.fullRange());
        var decoded = decode(packets, false);
        var picture = assertInstanceOf(YuvPicture.class, decoded.getFirst());
        assertEquals(ChromaFormat.YUV444, picture.chromaFormat());
        assertEquals(MatrixCoefficients.BT709, picture.color().matrix());
        var expected = ImageOps.toYuv(pictures.getFirst(), ChromaFormat.YUV444, ColorInfo.BT709_LIMITED);
        assertTrue(psnr(expected, picture) > 30);
    }

    @Test
    @DisplayName("8-bit pictures coded losslessly at 10 bits decode back to themselves")
    void eightBitInTenBits() {
        var pictures = Vp9TestVideo.synthesize(WIDTH, HEIGHT, 3);
        var options = Vp9EncoderOptions.builder().pictureFormat(Vp9Subsampling.YUV420, 10).quantizers(0, 0).build();
        var packets = encode(options, pictures);
        var header = VideoConfigs.vp9KeyFrame(packets.getFirst().data());
        assertNotNull(header);
        assertEquals(2, header.profile());
        assertEquals(10, header.bitDepth());
        var decoded = decode(packets, false);
        for (var i = 0; i < pictures.size(); i++) {
            var expected = pictures.get(i);
            var actual = assertInstanceOf(YuvPicture.class, decoded.get(i));
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    assertEquals(expected.y()[y * expected.yStride() + x], actual.y()[y * actual.yStride() + x]);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Vp9Subsampling.class, names = {"YUV420", "YUV422", "YUV444"})
    @DisplayName("12-bit pictures code in profile 2 or 3 and decode at full precision")
    void twelveBits(Vp9Subsampling subsampling) {
        var format = subsampling == Vp9Subsampling.YUV422 ? ChromaFormat.YUV422
                : subsampling == Vp9Subsampling.YUV444 ? ChromaFormat.YUV444 : ChromaFormat.YUV420;
        var pictures = Vp9TestVideo.synthesize16(WIDTH, HEIGHT, 5, format, 12);
        var options = Vp9EncoderOptions.builder().pictureFormat(subsampling, 12).bitrate(3_000_000).build();
        var packets = encode(options, pictures);
        var header = VideoConfigs.vp9KeyFrame(packets.getFirst().data());
        assertNotNull(header);
        assertEquals(options.profile(), header.profile());
        assertEquals(12, header.bitDepth());
        var decoded = decode(packets, true);
        for (var i = 0; i < pictures.size(); i++) {
            var actual = assertInstanceOf(Yuv16Picture.class, decoded.get(i));
            assertEquals(12, actual.bitDepth());
            assertEquals(format, actual.chromaFormat());
            var expected = pictures.get(i);
            long sse = 0;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var d = expected.y()[y * expected.yStride() + x] - actual.y()[y * actual.yStride() + x];
                    sse += (long) d * d;
                }
            }
            var psnr = 10 * Math.log10(4095.0 * 4095 * WIDTH * HEIGHT / Math.max(1, sse));
            assertTrue(psnr > 30, "frame " + i + " psnr " + psnr);
        }
    }

    @Test
    @DisplayName("an RGB picture coded as full-range 4:4:4 sRGB keeps its colors")
    void srgb() {
        var rgb = RgbaPicture.allocate(WIDTH, HEIGHT, false);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                var i = y * rgb.stride() + 4 * x;
                rgb.pixels()[i] = (byte) (x * 2);
                rgb.pixels()[i + 1] = (byte) (y * 3);
                rgb.pixels()[i + 2] = (byte) (x + y);
                rgb.pixels()[i + 3] = (byte) 255;
            }
        }
        var color = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
        var options = Vp9EncoderOptions.builder().pictureFormat(Vp9Subsampling.YUV444, 8).color(color)
                .quantizers(0, 0).build();
        var packets = encode(options, List.of(rgb, rgb));
        var header = VideoConfigs.vp9KeyFrame(packets.getFirst().data());
        assertNotNull(header);
        assertEquals(7, header.colorSpace());
        assertEquals(1, header.profile());
        var decoded = assertInstanceOf(YuvPicture.class, decode(packets, false).getFirst());
        assertEquals(MatrixCoefficients.IDENTITY, decoded.color().matrix());
        var back = ImageOps.toRgba(decoded);
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(rgb.pixels()[y * rgb.stride() + 4 * x + c], back.pixels()[y * back.stride() + 4 * x + c],
                            "pixel " + x + "," + y + " channel " + c);
                }
            }
        }
    }
}
