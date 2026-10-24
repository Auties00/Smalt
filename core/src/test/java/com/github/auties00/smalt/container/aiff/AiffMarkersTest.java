package com.github.auties00.smalt.container.aiff;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the AIFF {@code MARK}, {@code INST} and {@code COMT} chunks: markers as chapters, the instrument fields
 * and loops as keys, and comments, read from chunks built as the Apple AIFF specification lays them out and written
 * by {@link AiffMuxer} and the tag editor.
 */
@DisplayName("AIFF markers, instrument and comments")
class AiffMarkersTest {
    private static final int RATE = 44100;

    private static byte[] plain() {
        return Fixtures.read(Fixtures.tone("aiff", 440, RATE, 1, 2, List.of("-c:a", "pcm_s16be")));
    }

    private static byte[] mux(MediaTags tags) {
        var source = MuxSupport.demux(plain());
        var sink = MediaSink.memory();
        try (var muxer = new AiffMuxer(sink)) {
            source.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            source.order().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static List<Chapter> chapters(byte[] file) {
        try (var demuxer = new AiffDemuxer(MediaSource.of(file))) {
            return demuxer.chapters();
        }
    }

    private static Map<String, String> metadata(byte[] file) {
        try (var demuxer = new AiffDemuxer(MediaSource.of(file))) {
            return demuxer.metadata();
        }
    }

    private static byte[] append(byte[] file, byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(file);
        for (var chunk : chunks) {
            out.writeBytes(chunk);
        }
        var result = out.toByteArray();
        ByteBuffer.wrap(result).putInt(4, result.length - 8);
        return result;
    }

    private static byte[] chunk(String id, byte[] payload) {
        var buffer = ByteBuffer.allocate(8 + payload.length + (payload.length & 1));
        buffer.put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static byte[] marks(Object... idPositionName) {
        var out = new ByteArrayOutputStream();
        out.write(0);
        out.write(idPositionName.length / 3);
        for (var index = 0; index < idPositionName.length; index += 3) {
            var id = (Integer) idPositionName[index];
            var position = (Integer) idPositionName[index + 1];
            var name = ((String) idPositionName[index + 2]).getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(ByteBuffer.allocate(6).putShort((short) (int) id).putInt(position).array());
            out.write(name.length);
            out.writeBytes(name);
            if ((1 + name.length) % 2 != 0) {
                out.write(0);
            }
        }
        return chunk("MARK", out.toByteArray());
    }

    private static byte[] inst(int sustainMode, int begin, int end) {
        var buffer = ByteBuffer.allocate(20);
        buffer.put((byte) 60).put((byte) -3).put((byte) 10).put((byte) 100).put((byte) 1).put((byte) 127)
                .putShort((short) -6).putShort((short) sustainMode).putShort((short) begin).putShort((short) end)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0);
        return chunk("INST", buffer.array());
    }

    private static byte[] comments(String... texts) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) texts.length).array());
        for (var text : texts) {
            var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
            out.writeBytes(ByteBuffer.allocate(8).putInt(0).putShort((short) 0).putShort((short) bytes.length).array());
            out.writeBytes(bytes);
            if (bytes.length % 2 != 0) {
                out.write(0);
            }
        }
        return chunk("COMT", out.toByteArray());
    }

    private static long micros(long samples) {
        return Math.round(samples * 1e6 / RATE);
    }

    @Test
    @DisplayName("reads markers as chapters, leaving out the markers of the instrument's loops")
    void read() {
        var file = append(plain(), marks(1, 0, "Start", 2, 22050, "", 7, 44100, "Loop begin", 8, 66150, "Loop end",
                3, 61740, "Late"), inst(1, 7, 8), comments("first note", "second"));
        assertEquals(List.of(Chapter.of(0, micros(22050), "Start"), Chapter.of(micros(22050), micros(61740), null),
                Chapter.of(micros(61740), 2_000_000, "Late")), chapters(file));
        var metadata = metadata(file);
        assertEquals("60", metadata.get("inst:base_note"));
        assertEquals("-3", metadata.get("inst:detune"));
        assertEquals("-6", metadata.get("inst:gain"));
        assertEquals("1 44100 66150", metadata.get("inst:sustain_loop"));
        assertFalse(metadata.containsKey("inst:release_loop"));
        assertEquals("first note; second", metadata.get("COMT"));
        var tags = TagEditor.read(MediaSource.of(file));
        assertEquals(chapters(file), tags.chapters());
        assertEquals(List.of("first note", "second"), tags.getAll("comment"));
        assertEquals("100", tags.get("inst:high_note").orElseThrow());
    }

    @Test
    @DisplayName("writes chapters as markers and the instrument keys as an INST chunk with loop markers")
    void write() {
        var chapters = List.of(Chapter.of(0, micros(44100), "A"), Chapter.of(micros(44100), 2_000_000, "B"));
        var tags = MediaTags.builder().chapters(chapters).add("inst:base_note", "64").add("inst:gain", "3")
                .add("inst:sustain_loop", "1 1000 2000").add("inst:release_loop", "2 3000 4000")
                .add("comment", "kept").build();
        for (var output : List.of(mux(tags), TagEditor.write(plain(), tags, TagWriteOptions.defaults()))) {
            assertEquals(chapters, chapters(output));
            var read = TagEditor.read(MediaSource.of(output));
            assertEquals(chapters, read.chapters());
            assertEquals("64", read.get("inst:base_note").orElseThrow());
            assertEquals("3", read.get("inst:gain").orElseThrow());
            assertEquals("127", read.get("inst:high_note").orElseThrow());
            assertEquals("1 1000 2000", read.get("inst:sustain_loop").orElseThrow());
            assertEquals("2 3000 4000", read.get("inst:release_loop").orElseThrow());
            assertEquals(List.of("kept"), read.getAll("comment"));
            Oracle.assertDecodesCleanly(MuxSupport.save("markers.aiff", output));
        }
    }

    @Test
    @DisplayName("moves COMT comments to ANNO on a rewrite and drops removed markers")
    void rewrite() {
        var file = append(plain(), marks(1, 0, "Only"), comments("old"));
        var read = TagEditor.read(MediaSource.of(file));
        assertEquals(List.of("old"), read.getAll("comment"));
        var edited = TagEditor.write(file, read.toBuilder().chapters(List.of()).build(), TagWriteOptions.defaults());
        var text = new String(edited, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("COMT") || text.contains("MARK"), "the chunks are rewritten");
        assertTrue(text.contains("ANNO"));
        assertEquals(List.of("old"), TagEditor.read(MediaSource.of(edited)).getAll("comment"));
        assertTrue(chapters(edited).isEmpty());
    }

    @Test
    @DisplayName("ignores truncated chunks and rejects malformed instrument values")
    void malformed() {
        var mark = marks(1, 0, "Truncated name");
        var file = append(plain(), java.util.Arrays.copyOf(mark, 8 + 9));
        ByteBuffer.wrap(file).putInt(file.length - 9 - 4, 9);
        assertTrue(chapters(file).isEmpty());
        try (var muxer = new AiffMuxer(MediaSink.memory())) {
            assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                    .add("inst:sustain_loop", "1 2").build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                    .add("inst:gain", "loud").build()));
        }
    }
}
