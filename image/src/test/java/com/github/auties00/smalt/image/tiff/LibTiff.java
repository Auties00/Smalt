package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the libtiff command-line tools (tiffcp, tiff2rgba, raw2tiff, fax2tiff, tiffinfo) that
 * serve as black-box oracles, reads their uncompressed output with an independent minimal strip
 * reader, and fetches the libtiff test image sets. Tests skip when a tool or vector is absent.
 */
public final class LibTiff {
    static final String PICS_ARCHIVE = "libtiff-pics-3.8.0.tar.gz";
    static final String PICS_URL = "http://download.osgeo.org/libtiff/pics-3.8.0.tar.gz";
    static final String PICS_SHA256 = "e0e34732b61e1ce49eff2c7a079994c856d2a5f772f5228c84678272bc6829a9";
    static final String SOURCE_ARCHIVE = "libtiff-4.7.1.tar.gz";
    static final String SOURCE_URL = "https://download.osgeo.org/libtiff/tiff-4.7.1.tar.gz";
    static final String SOURCE_SHA256 = "f698d94f3103da8ca7438d84e0344e453fe0ba3b7486e04c5bf7a9a3fabe9b69";

    private static final Map<String, Optional<Path>> RESOLVED = new ConcurrentHashMap<>();
    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static volatile Map<String, byte[]> pics;
    private static volatile Map<String, byte[]> sources;

    private LibTiff() {
        throw new AssertionError();
    }

    static Optional<Path> find(String tool) {
        return RESOLVED.computeIfAbsent(tool, LibTiff::lookup);
    }

    private static Optional<Path> lookup(String tool) {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        var windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (var directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (var candidate : windows ? List.of(tool + ".exe", tool) : List.of(tool)) {
                try {
                    var file = Path.of(directory.trim(), candidate);
                    if (Files.isRegularFile(file) && Files.isExecutable(file)) {
                        return Optional.of(file);
                    }
                } catch (RuntimeException ignored) {
                    // malformed PATH entry
                }
            }
        }
        return Optional.empty();
    }

    static void assume(String... tools) {
        for (var tool : tools) {
            Assumptions.assumeTrue(find(tool).isPresent(), tool + " is not on PATH");
        }
    }

