package com.github.auties00.smalt.image.raw;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the value types of the raw decoder's API.
 */
class ApiTest {
    @Nested
    @DisplayName("CfaPattern")
    class Patterns {
        @Test
        @DisplayName("Bayer patterns read back, shift and name themselves")
        void bayer() {
            var rggb = CfaPattern.bayer("RGGB");
            assertTrue(rggb.isBayer());
            assertTrue(rggb.isRgb());
            assertEquals(CfaColor.BLUE, rggb.color(1, 1));
            assertEquals(CfaPattern.bayer("GBRG"), rggb.shifted(1, 0));
            assertEquals(CfaPattern.bayer("BGGR"), rggb.shifted(1, 1));
            assertEquals("RGGB", rggb.toString());
            assertFalse(CfaPattern.bayer("RGBG").isBayer());
            assertFalse(CfaPattern.bayer("CMYG").isRgb());
        }

        @Test
        @DisplayName("invalid patterns are rejected")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> CfaPattern.bayer("RGB"));
            assertThrows(IllegalArgumentException.class, () -> CfaPattern.bayer("RGGX"));
            assertThrows(IllegalArgumentException.class, () -> CfaPattern.of(2, 2, List.of(CfaColor.RED)));
            assertThrows(IllegalArgumentException.class, () -> CfaPattern.of(0, 1, List.of()));
            assertThrows(IllegalArgumentException.class, () -> CfaPattern.bayer("RGGB").color(-1, 0));
        }
    }

    @Nested
    @DisplayName("RawDecoderOptions")
    class Options {
        @Test
        @DisplayName("presets and builders produce equal options for equal settings")
        void presets() {
            var defaults = RawDecoderOptions.defaults();
            assertEquals(RawDemosaic.MENON, defaults.demosaic());
            assertEquals(RawColorSpace.SRGB, defaults.colorSpace());
            assertEquals(RawDemosaic.BILINEAR, RawDecoderOptions.fast().demosaic());
            assertEquals(RawColorSpace.LINEAR_REC2020, RawDecoderOptions.linear().colorSpace());
            assertTrue(RawDecoderOptions.linear().highBitDepth());
            assertEquals(defaults, defaults.toBuilder().build());
            assertEquals(defaults.toBuilder().exposure(1).build().hashCode(),
                    defaults.toBuilder().exposure(1).build().hashCode());
            assertNotEquals(defaults, defaults.toBuilder().whiteBalance(RawWhiteBalance.daylight()).build());
            assertEquals(120, RawDecoderOptions.ofMinimumSize(120, 80).minWidth());
        }

        @Test
        @DisplayName("generic image options map their limits, size hint and bit depth")
        void fromImageOptions() {
            var limits = MediaLimits.builder().maxPixels(1234567).build();
            var options = RawDecoderOptions.of(ImageDecoderOptions.builder().limits(limits).minSize(10, 20)
                    .highBitDepth(true).build());
            assertEquals(limits, options.limits());
            assertEquals(20, options.minHeight());
            assertTrue(options.highBitDepth());
        }

        @Test
        @DisplayName("the builder reaches every setting")
        void builderCoversSettings() {
            var limits = MediaLimits.builder().maxPixels(1234567).build();
            double[] matrix = {0.5, 0, 0, 0, 1, 0, 0, 0, 2};
            var built = RawDecoderOptions.builder().limits(limits).minSize(30, 20).embeddedPreview(false)
                    .demosaic(RawDemosaic.BILINEAR).whiteBalance(RawWhiteBalance.daylight())
                    .highlights(RawHighlights.BLEND).toneCurve(RawToneCurve.LINEAR).exposure(1.5)
                    .colorSpace(RawColorSpace.LINEAR_REC2020).highBitDepth(true).opcodes(false).defaultCrop(false)
                    .colorMatrix(matrix).build();
            assertEquals(limits, built.limits());
            assertEquals(30, built.minWidth());
            assertEquals(20, built.minHeight());
            assertFalse(built.embeddedPreview());
            assertEquals(RawDemosaic.BILINEAR, built.demosaic());
            assertEquals(RawWhiteBalance.daylight(), built.whiteBalance());
            assertEquals(RawHighlights.BLEND, built.highlights());
            assertEquals(RawToneCurve.LINEAR, built.toneCurve());
            assertEquals(1.5, built.exposure());
            assertEquals(RawColorSpace.LINEAR_REC2020, built.colorSpace());
            assertTrue(built.highBitDepth());
            assertFalse(built.opcodes());
            assertFalse(built.defaultCrop());
            assertArrayEquals(matrix, built.colorMatrix().orElseThrow());
            assertEquals(built, built.toBuilder().build());
            assertEquals(RawDecoderOptions.builder().demosaic(RawDemosaic.BILINEAR).build(), RawDecoderOptions.fast());
            assertEquals(RawDecoderOptions.builder().minSize(120, 80).build(),
                    RawDecoderOptions.ofMinimumSize(120, 80));
        }

        @Test
        @DisplayName("fullSize removes the size hint")
        void fullSize() {
            var cleared = RawDecoderOptions.builder().minSize(120, 80).fullSize().build();
            assertEquals(0, cleared.minWidth());
            assertEquals(0, cleared.minHeight());
            assertEquals(RawDecoderOptions.defaults(), cleared);
        }

        @Test
        @DisplayName("colorMatrix returns a copy of the matrix, or empty for the file's own")
        void colorMatrix() {
            assertTrue(RawDecoderOptions.defaults().colorMatrix().isEmpty());
            double[] matrix = {0.5, 0, 0, 0, 1, 0, 0, 0, 2};
            var options = RawDecoderOptions.builder().colorMatrix(matrix).build();
            assertArrayEquals(matrix, options.colorMatrix().orElseThrow());
            options.colorMatrix().orElseThrow()[0] = 9;
            assertArrayEquals(matrix, options.colorMatrix().orElseThrow());
            assertTrue(options.toBuilder().fileColorMatrix().build().colorMatrix().isEmpty());
            assertThrows(NullPointerException.class, () -> RawDecoderOptions.builder().colorMatrix(null));
        }

        @Test
        @DisplayName("build rejects out-of-range settings, which the setters store")
        void invalid() {
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().exposure(11).build());
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.builder().minSize(-1, 0).build());
            assertThrows(IllegalArgumentException.class,
                    () -> RawDecoderOptions.builder().exposure(Double.NaN).build());
            assertThrows(IllegalArgumentException.class, () -> RawDecoderOptions.ofMinimumSize(0, -1));
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.temperature(1000));
            assertThrows(IllegalArgumentException.class, () -> RawWhiteBalance.multipliers(1, 0, 1));
            assertThrows(NullPointerException.class, () -> RawDecoderOptions.builder().demosaic(null));
            var builder = RawDecoderOptions.builder().exposure(11);
            assertThrows(IllegalArgumentException.class, builder::build);
            assertEquals(1.0, builder.exposure(1).build().exposure());
        }
    }

    @Nested
    @DisplayName("CfaColor")
    class Colors {
        @ParameterizedTest
        @EnumSource(CfaColor.class)
        @DisplayName("of is the inverse of code")
        void roundTrip(CfaColor color) {
            assertEquals(color, CfaColor.of(color.code()));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 7, Integer.MAX_VALUE})
        @DisplayName("of rejects codes outside 0 to 6")
        void invalid(int code) {
            assertThrows(IllegalArgumentException.class, () -> CfaColor.of(code));
        }
    }

    @Nested
    @DisplayName("RawInfo capture time")
    class CaptureTime {
        @ParameterizedTest
        @CsvSource(delimiter = '|', value = {
                "2017:01:05 13:52:55|2017-01-05T13:52:55",
                "2017-01-05 13:52:55|2017-01-05T13:52:55",
                "2017:01:05T13:52:55|2017-01-05T13:52:55",
                "'  2017:01:05 13:52:55.123  '|2017-01-05T13:52:55",
                "1999:12:31 23:59:59 |1999-12-31T23:59:59"
        })
        @DisplayName("captureTime parses the EXIF form leniently")
        void parses(String text, String expected) {
            var info = info(text);
            assertEquals(LocalDateTime.parse(expected), info.captureTime().orElseThrow());
            assertEquals(text, info.captureTimeText().orElseThrow());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0000:00:00 00:00:00", "2017:13:05 13:52:55", "2017:02:30 10:00:00",
                "2017:01:05", "    :  :     :  :  ", "", "unknown"})
        @DisplayName("captureTime is empty for placeholders and invalid text, which captureTimeText keeps")
        void invalid(String text) {
            var info = info(text);
            assertTrue(info.captureTime().isEmpty());
            assertEquals(text, info.captureTimeText().orElseThrow());
        }

        @Test
        @DisplayName("both accessors are empty when the file records no time")
        void absent() {
            var info = info(null);
            assertTrue(info.captureTime().isEmpty());
            assertTrue(info.captureTimeText().isEmpty());
        }

        private static RawInfo info(String captureTime) {
            return new RawInfo(RawFormat.DNG, null, null, 4, 4, 4, 4, Orientation.NORMAL, 16, null, null, null,
                    false, 0, 0, 0, 0, null, captureTime, null, List.of(), CfaLayout.RECTANGULAR, List.of(""), false, 1);
        }
    }

    @Test
    @DisplayName("extractPreview with limits finds no preview in a file without one")
    void extractPreviewWithLimits() {
        var bytes = TinyDng.ramp(16, 16).write();
        assertTrue(RawDecoder.extractPreview(MediaSource.of(bytes), MediaLimits.DEFAULT).isEmpty());
        assertTrue(RawDecoder.extractPreview(MediaSource.of(bytes)).isEmpty());
    }

    @Test
    @DisplayName("the white balance choices describe themselves")
    void whiteBalance() {
        assertTrue(RawWhiteBalance.asShot() instanceof RawWhiteBalance.AsShot);
        assertEquals(5500, RawWhiteBalance.daylight().kelvin());
        assertEquals(2.0, RawWhiteBalance.multipliers(2, 1, 1.5).red());
        assertEquals(RawWhiteBalance.temperature(4000), RawWhiteBalance.temperature(4000));
    }

    @Test
    @DisplayName("every color space names its ICC profile")
    void colorSpaces() {
        for (var space : RawColorSpace.values()) {
            assertEquals(3, space.profile().channels(), space.name());
        }
        assertTrue(RawColorSpace.SRGB.profile().isSrgb());
    }
}
