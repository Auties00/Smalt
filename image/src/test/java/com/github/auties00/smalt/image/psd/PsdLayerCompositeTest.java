package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the rendering of a Photoshop composite from layers on documents built in the test: every blend mode in RGB,
 * gray and CMYK at 8, 16 and 32 bits against the blend formulas computed here in double precision, opacity and fill,
 * user, real and rendered vector masks, clipping groups, isolated and pass-through groups, artboards, the features
 * that are refused, and malformed layer data.
 */
@DisplayName("Photoshop layer composite")
class PsdLayerCompositeTest {
    private static final int SIZE = 12;

    private static final ImageDecoderOptions CHANNELS = ImageDecoderOptions.builder().keepChannels(true)
            .highBitDepth(true).build();

    private static final String[] MODES = {"norm", "diss", "dark", "mul ", "idiv", "lbrn", "dkCl", "lite", "scrn",
            "div ", "lddg", "lgCl", "over", "sLit", "hLit", "vLit", "lLit", "pLit", "hMix", "diff", "smud", "fsub",
            "fdiv", "hue ", "sat ", "colr", "lum "};

    /** Encodes values in 0..1 as a plane of the given depth. */
    static byte[] plane(double[] values, int depth) {
        var out = ByteBuffer.allocate(values.length * depth / 8);
        for (var value : values) {
            switch (depth) {
                case 8 -> out.put((byte) Math.round(value * 255));
                case 16 -> out.putShort((short) Math.round(value * 65535));
                default -> out.putFloat((float) value);
            }
        }
        return out.array();
    }

    /** Returns values on the sample grid of a depth, so that encoding them is exact. */
    static double[] random(Random random, int count, int depth) {
        var values = new double[count];
        for (var index = 0; index < count; index++) {
            values[index] = switch (depth) {
                case 8 -> random.nextInt(256) / 255.0;
                case 16 -> random.nextInt(65536) / 65535.0;
                default -> (float) random.nextDouble();
            };
        }
        return values;
    }

    static double[] constant(int count, double value) {
        var values = new double[count];
        java.util.Arrays.fill(values, value);
        return values;
    }

    /** Reads every sample of a decode (keepChannels) scaled to 0..1, with the channel types. */
    record Output(double[] values, int channels, List<ChannelType> types) {
        /**
         * Reads a decode of a document with {@code colors} color channels; a packed RGBA picture of a gray document
         * contributes its red samples.
         */
        static Output of(DecodedImage image, int colors) {
            var width = image.picture().width();
            var height = image.picture().height();
            var pixels = width * height;
            if (image.picture() instanceof ChannelPicture picture) {
                var channels = picture.channelCount();
                var values = new double[pixels * channels];
                for (var index = 0; index < values.length; index++) {
                    values[index] = switch (picture) {
                        case Channel8Picture eight -> (eight.pixels()[index] & 0xFF) / 255.0;
                        case Channel16Picture sixteen -> (sixteen.pixels()[index] & 0xFFFF) / 65535.0;
                        case ChannelFloatPicture floats -> floats.pixels()[index];
                    };
                }
                var types = picture.layout().channels().stream().map(channel -> channel.type()).toList();
                return new Output(values, channels, types);
            }
            var alpha = image.picture().hasAlpha();
            var channels = colors + (alpha ? 1 : 0);
            var values = new double[pixels * channels];
            for (var pixel = 0; pixel < pixels; pixel++) {
                for (var channel = 0; channel < channels; channel++) {
                    var source = channel < colors ? channel : 3;
                    values[pixel * channels + channel] = switch (image.picture()) {
                        case com.github.auties00.smalt.image.RgbaPicture rgba ->
                                (rgba.pixels()[(pixel / width) * rgba.stride() + (pixel % width) * 4 + source] & 0xFF)
                                        / 255.0;
                        case com.github.auties00.smalt.image.Rgba16Picture wide ->
                                (wide.pixels()[(pixel / width) * wide.stride() + (pixel % width) * 4 + source]
                                        & 0xFFFF) / 65535.0;
                        default -> throw new AssertionError(image.picture());
                    };
                }
            }
            var types = new ArrayList<ChannelType>(colors == 1 ? List.of(ChannelType.GRAY)
                    : List.of(ChannelType.RED, ChannelType.GREEN, ChannelType.BLUE));
            if (alpha) {
                types.add(ChannelType.ALPHA);
            }
            return new Output(values, channels, types);
        }

        double at(int pixel, int channel) {
            return values[pixel * channels + channel];
        }
    }

    static Output decode(byte[] file) {
        return decode(file, CHANNELS);
    }

    static Output decode(byte[] file, ImageDecoderOptions options) {
        var mode = (file[24] & 0xFF) << 8 | (file[25] & 0xFF);
        var colors = mode == 3 ? 3 : mode == 4 ? 4 : 1;
        return Output.of(PsdDecoder.decode(MediaSource.of(file), options), colors);
    }

    static PsdLayeredFiles.Layer layer(int top, int left, int bottom, int right) {
        return new PsdLayeredFiles.Layer().rect(top, left, bottom, right);
    }

    /** Adds every color channel and, unless {@code alpha} is null, the transparency channel. */
    static PsdLayeredFiles.Layer pixels(PsdLayeredFiles.Layer layer, int depth, double[][] colors, double[] alpha) {
        for (var channel = 0; channel < colors.length; channel++) {
            layer.channel(channel, plane(colors[channel], depth));
        }
        if (alpha != null) {
            layer.channel(-1, plane(alpha, depth));
        }
        return layer;
    }

