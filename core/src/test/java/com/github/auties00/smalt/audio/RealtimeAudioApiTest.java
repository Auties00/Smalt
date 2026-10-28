package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the codec-neutral real-time additions to the audio codec interfaces: a decoder that does not override
 * {@link AudioDecoder#conceal(int, Consumer)} reports no concealment and rejects the call as unsupported, and an
 * encoder that does not override {@link AudioEncoder#setTargetBitrate(long)} rejects a bitrate change the same way,
 * as {@code VideoEncoder} does.
 */
class RealtimeAudioApiTest {
    /** A decoder that implements only the abstract methods. */
    private static final class PlainDecoder implements AudioDecoder {
        @Override
        public int sampleRate() {
            return 8000;
        }

        @Override
        public ChannelLayout layout() {
            return ChannelLayout.MONO;
        }

        @Override
        public void decode(Packet packet, Consumer<AudioBuffer> output) {
        }

        @Override
        public void flush(Consumer<AudioBuffer> output) {
        }
    }

    @Test
    @DisplayName("a decoder without concealment reports none and rejects conceal as unsupported")
    void decoderDefault() {
        var decoder = new PlainDecoder();
        assertFalse(decoder.supportsConcealment());
        var failure = assertThrows(MediaException.Unsupported.class, () -> decoder.conceal(160, buffer -> { }));
        assertEquals("packet loss concealment", failure.format());
    }

    @Test
    @DisplayName("an encoder without live retargeting rejects setTargetBitrate as unsupported")
    void encoderDefault() {
        try (var encoder = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_S16LE, 8000, ChannelLayout.MONO))) {
            var failure = assertThrows(MediaException.Unsupported.class, () -> encoder.setTargetBitrate(64_000));
            assertEquals("bitrate change", failure.format());
        }
    }
}
