package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.subtitle.WebVttDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses the WebVTT files of the web-platform-tests ({@code webvtt/parsing}): the file parsing tests, whose
 * expectations are JavaScript assertions on the cues a browser builds (the count of cues, and the identifier, text,
 * start and end time of a cue, which are checked here; assertions on other cue attributes and on regions and style
 * sheets, which Smalt's cues do not carry, are counted and left), the cue text parsing tests, whose expectations are
 * the DOM trees of the cue text (the text of the tree's text nodes, in order, is checked against the plain text of the
 * cue), and the signature tests, every file of which a parser must reject.
 */
@DisplayName("WebVTT parsing (web-platform-tests)")
class WebVttConformanceTest {
    private static String read(String name) {
        try {
            return Files.readString(Conformance.vector(name), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static List<SubtitleCue> parse(String text) {
        return Subtitles.read(MediaSource.of(text.getBytes(StandardCharsets.UTF_8)),
                SubtitleReaderOptions.builder().sourceMarkup().build());
    }

    static List<String> files() {
        return Conformance.vectors("wpt-webvtt-").stream().filter(name -> name.endsWith(".test")).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("builds the cues the test's assertions describe")
    void file(String name) {
        var test = read(name);
        var separator = test.indexOf("\n===\n");
        assertTrue(separator > 0, name);
        var script = test.substring(0, separator);
        // the file part writes the characters a text editor would not keep as escapes, which the suite's build
        // script resolves
        var vtt = resolveCodes(test.substring(separator + 5));
        List<SubtitleCue> cues;
        try {
            cues = parse(vtt);
        } catch (MediaException.Malformed rejected) {
            cues = List.of();
        }
        var checked = 0;
        var length = Pattern.compile("assert_equals\\(cues\\.length, (\\d+)\\)").matcher(script);
        if (length.find()) {
            assertEquals(Integer.parseInt(length.group(1)), cues.size(), name + " cue count");
            checked++;
        }
        var property = Pattern.compile("assert_equals\\(cues\\[(\\d+)\\]\\.(id|text|startTime|endTime), "
                + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[0-9.]+)\\)").matcher(script);
        while (property.find()) {
            var cue = cues.get(Integer.parseInt(property.group(1)));
            var value = property.group(3);
            switch (property.group(2)) {
                case "id" -> assertEquals(unquote(value), cue.identifier() == null ? "" : cue.identifier(),
                        name + " id of cue " + property.group(1));
                case "text" -> assertEquals(unquote(value), cue.text(), name + " text of cue " + property.group(1));
                case "startTime" -> assertEquals(Double.parseDouble(value), cue.startMicros() / 1e6, 1e-9,
                        name + " start of cue " + property.group(1));
                default -> assertEquals(Double.parseDouble(value), cue.endMicros() / 1e6, 1e-9,
                        name + " end of cue " + property.group(1));
            }
            checked++;
        }
        assertTrue(checked > 0 || !script.contains("cues"), name + ": no assertion checked");
    }

    /**
     * Resolves the escapes of the file part of a test as the suite's build script does ({@code tools/build.py}
     * decodes it with Python's {@code unicode_escape} codec): the single-character escapes, a backslash
     * and {@code x} with two hexadecimal digits or {@code u} with four, octal escapes, and a backslash before a line feed, which joins the lines.
     */
    private static String resolveCodes(String text) {
        var out = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); index++) {
            var c = text.charAt(index);
            if (c != '\\' || index + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            var next = text.charAt(++index);
            switch (next) {
                case '\n' -> { }
                case '\\', '\'', '"' -> out.append(next);
                case 'a' -> out.append('\u0007');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'v' -> out.append('\u000B');
                case 'x' -> {
                    out.append((char) Integer.parseInt(text.substring(index + 1, index + 3), 16));
                    index += 2;
                }
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> {
                    if (next >= '0' && next <= '7') {
                        var end = index + 1;
                        while (end < text.length() && end < index + 3 && text.charAt(end) >= '0'
                                && text.charAt(end) <= '7') {
                            end++;
                        }
                        out.append((char) Integer.parseInt(text.substring(index, end), 8));
                        index = end - 1;
                    } else {
                        out.append('\\').append(next);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Resolves the escapes of a JavaScript string literal. */
    private static String unquote(String literal) {
        return unescape(literal.substring(1, literal.length() - 1));
    }

    /** Resolves the backslash escapes of the tests: n, t, r, f, a backslash, quotes, x and two and u and four hex digits. */
    private static String unescape(String text) {
        var out = new StringBuilder();
        for (var index = 0; index < text.length(); index++) {
            var c = text.charAt(index);
            if (c != '\\' || index + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            var next = text.charAt(++index);
            switch (next) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'f' -> out.append('\f');
                case 'x' -> {
                    out.append((char) Integer.parseInt(text.substring(index + 1, index + 3), 16));
                    index += 2;
                }
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(index + 1, index + 5), 16));
                    index += 4;
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wpt-webvtt-cue-text-entities.dat", "wpt-webvtt-cue-text-tags.dat",
            "wpt-webvtt-cue-text-text.dat", "wpt-webvtt-cue-text-timestamps.dat",
            "wpt-webvtt-cue-text-tree-building.dat"})
    @DisplayName("parses cue text to the text nodes of the expected tree")
    void cueText(String name) {
        var tests = read(name).split("(?m)^#data\\n");
        var count = 0;
        for (var test : tests) {
            if (test.isBlank()) {
                continue;
            }
            var data = unescape(test.substring(0, test.indexOf("\n#errors")));
            var tree = test.substring(test.indexOf("#document-fragment") + "#document-fragment".length());
            var expected = new StringBuilder();
            var node = Pattern.compile("(?m)^\\|\\s*\"(.*)\"$").matcher(tree);
            while (node.find()) {
                expected.append(unescape(node.group(1)));
            }
            var cues = parse("WEBVTT\n\n00:00.000 --> 00:01.000\n" + data + "\n");
            var actual = new StringBuilder();
            for (var cue : cues) {
                actual.append(Subtitles.plainText(cue.text(), SubtitleMarkup.WEBVTT));
            }
            assertEquals(expected.toString(), actual.toString(), name + ": " + data);
            count++;
        }
        assertTrue(count >= 5, count + " cases");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"wpt-webvtt-empty.vtt", "wpt-webvtt-signature-formfeed.vtt",
            "wpt-webvtt-signature-invalid-whitespace.vtt", "wpt-webvtt-signature-invalid.vtt",
            "wpt-webvtt-signature-lowercase.vtt", "wpt-webvtt-signature-missing-whitespace.vtt",
            "wpt-webvtt-signature-missing.vtt", "wpt-webvtt-signature-null.vtt", "wpt-webvtt-signature-partial.vtt",
            "wpt-webvtt-signature-two-boms.vtt", "wpt-webvtt-signature-websrt.vtt"})
    @DisplayName("rejects every file of the invalid signature test (signature-invalid.html)")
    void invalidSignature(String name) {
        assertTrue(read("wpt-webvtt-signature-invalid.html").contains(name.substring("wpt-webvtt-".length())));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Conformance.vector(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        // the test loads each file as a text track, which is parsed as WebVTT whatever it holds
        assertThrows(MediaException.class, () -> new WebVttDemuxer(MediaSource.of(bytes)), name);
    }

    @Test
    @DisplayName("reads the cues of the region tests (header-regions.html, regions-edge-case.html)")
    void regions() {
        for (var test : List.of(new String[]{"wpt-webvtt-header-regions", "10"},
                new String[]{"wpt-webvtt-regions-edge-case", "4"})) {
            var html = read(test[0] + ".html");
            assertTrue(html.contains("cues.length, " + test[1] + ")"), test[0]);
            assertEquals(Integer.parseInt(test[1]), parse(read(test[0] + ".vtt")).size(), test[0]);
        }
    }

}
