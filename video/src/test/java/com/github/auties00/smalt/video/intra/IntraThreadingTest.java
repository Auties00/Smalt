package com.github.auties00.smalt.video.intra;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdDecoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoder;
import com.github.auties00.smalt.video.dnxhd.DnxhdEncoderOptions;
import com.github.auties00.smalt.video.dnxhd.DnxhdProfile;
import com.github.auties00.smalt.video.prores.ProResDecoder;
import com.github.auties00.smalt.video.prores.ProResDecoderOptions;
import com.github.auties00.smalt.video.prores.ProResEncoder;
import com.github.auties00.smalt.video.prores.ProResEncoderOptions;
import com.github.auties00.smalt.video.prores.ProResProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that the ProRes and DNxHD decoders hand out the same pictures, with the same timestamps,
 * in the same order whatever the thread count, and that a corrupt frame surfaces as a
 * {@link MediaException} without breaking the frames around it.
 */
@DisplayName("ProRes and DNxHD decoders with several threads")
class IntraThreadingTest {
    /** The number of frames of a clip: more than the most in flight. */
    private static final int FRAMES = 23;

    private static List<Packet> proresPackets() {
        var pictures = IntraTesting.sourceFrames(200, 100, "yuv422p10le", 5);
        var encoder = new ProResEncoder(200, 100, ProResEncoderOptions.of(ProResProfile.HQ));
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < FRAMES; i++) {
            var frame = new VideoFrame(pictures.get(i % pictures.size()), i * 40L, 40, true);
            var packet = encoder.encodeFrame(frame);
            packets.add(new Packet(0, packet.data(), i * 40L, i * 40L, 40, true));
        }
        return packets;
    }

    private static List<Packet> dnxhdPackets() {
        var pictures = IntraTesting.sourceFrames(200, 100, "yuv422p", 5);
        var encoder = new DnxhdEncoder(200, 100, DnxhdEncoderOptions.of(DnxhdProfile.DNXHR_HQ));
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < FRAMES; i++) {
            var frame = new VideoFrame(pictures.get(i % pictures.size()), i * 40L, 40, true);
            var packet = encoder.encodeFrame(frame);
            packets.add(new Packet(0, packet.data(), i * 40L, i * 40L, 40, true));
        }
        return packets;
    }

    private record Decoded(List<byte[]> pictures, List<Long> timestamps) {
    }

    private static Decoded decodeProres(List<Packet> packets, int threads) {
        var pictures = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();
        Consumer<VideoFrame> sink = frame -> {
            pictures.add(IntraTesting.raw(frame.picture(), false));
            timestamps.add(frame.pts());
        };
        try (var decoder = new ProResDecoder(ProResDecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return new Decoded(pictures, timestamps);
    }

    private static Decoded decodeDnxhd(List<Packet> packets, int threads) {
        var pictures = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();
        Consumer<VideoFrame> sink = frame -> {
            pictures.add(IntraTesting.raw(frame.picture(), false));
            timestamps.add(frame.pts());
        };
        try (var decoder = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(threads).build())) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return new Decoded(pictures, timestamps);
    }

    private static void assertSame(Decoded expected, Decoded actual, String what) {
        assertEquals(expected.timestamps(), actual.timestamps(), what + " timestamps");
        assertEquals(expected.pictures().size(), actual.pictures().size(), what + " frames");
        for (var i = 0; i < expected.pictures().size(); i++) {
            assertArrayEquals(expected.pictures().get(i), actual.pictures().get(i), what + " frame " + i);
        }
    }

    @ParameterizedTest(name = "{0} threads")
    @ValueSource(ints = {0, 2, 4, 8})
    @DisplayName("ProRes hands out the pictures of one thread in order")
    void prores(int threads) {
        var packets = proresPackets();
        assertSame(decodeProres(packets, 1), decodeProres(packets, threads), "ProRes");
    }

    @ParameterizedTest(name = "{0} threads")
    @ValueSource(ints = {0, 2, 4, 8})
    @DisplayName("DNxHD hands out the pictures of one thread in order")
    void dnxhd(int threads) {
        var packets = dnxhdPackets();
        assertSame(decodeDnxhd(packets, 1), decodeDnxhd(packets, threads), "DNxHD");
    }

    @Test
    @DisplayName("a corrupt frame raises a MediaException and later frames still decode")
    void corruptFrame() {
        var packets = new ArrayList<>(dnxhdPackets());
        var broken = packets.get(5).data().clone();
        java.util.Arrays.fill(broken, 0, 64, (byte) 0xFF);
        packets.set(5, new Packet(0, broken, 5 * 40L, 5 * 40L, 40, true));
        var sink = new ArrayList<Picture>();
        var thrown = 0;
        try (var decoder = new DnxhdDecoder(DnxhdDecoderOptions.builder().threads(4).build())) {
            for (var packet : packets) {
                try {
                    decoder.decode(packet, frame -> sink.add(frame.picture()));
                } catch (MediaException exception) {
                    thrown++;
                }
            }
            try {
                decoder.flush(frame -> sink.add(frame.picture()));
            } catch (MediaException exception) {
                thrown++;
            }
        }
        assertEquals(1, thrown);
        assertEquals(FRAMES - 1, sink.size());
        assertThrows(NullPointerException.class, () -> new DnxhdDecoder().flush(null));
    }
}
