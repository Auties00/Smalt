package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the tags of Sony Wave64 files: the {@code summarylist} chunk {@link Wave64Muxer} writes, read back by
 * {@link WavDemuxer} and by ffprobe (whose reader fixes the layout: a 32-bit entry count, then per entry an
 * {@code INFO} identifier, a 32-bit byte length and a UTF-16LE value), the {@code id3 }, descriptive, {@code cue }
 * and {@code list adtl} chunks it writes as a WAV file holds them, the tag editor's view of the same tags in a WAV
 * file, hand-built chunk variants and damaged input.
 */
@DisplayName("Sony Wave64 tags")
class Wave64TagsTest {
    private static final int RATE = 8000;

    private static final byte[] DATA = HexFormat.of().parseHex("64617461f3acd3118cd100c04f8edb8a");

    private static final byte[] SUMMARY_LIST = HexFormat.of().parseHex("bc945f925a52d21186dc00c04f8edb8a");

    private static final byte[] LIST = HexFormat.of().parseHex("6c6973742f91cf11a5d628db04c10000");

    private static final MediaTags TEXT = MediaTags.builder()
            .add("title", "Title é 日本 😀")
            .add("artist", "Artist")
            .add("comment", "Comment")
            .add("album", "Album")
            .add("date", "2024")
            .add("genre", "Rock")
            .add("ISFT", "Smalt")
            .add("IENG", "Engineer")
            .build();