    /** Runs a tool, returning its standard output, or null when it exits with an error. */
    static byte[] tryRun(String tool, List<String> arguments) {
        var command = new ArrayList<String>();
        command.add(find(tool).orElseThrow().toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var out = new ByteArrayOutputStream();
            try (var input = process.getInputStream()) {
                input.transferTo(out);
            }
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(tool + " timed out: " + command);
            }
            return process.exitValue() == 0 ? out.toByteArray() : null;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    static byte[] run(String tool, List<String> arguments) {
        var output = tryRun(tool, arguments);
        if (output == null) {
            throw new AssertionError(tool + " failed: " + arguments);
        }
        return output;
    }

    /** Returns a fresh file path under target/tiff-tests. */
    static Path temp(String name) {
        try {
            var directory = Path.of("target", "tiff-tests");
            Files.createDirectories(directory);
            return directory.resolve(COUNTER.incrementAndGet() + "-" + name).toAbsolutePath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Path write(String name, byte[] data) {
        var path = temp(name);
        try {
            Files.write(path, data);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return path;
    }

    static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Transcodes a TIFF with tiffcp, returning the output file. */
    static byte[] tiffcp(byte[] input, List<String> options) {
        var in = write("in.tif", input);
        var out = temp("out.tif");
        var arguments = new ArrayList<>(options);
        arguments.add(in.toString());
        arguments.add(out.toString());
        run("tiffcp", arguments);
        return read(out);
    }

    /** Builds a TIFF from raw samples with raw2tiff. */
    static byte[] raw2tiff(byte[] raw, int width, int height, int bands, String type, String photometric, List<String> extra) {
        var in = write("in.raw", raw);
        var out = temp("raw.tif");
        var arguments = new ArrayList<>(List.of("-w", String.valueOf(width), "-l", String.valueOf(height),
                "-b", String.valueOf(bands), "-d", type, "-p", photometric, "-c", "none"));
        arguments.addAll(extra);
        arguments.add(in.toString());
        arguments.add(out.toString());
        run("raw2tiff", arguments);
        return read(out);
    }

    /**
     * Decodes a TIFF with libtiff's TIFFReadRGBAImage (tiff2rgba) to top-down RGBA, alpha
     * premultiplied as libtiff produces it, or returns null when libtiff cannot read the image.
     */
    static byte[] rgba(byte[] tiff) {
        var in = write("rgba-in.tif", tiff);
        var out = temp("rgba-out.tif");
        if (tryRun("tiff2rgba", List.of("-c", "none", in.toString(), out.toString())) == null || !Files.exists(out)) {
            return null;
        }
        var raw = RawStrips.read(read(out));
        if (raw.samples() == 4) {
            return raw.data();
        }
        var rgba = new byte[raw.width() * raw.height() * 4];
        for (var i = 0; i < raw.width() * raw.height(); i++) {
            rgba[i * 4] = raw.data()[i * 3];
            rgba[i * 4 + 1] = raw.data()[i * 3 + 1];
            rgba[i * 4 + 2] = raw.data()[i * 3 + 2];
            rgba[i * 4 + 3] = (byte) 0xFF;
        }
        return rgba;
    }

    /** Premultiplies straight RGBA as libtiff does for unassociated alpha: (v * a + 127) / 255. */
    static byte[] premultiply(byte[] rgba) {
        var out = rgba.clone();
        for (var i = 0; i < out.length; i += 4) {
            var a = out[i + 3] & 0xFF;
            for (var c = 0; c < 3; c++) {
                out[i + c] = (byte) (((out[i + c] & 0xFF) * a + 127) / 255);
            }
        }
        return out;
    }

    public static Map<String, byte[]> pics() {
        if (pics == null) {
            pics = RasterTesting.untarGz(Conformance.fetch(PICS_ARCHIVE, PICS_URL, PICS_SHA256));
        }
        return pics;
    }

    public static Map<String, byte[]> sources() {
        if (sources == null) {
            sources = RasterTesting.untarGz(Conformance.fetch(SOURCE_ARCHIVE, SOURCE_URL, SOURCE_SHA256));
        }
        return sources;
    }

    /** The samples of an uncompressed, interleaved, classic TIFF read independently of the decoder. */
    record RawStrips(int width, int height, int samples, int bits, byte[] data) {
        static RawStrips read(byte[] file) {
            var little = file[0] == 'I';
            var ifd = (int) u32(file, 4, little);
            var count = u16(file, ifd, little);
            int width = 0;
            int height = 0;
            int samples = 1;
            int bits = 1;
            long[] offsets = null;
            long[] counts = null;
            for (var i = 0; i < count; i++) {
                var at = ifd + 2 + i * 12;
                var tag = u16(file, at, little);
                var type = u16(file, at + 2, little);
                var n = (int) u32(file, at + 4, little);
                var values = values(file, at, type, n, little);
                switch (tag) {
                    case 256 -> width = (int) values[0];
                    case 257 -> height = (int) values[0];
                    case 258 -> bits = (int) values[0];
                    case 259 -> {
                        if (values[0] != 1) {
                            throw new IllegalStateException("compressed oracle output");
                        }
                    }
                    case 273 -> offsets = values;
                    case 277 -> samples = (int) values[0];
                    case 279 -> counts = values;
                    default -> {
                    }
                }
            }
            var out = new ByteArrayOutputStream();
            for (var i = 0; i < offsets.length; i++) {
                out.write(file, (int) offsets[i], (int) counts[i]);
            }
            var data = out.toByteArray();
            var expected = width * height * samples * bits / 8;
            return new RawStrips(width, height, samples, bits, java.util.Arrays.copyOf(data, expected));
        }

        private static long[] values(byte[] file, int entry, int type, int n, boolean little) {
            var size = switch (type) {
                case 3 -> 2;
                case 4 -> 4;
                default -> 1;
            };
            var inline = n * size <= 4;
            var base = inline ? entry + 8 : (int) u32(file, entry + 8, little);
            var values = new long[n];
            for (var i = 0; i < n; i++) {
                values[i] = switch (size) {
                    case 2 -> u16(file, base + i * 2, little);
                    case 4 -> u32(file, base + i * 4, little);
                    default -> file[base + i] & 0xFF;
                };
            }
            return values;
        }

        private static int u16(byte[] data, int at, boolean little) {
            return little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
        }

        private static long u32(byte[] data, int at, boolean little) {
            return little ? (u16(data, at, true) | (long) u16(data, at + 2, true) << 16)
                    : ((long) u16(data, at, false) << 16 | u16(data, at + 2, false));
        }
    }
}
