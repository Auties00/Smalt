package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.testkit.Oracle;

import java.io.ByteArrayOutputStream;
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
 * Runs OpenJPEG's command-line tools ({@code opj_decompress}, {@code opj_compress},
 * {@code opj_dump}) as black-box oracles. Tests skip when a tool is not on {@code PATH}.
 */
final class OpenJpeg {
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private OpenJpeg() {
        throw new AssertionError();
    }

    static Optional<Path> find(String tool) {
        return Oracle.find(tool);
    }

    static void assume(String tool) {
        Oracle.assume(tool);
    }

    /** Runs a tool and returns its output, or null when it fails. */
    static String run(String tool, List<String> arguments) {
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
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(tool + " timed out");
            }
            return process.exitValue() == 0 ? out.toString() : null;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    /** Returns a fresh path under target/jp2-tests. */
    static Path temp(String name) {
        try {
            var directory = Path.of("target", "jp2-tests");
            Files.createDirectories(directory);
            return directory.resolve(COUNTER.incrementAndGet() + "-" + name).toAbsolutePath();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Decodes a file with {@code opj_decompress} into one PGX plane per component.
     *
     * @param data      the file
     * @param extension the input extension ({@code j2k} or {@code jp2})
     * @param reduction the {@code -r} reduction
     * @param layers    the {@code -l} layer count, or 0 for all
     * @return the planes, or {@code null} when the tool rejects the file
     */
    static Jp2Testing.Plane[] decode(byte[] data, String extension, int reduction, int layers) {
        assume("opj_decompress");
        try {
            var input = temp("in." + extension);
            Files.write(input, data);
            var output = temp("out.pgx");
            var arguments = new ArrayList<>(List.of("-i", input.toString(), "-o", output.toString(), "-r",
                    String.valueOf(reduction)));
            if (layers > 0) {
                arguments.add("-l");
                arguments.add(String.valueOf(layers));
            }
            if (run("opj_decompress", arguments) == null) {
                return null;
            }
            var base = output.toString().substring(0, output.toString().length() - 4);
            var planes = new ArrayList<Jp2Testing.Plane>();
            for (var c = 0; ; c++) {
                var file = Path.of(base + "_" + c + ".pgx");
                if (!Files.exists(file)) {
                    if (c == 0 && Files.exists(output)) {
                        planes.add(Jp2Testing.pgx(Files.readAllBytes(output)));
                        Files.delete(output);
                    }
                    break;
                }
                planes.add(Jp2Testing.pgx(Files.readAllBytes(file)));
                Files.delete(file);
            }
            Files.delete(input);
            return planes.toArray(Jp2Testing.Plane[]::new);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
