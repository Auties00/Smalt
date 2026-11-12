package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.ts.TsDemuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.internal.tags.TsDescriptors;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.github.auties00.smalt.track.TrackDisposition.CLEAN_EFFECTS;
import static com.github.auties00.smalt.track.TrackDisposition.HEARING_IMPAIRED;
import static com.github.auties00.smalt.track.TrackDisposition.VISUAL_IMPAIRED;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the track names and audio types of MPEG transport streams with {@link TagEditor}: streams written by
 * {@link TsMuxer} and by ffmpeg (188 and 192-byte packets), checked by the demuxer, ffprobe and the bytes of every
 * packet that is not a program map packet, plus the layouts the in-place edit rejects and the descriptor codec.
 */
@DisplayName("MPEG-TS track tags")
class TsTrackTagsTest {
    /** An H.264 track and two AAC tracks. */
    static Path ffmpegFixture(List<String> extra) {
        var output = new java.util.ArrayList<>(List.of("-map", "0:v", "-map", "1:a", "-map", "2:a", "-shortest",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "64k",
                "-metadata:s:a:0", "language=eng", "-f", "mpegts"));
        output.addAll(extra);
        return Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i",
                TagFixtures.AUDIO, "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=44100:duration=2"), output);
    }

    /** The same streams remuxed by {@link TsMuxer} with names on the first two tracks. */
    static byte[] smaltFixture() {
        var source = MuxSupport.demux(Fixtures.read(ffmpegFixture(List.of())));
        var ids = source.tracks().stream().map(track -> track.id()).toList();
        return MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new TsMuxer(sink);
            muxer.setTags(MediaTags.builder().track(TrackTags.of(ids.get(0), "Main Video"))
                    .track(TrackTags.of(ids.get(1), "English", HEARING_IMPAIRED)).build());
            return muxer;
        });
    }

    @Test
    @DisplayName("renames tracks and changes audio types in place, leaving every other packet unchanged")
    void editsSmaltStream(@TempDir Path directory) throws Exception {
        var file = directory.resolve("named.ts");
        Files.write(file, smaltFixture());
        var before = Files.readAllBytes(file);
        var hash = TagFixtures.packetHash(file);
        var read = TagEditor.read(file);
        assertEquals(3, read.tracks().size(), read.toString());
        var video = read.tracks().get(0);
        var first = read.tracks().get(1);
        var second = read.tracks().get(2);
        assertEquals("Main Video", video.name().orElseThrow());
        assertEquals(Set.of(HEARING_IMPAIRED), first.disposition());
        assertTrue(second.name().isEmpty() && second.disposition().isEmpty());
        var edited = read.toBuilder()
                .track(first.withName("Director è").withDisposition(Set.of(VISUAL_IMPAIRED)))
                .track(second.withName("Effects").withDisposition(Set.of(CLEAN_EFFECTS)))
                .track(video.withName(null))
                .build();
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, edited));
        var after = Files.readAllBytes(file);
        assertEquals(before.length, after.length);
        assertOnlyPmtChanged(before, after, 188, pmtPid(after));
        assertEquals(hash, TagFixtures.packetHash(file));
        var again = TagEditor.read(file);
        assertEquals(edited.tracks(), again.tracks());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(after))) {
            assertEquals("Director è", demuxer.trackMetadata(first.trackId()).get("title"));
            assertEquals(Set.of(CLEAN_EFFECTS), demuxer.trackDisposition(second.trackId()));
            assertTrue(demuxer.trackMetadata(video.trackId()).isEmpty());
        }
        var probe = TagFixtures.ffprobe(file);
        TrackTagsContainersTest.assertDisposition(probe, 1, Map.of("visual_impaired", "1", "hearing_impaired", "0"));
        TrackTagsContainersTest.assertDisposition(probe, 2, Map.of("clean_effects", "1"));
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, again));
        Oracle.assertDecodesCleanly(file);
    }

    @ParameterizedTest(name = "{0}-byte packets")
    @ValueSource(ints = {188, 192})
    @DisplayName("edits a stream ffmpeg wrote, keeping its language descriptor and its packet layout")
    void editsFfmpegStream(int packetSize, @TempDir Path directory) throws Exception {
        var fixture = ffmpegFixture(packetSize == 192 ? List.of("-mpegts_m2ts_mode", "1") : List.of());
        var file = TagFixtures.copy(fixture, directory);
        var before = Files.readAllBytes(file);
        try (var demuxer = new TsDemuxer(MediaSource.of(before))) {
            assertEquals(packetSize, demuxer.packetSize());
        }
        var read = TagEditor.read(file);
        var audio = read.tracks().get(1);
        var edited = read.toBuilder().track(audio.withName("English commentary")
                .withDisposition(Set.of(HEARING_IMPAIRED))).track(read.tracks().get(2).withName("Second")).build();
        TagEditor.write(file, edited);
        var after = Files.readAllBytes(file);
        assertOnlyPmtChanged(before, after, packetSize, pmtPid(after));
        var again = TagEditor.read(file);
        assertEquals(edited.tracks(), again.tracks());
        var probe = TagFixtures.ffprobe(file);
        assertEquals("eng", probe.get("streams.stream.1.tags.language"), "the language descriptor is kept");
        TrackTagsContainersTest.assertDisposition(probe, 1, Map.of("hearing_impaired", "1"));
        assertTrue(TagVocabularyTest.indexOf(after, TsDescriptors.componentName("eng", "English commentary")) > 0);
        Oracle.assertDecodesCleanly(file);
    }

    @Test
    @DisplayName("writes a name outside ISO-8859-1 as UTF-16")
    void unicodeName(@TempDir Path directory) throws Exception {
        var file = directory.resolve("named.ts");
        Files.write(file, smaltFixture());
        var read = TagEditor.read(file);
        var renamed = read.tracks().get(2).withName("中文 🎵");
        TagEditor.write(file, read.toBuilder().track(renamed).build());
        assertEquals(renamed, TagEditor.read(file).track(renamed.trackId()).orElseThrow());
        var utf16 = "中文".getBytes(StandardCharsets.UTF_16BE);
        assertTrue(TagVocabularyTest.indexOf(Files.readAllBytes(file), utf16) > 0);
    }

    @Test
    @DisplayName("rejects tags for the whole file, which a transport stream cannot store")
    void rejectsGlobalTags(@TempDir Path directory) throws Exception {
        var file = directory.resolve("named.ts");
        Files.write(file, smaltFixture());
        var before = Files.readAllBytes(file);
        var read = TagEditor.read(file);
        assertTrue(read.entries().isEmpty() && read.pictures().isEmpty() && read.chapters().isEmpty());
        assertThrows(MediaException.Unsupported.class,
                () -> TagEditor.write(file, read.toBuilder().set(StandardTag.TITLE, "Movie").build()));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, read));
    }

    @Test
    @DisplayName("rejects names that make the program map section outgrow its packet, leaving the file unchanged")
    void rejectsOverflow(@TempDir Path directory) throws Exception {
        var file = directory.resolve("named.ts");
        Files.write(file, smaltFixture());
        var before = Files.readAllBytes(file);
        var read = TagEditor.read(file);
        var builder = read.toBuilder();
        for (var track : read.tracks()) {
            builder.track(track.withName("n".repeat(200)));
        }
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(file, builder.build()));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("rejects a program map section that does not start its packet payload")
    void rejectsSharedPacket(@TempDir Path directory) throws Exception {
        var data = smaltFixture();
        var pid = pmtPid(data);
        for (var packet = 0; packet + 188 <= data.length; packet += 188) {
            if (pidOf(data, packet) == pid && (data[packet + 1] & 0x40) != 0) {
                var payload = (data[packet + 3] & 0x20) != 0 ? packet + 5 + (data[packet + 4] & 0xFF) : packet + 4;
                // a pointer_field of 1: another section's last byte would precede this one
                data[payload] = 1;
                break;
            }
        }
        var file = directory.resolve("shared.ts");
        Files.write(file, data);
        var read = TagEditor.read(file);
        var renamed = read.tracks().get(1).withName("Renamed");
        var edited = read.toBuilder().track(renamed).build();
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(file, edited));
        assertArrayEquals(data, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("drops the other tags of a track and ignores tracks the stream lacks")
    void dropsOtherTags(@TempDir Path directory) throws Exception {
        var file = directory.resolve("named.ts");
        Files.write(file, smaltFixture());
        var read = TagEditor.read(file);
        var track = read.tracks().get(1);
        var described = new TrackTags(track.trackId(), MediaTags.builder().set(StandardTag.TITLE, "Kept")
                .set(StandardTag.COMMENT, "dropped").build(), Set.of(TrackDisposition.COMMENTARY, HEARING_IMPAIRED));
        TagEditor.write(file, read.toBuilder().track(described).track(TrackTags.of(4000, "Absent")).build());
        var again = TagEditor.read(file);
        assertEquals(TrackTags.of(track.trackId(), "Kept", HEARING_IMPAIRED),
                again.track(track.trackId()).orElseThrow());
        assertTrue(again.track(4000).isEmpty());
    }

    @Nested
    @DisplayName("descriptor codec")
    class Codec {
        @Test
        @DisplayName("cuts a name to the 247 bytes of one segment, without splitting a surrogate pair")
        void cuts() {
            var latin = TsDescriptors.componentName("eng", "a".repeat(300));
            assertEquals(2 + 255, latin.length);
            assertEquals("a".repeat(247), TsDescriptors.multipleString(latin, 2, latin.length - 2));
            var pairs = "🎵".repeat(100);
            var wide = TsDescriptors.componentName("eng", pairs);
            var decoded = TsDescriptors.multipleString(wide, 2, wide.length - 2);
            assertEquals(pairs.substring(0, 122), decoded);
            assertTrue(wide.length - 2 <= 255);
        }

        @Test
        @DisplayName("decodes nothing from a malformed multiple string structure")
        void malformed() {
            var valid = TsDescriptors.componentName("eng", "Name");
            var body = Arrays.copyOfRange(valid, 2, valid.length);
            assertEquals("Name", TsDescriptors.multipleString(body, 0, body.length));
            for (var length = 0; length < body.length; length++) {
                assertNull(TsDescriptors.multipleString(body, 0, length), "cut at " + length);
            }
            var noStrings = body.clone();
            noStrings[0] = 0;
            assertNull(TsDescriptors.multipleString(noStrings, 0, noStrings.length));
            var compressed = body.clone();
            compressed[5] = 1;
            assertNull(TsDescriptors.multipleString(compressed, 0, compressed.length), "compressed text is skipped");
            var oddUtf16 = new byte[]{1, 'e', 'n', 'g', 1, 0, 0x3F, 3, 0, 'A', 0};
            assertEquals("A", TsDescriptors.multipleString(oddUtf16, 0, oddUtf16.length));
        }

        @Test
        @DisplayName("maps the audio types to dispositions and back")
        void audioTypes() {
            for (var type = 0; type < 4; type++) {
                assertEquals(type, TsDescriptors.audioType(TsDescriptors.dispositions(type)));
            }
            assertEquals(Set.of(), TsDescriptors.dispositions(4));
            assertEquals(1, TsDescriptors.audioType(Set.of(VISUAL_IMPAIRED, CLEAN_EFFECTS)));
        }
    }

    /** Returns the PID of the program map table of the first program. */
    static int pmtPid(byte[] data) {
        try (var demuxer = new TsDemuxer(MediaSource.of(data))) {
            return demuxer.program().pmtPid();
        }
    }

    static int pidOf(byte[] data, int sync) {
        return (data[sync + 1] & 0x1F) << 8 | (data[sync + 2] & 0xFF);
    }

    /** Asserts that two streams differ only in the payload of program map packets, their headers kept. */
    static void assertOnlyPmtChanged(byte[] before, byte[] after, int packetSize, int pmtPid) {
        assertEquals(before.length, after.length);
        var sync = packetSize == 192 ? 4 : 0;
        var changed = 0;
        for (var packet = 0; packet + packetSize <= before.length; packet += packetSize) {
            var same = Arrays.equals(before, packet, packet + packetSize, after, packet, packet + packetSize);
            if (same) {
                continue;
            }
            assertEquals(pmtPid, pidOf(before, packet + sync), "only program map packets change, packet at " + packet);
            assertTrue(Arrays.equals(before, packet, packet + sync + 4, after, packet, packet + sync + 4),
                    "the packet header is kept");
            changed++;
        }
        assertTrue(changed > 0, "the program map packets are rewritten");
    }
}
