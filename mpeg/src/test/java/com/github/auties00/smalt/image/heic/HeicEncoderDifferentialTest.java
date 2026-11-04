package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.heic.HeicReference.Planes;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static com.github.auties00.smalt.image.heic.HeicSamples.fast;
import static com.github.auties00.smalt.image.heic.HeicSamples.parse;
import static com.github.auties00.smalt.image.heic.HeicSamples.rgba;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the HEIC files the encoder writes with ffmpeg: its HEVC decoder on every coded item (and on the grid tiles,
 * stitched here), and its ISOBMFF demuxer on whole files and image sequences. The decoded samples must equal those
 * of {@link HeicDecoder} exactly. The {@code it} module's {@code HeicEncoderInteropTest} reads them with libheif and
 * macOS ImageIO, whose PNG output needs {@code smalt-image}.
 */
@DisplayName("HEIC encoder against reference readers")
class HeicEncoderDifferentialTest {
    /** HEVC is a normative decoder: the samples of every reader are those of Smalt's decoder. */
    private static final Tolerance EXACT = Tolerance.exact();

    private static final Path DIRECTORY = Scratch.directory("heic-encoder");

    /** The encoder outputs the readers are given. */
    enum Sample {
        /** One even-sized image. */
        SINGLE(96, 64, false, 0, 8),
        /** An image of odd width and height, with a clean aperture. */
        ODD(33, 17, false, 0, 8),
        /** An image with an alpha item. */
        ALPHA(80, 48, true, 0, 8),
        /** A grid of 64-sample tiles. */
        GRID(150, 100, false, 64, 8),
        /** A grid of 64-sample tiles with an alpha grid. */
        ALPHA_GRID(100, 70, true, 64, 8),
        /** A 10-bit image. */
        TEN_BIT(64, 48, false, 0, 10);

        final int width;
        final int height;
        final boolean alpha;
        final int tile;
        final int depth;

        Sample(int width, int height, boolean alpha, int tile, int depth) {
            this.width = width;
            this.height = height;
            this.alpha = alpha;
            this.tile = tile;
            this.depth = depth;
        }

        byte[] encode() {
            var options = fast().bitDepth(depth);
            if (tile > 0) {
                options.gridTileSize(tile);
            }
            return HeicEncoder.encode(depth == 8 ? rgba(width, height, 0, alpha) : HeicSamples.rgba16(width, height,
                    alpha), options.build());
        }

        Path save() {
            return HeicEncoderDifferentialTest.save(name().toLowerCase(Locale.ROOT) + ".heic", encode());
        }
    }

