package com.github.auties00.smalt.image.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

/**
 * Generates inputs for the general-purpose compressor and decompressor tests, and checks the pins of their vectors.
 */
public final class CompressionSamples {
    private CompressionSamples() {
    }

    /**
     * Returns bytes mixing runs, text-like repeats, smooth ramps and noise, so that every block and literal
     * type occurs.
     */
    public static byte[] sample(Random random, int size) {
        var data = new byte[size];
        var i = 0;
        while (i < size) {
            var kind = random.nextInt(5);
            var length = Math.min(size - i, 1 + random.nextInt(5000));
            switch (kind) {
                case 0 -> Arrays.fill(data, i, i + length, (byte) random.nextInt(256));
                case 1 -> {
                    for (var k = 0; k < length; k++) {
                        data[i + k] = (byte) random.nextInt(256);
                    }
                }
                case 2 -> {
                    var distance = 1 + random.nextInt(Math.max(1, Math.min(i, 70000)));
                    for (var k = 0; k < length; k++) {
                        data[i + k] = i + k - distance >= 0 ? data[i + k - distance] : (byte) k;
                    }
                }
                case 3 -> {
                    var start = random.nextInt(256);
                    for (var k = 0; k < length; k++) {
                        data[i + k] = (byte) (start + k / (1 + random.nextInt(3)));
                    }
                }
                default -> {
                    for (var k = 0; k < length; k++) {
                        data[i + k] = (byte) ('a' + (int) Math.abs(random.nextGaussian() * 4) % 26);
                    }
                }
            }
            i += length;
        }
        return data;
    }

    /**
     * Fails unless every file a {@code sha256sum}-style manifest lists has its pinned hash.
     */
    public static void assertPinned(Path manifest, Path directory) {
        try {
            var lines = Files.readAllLines(manifest);
            if (lines.isEmpty()) {
                throw new AssertionError("empty manifest " + manifest);
            }
            for (var line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                var hash = line.substring(0, 64);
                var name = line.substring(66);
                var digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(name)));
                if (!hash.equals(HexFormat.of().formatHex(digest))) {
                    throw new AssertionError(name + " does not match its pin in " + manifest.getFileName());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
