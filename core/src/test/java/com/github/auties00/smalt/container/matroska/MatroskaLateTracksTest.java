package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers tracks declared after the first packet: on a seekable sink the muxer takes them, writes their blocks and a
 * {@code Tracks} element listing every track after the clusters, which the demuxer, the container validators and
 * ffprobe find through the {@code SeekHead}; on a stream and in live output it refuses them.
 */
@DisplayName("MatroskaMuxer tracks declared after the first packet")
class MatroskaLateTracksTest {
    static final Rational OPUS = Rational.timeBaseOf(48_000);

    /** An Opus track with an {@code OpusHead} configuration. */
    static AudioTrack opus(int id, int channels) {
        var head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN);
        head.put("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 1).put((byte) channels)
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

    /** Writes the first track's packets, declares the second after them, then writes its packets. */
    static void lateMux(MediaSink sink, MatroskaMuxerOptions options, List<Packet> first, List<Packet> second) {
        try (var muxer = new MatroskaMuxer(sink, options)) {
            muxer.addTrack(opus(1, 2));
            first.forEach(muxer::writePacket);
            assertTrue(muxer.supportsLateTracks());
            muxer.addTrack(opus(2, 1));
            second.forEach(muxer::writePacket);
            muxer.finish();
        }
    }

    @Test
    @DisplayName("writes a track declared after the header and lists it in the Tracks element after the clusters")
    void seekable() {
        var first = packets(1, 400, 0, 1);
        var second = packets(2, 300, 400 * 960L, 2);
        var sink = MediaSink.memory();
        lateMux(sink, MatroskaMuxerOptions.defaults(), first, second);
        var file = MuxSupport.validated("late-tracks", sink.toByteArray());
        var demuxed = MuxSupport.demux(file);
        assertEquals(2, demuxed.tracks().size());
        assertEquals(List.of(Codec.OPUS, Codec.OPUS), demuxed.tracks().stream().map(Track::codec).toList());
        assertEquals(2, ((AudioTrack) demuxed.tracks().get(0)).layout().orElseThrow().channels());
        assertEquals(1, ((AudioTrack) demuxed.tracks().get(1)).layout().orElseThrow().channels());
        assertPayloads(first, demuxed.packetsOf(demuxed.tracks().get(0)));
        assertPayloads(second, demuxed.packetsOf(demuxed.tracks().get(1)));
        // the demuxer moves both tracks by the same Opus codec delay
        var firstTimes = MuxSupport.ptsMicros(demuxed, demuxed.tracks().get(0));
        var times = MuxSupport.ptsMicros(demuxed, demuxed.tracks().get(1));
        assertEquals(OPUS.toMicros(second.getFirst().pts()), times.getFirst() - firstTimes.getFirst(), 1000);
        Oracle.assume(Oracle.Tool.FFPROBE);
        var saved = MuxSupport.save("late-tracks.mkv", file);
        assertEquals(second.size(), ProbedPacket.probe(saved, "a:1").size());
        assertEquals(first.size(), ProbedPacket.probe(saved, "a:0").size());
        MuxSupport.assertFfmpegClean("late-tracks-clean.mkv", file);
    }

    @Test
    @DisplayName("puts a track declared after packets but before the header in the header")
    void beforeHeader() {
        var first = packets(1, 3, 0, 3);
        var second = packets(2, 3, 0, 4);
        var third = packets(3, 3, 0, 7);
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            muxer.addTrack(opus(1, 2));
            muxer.addTrack(opus(2, 1));
            // the interleaving holds this packet until track 2 has one, so the header is not written yet
            muxer.writePacket(first.getFirst());
            muxer.addTrack(opus(3, 1));
            for (var index = 0; index < 3; index++) {
                if (index > 0) {
                    muxer.writePacket(first.get(index));
                }
                muxer.writePacket(second.get(index));
                muxer.writePacket(third.get(index));
            }
            muxer.finish();
        }
        var demuxed = MuxSupport.demux(MuxSupport.validated("late-before-header", sink.toByteArray()));
        assertEquals(3, demuxed.tracks().size());
        assertPayloads(first, demuxed.packetsOf(demuxed.tracks().get(0)));
        assertPayloads(second, demuxed.packetsOf(demuxed.tracks().get(1)));
        assertPayloads(third, demuxed.packetsOf(demuxed.tracks().get(2)));
    }

    @Test
    @DisplayName("refuses a track declared after the first packet on a stream and in live output")
    void refused() {
        try (var sink = MediaSink.of(new ByteArrayOutputStream()); var muxer = new MatroskaMuxer(sink)) {
            assertFalse(muxer.supportsLateTracks());
            muxer.addTrack(opus(1, 2));
            muxer.writePacket(packets(1, 1, 0, 5).getFirst());
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(opus(2, 1)));
        }
        try (var muxer = new MatroskaMuxer(MediaSink.memory(), MatroskaMuxerOptions.builder().live(true).build())) {
            assertFalse(muxer.supportsLateTracks());
            muxer.addTrack(opus(1, 2));
            muxer.writePacket(packets(1, 1, 0, 6).getFirst());
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(opus(2, 1)));
        }
        try (var muxer = new MatroskaMuxer(MediaSink.memory())) {
            muxer.addTrack(opus(1, 2));
            muxer.finish();
            assertThrows(IllegalStateException.class, () -> muxer.addTrack(opus(2, 1)));
        }
    }

    static void assertPayloads(List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "payload " + index);
        }
    }
}
