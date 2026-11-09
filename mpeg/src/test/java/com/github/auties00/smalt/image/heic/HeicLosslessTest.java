package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.image.heic.HeicSamples.bitDepth;
import static com.github.auties00.smalt.image.heic.HeicSamples.fast;
import static com.github.auties00.smalt.image.heic.HeicSamples.parse;
import static com.github.auties00.smalt.image.heic.HeicSamples.profileIdc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers lossless HEIC coding: RGB pictures as 4:4:4 with the identity matrix and planar pictures in their own
 * chroma format, alone, with alpha, in grids and in image sequences; Smalt's decoder must return the source samples
 * exactly.
 */
@DisplayName("Lossless HEIC")
class HeicLosslessTest {
    private static final Tolerance EXACT = Tolerance.exact();

    /** Returns an 8-bit picture of noise, edges and gradients, which no lossy coding keeps exactly. */
    static RgbaPicture noisy(int width, int height, long seed, boolean alpha) {
        var random = new Random(seed);
        var picture = RgbaPicture.allocate(width, height, alpha);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                picture.pixels()[at] = (byte) (x * 7 + random.nextInt(5));
                picture.pixels()[at + 1] = (byte) (((x / 5 + y / 3) & 1) == 0 ? 250 : 3 + random.nextInt(9));
                picture.pixels()[at + 2] = (byte) random.nextInt(256);
                picture.pixels()[at + 3] = (byte) (alpha ? (x * 11 + y * 3) & 0xFF : 255);
            }
        }
        return picture;
    }

    /** Returns the tight RGBA samples of a picture, alpha replaced by 255 when the picture carries none. */
    static byte[] rgba(Picture picture, boolean alpha) {
        var rgba = ImageOps.toRgba(picture);
        var out = new byte[rgba.width() * rgba.height() * 4];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rgba.width() * 4, rgba.width() * 4);
        }
        if (!alpha) {
            for (var i = 3; i < out.length; i += 4) {
                out[i] = (byte) 255;
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}x{1} alpha={2} grid={3}")
    @CsvSource({"61,47,false,0", "64,48,true,0", "37,23,true,0", "1,1,false,0", "150,100,false,64", "130,70,true,64"})
    @DisplayName("an 8-bit RGB picture decodes to its exact pixels, coded as 4:4:4 with the identity matrix")
    void rgb(int width, int height, boolean alpha, int grid) {
        var source = noisy(width, height, width * 31L + height, alpha);
        var builder = HeicEncoderOptions.forLossless().toBuilder().threads(1);
        if (grid > 0) {
            builder.gridTileSize(grid);
        }
        var bytes = HeicEncoder.encode(source, builder.build());
        var file = parse(bytes);
        assertTrue(file.brands().contains("heix"), file.brands().toString());
        var nclx = file.primaryItem().property(HeifProperty.ColorNclx.class);
        assertEquals(MatrixCoefficients.IDENTITY.code(), nclx.matrix());
        assertTrue(nclx.fullRange());
        var decoded = HeicDecoder.decode(MediaSource.of(bytes));
        assertEquals(width, decoded.picture().width());
        assertEquals(height, decoded.picture().height());
        EXACT.assertWithin("pixels", rgba(source, alpha), rgba(decoded.picture(), alpha));
    }

    @Test
    @DisplayName("the image item announces Main 4:4:4 Still Picture with 4:4:4 chroma and no clean aperture")
    void signaling() {
        var bytes = HeicEncoder.encode(noisy(33, 17, 1, false), HeicEncoderOptions.forLossless());
        var item = parse(bytes).primaryItem();
        var hvcC = item.property(HeifProperty.HevcConfiguration.class).record();
        assertEquals(4, profileIdc(hvcC));
        assertEquals(8, bitDepth(hvcC));
        assertEquals(3, hvcC[16] & 3, "chromaFormat");
        assertTrue(item.properties().stream().noneMatch(HeifProperty.CleanAperture.class::isInstance),
                "4:4:4 needs no clean aperture for odd sizes");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ChromaFormat.class, names = {"YUV420", "YUV422", "YUV444"})
    @DisplayName("planar pictures keep their chroma format, depth and samples")
    void planar(ChromaFormat format) {
        for (var depth : new int[]{8, 10}) {
            var random = new Random(depth * 7L + format.ordinal());
            var width = 45;
            var height = 31;
            var source = Yuv16Picture.allocate(width, height, format, depth, false, ColorInfo.BT709_LIMITED);
            for (var plane : List.of(source.y(), source.u(), source.v())) {
                for (var i = 0; i < plane.length; i++) {
                    plane[i] = (short) random.nextInt(1 << depth);
                }
            }
            var bytes = HeicEncoder.encode(source, HeicEncoderOptions.forLossless().toBuilder().threads(1).build());
            var decoded = HeicDecoder.decode(MediaSource.of(bytes), ImageDecoderOptions.builder().highBitDepth(true)
                    .build()).picture();
            assertEquals(format, switch (decoded) {
                case YuvPicture p -> p.chromaFormat();
                case Yuv16Picture p -> p.chromaFormat();
                default -> throw new AssertionError(decoded.toString());
            });
            EXACT.assertWithin(format + " " + depth + "-bit", planes(source), planes(decoded), depth);
        }
    }

    @Test
    @DisplayName("a monochrome picture keeps its luma exactly, with neutral chroma")
    void monochrome() {
        var source = Yuv16Picture.allocate(20, 14, ChromaFormat.MONOCHROME, 8, false, ColorInfo.BT709_LIMITED);
        var random = new Random(3);
        for (var i = 0; i < source.y().length; i++) {
            source.y()[i] = (short) random.nextInt(256);
        }
        var decoded = assertInstanceOf(YuvPicture.class,
                HeicDecoder.decode(MediaSource.of(HeicEncoder.encode(source, HeicEncoderOptions.forLossless())))
                        .picture());
        for (var y = 0; y < 14; y++) {
            for (var x = 0; x < 20; x++) {
                assertEquals(source.y()[y * source.yStride() + x], decoded.y()[y * decoded.yStride() + x] & 0xFF);
            }
        }
    }

    @Test
    @DisplayName("an animation decodes frame by frame to its exact pixels")
    void animation() {
        var sink = MediaSink.memory();
        var frames = new ArrayList<RgbaPicture>();
        try (var writer = HeicEncoder.openAnimationWriter(37, 23, fast().lossless(true).build(), sink)) {
            for (var i = 0; i < 6; i++) {
                var frame = noisy(37, 23, i, i >= 2);
                frames.add(frame);
                writer.write(frame, 40_000);
            }
            writer.finish();
        }
        var bytes = sink.toByteArray();
        assertTrue(parse(bytes).brands().contains("hevx"), parse(bytes).brands().toString());
        try (var source = MediaSource.of(bytes); var reader = HeicDecoder.openAnimation(source,
                ImageDecoderOptions.defaults())) {
            var index = 0;
            for (AnimationFrame frame = reader.read(); frame != null; frame = reader.read()) {
                EXACT.assertWithin("frame " + index, rgba(frames.get(index), index >= 2),
                        rgba(frame.picture(), index >= 2));
                index++;
            }
            assertEquals(frames.size(), index);
        }
    }

    @Test
    @DisplayName("a lossless request through Images codes the picture losslessly")
    void provider() {
        var source = noisy(29, 19, 9, false);
        var bytes = Images.encode(source, ImageFormat.HEIC, ImageEncoderOptions.lossless());
        var decoded = Images.decode(MediaSource.of(bytes));
        assertEquals(ImageFormat.HEIC, decoded.format());
        EXACT.assertWithin("pixels", rgba(source, false), rgba(decoded.picture(), false));
    }

    @Test
    @DisplayName("a lossless request through Images rejects samples deeper than 10 bits unless they are exactly 8-bit")
    void providerDepth() {
        var source = noisy(13, 9, 21, true);
        var widened = ImageOps.toSixteenBit(source);
        var exact = Images.decode(MediaSource.of(Images.encode(widened, ImageFormat.HEIC,
                ImageEncoderOptions.lossless())));
        EXACT.assertWithin("widened pixels", rgba(source, true), rgba(exact.picture(), true));
        var deep = ImageOps.toSixteenBit(source);
        // one sample that no 8-bit value widens to
        deep.pixels()[deep.stride() * 8 + 4 * 12 + 1] += 1;
        var exception = assertThrows(MediaException.Unsupported.class,
                () -> Images.encode(deep, ImageFormat.HEIC, ImageEncoderOptions.lossless()));
        assertTrue(exception.getMessage().contains("cannot be coded losslessly"), exception.getMessage());
    }

    @Test
    @DisplayName("the identity matrix is accepted only for lossless images, and YCgCo-R never")
    void validation() {
        var identity = ColorInfo.JPEG.withMatrix(MatrixCoefficients.IDENTITY);
        assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder().color(identity).build());
        HeicEncoderOptions.builder().color(identity).lossless(true).build();
        assertThrows(IllegalArgumentException.class, () -> HeicEncoderOptions.builder()
                .color(ColorInfo.JPEG.withMatrix(MatrixCoefficients.YCGCO_RE)).lossless(true).build());
        var options = HeicEncoderOptions.forLossless();
        assertTrue(options.lossless());
        assertEquals(options, options.toBuilder().build());
        assertTrue(options.toString().contains("lossless=true"));
        // a planar picture with the identity matrix is coded as it is
        var gbr = ImageOps.toYuv(noisy(16, 12, 4, false), ChromaFormat.YUV444, identity);
        var decoded = HeicDecoder.decode(MediaSource.of(HeicEncoder.encode(gbr, options))).picture();
        assertEquals(ColorRange.FULL, ((YuvPicture) decoded).color().range());
        EXACT.assertWithin("identity planes", planes(gbr), planes(decoded), 8);
    }

    /** Returns the planes of a Y'CbCr picture as 16-bit samples. */
    private static short[] planes(Picture picture) {
        var yuv = picture instanceof Yuv16Picture p ? p : ImageOps.toBitDepth((YuvPicture) picture, 8);
        var cw = yuv.chromaWidth();
        var ch = yuv.chromaHeight();
        var out = new short[yuv.width() * yuv.height() + 2 * cw * ch];
        var at = 0;
        for (var y = 0; y < yuv.height(); y++) {
            System.arraycopy(yuv.y(), y * yuv.yStride(), out, at, yuv.width());
            at += yuv.width();
        }
        for (var plane : List.of(yuv.u(), yuv.v())) {
            for (var y = 0; y < ch; y++) {
                System.arraycopy(plane, y * yuv.uvStride(), out, at, cw);
                at += cw;
            }
        }
        return out;
    }
}
