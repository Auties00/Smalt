package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.testkit.audio.TargetBitrate;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks {@link AmrDecoder#conceal(int, java.util.function.Consumer)}, which runs the bad frame handling of TS 26.091
 * and TS 26.191 for every lost frame, and {@link AmrEncoder#setTargetBitrate(long)}, which selects the codec mode for
 * a rate adaptation request: concealment equals decoding empty packets, keeps the output length, joins the frames
 * without clicks and lets the decoder converge after the loss, and a schedule of targets is followed by the modes at
 * or below them.
 */
class AmrConcealmentTest {
    static PacketLoss.Encoded encode(AmrMode mode, double seconds) {
        try (var encoder = new AmrEncoder(AmrEncoderOptions.of(mode))) {
            return PacketLoss.encode(encoder, PacketLoss.signal(encoder.sampleRate(), 1, seconds));
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = AmrMode.class, names = {"NB_12_2", "NB_5_90", "WB_12_65", "WB_23_85"})
    @DisplayName("conceals bursts and a random trace keeping the length, without clicks, then converges")
    void traces(AmrMode mode) {
        var encoded = encode(mode, 4);
        var packets = encoded.packets();
        // the CELP state the lost frames would have refreshed converges only slowly, so after the loss the decode
        // is only required to approach the reference
        var bounds = new PacketLoss.Bounds(true, 3.0, 6, 0, 0, 12, 0);
        for (var lost : List.of(PacketLoss.bursts(30, 1, 70, 3, 120, 6),
                PacketLoss.random(mode.ordinal(), packets.size(), 10, 2, 3, 14))) {
            PacketLoss.assertConceals(() -> new AmrDecoder(encoded.track()), packets, lost, bounds);
        }
    }

    @Test
    @DisplayName("produces the samples of empty packets, one frame per 20 ms")
    void matchesEmptyPackets() {
        var encoded = encode(AmrMode.NB_7_95, 1);
        var packets = encoded.packets();
        var viaConceal = new ArrayList<Float>();
        var viaPackets = new ArrayList<Float>();
        try (var concealing = new AmrDecoder(encoded.track()); var empty = new AmrDecoder(encoded.track())) {
            for (var i = 0; i < 30; i++) {
                if (i >= 10 && i < 13) {
                    if (i == 10) {
                        assertEquals(480, concealing.conceal(470, buffer -> add(viaConceal, buffer.plane(0),
                                buffer.frames())));
                    }
                    empty.decode(new Packet(1, new byte[0], 0, 0, 160, true), buffer -> add(viaPackets,
                            buffer.plane(0), buffer.frames()));
                    continue;
                }
                concealing.decode(packets.get(i), buffer -> add(viaConceal, buffer.plane(0), buffer.frames()));
                empty.decode(packets.get(i), buffer -> add(viaPackets, buffer.plane(0), buffer.frames()));
            }
            assertThrows(IllegalArgumentException.class, () -> concealing.conceal(0, buffer -> { }));
        }
        assertArrayEquals(viaPackets.toArray(), viaConceal.toArray());
    }

    static void add(List<Float> samples, float[] plane, int frames) {
        for (var i = 0; i < frames; i++) {
            samples.add(plane[i]);
        }
    }

    @Test
    @DisplayName("selects the mode at or below each target of a schedule")
    void targetBitrate() {
        try (var encoder = new AmrEncoder(AmrEncoderOptions.of(AmrMode.NB_12_2))) {
            // a stored frame adds its header byte and pads the speech bits to a byte, up to 10% over the mode's rate
            TargetBitrate.assertFollows(encoder, PacketLoss.signal(8000, 1, 4), 0.04, 0.12,
                    new TargetBitrate.Phase(1, 0), new TargetBitrate.Phase(1, 7400), new TargetBitrate.Phase(1, 4750),
                    new TargetBitrate.Phase(1, 10200));
            encoder.setTargetBitrate(9000);
            assertEquals(AmrMode.NB_7_95, encoder.mode());
            encoder.setTargetBitrate(1000);
            assertEquals(AmrMode.NB_4_75, encoder.mode());
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
        }
        try (var encoder = new AmrEncoder(AmrEncoderOptions.of(AmrMode.WB_6_60))) {
            encoder.setTargetBitrate(64000);
            assertEquals(AmrMode.WB_23_85, encoder.mode());
            assertEquals(Codec.AMR_WB, encoder.codec());
        }
    }
}
