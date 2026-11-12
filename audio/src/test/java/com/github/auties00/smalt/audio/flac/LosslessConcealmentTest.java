package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.AudioDecoder;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.alac.AlacDecoder;
import com.github.auties00.smalt.audio.alac.AlacEncoder;
import com.github.auties00.smalt.audio.alac.AlacEncoderOptions;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.AudioTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the waveform substitution concealment of the FLAC and ALAC decoders: the span is produced exactly, the
 * repetition of the last pitch periods joins the signal without clicks, and the packets after the one that follows
 * a loss decode bit for bit as without the loss.
 */
class LosslessConcealmentTest {
    static void check(AudioEncoder encoder, Function<AudioTrack, AudioDecoder> decoders, double seconds) {
        PacketLoss.Encoded encoded;
        try (encoder) {
            encoded = PacketLoss.encode(encoder, PacketLoss.signal(encoder.sampleRate(), encoder.layout().channels(),
                    seconds));
        }
        var packets = encoded.packets();
        // the first 10 ms repeat the last pitch period at full gain, so they keep the level of the signal
        var bounds = new PacketLoss.Bounds(true, 2.0, 6, encoded.track().sampleRate() / 100, 10, 1, 200);
        for (var lost : List.of(PacketLoss.bursts(5, 1, 12, 2), PacketLoss.random(packets.size(), packets.size(), 15,
                1.5, 2, 3))) {
            PacketLoss.assertConceals(() -> decoders.apply(encoded.track()), packets, lost, bounds);
        }
    }

    @ParameterizedTest(name = "FLAC {0} Hz {1} channels {2} bits, blocks of {3}")
    @CsvSource({"44100, 2, 16, 1152", "48000, 1, 24, 4096", "16000, 1, 16, 320", "96000, 2, 24, 2048"})
    @DisplayName("conceals FLAC blocks")
    void flac(int rate, int channels, int bits, int blockSize) {
        var options = FlacEncoderOptions.of(rate, ChannelLayout.defaultFor(channels), bits).toBuilder()
                .blockSize(blockSize).build();
        check(new FlacEncoder(options), FlacDecoder::new, 3);
    }

    @ParameterizedTest(name = "ALAC {0} Hz {1} channels {2} bits, frames of {3}")
    @CsvSource({"44100, 2, 16, 4096", "48000, 1, 24, 1024"})
    @DisplayName("conceals ALAC frames")
    void alac(int rate, int channels, int bits, int frameLength) {
        var options = AlacEncoderOptions.of(rate, ChannelLayout.defaultFor(channels), bits).toBuilder()
                .frameLength(frameLength).build();
        check(new AlacEncoder(options), AlacDecoder::new, 4);
    }

    @Test
    @DisplayName("conceals with silence before the first FLAC block, in the declared format")
    void beforeFirst() {
        PacketLoss.Encoded encoded;
        try (var encoder = new FlacEncoder(FlacEncoderOptions.of(22050, ChannelLayout.STEREO, 16))) {
            encoded = PacketLoss.encode(encoder, PacketLoss.signal(22050, 2, 0.3));
        }
        var decoder = new FlacDecoder(encoded.track());
        var handed = new int[1];
        assertEquals(5000, decoder.conceal(5000, buffer -> {
            assertEquals(22050, buffer.sampleRate());
            assertEquals(2, buffer.channels());
            handed[0] += buffer.frames();
            for (var i = 0; i < buffer.frames(); i++) {
                assertEquals(0, buffer.plane(1)[i]);
            }
        }));
        assertEquals(5000, handed[0]);
        assertTrue(decoder.supportsConcealment());
    }
}
