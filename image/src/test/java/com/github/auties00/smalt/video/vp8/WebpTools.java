package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;

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

/** Runs cwebp and dwebp on cached fixtures for the VP8 still-image tests. */
final class WebpTools {
    private static final Path CACHE = Path.of("target", "media-fixtures");

    private WebpTools() {
        throw new AssertionError();
    }

    /** Returns an RGB PNG rendered from an ffmpeg lavfi source scaled to the given size. */
    static Path png(String source, int width, int height) {
        return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", source + ",scale=" + width + ":" + height + ",format=rgb24"),
                List.of("-frames:v", "1", "-update", "1"));
    }

    /** Returns raw I420 data rendered from an ffmpeg lavfi source scaled to the given size. */
    static byte[] yuv(String source, int width, int height) {
        return Fixtures.read(Fixtures.ffmpeg("yuv", List.of("-f", "lavfi", "-i", source + ",scale=" + width + ":" + height),
                List.of("-frames:v", "1", "-pix_fmt", "yuv420p", "-f", "rawvideo")));
    }

    /** Encodes an input with cwebp, caching the result. */
    static byte[] cwebp(Path input, List<String> options) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var key = input.getFileName() + "|" + String.join(" ", options);
        var target = CACHE.resolve("cwebp-" + hash(key) + ".webp");
        try {
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(CACHE);
                var temporary = Files.createTempFile(CACHE, "cwebp-", ".webp");
                var arguments = new ArrayList<>(List.of("-quiet"));
                arguments.addAll(options);
                arguments.addAll(List.of(input.toString(), "-o", temporary.toString()));
                Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(5), arguments);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return Files.readAllBytes(target);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Encodes raw I420 input with cwebp (its -s mode), caching the result. */
    static byte[] cwebpYuv(byte[] yuv, int width, int height, List<String> options) {
        Oracle.assume(Oracle.Tool.CWEBP);
        try {
            Files.createDirectories(CACHE);
            var input = CACHE.resolve("i420-" + hash(HexFormat.of().formatHex(sha(yuv))) + ".yuv");
            if (!Files.isRegularFile(input)) {
                Files.write(input, yuv);
            }
            var arguments = new ArrayList<>(List.of("-s", Integer.toString(width), Integer.toString(height)));
            arguments.addAll(options);
            return cwebp(input, arguments);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes a WebP file with dwebp to raw planar YUV (dwebp -yuv layout). */
    static byte[] dwebpYuv(byte[] webp) {
        Oracle.assume(Oracle.Tool.DWEBP);
        try {
            Files.createDirectories(CACHE);
            var input = Files.createTempFile(CACHE, "dwebp-", ".webp");
            try {
                Files.write(input, webp);
                return Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(5),
                        List.of("-nodither", "-yuv", input.toString(), "-o", "-"));
            } finally {
                Files.deleteIfExists(input);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String hash(String key) {
        return HexFormat.of().formatHex(sha(key.getBytes(StandardCharsets.UTF_8)), 0, 12);
    }

    static byte[] sha(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
