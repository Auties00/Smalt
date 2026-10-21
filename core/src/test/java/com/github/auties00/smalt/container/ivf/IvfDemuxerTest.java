package com.github.auties00.smalt.container.ivf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the IVF demuxer: VP8, VP9 and AV1 files from ffmpeg's encoders (packets and keyframe flags
 * equal to ffprobe's), detection by {@link Containers}, hand-built headers, truncation and mutation
 * robustness.
 */
@DisplayName("IVF demuxer")
class IvfDemuxerTest {
    static List<Packet> packets(byte[] data) {
        try (var demuxer = new IvfDemuxer(MediaSource.of(data))) {
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            return packets;
        }
    }

    static byte[] ivf(String fourCc, int width, int height, int rate, int scale, byte[]... frames) {
        var size = 32;
        for (var frame : frames) {
            size += 12 + frame.length;
        }
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("DKIF".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0).putShort((short) 32)
                .put(fourCc.getBytes(StandardCharsets.US_ASCII)).putShort((short) width).putShort((short) height)
                .putInt(rate).putInt(scale).putInt(frames.length).putInt(0);
        var pts = 0L;
        for (var frame : frames) {
            buffer.putInt(frame.length).putLong(pts);
            buffer.put(frame);
            pts += 2;
        }
        return buffer.array();
    }

    private static List<Boolean> probeKeyframes(Path file) {
        var output = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                "-select_streams", "v:0", "-show_entries", "packet=flags", "-of", "csv=p=0", file.toString())),
                StandardCharsets.UTF_8);
        return output.lines().filter(line -> !line.isBlank()).map(line -> line.startsWith("K")).toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"libvpx, VP8", "libvpx-vp9, VP9", "libaom-av1, AV1"})
    @DisplayName("splits ffmpeg's IVF files like ffprobe")
    void ffprobe(String encoder, Codec codec) {
        var file = Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25", "-frames:v", "20"),
                List.of("-c:v", encoder, "-g", "8", "-b:v", "200k"));
        Oracle.assume(Oracle.Tool.FFPROBE);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(ContainerFormat.IVF, demuxer.format());
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(codec, track.codec());
            assertEquals(176, track.width());
            assertEquals(144, track.height());
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            var probed = ProbedPacket.probe(file, "v:0");
            assertEquals(probed.size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                assertEquals(probed.get(i).pts(), packet.pts(), "pts of packet " + i);
                assertEquals(probed.get(i).size(), packet.data().length, "size of packet " + i);
                assertEquals(probed.get(i).crc32(), ProbedPacket.crc32(packet.data()), "crc of packet " + i);
            }
            var keyframes = probeKeyframes(file);
            assertEquals(keyframes, packets.stream().map(Packet::keyframe).toList());
            assertTrue(keyframes.stream().filter(key -> key).count() >= 2, "expected periodic key frames");
        }
    }

    @Test
    @DisplayName("reads a hand-built header, its time base and frames")
    void handBuilt() {
        var data = ivf("AV01", 320, 240, 30000, 1001, new byte[]{0x12, 0x00, 0x0A, 0x01, 0x00}, new byte[]{0x12, 0x00},
                new byte[0]);
        try (var demuxer = new IvfDemuxer(MediaSource.of(data))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.AV1, track.codec());
            assertEquals(new Rational(1001, 30000), track.timeBase());
            assertEquals(new Rational(30000, 1001), track.frameRate().orElseThrow());
            var first = demuxer.read();
            assertArrayEquals(new byte[]{0x12, 0x00, 0x0A, 0x01, 0x00}, first.data());
            assertTrue(first.keyframe(), "a temporal unit with a sequence header is a key frame");
            var second = demuxer.read();
            assertEquals(2, second.pts());
            assertFalse(second.keyframe());
            assertEquals(0, demuxer.read().data().length);
            assertNull(demuxer.read());
            assertNull(demuxer.read());
        }
    }

    @Test
    @DisplayName("takes the frame rate of a 90 kHz clock time base from the step of the frame timestamps")
    void clockRate() {
        var frames = 3;
        var buffer = ByteBuffer.allocate(32 + frames * 13).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("DKIF".getBytes(StandardCharsets.US_ASCII)).putShort((short) 0).putShort((short) 32)
                .put("VP80".getBytes(StandardCharsets.US_ASCII)).putShort((short) 16).putShort((short) 16)
                .putInt(90_000).putInt(1).putInt(frames).putInt(0);
        for (var index = 0; index < frames; index++) {
            buffer.putInt(1).putLong(index * 3600L).put((byte) 1);
        }
        try (var demuxer = new IvfDemuxer(MediaSource.of(buffer.array()))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(new Rational(1, 90_000), track.timeBase());
            var rate = track.frameRate().orElseThrow();
            assertEquals(25.0, (double) rate.numerator() / rate.denominator(), 1e-9);
            assertEquals(120_000, demuxer.durationMicros());
        }
    }

    @Test
    @DisplayName("maps unknown FourCCs to an unknown video codec and defaults the time base")
    void unknownCodec() {
        var data = ivf("XYZ1", 16, 16, 0, 0, new byte[]{1, 2, 3});
        try (var demuxer = new IvfDemuxer(MediaSource.of(data))) {
            var track = (VideoTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.UNKNOWN_VIDEO, track.codec());
            assertEquals(new Rational(1, 1000), track.timeBase());
            assertTrue(track.frameRate().isEmpty());
            assertFalse(demuxer.read().keyframe());
        }
    }

    @Test
    @DisplayName("rejects inputs that are not IVF files")
    void notIvf() {
        assertThrows(MediaException.Malformed.class, () -> new IvfDemuxer(MediaSource.of(new byte[16])));
        var data = ivf("VP80", 16, 16, 30, 1);
        data[0] = 'X';
        assertThrows(MediaException.Malformed.class, () -> new IvfDemuxer(MediaSource.of(data)));
        assertFalse(IvfDemuxer.matches(data));
        assertTrue(IvfDemuxer.matches(ivf("VP80", 16, 16, 30, 1)));
    }

    @Test
    @DisplayName("ends the stream at a truncated frame")
    void truncatedFrame() {
        var data = ivf("VP80", 16, 16, 30, 1, new byte[100], new byte[100]);
        for (var length = 32; length < data.length; length++) {
            var expected = length >= 32 + 112 + 112 ? 2 : length >= 32 + 112 ? 1 : 0;
            assertEquals(expected, packets(Arrays.copyOf(data, length)).size(), "length " + length);
        }
    }

    @Test
    @DisplayName("mutated files only throw media exceptions")
    void mutations() {
        var file = Fixtures.ffmpeg("ivf", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25", "-frames:v", "20"),
                List.of("-c:v", "libaom-av1", "-g", "8", "-b:v", "200k"));
        var input = Fixtures.read(file);
        var sizeFields = new ArrayList<int[]>();
        sizeFields.add(new int[]{6, 2});
        for (var pos = 32; pos + 12 <= input.length; ) {
            sizeFields.add(new int[]{pos, 4});
            pos += 12 + ByteBuffer.wrap(input, pos, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }
        var outcomes = Mutations.run(Mutations.variants(input, sizeFields, 3, 500, false), Duration.ofSeconds(5), source -> {
            try (var demuxer = Containers.openDemuxer(source)) {
                while (demuxer.read() != null) {
                    // drain
                }
            }
        });
        assertTrue(outcomes.containsKey("ok"), outcomes.toString());
    }
}
