package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips tags through every container {@link TagEditor} supports, with ffprobe as the
 * independent reader and ffmpeg's packet hashes proving the audio and video data unchanged.
 */
@DisplayName("TagEditor")
class TagEditorTest {
    /** A container under test and what ffmpeg can read back from it. */
    record Case(String name, Path file, boolean ffprobeChapters, boolean ffprobePictures, boolean joinsValues, boolean inPlace) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> containers() {
        return Stream.of(
                new Case("mp3 id3v2.4", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k")), true, true, false, true),
                new Case("mp3 id3v2.3", TagFixtures.audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k",
                        "-id3v2_version", "3", "-write_id3v1", "1")), true, true, true, true),
                new Case("flac", TagFixtures.audio("flac", List.of("-c:a", "flac")), false, true, false, true),
                new Case("ogg vorbis", TagFixtures.audio("ogg", List.of("-c:a", "libvorbis")), true, false, false, true),
                new Case("ogg opus", TagFixtures.audio("opus", List.of("-c:a", "libopus")), true, false, false, true),
                new Case("ogg flac", TagFixtures.audio("oga", List.of("-c:a", "flac")), true, false, false, false),
                new Case("m4a", TagFixtures.audio("m4a", List.of("-c:a", "aac", "-b:a", "64k")), true, true, false, true),
                new Case("mp4 faststart", TagFixtures.video("mp4", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                        "-movflags", "+faststart")), true, false, false, true),
                new Case("mov", TagFixtures.video("mov", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac")),
                        true, false, false, true),
                new Case("fragmented mp4", TagFixtures.video("mp4", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a",
                        "aac", "-movflags", "+frag_keyframe+empty_moov+default_base_moof")), false, false, false, true),
                new Case("mka", TagFixtures.audio("mka", List.of("-c:a", "libopus")), true, true, false, true),
                new Case("mkv", TagFixtures.video("mkv", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac")),
                        true, true, false, true),
                new Case("webm", TagFixtures.video("webm", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-c:a",
                        "libopus")), true, true, false, true),
                new Case("fragmented mp4 absolute offsets", TagFixtures.video("mp4", List.of("-c:v", "libx264", "-preset",
                        "ultrafast", "-c:a", "aac", "-movflags", "+frag_keyframe+empty_moov")), false, false, false, true),
                new Case("adts", TagFixtures.audio("aac", List.of("-c:a", "aac", "-f", "adts", "-write_id3v2", "1")), false,
                        false, false, true),
                new Case("wav", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le")), false, false, false, true),
                new Case("wav id3", TagFixtures.audio("wav", List.of("-c:a", "pcm_s16le", "-write_id3v2", "1")), false, false,
                        false, true),
                new Case("aiff", TagFixtures.audio("aiff", List.of("-c:a", "pcm_s16be", "-write_id3v2", "1")), false, false,
                        false, true)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("reads the tags ffmpeg wrote")
    void readsFixture(Case container) {
        var tags = TagEditor.read(MediaSource.of(Fixtures.read(container.file())));
        var probe = TagFixtures.ffprobeTags(container.file());
        assertEquals(probe.get("title"), tags.get(StandardTag.TITLE).orElse(null));
        assertEquals(probe.get("artist"), tags.get(StandardTag.ARTIST).orElse(null));
        assertEquals(probe.get("album"), tags.get(StandardTag.ALBUM).orElse(null));
        assertEquals(probe.get("genre"), tags.get(StandardTag.GENRE).orElse(null));
        if (probe.containsKey("track")) {
            assertEquals(3, tags.trackNumber().orElse(-1));
        }
        var chapters = TagFixtures.ffprobeChapters(container.file());
        assertEquals(chapters.size(), tags.chapters().size());
        if (!chapters.isEmpty()) {
            assertEquals(List.of("Intro", "Middle"), tags.chapters().stream().map(chapter -> chapter.title().orElse("")).toList());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("writes every kind of tag, keeps the packets, and reads them back like ffprobe does")
    void roundTrip(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        var before = TagFixtures.packetHash(file);
        var sample = TagFixtures.sample();
        TagEditor.write(file, sample);
        assertEquals(before, TagFixtures.packetHash(file), "packets changed");
        var read = TagEditor.read(file);
        assertEquals(sample.get(StandardTag.TITLE), read.get(StandardTag.TITLE));
        assertEquals(container.joinsValues() ? List.of(String.join("/", sample.getAll(StandardTag.ARTIST)))
                : sample.getAll(StandardTag.ARTIST), read.getAll(StandardTag.ARTIST));
        assertEquals(sample.get(StandardTag.ALBUM), read.get(StandardTag.ALBUM));
        assertEquals(sample.get(StandardTag.ALBUM_ARTIST), read.get(StandardTag.ALBUM_ARTIST));
        assertEquals(sample.get(StandardTag.TRACK), read.get(StandardTag.TRACK));
        assertEquals(sample.get(StandardTag.DISC), read.get(StandardTag.DISC));
        assertEquals(sample.get(StandardTag.DATE), read.get(StandardTag.DATE));
        assertEquals(sample.get(StandardTag.GENRE), read.get(StandardTag.GENRE));
        assertEquals(sample.get(StandardTag.COMMENT), read.get(StandardTag.COMMENT));
        assertEquals(sample.get(StandardTag.COMPOSER), read.get(StandardTag.COMPOSER));
        assertEquals(sample.get(StandardTag.LYRICS), read.get(StandardTag.LYRICS));
        assertEquals(sample.get("REPLAYGAIN_TRACK_GAIN"), read.get("REPLAYGAIN_TRACK_GAIN"));
        assertEquals(1, read.pictures().size());
        assertArrayEquals(sample.pictures().getFirst().data(), read.pictures().getFirst().data());
        assertChapters(sample.chapters(), read.chapters());
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals(sample.get(StandardTag.TITLE).orElseThrow(), probe.get("title"));
        assertEquals(sample.get(StandardTag.ALBUM).orElseThrow(), probe.get("album"));
        assertTrue(probe.get("artist").contains("Artist"), probe.toString());
        if (container.ffprobeChapters()) {
            assertEquals(sample.chapters().stream().map(Chapter::title).toList(),
                    TagFixtures.ffprobeChapters(file).stream().map(Chapter::title).toList());
        }
        if (container.ffprobePictures()) {
            assertEquals(1, TagFixtures.ffprobeAttachedPictures(file));
        }
        Oracle.assertDecodesCleanly(file);
    }

    /** Compares chapters; the last end is the media duration in formats that store start times only. */
    static void assertChapters(List<Chapter> expected, List<Chapter> actual) {
        assertEquals(expected.size(), actual.size(), actual.toString());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).startMicros(), actual.get(index).startMicros());
            assertEquals(expected.get(index).title(), actual.get(index).title());
            if (index + 1 < expected.size()) {
                assertEquals(expected.get(index).endMicros(), actual.get(index).endMicros());
            } else {
                assertEquals(expected.get(index).endMicros(), actual.get(index).endMicros(), 50_000);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("edits in place once padding exists, and streams the same bytes it writes in place")
    void inPlace(Case container, @TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(container.file(), directory);
        TagEditor.write(file, TagFixtures.sample());
        var smaller = TagFixtures.sample().toBuilder().set(StandardTag.TITLE, "Short").build();
        var original = Files.readAllBytes(file);
        var streamed = MediaSink.memory();
        TagEditor.write(MediaSource.of(original), streamed, smaller, TagWriteOptions.defaults());
        assertEquals(container.inPlace() ? TagWriteResult.IN_PLACE : TagWriteResult.REWRITTEN, TagEditor.write(file, smaller));
        if (container.inPlace()) {
            // tags at the end of the file (MP4 with moov last) shrink with the file
            assertTrue(Files.size(file) <= original.length);
        }
        assertArrayEquals(streamed.toByteArray(), Files.readAllBytes(file));
        assertEquals("Short", TagEditor.read(file).get(StandardTag.TITLE).orElseThrow());
        // a format that joins values reaches its fixed point after one more write
        TagEditor.write(file, TagEditor.read(file));
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("removes every tag, picture and chapter")
    void removesEverything(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        var before = TagFixtures.packetHash(file);
        TagEditor.write(file, TagFixtures.sample());
        TagEditor.write(file, MediaTags.empty(), TagWriteOptions.builder().id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE)
                .build());
        var read = TagEditor.read(file);
        assertTrue(read.get(StandardTag.TITLE).isEmpty(), read.toString());
        assertTrue(read.pictures().isEmpty());
        assertTrue(read.chapters().isEmpty());
        assertEquals(before, TagFixtures.packetHash(file));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("agrees with Media.probe on the standard tags it writes")
    void probeAgrees(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        TagEditor.write(file, TagFixtures.sample());
        var read = TagEditor.read(file).asMap();
        var probed = com.github.auties00.smalt.Media.probe(MediaSource.of(Fixtures.read(file))).tags();
        for (var tag : List.of(StandardTag.TITLE, StandardTag.ALBUM, StandardTag.GENRE, StandardTag.COMPOSER)) {
            assertEquals(read.get(tag.key()), probed.get(tag.key()), tag + " in " + probed);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("keeps several pictures with their types and descriptions")
    void pictures(Case container, @TempDir Path directory) {
        var file = TagFixtures.copy(container.file(), directory);
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Pictures")
                .addPicture(TagFixtures.picture(AttachedPicture.Type.FRONT_COVER, "front"))
                .addPicture(TagFixtures.picture(AttachedPicture.Type.BACK_COVER, "back"))
                .build();
        TagEditor.write(file, tags);
        var read = TagEditor.read(file);
        assertEquals(2, read.pictures().size());
        assertEquals(AttachedPicture.Type.FRONT_COVER, read.pictures().getFirst().type());
    }
}
