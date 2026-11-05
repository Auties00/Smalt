package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers tracks declared after the first packet: the movie header, written by {@code finish()}, lists them, with
 * their samples, on a seekable sink and on a stream, with and without fast start, as the demuxer and ffprobe read
 * the file.
 */
@DisplayName("Mp4Muxer tracks declared after the first packet")
class Mp4LateTracksTest {
    static final Rational OPUS = Rational.timeBaseOf(48_000);

    /** An Opus track with an {@code OpusHead} configuration. */
    static AudioTrack opus(int id, int channels) {
        var head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(StandardCharsets.US_ASCII)).put((byte) 1).put((byte) channels)
                .putShort((short) 312).putInt(48_000).putShort((short) 0).put((byte) 0);
        return AudioTrack.builder().id(id).codec(Codec.OPUS).timeBase(OPUS).sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(channels)).skipSamples(312).codecConfig(head.array()).build();
    }

    /** Packets of 20 ms with a TOC byte and random payload, starting at a sample offset. */
    static List<Packet> packets(int trackId, int count, long start, long seed) {
        var random = new SplittableRandom(seed);
        var result = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            var data = new byte[40 + random.nextInt(40)];
            random.nextBytes(data);
            data[0] = (byte) 0xF8;
            var pts = start + index * 960L;
            result.add(new Packet(trackId, data, pts, pts, 960, true));
        }
        return result;
    }

    @ParameterizedTest(name = "fast start {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("lists a track declared after the first packet in the movie header")
    void lateTrack(boolean fastStart) {
        var first = packets(1, 300, 0, 1);
        var second = packets(2, 200, 300 * 960L, 2);
        var sink = MediaSink.memory();
        try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.builder().fastStart(fastStart).build())) {
            muxer.addTrack(opus(1, 2));
            first.forEach(muxer::writePacket);
            assertTrue(muxer.supportsLateTracks());
            muxer.addTrack(opus(2, 1));
            second.forEach(muxer::writePacket);
            muxer.finish();
        }
        var file = MuxSupport.validated("mp4-late-tracks", sink.toByteArray());
        var demuxed = MuxSupport.demux(file);
        assertEquals(2, demuxed.tracks().size());
        assertPayloads(first, demuxed.packetsOf(demuxed.tracks().get(0)));
        assertPayloads(second, demuxed.packetsOf(demuxed.tracks().get(1)));
        assertEquals(OPUS.toMicros(second.getFirst().pts()), MuxSupport.ptsMicros(demuxed, demuxed.tracks().get(1))
                .getFirst() - MuxSupport.ptsMicros(demuxed, demuxed.tracks().get(0)).getFirst(), 1000);
        Oracle.assume(Oracle.Tool.FFPROBE);
        var saved = MuxSupport.save("mp4-late-tracks-" + fastStart + ".mp4", file);
        assertEquals(first.size(), ProbedPacket.probe(saved, "a:0").size());
        assertEquals(second.size(), ProbedPacket.probe(saved, "a:1").size());
    }

    @Test
    @DisplayName("lists a late track on a stream too")
    void stream() {
        var first = packets(1, 50, 0, 3);
        var second = packets(2, 50, 50 * 960L, 4);
        var output = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(output); var muxer = new Mp4Muxer(sink)) {
            muxer.addTrack(opus(1, 2));
            first.forEach(muxer::writePacket);
            muxer.addTrack(opus(2, 2));
            second.forEach(muxer::writePacket);
            muxer.finish();
        }
        var demuxed = MuxSupport.demux(MuxSupport.validated("mp4-late-stream", output.toByteArray()));
        assertEquals(2, demuxed.tracks().size());
        assertPayloads(second, demuxed.packetsOf(demuxed.tracks().get(1)));
    }

    @Test
    @DisplayName("takes no late track when it only plans a header")
    void planner() {
        assertFalse(Mp4Muxer.planner(Mp4MuxerOptions.defaults()).supportsLateTracks());
    }

    static void assertPayloads(List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "payload " + index);
        }
    }
}
