package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackDisposition;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link MatroskaMuxer}: remuxing ffmpeg-made inputs of every supported codec through
 * Smalt's demuxers, the structure of the output (sizes, SeekHead, Cues, Duration), the three
 * output modes, ffmpeg decoding the output cleanly, and the rejection of invalid tracks.
 */
@DisplayName("MatroskaMuxer")
class MatroskaMuxerTest {
    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of("vp9-opus", "webm", List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8", "-g", "15", "-c:a", "libopus"), true),
                Arguments.of("vp8-vorbis", "webm", List.of("-c:v", "libvpx", "-deadline", "realtime", "-g", "15", "-c:a", "libvorbis"), true),
                Arguments.of("av1-opus", "mp4", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime", "-g", "15", "-c:a", "libopus"), true),
                Arguments.of("h264-aac", "mp4", List.of("-c:v", "libx264", "-bf", "2", "-g", "15", "-c:a", "aac"), false),
                Arguments.of("hevc-flac", "mkv", List.of("-c:v", "libx265", "-x265-params", "log-level=none:keyint=15", "-c:a", "flac"), false),
                Arguments.of("h264-mp3", "mkv", List.of("-c:v", "libx264", "-g", "15", "-c:a", "libmp3lame"), false),
                Arguments.of("h264-pcm", "mkv", List.of("-c:v", "libx264", "-g", "15", "-c:a", "pcm_s24le"), false));
    }

