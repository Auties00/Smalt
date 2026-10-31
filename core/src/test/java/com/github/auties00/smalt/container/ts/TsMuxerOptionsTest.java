package com.github.auties00.smalt.container.ts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link TsMuxerOptions.Builder#noMuxRate()} and the timestamp origin settings.
 */
@DisplayName("TsMuxerOptions")
class TsMuxerOptionsTest {
    @Test
    @DisplayName("noMuxRate restores the variable rate")
    void noMuxRate() {
        var constant = TsMuxerOptions.builder().muxRate(2_000_000).build();
        assertEquals(2_000_000, constant.muxRate());
        var variable = constant.toBuilder().noMuxRate().build();
        assertEquals(0, variable.muxRate());
        assertEquals(TsMuxerOptions.defaults(), variable);
    }

    @Test
    @DisplayName("timestampOriginMicros sets the input time written as the delay, automaticTimestampOrigin resets it")
    void timestampOrigin() {
        assertTrue(TsMuxerOptions.defaults().timestampOriginMicros().isEmpty());
        var options = TsMuxerOptions.builder().timestampOriginMicros(-5).build();
        assertEquals(OptionalLong.of(-5), options.timestampOriginMicros());
        assertNotEquals(TsMuxerOptions.defaults(), options);
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
        assertTrue(options.toString().contains("timestampOriginMicros=-5"), options.toString());
        assertEquals(TsMuxerOptions.defaults(), options.toBuilder().automaticTimestampOrigin().build());
        assertThrows(IllegalArgumentException.class, () -> TsMuxerOptions.builder()
                .timestampOriginMicros(Long.MIN_VALUE));
    }
}
