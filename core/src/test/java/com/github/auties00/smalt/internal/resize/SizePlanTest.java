package com.github.auties00.smalt.internal.resize;

import com.github.auties00.smalt.transcode.ScaleMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("size planning")
class SizePlanTest {
    @ParameterizedTest(name = "{0}x{1} into {2}x{3} {4}")
    @CsvSource({
            "1920, 1080, 1280, 720, FIT, false, 0, 0, true, 1280, 720, 1280, 720",
            "1920, 1080, 1000, 1000, FIT, false, 0, 0, true, 1000, 562, 1000, 562",
            "640, 480, 1280, 720, FIT, false, 0, 0, true, 640, 480, 640, 480",
            "640, 480, 1280, 720, FIT, true, 0, 0, true, 960, 720, 960, 720",
            "640, 480, 0, 0, FIT, false, 0, 0, true, 640, 480, 640, 480",
            "641, 481, 0, 0, FIT, false, 0, 0, true, 640, 480, 640, 480",
            "641, 481, 0, 0, FIT, false, 0, 0, false, 641, 481, 641, 481",
            "1920, 1080, 500, 500, FILL, false, 0, 0, true, 889, 500, 500, 500",
            "1920, 1080, 500, 300, EXACT, false, 0, 0, true, 500, 300, 500, 300",
            "1920, 1080, 0, 0, FIT, false, 0, 720, true, 1280, 720, 1280, 720",
            "1080, 1920, 0, 0, FIT, false, 1280, 0, true, 720, 1280, 720, 1280",
            "4000, 3000, 0, 0, FIT, false, 1600, 720, true, 960, 720, 960, 720"})
    @DisplayName("fit the box, then the edge limits, then even dimensions")
    void plan(int width, int height, int boxWidth, int boxHeight, ScaleMode mode, boolean upscale, int maxLong, int maxShort,
              boolean even, int scaledWidth, int scaledHeight, int outputWidth, int outputHeight) {
        var plan = SizePlan.of(width, height, boxWidth, boxHeight, mode, upscale, maxLong, maxShort, even);
        assertEquals(scaledWidth, plan.scaledWidth(), "scaled width");
        assertEquals(scaledHeight, plan.scaledHeight(), "scaled height");
        assertEquals(outputWidth, plan.outputWidth(), "output width");
        assertEquals(outputHeight, plan.outputHeight(), "output height");
        assertEquals((scaledWidth - outputWidth) / 2, plan.cropX());
    }
}
