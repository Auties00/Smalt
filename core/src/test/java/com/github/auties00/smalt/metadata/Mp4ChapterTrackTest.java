package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.container.SegmentSink;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4MuxerOptions;
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

import java.io.ByteArrayOutputStream;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the QuickTime chapter tracks that {@link TagEditor} and {@link Mp4Muxer} write next to the Nero
 * {@code chpl} box, and those {@link FragmentedMp4Muxer} writes with their samples in the first fragment: the
 * chapters must read back through the track alone (the {@code chpl} box renamed
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
                // the track fragments of ffmpeg's chapter track in a fragmented file become free boxes, so the old
                // track is removed rather than left as a text track no chapter reference names
                assertEquals(1, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.DATA).count());
            }
            if (container.name().startsWith("fragmented")) {
                var chapterId = Mp4Atoms.trackId(data, assertChapterTrack(data));
                var ids = Mp4Atoms.all(data, "moov/trak").stream().map(trak -> Mp4Atoms.trackId(data, trak)).toList();
                for (var moof : Mp4Atoms.top(data).stream().filter(box -> box.type().equals("moof")).toList()) {
                    for (var traf : Mp4Atoms.all(data, moof, "traf")) {
                        var id = Mp4Atoms.u32(data, Mp4Atoms.first(data, traf, "tfhd").payload() + 4);
                        assertTrue(ids.contains(id) && id != chapterId, "a fragment names track " + id);
                    }
                }
                com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
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

    @Nested
    @DisplayName("FragmentedMp4Muxer")
    class FragmentedMuxing {
        static Path audio() {
            return MuxedTagsTest.untagged("m4a", List.of("-c:a", "aac"));
        }

        static Path video() {
            return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                    TagFixtures.AUDIO), List.of("-map_metadata", "-1", "-c:v", "libx264", "-preset", "ultrafast", "-c:a",
                    "aac", "-shortest"));
        }

        static Stream<Arguments> muxers() {
            Function<MediaSink, Muxer> plain = FragmentedMp4Muxer::new;
            Function<MediaSink, Muxer> single = sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder()
                    .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.SINGLE).build());
            Function<MediaSink, Muxer> indexed = sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder()
                    .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.PER_FRAGMENT).randomAccessIndex(true).build());
            Function<MediaSink, Muxer> shortFragments = sink -> new FragmentedMp4Muxer(sink,
                    FragmentedMp4MuxerOptions.builder().fragmentMicros(200_000).build());
            return Stream.of(
                    Arguments.of("audio", audio(), plain),
                    Arguments.of("audio, short fragments", audio(), shortFragments),
                    Arguments.of("video", video(), plain),
                    Arguments.of("video, single segment index", video(), single),
                    Arguments.of("video, segment index per fragment", video(), indexed));
        }

        /** Remuxes into a streaming sink, which the muxer cannot seek back into. */
        static byte[] remuxStreaming(Path input, MediaTags tags) {
            var bytes = new ByteArrayOutputStream();
            MuxedTagsTest.remux(input, sink -> new FragmentedMp4Muxer(MediaSink.of(bytes)), tags);
            return bytes.toByteArray();
        }

        /** Remuxes into one sink per segment and joins the segments, initialization segment first. */
        static byte[] remuxSegments(Path input, MediaTags tags, List<Segment.Kind> kinds) {
            var bytes = new ByteArrayOutputStream();
            SegmentSink segments = segment -> {
                kinds.add(segment.kind());
                return MediaSink.of(bytes);
            };
            MuxedTagsTest.remux(input, sink -> new FragmentedMp4Muxer(segments,
                    FragmentedMp4MuxerOptions.builder().fragmentMicros(300_000).keyframeAligned(false).build()), tags);
            return bytes.toByteArray();
        }

        /** Returns the track fragments of the chapter track, in file order, and checks they are in the first moof. */
        static List<Mp4Atoms.Box> chapterFragments(byte[] data, long chapterId) {
            var result = new ArrayList<Mp4Atoms.Box>();
            var moofs = Mp4Atoms.top(data).stream().filter(box -> box.type().equals("moof")).toList();
            assertFalse(moofs.isEmpty(), "no movie fragment");
            for (var index = 0; index < moofs.size(); index++) {
                for (var traf : Mp4Atoms.all(data, moofs.get(index), "traf")) {
                    var tfhd = Mp4Atoms.first(data, traf, "tfhd");
                    if (Mp4Atoms.u32(data, tfhd.payload() + 4) == chapterId) {
                        assertEquals(0, index, "the chapter samples are in the first fragment");
                        result.add(traf);
                    }
                }
            }
            return result;
        }

        /** Checks the chapter track fragment and returns the duration of each sample, in its media timescale. */
        static long[] assertChapterFragment(byte[] data, List<Chapter> chapters) {
            var track = assertChapterTrack(data);
            var id = Mp4Atoms.trackId(data, track);
            var stsz = Mp4Atoms.first(data, track, "mdia/minf/stbl/stsz");
            assertEquals(0, Mp4Atoms.u32(data, stsz.payload() + 8), "the moov lists no chapter sample");
            var mdhd = Mp4Atoms.first(data, track, "mdia/mdhd");
            var timescale = Mp4Atoms.u32(data, mdhd.payload() + 12);
            assertNull(Mp4Atoms.first(data, track, "edts"), "a fragmented chapter track needs no edit");
            var fragments = chapterFragments(data, id);
            assertEquals(1, fragments.size(), "one chapter track fragment");
            var traf = fragments.getFirst();
            var tfdt = Mp4Atoms.first(data, traf, "tfdt");
            assertEquals(1, data[tfdt.payload()] & 0xFF);
            assertEquals(Math.round(chapters.getFirst().startMicros() * timescale / 1e6),
                    Mp4Atoms.u64(data, tfdt.payload() + 4), "the decoding time starts at the first chapter");
            var trun = Mp4Atoms.first(data, traf, "trun");
            var count = (int) Mp4Atoms.u32(data, trun.payload() + 4);
            assertEquals(chapters.size(), count);
            var durations = new long[count];
            // tr_flags 0x701: a data offset, then the duration, size and flags of each sample
            assertEquals(0x701, Mp4Atoms.u32(data, trun.payload()) & 0xFFFFFF);
            var moof = Mp4Atoms.top(data).stream().filter(box -> box.offset() < traf.offset() && traf.end() <= box.end())
                    .findFirst().orElseThrow();
            var sample = moof.offset() + (int) Mp4Atoms.u32(data, trun.payload() + 8);
            for (var index = 0; index < count; index++) {
                var entry = trun.payload() + 12 + index * 12;
                durations[index] = Mp4Atoms.u32(data, entry);
                var size = (int) Mp4Atoms.u32(data, entry + 4);
                var title = chapters.get(index).title().orElse("").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertEquals(title.length, (int) (Mp4Atoms.u32(data, sample) >>> 16), "title length of sample " + index);
                assertArrayEquals(title, java.util.Arrays.copyOfRange(data, sample + 2, sample + 2 + title.length));
                assertEquals("encd", Mp4Atoms.type(data, sample + 2 + title.length + 4));
                sample += size;
            }
            for (var index = 0; index + 1 < count; index++) {
                assertEquals(Math.round((chapters.get(index + 1).startMicros() - chapters.get(index).startMicros())
                        * timescale / 1e6), durations[index], "duration of sample " + index);
            }
            return durations;
        }

        static long durationMicros(byte[] data) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                return demuxer.durationMicros();
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("muxers")
        @DisplayName("writes a chapter track in the first fragment that Smalt, ffprobe and MP4Box read without chpl")
        void writesTrack(String name, Path input, Function<MediaSink, Muxer> factory, @TempDir Path directory) {
            var output = MuxedTagsTest.remux(input, factory, tags(CHAPTERS));
            var durations = assertChapterFragment(output, CHAPTERS);
            // the declared end of the last chapter, bounded by the end of the two-second movie
            assertEquals(500, durations[2], 30);
            var trackOnly = Mp4Atoms.withoutNeroChapters(output);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                assertChapters(CHAPTERS, demuxer.chapters());
                assertEquals(1, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.DATA).count());
                var packets = 0;
                for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets++;
                }
                assertTrue(packets > 0);
            }
            var file = write(directory, "fragmented.mp4", trackOnly);
            assertFfprobeChapters(CHAPTERS, file);
            assertEquals(TagFixtures.packetHash(input), TagFixtures.packetHash(file));
            Oracle.assertDecodesCleanly(file);
            assertMp4BoxListsTrack(file, Mp4Atoms.trackId(output, assertChapterTrack(output)));
        }

        /** Runs MP4Box on a file and checks that it lists a track: the chapter track, whose samples are in fragments. */
        static void assertMp4BoxListsTrack(Path file, long trackId) {
            // GPAC publishes no pinnable Windows build, so only the Linux and macOS jobs run this oracle; the rest
            // of each test still runs on Windows instead of being skipped.
            if (Oracle.find(Oracle.Tool.MP4BOX).isEmpty()) {
                return;
            }
            var result = Oracle.execute(Oracle.Tool.MP4BOX, java.time.Duration.ofMinutes(1), null,
                    List.of("-info", file.toAbsolutePath().toString()));
            var output = new String(result.stdout(), java.nio.charset.StandardCharsets.UTF_8) + result.stderr();
            var listed = java.util.regex.Pattern.compile("Track.*\\bID " + trackId + "\\b").matcher(output).find();
            assertTrue(listed, output);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("muxers")
        @DisplayName("ends an open last chapter at the movie end on a seekable output")
        void openLastChapterSeekable(String name, Path input, Function<MediaSink, Muxer> factory,
                                     @TempDir Path directory) {
            var chapters = List.of(Chapter.of(0, 600_000, "One"), Chapter.of(600_000, 600_000, "Rest"));
            var output = MuxedTagsTest.remux(input, factory, tags(chapters));
            // the patched duration replaces the zero written in the fragment: a patch at the wrong offset would
            // leave it zero or corrupt another field
            var durations = assertChapterFragment(output, chapters);
            var duration = durationMicros(output);
            assertTrue(durations[1] > 0, "the open end is patched");
            assertEquals(Math.round((duration - 600_000) / 1000.0), durations[1], 1);
            var trackOnly = Mp4Atoms.withoutNeroChapters(output);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                var read = demuxer.chapters();
                assertEquals(2, read.size());
                assertEquals(duration, read.get(1).endMicros(), 1000);
            }
            var file = write(directory, "open-end.mp4", trackOnly);
            com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
            assertEquals(TagFixtures.packetHash(input), TagFixtures.packetHash(file));
        }

        @Test
        @DisplayName("bounds a last chapter that ends after the movie by the movie end on a seekable output")
        void longLastChapter() {
            var chapters = List.of(Chapter.of(0, 600_000, "One"), Chapter.of(600_000, 900_000_000, "Long"));
            var output = MuxedTagsTest.remux(audio(), FragmentedMp4Muxer::new, tags(chapters));
            var durations = assertChapterFragment(output, chapters);
            assertEquals(Math.round((durationMicros(output) - 600_000) / 1000.0), durations[1], 1);
        }

        @Test
        @DisplayName("gives an open last chapter no duration on a streaming output, which reads back to the movie end")
        void openLastChapterStreaming(@TempDir Path directory) {
            var chapters = List.of(Chapter.of(0, 600_000, "One"), Chapter.of(600_000, 600_000, "Rest"));
            var output = remuxStreaming(audio(), tags(chapters));
            var durations = assertChapterFragment(output, chapters);
            assertEquals(0, durations[1]);
            var trackOnly = Mp4Atoms.withoutNeroChapters(output);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                var read = demuxer.chapters();
                assertEquals(List.of(0L, 600_000L), read.stream().map(Chapter::startMicros).toList());
                assertEquals(600_000, read.get(0).endMicros());
                assertEquals(demuxer.durationMicros(), read.get(1).endMicros());
            }
            Oracle.assertDecodesCleanly(write(directory, "streamed.m4a", trackOnly));
        }

        @Test
        @DisplayName("keeps a declared last end on a streaming output")
        void declaredEndStreaming() {
            var output = remuxStreaming(audio(), tags(CHAPTERS));
            var durations = assertChapterFragment(output, CHAPTERS);
            assertEquals(500, durations[2]);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(Mp4Atoms.withoutNeroChapters(output)))) {
                assertEquals(CHAPTERS, demuxer.chapters());
            }
        }

        @Test
        @DisplayName("puts the chapter track in the first media segment of a segmented output")
        void segments() {
            var kinds = new ArrayList<Segment.Kind>();
            var output = remuxSegments(video(), tags(CHAPTERS), kinds);
            assertEquals(Segment.Kind.INIT, kinds.getFirst());
            assertTrue(kinds.size() > 2, "several media segments: " + kinds);
            assertChapterFragment(output, CHAPTERS);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(Mp4Atoms.withoutNeroChapters(output)))) {
                assertEquals(CHAPTERS, demuxer.chapters());
            }
        }

        @Test
        @DisplayName("starts the chapter fragment at a late first chapter")
        void lateFirstChapter(@TempDir Path directory) {
            var chapters = List.of(Chapter.of(500_000, 1_200_000, "Late"), Chapter.of(1_200_000, 1_800_000, "Later"));
            var output = MuxedTagsTest.remux(audio(), FragmentedMp4Muxer::new, tags(chapters));
            assertChapterFragment(output, chapters);
            var trackOnly = Mp4Atoms.withoutNeroChapters(output);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                assertEquals(chapters, demuxer.chapters());
            }
            assertFfprobeChapters(chapters, write(directory, "late.m4a", trackOnly));
        }

        @Test
        @DisplayName("counts microsecond boundaries in a microsecond timescale")
        void microsecondTimescale() {
            var chapters = List.of(Chapter.of(0, 333_333, "A"), Chapter.of(333_333, 1_000_001, null),
                    Chapter.of(1_000_001, 1_900_000, "é".repeat(400)));
            var output = MuxedTagsTest.remux(audio(), FragmentedMp4Muxer::new, tags(chapters));
            var track = assertChapterTrack(output);
            assertEquals(1_000_000, Mp4Atoms.u32(output, Mp4Atoms.first(output, track, "mdia/mdhd").payload() + 12));
            assertChapterFragment(output, chapters);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
                assertEquals(chapters, demuxer.chapters(), "the track completes the title chpl cuts");
            }
        }

        @Test
        @DisplayName("writes more chapters than chpl holds into the chapter track")
        void manyChapters(@TempDir Path directory) {
            var chapters = manyChapterList();
            var output = MuxedTagsTest.remux(audio(), FragmentedMp4Muxer::new, tags(chapters));
            assertChapterFragment(output, chapters);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
                assertChapters(chapters, demuxer.chapters());
            }
            assertFfprobeChapters(chapters, write(directory, "many.m4a", output));
        }

        @Test
        @DisplayName("numbers the timecode track after the chapter track")
        void withTimecode(@TempDir Path directory) {
            var tags = tags(CHAPTERS).toBuilder().set("timecode", "01:00:00:00").build();
            var output = MuxedTagsTest.remux(video(), FragmentedMp4Muxer::new, tags);
            var chapterId = Mp4Atoms.trackId(output, assertChapterTrack(output));
            var traks = Mp4Atoms.all(output, "moov/trak");
            assertEquals(traks.size() - 1, chapterId, "the chapter track follows the declared tracks");
            var timecode = traks.stream().filter(trak -> Mp4Atoms.handler(output, trak).equals("tmcd")).findFirst()
                    .orElseThrow();
            assertEquals(chapterId + 1, Mp4Atoms.trackId(output, timecode));
            for (var trak : traks) {
                if (Mp4Atoms.handler(output, trak).equals("vide")) {
                    assertEquals(List.of(chapterId + 1), Mp4Atoms.all(output, trak, "tref/tmcd").stream()
                            .map(box -> Mp4Atoms.u32(output, box.payload())).toList());
                }
            }
            assertChapterFragment(output, CHAPTERS);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(Mp4Atoms.withoutNeroChapters(output)))) {
                assertChapters(CHAPTERS, demuxer.chapters());
                assertEquals("01:00:00:00", demuxer.timecode().orElseThrow().toString());
            }
            Oracle.assertDecodesCleanly(write(directory, "timecode.mp4", output));
        }

        @Test
        @DisplayName("writes no chapter track in a CMAF track file or without chapters")
        void noTrack() {
            var cmaf = MuxedTagsTest.remux(audio(), sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions
                    .builder().brand(FragmentedMp4MuxerOptions.Brand.CMAF).build()), tags(CHAPTERS));
            assertEquals(1, Mp4Atoms.all(cmaf, "moov/trak").size());
            assertTrue(Mp4Atoms.all(cmaf, "moov/trak/tref").isEmpty());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(cmaf))) {
                assertEquals(CHAPTERS, demuxer.chapters(), "the chapters stay in chpl");
            }
            var plain = MuxedTagsTest.remux(audio(), FragmentedMp4Muxer::new, tags(List.of()));
            assertEquals(1, Mp4Atoms.all(plain, "moov/trak").size());
            assertEquals(1, Mp4Atoms.all(plain, "moov/mvex/trex").size());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("muxers")
        @DisplayName("lets TagEditor replace the chapters of its output, freeing the old chapter track's fragment")
        void tagEditor(String name, Path input, Function<MediaSink, Muxer> factory, @TempDir Path directory) {
            var output = MuxedTagsTest.remux(input, factory, tags(CHAPTERS));
            var oldId = Mp4Atoms.trackId(output, assertChapterTrack(output));
            var traks = Mp4Atoms.all(output, "moov/trak").size();
            var other = List.of(Chapter.of(0, 900_000, "Uno"), Chapter.of(900_000, 1_300_000, "Dos"),
                    Chapter.of(1_300_000, 2_000_000, "Tres"));
            var edited = TagEditor.write(output, tags(other), TagWriteOptions.defaults());
            assertChapterTrack(edited);
            assertEquals(traks, Mp4Atoms.all(edited, "moov/trak").size(), "the old chapter track is replaced");
            assertTrue(Mp4Atoms.all(edited, "moov/trak").stream().noneMatch(trak -> Mp4Atoms.trackId(edited, trak)
                    == oldId), "the old chapter track is removed");
            assertTrue(Mp4Atoms.all(edited, "moov/mvex/trex").stream().noneMatch(trex -> Mp4Atoms.u32(edited,
                    trex.payload() + 4) == oldId), "the old chapter track's trex is removed");
            for (var moof : Mp4Atoms.top(edited).stream().filter(box -> box.type().equals("moof")).toList()) {
                for (var traf : Mp4Atoms.all(edited, moof, "traf")) {
                    var tfhd = Mp4Atoms.first(edited, traf, "tfhd");
                    assertTrue(Mp4Atoms.u32(edited, tfhd.payload() + 4) != oldId, "a fragment names the old track");
                }
            }
            var trackOnly = Mp4Atoms.withoutNeroChapters(edited);
            assertChapters(other, TagEditor.read(MediaSource.of(trackOnly)).chapters());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(trackOnly))) {
                assertChapters(other, demuxer.chapters());
                assertEquals(1, demuxer.tracks().stream().filter(track -> track.kind() == TrackKind.DATA).count(),
                        demuxer.tracks().toString());
            }
            var file = write(directory, "edited.mp4", edited);
            com.github.auties00.smalt.testkit.validate.Validators.assertValid(file);
            assertEquals(TagFixtures.packetHash(input), TagFixtures.packetHash(file));
            assertFfprobeChapters(other, write(directory, "edited-track-only.mp4", trackOnly));
            Oracle.assertDecodesCleanly(file);
        }
    }
}
