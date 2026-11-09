package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.TrackDisposition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the {@code LIST INFO} tags and the {@code strn} stream names of AVI files and writes them from
 * {@code AviMuxer}, with ffprobe as the independent reader and the packets of Smalt's demuxer and ffmpeg's packet
 * hashes proving the media unchanged: in place, the bytes from the {@code movi} list on are unchanged; when the
 * header grows, the OpenDML and legacy indexes follow the moved media.
 */
@DisplayName("AVI tags")
class AviTagsTest {
    static Path fixture() {
        return Fixtures.ffmpeg("avi", List.of("-f", "lavfi", "-i", TagFixtures.VIDEO, "-f", "lavfi", "-i", TagFixtures.AUDIO),
                List.of("-map", "0:v", "-map", "1:a", "-shortest", "-c:v", "mpeg4", "-c:a", "libmp3lame", "-b:a", "64k",
                        "-metadata", "title=Fixture Title", "-metadata", "artist=Fixture Artist",
                        "-metadata", "genre=Rock", "-metadata:s:v:0", "title=Video Name",
                        "-metadata:s:a:0", "title=Audio Name"));
    }

    /** An AVI file Smalt's muxer wrote, with OpenDML super and standard indexes and an idx1 index. */
    static byte[] openDml() {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        return MuxSupport.toMemory(source.tracks(), source.order(), AviMuxer::new);
    }

    static MediaTags edited() {
        return MediaTags.builder().set(StandardTag.TITLE, "Edited Title \u00E8").set(StandardTag.ARTIST, "Edited Artist")
                .set(StandardTag.COMMENT, "a comment").set(StandardTag.DATE, "2024").set(StandardTag.COPYRIGHT, "(c) Me")
                .set("IENG", "Engineer")
                .track(TrackTags.of(1, "Renamed Video"))
                .track(TrackTags.of(2, "Renamed Audio \u4E2D"))
                .build();
    }

    @Test
    @DisplayName("reads the tags and stream names ffmpeg wrote")
    void readsFixture() {
        var file = fixture();
        var read = TagEditor.read(file);
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals(probe.get("title"), read.get(StandardTag.TITLE).orElseThrow());
        assertEquals(probe.get("artist"), read.get(StandardTag.ARTIST).orElseThrow());
        assertEquals(probe.get("genre"), read.get(StandardTag.GENRE).orElseThrow());
        assertEquals(probe.get("software"), read.get(StandardTag.ENCODER).orElseThrow());
        assertEquals(List.of(TrackTags.of(1, "Video Name"), TrackTags.of(2, "Audio Name")), read.tracks());
        var probed = Media.probe(MediaSource.of(Fixtures.read(file)));
        assertEquals("Video Name", probed.streams().get(0).tags().get("title"));
        assertEquals("Audio Name", probed.streams().get(1).tags().get("title"));
    }

