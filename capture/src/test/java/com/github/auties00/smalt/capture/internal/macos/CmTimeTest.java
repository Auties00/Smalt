package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CMTime")
class CmTimeTest {
    @Test
    @DisplayName("round-trips through native memory field by field")
    void roundTrip() {
        try (var arena = Arena.ofConfined()) {
            var time = new CmTime(-123_456_789_012L, 90_000, CmTime.VALID | 2, 7);
            var segment = time.allocate(arena);
            assertEquals(-123_456_789_012L, segment.get(JAVA_LONG, 0));
            assertEquals(90_000, segment.get(JAVA_INT, 8));
            assertEquals(3, segment.get(JAVA_INT, 12));
            assertEquals(7, segment.get(JAVA_LONG, 16));
            assertEquals(time, CmTime.read(segment));
        }
    }

    @ParameterizedTest(name = "flags {0}, timescale {1} -> {2}")
    @CsvSource({"1,600,true", "3,600,true", "0,600,false", "5,600,false", "9,600,false", "17,600,false", "1,0,false", "1,-1,false"})
    @DisplayName("is numeric only when valid, finite and on a positive timescale")
    void numeric(int flags, int timescale, boolean numeric) {
        assertEquals(numeric, new CmTime(1, timescale, flags, 0).isNumeric());
    }

    @ParameterizedTest(name = "{0}/{1} s = {2} us")
    @CsvSource({
            "1,30,33333",
            "1001,30000,33366",
            "-1,30,-33334",
            "0,1,0",
            "1234567890123456,1000000000,1234567890123",
            "9223372036854775807,1000000000,9223372036854775"
    })
    @DisplayName("converts to microseconds rounding down without overflowing on host-clock values")
    void micros(long value, int timescale, long micros) {
        // the last row is Long.MAX_VALUE nanoseconds, far beyond where value * 1e6 would overflow
        assertEquals(micros, CmTime.of(value, timescale).toMicros());
    }

    @Test
    @DisplayName("refuses to convert a time that is not numeric")
    void notNumeric() {
        assertThrows(IllegalStateException.class, () -> new CmTime(1, 30, 0, 0).toMicros());
        assertThrows(ArithmeticException.class, () -> CmTime.of(Long.MAX_VALUE, 1).toMicros());
    }

    @Test
    @DisplayName("compares durations exactly, including across 64-bit products")
    void compare() {
        assertEquals(0, CmTime.of(1, 30).compareDuration(CmTime.of(1000, 30000)));
        assertTrue(CmTime.of(1, 30).compareDuration(CmTime.of(1001, 30000)) < 0);
        assertTrue(CmTime.of(2, 15).compareDuration(CmTime.of(1, 15)) > 0);
        var huge = CmTime.of(Long.MAX_VALUE, Integer.MAX_VALUE);
        var hugeMinusOne = CmTime.of(Long.MAX_VALUE - 1, Integer.MAX_VALUE);
        assertTrue(hugeMinusOne.compareDuration(huge) < 0);
        assertTrue(CmTime.of(-5, 1).compareDuration(CmTime.of(Long.MAX_VALUE, Integer.MAX_VALUE)) < 0);
    }

    @ParameterizedTest(name = "{0}/{1} fps -> {2}/{3} s")
    @CsvSource({"30,1,1,30", "30000,1001,1001,30000", "15,2,2,15", "60,2,1,30", "24,1,1,24"})
    @DisplayName("turns a frame rate into the reduced frame duration")
    void frameDuration(long numerator, long denominator, long value, int timescale) {
        var duration = CmTime.frameDuration(new Rational(numerator, denominator));
        assertEquals(CmTime.of(value, timescale), duration);
        assertEquals(new Rational(numerator, denominator).reduce(), duration.toRate());
    }

    @Test
    @DisplayName("approximates rates whose numerator overflows a CMTimeScale on a microsecond scale")
    void hugeRate() {
        var duration = CmTime.frameDuration(new Rational(1L << 40, 1));
        assertEquals(1_000_000, duration.timescale());
        assertEquals(1, duration.value());
    }

    @Test
    @DisplayName("refuses a rate from a non-positive duration")
    void badRate() {
        assertThrows(IllegalStateException.class, () -> CmTime.of(0, 30).toRate());
        assertThrows(IllegalStateException.class, () -> new CmTime(1, 30, 0, 0).toRate());
    }
}
