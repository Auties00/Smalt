package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reads every document of the W3C IMSC test suite (w3c/imsc-tests, the IMSC 1.0.1, 1.1, 1.2 and 1.3 text and
 * image profile tests) and compares the text Smalt shows during each stretch of time with what imscJS, the
 * reference implementation, shows: the paragraphs visible between two consecutive media time events, as
 * {@code src/test/resources/subtitle/imsc-timeline.tsv} records them (written by
 * {@code tools/testdata/imsc-oracle/timeline.js}). Texts are compared exactly after the whitespace
 * normalization TTML leaves to the renderer: runs of spaces collapsed, lines trimmed, empty lines dropped.
 */
@DisplayName("W3C IMSC test suite")
class ImscConformanceTest {
    private static final String VECTOR = "imsc-tests-b7f88fe.zip";

    private static final String PREFIX = "imsc-tests-b7f88fee6a181126931a254408440f38d8985224/";

    private static final Path TIMELINE = Path.of("src", "test", "resources", "subtitle", "imsc-timeline.tsv");

    /** One stretch of the reference timeline. */
    private record Stretch(long start, long end, List<String> texts) {
    }

    @TestFactory
    @DisplayName("every document shows the text imscJS shows, at every moment")
    Stream<DynamicTest> documents() throws IOException {
        var timelines = timelines();
        var documents = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(Conformance.open(Conformance.fetchListed(VECTOR)))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.isDirectory() && entry.getName().endsWith(".ttml") && entry.getName().startsWith(PREFIX)) {
                    documents.put(entry.getName().substring(PREFIX.length()), zip.readAllBytes());
                }
            }
        }
        assertEquals(321, documents.size(), "the documents of the pinned suite");
        assertTrue(documents.keySet().containsAll(timelines.keySet()), "every reference timeline has its document");
        var tests = new ArrayList<DynamicTest>();
        for (var entry : documents.entrySet()) {
            var name = entry.getKey();
            var data = entry.getValue();
            var expected = timelines.get(name);
            tests.add(DynamicTest.dynamicTest(name, () -> check(name, data, expected)));
        }
        return tests.stream();
    }

    private static void check(String name, byte[] data, List<Stretch> expected) {
        assertEquals(ContainerFormat.TTML, Containers.detect(MediaSource.of(data)).orElseThrow(), name);
        List<SubtitleCue> cues;
        try {
            cues = Subtitles.read(MediaSource.of(data), SubtitleReaderOptions.builder().markup(SubtitleMarkup.SUBRIP).build());
        } catch (MediaException.Malformed exception) {
            if (expected == null) {
                return;
            }
            throw new AssertionError(name + " is rejected but imscJS reads it", exception);
        }
        if (expected == null) {
            return;
        }
        for (var stretch : expected) {
            var middle = stretch.start() + (stretch.end() - stretch.start()) / 2;
            var shown = new ArrayList<String>();
            for (var cue : cues) {
                if (cue.startMicros() <= middle && middle < cue.endMicros()) {
                    var text = normalize(Subtitles.plainText(cue.text(), SubtitleMarkup.SUBRIP));
                    if (!text.isEmpty()) {
                        shown.add(text);
                    }
                }
            }
            shown.sort(null);
            assertEquals(stretch.texts(), shown, name + " from " + stretch.start() + " to " + stretch.end() + " us");
        }
    }

    private static String normalize(String text) {
        var lines = new ArrayList<String>();
        for (var line : text.split("\n")) {
            var collapsed = line.replaceAll("[ \t\r]+", " ").strip();
            if (!collapsed.isEmpty()) {
                lines.add(collapsed);
            }
        }
        return String.join("\n", lines);
    }

    private static Map<String, List<Stretch>> timelines() throws IOException {
        var result = new LinkedHashMap<String, List<Stretch>>();
        for (var line : Files.readAllLines(TIMELINE, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            var fields = line.split("\t", -1);
            if (fields.length < 3 || fields[1].equals("ERROR")) {
                fail("unexpected reference line " + line);
            }
            var texts = new ArrayList<String>();
            for (var index = 3; index < fields.length; index++) {
                var text = normalize(fields[index].replace("\\n", "\n"));
                if (!text.isEmpty()) {
                    texts.add(text);
                }
            }
            texts.sort(null);
            result.computeIfAbsent(fields[0], ignored -> new ArrayList<>())
                    .add(new Stretch(Long.parseLong(fields[1]), Long.parseLong(fields[2]), texts));
        }
        return result;
    }
}
