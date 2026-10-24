package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.track.Interlacing;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers how a {@code TrackEntry}'s display size, pixel crop and display unit give the sample
 * aspect ratio (RFC 9559 section 5.1.4.1.28), and how {@code FlagInterlaced} and {@code FieldOrder}
 * give the scan.
 */
@DisplayName("Matroska display size and field order")
class MatroskaDisplaySizeTest {
    static MatroskaTrackEntry entry(long width, long height, long displayWidth, long displayHeight, long unit) {
        var entry = new MatroskaTrackEntry();
        entry.pixelWidth = width;
        entry.pixelHeight = height;
        entry.displayWidth = displayWidth;
        entry.displayHeight = displayHeight;
        entry.displayUnit = unit;
        return entry;
    }

    @ParameterizedTest(name = "{0}x{1} shown {2}x{3} unit {4}")
    @CsvSource({"720, 576, 1024, 576, 0, 64, 45", "720, 576, 16, 9, 3, 64, 45", "720, 480, 640, 0, 0, 8, 9",
            "720, 480, 0, 360, 0, 4, 3", "176, 144, 176, 144, 0, 1, 1", "1920, 1080, 16, 9, 1, 1, 1",
            "1920, 1080, 32, 9, 2, 2, 1"})
    @DisplayName("divides the display size by the pixel size in any known unit")
    void ratio(long width, long height, long displayWidth, long displayHeight, long unit, long numerator, long denominator) {
        assertEquals(new Rational(numerator, denominator), entry(width, height, displayWidth, displayHeight, unit).sampleAspect());
    }

    @Test
    @DisplayName("measures the display size against the picture left by the crop")
    void crop() {
        var entry = entry(720, 576, 1024, 576, 0);
        entry.pixelCrop[2] = 8;
        entry.pixelCrop[3] = 8;
        // the 704 columns left are shown 1024 wide
        assertEquals(new Rational(16, 11), entry.sampleAspect());
        var defaulted = entry(720, 576, 0, 288, 0);
        defaulted.pixelCrop[0] = 144;
        defaulted.pixelCrop[1] = 144;
        // the display width defaults to the cropped width, the display height halves the 288 rows left
        assertEquals(new Rational(1, 1), defaulted.sampleAspect());
    }

    @Test
    @DisplayName("declares nothing without a display size, for an unknown unit, or when the crop leaves no picture")
    void undeclared() {
        assertNull(entry(720, 576, 0, 0, 0).sampleAspect());
        assertNull(entry(720, 576, 16, 9, 4).sampleAspect());
        assertNull(entry(720, 576, 16, 9, 7).sampleAspect());
        assertNull(entry(720, 576, 16, 0, 3).sampleAspect());
        assertNull(entry(0, 576, 16, 9, 3).sampleAspect());
        var cropped = entry(720, 576, 16, 9, 3);
        cropped.pixelCrop[2] = 720;
        assertNull(cropped.sampleAspect());
        var overflow = entry(720, 576, 16, 9, 3);
        overflow.pixelCrop[2] = Long.MIN_VALUE;
        assertNull(overflow.sampleAspect());
    }

    @Test
    @DisplayName("reduces ratios of very large display sizes, or declares none when they do not fit")
    void large() {
        assertEquals(new Rational(1, 1), entry(1L << 40, 1L << 40, 1L << 40, 1L << 40, 0).sampleAspect());
        assertNull(entry(3, 1L << 62, (1L << 62) + 1, 3, 3).sampleAspect());
    }

    @ParameterizedTest(name = "FlagInterlaced {0} FieldOrder {1}")
    @CsvSource({"2, 2, PROGRESSIVE", "2, 9, PROGRESSIVE", "1, 2, INTERLACED", "1, 0, INTERLACED", "1, 1, TOP_FIELD_FIRST_SEPARATED",
            "1, 6, BOTTOM_FIELD_FIRST_SEPARATED", "1, 9, TOP_FIELD_FIRST", "1, 14, BOTTOM_FIELD_FIRST", "1, 3, INTERLACED"})
    @DisplayName("gives the scan of the interlacing flag and the field order")
    void scan(long flag, long order, Interlacing expected) {
        var entry = new MatroskaTrackEntry();
        entry.flagInterlaced = flag;
        entry.fieldOrder = order;
        assertEquals(expected, entry.interlacing());
    }

    @Test
    @DisplayName("declares no scan when the interlacing flag is undetermined or unknown")
    void noScan() {
        var entry = new MatroskaTrackEntry();
        assertNull(entry.interlacing());
        entry.flagInterlaced = 3;
        entry.fieldOrder = 9;
        assertNull(entry.interlacing());
    }
}
