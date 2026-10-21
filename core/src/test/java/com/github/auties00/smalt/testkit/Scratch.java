package com.github.auties00.smalt.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * Scratch directories under {@code target} that one surefire fork owns.
 *
 * <p>A module's suite may run in several forks at once ({@code -Dsmalt.test.forks}), all with the module
 * directory as their working directory. A helper that several test classes share writes its files under
 * {@link #directory(String)}, which adds the fork's directory ({@code smalt.test.fork}, {@code fork-<number>},
 * set by the surefire configuration of the parent pom), so that two forks never write, or read back, the same
 * file. Outside surefire, for example in an IDE, the directory is {@code target/<name>} itself. Files shared
 * across forks on purpose, such as the content-keyed {@code target/media-fixtures} of {@link Fixtures}, are
 * written to a temporary file and moved into place instead ({@link #share(Path, byte[])}).
 */
public final class Scratch {
    /** The fork's directory name, {@code fork-<number>}, or an empty string outside surefire. */
    private static final String FORK = fork();

    private Scratch() {
        throw new AssertionError();
    }

    /**
     * Returns {@code target/<name>} of this fork, creating it.
     *
     * @param name the directory name under {@code target}
     * @return the directory
     */
    public static Path directory(String name) {
        var directory = FORK.isEmpty() ? Path.of("target", name) : Path.of("target", name, FORK);
        try {
            return Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Writes a file that several forks may write at once with the same contents: a file that already holds
     * them is left alone, and otherwise they go through a temporary file moved into place, so that a reader
     * never sees the file half written.
     *
     * @param file the file
     * @param data the contents
     * @return the file
     */
    public static Path share(Path file, byte[] data) {
        try {
            if (holds(file, data)) {
                return file;
            }
            var parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            var temporary = Files.createTempFile(parent, "scratch-", ".tmp");
            Files.write(temporary, data);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException exception) {
                // Windows refuses to replace a file another process has open: another fork wrote it first
                Files.deleteIfExists(temporary);
                if (!holds(file, data)) {
                    throw exception;
                }
            }
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static boolean holds(Path file, byte[] data) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == data.length
                && Arrays.equals(Files.readAllBytes(file), data);
    }

    private static String fork() {
        var value = System.getProperty("smalt.test.fork", "");
        return value.matches("fork-[0-9]+") ? value : "";
    }
}