    static Path save(String name, byte[] data) {
        try {
            return Files.write(DIRECTORY.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decodes a file with Smalt, keeping the source depth. */
    static Planes smalt(byte[] file) {
        return Planes.of(HeicDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().highBitDepth(true)
                .build()).picture());
    }

    /** Asserts that two planes hold the same samples. */
    static void assertPlane(String what, int[] expected, int[] actual) {
        EXACT.assertWithin(what, expected, actual);
    }

    /** Converts planes to an 8-bit picture of a color description, for the comparison of RGB frames. */
    static Picture toPicture(Planes planes, ColorInfo color) {
        var builder = YuvPicture.builder().size(planes.width(), planes.height()).chromaFormat(planes.format())
                .color(color);
        builder.luma(pack(planes.planes()[0]), planes.width());
        builder.chroma(pack(planes.planes()[1]), pack(planes.planes()[2]), planes.chromaWidth());
        return builder.build();
    }

    static byte[] pack(int[] samples) {
        var out = new byte[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (byte) samples[i];
        }
        return out;
    }

    /** Returns the RGBA pixels of a picture, 8 bits per channel. */
    static byte[] rgbaOf(Picture picture) {
        var rgba = ImageOps.toRgba(picture);
        var out = new byte[rgba.width() * rgba.height() * 4];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    @Nested
    @DisplayName("ffmpeg")
    class Ffmpeg {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Sample.class)
        @DisplayName("decodes every coded item, and the grid tiles stitched, to Smalt's samples")
        void items(Sample sample) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var bytes = sample.encode();
            var source = MediaSource.of(bytes);
            var file = parse(bytes);
            var primary = file.primaryItem();
            var actual = smalt(bytes);
            assertEquals(sample.width, actual.width());
            assertEquals(sample.height, actual.height());
            assertEquals(ChromaFormat.YUV420, actual.format());
            assertEquals(sample.depth, actual.depth());
            var codedWidth = (sample.width + 1) & ~1;
            var codedHeight = (sample.height + 1) & ~1;
            var expected = sample.tile > 0 ? HeicReference.grid(source, file, primary, ChromaFormat.YUV420,
                    sample.depth) : HeicReference.item(source, file, primary, codedWidth, codedHeight,
                    ChromaFormat.YUV420, sample.depth).crop(0, 0, sample.width, sample.height);
            for (var plane = 0; plane < 3; plane++) {
                assertPlane(sample + " plane " + plane, expected.planes()[plane], actual.planes()[plane]);
            }
            if (sample.alpha) {
                var alphaItem = file.auxiliariesOf(primary.id()).getFirst();
                var alpha = sample.tile > 0 ? HeicReference.grid(source, file, alphaItem, ChromaFormat.YUV420,
                        sample.depth) : HeicReference.item(source, file, alphaItem, codedWidth, codedHeight,
                        ChromaFormat.YUV420, sample.depth).crop(0, 0, sample.width, sample.height);
                assertPlane(sample + " alpha", alpha.planes()[0], actual.planes()[3]);
            }
        }

        // ffmpeg 7.1 rejects every file whose items carry a clean aperture property (Nokia's C013, C014, C023 and
        // MIAF007 included), so the odd-sized image, whose items it decodes above, is left out
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Sample.class, names = "ODD", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("demuxes and decodes the whole file without an error, and the container validators accept it")
        void cleanly(Sample sample) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            Oracle.assertDecodesCleanly(sample.save());
        }

        @Test
        @DisplayName("reads the primary image of a file to Smalt's samples")
        void primary() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = Sample.SINGLE.save();
            var actual = smalt(Sample.SINGLE.encode());
            var raw = Oracle.decodeVideo(path, "yuv420p", 1);
            var luma = actual.width() * actual.height();
            var expected = new int[luma];
            for (var i = 0; i < luma; i++) {
                expected[i] = raw[i] & 0xFF;
            }
            assertPlane("ffmpeg luma", expected, actual.planes()[0]);
        }

        @ParameterizedTest(name = "inter frames {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("plays an image sequence frame by frame as Smalt does")
        void sequence(boolean interFrames) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var durations = HeicSequenceEncoderTest.constant(8, 40_000);
            var bytes = HeicSequenceEncoderTest.animation(64, 48, fast().interFrames(interFrames).build(), durations,
                    -1);
            var path = save("sequence-" + interFrames + ".heic", bytes);
            Oracle.assertDecodesCleanly(path);
            var frames = HeicSequenceEncoderTest.play(bytes);
            var expected = HeicReference.decodeFile(path, 64, 48, ChromaFormat.YUV420, 8, true);
            assertEquals(frames.size(), expected.size(), "frames against ffmpeg");
            for (var i = 0; i < frames.size(); i++) {
                EXACT.assertWithin("frame " + i, rgbaOf(toPicture(expected.get(i), ColorInfo.JPEG)),
                        rgbaOf(frames.get(i).picture()));
            }
        }
    }

    @Test
    @DisplayName("every sample is a file of the strict HEIF parse whose items carry their spatial extents")
    void strict() {
        for (var sample : Sample.values()) {
            HeifFile file = parse(sample.encode());
            for (var item : file.items()) {
                if (item.type().equals("hvc1") || item.type().equals("grid")) {
                    assertTrue(item.property(HeifProperty.SpatialExtent.class) != null, sample + " item " + item.id());
                    assertTrue(item.property(HeifProperty.PixelInformation.class) != null, sample + " item "
                            + item.id());
                }
            }
        }
    }
}
