package com.github.auties00.smalt.spi;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.internal.provider.Artifacts;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers the container service-provider interface. Core's tests run on the module path, where a test cannot add a
 * {@code provides} clause, so the checks that need registered providers run in child JVMs on the class path with a
 * {@code META-INF/services} file naming the providers of this package ({@link ContainerProviderScenarios}), the way a
 * third-party jar registers one; the nested classes check this JVM, where no container provider is registered.
 */
@DisplayName("Container provider SPI")
class ContainerProviderTest {
    /** The checks a registered-provider child reports, in order. */
    private static final List<String> REGISTERED = List.of("detect", "detect after id3", "precedence", "probe length",
            "demux", "missing operation", "malformed", "mux", "probe", "audio reader", "transcode", "tags", "subtitles",
            "named format");

    @TestFactory
    @DisplayName("a class-path provider plugs a container into Containers, the readers, probe, tags and transcoder")
    Stream<DynamicTest> registered() throws Exception {
        var scratch = Scratch.directory("container-provider").toAbsolutePath();
        var results = run(scratch.resolve("registered"), List.of(ToyContainerProvider.class.getName(),
                MisbehavingContainerProviders.Throwing.class.getName(),
                MisbehavingContainerProviders.RiffClaiming.class.getName(),
                MisbehavingContainerProviders.OverCopying.class.getName(), Y4mTagProvider.class.getName()),
                "registered", scratch.toString());
        assertEquals(REGISTERED, List.copyOf(results.keySet()), "the child ran other checks: " + results);
        return results.entrySet().stream().map(result -> DynamicTest.dynamicTest(result.getKey(), () -> {
            if (result.getValue() != null) {
                fail(result.getValue());
            }
        }));
    }

