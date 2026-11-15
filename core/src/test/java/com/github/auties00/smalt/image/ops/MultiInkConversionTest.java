package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converts named-ink separations through the multi-ink ({@code nCLR}) profiles of
 * {@code tools/testdata/icc-multi-ink}: separations whose inks are in another order than the profile's channels, CMYK
 * pictures with spot colors, premultiplied and floating-point separations, RGB pictures to inks and device links
 * between ink sets, against Little CMS 2.17's conversion of the same samples (8-bit within one code value) and against
 * the transform applied to the samples in the profile's channel order.
 */
@DisplayName("Multi-ink conversions")
class MultiInkConversionTest {
    private static final List<String> NAMES = List.of("Cyan", "Magenta", "Yellow", "Black", "Orange", "Green",
            "Violet", "Red");

    /** The ink of each channel of {@code ink8.icc}, as an index of {@link #NAMES}. */
    private static final int[] PERMUTATION8 = {3, 0, 1, 2, 4, 5, 6, 7};

    static byte[] resource(String name) {
        return resource("inks", name);
    }

    static byte[] resource(String directory, String name) {
        try (InputStream in = MultiInkConversionTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/image/color/" + directory + "/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static ColorProfile profile(String name) {
        return ColorProfile.parse(resource(name + ".icc"));
    }

    static int levels8(int channels) {
        return switch (channels) {
            case 2 -> 17;
            case 3, 4 -> 9;
            case 5, 6 -> 4;
            default -> 3;
        };
    }

    /** The 8-bit input set of {@code smalt-lcmsinks.c} for 2 to 8 channels, in the profile's channel order. */
    static byte[] inputs8(int channels) {
        var levels = levels8(channels);
        var total = 1;
        for (var c = 0; c < channels; c++) {
            total *= levels;
        }
        var out = new byte[(total + 1024) * channels];
        for (var index = 0; index < total; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index * channels + c] = (byte) Math.floor((double) (rest % levels) / (levels - 1) * 255 + 0.5);
                rest /= levels;
            }
        }
        var state = 0x9E3779B97F4A7C15L;
        for (var i = total * channels; i < out.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    /** Returns the first {@code count} samples of each pixel of a picture's samples. */
    static byte[] leading(byte[] pixels, int channels, int count) {
        var out = new byte[pixels.length / channels * count];
        for (var p = 0; p < pixels.length / channels; p++) {
            System.arraycopy(pixels, p * channels, out, p * count, count);
        }
        return out;
    }

    @Test
    @DisplayName("inks listed in another order than the profile's channels are read by name")
    void permutedInks() {
        var ink8 = profile("ink8");
        var srgb = profile("lcms-srgb");
        var in = inputs8(8);
        var pixels = in.length / 8;
        var random = new SplittableRandom(81);
        var layout = PixelLayout.ofInks(NAMES).withChannel(Channel.of(ChannelType.ALPHA));
        var data = new byte[pixels * 9];
        for (var p = 0; p < pixels; p++) {
            for (var k = 0; k < 8; k++) {
                data[p * 9 + PERMUTATION8[k]] = in[p * 8 + k];
            }
            data[p * 9 + 8] = (byte) random.nextInt(256);
        }
        var picture = Channel8Picture.of(pixels, 1, pixels * 9, data, layout);
        var converted = assertInstanceOf(Channel8Picture.class, ImageOps.convertColor(picture, ink8, srgb));
        assertEquals(PixelLayout.RGBA, converted.layout());
        var rgb = leading(converted.pixels(), 4, 3);
        Tolerance.offByOne().assertWithin("ink8 by name", resource("ink8-to-srgb.8"), rgb);
        for (var p = 0; p < pixels; p++) {
            assertEquals(data[p * 9 + 8], converted.pixels()[p * 4 + 3], "alpha of pixel " + p);
        }

        // read in channel order, the same samples give other colors
        var unnamed = Channel8Picture.of(pixels, 1, pixels * 9, data,
                PixelLayout.ofInks(java.util.Collections.nCopies(8, "")).withChannel(Channel.of(ChannelType.ALPHA)));
        var positional = leading(((Channel8Picture) ImageOps.convertColor(unnamed, ink8, srgb)).pixels(), 4, 3);
        var differing = 0;
        for (var i = 0; i < rgb.length; i++) {
            differing += Math.abs((rgb[i] & 0xFF) - (positional[i] & 0xFF)) > 8 ? 1 : 0;
        }
        assertTrue(differing > rgb.length / 4, "only " + differing + " samples differ");

        // a decoded image converts through its profile the same way
        var image = new DecodedImage(picture, ImageFormat.TIFF, Orientation.NORMAL, pixels, 1,
                Optional.of(ink8.toByteArray()), Optional.empty());
        var packed = assertInstanceOf(RgbaPicture.class, ImageOps.toSrgb(image).picture());
        var direct = (Channel8Picture) ImageOps.convertColor(picture, ink8, ColorProfile.srgb());
        assertArrayEquals(direct.pixels(), packed.pixels());
    }

    @Test
    @DisplayName("spot colors named by the profile's colorants join the process inks")
    void spotChannels() {
        var ink5 = profile("ink5");
        var srgb = profile("lcms-srgb");
        var in = inputs8(5);
        var pixels = in.length / 5;
        var random = new SplittableRandom(5);
        var layout = PixelLayout.CMYKA.withChannel(Channel.ofSpot(" orange ")).withChannel(Channel.ofSpot("Gold"));
        var data = new byte[pixels * 7];
        for (var p = 0; p < pixels; p++) {
            System.arraycopy(in, p * 5, data, p * 7, 4);
            data[p * 7 + 4] = (byte) random.nextInt(256);
            data[p * 7 + 5] = in[p * 5 + 4];
            data[p * 7 + 6] = (byte) random.nextInt(256);
        }
        var picture = Channel8Picture.of(pixels, 1, pixels * 7, data, layout);
        var converted = (Channel8Picture) ImageOps.convertColor(picture, ink5, srgb);
        assertEquals(PixelLayout.RGBA.withChannel(Channel.ofSpot("Gold")), converted.layout());
        Tolerance.offByOne().assertWithin("ink5 with a spot", resource("ink5-to-srgb.8"),
                leading(converted.pixels(), 5, 3));
        for (var p = 0; p < pixels; p++) {
            assertEquals(data[p * 7 + 4], converted.pixels()[p * 5 + 3], "alpha of pixel " + p);
            assertEquals(data[p * 7 + 6], converted.pixels()[p * 5 + 4], "gold of pixel " + p);
        }

        // 16-bit samples convert as the transform converts them in the profile's order
        var wide = new short[pixels * 7];
        var gathered = new short[pixels * 5];
        for (var i = 0; i < wide.length; i++) {
            wide[i] = (short) random.nextInt(65536);
        }
        for (var p = 0; p < pixels; p++) {
            System.arraycopy(wide, p * 7, gathered, p * 5, 4);
            gathered[p * 5 + 4] = wide[p * 7 + 5];
        }
        var transform = ColorTransform.create(ink5, srgb);
        var expected = new short[pixels * 3];
        transform.transform(gathered, 0, 5, expected, 0, 3, pixels);
        var deep = (Channel16Picture) ImageOps.convertColor(Channel16Picture.of(pixels, 1, pixels * 7, wide, layout),
                transform);
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < 3; c++) {
                assertEquals(expected[p * 3 + c], deep.pixels()[p * 5 + c], "pixel " + p);
            }
            assertEquals(wide[p * 7 + 4], deep.pixels()[p * 5 + 3]);
            assertEquals(wide[p * 7 + 6], deep.pixels()[p * 5 + 4]);
        }

        // without the spot channel the profile does not fit the picture
        var missing = Channel8Picture.allocate(2, 2, PixelLayout.CMYKA.withChannel(Channel.ofSpot("Gold")));
        assertThrows(IllegalArgumentException.class, () -> ImageOps.convertColor(missing, ink5, srgb));
        // nor does it when two spot channels have the colorant's name
        var twice = Channel8Picture.allocate(2, 2, PixelLayout.CMYK.withChannel(Channel.ofSpot("Orange"))
                .withChannel(Channel.ofSpot("ORANGE")));
        assertThrows(IllegalArgumentException.class, () -> ImageOps.convertColor(twice, ink5, srgb));
    }

