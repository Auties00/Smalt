package com.github.auties00.smalt.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Generates media fixtures with ffmpeg and caches them by the hash of their generating command.
 *
 * <p>Fixtures live under {@code target/media-fixtures}, so a clean build regenerates them. Every
 * method skips the calling test when ffmpeg is not installed. Synthetic sources come from
 * ffmpeg's {@code lavfi} generators, which are deterministic across runs and machines for a given
 * ffmpeg version, with one exception: the {@code gradients} source changes on every run even with
 * a fixed seed, so tests must build smooth content with {@code geq} instead.
 */
public final class Fixtures {
    /** The cache directory. */
    private static final Path CACHE = Path.of("target", "media-fixtures");

    private Fixtures() {
        throw new AssertionError();
    }

    /**
     * Returns a file produced by running ffmpeg with the given output options on the given input
     * options, generating and caching it on first use.
     *
     * @param extension     the output file extension, which selects the muxer, for example
     *                      {@code "mp4"}
     * @param inputOptions  the arguments before the output, typically {@code -f lavfi -i ...}
     * @param outputOptions the codec and muxer arguments applied to the output
     * @return the cached fixture
     */
    public static Path ffmpeg(String extension, List<String> inputOptions, List<String> outputOptions) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var key = String.join("\u0000", inputOptions) + "\u0001" + String.join("\u0000", outputOptions) + "\u0001" + extension;
        var target = CACHE.resolve(hash(key) + "." + extension);
        if (Files.isRegularFile(target)) {
            return target;
        }
        try {
            Files.createDirectories(CACHE);
            var temporary = Files.createTempFile(CACHE, "fixture-", "." + extension);
            var arguments = new ArrayList<String>(List.of("-v", "error", "-y"));
            arguments.addAll(inputOptions);
            arguments.addAll(outputOptions);
            arguments.add(temporary.toString());
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), arguments);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns a still image rendered from ffmpeg's {@code testsrc2} pattern.
     *
     * @param extension     the output extension, for example {@code "png"} or {@code "jpg"}
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param outputOptions the encoder options, for example {@code -pix_fmt rgba}
     */
    public static Path testImage(String extension, int width, int height, List<String> outputOptions) {
        var options = new ArrayList<>(List.of("-frames:v", "1", "-update", "1"));
        options.addAll(outputOptions);
        return ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=1"), options);
    }

    /**
     * Returns a video rendered from ffmpeg's {@code testsrc2} pattern with a sine tone.
     *
     * @param extension     the output extension, for example {@code "mp4"}
     * @param width         the width in pixels
     * @param height        the height in pixels
     * @param seconds       the duration in seconds
     * @param outputOptions the codec options
     */
    public static Path testVideo(String extension, int width, int height, double seconds, List<String> outputOptions) {
        return ffmpeg(extension, List.of(
                "-f", "lavfi", "-i", "testsrc2=size=" + width + "x" + height + ":rate=30:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=" + seconds), outputOptions);
    }

    /**
     * Returns an audio file holding a sine tone.
     *
     * @param extension     the output extension, for example {@code "mp3"}
     * @param frequency     the tone frequency in hertz
     * @param sampleRate    the sampling rate in hertz
     * @param channels      the channel count
     * @param seconds       the duration in seconds
     * @param outputOptions the codec options
     */
    public static Path tone(String extension, int frequency, int sampleRate, int channels, double seconds,
                            List<String> outputOptions) {
        var options = new ArrayList<>(List.of("-ac", Integer.toString(channels)));
        options.addAll(outputOptions);
        return ffmpeg(extension, List.of("-f", "lavfi", "-i",
                "sine=frequency=" + frequency + ":sample_rate=" + sampleRate + ":duration=" + seconds), options);
    }

    /**
     * Returns the bytes of a fixture.
     */
    public static byte[] read(Path fixture) {
        try {
            return Files.readAllBytes(fixture);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String hash(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