    /** {@return the class names of the providers that cannot be used, the last one of a class that does not exist} */
    static Stream<String> invalidProviders() {
        return Stream.concat(Stream.of(MisbehavingContainerProviders.StealsName.class,
                        MisbehavingContainerProviders.WrongName.class, MisbehavingContainerProviders.Uppercase.class,
                        MisbehavingContainerProviders.NullName.class, MisbehavingContainerProviders.NullFormat.class,
                        MisbehavingContainerProviders.FailingName.class,
                        MisbehavingContainerProviders.FailingConstructor.class).map(Class::getName),
                Stream.of(ContainerProviderTest.class.getPackageName() + ".AbsentContainerProvider"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidProviders")
    @DisplayName("a provider with an invalid identity, or one that cannot be loaded, fails every lookup that needs the "
            + "providers")
    void invalid(String provider) throws Exception {
        var scratch = Scratch.directory("container-provider").toAbsolutePath()
                .resolve(provider.substring(provider.lastIndexOf('.') + 1).replace('$', '-'));
        var results = run(scratch, List.of(provider), "invalid", provider);
        assertEquals(1, results.size(), results.toString());
        var failure = results.values().iterator().next();
        if (failure != null) {
            fail(failure);
        }
    }

    /**
     * Runs {@link ContainerProviderScenarios} in a child JVM on the class path with the given providers registered.
     *
     * @return the outcome of each check, {@code null} for a pass and the failure output otherwise
     */
    private static Map<String, String> run(Path directory, List<String> providers, String... args) throws Exception {
        var services = directory.resolve("services");
        Files.createDirectories(services.resolve("META-INF/services"));
        Files.writeString(services.resolve("META-INF/services/" + ContainerProvider.class.getName()),
                String.join("\n", providers) + "\n", StandardCharsets.UTF_8);
        var classPath = System.getProperty("surefire.test.class.path", Path.of("target", "test-classes").toAbsolutePath()
                + File.pathSeparator + Path.of("target", "classes").toAbsolutePath());
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
        // the scenarios exercise most of the provider code, so under -Pcoverage the children report to this JVM's file
        command.addAll(coverageAgents());
        command.addAll(List.of("--add-modules", "jdk.incubator.vector", "-Xmx256m", "-cp",
                services + File.pathSeparator + classPath, ContainerProviderScenarios.class.getName()));
        command.addAll(List.of(args));
        var output = directory.resolve("output.txt");
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            fail("the child did not finish: " + read(output));
        }
        var text = read(output);
        assertEquals(0, process.exitValue(), text);
        var results = new LinkedHashMap<String, String>();
        String failing = null;
        var trace = new StringBuilder();
        for (var line : text.split("\\R")) {
            if (line.startsWith("PASS ") || line.startsWith("FAIL ")) {
                if (failing != null) {
                    results.put(failing, trace.toString());
                }
                failing = null;
                if (line.startsWith("PASS ")) {
                    results.put(line.substring(5), null);
                } else {
                    failing = line.substring(5);
                    trace.setLength(0);
                }
            } else if (failing != null) {
                trace.append(line).append('\n');
            }
        }
        if (failing != null) {
            results.put(failing, trace.toString());
        }
        if (results.isEmpty()) {
            fail("the child reported no check: " + text);
        }
        return results;
    }

    /** {@return the JaCoCo agent options this JVM was started with, none outside the coverage profile} */
    private static List<String> coverageAgents() {
        // the test module does not read java.management, which is resolved only when the arguments add jdk.management
        List<?> arguments;
        try {
            var runtime = Class.forName("java.lang.management.ManagementFactory").getMethod("getRuntimeMXBean")
                    .invoke(null);
            arguments = (List<?>) Class.forName("java.lang.management.RuntimeMXBean").getMethod("getInputArguments")
                    .invoke(runtime);
        } catch (ReflectiveOperationException exception) {
            return List.of();
        }
        return arguments.stream().map(String::valueOf)
                .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco")).toList();
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    @Nested
    @DisplayName("without a registered provider")
    class Unregistered {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ContainerFormat.class)
        @DisplayName("names every format by a short name that maps back to it")
        void shortNames(ContainerFormat format) {
            assertTrue(format.shortName().matches("[a-z0-9]+(-[a-z0-9]+)*"), format.shortName());
            assertEquals(format, ContainerFormat.ofShortName(format.shortName()).orElseThrow());
        }

        @Test
        @DisplayName("spells short names in lowercase with hyphens")
        void spelling() {
            assertEquals("mpeg-ts", ContainerFormat.MPEG_TS.shortName());
            assertEquals("av1-annex-b", ContainerFormat.AV1_ANNEX_B.shortName());
            assertEquals("other", ContainerFormat.OTHER.shortName());
            assertTrue(ContainerFormat.ofShortName("MPEG_TS").isEmpty());
            assertTrue(ContainerFormat.ofShortName("toy").isEmpty());
        }

        @Test
        @DisplayName("recognizes a toy file as no container and names its short name in no lookup")
        void toyUnknown() {
            var toy = MediaSource.of(ContainerProviderScenarios.audioFile());
            assertTrue(Containers.detect(toy).isEmpty());
            assertTrue(Containers.detectName(toy).isEmpty());
            // the file the registered toy provider takes ahead of the SubRip heuristic
            assertEquals("subrip", Containers.detectName(MediaSource.of(ContainerProviderScenarios.ambiguousFile()))
                    .orElseThrow());
            var failure = assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(toy));
            assertEquals("unknown container", failure.format());
            var writer = assertThrows(MediaException.Unsupported.class, () -> Containers.openMuxer("toy",
                    MediaSink.memory()));
            assertEquals("toy", writer.format());
            assertEquals(MediaException.Unsupported.Kind.FORMAT, writer.kind());
            assertTrue(writer.artifact().isEmpty());
            assertFalse(Containers.isMuxable("toy", Codec.PCM_S16LE));
            assertFalse(TagEditor.isSupported("toy"));
            assertThrows(IllegalArgumentException.class, () -> Subtitles.write(List.of(SubtitleCue.of(0, 1, "a")),
                    SubtitleMarkup.SUBRIP, "toy", MediaSink.memory()));
        }

        @Test
        @DisplayName("looks core's containers up by their short names")
        void coreByName() {
            var wav = MediaSource.of(ContainerProviderScenarios.wavFile());
            assertEquals("wav", Containers.detectName(wav).orElseThrow());
            try (var demuxer = Containers.openDemuxer(wav)) {
                assertEquals("wav", demuxer.formatName());
            }
            try (var muxer = Containers.openMuxer("matroska", MediaSink.memory())) {
                assertInstanceOf(MatroskaMuxer.class, muxer);
            }
            assertTrue(Containers.isMuxable("wav", Codec.PCM_S16LE));
            assertFalse(Containers.isMuxable("wav", Codec.OPUS));
            assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer("hls", MediaSink.memory()));
            assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer("other", MediaSink.memory()));
            assertThrows(IllegalArgumentException.class, () -> Containers.openMuxer(ContainerFormat.OTHER,
                    MediaSink.memory()));
            assertFalse(Containers.isMuxable(ContainerFormat.OTHER, Codec.PCM_S16LE));
            assertTrue(TagEditor.isSupported("mp4"));
            assertTrue(TagEditor.isSupported(ContainerFormat.MP4));
            assertFalse(TagEditor.isSupported(ContainerFormat.OTHER));
            assertFalse(TagEditor.isSupported("other"));
            assertFalse(TagEditor.isSupported("subrip"));
            // without its tag provider a YUV4MPEG2 file is read but not tagged
            assertFalse(TagEditor.isSupported(ContainerFormat.Y4M));
            assertFalse(TagEditor.isSupported("y4m"));
            var y4m = MediaSource.of(ContainerProviderScenarios.y4mFile());
            var failure = assertThrows(MediaException.Unsupported.class, () -> TagEditor.read(y4m));
            assertEquals("y4m", failure.format());
            assertEquals(MediaException.Unsupported.Kind.FORMAT, failure.kind());
            assertEquals("y4m is not tag-editable by Smalt", failure.getMessage());
            try (var demuxer = Containers.openDemuxer(y4m)) {
                assertEquals("y4m", demuxer.formatName());
            }
        }

