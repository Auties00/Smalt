package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the QuickTime chapter tracks that {@link TagEditor} and {@link Mp4Muxer} write next to the Nero
 * {@code chpl} box: the chapters must read back through the track alone (the {@code chpl} box renamed
 * {@code free}), by Smalt and by ffprobe as the black-box reader, and the track must have the QuickTime shape
 * (a disabled {@code text} track referenced through {@code tref/chap} by every audio and video track).
 */
@DisplayName("MP4 chapter tracks")
class Mp4ChapterTrackTest {
    static final List<Chapter> CHAPTERS = List.of(Chapter.of(0, 700_000, "One"),
            Chapter.of(700_000, 1_500_000, "Tw\u00F3 \u4E2D"), Chapter.of(1_500_000, 2_000_000, "Three"));

    record Case(String name, Path file) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> files() {
        return Stream.of(
                new Case("m4a without chapters", MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"))),
                new Case("m4a with ffmpeg chapters", TagFixtures.audio("m4a", List.of("-c:a", "aac"))),
                new Case("m4b audiobook", TagFixtures.audio("m4b", List.of("-c:a", "aac", "-brand", "M4B "))),
                new Case("mp4 faststart", TagFixtures.video("mp4", List.of("-c:v", "libx264", "-preset", "ultrafast",
                        "-c:a", "aac", "-movflags", "+faststart"))),
                new Case("mov", TagFixtures.video("mov", List.of("-c:v", "libx264", "-preset", "ultrafast", "-c:a",
                        "aac"))),
                new Case("fragmented mp4", TagFixtures.video("mp4", List.of("-c:v", "libx264", "-preset", "ultrafast",
                        "-c:a", "aac", "-movflags", "+frag_keyframe+empty_moov+default_base_moof"))),
                new Case("fragmented mp4 absolute offsets", TagFixtures.video("mp4", List.of("-c:v", "libx264",
                        "-preset", "ultrafast", "-c:a", "aac", "-movflags", "+frag_keyframe+empty_moov")))
        ).map(Arguments::of);
    }

    /** Three hundred chapters of five milliseconds: more than a Nero chpl box holds. */
    static List<Chapter> manyChapterList() {
        var chapters = new ArrayList<Chapter>();
        for (var index = 0; index < 300; index++) {
            chapters.add(Chapter.of(index * 5_000L, (index + 1) * 5_000L, "C" + index));
        }
        return chapters;
    }

    static MediaTags tags(List<Chapter> chapters) {
        return MediaTags.builder().set(StandardTag.TITLE, "Chaptered").chapters(chapters).build();
    }

