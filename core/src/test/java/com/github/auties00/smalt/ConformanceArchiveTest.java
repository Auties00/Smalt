package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's archive sets: members of ZIP archives read by HTTP range requests from a loopback server,
 * which serves archives written by {@code java.util.zip} (stored and deflated members, directory entries, and a
 * ZIP64 archive of more than 65535 entries), checked against the pinned central directory.
 */
@DisplayName("archive sets")
class ConformanceArchiveTest {
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static volatile boolean ignoreRanges;
    private static ServerSocket server;
    private static Thread acceptor;

    @TempDir
    Path cache;

    @BeforeAll
    static void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    var socket = server.accept();
                    Thread.ofVirtual().start(() -> serve(socket));
                } catch (IOException closed) {
                    return;
                }
            }
        });
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        acceptor.join();
    }

    private static void serve(Socket socket) {
        try (socket; var input = socket.getInputStream(); var output = socket.getOutputStream()) {
            var lines = headers(input);
            if (lines.isEmpty()) {
                return;
            }
            REQUESTS.incrementAndGet();
            var request = lines.getFirst().split(" ");
            var data = FILES.get(request[1]);
            if (data == null) {
                output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            var range = lines.stream().filter(line -> line.regionMatches(true, 0, "Range:", 0, 6)).findFirst();
            long start = 0;
            long end = data.length;
            var status = "200 OK";
            if (range.isPresent() && !ignoreRanges) {
                var bounds = range.get().substring(range.get().indexOf('=') + 1).trim().split("-");
                start = Long.parseLong(bounds[0]);
                end = Math.min(data.length, Long.parseLong(bounds[1]) + 1);
                status = "206 Partial Content";
            }
            var head = "HTTP/1.1 " + status + "\r\nContent-Type: application/zip\r\nContent-Length: " + (end - start)
                    + "\r\nConnection: close\r\n\r\n";
            output.write(head.getBytes(StandardCharsets.US_ASCII));
            if (!request[0].equals("HEAD")) {
                output.write(data, (int) start, (int) (end - start));
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    private static List<String> headers(InputStream input) throws IOException {
        var lines = new ArrayList<String>();
        var line = new ByteArrayOutputStream();
        for (var b = input.read(); b >= 0; b = input.read()) {
            if (b == '\n') {
                var text = line.toString(StandardCharsets.US_ASCII).strip();
                if (text.isEmpty()) {
                    return lines;
                }
                lines.add(text);
                line.reset();
            } else {
                line.write(b);
            }
        }
        return lines;
    }

    private static String url(String path) {
        return "http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + path;
    }

    /** Writes a ZIP archive of the given members, stored or deflated by the order of the map's entries. */
    private static byte[] zip(Map<String, byte[]> members, boolean store) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("suite/"));
            zip.closeEntry();
            var index = 0;
            for (var member : members.entrySet()) {
                var entry = new ZipEntry(member.getKey());
                if (store && index++ % 2 == 0) {
                    var crc = new CRC32();
                    crc.update(member.getValue());
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(member.getValue().length);
                    entry.setCompressedSize(member.getValue().length);
                    entry.setCrc(crc.getValue());
                }
                zip.putNextEntry(entry);
                zip.write(member.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /** Returns the SHA-256 of the bytes from the start of the central directory to the end of the archive. */
    private static String directorySha256(byte[] zip) throws Exception {
        var eocd = zip.length - 22;
        while (int32(zip, eocd) != 0x06054b50) {
            eocd--;
        }
        long offset = int32(zip, eocd + 16) & 0xFFFFFFFFL;
        if (offset == 0xFFFFFFFFL || (int32(zip, eocd + 8) & 0xFFFF) == 0xFFFF) {
            var record = int64(zip, eocd - 20 + 8);
            offset = int64(zip, (int) record + 48);
        }
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(zip, (int) offset, zip.length - (int) offset);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int int32(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
    }

    private static long int64(byte[] data, int offset) {
        return (int32(data, offset) & 0xFFFFFFFFL) | (long) int32(data, offset + 4) << 32;
    }

    private static Map<String, byte[]> members(int count, long seed) {
        var random = new Random(seed);
        var members = new java.util.LinkedHashMap<String, byte[]>();
        for (var i = 0; i < count; i++) {
            var data = new byte[random.nextInt(4096)];
            // half random, half repetitive, so that deflate both shrinks and expands members
            for (var j = 0; j < data.length; j++) {
                data[j] = (byte) (j < data.length / 2 ? random.nextInt(256) : j % 7);
            }
            members.put("suite/" + (i % 3 == 0 ? "streams/" : "md5/") + "test" + i + (i % 3 == 0 ? ".obu" : ".md5"), data);
        }
        return members;
    }

    @Test
    @DisplayName("lists and fetches the members of a set, stored and deflated, then reads them from the cache")
    void fetches() throws Exception {
        var members = members(40, 1);
        var zip = zip(members, true);
        FILES.put("/small.zip", zip);
        var archive = Conformance.archive("small", directorySha256(zip), url("/small.zip"), "/streams/[^/]+\\.obu$",
                cache);
        var expected = members.keySet().stream().filter(name -> name.endsWith(".obu")).toList();
        assertEquals(expected, archive.members());
        for (var name : expected) {
            assertArrayEquals(members.get(name), Files.readAllBytes(archive.fetch(name)), name);
        }
        var before = REQUESTS.get();
        for (var name : expected) {
            assertArrayEquals(members.get(name), Files.readAllBytes(archive.fetch(name)), name);
        }
        assertEquals(before, REQUESTS.get(), "cached members are not downloaded again");
        // a damaged cached copy is downloaded again
        var damaged = archive.fetch(expected.getFirst());
        Files.write(damaged, new byte[members.get(expected.getFirst()).length]);
        assertArrayEquals(members.get(expected.getFirst()), Files.readAllBytes(archive.fetch(expected.getFirst())));
        assertTrue(REQUESTS.get() > before);
        // a second instance reads the cached central directory
        var again = Conformance.archive("small", directorySha256(zip), url("/small.zip"), "/streams/", cache);
        before = REQUESTS.get();
        assertEquals(expected, again.members());
        assertEquals(before, REQUESTS.get());
    }

    @Test
    @DisplayName("reads the ZIP64 records of an archive of more than 65535 entries")
    void zip64() throws Exception {
        var members = new java.util.LinkedHashMap<String, byte[]>();
        for (var i = 0; i < 70_000; i++) {
            members.put("suite/m/" + i, new byte[]{(byte) i, (byte) (i >> 8), (byte) (i >> 16)});
        }
        var zip = zip(members, false);
        FILES.put("/zip64.zip", zip);
        var archive = Conformance.archive("zip64", directorySha256(zip), url("/zip64.zip"), "/m/6999[0-9]$", cache);
        assertEquals(10, archive.members().size());
        assertArrayEquals(members.get("suite/m/69999"), Files.readAllBytes(archive.fetch("suite/m/69999")));
    }

    @Test
    @DisplayName("fails on a central directory that differs from the pin, and on a member outside the set")
    void pins() throws Exception {
        var zip = zip(members(5, 2), true);
        FILES.put("/pinned.zip", zip);
        var wrong = Conformance.archive("wrong", "0".repeat(64), url("/pinned.zip"), "/streams/", cache);
        assertTrue(assertThrows(IllegalStateException.class, wrong::members).getMessage().contains("mismatch"));
        var archive = Conformance.archive("pinned", directorySha256(zip), url("/pinned.zip"), "/streams/", cache);
        assertThrows(IllegalArgumentException.class, () -> archive.fetch("suite/md5/test1.md5"));
        assertThrows(IllegalArgumentException.class, () -> archive.fetch("suite/streams/absent.obu"));
    }

    @Test
    @DisplayName("skips with data:vector when the server ignores range requests or the archive is gone")
    void unavailable() throws Exception {
        var zip = zip(members(5, 3), true);
        FILES.put("/ranges.zip", zip);
        ignoreRanges = true;
        try {
            var archive = Conformance.archive("ranges", directorySha256(zip), url("/ranges.zip"), "/streams/", cache);
            var skip = assertThrows(TestAbortedException.class, archive::members);
            assertEquals("data:vector", Skip.reasonOf(skip.getMessage()));
        } finally {
            ignoreRanges = false;
        }
        var gone = Conformance.archive("gone", directorySha256(zip), url("/gone.zip"), "/streams/", cache);
        assertEquals("data:vector", Skip.reasonOf(assertThrows(TestAbortedException.class, gone::members).getMessage()));
    }
}
