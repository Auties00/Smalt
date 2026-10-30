package com.github.auties00.smalt.audio.g726;

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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips of the G.726 encoder and decoder at every code size: the decoder must reproduce the encoder's own
 * reconstruction (both run the Recommendation's state machine, which {@link G726ConformanceTest} checks), the codes
 * must pack from the most significant bit with a final byte padded with zero bits, and the stream must survive WAV.
 */
@DisplayName("G.726 encoder and decoder")
class G726CodecTest {
    private static AudioTrack track(int codeSize) {
        return AudioTrack.builder().id(1).codec(Codec.ADPCM_G726).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).layout(ChannelLayout.MONO).bitsPerSample(codeSize).build();
    }

    /** Runs the coder on 16-bit samples, returning the reconstruction its decoder must output. */
    private static short[] reconstruction(int codeSize, int[] samples) {
        var codec = new G726Codec(codeSize);
        var out = new short[samples.length];
        for (var i = 0; i < samples.length; i++) {
            codec.estimate();
            var code = codec.quantize(samples[i] >> 2);
            out[i] = (short) G726Decoder.toLinear(codec.update(code));
        }
        return out;
    }

    @ParameterizedTest(name = "{0}-bit codes, at least {1} dB")
    @CsvSource({"2, 4", "3, 9", "4, 14", "5, 19"})
    @DisplayName("decodes its own stream to the encoder's reconstruction, near the input")
    void roundTrip(int codeSize, double minimumSnr) {
        var input = TelephonyStreams.signal(1, 8000, 16000, 0.7);
        var encoder = new G726Encoder(G726EncoderOptions.ofCodeSize(codeSize));
        assertEquals(codeSize, encoder.bitsPerSample());
        var packets = TelephonyStreams.encode(encoder, input);
        var decoder = AudioDecoders.create(TelephonyStreams.track(encoder));
        assertInstanceOf(G726Decoder.class, decoder);
        var decoded = TelephonyStreams.decodeShorts(decoder, packets);
        assertEquals(16000, decoded.length);
        assertArrayEquals(reconstruction(codeSize, input[0]), decoded);
        var snr = TelephonyStreams.snr(TelephonyStreams.interleave(input), decoded);
        assertTrue(snr >= minimumSnr, codeSize + "-bit SNR " + snr);
    }

    @Test
    @DisplayName("packs codes from the most significant bit and pads the final byte with zero bits")
    void packing() {
        var encoder = new G726Encoder(G726EncoderOptions.builder().codeSize(3).packetSamples(8).build());
        var input = new int[][]{TelephonyStreams.signal(1, 8000, 11, 0.5)[0]};
        var packets = TelephonyStreams.encode(encoder, input);
        assertEquals(2, packets.size());
        assertEquals(3, packets.get(0).size());
        assertEquals(8, packets.get(0).duration());
        // 3 codes of 3 bits: 9 bits in 2 bytes, the last 7 bits zero
        assertEquals(2, packets.get(1).size());
        assertEquals(3, packets.get(1).duration());
        assertEquals(0, packets.get(1).data()[1] & 0x7F);
        var codec = new G726Codec(3);
        var codes = new int[11];
        for (var i = 0; i < 11; i++) {
            codec.estimate();
            codes[i] = codec.quantize(input[0][i] >> 2);
            codec.update(codes[i]);
        }
        var first = packets.get(0).data();
        var bits = (first[0] & 0xFF) << 16 | (first[1] & 0xFF) << 8 | first[2] & 0xFF;
        for (var i = 0; i < 8; i++) {
            assertEquals(codes[i], bits >>> (21 - 3 * i) & 7, "code " + i);
        }
    }

    @ParameterizedTest(name = "{0} bit/s")
    @ValueSource(ints = {16000, 24000, 32000, 40000})
    @DisplayName("the codec-independent bitrate picks the code size")
    void bitrate(int bitrate) {
        var encoder = AudioEncoders.create(Codec.ADPCM_G726, AudioEncoderOptions.builder()
                .format(8000, ChannelLayout.MONO).bitrate(bitrate).build());
        assertEquals(bitrate / 8000, encoder.bitsPerSample());
    }

    @ParameterizedTest(name = "{0}-bit codes")
    @ValueSource(ints = {2, 3, 4, 5})
    @DisplayName("a round trip through WAV keeps every code and the code size")
    void wav(int codeSize) {
        var encoder = new G726Encoder(G726EncoderOptions.ofCodeSize(codeSize));
        var packets = TelephonyStreams.encode(encoder, TelephonyStreams.signal(1, 8000, 4000, 0.5));
        var file = TelephonyStreams.mux(ContainerFormat.WAV, TelephonyStreams.track(encoder), packets);
        var back = TelephonyStreams.demux(file);
        assertEquals(Codec.ADPCM_G726, back.track().codec());
        assertEquals(codeSize, back.track().bitsPerSample());
        var expected = TelephonyStreams.decodeShorts(new G726Decoder(track(codeSize)), packets);
        var actual = TelephonyStreams.decodeShorts(new G726Decoder(back.track()), back.packets());
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("decodes the bits after the last whole code of a packet as nothing")
    void trailingBits() {
        var decoder = new G726Decoder(track(5));
        var samples = TelephonyStreams.decodeShorts(decoder, List.of(new Packet(1, new byte[2], 0, 0, 3, true)));
        assertEquals(3, samples.length);
    }

    @Test
    @DisplayName("rejects tracks without a code size or of several channels")
    void invalidTracks() {
        assertThrows(MediaException.Malformed.class, () -> new G726Decoder(track(0)));
        assertThrows(MediaException.Malformed.class, () -> new G726Decoder(track(6)));
        assertThrows(MediaException.Unsupported.class, () -> new G726Decoder(track(4).toBuilder()
                .layout(ChannelLayout.STEREO).build()));
        assertThrows(IllegalArgumentException.class, () -> G726EncoderOptions.ofCodeSize(1));
        assertThrows(IllegalArgumentException.class, () -> G726EncoderOptions.builder().packetSamples(12));
    }

    @Test
    @DisplayName("options round trip through their builder")
    void options() {
        var options = G726EncoderOptions.builder().sampleRate(16000).codeSize(2).packetSamples(80).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(new ArrayList<>(List.of(16000, 2, 80)), new ArrayList<>(List.of(options.sampleRate(),
                options.codeSize(), options.packetSamples())));
        assertEquals(G726EncoderOptions.defaults(), G726EncoderOptions.ofCodeSize(4));
    }
}
