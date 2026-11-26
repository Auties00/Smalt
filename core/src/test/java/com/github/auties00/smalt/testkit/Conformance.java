package com.github.auties00.smalt.testkit;


import org.junit.jupiter.api.Assertions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the test data: conformance vectors, archive member sets and raw clips, from the project's hosted copy.
 *
 * <p>The project hosts all the data its tests read, and the tests read nothing else: nothing is downloaded from the
 * sources the manifests in {@code tools/testdata} name. The copy lives in {@link #directory()}, laid out as
 * <ul>
 *   <li>{@code vectors/<name>}, one file per row of {@code vectors.tsv};</li>
 *   <li>{@code archives/<set>.idx}, the member list of a row of {@code archives.tsv}, and
 *       {@code archives/<archive id>/<member path>}, its members;</li>
 *   <li>{@code clips/<name>}, one file per row of {@code clips.tsv}.</li>
 * </ul>
 * A file that is missing, unlisted or whose hash differs from its manifest row fails the test: the data is part of
 * the test.
 *
 * <p>The data is read-only. A test that derives files from it (an oracle's output, an extracted archive) writes them
 * to {@link #cache(String)}.
 */
public final class Conformance {
    /** The hosted test data. */
    private static final Path ROOT = resolve("smalt.testdata", "SMALT_TESTDATA", "testdata");

    /** The writable cache of derived files. */
    private static final Path CACHE = resolve("smalt.cache", "SMALT_CACHE", "cache");

    /** The vectors whose hash this JVM has checked, by name. */
    private static final Map<String, Path> VERIFIED = new ConcurrentHashMap<>();

    /** The sets opened by this JVM. */
    private static final Map<String, Archive> ARCHIVES = new ConcurrentHashMap<>();

    private Conformance() {
        throw new AssertionError();
    }

    private static Path resolve(String property, String environment, String fallback) {
        var configured = System.getProperty(property, System.getenv(environment));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        return Path.of(System.getProperty("user.home"), ".smalt", fallback);
    }

    /**
     * Returns the directory of the hosted test data: the {@code smalt.testdata} system property, else the
     * {@code SMALT_TESTDATA} environment variable, else {@code ~/.smalt/testdata}.
     *
     * @return the test-data directory
     */
    public static Path directory() {
        return ROOT;
    }

    /**
     * Returns a directory of the writable cache, created if needed: the {@code smalt.cache} system property, else
     * the {@code SMALT_CACHE} environment variable, else {@code ~/.smalt/cache}.
     *
     * @param name the subdirectory, for example {@code "raw-oracle"}
     * @return the directory
     */
    public static Path cache(String name) {
        var directory = CACHE.resolve(name);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return directory;
    }

    /**
     * Returns the directory of the raw clips, {@code clips} under {@link #directory()}.
     *
     * @return the clips directory
     */
    public static Path clips() {
        return ROOT.resolve("clips");
    }

    /**
     * Returns a vector listed in {@code tools/testdata/vectors.tsv}, checked against the SHA-256 of its row.
     *
     * @param name the vector name, the second column of the manifest
     * @return the file
     */
    public static Path vector(String name) {
        var verified = VERIFIED.get(name);
        if (verified != null) {
            return verified;
        }
        var row = Manifest.VECTORS.get(name);
        if (row == null) {
            Assertions.fail("vector " + name + " is not listed in " + Manifest.VECTORS_FILE);
        }
        var file = ROOT.resolve("vectors").resolve(name);
        if (!Files.isRegularFile(file)) {
            Assertions.fail("vector " + name + " is missing from " + ROOT.resolve("vectors") + missingHint());
        }
        try {
            var actual = sha256(file);
            if (!actual.equals(row.sha256())) {
                Assertions.fail("SHA-256 mismatch for " + file + ": expected " + row.sha256() + ", got " + actual);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        VERIFIED.put(name, file);
        return file;
    }

    /**
     * Returns whether {@code tools/testdata/vectors.tsv} lists a vector.
     *
     * @param name the vector name
     * @return whether the manifest lists it
     */
    public static boolean listed(String name) {
        return Manifest.VECTORS.containsKey(name);
    }

    /**
     * Returns the names of the vectors of {@code tools/testdata/vectors.tsv} that start with a prefix, sorted.
     *
     * @param prefix the name prefix
     * @return the names
     */
    public static List<String> vectors(String prefix) {
        return Manifest.VECTORS.keySet().stream().filter(name -> name.startsWith(prefix)).sorted().toList();
    }

    private static String missingHint() {
        return "; pull the hosted test data into " + ROOT + " (see tools/testdata/README.md)";
    }

    /**
     * Returns the lowercase hex SHA-256 of a file.
     *
     * @param file the file
     * @return the digest
     * @throws IOException if the file cannot be read
     */
    public static String sha256(Path file) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(input.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Opens a vector for reading.
     *
     * @param vector the file
     * @return the stream
     * @throws IOException if the file cannot be opened
     */
    public static InputStream open(Path vector) throws IOException {
        return Files.newInputStream(vector);
    }

    /**
     * Returns a set of members of an archive listed in {@code tools/testdata/archives.tsv}.
     *
     * @param set the set name, the second column of the manifest
     * @return the set
     */
    public static Archive archive(String set) {
        return ARCHIVES.computeIfAbsent(set, Archive::open);
    }

    /** A row of {@code vectors.tsv}. */
    private record VectorRow(String area, long size, String sha256, String source) {
    }

    /** A row of {@code archives.tsv}. */
    private record ArchiveRow(String area, String indexSha256, String source) {
    }

    /** The manifests, found by walking up from the working directory and loaded on first use. */
    private static final class Manifest {
        static final Path VECTORS_FILE = locate("vectors.tsv");
        static final Path ARCHIVES_FILE = VECTORS_FILE.resolveSibling("archives.tsv");
        static final Map<String, VectorRow> VECTORS = loadVectors();
        static final Map<String, ArchiveRow> ARCHIVES = loadArchives();

        private static Path locate(String name) {
            for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
                var candidate = directory.resolve("tools").resolve("testdata").resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("tools/testdata/" + name + " not found above " + Path.of("").toAbsolutePath());
        }

        private static List<String[]> rows(Path file, int columns) {
            try {
                return Files.readAllLines(file).stream()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .map(line -> line.split("\t", -1))
                        .peek(fields -> {
                            if (fields.length != columns) {
                                throw new IllegalStateException(file + ": expected " + columns + " columns: "
                                        + String.join("\t", fields));
                            }
                        })
                        .toList();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static Map<String, VectorRow> loadVectors() {
            var result = new java.util.HashMap<String, VectorRow>();
            for (var fields : rows(VECTORS_FILE, 5)) {
                result.put(fields[1], new VectorRow(fields[0], Long.parseLong(fields[2]), fields[3], fields[4]));
            }
            return Collections.unmodifiableMap(result);
        }

        private static Map<String, ArchiveRow> loadArchives() {
            var result = new java.util.HashMap<String, ArchiveRow>();
            for (var fields : rows(ARCHIVES_FILE, 7)) {
                result.put(fields[1], new ArchiveRow(fields[0], fields[3], fields[4]));
            }
            return Collections.unmodifiableMap(result);
        }
    }

    /**
     * A set of members of an archive: the files of {@code archives/<archive id>/} that the set's index lists, each
     * checked against the size and CRC-32 the archive's central directory records for it.
     */
    public static final class Archive {
        private final String name;
        private final Path root;
        private final Map<String, long[]> entries;

        private Archive(String name, Path root, Map<String, long[]> entries) {
            this.name = name;
            this.root = root;
            this.entries = entries;
        }

        private static Archive open(String set) {
            var row = Manifest.ARCHIVES.get(set);
            if (row == null) {
                Assertions.fail("archive set " + set + " is not listed in " + Manifest.ARCHIVES_FILE);
            }
            var index = ROOT.resolve("archives").resolve(set + ".idx");
            if (!Files.isRegularFile(index)) {
                Assertions.fail("the index of the archive set " + set + " is missing: " + index + missingHint());
            }
            try {
                var data = Files.readAllBytes(index);
                var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
                if (!actual.equals(row.indexSha256())) {
                    Assertions.fail("SHA-256 mismatch for " + index + ": expected " + row.indexSha256() + ", got "
                            + actual);
                }
                var entries = new LinkedHashMap<String, long[]>();
                for (var line : new String(data, StandardCharsets.UTF_8).split("\n")) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    var fields = line.split("\t");
                    entries.put(fields[0], new long[]{Long.parseLong(fields[1]), Long.parseLong(fields[2], 16)});
                }
                return new Archive(set, ROOT.resolve("archives").resolve(id(row.source())),
                        Collections.unmodifiableMap(entries));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        /**
         * Returns the directory name of an archive: its file name without {@code .zip}, then a dash and the first
         * 12 hex digits of the SHA-256 of its source URL.
         */
        static String id(String source) {
            var path = source.replaceFirst("[?#].*$", "");
            var base = path.substring(path.lastIndexOf('/') + 1).replaceFirst("(?i)\\.zip$", "")
                    .replaceAll("[^A-Za-z0-9._()+-]", "_");
            try {
                var digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
                return base + "-" + HexFormat.of().formatHex(digest).substring(0, 12);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        /** Returns the storage path of a member: empty, {@code .} and {@code ..} segments become {@code _}. */
        static String storage(String member) {
            var segments = member.split("/", -1);
            for (var i = 0; i < segments.length; i++) {
                if (segments[i].isEmpty() || segments[i].equals(".") || segments[i].equals("..")) {
                    segments[i] = "_";
                }
            }
            return String.join("/", segments);
        }

        /**
         * {@return the set name}
         */
        public String name() {
            return name;
        }

        /**
         * Returns the paths of the set's members, in the order of the archive's central directory.
         *
         * @return the member paths
         */
        public List<String> members() {
            return List.copyOf(entries.keySet());
        }

        /**
         * Returns a member, checked against its size and CRC-32.
         *
         * @param member the member path, one of {@link #members()}
         * @return the file
         */
        public Path member(String member) {
            var entry = entries.get(member);
            if (entry == null) {
                throw new IllegalArgumentException(member + " is not a member of the archive set " + name);
            }
            var file = root.resolve(storage(member));
            try {
                if (!Files.isRegularFile(file)) {
                    Assertions.fail("member " + member + " of the archive set " + name + " is missing: " + file
                            + missingHint());
                }
                if (Files.size(file) != entry[0] || crc32(file) != entry[1]) {
                    Assertions.fail("member " + member + " of the archive set " + name
                            + " differs from the size or CRC-32 of its index: " + file);
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return file;
        }

        private static long crc32(Path file) throws IOException {
            var crc = new java.util.zip.CRC32();
            try (var input = Files.newInputStream(file)) {
                var buffer = new byte[1 << 16];
                for (var count = input.read(buffer); count >= 0; count = input.read(buffer)) {
                    crc.update(buffer, 0, count);
                }
            }
            return crc.getValue();
        }
    }
}
