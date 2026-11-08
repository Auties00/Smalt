package com.github.auties00.smalt.audio.dts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.dts.DtsDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the DTS decoder truncated, corrupted and foreign data: it must reject what it cannot decode with a
 * {@link MediaException} only, keep decoding the frames that follow, and never output buffers whose planes disagree
 * with their layout.
 */
final class DtsDecoderRobustnessTest {
    private static final AudioTrack TRACK = AudioTrack.builder().codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000))
            .sampleRate(48000).build();

    private static List<byte[]> units(String vector) {
        var file = Conformance.fetchListed("dts-" + vector + ".dtshd");
        var units = new ArrayList<byte[]>();
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                units.add(packet.data());
            }
        }
        return units;
    }

    private static int decode(DtsDecoder decoder, byte[] data) {
        var count = new int[1];
        try {
            decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                assertEquals(buffer.layout().channels(), buffer.channels());
                for (var ch = 0; ch < buffer.channels(); ch++) {
                    assertTrue(buffer.plane(ch).length >= buffer.frames());
                }
                count[0]++;
            });
        } catch (MediaException expected) {
            return -1;
        }
        return count[0];
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"core_51_24_48_768_0", "xch_61_24_48_768", "xxch_71_24_48_2046", "x96_51_24_96_1509",
            "xbr_51_24_48_3840", "xll_51_24_48_768", "xll_51_24_48_none"})
    @DisplayName("truncated and corrupted units raise only media exceptions")
    void corrupted(String vector) {
        var units = units(vector);
        var random = new SplittableRandom(vector.hashCode());
        var decoder = new DtsDecoder(TRACK);
        for (var unit : units) {
            for (var cut = 0; cut < unit.length; cut += Math.max(1, unit.length / 37)) {
                decode(decoder, Arrays.copyOf(unit, cut));
            }
            for (var trial = 0; trial < 24; trial++) {
                var damaged = unit.clone();
                for (var flips = 0; flips < 1 + random.nextInt(4); flips++) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                decode(decoder, damaged);
            }
        }
    }

    @Test
    @DisplayName("a damaged unit does not stop the decoding of the next ones")
    void recovers() {
        var units = units("core_51_24_48_768_0");
        var decoder = new DtsDecoder(TRACK);
        var damaged = units.getFirst().clone();
        Arrays.fill(damaged, 20, damaged.length, (byte) 0x5A);
        assertEquals(-1, decode(decoder, damaged));
        assertEquals(1, decode(decoder, units.get(1)));
    }

    @Test
    @DisplayName("bytes that start no frame decode to nothing")
    void foreign() {
        var decoder = new DtsDecoder(TRACK);
        var random = new SplittableRandom(7);
        var noise = new byte[8192];
        for (var i = 0; i < noise.length; i++) {
            noise[i] = (byte) random.nextInt(256);
        }
        assertTrue(decode(decoder, noise) <= 0);
    }

    @Test
    @DisplayName("a closed decoder refuses packets")
    void closed() {
        var decoder = new DtsDecoder(TRACK);
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.decode(new Packet(0, new byte[16], 0, 0, 0, true),
                buffer -> { }));
    }
}
