package com.github.auties00.smalt.container.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.internal.tags.FlacCuesheet;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers FLAC {@code CUESHEET} blocks as chapters: read by {@link FlacDemuxer} and the tag editor from cue sheets
 * metaflac imports and from hand-built blocks, and written by {@link RawMuxer} and the tag editor in a form
 * metaflac exports with the same offsets.
 */
@DisplayName("FLAC cue sheets")
class FlacCuesheetTest {
    private static final int RATE = 44100;

    private static byte[] flac() {
        return Fixtures.read(Fixtures.tone("flac", 440, RATE, 2, 10, List.of("-c:a", "flac", "-sample_fmt", "s16")));
    }

    private static List<Chapter> chapters(byte[] file) {
        try (var demuxer = new FlacDemuxer(MediaSource.of(file))) {
            return demuxer.chapters();
        }
    }

    private static long micros(long samples) {
        return samples * 1_000_000L / RATE;
    }

    private static byte[] withTags(byte[] file, MediaTags tags) {
        var source = MuxSupport.demux(file);
        var sink = MediaSink.memory();
        try (var muxer = new RawMuxer(sink)) {
            source.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            source.order().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    private static String metaflac(Path file, String... arguments) {
        Oracle.assume("metaflac");
        var list = new ArrayList<>(List.of(arguments));
        list.add(file.toString());
        return new String(Oracle.run("metaflac", Duration.ofMinutes(1), null, list), StandardCharsets.UTF_8);
    }

    // exported to a file: the Windows build of metaflac writes nothing for "--export-cuesheet-to=-"
    private static String exportCuesheet(Path file) throws IOException {
        var cue = Files.createTempFile(file.toAbsolutePath().getParent(), "export-", ".cue");
        Files.delete(cue);
        metaflac(file, "--export-cuesheet-to=" + cue);
        var text = Files.readString(cue, StandardCharsets.UTF_8);
        Files.delete(cue);
        assertTrue(text.contains("TRACK"), text);
        return text;
    }

    /** Builds a cue sheet block body by hand, as RFC 9639 section 8.7 lays it out. */
    private static byte[] cuesheet(boolean cd, long leadOut, long[][] tracks) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[128]);
        out.writeBytes(new byte[8]);
        var flags = new byte[259];
        flags[0] = (byte) (cd ? 0x80 : 0);
        out.writeBytes(flags);
        out.write(tracks.length + 1);
        var number = 1;
        for (var track : tracks) {
            out.writeBytes(u64(track[0]));
            out.write(number++);
            out.writeBytes(new byte[12 + 14]);
            out.write((track.length - 1) / 2);
            for (var point = 1; point + 1 < track.length; point += 2) {
                out.writeBytes(u64(track[point + 1]));
                out.write((int) track[point]);
                out.writeBytes(new byte[3]);
            }
        }
        out.writeBytes(u64(leadOut));
        out.write(cd ? 170 : 255);
        out.writeBytes(new byte[12 + 14]);
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] u64(long value) {
        var bytes = new byte[8];
        for (var index = 0; index < 8; index++) {
            bytes[index] = (byte) (value >>> (56 - 8 * index));
        }
        return bytes;
    }

    @Test
    @DisplayName("decodes each track at its index point 1, and at its first index point without one")
    void decode() {
        var body = cuesheet(false, 441000, new long[][]{{0, 1, 0}, {88200, 0, 0, 1, 44100, 2, 88200}, {300000, 3, 50}});
        assertEquals(List.of(Chapter.of(0, micros(132300), null), Chapter.of(micros(132300), micros(300050), null),
                Chapter.of(micros(300050), micros(441000), null)), FlacCuesheet.decode(body, 0, body.length, RATE));
        assertTrue(FlacCuesheet.decode(cuesheet(true, 1000, new long[0][]), 0, 396 + 36, RATE).isEmpty());
    }

