package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
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

    @Test
    @DisplayName("reads a timecode iTunes item the tag editor writes when the file has no timecode track, the track winning otherwise")
    void editedItem() {
        var plain = Fixtures.read(video("mp4", "30000/1001", List.of()));
        var edited = TagEditor.write(plain, MediaTags.builder().add("timecode", "00:10:00;00").build(),
                TagWriteOptions.defaults());
        assertEquals(Timecode.parse("00:10:00;00", 30), timecodeOf(edited));
        var invalid = TagEditor.write(plain, MediaTags.builder().add("timecode", "00:01:00;00").build(),
                TagWriteOptions.defaults());
        try (var demuxer = new Mp4Demuxer(MediaSource.of(invalid))) {
            assertTrue(demuxer.timecode().isEmpty(), "a frame number drop-frame counting skips");
        }
        var tracked = Fixtures.read(video("mov", "25", List.of("-timecode", "10:00:00:00")));
        var retagged = TagEditor.write(tracked, MediaTags.builder().add("timecode", "11:00:00:00").build(),
                TagWriteOptions.defaults());
        assertEquals("10:00:00:00", timecodeOf(retagged).toString());
    }

    @Test
    @DisplayName("ignores a counter timecode track and a truncated tmcd sample entry")
    void unreadable() {
        var counter = new byte[TimecodeTrack.ENTRY_FIELDS];
        counter[7] = TimecodeTrack.FLAG_COUNTER;
        counter[11] = 30;
        counter[15] = 1;
        counter[16] = 30;
        assertEquals(null, TimecodeTrack.read(counter, new byte[]{0, 0, 0, 1}));
        assertEquals(null, TimecodeTrack.read(new byte[10], new byte[]{0, 0, 0, 1}));
        assertEquals(null, TimecodeTrack.read(new byte[TimecodeTrack.ENTRY_FIELDS], new byte[]{0, 0, 0, 1}));
        var fields = counter.clone();
        fields[7] = TimecodeTrack.FLAG_DROP_FRAME;
        fields[16] = 0;
        // the nominal rate falls back to the rounded-up rate when the frame count is zero
        assertEquals(Timecode.parse("00:00:01;00", 30), TimecodeTrack.read(fields, new byte[]{0, 0, 0, 30}));
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
