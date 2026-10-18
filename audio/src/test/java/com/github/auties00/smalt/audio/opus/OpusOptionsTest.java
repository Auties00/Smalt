package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the options convention on the Opus options types: defaults and presets, withers that
 * leave the original untouched, builders that round-trip through {@code toBuilder()}, value
 * equality, and validation.
 */
@DisplayName("Opus options")
class OpusOptionsTest {
    private static final MediaLimits SMALL = MediaLimits.builder().maxChannels(2).build();

    @Nested
    @DisplayName("OpusEncoderOptions")
    class Encoder {
        @Test
        @DisplayName("defaults() codes 48 kHz stereo audio at the automatic bitrate, VBR, complexity 10, 20 ms")
        void defaults() {
            var defaults = OpusEncoderOptions.defaults();
            assertEquals(48_000, defaults.sampleRate());
            assertEquals(2, defaults.channels());
            assertEquals(OpusApplication.AUDIO, defaults.application());
            assertEquals(OptionalInt.empty(), defaults.bitrate());
            assertEquals(OpusBitrateMode.VBR, defaults.bitrateMode());
            assertEquals(10, defaults.complexity());
            assertEquals(20_000, defaults.frameDurationMicros());
            assertEquals(960, defaults.frameSize());
            assertEquals(OpusSignal.AUTO, defaults.signal());
            assertEquals(OpusBandwidth.FULLBAND, defaults.maxBandwidth());
            assertFalse(defaults.inbandFec());
            assertEquals(0, defaults.packetLossPercent());
            assertFalse(defaults.dtx());
            assertEquals(defaults, OpusEncoderOptions.builder().build());
        }

        @Test
        @DisplayName("Builder.format(rate, layout) keeps the layout's channel count and rejects other counts")
        void formatLayout() {
            assertEquals(OpusEncoderOptions.builder().format(16_000, 1).build(),
                    OpusEncoderOptions.builder().format(16_000, ChannelLayout.MONO).build());
            assertEquals(OpusEncoderOptions.forRealtime().toBuilder().format(24_000, 2).build(),
                    OpusEncoderOptions.forRealtime().toBuilder().format(24_000, ChannelLayout.STEREO).build());
            assertThrows(IllegalArgumentException.class,
                    () -> OpusEncoderOptions.builder().format(48_000, ChannelLayout.SURROUND_5_1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> OpusEncoderOptions.builder().format(44_100, ChannelLayout.STEREO).build());
            assertThrows(NullPointerException.class,
                    () -> OpusEncoderOptions.builder().format(48_000, (ChannelLayout) null));
        }

        @Test
        @DisplayName("the no-argument encoder uses defaults()")
        void defaultEncoder() {
            assertEquals(OpusEncoderOptions.defaults(), new OpusEncoder().options());
        }

        @Test
        @DisplayName("forRealtime() and forVoip() are the settings of a new libopus encoder")
        void realtime() {
            var realtime = OpusEncoderOptions.forRealtime();
            assertEquals(OpusEncoderOptions.defaults().toBuilder().bitrateMode(OpusBitrateMode.CONSTRAINED_VBR)
                    .complexity(9).build(),
                    realtime);
            var voip = OpusEncoderOptions.forVoip(16_000, 1);
            assertEquals(realtime.toBuilder().format(16_000, 1).application(OpusApplication.VOIP).build(), voip);
            assertEquals(320, voip.frameSize());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.forVoip(44_100, 1));
        }

        @Test
        @DisplayName("forSpeech(bitrate) codes 16 kHz mono VoIP at the bitrate")
        void speech() {
            var speech = OpusEncoderOptions.forSpeech(24_000);
            assertEquals(16_000, speech.sampleRate());
            assertEquals(1, speech.channels());
            assertEquals(OpusApplication.VOIP, speech.application());
            assertEquals(OptionalInt.of(24_000), speech.bitrate());
            assertEquals(OpusBitrateMode.VBR, speech.bitrateMode());
            assertEquals(10, speech.complexity());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.forSpeech(300_001));
        }

