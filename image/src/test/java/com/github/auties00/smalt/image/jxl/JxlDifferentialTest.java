package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares this decoder with libjxl's tools on files cjxl produces: lossless and lossy modular,
 * VarDCT at several distances and efforts with progressive passes, resampling, noise and the
 * restoration filters, decoded by djxl; and JPEG files recompressed by cjxl, which must be rebuilt
 * byte for byte. The tests skip when cjxl or djxl is not on the PATH.
 */
class JxlDifferentialTest {
    /** Lossless coding reconstructs the samples exactly, whatever the decoder's float arithmetic. */
    static final Tolerance LOSSLESS = Tolerance.exact();

    /**
     * Lossy files go through float transforms that libjxl evaluates with its own approximations, which may round an
     * 8-bit output one level differently.
     */
    static final Tolerance LOSSY = Tolerance.offByOne();

    static final Path SCRATCH = Path.of("target", "jxl-scratch");

    static Path scratch(String name) {
        try {
            Files.createDirectories(SCRATCH);
            return SCRATCH.resolve(name);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A synthetic picture with gradients, hard edges, fine texture and, optionally, alpha. */
    static RgbaPicture picture(int width, int height, boolean alpha) {
        var pixels = new byte[width * height * 4];
        var random = new Random(width * 31L + height);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var i = (y * width + x) * 4;
                var edge = ((x / 16) + (y / 16)) % 2 == 0 ? 40 : 0;
                pixels[i] = (byte) Math.min(255, x * 255 / width + edge);
                pixels[i + 1] = (byte) Math.min(255, y * 255 / height + random.nextInt(12));
                pixels[i + 2] = (byte) (128 + 100 * Math.sin((x + y) / 9.0));
                pixels[i + 3] = alpha ? (byte) Math.min(255, (x + y) * 255 / (width + height) + 30) : (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    static byte[] tight(RgbaPicture picture) {
        var width = picture.width();
        var out = new byte[width * picture.height() * 4];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * width * 4, width * 4);
        }
        return out;
    }

    static RgbaPicture decodePng(Path file) {
        try {
            return (RgbaPicture) Images.decode(MediaSource.of(Files.readAllBytes(file))).picture();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("cjxl variants decoded by djxl")
    class Djxl {
        @ParameterizedTest(name = "cjxl {0}")
        @ValueSource(strings = {"-d 0 -e 1", "-d 0 -e 3", "-d 0 -e 7", "-d 0 -e 9", "-d 0 -e 7 --progressive"})
        @DisplayName("lossless files decode exactly like djxl")
        void lossless(String flags) {
            compare(flags, LOSSLESS);
        }

        @ParameterizedTest(name = "cjxl {0}")
        @ValueSource(strings = {"-d 1 -e 3", "-d 1 -e 7", "-d 0.3 -e 7", "-d 3 -e 9", "-d 2 --progressive",
                "-d 1 --progressive_dc=1", "-d 6 --resampling=2", "-d 1 --epf=3 --gaborish=1", "-d 1 --epf=0 --gaborish=0",
                "-d 1 --photon_noise_iso=6400", "-d 1 --faster_decoding=4", "-d 1.5 -e 7 --group_order=1",
                "-d 1 -m 1", "-d 2 -m 1 -e 7"})
        @DisplayName("lossy VarDCT and modular files decode within one level of djxl")
        void lossy(String flags) {
            compare(flags, LOSSY);
        }

        void compare(String flags, Tolerance tolerance) {
            Oracle.assume(Oracle.Tool.CJXL);
            Oracle.assume(Oracle.Tool.DJXL);
            for (var alpha : List.of(false, true)) {
                var source = picture(203, 141, alpha);
                var tag = Integer.toHexString(flags.hashCode()) + (alpha ? "-a" : "-o");
                var png = scratch("src-" + tag + ".png");
                var jxl = scratch("enc-" + tag + ".jxl");
                var reference = scratch("ref-" + tag + ".png");
                try {
                    Files.write(png, Images.encode(source, ImageFormat.PNG));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                var arguments = new ArrayList<>(List.of(png.toString(), jxl.toString(), "--quiet"));
                arguments.addAll(List.of(flags.split(" ")));
                Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), arguments);
                Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(jxl.toString(), reference.toString(), "--quiet"));
                var expected = tight(decodePng(reference));
                byte[] actual;
                try {
                    actual = tight((RgbaPicture) JxlDecoder.decode(MediaSource.of(Files.readAllBytes(jxl))).picture());
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                tolerance.assertWithin(flags + (alpha ? " with alpha" : ""), expected, actual);
            }
        }
    }

    /** Spot colors composited by both decoders from 16-bit samples, which may round one 16-bit level apart. */
    static final Tolerance SPOT_16 = Tolerance.maxAbs(2);

    /**
     * The LF image is the XYB average of each 8x8 block while djxl's LF-only render upsamples it smoothly, so their
     * box averages differ where the content bends the transfer functions inside a block.
     */
    static final Tolerance LF_ONLY = Tolerance.psnr(25);

    @Nested
    @DisplayName("spot colors, previews and progressive renders against djxl")
    class Features {
        @Test
        @DisplayName("the conformance spot case renders its spot colors as djxl does by default")
        void spot() throws IOException {
            Oracle.assume(Oracle.Tool.DJXL);
            var directory = Scratch.directory("jxl-features");
            var input = JxlVectors.find("spot").input();
            var reference = directory.resolve("spot-djxl.png");
            Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(input.toString(), reference.toString(),
                    "--bits_per_sample=16", "--quiet"));
            var expected = (Rgba16Picture) Images.decode(
                    MediaSource.of(Files.readAllBytes(reference)), ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            var actual = (Rgba16Picture) JxlDecoder.decode(
                    MediaSource.of(Files.readAllBytes(input)), ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
            SPOT_16.assertWithin("spot", tight16(expected), tight16(actual), 16);
        }

        @ParameterizedTest(name = "cjxl {0}")
        @ValueSource(strings = {"-d 1 -p", "-d 2 --progressive_ac", "-d 1.5 --qprogressive_ac",
                "-d 1 --progressive_dc=1 -p"})
        @DisplayName("reduced decodes of progressive files match djxl's progressive renders, box averaged")
        void progressive(String flags) throws IOException {
            Oracle.assume(Oracle.Tool.CJXL);
            Oracle.assume(Oracle.Tool.DJXL);
            var directory = Scratch.directory("jxl-features");
            var tag = Integer.toHexString(flags.hashCode());
            var png = directory.resolve("progressive-" + tag + ".png");
            var jxl = directory.resolve("progressive-" + tag + ".jxl");
            Files.write(png, Images.encode(picture(203, 141, false), ImageFormat.PNG));
            var arguments = new ArrayList<>(List.of(png.toString(), jxl.toString(), "--quiet"));
            arguments.addAll(List.of(flags.split(" ")));
            Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2), arguments);
            var data = Files.readAllBytes(jxl);
            var codestream = JxlCodestream.open(data, MediaLimits.DEFAULT);
            for (var factor : List.of(2, 8)) {
                var passes = codestream.reductionPasses(factor);
                if (passes < 0) {
                    continue;
                }
                var reference = directory.resolve("progressive-" + tag + "-s" + factor + ".png");
                Oracle.run(Oracle.Tool.DJXL, Duration.ofMinutes(2), List.of(jxl.toString(), reference.toString(),
                        "-s", Integer.toString(factor), "--bits_per_sample=16", "--quiet"));
                var expected = JxlFeaturesTest.boxed(ImageOps.toRgba16(Images.decode(MediaSource.of(
                        Files.readAllBytes(reference)), JxlFeaturesTest.DEEP).picture()), factor);
                var image = JxlDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().minSize((203 + factor - 1) / factor, (141 + factor - 1) / factor)
                        .build());
                assertEquals((203 + factor - 1) / factor, image.picture().width(), flags + " reduced by " + factor);
                var actual = tight((RgbaPicture) image.picture());
                (passes == 0 ? LF_ONLY : LOSSY).assertWithin(flags + " reduced by " + factor, expected, actual);
            }
        }
    }

    static short[] tight16(Rgba16Picture picture) {
        var width = picture.width();
        var out = new short[width * picture.height() * 4];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * width * 4, width * 4);
        }
        return out;
    }

    static Stream<Path> jpegs() throws IOException, URISyntaxException {
        var root = Path.of(JxlDifferentialTest.class.getResource("/com/github/auties00/smalt/image/jpeg").toURI());
        try (var files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".jpg") && !file.toString().contains("arithmetic"))
                    .sorted().toList().stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jpegs")
    @DisplayName("JPEG files recompressed by cjxl are rebuilt byte for byte")
    void jpegReconstruction(Path jpeg) throws IOException {
        Oracle.assume(Oracle.Tool.CJXL);
        var jxl = scratch("jpeg-" + jpeg.getFileName() + ".jxl");
        try {
            Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2),
                    List.of(jpeg.toString(), jxl.toString(), "--lossless_jpeg=1", "--quiet"));
        } catch (IllegalStateException unsupported) {
            // cjxl cannot recompress every JPEG losslessly (for example some restart layouts).
            return;
        }
        var rebuilt = JxlDecoder.reconstructJpeg(MediaSource.of(Files.readAllBytes(jxl))).orElseThrow();
        LOSSLESS.assertWithin("rebuilt " + jpeg.getFileName(), Files.readAllBytes(jpeg), rebuilt);
    }
}
