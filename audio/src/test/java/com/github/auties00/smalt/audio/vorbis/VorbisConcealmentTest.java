package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link VorbisDecoder#conceal(int, java.util.function.Consumer)}: lost packets are replaced by blocks of the
 * last block size whose spectrum repeats the last one with random signs, joined to their neighbours by the window
 * overlap without clicks, fading to silence from the fifth block, and the packet after the one that follows a loss
 * decodes exactly as without the loss.
 */
class VorbisConcealmentTest {
    static PacketLoss.Encoded encode(int rate, int channels, float quality, double seconds) {
        var options = VorbisEncoderOptions.ofQuality(quality).toBuilder()
                .format(rate, ChannelLayout.defaultFor(channels)).build();
        try (var encoder = new VorbisEncoder(options)) {
            return PacketLoss.encode(encoder, PacketLoss.signal(rate, channels, seconds));
        }
    }

    @ParameterizedTest(name = "{0} Hz {1} channels quality {2}")
    @CsvSource({"44100, 2, 0.4", "48000, 1, 0.1", "22050, 2, 0.7", "8000, 1, 0.2"})
    @DisplayName("conceals bursts and a random trace without clicks and recovers exactly")
    void traces(int rate, int channels, float quality) {
        var encoded = encode(rate, channels, quality, 4);
        var packets = encoded.packets();
        var bounds = new PacketLoss.Bounds(false, 2.5, 6, rate / 200, 12, 1, 100);
        for (var lost : List.of(PacketLoss.bursts(20, 1, 50, 2, 90, 7),
                PacketLoss.random(rate + channels, packets.size(), 10, 2, 3, 4))) {
            PacketLoss.assertConceals(() -> new VorbisDecoder(encoded.track()), packets, lost, bounds);
        }
    }

    @Test
    @DisplayName("conceals in units of half the last block and keeps the length of a run of long blocks")
    void units() {
        var encoded = encode(44100, 2, 0.4f, 2);
        var packets = encoded.packets();
        var reference = PacketLoss.decode(new VorbisDecoder(encoded.track()), packets);
        var longest = 0;
        for (var length : reference.lengths()) {
            longest = Math.max(longest, length);
        }
        var run = -1;
        for (var i = 10; i + 4 < packets.size() && run < 0; i++) {
            var steady = true;
            for (var k = i - 1; k <= i + 3; k++) {
                steady &= reference.lengths()[k] == longest;
            }
            if (steady) {
                run = i;
            }
        }
        assertTrue(run > 0, "the stream has a run of long blocks");
        var lossy = PacketLoss.decode(new VorbisDecoder(encoded.track()), packets, Set.of(run, run + 1, run + 2),
                reference);
        assertEquals(3 * longest, lossy.runs().getFirst().returned());
        assertEquals(reference.length(), lossy.length());
        var decoder = new VorbisDecoder(encoded.track());
        var handed = new int[1];
        var silent = decoder.conceal(10, buffer -> {
            handed[0] += buffer.frames();
            for (var i = 0; i < buffer.frames(); i++) {
                assertEquals(0, buffer.plane(0)[i]);
            }
        });
        assertEquals(handed[0], silent);
        assertTrue(silent > 0, "a span before the first packet is concealed with silence");
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(0, buffer -> { }));
    }

    @Test
    @DisplayName("fades a long loss to silence")
    void fades() {
        var encoded = encode(48000, 1, 0.3f, 2);
        var packets = encoded.packets();
        var decoder = new VorbisDecoder(encoded.track());
        for (var i = 0; i < 30; i++) {
            decoder.decode(packets.get(i), buffer -> { });
        }
        var energies = new java.util.ArrayList<Double>();
        decoder.conceal(48000, buffer -> energies.add(PacketLoss.energy(new float[][]{buffer.plane(0)}, 0,
                buffer.frames())));
        assertTrue(energies.size() > 8, "the span is concealed block by block");
        assertTrue(energies.get(1) > 0, "the second concealed block is heard");
        for (var i = 6; i < energies.size(); i++) {
            assertEquals(0, energies.get(i), "block " + i + " is silent");
        }
    }
}
