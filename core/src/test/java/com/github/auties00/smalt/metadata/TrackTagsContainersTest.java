package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.auties00.smalt.track.TrackDisposition.CAPTIONS;
import static com.github.auties00.smalt.track.TrackDisposition.COMMENTARY;
import static com.github.auties00.smalt.track.TrackDisposition.DEFAULT;
import static com.github.auties00.smalt.track.TrackDisposition.DUB;
import static com.github.auties00.smalt.track.TrackDisposition.FORCED;
import static com.github.auties00.smalt.track.TrackDisposition.HEARING_IMPAIRED;
import static com.github.auties00.smalt.track.TrackDisposition.ORIGINAL;
import static com.github.auties00.smalt.track.TrackDisposition.VISUAL_IMPAIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the names, tags and dispositions of tracks through every muxer that stores them (MP4, fragmented MP4,
 * Matroska, AVI, MPEG-TS) and edits them with {@link TagEditor} in MP4 and Matroska files, with ffprobe and
 * exiftool as the independent readers and Smalt's demuxers and {@code Media.probe} reading them back.
 */
@DisplayName("Track tags")
class TrackTagsContainersTest {
    /*
     * The track names are at least four bytes long: the MP4Box validator reads the udta name box as a full box,
     * while QuickTime, ffmpeg and exiftool (and Smalt) read it as plain text, so a shorter name fails its parse.
     */

