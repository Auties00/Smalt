package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.internal.tags.Mp4TimecodeTrack;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.validate.Findings;
import com.github.auties00.smalt.testkit.validate.IsoBmffValidator;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers QuickTime timecode tracks: the timecode {@link Mp4Demuxer} reads from ffmpeg's {@code tmcd} tracks, and the
 * {@code tmcd} track {@link Mp4Muxer} and {@link FragmentedMp4Muxer} write from the {@code timecode} tag, read back
 * by the demuxer and by ffprobe.
 */
@DisplayName("MP4 timecode tracks")
class Mp4TimecodeTest {
    private static Path video(String extension, String rate, List<String> extra) {
        var options = new ArrayList<>(List.of("-c:v", "mpeg4", "-q:v", "5"));
        options.addAll(extra);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc=rate=" + rate + ":duration=1:size=64x48"),
                options);
    }

    private static Timecode timecodeOf(byte[] file) {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
            return demuxer.timecode().orElseThrow();
        }
    }

    private static List<String> probedTimecodes(Path file) {
        var probe = new String(com.github.auties00.smalt.testkit.Oracle.run(com.github.auties00.smalt.testkit.Oracle.Tool.FFPROBE,
                java.time.Duration.ofMinutes(1), List.of("-v", "error", "-show_entries",
                        "stream=codec_type,codec_tag_string:stream_tags=timecode", "-of", "compact", file.toString())),
                java.nio.charset.StandardCharsets.UTF_8);
        return probe.lines().filter(line -> line.startsWith("stream")).toList();
    }

    private static byte[] mux(ContainerProbe.Demuxed source, MediaTags tags, Mp4MuxerOptions options) {
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, options)) {
            source.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            source.order().forEach(muxer::writePacket);
            muxer.finish();
        }
        return MuxSupport.validated("timecode", sink.toByteArray());
    }

    private static ContainerProbe.Demuxed withoutData(ContainerProbe.Demuxed demuxed) {
        var tracks = new ArrayList<Track>();
        var ids = new ArrayList<Integer>();
        for (var track : demuxed.tracks()) {
            if (track.kind() != TrackKind.DATA) {
                tracks.add(track);
                ids.add(track.id());
            }
        }
        var order = new ArrayList<Packet>();
        for (var packet : demuxed.order()) {
            if (ids.contains(packet.trackId())) {
                order.add(packet);
            }
        }
        return new ContainerProbe.Demuxed(tracks, demuxed.packets(), order, demuxed.metadata(), demuxed.durationMicros());
    }

    @ParameterizedTest
    @CsvSource({"mov, 30000/1001, 01:00:00;00", "mp4, 25, 10:11:12:13", "mov, 60000/1001, 00:59:59;59",
            "mp4, 24000/1001, 23:00:00:23", "mov, 30, 00:00:00:00"})
    @DisplayName("reads the timecode of an ffmpeg tmcd track as ffprobe does")
    void read(String extension, String rate, String text) {
        var file = video(extension, rate, List.of("-timecode", text));
        var timecode = timecodeOf(Fixtures.read(file));
        assertEquals(text, timecode.toString());
        var streams = probedTimecodes(file);
        assertTrue(streams.stream().anyMatch(line -> line.contains("tmcd") && line.endsWith("timecode=" + text)),
                streams.toString());
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(file)))) {
            assertTrue(demuxer.tracks().stream().anyMatch(track -> track instanceof DataTrack data
                    && data.handler().orElse("").equals("tmcd")), demuxer.tracks().toString());
        }
    }

    @Test
    @DisplayName("reports no timecode without a timecode track")
    void absent() {
        try (var demuxer = new Mp4Demuxer(MediaSource.of(Fixtures.read(video("mp4", "25", List.of()))))) {
            assertTrue(demuxer.timecode().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"30000/1001, 01:00:00;00, QUICKTIME", "30000/1001, 00:10:00;02, MP4", "25, 10:00:00:24, MP4",
            "24000/1001, 12:34:56:23, QUICKTIME", "60000/1001, 23:59:59;59, MP4"})
    @DisplayName("writes the timecode tag as a tmcd track that the demuxer and ffprobe read")
    void write(String rate, String text, String brand) {
        var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", rate, List.of()))));
        var options = brand.equals("QUICKTIME") ? Mp4MuxerOptions.quickTime() : Mp4MuxerOptions.mp4();
        var output = mux(source, MediaTags.builder().add("timecode", text).add("title", "clip").build(), options);
        assertEquals(text, timecodeOf(output).toString());
        var file = MuxSupport.save("timecode-" + brand + "-" + text.replace(':', '-').replace(';', '_') + ".mov", output);
        var streams = probedTimecodes(file);
        assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=data") && line.contains("tmcd")
                && line.endsWith("timecode=" + text)), streams.toString());
        assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=video") && line.endsWith("timecode=" + text)),
                "the video track references the timecode track: " + streams);
        // the tag becomes the track, not an iTunes item
        assertFalse(new String(output, java.nio.charset.StandardCharsets.ISO_8859_1).contains("----"), "no freeform atom");
        try (var demuxer = new Mp4Demuxer(MediaSource.of(output))) {
            assertFalse(demuxer.metadata().containsKey("timecode"));
            assertTrue(demuxer.metadata().containsValue("clip"), demuxer.metadata().toString());
        }
        MuxSupport.assertFfmpegClean("timecode-clean.mov", output);
    }

    @Test
    @DisplayName("writes a drop-frame timecode at 30000/1001 frames per second without a video track")
    void audioOnly() {
        var source = MuxSupport.demux(Fixtures.read(Fixtures.tone("m4a", 440, 48000, 1, 1, List.of("-c:a", "aac"))));
        var output = mux(source, MediaTags.builder().add("timecode", "00:00:10;00").build(), Mp4MuxerOptions.mp4());
        assertEquals("00:00:10;00", timecodeOf(output).toString());
        assertTrue(probedTimecodes(MuxSupport.save("timecode-audio.mp4", output)).stream()
                .anyMatch(line -> line.endsWith("timecode=00:00:10;00")));
    }

    @Test
    @DisplayName("rejects an address that is invalid at the video's rate, or that has no rate to count at")
    void invalid() {
        var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> mux(source,
                MediaTags.builder().add("timecode", "00:00:00:29").build(), Mp4MuxerOptions.mp4()));
        var audio = MuxSupport.demux(Fixtures.read(Fixtures.tone("m4a", 440, 48000, 1, 1, List.of("-c:a", "aac"))));
        assertThrows(IllegalArgumentException.class, () -> mux(audio,
                MediaTags.builder().add("timecode", "00:00:01:00").build(), Mp4MuxerOptions.mp4()));
        try (var muxer = new Mp4Muxer(MediaSink.memory())) {
            assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                    .add("timecode", "one o'clock").build()));
        }
    }

    /** Returns the offset and end of every {@code trak} of the movie whose handler is {@code tmcd}. */
    private static List<int[]> timecodeTraks(byte[] file) {
        var result = new ArrayList<int[]>();
        for (var moov : FragmentedMp4MuxerTest.boxes(file)) {
            if (!moov.type().equals("moov")) {
                continue;
            }
            var buffer = ByteBuffer.wrap(file);
            for (var trak = moov.offset() + 8; trak + 8 <= moov.offset() + moov.size(); trak += buffer.getInt(trak)) {
                if (!new String(file, trak + 4, 4, StandardCharsets.ISO_8859_1).equals("trak")) {
                    continue;
                }
                var text = new String(file, trak, buffer.getInt(trak), StandardCharsets.ISO_8859_1);
                var hdlr = text.indexOf("hdlr");
                if (hdlr >= 0 && text.startsWith("tmcd", hdlr + 12)) {
                    result.add(new int[]{trak, trak + buffer.getInt(trak)});
                }
            }
        }
        return result;
    }

    /** Writes a free-form {@code timecode} iTunes item, as files of other writers hold it, with no timecode track. */
    private static byte[] withTimecodeItem(byte[] file, String text) {
        var edited = TagEditor.write(file, MediaTags.builder().add("timecodx", text).build(), TagWriteOptions.defaults());
        var bytes = new String(edited, StandardCharsets.ISO_8859_1);
        var at = bytes.indexOf("timecodx");
        assertTrue(at > 0 && bytes.indexOf("timecodx", at + 1) < 0);
        edited[at + 7] = 'e';
        return edited;
    }

    @Test
    @DisplayName("reads a timecode iTunes item when the file has no timecode track, the track winning otherwise")
    void editedItem() {
        var plain = withTimecodeItem(Fixtures.read(video("mp4", "30000/1001", List.of())), "00:10:00;00");
        assertEquals("00:10:00;00", MuxSupport.demux(plain).metadata().get("timecode"));
        assertEquals(Timecode.parse("00:10:00;00", 30), timecodeOf(plain));
        // an unchanged item stays an item
        var kept = TagEditor.write(plain, TagEditor.read(MediaSource.of(plain)), TagWriteOptions.defaults());
        assertEquals("00:10:00;00", MuxSupport.demux(kept).metadata().get("timecode"));
        assertTrue(timecodeTraks(kept).isEmpty(), "no timecode track for an unchanged item");
        var tracked = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
        var retagged = TagEditor.write(tracked, MediaTags.builder().add("timecode", "11:00:00:00").build(),
                TagWriteOptions.defaults());
        assertEquals("11:00:00:00", timecodeOf(retagged).toString());
    }

    @Nested
    @DisplayName("tag edits")
    class TagEdits {
        private static byte[] edit(byte[] file, String timecode) {
            var builder = TagEditor.read(MediaSource.of(file)).toBuilder().remove("timecode");
            if (timecode != null) {
                builder.add("timecode", timecode);
            }
            var edited = TagEditor.write(file, builder.build(), TagWriteOptions.defaults());
            try {
                Validators.assertValid("timecode-original.mov", file);
            } catch (AssertionError exception) {
                // MP4Box reports the 2 bytes ffmpeg writes after a tmcd sample entry, which an edit that keeps the
                // track keeps too, so such a file is checked by the ISOBMFF validator alone
                var findings = new Findings("timecode-edit");
                IsoBmffValidator.validate(edited, findings, IsoBmffValidator.Kind.FILE, java.util.Map.of());
                findings.assertNone("the ISOBMFF validator");
                return edited;
            }
            return MuxSupport.validated("timecode-edit", edited);
        }

        private static void assertSameMedia(byte[] expected, byte[] actual) {
            var before = withoutData(MuxSupport.demux(expected));
            var after = withoutData(MuxSupport.demux(actual));
            var map = new java.util.HashMap<Integer, Integer>();
            for (var index = 0; index < before.tracks().size(); index++) {
                map.put(before.tracks().get(index).id(), after.tracks().get(index).id());
            }
            assertEquals(before.tracks().size(), after.tracks().size());
            MuxSupport.assertSamePayloads(before, after, map);
        }

        private static void assertProbed(String name, byte[] file, String text) {
            var streams = probedTimecodes(MuxSupport.save(name, file));
            assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=data") && line.contains("tmcd")
                    && line.endsWith("timecode=" + text)), streams.toString());
            assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=video")
                    && line.endsWith("timecode=" + text)), "the video track references the timecode track: " + streams);
        }

        @ParameterizedTest
        @CsvSource({"mov, 25, 10:00:00:00, 11:22:33:24", "mp4, 30000/1001, 01:00:00;00, 00:10:00;02",
                "mov, 30000/1001, 01:00:00;00, 02:00:00:00", "mp4, 24000/1001, 00:00:00:00, 23:59:59:23",
                "mov, 60000/1001, 00:59:59;59, 01:00:00;04"})
        @DisplayName("replaces the timecode track of a file, read back by the demuxer, the tag editor and ffprobe")
        void replace(String extension, String rate, String original, String text) {
            var file = Fixtures.read(video(extension, rate, List.of("-timecode", original)));
            assertEquals(original, TagEditor.read(MediaSource.of(file)).get("timecode").orElseThrow());
            var edited = edit(file, text);
            assertEquals(text, timecodeOf(edited).toString());
            assertEquals(text, TagEditor.read(MediaSource.of(edited)).get("timecode").orElseThrow());
            assertEquals(1, timecodeTraks(edited).size(), "one timecode track");
            assertFalse(new String(edited, StandardCharsets.ISO_8859_1).contains("----"), "no freeform atom");
            assertProbed("timecode-replace-" + extension + ".mov", edited, text);
            assertSameMedia(file, edited);
            MuxSupport.assertFfmpegClean("timecode-replace-clean." + extension, edited);
        }

        @ParameterizedTest
        @CsvSource({"mov, 25, 10:11:12:13", "mp4, 30000/1001, 00:00:10;00"})
        @DisplayName("adds a timecode track to a file without one, counted at the video's frame rate")
        void add(String extension, String rate, String text) {
            var file = Fixtures.read(video(extension, rate, List.of()));
            var edited = edit(file, text);
            assertEquals(text, timecodeOf(edited).toString());
            assertEquals(1, timecodeTraks(edited).size());
            assertProbed("timecode-add-" + extension + ".mov", edited, text);
            assertSameMedia(file, edited);
        }

        @Test
        @DisplayName("removes the timecode track and its references when the tag is removed")
        void remove() {
            var file = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
            var edited = edit(file, null);
            try (var demuxer = new Mp4Demuxer(MediaSource.of(edited))) {
                assertTrue(demuxer.timecode().isEmpty());
                assertTrue(demuxer.tracks().stream().noneMatch(track -> track instanceof DataTrack), demuxer.tracks().toString());
            }
            assertTrue(timecodeTraks(edited).isEmpty());
            assertFalse(new String(edited, StandardCharsets.ISO_8859_1).contains("tmcd"), "no tmcd reference is left");
            assertTrue(TagEditor.read(MediaSource.of(edited)).get("timecode").isEmpty());
            assertSameMedia(file, edited);
            MuxSupport.assertFfmpegClean("timecode-removed.mov", edited);
        }

        @Test
        @DisplayName("keeps the timecode track byte for byte when the timecode is unchanged")
        void unchanged() {
            var file = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
            var edited = edit(file, "10:00:00:00");
            var before = timecodeTraks(file).getFirst();
            var after = timecodeTraks(edited).getFirst();
            assertTrue(java.util.Arrays.equals(file, before[0], before[1], edited, after[0], after[1]));
            assertEquals("10:00:00:00", timecodeOf(edited).toString());
        }

        @Test
        @DisplayName("keeps the track tags of a replaced timecode track")
        void trackTags() {
            var file = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
            var tags = TagEditor.read(MediaSource.of(file));
            var timecodeTrack = tags.tracks().stream().filter(track -> track.trackId() == 2).findFirst().orElseThrow();
            var renamed = tags.toBuilder().track(timecodeTrack.withName("Reel 7")).set("timecode", "12:00:00:00").build();
            var edited = MuxSupport.validated("timecode-named", TagEditor.write(file, renamed, TagWriteOptions.defaults()));
            var reread = TagEditor.read(MediaSource.of(edited));
            assertEquals("12:00:00:00", reread.get("timecode").orElseThrow());
            assertEquals(java.util.Optional.of("Reel 7"), reread.tracks().stream().filter(track -> track.trackId() == 2)
                    .findFirst().orElseThrow().name());
        }

        @Test
        @DisplayName("rejects an address the video's rate cannot count, and one for a file without video")
        void invalid() {
            var file = Fixtures.read(video("mov", "25", List.of()));
            var failure = assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class,
                    () -> edit(file, "00:00:00:29"));
            assertTrue(failure.getMessage().contains("timecode"), failure.getMessage());
            var tracked = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> edit(tracked, "00:00:01;00"));
            var audio = Fixtures.read(Fixtures.tone("m4a", 440, 48000, 1, 1, List.of("-c:a", "aac")));
            assertThrows(com.github.auties00.smalt.MediaException.Unsupported.class, () -> edit(audio, "00:00:01:00"));
            var drop = edit(audio, "00:00:10;00");
            assertEquals("00:00:10;00", timecodeOf(drop).toString());
        }

        @ParameterizedTest
        @CsvSource({"25, 10:00:00:00, 11:22:33:24", "30000/1001, 01:00:00;00, 05:00:00:00",
                "30000/1001, 01:00:00:00, 00:10:00;02"})
        @DisplayName("writes the timecode of a fragmented file into the sample its first fragment holds")
        void fragmented(String rate, String original, String text) {
            var file = Fixtures.read(video("mp4", rate, List.of("-timecode", original, "-movflags", "frag_keyframe+empty_moov")));
            assertEquals(original, TagEditor.read(MediaSource.of(file)).get("timecode").orElseThrow());
            var edited = edit(file, text);
            assertEquals(text, timecodeOf(edited).toString());
            assertEquals(text, TagEditor.read(MediaSource.of(edited)).get("timecode").orElseThrow());
            try (var demuxer = new Mp4Demuxer(MediaSource.of(new ByteArrayInputStream(edited), edited.length))) {
                assertEquals(text, demuxer.timecode().orElseThrow().toString(), "read from a stream");
            }
            assertProbed("timecode-fragmented-edit.mp4", edited, text);
            assertSameMedia(file, edited);
        }

        @Test
        @DisplayName("writes and removes the timecode of a fragmented file Smalt muxed, in place")
        void fragmentedMuxed() {
            var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
            var file = Fragmented.muxFragmented(source, MediaTags.builder().add("timecode", "10:00:00:00").build(),
                    Fragmented.Output.SEEKABLE, FragmentedMp4MuxerOptions.Brand.MP4);
            var edited = edit(file, "20:00:00:00");
            assertEquals(file.length, edited.length, "the sample is written in place");
            assertEquals("20:00:00:00", timecodeOf(edited).toString());
            assertSameMedia(file, edited);
            var removed = edit(file, null);
            assertEquals(file.length, removed.length, "the track fragment becomes a free box in place");
            assertRemoved(file, removed);
        }

        @Test
        @DisplayName("corrects the data offsets of a track fragment that counted from the removed timecode track's data")
        void fragmentedChained() {
            var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
            var muxed = Fragmented.muxFragmented(source, MediaTags.builder().add("timecode", "10:00:00:00").build(),
                    Fragmented.Output.SEEKABLE, FragmentedMp4MuxerOptions.Brand.MP4);
            var file = chainTimecodeFirst(muxed);
            assertFalse(java.util.Arrays.equals(muxed, file), "the timecode track fragment was moved first");
            assertSameMedia(muxed, file);
            assertEquals("10:00:00:00", timecodeOf(file).toString());
            var removed = edit(file, null);
            assertEquals(file.length, removed.length, "the track fragment becomes a free box in place");
            assertRemoved(file, removed);
            assertValidFile("timecode-chained-removed", removed);
            var compressed = compressFragments(file);
            var compressedRemoved = TagEditor.write(compressed,
                    TagEditor.read(MediaSource.of(compressed)).toBuilder().remove("timecode").build(),
                    TagWriteOptions.defaults());
            assertTrue(java.util.Arrays.equals(removed, decompressFragments(compressedRemoved)),
                    "a compressed fragment gets the same correction");
        }

        // moves the timecode track fragment of the moof holding it in front of the others and makes every track
        // fragment of that moof count its data from the end of the previous one's (no tfhd base flags), rewriting the
        // trun data offsets so that every sample stays where it is
        private static byte[] chainTimecodeFirst(byte[] file) {
            var out = file.clone();
            var buffer = ByteBuffer.wrap(out);
            for (var at = 0; at + 8 <= out.length; at += buffer.getInt(at)) {
                if (!new String(out, at + 4, 4, StandardCharsets.ISO_8859_1).equals("moof")) {
                    continue;
                }
                var moofEnd = at + buffer.getInt(at);
                var trafs = new ArrayList<int[]>();
                for (var child = at + 8; child < moofEnd; child += buffer.getInt(child)) {
                    if (new String(out, child + 4, 4, StandardCharsets.ISO_8859_1).equals("traf")) {
                        trafs.add(new int[]{child, buffer.getInt(child)});
                    }
                }
                var timecode = -1;
                for (var index = 0; index < trafs.size(); index++) {
                    if (buffer.getInt(trafs.get(index)[0] + 8 + 12) == 2) {
                        timecode = index;
                    }
                }
                if (timecode <= 0) {
                    continue;
                }
                var order = new ArrayList<int[]>();
                order.add(trafs.get(timecode));
                for (var index = 0; index < trafs.size(); index++) {
                    if (index != timecode) {
                        order.add(trafs.get(index));
                    }
                }
                var reordered = new ByteArrayOutputStream();
                for (var traf : order) {
                    reordered.write(file, traf[0], traf[1]);
                }
                var bytes = reordered.toByteArray();
                System.arraycopy(bytes, 0, out, trafs.getFirst()[0], bytes.length);
                var previousEnd = -1L;
                for (var traf = trafs.getFirst()[0]; traf < trafs.getFirst()[0] + bytes.length; traf += buffer.getInt(traf)) {
                    var tfhd = traf + 8;
                    assertEquals(0x20000, buffer.getInt(tfhd + 8) & 0xFFFFFF, "default-base-is-moof only");
                    buffer.putInt(tfhd + 8, buffer.getInt(tfhd + 8) & 0xFF000000);
                    var base = previousEnd < 0 ? at : previousEnd;
                    var trun = tfhd + buffer.getInt(tfhd);
                    while (!new String(out, trun + 4, 4, StandardCharsets.ISO_8859_1).equals("trun")) {
                        trun += buffer.getInt(trun);
                    }
                    var flags = buffer.getInt(trun + 8) & 0xFFFFFF;
                    assertEquals(0x701, flags & 0x701, "a data offset, and durations, sizes and flags per sample");
                    var count = buffer.getInt(trun + 12);
                    var position = at + (long) buffer.getInt(trun + 16);
                    buffer.putInt(trun + 16, Math.toIntExact(position - base));
                    var entry = Integer.bitCount(flags & 0xF00) * 4;
                    var cursor = trun + 20 + ((flags & 4) != 0 ? 4 : 0);
                    for (var sample = 0; sample < count; sample++) {
                        position += buffer.getInt(cursor + 4) & 0xFFFFFFFFL;
                        cursor += entry;
                    }
                    previousEnd = position;
                }
            }
            return out;
        }

        private static void assertRemoved(byte[] file, byte[] edited) {
            try (var demuxer = new Mp4Demuxer(MediaSource.of(edited))) {
                assertTrue(demuxer.timecode().isEmpty());
                assertTrue(demuxer.tracks().stream().noneMatch(track -> track instanceof DataTrack),
                        demuxer.tracks().toString());
            }
            assertTrue(TagEditor.read(MediaSource.of(edited)).get("timecode").isEmpty());
            assertTrue(timecodeTraks(edited).isEmpty());
            assertFalse(new String(edited, StandardCharsets.ISO_8859_1).contains("tmcd"), "no tmcd box is left");
            assertSameMedia(file, edited);
        }

        private static void assertValidFile(String name, byte[] file) {
            var findings = new Findings(name);
            IsoBmffValidator.validate(file, findings, IsoBmffValidator.Kind.FILE, java.util.Map.of());
            findings.assertNone("the ISOBMFF validator");
        }

        @ParameterizedTest
        @CsvSource({"25, 10:00:00:00, 0", "30000/1001, 01:00:00;00, 0", "25, 10:00:00:00, 6000"})
        @DisplayName("removes the timecode track of a fragmented file, its track fragments turned into free boxes")
        void fragmentedRemove(String rate, String original, int titleLength) {
            var file = Fixtures.read(video("mp4", rate, List.of("-timecode", original, "-movflags", "frag_keyframe+empty_moov")));
            var tags = TagEditor.read(MediaSource.of(file)).toBuilder().remove("timecode");
            if (titleLength > 0) {
                // a title that does not fit the movie box moves every fragment and its explicit base data offsets
                tags.set("title", "t".repeat(titleLength));
            }
            var edited = TagEditor.write(file, tags.build(), TagWriteOptions.defaults());
            assertValidFile("timecode-fragmented-remove", edited);
            if (titleLength == 0) {
                assertEquals(file.length, edited.length, "the track fragments become free boxes in place");
            } else {
                assertTrue(edited.length > file.length + titleLength / 2, "the movie box grows");
            }
            assertRemoved(file, edited);
            var streams = probedTimecodes(MuxSupport.save("timecode-fragmented-removed.mp4", edited));
            assertTrue(streams.stream().noneMatch(line -> line.contains("timecode=") || line.contains("codec_type=data")),
                    streams.toString());
            MuxSupport.assertFfmpegClean("timecode-fragmented-removed-clean.mp4", edited);
        }

        @Test
        @DisplayName("writes a fragmented file's timecode in place while a longer movie box moves the fragments")
        void fragmentedMoved() {
            var file = Fixtures.read(video("mp4", "25", List.of("-timecode", "10:00:00:00", "-movflags", "frag_keyframe+empty_moov")));
            var tags = TagEditor.read(MediaSource.of(file)).toBuilder().set("timecode", "11:00:00:00")
                    .set("title", "t".repeat(6000)).build();
            var edited = TagEditor.write(file, tags, TagWriteOptions.defaults());
            assertTrue(edited.length > file.length + 3000, "the movie box grows");
            assertValidFile("timecode-fragmented-moved", edited);
            assertEquals("11:00:00:00", timecodeOf(edited).toString());
            assertEquals("11:00:00:00", TagEditor.read(MediaSource.of(edited)).get("timecode").orElseThrow());
            assertProbed("timecode-fragmented-moved.mp4", edited, "11:00:00:00");
            assertSameMedia(file, edited);
        }

        @Test
        @DisplayName("removes the timecode track named by compressed movie fragments, recompressing them")
        void compressedFragmentRemove() {
            var file = Fixtures.read(video("mp4", "25", List.of("-timecode", "10:00:00:00", "-movflags", "frag_keyframe+empty_moov")));
            var compressed = compressFragments(file);
            assertEquals("10:00:00:00", TagEditor.read(MediaSource.of(compressed)).get("timecode").orElseThrow());
            var edited = TagEditor.write(compressed,
                    TagEditor.read(MediaSource.of(compressed)).toBuilder().remove("timecode").build(),
                    TagWriteOptions.defaults());
            var plain = TagEditor.write(file, TagEditor.read(MediaSource.of(file)).toBuilder().remove("timecode").build(),
                    TagWriteOptions.defaults());
            // the recompressed fragments decompress to the fragments of the same edit of the uncompressed file
            assertTrue(java.util.Arrays.equals(plain, decompressFragments(edited)));
            try (var demuxer = new Mp4Demuxer(MediaSource.of(edited))) {
                assertTrue(demuxer.timecode().isEmpty());
                assertTrue(demuxer.tracks().stream().noneMatch(track -> track instanceof DataTrack));
            }
            assertSameMedia(file, edited);
        }

        // replaces every top-level moof by a !mof box holding its gzip-compressed payload
        private static byte[] compressFragments(byte[] file) {
            var out = new ByteArrayOutputStream();
            var buffer = ByteBuffer.wrap(file);
            for (var at = 0; at + 8 <= file.length; ) {
                var size = buffer.getInt(at);
                if (new String(file, at + 4, 4, StandardCharsets.ISO_8859_1).equals("moof")) {
                    var gzip = new ByteArrayOutputStream();
                    try (var stream = new java.util.zip.GZIPOutputStream(gzip)) {
                        stream.write(file, at + 8, size - 8);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                    out.writeBytes(ByteBuffer.allocate(8).putInt(8 + gzip.size())
                            .put("!mof".getBytes(StandardCharsets.ISO_8859_1)).array());
                    out.writeBytes(gzip.toByteArray());
                } else {
                    out.write(file, at, size);
                }
                at += size;
            }
            return out.toByteArray();
        }

        // replaces every top-level !mof box by the moof its payload decompresses to
        private static byte[] decompressFragments(byte[] file) {
            var out = new ByteArrayOutputStream();
            var buffer = ByteBuffer.wrap(file);
            for (var at = 0; at + 8 <= file.length; ) {
                var size = buffer.getInt(at);
                if (new String(file, at + 4, 4, StandardCharsets.ISO_8859_1).equals("!mof")) {
                    byte[] payload;
                    try (var stream = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(file, at + 8, size - 8))) {
                        payload = stream.readAllBytes();
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                    out.writeBytes(ByteBuffer.allocate(8).putInt(8 + payload.length)
                            .put("moof".getBytes(StandardCharsets.ISO_8859_1)).array());
                    out.writeBytes(payload);
                } else {
                    out.write(file, at, size);
                }
                at += size;
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("keeps the timecode of a CMAF track file an iTunes item")
        void cmaf() {
            var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
            var file = Fragmented.muxFragmented(source, MediaTags.builder().add("timecode", "01:00:00:00").build(),
                    Fragmented.Output.SEEKABLE, FragmentedMp4MuxerOptions.Brand.CMAF);
            var edited = edit(file, "02:00:00:00");
            try (var demuxer = new Mp4Demuxer(MediaSource.of(edited))) {
                assertEquals(1, demuxer.tracks().size());
                assertEquals("02:00:00:00", demuxer.metadata().get("timecode"));
            }
        }
    }

    @Test
    @DisplayName("ignores a counter timecode track and a truncated tmcd sample entry")
    void unreadable() {
        var counter = new byte[Mp4TimecodeTrack.ENTRY_FIELDS];
        counter[7] = Mp4TimecodeTrack.FLAG_COUNTER;
        counter[11] = 30;
        counter[15] = 1;
        counter[16] = 30;
        assertEquals(null, Mp4TimecodeTrack.read(counter, new byte[]{0, 0, 0, 1}));
        assertEquals(null, Mp4TimecodeTrack.read(new byte[10], new byte[]{0, 0, 0, 1}));
        assertEquals(null, Mp4TimecodeTrack.read(new byte[Mp4TimecodeTrack.ENTRY_FIELDS], new byte[]{0, 0, 0, 1}));
        var fields = counter.clone();
        fields[7] = Mp4TimecodeTrack.FLAG_DROP_FRAME;
        fields[16] = 0;
        // the nominal rate falls back to the rounded-up rate when the frame count is zero
        assertEquals(Timecode.parse("00:00:01;00", 30), Mp4TimecodeTrack.read(fields, new byte[]{0, 0, 0, 30}));
        assertTrue(Pattern.matches("\\d\\d:\\d\\d:\\d\\d;\\d\\d", Timecode.ofFrameNumber(-1, 30, true).toString()));
    }

    @Nested
    @DisplayName("fragmented files")
    class Fragmented {
        enum Output {SEEKABLE, STREAM, SINGLE_INDEX, SEGMENTS}

        private static byte[] muxFragmented(ContainerProbe.Demuxed source, MediaTags tags, Output output,
                                            FragmentedMp4MuxerOptions.Brand brand) {
            var options = (output == Output.SINGLE_INDEX ? FragmentedMp4MuxerOptions.dashOnDemand().toBuilder()
                    : FragmentedMp4MuxerOptions.builder()).brand(brand).fragmentMicros(250_000).build();
            if (output == Output.SEGMENTS) {
                var bodies = new ArrayList<ByteArrayMediaSink>();
                var muxer = new FragmentedMp4Muxer(segment -> {
                    var sink = MediaSink.memory();
                    bodies.add(sink);
                    return sink;
                }, options);
                feed(muxer, source, tags);
                var joined = new ByteArrayOutputStream();
                bodies.forEach(body -> joined.writeBytes(body.toByteArray()));
                return MuxSupport.validated("timecode-segments", joined.toByteArray());
            }
            if (output == Output.STREAM) {
                var stream = new ByteArrayOutputStream();
                try (var sink = MediaSink.of(stream)) {
                    feed(new FragmentedMp4Muxer(sink, options), source, tags);
                }
                return MuxSupport.validated("timecode-stream", stream.toByteArray());
            }
            var sink = MediaSink.memory();
            feed(new FragmentedMp4Muxer(sink, options), source, tags);
            return MuxSupport.validated("timecode-fragmented", sink.toByteArray());
        }

        private static void feed(FragmentedMp4Muxer muxer, ContainerProbe.Demuxed source, MediaTags tags) {
            try (muxer) {
                source.tracks().forEach(muxer::addTrack);
                muxer.setTags(tags);
                source.order().forEach(muxer::writePacket);
                muxer.finish();
            }
        }

        // the indexes of the moof boxes with a traf of the track, and the trun of its first one
        private static List<Integer> fragmentsOf(byte[] file, long trackId, int[] firstRun) {
            var result = new ArrayList<Integer>();
            var buffer = ByteBuffer.wrap(file);
            var moofs = FragmentedMp4MuxerTest.boxes(file).stream().filter(box -> box.type().equals("moof")).toList();
            for (var index = 0; index < moofs.size(); index++) {
                var moof = moofs.get(index);
                for (var child = moof.offset() + 8; child + 8 <= moof.offset() + moof.size(); ) {
                    var size = buffer.getInt(child);
                    var type = new String(file, child + 4, 4, StandardCharsets.ISO_8859_1);
                    if (type.equals("traf") && (buffer.getInt(child + 20) & 0xFFFFFFFFL) == trackId) {
                        result.add(index);
                        if (result.size() == 1) {
                            for (var box = child + 8; box + 8 <= child + size; box += buffer.getInt(box)) {
                                if (new String(file, box + 4, 4, StandardCharsets.ISO_8859_1).equals("trun")) {
                                    firstRun[0] = buffer.getInt(box + 12);
                                    firstRun[1] = buffer.getInt(box + 20);
                                    firstRun[2] = buffer.getInt(box + 24);
                                }
                            }
                        }
                    }
                    child += size;
                }
            }
            return result;
        }

        @ParameterizedTest
        @CsvSource({"30000/1001, 01:00:00;00", "25, 10:11:12:13"})
        @DisplayName("reads the timecode of an ffmpeg fragmented file, its tmcd sample in the first fragment")
        void read(String rate, String text) {
            var file = video("mp4", rate, List.of("-timecode", text, "-movflags", "frag_keyframe+empty_moov"));
            assertEquals(text, timecodeOf(Fixtures.read(file)).toString());
            var bytes = Fixtures.read(file);
            try (var demuxer = new Mp4Demuxer(MediaSource.of(new ByteArrayInputStream(bytes), bytes.length))) {
                assertEquals(text, demuxer.timecode().orElseThrow().toString(), "read from a stream");
            }
        }

        @ParameterizedTest
        @CsvSource({"30000/1001, 01:00:00;00, SEEKABLE", "25, 10:00:00:24, STREAM", "60000/1001, 23:59:59;59, SINGLE_INDEX",
                "24000/1001, 12:34:56:23, SEGMENTS", "30000/1001, 00:10:00;02, SEGMENTS"})
        @DisplayName("writes the timecode tag as a tmcd track whose sample is in the first fragment")
        void write(String rate, String text, Output output) {
            var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", rate, List.of()))));
            var file = muxFragmented(source, MediaTags.builder().add("timecode", text).add("title", "clip").build(),
                    output, FragmentedMp4MuxerOptions.Brand.MP4);
            assertEquals(text, timecodeOf(file).toString());
            var timecodeId = source.tracks().size() + 1;
            var run = new int[3];
            assertEquals(List.of(0), fragmentsOf(file, timecodeId, run), "one tmcd sample, in the first fragment");
            assertEquals(1, run[0], "one sample");
            assertEquals(4, run[2], "a 32-bit frame number");
            assertTrue(run[1] > 0, "the sample lasts the first fragment");
            var parts = rate.split("/");
            var denominator = parts.length > 1 ? Long.parseLong(parts[1]) : 1;
            var nominal = (int) ((Long.parseLong(parts[0]) + denominator - 1) / denominator);
            var frame = Timecode.parse(text, nominal).frameNumber();
            var saved = MuxSupport.save("timecode-" + output + ".mp4", file);
            var streams = probedTimecodes(saved);
            assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=data") && line.contains("tmcd")
                    && line.endsWith("timecode=" + text)), streams.toString());
            assertTrue(streams.stream().anyMatch(line -> line.contains("codec_type=video") && line.endsWith("timecode=" + text)),
                    "the video track references the timecode track: " + streams);
            assertFalse(new String(file, StandardCharsets.ISO_8859_1).contains("----"), "no freeform atom");
            try (var demuxer = new Mp4Demuxer(MediaSource.of(file))) {
                assertFalse(demuxer.metadata().containsKey("timecode"));
                assertTrue(demuxer.metadata().containsValue("clip"), demuxer.metadata().toString());
                var data = demuxer.tracks().stream().filter(track -> track instanceof DataTrack).toList();
                assertEquals(1, data.size(), demuxer.tracks().toString());
                assertEquals("tmcd", ((DataTrack) data.getFirst()).handler().orElseThrow());
            }
            // the sample holds the frame number of the address at the video's nominal rate
            assertEquals(frame, timecodeOf(file).frameNumber());
            MuxSupport.assertFfmpegClean("timecode-fragmented-clean.mp4", file);
        }

        @Test
        @DisplayName("writes a drop-frame timecode for an audio-only file and keeps the tag an item, read as the timecode, in a CMAF track file")
        void audioAndCmaf() {
            var audio = MuxSupport.demux(Fixtures.read(Fixtures.tone("m4a", 440, 48000, 1, 1, List.of("-c:a", "aac"))));
            var file = muxFragmented(audio, MediaTags.builder().add("timecode", "00:00:10;00").build(), Output.SEEKABLE,
                    FragmentedMp4MuxerOptions.Brand.MP4);
            assertEquals("00:00:10;00", timecodeOf(file).toString());
            assertTrue(probedTimecodes(MuxSupport.save("timecode-fragmented-audio.mp4", file)).stream()
                    .anyMatch(line -> line.endsWith("timecode=00:00:10;00")));
            var video = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
            var cmaf = muxFragmented(video, MediaTags.builder().add("timecode", "01:00:00:00").build(), Output.SEEKABLE,
                    FragmentedMp4MuxerOptions.Brand.CMAF);
            try (var demuxer = new Mp4Demuxer(MediaSource.of(cmaf))) {
                assertEquals(Timecode.parse("01:00:00:00", 25), demuxer.timecode().orElseThrow());
                assertEquals(1, demuxer.tracks().size());
                assertEquals("01:00:00:00", demuxer.metadata().get("timecode"), demuxer.metadata().toString());
            }
        }

        @Test
        @DisplayName("rejects an address that is invalid at the video's rate when the first packet arrives")
        void invalid() {
            var source = withoutData(MuxSupport.demux(Fixtures.read(video("mov", "25", List.of()))));
            try (var muxer = new FragmentedMp4Muxer(MediaSink.memory())) {
                source.tracks().forEach(muxer::addTrack);
                muxer.setTags(MediaTags.builder().add("timecode", "00:00:00:29").build());
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(source.order().getFirst()));
            }
            try (var muxer = new FragmentedMp4Muxer(MediaSink.memory())) {
                source.tracks().forEach(muxer::addTrack);
                muxer.setTags(MediaTags.builder().add("timecode", "00:00:00:29").build());
                assertThrows(IllegalArgumentException.class, muxer::finish);
            }
            try (var muxer = new FragmentedMp4Muxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.setTags(MediaTags.builder()
                        .add("timecode", "one o'clock").build()));
            }
        }
    }
}
