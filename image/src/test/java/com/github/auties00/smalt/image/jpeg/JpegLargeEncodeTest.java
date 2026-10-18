package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that pictures large enough to be transformed in several bands on several threads are
 * encoded byte for byte as libjpeg-turbo's {@code cjpeg -dct int -baseline} encodes them, and that
 * the planar and packed inputs of the same samples give the same file. Skipped when cjpeg is not
 * on {@code PATH}.
 */
class JpegLargeEncodeTest {
    private static Optional<Path> cjpeg() {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (var directory : path.split(java.io.File.pathSeparator)) {
            for (var name : new String[]{"cjpeg.exe", "cjpeg"}) {
                try {
                    var file = Path.of(directory.trim(), name);
                    if (Files.isRegularFile(file)) {
                        return Optional.of(file);
                    }
                } catch (RuntimeException ignored) {
                    // malformed PATH entry
                }
            }
        }
        return Optional.empty();
    }

    private static RgbaPicture photoLike(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        var seed = 0x9E3779B9L;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                var noise = (int) (seed >>> 59) - 16;
                var i = 4 * (y * width + x);
                pixels[i] = (byte) Math.clamp(128 + 90 * Math.sin(x / 37.0) * Math.cos(y / 53.0) + noise, 0, 255);
                pixels[i + 1] = (byte) Math.clamp(((x ^ y) & 0xFF) / 2 + 60 + noise, 0, 255);
                pixels[i + 2] = (byte) Math.clamp(255 * (x + y) / (double) (width + height) + noise, 0, 255);
                pixels[i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    private static byte[] runCjpeg(Path cjpeg, RgbaPicture picture, int quality, ChromaFormat format, boolean optimize,
                                   int restart) throws IOException, InterruptedException {
        var width = picture.width();
        var height = picture.height();
        var header = ("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII);
        var ppm = new byte[header.length + 3 * width * height];
        System.arraycopy(header, 0, ppm, 0, header.length);
        for (var i = 0; i < width * height; i++) {
            ppm[header.length + 3 * i] = picture.pixels()[4 * i];
            ppm[header.length + 3 * i + 1] = picture.pixels()[4 * i + 1];
            ppm[header.length + 3 * i + 2] = picture.pixels()[4 * i + 2];
        }
        var input = Files.createTempFile("large-encode", ".ppm");
        var output = Files.createTempFile("large-encode", ".jpg");
        try {
            Files.write(input, ppm);
            var command = new ArrayList<String>();
            command.add(cjpeg.toString());
            command.addAll(java.util.List.of("-dct", "int", "-baseline", "-quality", String.valueOf(quality)));
            if (format == ChromaFormat.MONOCHROME) {
                command.add("-grayscale");
            } else {
                command.addAll(java.util.List.of("-sample", format == ChromaFormat.YUV420 ? "2x2" : format == ChromaFormat.YUV422 ? "2x1" : "1x1"));
            }
            if (optimize) {
                command.add("-optimize");
            }
            if (restart != 0) {
                command.addAll(java.util.List.of("-restart", restart + "B"));
            }
            command.addAll(java.util.List.of("-outfile", output.toString(), input.toString()));
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            assertTrue(process.waitFor(2, TimeUnit.MINUTES) && process.exitValue() == 0, "cjpeg failed");
            return Files.readAllBytes(output);
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    @ParameterizedTest(name = "{0}x{1} {2} q{3} optimize={4} restart={5}")
    @CsvSource({
            "1600, 1200, YUV420, 92, true, 0",
            "1600, 1200, YUV420, 92, false, 0",
            "2560, 1920, YUV420, 75, true, 0",
            "1601, 1203, YUV420, 92, true, 7",
            "1603, 1201, YUV422, 50, false, 3",
            "1600, 1199, YUV444, 92, true, 0",
            "1920, 1081, MONOCHROME, 85, true, 11"
    })
    @DisplayName("large pictures match cjpeg byte for byte")
    void matchesCjpeg(int width, int height, ChromaFormat format, int quality, boolean optimize, int restart)
            throws IOException, InterruptedException {
        var cjpeg = cjpeg();
        Assumptions.assumeTrue(cjpeg.isPresent(), "cjpeg is not on PATH");
        var picture = photoLike(width, height);
        var ours = JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(quality).chromaFormat(format)
                .optimizeHuffman(optimize).restartInterval(restart).build());
        assertArrayEquals(runCjpeg(cjpeg.get(), picture, quality, format, optimize, restart), ours);
    }

    @Test
    @DisplayName("repeated encodes of a large planar picture are identical whatever the thread schedule")
    void deterministic() {
        var planar = ImageOps.toYuv(photoLike(2016, 1512), ChromaFormat.YUV420, ColorInfo.JPEG);
        var first = JpegEncoder.encode(planar, JpegEncoderOptions.ofQuality(92));
        for (var i = 0; i < 5; i++) {
            assertArrayEquals(first, JpegEncoder.encode(planar, JpegEncoderOptions.ofQuality(92)));
        }
    }
}
