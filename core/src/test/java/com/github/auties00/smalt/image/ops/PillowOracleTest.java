package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares the image operations with Pillow 12 outputs used as a black-box oracle. The inputs, the
 * cases and Pillow's outputs come from {@code tools/testdata/imageops-oracle.py}, which also
 * documents how each Pillow call maps to a Smalt one and how each case is compared: exactly, or
 * within a tolerance away from the edges where Pillow handles borders differently (its kernel
 * filters leave the border unfiltered; its interpolating rotations and transforms replicate edges
 * and truncate; its resampling renormalizes the taps clipped at the edges and rounds to 8 bits
 * between its passes). The few cases without a Pillow counterpart (the reference hue rotation, the
 * vignette and the white balance) compare with the formulas the javadoc states, computed by the
 * same script.
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

    record Stat(String input, boolean masked, int band, String[] fields) {
        @Override
        public String toString() {
            return input + (masked ? " masked" : "") + " band " + band;
        }
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var fields : rows("cases.tsv")) {
            cases.add(new Case(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
        }
        return cases;
    }

    static List<Stat> stats() {
        var stats = new ArrayList<Stat>();
        for (var fields : rows("stats.tsv")) {
            stats.add(new Stat(fields[0], fields[1].equals("mask"), Integer.parseInt(fields[2]), fields));
        }
        return stats;
    }

    private static List<String[]> rows(String name) {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource(name), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
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

    static RgbaPicture input(String name) {
        var raw = resource(name + ".bin");
        var alpha = name.equals("rgba") || name.equals("posta");
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

    static Channel8Picture mask() {
        return Channel8Picture.of(WIDTH, HEIGHT, WIDTH, resource("mask.bin"), PixelLayout.GRAY);
    }

    private static double[] numbers(String parameters, int from) {
        var fields = parameters.split(" ");
        var values = new double[fields.length - from];
        for (var i = 0; i < values.length; i++) {
            values[i] = Double.parseDouble(fields[from + i]);
        }
        return values;
    }

    private static Quad pillowQuad(double[] q, int at) {
        // Pillow lists the corners NW, SW, SE, NE
        return new Quad(q[at], q[at + 1], q[at + 6], q[at + 7], q[at + 4], q[at + 5], q[at + 2], q[at + 3]);
    }

    private static List<MeshCell> cells(double[] values) {
        var cells = new ArrayList<MeshCell>();
        for (var at = 2; at < values.length; at += 12) {
            var x0 = (int) values[at];
            var y0 = (int) values[at + 1];
            var target = new Region(x0, y0, (int) values[at + 2] - x0, (int) values[at + 3] - y0);
            cells.add(new MeshCell(target, pillowQuad(values, at + 4)));
        }
        return cells;
    }

    private static Picture run(Case test, RgbaPicture input) {
        var p = test.parameters().isEmpty() ? new String[0] : test.parameters().split(" ");
        var background = input.hasAlpha() ? 0 : 0xFF000000;
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
            case "resize" -> ImageOps.resize(input, Integer.parseInt(p[1]), Integer.parseInt(p[2]), ResizeFilter.valueOf(p[0]));
            case "equalize" -> ImageAdjustments.equalize(input, p.length == 0 ? null : mask());
            case "posterize" -> ImageAdjustments.posterize(input, Integer.parseInt(p[0]));
            case "solarize" -> ImageAdjustments.solarize(input, Integer.parseInt(p[0]));
            case "min" -> ImageFilters.minimum(input, Integer.parseInt(p[0]) / 2);
            case "max" -> ImageFilters.maximum(input, Integer.parseInt(p[0]) / 2);
            case "rank" -> ImageFilters.rank(input, Integer.parseInt(p[0]) / 2, Integer.parseInt(p[1]));
            case "mode" -> ImageFilters.mode(input, Integer.parseInt(p[0]) / 2);
            case "perspective-nearest", "perspective-bilinear" -> ImageOps.perspective(input,
                    PerspectiveTransform.ofCoefficients(numbers(test.parameters(), 2)).inverse(), Integer.parseInt(p[0]),
                    Integer.parseInt(p[1]), interpolation(test), background);
            case "quad-nearest", "quad-bilinear" -> ImageOps.quad(input, pillowQuad(numbers(test.parameters(), 0), 2),
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), interpolation(test), background);
            case "mesh-nearest", "mesh-bilinear" -> ImageOps.mesh(input, cells(numbers(test.parameters(), 0)),
                    Integer.parseInt(p[0]), Integer.parseInt(p[1]), interpolation(test), background);
            case "split" -> ImageOps.split(input).get(Integer.parseInt(p[0]));
            case "merge" -> {
                var planes = ImageOps.split(input);
                yield ImageOps.merge(PixelLayout.RGB, List.of(planes.get(2), planes.get(1), planes.get(0)));
            }
            case "putalpha" -> p[0].equals("mask") ? ImageOps.putAlpha(input, mask())
                    : ImageOps.putAlpha(input, Integer.parseInt(p[0]));
            case "hue" -> ImageAdjustments.rotateHue(input, Double.parseDouble(p[0]));
            case "vignette" -> ImageAdjustments.vignette(input, Double.parseDouble(p[0]), Double.parseDouble(p[1]));
            case "whitebalance" -> ImageAdjustments.whiteBalance(input, Double.parseDouble(p[0]),
                    Double.parseDouble(p[1]));
            default -> throw new IllegalArgumentException(test.operation());
        };
    }

    private static Interpolation interpolation(Case test) {
        return test.operation().endsWith("nearest") ? Interpolation.NEAREST : Interpolation.BILINEAR;
    }

    private static int sample(Picture picture, int x, int y, int c) {
        return switch (picture) {
            case RgbaPicture rgba -> rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFF;
            case Channel8Picture channels -> channels.pixels()[y * channels.stride() + channels.channelCount() * x + c]
                    & 0xFF;
            default -> throw new IllegalArgumentException("unexpected result " + picture);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("matches Pillow")
    void matches(Case test) {
        var input = input(test.input());
        if (test.mode().equals("HIST")) {
            histogram(test, input);
            return;
        }
        var actual = run(test, input);
        var expected = resource(test.name() + ".bin");
        var channels = switch (test.mode()) {
            case "L" -> 1;
            case "RGB" -> 3;
            case "RGBA" -> 4;
            default -> throw new IllegalArgumentException(test.mode());
        };
        var sized = test.operation().equals("resize") ? 1 : test.comparison().startsWith("mapped") ? 0 : -1;
        var p = test.parameters().split(" ");
        var width = sized < 0 ? WIDTH : Integer.parseInt(p[sized]);
        var height = sized < 0 ? HEIGHT : Integer.parseInt(p[sized + 1]);
        assertEquals(width, actual.width());
        assertEquals(height, actual.height());
        assertEquals(width * height * channels, expected.length);
        if (actual instanceof ChannelPicture picture) {
            assertEquals(channels, picture.channelCount());
        } else {
            assertEquals(channels == 4, actual.hasAlpha(), "alpha flag");
        }
        var comparison = test.comparison().split(":");
        var inverse = test.operation().startsWith("rotate")
                ? AffineTransform.translation(-WIDTH / 2.0, -HEIGHT / 2.0)
                .then(AffineTransform.rotation(Double.parseDouble(test.parameters())))
                .then(AffineTransform.translation(WIDTH / 2.0, HEIGHT / 2.0)).inverse()
                : null;
        var worst = 0;
        var compared = 0;
        var mismatches = new ArrayList<String>();
        var point = new double[2];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                if (!included(test, comparison, inverse, x, y, width, height, point)) {
                    continue;
                }
                compared++;
                for (var c = 0; c < channels; c++) {
                    var want = expected[(y * width + x) * channels + c] & 0xFF;
                    var got = sample(actual, x, y, c);
                    var diff = Math.abs(want - got);
                    worst = Math.max(worst, diff);
                    if (diff > tolerance(comparison) && mismatches.size() < 8) {
                        mismatches.add("(" + x + "," + y + ")[" + c + "] want " + want + " got " + got);
                    }
                }
            }
        }
        assertTrue(compared > width * height / 4, "too few samples compared: " + compared);
        if (!mismatches.isEmpty()) {
            fail(test.name() + ": worst difference " + worst + " over tolerance " + tolerance(comparison) + ": " + mismatches);
        }
    }

    private static void histogram(Case test, RgbaPicture input) {
        var histogram = ImageOps.histogram(input, test.parameters().equals("mask") ? mask() : null);
        var raw = ByteBuffer.wrap(resource(test.name() + ".bin")).order(ByteOrder.LITTLE_ENDIAN);
        var expected = new long[raw.remaining() / 4];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = raw.getInt() & 0xFFFFFFFFL;
        }
        assertEquals(expected.length / 256, histogram.channels());
        var actual = new long[expected.length];
        for (var c = 0; c < histogram.channels(); c++) {
            System.arraycopy(histogram.counts(c), 0, actual, c * 256, 256);
        }
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stats")
    @DisplayName("matches ImageStat")
    void statistics(Stat stat) {
        var histogram = ImageOps.histogram(input(stat.input()), stat.masked() ? mask() : null);
        var actual = histogram.statistics(stat.band());
        var f = stat.fields();
        assertEquals(Long.parseLong(f[3]), actual.count(), "count");
        assertEquals(Integer.parseInt(f[4]), actual.minimum(), "minimum");
        assertEquals(Integer.parseInt(f[5]), actual.maximum(), "maximum");
        assertEquals(Integer.parseInt(f[6]), actual.median(), "median");
        assertEquals(Double.parseDouble(f[7]), actual.sum(), "sum");
        assertEquals(Double.parseDouble(f[8]), actual.sumOfSquares(), "sum of squares");
        assertEquals(Double.parseDouble(f[9]), actual.mean(), "mean");
        assertEquals(Double.parseDouble(f[10]), actual.rms(), "rms");
        assertEquals(Double.parseDouble(f[11]), actual.variance(), "variance");
        assertEquals(Double.parseDouble(f[12]), actual.standardDeviation(), "standard deviation");
    }

    private static int tolerance(String[] comparison) {
        return switch (comparison[0]) {
            case "exact" -> 0;
            case "max" -> Integer.parseInt(comparison[1]);
            case "interior", "rotated", "mapped" -> Integer.parseInt(comparison[2]);
            default -> throw new IllegalArgumentException(comparison[0]);
        };
    }

    // the source point Pillow samples for an output pixel of a transform case, NaN when it samples nothing
    private static void pillowPoint(Case test, int x, int y, double[] point) {
        var values = numbers(test.parameters(), 0);
        var xin = x + 0.5;
        var yin = y + 0.5;
        point[0] = Double.NaN;
        point[1] = Double.NaN;
        if (test.operation().startsWith("perspective")) {
            var a = Arrays.copyOfRange(values, 2, 10);
            var w = a[6] * xin + a[7] * yin + 1;
            point[0] = (a[0] * xin + a[1] * yin + a[2]) / w;
            point[1] = (a[3] * xin + a[4] * yin + a[5]) / w;
            return;
        }
        if (test.operation().startsWith("quad")) {
            bilinear(values, 2, 0, 0, (int) values[0], (int) values[1], xin, yin, point);
            return;
        }
        for (var at = 2; at < values.length; at += 12) {
            var x0 = (int) values[at];
            var y0 = (int) values[at + 1];
            var x1 = (int) values[at + 2];
            var y1 = (int) values[at + 3];
            if (x >= x0 && x < x1 && y >= y0 && y < y1) {
                bilinear(values, at + 4, x0, y0, x1 - x0, y1 - y0, x - x0 + 0.5, y - y0 + 0.5, point);
            }
        }
    }

    private static void bilinear(double[] q, int at, int left, int top, int w, int h, double xin, double yin,
                                 double[] point) {
        var x0 = q[at];
        var y0 = q[at + 1];
        var as = 1.0 / w;
        var at2 = 1.0 / h;
        point[0] = x0 + (q[at + 6] - x0) * as * xin + (q[at + 2] - x0) * at2 * yin
                + (q[at + 4] - q[at + 2] - q[at + 6] + x0) * as * at2 * xin * yin;
        point[1] = y0 + (q[at + 7] - y0) * as * xin + (q[at + 3] - y0) * at2 * yin
                + (q[at + 5] - q[at + 3] - q[at + 7] + y0) * as * at2 * xin * yin;
    }

    private static boolean included(Case test, String[] comparison, AffineTransform inverse, int x, int y, int width,
                                    int height, double[] point) {
        return switch (comparison[0]) {
            case "exact", "max" -> true;
            case "interior" -> {
                var border = Integer.parseInt(comparison[1]);
                yield x >= border && y >= border && x < width - border && y < height - border;
            }
            case "rotated", "mapped" -> {
                // the point the output pixel's center maps to, measured from the source pixel centers
                var margin = Integer.parseInt(comparison[1]);
                double u;
                double v;
                if (inverse != null) {
                    u = inverse.mapX(x + 0.5, y + 0.5);
                    v = inverse.mapY(x + 0.5, y + 0.5);
                } else {
                    pillowPoint(test, x, y, point);
                    u = point[0];
                    v = point[1];
                    if (Double.isNaN(u)) {
                        // outside every mesh cell both take the background
                        yield true;
                    }
                }
                if (margin == 0) {
                    // nearest: Pillow steps its rotations in 16.16 fixed point and Smalt inverts the
                    // transforms it is given, so points within 1/1000 of a pixel boundary may land on
                    // the neighboring pixel there
                    yield Math.abs(u - Math.rint(u)) > 1e-3 && Math.abs(v - Math.rint(v)) > 1e-3;
                }
                yield u - 0.5 >= margin && v - 0.5 >= margin && u + 0.5 <= WIDTH - margin && v + 0.5 <= HEIGHT - margin;
            }
            default -> throw new IllegalArgumentException(comparison[0]);
        };
    }
}
