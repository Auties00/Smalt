package com.github.auties00.smalt.testkit;

import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Compares readable text dumps with the golden files kept under {@code src/test/resources/dumps/} of the
 * module running the test.
 *
 * <p>A golden file is plain text with LF line endings (CRLF is accepted when reading), one fact per line,
 * so that a change in behavior shows up as a reviewable diff. A missing golden fails the test. Running
 * with {@code -Dsmalt.test.updateDumps=true} (or {@code SMALT_TEST_UPDATE_DUMPS=true}) writes every
 * compared dump to its golden file instead, creating directories as needed, and passes; review the
 * diff before committing it.
 *
 * <p>Golden files are resolved against the module directory, the working directory of surefire, so a
 * test run from another directory sets {@code smalt.test.dumps} to the dumps directory.
 */
public final class Dumps {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    private Dumps() {
        throw new AssertionError();
    }

    /**
     * {@return whether dumps are written to their golden files instead of compared}
     */
    public static boolean updating() {
        var value = System.getProperty("smalt.test.updateDumps", System.getenv("SMALT_TEST_UPDATE_DUMPS"));
        return value != null && Boolean.parseBoolean(value.strip());
    }

    /**
     * {@return the directory holding the golden dumps of the running module}
     */
    public static Path directory() {
        var configured = System.getProperty("smalt.test.dumps");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of("src", "test", "resources", "dumps");
    }

    /**
     * Returns the golden file of a dump.
     *
     * @param name the dump name, a relative path of letters, digits, dots, dashes and underscores
     * without the {@code .dump} extension, for example {@code "mp4/faststart"}
     * @return the golden file
     * @throws IllegalArgumentException if {@code name} is not such a path
     */
    public static Path golden(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid dump name " + name);
        }
        return directory().resolve(name + ".dump");
    }

    /**
     * Asserts that a dump equals its golden file, or writes it there when {@linkplain #updating()
     * updating}.
     *
     * @param name the dump name, as {@link #golden(String)} takes it
     * @param actual the dump text; a missing final newline is added
     * @throws AssertionFailedError if the golden file is missing or differs, naming the first differing
     * line
     */
    public static void assertMatches(String name, String actual) {
        var text = actual.endsWith("\n") ? actual : actual + "\n";
        var file = golden(name);
        if (updating()) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                if (!Files.isRegularFile(file) || !normalize(Files.readString(file)).equals(text)) {
                    Files.writeString(file, text, StandardCharsets.UTF_8);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException("cannot write " + file, exception);
            }
            return;
        }
        if (!Files.isRegularFile(file)) {
            throw new AssertionFailedError("missing golden dump " + file.toAbsolutePath()
                    + "; run with -Dsmalt.test.updateDumps=true to create it and review it");
        }
        String expected;
        try {
            expected = normalize(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read " + file, exception);
        }
        if (!expected.equals(text)) {
            throw new AssertionFailedError("dump " + name + " differs from " + file + " " + firstDifference(expected,
                    text) + "; run with -Dsmalt.test.updateDumps=true to accept the change", expected, text);
        }
    }

    /**
     * Describes the first line where two texts differ, with the lines of both.
     *
     * @param expected the expected text
     * @param actual the actual text
     * @return the description, or an empty string when the texts are equal
     */
    public static String firstDifference(String expected, String actual) {
        var left = expected.split("\n", -1);
        var right = actual.split("\n", -1);
        var count = Math.min(left.length, right.length);
        for (var i = 0; i < count; i++) {
            if (!left[i].equals(right[i])) {
                return "at line " + (i + 1) + ": expected <" + left[i] + "> but was <" + right[i] + ">";
            }
        }
        if (left.length != right.length) {
            return "at line " + (count + 1) + ": expected " + left.length + " lines but was " + right.length;
        }
        return "";
    }

    /**
     * {@return the CRC-32 of a byte range, as eight lowercase hex digits}
     *
     * @param data the bytes
     * @param offset the first byte
     * @param length the number of bytes
     */
    public static String crc32(byte[] data, int offset, int length) {
        var crc = new CRC32();
        crc.update(data, offset, length);
        return "%08x".formatted(crc.getValue());
    }

    /**
     * {@return the CRC-32 of a byte array, as eight lowercase hex digits}
     *
     * @param data the bytes
     */
    public static String crc32(byte[] data) {
        return crc32(data, 0, data.length);
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }
}
