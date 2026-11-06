package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Segment;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes transport streams from demuxed files of every supported codec and checks them with
 * ffmpeg (clean decode without warnings, the same decoded video, the stream parameters) and
 * with Smalt's own demuxer (the same payloads and timestamps); covers M2TS, constant bit rate,
 * table and clock reference intervals, segmentation, ID3 timed metadata and invalid input.
 */
@DisplayName("MPEG-TS muxer")
class TsMuxerTest {
    static Stream<Arguments> inputs() {
        return Stream.of(
                Arguments.of("h264 aac mp4", TsTesting.av("mp4", 2, List.of("-c:v", "libx264", "-g", "15", "-bf", "2", "-c:a", "aac"))),
                Arguments.of("h264 pts-only mkv", TsTesting.av("mkv", 2, List.of("-c:v", "libx264", "-g", "15", "-bf", "3", "-c:a", "aac"))),
                Arguments.of("hevc opus mkv", TsTesting.av("mkv", 2, List.of("-c:v", "libx265", "-x265-params", "log-level=error:keyint=15",
                        "-c:a", "libopus"))),
                Arguments.of("mp3", TsTesting.tone("mp3", 44_100, 2, 2, List.of("-c:a", "libmp3lame"))),
                Arguments.of("mp2", TsTesting.tone("mp2", 48_000, 2, 2, List.of("-c:a", "mp2"))),
                Arguments.of("ac3 mkv", TsTesting.tone("mkv", 48_000, 6, 2, List.of("-c:a", "ac3"))),
                Arguments.of("e-ac-3 mkv", TsTesting.tone("mkv", 48_000, 2, 2, List.of("-c:a", "eac3"))),
                Arguments.of("opus 5.1 ogg", TsTesting.tone("ogg", 48_000, 6, 2, List.of("-c:a", "libopus"))),
                Arguments.of("aac 5.1 m4a", TsTesting.tone("m4a", 44_100, 6, 2, List.of("-c:a", "aac"))));
    }

