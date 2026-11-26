package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reads the tag test files of TagLib's suite: ID3v1 and ID3v2.2 to 2.4 (unsynchronized, compressed, extended headers,
 * duplicate and invalid frames), APE, Vorbis comments, FLAC, iTunes {@code ilst}, Matroska, RIFF and AIFF, ASF and DSD
 * tags, and the malformed files TagLib keeps as regressions (infinite loops, excessive allocations, zero sizes). On
 * every file the tag reader and the probe must end in bounded time, failing only with {@link MediaException}; where
 * both Smalt and ffprobe read tags, the title, artist, album and genre must agree, and where Smalt can write the
 * container, writing the tags it read back must read back the same.
 */
@DisplayName("TagLib tag corpus")
class TagLibCorpusTest {
    /** The tags compared with ffprobe's format tags, by ffprobe key. */
    private static final Map<String, StandardTag> COMPARED = Map.of("title", StandardTag.TITLE, "artist",
            StandardTag.ARTIST, "album", StandardTag.ALBUM, "genre", StandardTag.GENRE);

    static List<String> files() {
        return Conformance.vectors("taglib-");
    }

    /** Runs ffprobe for the format and stream tags of a file; empty when ffprobe cannot read it. */
    static Optional<Map<String, String>> ffprobeTags(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var result = Oracle.execute(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), null, List.of("-v", "error",
                "-show_entries", "format_tags:stream_tags", "-of", "flat", file.toString()));
        if (result.exitCode() != 0) {
            return Optional.empty();
        }
        var tags = new HashMap<String, String>();
        for (var line : new String(result.stdout(), StandardCharsets.UTF_8).split("\r?\n")) {
            var equals = line.indexOf('=');
            if (equals < 0 || !line.startsWith("format.tags.")) {
                continue;
            }
            var value = line.substring(equals + 1);
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            tags.put(line.substring("format.tags.".length(), equals).toLowerCase(java.util.Locale.ROOT),
                    value.replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return Optional.of(tags);
    }

    /**
     * Reads the tags of a file and checks them against ffprobe and through a round trip; returns the tags, or empty
     * when the reader rejected the file with a {@link MediaException}.
     */
    static Optional<MediaTags> assertTags(Path file, String name) {
        var tags = assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try {
                Media.probe(MediaSource.of(file));
            } catch (MediaException expected) {
                // reported as documented
            }
            try {
                return Optional.of(TagEditor.read(file));
            } catch (MediaException expected) {
                return Optional.<MediaTags>empty();
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError unexpected) {
                return fail(name + ": " + unexpected, unexpected);
            }
        }, name + " ends in bounded time");
        if (tags.isEmpty()) {
            return tags;
        }
        var probe = ffprobeTags(file);
        // of two tag blocks of one file ffmpeg keeps the last, Smalt the first: no format defines which, so a file of
        // duplicated tags is only read
        if (probe.isPresent() && !name.contains("duplicate") && !name.contains("multiple-vc")) {
            for (var entry : COMPARED.entrySet()) {
                var expected = probe.get().get(entry.getKey());
                var actual = tags.get().getAll(entry.getValue());
                if (expected != null && !expected.isBlank() && !actual.isEmpty()) {
                    // ffmpeg joins the values of a multi-valued field with semicolons
                    assertEquals(expected.strip(), String.join(";", actual).strip(), name + " " + entry.getKey());
                }
            }
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        byte[] written;
        try {
            written = TagEditor.write(bytes, tags.get(), TagWriteOptions.defaults());
        } catch (MediaException.Unsupported | MediaException.Malformed | MediaException.LimitExceeded skipped) {
            // a container Smalt reads but does not write, or one too damaged to rewrite
            return tags;
        }
        var reread = TagEditor.read(MediaSource.of(written));
        for (var tag : COMPARED.values()) {
            assertEquals(tags.get().get(tag), reread.get(tag), name + " round trip of " + tag);
        }
        return tags;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("reads every file in bounded time, agreeing with ffprobe and keeping its tags through a rewrite")
    void corpus(String name) {
        assertTags(Conformance.vector(name), name);
    }
}
