package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the packet loss concealment of the PCM decoder, the waveform substitution of ITU-T G.711 Appendix I: on
 * 20 ms packets of 16-bit and G.711 PCM it keeps the output length, joins the repeated periods without clicks, fades
 * to silence between 10 and 60 ms, overlaps the first packet after the loss and leaves every later packet bit-exact,
 * and an exactly periodic signal continues exactly through the first 10 ms of a loss.
 */
class PcmConcealmentTest {
    static PacketLoss.Encoded encode(Codec codec, int rate, ChannelLayout layout, float[][] planes) {
        var options = PcmEncoderOptions.builder().codec(codec).format(rate, layout).framesPerPacket(rate / 50).build();
        return PacketLoss.encode(new PcmEncoder(options), planes);
    }

    @ParameterizedTest(name = "{0} {1} Hz {2} channels")
    @CsvSource({"PCM_S16LE, 48000, 2", "PCM_S24LE, 44100, 1", "PCM_MULAW, 8000, 1", "PCM_ALAW, 8000, 1",
            "PCM_F32LE, 16000, 2"})
    @DisplayName("conceals bursts of 1 to 6 packets and a random trace without clicks, keeping the length")
    void traces(Codec codec, int rate, int channels) {
        var layout = ChannelLayout.defaultFor(channels);
        var encoded = encode(codec, rate, layout, PacketLoss.signal(rate, channels, 4));
        var packets = encoded.packets();
        for (var lost : List.of(PacketLoss.bursts(20, 1, 60, 3, 120, 6),
                PacketLoss.random(rate, packets.size(), 10, 2, 3, 4))) {
            // the first 10 ms repeat the last pitch period at full gain, so they keep the level of the signal
            var bounds = new PacketLoss.Bounds(true, 2.0, 6.0, rate / 100, 10, 1, 200);
            PacketLoss.assertConceals(() -> new PcmDecoder(encoded.track()), packets, lost, bounds);
        }
    }

    @Test
    @DisplayName("repeats an exactly periodic signal exactly for 10 ms, then fades it to silence by 60 ms")
    void periodic() {
        var rate = 48000;
        var period = 400;
        var frames = rate;
        var planes = new float[1][frames];
        for (var i = 0; i < frames; i++) {
            var phase = 2 * Math.PI * (i % period) / period;
            planes[0][i] = (float) (0.3 * StrictMath.sin(phase) + 0.1 * StrictMath.sin(3 * phase + 1));
        }
        var encoded = encode(Codec.PCM_F32LE, rate, ChannelLayout.MONO, planes);
        var decoder = new PcmDecoder(encoded.track());
        var history = 12;
        for (var i = 0; i < history; i++) {
            decoder.decode(encoded.packets().get(i), buffer -> { });
        }
        var concealed = new float[rate / 10];
        var at = new int[1];
        var produced = decoder.conceal(concealed.length, buffer -> {
            System.arraycopy(buffer.plane(0), 0, concealed, at[0], buffer.frames());
            at[0] += buffer.frames();
        });
        assertEquals(concealed.length, produced);
        assertEquals(concealed.length, at[0]);
        var start = history * rate / 50;
        for (var i = 0; i < rate / 100; i++) {
            assertEquals(planes[0][start + i], concealed[i], 1e-6, "sample " + i + " of the first 10 ms");
        }
        var muted = rate * 60 / 1000;
        for (var i = muted; i < concealed.length; i++) {
            assertEquals(0, concealed[i], "sample " + i + " after 60 ms");
        }
        var mid = PacketLoss.energy(new float[][]{concealed}, rate * 30 / 1000, rate * 40 / 1000);
        var full = PacketLoss.energy(new float[][]{concealed}, 0, rate / 100);
        assertTrue(mid < full * 0.6 && mid > full * 0.1, "the gain falls linearly: " + mid + " against " + full);
    }

    @Test
    @DisplayName("overlaps only the first packet after a loss and decodes the later ones bit for bit")
    void recovery() {
        var rate = 16000;
        var encoded = encode(Codec.PCM_S16LE, rate, ChannelLayout.MONO, PacketLoss.signal(rate, 1, 2));
        var reference = PacketLoss.decode(new PcmDecoder(encoded.track()), encoded.packets());
        var lossy = PacketLoss.decode(new PcmDecoder(encoded.track()), encoded.packets(), Set.of(30), reference);
        var packet = rate / 50;
        var next = 31 * packet;
        // a 20 ms loss is overlapped over 4 ms plus 4 ms for the 10 ms beyond the first 10
        var overlap = rate * 8 / 1000;
        var differs = false;
        for (var i = next; i < next + overlap; i++) {
            differs |= reference.planes()[0][i] != lossy.planes()[0][i];
        }
        assertTrue(differs, "the first packet after the loss is overlapped with the repetition");
        assertTrue(reference.planes()[0][next + overlap - 1] != lossy.planes()[0][next + overlap - 1],
                "the overlap lasts 8 ms");
        for (var i = next + overlap; i < reference.length(); i++) {
            assertEquals(reference.planes()[0][i], lossy.planes()[0][i], "sample " + i);
        }
        for (var i = 0; i < 30 * packet; i++) {
            assertEquals(reference.planes()[0][i], lossy.planes()[0][i], "sample " + i + " before the loss");
        }
    }

    @Test
    @DisplayName("conceals with silence before the first packet and rejects a span that is not positive")
    void edges() {
        var encoded = encode(Codec.PCM_S16LE, 8000, ChannelLayout.STEREO, PacketLoss.signal(8000, 2, 0.2));
        var decoder = new PcmDecoder(encoded.track());
        var blocks = new ArrayList<float[]>();
        assertEquals(1000, decoder.conceal(1000, buffer -> blocks.add(buffer.plane(1).clone())));
        for (var block : blocks) {
            for (var sample : block) {
                assertEquals(0, sample);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(0, buffer -> { }));
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(-5, buffer -> { }));
        assertTrue(decoder.supportsConcealment());
    }

    @Test
    @DisplayName("conceals a span shorter than a packet and one of many packets in blocks of the reported format")
    void spans() {
        var encoded = encode(Codec.PCM_S16LE, 48000, ChannelLayout.STEREO, PacketLoss.signal(48000, 2, 0.5));
        var decoder = new PcmDecoder(encoded.track());
        decoder.decode(encoded.packets().getFirst(), buffer -> { });
        for (var span : new int[]{1, 7, 960, 48000}) {
            var total = new int[1];
            assertEquals(span, decoder.conceal(span, buffer -> {
                assertEquals(ChannelLayout.STEREO, buffer.layout());
                assertEquals(48000, buffer.sampleRate());
                total[0] += buffer.frames();
            }));
            assertEquals(span, total[0]);
        }
        var block = new AudioBuffer[1];
        decoder.decode(new Packet(1, new byte[]{1, 0, 2, 0}, 0, 0, 1, true), buffer -> block[0] = buffer);
        var first = block[0].plane(0)[0];
        assertTrue(first > 0 && first < 0x1p-15f, "a one-frame packet after a muted loss fades in from silence: "
                + first);
    }
}