    @Test
    @DisplayName("edits in place within the header and filler space, leaving the movi list and the index untouched")
    void inPlace(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(fixture(), directory);
        var original = Files.readAllBytes(file);
        var movi = moviOffset(original);
        var hash = TagFixtures.packetHash(file);
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, edited()));
        var bytes = Files.readAllBytes(file);
        assertEquals(original.length, bytes.length);
        assertEquals(movi, moviOffset(bytes));
        assertArrayEquals(Arrays.copyOfRange(original, (int) movi, original.length),
                Arrays.copyOfRange(bytes, (int) movi, bytes.length), "movi and idx1 bytes changed");
        assertEdited(file);
        assertEquals(hash, TagFixtures.packetHash(file));
        assertEquals(TagWriteResult.UNCHANGED, TagEditor.write(file, TagEditor.read(file)));
    }

    @Test
    @DisplayName("moves the media when the header grows, keeping the legacy index relative to the movi list")
    void grows(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(fixture(), directory);
        var before = MuxSupport.demux(Files.readAllBytes(file));
        var hash = TagFixtures.packetHash(file);
        var tags = edited().toBuilder().set(StandardTag.COMMENT, "c".repeat(20_000)).build();
        assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, tags));
        var bytes = Files.readAllBytes(file);
        assertEquals(bytes.length - 8, u32(bytes, 4), "RIFF size");
        assertEquals(hash, TagFixtures.packetHash(file));
        MuxSupport.assertSamePayloads(before, MuxSupport.demux(bytes), Map.of(1, 1, 2, 2));
        assertEquals("c".repeat(20_000), TagEditor.read(file).get(StandardTag.COMMENT).orElseThrow());
        assertEquals("c".repeat(20_000), TagFixtures.ffprobeTags(file).get("comment"));
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, edited()), "the padding takes the next edit");
        assertEdited(file);
    }

    @Test
    @DisplayName("moves the OpenDML super and standard indexes with the media of a file Smalt muxed")
    void growsOpenDml(@TempDir Path directory) throws Exception {
        var muxed = openDml();
        assertTrue(indexOf(muxed, "indx") > 0 && indexOf(muxed, "ix00") > 0 && indexOf(muxed, "idx1") > 0);
        var file = directory.resolve("odml.avi");
        Files.write(file, muxed);
        var before = MuxSupport.demux(muxed);
        var hash = TagFixtures.packetHash(file);
        var tags = edited().toBuilder().set(StandardTag.COMMENT, "c".repeat(20_000)).build();
        assertEquals(TagWriteResult.REWRITTEN, TagEditor.write(file, tags));
        var bytes = Files.readAllBytes(file);
        var delta = bytes.length - muxed.length;
        var oldIndex = indexOf(muxed, "ix00");
        var newIndex = indexOf(bytes, "ix00");
        assertEquals(oldIndex + delta, newIndex);
        assertEquals(u64(muxed, oldIndex + 8 + 12) + delta, u64(bytes, newIndex + 8 + 12), "qwBaseOffset");
        MuxSupport.assertSamePayloads(before, MuxSupport.demux(bytes), Map.of(1, 1, 2, 2));
        assertEquals(hash, TagFixtures.packetHash(file));
        assertEdited(file);
    }

    @Test
    @DisplayName("moves an idx1 index of absolute offsets with the media")
    void absoluteIndex(@TempDir Path directory) throws Exception {
        var original = Fixtures.read(fixture());
        var absolute = original.clone();
        var movi = moviOffset(original) + 8;
        var idx1 = indexOf(absolute, "idx1");
        var count = u32(absolute, idx1 + 4) / 16;
        for (var entry = 0; entry < count; entry++) {
            var at = idx1 + 8 + 16 * entry + 8;
            ByteBuffer.wrap(absolute).order(ByteOrder.LITTLE_ENDIAN).putInt(at, (int) (u32(absolute, at) + movi));
        }
        var before = MuxSupport.demux(original);
        MuxSupport.assertSamePayloads(before, MuxSupport.demux(absolute), Map.of(1, 1, 2, 2));
        var file = directory.resolve("absolute.avi");
        Files.write(file, absolute);
        TagEditor.write(file, edited().toBuilder().set(StandardTag.COMMENT, "c".repeat(20_000)).build());
        var bytes = Files.readAllBytes(file);
        var delta = bytes.length - absolute.length;
        var newIdx1 = indexOf(bytes, "idx1");
        assertEquals(u32(absolute, idx1 + 16) + delta, u32(bytes, newIdx1 + 16), "first absolute offset");
        MuxSupport.assertSamePayloads(before, MuxSupport.demux(bytes), Map.of(1, 1, 2, 2));
        Oracle.assertDecodesCleanly(file);
    }

    @Test
    @DisplayName("removes the stream names of the tracks the tags describe without one, and every INFO entry")
    void removes(@TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(fixture(), directory);
        TagEditor.write(file, MediaTags.builder().track(TrackTags.of(2)).build());
        var bytes = Files.readAllBytes(file);
        assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("Audio Name"));
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("Video Name"));
        assertTrue(indexOf(bytes, "INFO") < 0);
        var read = TagEditor.read(file);
        assertTrue(read.entries().isEmpty());
        assertEquals(List.of(TrackTags.of(1, "Video Name"), TrackTags.of(2)), read.tracks());
        assertNull(TagFixtures.ffprobeStreamTags(file, 1).get("title"));
    }

    @Test
    @DisplayName("rejects pictures and chapters, which AVI cannot store")
    void unsupported(@TempDir Path directory) {
        var file = TagFixtures.copy(fixture(), directory);
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(file, MediaTags.builder()
                .addPicture(TagFixtures.picture(AttachedPicture.Type.FRONT_COVER, "front")).build()));
        assertThrows(MediaException.Unsupported.class, () -> TagEditor.write(file, MediaTags.builder()
                .addChapter(com.github.auties00.smalt.container.Chapter.of(0, 1000, "c")).build()));
        assertTrue(TagEditor.isSupported(com.github.auties00.smalt.container.ContainerFormat.AVI));
    }

    @Test
    @DisplayName("writes the INFO list and the stream names from the muxer")
    void muxer() {
        var source = MuxSupport.demux(Fixtures.read(fixture()));
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Muxed").set(StandardTag.ARTIST, "Muxer Artist")
                .set(StandardTag.ALBUM_ARTIST, "dropped").track(TrackTags.of(1, "Muxed Video", TrackDisposition.DEFAULT))
                .track(TrackTags.of(2, "Muxed Audio")).build();
        var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new AviMuxer(sink);
            assertTrue(muxer.supportsTags());
            muxer.setTags(tags);
            return muxer;
        });
        var file = MuxSupport.save("tagged.avi", output);
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals("Muxed", probe.get("title"));
        assertEquals("Muxer Artist", probe.get("artist"));
        assertEquals("Smalt", probe.get("software"));
        assertEquals("Muxed Video", TagFixtures.ffprobeStreamTags(file, 0).get("title"));
        assertEquals("Muxed Audio", TagFixtures.ffprobeStreamTags(file, 1).get("title"));
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals(List.of(TrackTags.of(1, "Muxed Video"), TrackTags.of(2, "Muxed Audio")), read.tracks());
        assertTrue(read.get(StandardTag.ALBUM_ARTIST).isEmpty());
        MuxSupport.assertSamePayloads(source, MuxSupport.demux(output), Map.of(1, 1, 2, 2));
        var encoder = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new AviMuxer(sink);
            muxer.setTags(MediaTags.builder().set(StandardTag.ENCODER, "Other").build());
            return muxer;
        });
        assertEquals("Other", TagEditor.read(MediaSource.of(encoder)).get(StandardTag.ENCODER).orElseThrow());
    }

    @Test
    @DisplayName("reads or rejects with a MediaException every truncation of a tagged file")
    void truncated() {
        var tagged = TagEditor.write(Fixtures.read(fixture()), edited(), TagWriteOptions.defaults());
        for (var cut = 0; cut < 12_000; cut += 61) {
            var bytes = Arrays.copyOf(tagged, cut);
            try {
                TagEditor.read(MediaSource.of(bytes));
                TagEditor.write(bytes, edited(), TagWriteOptions.defaults());
            } catch (MediaException expected) {
                // a cut header is rejected
            }
        }
    }

    static void assertEdited(Path file) {
        var read = TagEditor.read(file);
        assertEquals("Edited Title \u00E8", read.get(StandardTag.TITLE).orElseThrow());
        assertEquals("Edited Artist", read.get(StandardTag.ARTIST).orElseThrow());
        assertEquals("Engineer", read.get("IENG").orElseThrow());
        assertEquals(List.of(TrackTags.of(1, "Renamed Video"), TrackTags.of(2, "Renamed Audio \u4E2D")), read.tracks());
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals("Edited Title \u00E8", probe.get("title"));
        assertEquals(read.get(StandardTag.COMMENT).orElseThrow(), probe.get("comment"));
        assertEquals("Renamed Video", TagFixtures.ffprobeStreamTags(file, 0).get("title"));
        assertEquals("Renamed Audio \u4E2D", TagFixtures.ffprobeStreamTags(file, 1).get("title"));
        var probed = Media.probe(MediaSource.of(Fixtures.read(file)));
        assertEquals("Edited Title \u00E8", probed.tags().get("title"));
        assertEquals("Renamed Audio \u4E2D", probed.streams().get(1).tags().get("title"));
    }

    static long moviOffset(byte[] avi) {
        var position = 12;
        while (position + 12 <= avi.length) {
            var size = (int) u32(avi, position + 4);
            if (new String(avi, position, 4, StandardCharsets.US_ASCII).equals("LIST")
                    && new String(avi, position + 8, 4, StandardCharsets.US_ASCII).equals("movi")) {
                return position;
            }
            position += 8 + size + (size & 1);
        }
        throw new AssertionError("no movi list");
    }

    static int indexOf(byte[] data, String text) {
        return TagVocabularyTest.indexOf(data, text.getBytes(StandardCharsets.ISO_8859_1));
    }

    static long u32(byte[] data, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(offset));
    }

    static long u64(byte[] data, int offset) {
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getLong(offset);
    }
}