        @Test
        @DisplayName("withers change one setting and leave the original untouched")
        void withers() {
            var base = OpusEncoderOptions.defaults();
            var changed = base.toBuilder().format(24_000, 1)
                    .application(OpusApplication.RESTRICTED_LOWDELAY)
                    .bitrate(48_000)
                    .bitrateMode(OpusBitrateMode.CBR)
                    .complexity(3)
                    .frameDurationMicros(10_000)
                    .signal(OpusSignal.MUSIC)
                    .maxBandwidth(OpusBandwidth.SUPERWIDEBAND)
                    .inbandFec(true)
                    .packetLossPercent(12)
                    .dtx(true).build();
            assertEquals(OpusEncoderOptions.defaults(), base);
            assertEquals(24_000, changed.sampleRate());
            assertEquals(1, changed.channels());
            assertEquals(OpusApplication.RESTRICTED_LOWDELAY, changed.application());
            assertEquals(OptionalInt.of(48_000), changed.bitrate());
            assertEquals(OpusBitrateMode.CBR, changed.bitrateMode());
            assertEquals(3, changed.complexity());
            assertEquals(240, changed.frameSize());
            assertEquals(OpusSignal.MUSIC, changed.signal());
            assertEquals(OpusBandwidth.SUPERWIDEBAND, changed.maxBandwidth());
            assertTrue(changed.inbandFec());
            assertEquals(12, changed.packetLossPercent());
            assertTrue(changed.dtx());
            assertEquals(OptionalInt.empty(), changed.toBuilder().automaticBitrate().build().bitrate());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
        }

        @Test
        @DisplayName("checks the bitrate against the channel count")
        void bitrateFitsChannels() {
            var stereo = OpusEncoderOptions.defaults().toBuilder().bitrate(400_000).build();
            assertThrows(IllegalArgumentException.class, () -> stereo.toBuilder().format(48_000, 1).build());
            assertEquals(1, stereo.toBuilder().automaticBitrate().format(48_000, 1).build().channels());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"packet loss", "frame", "complexity", "bitrate"})
        @DisplayName("rejects out-of-range values")
        void rejects(String field) {
            var builder = OpusEncoderOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> {
                switch (field) {
                    case "packet loss" -> builder.packetLossPercent(101).build();
                    case "frame" -> builder.frameDurationMicros(15_000).build();
                    case "complexity" -> builder.complexity(-1).build();
                    default -> builder.bitrate(0).build();
                }
            });
        }

        @Test
        @DisplayName("rejects null enums")
        void nulls() {
            var defaults = OpusEncoderOptions.defaults();
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().application(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().bitrateMode(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().signal(null).build());
            assertThrows(NullPointerException.class, () -> defaults.toBuilder().maxBandwidth(null).build());
        }
    }

    @Nested
    @DisplayName("OpusDecoderOptions")
    class Decoder {
        @Test
        @DisplayName("defaults() decodes at 48 kHz into the stream's layout under the default limits")
        void defaults() {
            var defaults = OpusDecoderOptions.defaults();
            assertEquals(MediaLimits.DEFAULT, defaults.limits());
            assertEquals(48_000, defaults.sampleRate());
            assertEquals(OptionalInt.empty(), defaults.channels());
            assertEquals(defaults, OpusDecoderOptions.builder().build());
        }

        @Test
        @DisplayName("of(rate, channels), withers and builders agree")
        void withers() {
            var options = OpusDecoderOptions.of(16_000, 1).toBuilder().limits(SMALL).build();
            assertEquals(16_000, options.sampleRate());
            assertEquals(OptionalInt.of(1), options.channels());
            assertEquals(SMALL, options.limits());
            assertEquals(options, OpusDecoderOptions.builder().sampleRate(16_000).channels(1).limits(SMALL).build());
            assertEquals(options, options.toBuilder().build());
            assertEquals(OptionalInt.empty(), options.toBuilder().sourceChannels().build().channels());
            assertEquals(OptionalInt.of(2), options.toBuilder().channels(2).build().channels());
            assertEquals(OpusDecoderOptions.defaults(), OpusDecoderOptions.defaults().toBuilder().sampleRate(48_000)
                    .build());
            assertNotEquals(OpusDecoderOptions.defaults(), options);
        }

        @Test
        @DisplayName("Builder.sourceChannels() returns to the count the source declares")
        void sourceChannels() {
            var options = OpusDecoderOptions.of(16_000, 1);
            assertEquals(OptionalInt.empty(), options.toBuilder().sourceChannels().build().channels());
            assertEquals(OpusDecoderOptions.builder().sampleRate(16_000).build(),
                    options.toBuilder().sourceChannels().build());
        }

        @Test
        @DisplayName("rejects unsupported rates and channel counts and null limits")
        void rejects() {
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.of(44_100, 2));
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.of(48_000, 3));
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.defaults().toBuilder().channels(0)
                    .build());
            assertThrows(NullPointerException.class, () -> OpusDecoderOptions.defaults().toBuilder().limits(null)
                    .build());
        }
    }
}
