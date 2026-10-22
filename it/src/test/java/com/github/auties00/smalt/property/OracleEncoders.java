package com.github.auties00.smalt.property;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encodes test inputs with the reference tools, for the properties that need an encoder Smalt does not have (ALAC,
 * lossless JPEG XL) or a second, independent one. Each method skips the calling test when no tool can do it.
 */
public final class OracleEncoders {
    private static final Map<String, Boolean> FFMPEG_ENCODERS = new ConcurrentHashMap<>();

    private static final Path DIRECTORY = Path.of("target", "oracle-encoded");

    private OracleEncoders() {
        throw new AssertionError();
    }

    /**
     * Returns whether the ffmpeg on the path lists an encoder.
     *
     * @param name the encoder name, as {@code ffmpeg -encoders} prints it
     * @return {@code true} when ffmpeg is present and has the encoder
     */
    public static boolean hasFfmpegEncoder(String name) {
        return FFMPEG_ENCODERS.computeIfAbsent(name, key -> {
            if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                return false;
            }
            var listing = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofSeconds(30), List.of("-hide_banner", "-encoders"));
            for (var line : listing.split("\\R")) {
                var fields = line.trim().split("\\s+");
                if (fields.length >= 2 && fields[1].equals(key)) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Skips the calling test unless ffmpeg has an encoder, or fails it where the oracles are required.
     *
     * @param name the encoder name
     */
    public static void assumeFfmpegEncoder(String name) {
        Oracle.assumeEncoder(name);
    }

    /**
     * Encodes a picture losslessly to JPEG XL with cjxl, from a PNG.
     *
     * @param picture the picture
     * @param name a file name stem unique to the caller
     * @return the JPEG XL codestream or container
     */
    public static byte[] jxl(com.github.auties00.smalt.image.Picture picture, String name) {
        Oracle.assume(Oracle.Tool.CJXL);
        var output = DIRECTORY.resolve(name + ".jxl");
        var input = write(name + ".png", com.github.auties00.smalt.image.Images.encode(picture,
                com.github.auties00.smalt.image.ImageFormat.PNG));
        Oracle.run(Oracle.Tool.CJXL, Duration.ofMinutes(2),
                List.of(input.toString(), output.toString(), "-d", "0", "-e", "1"));
        return read(output);
    }

    /**
     * Skips the calling test unless libjpeg-turbo's cjpeg can write lossless JPEG (version 3.0 or later).
     */
    public static void assumeCjpegLossless() {
        Oracle.assume(Oracle.Tool.CJPEG);
        var help = Oracle.execute(Oracle.Tool.CJPEG, Duration.ofSeconds(30), new byte[0], List.of("-help"));
        var text = help.stderr() + new String(help.stdout(), java.nio.charset.StandardCharsets.UTF_8);
        Skip.unless(text.contains("-lossless"), "oracle:cjpeg-lossless", "cjpeg cannot write lossless JPEG");
    }

    /**
     * Encodes a PGM or PPM file to lossless JPEG with libjpeg-turbo's cjpeg.
     *
     * @param netpbm the picture, a binary PGM or PPM file
     * @param precision the sample precision in bits
     * @param predictor the lossless predictor, from 1 to 7
     * @param name a file name stem unique to the caller
     * @return the JPEG file
     */
    public static byte[] cjpegLossless(byte[] netpbm, int precision, int predictor, String name) {
        var input = write(name + ".pnm", netpbm);
        var output = DIRECTORY.resolve(name + ".jpg");
        Oracle.run(Oracle.Tool.CJPEG, Duration.ofMinutes(1), List.of("-precision", Integer.toString(precision),
                "-lossless", Integer.toString(predictor), "-outfile", output.toString(), input.toString()));
        return read(output);
    }

    /**
     * Runs ffmpeg with the given arguments, overwriting the output file.
     *
     * @param arguments the arguments between the global options and the output file
     * @param output the output file
     */
    public static void ffmpeg(List<String> arguments, Path output) {
        var all = new ArrayList<>(List.of("-hide_banner", "-loglevel", "error", "-y"));
        all.addAll(arguments);
        all.add(output.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), all);
    }

    /**
     * Writes a file under the encoder directory.
     *
     * @param name the file name
     * @param data the content
     * @return the path
     */
    public static Path write(String name, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var path = DIRECTORY.resolve(name);
            Files.write(path, data);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns a path under the encoder directory, creating the directory.
     *
     * @param name the file name
     * @return the path
     */
    public static Path path(String name) {
        try {
            Files.createDirectories(DIRECTORY);
            return DIRECTORY.resolve(name);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Reads a file.
     *
     * @param path the file
     * @return its content
     */
    public static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
