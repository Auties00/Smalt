package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads ffmpeg-generated transport streams of every supported coding and compares tracks,
 * packets, timestamps and decoded output with ffmpeg's own reading of the same files; covers
 * packet layouts, programs, timeline wraps and splices, damaged input and seeking.
 */
@DisplayName("MPEG-TS demuxer")
class TsDemuxerTest {
    static Stream<Arguments> codings() {
        return Stream.of(
                Arguments.of("h264 aac", TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-g", "15", "-bf", "2", "-c:a", "aac", "-ac", "2"))),
                Arguments.of("hevc ac3", TsTesting.av("ts", 2, List.of("-c:v", "libx265", "-x265-params", "log-level=error:keyint=15",
                        "-c:a", "ac3", "-ar", "44100"))),
                Arguments.of("h264 mp3", TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-bf", "0", "-c:a", "libmp3lame"))),
                Arguments.of("mp2", TsTesting.tone("ts", 48_000, 2, 2, List.of("-c:a", "mp2"))),
                Arguments.of("e-ac-3", TsTesting.tone("ts", 48_000, 2, 2, List.of("-c:a", "eac3"))),
                Arguments.of("opus", TsTesting.tone("ts", 48_000, 2, 2, List.of("-c:a", "libopus"))),
                Arguments.of("opus 5.1", TsTesting.tone("ts", 48_000, 6, 2, List.of("-c:a", "libopus"))),
                Arguments.of("aac 5.1", TsTesting.tone("ts", 48_000, 6, 2, List.of("-c:a", "aac"))),
                Arguments.of("aac latm", TsTesting.tone("ts", 44_100, 2, 2, List.of("-c:a", "aac", "-mpegts_flags", "latm"))),
                Arguments.of("m2ts", TsTesting.av("m2ts", 2, List.of("-c:v", "libx264", "-g", "15", "-c:a", "aac", "-mpegts_m2ts_mode", "1"))),
                // broadcast-like: DVB signaling (AC-3 as private data with its descriptor), a
                // constant 2 Mb/s multiplex padded with null packets, and a language descriptor
                Arguments.of("dvb ac3 cbr", TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-g", "12", "-c:a", "ac3",
                        "-mpegts_flags", "system_b", "-muxrate", "2000000", "-metadata:s:a:0", "language=deu"))));
    }

