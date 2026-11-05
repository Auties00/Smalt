package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the size of thumbnails (the displayed picture is scaled down to fit the box with its aspect
 * kept, each side rounded to the nearest pixel and at least 1, and never scaled up) and when pictures
 * can be spread over a video that cannot seek.
 */
class ThumbnailPlanningTest {
    @Test
    @DisplayName("fits every picture into every box with the rounding of the box fit")
    void sizes() {
        int[] sides = {1, 2, 3, 7, 16, 99, 100, 101, 239, 333, 641};
        int[] boxes = {0, 1, 5, 80, 160, 320, 700};
        // a box of 0 by 0 stands for full size, the only way a side is unbounded
        for (var width : sides) {
            for (var height : sides) {
                var picture = RgbaPicture.allocate(width, height, false);
                for (var boxWidth : boxes) {
                    for (var boxHeight : boxes) {
                        if ((boxWidth == 0) != (boxHeight == 0)) {
                            continue;
                        }
                        for (var orientation : new Orientation[]{Orientation.NORMAL, Orientation.ROTATE_90}) {
                            var options = boxWidth == 0 ? ThumbnailOptions.fullSize()
                                    : ThumbnailOptions.builder().maxSize(boxWidth, boxHeight).build();
                            var result = Thumbnails.finish(picture, orientation, null, options);
                            var swaps = orientation.swapsDimensions();
                            var displayWidth = swaps ? height : width;
                            var displayHeight = swaps ? width : height;
                            var scale = 1.0;
                            if (boxWidth > 0) {
                                scale = Math.min(scale, (double) boxWidth / displayWidth);
                            }
                            if (boxHeight > 0) {
                                scale = Math.min(scale, (double) boxHeight / displayHeight);
                            }
                            var expectedWidth = Math.max(1, (int) Math.round(displayWidth * scale));
                            var expectedHeight = Math.max(1, (int) Math.round(displayHeight * scale));
                            var what = width + "x" + height + " in " + boxWidth + "x" + boxHeight + " " + orientation;
                            assertEquals(expectedWidth, result.width(), what);
                            assertEquals(expectedHeight, result.height(), what);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("converts a deep YCgCo-R picture from its own depth, whose 8-bit samples keep fewer R'G'B' bits")
    void ycgcoR() {
        var source = RgbaPicture.allocate(40, 30, false);
        for (var i = 0; i < source.pixels().length; i += 4) {
            source.pixels()[i] = (byte) (i * 7 + 1);
            source.pixels()[i + 1] = (byte) (i * 13 + 2);
            source.pixels()[i + 2] = (byte) (i * 29 + 3);
            source.pixels()[i + 3] = (byte) 255;
        }
        var color = new ColorInfo(MatrixCoefficients.YCGCO_RE, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
        var deep = ImageOps.toYuv16(source, ChromaFormat.YUV444, color, 10);
        var result = Thumbnails.finish(deep, Orientation.NORMAL, null, ThumbnailOptions.fullSize());
        assertArrayEquals(source.pixels(), result.pixels());
    }

    @Test
    @DisplayName("copies a picture that needs no change out of the decoder's buffer")
    void copies() {
        var picture = RgbaPicture.allocate(40, 30, true);
        picture.pixels()[5] = 42;
        var result = Thumbnails.finish(picture, Orientation.NORMAL, null, ThumbnailOptions.fullSize());
        assertNotSame(picture, result);
        assertNotSame(picture.pixels(), result.pixels());
        assertEquals(42, result.pixels()[5]);
        assertEquals(true, result.hasAlpha());
    }

    @ParameterizedTest(name = "{0}x{1} sar {2}/{3} {4} in {5}x{6}")
    @CsvSource({"176, 144, 16, 11, NORMAL, 0, 0, 256, 144", "176, 144, 16, 11, ROTATE_90, 0, 0, 144, 256",
            "720, 480, 8, 9, NORMAL, 0, 0, 640, 480", "720, 576, 64, 45, NORMAL, 0, 0, 1024, 576",
            "176, 144, 16, 11, NORMAL, 128, 128, 128, 72", "176, 144, 16, 11, ROTATE_90, 128, 128, 72, 128",
            "720, 480, 8, 9, NORMAL, 320, 320, 320, 240", "176, 144, 1, 1, NORMAL, 0, 0, 176, 144",
            "3, 2, 1, 100, NORMAL, 0, 0, 1, 2"})
    @DisplayName("stretches anamorphic pictures to width * sample aspect ratio before fitting the box")
    void anamorphic(int width, int height, long numerator, long denominator, Orientation orientation, int boxWidth,
                    int boxHeight, int expectedWidth, int expectedHeight) {
        var picture = RgbaPicture.allocate(width, height, false);
        var options = boxWidth == 0 ? ThumbnailOptions.fullSize() : ThumbnailOptions.builder().maxSize(boxWidth, boxHeight)
                .build();
        var result = Thumbnails.finish(picture, orientation, new Rational(numerator, denominator), options);
        assertEquals(expectedWidth, result.width());
        assertEquals(expectedHeight, result.height());
    }

    @Test
    @DisplayName("stretches the pixels, not only the size: the left half stays left of the stretched middle")
    void stretchedContent() {
        var picture = RgbaPicture.allocate(64, 8, false);
        var pixels = picture.pixels();
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 64; x++) {
                var offset = y * picture.stride() + x * 4;
                pixels[offset] = (byte) (x < 32 ? 255 : 0);
                pixels[offset + 2] = (byte) (x < 32 ? 0 : 255);
                pixels[offset + 3] = (byte) 255;
            }
        }
        var result = Thumbnails.finish(picture, Orientation.NORMAL, new Rational(2, 1), ThumbnailOptions.fullSize());
        assertEquals(128, result.width());
        for (var x : new int[]{0, 40, 60}) {
            assertEquals(255, result.pixels()[4 * 4 * 128 + x * 4] & 0xFF, "red at " + x);
        }
        for (var x : new int[]{68, 90, 127}) {
            assertEquals(255, result.pixels()[4 * 4 * 128 + x * 4 + 2] & 0xFF, "blue at " + x);
        }
    }

    @Test
    @DisplayName("rejects a stretched thumbnail beyond the limits, but not a stretch the box scales down")
    void stretchLimit() {
        var picture = RgbaPicture.allocate(64, 64, false);
        var options = ThumbnailOptions.fullSize().toBuilder().limits(MediaLimits.builder().maxPixels(64 * 64 * 4).build())
                .build();
        assertThrows(MediaException.LimitExceeded.class,
                () -> Thumbnails.finish(picture, Orientation.NORMAL, new Rational(100, 1), options));
        // the box bounds what is allocated, so the same stretch fits once it is scaled down
        var boxed = options.toBuilder().maxSize(64, 64).build();
        var result = Thumbnails.finish(picture, Orientation.NORMAL, new Rational(100, 1), boxed);
        assertEquals(64, result.width());
        assertEquals(1, result.height());
    }

    @Test
    @DisplayName("spreads pictures over a video that cannot seek unless a representative frame needs reading ahead")
    void forwardReading() {
        var plain = ThumbnailOptions.fitting(80, 80);
        var best = ThumbnailOptions.builder().maxSize(80, 80).bestFrame(true).build();
        assertDoesNotThrow(() -> Thumbnails.checkForwardReading(false, 4, 4_000_000, plain));
        assertDoesNotThrow(() -> Thumbnails.checkForwardReading(false, 1, 4_000_000, best));
        assertDoesNotThrow(() -> Thumbnails.checkForwardReading(false, 4, 40_000_000, best));
        assertDoesNotThrow(() -> Thumbnails.checkForwardReading(true, 4, 4_000_000, best));
        assertThrows(MediaException.Unsupported.class, () -> Thumbnails.checkForwardReading(false, 4, 4_000_000, best));
    }
}
