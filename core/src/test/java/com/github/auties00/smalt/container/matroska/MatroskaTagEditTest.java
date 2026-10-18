package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the structure of Matroska files after {@link TagEditor} rewrites their segment: every
 * SeekHead entry and cue point must still land on the element it names. The fixtures are written
 * without CRC-32 elements, which the structure checker does not skip.
 */
@DisplayName("Matroska tag edits")
class MatroskaTagEditTest {
    static final String VIDEO = "testsrc2=size=96x64:rate=10:duration=3";

    static Stream<Arguments> files() {
        return Stream.of(
                Arguments.of("cues after clusters", Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", VIDEO),
                        List.of("-c:v", "libx264", "-preset", "ultrafast", "-g", "5", "-metadata", "title=Old", "-write_crc32", "0"))),
                Arguments.of("cues before clusters", Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", VIDEO),
                        List.of("-c:v", "libx264", "-preset", "ultrafast", "-g", "5", "-reserve_index_space", "4096", "-write_crc32",
                                "0"))),
                Arguments.of("webm", Fixtures.ffmpeg("webm", List.of("-f", "lavfi", "-i", VIDEO),
                        List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-g", "5"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("keeps every seek entry and cue point valid after a rewrite and an in-place edit")
    void structure(String name, java.nio.file.Path file) {
        var original = Fixtures.read(file);
        var before = MatroskaStructure.check(original);
        var cover = AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "",
                Fixtures.read(Fixtures.testImage("png", 32, 32, List.of())));
        var tags = MediaTags.builder().set(StandardTag.TITLE, "New").set(StandardTag.ARTIST, "Somebody")
                .set("CUSTOM", "x".repeat(20_000)).addPicture(cover).build();
        var rewritten = TagEditor.write(original, tags, TagWriteOptions.defaults());
        var after = MatroskaStructure.check(rewritten);
        assertEquals(before.cues.size(), after.cues.size());
        assertEquals(before.clusterPositions.size(), after.clusterPositions.size());
        assertFalse(after.cues.isEmpty() && !before.cues.isEmpty());
        var read = TagEditor.read(MediaSource.of(rewritten));
        assertEquals("New", read.get(StandardTag.TITLE).orElseThrow());
        assertEquals(1, read.pictures().size());
        var smaller = TagEditor.write(rewritten, tags.toBuilder().set("CUSTOM", "y").build(), TagWriteOptions.defaults());
        assertEquals(rewritten.length, smaller.length, "the smaller tags fit in place");
        MatroskaStructure.check(smaller);
        assertTrue(TagEditor.read(MediaSource.of(smaller)).get("CUSTOM").orElseThrow().equals("y"));
        try (var demuxer = new MatroskaDemuxer(MediaSource.of(smaller))) {
            demuxer.seek(2_000_000);
            assertTrue(demuxer.read() != null);
        }
    }
}