    @Nested
    @DisplayName("against ffprobe")
    class AgainstFfprobe {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsDemuxerTest#codings")
        @DisplayName("exposes the streams ffprobe sees with their parameters")
        void streams(String name, Path file) {
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, Containers::openDemuxer);
            assertEquals(probe.streams().size(), ours.tracks().size(), "track count");
            for (var index = 0; index < ours.tracks().size(); index++) {
                var track = ours.tracks().get(index);
                var stream = probe.streams().get(index);
                var expected = switch (stream.codecName()) {
                    case "aac_latm" -> "aac";
                    default -> stream.codecName();
                };
                assertEquals(expected, track.codec().displayName(), "codec of track " + index);
                assertEquals(Integer.decode(stream.get("id")), track.id(), "PID of track " + index);
                var language = stream.get("tag:language");
                if (language != null) {
                    assertEquals(language, track.language().orElseThrow(), "language of track " + index);
                }
                switch (track) {
                    case VideoTrack video -> {
                        assertEquals(Integer.parseInt(stream.get("width")), video.width());
                        assertEquals(Integer.parseInt(stream.get("height")), video.height());
                        assertTrue(video.codecConfig().isPresent(), "configuration record");
                    }
                    case AudioTrack audio -> {
                        assertEquals(Integer.parseInt(stream.get("sample_rate")), audio.sampleRate(), "sample rate");
                        assertEquals(Integer.parseInt(stream.get("channels")), audio.layout().orElseThrow().channels(), "channels");
                    }
                    default -> {
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsDemuxerTest#codings")
        @DisplayName("returns ffprobe's packets with its timestamps, key frames and payloads")
        void packets(String name, Path file) {
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, Containers::openDemuxer);
            var start = Long.MAX_VALUE;
            for (var packet : probe.packets()) {
                if (packet.pts() != Packet.NO_TIMESTAMP) {
                    start = Math.min(start, packet.pts());
                }
            }
            var end = Long.MIN_VALUE;
            for (var index = 0; index < ours.tracks().size(); index++) {
                var track = ours.tracks().get(index);
                var expected = probe.packetsOf(index);
                var actual = ours.packetsOf(track);
                var label = name + " track " + index;
                // audio frames follow a sample counter; ffmpeg adds a rounded 90 kHz frame duration
                // per frame after each PES time, which drifts a few ticks within a PES packet
                var tolerance = track.kind() == TrackKind.AUDIO ? 12 : 0;
                assertEquals(expected.size(), actual.size(), label + " packet count");
                for (var position = 0; position < expected.size(); position++) {
                    var want = expected.get(position);
                    var got = actual.get(position);
                    var where = label + " packet " + position;
                    var pts = track.timeBase().rescale(got.pts(), Rational.timeBaseOf(90_000));
                    assertTrue(Math.abs(want.pts() - start - pts) <= tolerance, where + " pts " + (want.pts() - start) + " vs " + pts);
                    if (want.duration() > 0) {
                        end = Math.max(end, want.pts() - start + want.duration());
                    }
                    if (track.kind() == TrackKind.VIDEO) {
                        assertEquals(want.dts() - start, got.dts(), where + " dts");
                        assertEquals(want.key(), got.keyframe(), where + " key frame");
                    }
                    switch (track.codec()) {
                        case MP3, MP2, AC3, EAC3, OPUS -> assertEquals(want.hash(), ContainerProbe.crc32(got.data()), where + " payload");
                        case AAC -> {
                            if (!name.contains("latm")) {
                                assertEquals(want.size() - 7, got.size(), where + " payload size");
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
            // ffprobe's format duration stops at the last PES time; the packets show the real end
            var duration = end * 1_000_000 / 90_000;
            // an Opus end trim shortens our last packet; ffprobe reports the coded duration
            var slack = name.startsWith("opus") ? 20_000 : 1_000;
            assertTrue(Math.abs(ours.durationMicros() - duration) <= slack,
                    name + " duration " + ours.durationMicros() + " vs the end of ffprobe's packets " + duration);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.ts.TsDemuxerTest#codings")
        @DisplayName("hands the decoders the same media: a remux to Matroska decodes like the original")
        void decodesAlike(String name, Path file) {
            var ours = ContainerProbe.demux(file, Containers::openDemuxer);
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska())) {
                ours.tracks().forEach(muxer::addTrack);
                ours.order().forEach(muxer::writePacket);
                muxer.finish();
            }
            var remuxed = TsTesting.save(name.replace(' ', '_') + "-remux.mkv", sink.toByteArray());
            TsTesting.decodeWarnings(TsTesting.ffmpegPath(remuxed));
            if (ours.tracks().stream().anyMatch(track -> track.kind() == TrackKind.VIDEO)) {
                assertEquals(TsTesting.decodedMd5(file, "v"), TsTesting.decodedMd5(remuxed, "v"), "decoded video");
            }
            if (!name.startsWith("opus")) {
                assertEquals(TsTesting.decodedMd5(file, "a"), TsTesting.decodedMd5(remuxed, "a"), "decoded audio");
            }
        }

        @Test
        @DisplayName("is described by the probe API like ffprobe describes it")
        void probe() {
            var file = TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-profile:v", "main", "-c:a", "aac"));
            try (var source = MediaSource.of(file)) {
                var info = Media.probe(source);
                assertEquals(ContainerFormat.MPEG_TS, info.container().orElseThrow());
                assertEquals(2, info.streams().size());
                var video = info.video().orElseThrow();
                assertEquals(Codec.H264, video.codec());
                assertEquals("Main", video.profile().orElseThrow());
                assertEquals(160, video.width());
                var duration = info.durationMicros().orElseThrow();
                assertTrue(Math.abs(duration - 2_000_000) <= 50_000, "duration " + duration);
                assertEquals(48_000, info.audio().orElseThrow().sampleRate());
            }
        }
    }

    @Nested
    @DisplayName("packet layouts")
    class Layouts {
        @Test
        @DisplayName("recognizes 188, 192 and 204-byte packets and leading garbage")
        void layouts() {
            var plain = Fixtures.read(TsTesting.av("ts", 1, List.of("-c:v", "libx264", "-c:a", "aac")));
            var reference = TsTesting.demux(plain);
            var m2ts = new ByteArrayOutputStream();
            var rs = new ByteArrayOutputStream();
            for (var offset = 0; offset + 188 <= plain.length; offset += 188) {
                m2ts.write(new byte[]{0x00, 0x01, 0x02, 0x03}, 0, 4);
                m2ts.write(plain, offset, 188);
                rs.write(plain, offset, 188);
                rs.write(new byte[16], 0, 16);
            }
            var garbage = new byte[3000 + plain.length];
            new Random(7).nextBytes(garbage);
            for (var index = 0; index < 3000; index++) {
                if (garbage[index] == 0x47) {
                    garbage[index] = 0x46;
                }
            }
            System.arraycopy(plain, 0, garbage, 3000, plain.length);
            var variants = List.of(Arguments.of(192, m2ts.toByteArray()), Arguments.of(204, rs.toByteArray()),
                    Arguments.of(188, garbage));
            for (var variant : variants) {
                var size = (int) variant.get()[0];
                var bytes = (byte[]) variant.get()[1];
                try (var source = MediaSource.of(bytes); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                    assertEquals(size, demuxer.packetSize());
                    var actual = ContainerProbe.demuxAll(demuxer);
                    assertEquals(reference.order().size(), actual.order().size(), size + "-byte packets: packet count");
                    for (var index = 0; index < actual.order().size(); index++) {
                        assertArrayEquals(reference.order().get(index).data(), actual.order().get(index).data());
                        assertEquals(reference.order().get(index).pts(), actual.order().get(index).pts());
                    }
                }
            }
            assertEquals(ContainerFormat.MPEG_TS, Containers.detect(MediaSource.of(m2ts.toByteArray()))
                    .orElseThrow());
            assertEquals(ContainerFormat.MPEG_TS, Containers.detect(MediaSource.of(rs.toByteArray())).orElseThrow());
            assertEquals(ContainerFormat.MPEG_TS, Containers.detect(MediaSource.of(plain)).orElseThrow());
            assertEquals(ContainerFormat.MPEG_TS, Containers.detect(MediaSource.of(garbage)).orElseThrow());
        }

        @Test
        @DisplayName("reports M2TS files as 192-byte layouts")
        void m2tsSize() {
            var file = TsTesting.av("m2ts", 1, List.of("-c:v", "libx264", "-c:a", "aac", "-mpegts_m2ts_mode", "1"));
            try (var source = MediaSource.of(file); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                assertEquals(192, demuxer.packetSize());
            }
        }

        @Test
        @DisplayName("does not take random bytes or short inputs for transport streams")
        void rejects() {
            var random = new byte[8192];
            new Random(1).nextBytes(random);
            assertFalse(TsDemuxer.matches(random));
            assertFalse(TsDemuxer.matches(new byte[]{0x47}));
            assertFalse(TsDemuxer.matches(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> new TsDemuxer(MediaSource.of(random), TsDemuxerOptions.defaults()));
            assertThrows(MediaException.Malformed.class, () -> new TsDemuxer(MediaSource.of(new byte[0]), TsDemuxerOptions.defaults()));
        }
    }

    @Nested
    @DisplayName("programs")
    class Programs {
        private Path twoPrograms() {
            return Fixtures.ffmpeg("ts", List.of(
                    "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=25:duration=1",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                    "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=1"),
                    List.of("-map", "0:v", "-map", "1:a", "-map", "2:a", "-c:v", "libx264", "-c:a", "aac",
                            "-program", "title=first:st=0:st=1", "-program", "title=second:st=2"));
        }

        @Test
        @DisplayName("exposes the first program and lists them all")
        void first() {
            try (var source = MediaSource.of(twoPrograms()); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                assertEquals(2, demuxer.programs().size());
                assertEquals(demuxer.programs().getFirst().number(), demuxer.program().number());
                assertEquals(2, demuxer.tracks().size());
                assertEquals(Codec.H264, demuxer.tracks().getFirst().codec());
            }
        }

        @Test
        @DisplayName("exposes the program the options select")
        void selected() {
            try (var source = MediaSource.of(twoPrograms())) {
                int second;
                try (var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                    second = demuxer.programs().get(1).number();
                }
                try (var demuxer = new TsDemuxer(source, TsDemuxerOptions.builder().program(second).build())) {
                    assertEquals(second, demuxer.program().number());
                    assertEquals(1, demuxer.tracks().size());
                    var audio = assertInstanceOf(AudioTrack.class, demuxer.tracks().getFirst());
                    assertEquals(44_100, audio.sampleRate());
                    assertTrue(ContainerProbe.demuxAll(demuxer).order().size() > 30);
                }
                assertThrows(MediaException.Unsupported.class,
                        () -> new TsDemuxer(source, TsDemuxerOptions.builder().program(999).build()));
            }
        }
    }

    @Nested
    @DisplayName("timeline")
    class Timeline {
        @Test
        @DisplayName("starts the presentation at zero")
        void origin() {
            var demuxed = TsTesting.demux(Fixtures.read(TsTesting.av("ts", 1, List.of("-c:v", "libx264", "-c:a", "aac"))));
            var earliest = Long.MAX_VALUE;
            for (var track : demuxed.tracks()) {
                for (var packet : demuxed.packetsOf(track)) {
                    earliest = Math.min(earliest, TsTesting.micros(track, packet));
                }
            }
            assertEquals(0, earliest);
        }

        @Test
        @DisplayName("continues across the 33-bit timestamp wrap")
        void wrap() {
            var file = TsTesting.av("ts", 3, List.of("-c:v", "libx264", "-c:a", "aac", "-output_ts_offset", "95442.5"));
            var demuxed = ContainerProbe.demux(file, Containers::openDemuxer);
            for (var track : demuxed.tracks()) {
                var previous = Long.MIN_VALUE;
                for (var packet : demuxed.packetsOf(track)) {
                    assertTrue(packet.dts() > previous, "monotonic across the wrap");
                    previous = packet.dts();
                }
                var last = demuxed.packetsOf(track).getLast();
                var micros = TsTesting.micros(track, last);
                assertTrue(micros > 2_800_000 && micros < 3_100_000, "end of " + track.codec() + " at " + micros);
            }
            assertTrue(Math.abs(demuxed.durationMicros() - 3_000_000) < 60_000, "duration " + demuxed.durationMicros());
        }

        @Test
        @DisplayName("continues across a splice that restarts the clock")
        void splice() {
            var first = Fixtures.read(TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-g", "15", "-c:a", "aac")));
            var second = Fixtures.read(TsTesting.av("ts", 1, List.of("-c:v", "libx264", "-g", "15", "-c:a", "aac", "-b:a", "64k")));
            var joined = new byte[first.length + second.length];
            System.arraycopy(first, 0, joined, 0, first.length);
            System.arraycopy(second, 0, joined, first.length, second.length);
            var demuxed = TsTesting.demux(joined);
            var separate = TsTesting.demux(first);
            var tail = TsTesting.demux(second);
            for (var track : demuxed.tracks()) {
                var packets = demuxed.packetsOf(track);
                var expected = separate.packets().get(track.id()).size() + tail.packets().get(track.id()).size();
                assertTrue(Math.abs(packets.size() - expected) <= 2, track.codec() + " packets " + packets.size() + " vs " + expected);
                var previous = Long.MIN_VALUE;
                for (var packet : packets) {
                    assertTrue(packet.dts() > previous, track.codec() + " decoding times go back across the splice");
                    previous = packet.dts();
                }
                var end = TsTesting.micros(track, packets.getLast());
                assertTrue(end > 2_800_000 && end < 3_300_000, track.codec() + " ends at " + end);
            }
        }
    }

    @Nested
    @DisplayName("damaged input")
    class Damaged {
        private byte[] input() {
            return Fixtures.read(TsTesting.av("ts", 2, List.of("-c:v", "libx264", "-g", "15", "-c:a", "aac")));
        }

        @Test
        @DisplayName("flags the access unit a lost transport packet damages and keeps reading")
        void lostPackets() {
            var data = input();
            var reference = TsTesting.demux(data);
            var videoPid = reference.tracks().getFirst().id();
            var output = new ByteArrayOutputStream();
            var dropped = 0;
            for (var offset = 0; offset + 188 <= data.length; offset += 188) {
                var pid = (data[offset + 1] & 0x1F) << 8 | (data[offset + 2] & 0xFF);
                if (pid == videoPid && (data[offset + 1] & 0x40) == 0 && offset > data.length / 2 && dropped < 3) {
                    dropped++;
                    continue;
                }
                output.write(data, offset, 188);
            }
            var damaged = TsTesting.demux(output.toByteArray());
            var video = damaged.packetsOf(damaged.tracks().getFirst());
            assertTrue(video.stream().anyMatch(packet -> packet.flags().contains(PacketFlag.CORRUPT)), "a packet is flagged");
            assertTrue(video.size() >= reference.packetsOf(reference.tracks().getFirst()).size() - 1);
            assertEquals(reference.packetsOf(reference.tracks().get(1)).size(), damaged.packetsOf(damaged.tracks().get(1)).size());
        }

        @Test
        @DisplayName("resynchronizes after garbage in the middle")
        void garbage() {
            var data = input();
            var reference = TsTesting.demux(data);
            var cut = (data.length / 188 / 2) * 188;
            var noise = new byte[1000];
            new Random(3).nextBytes(noise);
            var damaged = new byte[data.length + noise.length];
            System.arraycopy(data, 0, damaged, 0, cut);
            System.arraycopy(noise, 0, damaged, cut, noise.length);
            System.arraycopy(data, cut, damaged, cut + noise.length, data.length - cut);
            var demuxed = TsTesting.demux(damaged);
            for (var track : reference.tracks()) {
                var expected = reference.packetsOf(track).size();
                assertTrue(demuxed.packets().get(track.id()).size() >= expected - 3, track.codec() + " lost too many packets");
                assertArrayEquals(reference.packetsOf(track).getLast().data(), demuxed.packets().get(track.id()).getLast().data());
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"h264 aac", "hevc ac3", "opus", "aac latm", "m2ts"})
        @DisplayName("fails only with media exceptions on mutated input, in bounded time")
        void mutations(String name) {
            var file = codings().filter(arguments -> arguments.get()[0].equals(name)).findFirst().orElseThrow().get()[1];
            var input = Fixtures.read((Path) file);
            var variants = Mutations.variants(input, List.of(), name.hashCode(), 120, false);
            var outcomes = Mutations.run(variants, Duration.ofSeconds(20), source -> {
                try (var demuxer = Containers.openDemuxer(source)) {
                    var count = 0;
                    while (demuxer.read() != null && count++ < 100_000) {
                        // read to the end
                    }
                    if (demuxer.isSeekable()) {
                        demuxer.seek(700_000);
                        demuxer.read();
                    }
                }
            });
            assertTrue(outcomes.getOrDefault("ok", 0) > 0, "some variants must still demux: " + outcomes);
        }

        @Test
        @DisplayName("reads truncated files up to the cut, and rejects a stub too short to describe")
        void truncated() {
            var data = input();
            var full = TsTesting.demux(data).order().size();
            for (var cut : new int[]{data.length / 3, data.length / 2 + 77, data.length - 100}) {
                var count = TsTesting.demux(Arrays.copyOf(data, cut)).order().size();
                assertTrue(count > 0 && count <= full, "packets of a file cut at " + cut + ": " + count);
            }
            assertThrows(MediaException.Malformed.class, () -> TsTesting.demux(Arrays.copyOf(data, 1000)));
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {
        private Path file() {
            return TsTesting.av("ts", 8, List.of("-c:v", "libx264", "-g", "60", "-keyint_min", "60", "-sc_threshold", "0",
                    "-bf", "2", "-c:a", "aac"));
        }

        @ParameterizedTest(name = "to {0} us")
        @ValueSource(longs = {0, 1, 400_000, 1_999_999, 2_000_000, 2_050_000, 3_500_000, 5_999_000, 7_900_000})
        @DisplayName("restarts every track at its last key frame at or before the target")
        void keyFrames(long target) {
            var data = Fixtures.read(file());
            var linear = TsTesting.demux(data);
            try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                demuxer.read();
                demuxer.seek(target);
                var after = new ArrayList<Packet>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    after.add(packet);
                }
                for (var track : demuxer.tracks()) {
                    var all = linear.packetsOf(track);
                    var start = -1;
                    for (var index = 0; index < all.size(); index++) {
                        if (all.get(index).keyframe() && TsTesting.micros(track, all.get(index)) <= target) {
                            start = index;
                        }
                    }
                    if (start < 0) {
                        start = 0;
                    }
                    var expected = all.subList(start, all.size());
                    var actual = after.stream().filter(packet -> packet.trackId() == track.id()).toList();
                    assertEquals(expected.size(), actual.size(), track.codec() + " packets after the seek");
                    for (var index = 0; index < expected.size(); index++) {
                        assertEquals(expected.get(index).pts(), actual.get(index).pts(), track.codec() + " packet " + index);
                        assertArrayEquals(expected.get(index).data(), actual.get(index).data());
                    }
                }
            }
        }

        @Test
        @DisplayName("returns nothing after a seek past the end, and starts over after a seek to zero")
        void bounds() {
            var data = Fixtures.read(file());
            try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source, TsDemuxerOptions.defaults())) {
                var first = demuxer.read();
                demuxer.seek(60_000_000);
                assertNull(demuxer.read());
                demuxer.seek(0);
                var again = demuxer.read();
                assertNotNull(again);
                assertArrayEquals(first.data(), again.data());
            }
        }

        @Test
        @DisplayName("cannot seek a source of unknown size")
        void unsized() {
            var data = Fixtures.read(file());
            try (var demuxer = new TsDemuxer(new UnsizedSource(data, 4096), TsDemuxerOptions.defaults())) {
                assertFalse(demuxer.isSeekable());
                assertEquals(-1, demuxer.durationMicros());
                assertThrows(MediaException.Unsupported.class, () -> demuxer.seek(1_000_000));
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(TsTesting.demux(data).order().size(), count);
            }
        }
    }

    @Nested
    @DisplayName("registration")
    class Registration {
        @Test
        @DisplayName("opens transport streams through the container registry")
        void registry() {
            var file = TsTesting.av("ts", 1, List.of("-c:v", "libx264", "-c:a", "aac"));
            try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source,
                    DemuxerOptions.defaults())) {
                assertInstanceOf(TsDemuxer.class, demuxer);
                assertEquals(ContainerFormat.MPEG_TS, demuxer.format());
            }
        }

        @Test
        @DisplayName("validates its options")
        void options() {
            var options = TsDemuxerOptions.builder().program(3).probeBytes(1 << 20).build();
            assertEquals(3, options.program().getAsInt());
            assertEquals(options, options.toBuilder().build());
            assertEquals(TsDemuxerOptions.defaults(), options.toBuilder().firstProgram()
                    .probeBytes(TsDemuxerOptions.DEFAULT_PROBE_BYTES).build());
            assertThrows(IllegalArgumentException.class, () -> TsDemuxerOptions.builder().program(0).build());
            assertThrows(IllegalArgumentException.class, () -> TsDemuxerOptions.builder().probeBytes(100).build());
        }

        @Test
        @DisplayName("exposes undescribed private PES streams as data")
        void data() {
            var demuxed = TsTesting.demux(TsMuxerTest.id3Stream());
            var data = demuxed.tracks().stream().filter(track -> track instanceof DataTrack).findFirst().orElseThrow();
            assertEquals("id3", ((DataTrack) data).handler().orElseThrow());
            assertFalse(demuxed.packetsOf(data).isEmpty());
        }
    }
}
