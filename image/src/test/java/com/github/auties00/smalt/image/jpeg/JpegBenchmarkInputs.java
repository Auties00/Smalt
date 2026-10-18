package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.testkit.Oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Generates the benchmark inputs once: a 4032x3024 photo-like RGB image (ffmpeg's testsrc2 with
 * uniform noise, which compresses like a real 12 MP phone photo at about 4 MB) and its JPEG
 * encodings.
 */
final class JpegBenchmarkInputs {
    static final int WIDTH = 4032;
    static final int HEIGHT = 3024;

    private JpegBenchmarkInputs() {
        throw new AssertionError();
    }

    static synchronized Path ensure(String name) {
        var path = JpegDecodeBenchmark.path(name);
        if (Files.isRegularFile(path)) {
            return path;
        }
        try {
            Files.createDirectories(path.getParent());
            switch (name) {
                case "photo.rgb" -> Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-y",
                        "-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=1,noise=alls=30:allf=u",
                        "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", path.toString()));
                case "photo_q92_420.jpg" -> {
                    var rgb = Files.readAllBytes(ensure("photo.rgb"));
                    var jpeg = JpegEncoder.encode(JpegTestSupport.rgba(WIDTH, HEIGHT, rgb),
                            JpegEncoderOptions.ofQuality(92));
                    Files.write(path, jpeg);
                }
                case "photo_q92_420_progressive.jpg" -> Files.write(path,
                        com.github.auties00.smalt.image.jpeg.internal.ProgressiveInputs.progressive(
                                Files.readAllBytes(ensure("photo_q92_420.jpg"))));
                default -> throw new IllegalArgumentException("unknown benchmark input " + name);
            }
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
