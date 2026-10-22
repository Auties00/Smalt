package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the independent BMP readers the encoder tests compare against: the JDK's ImageIO reader in a separate JVM
 * ({@code tools/testdata/bmp-oracle/ImageIoBmpDump.java}, so this module never reads {@code java.desktop}), Pillow
 * ({@code pillow-bmp-dump.py} there, run by the Python of {@code -Dsmalt.test.python} or the first {@code python3}
 * or {@code python} on {@code PATH}) and ffmpeg. A reader that cannot read a file yields {@code null} for it.
 */
final class BmpOracles {
    private static final Path DIRECTORY = Path.of("..", "tools", "testdata", "bmp-oracle");

    private static final AtomicInteger FILES = new AtomicInteger();

    private BmpOracles() {
    }

    static boolean hasImageIo() {
        return Files.isRegularFile(DIRECTORY.resolve("ImageIoBmpDump.java"))
                && ProcessHandle.current().info().command().isPresent();
    }

    static Optional<String> python() {
        var configured = System.getProperty("smalt.test.python");
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured);
        }
        return Oracle.find("python3").or(() -> Oracle.find("python")).map(Path::toString);
    }

    static Path file(String what, byte[] bmp) {
        return RasterTesting.write("bmp-oracle-" + FILES.incrementAndGet() + "-" + what.replaceAll("[^A-Za-z0-9]", "_")
                + ".bmp", bmp).toAbsolutePath();
    }

    /** Reads files with ImageIO in one JVM, returning RGBA8 per file or {@code null} where the reader failed. */
    static List<byte[]> imageIo(List<byte[]> files) {
        var command = new ArrayList<String>();
        command.add(ProcessHandle.current().info().command().orElseThrow());
        command.add(DIRECTORY.resolve("ImageIoBmpDump.java").toAbsolutePath().toString());
        return run(command, files, "imageio", 0);
    }

    /** Reads files with Pillow, or returns {@code null} when Python or Pillow is missing. */
    static List<byte[]> pillow(List<byte[]> files) {
        var python = python();
        if (python.isEmpty()) {
            return null;
        }
        var command = new ArrayList<String>();
        command.add(python.get());
        command.add("-P");
        command.add(DIRECTORY.resolve("pillow-bmp-dump.py").toAbsolutePath().toString());
        return run(command, files, "pillow", 3);
    }

    /** Returns ffmpeg's RGBA decode of a file, or {@code null} when ffmpeg rejects it. */
    static byte[] ffmpeg(byte[] bmp) {
        var path = file("ffmpeg", bmp);
        var result = Oracle.execute(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), null, List.of("-v", "error",
                "-i", path.toString(), "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
        return result.exitCode() == 0 ? result.stdout() : null;
    }

    private static List<byte[]> run(List<String> command, List<byte[]> files, String reader, int missingStatus) {
        var paths = new ArrayList<Path>();
        for (var data : files) {
            var path = file(reader, data);
            paths.add(path);
            command.add(path.toString());
        }
        try {
            var log = Path.of("target", "raster-tests", reader + ".log");
            var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(reader + " timed out");
            }
            if (missingStatus != 0 && process.exitValue() == missingStatus) {
                return null;
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(reader + " failed: " + Files.readString(log));
            }
            var results = new ArrayList<byte[]>();
            for (var path : paths) {
                var output = Path.of(path + "." + reader + ".rgba");
                results.add(Files.isRegularFile(output) ? Files.readAllBytes(output) : null);
            }
            return results;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
