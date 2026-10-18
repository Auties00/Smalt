package com.github.auties00.smalt.testkit;

import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs the reference media tools (ffmpeg, ffprobe, the libwebp, libjxl, libjpeg-turbo, OpenJPEG
 * and libtiff tools, exiftool, flac) that serve as independent oracles in differential tests.
 *
 * <p>Tools are looked up on {@code PATH} once. Tests call {@link #assume(Tool)} so they are
 * skipped, not failed, on machines without the tool; a tool without a {@link Tool} constant is
 * found and run by its executable name.
 */
public final class Oracle {
    /**
     * The reference tools.
     */
    public enum Tool {
        /** The ffmpeg CLI. */
        FFMPEG("ffmpeg"),
        /** The ffprobe CLI. */
        FFPROBE("ffprobe"),
        /** libwebp's encoder CLI. */
        CWEBP("cwebp"),
        /** libwebp's decoder CLI. */
        DWEBP("dwebp"),
        /** Phil Harvey's ExifTool, the metadata reader and writer. */
        EXIFTOOL("exiftool"),

        /** The reference FLAC command-line tool. */
        FLAC("flac"),

        /** libjxl's JPEG XL encoder CLI. */
        CJXL("cjxl"),

        /** libjxl's JPEG XL decoder CLI. */
        DJXL("djxl"),

        /** libwebp's container muxing CLI. */
        WEBPMUX("webpmux"),

        /** OpenJPEG's JPEG 2000 encoder CLI. */
        OPJ_COMPRESS("opj_compress"),

        /** OpenJPEG's JPEG 2000 decoder CLI. */
        OPJ_DECOMPRESS("opj_decompress"),

        /** libtiff's TIFF copying and recompression CLI. */
        TIFFCP("tiffcp"),

        /** libjpeg-turbo's lossless JPEG transformation CLI. */
        JPEGTRAN("jpegtran"),

        /** libjpeg-turbo's JPEG decoder CLI. */
        DJPEG("djpeg"),

        /** libjpeg-turbo's JPEG encoder CLI. */
        CJPEG("cjpeg");

        /** The executable name without extension. */
        private final String executable;

        Tool(String executable) {
            this.executable = executable;
        }
    }

    /** The resolved executable of each tool, empty when absent. */
    private static final Map<Tool, Optional<Path>> RESOLVED = new ConcurrentHashMap<>();

    private Oracle() {
        throw new AssertionError();
    }

    /** The resolved executable of each tool looked up by name, empty when absent. */
    private static final Map<String, Optional<Path>> RESOLVED_NAMES = new ConcurrentHashMap<>();

    /**
     * The outcome of a tool run that does not fail on a non-zero exit.
     *
     * @param exitCode the exit code
     * @param stdout the captured standard output
     * @param stderr the captured standard error, as UTF-8 text
     */
    public record Result(int exitCode, byte[] stdout, String stderr) {
    }

    /**
     * Returns the executable of a tool, searching {@code PATH}.
     */
    public static Optional<Path> find(Tool tool) {
        return RESOLVED.computeIfAbsent(tool, key -> lookup(key.executable));
    }

    /**
     * Returns the executable of a tool by name, searching {@code PATH}, for a tool without a
     * {@link Tool} constant.
     */
    public static Optional<Path> find(String executable) {
        return RESOLVED_NAMES.computeIfAbsent(executable, Oracle::lookup);
    }

    private static Optional<Path> lookup(String executable) {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        var windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (var directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (var candidate : windows ? List.of(executable + ".exe", executable) : List.of(executable)) {
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

    /**
     * Skips the calling test when the tool is not installed.
     */
    public static void assume(Tool tool) {
        Assumptions.assumeTrue(find(tool).isPresent(), tool.executable + " is not on PATH");
    }

    /**
     * Skips the calling test when the tool of that name is not installed.
     */
    public static void assume(String executable) {
        Assumptions.assumeTrue(find(executable).isPresent(), executable + " is not on PATH");
    }

    /**
     * Runs a tool and returns its standard output, failing on a non-zero exit.
     *
     * @param tool      the tool to run
     * @param timeout   the maximum run time
     * @param arguments the command-line arguments
     * @return the captured standard output
     */
    public static byte[] run(Tool tool, Duration timeout, List<String> arguments) {
        return run(tool, timeout, null, arguments);
    }

    /**
     * Runs a tool with the given standard input and returns its standard output, failing on a
     * non-zero exit.
     */
    public static byte[] run(Tool tool, Duration timeout, byte[] stdin, List<String> arguments) {
        return run(executable(tool), timeout, stdin, arguments);
    }

    /**
     * Runs a tool by name with the given standard input and returns its standard output, failing
     * on a non-zero exit; for a tool without a {@link Tool} constant.
     */
    public static byte[] run(String executable, Duration timeout, byte[] stdin, List<String> arguments) {
        return run(find(executable).orElseThrow(() -> new IllegalStateException(executable + " is not on PATH")),
                timeout, stdin, arguments);
    }

    /**
     * Runs a tool and returns its exit code and both outputs, without failing on a non-zero exit;
     * for tests that inspect the diagnostics of the tool.
     */
    public static Result execute(Tool tool, Duration timeout, byte[] stdin, List<String> arguments) {
        return execute(executable(tool), timeout, stdin, arguments, null);
    }

    /**
     * Runs a tool and returns its standard output followed by its standard error, as one text,
     * failing on a non-zero exit; for tools that report their results on standard error.
     */
    public static String runMerged(Tool tool, Duration timeout, List<String> arguments) {
        var result = execute(executable(tool), timeout, null, arguments, null);
        var text = new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8) + result.stderr();
        if (result.exitCode() != 0) {
            throw new IllegalStateException(tool.executable + " exited with " + result.exitCode() + ": " + arguments
                    + "\n" + text);
        }
        return text;
    }

    /**
     * Runs a tool and streams its standard output into {@code stdout} as it is produced, failing on
     * a non-zero exit; for outputs too large to hold, such as long raw decodes.
     *
     * @return the captured standard error
     */
    public static String run(Tool tool, Duration timeout, List<String> arguments, java.io.OutputStream stdout) {
        var result = execute(executable(tool), timeout, null, arguments, stdout);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(tool.executable + " exited with " + result.exitCode() + ": " + arguments
                    + "\n" + result.stderr());
        }
        return result.stderr();
    }

    private static Path executable(Tool tool) {
        return find(tool).orElseThrow(() -> new IllegalStateException(tool.executable + " is not on PATH"));
    }

    private static byte[] run(Path executable, Duration timeout, byte[] stdin, List<String> arguments) {
        var result = execute(executable, timeout, stdin, arguments, null);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(executable.getFileName() + " exited with " + result.exitCode() + ": "
                    + arguments + "\n" + result.stderr());
        }
        return result.stdout();
    }

    /**
     * Runs an executable, draining both outputs on helper threads so the process never blocks on a
     * full pipe; {@code stdout}, when given, receives the standard output instead of the result.
     */
    private static Result execute(Path executable, Duration timeout, byte[] stdin, List<String> arguments,
                                  java.io.OutputStream stdout) {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(arguments);
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            var out = drain(process.getInputStream(), stdout);
            var err = drain(process.getErrorStream(), null);
            if (stdin != null) {
                try (var input = process.getOutputStream()) {
                    input.write(stdin);
                }
            } else {
                process.getOutputStream().close();
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(executable.getFileName() + " timed out: " + command);
            }
            out.join();
            err.join();
            var result = new Result(process.exitValue(), out.bytes(), err.result());
            process = null;
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } finally {
            // never leave a tool running after a timeout, an interrupt or a failed write to its input
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Decodes the first video stream (or image) of a file to raw frames in the given ffmpeg
     * pixel format, for example {@code rgba}, {@code yuv420p} or {@code gray}.
     */
    public static byte[] decodeVideo(Path input, String pixelFormat, int maxFrames) {
        return decodeVideo(input, List.of(), pixelFormat, maxFrames);
    }

    /**
     * Decodes the first video stream (or image) of a file to raw frames, with extra ffmpeg
     * arguments placed before {@code -i}, such as {@code -c:v libaom-av1} to pick a decoder or
     * {@code -f h264} to force the input format.
     */
    public static byte[] decodeVideo(Path input, List<String> inputArguments, String pixelFormat, int maxFrames) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(inputArguments);
        arguments.addAll(List.of("-i", input.toString(), "-map", "0:v:0"));
        if (maxFrames > 0) {
            arguments.addAll(List.of("-frames:v", Integer.toString(maxFrames)));
        }
        arguments.addAll(List.of("-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
        return run(Tool.FFMPEG, Duration.ofMinutes(5), arguments);
    }

    /**
     * Decodes the first audio stream of a file to interleaved 32-bit little-endian float PCM at
     * its native rate and channel count.
     */
    public static float[] decodeAudio(Path input) {
        return decodeAudio(input, List.of());
    }

    /**
     * Decodes the first audio stream of a file to interleaved 32-bit little-endian float PCM, with
     * extra ffmpeg arguments placed before {@code -i}.
     */
    public static float[] decodeAudio(Path input, List<String> inputArguments) {
        var arguments = new ArrayList<>(List.of("-v", "error"));
        arguments.addAll(inputArguments);
        arguments.addAll(List.of("-i", input.toString(), "-map", "0:a:0", "-f", "f32le", "-"));
        var raw = run(Tool.FFMPEG, Duration.ofMinutes(5), arguments);
        var samples = new float[raw.length / 4];
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples);
        return samples;
    }

    /**
     * Returns ffprobe's JSON description of the streams and format of a file.
     */
    public static String probe(Path input) {
        return new String(run(Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", input.toString())), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Fully decodes every stream of a file with ffmpeg, failing if ffmpeg reports any error;
     * the conformance check for muxer and encoder output.
     */
    public static void assertDecodesCleanly(Path input) {
        run(Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-xerror", "-i", input.toString(), "-f", "null", "-"));
    }

    private static Drain drain(InputStream stream, java.io.OutputStream target) {
        var drain = new Drain(stream, target);
        drain.start();
        return drain;
    }

    /** Collects a process stream on a background thread so the process never blocks on a full pipe. */
    private static final class Drain extends Thread {
        private final InputStream stream;
        private final ByteArrayOutputStream collected = new ByteArrayOutputStream();
        private final java.io.OutputStream target;

        private Drain(InputStream stream, java.io.OutputStream target) {
            this.stream = stream;
            this.target = target;
            setDaemon(true);
        }

        @Override
        public void run() {
            try (stream) {
                stream.transferTo(target != null ? target : collected);
            } catch (IOException ignored) {
                // the process died, or the target failed; whatever was collected is returned
            }
        }

        byte[] bytes() {
            return collected.toByteArray();
        }

        String result() {
            return collected.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
