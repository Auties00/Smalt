package com.github.auties00.smalt.container;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Timecode")
class TimecodeTest {
    @ParameterizedTest
    @CsvSource({
            // nominal rate, drop frame, text, frame number (SMPTE ST 12-1 counting)
            "30, true, 01:00:00;00, 107892",
            "30, true, 00:01:00;02, 1800",
            "30, true, 00:10:00;00, 17982",
            "30, true, 23:59:59;29, 2589407",
            "60, true, 00:01:00;04, 3600",
            "60, true, 01:00:00;00, 215784",
            "30, false, 01:00:00:00, 108000",
            "25, false, 10:11:12:13, 916813",
            "24, false, 00:00:01:23, 47",
            "120, false, 00:00:00:119, 119"
    })
    @DisplayName("maps an address to its frame number and back")
    void frameNumbers(int rate, boolean drop, String text, long number) {
        var timecode = Timecode.parse(text, rate);
        assertEquals(drop, timecode.dropFrame());
        assertEquals(number, timecode.frameNumber());
        assertEquals(timecode, Timecode.ofFrameNumber(number, rate, drop));
        assertEquals(text, timecode.toString());
    }

    @Test
    @DisplayName("counts every drop-frame frame number of a day exactly once, in order")
    void dropFrameDay() {
        var previous = -1L;
        var perDay = 24L * 60 * 60 * 30 - 2L * (24 * 60 - 24 * 6);
        for (var number = 0L; number < perDay; number += 7) {
            var timecode = Timecode.ofFrameNumber(number, 30, true);
            assertEquals(number, timecode.frameNumber(), timecode.toString());
            assertTrue(timecode.frameNumber() > previous);
            previous = timecode.frameNumber();
            if (timecode.seconds() == 0 && timecode.minutes() % 10 != 0) {
                assertTrue(timecode.frames() >= 2, timecode.toString());
            }
        }
        assertEquals(Timecode.parse("00:00:00;00", 30), Timecode.ofFrameNumber(perDay, 30, true));
        assertEquals(Timecode.parse("23:59:59;29", 30), Timecode.ofFrameNumber(-1, 30, true));
    }

    @Test
    @DisplayName("accepts the drop-frame separators and wraps at 24 hours")
    void separators() {
        for (var separator : new String[]{";", ".", ","}) {
            assertTrue(Timecode.parse("01:02:03" + separator + "04", 30).dropFrame());
        }
        assertFalse(Timecode.parse("01:02:03:04", 30).dropFrame());
        assertEquals(Timecode.parse("00:00:00:05", 25), Timecode.ofFrameNumber(24L * 3600 * 25 + 5, 25, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "01:00:00", "01:00:00:00:00", "1:2:3:x", "24:00:00:00", "00:60:00:00",
            "00:00:60:00", "00:00:00:30", "00:01:00;00", "00:01:00;01", "0001:00:00:00", ":00:00:00", "00;00:00:00"})
    @DisplayName("rejects malformed and impossible addresses")
    void invalid(String text) {
        assertThrows(IllegalArgumentException.class, () -> Timecode.parse(text, 30));
    }

    @Test
    @DisplayName("rejects drop-frame counting at a rate that is not a multiple of 30, and rates out of range")
    void rates() {
        assertThrows(IllegalArgumentException.class, () -> Timecode.parse("00:00:00;00", 25));
        assertThrows(IllegalArgumentException.class, () -> Timecode.ofFrameNumber(0, 24, true));
        assertThrows(IllegalArgumentException.class, () -> Timecode.ofFrameNumber(0, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new Timecode(0, 0, 0, 0, 256, false));
    }
}
