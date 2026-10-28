package com.github.auties00.smalt.audio.g722;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.telephony.TelephonyStreams;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips of the G.722 encoder and decoder: the decode of the encoder's stream must follow the input, delayed by
 * the filters, the reduced modes must decode the same stream with its lowest bits ignored, and the stream must
 * survive WAV. The sub-band coders are checked bit for bit by {@link G722ConformanceTest}.
 */
@DisplayName("G.722 encoder and decoder")
class G722CodecTest {
    private static AudioTrack track(int bitsPerSample) {
        return AudioTrack.builder().id(1).codec(Codec.ADPCM_G722).timeBase(Rational.timeBaseOf(16000))
                .sampleRate(16000).layout(ChannelLayout.MONO).bitsPerSample(bitsPerSample).build();
    }

    /** Returns the best SNR of a decode against its input over the lags the filters' delay can take. */
    private static double alignedSnr(short[] input, short[] decoded) {
        var best = Double.NEGATIVE_INFINITY;
        for (var lag = 0; lag <= 48; lag++) {
            var shifted = Arrays.copyOfRange(decoded, lag, decoded.length);
            best = Math.max(best, TelephonyStreams.snr(Arrays.copyOf(input, shifted.length), shifted));
        }
        return best;
    }

    @Test
    @DisplayName("decodes its own stream close to the input")
    void roundTrip() {
        var input = TelephonyStreams.signal(1, 16000, 32000, 0.7);
        var encoder = new G722Encoder();
        var packets = TelephonyStreams.encode(encoder, input);
        assertEquals(100, packets.size());
        assertEquals(160, packets.getFirst().size());
        var decoder = AudioDecoders.create(TelephonyStreams.track(encoder));
        assertInstanceOf(G722Decoder.class, decoder);
        var decoded = TelephonyStreams.decodeShorts(decoder, packets);
        assertEquals(32000, decoded.length);
        var snr = alignedSnr(TelephonyStreams.interleave(input), decoded);
        assertTrue(snr >= 15, "SNR " + snr);
        for (var sample : decoded) {
            assertEquals(0, sample & 1, "the 15-bit output fills the top bits");
        }
    }

    @ParameterizedTest(name = "bitsPerSample {0} decodes as mode {1}")
    @CsvSource({"7, 2", "6, 3"})
    @DisplayName("the reduced modes ignore the auxiliary bits")
    void modes(int bitsPerSample, int mode) {
        var packets = TelephonyStreams.encode(new G722Encoder(), TelephonyStreams.signal(1, 16000, 3200, 0.6));
        var mask = mode == 2 ? 0xFE : 0xFC;
        var scrambled = packets.stream().map(packet -> {
            var data = packet.data().clone();
            for (var i = 0; i < data.length; i++) {
                data[i] = (byte) ((data[i] & mask) | (i * 7 & ~mask));
            }
            return packet.withData(data);
        }).toList();
        var clean = TelephonyStreams.decodeShorts(new G722Decoder(track(bitsPerSample)), packets);
        var withAux = TelephonyStreams.decodeShorts(new G722Decoder(track(bitsPerSample)), scrambled);
        assertArrayEquals(clean, withAux);
        var full = TelephonyStreams.decodeShorts(new G722Decoder(track(0)), packets);
        assertTrue(alignedSnr(full, clean) > 10, "a reduced mode stays close to the full decode");
    }

    @Test
    @DisplayName("outputs 16 kHz whatever rate the track declares")
    void rate() {
        var declared = track(8).toBuilder().sampleRate(8000).timeBase(Rational.timeBaseOf(8000)).build();
        assertEquals(16000, new G722Decoder(declared).sampleRate());
    }

    @Test
    @DisplayName("a round trip through WAV keeps every byte")
    void wav() {
        var encoder = new G722Encoder();
        var packets = TelephonyStreams.encode(encoder, TelephonyStreams.signal(1, 16000, 8001, 0.5));
        assertEquals(1, packets.getLast().duration());
        var file = TelephonyStreams.mux(ContainerFormat.WAV, TelephonyStreams.track(encoder), packets);
        var back = TelephonyStreams.demux(file);
        assertEquals(Codec.ADPCM_G722, back.track().codec());
        var expected = TelephonyStreams.decodeShorts(new G722Decoder(track(0)), packets);
        var actual = TelephonyStreams.decodeShorts(new G722Decoder(back.track()), back.packets());
        assertArrayEquals(Arrays.copyOf(expected, actual.length), actual);
    }

    @Test
    @DisplayName("accepts only 16 kHz mono input")
    void format() {
        assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.ADPCM_G722,
                AudioEncoderOptions.of(8000, ChannelLayout.MONO)));
        assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.ADPCM_G722,
                AudioEncoderOptions.of(16000, ChannelLayout.STEREO)));
        assertThrows(MediaException.Unsupported.class, () -> new G722Decoder(track(0).toBuilder()
                .layout(ChannelLayout.STEREO).build()));
        var samples = TelephonyStreams.decodeShorts(new G722Decoder(track(0)),
                List.of(new Packet(1, new byte[3], 0, 0, 6, true)));
        assertEquals(6, samples.length);
    }
}
