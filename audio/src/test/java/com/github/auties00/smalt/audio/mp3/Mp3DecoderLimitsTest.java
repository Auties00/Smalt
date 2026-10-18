package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the {@link AudioDecoderOptions#limits()} a {@link Mp3Decoder} enforces.
 */
@DisplayName("Mp3Decoder limits")
class Mp3DecoderLimitsTest {
    private static final MediaLimits MONO = MediaLimits.builder().maxChannels(1).build();

    // MPEG-1 Layer III, 128 kb/s, 44.1 kHz, stereo, no CRC: 417 bytes, all-zero side information
    private static Packet stereoFrame() {
        var frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) 0x90;
        return new Packet(0, frame, 0, 0, 0, true);
    }

    private static AudioTrack track() {
        return AudioTrack.builder().codec(Codec.MP3).timeBase(Rational.timeBaseOf(44100)).build();
    }

    @Test
    @DisplayName("checks the channel count of every frame against the limits")
    void limits() {
        try (var decoder = new Mp3Decoder(track(), AudioDecoderOptions.defaults())) {
            var frames = new int[1];
            decoder.decode(stereoFrame(), buffer -> frames[0] += buffer.frames());
            assertEquals(1152, frames[0]);
            assertEquals(2, decoder.layout().channels());
        }
        try (var decoder = new Mp3Decoder(track(), AudioDecoderOptions.builder().limits(MONO).build())) {
            assertThrows(MediaException.LimitExceeded.class, () -> decoder.decode(stereoFrame(), buffer -> {
            }));
        }
    }
}
