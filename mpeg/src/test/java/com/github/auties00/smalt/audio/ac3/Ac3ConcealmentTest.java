package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link Ac3Decoder#conceal(int, java.util.function.Consumer)} on AC-3 and E-AC-3 streams: a lost access
 * unit is concealed as a damaged one, repeating the last good coefficients with a fade in the last unit's layout, so
 * the output keeps its length and format, joins without clicks, and from the second unit after it decodes as without
 * the loss but for the dither; concealing equals decoding the lost unit flagged corrupt.
 */
class Ac3ConcealmentTest {
    /**
     * The decode after a loss converges to the reference except for the dither of the mantissas coded with no bits,
     * whose generator the lost units no longer advance.
     */
    private static final PacketLoss.Bounds BOUNDS = new PacketLoss.Bounds(true, 2.5, 6, 256, 12, 1, 20);

    static PacketLoss.Encoded encode(AudioEncoder encoder, double seconds) {
        try (encoder) {
            return PacketLoss.encode(encoder, PacketLoss.signal(encoder.sampleRate(), encoder.layout().channels(),
                    seconds));
        }
    }

    static void check(PacketLoss.Encoded encoded) {
        var packets = encoded.packets();
        for (var lost : List.of(PacketLoss.bursts(15, 1, 40, 2, 70, 5),
                PacketLoss.random(packets.size(), packets.size(), 10, 2, 3, 3))) {
            PacketLoss.assertConceals(() -> new Ac3Decoder(encoded.track()), packets, lost, BOUNDS);
        }
    }

    @Test
    @DisplayName("conceals AC-3 stereo and 5.1 access units")
    void ac3() {
        check(encode(new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.STEREO)), 3));
        check(encode(new Ac3Encoder(Ac3EncoderOptions.of(48000, ChannelLayout.SURROUND_5_1)), 3));
    }

    @Test
    @DisplayName("conceals E-AC-3 access units")
    void eac3() {
        check(encode(new Eac3Encoder(Eac3EncoderOptions.of(48000, ChannelLayout.STEREO)), 3));
    }

    @Test
    @DisplayName("conceals 7.1 E-AC-3 access units, the channels of the dependent substream included")
    void eac3Dependent() {
        var encoded = encode(new Eac3Encoder(Eac3EncoderOptions.of(48000, ChannelLayout.SURROUND_7_1)), 3);
        check(encoded);
        var packets = encoded.packets();
        var reference = PacketLoss.decode(new Ac3Decoder(encoded.track()), packets);
        var lossy = PacketLoss.decode(new Ac3Decoder(encoded.track()), packets, Set.of(20), reference);
        assertEquals(8, lossy.planes().length);
        var run = lossy.runs().getFirst();
        var before = reference.starts()[20];
        for (var c = 0; c < 8; c++) {
            var heard = PacketLoss.energy(new float[][]{reference.planes()[c]}, before - 1536, before);
            var concealed = PacketLoss.energy(new float[][]{lossy.planes()[c]}, run.from() + 256, run.to());
            if (heard > 1e-8) {
                assertTrue(concealed > heard / 100, "channel " + c + " is concealed, not silenced: " + concealed
                        + " against " + heard);
            }
        }
    }

    @Test
    @DisplayName("conceals like a unit flagged corrupt and rounds the span to whole units")
    void matchesCorrupt() {
        var encoded = encode(new Ac3Encoder(Ac3EncoderOptions.of(44100, ChannelLayout.STEREO)), 1);
        var packets = encoded.packets();
        var viaConceal = new ArrayList<float[]>();
        var viaFlag = new ArrayList<float[]>();
        try (var concealing = new Ac3Decoder(encoded.track()); var flagged = new Ac3Decoder(encoded.track())) {
            assertEquals(1536, concealing.conceal(10, buffer -> { }));
            flagged.decode(new Packet(1, packets.getFirst().data(), 0, 0, 1536,
                    Set.of(com.github.auties00.smalt.track.PacketFlag.KEY,
                            com.github.auties00.smalt.track.PacketFlag.CORRUPT), PacketSideData.NONE), buffer -> { });
            for (var i = 1; i < 20; i++) {
                if (i == 8) {
                    assertEquals(1536, concealing.conceal(1600, buffer -> viaConceal.add(buffer.plane(0).clone())));
                    var damaged = packets.get(i);
                    flagged.decode(new Packet(1, damaged.data(), damaged.pts(), damaged.dts(), damaged.duration(),
                            Set.of(com.github.auties00.smalt.track.PacketFlag.CORRUPT), PacketSideData.NONE),
                            buffer -> viaFlag.add(buffer.plane(0).clone()));
                    continue;
                }
                concealing.decode(packets.get(i), buffer -> viaConceal.add(buffer.plane(0).clone()));
                flagged.decode(packets.get(i), buffer -> viaFlag.add(buffer.plane(0).clone()));
            }
            assertThrows(IllegalArgumentException.class, () -> concealing.conceal(0, buffer -> { }));
        }
        assertEquals(viaFlag.size(), viaConceal.size());
        for (var i = 0; i < viaFlag.size(); i++) {
            assertArrayEquals(viaFlag.get(i), viaConceal.get(i), "unit " + i);
        }
    }
}
