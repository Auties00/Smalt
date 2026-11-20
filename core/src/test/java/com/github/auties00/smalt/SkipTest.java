package com.github.auties00.smalt;

import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the test kit's skip reasons, the requirement switches that turn a missing oracle or device into a
 * failure, and the source policy that every skip of every module goes through them, which is what lets
 * {@code tools/testdata/skips.py} count skips by reason against the baseline.
 */
@DisplayName("skip reasons")
class SkipTest {
    private static final Path ROOT = root();

    private static Path root() {
        for (var directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("tools").resolve("testdata").resolve("skips.py"))) {
                return directory;
            }
        }
        throw new IllegalStateException("tools/testdata/skips.py not found above " + Path.of("").toAbsolutePath());
    }

    @Nested
    @DisplayName("messages")
    class Messages {
        @Test
        @DisplayName("prefix the detail with the reason id, which reads back from the message")
        void format() {
            var message = Skip.message("oracle:ffmpeg", "ffmpeg is not on PATH");
            assertEquals("[oracle:ffmpeg] ffmpeg is not on PATH", message);
            assertEquals("oracle:ffmpeg", Skip.reasonOf(message));
            assertEquals(Skip.Family.ORACLE, Skip.family("oracle:ffmpeg"));
            assertEquals(Skip.Family.OPT_IN, Skip.family("opt-in:capture-live"));
            assertNull(Skip.reasonOf("ffmpeg is not on PATH"));
            assertNull(Skip.reasonOf(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"ffmpeg", "Oracle:ffmpeg", "oracle:", "oracle:-x", "tools:ffmpeg", "oracle:ff mpeg"})
        @DisplayName("reject a malformed id or an unknown family")
        void malformed(String reason) {
            assertThrows(IllegalArgumentException.class, () -> Skip.message(reason, "detail"));
            assertThrows(IllegalArgumentException.class, () -> Skip.unless(false, reason, "detail"));
        }

        @Test
        @DisplayName("skip only when the condition fails, with the reason in the message")
        void skips() {
            assertDoesNotThrow(() -> Skip.unless(true, "data:vector", "present"));
            assertDoesNotThrow(() -> Skip.when(false, "input:layout", "applies"));
            var skipped = assertThrows(TestAbortedException.class, () -> Skip.unless(false, "data:vector", "absent"));
            assertEquals("[data:vector] absent", skipped.getMessage());
            var when = assertThrows(TestAbortedException.class, () -> Skip.when(true, "input:layout", "n/a"));
            assertEquals("input:layout", Skip.reasonOf(when.getMessage()));
            var now = assertThrows(TestAbortedException.class, () -> {
                throw Skip.now("env:threads", "one thread");
            });
            assertEquals("[env:threads] one thread", now.getMessage());
            var vector = assertThrows(TestAbortedException.class, () -> Skip.unlessVector(false));
            assertEquals("simd:disabled", Skip.reasonOf(vector.getMessage()));
        }

        @Test
        @DisplayName("skip a missing device, which carries the device family")
        void device() {
            assertDoesNotThrow(() -> Skip.unlessDevice(true, "camera", "present"));
            // no requireDevices switch names this kind, so a missing device skips
            var skipped = assertThrows(TestAbortedException.class,
                    () -> Skip.unlessDevice(false, "smalt-test-kind", "absent"));
            assertEquals("device:smalt-test-kind", Skip.reasonOf(skipped.getMessage()));
        }
    }

    @Nested
    @DisplayName("requirement switches")
    class Switches {
        private static final String PROPERTY = "smalt.test.skipTestSwitch";

        private boolean requires(String value, String item) {
            var previous = System.getProperty(PROPERTY);
            try {
                if (value == null) {
                    System.clearProperty(PROPERTY);
                } else {
                    System.setProperty(PROPERTY, value);
                }
                return Skip.requires(PROPERTY, "SMALT_TEST_SKIP_TEST_SWITCH_UNSET", item);
            } finally {
                if (previous == null) {
                    System.clearProperty(PROPERTY);
                } else {
                    System.setProperty(PROPERTY, previous);
                }
            }
        }

        @Test
        @DisplayName("require every item for true, the listed items for a list, and nothing otherwise")
        void values() {
            assertTrue(requires("true", "ffmpeg"));
            assertTrue(requires("TRUE", "cjxl"));
            assertTrue(requires("ffmpeg, cwebp", "cwebp"));
            assertTrue(requires("ffmpeg,MP4Box", "mp4box"));
            assertFalse(requires("ffmpeg,cwebp", "dwebp"));
            assertFalse(requires("false", "ffmpeg"));
            assertFalse(requires("", "ffmpeg"));
            assertFalse(requires(null, "ffmpeg"));
        }
    }

    @Nested
    @DisplayName("oracles")
    class Oracles {
        private static final List<String> MISSING = List.of("Unknown encoder 'libopencore_amrnb'",
                "[aost#0:0] Encoder libvo_amrwbenc not found", "No such filter: 'zscale'", "Unknown decoder 'libdav1d'",
                "Requested output format 'ivf' is not known.");

        @Test
        @DisplayName("an ffmpeg build without a component skips with the feature reason")
        void missingFeature() {
            var ffmpeg = Path.of("bin", "ffmpeg.exe");
            withOracles("false", () -> {
                for (var stderr : MISSING) {
                    var skipped = assertThrows(TestAbortedException.class, () -> Oracle.skipOnMissingFeature(ffmpeg, stderr));
                    assertEquals("oracle:ffmpeg-feature", Skip.reasonOf(skipped.getMessage()), stderr);
                }
                assertDoesNotThrow(() -> Oracle.skipOnMissingFeature(ffmpeg, "Invalid data found when processing input"));
                assertDoesNotThrow(() -> Oracle.skipOnMissingFeature(Path.of("cwebp"), "Unknown encoder 'x'"));
            });
        }

        @Test
        @DisplayName("an ffmpeg build without a component fails instead when requireOracles names ffmpeg")
        void requiredFeature() {
            var ffmpeg = Path.of("bin", "ffmpeg");
            for (var value : List.of("true", "ffmpeg", "cwebp,ffmpeg")) {
                withOracles(value, () -> {
                    for (var stderr : MISSING) {
                        var failure = assertThrows(AssertionFailedError.class, () -> Oracle.skipOnMissingFeature(ffmpeg, stderr));
                        assertEquals("oracle:ffmpeg-feature", Skip.reasonOf(failure.getMessage()), stderr);
                    }
                    var failure = assertThrows(AssertionFailedError.class, () -> Oracle.missingFeature("no libspeex"));
                    assertTrue(failure.getMessage().contains("features.tsv"), failure.getMessage());
                });
            }
            withOracles("cwebp", () -> assertThrows(TestAbortedException.class, () -> Oracle.missingFeature("no libspeex")));
        }

        @Test
        @DisplayName("the ffmpeg on the PATH has every component of features.tsv where requireOracles names ffmpeg")
        void ffmpegComponents() {
            if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
                return;
            }
            var listings = new java.util.HashMap<String, java.util.Set<String>>();
            var missing = new ArrayList<String>();
            for (var feature : features()) {
                var kind = feature[0];
                var name = feature[1];
                if (kind.equals("pixfmt")) {
                    var encoder = name.substring(0, name.indexOf(':'));
                    var formats = listings.computeIfAbsent("pixfmt " + encoder, key -> pixelFormats(encoder));
                    if (!formats.contains(name.substring(name.indexOf(':') + 1))) {
                        missing.add(name);
                    }
                } else if (!listings.computeIfAbsent(kind, SkipTest::components).contains(name)) {
                    missing.add(kind + " " + name);
                }
            }
            // ffmpeg's own components prove the listings are read, whatever the build
            assertTrue(listings.computeIfAbsent("encoder", SkipTest::components).contains("aac"), listings.toString());
            assertTrue(listings.computeIfAbsent("filter", SkipTest::components).contains("scale"), listings.toString());
            if (Oracle.required(Oracle.Tool.FFMPEG)) {
                assertEquals(List.of(), missing, "the ffmpeg of CI has every component of features.tsv");
            }
        }

        @Test
        @DisplayName("an exit by a crash signal or an access violation is a crash, an error exit is not")
        void crashCodes() {
            for (var code : List.of(132, 133, 134, 135, 139, 0xC0000005)) {
                assertTrue(Oracle.crashed(code), Integer.toString(code));
            }
            for (var code : List.of(0, 1, 2, 69, 137, 255, -1)) {
                assertFalse(Oracle.crashed(code), Integer.toString(code));
            }
        }

        @Test
        @DisplayName("an ffmpeg run that crashes is repeated up to three times, an error exit or another tool is not")
        void crashRetry(@org.junit.jupiter.api.io.TempDir Path directory) throws IOException {
            Skip.when(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
                    "platform:unix", "the fake tool is a shell script");
            // counts its runs in a file and crashes on each run before the given one
            var script = String.join("\n", "#!/bin/sh", "count=$(cat \"$1\" 2>/dev/null || echo 0)",
                    "count=$((count + 1))", "echo $count > \"$1\"", "[ $count -lt $2 ] && kill -SEGV $$",
                    "[ \"$3\" = fail ] && exit 1", "echo done", "");
            var ffmpeg = Files.writeString(directory.resolve("ffmpeg-crashing"), script);
            var other = Files.writeString(directory.resolve("tool-crashing"), script);
            assertTrue(ffmpeg.toFile().setExecutable(true) && other.toFile().setExecutable(true));
            record Case(Path tool, int crashes, String mode, int exit, int runs) {
            }
            var cases = List.of(new Case(ffmpeg, 2, "pass", 0, 3), new Case(ffmpeg, 9, "pass", 139, 3),
                    new Case(ffmpeg, 0, "fail", 1, 1), new Case(other, 2, "pass", 139, 1));
            for (var test : cases) {
                var counter = directory.resolve("runs-" + cases.indexOf(test));
                var result = Oracle.execute(test.tool(), java.time.Duration.ofMinutes(1), null,
                        List.of(counter.toString(), Integer.toString(test.crashes() + 1), test.mode()));
                assertEquals(test.exit(), result.exitCode(), test.toString());
                assertEquals(test.runs(), Integer.parseInt(Files.readString(counter).strip()), test.toString());
            }
        }

        @Test
        @DisplayName("every tool has a well-formed reason id and is found by its executable name")
        void tools() {
            for (var tool : Oracle.Tool.values()) {
                assertEquals(Skip.Family.ORACLE, Skip.family("oracle:" + tool.id()), tool.name());
                assertEquals(tool, Oracle.Tool.of(tool.executable()).orElseThrow());
            }
            assertTrue(Oracle.Tool.of("smalt-no-such-tool").isEmpty());
        }

        @Test
        @DisplayName("every tool the requirement switch names is installed")
        void provisioned() {
            var missing = new ArrayList<String>();
            for (var tool : Oracle.Tool.values()) {
                if (Oracle.required(tool)) {
                    try {
                        Oracle.find(tool);
                    } catch (AssertionFailedError error) {
                        missing.add(tool.executable());
                    }
                }
            }
            assertEquals(List.of(), missing, "tools/testdata/oracles.py install provisions every required oracle");
        }

        @Test
        @DisplayName("a missing tool fails instead of skipping when requireOracles names it")
        void required() {
            var tool = Oracle.Tool.MKVALIDATOR;
            if (Oracle.required(tool)) {
                assertTrue(Oracle.find(tool).isPresent(), "a required oracle is installed");
                return;
            }
            if (Oracle.find(tool).isPresent()) {
                assertDoesNotThrow(() -> Oracle.assume(tool));
                return;
            }
            var skipped = assertThrows(TestAbortedException.class, () -> Oracle.assume(tool));
            assertEquals("oracle:mkvalidator", Skip.reasonOf(skipped.getMessage()));
        }
    }

    @Nested
    @DisplayName("source policy")
    class Policy {
        /** JUnit's own ways to skip, which must not carry a free-text reason. */
        private static final Pattern RAW = Pattern.compile(
                "\\bAssumptions\\s*\\.\\s*(assume\\w*|abort)\\s*\\(|(?<![\\w.])(assumeTrue|assumeFalse|assumingThat)\\s*\\("
                        + "|new\\s+(?:org\\.opentest4j\\.)?TestAbortedException\\s*\\(");

        /** A conditional annotation and its arguments, which must name a disabledReason with an id. */
        private static final Pattern CONDITION = Pattern.compile(
                "@(?:Disabled|(?:Enabled|Disabled)(?:OnOs|OnJre|ForJreRange|InNativeImage|If\\w*))\\b(\\([^)]*\\))?");

        /** A literal reason id passed to the test kit. */
        private static final Pattern LITERAL = Pattern.compile(
                "Skip\\s*\\.\\s*(?:unless|when|now)\\s*\\([^;]*?\"([a-z-]+:[^\"]*)\"(?!\\s*\\+)");

        private static final Pattern DEVICE = Pattern.compile("Skip\\s*\\.\\s*unlessDevice\\s*\\([^;]*?,\\s*\"([^\"]*)\"");

        private static Stream<Path> sources() throws IOException {
            var files = new ArrayList<Path>();
            try (var modules = Files.list(ROOT)) {
                for (var module : modules.toList()) {
                    var tests = module.resolve("src").resolve("test").resolve("java");
                    if (Files.isDirectory(tests)) {
                        try (var walk = Files.walk(tests)) {
                            walk.filter(file -> file.toString().endsWith(".java")).forEach(files::add);
                        }
                    }
                }
            }
            return files.stream();
        }

        private static String read(Path file) {
            try {
                return Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static String location(Path file, String text, int offset) {
            var line = 1 + (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
            return ROOT.relativize(file).toString().replace('\\', '/') + ":" + line;
        }

        @Test
        @DisplayName("no test skips through JUnit directly")
        void noRawAssumptions() throws IOException {
            var offenders = new ArrayList<String>();
            try (var files = sources()) {
                for (var file : files.toList()) {
                    if (file.endsWith(Path.of("testkit", "Skip.java")) || file.endsWith(Path.of("smalt", "SkipTest.java"))) {
                        continue;
                    }
                    var text = read(file);
                    var matcher = RAW.matcher(text);
                    while (matcher.find()) {
                        var lineStart = text.lastIndexOf('\n', matcher.start()) + 1;
                        if (!text.substring(lineStart, matcher.start()).strip().startsWith("import")) {
                            offenders.add(location(file, text, matcher.start()) + " " + matcher.group());
                        }
                    }
                }
            }
            assertEquals(List.of(), offenders, "skip through com.github.auties00.smalt.testkit.Skip with a reason id");
        }

        @Test
        @DisplayName("every conditional annotation names a disabledReason with a reason id")
        void conditions() throws IOException {
            var offenders = new ArrayList<String>();
            try (var files = sources()) {
                for (var file : files.toList()) {
                    var text = read(file);
                    var matcher = CONDITION.matcher(text);
                    while (matcher.find()) {
                        var arguments = matcher.group(1) == null ? "" : matcher.group(1);
                        var reason = Pattern.compile("(?:disabledReason\\s*=\\s*|^\\(\\s*)\"\\[([^\\]]+)] ").matcher(arguments);
                        var valid = reason.find();
                        if (valid) {
                            try {
                                Skip.family(reason.group(1));
                            } catch (IllegalArgumentException exception) {
                                valid = false;
                            }
                        }
                        if (!valid && !text.substring(Math.max(0, matcher.start() - 40), matcher.start()).contains("{@code")) {
                            offenders.add(location(file, text, matcher.start()) + " " + matcher.group());
                        }
                    }
                }
            }
            assertEquals(List.of(), offenders, "give the condition a disabledReason starting with [family:name]");
        }

        @Test
        @DisplayName("every literal reason id names a known family, and every device kind is well formed")
        void literals() throws IOException {
            var offenders = new LinkedHashMap<String, String>();
            try (var files = sources()) {
                for (var file : files.toList()) {
                    var text = read(file);
                    var matcher = LITERAL.matcher(text);
                    while (matcher.find()) {
                        try {
                            Skip.family(matcher.group(1));
                        } catch (IllegalArgumentException exception) {
                            offenders.put(location(file, text, matcher.start()), exception.getMessage());
                        }
                    }
                    var device = DEVICE.matcher(text);
                    while (device.find()) {
                        try {
                            Skip.family("device:" + device.group(1));
                        } catch (IllegalArgumentException exception) {
                            offenders.put(location(file, text, device.start()), exception.getMessage());
                        }
                    }
                }
            }
            assertEquals(new LinkedHashMap<>(), offenders);
        }

        /** A string literal naming an ffmpeg component of an external library, such as {@code "libx264"}. */
        private static final Pattern LIBRARY_COMPONENT = Pattern.compile("\"(lib[a-z0-9]+(?:[_-][a-z0-9]+)*)\"");

        /** A component the test kit is asked for by name. */
        private static final Pattern ASSUMED_COMPONENT = Pattern.compile("assume(?:Encoder|Filter)\\(\"([^\"]+)\"\\)");

        @Test
        @DisplayName("every external ffmpeg component a test names is listed in features.tsv")
        void ffmpegFeatures() throws IOException {
            var listed = new java.util.HashSet<String>();
            for (var feature : features()) {
                listed.add(feature[0].equals("pixfmt") ? feature[1].substring(0, feature[1].indexOf(':')) : feature[1]);
            }
            var offenders = new LinkedHashMap<String, String>();
            try (var files = sources()) {
                for (var file : files.toList()) {
                    if (file.endsWith(Path.of("smalt", "SkipTest.java"))) {
                        continue;
                    }
                    var text = read(file);
                    for (var pattern : List.of(LIBRARY_COMPONENT, ASSUMED_COMPONENT)) {
                        var matcher = pattern.matcher(text);
                        while (matcher.find()) {
                            if (!listed.contains(matcher.group(1))) {
                                offenders.put(location(file, text, matcher.start()), matcher.group(1));
                            }
                        }
                    }
                    if (text.contains("\"zscale=") && !listed.contains("zscale")) {
                        offenders.put(ROOT.relativize(file).toString(), "zscale");
                    }
                }
            }
            assertEquals(new LinkedHashMap<>(), offenders,
                    "list the component in tools/testdata/ffmpeg-oracle/features.tsv, so that every CI runner has it");
        }

        @Test
        @DisplayName("skips.py knows exactly the families of Skip.Family, with the same environment flags")
        void scriptFamilies() throws IOException {
            var script = Files.readString(ROOT.resolve("tools").resolve("testdata").resolve("skips.py"));
            var block = script.substring(script.indexOf("FAMILIES = {"), script.indexOf("}", script.indexOf("FAMILIES = {")));
            var entries = Pattern.compile("'([a-z-]+)': (True|False)").matcher(block);
            var declared = new LinkedHashMap<String, Boolean>();
            while (entries.find()) {
                declared.put(entries.group(1), Boolean.parseBoolean(entries.group(2).toLowerCase()));
            }
            var expected = new LinkedHashMap<String, Boolean>();
            for (var family : Skip.Family.values()) {
                expected.put(family.id(), family.environment());
            }
            assertEquals(expected, declared);
        }
    }

    /** Returns the (kind, name) rows of {@code tools/testdata/ffmpeg-oracle/features.tsv}. */
    private static List<String[]> features() {
        try {
            var rows = new ArrayList<String[]>();
            for (var line : Files.readAllLines(ROOT.resolve("tools").resolve("testdata").resolve("ffmpeg-oracle")
                    .resolve("features.tsv"), StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var fields = line.split("\t");
                    assertTrue(List.of("encoder", "decoder", "filter", "muxer", "demuxer", "pixfmt").contains(fields[0]), line);
                    assertEquals(fields[0].equals("pixfmt"), fields[1].contains(":"), line);
                    rows.add(new String[]{fields[0], fields[1]});
                }
            }
            assertFalse(rows.isEmpty());
            return rows;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Returns the component names of an ffmpeg listing of a kind ({@code -encoders} for {@code encoder}). */
    private static java.util.Set<String> components(String kind) {
        var output = new String(Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1),
                List.of("-hide_banner", "-" + kind + "s")), StandardCharsets.UTF_8);
        var names = new java.util.HashSet<String>();
        for (var line : output.lines().toList()) {
            var fields = line.trim().split(" +");
            if (fields.length > 1 && !fields[1].equals("=") && !fields[0].startsWith("-")) {
                names.add(fields[1]);
            }
        }
        return names;
    }

    /** Returns the pixel formats an ffmpeg encoder accepts, as {@code ffmpeg -h encoder=<name>} lists them. */
    private static java.util.Set<String> pixelFormats(String encoder) {
        var output = new String(Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1),
                List.of("-hide_banner", "-h", "encoder=" + encoder)), StandardCharsets.UTF_8);
        for (var line : output.lines().toList()) {
            if (line.strip().startsWith("Supported pixel formats:")) {
                return java.util.Set.of(line.substring(line.indexOf(':') + 1).trim().split(" +"));
            }
        }
        return java.util.Set.of();
    }

    /** Runs an action with {@code smalt.test.requireOracles} set to a value, restoring it afterwards. */
    private static void withOracles(String value, Runnable action) {
        var previous = System.getProperty("smalt.test.requireOracles");
        try {
            System.setProperty("smalt.test.requireOracles", value);
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty("smalt.test.requireOracles");
            } else {
                System.setProperty("smalt.test.requireOracles", previous);
            }
        }
    }

    @Test
    @DisplayName("a required device that is missing fails the test")
    void requiredDevice() {
        var previous = System.getProperty("smalt.capture.requireDevices");
        try {
            System.setProperty("smalt.capture.requireDevices", "smalt-required-kind");
            var failure = assertThrows(AssertionFailedError.class,
                    () -> Skip.unlessDevice(false, "smalt-required-kind", "absent"));
            assertEquals("device:smalt-required-kind", Skip.reasonOf(failure.getMessage()));
            assertThrows(TestAbortedException.class, () -> Skip.unlessDevice(false, "smalt-other-kind", "absent"));
        } finally {
            if (previous == null) {
                System.clearProperty("smalt.capture.requireDevices");
            } else {
                System.setProperty("smalt.capture.requireDevices", previous);
            }
        }
    }
}
