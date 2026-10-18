package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.Assumptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Shared helpers of the WebP tests: the VP8 assumption guard, PAM/PGM serialization matching
 * dwebp, digests, libwebp's extra command-line tools, and scratch files.
 */
final class WebpTestSupport {
    static final Path SCRATCH = Path.of("target", "webp-scratch");

    /** Numbers the oracle outputs, keeping their paths short for tools without long-path support. */
    static final java.util.concurrent.atomic.AtomicInteger SCRATCH_COUNTER = new java.util.concurrent.atomic.AtomicInteger();

    private WebpTestSupport() {
        throw new AssertionError();
    }

    /** Runs an action that needs VP8 pixels, skipping the test while the VP8 codec is the stub. */
    static <T> T withVp8(Supplier<T> action) {
        try {
            return action.get();
        } catch (MediaException.Unsupported exception) {
            if ("vp8-stub".equals(exception.format())) {
                Assumptions.abort("VP8 codec pending the VP8 workstream merge");
            }
            throw exception;
        }
    }

    static void withVp8(Runnable action) {
        withVp8(() -> {
            action.run();
            return null;
        });
    }

    /** Decodes any WebP to RGBA (lossy through fancy upsampling), as dwebp -pam does. */
    static RgbaPicture decodeRgba(byte[] file) {
        try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
            var frame = reader.read();
            if (frame == null) {
                throw new AssertionError("no frame");
            }
            return frame.picture();
        }
    }

    static byte[] tightRgba(RgbaPicture picture) {
        var width = picture.width();
        var out = new byte[width * picture.height() * 4];
        for (var y = 0; y < picture.height(); y++) {
            System.arraycopy(picture.pixels(), y * picture.stride(), out, y * width * 4, width * 4);
        }
        return out;
    }

    /** Serializes RGBA like dwebp -pam. */
    static byte[] pam(RgbaPicture picture) {
        var header = "P7\nWIDTH " + picture.width() + "\nHEIGHT " + picture.height()
                + "\nDEPTH 4\nMAXVAL 255\nTUPLTYPE RGB_ALPHA\nENDHDR\n";
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(tightRgba(picture));
        return out.toByteArray();
    }

    /** Serializes Y'CbCr(A) like dwebp -pgm. */
    static byte[] pgm(YuvPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var uvWidth = (width + 1) / 2;
        var uvHeight = (height + 1) / 2;
        var alphaHeight = picture.alpha() != null ? height : 0;
        var out = new ByteArrayOutputStream();
        out.writeBytes(("P5\n" + ((width + 1) & ~1) + " " + (height + uvHeight + alphaHeight) + "\n255\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (var y = 0; y < height; y++) {
            out.write(picture.y(), y * picture.yStride(), width);
            if ((width & 1) != 0) {
                out.write(0);
            }
        }
        for (var y = 0; y < uvHeight; y++) {
            out.write(picture.u(), y * picture.uvStride(), uvWidth);
            out.write(picture.v(), y * picture.uvStride(), uvWidth);
        }
        for (var y = 0; y < alphaHeight; y++) {
            out.write(picture.alpha(), y * picture.alphaStride(), width);
            if ((width & 1) != 0) {
                out.write(0);
            }
        }
        return out.toByteArray();
    }

    /** Returns the RGBA samples of a PAM file written by dwebp -pam (or the corpus). */
    static byte[] pamPixels(byte[] pam) {
        var marker = "ENDHDR\n".getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var i = 0; i + marker.length <= pam.length; i++) {
            for (var j = 0; j < marker.length; j++) {
                if (pam[i + j] != marker[j]) {
                    continue outer;
                }
            }
            var start = i + marker.length;
            var pixels = new byte[pam.length - start];
            System.arraycopy(pam, start, pixels, 0, pixels.length);
            return pixels;
        }
        throw new IllegalArgumentException("not a PAM file");
    }

    static String md5(byte[] data) {
        return digest("MD5", data);
    }

    static String sha256(byte[] data) {
        return digest("SHA-256", data);
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Path scratch(String name, byte[] data) {
        try {
            Files.createDirectories(SCRATCH);
            var file = SCRATCH.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns a path in the scratch directory, creating the directory. */
    static Path scratchPath(String name) {
        try {
            Files.createDirectories(SCRATCH);
            return SCRATCH.resolve(name);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static byte[] readFile(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes a file with dwebp to PAM RGBA pixels (skipping when dwebp is absent). */
    static byte[] dwebpRgba(Path file) {
        Oracle.assume(Oracle.Tool.DWEBP);
        var out = scratchPath("dw" + SCRATCH_COUNTER.incrementAndGet() + ".pam");
        Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(2), List.of(file.toString(), "-pam", "-o", out.toString()));
        return pamPixels(readFile(out));
    }

    /** Locates one of libwebp's extra command-line tools (webpmux, img2webp, gif2webp, webpinfo). */
    static Optional<Path> tool(String name) {
        var path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        var windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        for (var directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (var candidate : windows ? List.of(name + ".exe", name) : List.of(name)) {
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

    /** Runs an extra tool, skipping when it is absent, and returns stdout+stderr; fails on a non-zero exit. */
    static String runTool(String name, List<String> arguments) {
        var executable = tool(name);
        Assumptions.assumeTrue(executable.isPresent(), name + " is not on PATH");
        var command = new ArrayList<String>();
        command.add(executable.get().toString());
        command.addAll(arguments);
        try {
            var process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(name + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(name + " exited with " + process.exitValue() + ": " + command + "\n" + output);
            }
            return output;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
