package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.io.ByteArrayMediaSink;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link FragmentedMp4Muxer}: remuxing ffmpeg-made inputs into fragmented MP4 read back by
 * Smalt's MP4 demuxer and by ffmpeg, the box layout (init segment, aligned fragments,
 * {@code sidx}, {@code mfra}), CMAF segmentation into separate sinks, streaming sinks, timing
 * (B-frames, priming, presentation-timed input) and invalid input.
 */
@DisplayName("FragmentedMp4Muxer")
class FragmentedMp4MuxerTest {
    /** One top-level box: its type, offset and size. */
    record Box(String type, int offset, int size) {
    }

    static List<Box> boxes(byte[] data) {
        var result = new ArrayList<Box>();
        var buffer = ByteBuffer.wrap(data);
        var offset = 0;
        while (offset + 8 <= data.length) {
            long size = buffer.getInt(offset) & 0xFFFFFFFFL;
            var type = new String(data, offset + 4, 4, StandardCharsets.ISO_8859_1);
            if (size == 1) {
                size = buffer.getLong(offset + 8);
            }
            assertTrue(size >= 8 && offset + size <= data.length, "box " + type + " at " + offset + " overruns");
            result.add(new Box(type, offset, (int) size));
            offset += (int) size;
        }
        assertEquals(data.length, offset, "trailing bytes");
        return result;
    }

    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of("h264-aac", "mp4", List.of("-c:v", "libx264", "-bf", "2", "-g", "30", "-c:a", "aac")),
                Arguments.of("hevc-opus", "mkv", List.of("-c:v", "libx265", "-x265-params", "log-level=none:keyint=30", "-c:a", "libopus")),
                Arguments.of("vp9-opus", "webm", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-g", "30", "-c:a", "libopus")),
                Arguments.of("av1-mp3", "mkv", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime", "-g", "30", "-c:a", "libmp3lame")),
                Arguments.of("h264-flac", "mkv", List.of("-c:v", "libx264", "-bf", "3", "-g", "30", "-c:a", "flac")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("remuxes every packet with exact presentation times and decodes cleanly with ffmpeg")
    void remux(String name, String extension, List<String> codecs) {
        var input = Fixtures.read(Fixtures.testVideo(extension, 160, 120, 3, codecs));
        var source = MuxSupport.demux(input);
        var output = MuxSupport.toMemory(source.tracks(), source.order(),
                sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(1_000_000)
                        .build()));
        var layout = boxes(output);
        assertEquals("ftyp", layout.get(0).type());
        assertEquals("moov", layout.get(1).type());
        for (var index = 2; index < layout.size(); index += 2) {
            assertEquals("moof", layout.get(index).type());
            assertEquals("mdat", layout.get(index + 1).type());
        }
        assertTrue(layout.size() >= 2 + 2 * 3, "three or more fragments");
        var result = MuxSupport.demux(output);
        var map = new HashMap<Integer, Integer>();
        for (var index = 0; index < source.tracks().size(); index++) {
            map.put(source.tracks().get(index).id(), result.tracks().get(index).id());
        }
        MuxSupport.assertSamePayloads(source, result, map);
        for (var index = 0; index < source.tracks().size(); index++) {
            var before = source.tracks().get(index);
            var after = result.tracks().get(index);
            if (before.kind() == TrackKind.VIDEO) {
                assertEquals(MuxSupport.ptsMicros(source, before), MuxSupport.ptsMicros(result, after), "video presentation times");
            }
        }
        MuxSupport.assertFfmpegClean(name + ".mp4", output);
        var path = MuxSupport.save(name + "-frames.mp4", output);
        var decoded = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error", "-i", path.toString(),
                "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "gray", "-"));
        assertEquals(160 * 120 * 90, decoded.length, "ffmpeg decodes all 90 frames");
        var probe = ContainerProbe.probe(path);
        var videoStream = probe.streams().stream().filter(stream -> "video".equals(stream.get("codec_type"))).findFirst().orElseThrow();
        var firstVideo = probe.packetsOf(videoStream.index()).stream().mapToLong(ContainerProbe.ProbePacket::pts).min().orElseThrow();
        assertEquals(0, firstVideo, "ffmpeg presents the first picture at zero");
    }

    @Test
    @DisplayName("starts every fragment with a video key frame")
    void keyframeAligned() {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 4, List.of("-c:v", "libx264", "-g", "20", "-c:a", "aac")));
        var source = MuxSupport.demux(input);
        var segments = new ArrayList<Segment>();
        var bodies = new ArrayList<ByteArrayMediaSink>();
        var muxer = new FragmentedMp4Muxer(segment -> {
            segments.add(segment);
            var sink = MediaSink.memory();
            bodies.add(sink);
            return sink;
        }, FragmentedMp4MuxerOptions.builder().fragmentMicros(1_000_000).build());
        MuxSupport.mux(source.tracks(), source.order(), muxer);
        assertEquals(Segment.Kind.INIT, segments.getFirst().kind());
        for (var index = 1; index < segments.size(); index++) {
            var segment = segments.get(index);
            assertEquals(index, segment.sequenceNumber());
            assertTrue(segment.independent(), "segment " + index + " starts with a key frame");
            if (index + 1 < segments.size()) {
                assertTrue(segment.durationMicros() >= 1_000_000, "segment " + index + " lasts " + segment.durationMicros());
            }
            var layout = boxes(bodies.get(index).toByteArray());
            assertEquals(List.of("styp", "moof", "mdat"), layout.stream().map(Box::type).toList());
        }
        var joined = new ByteArrayOutputStream();
        bodies.forEach(body -> joined.writeBytes(body.toByteArray()));
        var result = MuxSupport.demux(joined.toByteArray());
        assertEquals(source.order().size(), result.order().size());
        MuxSupport.assertFfmpegClean("segments.mp4", joined.toByteArray());
    }

