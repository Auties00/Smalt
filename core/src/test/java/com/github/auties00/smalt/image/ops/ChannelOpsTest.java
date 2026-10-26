package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the operations of {@link ImageOps} on channel pictures: the conversions to packed RGBA against an
 * independent model of the documented formulas, the sample type conversions, the geometric operations against
 * the packed RGBA operations, resampling, color transforms, and the rejection of the other operations.
 */
@DisplayName("Channel picture operations")
class ChannelOpsTest {
    private static final PixelLayout CMYK_DEPTH = PixelLayout.CMYKA.withChannel(Channel.of(ChannelType.DEPTH));

    private static final PixelLayout RGB_EXTRAS = PixelLayout.RGB.withChannel(Channel.of(ChannelType.DEPTH))
            .withChannel(Channel.of(ChannelType.ALPHA)).withChannel(Channel.ofSpot("Gold"));

    private static final PixelLayout PREMULTIPLIED = PixelLayout.CMYK
            .withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));

    static Channel8Picture random8(SplittableRandom random, int width, int height, PixelLayout layout) {
        var stride = width * layout.count() + random.nextInt(3);
        var pixels = new byte[stride * height];
        random.nextBytes(pixels);
        return Channel8Picture.of(width, height, stride, pixels, layout);
    }

    static Channel16Picture random16(SplittableRandom random, int width, int height, PixelLayout layout) {
        var stride = width * layout.count() + random.nextInt(3);
        var pixels = new short[stride * height];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (short) random.nextInt(65536);
        }
        return Channel16Picture.of(width, height, stride, pixels, layout);
    }

    static ChannelFloatPicture randomFloat(SplittableRandom random, int width, int height, PixelLayout layout) {
        var stride = width * layout.count() + random.nextInt(3);
        var pixels = new float[stride * height];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = (float) (random.nextDouble() * 1.4 - 0.2);
        }
        return ChannelFloatPicture.of(width, height, stride, pixels, layout);
    }

    /** Reads one sample as an integer of the picture's integer depth: floats quantized to 16 bits. */
    static int sample(ChannelPicture picture, int x, int y, int c) {
        var at = y * picture.stride() + x * picture.channelCount() + c;
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels()[at] & 0xFF;
            case Channel16Picture sixteen -> sixteen.pixels()[at] & 0xFFFF;
            case ChannelFloatPicture floating -> {
                var v = floating.pixels()[at];
                yield Float.isNaN(v) || v <= 0 ? 0 : v >= 1 ? 65535 : (int) Math.floor(v * 65535.0 + 0.5);
            }
        };
    }

    /** The expected RGBA of one pixel at the picture's integer depth, from the documented formulas. */
    static int[] expectedRgba(ChannelPicture picture, int x, int y) {
        var layout = picture.layout();
        var max = picture instanceof Channel8Picture ? 255 : 65535;
        var alphaIndex = layout.alphaIndex();
        var a = alphaIndex >= 0 ? sample(picture, x, y, alphaIndex) : max;
        var colors = new long[layout.colorChannels()];
        for (var c = 0; c < colors.length; c++) {
            long v = sample(picture, x, y, c);
            if (layout.isAlphaPremultiplied()) {
                // round(v * max / a), halves up, clamped
                v = a == 0 ? 0 : Math.min(max, Math.floorDiv(2 * v * max + a, 2L * a));
            }
            colors[c] = v;
        }
        return switch (layout.colorModel()) {
            case GRAY -> new int[]{(int) colors[0], (int) colors[0], (int) colors[0], a};
            case RGB -> new int[]{(int) colors[0], (int) colors[1], (int) colors[2], a};
            default -> {
                var k = max - colors[3];
                var rgb = new int[4];
                for (var c = 0; c < 3; c++) {
                    // exact rational rounding of (max - ink) * (max - black) / max
                    rgb[c] = (int) Math.floorDiv(2 * (max - colors[c]) * k + max, 2L * max);
                }
                rgb[3] = a;
                yield rgb;
            }
        };
    }

    static void assertRgba(ChannelPicture source, Picture converted, boolean sixteen) {
        assertEquals(source.width(), converted.width());
        assertEquals(source.height(), converted.height());
        assertEquals(source.layout().hasAlpha(), converted.hasAlpha());
        var max = source instanceof Channel8Picture ? 255 : 65535;
        for (var y = 0; y < source.height(); y++) {
            for (var x = 0; x < source.width(); x++) {
                var expected = expectedRgba(source, x, y);
                for (var c = 0; c < 4; c++) {
                    var value = expected[c];
                    int actual;
                    if (sixteen) {
                        value = max == 255 ? value * 257 : value;
                        var rgba = (Rgba16Picture) converted;
                        actual = rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFFFF;
                    } else {
                        value = max == 65535 ? (value * 255 + 32767) / 65535 : value;
                        var rgba = (RgbaPicture) converted;
                        actual = rgba.pixels()[y * rgba.stride() + 4 * x + c] & 0xFF;
                    }
                    assertEquals(value, actual, "pixel " + x + "," + y + " channel " + c);
                }
            }
        }
    }

    static List<ChannelPicture> samples(long seed) {
        var random = new SplittableRandom(seed);
        return List.of(random8(random, 13, 7, PixelLayout.CMYK), random16(random, 9, 5, PixelLayout.CMYK),
                randomFloat(random, 11, 3, PixelLayout.CMYK), random8(random, 6, 6, CMYK_DEPTH),
                random16(random, 7, 4, PREMULTIPLIED), random8(random, 5, 9, PREMULTIPLIED),
                random8(random, 8, 3, RGB_EXTRAS), random16(random, 3, 8, PixelLayout.GRAY_ALPHA),
                randomFloat(random, 4, 4, PixelLayout.RGBA), random8(random, 10, 2, PixelLayout.GRAY),
                randomFloat(random, 5, 5, PREMULTIPLIED));
    }

    @Nested
    @DisplayName("conversions to RGBA")
    class ToRgba {
        @Test
        @DisplayName("follow the documented formulas at 8 and 16 bits for every color model")
        void formulas() {
            for (var picture : samples(1)) {
                assertRgba(picture, ImageOps.toRgba(picture), false);
                assertRgba(picture, ImageOps.toRgba16(picture), true);
            }
        }

        @Test
        @DisplayName("reduce the 16-bit conversion to exactly the 8-bit one")
        void eightBitIsReducedSixteen() {
            for (var picture : samples(2)) {
                assertArrayEquals(ImageOps.toRgba(picture).pixels(),
                        ImageOps.toEightBit(ImageOps.toRgba16(picture)).pixels(), picture.layout().toString());
            }
        }

        @Test
        @DisplayName("equal the inverted-CMYK formula of Adobe JPEG files, R = round(C' * K' / 255)")
        void adobeJpegFormula() {
            var picture = Channel8Picture.allocate(256, 256, PixelLayout.CMYK);
            for (var y = 0; y < 256; y++) {
                for (var x = 0; x < 256; x++) {
                    var at = (y * 256 + x) * 4;
                    picture.pixels()[at] = (byte) x;
                    picture.pixels()[at + 1] = (byte) (255 - x);
                    picture.pixels()[at + 2] = (byte) ((x * 7) & 0xFF);
                    picture.pixels()[at + 3] = (byte) y;
                }
            }
            var rgba = ImageOps.toRgba(picture);
            for (var y = 0; y < 256; y++) {
                for (var x = 0; x < 256; x++) {
                    var stored = new int[]{255 - x, x, 255 - ((x * 7) & 0xFF)};
                    for (var c = 0; c < 3; c++) {
                        assertEquals(Math.round(stored[c] * (255 - y) / 255.0), rgba.pixels()[(y * 256 + x) * 4 + c] & 0xFF);
                    }
                    assertEquals(255, rgba.pixels()[(y * 256 + x) * 4 + 3] & 0xFF);
                }
            }
        }

        @Test
        @DisplayName("keep RGBA pictures exactly through the channel types")
        void roundTrips() {
            var random = new SplittableRandom(3);
            for (var alpha : new boolean[]{false, true}) {
                var rgba = Pictures.randomRgba(random, 17, 9, alpha);
                var eight = ImageOps.toChannel8(rgba);
                assertEquals(alpha ? PixelLayout.RGBA : PixelLayout.RGB, eight.layout());
                assertArrayEquals(Pictures.tight(rgba), ImageOps.toRgba(eight).pixels());
                assertEquals(alpha, ImageOps.toRgba(eight).hasAlpha());
                assertArrayEquals(Pictures.tight(rgba), ImageOps.toRgba(ImageOps.toChannelFloat(rgba)).pixels());
                var wide = ImageOps.toSixteenBit(rgba);
                assertArrayEquals(wide.pixels(), ImageOps.toRgba16(ImageOps.toChannel16(wide)).pixels());
                assertArrayEquals(wide.pixels(), ImageOps.toRgba16(ImageOps.toChannelFloat(wide)).pixels());
                assertArrayEquals(ImageOps.toChannel8(wide).pixels(), eight.pixels());
            }
        }

        @Test
        @DisplayName("quantize floats to 16 bits first, clamping and mapping NaN to zero")
        void floats() {
            var values = new float[]{Float.NaN, -3.0F, 0.0F, 0.5F, 1.0F, 7.5F, Float.POSITIVE_INFINITY,
                    Float.NEGATIVE_INFINITY, 2.0F / 65535.0F, 0.25F, 0.75F, 0.999F};
            var picture = ChannelFloatPicture.of(values.length, 1, values.length, values, PixelLayout.GRAY);
            var wide = ImageOps.toRgba16(picture);
            var narrow = ImageOps.toRgba(picture);
            var expected = new int[]{0, 0, 0, 32768, 65535, 65535, 65535, 0, 2, 16384, 49151, 65469};
            for (var i = 0; i < values.length; i++) {
                assertEquals(expected[i], wide.pixels()[4 * i] & 0xFFFF, "value " + values[i]);
                assertEquals((expected[i] * 255 + 32767) / 65535, narrow.pixels()[4 * i] & 0xFF);
            }
            assertArrayEquals(wide.pixels(), ImageOps.toRgba16(ImageOps.toChannel16(picture)).pixels());
            assertArrayEquals(narrow.pixels(), ImageOps.toRgba(ImageOps.toChannel8(picture)).pixels());
        }

        @Test
        @DisplayName("convert to Y'CbCr through RGBA of the picture's depth")
        void yuv() {
            var random = new SplittableRandom(4);
            var rgba = Pictures.randomRgba(random, 19, 11, true);
            var channels = ImageOps.toChannel8(rgba);
            for (var format : ChromaFormat.values()) {
                var expected = ImageOps.toYuv(rgba, format, ColorInfo.JPEG);
                var actual = ImageOps.toYuv(channels, format, ColorInfo.JPEG);
                assertArrayEquals(Pictures.raw(expected), Pictures.raw(actual), format.name());
            }
            var wide = random16(random, 9, 7, PixelLayout.RGBA);
            var expected = ImageOps.toYuv16(ImageOps.toRgba16(wide), ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10);
            var actual = ImageOps.toYuv16(wide, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 10);
            assertArrayEquals(expected.y(), actual.y());
            assertArrayEquals(expected.u(), actual.u());
            assertArrayEquals(expected.alpha(), actual.alpha());
        }

        @Test
        @DisplayName("reject ink separations, which need a profile")
        void inks() {
            var picture = Channel8Picture.allocate(2, 2, PixelLayout.ofInks(List.of("Orange", "Green")));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.toRgba(picture));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.toRgba16(picture));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.toSrgb(picture));
            assertThrows(MediaException.Unsupported.class,
                    () -> ImageOps.toYuv(picture, ChromaFormat.YUV420, ColorInfo.JPEG));
            assertSame(picture, ImageOps.toEightBit(picture));
            assertEquals(picture.layout(), ImageOps.toChannelFloat(picture).layout());
        }
    }

    @Nested
    @DisplayName("sample types")
    class SampleTypes {
        @Test
        @DisplayName("convert between 8, 16 and floating-point samples with the documented rounding")
        void conversions() {
            var random = new SplittableRandom(5);
            var eight = random8(random, 23, 5, CMYK_DEPTH);
            var wide = ImageOps.toChannel16(eight);
            var floating = ImageOps.toChannelFloat(eight);
            assertEquals(CMYK_DEPTH, wide.layout());
            assertEquals(CMYK_DEPTH, floating.layout());
            for (var y = 0; y < 5; y++) {
                for (var x = 0; x < 23; x++) {
                    for (var c = 0; c < CMYK_DEPTH.count(); c++) {
                        var v = sample(eight, x, y, c);
                        assertEquals(v * 257, sample(wide, x, y, c));
                        assertEquals(v / 255.0F, floating.pixels()[y * floating.stride() + x * 6 + c]);
                    }
                }
            }
            assertArrayEquals(Pictures.tight(eight.pixels(), eight.stride(), 23 * 6, 5),
                    ImageOps.toChannel8(wide).pixels());
            assertArrayEquals(Pictures.tight(eight.pixels(), eight.stride(), 23 * 6, 5),
                    ImageOps.toChannel8(floating).pixels());
            var sixteen = random16(random, 7, 3, PixelLayout.GRAY);
            var back = ImageOps.toChannel16(ImageOps.toChannelFloat(sixteen));
            var reduced = ImageOps.toChannel8(sixteen);
            for (var x = 0; x < 7; x++) {
                for (var y = 0; y < 3; y++) {
                    assertEquals(sample(sixteen, x, y, 0), sample(back, x, y, 0));
                    assertEquals((sample(sixteen, x, y, 0) * 255 + 32767) / 65535, sample(reduced, x, y, 0));
                }
            }
            assertSame(sixteen, ImageOps.toChannel16(sixteen));
            assertSame(floating, ImageOps.toChannelFloat(floating));
            assertSame(eight, ImageOps.toEightBit(eight));
            assertInstanceOf(Channel8Picture.class, ImageOps.toEightBit(floating));
        }

        @Test
        @DisplayName("convert planar pictures through RGBA")
        void planar() {
            var random = new SplittableRandom(6);
            var yuv = Pictures.randomYuv(random, 12, 8, ChromaFormat.YUV420, true, ColorInfo.JPEG);
            assertArrayEquals(ImageOps.toRgba(yuv).pixels(), ImageOps.toRgba(ImageOps.toChannel8(yuv)).pixels());
            var deep = ImageOps.toYuv16(yuv, ChromaFormat.YUV444, ColorInfo.BT709_LIMITED, 10);
            assertArrayEquals(ImageOps.toRgba16(deep).pixels(), ImageOps.toRgba16(ImageOps.toChannel16(deep)).pixels());
        }
    }

    @Nested
    @DisplayName("geometry")
    class Geometry {
        @ParameterizedTest
        @EnumSource(Orientation.class)
        @DisplayName("orients every sample type exactly as packed RGBA is oriented")
        void orient(Orientation orientation) {
            var random = new SplittableRandom(orientation.ordinal());
            for (var size : new int[][]{{7, 5}, {1, 9}, {9, 1}, {33, 17}}) {
                var width = size[0];
                var height = size[1];
                // a coordinate map: red and green hold x, blue and alpha hold y
                var map = RgbaPicture.allocate(width, height, true);
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var at = (y * width + x) * 4;
                        map.pixels()[at] = (byte) x;
                        map.pixels()[at + 2] = (byte) y;
                    }
                }
                var mapped = (RgbaPicture) ImageOps.orient(map, orientation);
                for (var picture : List.<ChannelPicture>of(random8(random, width, height, CMYK_DEPTH),
                        random16(random, width, height, RGB_EXTRAS), randomFloat(random, width, height, CMYK_DEPTH))) {
                    var oriented = (ChannelPicture) ImageOps.orient(picture, orientation);
                    assertEquals(mapped.width(), oriented.width());
                    assertEquals(mapped.height(), oriented.height());
                    assertSame(picture.getClass(), oriented.getClass());
                    for (var y = 0; y < oriented.height(); y++) {
                        for (var x = 0; x < oriented.width(); x++) {
                            var at = y * mapped.stride() + x * 4;
                            var sx = mapped.pixels()[at] & 0xFF;
                            var sy = mapped.pixels()[at + 2] & 0xFF;
                            for (var c = 0; c < picture.channelCount(); c++) {
                                assertEquals(raw(picture, sx, sy, c), raw(oriented, x, y, c),
                                        orientation + " " + x + "," + y);
                            }
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("crops, flips and turns by quarter turns without changing samples")
        void cropAndTurns() {
            var random = new SplittableRandom(7);
            var picture = randomFloat(random, 20, 12, CMYK_DEPTH);
            var cropped = (ChannelFloatPicture) ImageOps.crop(picture, 3, 2, 9, 7);
            for (var y = 0; y < 7; y++) {
                for (var x = 0; x < 9; x++) {
                    for (var c = 0; c < 6; c++) {
                        assertEquals(raw(picture, x + 3, y + 2, c), raw(cropped, x, y, c));
                    }
                }
            }
            assertSame(picture, ImageOps.crop(picture, 0, 0, 20, 12));
            var turned = (ChannelPicture) ImageOps.rotate(picture, 90);
            assertEquals(12, turned.width());
            assertEquals(raw(picture, 0, 11, 0), raw(turned, 0, 0, 0));
            var back = (ChannelPicture) ImageOps.flipVertical(ImageOps.flipHorizontal(ImageOps.rotate(picture, 180)));
            assertEquals(tight(picture), tight(back));
            var inscribed = (ChannelPicture) ImageOps.rotate(picture, 90, Interpolation.BILINEAR,
                    RotationCanvas.INSCRIBED, 0);
            var expected = (ChannelPicture) ImageOps.crop(ImageOps.orient(picture, Orientation.ROTATE_90), 0, 6, 12, 7);
            assertEquals(tight(expected), tight(inscribed));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.rotate(picture, 270, Interpolation.BILINEAR,
                    RotationCanvas.KEEP, 0));
        }

        @Test
        @DisplayName("scans the alpha channel for opacity")
        void opacity() {
            var picture = Channel16Picture.allocate(3, 2, PixelLayout.CMYKA);
            for (var i = 4; i < picture.pixels().length; i += 5) {
                picture.pixels()[i] = (short) 0xFFFF;
            }
            assertTrue(ImageOps.isOpaque(picture));
            picture.pixels()[9] = 12;
            assertFalse(ImageOps.isOpaque(picture));
            assertTrue(ImageOps.isOpaque(Channel8Picture.allocate(2, 2, PixelLayout.CMYK)));
            var floating = ChannelFloatPicture.allocate(1, 1, PixelLayout.GRAY_ALPHA);
            floating.pixels()[1] = 1.5F;
            assertTrue(ImageOps.isOpaque(floating));
            floating.pixels()[1] = Float.NaN;
            assertFalse(ImageOps.isOpaque(floating));
        }

        @Test
        @DisplayName("copies channel pictures, recycling one of the same shape")
        void copy() {
            var random = new SplittableRandom(8);
            var picture = random16(random, 6, 4, RGB_EXTRAS);
            var copy = (Channel16Picture) PictureCopy.copy(picture, null);
            assertNotSame(picture.pixels(), copy.pixels());
            assertEquals(tight(picture), tight(copy));
            var other = random16(random, 6, 4, RGB_EXTRAS);
            assertSame(copy, PictureCopy.copy(other, copy));
            assertEquals(tight(other), tight(copy));
            assertNotSame(copy, PictureCopy.copy(random16(random, 6, 4, PixelLayout.CMYK), copy));
        }
    }

    @Nested
    @DisplayName("resampling")
    class Resampling {
        @ParameterizedTest
        @EnumSource(ResizeFilter.class)
        @DisplayName("matches the 16-bit RGBA resampler within one level")
        void matchesRgba16(ResizeFilter filter) {
            var random = new SplittableRandom(filter.ordinal() + 10);
            var wide = ImageOps.toSixteenBit(Pictures.randomRgba(random, 37, 23, true));
            var channels = ImageOps.toChannel16(wide);
            for (var size : new int[][]{{11, 7}, {74, 46}, {37, 9}, {5, 60}}) {
                var expected = (Rgba16Picture) ImageOps.resize(wide, size[0], size[1], filter);
                var actual = ImageOps.toRgba16(ImageOps.resize(channels, size[0], size[1], filter));
                var differences = 0;
                for (var i = 0; i < expected.pixels().length; i++) {
                    var difference = Math.abs((expected.pixels()[i] & 0xFFFF) - (actual.pixels()[i] & 0xFFFF));
                    assertTrue(difference <= 1, filter + " sample " + i + " differs by " + difference);
                    differences += difference;
                }
                assertTrue(differences * 100 <= expected.pixels().length, filter + ": " + differences + " differences");
            }
        }

        @ParameterizedTest
        @EnumSource(ResizeFilter.class)
        @DisplayName("filters the channels of a picture without alpha independently")
        void independentChannels(ResizeFilter filter) {
            var random = new SplittableRandom(filter.ordinal() + 20);
            var layout = PixelLayout.CMYK.withChannel(Channel.of(ChannelType.DEPTH)).withChannel(Channel.ofSpot("S"));
            for (var picture : List.<ChannelPicture>of(random8(random, 29, 13, layout), random16(random, 29, 13, layout),
                    randomFloat(random, 29, 13, layout))) {
                var resized = (ChannelPicture) ImageOps.resize(picture, 17, 31, filter);
                assertSame(picture.getClass(), resized.getClass());
                assertEquals(layout, resized.layout());
                for (var c = 0; c < layout.count(); c++) {
                    var single = (ChannelPicture) ImageOps.resize(channel(picture, c), 17, 31, filter);
                    for (var y = 0; y < 31; y++) {
                        for (var x = 0; x < 17; x++) {
                            assertEquals(raw(single, x, y, 0), raw(resized, x, y, c), filter + " channel " + c);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps floating-point values outside 0..1 and copies them exactly with the nearest filter")
        void highDynamicRange() {
            var picture = ChannelFloatPicture.allocate(8, 8, PixelLayout.RGB);
            java.util.Arrays.fill(picture.pixels(), 4.0F);
            var resized = (ChannelFloatPicture) ImageOps.resize(picture, 5, 13, ResizeFilter.LANCZOS3);
            for (var value : resized.pixels()) {
                assertEquals(4.0F, value);
            }
            var random = new SplittableRandom(30);
            var values = randomFloat(random, 9, 9, CMYK_DEPTH);
            values.pixels()[0] = -12.5F;
            var nearest = (ChannelFloatPicture) ImageOps.resize(values, 27, 27, ResizeFilter.NEAREST);
            for (var y = 0; y < 27; y++) {
                for (var x = 0; x < 27; x++) {
                    for (var c = 0; c < 6; c++) {
                        assertEquals(raw(values, x / 3, y / 3, c), raw(nearest, x, y, c));
                    }
                }
            }
            var sixteen = random16(random, 30, 20, CMYK_DEPTH);
            for (var filter : ResizeFilter.values()) {
                var fromFloat = ImageOps.toChannel16(ImageOps.resize(ImageOps.toChannelFloat(sixteen), 13, 9, filter));
                var fromInteger = (Channel16Picture) ImageOps.resize(sixteen, 13, 9, filter);
                for (var i = 0; i < fromFloat.pixels().length; i++) {
                    assertEquals(fromInteger.pixels()[i] & 0xFFFF, fromFloat.pixels()[i] & 0xFFFF, 1, filter.name());
                }
            }
        }

        @Test
        @DisplayName("does not bleed the color of transparent pixels")
        void premultiplied() {
            var picture = Channel8Picture.allocate(4, 1, PixelLayout.CMYKA);
            var pixels = picture.pixels();
            for (var x = 0; x < 4; x++) {
                pixels[x * 5] = (byte) (x < 2 ? 255 : 10);
                pixels[x * 5 + 4] = (byte) (x < 2 ? 0 : 255);
            }
            for (var filter : ResizeFilter.values()) {
                if (filter == ResizeFilter.NEAREST) {
                    continue;
                }
                for (var width : new int[]{1, 2, 3, 7}) {
                    var resized = (Channel8Picture) ImageOps.resize(picture, width, 1, filter);
                    for (var x = 0; x < width; x++) {
                        // only opaque pixels carry cyan, all of it 10, so a straight result is 10 or nothing
                        var alpha = resized.pixels()[x * 5 + 4] & 0xFF;
                        assertEquals(alpha == 0 ? 0 : 10, resized.pixels()[x * 5] & 0xFF, filter + " " + width);
                    }
                }
            }
        }

        @Test
        @DisplayName("fits, covers and smart-crops channel pictures to the requested size")
        void derived() {
            var random = new SplittableRandom(31);
            var picture = random16(random, 40, 20, CMYK_DEPTH);
            assertEquals(10, ImageOps.fitWithin(picture, 10, ResizeFilter.BICUBIC).width());
            var square = (ChannelPicture) ImageOps.coverSquare(picture, 8, ResizeFilter.BILINEAR);
            assertEquals(8, square.width());
            assertEquals(CMYK_DEPTH, square.layout());
            var cropped = ImageOps.smartCrop(picture, 10, 10, SmartCropStrategy.ENTROPY, ResizeFilter.BICUBIC);
            assertInstanceOf(Channel16Picture.class, cropped);
            assertEquals(10, cropped.height());
        }
    }

    @Nested
    @DisplayName("color transforms")
    class ColorTransforms {
        @Test
        @DisplayName("convert the color channels as packed RGBA converts and copy the extra channels")
        void rgbProfiles() {
            var random = new SplittableRandom(40);
            var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            var eight = random8(random, 21, 9, RGB_EXTRAS);
            var converted = (Channel8Picture) ImageOps.convertColor(eight, transform);
            assertEquals(RGB_EXTRAS, converted.layout());
            var rgba = Channel8Picture.allocate(21, 9, PixelLayout.RGBA);
            for (var y = 0; y < 9; y++) {
                for (var x = 0; x < 21; x++) {
                    for (var c = 0; c < 3; c++) {
                        rgba.pixels()[(y * 21 + x) * 4 + c] = (byte) sample(eight, x, y, c);
                    }
                    rgba.pixels()[(y * 21 + x) * 4 + 3] = (byte) sample(eight, x, y, 4);
                }
            }
            var expected = (RgbaPicture) ImageOps.convertColor(ImageOps.toRgba(rgba), transform);
            for (var y = 0; y < 9; y++) {
                for (var x = 0; x < 21; x++) {
                    for (var c = 0; c < 3; c++) {
                        assertEquals(expected.pixels()[(y * 21 + x) * 4 + c] & 0xFF, sample(converted, x, y, c));
                    }
                    for (var c = 3; c < 6; c++) {
                        assertEquals(sample(eight, x, y, c), sample(converted, x, y, c));
                    }
                }
            }
            var wide = random16(random, 5, 5, PixelLayout.RGBA);
            var expectedWide = (Rgba16Picture) ImageOps.convertColor(ImageOps.toRgba16(wide), transform);
            assertArrayEquals(expectedWide.pixels(),
                    ImageOps.toRgba16(ImageOps.convertColor(wide, transform)).pixels());
        }

        @Test
        @DisplayName("produce gray channels from a gray target and reject mismatched or floating-point inputs")
        void targets() {
            var random = new SplittableRandom(41);
            var picture = random8(random, 4, 4, PixelLayout.RGBA);
            var gray = (ChannelPicture) ImageOps.convertColor(picture, ColorProfile.srgb(), ColorProfile.grayGamma22());
            assertEquals(PixelLayout.GRAY_ALPHA, gray.layout());
            assertThrows(IllegalArgumentException.class, () -> ImageOps.convertColor(random8(random, 2, 2,
                    PixelLayout.CMYK), ColorProfile.srgb(), ColorProfile.displayP3()));
            assertThrows(MediaException.Unsupported.class, () -> ImageOps.convertColor(randomFloat(random, 2, 2,
                    PixelLayout.RGB), ColorProfile.displayP3(), ColorProfile.srgb()));
        }

        @Test
        @DisplayName("converts premultiplied colors straight and multiplies them again")
        void premultipliedColors() {
            var layout = PixelLayout.RGB.withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));
            var random = new SplittableRandom(43);
            var picture = Channel8Picture.allocate(16, 16, layout);
            for (var i = 0; i < 256; i++) {
                var alpha = i;
                picture.pixels()[i * 4 + 3] = (byte) alpha;
                for (var c = 0; c < 3; c++) {
                    picture.pixels()[i * 4 + c] = (byte) random.nextInt(alpha + 1);
                }
            }
            var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            var straight = new byte[256 * 3];
            for (var i = 0; i < 256; i++) {
                for (var c = 0; c < 3; c++) {
                    straight[i * 3 + c] = (byte) ChannelOps.unpremultiply(picture.pixels()[i * 4 + c] & 0xFF, i, 255);
                }
            }
            var transformed = new byte[256 * 3];
            transform.transform(straight, 0, 3, transformed, 0, 3, 256);
            var converted = (Channel8Picture) ImageOps.convertColor(picture, transform);
            assertEquals(layout, converted.layout());
            var decoded = new DecodedImage(picture, ImageFormat.TIFF, Orientation.NORMAL, 16, 16,
                    Optional.of(ColorProfile.displayP3().toByteArray()), Optional.empty());
            var srgb = (RgbaPicture) ImageOps.toSrgb(decoded).picture();
            assertTrue(srgb.hasAlpha());
            for (var i = 0; i < 256; i++) {
                assertEquals(i, converted.pixels()[i * 4 + 3] & 0xFF);
                assertEquals(i, srgb.pixels()[i * 4 + 3] & 0xFF);
                for (var c = 0; c < 3; c++) {
                    var value = transformed[i * 3 + c] & 0xFF;
                    assertEquals((value * i + 127) / 255, converted.pixels()[i * 4 + c] & 0xFF, "pixel " + i);
                    // the sRGB conversion of a decoded image keeps the straight colors, without multiplying again
                    assertEquals(value, srgb.pixels()[i * 4 + c] & 0xFF, "pixel " + i);
                }
            }
        }

        @Test
        @DisplayName("convert a decoded channel picture to packed sRGB, through its profile when it has one")
        void decodedToSrgb() {
            var random = new SplittableRandom(42);
            var picture = random8(random, 6, 6, PixelLayout.CMYK);
            var plain = new DecodedImage(picture, ImageFormat.TIFF, Orientation.NORMAL, 6, 6, Optional.empty(),
                    Optional.empty());
            var naive = ImageOps.toSrgb(plain);
            assertArrayEquals(ImageOps.toRgba(picture).pixels(), ((RgbaPicture) naive.picture()).pixels());
            assertTrue(naive.colorProfile().isEmpty());
            var rgb = random16(random, 6, 6, PixelLayout.RGB);
            var tagged = new DecodedImage(rgb, ImageFormat.TIFF, Orientation.NORMAL, 6, 6,
                    Optional.of(ColorProfile.displayP3().toByteArray()), Optional.empty());
            var converted = ImageOps.toSrgb(tagged);
            assertEquals(ColorProfile.srgb(), converted.colorProfile().orElseThrow());
            assertTrue(converted.iccProfile().isEmpty());
            var expected = ImageOps.convertColor(ImageOps.toRgba16(rgb), ColorProfile.displayP3(), ColorProfile.srgb());
            assertArrayEquals(((Rgba16Picture) expected).pixels(), ((Rgba16Picture) converted.picture()).pixels());
            // the metadata other than the color description survives both conversions
            var xmp = "<x:xmpmeta xmlns:x='adobe:ns:meta/'/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var exif = new byte[]{'M', 'M', 0, 42, 0, 0, 0, 8, 0, 0};
            for (var image : List.of(plain, tagged)) {
                var withMetadata = new DecodedImage(image.picture(), image.format(), image.orientation(),
                        image.sourceWidth(), image.sourceHeight(), image.iccProfile(), Optional.of(exif),
                        Optional.of(xmp), image.colorProfile());
                var srgb = ImageOps.toSrgb(withMetadata);
                assertArrayEquals(xmp, srgb.xmp().orElseThrow());
                assertArrayEquals(exif, srgb.exif().orElseThrow());
            }
        }
    }

    @Test
    @DisplayName("operations that compute RGB pixels reject channel pictures")
    void rejected() {
        var random = new SplittableRandom(50);
        var picture = random8(random, 9, 9, PixelLayout.CMYKA);
        List<Supplier<Object>> operations = List.of(
                () -> ImageOps.pad(picture, 12, 12, 1, 1, 0xFF000000),
                () -> ImageOps.flatten(picture, 0xFFFFFF),
                () -> ImageOps.extend(picture, 1, 1, 1, 1, ExtendMode.REPLICATE, 0),
                () -> ImageOps.resizeCanvas(picture, 12, 12, Anchor.CENTER, 0),
                () -> ImageOps.trim(picture, 3),
                () -> ImageOps.rotate(picture, 30),
                () -> ImageOps.fitInto(picture, 5, 3, ResizeFilter.BILINEAR, 0),
                () -> ImageOps.affine(picture, AffineTransform.rotation(10), 9, 9, Interpolation.BILINEAR, 0),
                () -> ImageFilters.gaussianBlur(picture, 2),
                () -> ImageAdjustments.brightness(picture, 0.2),
                () -> ImageCompositing.composite(picture, picture, 0, 0, CompositeOptions.defaults()));
        for (var operation : operations) {
            assertThrows(MediaException.Unsupported.class, operation::get);
        }
    }

    @Test
    @DisplayName("random operations on channel pictures return valid pictures or documented exceptions")
    void randomOperations() {
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            var random = new SplittableRandom(2027);
            var layouts = List.of(PixelLayout.CMYK, CMYK_DEPTH, RGB_EXTRAS, PREMULTIPLIED, PixelLayout.GRAY,
                    PixelLayout.ofInks(List.of("A", "B", "C")));
            var successes = 0;
            for (var round = 0; round < 600; round++) {
                var layout = layouts.get(random.nextInt(layouts.size()));
                var width = 1 + random.nextInt(40);
                var height = 1 + random.nextInt(40);
                ChannelPicture input = switch (random.nextInt(3)) {
                    case 0 -> random8(random, width, height, layout);
                    case 1 -> random16(random, width, height, layout);
                    default -> randomFloat(random, width, height, layout);
                };
                var filter = ResizeFilter.values()[random.nextInt(ResizeFilter.values().length)];
                try {
                    Picture result = switch (random.nextInt(7)) {
                        case 0 -> ImageOps.resize(input, 1 + random.nextInt(60), 1 + random.nextInt(60), filter);
                        case 1 -> ImageOps.orient(input, Orientation.values()[random.nextInt(8)]);
                        case 2 -> ImageOps.crop(input, random.nextInt(width), random.nextInt(height), 1, 1);
                        case 3 -> ImageOps.toRgba16(input);
                        case 4 -> ImageOps.toChannelFloat(ImageOps.toChannel8(input));
                        case 5 -> ImageOps.coverSquare(input, 1 + random.nextInt(30), filter);
                        default -> ImageOps.toYuv(input, ChromaFormat.YUV420, ColorInfo.JPEG);
                    };
                    assertTrue(result.width() > 0 && result.height() > 0);
                    if (result instanceof ChannelPicture channels) {
                        assertEquals(input.layout(), channels.layout());
                        assertTrue(channels.stride() >= channels.width() * channels.channelCount());
                    }
                    successes++;
                } catch (MediaException.Unsupported expected) {
                    assertEquals(com.github.auties00.smalt.image.ColorModel.INK, layout.colorModel());
                }
            }
            assertTrue(successes > 400, "successes: " + successes);
        });
    }

    private static Object raw(ChannelPicture picture, int x, int y, int c) {
        var at = y * picture.stride() + x * picture.channelCount() + c;
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels()[at];
            case Channel16Picture sixteen -> sixteen.pixels()[at];
            case ChannelFloatPicture floating -> floating.pixels()[at];
        };
    }

    private static String tight(ChannelPicture picture) {
        var row = picture.width() * picture.channelCount();
        return switch (picture) {
            case Channel8Picture eight -> java.util.Arrays.toString(Pictures.tight(eight.pixels(), eight.stride(), row,
                    eight.height()));
            case Channel16Picture sixteen -> {
                var out = new short[row * sixteen.height()];
                for (var y = 0; y < sixteen.height(); y++) {
                    System.arraycopy(sixteen.pixels(), y * sixteen.stride(), out, y * row, row);
                }
                yield java.util.Arrays.toString(out);
            }
            case ChannelFloatPicture floating -> {
                var out = new float[row * floating.height()];
                for (var y = 0; y < floating.height(); y++) {
                    System.arraycopy(floating.pixels(), y * floating.stride(), out, y * row, row);
                }
                yield java.util.Arrays.toString(out);
            }
        };
    }

    /** Extracts one channel as a gray picture of the same sample type. */
    private static ChannelPicture channel(ChannelPicture picture, int c) {
        var width = picture.width();
        var height = picture.height();
        var n = picture.channelCount();
        return switch (picture) {
            case Channel8Picture eight -> {
                var out = Channel8Picture.allocate(width, height, PixelLayout.GRAY);
                for (var i = 0; i < width * height; i++) {
                    out.pixels()[i] = eight.pixels()[(i / width) * eight.stride() + (i % width) * n + c];
                }
                yield out;
            }
            case Channel16Picture sixteen -> {
                var out = Channel16Picture.allocate(width, height, PixelLayout.GRAY);
                for (var i = 0; i < width * height; i++) {
                    out.pixels()[i] = sixteen.pixels()[(i / width) * sixteen.stride() + (i % width) * n + c];
                }
                yield out;
            }
            case ChannelFloatPicture floating -> {
                var out = ChannelFloatPicture.allocate(width, height, PixelLayout.GRAY);
                for (var i = 0; i < width * height; i++) {
                    out.pixels()[i] = floating.pixels()[(i / width) * floating.stride() + (i % width) * n + c];
                }
                yield out;
            }
        };
    }
}
