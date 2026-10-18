package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares the image operations with Pillow 12.3 outputs used as a black-box oracle. The inputs,
 * the cases and Pillow's outputs come from {@code tools/testdata/imageops-oracle.py}, which also
 * documents how each Pillow call maps to a Smalt one and how each case is compared: exactly, or
 * within a tolerance away from the edges where Pillow handles borders differently (its kernel
 * filters leave the border unfiltered; its interpolating rotations replicate edges and truncate).
 */
@DisplayName("Pillow oracle")
class PillowOracleTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/ops/pillow/";
    private static final int WIDTH = 61;
    private static final int HEIGHT = 47;

    record Case(String name, String input, String operation, String parameters, String comparison, String mode) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var line : new String(resource("cases.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var fields = line.split("\t", -1);
            cases.add(new Case(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
        }
        return cases;
    }

    private static byte[] resource(String name) {
        try (var stream = PillowOracleTest.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static RgbaPicture input(String name) {
        var raw = resource(name + ".bin");
        var alpha = name.equals("rgba");
        var channels = alpha ? 4 : 3;
        var picture = RgbaPicture.allocate(WIDTH, HEIGHT, alpha);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            for (var c = 0; c < channels; c++) {
                picture.pixels()[4 * i + c] = raw[channels * i + c];
            }
            if (!alpha) {
                picture.pixels()[4 * i + 3] = (byte) 255;
            }
        }
        return picture;
    }

    private static Picture run(Case test, RgbaPicture input) {
        var p = test.parameters().isEmpty() ? new String[0] : test.parameters().split(" ");
        return switch (test.operation()) {
            case "gauss-box" -> ImageFilters.gaussianBlur(input, Double.parseDouble(p[0]), BlurMethod.BOX);
            case "box" -> ImageFilters.boxBlur(input, Double.parseDouble(p[0]));
            case "unsharp" -> ImageFilters.unsharpMask(input, Double.parseDouble(p[0]), Integer.parseInt(p[1]) / 100.0,
                    Integer.parseInt(p[2]), BlurMethod.BOX);
            case "median" -> ImageFilters.median(input, (Integer.parseInt(p[0]) - 1) / 2);
            case "kernel" -> ImageFilters.convolve(input, switch (p[0]) {
                case "sharpen" -> ConvolutionKernel.sharpen();
                case "smooth" -> ConvolutionKernel.smooth();
                case "detail" -> ConvolutionKernel.detail();
                case "edgeEnhance" -> ConvolutionKernel.edgeEnhance();
                case "findEdges" -> ConvolutionKernel.findEdges();
                case "emboss" -> ConvolutionKernel.emboss();
                default -> throw new IllegalArgumentException(p[0]);
            });
            case "rotate-nearest" -> ImageOps.rotate(input, Double.parseDouble(p[0]), Interpolation.NEAREST,
                    RotationCanvas.KEEP, 0xFF000000);
            case "rotate-bilinear" -> ImageOps.rotate(input, Double.parseDouble(p[0]), Interpolation.BILINEAR,
                    RotationCanvas.KEEP, 0xFF000000);
            case "brightness" -> ImageAdjustments.brightness(input, Double.parseDouble(p[0]));
            case "contrast" -> ImageAdjustments.contrast(input, Double.parseDouble(p[0]));
            case "saturation" -> ImageAdjustments.saturation(input, Double.parseDouble(p[0]));
            case "autolevels" -> ImageAdjustments.autoLevels(input, Double.parseDouble(p[0]));
            case "autocontrast" -> ImageAdjustments.autoContrast(input, Double.parseDouble(p[0]));
            case "gray" -> ImageAdjustments.grayscale(input, GrayscaleMode.BT601);
            case "colorize" -> ImageAdjustments.colorize(input, Integer.parseInt(p[0], 16), Integer.parseInt(p[1], 16));
            case "invert" -> ImageAdjustments.invert(input);
            default -> throw new IllegalArgumentException(test.operation());
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches Pillow")
    void matches(Case test) {
        var input = input(test.input());
        var actual = (RgbaPicture) run(test, input);
        var expected = resource(test.name() + ".bin");
        var channels = switch (test.mode()) {
            case "L" -> 1;
            case "RGB" -> 3;
            case "RGBA" -> 4;
            default -> throw new IllegalArgumentException(test.mode());
        };
        assertEquals(WIDTH, actual.width());
        assertEquals(HEIGHT, actual.height());
        assertEquals(WIDTH * HEIGHT * channels, expected.length);
        var comparison = test.comparison().split(":");
        var inverse = test.operation().startsWith("rotate")
                ? AffineTransform.translation(-WIDTH / 2.0, -HEIGHT / 2.0)
                .then(AffineTransform.rotation(Double.parseDouble(test.parameters())))
                .then(AffineTransform.translation(WIDTH / 2.0, HEIGHT / 2.0)).inverse()
                : null;
        var worst = 0;
        var compared = 0;
        var mismatches = new ArrayList<String>();
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                if (!included(comparison, inverse, x, y)) {
                    continue;
                }
                compared++;
                for (var c = 0; c < channels; c++) {
                    var want = expected[(y * WIDTH + x) * channels + c] & 0xFF;
                    var got = actual.pixels()[y * actual.stride() + 4 * x + c] & 0xFF;
                    var diff = Math.abs(want - got);
                    worst = Math.max(worst, diff);
                    if (diff > tolerance(comparison) && mismatches.size() < 8) {
                        mismatches.add("(" + x + "," + y + ")[" + c + "] want " + want + " got " + got);
                    }
                }
            }
        }
        assertTrue(compared > WIDTH * HEIGHT / 4, "too few samples compared: " + compared);
        if (!mismatches.isEmpty()) {
            fail(test.name() + ": worst difference " + worst + " over tolerance " + tolerance(comparison) + ": " + mismatches);
        }
    }

    private static int tolerance(String[] comparison) {
        return switch (comparison[0]) {
            case "exact" -> 0;
            case "max" -> Integer.parseInt(comparison[1]);
            case "interior", "rotated" -> Integer.parseInt(comparison[2]);
            default -> throw new IllegalArgumentException(comparison[0]);
        };
    }

    private static boolean included(String[] comparison, AffineTransform inverse, int x, int y) {
        return switch (comparison[0]) {
            case "exact", "max" -> true;
            case "interior" -> {
                var border = Integer.parseInt(comparison[1]);
                yield x >= border && y >= border && x < WIDTH - border && y < HEIGHT - border;
            }
            case "rotated" -> {
                // the point the output pixel's center maps to, measured from the source pixel centers
                var margin = Integer.parseInt(comparison[1]);
                var u = inverse.mapX(x + 0.5, y + 0.5);
                var v = inverse.mapY(x + 0.5, y + 0.5);
                if (margin == 0) {
                    // nearest: Pillow steps its coordinates in 16.16 fixed point, so points within
                    // 1/1000 of a pixel boundary may land on the neighboring pixel there
                    yield Math.abs(u - Math.rint(u)) > 1e-3 && Math.abs(v - Math.rint(v)) > 1e-3;
                }
                yield u - 0.5 >= margin && v - 0.5 >= margin && u + 0.5 <= WIDTH - margin && v + 0.5 <= HEIGHT - margin;
            }
            default -> throw new IllegalArgumentException(comparison[0]);
        };
    }
}