    /** Remuxes a file into a transport stream with the given muxer. */
    static byte[] remux(Path input, Function<MediaSink, TsMuxer> factory) {
        try (var source = MediaSource.of(input); var demuxer = Containers.openDemuxer(source)) {
            var sink = MediaSink.memory();
            try (var muxer = factory.apply(sink)) {
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }
    }

    /** Returns the NAL units of a length-prefixed access unit, without delimiters and parameter sets. */
    static List<String> slices(byte[] unit, int lengthSize, boolean hevc) {
        var result = new ArrayList<String>();
        for (var offset = 0; offset + lengthSize <= unit.length; ) {
            var length = 0;
            for (var index = 0; index < lengthSize; index++) {
                length = (length << 8) | (unit[offset + index] & 0xFF);
            }
            var start = offset + lengthSize;
            var type = hevc ? (unit[start] >> 1) & 0x3F : unit[start] & 0x1F;
            // parameter sets and SEI may be repeated before key frames; the slices must be the same
            var skip = hevc ? type >= 32 && type <= 40 : type >= 6 && type <= 9;
            if (!skip) {
                result.add(ContainerProbe.crc32(Arrays.copyOfRange(unit, start, start + length)));
            }
            offset = start + length;
        }
        return result;
    }

    /** Returns a transport stream with an AAC tone and an ID3 timed metadata track. */
    static byte[] id3Stream() {
        var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                .codecConfig(new byte[]{0x11, (byte) 0x90}).sampleRate(48_000)
                .layout(com.github.auties00.smalt.audio.ChannelLayout.STEREO).build();
        var data = DataTrack.builder().id(2).timeBase(Rational.timeBaseOf(90_000)).handler("id3").build();
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink)) {
            muxer.addTrack(audio);
            muxer.addTrack(data);
            for (var frame = 0; frame < 100; frame++) {
                muxer.writePacket(new Packet(1, silentAacFrame(), frame * 1024L, frame * 1024L, 1024, true));
                if (frame % 25 == 0) {
                    var time = frame * 1024L * 90_000 / 48_000;
                    muxer.writePacket(new Packet(2, id3Tag("frame " + frame), time, time, 0, true));
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns an AAC-LC stereo raw data block of digital silence. */
    static byte[] silentAacFrame() {
        return new byte[]{0x21, 0x00, 0x49, (byte) 0x90, 0x02, 0x19, 0x00, 0x23, (byte) 0x80};
    }

    /** Returns an ID3v2.4 tag holding one title frame. */
    static byte[] id3Tag(String title) {
        var text = title.getBytes(StandardCharsets.ISO_8859_1);
        var frameSize = 1 + text.length;
        var tag = new ByteArrayOutputStream();
        tag.writeBytes(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0, 0, (byte) (10 + frameSize)});
        tag.writeBytes(new byte[]{'T', 'I', 'T', '2', 0, 0, 0, (byte) frameSize, 0, 0, 0});
        tag.writeBytes(text);
        return tag.toByteArray();
    }

    @Nested
    @DisplayName("against ffmpeg")
    class AgainstFfmpeg {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsMuxerTest#inputs")
        @DisplayName("writes streams ffmpeg decodes without a warning, to the same pictures")
        void decodes(String name, Path input) {
            var output = TsTesting.save(name.replace(' ', '_') + ".ts", remux(input, TsMuxer::new));
            var warnings = TsTesting.decodeWarnings(TsTesting.ffmpegPath(output));
            assertTrue(warnings.isBlank(), "ffmpeg warnings:\n" + warnings);
            var probe = ContainerProbe.probe(output);
            var source = ContainerProbe.demux(input, Containers::openDemuxer);
            assertEquals(source.tracks().size(), probe.streams().size(), "stream count");
            for (var index = 0; index < source.tracks().size(); index++) {
                var track = source.tracks().get(index);
                var stream = probe.streams().get(index);
                assertEquals(track.codec() == Codec.MP2 ? "mp2" : track.codec().displayName(), stream.codecName(), "codec");
                assertEquals(source.packetsOf(track).size(), probe.packetsOf(index).size(), track.codec() + " packet count");
            }
            if (source.tracks().stream().anyMatch(track -> track.kind() == TrackKind.VIDEO)) {
                assertEquals(TsTesting.decodedMd5(input, "v"), TsTesting.decodedMd5(output, "v"), "decoded video");
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsMuxerTest#inputs")
        @DisplayName("round-trips the payloads and timestamps through the demuxer")
        void roundTrip(String name, Path input) {
            var source = ContainerProbe.demux(input, Containers::openDemuxer);
            var output = TsTesting.demux(remux(input, TsMuxer::new));
            assertEquals(source.tracks().size(), output.tracks().size());
            for (var index = 0; index < source.tracks().size(); index++) {
                var before = source.tracks().get(index);
                var after = output.tracks().get(index);
                assertEquals(before.codec(), after.codec());
                var expected = source.packetsOf(before);
                var actual = output.packetsOf(after);
                assertEquals(expected.size(), actual.size(), before.codec() + " packet count");
                var offset = TsTesting.micros(after, actual.getFirst()) - TsTesting.micros(before, expected.getFirst());
                // Matroska stores milliseconds; the transport stream keeps the audio sample counter
                var tolerance = name.contains("mkv") && before.kind() == TrackKind.AUDIO ? 1_000 : 25;
                for (var position = 0; position < expected.size(); position++) {
                    var want = expected.get(position);
                    var got = actual.get(position);
                    if (before.kind() == TrackKind.VIDEO) {
                        var hevc = before.codec() == Codec.HEVC;
                        var lengthSize = hevc ? (before.codecConfig().orElseThrow()[21] & 3) + 1 : (before.codecConfig().orElseThrow()[4] & 3) + 1;
                        assertEquals(slices(want.data(), lengthSize, hevc), slices(got.data(), 4, hevc), "slices of access unit " + position);
                        assertEquals(want.keyframe(), got.keyframe(), "key frame " + position);
                    } else {
                        assertArrayEquals(want.data(), got.data(), before.codec() + " payload " + position);
                    }
                    var difference = TsTesting.micros(after, got) - offset - TsTesting.micros(before, want);
                    assertTrue(Math.abs(difference) <= tolerance, before.codec() + " packet " + position + " moved by " + difference + " us");
                }
            }
        }

        @Test
        @DisplayName("gives presentation-only H.264 monotonic decoding times at or below the presentation times")
        void decodingTimes() {
            var input = inputs().filter(arguments -> arguments.get()[0].equals("h264 pts-only mkv")).findFirst().orElseThrow();
            var output = TsTesting.demux(remux((Path) input.get()[1], TsMuxer::new));
            var video = output.tracks().getFirst();
            var previous = Long.MIN_VALUE;
            for (var packet : output.packetsOf(video)) {
                assertTrue(packet.dts() > previous, "monotonic decoding times");
                assertTrue(packet.dts() <= packet.pts(), "decoding before presentation");
                previous = packet.dts();
            }
        }

        @Test
        @DisplayName("writes ID3 timed metadata ffprobe recognizes and the demuxer reads back")
        void id3() {
            var bytes = id3Stream();
            var file = TsTesting.save("id3.ts", bytes);
            var probe = ContainerProbe.probe(file);
            assertEquals("timed_id3", probe.streams().get(1).codecName());
            var demuxed = TsTesting.demux(bytes);
            var data = assertInstanceOf(DataTrack.class, demuxed.tracks().get(1));
            assertEquals("id3", data.handler().orElseThrow());
            var packets = demuxed.packetsOf(data);
            assertEquals(4, packets.size());
            assertArrayEquals(id3Tag("frame 25"), packets.get(1).data());
            assertEquals(Math.round(25 * 1024 / 48_000.0 * 1_000_000), TsTesting.micros(data, packets.get(1)), 20);
        }
    }

    @Nested
    @DisplayName("layout and timing")
    class LayoutAndTiming {
        private Path input() {
            return TsTesting.av("mp4", 3, List.of("-c:v", "libx264", "-g", "30", "-c:a", "aac"));
        }

        @Test
        @DisplayName("repeats the tables every 100 ms and the clock reference at most every 40 ms")
        void intervals() {
            var bytes = remux(input(), TsMuxer::new);
            var packets = TsTesting.packets(bytes, 188, 0);
            var pat = 0;
            var lastPcr = -1L;
            var maxGap = 0L;
            var pcrs = 0;
            for (var packet : packets) {
                assertEquals(0x47, packet[0] & 0xFF);
                if (TsTesting.pid(packet) == 0) {
                    pat++;
                }
                var pcr = TsTesting.pcr(packet);
                if (pcr >= 0) {
                    if (lastPcr >= 0) {
                        assertTrue(pcr > lastPcr, "increasing clock references");
                        maxGap = Math.max(maxGap, pcr - lastPcr);
                    }
                    lastPcr = pcr;
                    pcrs++;
                }
            }
            assertTrue(pat >= 25 && pat <= 40, "program association tables: " + pat);
            assertTrue(pcrs >= 75, "clock references: " + pcrs);
            assertTrue(maxGap <= 40 * 27_000, "largest clock reference gap " + maxGap / 27_000 + " ms");
        }

        @Test
        @DisplayName("maps the timestamp origin, not the first decoding time, to the delay when one is set")
        void timestampOrigin() {
            for (var origin : new Long[]{null, 0L, 500_000L}) {
                var origins = new ArrayList<Long>();
                for (var startSeconds = 1; startSeconds <= 2; startSeconds++) {
                    var builder = TsMuxerOptions.builder();
                    if (origin != null) {
                        builder.timestampOriginMicros(origin);
                    }
                    var bytes = aacStream(startSeconds * 48_000L, builder.build());
                    try (var source = MediaSource.of(bytes);
                         var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                        origins.add(demuxer.originTicks());
                        var first = demuxer.read();
                        assertEquals(0, first.pts(), "the demuxer reports its origin as zero");
                    }
                }
                if (origin == null) {
                    // each stream starts at the delay, 700 ms
                    assertEquals(List.of(63_000L, 63_000L), origins);
                } else {
                    // streams given one origin share their timestamps: one second of input apart
                    var zero = 63_000L - origin * 9 / 100;
                    assertEquals(List.of(zero + 90_000, zero + 180_000), origins);
                }
            }
        }

        /** Returns a transport stream of 50 AAC frames whose first starts at a time, in 1/48000 s. */
        private static byte[] aacStream(long start, TsMuxerOptions options) {
            var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                    .codecConfig(new byte[]{0x11, (byte) 0x90}).sampleRate(48_000)
                    .layout(com.github.auties00.smalt.audio.ChannelLayout.STEREO).build();
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink, options)) {
                muxer.addTrack(audio);
                for (var frame = 0; frame < 50; frame++) {
                    var time = start + frame * 1024L;
                    muxer.writePacket(new Packet(1, silentAacFrame(), time, time, 1024, true));
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }

        @Test
        @DisplayName("writes M2TS packets with increasing arrival time stamps")
        void m2ts() {
            var bytes = remux(input(), sink -> new TsMuxer(sink, TsMuxerOptions.m2ts()));
            assertEquals(0, bytes.length % 192);
            var previous = -1L;
            for (var offset = 0; offset < bytes.length; offset += 192) {
                assertEquals(0x47, bytes[offset + 4] & 0xFF);
                var stamp = ((bytes[offset] & 0x3FL) << 24) | ((bytes[offset + 1] & 0xFFL) << 16) | ((bytes[offset + 2] & 0xFFL) << 8)
                        | (bytes[offset + 3] & 0xFFL);
                assertTrue(stamp > previous, "arrival time stamps increase");
                previous = stamp;
            }
            var file = TsTesting.save("m2ts.m2ts", bytes);
            TsTesting.decodeWarnings(TsTesting.ffmpegPath(file));
            assertEquals("192", ContainerProbe.probe(file).streams().getFirst().get("ts_packetsize"));
            try (var source = MediaSource.of(bytes); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                assertEquals(192, demuxer.packetSize());
            }
        }

        @Test
        @DisplayName("pads to a constant bit rate with null packets and position-based clock references")
        void constantRate() {
            var rate = 1_500_000L;
            var bytes = remux(input(), sink -> new TsMuxer(sink, TsMuxerOptions.builder().muxRate(rate).build()));
            var packets = TsTesting.packets(bytes, 188, 0);
            var nulls = packets.stream().filter(packet -> TsTesting.pid(packet) == 0x1FFF).count();
            assertTrue(nulls > 0, "null packets");
            var expected = rate * 3 / 8;
            assertTrue(Math.abs(bytes.length - expected) < expected / 20, "size " + bytes.length + " vs " + expected);
            var firstIndex = -1;
            var firstPcr = -1L;
            for (var index = 0; index < packets.size(); index++) {
                var pcr = TsTesting.pcr(packets.get(index));
                if (pcr < 0) {
                    continue;
                }
                if (firstIndex < 0) {
                    firstIndex = index;
                    firstPcr = pcr;
                    continue;
                }
                var position = (long) (index - firstIndex) * 188 * 8 * 27_000_000 / rate;
                assertTrue(Math.abs(pcr - firstPcr - position) <= 1, "clock reference follows the position");
            }
            var file = TsTesting.save("cbr.ts", bytes);
            var warnings = TsTesting.decodeWarnings(TsTesting.ffmpegPath(file));
            assertTrue(warnings.isBlank(), warnings);
        }

        @Test
        @DisplayName("writes the same bytes to a stream as to memory")
        void streaming() {
            var memory = remux(input(), TsMuxer::new);
            try (var source = MediaSource.of(input()); var demuxer = Containers.openDemuxer(source)) {
                var stream = new ByteArrayOutputStream();
                try (var sink = MediaSink.of(stream); var muxer = new TsMuxer(sink)) {
                    demuxer.tracks().forEach(muxer::addTrack);
                    for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                        muxer.writePacket(packet);
                    }
                    muxer.finish();
                }
                assertArrayEquals(memory, stream.toByteArray());
            }
        }

        @Test
        @DisplayName("cuts key-frame-aligned segments that each open with the program tables")
        void segments() {
            var input = TsTesting.av("mp4", 7, List.of("-c:v", "libx264", "-g", "30", "-keyint_min", "30", "-sc_threshold", "0",
                    "-c:a", "aac"));
            var segments = new ArrayList<Segment>();
            var bodies = new ArrayList<byte[]>();
            try (var source = MediaSource.of(input); var demuxer = Containers.openDemuxer(source)) {
                var muxer = new TsMuxer(segment -> {
                    segments.add(segment);
                    var sink = MediaSink.memory();
                    return new com.github.auties00.smalt.io.MediaSink() {
                        @Override
                        public long position() {
                            return sink.position();
                        }

                        @Override
                        public void position(long position) {
                            sink.position(position);
                        }

                        @Override
                        public boolean isSeekable() {
                            return false;
                        }

                        @Override
                        public void write(java.nio.ByteBuffer data) {
                            sink.write(data);
                        }

                        @Override
                        public void close() {
                            bodies.add(sink.toByteArray());
                        }
                    };
                }, TsMuxerOptions.builder().segmentMicros(2_000_000).build());
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            assertEquals(4, segments.size());
            var total = 0L;
            for (var index = 0; index < segments.size(); index++) {
                var segment = segments.get(index);
                var body = bodies.get(index);
                assertEquals(index + 1, segment.sequenceNumber());
                assertEquals(body.length, segment.size());
                assertTrue(segment.independent());
                assertEquals(0, TsTesting.pid(Arrays.copyOf(body, 188)), "segment opens with the program association table");
                assertEquals(0x1000, TsTesting.pid(Arrays.copyOfRange(body, 188, 376)), "then the program map table");
                var demuxed = TsTesting.demux(body);
                var video = demuxed.packetsOf(demuxed.tracks().getFirst());
                assertTrue(video.getFirst().keyframe(), "segment " + index + " starts with a key frame");
                total += segment.durationMicros();
            }
            assertEquals(2_000_000, segments.getFirst().durationMicros());
            assertTrue(Math.abs(total - 7_000_000) <= 40_000, "total " + total);
        }
    }

    @Nested
    @DisplayName("invalid use")
    class InvalidUse {
        private final VideoTrack h264 = VideoTrack.builder().id(1).codec(Codec.H264)
                .timeBase(Rational.timeBaseOf(90_000))
                .codecConfig(new byte[]{1, 0x42, (byte) 0xC0, 0x1E, (byte) 0xFF, (byte) 0xE1, 0, 4, 0x67, 0x42, (byte) 0xC0, 0x1E,
                        1, 0, 2, 0x68, (byte) 0xCE}).width(16).height(16).build();

        @Test
        @DisplayName("rejects codecs, configurations and calls it cannot honor")
        void rejects() {
            var muxer = new TsMuxer(MediaSink.memory());
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(VideoTrack.builder().id(5).codec(Codec.VP9)
                    .timeBase(Rational.timeBaseOf(90_000)).build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(VideoTrack.builder().id(6).codec(Codec.H264)
                    .timeBase(Rational.timeBaseOf(90_000)).build()));
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(7).codec(Codec.AAC)
                    .timeBase(Rational.timeBaseOf(48_000)).codecConfig(new byte[]{0x17, (byte) 0x80, 0, 0, 0})
                            .build()));
            muxer.addTrack(h264);
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(h264));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(9, new byte[4], 0, 0, 0, true)));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, new byte[]{0, 0, 0, 9, 1},
                    Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true)));
            muxer.writePacket(new Packet(1, new byte[]{0, 0, 0, 2, 0x65, (byte) 0x88}, 0, 0, 3000, true));
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(AudioTrack.builder().id(8).codec(Codec.MP3)
                    .timeBase(Rational.timeBaseOf(48_000)).build()));
            muxer.finish();
            assertThrows(IllegalStateException.class, muxer::finish);
            assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, new byte[4], 0, 0, 0, true)));
        }

        @Test
        @DisplayName("validates its options")
        void options() {
            var options = TsMuxerOptions.builder().muxRate(2_000_000).programNumber(5).pmtPid(0x200).firstPid(0x300)
                    .segmentMicros(4_000_000).build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(TsMuxerOptions.PacketFormat.M2TS, TsMuxerOptions.m2ts().packetFormat());
            assertThrows(IllegalArgumentException.class, () -> TsMuxerOptions.builder().muxRate(100).build());
            assertThrows(IllegalArgumentException.class, () -> TsMuxerOptions.builder().pcrIntervalMicros(200_000)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> TsMuxerOptions.builder().programNumber(0).pmtPid(0x100)
                    .firstPid(0x200).build());
            assertThrows(IllegalArgumentException.class, () -> TsMuxerOptions.builder().segmentMicros(0).build());
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {
        @Test
        @DisplayName("is the muxer the container registry creates for transport streams")
        void registry() {
            assertTrue(Containers.isMuxable(ContainerFormat.MPEG_TS, Codec.H264));
            assertFalse(Containers.isMuxable(ContainerFormat.MPEG_TS, Codec.VP9));
            assertInstanceOf(TsMuxer.class, Containers.openMuxer(ContainerFormat.MPEG_TS, MediaSink.memory()));
        }

        @Test
        @DisplayName("is the transcode output for .ts files, copying H.264 and AAC")
        void transcode() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            assertEquals(OutputContainer.MPEG_TS, OutputContainer.forFileName("clip.ts").orElseThrow());
            var input = TsTesting.av("mp4", 2, List.of("-c:v", "libx264", "-c:a", "aac"));
            var sink = MediaSink.memory();
            try (var source = MediaSource.of(input)) {
                var result = Transcoder.transcode(source, sink, TranscodeOptions.of(OutputContainer.MPEG_TS));
                assertEquals(OutputContainer.MPEG_TS, result.container());
            }
            var file = TsTesting.save("transcoded.ts", sink.toByteArray());
            assertTrue(TsTesting.decodeWarnings(TsTesting.ffmpegPath(file)).isBlank());
            var demuxed = TsTesting.demux(sink.toByteArray());
            assertEquals(List.of(Codec.H264, Codec.AAC), demuxed.tracks().stream().map(Track::codec).toList());
            assertTrue(Math.abs(demuxed.durationMicros() - 2_000_000) <= 50_000);
        }
    }
}