        @Test
        @DisplayName("writes a subtitle file by short name as it does by format")
        void subtitlesByName() {
            var cues = List.of(SubtitleCue.of(0, 1_000_000, "One"), SubtitleCue.of(2_000_000, 3_000_000, "Two"));
            for (var format : List.of(ContainerFormat.SUBRIP, ContainerFormat.WEBVTT, ContainerFormat.TTML)) {
                var byFormat = MediaSink.memory();
                Subtitles.write(cues, SubtitleMarkup.SUBRIP, format, byFormat);
                var byName = MediaSink.memory();
                Subtitles.write(cues, SubtitleMarkup.SUBRIP, format.shortName(), byName);
                assertArrayEquals(byFormat.toByteArray(), byName.toByteArray(), format.toString());
            }
            assertThrows(IllegalArgumentException.class, () -> Subtitles.write(cues, SubtitleMarkup.SUBRIP, "mp4",
                    MediaSink.memory()));
        }
    }

    @Nested
    @DisplayName("missing artifacts")
    class MissingArtifacts {
        @ParameterizedTest(name = "{0}")
        @EnumSource(ContainerFormat.class)
        @DisplayName("names no artifact for a format core implements")
        void coreFormats(ContainerFormat format) {
            assertNull(Artifacts.containerOf(format), format.toString());
        }

        @Test
        @DisplayName("names the artifact that implements an absent container in the failure")
        void named() {
            var failure = Artifacts.unsupportedContainer("mxf", "demuxable", "smalt-video");
            assertEquals(MediaException.Unsupported.Kind.FORMAT, failure.kind());
            assertEquals("mxf", failure.format());
            assertEquals("smalt-video", failure.artifact().orElseThrow());
            assertTrue(failure.getMessage().contains("com.github.auties00:smalt-video"), failure.getMessage());
            var unnamed = Artifacts.unsupportedContainer("toy", "writable", null);
            assertTrue(unnamed.artifact().isEmpty());
            assertEquals("toy is not writable by Smalt", unnamed.getMessage());
        }
    }

    @Nested
    @DisplayName("TagRewrite")
    class Rewrites {
        @Test
        @DisplayName("merges adjacent copies of contiguous ranges and skips empty pieces")
        void merges() {
            var rewrite = TagRewrite.builder().copy(0, 10).copy(10, 5).bytes(new byte[0]).copy(30, 0)
                    .bytes(new byte[]{1, 2}).copy(15, 3).build();
            assertEquals(List.of(new TagRewrite.Copy(0, 15), new TagRewrite.Bytes(new byte[]{1, 2}),
                    new TagRewrite.Copy(15, 3)), rewrite.pieces());
            assertEquals(20, rewrite.length());
            assertFalse(rewrite.isInPlace());
        }

        @Test
        @DisplayName("is in place only while every copy lands where it was")
        void inPlace() {
            assertTrue(TagRewrite.builder().copy(0, 4).bytes(new byte[6]).copy(10, 90).build().isInPlace());
            assertTrue(TagRewrite.builder().bytes(new byte[3]).copy(3, 7).bytes(new byte[5]).build().isInPlace());
            assertFalse(TagRewrite.builder().bytes(new byte[3]).copy(4, 7).build().isInPlace());
            assertTrue(TagRewrite.unchanged(0).pieces().isEmpty());
            assertEquals(List.of(new TagRewrite.Copy(0, 42)), TagRewrite.unchanged(42).pieces());
        }

        @Test
        @DisplayName("keeps copies of ranges that are not contiguous apart")
        void separate() {
            var rewrite = TagRewrite.builder().copy(0, 10).copy(11, 5).copy(5, 2).build();
            assertEquals(List.of(new TagRewrite.Copy(0, 10), new TagRewrite.Copy(11, 5), new TagRewrite.Copy(5, 2)),
                    rewrite.pieces());
            assertEquals(17, rewrite.length());
            assertFalse(rewrite.isInPlace());
        }

        @Test
        @DisplayName("copies the literal bytes it is given")
        void copiesBytes() {
            var data = new byte[]{7, 8, 9};
            var rewrite = TagRewrite.builder().bytes(data).build();
            data[0] = 0;
            assertArrayEquals(new byte[]{7, 8, 9}, ((TagRewrite.Bytes) rewrite.pieces().getFirst()).data());
        }

        @Test
        @DisplayName("compares literal pieces by their bytes")
        void bytesEquality() {
            var piece = new TagRewrite.Bytes(new byte[]{1, 2});
            assertEquals(piece, new TagRewrite.Bytes(new byte[]{1, 2}));
            assertEquals(piece.hashCode(), new TagRewrite.Bytes(new byte[]{1, 2}).hashCode());
            assertNotEquals(piece, new TagRewrite.Bytes(new byte[]{1, 3}));
            assertNotEquals(piece, new TagRewrite.Copy(1, 2));
            assertEquals("Bytes[length=2]", piece.toString());
        }

        @Test
        @DisplayName("rejects negative and overflowing ranges")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> TagRewrite.builder().copy(-1, 4));
            assertThrows(IllegalArgumentException.class, () -> TagRewrite.builder().copy(4, -1));
            assertThrows(IllegalArgumentException.class, () -> TagRewrite.builder().copy(Long.MAX_VALUE, 1));
            assertThrows(IllegalArgumentException.class, () -> TagRewrite.builder().copy(0, Long.MAX_VALUE)
                    .bytes(new byte[1]));
            assertThrows(IllegalArgumentException.class, () -> TagRewrite.unchanged(-1));
            assertThrows(IllegalArgumentException.class, () -> new TagRewrite.Copy(0, 0));
            assertThrows(IllegalArgumentException.class, () -> new TagRewrite.Copy(-1, 1));
            assertThrows(IllegalArgumentException.class, () -> new TagRewrite.Copy(Long.MAX_VALUE, 1));
            // a range may end exactly at Long.MAX_VALUE
            assertEquals(1, new TagRewrite.Copy(Long.MAX_VALUE - 1, 1).length());
            assertThrows(IllegalArgumentException.class, () -> new TagRewrite.Bytes(new byte[0]));
        }
    }
}
