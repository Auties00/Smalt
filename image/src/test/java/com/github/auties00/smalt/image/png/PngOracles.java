package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the independent PNG readers the encoder tests compare against: libpng through
 * {@code smalt-pngdump} (built by {@code tools/testdata/png-oracle/build.sh} and found on
 * {@code PATH}), the JDK's ImageIO reader in a separate JVM ({@code ImageIoPngDump.java}, so this
 * module never reads {@code java.desktop}), and ffmpeg.
 */
final class PngOracles {
    static final String LIBPNG = "smalt-pngdump";

    private static final Path IMAGE_IO_DUMP = Path.of("..", "tools", "testdata", "png-oracle", "ImageIoPngDump.java");

    private static final AtomicInteger FILES = new AtomicInteger();

    private PngOracles() {
    }

    static boolean hasLibpng() {
        return Oracle.find(LIBPNG).isPresent();
    }

    static boolean hasImageIo() {
        return Files.isRegularFile(IMAGE_IO_DUMP) && ProcessHandle.current().info().command().isPresent();
    }

    static Path file(String what, byte[] png) {
        return RasterTesting.write("oracle-" + FILES.incrementAndGet() + "-" + what.replaceAll("[^A-Za-z0-9]", "_")
                + ".png", png);
    }

    /** Returns the chunk lines libpng reports, warnings included as {@code warning ...} lines. */
    static List<String> libpngInfo(byte[] png) {
        var out = Oracle.run(LIBPNG, Duration.ofMinutes(1), null, List.of("info", file("info", png).toString()));
        return new String(out, StandardCharsets.US_ASCII).lines().toList();
    }

    /** Returns libpng's RGBA expansion: 8-bit samples, or big-endian 16-bit samples for 16-bit images. */
    static byte[] libpngPixels(byte[] png) {
        return Oracle.run(LIBPNG, Duration.ofMinutes(1), null, List.of("pixels", file("pixels", png).toString()));
    }

    /** Returns the line of a chunk in libpng's report, or {@code null}. */
    static String libpngChunk(List<String> info, String chunk) {
        return info.stream().filter(line -> line.startsWith(chunk + " ")).findFirst().orElse(null);
    }

    /** The output of ImageIO for one file: the metadata tree and RGBA16 big-endian pixels. */
    record ImageIoResult(String metadata, byte[] rgba16) {
    }

    /** Reads PNG datastreams with ImageIO in one JVM. */
    static List<ImageIoResult> imageIo(List<byte[]> pngs) {
        var paths = new ArrayList<Path>();
        for (var png : pngs) {
            paths.add(file("imageio", png).toAbsolutePath());
        }
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add(IMAGE_IO_DUMP.toAbsolutePath().toString());
        for (var path : paths) {
            command.add(path.toString());
        }
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(Path.of("target", "raster-tests", "imageio.log").toFile()).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("ImageIoPngDump timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ImageIoPngDump failed: "
                        + Files.readString(Path.of("target", "raster-tests", "imageio.log")));
            }
            var results = new ArrayList<ImageIoResult>();
            for (var path : paths) {
                results.add(new ImageIoResult(Files.readString(Path.of(path + ".imageio.txt")),
                        Files.readAllBytes(Path.of(path + ".imageio.rgba16"))));
            }
            return results;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Returns ffmpeg's decode of a datastream in a raw pixel format, such as rgba or rgba64le. */
    static byte[] ffmpeg(byte[] png, String pixelFormat) {
        return RasterTesting.ffmpegFrames(file("ffmpeg", png), pixelFormat);
    }
}