    @Test
    @DisplayName("rejects truncated cue sheets, tracks without index points and decreasing offsets")
    void malformed() {
        var body = cuesheet(false, 441000, new long[][]{{0, 1, 0}, {88200, 1, 0}});
        assertThrows(MediaException.Malformed.class, () -> FlacCuesheet.decode(body, 0, 300, RATE));
        assertThrows(MediaException.Malformed.class, () -> FlacCuesheet.decode(body, 0, body.length - 10, RATE));
        var noIndex = cuesheet(false, 441000, new long[][]{{0}});
        assertThrows(MediaException.Malformed.class, () -> FlacCuesheet.decode(noIndex, 0, noIndex.length, RATE));
        var decreasing = cuesheet(false, 441000, new long[][]{{88200, 1, 0}, {0, 1, 0}});
        assertThrows(MediaException.Malformed.class, () -> FlacCuesheet.decode(decreasing, 0, decreasing.length, RATE));
    }

    @Test
    @DisplayName("encodes chapters into a cue sheet that decodes to the same starts")
    void encode() {
        var chapters = List.of(Chapter.of(0, 2_000_000, "a"), Chapter.of(2_000_000, 7_500_000, "b"),
                Chapter.of(7_500_000, 10_000_000, null));
        var body = FlacCuesheet.encode(chapters, RATE, 441000);
        assertEquals(396 + 4 * 36 + 3 * 12, body.length);
        assertEquals(List.of(Chapter.of(0, 2_000_000, null), Chapter.of(2_000_000, 7_500_000, null),
                Chapter.of(7_500_000, 10_000_000, null)), FlacCuesheet.decode(body, 0, body.length, RATE));
        var many = new ArrayList<Chapter>();
        for (var index = 0; index < FlacCuesheet.MAX_CHAPTERS + 1; index++) {
            many.add(Chapter.of(index * 1000L, index * 1000L + 1000, null));
        }
        assertEquals(null, FlacCuesheet.encode(many, RATE, 0));
        assertEquals(FlacCuesheet.MAX_CHAPTERS, FlacCuesheet.decode(FlacCuesheet.encode(many.subList(0,
                FlacCuesheet.MAX_CHAPTERS), RATE, 0), 0, 396 + 255 * 36 + 254 * 12, RATE).size());
    }

    @Test
    @DisplayName("reads the cue sheet metaflac imports from a CD cue file")
    void metaflacImport() throws IOException {
        Oracle.assume("metaflac");
        var directory = Files.createTempDirectory("flac-cuesheet");
        var file = directory.resolve("tone.flac");
        Files.write(file, flac());
        var cue = directory.resolve("tone.cue");
        Files.writeString(cue, """
                FILE "tone.wav" WAVE
                  TRACK 01 AUDIO
                    INDEX 01 00:00:00
                  TRACK 02 AUDIO
                    INDEX 00 00:03:00
                    INDEX 01 00:04:00
                  TRACK 03 AUDIO
                    INDEX 01 00:07:37
                """);
        metaflac(file, "--import-cuesheet-from=" + cue);
        var data = Files.readAllBytes(file);
        var expected = List.of(Chapter.of(0, micros(4 * RATE), null), Chapter.of(micros(4 * RATE), micros(7 * RATE + 37 * 588), null),
                Chapter.of(micros(7 * RATE + 37 * 588), micros(10L * RATE), null));
        assertEquals(expected, chapters(data));
        assertEquals(expected, TagEditor.read(MediaSource.of(data)).chapters());
        // an edit that keeps the chapters keeps the block and writes no chapter comments
        var edited = TagEditor.write(data, TagEditor.read(MediaSource.of(data)).toBuilder().add("artist", "x").build(),
                TagWriteOptions.defaults());
        assertEquals(expected, chapters(edited));
        assertFalse(new String(edited, StandardCharsets.ISO_8859_1).contains("CHAPTER001"));
        var editedFile = directory.resolve("edited.flac");
        Files.write(editedFile, edited);
        // the FILE line names the exported file
        assertEquals(exportCuesheet(file).lines().filter(line -> !line.startsWith("FILE")).toList(),
                exportCuesheet(editedFile).lines().filter(line -> !line.startsWith("FILE")).toList());
    }

