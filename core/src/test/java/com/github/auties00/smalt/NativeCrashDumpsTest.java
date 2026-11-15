package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.NativeCrashDumps;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's native crash dumps: a child JVM installs the filter and starts a native thread that faults,
 * and the test reads the minidump and the report it leaves.
 */
@DisplayName("native crash dumps")
class NativeCrashDumpsTest {
    /**
     * The report line of the child's crash: an access violation executing at the thread's start address.
     */
    private static final Pattern REPORT = Pattern.compile("native crash: exception 0xc0000005 at 0x0000000000000010 "
            + "on thread (\\d+) of process (\\d+), executing 0x0000000000000010, in no module; dump .*native-crash-\\2-\\1\\.dmp");

    @Test
    @DisplayName("writes a minidump and a report when a thread the JVM did not create faults")
    void nativeThread(@TempDir Path directory) throws Exception {
        Skip.unless(NativeCrashDumps.supported(), "platform:windows", "the filter is the Windows x64 unhandled exception filter");
        var process = child(directory.toString());
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output);
        var exit = "exit 0x" + Integer.toHexString(process.exitValue()) + ", output: " + output;
        // 3 is the child's exit when no crash ended it within 30 s
        assertNotEquals(3, process.exitValue(), exit);
        assertNotEquals(0, process.exitValue(), exit);
        var matcher = REPORT.matcher(output);
        assertTrue(matcher.find(), exit);
        var tid = Long.parseLong(matcher.group(1));
        var pid = Long.parseLong(matcher.group(2));
        assertEquals(process.pid(), pid);
        var files = NativeCrashDumps.files(directory, pid, tid);
        assertEquals(matcher.group(), Files.readString(files[1]).strip());
        var dump = ByteBuffer.wrap(Files.readAllBytes(files[0])).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x504D444D, dump.getInt(0), "MDMP signature");
        var streams = dump.getInt(8);
        var directoryRva = dump.getInt(12);
        var exception = -1;
        var threads = -1;
        var modules = -1;
        for (var i = 0; i < streams; i++) {
            var entry = directoryRva + 12 * i;
            switch (dump.getInt(entry)) {
                case 3 -> threads = dump.getInt(entry + 8);
                case 4 -> modules = dump.getInt(entry + 8);
                case 6 -> exception = dump.getInt(entry + 8);
                default -> {
                }
            }
        }
        assertTrue(exception > 0 && threads > 0 && modules > 0, "exception, thread and module streams");
        assertEquals(tid, Integer.toUnsignedLong(dump.getInt(exception)), "faulting thread");
        assertEquals(0xC0000005, dump.getInt(exception + 8), "exception code");
        assertEquals(0x10L, dump.getLong(exception + 24), "exception address");
        var listed = false;
        var count = dump.getInt(threads);
        for (var i = 0; i < count; i++) {
            // MINIDUMP_THREAD is 48 bytes, the thread id first
            listed |= Integer.toUnsignedLong(dump.getInt(threads + 4 + 48 * i)) == tid;
        }
        assertTrue(listed, "the faulting thread is in the thread list");
        assertTrue(dump.getInt(modules) > 10, "the module list holds the JVM's modules");
        var ring = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("exceptions-" + pid + ".bin")))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(NativeCrashDumps.RING_HEADER + NativeCrashDumps.RING_ENTRIES * NativeCrashDumps.RING_ENTRY,
                ring.capacity());
        var recorded = ring.getInt(0);
        assertTrue(recorded > 0, "the vectored handler recorded the crash");
        var last = -1;
        for (var i = Math.max(0, recorded - NativeCrashDumps.RING_ENTRIES); i < recorded; i++) {
            var entry = NativeCrashDumps.RING_HEADER + (i % NativeCrashDumps.RING_ENTRIES) * NativeCrashDumps.RING_ENTRY;
            if (Integer.toUnsignedLong(ring.getInt(entry + 4)) == tid) {
                last = entry;
            }
        }
        assertTrue(last >= 0, "the faulting thread's exception is in the ring");
        assertEquals(0xC0000005, ring.getInt(last), "recorded code");
        assertEquals(0x10L, ring.getLong(last + 8), "recorded address");
        assertEquals(0x10L, ring.getLong(last + 24), "recorded fault address");
        assertTrue(ring.getLong(last + 16) != 0, "recorded stack pointer");
        var moduleList = Files.readString(directory.resolve("modules-" + pid + ".txt")).toLowerCase(java.util.Locale.ROOT);
        assertTrue(moduleList.contains("kernel32.dll") && moduleList.contains("jvm.dll"), moduleList);
        assertFalse(Files.exists(directory.resolve("exit-" + pid + ".txt")), "a JVM that died writes no exit marker");
    }

    @Test
    @DisplayName("assembles the vectored handler that records an exception into the ring")
    void recorderCode() {
        var ring = 0x0000020011223340L;
        var expected = "4C8B11" + "4C8B5908" + "48B8" + le(ring) + "BA01000000" + "F00FC110" + "81E2FF030000"
                + "C1E205" + "488D441040" + "458B02" + "448900" + "65448B042548000000" + "44894004"
                + "4D8B4210" + "4C894008" + "4D8B8398000000" + "4C894010" + "4D8B4228" + "4C894018" + "31C0" + "C3";
        assertEquals(expected, java.util.HexFormat.of().withUpperCase().formatHex(NativeCrashDumps.recorderCode(ring)));
    }

    @Test
    @DisplayName("Windows hands a crash on a thread the JVM did not create to the process filter")
    void processFilter() throws Exception {
        Skip.unless(NativeCrashDumps.supported(), "platform:windows", "the filter is the Windows x64 unhandled exception filter");
        var process = child("--exit-filter");
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), output);
        var exit = process.exitValue();
        assertNotEquals(3, exit, output);
        assertNotEquals(0xC0000005, exit, "the process filter was not called: " + output);
    }

    @Test
    @DisplayName("assembles the filter that hands the crash to the watcher and chains to the replaced filter")
    void filterCode() {
        long page = 0x1122334455660000L, threadId = 0x7FF0000000000010L, setEvent = 0x7FF0000000000020L;
        long wait = 0x7FF0000000000030L, crashed = 0x0000000000000104L, released = 0x0000000000000108L;
        long replaced = 0x7FF0000000000040L;
        var expected = "4883EC28" + "48B8" + le(page + NativeCrashDumps.POINTERS_SLOT) + "488908"
                + "48B8" + le(threadId) + "FFD0" + "48BA" + le(page + NativeCrashDumps.THREAD_SLOT) + "8902"
                + "48B9" + le(crashed) + "48B8" + le(setEvent) + "FFD0"
                + "48B9" + le(released) + "BA60EA0000" + "48B8" + le(wait) + "FFD0"
                + "48B8" + le(page + NativeCrashDumps.POINTERS_SLOT) + "488B08" + "48B8" + le(replaced)
                + "4885C0" + "7404" + "FFD0" + "EB02" + "31C0" + "4883C428" + "C3";
        assertEquals(expected, java.util.HexFormat.of().withUpperCase()
                .formatHex(NativeCrashDumps.filterCode(page, threadId, setEvent, wait, crashed, released, replaced)));
    }

    /**
     * {@return a 64-bit value as little-endian hexadecimal digits}
     */
    private static String le(long value) {
        return java.util.HexFormat.of().withUpperCase().formatHex(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(value).array());
    }

    @Test
    @DisplayName("names its files after the process and the thread")
    void files() {
        var files = NativeCrashDumps.files(Path.of("dumps"), 12, 34);
        assertEquals(Path.of("dumps", "native-crash-12-34.dmp"), files[0]);
        assertEquals(Path.of("dumps", "native-crash-12-34.txt"), files[1]);
    }

    @Test
    @DisplayName("refuses to install outside Windows on x64")
    void otherSystems(@TempDir Path directory) {
        Skip.when(NativeCrashDumps.supported(), "platform:not-windows", "installing works on Windows x64");
        assertThrows(UnsupportedOperationException.class, () -> NativeCrashDumps.install(directory));
        assertFalse(NativeCrashDumps.installed());
    }

    /**
     * {@return a child JVM running the crash of {@link NativeCrashDumps#main(String...)}, its output merged}
     */
    private static Process child(String argument) throws Exception {
        var java = ProcessHandle.current().info().command().orElseThrow();
        return new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-Xmx64m", "-cp", classRoot().toString(),
                NativeCrashDumps.class.getName(), argument).redirectErrorStream(true).start();
    }

    /**
     * {@return the class directory or jar holding the test kit}
     */
    private static Path classRoot() throws URISyntaxException {
        var resource = NativeCrashDumps.class.getResource("NativeCrashDumps.class");
        assertNotNull(resource);
        var url = resource.toString();
        if (url.startsWith("jar:")) {
            return Path.of(new java.net.URI(url.substring(4, url.indexOf("!/"))));
        }
        var file = Path.of(resource.toURI());
        var depth = NativeCrashDumps.class.getName().split("\\.").length;
        for (var i = 0; i < depth; i++) {
            file = file.getParent();
        }
        return file;
    }
}