    /** A layer of one color, the whole document, optionally with a constant transparency. */
    static PsdLayeredFiles.Layer solid(int width, int height, double[] color, double alpha) {
        var count = width * height;
        var colors = new double[color.length][];
        for (var channel = 0; channel < color.length; channel++) {
            colors[channel] = constant(count, color[channel]);
        }
        return pixels(layer(0, 0, height, width), 8, colors, alpha >= 1 ? null : constant(count, alpha));
    }

    // The blend functions B(cb, cs), written from ISO 32000-1 section 11.3.5 and Photoshop's documented modes.

    static double separable(String mode, double cb, double cs) {
        return switch (mode) {
            case "dark" -> Math.min(cb, cs);
            case "mul " -> cb * cs;
            case "idiv" -> cb == 1 ? 1 : cs == 0 ? 0 : 1 - Math.min(1, (1 - cb) / cs);
            case "lbrn" -> Math.max(0, cb + cs - 1);
            case "lite" -> Math.max(cb, cs);
            case "scrn" -> 1 - (1 - cb) * (1 - cs);
            case "div " -> cb == 0 ? 0 : cs == 1 ? 1 : Math.min(1, cb / (1 - cs));
            case "lddg" -> Math.min(1, cb + cs);
            case "over" -> cb <= 0.5 ? 2 * cb * cs : 1 - 2 * (1 - cb) * (1 - cs);
            case "hLit" -> cs <= 0.5 ? 2 * cb * cs : 1 - 2 * (1 - cb) * (1 - cs);
            case "sLit" -> {
                if (cs <= 0.5) {
                    yield cb - (1 - 2 * cs) * cb * (1 - cb);
                }
                var d = cb <= 0.25 ? ((16 * cb - 12) * cb + 4) * cb : Math.sqrt(cb);
                yield cb + (2 * cs - 1) * (d - cb);
            }
            case "vLit" -> cs == 0 ? 0 : cs == 1 ? 1 : cs <= 0.5 ? 1 - Math.min(1, (1 - cb) / (2 * cs))
                    : Math.min(1, cb / (2 * (1 - cs)));
            case "lLit" -> Math.max(0, Math.min(1, cb + 2 * cs - 1));
            case "pLit" -> cs > 0.5 ? Math.max(cb, 2 * cs - 1) : Math.min(cb, 2 * cs);
            case "hMix" -> {
                var total = cb + cs;
                yield Math.abs(total - 1) < 1e-9 ? (cb > 0.5 ? 1 : 0) : total > 1 ? 1 : 0;
            }
            case "diff" -> Math.abs(cb - cs);
            case "smud" -> cb + cs - 2 * cb * cs;
            case "fsub" -> Math.max(0, cb - cs);
            case "fdiv" -> cs == 0 ? (cb == 0 ? 0 : 1) : Math.min(1, cb / cs);
            default -> cs;
        };
    }

    static double lum(double[] c) {
        return 0.3 * c[0] + 0.59 * c[1] + 0.11 * c[2];
    }

    static double[] clip(double[] c) {
        var l = lum(c);
        var n = Math.min(c[0], Math.min(c[1], c[2]));
        var x = Math.max(c[0], Math.max(c[1], c[2]));
        var out = c.clone();
        if (n < 0) {
            for (var i = 0; i < 3; i++) {
                out[i] = l + (out[i] - l) * l / (l - n);
            }
        }
        if (x > 1) {
            for (var i = 0; i < 3; i++) {
                out[i] = l + (out[i] - l) * (1 - l) / (x - l);
            }
        }
        for (var i = 0; i < 3; i++) {
            out[i] = Math.max(0, Math.min(1, out[i]));
        }
        return out;
    }

    static double[] setLum(double[] c, double l) {
        var d = l - lum(c);
        return clip(new double[]{c[0] + d, c[1] + d, c[2] + d});
    }

