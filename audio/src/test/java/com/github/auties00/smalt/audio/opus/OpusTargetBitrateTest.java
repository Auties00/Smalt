package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.testkit.audio.TargetBitrate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that {@link OpusEncoder#setTargetBitrate(long)} retargets a running stream: through a schedule of falling
 * and rising targets, the bitrate of the packets coded after each change is within 10% of the new target, in the
 * constant and the constrained variable bitrate modes.
 */
class OpusTargetBitrateTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = OpusBitrateMode.class, names = {"CBR", "CONSTRAINED_VBR"})
    @DisplayName("follows a schedule of targets within 10% after 200 ms")
    void schedule(OpusBitrateMode mode) {
        var options = OpusEncoderOptions.defaults().toBuilder().format(48000, 2).application(OpusApplication.AUDIO)
                .bitrate(96000).bitrateMode(mode).frameDurationMicros(20_000).build();
        try (var encoder = new OpusEncoder(options)) {
            TargetBitrate.assertFollows(encoder, PacketLoss.signal(48000, 2, 6), 0.2, 0.1,
                    new TargetBitrate.Phase(1.5, 0), new TargetBitrate.Phase(1.5, 32000),
                    new TargetBitrate.Phase(1.5, 128000), new TargetBitrate.Phase(1.5, 16000));
        }
    }

    @Test
    @DisplayName("takes the target as the explicit bitrate and rejects one that is not a positive int")
    void bounds() {
        try (var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(16000, 1))) {
            encoder.setTargetBitrate(12000);
            assertEquals(12000, encoder.bitrate());
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(1L << 31));
        }
    }
}
