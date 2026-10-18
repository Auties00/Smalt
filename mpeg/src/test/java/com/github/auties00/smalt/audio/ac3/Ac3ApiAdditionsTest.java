package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the AC-3 options API: the separate light compression scale setters, validated by
 * {@code build()}, the {@code automaticBitrate()} resets, and the equality of options that set the
 * bit rate against options that leave it to the layout.
 */
@DisplayName("AC-3 API additions")
class Ac3ApiAdditionsTest {
    @Nested
    @DisplayName("Ac3DecoderOptions.Builder cutScale and boostScale")
    class Scales {
        @Test
        @DisplayName("set each scale alone")
        void separate() {
            var options = Ac3DecoderOptions.builder().cutScale(0.25f).boostScale(0.75f).build();
            assertEquals(0.25f, options.cutScale());
            assertEquals(0.75f, options.boostScale());
            var cutOnly = Ac3DecoderOptions.defaults().toBuilder().cutScale(0.5f).build();
            assertEquals(0.5f, cutOnly.cutScale());
            assertEquals(Ac3DecoderOptions.defaults().boostScale(), cutOnly.boostScale());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(floats = {-0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY})
        @DisplayName("build() rejects a scale outside 0 to 1")
        void rejects(float scale) {
            var cut = Ac3DecoderOptions.builder().cutScale(scale);
            assertThrows(IllegalArgumentException.class, cut::build);
            var boost = Ac3DecoderOptions.builder().boostScale(scale);
            assertThrows(IllegalArgumentException.class, boost::build);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(floats = {0f, 1f})
        @DisplayName("build() accepts the ends of the range")
        void accepts(float scale) {
            var options = Ac3DecoderOptions.builder().cutScale(scale).boostScale(scale).build();
            assertEquals(scale, options.cutScale());
            assertEquals(scale, options.boostScale());
        }
    }

    @Test
    @DisplayName("Ac3EncoderOptions.Builder.automaticBitrate() restores the layout's default bit rate")
    void ac3AutomaticBitrate() {
        var configured = Ac3EncoderOptions.builder().format(48_000, ChannelLayout.SURROUND_5_1).bitrate(640_000)
                .build();
        var reset = configured.toBuilder().automaticBitrate().build();
        assertEquals(OptionalInt.empty(), reset.bitrate());
        assertEquals(448_000, reset.effectiveBitrate());
        assertEquals(Ac3EncoderOptions.builder().format(48_000, ChannelLayout.SURROUND_5_1).build(), reset);
    }

    @Test
    @DisplayName("Eac3EncoderOptions.Builder.automaticBitrate() restores the layout's default bit rate")
    void eac3AutomaticBitrate() {
        var configured = Eac3EncoderOptions.builder().format(48_000, ChannelLayout.STEREO).bitrate(256_000).build();
        var reset = configured.toBuilder().automaticBitrate().build();
        assertEquals(OptionalInt.empty(), reset.bitrate());
        assertEquals(192_000, reset.effectiveBitrate());
        assertEquals(Eac3EncoderOptions.builder().format(48_000, ChannelLayout.STEREO).build(), reset);
    }

    @Test
    @DisplayName("Ac3EncoderOptions with an explicit bit rate differ from options leaving it to the layout")
    void ac3ExplicitBitrateEquality() {
        var explicit = Ac3EncoderOptions.builder().bitrate(192_000).build();
        var automatic = Ac3EncoderOptions.defaults();
        assertEquals(automatic.effectiveBitrate(), explicit.effectiveBitrate());
        assertEquals(OptionalInt.of(192_000), explicit.bitrate());
        assertNotEquals(automatic, explicit);
        var surround = explicit.toBuilder().format(48_000, ChannelLayout.SURROUND_5_1).build();
        assertNotEquals(automatic.toBuilder().format(48_000, ChannelLayout.SURROUND_5_1).build().effectiveBitrate(),
                surround.effectiveBitrate());
        assertEquals(explicit, Ac3EncoderOptions.builder().bitrate(192_000).build());
        assertEquals(explicit.hashCode(), Ac3EncoderOptions.builder().bitrate(192_000).build().hashCode());
    }

    @Test
    @DisplayName("Eac3EncoderOptions with an explicit bit rate differ from options leaving it to the layout")
    void eac3ExplicitBitrateEquality() {
        var explicit = Eac3EncoderOptions.builder().bitrate(192_000).build();
        var automatic = Eac3EncoderOptions.defaults();
        assertEquals(automatic.effectiveBitrate(), explicit.effectiveBitrate());
        assertEquals(OptionalInt.of(192_000), explicit.bitrate());
        assertNotEquals(automatic, explicit);
        var surround = explicit.toBuilder().format(48_000, ChannelLayout.SURROUND_5_1).build();
        assertNotEquals(automatic.toBuilder().format(48_000, ChannelLayout.SURROUND_5_1).build().effectiveBitrate(),
                surround.effectiveBitrate());
        assertEquals(explicit, Eac3EncoderOptions.builder().bitrate(192_000).build());
        assertEquals(explicit.hashCode(), Eac3EncoderOptions.builder().bitrate(192_000).build().hashCode());
    }
}
