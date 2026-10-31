package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the tables of the low delay object types: the scalefactor bands of 512- and 480-sample frames, the low
 * overlap window of AAC-LD and the shape of the AAC-ELD synthesis windows.
 */
class LowDelayTablesTest {
    @ParameterizedTest(name = "{1} samples at {0} Hz")
    @CsvSource({"48000, 512, 36", "44100, 512, 36", "32000, 512, 37", "24000, 512, 31", "22050, 512, 31",
            "48000, 480, 35", "44100, 480, 35", "32000, 480, 37", "24000, 480, 30", "22050, 480, 30"})
    @DisplayName("scalefactor bands of low delay frames tile the frame in multiples of four lines")
    void bands(int rate, int frameLength, int bands) {
        // the band counts of the low delay tables of ISO/IEC 14496-3, which serve 44.1 kHz with the 48 kHz table
        var offsets = SamplingTables.longOffsets(SamplingTables.tableIndexFor(rate), frameLength);
        assertEquals(bands, offsets.length - 1);
        assertEquals(0, offsets[0]);
        assertEquals(frameLength, offsets[bands]);
        for (var band = 0; band < bands; band++) {
            var width = offsets[band + 1] - offsets[band];
            assertTrue(width > 0 && width % 4 == 0, "band " + band + " is " + width + " lines wide");
            if (band > 0) {
                assertTrue(width >= offsets[band] - offsets[band - 1], "band " + band + " narrows");
            }
        }
    }

    @ParameterizedTest(name = "{0} samples")
    @ValueSource(ints = {512, 480})
    @DisplayName("the low overlap window is zero, a sine slope and one, and satisfies the Princen-Bradley condition")
    void lowOverlapWindow(int frameLength) {
        var window = Windows.lowOverlap(2 * frameLength);
        assertEquals(2 * frameLength, window.length);
        var zeros = 3 * frameLength / 8;
        var slope = frameLength / 4;
        for (var n = 0; n < zeros; n++) {
            assertEquals(0, window[n]);
        }
        for (var n = zeros + slope; n < frameLength; n++) {
            assertEquals(1, window[n]);
        }
        for (var n = 0; n < 2 * frameLength; n++) {
            assertEquals(window[n], window[2 * frameLength - 1 - n], "symmetry at " + n);
        }
        for (var n = 0; n < frameLength; n++) {
            var sum = (double) window[n] * window[n] + (double) window[n + frameLength] * window[n + frameLength];
            assertEquals(1, sum, 1e-6, "TDAC at " + n);
        }
    }

    @ParameterizedTest(name = "{0} samples")
    @ValueSource(ints = {512, 480})
    @DisplayName("the AAC-ELD windows span four frames and begin with a quarter frame of zeros")
    void enhancedLowDelayWindows(int frameLength) {
        var window = LowDelayWindows.window(frameLength);
        assertEquals(4 * frameLength, window.length);
        for (var n = 0; n < frameLength / 4; n++) {
            assertEquals(0, window[n], "sample " + n);
        }
        var peak = 0f;
        for (var value : window) {
            peak = Math.max(peak, Math.abs(value));
        }
        assertTrue(peak > 0.9 && peak < 1.5, "peak " + peak);
        // the window is a copy: changing it leaves the decoder's table intact
        window[frameLength] = 1234;
        assertTrue(LowDelayWindows.window(frameLength)[frameLength] != 1234);
    }

    @Test
    @DisplayName("has no AAC-ELD window for other frame lengths")
    void otherLengths() {
        assertThrows(IllegalArgumentException.class, () -> LowDelayWindows.window(1024));
    }
}
