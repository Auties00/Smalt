package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.raw.Mpeg4VideoDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the timestamps and seeking of {@link Mpeg4VideoDemuxer}: VOP times of MPEG-4 Part 2 streams with B-VOPs,
 * group of VOP headers and irregular frame times, checked against the times ffmpeg's encoder stored in an MP4 file
 * holding the same pictures; temporal references of H.263 streams, wrapping and with a custom picture clock; seeks
 * that resume with the timestamps of a linear read; and damaged streams.
 */
@DisplayName("MPEG-4 Part 2 and H.263 elementary stream timing")
class Mpeg4TimingTest {
    private static Path encode(String extension, String filter, List<String> codec) {
        var output = new ArrayList<>(List.of("-vf", filter, "-fps_mode", "passthrough", "-an"));
        output.addAll(codec);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=30:duration=4"),
                output);
    }

    /** Copies the pictures of a file into an elementary stream, MPEG-4 with its headers before every key picture. */
    private static byte[] copyToElementary(Path file, String format) {
        var arguments = new ArrayList<>(List.of("-v", "error", "-i", file.toString(), "-c", "copy"));
        if (format.equals("m4v")) {
            arguments.addAll(List.of("-bsf:v", "dump_extra"));
        }
        arguments.addAll(List.of("-f", format, "-"));
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), arguments);
    }

    private static List<Packet> readAll(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    private static void assertDecodingTimes(List<Packet> packets) {
        for (var i = 0; i < packets.size(); i++) {
            var packet = packets.get(i);
            assertTrue(packet.dts() <= packet.pts(), "dts after pts at " + i);
            if (i > 0) {
                assertTrue(packet.dts() >= packets.get(i - 1).dts(), "decreasing dts at " + i);
            }
        }
    }

    /** Asserts that two timestamps relative to their first values are the same time, exactly. */
    private static void assertSameTimes(List<Packet> expected, Rational expectedBase, List<Packet> actual,
                                        Rational actualBase) {
        assertEquals(expected.size(), actual.size(), "pictures");
        var expectedFirst = expected.getFirst().pts();
        var actualFirst = actual.getFirst().pts();
        for (var i = 0; i < expected.size(); i++) {
            var want = (expected.get(i).pts() - expectedFirst) * expectedBase.numerator() * actualBase.denominator();
            var got = (actual.get(i).pts() - actualFirst) * actualBase.numerator() * expectedBase.denominator();
            assertEquals(want, got, "time of picture " + i);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"regular", "irregular"})
    @DisplayName("time MPEG-4 VOPs as the encoder did, with B-VOPs and group of VOP headers")
    void mpeg4(String timing) {
        // the irregular stream skips half a second after picture 20 and a second and a half after picture 70, so
        // modulo_time_base counts several seconds at once
        var filter = timing.equals("regular") ? "null"
                : "setpts='(N+if(gte(N,20),15,0)+if(gte(N,70),45,0))/30/TB'";
        var mp4File = encode("mp4", filter, List.of("-c:v", "mpeg4", "-bf", "2", "-g", "12", "-enc_time_base",
                "1/30"));
        var mp4 = MuxSupport.demux(Fixtures.read(mp4File));
        var mp4Track = mp4.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                .orElseThrow();
        var stream = copyToElementary(mp4File, "m4v");
        try (var demuxer = new Mpeg4VideoDemuxer(MediaSource.of(stream))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(new Rational(1, 30), track.timeBase(), "vop_time_increment_resolution");
            var packets = readAll(demuxer);
            assertDecodingTimes(packets);
            assertEquals(0, packets.stream().mapToLong(Packet::pts).min().orElseThrow());
            assertSameTimes(mp4.packetsOf(mp4Track), mp4Track.timeBase(), packets, track.timeBase());
            var presented = packets.stream().map(Packet::pts).sorted().toList();
            assertEquals(presented.size(), presented.stream().distinct().count(), "distinct presentation times");
        }
    }

    @Test
    @DisplayName("seek to the intra VOP at or before a time and resume with the timestamps of a linear read")
    void seek() {
        var mp4File = encode("mp4", "null", List.of("-c:v", "mpeg4", "-bf", "2", "-g", "12"));
        var stream = copyToElementary(mp4File, "m4v");
        assertSeeks(stream, new Rational(1, 30));
        var h263 = copyToElementary(encode("3gp", "null", List.of("-c:v", "h263", "-g", "12")), "h263");
        assertSeeks(h263, new Rational(1001, 30_000));
    }

    private static void assertSeeks(byte[] stream, Rational timeBase) {
        List<Packet> linear;
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            assertTrue(demuxer.isSeekable());
            assertEquals(-1, demuxer.durationMicros());
            linear = readAll(demuxer);
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            for (var seconds : new double[]{2.5, 0.0, 1.1, 3.9, 9.0, -1.0, 0.7}) {
                demuxer.seek((long) (seconds * 1_000_000));
                var after = readAll(demuxer);
                var target = Math.floorDiv((long) (seconds * 1_000_000) * timeBase.denominator(),
                        1_000_000L * timeBase.numerator());
                if (seconds >= 4) {
                    assertTrue(after.isEmpty(), "nothing after the end");
                    continue;
                }
                var start = -1;
                for (var i = 0; i < linear.size(); i++) {
                    var packet = linear.get(i);
                    if (packet.keyframe() && packet.pts() <= Math.max(0, target)
                            && (start < 0 || packet.pts() > linear.get(start).pts())) {
                        start = i;
                    }
                }
                var expected = linear.subList(start, linear.size());
                assertEquals(expected.size(), after.size(), "packets after a seek to " + seconds);
                for (var i = 0; i < after.size(); i++) {
                    assertArrayEquals(expected.get(i).data(), after.get(i).data(), "packet " + i);
                    assertEquals(expected.get(i).pts(), after.get(i).pts(), "pts of packet " + i);
                    assertEquals(expected.get(i).dts(), after.get(i).dts(), "dts of packet " + i);
                }
            }
            var duration = demuxer.durationMicros();
            assertTrue(Math.abs(duration - 4_000_000) < 70_000, "duration " + duration);
        }
    }

    @Test
    @DisplayName("time H.263 pictures by their temporal references across the wrap at 256")
    void h263() {
        // 15000/1001 frames a second: each picture advances the temporal reference by 2, so it wraps after 128
        var file = Fixtures.ffmpeg("h263", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=15000/1001"
                + ":duration=10"), List.of("-c:v", "h263", "-g", "30", "-an", "-f", "h263"));
        try (var demuxer = new Mpeg4VideoDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = demuxer.tracks().getFirst();
            assertEquals(new Rational(1001, 30_000), track.timeBase());
            var packets = readAll(demuxer);
            assertTrue(packets.size() > 140, "the stream wraps its temporal reference");
            assertDecodingTimes(packets);
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(2L * i, packets.get(i).pts(), "picture " + i);
                assertEquals(packets.get(i).pts(), packets.get(i).dts(), "no reordering without PLUSPTYPE");
            }
        }
    }

    @Test
    @DisplayName("time H.263 version 2 pictures by the picture clock of the extended picture type")
    void h263Plus() {
        var file = Fixtures.ffmpeg("263", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=2"),
                List.of("-c:v", "h263p", "-g", "10", "-an", "-f", "rawvideo"));
        try (var demuxer = new Mpeg4VideoDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var base = demuxer.tracks().getFirst().timeBase();
            var packets = readAll(demuxer);
            assertEquals(50, packets.size());
            assertDecodingTimes(packets);
            var shown = packets.stream().sorted(Comparator.comparingLong(Packet::pts)).toList();
            for (var i = 0; i < shown.size(); i++) {
                // within half a clock period of the frame's time
                var seconds = (double) shown.get(i).pts() * base.numerator() / base.denominator();
                assertTrue(Math.abs(seconds - i / 25.0) <= 0.5 * base.numerator() / base.denominator() + 1e-9,
                        "picture " + i + " at " + seconds);
            }
        }
    }

    @Test
    @DisplayName("read damaged and truncated streams to their end without failing outside MediaException")
    void damaged() {
        var mpeg4 = copyToElementary(encode("mp4", "null", List.of("-c:v", "mpeg4", "-bf", "2", "-g", "12")), "m4v");
        var h263 = copyToElementary(encode("3gp", "null", List.of("-c:v", "h263", "-g", "12")), "h263");
        var random = new Random(7);
        for (var source : List.of(mpeg4, h263)) {
            for (var round = 0; round < 40; round++) {
                var data = java.util.Arrays.copyOf(source, 8 + random.nextInt(source.length - 8));
                for (var flips = 0; flips < 1 + random.nextInt(20); flips++) {
                    var at = 4 + random.nextInt(data.length - 4);
                    data[at] = (byte) random.nextInt(256);
                }
                try (var demuxer = new Mpeg4VideoDemuxer(MediaSource.of(data))) {
                    var packets = readAll(demuxer);
                    assertDecodingTimes(packets.stream().filter(packet -> packet.pts() != Packet.NO_TIMESTAMP
                            && packet.dts() != Packet.NO_TIMESTAMP).toList());
                    demuxer.seek(random.nextInt(3_000_000));
                    readAll(demuxer);
                } catch (MediaException expected) {
                    // a damaged stream may be refused
                }
            }
        }
    }
}
