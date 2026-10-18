package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives every operation with a few thousand randomized argument combinations, including
 * degenerate sizes, extreme ratios, out-of-range offsets and every enum constant, and checks that
 * each call either returns a well-formed picture of the documented size or throws one of the
 * documented exceptions, within a time bound.
 */
@DisplayName("Robustness")
class RobustnessTest {
    private static final int[] SIZES = {1, 2, 3, 5, 7, 8, 9, 16, 17, 31, 64, 100};

    private static int size(SplittableRandom random) {
        return random.nextInt(10) == 0 ? 1 + random.nextInt(300) : SIZES[random.nextInt(SIZES.length)];
    }

    private static Picture picture(SplittableRandom random) {
        var width = size(random);
        var height = size(random);
        if (random.nextBoolean()) {
            return Pictures.randomRgba(random, width, height, random.nextBoolean());
        }
        var format = ChromaFormat.values()[random.nextInt(ChromaFormat.values().length)];
        return Pictures.randomYuv(random, width, height, format, random.nextBoolean(), color(random));
    }

    private static ColorInfo color(SplittableRandom random) {
        var matrix = MatrixCoefficients.values()[random.nextInt(MatrixCoefficients.values().length)];
        var range = ColorRange.values()[random.nextInt(2)];
        return new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
    }

    private static int argument(SplittableRandom random) {
        return switch (random.nextInt(8)) {
            case 0 -> 0;
            case 1 -> -random.nextInt(1000);
            case 2 -> 20_000 + random.nextInt(1000);
            case 3 -> Integer.MAX_VALUE;
            case 4 -> Integer.MIN_VALUE;
            default -> 1 + random.nextInt(400);
        };
    }

    private static void check(Picture picture) {
        assertNotNull(picture);
        assertTrue(picture.width() > 0 && picture.height() > 0);
        switch (picture) {
            case RgbaPicture rgba -> assertTrue(rgba.pixels().length >= 4L * rgba.width() * rgba.height());
            case YuvPicture yuv -> {
                assertTrue(yuv.y().length >= (long) yuv.width() * yuv.height());
                assertEquals(yuv.hasAlpha(), yuv.alpha() != null);
            }
            case Rgba16Picture rgba -> assertTrue(rgba.pixels().length >= 4L * rgba.width() * rgba.height());
            case Yuv16Picture yuv -> {
                assertTrue(yuv.y().length >= (long) yuv.width() * yuv.height());
                assertEquals(yuv.hasAlpha(), yuv.alpha() != null);
            }
        }
    }

    @Test
    @DisplayName("random operations return valid pictures or documented exceptions")
    void randomOperations() {
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            var random = new SplittableRandom(2026);
            var outcomes = new int[3];
            for (var round = 0; round < 3000; round++) {
                var input = picture(random);
                var filter = ResizeFilter.values()[random.nextInt(ResizeFilter.values().length)];
                try {
                    var result = switch (random.nextInt(11)) {
                        case 0 -> ImageOps.toRgba(input, ChromaUpsampling.values()[random.nextInt(2)]);
                        case 1 -> ImageOps.toYuv(input, ChromaFormat.values()[random.nextInt(4)], color(random),
                                ChromaDownsampling.values()[random.nextInt(2)]);
                        case 2 -> ImageOps.resize(input, argument(random), argument(random), filter);
                        case 3 -> ImageOps.orient(input, Orientation.values()[random.nextInt(8)]);
                        case 4 -> ImageOps.crop(input, argument(random) % 50, argument(random) % 50, argument(random), argument(random));
                        case 5 -> ImageOps.pad(input, argument(random), argument(random), random.nextInt(), random.nextInt(), random.nextInt());
                        case 6 -> ImageOps.flatten(input, random.nextInt());
                        case 7 -> ImageOps.fitWithin(input, argument(random), filter);
                        case 8 -> ImageOps.fitInto(input, argument(random), argument(random), filter, random.nextInt());
                        case 9 -> ImageOps.coverSquare(input, argument(random), filter);
                        default -> {
                            ImageOps.isOpaque(input);
                            yield input;
                        }
                    };
                    check(result);
                    outcomes[0]++;
                } catch (IllegalArgumentException expected) {
                    outcomes[1]++;
                } catch (MediaException.LimitExceeded | MediaException.Unsupported expected) {
                    outcomes[2]++;
                }
            }
            assertTrue(outcomes[0] > 1000, "too few successful operations: " + outcomes[0]);
            assertTrue(outcomes[1] > 50 && outcomes[2] > 50, "rejections: " + outcomes[1] + ", " + outcomes[2]);
        });
    }

    @Test
    @DisplayName("pictures of the largest allowed side resize and orient without failing")
    void largestSides() {
        assertTimeoutPreemptively(Duration.ofMinutes(1), () -> {
            var wide = YuvPicture.allocate(16_384, 2, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            assertEquals(3, ImageOps.resize(wide, 3, 16_384, ResizeFilter.LANCZOS3).width());
            assertEquals(2, ImageOps.orient(wide, Orientation.ROTATE_270).width());
            var tall = RgbaPicture.allocate(1, 16_384, true);
            assertEquals(16_384, ImageOps.resize(tall, 16_384, 1, ResizeFilter.AREA).width());
            assertEquals(1, ImageOps.fitWithin(tall, 1, ResizeFilter.BICUBIC).height());
        });
    }
}