    private static final MediaTags RICH = MediaTags.builder()
            .add("title", "Rich")
            .add("artist", "First")
            .add("artist", "Second")
            .add("custom_key", "custom value")
            .add("originator", "Smalt")
            .add("time_reference", "8000")
            .add("coding_history", "A=PCM,F=8000")
            .add("ixml", "<BWFXML><PROJECT>p</PROJECT></BWFXML>")
            .add("chna", "1 ATU_00000001 AT_00010001_01 AP_00010002")
            .add("smpl:unity_note", "60")
            .add("smpl:loop", "0 0 10 20 0 0")
            .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "cover",
                    new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}))
            .chapters(List.of(Chapter.of(0, 250_000, "one"), Chapter.of(250_000, 500_000, "two")))
            .build();

    private static PcmPackets tone() {
        return PcmPackets.tone(Codec.PCM_S16LE, RATE, ChannelLayout.MONO, RATE / 2);
    }

    private static byte[] mux(Function<MediaSink, ? extends com.github.auties00.smalt.container.Muxer> factory,
                              MediaTags tags) {
        var input = tone();
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(input.track());
            muxer.setTags(tags);
            input.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static byte[] w64(MediaTags tags) {
        return mux(Wave64Muxer::new, tags);
    }

    private static Map<String, String> metadata(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            return demuxer.metadata();
        }
    }

    private static List<Chapter> chapters(byte[] file) {
        try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
            return demuxer.chapters();
        }
    }

    private static Map<String, String> ffprobeTags(String name, byte[] file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var path = MuxSupport.save(name, file);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1", path.toString())),
                StandardCharsets.UTF_8);
        var tags = new LinkedHashMap<String, String>();
        for (var line : output.lines().toList()) {
            if (line.startsWith("TAG:")) {
                var equals = line.indexOf('=');
                tags.put(line.substring(4, equals), line.substring(equals + 1));
            }
        }
        return tags;
    }

    /** Returns the offset of the first chunk of a GUID, or -1. */
    private static int find(byte[] file, byte[] guid) {
        var offset = 40;
        while (offset + 24 <= file.length) {
            if (Arrays.equals(file, offset, offset + 16, guid, 0, 16)) {
                return offset;
            }
            var size = ByteBuffer.wrap(file, offset + 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
            if (size < 24) {
                return -1;
            }
            offset += (int) (size + 7 & ~7L);
        }
        return -1;
    }

    private static byte[] guidOf(String id) {
        var guid = HexFormat.of().parseHex("00000000f3acd3118cd100c04f8edb8a");
        System.arraycopy(id.getBytes(StandardCharsets.ISO_8859_1), 0, guid, 0, 4);
        return guid;
    }

    /** Builds a Wave64 chunk with its 24-byte header and padding. */
    private static byte[] chunk(byte[] guid, byte[] body) {
        var buffer = ByteBuffer.allocate(24 + body.length + (-body.length & 7)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(guid).putLong(24L + body.length).put(body);
        return buffer.array();
    }

    /** Builds a summarylist body of a declared count and of entries holding UTF-16LE values. */
    private static byte[] summary(int count, boolean terminated, String... pairs) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array());
        for (var i = 0; i < pairs.length; i += 2) {
            var text = (pairs[i + 1] + (terminated ? "\0" : "")).getBytes(StandardCharsets.UTF_16LE);
            out.writeBytes(pairs[i].getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(text.length).array());
            out.writeBytes(text);
        }
        return out.toByteArray();
    }

    /** Inserts chunks before the data chunk of an untagged file, or appends them after its data, fixing the size. */
    private static byte[] insert(boolean beforeData, byte[]... chunks) {
        var file = w64(MediaTags.empty());
        var at = beforeData ? find(file, DATA) : file.length;
        var out = new ByteArrayOutputStream();
        out.write(file, 0, at);
        for (var chunk : chunks) {
            out.writeBytes(chunk);
        }
        out.write(file, at, file.length - at);
        var result = out.toByteArray();
        ByteBuffer.wrap(result, 16, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(result.length);
        return result;
    }

    @Nested
    @DisplayName("writing")
    class Writing {
        @Test
        @DisplayName("stores INFO keys in a summarylist chunk that ffprobe and WavDemuxer read back unchanged")
        void againstFfprobe() {
            var file = w64(TEXT);
            var expected = new LinkedHashMap<String, String>();
            expected.put("INAM", "Title é 日本 😀");
            expected.put("IART", "Artist");
            expected.put("ICMT", "Comment");
            expected.put("IPRD", "Album");
            expected.put("ICRD", "2024");
            expected.put("IGNR", "Rock");
            expected.put("ISFT", "Smalt");
            expected.put("IENG", "Engineer");
            assertEquals(expected, metadata(file));
            assertTrue(find(file, SUMMARY_LIST) > 0);
            assertTrue(find(file, SUMMARY_LIST) < find(file, DATA));
            assertEquals(-1, find(file, guidOf("id3 ")), "every key fits the summarylist chunk");
            var probe = ffprobeTags("w64-tags.w64", file);
            assertEquals(Map.of("title", "Title é 日本 😀", "artist", "Artist",
                    "comment", "Comment", "album", "Album", "date", "2024", "genre", "Rock",
                    "encoder", "Smalt", "IENG", "Engineer"), probe);
            MuxSupport.assertFfmpegClean("w64-tags.w64", file);
        }

        @Test
        @DisplayName("lays out a summarylist entry as a count, the identifier, the byte length and NUL-ended UTF-16LE")
        void layout() {
            var file = w64(MediaTags.builder().add("title", "Hi").build());
            var at = find(file, SUMMARY_LIST);
            var expected = HexFormat.of().parseHex("bc945f925a52d21186dc00c04f8edb8a" + "2a00000000000000"
                    + "01000000" + "494e414d" + "06000000" + "480069000000" + "000000000000");
            assertArrayEquals(expected, Arrays.copyOfRange(file, at, at + expected.length));
            assertEquals(at + expected.length, find(file, DATA));
            assertEquals(file.length, ByteBuffer.wrap(file, 16, 8).order(ByteOrder.LITTLE_ENDIAN).getLong());
        }

        @Test
        @DisplayName("writes the id3, Broadcast Wave, iXML, chna, smpl, cue and list chunks a WAV file gets, read as it")
        void sameAsWav() {
            var w64 = w64(RICH);
            var wav = mux(WavMuxer::new, RICH);
            assertEquals(metadata(wav), metadata(w64));
            assertEquals(chapters(wav), chapters(w64));
            assertEquals(2, chapters(w64).size());
            assertTrue(metadata(w64).values().stream().anyMatch(value -> value.contains("custom value")),
                    "a key the summarylist chunk cannot store goes to the id3 chunk");
            assertEquals("Smalt", metadata(w64).get("originator"));
            assertEquals("60", metadata(w64).get("smpl:unity_note"));
            var data = find(w64, DATA);
            for (var guid : List.of(SUMMARY_LIST, guidOf("id3 "), guidOf("bext"), guidOf("iXML"), guidOf("chna"),
                    guidOf("smpl"), guidOf("cue "), LIST)) {
                var at = find(w64, guid);
                assertTrue(at > 0 && at < data, HexFormat.of().formatHex(guid));
                assertEquals(0, at % 8);
            }
            var list = find(w64, LIST);
            assertEquals("adtl", new String(w64, list + 24, 4, StandardCharsets.ISO_8859_1));
        }

        @Test
        @DisplayName("carries the tags the tag editor reads from a WAV file into a Wave64 file that reads the same")
        void tagEditorRoundTrip() {
            var wav = mux(WavMuxer::new, RICH);
            var edited = TagEditor.read(MediaSource.of(wav));
            var w64 = w64(edited);
            assertEquals(metadata(wav), metadata(w64));
            assertEquals(chapters(wav), chapters(w64));
            assertFalse(metadata(w64).isEmpty());
        }

        @Test
        @DisplayName("writes the tags to a streaming sink, ahead of the data read to the end of the file")
        void streaming() {
            var input = tone();
            var output = new ByteArrayOutputStream();
            try (var sink = MediaSink.of(output); var muxer = new Wave64Muxer(sink)) {
                muxer.addTrack(input.track());
                muxer.setTags(TEXT);
                input.packets().forEach(muxer::writePacket);
                muxer.finish();
            }
            var file = output.toByteArray();
            assertEquals(metadata(w64(TEXT)), metadata(file));
            assertEquals(0, ByteBuffer.wrap(file, 16, 8).getLong());
        }

        @Test
        @DisplayName("rejects tags after the first packet and malformed descriptive values, keeping the earlier tags")
        void rejections() {
            var input = tone();
            var sink = MediaSink.memory();
            try (var muxer = new Wave64Muxer(sink)) {
                assertTrue(muxer.supportsTags());
                muxer.addTrack(input.track());
                muxer.setTags(TEXT);
                assertThrows(IllegalArgumentException.class,
                        () -> muxer.setTags(MediaTags.builder().add("smpl:loop", "not a loop").build()));
                input.packets().forEach(muxer::writePacket);
                assertThrows(IllegalStateException.class, () -> muxer.setTags(MediaTags.empty()));
                muxer.finish();
            }
            assertEquals("Artist", metadata(sink.toByteArray()).get("IART"));
        }

        @Test
        @DisplayName("writes no metadata chunk for empty tags")
        void empty() {
            var file = w64(MediaTags.empty());
            assertEquals(-1, find(file, SUMMARY_LIST));
            assertEquals(-1, find(file, LIST));
            assertTrue(metadata(file).isEmpty());
        }
    }

    @Nested
    @DisplayName("probing and transcoding")
    class Conversions {
        @Test
        @DisplayName("probes the summarylist identifiers under their standard tag names")
        void probe() {
            var tags = Media.probe(MediaSource.of(w64(TEXT))).tags();
            assertEquals("Title é 日本 😀", tags.get("title"), tags.toString());
            assertEquals("Artist", tags.get("artist"), tags.toString());
            assertEquals("Album", tags.get("album"), tags.toString());
            assertFalse(tags.containsKey("INAM"), tags.toString());
        }

        @Test
        @DisplayName("carries the tags of a Wave64 input into another container under their standard names")
        void fromWave64() {
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(w64(TEXT)), sink, TranscodeOptions.of(OutputContainer.MATROSKA));
            var read = TagEditor.read(MediaSource.of(sink.toByteArray()));
            assertEquals(List.of("Title é 日本 😀"), read.getAll(StandardTag.TITLE), read.toString());
            assertEquals(List.of("Artist"), read.getAll(StandardTag.ARTIST), read.toString());
            assertEquals(List.of("Rock"), read.getAll(StandardTag.GENRE), read.toString());
        }

        @Test
        @DisplayName("carries the tags and chapters of a WAV input into a Wave64 output")
        void toWave64() {
            var wav = mux(WavMuxer::new, RICH);
            var sink = MediaSink.memory();
            Transcoder.transcode(MediaSource.of(wav), sink, TranscodeOptions.of(OutputContainer.W64));
            var w64 = sink.toByteArray();
            var read = metadata(w64);
            assertEquals("Rich", read.get("INAM"), read.toString());
            assertEquals("Smalt", read.get("originator"), read.toString());
            assertEquals("60", read.get("smpl:unity_note"), read.toString());
            assertTrue(read.values().stream().anyMatch(value -> value.contains("Second")), read.toString());
            assertEquals(List.of("one", "two"), chapters(w64).stream().map(chapter -> chapter.title().orElseThrow())
                    .toList());
            assertTrue(find(w64, SUMMARY_LIST) > 0 && find(w64, SUMMARY_LIST) < find(w64, DATA));
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {
        @Test
        @DisplayName("reads values without a NUL, stops at the declared count and joins repeated identifiers")
        void variants() {
            assertEquals(Map.of("INAM", "Hello", "IART", "Me"),
                    metadata(insert(true, chunk(SUMMARY_LIST, summary(2, false, "INAM", "Hello", "IART", "Me")))));
            assertEquals(Map.of("INAM", "A"),
                    metadata(insert(true, chunk(SUMMARY_LIST, summary(1, true, "INAM", "A", "IART", "B")))));
            assertEquals(Map.of("INAM", "A; B"),
                    metadata(insert(true, chunk(SUMMARY_LIST, summary(2, true, "INAM", "A", "INAM", "B")))));
            assertEquals(Map.of("XYZW", "odd"),
                    metadata(insert(false, chunk(SUMMARY_LIST, summary(1, true, "XYZW", "odd")))));
        }

        @Test
        @DisplayName("reads the INFO entries of a list chunk as those of a RIFF LIST chunk")
        void listInfo() {
            var info = ByteBuffer.allocate(4 + 8 + 6 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN);
            info.put("INFO".getBytes(StandardCharsets.ISO_8859_1));
            info.put("INAM".getBytes(StandardCharsets.ISO_8859_1)).putInt(6).put("Hello\0".getBytes(StandardCharsets.ISO_8859_1));
            info.put("IART".getBytes(StandardCharsets.ISO_8859_1)).putInt(3).put("Me\0\0".getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(Map.of("INAM", "Hello", "IART", "Me"), metadata(insert(true, chunk(LIST, info.array()))));
        }

        @Test
        @DisplayName("keeps the entries before one that is too long, unprintable or past the declared count")
        void damagedEntries() {
            var overlong = summary(2, true, "INAM", "A", "IART", "B");
            ByteBuffer.wrap(overlong, 4 + 8 + 4 + 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(1000);
            assertEquals(Map.of("INAM", "A"), metadata(insert(true, chunk(SUMMARY_LIST, overlong))));
            var unprintable = summary(2, true, "INAM", "A", "I\u0001RT", "B");
            assertEquals(Map.of("INAM", "A"), metadata(insert(true, chunk(SUMMARY_LIST, unprintable))));
            var huge = summary(-1, true, "INAM", "A");
            assertEquals(Map.of("INAM", "A"), metadata(insert(true, chunk(SUMMARY_LIST, huge))));
            assertTrue(metadata(insert(true, chunk(SUMMARY_LIST, new byte[3]))).isEmpty());
            assertTrue(metadata(insert(true, chunk(SUMMARY_LIST, summary(5, true)))).isEmpty());
        }

        @Test
        @DisplayName("reads or rejects every truncation and byte change of a tagged file with a MediaException")
        void robustness() {
            var file = w64(RICH);
            var data = find(file, DATA);
            for (var length = 0; length <= data + 32; length++) {
                demuxAll(Arrays.copyOf(file, length));
            }
            var random = new Random(64);
            for (var round = 0; round < 2000; round++) {
                var damaged = file.clone();
                for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                    damaged[40 + random.nextInt(data - 40)] = (byte) random.nextInt(256);
                }
                demuxAll(damaged);
            }
        }

        private static void demuxAll(byte[] file) {
            try (var demuxer = new WavDemuxer(MediaSource.of(file))) {
                assertFalse(demuxer.metadata() == null);
                demuxer.chapters();
                while (demuxer.read() != null) {
                    assertTrue(true);
                }
            } catch (MediaException expected) {
                assertTrue(expected instanceof MediaException);
            }
        }
    }
}
