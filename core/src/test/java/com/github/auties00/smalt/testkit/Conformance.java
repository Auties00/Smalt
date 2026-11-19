package com.github.auties00.smalt.testkit;


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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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
            Skip.unless(DOWNLOAD, "data:vector", "conformance vector " + name + " is not cached in " + CACHE
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
            throw Skip.now("data:vector", "conformance vector " + name + " unavailable: " + exception);
        }
    }

    /**
     * Returns the cached copy of a vector listed in {@code tools/testdata/vectors.tsv}, downloading it
     * first when needed, with the URL and SHA-256 of its manifest row.
     *
     * <p>The manifest is found by walking up from the working directory; a test that cannot find it, or
     * whose vector is not listed, fails, because the manifest is part of the repository.
     *
     * @param name the vector name, the second column of the manifest
     * @return the verified file
     */
    public static Path fetchListed(String name) {
        var row = Manifest.ROWS.get(name);
        if (row == null) {
            throw new IllegalArgumentException("vector " + name + " is not listed in " + Manifest.FILE);
        }
        return fetch(name, row[1], row[0]);
    }

    /**
     * The rows of {@code tools/testdata/vectors.tsv}, loaded on first use.
     */
    private static final class Manifest {
        static final Path FILE = locate();
        static final java.util.Map<String, String[]> ROWS = load();

        private static Path locate() {
            for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
                var candidate = directory.resolve("tools").resolve("testdata").resolve("vectors.tsv");
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            throw new IllegalStateException("tools/testdata/vectors.tsv not found above " + Path.of("").toAbsolutePath());
        }

        private static java.util.Map<String, String[]> load() {
            var rows = new java.util.HashMap<String, String[]>();
            try {
                for (var line : Files.readAllLines(FILE)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    var columns = line.split("\t");
                    if (columns.length >= 5) {
                        rows.put(columns[1], new String[]{columns[3], columns[4]});
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return rows;
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

    /**
     * The pauses before the further attempts of a download that failed transiently, those of
     * {@code tools/testdata/testdata.py}: the hosts of the conformance suites answer 5xx, or reset the connections of
     * a runner that downloads many vectors, for minutes at a time.
     */
    private static final long[] RETRY_PAUSES_MILLIS = {10_000, 30_000, 60_000, 90_000, 120_000};

    /**
     * Downloads a file, retrying when the server answers 429 or 5xx or the connection fails, as the hosts of the
     * conformance suites do under the load of many runners.
     */
    private static void download(String url, Path target) throws IOException {
        for (var attempt = 0; ; attempt++) {
            try {
                downloadOnce(url, target);
                return;
            } catch (Transient exception) {
                if (attempt >= RETRY_PAUSES_MILLIS.length) {
                    throw Skip.now("data:vector", exception.getMessage());
                }
                pause(RETRY_PAUSES_MILLIS[attempt]);
            } catch (IOException exception) {
                if (attempt >= RETRY_PAUSES_MILLIS.length) {
                    throw exception;
                }
                pause(RETRY_PAUSES_MILLIS[attempt]);
            }
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw Skip.now("data:vector", "interrupted while waiting to retry a download");
        }
    }

    /** A server answer that a later attempt may not repeat. */
    private static final class Transient extends Exception {
        Transient(String message) {
            super(message, null, false, false);
        }
    }

    private static void downloadOnce(String url, Path target) throws IOException, Transient {
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
                Skip.unless(next != null, "data:vector", "redirect without Location from " + location);
                location = location.resolve(next);
                continue;
            }
            if (status == 429 || status / 100 == 5) {
                connection.disconnect();
                throw new Transient("HTTP " + status + " from " + location);
            }
            Skip.unless(status / 100 == 2, "data:vector", "HTTP " + status + " from " + location);
            // An HTML page in place of a binary vector is a landing or bot-check page, not the file
            var contentType = connection.getContentType();
            var path = location.getPath().toLowerCase();
            if (contentType != null && contentType.startsWith("text/html")
                    && !path.endsWith(".html") && !path.endsWith(".htm")) {
                connection.disconnect();
                throw Skip.now("data:vector", "HTML page instead of the file from " + location);
            }
            try (var input = connection.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return;
        }
        throw Skip.now("data:vector", "too many redirects from " + url);
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

    /**
     * Returns a set of members of a ZIP archive listed in {@code tools/testdata/archives.tsv}.
     *
     * <p>A set names an archive by URL and by the SHA-256 of its central directory, and selects the members whose
     * path matches a regular expression. The central directory and each member are downloaded on first use by HTTP
     * range requests, so that a suite of several gigabytes is never downloaded whole, and cached in
     * {@code <cache>/archives}, where {@code tools/testdata/testdata.py fetch} also puts them. A member is checked
     * against the size and CRC-32 the pinned central directory records. A set that is not listed fails the test.
     *
     * @param set the set name, the second column of the manifest
     * @return the set
     */
    public static Archive archive(String set) {
        var row = ArchiveManifest.ROWS.get(set);
        if (row == null) {
            throw new IllegalArgumentException("archive set " + set + " is not listed in " + ArchiveManifest.FILE);
        }
        return ARCHIVES.computeIfAbsent(set, ignored -> new Archive(set, row[0], row[1], row[2], CACHE, DOWNLOAD));
    }

    /**
     * Returns a set of members of a ZIP archive that no manifest lists, cached in a given directory and always
     * downloaded when missing, for the test kit's own tests.
     *
     * @param set             the set name
     * @param directorySha256 the SHA-256 of the archive's central directory, from its start to the end of the archive
     * @param url             the archive's URL, which must answer HTTP range requests
     * @param members         the regular expression the member paths of the set contain a match of
     * @param cache           the directory that holds the {@code archives} cache
     * @return the set
     */
    public static Archive archive(String set, String directorySha256, String url, String members, Path cache) {
        return new Archive(set, directorySha256, url, members, cache, true);
    }

    /** The parsed central directories of the archives opened by this JVM, by their cache file. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, java.util.Map<String, Archive.Entry>> DIRECTORIES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The sets opened by this JVM, so that each central directory is read once. */
    private static final java.util.concurrent.ConcurrentHashMap<String, Archive> ARCHIVES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The rows of {@code tools/testdata/archives.tsv}, loaded on first use: set name to cdsha256, url and members.
     */
    private static final class ArchiveManifest {
        static final Path FILE = Manifest.FILE.resolveSibling("archives.tsv");
        static final java.util.Map<String, String[]> ROWS = load();

        private static java.util.Map<String, String[]> load() {
            var rows = new java.util.HashMap<String, String[]>();
            if (!Files.isRegularFile(FILE)) {
                return rows;
            }
            try {
                for (var line : Files.readAllLines(FILE)) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    var columns = line.split("\t");
                    if (columns.length >= 7) {
                        rows.put(columns[1], new String[]{columns[3], columns[4], columns[5]});
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return rows;
        }
    }

    /**
     * A set of members of a ZIP archive, read by HTTP range requests (the end of central directory record, its
     * ZIP64 counterpart, the central directory headers and the local file headers of the ZIP application note).
     */
    public static final class Archive {
        private final String name;
        private final String directorySha256;
        private final String url;
        private final java.util.regex.Pattern pattern;
        private final Path root;
        private final Path directoryFile;
        private final boolean download;
        private volatile java.util.Map<String, Entry> entries;
        private volatile List<String> members;

        record Entry(String name, int method, int flags, long crc, long compressedSize, long size,
                             long offset, long end) {
        }

        private Archive(String name, String directorySha256, String url, String members, Path cache,
                        boolean download) {
            this.name = name;
            this.download = download;
            this.directorySha256 = directorySha256;
            this.url = url;
            this.pattern = java.util.regex.Pattern.compile(members);
            var base = URI.create(url).getRawPath();
            base = base.substring(base.lastIndexOf('/') + 1).replaceFirst("(?i)\\.zip$", "");
            var id = base + "-" + directorySha256.substring(0, 16);
            this.root = cache.resolve("archives").resolve(id);
            this.directoryFile = cache.resolve("archives").resolve(id + ".cd");
        }

        /**
         * {@return the set name}
         */
        public String name() {
            return name;
        }

        /**
         * Returns the paths of the set's members, in the order of the central directory.
         *
         * @return the member paths
         */
        public List<String> members() {
            var result = members;
            if (result == null) {
                result = directory().keySet().stream().filter(path -> pattern.matcher(path).find()).toList();
                members = result;
            }
            return result;
        }

        /**
         * Returns the cached copy of a member, downloading it first when needed.
         *
         * @param member the member path, one of {@link #members()}
         * @return the verified file
         */
        public Path fetch(String member) {
            var entry = directory().get(member);
            if (entry == null || !pattern.matcher(member).find()) {
                throw new IllegalArgumentException(member + " is not a member of the archive set " + name);
            }
            var target = root.resolve(member);
            try {
                if (Files.isRegularFile(target) && Files.size(target) == entry.size() && crc32(target) == entry.crc()) {
                    return target;
                }
                Skip.unless(download, "data:vector", "member " + member + " of the archive set " + name
                        + " is not cached in " + root + " and downloads are off (smalt.conformance.download=false)");
                var data = extract(entry, range(entry.offset(), entry.end()));
                Files.createDirectories(target.getParent());
                var temporary = Files.createTempFile(target.getParent(), "download-", ".part");
                try {
                    Files.write(temporary, data);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return target;
            } catch (IOException exception) {
                throw Skip.now("data:vector", "member " + member + " of the archive set " + name + " unavailable: "
                        + exception);
            }
        }

        private java.util.Map<String, Entry> directory() {
            var result = entries;
            if (result != null) {
                return result;
            }
            synchronized (this) {
                if (entries == null) {
                    // the sets of one archive share its parsed directory, which for a large suite is big
                    entries = DIRECTORIES.computeIfAbsent(directoryFile, ignored -> parse(loadDirectory()));
                }
                return entries;
            }
        }

        private byte[] loadDirectory() {
            try {
                if (Files.isRegularFile(directoryFile)) {
                    var data = Files.readAllBytes(directoryFile);
                    if (sha256(data).equals(directorySha256)) {
                        return data;
                    }
                }
                Skip.unless(download, "data:vector", "the central directory of the archive set " + name
                        + " is not cached in " + directoryFile.getParent() + " and downloads are off");
                var data = readDirectory();
                var actual = sha256(data);
                if (!actual.equals(directorySha256)) {
                    throw new IllegalStateException("central directory SHA-256 mismatch for " + url + ": expected "
                            + directorySha256 + ", got " + actual);
                }
                Files.createDirectories(directoryFile.getParent());
                var temporary = Files.createTempFile(directoryFile.getParent(), "download-", ".part");
                try {
                    Files.write(temporary, data);
                    Files.move(temporary, directoryFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return data;
            } catch (IOException exception) {
                throw Skip.now("data:vector", "the central directory of the archive set " + name + " unavailable: "
                        + exception);
            }
        }

        /** Downloads the bytes from the start of the central directory to the end of the archive. */
        private byte[] readDirectory() throws IOException {
            var size = contentLength();
            var tailStart = Math.max(0, size - (65535 + 22 + 20 + 56));
            var tail = range(tailStart, size);
            var eocd = -1;
            for (var i = tail.length - 22; i >= 0; i--) {
                if (int32(tail, i) == 0x06054b50) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                throw new IOException(url + " is not a ZIP archive");
            }
            var entries = int16(tail, eocd + 10);
            var directorySize = uint32(tail, eocd + 12);
            long directoryOffset = uint32(tail, eocd + 16);
            if (directoryOffset == 0xFFFFFFFFL || entries == 0xFFFF || directorySize == 0xFFFFFFFFL) {
                var locator = eocd - 20;
                if (locator < 0 || int32(tail, locator) != 0x07064b50) {
                    throw new IOException(url + ": missing ZIP64 end of central directory locator");
                }
                var record = int64(tail, locator + 8);
                var header = record >= tailStart
                        ? java.util.Arrays.copyOfRange(tail, (int) (record - tailStart), (int) (record - tailStart) + 56)
                        : range(record, record + 56);
                if (int32(header, 0) != 0x06064b50) {
                    throw new IOException(url + ": corrupt ZIP64 end of central directory record");
                }
                directoryOffset = int64(header, 48);
            }
            if (directoryOffset >= tailStart) {
                return java.util.Arrays.copyOfRange(tail, (int) (directoryOffset - tailStart), tail.length);
            }
            var head = range(directoryOffset, tailStart);
            var result = new byte[head.length + tail.length];
            System.arraycopy(head, 0, result, 0, head.length);
            System.arraycopy(tail, 0, result, head.length, tail.length);
            return result;
        }

        private static java.util.Map<String, Entry> parse(byte[] directory) {
            var list = new ArrayList<Entry>();
            var position = 0;
            while (position + 46 <= directory.length && int32(directory, position) == 0x02014b50) {
                var flags = int16(directory, position + 8);
                var method = int16(directory, position + 10);
                var crc = uint32(directory, position + 16);
                var compressedSize = uint32(directory, position + 20);
                var size = uint32(directory, position + 24);
                var nameLength = int16(directory, position + 28);
                var extraLength = int16(directory, position + 30);
                var commentLength = int16(directory, position + 32);
                var offset = uint32(directory, position + 42);
                var name = new String(directory, position + 46, nameLength, (flags & 0x800) != 0
                        ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.StandardCharsets.ISO_8859_1);
                var extra = position + 46 + nameLength;
                for (var cursor = extra; cursor + 4 <= extra + extraLength; ) {
                    var tag = int16(directory, cursor);
                    var length = int16(directory, cursor + 2);
                    if (tag == 1) {
                        var index = cursor + 4;
                        if (size == 0xFFFFFFFFL) {
                            size = int64(directory, index);
                            index += 8;
                        }
                        if (compressedSize == 0xFFFFFFFFL) {
                            compressedSize = int64(directory, index);
                            index += 8;
                        }
                        if (offset == 0xFFFFFFFFL) {
                            offset = int64(directory, index);
                        }
                    }
                    cursor += 4 + length;
                }
                list.add(new Entry(name, method, flags, crc, compressedSize, size, offset, -1));
                position += 46 + nameLength + extraLength + commentLength;
            }
            var directoryOffset = directoryOffset(directory, position);
            var byOffset = new ArrayList<>(list);
            byOffset.sort(java.util.Comparator.comparingLong(Entry::offset));
            var ends = new java.util.HashMap<String, Long>();
            for (var i = 0; i < byOffset.size(); i++) {
                ends.put(byOffset.get(i).name(), i + 1 < byOffset.size() ? byOffset.get(i + 1).offset() : directoryOffset);
            }
            var result = new java.util.LinkedHashMap<String, Entry>();
            for (var entry : list) {
                if (!entry.name().endsWith("/")) {
                    result.put(entry.name(), new Entry(entry.name(), entry.method(), entry.flags(), entry.crc(),
                            entry.compressedSize(), entry.size(), entry.offset(), ends.get(entry.name())));
                }
            }
            return result;
        }

        /** Reads the offset of the directory from the end record that follows its headers. */
        private static long directoryOffset(byte[] directory, int position) {
            if (position + 56 <= directory.length && int32(directory, position) == 0x06064b50) {
                return int64(directory, position + 48);
            }
            if (position + 22 <= directory.length && int32(directory, position) == 0x06054b50) {
                return uint32(directory, position + 16);
            }
            throw new IllegalStateException("no end of central directory record after the directory");
        }

        private static byte[] extract(Entry entry, byte[] data) throws IOException {
            if (int32(data, 0) != 0x04034b50) {
                throw new IOException(entry.name() + ": no local file header");
            }
            if ((entry.flags() & 1) != 0) {
                throw new IOException(entry.name() + ": encrypted member");
            }
            var start = 30 + int16(data, 26) + int16(data, 28);
            byte[] content;
            if (entry.method() == 0) {
                content = java.util.Arrays.copyOfRange(data, start, start + Math.toIntExact(entry.compressedSize()));
            } else if (entry.method() == 8) {
                var inflater = new java.util.zip.Inflater(true);
                try {
                    inflater.setInput(data, start, Math.toIntExact(entry.compressedSize()));
                    content = new byte[Math.toIntExact(entry.size())];
                    var written = 0;
                    while (written < content.length && !inflater.finished()) {
                        var count = inflater.inflate(content, written, content.length - written);
                        if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                            break;
                        }
                        written += count;
                    }
                    if (written != content.length) {
                        throw new IOException(entry.name() + ": truncated deflate stream");
                    }
                } catch (java.util.zip.DataFormatException exception) {
                    throw new IOException(entry.name() + ": corrupt deflate stream", exception);
                } finally {
                    inflater.end();
                }
            } else {
                throw new IOException(entry.name() + ": compression method " + entry.method());
            }
            var crc = new java.util.zip.CRC32();
            crc.update(content);
            if (content.length != entry.size() || crc.getValue() != entry.crc()) {
                throw new IOException(entry.name() + ": size or CRC-32 differs from the central directory");
            }
            return content;
        }

        private long contentLength() throws IOException {
            var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(120_000);
                Skip.unless(connection.getResponseCode() / 100 == 2, "data:vector",
                        "HTTP " + connection.getResponseCode() + " from " + url);
                var length = connection.getContentLengthLong();
                if (length < 0) {
                    throw new IOException("no length for " + url);
                }
                return length;
            } finally {
                connection.disconnect();
            }
        }

        /** Returns bytes [start, end) of the archive, retrying a failed request with the pauses of a download. */
        private byte[] range(long start, long end) throws IOException {
            for (var attempt = 0; ; attempt++) {
                try {
                    return rangeOnce(start, end);
                } catch (Transient exception) {
                    if (attempt >= RETRY_PAUSES_MILLIS.length) {
                        throw Skip.now("data:vector", exception.getMessage());
                    }
                } catch (IOException exception) {
                    if (attempt >= RETRY_PAUSES_MILLIS.length) {
                        throw exception;
                    }
                }
                pause(RETRY_PAUSES_MILLIS[attempt]);
            }
        }

        private byte[] rangeOnce(long start, long end) throws IOException, Transient {
            var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            try {
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(300_000);
                connection.setRequestProperty("Range", "bytes=" + start + "-" + (end - 1));
                var status = connection.getResponseCode();
                if (status == 429 || status / 100 == 5) {
                    throw new Transient("HTTP " + status + " for a range of " + url);
                }
                Skip.unless(status == 206, "data:vector", "HTTP " + status + " for a range of " + url);
                try (var input = connection.getInputStream()) {
                    var data = input.readAllBytes();
                    if (data.length != end - start) {
                        throw new IOException("short range read of " + url + ": " + data.length + " of "
                                + (end - start) + " bytes");
                    }
                    return data;
                }
            } finally {
                connection.disconnect();
            }
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

        private static String sha256(byte[] data) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private static int int16(byte[] data, int offset) {
            return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
        }

        private static int int32(byte[] data, int offset) {
            return int16(data, offset) | int16(data, offset + 2) << 16;
        }

        private static long uint32(byte[] data, int offset) {
            return int32(data, offset) & 0xFFFFFFFFL;
        }

        private static long int64(byte[] data, int offset) {
            return uint32(data, offset) | uint32(data, offset + 4) << 32;
        }
    }
}