    @Test
    @DisplayName("premultiplied separations are divided by their alpha before the transform")
    void premultiplied() {
        var ink5 = profile("ink5");
        var srgb = ColorProfile.srgb();
        var random = new SplittableRandom(55);
        var pixels = 4096;
        var names = List.of("Orange", "Black", "Yellow", "Magenta", "Cyan");
        var layout = PixelLayout.ofInks(names).withChannel(Channel.of(ChannelType.PREMULTIPLIED_ALPHA));
        var data = new byte[pixels * 6];
        var gathered = new byte[pixels * 5];
        for (var p = 0; p < pixels; p++) {
            var alpha = random.nextInt(256);
            data[p * 6 + 5] = (byte) alpha;
            for (var c = 0; c < 5; c++) {
                data[p * 6 + c] = (byte) random.nextInt(alpha + 1);
            }
            for (var k = 0; k < 5; k++) {
                var value = data[p * 6 + names.indexOf(NAMES.get(k))] & 0xFF;
                gathered[p * 5 + k] = (byte) ChannelOps.unpremultiply(value, alpha, 255);
            }
        }
        var transform = ColorTransform.create(ink5, srgb);
        var straight = new byte[pixels * 3];
        transform.transform(gathered, 0, 5, straight, 0, 3, pixels);
        var picture = Channel8Picture.of(pixels, 1, pixels * 6, data, layout);
        var converted = (Channel8Picture) ImageOps.convertColor(picture, transform);
        assertEquals(ChannelType.PREMULTIPLIED_ALPHA, converted.layout().channel(3).type());
        var image = new DecodedImage(picture, ImageFormat.TIFF, Orientation.NORMAL, pixels, 1,
                Optional.of(ink5.toByteArray()), Optional.empty());
        var packed = (RgbaPicture) ImageOps.toSrgb(image).picture();
        for (var p = 0; p < pixels; p++) {
            var alpha = data[p * 6 + 5] & 0xFF;
            for (var c = 0; c < 3; c++) {
                var value = straight[p * 3 + c] & 0xFF;
                assertEquals((byte) ((value * alpha + 127) / 255), converted.pixels()[p * 4 + c], "pixel " + p);
                assertEquals(straight[p * 3 + c], packed.pixels()[p * 4 + c], "pixel " + p);
            }
            assertEquals((byte) alpha, packed.pixels()[p * 4 + 3]);
        }
    }