    static Path write(Path directory, String name, byte[] data) {
        try {
            var file = directory.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** Returns the chapter tracks: the tracks some {@code tref/chap} names. */
    static List<Mp4Atoms.Box> chapterTracks(byte[] data) {
        var referenced = new ArrayList<Long>();
        for (var trak : Mp4Atoms.all(data, "moov/trak")) {
            referenced.addAll(Mp4Atoms.chapterReferences(data, trak));
        }
        var result = new ArrayList<Mp4Atoms.Box>();
        for (var trak : Mp4Atoms.all(data, "moov/trak")) {
            if (referenced.contains(Mp4Atoms.trackId(data, trak))) {
                result.add(trak);
            }
        }
        return result;
    }

    /** Asserts the QuickTime chapter track shape and returns the chapter track. */
    static Mp4Atoms.Box assertChapterTrack(byte[] data) {
        var chapters = chapterTracks(data);
        assertEquals(1, chapters.size(), "one chapter track");
        var track = chapters.getFirst();
        var id = Mp4Atoms.trackId(data, track);
        assertEquals("text", Mp4Atoms.handler(data, track));
        assertEquals(0, Mp4Atoms.trackFlags(data, track) & 1, "the chapter track is disabled");
        assertNotNull(Mp4Atoms.first(data, track, "mdia/minf/stbl/stsd"));
        var stsd = Mp4Atoms.first(data, track, "mdia/minf/stbl/stsd");
        assertEquals("text", Mp4Atoms.type(data, stsd.payload() + 12));
        assertNotNull(Mp4Atoms.first(data, track, "mdia/minf/gmhd/gmin"));
        var ids = new ArrayList<Long>();
        for (var trak : Mp4Atoms.all(data, "moov/trak")) {
            var trackId = Mp4Atoms.trackId(data, trak);
            assertFalse(ids.contains(trackId), "duplicate track id " + trackId);
            ids.add(trackId);
            var handler = Mp4Atoms.handler(data, trak);
            if (handler.equals("soun") || handler.equals("vide")) {
                assertEquals(List.of(id), Mp4Atoms.chapterReferences(data, trak), handler + " track reference");
            }
        }
        var mvhd = Mp4Atoms.all(data, "moov/mvhd").getFirst();
        var nextId = Mp4Atoms.u32(data, mvhd.payload() + ((data[mvhd.payload()] & 0xFF) == 1 ? 108 : 96));
        assertTrue(nextId > ids.stream().mapToLong(Long::longValue).max().orElseThrow(), "next_track_ID " + nextId);
        if (!Mp4Atoms.all(data, "moov/mvex").isEmpty()) {
            var extended = new ArrayList<Long>();
            for (var trex : Mp4Atoms.all(data, "moov/mvex/trex")) {
                extended.add(Mp4Atoms.u32(data, trex.payload() + 4));
            }
            assertTrue(extended.contains(id), "trex of the chapter track");
        }
        return track;
    }

    /** Compares chapters read from a track or from chpl; the last end may be stretched to the movie end. */
    static void assertChapters(List<Chapter> expected, List<Chapter> actual) {
        TagEditorTest.assertChapters(expected, actual);
    }

    static void assertFfprobeChapters(List<Chapter> expected, Path file) {
        var probed = TagFixtures.ffprobeChapters(file);
        assertEquals(expected.size(), probed.size(), probed.toString());
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).startMicros(), probed.get(index).startMicros(), 1000);
            assertEquals(expected.get(index).title().orElse(null), probed.get(index).title().orElse(null));
            if (index + 1 < expected.size()) {
                assertEquals(expected.get(index).endMicros(), probed.get(index).endMicros(), 1000);
            }
        }
    }

