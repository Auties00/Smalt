package com.github.auties00.smalt.testkit.io;

import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Lists the committed samples of the I/O behavior harnesses and checks them against
 * {@code tools/testdata/behavior/samples.tsv}, the manifest that pins each one by size and SHA-256.
 *
 * <p>A sample's golden dumps are tied to its exact bytes, so a sample regenerated with another encoder build
 * changes its dumps; the manifest makes that a reviewed change. {@code python -I
 * tools/testdata/behavior/pin-samples.py} rewrites the manifest from the committed files.
 */
public final class BehaviorSamples {
    private static final String MANIFEST = "tools/testdata/behavior/samples.tsv";

    private BehaviorSamples() {
        throw new AssertionError();
    }

    /**
     * Returns the samples under a directory, as sorted paths relative to it with forward slashes.
     *
     * @param directory the samples directory, for example {@code src/test/resources/samples}
     * @return the relative paths
     * @throws UncheckedIOException if the directory cannot be listed
     */
    public static List<String> list(Path directory) {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list " + directory, exception);
        }
    }

    /**
     * Asserts that the samples under a directory are exactly the manifest's rows for that directory, each
     * with the pinned size and SHA-256.
     *
     * @param directory the samples directory, inside the repository
     * @throws AssertionFailedError if a sample is unlisted, missing or differs from its row
     */
    public static void assertPinned(Path directory) {
        var absolute = directory.toAbsolutePath().normalize();
        var root = absolute;
        while (root != null && !Files.isRegularFile(root.resolve(MANIFEST))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new AssertionFailedError(MANIFEST + " not found above " + absolute);
        }
        var prefix = root.relativize(absolute).toString().replace('\\', '/') + "/";
        var rows = new TreeMap<String, String[]>();
        try {
            for (var line : Files.readAllLines(root.resolve(MANIFEST))) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.split("\t");
                if (columns[0].startsWith(prefix)) {
                    rows.put(columns[0].substring(prefix.length()), columns);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read " + MANIFEST, exception);
        }
        var problems = new StringBuilder();
        for (var sample : list(directory)) {
            var row = rows.remove(sample);
            if (row == null) {
                problems.append("\n  ").append(sample).append(" is not listed");
                continue;
            }
            byte[] data;
            try {
                data = Files.readAllBytes(directory.resolve(sample));
            } catch (IOException exception) {
                throw new UncheckedIOException("cannot read " + sample, exception);
            }
            var hash = sha256(data);
            if (data.length != Long.parseLong(row[1]) || !hash.equals(row[2])) {
                problems.append("\n  ").append(sample).append(" is ").append(data.length).append(" bytes, ")
                        .append(hash).append(", but is pinned as ").append(row[1]).append(" bytes, ").append(row[2]);
            }
        }
        for (var missing : rows.keySet()) {
            problems.append("\n  ").append(missing).append(" is listed but missing");
        }
        if (!problems.isEmpty()) {
            throw new AssertionFailedError("the samples of " + prefix + " do not match " + MANIFEST + problems
                    + "\nrun python -I tools/testdata/behavior/pin-samples.py after reviewing the change");
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
