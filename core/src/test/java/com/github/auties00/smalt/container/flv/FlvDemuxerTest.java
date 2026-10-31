package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;

import static com.github.auties00.smalt.container.flv.FlvBuilder.ascii;
import static com.github.auties00.smalt.container.flv.FlvBuilder.bytes;
import static com.github.auties00.smalt.container.flv.FlvBuilder.concat;
import static com.github.auties00.smalt.container.flv.FlvBuilder.u24;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the FLV demuxer: files of ffmpeg's FLV muxer, legacy and enhanced, against ffprobe's packets and against
 * the MP4 file they were remuxed from; hand-built enhanced headers (multitrack, ModEx, metadata frames, commands);
 * the onMetaData object; configuration changes; resynchronization; seeking through the onMetaData key frame index
 * and through the full index against the seek contract; and mutation robustness.
 */
@DisplayName("FLV demuxer")
class FlvDemuxerTest {
    static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "flv");

    record Probed(int stream, long pts, long dts, int size, boolean key, long crc) {
    }

    /** Returns ffprobe's packets of every stream of a file, in file order. */
    static List<Probed> probe(Path file) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-show_entries", "packet=stream_index,pts,dts,size,flags,data_hash", "-show_data_hash", "CRC32",
                "-of", "compact=p=0:nk=0", file.toString())), StandardCharsets.UTF_8);
        var packets = new ArrayList<Probed>();
        for (var line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            var fields = new HashMap<String, String>();
            for (var field : line.split("\\|")) {
                var separator = field.indexOf('=');
                if (separator > 0) {
                    fields.put(field.substring(0, separator), field.substring(separator + 1).trim());
                }
            }
            var hash = fields.get("data_hash");
            packets.add(new Probed(Integer.parseInt(fields.get("stream_index")), Long.parseLong(fields.get("pts")),
                    Long.parseLong(fields.get("dts")), Integer.parseInt(fields.get("size")),
                    fields.get("flags").startsWith("K"), Long.parseLong(hash.substring(hash.indexOf(':') + 1), 16)));
        }
        return packets;
    }

    static long crc(byte[] data) {
        var crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    static List<Packet> read(byte[] data) {
        try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
            return Packets.readAll(demuxer);
        }
    }

    record Fixture(String name, List<String> input, List<String> output, List<Codec> codecs) {
        @Override
        public String toString() {
            return name;
        }
    }

    static final List<String> VIDEO = List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=15:duration=2");

    static final List<String> VIDEO_AUDIO = List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=15:duration=2",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2");

    static Stream<Fixture> ffmpegFiles() {
        return Stream.of(
                new Fixture("Sorenson Spark and MP3", VIDEO_AUDIO, List.of("-c:v", "flv1", "-g", "8", "-c:a",
                        "libmp3lame", "-ac", "1", "-b:a", "48k"), List.of(Codec.FLV1, Codec.MP3)),
                new Fixture("H.264 with B-frames and AAC", VIDEO_AUDIO, List.of("-c:v", "libx264", "-preset",
                        "ultrafast", "-bf", "2", "-g", "10", "-c:a", "aac", "-b:a", "64k"),
                        List.of(Codec.H264, Codec.AAC)),
                new Fixture("HEVC and AAC", VIDEO_AUDIO, List.of("-c:v", "libx265", "-preset", "ultrafast", "-g",
                        "6", "-x265-params", "log-level=error:pools=1:frame-threads=1", "-c:a", "aac", "-ac", "2"),
                        List.of(Codec.HEVC, Codec.AAC)),
                new Fixture("VP9", VIDEO, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
                        "-g", "6", "-b:v", "200k"), List.of(Codec.VP9)),
                new Fixture("AV1", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=15:duration=0.6"),
                        List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-g", "4"), List.of(Codec.AV1)),
                new Fixture("16-bit stereo PCM", List.of("-f", "lavfi", "-i",
                        "sine=frequency=440:sample_rate=22050:duration=0.5"), List.of("-c:a", "pcm_s16le", "-ac", "2"),
                        List.of(Codec.PCM_S16LE)),
                new Fixture("8-bit PCM", List.of("-f", "lavfi", "-i", "sine=sample_rate=11025:duration=0.5"),
                        List.of("-c:a", "pcm_u8"), List.of(Codec.PCM_U8)),
                new Fixture("A-law", List.of("-f", "lavfi", "-i", "sine=sample_rate=8000:duration=0.5"),
                        List.of("-c:a", "pcm_alaw"), List.of(Codec.PCM_ALAW)),
                new Fixture("mu-law", List.of("-f", "lavfi", "-i", "sine=sample_rate=8000:duration=0.5"),
                        List.of("-c:a", "pcm_mulaw"), List.of(Codec.PCM_MULAW)),
                new Fixture("Speex", List.of("-f", "lavfi", "-i", "sine=sample_rate=16000:duration=0.5"),
                        List.of("-c:a", "libspeex"), List.of(Codec.SPEEX)),
                new Fixture("Nellymoser", List.of("-f", "lavfi", "-i", "sine=sample_rate=22050:duration=0.5"),
                        List.of("-c:a", "nellymoser"), List.of(Codec.UNKNOWN_AUDIO)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ffmpegFiles")
    @DisplayName("splits ffmpeg's files exactly as ffprobe does: timestamps, sizes, key frames and payload CRCs")
    void matchesFfprobe(Fixture fixture) {
        var file = Fixtures.ffmpeg("flv", fixture.input(), fixture.output());
        var probed = probe(file);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(ContainerFormat.FLV, demuxer.format());
            assertInstanceOf(FlvDemuxer.class, demuxer);
            var codecs = demuxer.tracks().stream().map(Track::codec).sorted().toList();
            assertEquals(fixture.codecs().stream().sorted().toList(), codecs);
            for (var track : demuxer.tracks()) {
                assertEquals(Rational.MILLISECONDS, track.timeBase());
            }
            var packets = Packets.readAll(demuxer);
            assertEquals(probed.size(), packets.size(), "packet count");
            var streamOfTrack = new HashMap<Integer, Integer>();
            for (var index = 0; index < packets.size(); index++) {
                var packet = packets.get(index);
                var expected = probed.get(index);
                var stream = streamOfTrack.computeIfAbsent(packet.trackId(), _ -> expected.stream());
                assertEquals(expected.stream(), stream, "stream of packet " + index);
                assertEquals(expected.pts(), packet.pts(), "pts of packet " + index);
                assertEquals(expected.dts(), packet.dts(), "dts of packet " + index);
                assertEquals(expected.size(), packet.size(), "size of packet " + index);
                assertEquals(expected.crc(), crc(packet.data()), "payload of packet " + index);
                assertEquals(expected.key(), packet.keyframe(), "key flag of packet " + index);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ffmpegFiles")
    @DisplayName("describes the tracks as ffprobe does: frame size, sampling rate and channels")
    void describesTracks(Fixture fixture) {
        var file = Fixtures.ffmpeg("flv", fixture.input(), fixture.output());
        Oracle.assume(Oracle.Tool.FFPROBE);
        var streams = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "stream=codec_type,width,height,sample_rate,channels", "-of", "compact=p=0:nk=0",
                file.toString())), StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank()).toList();
        try (var demuxer = new FlvDemuxer(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(streams.size(), demuxer.tracks().size());
            for (var index = 0; index < streams.size(); index++) {
                var line = streams.get(index);
                var track = demuxer.tracks().get(index);
                if (track instanceof VideoTrack video) {
                    assertTrue(line.contains("width=" + video.width() + "|"), line + " vs " + video);
                    assertTrue(line.contains("height=" + video.height()), line + " vs " + video);
                } else if (track instanceof AudioTrack audio && audio.codec() != Codec.UNKNOWN_AUDIO) {
                    assertTrue(line.contains("sample_rate=" + audio.sampleRate()), line + " vs " + audio);
                    assertTrue(line.contains("channels=" + audio.layout().orElseThrow().channels()),
                            line + " vs " + audio);
                }
            }
        }
    }

    static Stream<Fixture> mp4Sources() {
        return Stream.of(
                new Fixture("H.264 with B-frames and AAC", VIDEO_AUDIO, List.of("-c:v", "libx264", "-preset",
                        "ultrafast", "-bf", "3", "-g", "12", "-c:a", "aac", "-b:a", "64k", "-ac", "2"),
                        List.of(Codec.H264, Codec.AAC)),
                new Fixture("HEVC and MP3", VIDEO_AUDIO, List.of("-c:v", "libx265", "-preset", "ultrafast", "-g", "6",
                        "-x265-params", "log-level=error:pools=1:frame-threads=1", "-c:a", "libmp3lame", "-ac", "1"),
                        List.of(Codec.HEVC, Codec.MP3)),
                new Fixture("AV1", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=15:duration=0.6"),
                        List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-g", "4"), List.of(Codec.AV1)),
                new Fixture("VP9", VIDEO, List.of("-c:v", "libvpx-vp9", "-deadline", "realtime", "-cpu-used", "8",
                        "-g", "6", "-b:v", "200k"), List.of(Codec.VP9)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mp4Sources")
    @DisplayName("reads the packets and configurations of an MP4 file that ffmpeg remuxed into FLV")
    void matchesMp4(Fixture fixture) {
        var mp4 = Fixtures.ffmpeg("mp4", fixture.input(), fixture.output());
        var flv = Fixtures.ffmpeg("flv", List.of("-i", mp4.toString()), List.of("-c", "copy"));
        try (var original = Containers.openDemuxer(MediaSource.of(Fixtures.read(mp4)));
             var remuxed = new FlvDemuxer(MediaSource.of(Fixtures.read(flv)))) {
            assertEquals(original.tracks().size(), remuxed.tracks().size());
            var pairs = new HashMap<Integer, Track>();
            for (var track : original.tracks()) {
                var match = remuxed.tracks().stream().filter(other -> other.kind() == track.kind()).findFirst()
                        .orElseThrow();
                assertEquals(track.codec(), match.codec());
                if (track.codec() != Codec.MP3) {
                    assertArrayEquals(track.codecConfig().orElseThrow(), match.codecConfig().orElseThrow(),
                            "configuration of " + track.codec());
                }
                pairs.put(match.id(), track);
            }
            var expected = new HashMap<Integer, List<Packet>>();
            for (var packet : Packets.readAll(original)) {
                expected.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
            }
            var actual = new HashMap<Integer, List<Packet>>();
            for (var packet : Packets.readAll(remuxed)) {
                actual.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
            }
            for (var entry : pairs.entrySet()) {
                var source = entry.getValue();
                var ours = actual.get(entry.getKey());
                var theirs = expected.get(source.id());
                assertEquals(theirs.size(), ours.size(), "packets of " + source.codec());
                long offset = Long.MIN_VALUE;
                for (var index = 0; index < ours.size(); index++) {
                    var a = theirs.get(index);
                    var b = ours.get(index);
                    assertArrayEquals(a.data(), b.data(), source.codec() + " packet " + index);
                    assertEquals(a.keyframe(), b.keyframe(), source.codec() + " key flag " + index);
                    var pts = source.timeBase().rescale(a.pts(), Rational.MILLISECONDS);
                    var dts = source.timeBase().rescale(a.dts(), Rational.MILLISECONDS);
                    if (offset == Long.MIN_VALUE) {
                        offset = b.dts() - dts;
                    }
                    // ffmpeg rounds each timestamp once on the way into milliseconds, after shifting the stream
                    assertTrue(Math.abs(b.pts() - offset - pts) <= 1, source.codec() + " pts " + index + ": "
                            + b.pts() + " vs " + pts);
                    assertTrue(Math.abs(b.dts() - offset - dts) <= 1, source.codec() + " dts " + index);
                }
            }
        }
    }

    @Nested
    @DisplayName("onMetaData")
    class MetaData {
        @Test
        @DisplayName("exposes the scalar values, the duration and an NTSC frame rate")
        void values() {
            var metadata = new FlvBuilder.Amf().string("onMetaData").ecma(6)
                    .name("duration").number(12.5)
                    .name("framerate").number(30000.0 / 1001)
                    .name("width").number(64)
                    .name("height").number(32)
                    .name("stereo").bool(true)
                    .name("encoder").string("hand")
                    .end().bytes();
            var data = new FlvBuilder(false, true)
                    .tag(18, 0, metadata)
                    .tag(9, 0, concat(bytes(0x12), sorenson(0, 64, 32)))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertEquals(12_500_000, demuxer.durationMicros());
                assertEquals("12.5", demuxer.metadata().get("duration"));
                assertEquals("64", demuxer.metadata().get("width"));
                assertEquals("true", demuxer.metadata().get("stereo"));
                assertEquals("hand", demuxer.metadata().get("encoder"));
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(new Rational(30000, 1001), track.frameRate().orElseThrow());
                assertEquals(64, track.width());
                assertEquals(32, track.height());
            }
        }

        @Test
        @DisplayName("takes the duration from the timestamps of the first and last tags without onMetaData")
        void durationFromTags() {
            var data = new FlvBuilder(true, false)
                    .tag(8, 0, bytes(0x3E, 1, 2))
                    .tag(8, 500, bytes(0x3E, 3, 4))
                    .tag(8, 1250, bytes(0x3E, 5, 6))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertEquals(1_250_000, demuxer.durationMicros());
                var audio = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.PCM_S16LE, audio.codec());
                assertEquals(44100, audio.sampleRate());
                assertEquals(1, audio.layout().orElseThrow().channels());
            }
        }

        @Test
        @DisplayName("survives a corrupt onMetaData object")
        void corrupt() {
            var data = new FlvBuilder(true, false)
                    .tag(18, 0, concat(new FlvBuilder.Amf().string("onMetaData").bytes(), bytes(8, 0, 0, 0, 1, 0, 9)))
                    .tag(8, 0, bytes(0x3E, 1, 2))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertTrue(demuxer.metadata().isEmpty());
                assertEquals(1, read(data).size());
            }
        }
    }

    /** Returns a Sorenson picture header of a custom 8-bit size followed by stuffing. */
    static byte[] sorenson(int type, int width, int height) {
        // 17-bit start code, version 0, temporal reference 0, size 000, then the 8-bit width and height
        var bits = new StringBuilder("00000000000000001").append("00000").append("00000000").append("000");
        bits.append(String.format("%8s", Integer.toBinaryString(width)).replace(' ', '0'));
        bits.append(String.format("%8s", Integer.toBinaryString(height)).replace(' ', '0'));
        bits.append(type == 0 ? "00" : type == 1 ? "01" : "10").append("0").append("00101").append("0");
        while (bits.length() % 8 != 0) {
            bits.append('0');
        }
        var out = new byte[bits.length() / 8 + 2];
        for (var index = 0; index < bits.length(); index++) {
            if (bits.charAt(index) == '1') {
                out[index >> 3] |= (byte) (0x80 >>> (index & 7));
            }
        }
        return out;
    }

    @Nested
    @DisplayName("enhanced headers")
    class Enhanced {
        @Test
        @DisplayName("reads the Opus and FLAC tracks of a multitrack file with ModEx and ManyTracksManyCodecs")
        void multitrackAudio() throws Exception {
            var data = Files.readAllBytes(SAMPLES.resolve("opus-flac-multitrack.flv"));
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertEquals(2, demuxer.tracks().size());
                var opus = (AudioTrack) demuxer.tracks().get(0);
                var flac = (AudioTrack) demuxer.tracks().get(1);
                assertEquals(Codec.OPUS, opus.codec());
                assertEquals(48000, opus.sampleRate());
                assertEquals(1, opus.layout().orElseThrow().channels());
                assertTrue(new String(opus.codecConfig().orElseThrow(), 0, 8, StandardCharsets.US_ASCII)
                        .equals("OpusHead"));
                assertEquals(Codec.FLAC, flac.codec());
                assertEquals(22050, flac.sampleRate());
                assertArrayEquals(ascii("fLaC"), Arrays.copyOf(flac.codecConfig().orElseThrow(), 4));
                var packets = Packets.readAll(demuxer);
                var opusPackets = packets.stream().filter(packet -> packet.trackId() == opus.id()).toList();
                var flacPackets = packets.stream().filter(packet -> packet.trackId() == flac.id()).toList();
                // ffmpeg's Ogg Opus encoding of one second holds 51 packets of 20 ms
                assertEquals(51, opusPackets.size());
                assertEquals(10, flacPackets.size());
                for (var index = 0; index < opusPackets.size(); index++) {
                    assertEquals(20L * index, opusPackets.get(index).pts());
                    assertTrue(opusPackets.get(index).keyframe());
                }
                for (var index = 0; index < flacPackets.size(); index++) {
                    assertEquals(100L * index, flacPackets.get(index).pts());
                    // every FLAC frame starts with its sync code
                    assertEquals(0xFF, flacPackets.get(index).data()[0] & 0xFF);
                    assertEquals(0xF8, flacPackets.get(index).data()[1] & 0xFE);
                }
            }
        }

        @Test
        @DisplayName("splits a ManyTracks video message, applies the composition time of hvc1 and skips commands")
        void multitrackVideo() {
            var config = bytes(1, 1, 0x60, 0, 0, 0, 0x90, 0, 0, 0, 0, 0, 0x5D, 0xF0, 0, 0xFC, 0xFD, 0xF8, 0xF8, 0, 0,
                    0x0F, 0);
            var frameA = bytes(0, 0, 0, 2, 0x26, 0x01);
            var frameB = bytes(0, 0, 0, 2, 0x02, 0x01);
            // multitrack ManyTracks (type 1) of hvc1: SequenceStart for tracks 0 and 2
            var starts = concat(bytes(0x90 | 0x10 | 6, 0x10), ascii("hvc1"),
                    bytes(0), u24(config.length), config, bytes(2), u24(config.length), config);
            // CodedFrames for both tracks, each with a composition time offset of 40 ms
            var frames = concat(bytes(0x90 | 0x10 | 6, 0x11), ascii("hvc1"),
                    bytes(0), u24(3 + frameA.length), u24(40), frameA,
                    bytes(2), u24(3 + frameB.length), u24(40), frameB);
            var command = concat(bytes(0x80 | 0x50 | 1), ascii("hvc1"), bytes(0));
            var inter = concat(bytes(0x80 | 0x20 | 3), ascii("hvc1"), frameB);
            var data = new FlvBuilder(false, true)
                    .tag(9, 0, starts)
                    .tag(9, 0, frames)
                    .tag(9, 20, command)
                    .tag(9, 33, inter)
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertEquals(2, demuxer.tracks().size());
                for (var track : demuxer.tracks()) {
                    assertEquals(Codec.HEVC, track.codec());
                    assertArrayEquals(config, track.codecConfig().orElseThrow());
                }
                var packets = Packets.readAll(demuxer);
                assertEquals(3, packets.size());
                assertEquals(40, packets.get(0).pts());
                assertEquals(0, packets.get(0).dts());
                assertArrayEquals(frameA, packets.get(0).data());
                assertTrue(packets.get(0).keyframe());
                assertEquals(demuxer.tracks().get(1).id(), packets.get(1).trackId());
                assertArrayEquals(frameB, packets.get(1).data());
                assertEquals(33, packets.get(2).pts());
                assertFalse(packets.get(2).keyframe());
            }
        }

        @Test
        @DisplayName("skips ModEx data before the packet type and reads a colorInfo metadata frame into the color")
        void modExAndColorInfo() {
            var colorInfo = new FlvBuilder.Amf().string("colorInfo").object()
                    .name("colorConfig").object()
                    .name("bitDepth").number(10)
                    .name("colorPrimaries").number(9)
                    .name("transferCharacteristics").number(16)
                    .name("matrixCoefficients").number(9)
                    .end()
                    .name("hdrCll").object().name("maxFall").number(400).name("maxCLL").number(1000).end()
                    .name("hdrMdcv").object()
                    .name("redX").number(0.708).name("redY").number(0.292)
                    .name("greenX").number(0.17).name("greenY").number(0.797)
                    .name("blueX").number(0.131).name("blueY").number(0.046)
                    .name("whitePointX").number(0.3127).name("whitePointY").number(0.329)
                    .name("maxLuminance").number(1000).name("minLuminance").number(0.0001)
                    .end()
                    .end().bytes();
            var keyFrame = bytes(0x10, 0x01, 0x02, 0x03);
            var data = new FlvBuilder(false, true)
                    .tag(9, 0, concat(bytes(0x80 | 0x10 | 4), ascii("vp08"), colorInfo))
                    .tag(9, 0, concat(bytes(0x80 | 0x10 | 7, 255), bytes(0, 4), new byte[5], bytes(0x01),
                            ascii("vp08"), keyFrame))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.VP8, track.codec());
                var color = track.color().orElseThrow();
                assertEquals(ColorPrimaries.BT2020, color.primaries());
                assertEquals(TransferCharacteristics.PQ, color.transfer());
                assertEquals(1000, color.contentLightLevel().orElseThrow().maxContentLightLevel());
                assertEquals(400, color.contentLightLevel().orElseThrow().maxFrameAverageLightLevel());
                assertEquals(1000, color.masteringDisplay().orElseThrow().maxLuminance(), 1e-9);
                var packet = demuxer.read();
                assertArrayEquals(keyFrame, packet.data());
                assertTrue(packet.keyframe());
                assertNull(demuxer.read());
            }
        }

        @Test
        @DisplayName("maps the legacy codecs and VVC to unknown tracks whose packets still pass through")
        void unknownCodecs() {
            var data = new FlvBuilder(true, true)
                    .tag(9, 0, concat(bytes(0x14), bytes(0, 1, 2, 3)))
                    .tag(8, 0, concat(bytes(0x6E), bytes(9, 9)))
                    .tag(9, 40, concat(bytes(0x80 | 0x10 | 6, 0x03), ascii("vvc1"), bytes(1), bytes(7)))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                assertEquals(List.of(Codec.UNKNOWN_VIDEO, Codec.UNKNOWN_AUDIO, Codec.UNKNOWN_VIDEO),
                        demuxer.tracks().stream().map(Track::codec).toList());
                var packets = Packets.readAll(demuxer);
                assertEquals(3, packets.size());
                assertArrayEquals(bytes(0, 1, 2, 3), packets.get(0).data());
                assertArrayEquals(bytes(7), packets.get(2).data());
                assertEquals(demuxer.tracks().get(2).id(), packets.get(2).trackId());
            }
        }
    }

    @Nested
    @DisplayName("stream")
    class StreamBehavior {
        @Test
        @DisplayName("hands a later sequence header to the next packet as a new configuration")
        void configChange() {
            var first = bytes(0x12, 0x10);
            var second = bytes(0x13, 0x88);
            var data = new FlvBuilder(true, false)
                    .tag(8, 0, concat(bytes(0xAF, 0), first))
                    .tag(8, 0, bytes(0xAF, 1, 1))
                    .tag(8, 23, concat(bytes(0xAF, 0), first))
                    .tag(8, 23, bytes(0xAF, 1, 2))
                    .tag(8, 46, concat(bytes(0xAF, 0), second))
                    .tag(8, 46, bytes(0xAF, 1, 3))
                    .tag(8, 69, bytes(0xAF, 1, 4))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertArrayEquals(first, track.codecConfig().orElseThrow());
                var packets = Packets.readAll(demuxer);
                assertEquals(4, packets.size());
                assertTrue(packets.get(0).sideData().newCodecConfig().isEmpty());
                assertTrue(packets.get(1).sideData().newCodecConfig().isEmpty(), "a repeated header changes nothing");
                assertArrayEquals(second, packets.get(2).sideData().newCodecConfig().orElseThrow());
                assertTrue(packets.get(3).sideData().newCodecConfig().isEmpty());
            }
        }

        @Test
        @DisplayName("flags disposable Sorenson pictures and reads the signed 32-bit timestamps")
        void frameTypes() {
            var data = new FlvBuilder(false, true)
                    .tag(9, 0, concat(bytes(0x12), sorenson(0, 32, 16)))
                    .tag(9, 33, concat(bytes(0x22), sorenson(1, 32, 16)))
                    .tag(9, 66, concat(bytes(0x32), sorenson(2, 32, 16)))
                    .tag(9, 0xFFFFFF + 1, concat(bytes(0x22), sorenson(1, 32, 16)))
                    .tag(9, -40, concat(bytes(0x22), sorenson(1, 32, 16)))
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.FLV1, track.codec());
                assertEquals(32, track.width());
                assertEquals(16, track.height());
                var packets = Packets.readAll(demuxer);
                assertTrue(packets.get(0).keyframe());
                assertFalse(packets.get(1).keyframe());
                assertTrue(packets.get(2).flags().contains(PacketFlag.DISCARDABLE));
                assertFalse(packets.get(1).flags().contains(PacketFlag.DISCARDABLE));
                assertEquals(0x1000000L, packets.get(3).pts());
                assertEquals(-40, packets.get(4).pts());
            }
        }

        @Test
        @DisplayName("finds the next tag after a damaged tag header")
        void resync() {
            var good = new FlvBuilder(true, false)
                    .tag(8, 0, bytes(0x3E, 1, 2))
                    .bytes();
            var tail = new FlvBuilder(true, false).tag(8, 23, bytes(0x3E, 3, 4)).bytes();
            var garbage = bytes(0x55, 0x00, 0x00, 0x02, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);
            var data = concat(good, garbage, Arrays.copyOfRange(tail, 13, tail.length));
            var packets = read(data);
            assertEquals(2, packets.size());
            assertArrayEquals(bytes(3, 4), packets.get(1).data());
        }

        @Test
        @DisplayName("looks for the next tag through a region where every byte starts a candidate header, quickly")
        void resyncThroughCandidates() {
            var good = new FlvBuilder(true, false).tag(8, 0, bytes(0x3E, 1, 2)).bytes();
            var tail = new FlvBuilder(true, false).tag(8, 23, bytes(0x3E, 3, 4)).bytes();
            // every byte is an audio tag type whose size field (0x080808) points 514 KiB ahead, inside the file, so
            // every offset of the 1000 KiB is a candidate whose back-pointer must be read
            var candidates = new byte[1000 * 1024];
            Arrays.fill(candidates, (byte) 8);
            var data = concat(good, bytes(0x55, 0, 0, 2), candidates, Arrays.copyOfRange(tail, 13, tail.length),
                    new byte[600 * 1024]);
            var packets = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> read(data));
            assertEquals(2, packets.size());
            assertArrayEquals(bytes(3, 4), packets.get(1).data());
        }

        @Test
        @DisplayName("ends at a truncated last tag and refuses encrypted tags")
        void truncatedAndEncrypted() {
            var data = new FlvBuilder(true, false)
                    .tag(8, 0, bytes(0x3E, 1, 2))
                    .tag(8, 23, bytes(0x3E, 3, 4, 5, 6))
                    .bytes();
            assertEquals(1, read(Arrays.copyOf(data, data.length - 6)).size());
            var encrypted = new FlvBuilder(true, false)
                    .tag(8, 0, bytes(0x3E, 1, 2))
                    .tag(8, 23, bytes(0x3E, 3, 4), 0x20)
                    .bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(encrypted))) {
                assertTrue(demuxer.read() != null);
                assertThrows(MediaException.Unsupported.class, demuxer::read);
            }
        }

        @Test
        @DisplayName("rejects files that are not FLV")
        void notFlv() {
            assertThrows(MediaException.Malformed.class, () -> new FlvDemuxer(MediaSource.of(new byte[16])));
            var data = new FlvBuilder(true, false).bytes();
            assertTrue(FlvDemuxer.matches(data));
            data[8] = 2;
            assertFalse(FlvDemuxer.matches(data));
            assertThrows(MediaException.Malformed.class, () -> new FlvDemuxer(MediaSource.of(data)));
            assertTrue(new FlvDemuxer(MediaSource.of(new FlvBuilder(false, false).bytes())).tracks().isEmpty());
        }

        @Test
        @DisplayName("fails only with MediaException on mutated and truncated files, within a time bound")
        void mutations() throws Exception {
            for (var sample : List.of("h264-aac.flv", "flv1-mp3.flv", "opus-flac-multitrack.flv", "vp9.flv")) {
                var data = Files.readAllBytes(SAMPLES.resolve(sample));
                var variants = Mutations.variants(data, List.of(new int[]{5, 4}, new int[]{14, 3}), sample.hashCode(),
                        120, false);
                Mutations.run(variants, Duration.ofSeconds(20), source -> {
                    try (var demuxer = new FlvDemuxer(source)) {
                        demuxer.tracks();
                        Packets.readAll(demuxer);
                        if (demuxer.durationMicros() > 0) {
                            demuxer.seek(demuxer.durationMicros() / 2);
                            Packets.readAll(demuxer);
                        }
                    }
                });
            }
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {
        /** Returns, per track, the packets the seek contract requires after a seek to {@code micros}. */
        static Map<Integer, List<Packet>> expected(List<Packet> linear, List<Track> tracks, long micros) {
            var result = new HashMap<Integer, List<Packet>>();
            for (var track : tracks) {
                var packets = linear.stream().filter(packet -> packet.trackId() == track.id()).toList();
                var target = Math.floorDiv(micros, 1000);
                var start = -1;
                for (var index = 0; index < packets.size(); index++) {
                    if (packets.get(index).keyframe() && packets.get(index).pts() <= target) {
                        start = index;
                    }
                }
                if (start < 0) {
                    start = 0;
                    while (start < packets.size() && !packets.get(start).keyframe()) {
                        start++;
                    }
                }
                var first = packets.stream().mapToLong(Packet::pts).min().orElse(0);
                var last = packets.stream().mapToLong(Packet::pts).max().orElse(0);
                var interval = packets.size() > 1 ? Math.max(1, (last - first) / (packets.size() - 1)) : 1;
                result.put(track.id(), last + interval <= target ? List.of() : packets.subList(start, packets.size()));
            }
            return result;
        }

        static void assertSeeks(byte[] data, long... targets) {
            List<Packet> linear;
            List<Track> tracks;
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                tracks = demuxer.tracks();
                linear = Packets.readAll(demuxer);
            }
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                for (var micros : targets) {
                    demuxer.seek(micros);
                    var after = Packets.readAll(demuxer);
                    var expected = expected(linear, tracks, micros);
                    for (var track : tracks) {
                        var ours = after.stream().filter(packet -> packet.trackId() == track.id()).toList();
                        var theirs = expected.get(track.id());
                        assertEquals(theirs.size(), ours.size(), track.codec() + " after a seek to " + micros);
                        for (var index = 0; index < ours.size(); index++) {
                            assertEquals(theirs.get(index).pts(), ours.get(index).pts());
                            assertArrayEquals(theirs.get(index).data(), ours.get(index).data());
                        }
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("lands on the start packets of every track, with and without the onMetaData key frame index")
        void contract(boolean index) {
            var options = new ArrayList<>(List.of("-c:v", "libx264", "-preset", "ultrafast", "-g", "15", "-bf", "2",
                    "-c:a", "aac", "-b:a", "48k"));
            if (index) {
                options.addAll(List.of("-flvflags", "add_keyframe_index"));
            }
            var file = Fixtures.ffmpeg("flv", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=15:duration=4",
                    "-f", "lavfi", "-i", "sine=sample_rate=44100:duration=4"), options);
            var data = Fixtures.read(file);
            assertSeeks(data, 0, 1_500_000, 1_000_000, 3_210_000, 2_000_000, 3_900_000, 10_000_000, -5);
        }

        @Test
        @DisplayName("seeks through the onMetaData index without reading the whole file")
        void indexAvoidsScan() {
            var file = Fixtures.ffmpeg("flv", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=30"),
                    List.of("-c:v", "flv1", "-q:v", "2", "-g", "25", "-flvflags", "add_keyframe_index"));
            var data = Fixtures.read(file);
            var counting = new Counting(data);
            try (var demuxer = new FlvDemuxer(counting)) {
                var before = counting.read;
                demuxer.seek(20_000_000);
                var packet = demuxer.read();
                assertEquals(20_000, packet.pts());
                assertTrue(packet.keyframe());
                assertTrue(counting.read - before < data.length / 4, "read " + (counting.read - before) + " of "
                        + data.length + " bytes");
            }
        }

        @Test
        @DisplayName("ignores a key frame index whose times do not match the tags")
        void wrongIndex() {
            var file = Fixtures.ffmpeg("flv", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=4"),
                    List.of("-c:v", "flv1", "-g", "25", "-flvflags", "add_keyframe_index"));
            var data = Fixtures.read(file);
            // the property name "times" (its length, its bytes) and its strict array marker
            var times = indexOf(data, concat(bytes(0, 5), ascii("times"), bytes(10)));
            assertTrue(times > 0);
            // the second index entry, the key frame at 1 s, claims 0.5 s: a seek to 0.7 s must not land on it
            var value = times + 8 + 4 + 9 + 1;
            assertEquals(1.0, ByteBuffer.wrap(data, value, 8).getDouble());
            ByteBuffer.wrap(data, value, 8).putDouble(0.5);
            assertSeeks(data, 700_000, 2_500_000);
        }

        @Test
        @DisplayName("restores the configuration in force at the seek position")
        void configurationAtPosition() {
            var first = bytes(0x12, 0x10);
            var second = bytes(0x13, 0x88);
            var builder = new FlvBuilder(true, false).tag(8, 0, concat(bytes(0xAF, 0), first));
            for (var index = 0; index < 20; index++) {
                if (index == 10) {
                    builder.tag(8, index * 23, concat(bytes(0xAF, 0), second));
                }
                builder.tag(8, index * 23, bytes(0xAF, 1, index));
            }
            var data = builder.bytes();
            try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
                demuxer.seek(400_000);
                var packet = demuxer.read();
                assertEquals(391, packet.pts());
                assertArrayEquals(second, packet.sideData().newCodecConfig().orElseThrow());
                demuxer.seek(0);
                assertTrue(demuxer.read().sideData().newCodecConfig().isEmpty());
                var later = Packets.readAll(demuxer);
                assertArrayEquals(second, later.get(9).sideData().newCodecConfig().orElseThrow());
            }
        }

        static int indexOf(byte[] data, byte[] pattern) {
            outer:
            for (var index = 0; index <= data.length - pattern.length; index++) {
                for (var offset = 0; offset < pattern.length; offset++) {
                    if (data[index + offset] != pattern[offset]) {
                        continue outer;
                    }
                }
                return index;
            }
            return -1;
        }
    }

    /** A source over bytes that counts the bytes read. */
    static final class Counting implements MediaSource {
        private final byte[] data;
        long read;

        Counting(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            var count = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            read += count;
            return count;
        }

        @Override
        public void close() {
        }
    }

    @Test
    @DisplayName("remuxes into MP4 without re-encoding: valid output, the same packets, decodable by ffmpeg")
    void remuxToMp4(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var flv = Fixtures.ffmpeg("flv", VIDEO_AUDIO, List.of("-c:v", "libx264", "-preset", "ultrafast", "-bf", "2",
                "-g", "10", "-c:a", "aac", "-b:a", "64k"));
        var data = Fixtures.read(flv);
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        com.github.auties00.smalt.container.mp4.Mp4Remuxer.remux(MediaSource.of(data), sink);
        var mp4 = directory.resolve("remuxed.mp4");
        Files.write(mp4, sink.toByteArray());
        Oracle.assertDecodesCleanly(mp4);
        List<Packet> original;
        try (var demuxer = new FlvDemuxer(MediaSource.of(data))) {
            original = Packets.readAll(demuxer);
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
            assertEquals(ContainerFormat.MP4, demuxer.format());
            var remuxed = Packets.readAll(demuxer);
            assertEquals(original.size(), remuxed.size());
            var payloads = original.stream().map(packet -> crc(packet.data())).sorted().toList();
            assertEquals(payloads, remuxed.stream().map(packet -> crc(packet.data())).sorted().toList());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"avi", "mkv"})
    @DisplayName("identifies Sorenson Spark by its FLV1 FourCC in AVI and Matroska, and writes it back into AVI")
    void otherContainers(String extension) {
        var file = Fixtures.ffmpeg(extension, VIDEO, List.of("-c:v", "flv1", "-g", "8"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = demuxer.tracks().getFirst();
            assertEquals(Codec.FLV1, track.codec());
            var packets = Packets.readAll(demuxer);
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            try (var muxer = new com.github.auties00.smalt.container.avi.AviMuxer(sink)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            try (var back = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                assertEquals(Codec.FLV1, back.tracks().getFirst().codec());
                var read = Packets.readAll(back);
                assertEquals(packets.size(), read.size());
                for (var index = 0; index < read.size(); index++) {
                    assertArrayEquals(packets.get(index).data(), read.get(index).data());
                }
            }
        }
    }

    @Test
    @DisplayName("is the demuxer Containers opens for FLV, with track kinds in file order")
    void opened() throws Exception {
        var data = Files.readAllBytes(SAMPLES.resolve("flv1-mp3.flv"));
        assertEquals(ContainerFormat.FLV, Containers.detect(MediaSource.of(data)).orElseThrow());
        try (Demuxer demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            assertEquals(List.of(TrackKind.AUDIO, TrackKind.VIDEO), demuxer.tracks().stream().map(Track::kind)
                    .toList());
            assertEquals("flv", demuxer.formatName());
        }
    }
}
