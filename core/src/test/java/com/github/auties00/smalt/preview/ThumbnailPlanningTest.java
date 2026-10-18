package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                            var result = Thumbnails.finish(picture, orientation, options);
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
    @DisplayName("copies a picture that needs no change out of the decoder's buffer")
    void copies() {
        var picture = RgbaPicture.allocate(40, 30, true);
        picture.pixels()[5] = 42;
        var result = Thumbnails.finish(picture, Orientation.NORMAL, ThumbnailOptions.fullSize());
        assertNotSame(picture, result);
        assertNotSame(picture.pixels(), result.pixels());
        assertEquals(42, result.pixels()[5]);
        assertEquals(true, result.hasAlpha());
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