    @Test
    @DisplayName("writes CMAF track files with cmfc and cmfs brands, one track each")
    void cmaf() {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 3, List.of("-c:v", "libx264", "-g", "30", "-an")));
        var source = MuxSupport.demux(input);
        var bodies = new ArrayList<byte[]>();
        var holder = new ArrayList<ByteArrayMediaSink>();
        var muxer = new FragmentedMp4Muxer(segment -> {
            var sink = MediaSink.memory();
            holder.add(sink);
            return sink;
        }, FragmentedMp4MuxerOptions.cmaf().toBuilder()
                .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.PER_FRAGMENT).build());
        MuxSupport.mux(source.tracks(), source.order(), muxer);
        holder.forEach(sink -> bodies.add(sink.toByteArray()));
        var init = new String(bodies.getFirst(), StandardCharsets.ISO_8859_1);
        assertTrue(init.contains("cmfc"));
        for (var index = 1; index < bodies.size(); index++) {
            var layout = boxes(bodies.get(index));
            assertEquals(List.of("styp", "sidx", "moof", "mdat"), layout.stream().map(Box::type).toList());
            var styp = new String(bodies.get(index), 8, 4, StandardCharsets.ISO_8859_1);
            assertEquals("cmfs", styp);
            var referenced = ByteBuffer.wrap(bodies.get(index)).getInt(layout.get(1).offset() + 40) & 0x7FFFFFFF;
            assertEquals(layout.get(2).size() + layout.get(3).size(), referenced, "sidx references the fragment");
        }
        var joined = new ByteArrayOutputStream();
        bodies.forEach(joined::writeBytes);
        MuxSupport.assertFfmpegClean("cmaf.mp4", joined.toByteArray());
        var twoTracks = new FragmentedMp4Muxer(MediaSink.memory(), FragmentedMp4MuxerOptions.cmaf());
        twoTracks.addTrack(source.tracks().getFirst());
        assertThrows(IllegalArgumentException.class, () -> twoTracks.addTrack(pcmLike(7)));
    }

    @Test
    @DisplayName("writes the same fragments to a stream, and indexes a seekable file with mehd, sidx and mfra")
    void streamingAndIndexes() {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 3, List.of("-c:v", "libx264", "-g", "30", "-c:a", "aac")));
        var source = MuxSupport.demux(input);
        var options = FragmentedMp4MuxerOptions.builder()
                .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.PER_FRAGMENT)
                .randomAccessIndex(true).build();
        var memory = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new FragmentedMp4Muxer(sink, options));
        var stream = MuxSupport.toStream(source.tracks(), source.order(), sink -> new FragmentedMp4Muxer(sink, options));
        // the seekable output adds the 20-byte mehd box that the stream cannot patch
        assertEquals(memory.length - 20, stream.length);
        var layout = boxes(memory);
        var last = layout.getLast();
        assertEquals("mfra", last.type());
        var buffer = ByteBuffer.wrap(memory);
        assertEquals(last.size(), buffer.getInt(memory.length - 4), "mfro size");
        var moofs = layout.stream().filter(box -> box.type().equals("moof")).map(Box::offset).toList();
        var tfra = last.offset() + 8;
        assertEquals("tfra", new String(memory, tfra + 4, 4, StandardCharsets.ISO_8859_1));
        var entries = buffer.getInt(tfra + 12 + 4 + 4);
        assertTrue(entries > 0);
        for (var entry = 0; entry < entries; entry++) {
            var moofOffset = buffer.getLong(tfra + 24 + entry * 19 + 8);
            assertTrue(moofs.contains((int) moofOffset), "tfra points at a moof");
        }
        MuxSupport.assertFfmpegClean("indexed.mp4", memory);
        MuxSupport.assertFfmpegClean("indexed-stream.mp4", stream);
        var fromMemory = MuxSupport.demux(memory);
        var fromStream = MuxSupport.demux(stream);
        assertEquals(fromMemory.order().size(), fromStream.order().size());
    }

    @Nested
    @DisplayName("single segment index")
    class SingleIndex {
        static Stream<Arguments> files() {
            return Stream.of(
                    Arguments.of("cmaf-video", List.of("-c:v", "libx264", "-bf", "2", "-g", "30", "-an"),
                            FragmentedMp4MuxerOptions.dashOnDemand()),
                    Arguments.of("mp4-video-audio", List.of("-c:v", "libx264", "-g", "30", "-c:a", "aac"),
                            FragmentedMp4MuxerOptions.builder()
                                    .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.SINGLE)
                                    .randomAccessIndex(true).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("files")
        @DisplayName("lays out ftyp, moov, one sidx referencing every fragment, then the fragments")
        void layout(String name, List<String> codecs, FragmentedMp4MuxerOptions base) {
            var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 5, codecs));
            var source = MuxSupport.demux(input);
            var options = base.toBuilder().fragmentMicros(1_000_000).build();
            var holder = new FragmentedMp4Muxer[1];
            var memory = MuxSupport.toMemory(source.tracks(), source.order(), sink -> holder[0] = new FragmentedMp4Muxer(sink, options));
            var layout = holder[0].indexLayout().orElseThrow();
            var boxes = boxes(memory);
            assertEquals(List.of("ftyp", "moov", "sidx"), boxes.subList(0, 3).stream().map(Box::type).toList());
            assertTrue(new String(memory, 0, boxes.getFirst().size(), StandardCharsets.ISO_8859_1).contains("dash"));
            var fragments = new ArrayList<Box[]>();
            for (var index = 3; index < boxes.size() && !boxes.get(index).type().equals("mfra"); index += 2) {
                assertEquals("moof", boxes.get(index).type());
                assertEquals("mdat", boxes.get(index + 1).type());
                fragments.add(new Box[]{boxes.get(index), boxes.get(index + 1)});
            }
            assertTrue(fragments.size() >= 4, "fragments: " + fragments.size());
            var sidx = boxes.get(2);
            assertEquals(sidx.offset(), layout.initializationSize());
            assertEquals(sidx.offset(), layout.indexOffset());
            assertEquals(sidx.size(), layout.indexSize());
            assertEquals(fragments.size(), layout.subsegments().size());
            var buffer = ByteBuffer.wrap(memory);
            var at = sidx.offset();
            assertEquals(1, memory[at + 8], "version 1");
            assertEquals(0, buffer.getLong(at + 28), "first_offset");
            assertEquals(fragments.size(), buffer.getShort(at + 38) & 0xFFFF, "reference_count");
            var timescale = buffer.getInt(at + 16) & 0xFFFFFFFFL;
            var start = buffer.getLong(at + 20);
            assertEquals(0, start, "earliest presentation time");
            var expectedStart = 0L;
            for (var index = 0; index < fragments.size(); index++) {
                var reference = at + 40 + 12 * index;
                var fragment = fragments.get(index);
                var subsegment = layout.subsegments().get(index);
                assertEquals(fragment[0].size() + fragment[1].size(), buffer.getInt(reference) & 0x7FFFFFFF, "referenced_size");
                assertEquals(fragment[0].offset(), subsegment.offset());
                assertEquals(fragment[0].size() + fragment[1].size(), subsegment.size());
                assertEquals(0x90000000, buffer.getInt(reference + 8), "starts with a SAP of type 1");
                assertTrue(subsegment.independent());
                if (source.tracks().size() == 1) {
                    // with several tracks a fragment starts at the earliest sample of any of them
                    assertTrue(Math.abs(subsegment.startMicros() - expectedStart * 1_000_000 / timescale) <= 1,
                            "subsegment " + index + " starts where the previous ends");
                }
                expectedStart += buffer.getInt(reference + 4) & 0xFFFFFFFFL;
            }
            assertEquals(source.order().size(), MuxSupport.demux(memory).order().size());
            MuxSupport.assertFfmpegClean("single-index-" + name + ".mp4", memory);
        }

        @Test
        @DisplayName("writes the same file to a streaming sink, without the mehd it cannot patch")
        void streaming() {
            var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 3, List.of("-c:v", "libx264", "-g", "30", "-an")));
            var source = MuxSupport.demux(input);
            var options = FragmentedMp4MuxerOptions.dashOnDemand().toBuilder().fragmentMicros(1_000_000).build();
            var memory = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new FragmentedMp4Muxer(sink, options));
            var stream = MuxSupport.toStream(source.tracks(), source.order(), sink -> new FragmentedMp4Muxer(sink, options));
            assertEquals(memory.length - 20, stream.length);
            assertEquals(boxes(memory).stream().map(Box::type).toList(), boxes(stream).stream().map(Box::type).toList());
            assertEquals(source.order().size(), MuxSupport.demux(stream).order().size());
        }

        @Test
        @DisplayName("needs a MediaSink output and reports no layout before finishing")
        void constraints() {
            var options = FragmentedMp4MuxerOptions.dashOnDemand();
            assertThrows(IllegalArgumentException.class, () -> new FragmentedMp4Muxer(segment -> MediaSink.memory(), options));
            var muxer = new FragmentedMp4Muxer(MediaSink.memory(), options);
            assertTrue(muxer.indexLayout().isEmpty());
            assertTrue(new FragmentedMp4Muxer(MediaSink.memory()).indexLayout().isEmpty());
            assertEquals(FragmentedMp4MuxerOptions.SegmentIndex.NONE, FragmentedMp4MuxerOptions.defaults().segmentIndex());
            assertEquals(options, options.toBuilder().build());
        }
    }

    @Nested
    @DisplayName("timing")
    class Timing {
        @Test
        @DisplayName("keeps AAC priming as an edit list and the trimmed duration on a seekable sink")
        void aacPriming() {
            var input = Fixtures.read(Fixtures.tone("m4a", 440, 44_100, 2, 1.3, List.of("-c:a", "aac")));
            var source = MuxSupport.demux(input);
            var original = (AudioTrack) source.tracks().getFirst();
            assertTrue(original.skipSamples() > 0);
            var seekable = MuxSupport.demux(MuxSupport.toMemory(source.tracks(), source.order(), FragmentedMp4Muxer::new));
            var track = (AudioTrack) seekable.tracks().getFirst();
            assertEquals(original.skipSamples(), track.skipSamples());
            assertEquals(original.duration(), track.duration());
            var streamed = MuxSupport.toStream(source.tracks(), source.order(), FragmentedMp4Muxer::new);
            var open = (AudioTrack) MuxSupport.demux(streamed).tracks().getFirst();
            assertEquals(original.skipSamples(), open.skipSamples());
            assertTrue(open.duration() >= original.duration(), "an open-ended edit spans every fragment");
            var decodedIn = Oracle.decodeAudio(MuxSupport.save("aac-in.m4a", input));
            var decodedOut = Oracle.decodeAudio(MuxSupport.save("aac-out.mp4", streamed));
            assertTrue(Math.abs(decodedIn.length - decodedOut.length) <= 2 * 2048, "ffmpeg decodes the same length");
        }

        @Test
        @DisplayName("derives decoding times of presentation-timed H.264 from Matroska")
        void ptsOnly() {
            var input = Fixtures.read(Fixtures.testVideo("mkv", 160, 120, 3, List.of("-c:v", "libx264", "-bf", "3", "-g", "30", "-an")));
            var source = MuxSupport.demux(input);
            assertEquals(Packet.NO_TIMESTAMP, source.order().get(1).dts());
            var output = MuxSupport.toMemory(source.tracks(), source.order(), FragmentedMp4Muxer::new);
            var result = MuxSupport.demux(output);
            MuxSupport.assertTimesClose(MuxSupport.ptsMicros(source, source.tracks().getFirst()),
                    MuxSupport.ptsMicros(result, result.tracks().getFirst()), 0);
            var probe = ContainerProbe.probe(MuxSupport.save("pts-only.mp4", output));
            for (var packet : probe.packets()) {
                assertTrue(packet.dts() <= packet.pts(), "ffmpeg decodes before presenting");
            }
            MuxSupport.assertFfmpegClean("pts-only.mp4", output);
        }

        @Test
        @DisplayName("remuxes Ogg Opus with its pre-skip in the edit list")
        void oggOpus() {
            var input = Fixtures.read(Fixtures.tone("ogg", 440, 48_000, 1, 1.01, List.of("-c:a", "libopus")));
            var source = MuxSupport.demux(input);
            var output = MuxSupport.toMemory(source.tracks(), source.order(), FragmentedMp4Muxer::new);
            MuxSupport.assertSamePayloads(source, MuxSupport.demux(output),
                    java.util.Map.of(source.tracks().getFirst().id(), 1));
            MuxSupport.assertFfmpegClean("opus.mp4", output);
            // ffmpeg ignores the end of an MP4 Opus edit, so compare with its own MP4 remux of the input
            var reference = MuxSupport.save("opus-ffmpeg.mp4", new byte[0]);
            Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i",
                    MuxSupport.save("opus-in.ogg", input).toString(), "-c", "copy", reference.toString()));
            var decodedOut = Oracle.decodeAudio(MuxSupport.save("opus-out.mp4", output));
            assertEquals(Oracle.decodeAudio(reference).length, decodedOut.length, "ffmpeg decodes the same samples");
            var track = (AudioTrack) MuxSupport.demux(output).tracks().getFirst();
            assertEquals(source.tracks().getFirst().duration(), track.duration(), "the edit keeps the end trim");
        }
    }

    @Nested
    @DisplayName("edge cases")
    class Edges {
        @Test
        @DisplayName("writes only the initialization segment for tracks without packets")
        void empty() {
            var input = Fixtures.read(Fixtures.tone("m4a", 440, 44_100, 2, 0.2, List.of("-c:a", "aac")));
            var track = MuxSupport.demux(input).tracks().getFirst();
            var output = MuxSupport.toMemory(List.of(track), List.of(), FragmentedMp4Muxer::new);
            assertEquals(List.of("ftyp", "moov"), boxes(output).stream().map(Box::type).toList());
            assertTrue(MuxSupport.demux(output).order().isEmpty());
        }

        @Test
        @DisplayName("rejects unsupported codecs, undeclared tracks, decreasing and overflowing timestamps")
        void rejections() {
            try (var muxer = new FragmentedMp4Muxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(pcmLike(0)));
                assertFalse(FragmentedMp4Muxer.supports(Codec.VORBIS));
            }
            var input = Fixtures.read(Fixtures.tone("m4a", 440, 44_100, 2, 0.2, List.of("-c:a", "aac")));
            var aac = (AudioTrack) MuxSupport.demux(input).tracks().getFirst();
            // a time base numerator above one makes huge timestamps overflow the media timescale
            var track = AudioTrack.builder()
                    .id(aac.id())
                    .codec(aac.codec())
                    .timeBase(new Rational(2, 88_200))
                    .duration(aac.duration())
                    .codecConfig(aac.codecConfig().orElse(null))
                    .language(aac.language().orElse(null))
                    .sampleRate(aac.sampleRate())
                    .layout(aac.layout().orElse(null))
                    .bitsPerSample(aac.bitsPerSample())
                    .skipSamples(aac.skipSamples())
                    .build();
            try (var muxer = new FragmentedMp4Muxer(MediaSink.memory())) {
                muxer.addTrack(track);
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(99, new byte[4], 0, 0, 1024, true)));
                muxer.writePacket(new Packet(track.id(), new byte[4], 2048, 2048, 1024, true));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(track.id(), new byte[4], 1024, 1024, 1024, true)));
                assertThrows(IllegalArgumentException.class,
                        () -> muxer.writePacket(new Packet(track.id(), new byte[4], Long.MAX_VALUE, Long.MAX_VALUE, 0, true)));
                assertThrows(IllegalStateException.class, () -> muxer.addTrack(pcmLike(5)));
            }
        }
    }

    private static Track pcmLike(int id) {
        return AudioTrack.builder()
                .id(id)
                .codec(Codec.PCM_S16LE)
                .timeBase(Rational.timeBaseOf(48_000))
                .duration(-1)
                .sampleRate(48_000)
                .layout(ChannelLayout.MONO)
                .bitsPerSample(16)
                .skipSamples(0)
                .build();
    }

    @Test
    @DisplayName("Mp4Muxer and FragmentedMp4Muxer give Ogg Opus the pre-skip once as edit media time")
    void oggOpusEdit() {
        var input = Fixtures.read(Fixtures.tone("ogg", 440, 48_000, 1, 1.01, List.of("-c:a", "libopus")));
        var source = MuxSupport.demux(input);
        assertEquals(-312, source.order().getFirst().pts());
        for (var output : List.of(MuxSupport.toMemory(source.tracks(), source.order(), Mp4Muxer::new),
                MuxSupport.toMemory(source.tracks(), source.order(), FragmentedMp4Muxer::new))) {
            var elst = new String(output, StandardCharsets.ISO_8859_1).indexOf("elst");
            var buffer = ByteBuffer.wrap(output);
            var version = output[elst + 4];
            var mediaTime = version == 1 ? buffer.getLong(elst + 20) : buffer.getInt(elst + 16);
            assertEquals(312, mediaTime);
            assertEquals(source.tracks().getFirst().duration(), MuxSupport.demux(output).tracks().getFirst().duration());
        }
    }

    @Test
    @DisplayName("Mp4Muxer writes VP9 and AV1 with configurations derived from the first key frame")
    void progressiveVpxAndAv1() {
        for (var codec : List.of(List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8"),
                List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime"))) {
            var options = new ArrayList<>(codec);
            options.addAll(List.of("-c:a", "libopus"));
            var input = Fixtures.read(Fixtures.testVideo("webm", 128, 96, 1, options));
            var source = MuxSupport.demux(input);
            var output = MuxSupport.toMemory(source.tracks(), source.order(), Mp4Muxer::new);
            var result = MuxSupport.demux(output);
            var video = result.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst().orElseThrow();
            assertTrue(video.codecConfig().isPresent());
            MuxSupport.assertFfmpegClean("progressive-" + video.codec().displayName() + ".mp4", output);
            var fragmented = MuxSupport.toMemory(source.tracks(), source.order(), FragmentedMp4Muxer::new);
            MuxSupport.assertFfmpegClean("fragmented-" + video.codec().displayName() + ".mp4", fragmented);
            assertArrayEquals(video.codecConfig().get(), MuxSupport.demux(fragmented).tracks().stream()
                    .filter(track -> track.kind() == TrackKind.VIDEO).findFirst().orElseThrow().codecConfig().orElseThrow());
        }
    }
}
