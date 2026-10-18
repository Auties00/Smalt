package com.github.auties00.smalt.testkit;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Downloads and caches official conformance vectors (ITU-T bitstreams, codec test vectors,
 * PngSuite, and so on), verifying each against a pinned SHA-256.
 *
 * <p>The cache lives in {@link #directory()}, shared by every checkout.
 * When a vector is missing and cannot be downloaded (offline machine, moved URL, downloads turned
 * off, or a server answering with an HTML page instead of the file), the calling test is skipped
 * rather than failed; a hash mismatch always fails, because it means the pinned vector changed
 * under us. Only {@code java.base} networking is used, because
 * tests run inside the Smalt modules, which read no other JDK module.
 *
 * <p>Two switches serve continuous integration, which fills the cache ahead of the build from the
 * tiered manifest in {@code tools/testdata} and then runs the tests against it:
 * <ul>
 *   <li>{@code smalt.conformance.download} (or {@code SMALT_CONFORMANCE_DOWNLOAD}) set to
 *       {@code false} never downloads: a vector absent from the cache skips its test, so a
 *       build restricted to a tier never reaches out to the network;</li>
 *   <li>{@code smalt.conformance.record} (or {@code SMALT_CONFORMANCE_RECORD}) names a file to
 *       which every requested vector is appended as a tab-separated {@code name url sha256} line,
 *       cached or not, which is how the manifest is regenerated.</li>
 * </ul>
 */
public final class Conformance {
    /** The cache directory. */
    private static final Path CACHE = resolveDirectory();

    /** Whether missing vectors are downloaded. */
    private static final boolean DOWNLOAD = !"false".equalsIgnoreCase(setting("smalt.conformance.download",
            "SMALT_CONFORMANCE_DOWNLOAD"));

    /** The file every requested vector is recorded to, or {@code null} when recording is off. */
    private static final Path RECORD = resolveRecord();

    /** Serializes appends to {@link #RECORD} across the test threads of one JVM. */
    private static final Object RECORD_LOCK = new Object();

    /**
     * Returns the cache directory.
     *
     * @return the cache directory
     */
    public static Path directory() {
        return CACHE;
    }

    /**
     * Resolves the cache directory: the {@code smalt.conformance.cache} system property, else the
     * {@code SMALT_CONFORMANCE_CACHE} environment variable, else {@code ~/.smalt/cache/conformance}.
     *
     * @return the cache directory
     */
    private static Path resolveDirectory() {
        var configured = System.getProperty("smalt.conformance.cache", System.getenv("SMALT_CONFORMANCE_CACHE"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".smalt", "cache", "conformance");
    }

    /**
     * Returns a setting from a system property, else from an environment variable.
     *
     * @param property    the system property
     * @param environment the environment variable
     * @return the value, or {@code null} when neither is set or the value is blank
     */
    private static String setting(String property, String environment) {
        var value = System.getProperty(property, System.getenv(environment));
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Resolves the record file from {@code smalt.conformance.record} or
     * {@code SMALT_CONFORMANCE_RECORD}.
     *
     * @return the record file, or {@code null} when recording is off
     */
    private static Path resolveRecord() {
        var configured = setting("smalt.conformance.record", "SMALT_CONFORMANCE_RECORD");
        return configured == null ? null : Path.of(configured);
    }

    private Conformance() {
        throw new AssertionError();
    }

    /**
     * Returns the cached copy of a vector, downloading it first when needed.
     *
     * @param name   the cache file name, unique across all suites, for example
     *               {@code "vp80-00-comprehensive-001.ivf"}
     * @param url    the download location
     * @param sha256 the expected lowercase hex SHA-256 of the file
     * @return the verified file
     */
    public static Path fetch(String name, String url, String sha256) {
        var target = CACHE.resolve(name);
        try {
            record(name, url, sha256);
            if (Files.isRegularFile(target)) {
                verify(target, sha256);
                return target;
            }
            Assumptions.assumeTrue(DOWNLOAD, "conformance vector " + name + " is not cached in " + CACHE
                    + " and downloads are off (smalt.conformance.download=false)");
            Files.createDirectories(CACHE);
            var temporary = Files.createTempFile(CACHE, "download-", ".part");
            try {
                download(url, temporary);
                verify(temporary, sha256);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            Assumptions.abort("conformance vector " + name + " unavailable: " + exception);
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Appends a requested vector to the record file, when recording is on.
     *
     * @param name   the cache file name
     * @param url    the download location
     * @param sha256 the expected SHA-256
     * @throws IOException if the record file cannot be written
     */
    private static void record(String name, String url, String sha256) throws IOException {
        if (RECORD == null) {
            return;
        }
        synchronized (RECORD_LOCK) {
            var parent = RECORD.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(RECORD, name + "\t" + url + "\t" + sha256.toLowerCase() + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static void download(String url, Path target) throws IOException {
        var location = URI.create(url);
        for (var redirects = 0; redirects < 8; redirects++) {
            var connection = (HttpURLConnection) location.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(300_000);
            var status = connection.getResponseCode();
            if (status / 100 == 3) {
                var next = connection.getHeaderField("Location");
                connection.disconnect();
                Assumptions.assumeTrue(next != null, "redirect without Location from " + location);
                location = location.resolve(next);
                continue;
            }
            Assumptions.assumeTrue(status / 100 == 2, "HTTP " + status + " from " + location);
            // An HTML page in place of a binary vector is a landing or bot-check page, not the file
            var contentType = connection.getContentType();
            var path = location.getPath().toLowerCase();
            if (contentType != null && contentType.startsWith("text/html")
                    && !path.endsWith(".html") && !path.endsWith(".htm")) {
                connection.disconnect();
                Assumptions.abort("HTML page instead of the file from " + location);
            }
            try (var input = connection.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        Assumptions.abort("too many redirects from " + url);
    }

    /**
     * Returns the lowercase hex SHA-256 of a file.
     */
    public static String sha256(Path file) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void verify(Path file, String expected) throws IOException {
        var actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException("SHA-256 mismatch for " + file + ": expected " + expected + ", got " + actual);
        }
    }

    /**
     * Opens a cached vector for reading.
     */
    public static InputStream open(Path vector) throws IOException {
        return Files.newInputStream(vector);
    }
}
