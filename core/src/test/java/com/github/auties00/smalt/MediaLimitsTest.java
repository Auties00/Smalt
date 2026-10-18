package com.github.auties00.smalt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the builder of {@link MediaLimits}: the defaults, round trips through
 * {@link MediaLimits#toBuilder()}, and validation in {@link MediaLimits.Builder#build()}.
 */
@DisplayName("MediaLimits")
class MediaLimitsTest {
    private static MediaLimits small() {
        return MediaLimits.builder()
                .maxDimension(64)
                .maxPixels(4096)
                .maxFrames(2)
                .maxChannels(3)
                .maxBytes(1 << 20)
                .build();
    }

    @Test
    @DisplayName("defaults() is the DEFAULT instance and builder() starts from it")
    void defaults() {
        assertSame(MediaLimits.DEFAULT, MediaLimits.defaults());
        assertEquals(MediaLimits.DEFAULT, MediaLimits.builder().build());
    }

    @Test
    @DisplayName("every bound is settable")
    void everyBound() {
        var limits = small();
        assertEquals(64, limits.maxDimension());
        assertEquals(4096, limits.maxPixels());
        assertEquals(2, limits.maxFrames());
        assertEquals(3, limits.maxChannels());
        assertEquals(1 << 20, limits.maxBytes());
    }

    @Test
    @DisplayName("toBuilder() round-trips and changes one bound at a time")
    void toBuilder() {
        var limits = small();
        assertEquals(limits, limits.toBuilder().build());
        var changed = limits.toBuilder().maxPixels(9000).build();
        assertEquals(9000, changed.maxPixels());
        assertEquals(limits.maxDimension(), changed.maxDimension());
        assertEquals(limits.maxFrames(), changed.maxFrames());
        assertEquals(limits.maxChannels(), changed.maxChannels());
        assertEquals(limits.maxBytes(), changed.maxBytes());
    }

    @ParameterizedTest(name = "rejects bound {0}")
    @ValueSource(strings = {"maxDimension", "maxPixels", "maxFrames", "maxChannels", "maxBytes"})
    @DisplayName("build() rejects a bound that is not positive")
    void rejectsNonPositive(String bound) {
        UnaryOperator<MediaLimits.Builder> invalid = switch (bound) {
            case "maxDimension" -> builder -> builder.maxDimension(0);
            case "maxPixels" -> builder -> builder.maxPixels(0);
            case "maxFrames" -> builder -> builder.maxFrames(-1);
            case "maxChannels" -> builder -> builder.maxChannels(0);
            default -> builder -> builder.maxBytes(-5);
        };
        var builder = invalid.apply(MediaLimits.builder());
        assertThrows(IllegalArgumentException.class, builder::build);
    }
}
