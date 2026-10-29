package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.testkit.audio.TargetBitrate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link AacEncoder#setTargetBitrate(long)}: a constant bitrate stream averages each new target within 10%
 * once its bit reservoir settled, a variable bitrate stream moves its rate with the nominal one, the automatic
 * bandwidth and the options follow the target, the retargeted stream decodes, and targets outside the format's range
 * are rejected.
 */
class AacTargetBitrateTest {
    @Test
    @DisplayName("averages each target of a constant bitrate schedule within 10%")
    void constant() {
        try (var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 128000))) {
            TargetBitrate.assertFollows(encoder, PacketLoss.signal(48000, 2, 12), 0.5, 0.1,
                    new TargetBitrate.Phase(3, 0), new TargetBitrate.Phase(3, 48000),
                    new TargetBitrate.Phase(3, 192000), new TargetBitrate.Phase(3, 32000));
            assertEquals(32000, encoder.options().bitrate());
            assertTrue(encoder.options().effectiveBandwidth() < AacEncoderOptions.of(48000, ChannelLayout.STEREO,
                    128000).effectiveBandwidth(), "the automatic bandwidth follows the bitrate");
        }
    }

    @Test
    @DisplayName("moves a variable bitrate stream with its nominal bitrate")
    void variable() {
        var options = AacEncoderOptions.of(44100, ChannelLayout.STEREO, 96000).toBuilder()
                .bitrateMode(AacBitrateMode.VBR).build();
        try (var encoder = new AacEncoder(options)) {
            var measured = TargetBitrate.assertFollows(encoder, PacketLoss.signal(44100, 2, 9), 0.5, 10,
                    new TargetBitrate.Phase(3, 0), new TargetBitrate.Phase(3, 48000), new TargetBitrate.Phase(3,
                            160000));
            assertTrue(measured[1] < measured[0] && measured[0] < measured[2], "the rate follows the nominal one: "
                    + java.util.Arrays.toString(measured));
        }
    }

    @Test
    @DisplayName("decodes every block of a retargeted stream and rejects targets the format cannot take")
    void decodesAndRejects() {
        var encoded = new java.util.ArrayList<com.github.auties00.smalt.track.Packet>();
        var signal = PacketLoss.signal(32000, 1, 2);
        try (var encoder = new AacEncoder(AacEncoderOptions.of(32000, ChannelLayout.MONO, 64000))) {
            var half = new float[][]{java.util.Arrays.copyOf(signal[0], 32000)};
            var rest = new float[][]{java.util.Arrays.copyOfRange(signal[0], 32000, 64000)};
            encoder.encode(new com.github.auties00.smalt.audio.AudioBuffer(ChannelLayout.MONO, 32000, half, 32000),
                    encoded::add);
            encoder.setTargetBitrate(16000);
            encoder.encode(new com.github.auties00.smalt.audio.AudioBuffer(ChannelLayout.MONO, 32000, rest, 32000),
                    encoded::add);
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(1000));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(10_000_000));
            assertEquals(16000, encoder.options().bitrate());
            encoder.flush(encoded::add);
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(32000));
            var track = com.github.auties00.smalt.track.AudioTrack.builder().id(1)
                    .codec(com.github.auties00.smalt.track.Codec.AAC)
                    .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(32000)).sampleRate(32000)
                    .codecConfig(encoder.codecConfig().orElseThrow()).build();
            var decoded = PacketLoss.decode(new AacDecoder(track), encoded);
            assertEquals(encoded.size() * 1024, decoded.length());
        }
    }
}
