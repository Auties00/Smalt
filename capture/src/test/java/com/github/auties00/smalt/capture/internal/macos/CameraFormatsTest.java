package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.CaptureMode;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers mode listing and mode selection on format lists shaped like the ones real cameras
 * report: a built-in camera with continuous 1-30 fps ranges, a webcam with discrete rates, and
 * NTSC-style 29.97 fps formats.
 */
@DisplayName("camera formats")
class CameraFormatsTest {
    private static final int NV12 = FourCc.NV12_VIDEO_RANGE;
    private static final int YUY2 = FourCc.of("yuvs");

    private static CameraFormats.RateRange range(long minValue, int minScale, long maxValue, int maxScale) {
        var minDuration = CmTime.of(minValue, minScale);
        var maxDuration = CmTime.of(maxValue, maxScale);
        return new CameraFormats.RateRange((double) maxScale / maxValue, (double) minScale / minValue, minDuration, maxDuration);
    }

    private static CameraFormats.RateRange fixed(int fps) {
        return range(1, fps, 1, fps);
    }

    private static CameraFormats.Format format(int width, int height, int pixelFormat, CameraFormats.RateRange... ranges) {
        return new CameraFormats.Format(width, height, pixelFormat, List.of(ranges));
    }

    private static final List<CameraFormats.Format> BUILT_IN = List.of(
            format(640, 480, NV12, range(1, 30, 1, 1)),
            format(1280, 720, NV12, range(1, 30, 1, 1)),
            format(1920, 1080, NV12, range(1, 30, 1, 1)),
            format(1920, 1080, YUY2, range(1, 30, 1, 1)));

    private static final List<CameraFormats.Format> WEBCAM = List.of(
            format(1280, 720, YUY2, fixed(10), fixed(5)),
            format(640, 480, YUY2, fixed(30), fixed(15)),
            format(1280, 720, NV12, fixed(30)),
            format(0, 0, 0),
            format(320, 240, YUY2, range(1001, 30000, 1001, 30000)));

    @Nested
    @DisplayName("listing")
    class Listing {
        @Test
        @DisplayName("lists one mode per format and range, largest first then fastest first, without duplicates")
        void order() {
            var modes = CameraFormats.modes(WEBCAM);
            assertEquals(List.of(
                    CaptureMode.of(1280, 720, 30),
                    CaptureMode.of(1280, 720, 10),
                    CaptureMode.of(1280, 720, 5),
                    CaptureMode.of(640, 480, 30),
                    CaptureMode.of(640, 480, 15),
                    new CaptureMode(320, 240, new Rational(30000, 1001))), modes);
        }

        @Test
        @DisplayName("lists a size offered in two pixel formats once")
        void deduplicates() {
            assertEquals(3, CameraFormats.modes(BUILT_IN).size());
            assertEquals(CaptureMode.of(1920, 1080, 30), CameraFormats.modes(BUILT_IN).getFirst());
        }

        @Test
        @DisplayName("derives the rate from maxFrameRate when the duration is not numeric")
        void fallbackRate() {
            var broken = new CameraFormats.RateRange(1, 29.97, new CmTime(0, 0, 0, 0), new CmTime(0, 0, 0, 0));
            var modes = CameraFormats.modes(List.of(format(640, 480, NV12, broken)));
            assertEquals(List.of(new CaptureMode(640, 480, new Rational(2997, 100))), modes);
        }

        @Test
        @DisplayName("skips ranges without any rate")
        void skipsEmptyRates() {
            var empty = new CameraFormats.RateRange(Double.NaN, 0, new CmTime(0, 0, 0, 0), new CmTime(0, 0, 0, 0));
            assertTrue(CameraFormats.modes(List.of(format(640, 480, NV12, empty))).isEmpty());
        }
    }

    @Nested
    @DisplayName("selection")
    class Selection {
        @Test
        @DisplayName("picks the NV12 format among equal sizes and the exact frame duration inside a range")
        void insideRange() {
            var selection = CameraFormats.select(BUILT_IN, CaptureMode.of(1920, 1080, 15)).orElseThrow();
            assertEquals(2, selection.index());
            assertEquals(CmTime.of(1, 15), selection.frameDuration());
            assertEquals(CaptureMode.of(1920, 1080, 15), selection.mode());
        }

        @Test
        @DisplayName("prefers an NV12 format listed after a non-NV12 one")
        void prefersNv12() {
            var formats = List.of(format(1280, 720, YUY2, fixed(30)), format(1280, 720, NV12, fixed(30)));
            assertEquals(1, CameraFormats.select(formats, CaptureMode.of(1280, 720, 30)).orElseThrow().index());
        }

        @Test
        @DisplayName("returns the range's own duration object when the request equals a limit")
        void exactLimit() {
            var selection = CameraFormats.select(WEBCAM, CaptureMode.of(640, 480, 15)).orElseThrow();
            assertEquals(1, selection.index());
            assertEquals(CmTime.of(1, 15), selection.frameDuration());
        }

        @Test
        @DisplayName("snaps 30 fps to a 29.97 fps format")
        void snapsNtsc() {
            var selection = CameraFormats.select(WEBCAM, CaptureMode.of(320, 240, 30)).orElseThrow();
            assertEquals(4, selection.index());
            assertEquals(CmTime.of(1001, 30000), selection.frameDuration());
            assertEquals(new Rational(30000, 1001), selection.mode().frameRate());
        }

        @Test
        @DisplayName("rejects sizes and rates no format offers")
        void rejects() {
            assertTrue(CameraFormats.select(WEBCAM, CaptureMode.of(1280, 720, 60)).isEmpty());
            assertTrue(CameraFormats.select(WEBCAM, CaptureMode.of(1280, 720, 20)).isEmpty());
            assertTrue(CameraFormats.select(WEBCAM, CaptureMode.of(800, 600, 30)).isEmpty());
            assertTrue(CameraFormats.select(BUILT_IN, CaptureMode.of(640, 480, 31)).isEmpty());
        }

        @Test
        @DisplayName("ignores ranges whose durations are not numeric or inverted")
        void brokenRanges() {
            var inverted = range(1, 1, 1, 30);
            var invalid = new CameraFormats.RateRange(1, 30, new CmTime(1, 30, 0, 0), CmTime.of(1, 1));
            var formats = List.of(format(640, 480, NV12, inverted, invalid));
            assertTrue(CameraFormats.select(formats, CaptureMode.of(640, 480, 15)).isEmpty());
        }
    }

    @Nested
    @DisplayName("active format")
    class Active {
        @Test
        @DisplayName("keeps the active duration when the active format admits it")
        void keepsDuration() {
            var selection = CameraFormats.active(BUILT_IN, 1, CmTime.of(1, 24)).orElseThrow();
            assertEquals(1, selection.index());
            assertEquals(CaptureMode.of(1280, 720, 24), selection.mode());
        }

        @Test
        @DisplayName("falls back to the fastest rate of the active format")
        void fallsBack() {
            var selection = CameraFormats.active(WEBCAM, 0, new CmTime(0, 0, 0, 0)).orElseThrow();
            assertEquals(CmTime.of(1, 10), selection.frameDuration());
        }

        @Test
        @DisplayName("finds nothing for an unknown or empty active format")
        void nothing() {
            assertTrue(CameraFormats.active(WEBCAM, -1, CmTime.of(1, 30)).isEmpty());
            assertTrue(CameraFormats.active(WEBCAM, 3, CmTime.of(1, 30)).isEmpty());
            assertTrue(CameraFormats.active(WEBCAM, 99, CmTime.of(1, 30)).isEmpty());
        }
    }
}