    static double sat(double[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    static double[] setSat(double[] c, double s) {
        var order = new Integer[]{0, 1, 2};
        java.util.Arrays.sort(order, (a, b) -> Double.compare(c[a], c[b]));
        var out = new double[3];
        var min = c[order[0]];
        var mid = c[order[1]];
        var max = c[order[2]];
        if (max > min) {
            out[order[1]] = (mid - min) * s / (max - min);
            out[order[2]] = s;
        }
        return out;
    }

    /** Returns B(cb, cs) of a pixel of {@code channels} colors. */
    static double[] blend(String mode, double[] cb, double[] cs) {
        var n = cb.length;
        var out = new double[n];
        switch (mode) {
            case "dkCl", "lgCl" -> {
                var lb = lightness(cb);
                var ls = lightness(cs);
                var pick = mode.equals("dkCl") ? ls < lb : ls > lb;
                return (pick ? cs : cb).clone();
            }
            case "hue ", "sat ", "colr", "lum " -> {
                if (n == 1) {
                    return mode.equals("lum ") ? cs.clone() : cb.clone();
                }
                var b = new double[]{cb[0], cb[1], cb[2]};
                var s = new double[]{cs[0], cs[1], cs[2]};
                var rgb = switch (mode) {
                    case "hue " -> setLum(setSat(s, sat(b)), lum(b));
                    case "sat " -> setLum(setSat(b, sat(s)), lum(b));
                    case "colr" -> setLum(s, lum(b));
                    default -> setLum(b, lum(s));
                };
                System.arraycopy(rgb, 0, out, 0, 3);
                if (n == 4) {
                    out[3] = mode.equals("lum ") ? cs[3] : cb[3];
                }
                return out;
            }
            default -> {
                for (var i = 0; i < n; i++) {
                    out[i] = separable(mode, cb[i], cs[i]);
                }
                return out;
            }
        }
    }

    private static double lightness(double[] c) {
        return switch (c.length) {
            case 1 -> c[0];
            case 4 -> lum(new double[]{c[0] * c[3], c[1] * c[3], c[2] * c[3]});
            default -> lum(c);
        };
    }

    static Stream<Arguments> modes() {
        var arguments = new ArrayList<Arguments>();
        for (var mode : MODES) {
            if (mode.equals("diss")) {
                continue;
            }
            for (var colorMode : new int[]{3, 1, 4}) {
                for (var depth : new int[]{8, 16, 32}) {
                    arguments.add(Arguments.of(mode, colorMode, depth));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "''{0}'' mode {1} depth {2}")
    @MethodSource("modes")
    @DisplayName("blends a translucent layer over an opaque one as the blend formula says")
    void blendModes(String mode, int colorMode, int depth) {
        var colors = colorMode == 3 ? 3 : colorMode == 4 ? 4 : 1;
        var random = new Random(mode.hashCode() * 31L + colorMode * 7L + depth);
        var count = SIZE * SIZE;
        var bottom = new double[colors][];
        var top = new double[colors][];
        for (var channel = 0; channel < colors; channel++) {
            bottom[channel] = random(random, count, depth);
            top[channel] = random(random, count, depth);
        }
        var alpha = random(random, count, depth);
        var opacity = 200;
        var file = new PsdLayeredFiles(1, SIZE, SIZE, depth, colorMode).opaqueComposite()
                .add(pixels(layer(0, 0, SIZE, SIZE), depth, bottom, null).name("Background"))
                .add(pixels(layer(0, 0, SIZE, SIZE), depth, top, alpha).blend(mode).opacity(opacity));
        if (depth > 8) {
            file.tagged();
        }
        var output = decode(file.build());
        assertEquals(colors, output.channels(), "an opaque composite has no transparency channel");
        var step = depth == 8 ? 1 / 255.0 : depth == 16 ? 1 / 65535.0 : 0;
        var tolerance = depth == 32 ? 2e-5 : step + 1e-9;
        for (var pixel = 0; pixel < count; pixel++) {
            var cb = new double[colors];
            var cs = new double[colors];
            for (var channel = 0; channel < colors; channel++) {
                cb[channel] = bottom[channel][pixel];
                cs[channel] = top[channel][pixel];
            }
            var as = alpha[pixel] * (depth == 32 ? (float) (opacity / 255.0) : opacity / 255.0);
            var blended = blend(mode, cb, cs);
            for (var channel = 0; channel < colors; channel++) {
                var expected = (1 - as) * cb[channel] + as * blended[channel];
                var actual = output.at(pixel, channel);
                if (colorMode == 4) {
                    actual = 1 - actual;
                }
                assertEquals(expected, actual, tolerance, "mode '" + mode + "' pixel " + pixel + " channel " + channel
                        + " backdrop " + java.util.Arrays.toString(cb) + " source " + java.util.Arrays.toString(cs));
            }
        }
    }

    @Test
    @DisplayName("dissolves a layer into whole pixels, as many as its opacity, the same way on every decode")
    void dissolve() {
        var width = 64;
        var file = new PsdLayeredFiles(1, width, width, 8, 3).opaqueComposite()
                .add(solid(width, width, new double[]{0, 0, 1}, 1).name("Background"))
                .add(solid(width, width, new double[]{1, 0, 0}, 1).blend("diss").opacity(102))
                .build();
        var output = decode(file);
        var red = 0;
        for (var pixel = 0; pixel < width * width; pixel++) {
            var r = output.at(pixel, 0);
            var b = output.at(pixel, 2);
            assertTrue((r == 1 && b == 0) || (r == 0 && b == 1), "pixel " + pixel + " is either layer");
            red += r == 1 ? 1 : 0;
        }
        assertEquals(102 / 255.0, red / (double) (width * width), 0.04);
        assertArrayEquals(output.values(), decode(file).values());
        var stacked = decode(new PsdLayeredFiles(1, width, width, 8, 3).opaqueComposite()
                .add(solid(width, width, new double[]{0, 0, 1}, 1).name("Background"))
                .add(solid(width, width, new double[]{1, 0, 0}, 1).blend("diss").opacity(102))
                .add(solid(width, width, new double[]{0, 1, 0}, 0.6).blend("diss").opacity(102))
                .build());
        for (var pixel = 0; pixel < width * width; pixel++) {
            var shown = output.at(pixel, 0) == 1;
            var expected = shown ? new double[]{0.4, 0.6, 0} : new double[]{0, 0, 1};
            for (var channel = 0; channel < 3; channel++) {
                assertEquals(expected[channel], stacked.at(pixel, channel), 0.5 / 255 + 1e-6,
                        "dissolving layers share the noise and keep their own alpha, pixel " + pixel);
            }
        }
    }

    @Nested
    @DisplayName("opacity and masks")
    class Opacity {
        @Test
        @DisplayName("multiplies the transparency by the opacity and the fill opacity over a transparent canvas")
        void opacityAndFill() {
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3)
                    .add(solid(4, 4, new double[]{0.2, 0.4, 0.6}, 0.8).opacity(128).fill(64))
                    .build();
            var output = decode(file);
            assertEquals(4, output.channels());
            assertEquals(ChannelType.ALPHA, output.types().get(3));
            for (var pixel = 0; pixel < 16; pixel++) {
                assertEquals(0.2, output.at(pixel, 0), 0.5 / 255);
                assertEquals(0.8 * 128 / 255 * 64 / 255, output.at(pixel, 3), 0.5 / 255 + 1e-9);
            }
        }

        @ParameterizedTest(name = "flags {0}, background {1}, density {2}")
        @CsvSource({"0,0,", "0,255,", "4,0,", "0,0,128", "2,0,"})
        @DisplayName("applies a user mask over its rectangle, with its background, inversion and density")
        void userMask(int flags, int background, Integer density) {
            var maskPlane = new byte[2 * 3];
            for (var index = 0; index < maskPlane.length; index++) {
                maskPlane[index] = (byte) (index * 40);
            }
            var top = solid(4, 4, new double[]{1, 0, 0}, 1).mask(new int[]{1, 1, 3, 4}, background, flags,
                    maskPlane, density);
            var output = decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(top).build());
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    double mask;
                    if (y >= 1 && y < 3 && x >= 1) {
                        mask = (maskPlane[(y - 1) * 3 + x - 1] & 0xFF) / 255.0;
                    } else {
                        mask = background / 255.0;
                    }
                    if ((flags & 4) != 0) {
                        mask = 1 - mask;
                    }
                    if (density != null) {
                        mask = density / 255.0 * mask + 1 - density / 255.0;
                    }
                    if ((flags & 2) != 0) {
                        mask = 1;
                    }
                    assertEquals(mask, output.at(y * 4 + x, 3), 0.5 / 255 + 1e-6, "pixel " + x + "," + y);
                }
            }
        }

        @ParameterizedTest(name = "vector mask enabled {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("applies the user and vector masks rendered together, or the real user mask alone when the vector"
                + " mask is disabled")
        void realMask(boolean vectorEnabled) {
            var layer = solid(4, 4, new double[]{0, 1, 0}, 1);
            var vector = new byte[16];
            var user = new byte[16];
            for (var index = 0; index < 16; index++) {
                vector[index] = (byte) (index < 8 ? 255 : 51);
                user[index] = (byte) (index % 2 == 0 ? 255 : 102);
            }
            layer.channel(-2, vector).channel(-3, user);
            var mask = ByteBuffer.allocate(36);
            mask.putInt(0).putInt(0).putInt(4).putInt(4).put((byte) 0).put((byte) 8);
            mask.put((byte) 0).put((byte) 0).putInt(0).putInt(0).putInt(4).putInt(4);
            layer.maskData(mask.array());
            layer.block("vmsk", ByteBuffer.allocate(8).putInt(3).putInt(vectorEnabled ? 0 : 4).array());
            var output = decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(layer).build());
            for (var pixel = 0; pixel < 16; pixel++) {
                var expected = vectorEnabled ? (vector[pixel] & 0xFF) / 255.0 : (user[pixel] & 0xFF) / 255.0;
                assertEquals(expected, output.at(pixel, 3), 0.5 / 255 + 1e-6, "pixel " + pixel);
            }
        }

        @Test
        @DisplayName("ignores the vector mask of a shape layer, whose pixels carry the shape")
        void shapeLayer() {
            var layer = solid(4, 4, new double[]{0, 0, 1}, 0.5).block("SoCo", new byte[4])
                    .block("vmsk", ByteBuffer.allocate(8).putInt(3).putInt(0).array());
            var output = decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(layer).build());
            assertEquals(0.5, output.at(5, 3), 0.5 / 255 + 1e-6);
        }

