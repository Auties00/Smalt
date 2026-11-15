package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers of the image metadata tests: fixtures, exiftool as the independent reader and writer,
 * and format-level extraction of the compressed pixel data that edits must not touch.
 */
final class MetadataSupport {
    static final Path DIRECTORY = Scratch.directory("metadata-fixtures");

    private MetadataSupport() {
    }

    static byte[] jpeg() {
        return Fixtures.read(Fixtures.testImage("jpg", 96, 64, List.of("-q:v", "3")));
    }

    static byte[] png() {
        return Fixtures.read(Fixtures.testImage("png", 96, 64, List.of()));
    }

    static byte[] webp() {
        return Fixtures.read(Fixtures.testImage("webp", 96, 64, List.of("-c:v", "libwebp", "-lossless", "0")));
    }

    static byte[] webpLossless() {
        return Fixtures.read(Fixtures.testImage("webp", 96, 64, List.of("-c:v", "libwebp", "-lossless", "1")));
    }

    static byte[] gif() {
        return Fixtures.read(Fixtures.testImage("gif", 96, 64, List.of()));
    }

    static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Runs exiftool with arguments, returning its standard output. */
    static String exiftool(List<String> arguments) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        return new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), arguments), StandardCharsets.UTF_8);
    }

    /** Writes tags with exiftool into a copy of an image and returns the result. */
    static byte[] exiftoolWrite(String name, byte[] image, List<String> assignments) {
        var file = save(name, image);
        var arguments = new ArrayList<>(List.of("-overwrite_original", "-charset", "iptc=UTF8", "-codedcharacterset=UTF8"));
        arguments.addAll(assignments);
        arguments.add(file.toString());
        exiftool(arguments);
        return Fixtures.read(file);
    }

    /** Reads every tag exiftool sees, keyed {@code Group1:Tag} with numeric values. */
    static Map<String, String> exiftoolRead(byte[] image, String name) {
        var file = save(name, image);
        var output = exiftool(List.of("-a", "-G1", "-s", "-n", "-charset", "UTF8", file.toString()));
        var result = new LinkedHashMap<String, String>();
        for (var line : output.split("\\R")) {
            if (!line.startsWith("[")) {
                continue;
            }
            var groupEnd = line.indexOf(']');
            var colon = line.indexOf(':', groupEnd);
            if (groupEnd < 0 || colon < 0) {
                continue;
            }
            var group = line.substring(1, groupEnd).trim();
            var tag = line.substring(groupEnd + 1, colon).trim();
            result.putIfAbsent(group + ":" + tag, line.substring(colon + 1).trim());
        }
        return result;
    }

    /** Returns the warnings exiftool's validation reports. */
    static List<String> exiftoolWarnings(byte[] image, String name) {
        var file = save(name, image);
        var output = exiftool(List.of("-validate", "-warning", "-a", "-s", file.toString()));
        var result = new ArrayList<String>();
        for (var line : output.split("\\R")) {
            if (line.startsWith("Warning") || line.startsWith("Error")) {
                result.add(line);
            }
        }
        return result;
    }

    /** Returns the bytes of a JPEG file from its first start-of-scan marker on. */
    static byte[] jpegScan(byte[] jpeg) {
        var position = 2;
        while (position + 4 <= jpeg.length) {
            var marker = jpeg[position + 1] & 0xFF;
            if (marker == 0xDA) {
                return Arrays.copyOfRange(jpeg, position, jpeg.length);
            }
            var length = ((jpeg[position + 2] & 0xFF) << 8) | (jpeg[position + 3] & 0xFF);
            position += 2 + length;
        }
        throw new AssertionError("no start-of-scan marker");
    }

    /** Returns the concatenated payload of the image chunks of a PNG file. */
    static byte[] pngImageData(byte[] png) {
        var out = new ByteArrayOutputStream();
        var position = 8;
        while (position + 12 <= png.length) {
            var length = ((png[position] & 0xFF) << 24) | ((png[position + 1] & 0xFF) << 16) | ((png[position + 2] & 0xFF) << 8)
                    | (png[position + 3] & 0xFF);
            var type = new String(png, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("IDAT") || type.equals("fdAT") || type.equals("PLTE") || type.equals("tRNS")) {
                out.write(png, position + 4, length + 4);
            }
            position += 12 + length;
        }
        return out.toByteArray();
    }

    /** Returns the concatenated image chunks of a WebP file. */
    static byte[] webpImageData(byte[] webp) {
        var out = new ByteArrayOutputStream();
        for (var chunk : WebpContainer.parse(webp)) {
            switch (chunk.fourCc()) {
                case "VP8 ", "VP8L", "ALPH", "ANMF", "ANIM" -> out.write(webp, chunk.offset(), chunk.end() - chunk.offset());
                default -> {
                }
            }
        }
        return out.toByteArray();
    }

    /** Returns the bytes of the image blocks of a GIF file, found by walking its blocks. */
    static byte[] gifImageData(byte[] gif) {
        var out = new ByteArrayOutputStream();
        var packed = gif[10] & 0xFF;
        var position = 13 + ((packed & 0x80) != 0 ? 3 * (1 << ((packed & 0x07) + 1)) : 0);
        while (position < gif.length && (gif[position] & 0xFF) != 0x3B) {
            var start = position;
            if ((gif[position] & 0xFF) == 0x21) {
                position += 2;
            } else {
                var local = gif[position + 9] & 0xFF;
                position += 10 + ((local & 0x80) != 0 ? 3 * (1 << ((local & 0x07) + 1)) : 0) + 1;
            }
            while ((gif[position] & 0xFF) != 0) {
                position += 1 + (gif[position] & 0xFF);
            }
            position++;
            if ((gif[start] & 0xFF) == 0x2C) {
                out.write(gif, start, position - start);
            }
        }
        return out.toByteArray();
    }
}