    @Nested
    @DisplayName("TagEditor")
    class Editor {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.metadata.Mp4ChapterTrackTest#files")
        @DisplayName("writes a chapter track that Smalt and ffprobe read without the chpl box")
        void writesTrack(Case container, @TempDir Path directory) {
            var file = TagFixtures.copy(container.file(), directory);
            var before = TagFixtures.packetHash(file);
            TagEditor.write(file, tags(CHAPTERS));
            assertEquals(before, TagFixtures.packetHash(file), "packets changed");
            var data = Fixtures.read(file);
            assertChapterTrack(data);
            assertChapters(CHAPTERS, TagEditor.read(MediaSource.of(data)).chapters());
            var trackOnly = Mp4Atoms.withoutNeroChapters(data);
            assertChapters(CHAPTERS, TagEditor.read(MediaSource.of(trackOnly)).chapters());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                assertChapters(CHAPTERS, demuxer.chapters());
                // a fragmented file keeps ffmpeg's chapter track, whose samples its fragments carry, as a text track
                // no chapter reference names, since a track fragment must name a track of the movie
                var dataTracks = container.name().startsWith("fragmented") ? 2 : 1;
                assertEquals(dataTracks, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.DATA)
                        .count());
            }
            var probeFile = write(directory, "track-only-" + file.getFileName(), trackOnly);
            assertFfprobeChapters(CHAPTERS, probeFile);
            assertFfprobeChapters(CHAPTERS, file);
            Oracle.assertDecodesCleanly(probeFile);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.metadata.Mp4ChapterTrackTest#files")
        @DisplayName("replaces the chapter track in the space of the old one and removes it with the chapters")
        void replacesTrack(Case container, @TempDir Path directory) throws IOException {
            var file = TagFixtures.copy(container.file(), directory);
            var before = TagFixtures.packetHash(file);
            var originalBoxes = Mp4Atoms.top(Fixtures.read(file)).size();
            TagEditor.write(file, tags(CHAPTERS));
            var first = Fixtures.read(file);
            var other = List.of(Chapter.of(0, 900_000, "Uno"), Chapter.of(900_000, 1_300_000, "Dos \u00E9\u00E9"),
                    Chapter.of(1_300_000, 2_000_000, "Tres!"));
            assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, tags(other)));
            var second = Fixtures.read(file);
            assertEquals(first.length, second.length);
            assertEquals(Mp4Atoms.top(first).size(), Mp4Atoms.top(second).size(), "no box is left behind");
            assertChapterTrack(second);
            assertChapters(other, TagEditor.read(MediaSource.of(Mp4Atoms.withoutNeroChapters(second))).chapters());
            assertEquals(before, TagFixtures.packetHash(file));
            TagEditor.write(file, MediaTags.empty());
            var removed = Fixtures.read(file);
            assertTrue(chapterTracks(removed).isEmpty(), "no chapter track remains");
            for (var trak : Mp4Atoms.all(removed, "moov/trak")) {
                assertTrue(Mp4Atoms.chapterReferences(removed, trak).isEmpty());
            }
            assertTrue(Mp4Atoms.top(removed).size() <= originalBoxes + 1, Mp4Atoms.top(removed).toString());
            assertTrue(TagEditor.read(file).chapters().isEmpty());
            assertEquals(before, TagFixtures.packetHash(file));
            Oracle.assertDecodesCleanly(file);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.metadata.Mp4ChapterTrackTest#files")
        @DisplayName("keeps the chapter track byte for byte while the chapters do not change")
        void keepsTrack(Case container, @TempDir Path directory) {
            var file = TagFixtures.copy(container.file(), directory);
            TagEditor.write(file, tags(CHAPTERS));
            var first = Fixtures.read(file);
            var track = assertChapterTrack(first);
            var read = TagEditor.read(file);
            assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, read.toBuilder().set(StandardTag.ALBUM, "A").build()));
            var second = Fixtures.read(file);
            var kept = assertChapterTrack(second);
            assertArrayEquals(java.util.Arrays.copyOfRange(first, track.offset(), track.end()),
                    java.util.Arrays.copyOfRange(second, kept.offset(), kept.end()));
            assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
        }

        @Test
        @DisplayName("starts a late first chapter with an empty edit")
        void lateFirstChapter(@TempDir Path directory) {
            var file = TagFixtures.copy(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), directory);
            var chapters = List.of(Chapter.of(500_000, 1_200_000, "Late"), Chapter.of(1_200_000, 1_800_000, "Later"));
            TagEditor.write(file, tags(chapters));
            var trackOnly = Mp4Atoms.withoutNeroChapters(Fixtures.read(file));
            var track = assertChapterTrack(trackOnly);
            var elst = Mp4Atoms.first(trackOnly, track, "edts/elst");
            assertNotNull(elst);
            assertEquals(2, Mp4Atoms.u32(trackOnly, elst.payload() + 4));
            assertEquals(0xFFFFFFFFL, Mp4Atoms.u32(trackOnly, elst.payload() + 12), "empty edit");
            var read = TagEditor.read(MediaSource.of(trackOnly)).chapters();
            assertEquals(chapters, read);
            assertFfprobeChapters(chapters, write(directory, "late.m4a", trackOnly));
        }

        @Test
        @DisplayName("keeps chapter titles longer than chpl holds, untitled chapters and microsecond starts")
        void titlesAndPrecision(@TempDir Path directory) {
            var file = TagFixtures.copy(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), directory);
            var longTitle = "\u00E9".repeat(400);
            var chapters = List.of(Chapter.of(0, 333_333, longTitle), Chapter.of(333_333, 1_000_001, null),
                    Chapter.of(1_000_001, 1_900_000, "End"));
            TagEditor.write(file, tags(chapters));
            var data = Fixtures.read(file);
            var chpl = Mp4Atoms.all(data, "moov/udta/chpl").getFirst();
            // version 1 header, 4 reserved bytes, count, then the first 64-bit start and its title length
            assertEquals(254, data[chpl.payload() + 9 + 8] & 0xFF, "chpl cuts the title at a character boundary");
            assertEquals(chapters, TagEditor.read(MediaSource.of(data)).chapters(), "the track completes the cut title");
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                assertEquals(chapters, demuxer.chapters());
            }
            var track = TagEditor.read(MediaSource.of(Mp4Atoms.withoutNeroChapters(data))).chapters();
            assertEquals(chapters, track);
            var read = TagEditor.read(file);
            TagEditor.write(file, read.toBuilder().set(StandardTag.ALBUM, "A").build());
            assertEquals(chapters, TagEditor.read(file).chapters(), "a rewrite keeps the full titles");
        }

        @Test
        @DisplayName("reads more chapters than chpl holds from the chapter track and keeps them on rewrite")
        void manyChapters(@TempDir Path directory) {
            var file = TagFixtures.copy(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), directory);
            var chapters = manyChapterList();
            TagEditor.write(file, tags(chapters));
            var data = Fixtures.read(file);
            assertEquals(255, data[Mp4Atoms.all(data, "moov/udta/chpl").getFirst().payload() + 8] & 0xFF);
            assertChapters(chapters, TagEditor.read(file).chapters());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                assertChapters(chapters, demuxer.chapters());
            }
            assertFfprobeChapters(chapters, file);
            TagEditor.write(file, TagEditor.read(file).toBuilder().set(StandardTag.ALBUM, "A").build());
            assertChapters(chapters, TagEditor.read(file).chapters());
            assertEquals(1, chapterTracks(Fixtures.read(file)).size());
        }

        @Test
        @DisplayName("replaces an empty chap reference instead of adding a second one")
        void emptyReference() {
            var data = TagEditor.write(Fixtures.read(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"))),
                    tags(CHAPTERS), TagWriteOptions.defaults());
            var audio = Mp4Atoms.all(data, "moov/trak").stream()
                    .filter(trak -> Mp4Atoms.handler(data, trak).equals("soun")).findFirst().orElseThrow();
            var chap = Mp4Atoms.first(data, audio, "tref/chap");
            // the chap box shrinks to its header; its old identifier stays behind as bytes no box covers
            data[chap.offset() + 3] = 8;
            assertTrue(chapterTracks(data).isEmpty());
            var other = List.of(Chapter.of(0, 1_000_000, "New"), Chapter.of(1_000_000, 2_000_000, "Newer"));
            var edited = TagEditor.write(data, tags(other), TagWriteOptions.defaults());
            var track = assertChapterTrack(edited);
            for (var trak : Mp4Atoms.all(edited, "moov/trak")) {
                if (Mp4Atoms.handler(edited, trak).equals("soun")) {
                    assertEquals(1, Mp4Atoms.all(edited, trak, "tref/chap").size());
                    assertEquals(List.of(Mp4Atoms.trackId(edited, track)), Mp4Atoms.chapterReferences(edited, trak));
                }
            }
            assertChapters(other, TagEditor.read(MediaSource.of(Mp4Atoms.withoutNeroChapters(edited))).chapters());
        }

        @Test
        @DisplayName("reads chpl when a full box disagrees with the chapter track")
        void disagreeingTrack(@TempDir Path directory) {
            var file = TagFixtures.copy(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), directory);
            var chapters = manyChapterList();
            TagEditor.write(file, tags(chapters));
            var data = Fixtures.read(file);
            var track = assertChapterTrack(data);
            var stco = Mp4Atoms.first(data, track, "mdia/minf/stbl/stco");
            var samples = (int) Mp4Atoms.u32(data, stco.payload() + 8);
            // the second title of the track, "C1", becomes "D1"
            var second = samples + 2 + "C0".length() + 12 + 2;
            assertEquals('C', data[second]);
            data[second] = 'D';
            var nero = TagEditor.read(MediaSource.of(data)).chapters();
            assertEquals(255, nero.size());
            assertEquals("C1", nero.get(1).title().orElseThrow());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                assertEquals(nero, demuxer.chapters());
            }
        }

        @Test
        @DisplayName("places the chapter samples before moov and gives them a 64-bit offset only when needed")
        void layout(@TempDir Path directory) {
            var file = TagFixtures.copy(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), directory);
            TagEditor.write(file, tags(CHAPTERS));
            var data = Fixtures.read(file);
            var top = Mp4Atoms.top(data);
            var moov = top.stream().filter(box -> box.type().equals("moov")).findFirst().orElseThrow();
            var before = top.get(top.indexOf(moov) - 1);
            assertEquals("mdat", before.type());
            var track = assertChapterTrack(data);
            var stco = Mp4Atoms.first(data, track, "mdia/minf/stbl/stco");
            assertNotNull(stco);
            assertEquals(before.payload(), Mp4Atoms.u32(data, stco.payload() + 8));
            // each sample: 16-bit length, UTF-8 title, then the 12-byte encd atom
            assertEquals(3, Mp4Atoms.u32(data, before.payload()) >>> 16);
            assertEquals("One", new String(data, before.payload() + 2, 3, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("encd", Mp4Atoms.type(data, before.payload() + 9));
            assertEquals(0x100, Mp4Atoms.u32(data, before.payload() + 13));
        }

        @Test
        @DisplayName("edits damaged files with chapter tracks without failing other than with MediaException")
        void robustness() {
            var base = MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"));
            var edited = TagEditor.write(Fixtures.read(base), tags(CHAPTERS), TagWriteOptions.defaults());
            var random = new Random(7);
            for (var round = 0; round < 300; round++) {
                var damaged = edited.clone();
                var moov = Mp4Atoms.top(damaged).stream().filter(box -> box.type().equals("moov")).findFirst().orElseThrow();
                for (var flip = 0; flip < 1 + random.nextInt(4); flip++) {
                    damaged[moov.offset() + random.nextInt(moov.size())] ^= (byte) (1 << random.nextInt(8));
                }
                if (round % 5 == 0) {
                    damaged = java.util.Arrays.copyOf(damaged, moov.offset() + random.nextInt(moov.size()));
                }
                var input = damaged;
                try {
                    var tags = TagEditor.read(MediaSource.of(input));
                    TagEditor.write(input, tags.toBuilder().chapters(List.of(Chapter.of(0, 10, "x"))).build(),
                            TagWriteOptions.defaults());
                    TagEditor.write(input, MediaTags.empty(), TagWriteOptions.defaults());
                } catch (com.github.auties00.smalt.MediaException expected) {
                    // a damaged file may be rejected, but only this way
                }
            }
        }
    }

    @Nested
    @DisplayName("Mp4Muxer")
    class Muxing {
        static Stream<Arguments> muxers() {
            Function<MediaSink, Muxer> fast = Mp4Muxer::new;
            Function<MediaSink, Muxer> progressive = sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().fastStart(false)
                    .build());
            Function<MediaSink, Muxer> m4a = sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder()
                    .brand(Mp4MuxerOptions.Brand.M4A).build());
            Function<MediaSink, Muxer> quickTime = sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder()
                    .brand(Mp4MuxerOptions.Brand.QUICKTIME).build());
            var audio = MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"));
            var video = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                    TagFixtures.AUDIO), List.of("-map_metadata", "-1", "-c:v", "libx264", "-preset", "ultrafast", "-c:a",
                    "aac", "-shortest"));
            return Stream.of(
                    Arguments.of("fast start", audio, fast),
                    Arguments.of("progressive", audio, progressive),
                    Arguments.of("m4a brand", audio, m4a),
                    Arguments.of("video fast start", video, fast),
                    Arguments.of("video quicktime", video, quickTime));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("muxers")
        @DisplayName("writes a chapter track that Smalt and ffprobe read without the chpl box")
        void writesTrack(String name, Path input, Function<MediaSink, Muxer> factory, @TempDir Path directory) {
            var output = MuxedTagsTest.remux(input, factory, tags(CHAPTERS));
            assertChapterTrack(output);
            var trackOnly = Mp4Atoms.withoutNeroChapters(output);
            assertChapters(CHAPTERS, TagEditor.read(MediaSource.of(trackOnly)).chapters());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                assertChapters(CHAPTERS, demuxer.chapters());
                var packets = 0;
                for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets++;
                }
                assertTrue(packets > 0);
            }
            var file = write(directory, "muxed.mp4", trackOnly);
            assertFfprobeChapters(CHAPTERS, file);
            assertEquals(TagFixtures.packetHash(input), TagFixtures.packetHash(file));
            Oracle.assertDecodesCleanly(file);
        }

        @Test
        @DisplayName("writes more chapters than chpl holds into the chapter track")
        void manyChapters(@TempDir Path directory) {
            var chapters = manyChapterList();
            var output = MuxedTagsTest.remux(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), Mp4Muxer::new,
                    tags(chapters));
            assertChapterTrack(output);
            assertChapters(chapters, TagEditor.read(MediaSource.of(output)).chapters());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
                assertChapters(chapters, demuxer.chapters());
            }
            assertFfprobeChapters(chapters, write(directory, "many.m4a", output));
        }

        @Test
        @DisplayName("writes no chapter track without chapters")
        void noChapters() {
            var output = MuxedTagsTest.remux(MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac")), Mp4Muxer::new,
                    tags(List.of()));
            assertEquals(1, Mp4Atoms.all(output, "moov/trak").size());
            assertTrue(Mp4Atoms.all(output, "moov/trak/tref").isEmpty());
        }
    }
}
