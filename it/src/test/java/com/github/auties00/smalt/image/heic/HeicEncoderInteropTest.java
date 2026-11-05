package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.image.AnimationWriter;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the HEIC files the HEIC encoder writes with other HEIF readers, which decode them to PNG files that
 * {@code smalt-image} reads: libheif's {@code heif-info} and {@code heif-dec} ({@code heif-convert} before libheif
 * 1.18) and, on macOS, the system's ImageIO through {@code sips}. Both convert to RGB with their own matrix
 * arithmetic and chroma upsampling, so their pixels agree with Smalt's decode within a PSNR bound; their sizes, the
 * clean aperture applied, and their alpha must match.
 */
@DisplayName("HEIC encoder output read by libheif and ImageIO")
class HeicEncoderInteropTest {
    /** The readers' RGB conversion differs from Smalt's in rounding and chroma upsampling. */
    private static final Tolerance RGB = Tolerance.psnr(32);

    private static final Path DIRECTORY = Scratch.directory("heic-interop");

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
            var options = options().bitDepth(depth);
            if (tile > 0) {
                options.gridTileSize(tile);
            }
            return HeicEncoder.encode(depth == 8 ? rgba(width, height, 0, alpha) : rgba16(width, height, alpha),
                    options.build());
        }

        Path save() {
            return HeicEncoderInteropTest.save(name().toLowerCase(Locale.ROOT) + ".heic", encode());
        }
    }

    static HeicEncoderOptions.Builder options() {
        return HeicEncoderOptions.ofQuality(80).toBuilder().speed(HevcEncoderSpeed.FASTEST).threads(1);
    }

    /** Returns an 8-bit picture of smooth gradients whose content moves with {@code frame}. */
    static RgbaPicture rgba(int width, int height, int frame, boolean alpha) {
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                pixels[i] = (byte) ((x + frame * 2) * 200 / width + 20);
                pixels[i + 1] = (byte) ((y + frame) * 200 / height + 30);
                pixels[i + 2] = (byte) (128 + 60 * Math.sin((x + y + frame) / 9.0));
                pixels[i + 3] = (byte) (alpha ? 40 + x * 215 / Math.max(1, width - 1) : 255);
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    /** Returns a 16-bit picture of smooth gradients. */
    static Rgba16Picture rgba16(int width, int height, boolean alpha) {
        var pixels = new short[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                pixels[i] = (short) (x * 60000 / width + 2000);
                pixels[i + 1] = (short) (y * 60000 / height + 1000);
                pixels[i + 2] = (short) (32768 + 20000 * Math.sin((x + y) / 11.0));
                pixels[i + 3] = (short) (alpha ? 65535 * x / Math.max(1, width - 1) : 65535);
            }
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha);
    }

    static Path save(String name, byte[] data) {
        try {
            return Files.write(DIRECTORY.resolve(name), data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the tight 8-bit RGBA samples of a picture. */
    static byte[] rgbaOf(Picture picture) {
        var rgba = ImageOps.toRgba(picture);
        var out = new byte[rgba.width() * rgba.height() * 4];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    /** Returns the alpha samples of tight RGBA samples. */
    static byte[] alphaOf(byte[] rgba) {
        var out = new byte[rgba.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = rgba[i * 4 + 3];
        }
        return out;
    }

    /** Asserts that a reader's PNG holds the picture Smalt decodes from the same file. */
    static void assertSame(String what, Path png, byte[] file, boolean alpha) {
        var reference = Images.decode(png).picture();
        var actual = HeicDecoder.decode(MediaSource.of(file)).picture();
        assertEquals(actual.width(), reference.width(), what + " width");
        assertEquals(actual.height(), reference.height(), what + " height");
        var expected = rgbaOf(reference);
        var decoded = rgbaOf(actual);
        RGB.assertWithin(what, expected, decoded);
        if (alpha) {
            // alpha is the luma of a full-range auxiliary image: both readers take it as it is
            Tolerance.offByOne().assertWithin(what + " alpha", alphaOf(expected), alphaOf(decoded));
        }
    }

    @Nested
    @DisplayName("libheif's heif-info and heif-dec")
    class Libheif {
        /** Returns libheif's decoder, {@code heif-dec}, or {@code heif-convert} where libheif predates the name. */
        private static Path decoder() {
            var decoder = Oracle.find(Oracle.Tool.HEIF_DEC).or(() -> Oracle.find(Oracle.Tool.HEIF_CONVERT));
            Skip.unless(decoder.isPresent(), "oracle:heif-dec", "neither heif-dec nor heif-convert is on PATH");
            return decoder.orElseThrow();
        }

        /** Decodes a HEIC file to a PNG file with libheif's decoder, failing on a non-zero exit. */
        private static Path decode(Path input) {
            var png = DIRECTORY.resolve(input.getFileName() + "-libheif.png");
            var result = Oracle.execute(decoder(), Duration.ofMinutes(1), null, List.of(input.toString(),
                    png.toString()));
            assertEquals(0, result.exitCode(), result.stderr());
            return png;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Sample.class)
        @DisplayName("heif-info describes the image and heif-dec decodes it to Smalt's pixels")
        void still(Sample sample) {
            Oracle.assume(Oracle.Tool.HEIF_INFO);
            var path = sample.save();
            var info = Oracle.runMerged(Oracle.Tool.HEIF_INFO, Duration.ofMinutes(1), List.of(path.toString()));
            assertTrue(info.contains(sample.width + "x" + sample.height), info);
            assertSame(sample + " libheif", decode(path), sample.encode(), sample.alpha);
        }

        @Test
        @DisplayName("heif-info reads an image sequence and heif-dec decodes its primary image")
        void sequence() {
            Oracle.assume(Oracle.Tool.HEIF_INFO);
            var sink = MediaSink.memory();
            try (AnimationWriter writer = HeicEncoder.openAnimationWriter(64, 48, options().build(), sink)) {
                for (var frame = 0; frame < 5; frame++) {
                    writer.write(rgba(64, 48, frame, frame > 0), 40_000);
                }
                writer.finish();
            }
            var bytes = sink.toByteArray();
            var path = save("sequence.heic", bytes);
            var info = Oracle.runMerged(Oracle.Tool.HEIF_INFO, Duration.ofMinutes(1), List.of(path.toString()));
            assertTrue(info.contains("64x48"), info);
            assertSame("sequence primary", decode(path), bytes, false);
        }
    }

    @Nested
    @DisplayName("macOS ImageIO")
    class MacOs {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Sample.class)
        @DisplayName("sips converts the file to Smalt's pixels")
        void sips(Sample sample) {
            var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            Skip.unless(os.startsWith("mac"), "platform:macos", "sips is part of macOS");
            var path = sample.save();
            var png = DIRECTORY.resolve(sample.name().toLowerCase(Locale.ROOT) + "-sips.png");
            var result = Oracle.execute(Path.of("/usr/bin/sips"), Duration.ofMinutes(1), null, List.of("-s", "format",
                    "png", path.toString(), "--out", png.toString()));
            assertEquals(0, result.exitCode(), result.stderr());
            assertSame(sample + " ImageIO", png, sample.encode(), sample.alpha);
        }
    }
}