    private static MatroskaMuxerOptions optionsFor(boolean webm) {
        return webm ? MatroskaMuxerOptions.webm() : MatroskaMuxerOptions.matroska();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("remuxes every packet, keeps timing within a tick and decodes cleanly with ffmpeg")
    void remux(String name, String extension, List<String> codecs, boolean webm) {
        var input = Fixtures.read(Fixtures.testVideo(extension, 160, 120, 1.5, codecs));
        var source = MuxSupport.demux(input);
        var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, optionsFor(webm)));
        var structure = MatroskaStructure.check(output);
        assertEquals(webm ? "webm" : "matroska", structure.docType);
        assertTrue(structure.seeks.containsKey((long) EbmlIds.CUES), "SeekHead lists Cues");
        assertFalse(structure.cues.isEmpty());
        assertTrue(structure.duration > 1000);
        var result = MuxSupport.demux(output);
        var map = new HashMap<Integer, Integer>();
        for (var index = 0; index < source.tracks().size(); index++) {
            map.put(source.tracks().get(index).id(), result.tracks().get(index).id());
            assertEquals(source.tracks().get(index).codec(), result.tracks().get(index).codec());
        }
        MuxSupport.assertSamePayloads(source, result, map);
        for (var index = 0; index < source.tracks().size(); index++) {
            var before = source.tracks().get(index);
            var after = result.tracks().get(index);
            if (before.codec() != Codec.OPUS) {
                MuxSupport.assertTimesClose(MuxSupport.ptsMicros(source, before), MuxSupport.ptsMicros(result, after), 1000);
            }
        }
        MuxSupport.assertFfmpegClean(name + ".mkv", output);
    }

    @Test
    @DisplayName("writes the same blocks to a stream, with an unknown segment size and no Cues")
    void streaming() {
        var input = Fixtures.read(Fixtures.testVideo("webm", 160, 120, 1, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime",
                "-cpu-used", "8", "-c:a", "libopus")));
        var source = MuxSupport.demux(input);
        var output = MuxSupport.toStream(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
        var structure = MatroskaStructure.check(output);
        assertEquals(MatroskaStructure.UNKNOWN, structure.segmentSize);
        assertTrue(structure.cues.isEmpty());
        assertFalse(structure.unknownClusters);
        assertEquals(-1, structure.duration);
        var result = MuxSupport.demux(output);
        MuxSupport.assertSamePayloads(source, result, identity(source, result));
        MuxSupport.assertFfmpegClean("stream.webm", output);
    }

    @Test
    @DisplayName("writes live WebM with unknown-size clusters, block by block, on any sink")
    void live() {
        var input = Fixtures.read(Fixtures.testVideo("webm", 160, 120, 1, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime",
                "-cpu-used", "8", "-c:a", "libopus")));
        var source = MuxSupport.demux(input);
        var viaMemory = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.liveWebm()));
        var viaStream = MuxSupport.toStream(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.liveWebm()));
        assertArrayEquals(viaMemory, viaStream);
        var structure = MatroskaStructure.check(viaStream);
        assertTrue(structure.unknownClusters);
        assertEquals(MatroskaStructure.UNKNOWN, structure.segmentSize);
        var result = MuxSupport.demux(viaStream);
        MuxSupport.assertSamePayloads(source, result, identity(source, result));
        MuxSupport.assertFfmpegClean("live.webm", viaStream);
    }

    @Test
    @DisplayName("remuxes Ogg Opus to WebM with the pre-skip as CodecDelay and the end trim as DiscardPadding")
    void oggOpus() {
        var input = Fixtures.read(Fixtures.tone("ogg", 440, 48_000, 2, 1.01, List.of("-c:a", "libopus")));
        var source = MuxSupport.demux(input);
        var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
        MatroskaStructure.check(output);
        var result = MuxSupport.demux(output);
        MuxSupport.assertSamePayloads(source, result, identity(source, result));
        var sourceTrack = source.tracks().getFirst();
        var resultTrack = result.tracks().getFirst();
        var before = source.packetsOf(sourceTrack);
        var after = result.packetsOf(resultTrack);
        assertEquals(before.getLast().duration(), after.getLast().duration(), "trimmed duration of the last packet");
        var path = MuxSupport.save("ogg-opus.webm", output);
        var probeIn = ContainerProbe.probe(MuxSupport.save("ogg-opus.ogg", input));
        var probeOut = ContainerProbe.probe(path);
        var startIn = probeIn.packets().getFirst().pts() * 1_000_000 / 48_000;
        var startOut = probeOut.packets().getFirst().pts() * 1000;
        assertTrue(Math.abs(startIn - startOut) <= 1000, "ffmpeg sees the same start after subtracting CodecDelay: "
                + startIn + " vs " + startOut);
        MuxSupport.assertFfmpegClean("ogg-opus.webm", output);
        var decodedIn = com.github.auties00.smalt.testkit.Oracle.decodeAudio(MuxSupport.save("ogg-opus-in.ogg", input));
        var decodedOut = com.github.auties00.smalt.testkit.Oracle.decodeAudio(path);
        assertEquals(decodedIn.length, decodedOut.length, "ffmpeg decodes the same number of samples");
    }

    @ParameterizedTest(name = "{0} samples")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 1000, 69_999, 70_000, 70_001})
    @DisplayName("ends the segment Duration on the last sample, finer than a millisecond, so no sample is cut")
    void exactDuration(int samples) {
        var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(new Rational(1, 44_100))
                .sampleRate(44_100).layout(ChannelLayout.MONO).build();
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(track);
            for (var start = 0; start < samples; start += 1000) {
                var count = Math.min(1000, samples - start);
                muxer.writePacket(new Packet(1, new byte[2 * count], start, start, count, true));
            }
            muxer.finish();
        }
        var structure = MatroskaStructure.check(sink.toByteArray());
        assertEquals(samples * 1000.0 / 44_100, structure.duration, 1e-6, "segment Duration in milliseconds");
        try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(
                com.github.auties00.smalt.io.MediaSource.of(sink.toByteArray()))) {
            assertEquals(samples, demuxer.tracks().getFirst().duration(), "track duration in samples");
        }
    }

    @Test
    @DisplayName("produces byte-identical output for the same input")
    void deterministic() {
        var input = Fixtures.read(Fixtures.testVideo("mp4", 160, 120, 1, List.of("-c:v", "libx264", "-c:a", "aac")));
        var source = MuxSupport.demux(input);
        var first = MuxSupport.toMemory(source.tracks(), source.order(), MatroskaMuxer::new);
        var second = MuxSupport.toMemory(source.tracks(), source.order(), MatroskaMuxer::new);
        assertArrayEquals(first, second);
    }

    @Nested
    @DisplayName("track description")
    class Description {
        @Test
        @DisplayName("writes language, flags, color and frame rate")
        void metadata() {
            var input = Fixtures.read(Fixtures.testVideo("webm", 160, 120, 1, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime",
                    "-cpu-used", "8", "-c:a", "libopus")));
            var source = MuxSupport.demux(input);
            var video = (VideoTrack) source.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst().orElseThrow();
            var audio = (AudioTrack) source.tracks().stream().filter(track -> track.kind() == TrackKind.AUDIO).findFirst().orElseThrow();
            var colored = VideoTrack.builder()
                    .id(video.id())
                    .codec(video.codec())
                    .timeBase(video.timeBase())
                    .duration(video.duration())
                    .codecConfig(video.codecConfig().orElse(null))
                    .language("ita")
                    .width(video.width())
                    .height(video.height())
                    .orientation(Orientation.NORMAL)
                    .frameRate(new Rational(30, 1))
                    .color(ColorInfo.BT709_LIMITED)
                    .build();
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink, MatroskaMuxerOptions.webm().toBuilder().title("title").build())) {
                muxer.addTrack(colored, EnumSet.of(TrackDisposition.DEFAULT));
                muxer.addTrack(audio, EnumSet.of(TrackDisposition.FORCED, TrackDisposition.COMMENTARY));
                source.order().forEach(muxer::writePacket);
                muxer.finish();
            }
            var result = MuxSupport.demux(sink.toByteArray());
            var readVideo = (VideoTrack) result.tracks().getFirst();
            assertEquals(Optional.of("ita"), readVideo.language());
            assertEquals(Optional.of(ColorInfo.BT709_LIMITED), readVideo.color());
            // DefaultDuration stores whole nanoseconds, so 30 fps reads back as 1e9 / 33333333
            assertEquals(30.0, readVideo.frameRate().orElseThrow().toDouble(), 1e-6);
            var probe = ContainerProbe.probe(MuxSupport.save("metadata.webm", sink.toByteArray()));
            // the VP9 bitstream's own color space (unknown) overrides the container's matrix in ffprobe
            assertEquals("bt709", probe.streams().getFirst().get("color_primaries"));
            assertEquals("bt709", probe.streams().getFirst().get("color_transfer"));
            assertEquals("tv", probe.streams().getFirst().get("color_range"));
            assertEquals("1", probe.streams().get(1).get("disposition:forced"));
            assertEquals("1", probe.streams().get(1).get("disposition:comment"));
            assertEquals("0", probe.streams().get(1).get("disposition:default"));
        }

        @Test
        @DisplayName("synthesizes the AV1 CodecPrivate from the sequence header of an IVF input")
        void av1FromIvf() {
            var input = Fixtures.read(Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=10:duration=1"),
                    List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime")));
            var source = MuxSupport.demux(input);
            assertTrue(source.tracks().getFirst().codecConfig().isEmpty());
            var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            var result = MuxSupport.demux(output);
            var config = result.tracks().getFirst().codecConfig().orElseThrow();
            assertEquals((byte) 0x81, config[0]);
            var probe = ContainerProbe.probe(MuxSupport.save("ivf-av1.webm", output));
            assertEquals("av1", probe.streams().getFirst().codecName());
            assertArrayEquals(config, probe.streams().getFirst().extradata());
            MuxSupport.assertFfmpegClean("ivf-av1.webm", output);
        }
    }

    @Nested
    @DisplayName("edge cases")
    class Edges {
        @Test
        @DisplayName("writes a valid file for a track without packets")
        void emptyTrack() {
            var track = pcmTrack(1);
            var output = MuxSupport.toMemory(List.of(track), List.of(), MatroskaMuxer::new);
            var structure = MatroskaStructure.check(output);
            assertTrue(structure.clusterPositions.isEmpty());
            // RFC 9559 requires a positive Duration, so the reserved element turns into Void padding of its size
            assertEquals(-1, structure.duration, "no Duration element");
            // the SeekHead, ahead of Info, names Info and Tracks by their ids too, so the elements are found by it
            var info = (int) (structure.segmentStart + structure.seeks.get((long) EbmlIds.INFO));
            var tracks = (int) (structure.segmentStart + structure.seeks.get((long) EbmlIds.TRACKS));
            var padding = indexOf(output, info, new byte[]{(byte) 0xEC, 0x40, 0x08});
            assertTrue(info < tracks && padding > info && padding < tracks, "an 11-byte Void element in Info");
            var result = MuxSupport.demux(output);
            assertEquals(1, result.tracks().size());
            assertTrue(result.order().isEmpty());
        }

        @Test
        @DisplayName("writes many tracks and interleaves them by time")
        void manyTracks() {
            var tracks = new ArrayList<Track>();
            var packets = new ArrayList<Packet>();
            for (var id = 0; id < 24; id++) {
                tracks.add(pcmTrack(id));
            }
            for (var block = 0; block < 50; block++) {
                for (var id = 0; id < 24; id++) {
                    packets.add(new Packet(id, new byte[960 * 2], block * 960L, block * 960L, 960, true));
                }
            }
            var output = MuxSupport.toMemory(tracks, packets, MatroskaMuxer::new);
            MatroskaStructure.check(output);
            var result = MuxSupport.demux(output);
            assertEquals(24, result.tracks().size());
            assertEquals(packets.size(), result.order().size());
            MuxSupport.assertFfmpegClean("many.mkv", output);
        }

        @Test
        @DisplayName("writes blocks in decoding order when a video encoder holds frames back behind the audio")
        void delayedVideo() {
            // 10 s of 30 fps video released the way a 35-frame lookahead coding 16-frame groups releases
            // it (each group 1.2 to 1.7 s after its first frame), interleaved with 20 ms audio packets
            var video = VideoTrack.builder().id(1).codec(Codec.VP8).timeBase(new Rational(1, 90_000))
                    .width(160).height(120).build();
            var audio = pcmTrack(2);
            record Release(double seconds, Packet packet) {
            }
            var releases = new ArrayList<Release>();
            for (var frame = 0; frame < 300; frame++) {
                var release = Math.min(300, (frame / 16 + 1) * 16 + 35) / 30.0;
                releases.add(new Release(release, new Packet(1, new byte[200], frame * 3000L, frame * 3000L, 3000, true)));
            }
            for (var block = 0; block < 500; block++) {
                releases.add(new Release(block * 0.02, new Packet(2, new byte[1920], block * 960L, block * 960L, 960, true)));
            }
            releases.sort(java.util.Comparator.comparingDouble(Release::seconds));
            var output = MuxSupport.toMemory(List.of(video, audio), releases.stream().map(Release::packet).toList(),
                    MatroskaMuxer::new);
            MatroskaStructure.check(output);
            var result = MuxSupport.demux(output);
            assertEquals(800, result.order().size());
            var timeBases = new HashMap<Integer, Rational>();
            result.tracks().forEach(track -> timeBases.put(track.id(), track.timeBase()));
            var last = Long.MIN_VALUE;
            for (var packet : result.order()) {
                var micros = timeBases.get(packet.trackId()).toMicros(packet.dts());
                assertTrue(micros >= last, "block at " + micros + " us after one at " + last + " us");
                last = micros;
            }
        }

        @Test
        @DisplayName("splits clusters when timestamps jump beyond the 16-bit block offset")
        void timestampJumps() {
            var track = pcmTrack(0);
            var packets = new ArrayList<Packet>();
            for (var index = 0; index < 20; index++) {
                var time = index * 48_000L * 3600;
                packets.add(new Packet(0, new byte[960 * 2], time, time, 960, true));
            }
            var output = MuxSupport.toMemory(List.of(track), packets, MatroskaMuxer::new);
            var structure = MatroskaStructure.check(output);
            assertEquals(20, structure.clusterPositions.size());
            var result = MuxSupport.demux(output);
            assertEquals(19L * 3600 * 48_000, result.order().getLast().pts());
        }

        @Test
        @DisplayName("shifts negative start times to zero")
        void negativeStart() {
            var track = pcmTrack(0);
            var packets = List.of(new Packet(0, new byte[1920], -960, -960, 960, true), new Packet(0, new byte[1920], 0, 0, 960, true));
            var result = MuxSupport.demux(MuxSupport.toMemory(List.of(track), packets, MatroskaMuxer::new));
            assertEquals(0, result.order().getFirst().pts());
            assertEquals(960, result.order().getLast().pts());
        }

        @Test
        @DisplayName("rejects timestamps that do not fit microseconds")
        void hugeTimestamps() {
            var track = AudioTrack.builder()
                    .id(0)
                    .codec(Codec.PCM_S16LE)
                    .timeBase(new Rational(1, 1))
                    .duration(-1)
                    .sampleRate(48_000)
                    .layout(ChannelLayout.MONO)
                    .bitsPerSample(16)
                    .skipSamples(0)
                    .build();
            try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
                muxer.addTrack(track);
                assertThrows(IllegalArgumentException.class,
                        () -> muxer.writePacket(new Packet(0, new byte[2], Long.MAX_VALUE / 2, Long.MAX_VALUE / 2, 0, true)));
            }
        }

        @Test
        @DisplayName("rejects H.264 in WebM, undeclared tracks and tracks added late")
        void rejections() {
            var h264 = VideoTrack.builder()
                    .id(0)
                    .codec(Codec.H264)
                    .timeBase(Rational.MILLISECONDS)
                    .duration(-1)
                    .codecConfig(new byte[]{1, 2, 3})
                    .width(16)
                    .height(16)
                    .orientation(Orientation.NORMAL)
                    .build();
            try (var muxer = new MatroskaMuxer(MediaSink.memory(), MatroskaMuxerOptions.webm())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(h264));
            }
            assertFalse(MatroskaMuxer.supports(MatroskaDocType.WEBM, Codec.AAC));
            assertTrue(MatroskaMuxer.supports(MatroskaDocType.MATROSKA, Codec.AAC));
            try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
                muxer.addTrack(pcmTrack(0));
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(pcmTrack(0)));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(5, new byte[2], 0, 0, 1, true)));
                muxer.writePacket(new Packet(0, new byte[2], 0, 0, 1, true));
                assertThrows(IllegalStateException.class, () -> muxer.addTrack(pcmTrack(1)));
                muxer.finish();
                assertThrows(IllegalStateException.class, muxer::finish);
            }
        }
    }

    /** Returns the offset of the first occurrence of a byte pattern at or after an offset, or -1. */
    private static int indexOf(byte[] data, int from, byte[] pattern) {
        outer:
        for (var i = Math.max(0, from); i + pattern.length <= data.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static AudioTrack pcmTrack(int id) {
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

    private static HashMap<Integer, Integer> identity(ContainerProbe.Demuxed source, ContainerProbe.Demuxed result) {
        var map = new HashMap<Integer, Integer>();
        for (var index = 0; index < source.tracks().size(); index++) {
            map.put(source.tracks().get(index).id(), result.tracks().get(index).id());
        }
        return map;
    }
}
