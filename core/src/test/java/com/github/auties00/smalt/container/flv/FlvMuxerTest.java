package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the FLV muxer: remuxes of ffmpeg's files and of the hand-built enhanced samples that the independent
 * {@code FlvValidator} accepts and that read back packet for packet (and, for the codecs ffmpeg 7.1 reads, that
 * ffprobe splits into the same packets and ffmpeg decodes without errors); the key frame index on seekable and
 * streaming sinks and through the temporary file; tags and XMP; configuration changes; multitrack output;
 * interleaving; and the rejected inputs.
 */
@DisplayName("FLV muxer")
class FlvMuxerTest {
    static final Path SAMPLES = FlvDemuxerTest.SAMPLES;

    record Remux(List<Track> tracks, List<Packet> packets, byte[] file) {
    }

    /** Remuxes every packet of a demuxer into FLV. */
    static Remux remux(byte[] input, FlvMuxerOptions options, MediaTags tags) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(input))) {
            var sink = MediaSink.memory();
            var packets = new ArrayList<Packet>();
            try (var muxer = new FlvMuxer(sink, options)) {
                for (var track : demuxer.tracks()) {
                    muxer.addTrack(track);
                }
                if (tags != null) {
                    muxer.setTags(tags);
                }
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            return new Remux(demuxer.tracks(), packets, sink.toByteArray());
        }
    }

    /** Asserts that a remux reads back with the same tracks and the same packets, rescaled to milliseconds. */
    static void assertReadsBack(Remux remux) {
        Validators.assertValidFlv("remux", remux.file());
        try (var demuxer = new FlvDemuxer(MediaSource.of(remux.file()))) {
            assertEquals(remux.tracks().size(), demuxer.tracks().size());
            var byId = new HashMap<Integer, Track>();
            for (var source : remux.tracks()) {
                var match = demuxer.tracks().stream().filter(track -> track.codec() == source.codec()
                        && !byId.containsKey(track.id()) && track.kind() == source.kind()).findFirst().orElseThrow();
                byId.put(match.id(), source);
                if (source.codec() != Codec.MP3 && source.codecConfig().isPresent()) {
                    assertArrayEquals(source.codecConfig().get(), match.codecConfig().orElseThrow(),
                            "configuration of " + source.codec());
                }
            }
            var expected = new HashMap<Integer, List<Packet>>();
            for (var packet : remux.packets()) {
                expected.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
            }
            var actual = new HashMap<Integer, List<Packet>>();
            for (var packet : Packets.readAll(demuxer)) {
                actual.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
            }
            for (var entry : byId.entrySet()) {
                var source = entry.getValue();
                var ours = actual.getOrDefault(entry.getKey(), List.of());
                var theirs = expected.getOrDefault(source.id(), List.of());
                assertEquals(theirs.size(), ours.size(), "packets of " + source.codec());
                for (var index = 0; index < ours.size(); index++) {
                    var a = theirs.get(index);
                    var b = ours.get(index);
                    assertArrayEquals(a.data(), b.data(), source.codec() + " packet " + index);
                    assertEquals(a.keyframe(), b.keyframe(), source.codec() + " key flag " + index);
                    assertEquals(source.timeBase().rescale(a.pts(), Rational.MILLISECONDS), b.pts(),
                            source.codec() + " pts " + index);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"flv1-mp3.flv", "h264-aac.flv", "hevc.flv", "vp9.flv", "av1.flv",
            "opus-flac-multitrack.flv"})
    @DisplayName("remuxes the samples into files the validator accepts and that read back packet for packet")
    void samples(String sample) throws Exception {
        var remux = remux(Files.readAllBytes(SAMPLES.resolve(sample)), FlvMuxerOptions.defaults(), null);
        assertReadsBack(remux);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"flv1-mp3.flv", "h264-aac.flv", "hevc.flv", "vp9.flv", "av1.flv"})
    @DisplayName("writes files that ffprobe splits into the same packets and ffmpeg decodes without errors")
    void ffmpegReads(String sample, @TempDir Path directory) throws Exception {
        var source = SAMPLES.resolve(sample);
        var remux = remux(Files.readAllBytes(source), FlvMuxerOptions.defaults(), null);
        var file = directory.resolve("remux.flv");
        Files.write(file, remux.file());
        var original = byStream(FlvDemuxerTest.probe(source));
        var written = byStream(FlvDemuxerTest.probe(file));
        assertEquals(original.size(), written.size(), "streams");
        for (var stream : original.values()) {
            var match = written.values().stream().filter(other -> other.getFirst().crc() == stream.getFirst().crc())
                    .findFirst().orElseThrow();
            assertEquals(stream.size(), match.size(), "packets");
            for (var index = 0; index < stream.size(); index++) {
                var a = stream.get(index);
                var b = match.get(index);
                assertEquals(a.crc(), b.crc(), "payload of packet " + index);
                assertEquals(a.pts(), b.pts(), "pts of packet " + index);
                assertEquals(a.dts(), b.dts(), "dts of packet " + index);
                assertEquals(a.key(), b.key(), "key flag of packet " + index);
            }
        }
        Oracle.assertDecodesCleanly(file);
    }

    static Map<Integer, List<FlvDemuxerTest.Probed>> byStream(List<FlvDemuxerTest.Probed> packets) {
        var streams = new HashMap<Integer, List<FlvDemuxerTest.Probed>>();
        for (var packet : packets) {
            streams.computeIfAbsent(packet.stream(), _ -> new ArrayList<>()).add(packet);
        }
        return streams;
    }

    @Test
    @DisplayName("remuxes an MP4 file of H.264 with B-frames and AAC with the composition time offsets")
    void fromMp4() {
        var mp4 = Fixtures.ffmpeg("mp4", FlvDemuxerTest.VIDEO_AUDIO, List.of("-c:v", "libx264", "-preset",
                "ultrafast", "-bf", "3", "-g", "12", "-c:a", "aac", "-ac", "2"));
        var remux = remux(Fixtures.read(mp4), FlvMuxerOptions.defaults(), null);
        assertReadsBack(remux);
        try (var demuxer = new FlvDemuxer(MediaSource.of(remux.file()))) {
            var video = demuxer.tracks().stream().filter(track -> track.codec() == Codec.H264).findFirst()
                    .orElseThrow();
            var reordered = Packets.readAll(demuxer).stream().filter(packet -> packet.trackId() == video.id())
                    .anyMatch(packet -> packet.pts() != packet.dts());
            assertTrue(reordered, "B-frames keep their composition time offsets");
        }
    }

    @Nested
    @DisplayName("onMetaData")
    class MetaData {
        @Test
        @DisplayName("states the duration, file size, codecs and frame geometry on a seekable sink")
        void patched() throws Exception {
            var remux = remux(Files.readAllBytes(SAMPLES.resolve("h264-aac.flv")), FlvMuxerOptions.defaults(), null);
            var summary = Validators.assertValidFlv("patched", remux.file());
            var metadata = summary.metadata();
            assertEquals((double) remux.file().length, metadata.get("filesize"));
            assertEquals(160.0, metadata.get("width"));
            assertEquals(120.0, metadata.get("height"));
            assertEquals(7.0, metadata.get("videocodecid"));
            assertEquals(10.0, metadata.get("audiocodecid"));
            assertEquals(44100.0, metadata.get("audiosamplerate"));
            assertEquals("Smalt", metadata.get("encoder"));
            assertTrue((Double) metadata.get("duration") >= 1.9, "duration " + metadata.get("duration"));
            try (var demuxer = new FlvDemuxer(MediaSource.of(remux.file()))) {
                assertEquals(Math.round((Double) metadata.get("duration") * 1_000_000), demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("writes the FourCC codec identifiers and the extra tracks of a multitrack file")
        void enhanced() throws Exception {
            var remux = remux(Files.readAllBytes(SAMPLES.resolve("opus-flac-multitrack.flv")),
                    FlvMuxerOptions.defaults(), null);
            var metadata = Validators.assertValidFlv("enhanced", remux.file()).metadata();
            assertEquals((double) FlvCodecs.fourCc("Opus"), metadata.get("audiocodecid"));
            var extra = (Map<?, ?>) ((Map<?, ?>) metadata.get("audioTrackIdInfoMap")).get("1");
            assertEquals((double) FlvCodecs.fourCc("fLaC"), extra.get("audiocodecid"));
            assertEquals(22050.0, extra.get("samplerate"));
        }

        @Test
        @DisplayName("keeps zero duration and size on a streaming sink")
        void streaming() throws Exception {
            var input = Files.readAllBytes(SAMPLES.resolve("flv1-mp3.flv"));
            var stream = new ByteArrayOutputStream();
            try (var demuxer = new FlvDemuxer(MediaSource.of(input)); var sink = MediaSink.of(stream);
                 var muxer = new FlvMuxer(sink)) {
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            var metadata = Validators.assertValidFlv("streaming", stream.toByteArray()).metadata();
            assertEquals(0.0, metadata.get("duration"));
            assertEquals(0.0, metadata.get("filesize"));
        }

        @Test
        @DisplayName("stores text tags as onMetaData strings, leaves out its own properties, and the XMP packet")
        void tags() throws Exception {
            var xmp = XmpPacket.empty().with("http://purl.org/dc/elements/1.1/", "title", "XMP title");
            var tags = MediaTags.builder().set(StandardTag.TITLE, "A title").set(StandardTag.ARTIST, "Someone")
                    .set("width", "999").set(StandardTag.ENCODER, "Tool 1.0").xmp(xmp).build();
            var remux = remux(Files.readAllBytes(SAMPLES.resolve("flv1-mp3.flv")), FlvMuxerOptions.defaults(), tags);
            try (var demuxer = new FlvDemuxer(MediaSource.of(remux.file()))) {
                assertEquals("A title", demuxer.metadata().get("title"));
                assertEquals("Someone", demuxer.metadata().get("artist"));
                assertEquals("160", demuxer.metadata().get("width"));
                assertEquals("Tool 1.0", demuxer.metadata().get("encoder"));
            }
            var text = new String(remux.file(), StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("onXMPData") && text.contains("liveXML") && text.contains("XMP title"));
            assertTrue(new FlvMuxer(MediaSink.memory()).supportsTags());
        }
    }

    @Nested
    @DisplayName("key frame index")
    class KeyframeIndex {
        @Test
        @DisplayName("indexes every key frame of the first video track, identically on seekable and streaming sinks")
        void index(@TempDir Path directory) throws Exception {
            var input = Files.readAllBytes(SAMPLES.resolve("h264-aac.flv"));
            var options = FlvMuxerOptions.builder().keyframeIndex(true).temporaryDirectory(directory).build();
            var seekable = remux(input, options, null);
            var summary = Validators.assertValidFlv("indexed", seekable.file());
            var keys = seekable.packets().stream().filter(packet -> packet.keyframe() && packet.trackId()
                    == seekable.tracks().stream().filter(track -> track.codec() == Codec.H264).findFirst()
                    .orElseThrow().id()).count();
            assertEquals(keys, summary.keyframesIndexed());
            assertEquals((double) seekable.file().length, summary.metadata().get("filesize"));
            assertTrue((Double) summary.metadata().get("duration") > 1.9);
            var stream = new ByteArrayOutputStream();
            try (var demuxer = new FlvDemuxer(MediaSource.of(input)); var sink = MediaSink.of(stream);
                 var muxer = new FlvMuxer(sink, options)) {
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            assertArrayEquals(seekable.file(), stream.toByteArray());
            assertReadsBack(seekable);
            try (var listing = Files.list(directory)) {
                assertEquals(0, listing.count(), "the spool leaves no file behind");
            }
        }

        @Test
        @DisplayName("spills to a temporary file past 16 MiB and writes the same bytes")
        void spill(@TempDir Path directory) {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.MILLISECONDS).width(64)
                    .height(48).codecConfig(Streams.AVCC).build();
            var payload = new byte[1 << 20];
            for (var index = 0; index < payload.length; index++) {
                payload[index] = (byte) (index * 31);
            }
            var sink = MediaSink.memory();
            try (var muxer = new FlvMuxer(sink, FlvMuxerOptions.builder().keyframeIndex(true)
                    .temporaryDirectory(directory).build())) {
                muxer.addTrack(track);
                for (var index = 0; index < 20; index++) {
                    muxer.writePacket(new Packet(1, payload, index * 40L, index * 40L, 40, index % 5 == 0));
                }
                muxer.finish();
            }
            var big = sink.toByteArray();
            assertTrue(big.length > 20 << 20);
            var summary = Validators.assertValidFlv("spilled", big);
            assertEquals(4, summary.keyframesIndexed());
            try (var demuxer = new FlvDemuxer(MediaSource.of(big))) {
                var packets = Packets.readAll(demuxer);
                assertEquals(20, packets.size());
                assertArrayEquals(payload, packets.get(19).data());
                demuxer.seek(500_000);
                assertEquals(400, demuxer.read().pts());
            }
        }
    }

    @Nested
    @DisplayName("streams")
    class Streams {
        static AudioTrack aac(int id) {
            return AudioTrack.builder().id(id).codec(Codec.AAC).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                    .layout(ChannelLayout.STEREO).codecConfig(new byte[]{0x12, 0x10}).build();
        }

        static VideoTrack h264(int id, byte[] config) {
            return VideoTrack.builder().id(id).codec(Codec.H264).timeBase(new Rational(1, 90000)).width(64).height(48)
                    .codecConfig(config).build();
        }

        static final byte[] AVCC = {1, 0x42, 0, 0x1E, (byte) 0xFF, (byte) 0xE0, 0};

        @Test
        @DisplayName("writes the second track of a kind as a multitrack track and reads both back")
        void multitrack() {
            var sink = MediaSink.memory();
            var packets = new ArrayList<Packet>();
            try (var muxer = new FlvMuxer(sink)) {
                muxer.addTrack(h264(1, AVCC));
                muxer.addTrack(h264(2, AVCC));
                muxer.addTrack(aac(3));
                muxer.addTrack(aac(4));
                for (var index = 0; index < 10; index++) {
                    for (var id = 1; id <= 4; id++) {
                        var video = id <= 2;
                        var timestamp = video ? index * 3600L : index * 1024L;
                        var packet = new Packet(id, new byte[]{(byte) id, (byte) index, 0, 1}, timestamp, timestamp,
                                Packet.UNKNOWN_DURATION, !video || index % 4 == 0);
                        packets.add(packet);
                        muxer.writePacket(packet);
                    }
                }
                muxer.finish();
            }
            var file = sink.toByteArray();
            var summary = Validators.assertValidFlv("multitrack", file);
            assertEquals(2, summary.videoStreams());
            assertEquals(2, summary.audioStreams());
            try (var demuxer = new FlvDemuxer(MediaSource.of(file))) {
                assertEquals(4, demuxer.tracks().size());
                var read = Packets.readAll(demuxer);
                assertEquals(packets.size(), read.size());
                for (var track : demuxer.tracks()) {
                    assertEquals(10, read.stream().filter(packet -> packet.trackId() == track.id()).count());
                }
            }
        }

        @Test
        @DisplayName("interleaves packets written track after track by timestamp")
        void interleaves() {
            var sink = MediaSink.memory();
            try (var muxer = new FlvMuxer(sink)) {
                muxer.addTrack(h264(1, AVCC));
                muxer.addTrack(aac(2));
                for (var index = 0; index < 20; index++) {
                    muxer.writePacket(new Packet(1, new byte[]{0, 0, 0, 1, 0x65}, index * 3600L, index * 3600L,
                            3600, index == 0));
                }
                for (var index = 0; index < 30; index++) {
                    muxer.writePacket(new Packet(2, new byte[]{1, 2}, index * 1024L, index * 1024L, 1024, true));
                }
                muxer.finish();
            }
            var packets = FlvDemuxerTest.read(sink.toByteArray());
            for (var index = 1; index < packets.size(); index++) {
                assertTrue(packets.get(index).dts() >= packets.get(index - 1).dts(), "tag " + index + " is in order");
            }
        }

        @Test
        @DisplayName("writes a new sequence start for a configuration change and keeps signed timestamps")
        void configChange() {
            var sink = MediaSink.memory();
            var second = new byte[]{0x13, (byte) 0x88};
            try (var muxer = new FlvMuxer(sink)) {
                muxer.addTrack(aac(1));
                muxer.writePacket(new Packet(1, new byte[]{1}, -2048, -2048, 1024, true));
                muxer.writePacket(new Packet(1, new byte[]{2}, 0, 0, 1024, Set.of(PacketFlag.KEY),
                        PacketSideData.builder().newCodecConfig(second).build()));
                muxer.writePacket(new Packet(1, new byte[]{3}, 1024, 1024, 1024, true));
                muxer.finish();
            }
            Validators.assertValidFlv("change", sink.toByteArray());
            var packets = FlvDemuxerTest.read(sink.toByteArray());
            assertEquals(-46, packets.get(0).pts());
            assertArrayEquals(second, packets.get(1).sideData().newCodecConfig().orElseThrow());
            assertTrue(packets.get(2).sideData().newCodecConfig().isEmpty());
        }

        @Test
        @DisplayName("writes Sorenson frame types from the picture type, disposable pictures included")
        void sorensonFrameTypes() {
            var sink = MediaSink.memory();
            var track = VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).width(32)
                    .height(16).build();
            try (var muxer = new FlvMuxer(sink)) {
                muxer.addTrack(track);
                muxer.writePacket(new Packet(1, FlvDemuxerTest.sorenson(0, 32, 16), 0, 0, 33, false));
                muxer.writePacket(new Packet(1, FlvDemuxerTest.sorenson(1, 32, 16), 33, 33, 33, true));
                muxer.writePacket(new Packet(1, FlvDemuxerTest.sorenson(2, 32, 16), 66, 66, 33, false));
                muxer.finish();
            }
            var packets = FlvDemuxerTest.read(sink.toByteArray());
            assertTrue(packets.get(0).keyframe(), "an intra picture is a key frame whatever the packet says");
            assertFalse(packets.get(1).keyframe());
            assertTrue(packets.get(2).flags().contains(PacketFlag.DISCARDABLE));
        }

        @Test
        @DisplayName("derives the sequence start of AV1, VP9 and VP8 tracks without a configuration")
        void derivedConfiguration() throws Exception {
            for (var sample : List.of("av1.flv", "vp9.flv")) {
                var input = Files.readAllBytes(SAMPLES.resolve(sample));
                try (var demuxer = new FlvDemuxer(MediaSource.of(input))) {
                    var source = (VideoTrack) demuxer.tracks().getFirst();
                    var bare = source.toBuilder().codecConfig(null).build();
                    var sink = MediaSink.memory();
                    try (var muxer = new FlvMuxer(sink)) {
                        muxer.addTrack(bare);
                        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                            muxer.writePacket(packet);
                        }
                        muxer.finish();
                    }
                    Validators.assertValidFlv(sample, sink.toByteArray());
                    try (var back = new FlvDemuxer(MediaSource.of(sink.toByteArray()))) {
                        var config = back.tracks().getFirst().codecConfig().orElseThrow();
                        assertTrue(config.length >= 4, sample);
                        if (source.codec() == Codec.AV1) {
                            assertEquals(0x81, config[0] & 0xFF);
                        } else {
                            assertEquals(source.codecConfig().orElseThrow()[0], config[0], "VP9 profile");
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects what FLV cannot carry")
        void rejects() {
            var muxer = new FlvMuxer(MediaSink.memory());
            assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, new byte[1], 0, 0, 1,
                    true)));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(1)
                    .codec(Codec.VORBIS).timeBase(Rational.MILLISECONDS).build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(h264(1, null)));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(1)
                    .codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .layout(ChannelLayout.STEREO).build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(1)
                    .codec(Codec.SPEEX).timeBase(Rational.MILLISECONDS).sampleRate(8000).build()));
            muxer.addTrack(VideoTrack.builder().id(1).codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).build());
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(VideoTrack.builder().id(2)
                    .codec(Codec.FLV1).timeBase(Rational.MILLISECONDS).build()), "Sorenson only as track 0");
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(h264(1, AVCC)), "duplicate id");
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(9, new byte[1], 0, 0,
                    1, true)));
            assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(new Packet(1, new byte[3], 0, 0,
                    1, Set.of(PacketFlag.KEY), PacketSideData.builder().newCodecConfig(new byte[]{1}).build())));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1,
                    new byte[0x1000000], 0, 0, 1, true)), "a body over 16 MiB");
            muxer.writePacket(new Packet(1, FlvDemuxerTest.sorenson(0, 16, 16), 0, 0, 1, true));
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(aac(5)));
            assertThrows(IllegalStateException.class, () -> muxer.setTags(MediaTags.empty()));
            muxer.finish();
            assertThrows(IllegalStateException.class, muxer::finish);
            muxer.close();
            assertTrue(FlvMuxer.supports(Codec.HEVC) && FlvMuxer.supports(Codec.OPUS) && !FlvMuxer.supports(Codec.MP2));
            assertEquals(Containers.isMuxable(ContainerFormat.FLV, Codec.AV1), FlvMuxer.supports(Codec.AV1));
        }

        @Test
        @DisplayName("opens through Containers and keeps the options of its builder")
        void options() {
            try (var muxer = Containers.openMuxer(ContainerFormat.FLV, MediaSink.memory())) {
                assertTrue(muxer instanceof FlvMuxer);
            }
            var options = FlvMuxerOptions.builder().keyframeIndex(true).temporaryDirectory(Path.of("x")).build();
            assertEquals(options, options.toBuilder().build());
            assertTrue(options.keyframeIndex());
            assertEquals(Path.of("x"), options.temporaryDirectory().orElseThrow());
            assertTrue(options.toBuilder().defaultTemporaryDirectory().build().temporaryDirectory().isEmpty());
            assertFalse(FlvMuxerOptions.defaults().keyframeIndex());
            assertThrows(NullPointerException.class, () -> FlvMuxerOptions.builder().temporaryDirectory(null));
        }
    }
}