    @Test
    @DisplayName("writes chapters as a cue sheet that metaflac exports with the same offsets")
    void metaflacExport() throws IOException {
        var chapters = List.of(Chapter.of(0, 2_500_000, "first"), Chapter.of(2_500_000, 6_000_000, "second"),
                Chapter.of(6_000_000, 10_000_000, "third"));
        var tags = MediaTags.builder().add("title", "cues").chapters(chapters).build();
        var muxed = withTags(flac(), tags);
        var edited = TagEditor.write(flac(), tags, TagWriteOptions.defaults());
        for (var output : List.of(muxed, edited)) {
            // the comments keep the titles and win over the cue sheet
            assertEquals(chapters, chapters(output));
            var file = MuxSupport.save("cuesheet-" + Arrays.hashCode(output) + ".flac", output);
            var exported = exportCuesheet(file);
            var offsets = Pattern.compile("INDEX 01 (\\S+)").matcher(exported).results().map(match -> match.group(1)).toList();
            assertEquals(3, offsets.size(), exported);
            // a cue sheet that is not a CD-DA one is exported with sample offsets
            assertEquals(List.of("0", Long.toString(2_500_000L * RATE / 1_000_000), Long.toString(6L * RATE)),
                    offsets.stream().map(offset -> offset.contains(":") ? Long.toString(msfToSamples(offset)) : offset)
                            .toList(), exported);
            var stripped = TagEditor.write(output, MediaTags.builder().add("title", "cues").build(), TagWriteOptions.defaults());
            assertTrue(chapters(stripped).isEmpty());
            assertFalse(new String(stripped, StandardCharsets.ISO_8859_1).contains("CHAPTER001"));
        }
    }

    @Test
    @DisplayName("prefers chapter comments over the cue sheet, and keeps a damaged cue sheet from failing")
    void precedence() {
        var body = FlacCuesheet.encode(List.of(Chapter.of(0, 4_000_000, null), Chapter.of(4_000_000, 10_000_000, null)),
                RATE, 10L * RATE);
        var cueOnly = insertBlock(flac(), 5, body);
        assertEquals(List.of(Chapter.of(0, 4_000_000, null), Chapter.of(4_000_000, 10_000_000, null)), chapters(cueOnly));
        var damaged = cueOnly.clone();
        // the track count, past the end of the block
        damaged[indexOfCuesheet(damaged) + 4 + 395] = (byte) 200;
        assertTrue(chapters(damaged).isEmpty());
        assertTrue(TagEditor.read(MediaSource.of(damaged)).chapters().isEmpty());
        var tags = MediaTags.builder().chapters(List.of(Chapter.of(0, 5_000_000, "titled"),
                Chapter.of(5_000_000, 10_000_000, "two"))).build();
        assertEquals(tags.chapters(), chapters(withTags(cueOnly, tags)));
    }

    /** Inserts a metadata block after STREAMINFO. */
    private static byte[] insertBlock(byte[] file, int type, byte[] body) {
        var streamInfoEnd = 4 + 4 + 34;
        var last = (file[4] & 0x80) != 0;
        var out = new java.io.ByteArrayOutputStream();
        out.write(file, 0, 4);
        out.write(file[4] & 0x7F);
        out.write(file, 5, streamInfoEnd - 5);
        out.write(type | (last ? 0x80 : 0));
        out.write(body.length >>> 16);
        out.write(body.length >>> 8);
        out.write(body.length);
        out.writeBytes(body);
        out.write(file, streamInfoEnd, file.length - streamInfoEnd);
        return out.toByteArray();
    }

    private static int indexOfCuesheet(byte[] file) {
        var offset = 4;
        while (offset + 4 <= file.length) {
            var type = file[offset] & 0x7F;
            var length = (file[offset + 1] & 0xFF) << 16 | (file[offset + 2] & 0xFF) << 8 | file[offset + 3] & 0xFF;
            if (type == 5) {
                return offset;
            }
            if ((file[offset] & 0x80) != 0) {
                break;
            }
            offset += 4 + length;
        }
        throw new AssertionError("no CUESHEET block");
    }

    private static long msfToSamples(String msf) {
        var parts = msf.split(":");
        return (Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1])) * RATE + Long.parseLong(parts[2]) * 588;
    }
}
