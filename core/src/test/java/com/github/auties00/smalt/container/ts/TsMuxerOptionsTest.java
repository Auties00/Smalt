package com.github.auties00.smalt.container.ts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers {@link TsMuxerOptions.Builder#noMuxRate()}.
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
}