    /** A video track and two audio tracks, ids 1 to 3. */
    static Path fixture() {
        return Fixtures.ffmpeg("mkv", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i", TagFixtures.AUDIO,
                        "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100:duration=2"),
                List.of("-map", "0:v", "-map", "1:a", "-map", "2:a", "-shortest", "-c:v", "libx264", "-preset", "ultrafast",
                        "-c:a", "aac", "-b:a", "64k"));
    }

    static MediaTags written() {
        return MediaTags.builder().set(StandardTag.TITLE, "Movie")
                .track(new TrackTags(1, MediaTags.builder().set(StandardTag.TITLE, "Main Video")
                        .set(StandardTag.COMMENT, "video comment").build(), Set.of(DEFAULT)))
                .track(TrackTags.of(2, "English Audio", DEFAULT, ORIGINAL))
                .track(TrackTags.of(3, "Commentary \u00E8", COMMENTARY, HEARING_IMPAIRED))
                .build();
    }

    /** A muxer under test and the dispositions its container keeps of {@link #written()}. */
    record Case(String name, String extension, Function<MediaSink, Muxer> factory, List<Set<TrackDisposition>> kept,
                boolean otherTags) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> muxers() {
        var mp4 = List.<Set<TrackDisposition>>of(Set.of(DEFAULT), Set.of(DEFAULT),
                Set.of(COMMENTARY, HEARING_IMPAIRED, CAPTIONS));
        return Stream.of(
                new Case("mp4", "mp4", Mp4Muxer::new, mp4, true),
                new Case("fragmented mp4", "mp4", FragmentedMp4Muxer::new, mp4, true),
                new Case("matroska", "mkv", MatroskaMuxer::new, List.of(Set.of(DEFAULT), Set.of(DEFAULT, ORIGINAL),
                        Set.of(COMMENTARY, HEARING_IMPAIRED)), true),
                new Case("avi", "avi", AviMuxer::new, List.of(Set.of(), Set.of(), Set.of()), false),
                new Case("mpeg-ts", "ts", TsMuxer::new, List.of(Set.of(), Set.of(), Set.of(HEARING_IMPAIRED)), false)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("writes the names, tags and dispositions of the tracks that the demuxer and ffprobe read back")
    void muxes(Case muxer) {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        var tags = written();
        var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var created = muxer.factory().apply(sink);
            assertTrue(created.supportsTags());
            created.setTags(tags);
            return created;
        });
        var names = List.of("Main Video", "English Audio", "Commentary \u00E8");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(output))) {
            var tracks = demuxer.tracks();
            assertEquals(3, tracks.size());
            for (var index = 0; index < 3; index++) {
                var id = tracks.get(index).id();
                assertEquals(names.get(index), demuxer.trackMetadata(id).get("title"), muxer + " track " + id);
                assertEquals(muxer.kept().get(index), demuxer.trackDisposition(id), muxer + " track " + id);
            }
            if (muxer.otherTags()) {
                var videoTags = demuxer.trackMetadata(tracks.getFirst().id());
                assertTrue(videoTags.entrySet().stream().anyMatch(entry -> StandardTag.normalize(entry.getKey())
                        .equals(StandardTag.COMMENT.key()) && entry.getValue().equals("video comment")), videoTags.toString());
            }
        }
        var probed = Media.probe(MediaSource.of(output));
        for (var index = 0; index < 3; index++) {
            assertEquals(names.get(index), probed.streams().get(index).tags().get("title"));
            assertEquals(muxer.kept().get(index), probed.streams().get(index).disposition());
        }
        var demuxed = MuxSupport.demux(output);
        // AVI and MPEG-TS carry H.264 as Annex B with the parameter sets in front of key frames, so only the audio
        // payloads are compared there
        var compared = muxer.name().equals("avi") || muxer.name().equals("mpeg-ts")
                ? Map.of(2, demuxed.tracks().get(1).id(), 3, demuxed.tracks().get(2).id())
                : Map.of(1, demuxed.tracks().get(0).id(), 2, demuxed.tracks().get(1).id(), 3, demuxed.tracks().get(2).id());
        MuxSupport.assertSamePayloads(source, demuxed, compared);
        var file = MuxSupport.save("track-tags." + muxer.extension(), output);
        var probe = TagFixtures.ffprobe(file);
        switch (muxer.name()) {
            case "matroska" -> {
                assertEquals("Commentary \u00E8", probe.get("streams.stream.2.tags.title"));
                assertDisposition(probe, 1, Map.of("default", "1", "original", "1", "comment", "0"));
                assertDisposition(probe, 2, Map.of("default", "0", "comment", "1", "hearing_impaired", "1"));
            }
            case "mp4", "fragmented mp4" -> {
                assertDisposition(probe, 1, Map.of("default", "1", "comment", "0"));
                assertDisposition(probe, 2, Map.of("default", "0", "comment", "1", "hearing_impaired", "1",
                        "captions", "1"));
                var exif = exiftool(file);
                assertTrue(exif.contains("\"Track3Name\": \"Commentary \u00E8\""), exif);
                assertTrue(exif.contains("\"Track1Name\": \"Main Video\""), exif);
            }
            case "avi" -> assertEquals("English Audio", probe.get("streams.stream.1.tags.title"));
            case "mpeg-ts" -> {
                assertDisposition(probe, 2, Map.of("hearing_impaired", "1"));
                assertTrue(TagVocabularyTest.indexOf(output, componentName("Commentary \u00E8")) > 0);
            }
            default -> throw new AssertionError(muxer.name());
        }
    }

    @Test
    @DisplayName("writes each disposition MP4 and Matroska store, as ffprobe reads it")
    void dispositions() {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        var tags = MediaTags.builder()
                .track(TrackTags.of(2, "Dubbed audio", DEFAULT, DUB, FORCED))
                .track(TrackTags.of(3, "Described", VISUAL_IMPAIRED, TrackDisposition.DESCRIPTIONS,
                        TrackDisposition.KARAOKE))
                .build();
        var mp4 = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new Mp4Muxer(sink);
            muxer.setTags(tags);
            return muxer;
        });
        var mp4Probe = TagFixtures.ffprobe(MuxSupport.save("dispositions.mp4", mp4));
        assertDisposition(mp4Probe, 1, Map.of("default", "1", "dub", "1", "forced", "1"));
        assertDisposition(mp4Probe, 2, Map.of("default", "0", "visual_impaired", "1", "descriptions", "1"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mp4))) {
            assertEquals(EnumSet.of(DEFAULT, DUB, FORCED), demuxer.trackDisposition(2));
            assertEquals(EnumSet.of(VISUAL_IMPAIRED, TrackDisposition.DESCRIPTIONS, TrackDisposition.KARAOKE),
                    demuxer.trackDisposition(3));
        }
        var mkv = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new MatroskaMuxer(sink);
            muxer.setTags(tags);
            return muxer;
        });
        var mkvProbe = TagFixtures.ffprobe(MuxSupport.save("dispositions.mkv", mkv));
        assertDisposition(mkvProbe, 1, Map.of("default", "1", "forced", "1"));
        assertDisposition(mkvProbe, 2, Map.of("default", "0", "visual_impaired", "1"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mkv))) {
            assertEquals(EnumSet.of(DEFAULT, FORCED), demuxer.trackDisposition(2));
            assertEquals(EnumSet.of(VISUAL_IMPAIRED, TrackDisposition.DESCRIPTIONS), demuxer.trackDisposition(3));
            assertEquals(EnumSet.of(DEFAULT), demuxer.trackDisposition(1), "the first track of its kind keeps its default");
        }
    }

    @Test
    @DisplayName("keeps one enabled audio track in an MP4 file when no audio track is the default")
    void enabledAudio() {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        var tags = MediaTags.builder().track(TrackTags.of(2, "Audio A")).track(TrackTags.of(3, "Audio B")).build();
        var mp4 = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new Mp4Muxer(sink);
            muxer.setTags(tags);
            return muxer;
        });
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mp4))) {
            assertEquals(Set.of(DEFAULT), demuxer.trackDisposition(1), "a track without tags stays enabled");
            assertEquals(Set.of(DEFAULT), demuxer.trackDisposition(2), "the first audio track stays enabled");
            assertEquals(Set.of(), demuxer.trackDisposition(3));
        }
    }

    @Test
    @DisplayName("edits the names, tags and flags of the tracks of a Matroska file, keeping the others")
    void editsMatroska(@TempDir Path directory) {
        var file = TagFixtures.copy(fixture(), directory);
        var hash = TagFixtures.packetHash(file);
        var read = TagEditor.read(file);
        assertEquals(3, read.tracks().size());
        assertEquals(Set.of(DEFAULT), read.track(2).orElseThrow().disposition());
        assertTrue(read.track(2).orElseThrow().tags().get("DURATION").isPresent(), read.toString());
        var before = TagFixtures.ffprobeStreamTags(file, 0);
        var renamed = MediaTags.builder().set(StandardTag.TITLE, "Director \u00E8");
        read.track(3).orElseThrow().tags().entries().forEach(entry -> renamed.add(entry.key(), entry.value()));
        renamed.set(StandardTag.LANGUAGE, "ita").set(StandardTag.COMMENT, "notes");
        var commentary = new TrackTags(3, renamed.build(), Set.of(COMMENTARY, VISUAL_IMPAIRED));
        var edited = read.toBuilder().track(commentary).build();
        TagEditor.write(file, edited);
        assertEquals(hash, TagFixtures.packetHash(file));
        var again = TagEditor.read(file);
        assertEquals(commentary, again.track(3).orElseThrow());
        assertEquals(read.track(1), again.track(1));
        assertEquals(read.track(2), again.track(2));
        var probe = TagFixtures.ffprobe(file);
        assertEquals("Director \u00E8", probe.get("streams.stream.2.tags.title"));
        assertEquals("notes", probe.get("streams.stream.2.tags.COMMENT"));
        assertDisposition(probe, 2, Map.of("default", "0", "comment", "1", "visual_impaired", "1"));
        assertDisposition(probe, 1, Map.of("default", "1"));
        assertEquals(before, TagFixtures.ffprobeStreamTags(file, 0), "an unedited track keeps its tags");
        assertEquals(Set.of(COMMENTARY, VISUAL_IMPAIRED), Media.probe(MediaSource.of(Fixtures.read(file))).streams().get(2)
                .disposition());
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, again));
        Oracle.assertDecodesCleanly(file);
    }

    @Test
    @DisplayName("edits the names, tags, roles and enabled flags of the tracks of an MP4 file")
    void editsMp4(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(Fixtures.ffmpeg("mp4", List.of("-i", fixture().toString()),
                List.of("-map", "0", "-c", "copy")), directory);
        var hash = TagFixtures.packetHash(file);
        var read = TagEditor.read(file);
        assertEquals(List.of(1, 2, 3), read.tracks().stream().map(TrackTags::trackId).toList());
        assertTrue(read.tracks().stream().allMatch(track -> track.name().isEmpty()), read.toString());
        assertEquals(Set.of(DEFAULT), read.track(1).orElseThrow().disposition());
        var edited = read.toBuilder()
                .track(new TrackTags(2, MediaTags.builder().set(StandardTag.TITLE, "Dubbed").set(StandardTag.COMMENT, "c")
                        .build(), Set.of(DEFAULT, DUB)))
                .track(TrackTags.of(3, "Commentary", COMMENTARY, CAPTIONS, HEARING_IMPAIRED))
                .build();
        TagEditor.write(file, edited);
        assertEquals(hash, TagFixtures.packetHash(file));
        var again = TagEditor.read(file);
        assertEquals(edited.tracks(), again.tracks());
        var probe = TagFixtures.ffprobe(file);
        assertDisposition(probe, 1, Map.of("default", "1", "dub", "1"));
        assertDisposition(probe, 2, Map.of("default", "0", "comment", "1", "captions", "1", "hearing_impaired", "1"));
        var exif = exiftool(file);
        assertTrue(exif.contains("\"Track2Name\": \"Dubbed\"") && exif.contains("\"Track3Name\": \"Commentary\""), exif);
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, again));
        TagEditor.write(file, again.toBuilder().track(TrackTags.of(3)).track(TrackTags.of(2)).build());
        var cleared = TagEditor.read(file);
        assertEquals(List.of(new TrackTags(2, MediaTags.empty(), Set.of(DEFAULT)), TrackTags.of(3)),
                cleared.tracks().subList(1, 3), "the first audio track stays enabled when no audio track is the default");
        assertTrue(Files.size(file) > 0);
        Oracle.assertDecodesCleanly(file);
    }

    @Test
    @DisplayName("keeps the tags of the other Matroska tracks when unknown items are dropped")
    void matroskaTrackTagsWithoutUnknown(@TempDir Path directory) {
        var file = TagFixtures.copy(fixture(), directory);
        var read = TagEditor.read(file);
        assertTrue(read.track(2).orElseThrow().tags().get("DURATION").isPresent(), read.toString());
        var options = TagWriteOptions.builder().preserveUnknown(false).build();
        TagEditor.write(file, read.toBuilder().set(StandardTag.ARTIST, "Someone").build(), options);
        var global = TagEditor.read(file);
        assertEquals(read.tracks(), global.tracks(), "a global edit keeps every track's tags");
        var renamed = global.track(3).orElseThrow().withName("Renamed");
        TagEditor.write(file, global.toBuilder().track(renamed).build(), options);
        var again = TagEditor.read(file);
        assertEquals(renamed, again.track(3).orElseThrow());
        assertEquals(read.track(1), again.track(1), "an unlisted track keeps its tags");
        assertEquals(read.track(2), again.track(2), "an unchanged track keeps its tags");
    }

    @Test
    @DisplayName("keeps the kind boxes of other schemes and roles when the tags of an MP4 track change")
    void mp4ForeignKinds(@TempDir Path directory) throws Exception {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        var mp4 = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new Mp4Muxer(sink);
            muxer.setTags(MediaTags.builder().track(TrackTags.of(2, "Dubbed", DEFAULT, DUB))
                    .track(TrackTags.of(3, "Other", COMMENTARY)).build());
            return muxer;
        });
        // the kind box of track 2 moves to a scheme Smalt does not map, one of the same length
        var role = ("urn:mpeg:dash:role:2011" + "\u0000dub\u0000").getBytes(StandardCharsets.US_ASCII);
        var foreign = "urn:mpeg:dash:role:2099\u0000dub\u0000".getBytes(StandardCharsets.US_ASCII);
        var at = TagVocabularyTest.indexOf(mp4, role);
        assertTrue(at > 0);
        System.arraycopy(foreign, 0, mp4, at, foreign.length);
        var file = directory.resolve("kinds.mp4");
        Files.write(file, mp4);
        var read = TagEditor.read(file);
        assertEquals(Set.of(DEFAULT), read.track(2).orElseThrow().disposition());
        TagEditor.write(file, read.toBuilder().track(read.track(2).orElseThrow().withName("Renamed"))
                .track(read.track(3).orElseThrow().withDisposition(Set.of(DUB))).build());
        var bytes = Files.readAllBytes(file);
        assertTrue(TagVocabularyTest.indexOf(bytes, foreign) > 0, "a kind box of another scheme is kept");
        assertTrue(TagVocabularyTest.indexOf(bytes, ("urn:mpeg:dash:role:2011" + "\u0000commentary\u0000")
                .getBytes(StandardCharsets.US_ASCII)) < 0, "a mapped role is replaced");
        var again = TagEditor.read(file);
        assertEquals("Renamed", again.track(2).orElseThrow().name().orElseThrow());
        assertEquals(Set.of(DUB), again.track(3).orElseThrow().disposition());
    }

    static void assertDisposition(Map<String, String> probe, int stream, Map<String, String> expected) {
        for (var entry : expected.entrySet()) {
            assertEquals(entry.getValue(), probe.get("streams.stream." + stream + ".disposition." + entry.getKey()),
                    "disposition " + entry.getKey() + " of stream " + stream);
        }
    }

    static String exiftool(Path file) {
        Oracle.assume(Oracle.Tool.EXIFTOOL);
        return new String(Oracle.run(Oracle.Tool.EXIFTOOL, Duration.ofMinutes(1), List.of("-j", file.toString())),
                StandardCharsets.UTF_8);
    }

    /** The ATSC component name descriptor of a name, as the transport stream muxer writes it. */
    static byte[] componentName(String name) {
        var latin = name.getBytes(StandardCharsets.ISO_8859_1);
        var out = new java.io.ByteArrayOutputStream();
        out.write(0xA3);
        out.write(8 + latin.length);
        out.write(1);
        out.writeBytes("und".getBytes(StandardCharsets.US_ASCII));
        out.write(1);
        out.write(0);
        out.write(0);
        out.write(latin.length);
        out.writeBytes(latin);
        return out.toByteArray();
    }
}
