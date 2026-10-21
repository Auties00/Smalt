package com.github.auties00.smalt.testkit;

import org.junit.jupiter.api.Assertions;
import org.opentest4j.TestAbortedException;

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
 * skipped, not failed, on machines without the tool, with the reason id {@code oracle:<executable>}; a tool
 * without a {@link Tool} constant is found and run by its executable name. When
 * {@code smalt.test.requireOracles} (see {@link Skip}) names a tool, a missing tool fails the test instead,
 * through {@link #assume(Tool)} and {@link #find(Tool)} alike, so that a test that only checks for a tool before
 * comparing with it cannot pass silently where CI provisions the tool.
 *
 * <p>An ffmpeg or ffprobe run that fails because the build lacks an encoder, a decoder, a format or a filter
 * skips the test with the reason id {@code oracle:ffmpeg-feature} (see {@link #missingFeature(String)}), so that a
 * local ffmpeg without, say, the opencore AMR libraries skips the tests that need them. On CI it fails instead:
 * every runner's ffmpeg has every component of {@code tools/testdata/ffmpeg-oracle/features.tsv}, and
 * {@code smalt.test.requireOracles} names ffmpeg there, so a missing component is a broken environment, not a gap
 * the skip baseline may record.
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
        CJPEG("cjpeg"),

        /** libwebp's animation encoder CLI. */
        IMG2WEBP("img2webp"),

        /** libwebp's GIF to WebP converter CLI. */
        GIF2WEBP("gif2webp"),

        /** libwebp's container structure dump CLI. */
        WEBPINFO("webpinfo"),

        /** OpenJPEG's codestream structure dump CLI. */
        OPJ_DUMP("opj_dump"),

        /** libtiff's directory listing CLI. */
        TIFFINFO("tiffinfo"),

        /** libtiff's raw tag dump CLI. */
        TIFFDUMP("tiffdump"),

        /** libtiff's RGBA conversion CLI. */
        TIFF2RGBA("tiff2rgba"),

        /** opus-tools' Opus encoder CLI. */
        OPUSENC("opusenc"),

        /** opus-tools' Opus decoder CLI. */
        OPUSDEC("opusdec"),

        /** libopus' {@code opus_compare}, the RFC 6716 decoder conformance comparison. */
        OPUS_COMPARE("opus_compare"),

        /** GPAC's MP4Box, the ISO base media file inspector and packager. */
        MP4BOX("MP4Box"),

        /** MKVToolNix's Matroska muxer. */
        MKVMERGE("mkvmerge"),

        /** MKVToolNix's Matroska structure dump. */
        MKVINFO("mkvinfo"),

        /** The Matroska foundation's Matroska and WebM conformance validator. */
        MKVALIDATOR("mkvalidator"),

        /** TSDuck's transport stream processor. */
        TSP("tsp"),

        /** SoX, the audio file converter. */
        SOX("sox"),

        /** {@code tools/testdata/png-oracle}'s libpng chunk and pixel dump. */
        PNGDUMP("smalt-pngdump"),

        /** {@code tools/testdata/icc-hdr}'s Little CMS profile writer and reader. */
        LCMSDUMP("smalt-lcmsdump");

        /** The executable name without extension. */
        private final String executable;

        Tool(String executable) {
            this.executable = executable;
        }

        /** {@return the executable name without extension} */
        public String executable() {
            return executable;
        }

        /** {@return the lowercase executable name, the name part of the tool's skip reason id} */
        public String id() {
            return executable.toLowerCase(java.util.Locale.ROOT);
        }

        /**
         * Returns the tool of an executable name.
         *
         * @param executable the executable name without extension
         * @return the tool, or empty for a tool without a constant
         */
        public static Optional<Tool> of(String executable) {
            for (var tool : values()) {
                if (tool.executable.equals(executable)) {
                    return Optional.of(tool);
                }
            }
            return Optional.empty();
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
     * Returns the executable of a tool, searching {@code PATH}; fails the calling test when the tool is
     * missing and {@code smalt.test.requireOracles} requires it.
     */
    public static Optional<Path> find(Tool tool) {
        var found = RESOLVED.computeIfAbsent(tool, key -> lookup(key.executable));
        if (found.isEmpty() && required(tool)) {
            Assertions.fail(Skip.message("oracle:" + tool.id(), tool.executable
                    + " is not on PATH and smalt.test.requireOracles requires it"));
        }
        return found;
    }

    /**
     * Returns the executable of a tool by name, searching {@code PATH}; a name with a {@link Tool}
     * constant behaves as {@link #find(Tool)}.
     */
    public static Optional<Path> find(String executable) {
        var tool = Tool.of(executable);
        if (tool.isPresent()) {
            return find(tool.get());
        }
        return RESOLVED_NAMES.computeIfAbsent(executable, Oracle::lookup);
    }

    /**
     * Returns whether {@code smalt.test.requireOracles} requires a tool.
     *
     * @param tool the tool
     * @return whether a missing tool fails instead of skipping
     */
    public static boolean required(Tool tool) {
        return Skip.requires("smalt.test.requireOracles", "SMALT_TEST_REQUIRE_ORACLES", tool.executable);
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
     * Skips the calling test when the tool is not installed, or fails it when
     * {@code smalt.test.requireOracles} requires the tool.
     */
    public static void assume(Tool tool) {
        Skip.unless(find(tool).isPresent(), "oracle:" + tool.id(), tool.executable + " is not on PATH");
    }

    /**
     * Skips the calling test when the tool of that name is not installed; a name with a {@link Tool}
     * constant behaves as {@link #assume(Tool)}.
     */
    public static void assume(String executable) {
        Skip.unless(find(executable).isPresent(), "oracle:" + executable.toLowerCase(java.util.Locale.ROOT),
                executable + " is not on PATH");
    }

    /**
     * Skips the calling test unless ffmpeg is installed and its build has an encoder; fails it instead when
     * {@code smalt.test.requireOracles} requires ffmpeg (see {@link #missingFeature(String)}).
     *
     * @param encoder the encoder name as {@code ffmpeg -encoders} lists it, for example {@code libopencore_amrnb}
     */
    public static void assumeEncoder(String encoder) {
        assume(Tool.FFMPEG);
        if (!Features.ENCODERS.contains(encoder)) {
            throw missingFeature("the ffmpeg build has no " + encoder + " encoder");
        }
    }

    /**
     * Skips the calling test unless ffmpeg is installed and its build has a filter; fails it instead when
     * {@code smalt.test.requireOracles} requires ffmpeg (see {@link #missingFeature(String)}).
     *
     * @param filter the filter name as {@code ffmpeg -filters} lists it, for example {@code zscale}
     */
    public static void assumeFilter(String filter) {
        assume(Tool.FFMPEG);
        if (!Features.FILTERS.contains(filter)) {
            throw missingFeature("the ffmpeg build has no " + filter + " filter");
        }
    }

    /**
     * Skips the calling test with the reason id {@code oracle:ffmpeg-feature} because the ffmpeg build lacks a
     * component the test needs, or fails it when {@code smalt.test.requireOracles} requires ffmpeg.
     *
     * <p>The requirement switch names ffmpeg on CI, whose ffmpeg has every component of
     * {@code tools/testdata/ffmpeg-oracle/features.tsv}; a test that needs a component of an external library
     * lists it there.
     *
     * @param detail what the build lacks
     * @return never returns normally; declared so that a caller can write {@code throw Oracle.missingFeature(...)}
     */
    public static TestAbortedException missingFeature(String detail) {
        if (required(Tool.FFMPEG)) {
            Assertions.fail(Skip.message("oracle:ffmpeg-feature", detail
                    + ", and smalt.test.requireOracles requires an ffmpeg with every component of"
                    + " tools/testdata/ffmpeg-oracle/features.tsv"));
        }
        throw Skip.now("oracle:ffmpeg-feature", detail);
    }

    /** The encoders and filters of the ffmpeg on {@code PATH}, listed once. */
    private static final class Features {
        static final java.util.Set<String> ENCODERS = list("-encoders");
        static final java.util.Set<String> FILTERS = list("-filters");

        /** Collects the second column of an ffmpeg listing, the component names. */
        private static java.util.Set<String> list(String option) {
            var result = execute(executable(Tool.FFMPEG), Duration.ofMinutes(1), null,
                    List.of("-hide_banner", option), null);
            var names = new java.util.HashSet<String>();
            for (var line : new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                var fields = line.trim().split(" +");
                if (fields.length > 1 && !fields[1].equals("=")) {
                    names.add(fields[1]);
                }
            }
            return java.util.Set.copyOf(names);
        }
    }

    /** The ffmpeg diagnostics of a build that lacks a component, with the component name in one group. */
    private static final java.util.regex.Pattern MISSING_FEATURE = java.util.regex.Pattern.compile(
            "Unknown encoder '([^']+)'|Encoder ([^ ]+) not found|Unknown decoder '([^']+)'"
                    + "|No such filter: '([^']+)'|Unknown input format: '([^']+)'"
                    + "|Requested output format '([^']+)' is not (?:a suitable output format|known)");

    /**
     * Skips the calling test when a failed ffmpeg or ffprobe run reports a component its build lacks, or fails it
     * when {@code smalt.test.requireOracles} requires ffmpeg (see {@link #missingFeature(String)}).
     *
     * @param executable the executable that ran
     * @param stderr its standard error
     */
    public static void skipOnMissingFeature(Path executable, String stderr) {
        var name = executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.startsWith("ffmpeg") && !name.startsWith("ffprobe")) {
            return;
        }
        var matcher = MISSING_FEATURE.matcher(stderr);
        if (matcher.find()) {
            for (var group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    throw missingFeature("the ffmpeg build lacks " + matcher.group(group));
                }
            }
        }
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
        assume(executable);
        return run(find(executable).orElseThrow(), timeout, stdin, arguments);
    }

    /**
     * Runs a tool and returns its exit code and both outputs, without failing on a non-zero exit;
     * for tests that inspect the diagnostics of the tool.
     */
    public static Result execute(Tool tool, Duration timeout, byte[] stdin, List<String> arguments) {
        return execute(executable(tool), timeout, stdin, arguments, null);
    }

    /**
     * Runs an executable by path and returns its exit code and both outputs, without failing on a non-zero exit;
     * an ffmpeg or ffprobe executable that crashes is run again as for the tools (see {@link #crashed(int)}).
     *
     * @param executable the executable
     * @param timeout    the maximum run time of one attempt
     * @param stdin      the standard input, or {@code null} for none
     * @param arguments  the command-line arguments
     * @return the exit code and outputs of the last attempt
     */
    public static Result execute(Path executable, Duration timeout, byte[] stdin, List<String> arguments) {
        return execute(executable, timeout, stdin, arguments, null);
    }

    /**
     * Runs a tool and returns its standard output followed by its standard error, as one text,
     * failing on a non-zero exit; for tools that report their results on standard error.
     */
    public static String runMerged(Tool tool, Duration timeout, List<String> arguments) {
        var result = execute(executable(tool), timeout, null, arguments, null);
        var text = new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8) + result.stderr();
        if (result.exitCode() != 0) {
            skipOnMissingFeature(executable(tool), result.stderr());
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
            skipOnMissingFeature(executable(tool), result.stderr());
            throw new IllegalStateException(tool.executable + " exited with " + result.exitCode() + ": " + arguments
                    + "\n" + result.stderr());
        }
        return result.stderr();
    }

    /** Returns the executable of a tool, skipping the calling test (or failing it, when required) without it. */
    private static Path executable(Tool tool) {
        assume(tool);
        return find(tool).orElseThrow();
    }

    private static byte[] run(Path executable, Duration timeout, byte[] stdin, List<String> arguments) {
        var result = execute(executable, timeout, stdin, arguments, null);
        if (result.exitCode() != 0) {
            skipOnMissingFeature(executable, result.stderr());
            throw new IllegalStateException(executable.getFileName() + " exited with " + result.exitCode() + ": "
                    + arguments + "\n" + result.stderr());
        }
        return result.stdout();
    }

    /** The most times an ffmpeg or ffprobe run that crashed is made. */
    private static final int CRASH_ATTEMPTS = 3;

    /**
     * Runs an executable as {@link #executeOnce} does, repeating an ffmpeg or ffprobe run that crashed (see
     * {@link #crashed(int)}) up to {@link #CRASH_ATTEMPTS} times in all when its standard output is captured.
     *
     * <p>ffmpeg 7.1's libaom wrapper crashes now and then in its muxing thread on macOS (a released buffer is
     * released again, in about one run in fifteen of a realtime AV1 fixture under a test suite's load, and in
     * Homebrew's build too); the run is otherwise deterministic, so a repeat yields the fixture. A crash that
     * repeats, or one of another tool, is returned as it is.
     */
    private static Result execute(Path executable, Duration timeout, byte[] stdin, List<String> arguments,
                                  java.io.OutputStream stdout) {
        var name = executable.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        var repeatable = stdout == null && (name.startsWith("ffmpeg") || name.startsWith("ffprobe"));
        for (var attempt = 1; ; attempt++) {
            var result = executeOnce(executable, timeout, stdin, arguments, stdout);
            if (!repeatable || !crashed(result.exitCode()) || attempt == CRASH_ATTEMPTS) {
                return result;
            }
            System.err.println(executable.getFileName() + " crashed with exit code " + result.exitCode()
                    + ", running it again: " + arguments);
        }
    }

    /**
     * Returns whether an exit code reports a crash rather than an error exit: 128 plus SIGILL, SIGTRAP (with which
     * macOS's allocator stops a process whose heap is corrupt), SIGABRT, SIGBUS or SIGSEGV, as the JDK reports a
     * process killed by a signal on Unix, or STATUS_ACCESS_VIOLATION on Windows.
     *
     * @param exitCode the exit code
     * @return whether the process crashed
     */
    public static boolean crashed(int exitCode) {
        return exitCode == 128 + 4 || exitCode == 128 + 5 || exitCode == 128 + 6 || exitCode == 128 + 7
                || exitCode == 128 + 11
                || exitCode == 0xC0000005;
    }

    /**
     * Runs an executable, draining both outputs on helper threads so the process never blocks on a
     * full pipe; {@code stdout}, when given, receives the standard output instead of the result.
     */
    private static Result executeOnce(Path executable, Duration timeout, byte[] stdin, List<String> arguments,
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
