package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the descriptive chunks of WAV files: the Broadcast Wave {@code bext} chunk (EBU Tech 3285) against
 * ffmpeg's writer and reader, the {@code iXML}, {@code axml}, {@code chna} and {@code smpl} chunks, and cue points
 * with their {@code LIST adtl} labels and regions as chapters, through {@link WavDemuxer}, {@link WavMuxer} and the
 * tag editor.
 */
@DisplayName("WAV descriptive chunks")
class WavDescriptiveChunksTest {
    private static final int RATE = 48000;

    private static final String UMID = "0x0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20";

    private static byte[] plain() {
        return Fixtures.read(Fixtures.tone("wav", 440, RATE, 2, 2, List.of("-c:a", "pcm_s16le")));
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

    private static byte[] mux(byte[] file, MediaTags tags) {
        ContainerProbe.Demuxed source = MuxSupport.demux(file);
        var sink = MediaSink.memory();
        try (var muxer = new WavMuxer(sink)) {
            source.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            source.order().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static Map<String, String> ffprobeTags(byte[] file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var path = MuxSupport.save("bext-" + Integer.toHexString(java.util.Arrays.hashCode(file)) + ".wav", file);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1", path.toString())), StandardCharsets.UTF_8);
        var tags = new java.util.LinkedHashMap<String, String>();
        for (var line : output.lines().toList()) {
            if (line.startsWith("TAG:")) {
                var equals = line.indexOf('=');
                tags.put(line.substring(4, equals), line.substring(equals + 1));
            }
        }
        return tags;
    }

    /** Appends chunks to a RIFF file and fixes its size. */
    private static byte[] append(byte[] file, byte[]... chunks) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(file);
        for (var chunk : chunks) {
            out.writeBytes(chunk);
        }
        var result = out.toByteArray();
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, result.length - 8);
        return result;
    }

    private static byte[] chunk(String id, byte[] payload) {
        var buffer = ByteBuffer.allocate(8 + payload.length + (payload.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(id.getBytes(StandardCharsets.ISO_8859_1)).putInt(payload.length).put(payload);
        return buffer.array();
    }

    private static byte[] cue(int[][] points) {
        var buffer = ByteBuffer.allocate(4 + points.length * 24).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(points.length);
        for (var point : points) {
            buffer.putInt(point[0]).putInt(point[1]).put((point.length > 3 ? "slnt" : "data").getBytes(StandardCharsets.ISO_8859_1))
                    .putInt(0).putInt(0).putInt(point[2]);
        }
        return chunk("cue ", buffer.array());
    }

    private static byte[] adtl(byte[]... entries) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("adtl".getBytes(StandardCharsets.ISO_8859_1));
        for (var entry : entries) {
            out.writeBytes(entry);
        }
        return chunk("LIST", out.toByteArray());
    }

    private static byte[] label(int id, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        var buffer = ByteBuffer.allocate(4 + bytes.length + 1).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(id).put(bytes).put((byte) 0);
        return chunk("labl", buffer.array());
    }

    private static byte[] region(int id, int length) {
        var buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(id).putInt(length).put("rgn ".getBytes(StandardCharsets.ISO_8859_1));
        return chunk("ltxt", buffer.array());
    }

    private static long micros(long samples) {
        return samples * 1_000_000L / RATE;
    }

    @Nested
    @DisplayName("Broadcast Wave")
    class BroadcastWave {
        @Test
        @DisplayName("reads the bext chunk ffmpeg writes")
        void ffmpegWriter() {
            var file = Fixtures.read(Fixtures.tone("wav", 440, RATE, 1, 1, List.of("-c:a", "pcm_s16le", "-write_bext",
                    "1", "-metadata", "description=Desc", "-metadata", "originator=Orig", "-metadata",
                    "originator_reference=Ref", "-metadata", "origination_date=2026-10-24", "-metadata",
                    "origination_time=12:34:56", "-metadata", "time_reference=48000", "-metadata",
                    "coding_history=A=PCM,F=48000", "-metadata", "umid=" + UMID)));
            var expected = Map.of("description", "Desc", "originator", "Orig", "originator_reference", "Ref",
                    "origination_date", "2026-10-24", "origination_time", "12:34:56", "time_reference", "48000",
                    "umid", UMID, "coding_history", "A=PCM,F=48000");
            var metadata = metadata(file);
            var tags = TagEditor.read(MediaSource.of(file));
            for (var entry : expected.entrySet()) {
                assertEquals(entry.getValue(), metadata.get(entry.getKey()), entry.getKey());
                assertEquals(entry.getValue(), tags.get(entry.getKey()).orElse(null), entry.getKey());
            }
        }

        @Test
        @DisplayName("writes a bext chunk that ffprobe reads, version 2 loudness included")
        void writer() {
            var tags = MediaTags.builder().add("description", "A take").add("originator", "Smalt")
                    .add("originator_reference", "REF123").add("origination_date", "2026-10-24")
                    .add("origination_time", "08:09:10").add("time_reference", "172800000").add("umid", UMID)
                    .add("coding_history", "A=PCM,F=48000,W=16,M=stereo").add("loudness_value", "-23.00")
                    .add("max_true_peak_level", "-1.50").add("title", "Name").build();
            for (var output : List.of(mux(plain(), tags), TagEditor.write(plain(), tags, TagWriteOptions.defaults()))) {
                var probed = ffprobeTags(output);
                assertEquals("A take", probed.get("comment"));
                assertEquals("Smalt", probed.get("encoded_by"));
                assertEquals("REF123", probed.get("originator_reference"));
                assertEquals("2026-10-24", probed.get("date"));
                assertEquals("08:09:10", probed.get("creation_time"));
                assertEquals("172800000", probed.get("time_reference"));
                assertEquals(UMID, probed.get("umid"));
                assertEquals("A=PCM,F=48000,W=16,M=stereo", probed.get("coding_history"));
                assertEquals("Name", probed.get("title"));
                var read = TagEditor.read(MediaSource.of(output));
                assertEquals("-23.00", read.get("loudness_value").orElseThrow());
                assertEquals("-1.50", read.get("max_true_peak_level").orElseThrow());
                assertTrue(read.get("loudness_range").isEmpty());
                // the bext keys are stored once, in bext
                assertFalse(new String(output, StandardCharsets.ISO_8859_1).contains("id3 "));
                assertEquals(metadata(output).get("originator"), "Smalt");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"umid=0x123", "umid=zz", "loudness_value=loud", "time_reference=-1",
                "loudness_range=400"})
        @DisplayName("rejects malformed values")
        void malformed(String entry) {
            var key = entry.substring(0, entry.indexOf('='));
            var tags = MediaTags.builder().add(key, entry.substring(key.length() + 1)).build();
            try (var muxer = new WavMuxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.setTags(tags));
            }
        }
    }

    @Nested
    @DisplayName("XML, ADM and sampler chunks")
    class Other {
        @Test
        @DisplayName("round trips the iXML, axml, chna and smpl keys through the muxer, the demuxer and the tag editor")
        void roundTrip() {
            var ixml = "<?xml version=\"1.0\"?><BWFXML><PROJECT>Smalt</PROJECT><SCENE>12</SCENE></BWFXML>";
            var axml = "<ebuCoreMain><coreMetadata/></ebuCoreMain>";
            var chna = "1 ATU_00000001 AT_00010001_01 AP_00010002\n2 ATU_00000002 AT_00010002_01 AP_00010002";
            var tags = MediaTags.builder().add("ixml", ixml).add("axml", axml).add("chna", chna)
                    .add("smpl:manufacturer", "71").add("smpl:product", "2").add("smpl:sample_period", "20833")
                    .add("smpl:unity_note", "60").add("smpl:pitch_fraction", "2147483648")
                    .add("smpl:smpte_format", "25").add("smpl:smpte_offset", "-01:02:03:04")
                    .add("smpl:loop", "0 0 1000 47999 0 0").add("smpl:loop", "1 1 2000 3000 0 4").build();
            var muxed = mux(plain(), tags);
            var edited = TagEditor.write(plain(), tags, TagWriteOptions.defaults());
            for (var output : List.of(muxed, edited)) {
                var read = TagEditor.read(MediaSource.of(output));
                for (var entry : tags.entries()) {
                    assertTrue(read.getAll(entry.key()).contains(entry.value()), entry + " in " + read);
                }
                assertEquals(2, read.getAll("smpl:loop").size());
                var metadata = metadata(output);
                assertEquals(ixml, metadata.get("ixml"));
                assertEquals(chna, metadata.get("chna"));
                assertEquals("60", metadata.get("smpl:unity_note"));
                Oracle.assertDecodesCleanly(MuxSupport.save("descriptive.wav", output));
            }
            var text = new String(muxed, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("iXML") && text.contains("axml") && text.contains("chna") && text.contains("smpl"));
            // ITU-R BS.2088: two tracks and two audio identifiers
            var at = text.indexOf("chna") + 8;
            assertEquals(2, muxed[at] | muxed[at + 1] << 8);
            assertEquals(2, muxed[at + 2] | muxed[at + 3] << 8);
        }

        @Test
        @DisplayName("rejects a chna line that is not four fields and a smpl loop that is not six numbers")
        void malformed() {
            try (var muxer = new WavMuxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                        .add("chna", "1 ATU_00000001").build()));
                assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                        .add("smpl:loop", "1 2 3").build()));
                assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                        .add("smpl:smpte_offset", "01:02").build()));
            }
        }
    }

    @Nested
    @DisplayName("cue points")
    class CuePoints {
        @Test
        @DisplayName("reads cue points as chapters titled by labl and ended by ltxt or the next cue point")
        void read() {
            var file = append(plain(), cue(new int[][]{{1, 0, 0}, {2, 999, 24000}, {3, 0, 48000}, {4, 72000, 70000, 1}}),
                    adtl(label(1, "Intro"), region(1, 12000), label(3, "Ünïcode"), region(9, 100)));
            assertEquals(List.of(Chapter.of(0, micros(12000), "Intro"), Chapter.of(micros(24000), micros(48000), null),
                    Chapter.of(micros(48000), micros(72000), "Ünïcode"), Chapter.of(micros(72000), 2_000_000, null)),
                    chapters(file));
            assertEquals(chapters(file), TagEditor.read(MediaSource.of(file)).chapters());
        }

        @Test
        @DisplayName("writes chapters as cue points with labels and regions the demuxer and the tag editor read back")
        void write() {
            var chapters = List.of(Chapter.of(0, 500_000, "one"), Chapter.of(500_000, 1_250_000, null),
                    Chapter.of(1_250_000, 2_000_000, "three"));
            var tags = MediaTags.builder().chapters(chapters).add("title", "cues").build();
            var muxed = mux(plain(), tags);
            var edited = TagEditor.write(plain(), tags, TagWriteOptions.defaults());
            for (var output : List.of(muxed, edited)) {
                assertEquals(chapters, chapters(output));
                assertEquals(chapters, TagEditor.read(MediaSource.of(output)).chapters());
                Oracle.assertDecodesCleanly(MuxSupport.save("cues.wav", output));
            }
            // an edit that removes the chapters removes the cue chunk and its labels
            var stripped = TagEditor.write(edited, MediaTags.builder().add("title", "cues").build(), TagWriteOptions.defaults());
            assertTrue(chapters(stripped).isEmpty());
            var text = new String(stripped, StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("cue ") || text.contains("adtl"), "cue chunks remain");
        }

        @Test
        @DisplayName("ignores truncated cue and adtl chunks")
        void truncated() {
            var cue = cue(new int[][]{{1, 0, 100}, {2, 0, 200}});
            ByteBuffer.wrap(cue).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 1000);
            var file = append(plain(), cue, chunk("LIST", "adtlxx".getBytes(StandardCharsets.ISO_8859_1)));
            assertEquals(List.of(micros(100), micros(200)), chapters(file).stream().map(Chapter::startMicros).toList());
        }
    }
}
