package com.github.auties00.smalt.internal.compress;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The bzip2 and LZO1X streams written by tools/testdata/compress/make-vectors.py, each with the size and SHA-256 of
 * its decoded content.
 */
public final class CompressVectors {
    /**
     * The directory of the vectors, relative to the core module.
     */
    public static final Path DIRECTORY = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "internal", "compress");

    private CompressVectors() {
    }

    /**
     * One stream and what it decodes to.
     *
     * @param name the file name
     * @param size the decoded size
     * @param sha256 the SHA-256 of the decoded content, in lowercase hexadecimal
     * @param stream the compressed bytes
     */
    public record Vector(String name, int size, String sha256, byte[] stream) {
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Returns the vectors of one format.
     *
     * @param format {@code bzip2} or {@code lzo}
     * @return the vectors, in manifest order
     */
    public static List<Vector> of(String format) {
        var directory = DIRECTORY.resolve(format);
        try {
            return Files.readAllLines(directory.resolve("files.tsv")).stream()
                    .filter(line -> !line.startsWith("#") && !line.isBlank())
                    .map(line -> line.split("\t"))
                    .map(parts -> new Vector(parts[0], Integer.parseInt(parts[1]), parts[2], bytes(directory.resolve(parts[0]))))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Returns one vector of a format.
     *
     * @param format {@code bzip2} or {@code lzo}
     * @param name the file name
     * @return the vector
     */
    public static Vector named(String format, String name) {
        return of(format).stream().filter(vector -> vector.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * Returns the SHA-256 of bytes in lowercase hexadecimal.
     *
     * @param data the bytes
     * @return the digest
     */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static byte[] bytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
