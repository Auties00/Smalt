package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
            compare(flags, 0);
        }

        @ParameterizedTest(name = "cjxl {0}")
        @ValueSource(strings = {"-d 1 -e 3", "-d 1 -e 7", "-d 0.3 -e 7", "-d 3 -e 9", "-d 2 --progressive",
                "-d 1 --progressive_dc=1", "-d 6 --resampling=2", "-d 1 --epf=3 --gaborish=1", "-d 1 --epf=0 --gaborish=0",
                "-d 1 --photon_noise_iso=6400", "-d 1 --faster_decoding=4", "-d 1.5 -e 7 --group_order=1",
                "-d 1 -m 1", "-d 2 -m 1 -e 7"})
        @DisplayName("lossy VarDCT and modular files decode within one level of djxl")
        void lossy(String flags) {
            compare(flags, 1);
        }

        void compare(String flags, int tolerance) {
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
                assertEquals(expected.length, actual.length, "size");
                var worst = 0;
                for (var i = 0; i < expected.length; i++) {
                    worst = Math.max(worst, Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)));
                }
                assertTrue(worst <= tolerance, flags + (alpha ? " with alpha" : "") + ": difference " + worst);
            }
        }
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
        assertArrayEquals(Files.readAllBytes(jpeg), rebuilt);
    }
}
