package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on the AAC options types: defaults and presets, builders that
 * leave the original untouched and round-trip through {@code toBuilder()}, value equality,
 * validation, and the decoder limits.
 */
@DisplayName("AAC options")
class AacOptionsTest {
    @Nested
    @DisplayName("AacEncoderOptions")
    class Encoder {
        @Test
        @DisplayName("defaults() is 48 kHz stereo at 128 kb/s CBR with the automatic bandwidth")
        void defaults() {
            var defaults = AacEncoderOptions.defaults();
            assertEquals(48_000, defaults.sampleRate());
            assertEquals(ChannelLayout.STEREO, defaults.layout());
            assertEquals(128_000, defaults.bitrate());
            assertEquals(AacBitrateMode.CBR, defaults.bitrateMode());
            assertEquals(OptionalInt.empty(), defaults.bandwidth());
            assertEquals(defaults, AacEncoderOptions.of(48_000, ChannelLayout.STEREO, 128_000));
            assertEquals(defaults, AacEncoderOptions.builder().build());
        }

        @Test
        @DisplayName("the builder changes settings and leaves the original untouched")
        void changes() {
            var base = AacEncoderOptions.defaults();
            var changed = base.toBuilder().format(44_100, ChannelLayout.MONO)
                    .bitrate(64_000)
                    .bitrateMode(AacBitrateMode.VBR)
                    .bandwidth(15_000).build();
            assertEquals(AacEncoderOptions.defaults(), base);
            assertEquals(44_100, changed.sampleRate());
            assertEquals(ChannelLayout.MONO, changed.layout());
            assertEquals(64_000, changed.bitrate());
            assertEquals(AacBitrateMode.VBR, changed.bitrateMode());
            assertEquals(OptionalInt.of(15_000), changed.bandwidth());
            assertEquals(15_000, changed.effectiveBandwidth());
            assertEquals(OptionalInt.empty(), changed.toBuilder().automaticBandwidth().build().bandwidth());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertEquals(changed, AacEncoderOptions.builder().format(44_100, ChannelLayout.MONO).bitrate(64_000)
                    .bitrateMode(AacBitrateMode.VBR).bandwidth(15_000).build());
            assertNotEquals(base, changed);
        }

        @Test
        @DisplayName("checks the bitrate and the bandwidth against the format")
        void validation() {
            var defaults = AacEncoderOptions.defaults();
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().format(44_000, ChannelLayout.STEREO).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().format(48_000, ChannelLayout.defaultFor(3)).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().format(8_000, ChannelLayout.STEREO).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().bitrate(11_999).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().bitrate(0).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().bandwidth(24_001).build());
            assertThrows(IllegalArgumentException.class, () -> defaults.toBuilder().bandwidth(0).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().bitrateMode(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().format(48_000, null).build());
        }
    }

    @Nested
    @DisplayName("AacDecoderOptions")
    class Decoder {
        private static final MediaLimits MONO = MediaLimits.builder().maxChannels(1).build();

        @Test
        @DisplayName("defaults, builders and equality follow the options convention")
        void convention() {
            var defaults = AacDecoderOptions.defaults();
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            var mono = defaults.toBuilder().limits(MONO).build();
            assertEquals(mono, AacDecoderOptions.builder().limits(MONO).build());
            assertEquals(mono, mono.toBuilder().build());
            assertNotEquals(defaults, mono);
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().limits(null).build());
        }

        @Test
        @DisplayName("enables the HE-AAC extensions by default and turns them off through the builder")
        void spectralBandReplication() {
            var defaults = AacDecoderOptions.defaults();
            assertTrue(defaults.spectralBandReplication());
            var off = defaults.toBuilder().spectralBandReplication(false).build();
            assertFalse(off.spectralBandReplication());
            assertTrue(defaults.spectralBandReplication(), "the original is untouched");
            assertEquals(off, AacDecoderOptions.builder().spectralBandReplication(false).build());
            assertEquals(off, off.toBuilder().build());
            assertEquals(off.hashCode(), off.toBuilder().build().hashCode());
            assertNotEquals(defaults, off);
            assertEquals(defaults, off.toBuilder().spectralBandReplication(true).build());
            assertEquals(MONO, off.toBuilder().limits(MONO).build().limits());
            assertFalse(off.toBuilder().limits(MONO).build().spectralBandReplication());
        }

        @Test
        @DisplayName("checks the configured channel count against the limits")
        void limits() {
            var config = new AacEncoder(AacEncoderOptions.defaults()).codecConfig().orElseThrow();
            var track = AacDecoderRobustnessTest.track(config);
            assertEquals(2, new AacDecoder(track, AacDecoderOptions.defaults()).layout().channels());
            assertThrows(MediaException.LimitExceeded.class,
                    () -> new AacDecoder(track, AacDecoderOptions.defaults().toBuilder().limits(MONO).build()));
        }
    }
}
