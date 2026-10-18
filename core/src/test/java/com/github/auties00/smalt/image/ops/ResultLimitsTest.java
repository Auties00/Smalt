package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Covers the rule that an operation checks only the growth it causes: a picture whose side already exceeds the
 * default limits (as a decoder run under raised limits returns) passes through every operation that does not
 * enlarge it, while a result larger than its input is still held to the default limits.
 */
@DisplayName("Result limits")
class ResultLimitsTest {
    private static final int SIDE = 16_385;

    private static RgbaPicture wideRgba() {
        return RgbaPicture.allocate(SIDE, 2, true);
    }

    private static YuvPicture wideYuv() {
        return YuvPicture.allocate(SIDE, 2, ChromaFormat.YUV420, true, ColorInfo.JPEG);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> preserving() {
        return Stream.of(
                arguments("toRgba", (Function<Picture, Picture>) picture -> ImageOps.toRgba(wideYuv())),
                arguments("toYuv", (Function<Picture, Picture>) picture -> ImageOps.toYuv(picture, ChromaFormat.YUV420,
                        ColorInfo.JPEG)),
                arguments("orient", (Function<Picture, Picture>) picture -> ImageOps.orient(picture,
                        Orientation.ROTATE_90)),
                arguments("resize to a smaller area", (Function<Picture, Picture>) picture -> ImageOps.resize(picture,
                        SIDE, 1, ResizeFilter.BILINEAR)),
                arguments("crop", (Function<Picture, Picture>) picture -> ImageOps.crop(picture, 0, 0, SIDE, 1)),
                arguments("toSixteenBit", (Function<Picture, Picture>) picture -> ImageOps.toSixteenBit(wideRgba())),
                arguments("toBitDepth", (Function<Picture, Picture>) picture -> ImageOps.toBitDepth(wideYuv(), 10)),
                arguments("toSrgb", (Function<Picture, Picture>) ImageOps::toSrgb),
                arguments("rotate keep", (Function<Picture, Picture>) picture -> ImageOps.rotate(picture, 30,
                        Interpolation.NEAREST, RotationCanvas.KEEP, 0)),
                arguments("blur", (Function<Picture, Picture>) picture -> ImageFilters.boxBlur(picture, 1)),
                arguments("brightness", (Function<Picture, Picture>) picture -> ImageAdjustments.brightness(picture,
                        1.5)),
                arguments("resizeCanvas", (Function<Picture, Picture>) picture -> ImageOps.resizeCanvas(picture, SIDE,
                        1, Anchor.CENTER, 0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("preserving")
    @DisplayName("operations that do not enlarge a picture accept one beyond the default limits")
    void preservingOperations(String name, Function<Picture, Picture> operation) {
        var result = operation.apply(wideRgba());
        assertEquals(SIDE, Math.max(result.width(), result.height()));
    }

    @Test
    @DisplayName("quantization accepts a picture beyond the default limits")
    void quantize() {
        assertEquals(SIDE, ImageQuantization.quantize(wideRgba()).width());
    }

    @Nested
    @DisplayName("results larger than their input")
    class Growth {
        @Test
        @DisplayName("are held to the default limits")
        void enlarged() {
            assertThrows(MediaException.LimitExceeded.class,
                    () -> ImageOps.extend(wideRgba(), 0, 1, 0, 0, ExtendMode.REPLICATE, 0));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> ImageOps.resize(RgbaPicture.allocate(4, 4, false), SIDE, 1, ResizeFilter.AREA));
            assertThrows(MediaException.LimitExceeded.class,
                    () -> ImageOps.pad(wideRgba(), SIDE + 1, 2, 0, 0, 0));
        }

        @Test
        @DisplayName("within the default limits succeed")
        void withinLimits() {
            var result = ImageOps.extend(RgbaPicture.allocate(16_383, 1, false), 0, 1, 0, 0, ExtendMode.REPLICATE, 0);
            assertEquals(16_384, result.width());
        }
    }
}
