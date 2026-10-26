package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link OpusDecoder#conceal(int, java.util.function.Consumer)}, the codec-neutral entry to the Opus packet
 * loss concealment: it produces exactly what zero-length packets of the same durations produce (the libopus
 * concealment the golden traces already pin), keeps the output length on 20 ms packets of every mode, joins the
 * concealed spans without clicks and converges back to the reference after the loss.
 */
class OpusConcealmentTest {
    static PacketLoss.Encoded stream(int rate, int channels, OpusApplication application, int bitrate) {
        var options = OpusEncoderOptions.defaults().toBuilder().format(rate, channels).application(application)
                .bitrate(bitrate).frameDurationMicros(20_000).build();
        try (var encoder = new OpusEncoder(options)) {
            return PacketLoss.encode(encoder, PacketLoss.signal(rate, channels, 3));
        }
    }

    @ParameterizedTest(name = "{2} {0} Hz {1} channels {3} bit/s")
    @CsvSource({"48000, 2, AUDIO, 64000", "16000, 1, VOIP, 20000", "48000, 1, RESTRICTED_LOWDELAY, 48000",
            "24000, 2, AUDIO, 32000"})
    @DisplayName("conceals bursts and a random trace keeping the length, without clicks, then converges")
    void traces(int rate, int channels, OpusApplication application, int bitrate) {
        var encoded = stream(rate, channels, application, bitrate);
        var packets = encoded.packets();
        var bounds = new PacketLoss.Bounds(true, 2.5, 6, rate / 200, 20, 10, 10);
        for (var lost : List.of(PacketLoss.bursts(20, 1, 50, 3, 90, 8), PacketLoss.random(rate, packets.size(), 10,
                2, 3, 12))) {
            PacketLoss.assertConceals(() -> new OpusDecoder(encoded.track()), packets, lost, bounds);
        }
    }

    @Test
    @DisplayName("produces the samples of zero-length packets of the same durations")
    void matchesEmptyPackets() {
        var encoded = stream(48000, 2, OpusApplication.AUDIO, 64000);
        var packets = encoded.packets();
        var viaConceal = new ArrayList<float[]>();
        var viaPackets = new ArrayList<float[]>();
        try (var concealing = new OpusDecoder(encoded.track()); var empty = new OpusDecoder(encoded.track())) {
            for (var i = 0; i < 40; i++) {
                if (i >= 20 && i < 24) {
                    if (i == 20) {
                        assertEquals(4 * 960, concealing.conceal(4 * 960, buffer -> viaConceal.add(left(buffer))));
                    }
                    empty.decode(new Packet(1, new byte[0], Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 960, false),
                            buffer -> viaPackets.add(left(buffer)));
                    continue;
                }
                concealing.decode(packets.get(i), buffer -> viaConceal.add(left(buffer)));
                empty.decode(packets.get(i), buffer -> viaPackets.add(left(buffer)));
            }
        }
        assertArrayEquals(flatten(viaPackets), flatten(viaConceal));
    }

    @Test
    @DisplayName("rounds the span to whole 2.5 ms units and drops the pre-skip from concealed samples")
    void units() {
        var encoded = stream(48000, 1, OpusApplication.AUDIO, 48000);
        try (var decoder = new OpusDecoder(encoded.track())) {
            var skip = (int) (48000L * preSkip(encoded) / 48000);
            var handed = new int[1];
            var first = decoder.conceal(960, buffer -> {
                handed[0] += buffer.frames();
                for (var i = 0; i < buffer.frames(); i++) {
                    assertEquals(0, buffer.plane(0)[i], "silence before the first packet");
                }
            });
            assertEquals(960 - skip, first);
            assertEquals(first, handed[0]);
            decoder.decode(encoded.packets().get(1), buffer -> { });
            assertEquals(120, decoder.conceal(100, buffer -> { }));
            assertEquals(960, decoder.conceal(1000, buffer -> { }));
            assertEquals(48000, decoder.conceal(48000, buffer -> { }));
            assertThrows(IllegalArgumentException.class, () -> decoder.conceal(0, buffer -> { }));
            assertTrue(decoder.supportsConcealment());
        }
    }

    @Test
    @DisplayName("rejects concealment once closed")
    void closed() {
        var encoded = stream(16000, 1, OpusApplication.VOIP, 16000);
        var decoder = new OpusDecoder(encoded.track());
        decoder.close();
        assertThrows(IllegalStateException.class, () -> decoder.conceal(320, buffer -> { }));
    }

    static int preSkip(PacketLoss.Encoded encoded) {
        var head = encoded.track().codecConfig().orElseThrow();
        return (head[10] & 0xFF) | (head[11] & 0xFF) << 8;
    }

    static float[] left(AudioBuffer buffer) {
        return java.util.Arrays.copyOf(buffer.plane(0), buffer.frames());
    }

    static float[] flatten(List<float[]> blocks) {
        var total = blocks.stream().mapToInt(block -> block.length).sum();
        var out = new float[total];
        var at = 0;
        for (var block : blocks) {
            System.arraycopy(block, 0, out, at, block.length);
            at += block.length;
        }
        return out;
    }

    @Test
    @DisplayName("lets a lost run at the end of the stream be concealed with nothing after it")
    void trailingLoss() {
        var encoded = stream(48000, 2, OpusApplication.AUDIO, 64000);
        var packets = encoded.packets();
        var reference = PacketLoss.decode(new OpusDecoder(encoded.track()), packets);
        var lost = Set.of(packets.size() - 2, packets.size() - 1);
        var lossy = PacketLoss.decode(new OpusDecoder(encoded.track()), packets, lost, reference);
        assertEquals(reference.length(), lossy.length());
    }
}
