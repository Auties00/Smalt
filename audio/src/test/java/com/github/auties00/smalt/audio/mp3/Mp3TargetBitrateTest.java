package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.testkit.audio.TargetBitrate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks {@link Mp3Encoder#setTargetBitrate(long)}: constant bitrate frames step to the version's bitrate at or below
 * the target, average bitrate streams steer their noise level to the new average within 10%, the automatic
 * bandwidth and the options follow, every frame still decodes, and variable bitrate streams, which have no target,
 * reject the call.
 */
class Mp3TargetBitrateTest {
    @Test
    @DisplayName("steps constant bitrate frames to the bitrate at or below each target")
    void constant() {
        var options = Mp3EncoderOptions.builder().format(44100, ChannelLayout.STEREO).cbr(128000).build();
        try (var encoder = new Mp3Encoder(options)) {
            TargetBitrate.assertFollows(encoder, PacketLoss.signal(44100, 2, 6), 0.1, 0.02,
                    new TargetBitrate.Phase(1.5, 0), new TargetBitrate.Phase(1.5, 64000),
                    new TargetBitrate.Phase(1.5, 192000), new TargetBitrate.Phase(1.5, 40000));
            encoder.setTargetBitrate(70000);
            assertEquals(64000, encoder.options().bitrate());
            encoder.setTargetBitrate(1000);
            assertEquals(32000, encoder.options().bitrate());
        }
    }

    @Test
    @DisplayName("steers average bitrate streams to each new average within 10%")
    void average() {
        var options = Mp3EncoderOptions.builder().format(44100, ChannelLayout.STEREO).abr(160000).build();
        try (var encoder = new Mp3Encoder(options)) {
            TargetBitrate.assertFollows(encoder, PacketLoss.signal(44100, 2, 12), 1.0, 0.1,
                    new TargetBitrate.Phase(3, 0), new TargetBitrate.Phase(3, 96000),
                    new TargetBitrate.Phase(3, 192000), new TargetBitrate.Phase(3, 128000));
            encoder.setTargetBitrate(1_000_000);
            assertEquals(320000, encoder.options().bitrate());
        }
    }

    @Test
    @DisplayName("decodes every frame of a stream whose bitrate changed")
    void decodes() {
        var options = Mp3EncoderOptions.builder().format(24000, ChannelLayout.MONO).cbr(64000).build();
        try (var encoder = new Mp3Encoder(options)) {
            var signal = PacketLoss.signal(24000, 1, 2);
            var first = new float[][]{java.util.Arrays.copyOf(signal[0], 24000)};
            var second = new float[][]{java.util.Arrays.copyOfRange(signal[0], 24000, 48000)};
            var packets = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
            var buffer = new com.github.auties00.smalt.audio.AudioBuffer(ChannelLayout.MONO, 24000, first, 24000);
            encoder.encode(buffer, packets::add);
            encoder.setTargetBitrate(24000);
            encoder.encode(new com.github.auties00.smalt.audio.AudioBuffer(ChannelLayout.MONO, 24000, second, 24000),
                    packets::add);
            encoder.flush(packets::add);
            var track = com.github.auties00.smalt.track.AudioTrack.builder().id(1)
                    .codec(com.github.auties00.smalt.track.Codec.MP3)
                    .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(24000)).sampleRate(24000)
                    .layout(ChannelLayout.MONO).build();
            var decoded = PacketLoss.decode(new Mp3Decoder(track), packets);
            assertEquals(packets.size() * 576, decoded.length());
            var sizes = packets.stream().mapToInt(com.github.auties00.smalt.track.Packet::size).distinct().count();
            assertEquals(true, sizes >= 2, "the frame size changed");
        }
    }

    @Test
    @DisplayName("rejects a change in variable bitrate mode and a bitrate that is not positive")
    void rejected() {
        try (var encoder = new Mp3Encoder(Mp3EncoderOptions.builder().format(44100, ChannelLayout.STEREO).vbr(4)
                .build())) {
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(128000));
        }
        try (var encoder = new Mp3Encoder()) {
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
        }
    }
}