        @Test
        @DisplayName("clips layers that reach past the canvas and composites the part inside")
        void outside() {
            var colors = new double[][]{constant(6 * 6, 1), constant(6 * 6, 0.5), constant(6 * 6, 0)};
            var layer = pixels(layer(-3, -2, 3, 4), 8, colors, null);
            var output = decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(layer).build());
            for (var y = 0; y < 4; y++) {
                for (var x = 0; x < 4; x++) {
                    assertEquals(y < 3 ? 1 : 0, output.at(y * 4 + x, 3), 1e-9, "pixel " + x + "," + y);
                }
            }
        }
    }

    @Nested
    @DisplayName("clipping groups")
    class Clipping {
        private PsdLayeredFiles document(boolean baseHidden, boolean memberHidden, String memberMode) {
            var base = solid(4, 4, new double[]{0, 0, 1}, 0.5).opacity(255);
            base.channel(-1, plane(new double[]{0, 0, 0, 0, 0.5, 0.5, 0.5, 0.5, 1, 1, 1, 1, 1, 1, 1, 1}, 8));
            return new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{1, 1, 1}, 1).name("Background"))
                    .add(base.hidden(baseHidden))
                    .add(solid(4, 4, new double[]{1, 0, 0}, 1).clipping(true).hidden(memberHidden)
                            .blend(memberMode))
                    .add(solid(4, 4, new double[]{0, 1, 0}, 0.5).clipping(true).opacity(128));
        }

        @Test
        @DisplayName("paints the clipped layers within the base's transparency")
        void clipped() {
            var output = decode(document(false, false, "norm").build());
            for (var pixel = 0; pixel < 16; pixel++) {
                var a = pixel < 4 ? 0 : pixel < 8 ? 0.5 : 1;
                var green = 0.5 * 128 / 255.0;
                var r = 1 - green;
                var g = green;
                var expected = new double[]{(1 - a) + a * r, (1 - a) + a * g, 1 - a};
                for (var channel = 0; channel < 3; channel++) {
                    assertEquals(expected[channel], output.at(pixel, channel), 1.5 / 255,
                            "pixel " + pixel + " channel " + channel);
                }
            }
        }

        @Test
        @DisplayName("blends the clipped layers with the base's color")
        void blended() {
            var output = decode(document(false, true, "mul ").build());
            var green = 0.5 * 128 / 255.0;
            assertEquals(1, output.at(1, 0), 1e-9);
            assertEquals(0, output.at(12, 0), 1.5 / 255);
            assertEquals(green, output.at(12, 1), 1.5 / 255);
            assertEquals(1 - green, output.at(12, 2), 1.5 / 255);
            var multiplied = decode(document(false, false, "mul ").build());
            assertEquals(0, multiplied.at(12, 0), 1.5 / 255, "blue multiplied by red is black");
            assertEquals(green, multiplied.at(12, 1), 1.5 / 255);
            assertEquals(0, multiplied.at(12, 2), 1.5 / 255);
            assertEquals(0.5 + 0.5 * green, multiplied.at(4, 1), 1.5 / 255, "half-transparent base row");
        }

        @Test
        @DisplayName("hides the clipped layers with their base")
        void hiddenBase() {
            var output = decode(document(true, false, "norm").build());
            for (var index = 0; index < output.values().length; index++) {
                assertEquals(1, output.values()[index], 1e-9);
            }
        }

        @Test
        @DisplayName("refuses clipped layers that do not blend as a group")
        void notAsGroup() {
            var file = document(false, false, "norm");
            var built = new PsdLayeredFiles(1, 4, 4, 8, 3)
                    .add(solid(4, 4, new double[]{0, 0, 1}, 1).block("clbl", new byte[]{0, 0, 0, 0}))
                    .add(solid(4, 4, new double[]{1, 0, 0}, 1).clipping(true)).build();
            assertNotNull(file);
            assertThrows(MediaException.Unsupported.class, () -> decode(built));
        }
    }

    @Nested
    @DisplayName("groups")
    class Groups {
        private PsdLayeredFiles document(String groupMode, String layerMode, int opacity) {
            return new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{0.8, 0.6, 0.4}, 1).name("Background"))
                    .open()
                    .add(solid(4, 4, new double[]{0.5, 0.5, 1}, 1).blend(layerMode))
                    .close("Group", groupMode, opacity);
        }

        @Test
        @DisplayName("composites an isolated group's layers onto transparency, then the group with its mode")
        void isolated() {
            var normal = decode(document("norm", "mul ", 255).build());
            assertEquals(0.5, normal.at(0, 0), 0.5 / 255 + 1e-6);
            var multiplied = decode(document("mul ", "norm", 255).build());
            assertEquals(0.4, multiplied.at(0, 0), 0.5 / 255 + 1e-6);
            assertEquals(0.4, multiplied.at(0, 2), 0.5 / 255 + 1e-6);
        }

        @Test
        @DisplayName("blends a pass-through group's layers with the layers below it")
        void passThrough() {
            var output = decode(document("pass", "mul ", 255).build());
            assertEquals(0.4, output.at(0, 0), 0.5 / 255 + 1e-6);
            assertEquals(0.3, output.at(0, 1), 0.5 / 255 + 1e-6);
            assertEquals(0.4, output.at(0, 2), 0.5 / 255 + 1e-6);
        }

        @Test
        @DisplayName("mixes the canvas before and after a pass-through group by its opacity and mask")
        void passThroughOpacity() {
            var group = new PsdLayeredFiles.Layer().name("Group").opacity(128);
            var maskPlane = new byte[]{(byte) 255, 0, (byte) 255, 0};
            group.mask(new int[]{0, 0, 1, 4}, 255, 0, maskPlane, null);
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{0.8, 0.6, 0.4}, 1).name("Background"))
                    .open()
                    .add(solid(4, 4, new double[]{0.5, 0.5, 1}, 1).blend("mul "))
                    .close(group, "pass")
                    .build();
            var output = decode(file);
            var m = 128 / 255.0;
            assertEquals((1 - m) * 0.8 + m * 0.4, output.at(0, 0), 0.5 / 255 + 1e-6);
            assertEquals(0.8, output.at(1, 0), 0.5 / 255 + 1e-6);
            assertEquals((1 - m) * 0.8 + m * 0.4, output.at(5, 0), 0.5 / 255 + 1e-6);
        }

        @Test
        @DisplayName("skips hidden and empty groups and composites nested ones")
        void nested() {
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{0.2, 0.2, 0.2}, 1).name("Background"))
                    .open()
                    .open()
                    .add(solid(4, 4, new double[]{0, 0, 0}, 1))
                    .close(new PsdLayeredFiles.Layer().name("Hidden").hidden(true), "norm")
                    .open()
                    .close("Empty", "pass", 255)
                    .open()
                    .add(solid(4, 4, new double[]{0.5, 0.5, 0.5}, 1).blend("mul "))
                    .close("Inner", "pass", 255)
                    .close("Outer", "scrn", 255)
                    .build();
            var output = decode(file);
            assertEquals(0.6, output.at(0, 0), 0.5 / 255 + 1e-6);
        }

        @Test
        @DisplayName("fills an artboard with its background color and clips its layers to it")
        void artboard() throws IOException {
            var descriptor = new Descriptor()
                    .object("artboardRect", new Descriptor().doub("Top ", 1).doub("Left", 1).doub("Btom", 3)
                            .doub("Rght", 3))
                    .integer("artboardBackgroundType", 1);
            var artboard = new PsdLayeredFiles.Layer().name("Artboard").block("artb", descriptor.versioned());
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3)
                    .open()
                    .add(solid(4, 4, new double[]{1, 0, 0}, 0.5))
                    .close(artboard, "pass")
                    .build();
            var output = decode(file);
            assertEquals(0, output.at(0, 3), 1e-9);
            assertEquals(1, output.at(5, 3), 1e-9);
            assertEquals(1, output.at(5, 0), 1e-9);
            assertEquals(0.5, output.at(5, 1), 0.5 / 255 + 1e-6);
            assertEquals(0, output.at(15, 3), 1e-9);
            var transparent = new Descriptor()
                    .object("artboardRect", new Descriptor().doub("Top ", 1).doub("Left", 1).doub("Btom", 3)
                            .doub("Rght", 3))
                    .integer("artboardBackgroundType", 3);
            var clear = decode(new PsdLayeredFiles(1, 4, 4, 8, 3)
                    .open()
                    .add(solid(4, 4, new double[]{1, 0, 0}, 0.5))
                    .close(new PsdLayeredFiles.Layer().name("Artboard").block("artb", transparent.versioned()), "pass")
                    .build());
            assertEquals(0.5, clear.at(5, 3), 0.5 / 255 + 1e-6);
            assertEquals(0, clear.at(0, 3), 1e-9);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {
        private byte[] with(PsdLayeredFiles.Layer layer) {
            return new PsdLayeredFiles(1, 4, 4, 8, 3).add(layer).build();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"levl", "curv", "hue2", "expA", "selc"})
        @DisplayName("refuses visible adjustment layers")
        void adjustment(String key) {
            var file = with(solid(4, 4, new double[]{1, 1, 1}, 1).block(key, new byte[8]));
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(file));
            assertTrue(exception.getMessage().contains("adjustment"), exception.getMessage());
            var hidden = with(solid(4, 4, new double[]{1, 1, 1}, 1).block(key, new byte[8]).hidden(true));
            assertEquals(4, decode(hidden).channels());
        }

        @Test
        @DisplayName("refuses enabled layer effects and accepts disabled ones")
        void effects() throws IOException {
            var enabled = new Descriptor().bool("masterFXSwitch", true)
                    .object("DrSh", new Descriptor().bool("enab", true));
            var off = new Descriptor().bool("masterFXSwitch", false)
                    .object("DrSh", new Descriptor().bool("enab", true));
            var disabled = new Descriptor().object("DrSh", new Descriptor().bool("enab", false))
                    .list("dropShadowMulti", new Descriptor().bool("enab", false).bool("present", true));
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lfx2", effects(enabled)))));
            assertEquals(4, decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lfx2", effects(off))))
                    .channels());
            assertEquals(4, decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lmfx", effects(disabled))))
                    .channels());
            var legacy = ByteBuffer.allocate(4 + 12 + 7 + 12 + 51);
            legacy.putShort((short) 0).putShort((short) 2);
            legacy.put("8BIMcmnS".getBytes(StandardCharsets.ISO_8859_1)).putInt(7).putInt(0).put((byte) 1)
                    .putShort((short) 0);
            legacy.put("8BIMdsdw".getBytes(StandardCharsets.ISO_8859_1)).putInt(51);
            var shadow = new byte[51];
            shadow[38] = 1;
            legacy.put(shadow);
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lrFX", legacy.array()))));
        }

        private byte[] effects(Descriptor descriptor) throws IOException {
            var body = descriptor.versioned();
            var out = new byte[4 + body.length];
            System.arraycopy(body, 0, out, 4, body.length);
            return out;
        }

        @ParameterizedTest(name = "''{0}''")
        @ValueSource(strings = {"idiv", "lbrn", "div ", "lddg", "vLit", "lLit", "hMix", "diff"})
        @DisplayName("refuses a reduced fill opacity in the modes that apply it inside the blend")
        void fillSensitive(String mode) {
            var file = with(solid(4, 4, new double[]{0.5, 0.5, 0.5}, 1).blend(mode).fill(128));
            var exception = assertThrows(MediaException.Unsupported.class, () -> decode(file));
            assertTrue(exception.getMessage().contains("fill opacity"), exception.getMessage());
            assertEquals(4, decode(with(solid(4, 4, new double[]{0.5, 0.5, 0.5}, 1).blend(mode).opacity(128)))
                    .channels());
            assertEquals(4, decode(with(solid(4, 4, new double[]{0.5, 0.5, 0.5}, 1).blend("mul ").fill(128)))
                    .channels());
        }

        @Test
        @DisplayName("refuses visible layers whose effects descriptor it cannot read")
        void unreadableEffects() {
            var garbage = new byte[]{0, 0, 0, 0, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0,
                    0, 0, 'k', 'e', 'y', ' ', '?', '?', '?', '?'};
            var exception = assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lfx2", garbage))));
            assertTrue(exception.getMessage().contains("cannot read"), exception.getMessage());
            assertEquals(4, decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("lfx2", garbage).hidden(true)))
                    .channels());
        }

        @Test
        @DisplayName("refuses knockout, Blend If ranges, channel restrictions and feathered masks")
        void advanced() {
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("knko", new byte[]{1, 0, 0, 0}))));
            var ranges = new byte[32];
            for (var at = 0; at < 32; at += 4) {
                ranges[at + 2] = (byte) 255;
                ranges[at + 3] = (byte) 255;
            }
            ranges[1] = 40;
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).ranges(ranges))));
            assertThrows(MediaException.Unsupported.class,
                    () -> decode(with(solid(4, 4, new double[]{1, 1, 1}, 1).block("brst", new byte[4]))));
            var mask = ByteBuffer.allocate(28);
            mask.putInt(0).putInt(0).putInt(4).putInt(4).put((byte) 0).put((byte) 16).put((byte) 2)
                    .putDouble(2.5);
            var layer = solid(4, 4, new double[]{1, 1, 1}, 1).channel(-2, new byte[16]).maskData(mask.array());
            assertThrows(MediaException.Unsupported.class, () -> decode(with(layer)));
        }

        @Test
        @DisplayName("refuses the layers of a document whose color mode is not composited")
        void lab() {
            var file = new PsdLayeredFiles(1, 4, 4, 8, 9).add(solid(4, 4, new double[]{1, 0.5, 0.5}, 1)).build();
            assertThrows(MediaException.Unsupported.class, () -> decode(file));
        }
    }

    @Nested
    @DisplayName("documents")
    class Documents {
        @Test
        @DisplayName("gives the rendered composite the transparency the layer count declares, flattening it otherwise")
        void probe() {
            var transparent = new PsdLayeredFiles(1, 4, 4, 8, 3).add(solid(4, 4, new double[]{1, 0, 0}, 1)).build();
            assertTrue(PsdDecoder.probe(MediaSource.of(transparent)).hasAlpha());
            var opaque = new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{1, 0, 0}, 1)).build();
            assertFalse(PsdDecoder.probe(MediaSource.of(opaque)).hasAlpha());
            assertFalse(PsdDecoder.decode(MediaSource.of(opaque)).picture().hasAlpha());
            var hidden = new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{1, 0, 0}, 1).hidden(true)).build();
            assertFalse(PsdDecoder.probe(MediaSource.of(hidden)).hasAlpha());
            assertEquals(1, decode(hidden).at(0, 0), "an empty composite without transparency is white");
            var translucent = decode(new PsdLayeredFiles(1, 4, 4, 8, 3).opaqueComposite()
                    .add(solid(4, 4, new double[]{1, 0, 0}, 0.4)).build());
            assertEquals(3, translucent.channels());
            assertEquals(1, translucent.at(0, 0), 1e-9);
            assertEquals(0.6, translucent.at(0, 1), 0.5 / 255 + 1e-6, "flattened onto white");
        }

        @ParameterizedTest(name = "version {0}, compression {1}, depth {2}")
        @CsvSource({"1,0,8", "1,1,8", "1,2,8", "1,3,8", "2,1,8", "2,3,16", "1,3,32", "2,0,32", "1,1,16"})
        @DisplayName("reads layer channels in every compression, depth and version")
        void compressions(int version, int compression, int depth) {
            var random = new Random(version * 100 + compression * 10 + depth);
            var colors = new double[][]{random(random, 30, depth), random(random, 30, depth),
                    random(random, 30, depth)};
            var alpha = random(random, 30, depth);
            var file = new PsdLayeredFiles(version, 6, 5, depth, 3).compression(compression)
                    .add(pixels(layer(0, 0, 5, 6), depth, colors, alpha));
            if (depth != 8) {
                file.tagged();
            }
            var output = decode(file.build());
            var tolerance = depth == 32 ? 1e-6 : 0.5 / (depth == 8 ? 255 : 65535) + 1e-9;
            for (var pixel = 0; pixel < 30; pixel++) {
                assertEquals(alpha[pixel], output.at(pixel, 3), tolerance, "alpha of " + pixel);
                for (var channel = 0; channel < 3; channel++) {
                    assertEquals(colors[channel][pixel], output.at(pixel, channel), tolerance,
                            "pixel " + pixel + " channel " + channel);
                }
            }
        }

        @Test
        @DisplayName("keeps the document's alpha channels after the rendered composite")
        void extraChannels() {
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3).extraAlphaChannels(2)
                    .add(solid(4, 4, new double[]{1, 0, 0}, 1)).build();
            var output = decode(file);
            assertEquals(List.of(ChannelType.RED, ChannelType.GREEN, ChannelType.BLUE, ChannelType.ALPHA,
                    ChannelType.SELECTION_MASK, ChannelType.SELECTION_MASK), output.types());
            assertEquals((0x40 + 4) / 255.0, output.at(3, 4), 1e-9);
            assertEquals((0x40 + 5) / 255.0, output.at(3, 5), 1e-9);
            var packed = Images.decode(MediaSource.of(file));
            assertTrue(packed.picture().hasAlpha());
            assertArrayEquals(new byte[]{(byte) 255, 0, 0, (byte) 255},
                    java.util.Arrays.copyOf(RasterTesting.tight(packed.picture()), 4));
        }

        @Test
        @DisplayName("renders gray and CMYK documents and converts them like a saved composite")
        void otherModes() {
            var gray = new PsdLayeredFiles(1, 4, 4, 8, 1).opaqueComposite()
                    .add(solid(4, 4, new double[]{0.25}, 1)).build();
            assertEquals(0.25, decode(gray).at(0, 0), 0.5 / 255);
            var cmyk = new PsdLayeredFiles(1, 4, 4, 8, 4).opaqueComposite()
                    .add(solid(4, 4, new double[]{1, 0.5, 1, 1}, 1)).build();
            var output = decode(cmyk);
            assertEquals(0, output.at(0, 0), 1e-9);
            assertEquals(0.5, output.at(0, 1), 0.5 / 255);
        }

        @Test
        @DisplayName("decodes a region of the rendered composite")
        void region() {
            var random = new Random(5);
            var colors = new double[][]{random(random, 64, 8), random(random, 64, 8), random(random, 64, 8)};
            var file = new PsdLayeredFiles(1, 8, 8, 8, 3).add(pixels(layer(0, 0, 8, 8), 8, colors, null)).build();
            var whole = decode(file);
            var region = decode(file, CHANNELS.toBuilder().region(2, 3, 4, 2).build());
            for (var y = 0; y < 2; y++) {
                for (var x = 0; x < 4; x++) {
                    for (var channel = 0; channel < 4; channel++) {
                        assertEquals(whole.at((y + 3) * 8 + x + 2, channel), region.at(y * 4 + x, channel));
                    }
                }
            }
        }

        @Test
        @DisplayName("rejects truncated and corrupted layer data with a media exception")
        void malformed() {
            var random = new Random(9);
            var colors = new double[][]{random(random, 64, 8), random(random, 64, 8), random(random, 64, 8)};
            var file = new PsdLayeredFiles(1, 8, 8, 8, 3).open()
                    .add(pixels(layer(0, 0, 8, 8), 8, colors, random(random, 64, 8)).blend("over")
                            .mask(new int[]{0, 0, 8, 8}, 0, 0, new byte[64], 100))
                    .close("Group", "pass", 200).build();
            for (var length = 0; length < file.length; length += 7) {
                var truncated = java.util.Arrays.copyOf(file, length);
                try {
                    PsdDecoder.decode(MediaSource.of(truncated));
                } catch (MediaException expected) {
                    // the only failure allowed
                }
            }
            RasterTesting.mutate(List.of(file), 300, 11, source -> PsdDecoder.decode(source));
        }

        @Test
        @DisplayName("rejects oversized rectangles, short mask parameters and a vector mask without its rendering")
        void malformedFields() {
            var file = new PsdLayeredFiles(1, 4, 4, 8, 3).add(solid(4, 4, new double[]{1, 0, 0}, 1)).build();
            var record = ByteBuffer.allocate(18).putInt(0).putInt(0).putInt(4).putInt(4).putShort((short) 3)
                    .array();
            var at = indexOf(file, record);
            assertTrue(at > 0);
            // Sides whose difference overflows an int.
            var wide = file.clone();
            ByteBuffer.wrap(wide).putInt(at + 4, Integer.MIN_VALUE).putInt(at + 12, Integer.MAX_VALUE);
            assertThrows(MediaException.Malformed.class, () -> decode(wide));
            var tall = file.clone();
            ByteBuffer.wrap(tall).putInt(at + 8, PsdLayers.MAX_SIDE + 1);
            assertThrows(MediaException.Malformed.class, () -> decode(tall));
            // A density flag whose value lies past the mask data.
            var mask = ByteBuffer.allocate(19);
            mask.putInt(0).putInt(0).putInt(4).putInt(4).put((byte) 0).put((byte) 16).put((byte) 5);
            var shortMask = solid(4, 4, new double[]{1, 1, 1}, 1).channel(-2, new byte[16]).maskData(mask.array());
            assertThrows(MediaException.Malformed.class,
                    () -> decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(shortMask).build()));
            // A real user mask and an enabled vector mask, but no -2 channel holding their rendering.
            var real = ByteBuffer.allocate(36);
            real.putInt(0).putInt(0).putInt(4).putInt(4).put((byte) 0).put((byte) 8);
            real.put((byte) 0).put((byte) 0).putInt(0).putInt(0).putInt(4).putInt(4);
            var noRendering = solid(4, 4, new double[]{0, 1, 0}, 1).channel(-3, new byte[16]).maskData(real.array())
                    .block("vmsk", ByteBuffer.allocate(8).putInt(3).putInt(0).array());
            var refusal = assertThrows(MediaException.Unsupported.class,
                    () -> decode(new PsdLayeredFiles(1, 4, 4, 8, 3).add(noRendering).build()));
            assertTrue(refusal.getMessage().contains("vector masks stored without their rendering"),
                    refusal.getMessage());
        }

        private static int indexOf(byte[] data, byte[] pattern) {
            for (var start = 0; start + pattern.length <= data.length; start++) {
                if (java.util.Arrays.equals(data, start, start + pattern.length, pattern, 0, pattern.length)) {
                    return start;
                }
            }
            return -1;
        }

        @Test
        @DisplayName("rejects groups nested too deeply")
        void nesting() {
            var file = new PsdLayeredFiles(1, 2, 2, 8, 3);
            for (var level = 0; level < PsdLayers.MAX_NESTING + 2; level++) {
                file.open();
            }
            assertThrows(MediaException.Malformed.class, () -> decode(file.build()));
        }
    }

    /**
     * Writes Photoshop action descriptors for the test blocks.
     */
    static final class Descriptor {
        private final ByteArrayOutputStream items = new ByteArrayOutputStream();
        private int count;

        private DataOutputStream out() {
            return new DataOutputStream(items);
        }

        private void key(String key) throws IOException {
            var out = out();
            if (key.length() == 4) {
                out.writeInt(0);
            } else {
                out.writeInt(key.length());
            }
            out.writeBytes(key);
            count++;
        }

        Descriptor bool(String key, boolean value) {
            try {
                key(key);
                out().writeBytes("bool");
                out().writeByte(value ? 1 : 0);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return this;
        }

        Descriptor doub(String key, double value) {
            try {
                key(key);
                out().writeBytes("doub");
                out().writeDouble(value);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return this;
        }

        Descriptor integer(String key, int value) {
            try {
                key(key);
                out().writeBytes("long");
                out().writeInt(value);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return this;
        }

        Descriptor object(String key, Descriptor value) {
            try {
                key(key);
                out().writeBytes("Objc");
                out().write(value.body());
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return this;
        }

        Descriptor list(String key, Descriptor... values) {
            try {
                key(key);
                out().writeBytes("VlLs");
                out().writeInt(values.length);
                for (var value : values) {
                    out().writeBytes("Objc");
                    out().write(value.body());
                }
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return this;
        }

        byte[] body() throws IOException {
            var result = new ByteArrayOutputStream();
            var out = new DataOutputStream(result);
            out.writeInt(1);
            out.writeShort(0);
            out.writeInt(0);
            out.writeBytes("null");
            out.writeInt(count);
            items.writeTo(out);
            return result.toByteArray();
        }

        byte[] versioned() throws IOException {
            var body = body();
            var out = ByteBuffer.allocate(4 + body.length);
            out.putInt(16).put(body);
            return out.array();
        }
    }
}
