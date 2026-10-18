package com.github.auties00.smalt.internal.check;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the option checks and their single message format.
 */
class ChecksTest {
    @Test
    @DisplayName("returns the checked value")
    void returnsValue() {
        assertEquals(5, Checks.inRange("quality", 5, 0, 100));
        assertEquals(5L, Checks.inRange("bitrate", 5L, 1L, 10L));
        assertEquals(0.5, Checks.inRange("strength", 0.5, 0, 1));
        assertEquals(1, Checks.positive("threads", 1));
        assertEquals(0L, Checks.nonNegative("delay", 0L));
        var list = List.of(1);
        assertSame(list, Checks.notEmpty("modes", list));
        assertEquals(Duration.ZERO, Checks.nonNegative("buffer", Duration.ZERO));
    }

    @Test
    @DisplayName("names the setting, the value and the rule in every message")
    void messages() {
        assertMessage("quality 120 is not in [0, 100]", () -> Checks.inRange("quality", 120, 0, 100));
        assertMessage("bitrate -1 is not in [1, 10]", () -> Checks.inRange("bitrate", -1L, 1L, 10L));
        assertMessage("strength 1.5 is not in [0.0, 1.0]", () -> Checks.inRange("strength", 1.5, 0, 1));
        assertMessage("strength NaN is not in [0.0, 1.0]", () -> Checks.inRange("strength", Double.NaN, 0, 1));
        assertMessage("buffer PT2S is not in [PT0S, PT1S]",
                () -> Checks.inRange("buffer", Duration.ofSeconds(2), Duration.ZERO, Duration.ofSeconds(1)));
        assertMessage("thread count 0 is not positive", () -> Checks.positive("thread count", 0));
        assertMessage("bitrate -5 is not positive", () -> Checks.positive("bitrate", -5L));
        assertMessage("gain NaN is not positive", () -> Checks.positive("gain", Double.NaN));
        assertMessage("gain NaN is not a number", () -> Checks.nonNegative("gain", Double.NaN));
        assertMessage("interval PT0S is not positive", () -> Checks.positive("interval", Duration.ZERO));
        assertMessage("delay -1 is negative", () -> Checks.nonNegative("delay", -1));
        assertMessage("delay -1 is negative", () -> Checks.nonNegative("delay", -1L));
        assertMessage("pad -0.5 is negative", () -> Checks.nonNegative("pad", -0.5));
        assertMessage("lead PT-1S is negative", () -> Checks.nonNegative("lead", Duration.ofSeconds(-1)));
        assertMessage("modes is empty", () -> Checks.notEmpty("modes", List.of()));
        assertMessage("name is empty", () -> Checks.notEmpty("name", ""));
        assertMessage("key is empty", () -> Checks.notEmpty("key", new byte[0]));
    }

    private static void assertMessage(String message, Executable check) {
        assertEquals(message, assertThrows(IllegalArgumentException.class, check).getMessage());
    }
}