    @Test
    @DisplayName("floating-point separations convert in floating point, read by name")
    void floatingPoint() {
        var ink7 = profile("ink7");
        var srgb = ColorProfile.srgb();
        var random = new SplittableRandom(7);
        var pixels = 2048;
        var names = new ArrayList<>(NAMES.subList(0, 7)).reversed();
        var data = new float[pixels * 7];
        var gathered = new float[pixels * 7];
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < 7; c++) {
                data[p * 7 + c] = random.nextFloat() * 1.2f - 0.1f;
            }
            for (var k = 0; k < 7; k++) {
                gathered[p * 7 + k] = data[p * 7 + names.indexOf(NAMES.get(k))];
            }
        }
        var transform = ColorTransform.create(ink7, srgb);
        var expected = new float[pixels * 3];
        transform.transform(gathered, 0, 7, expected, 0, 3, pixels);
        var converted = (ChannelFloatPicture) ImageOps.convertColor(
                ChannelFloatPicture.of(pixels, 1, pixels * 7, data, PixelLayout.ofInks(names)), transform);
        assertEquals(PixelLayout.RGB, converted.layout());
        assertArrayEquals(expected, converted.pixels());
    }

    @Test
    @DisplayName("RGB pictures convert to separations named by the target's colorants")
    void rgbToInks() {
        var srgb = profile("lcms-srgb");
        var in = inputs8(3);
        var pixels = in.length / 3;
        for (var name : List.of("ink2", "ink3-lut8", "ink5", "ink7", "ink8")) {
            var target = profile(name);
            var rgba = RgbaPicture.allocate(pixels, 1, false);
            for (var p = 0; p < pixels; p++) {
                System.arraycopy(in, p * 3, rgba.pixels(), p * 4, 3);
                rgba.pixels()[p * 4 + 3] = (byte) 0xFF;
            }
            var converted = assertInstanceOf(Channel8Picture.class, ImageOps.convertColor(rgba, srgb, target));
            var inks = target.channels();
            assertEquals(ColorModel.INK, converted.layout().colorModel(), name);
            assertEquals(inks, converted.layout().count(), name);
            for (var c = 0; c < inks; c++) {
                var expected = name.equals("ink3-lut8") ? "" : target.colorants().get(c).name();
                assertEquals(expected, converted.layout().channel(c).name(), name);
            }
            Tolerance.offByOne().assertWithin(name, resource("srgb-to-" + name + ".8"), converted.pixels());

            var random = new SplittableRandom(inks);
            var wide = Rgba16Picture.allocate(64, 16, true);
            for (var i = 0; i < wide.pixels().length; i++) {
                wide.pixels()[i] = (short) random.nextInt(65536);
            }
            var transform = ColorTransform.create(srgb, target);
            var deep = assertInstanceOf(Channel16Picture.class, ImageOps.convertColor(wide, transform));
            assertEquals(inks + 1, deep.layout().count(), name);
            assertEquals(ChannelType.ALPHA, deep.layout().channel(inks).type(), name);
            var expected = new short[64 * 16 * inks];
            transform.transform(wide.pixels(), 0, 4, expected, 0, inks, 64 * 16);
            for (var p = 0; p < 64 * 16; p++) {
                for (var c = 0; c < inks; c++) {
                    assertEquals(expected[p * inks + c], deep.pixels()[p * (inks + 1) + c], name);
                }
                assertEquals(wide.pixels()[p * 4 + 3], deep.pixels()[p * (inks + 1) + inks], name);
            }
        }
        // a separation converts back to RGB through the same profile
        var ink5 = profile("ink5");
        var separation = (Channel8Picture) ImageOps.convertColor(RgbaPicture.allocate(4, 4, false), srgb, ink5);
        assertInstanceOf(Channel8Picture.class, ImageOps.convertColor(separation, ink5, srgb));
    }

    @Test
    @DisplayName("device links between ink sets name the inks they write")
    void deviceLink() {
        var link = profile("link-ink5-ink7");
        var in = inputs8(5);
        var pixels = in.length / 5;
        var picture = Channel8Picture.of(pixels, 1, pixels * 5, in, PixelLayout.ofInks(NAMES.subList(0, 5)));
        var converted = (Channel8Picture) ImageOps.convertColor(picture, ColorTransform.create(List.of(link)));
        assertEquals(PixelLayout.ofInks(NAMES.subList(0, 7)), converted.layout());
        Tolerance.offByOne().assertWithin("link", resource("link-ink5-ink7.8"), converted.pixels());
    }

    @Test
    @DisplayName("a device link to CMYK makes a CMYK picture")
    void cmykLink() {
        var link = ColorProfile.parse(resource("advanced", "link-rgb-cmyk-v4.icc"));
        var transform = ColorTransform.create(List.of(link));
        var random = new SplittableRandom(4);
        var rgba = RgbaPicture.allocate(32, 8, true);
        random.nextBytes(rgba.pixels());
        var converted = assertInstanceOf(Channel8Picture.class, ImageOps.convertColor(rgba, transform));
        assertEquals(PixelLayout.CMYKA, converted.layout());
        var expected = new byte[32 * 8 * 4];
        transform.transform(rgba.pixels(), 0, 4, expected, 0, 4, 32 * 8);
        for (var p = 0; p < 32 * 8; p++) {
            for (var c = 0; c < 4; c++) {
                assertEquals(expected[p * 4 + c], converted.pixels()[p * 5 + c]);
            }
            assertEquals(rgba.pixels()[p * 4 + 3], converted.pixels()[p * 5 + 4]);
        }
    }

    @Test
    @DisplayName("separations that do not fit their profile are rejected or left without color management")
    void mismatches() {
        var ink8 = profile("ink8");
        var srgb = ColorProfile.srgb();
        var five = Channel8Picture.allocate(3, 3, PixelLayout.ofInks(NAMES.subList(0, 5)));
        assertThrows(IllegalArgumentException.class, () -> ImageOps.convertColor(five, ink8, srgb));
        assertThrows(IllegalArgumentException.class,
                () -> ImageOps.convertColor(RgbaPicture.allocate(2, 2, false), ink8, srgb));
        var image = new DecodedImage(five, ImageFormat.TIFF, Orientation.NORMAL, 3, 3,
                Optional.of(ink8.toByteArray()), Optional.empty());
        var failure = assertThrows(MediaException.Unsupported.class, () -> ImageOps.toSrgb(image));
        assertEquals("ink separations", failure.format());
        // inks named twice are read in channel order
        var twice = new ArrayList<>(NAMES);
        twice.set(1, "Cyan");
        var data = new byte[8 * 16];
        new SplittableRandom(2).nextBytes(data);
        var transform = ColorTransform.create(ink8, srgb);
        var expected = new byte[16 * 3];
        transform.transform(data, 0, 8, expected, 0, 3, 16);
        var converted = (Channel8Picture) ImageOps.convertColor(Channel8Picture.of(16, 1, 128, data,
                PixelLayout.ofInks(twice)), transform);
        assertArrayEquals(expected, converted.pixels());
        assertFalse(converted.layout().hasAlpha());

        // a damaged colorant table names no inks: separations are read in channel order, and written unnamed
        var bytes = resource("ink5.icc");
        var buffer = java.nio.ByteBuffer.wrap(bytes);
        for (var i = 0; i < buffer.getInt(128); i++) {
            if (buffer.getInt(132 + i * 12) == 0x636C7274) {
                bytes[buffer.getInt(132 + i * 12 + 4)] = 'x';
            }
        }
        var damaged = ColorProfile.parse(bytes);
        var reversed = new ArrayList<>(NAMES.subList(0, 5)).reversed();
        var samples = new byte[5 * 16];
        new SplittableRandom(3).nextBytes(samples);
        var plain = ColorTransform.create(damaged, srgb);
        var straight = new byte[16 * 3];
        plain.transform(samples, 0, 5, straight, 0, 3, 16);
        var read = (Channel8Picture) ImageOps.convertColor(Channel8Picture.of(16, 1, 80, samples,
                PixelLayout.ofInks(reversed)), plain);
        assertArrayEquals(straight, read.pixels());
        var written = (Channel8Picture) ImageOps.convertColor(RgbaPicture.allocate(2, 2, false), srgb, damaged);
        assertEquals(PixelLayout.ofInks(java.util.Collections.nCopies(5, "")), written.layout());
    }
}
