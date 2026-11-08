package com.github.auties00.smalt.image.internal.raster;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ink separations of the raster decoders (JPEG XR independent channels): converted to sRGB through an
 * embedded multi-ink profile, as {@link ImageOps#convertColor} converts them, with or without
 * {@code convertToSrgb}.
 */
@DisplayName("Raster ink separations")
class RasterOutputInksTest {
    private static final int WIDTH = 13;
    private static final int HEIGHT = 5;

    private static byte[] icc() {
        // the two-ink profile of tools/testdata/icc-multi-ink, pinned by TiffInksTest
        try (var input = RasterOutputInksTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/image/tiff/inks-named/ink2.icc")) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static final PixelLayout UNNAMED = PixelLayout.ofInks(List.of("", ""));

    @Test
    @DisplayName("8-bit separations convert through their multi-ink profile by default and with convertToSrgb")
    void eightBit() {
        var icc = icc();
        var profile = ColorProfile.parse(icc);
        var data = new byte[WIDTH * HEIGHT * 2];
        new SplittableRandom(7).nextBytes(data);
        var picture = Channel8Picture.of(WIDTH, HEIGHT, WIDTH * 2, data, UNNAMED);
        var expected = (Channel8Picture) ImageOps.convertColor(picture, profile, ColorProfile.srgb());
        for (var options : List.of(ImageDecoderOptions.defaults(),
                ImageDecoderOptions.builder().convertToSrgb(true).build(),
                ImageDecoderOptions.builder().convertToSrgb(true).keepChannels(true).build())) {
            var decoded = RasterOutput.finish(picture, ImageFormat.JXR, Orientation.NORMAL, profile, icc, options);
            var rgba = assertInstanceOf(RgbaPicture.class, decoded.picture());
            assertTrue(decoded.colorProfile().orElseThrow().isSrgb());
            for (var p = 0; p < WIDTH * HEIGHT; p++) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(expected.pixels()[p * 3 + c], rgba.pixels()[p * 4 + c], "pixel " + p);
                }
                assertEquals((byte) 0xFF, rgba.pixels()[p * 4 + 3]);
            }
        }
    }

    @Test
    @DisplayName("16-bit separations keep 16 bits with highBitDepth")
    void sixteenBit() {
        var icc = icc();
        var profile = ColorProfile.parse(icc);
        var random = new SplittableRandom(11);
        var data = new short[WIDTH * HEIGHT * 2];
        for (var i = 0; i < data.length; i++) {
            data[i] = (short) random.nextInt(65536);
        }
        var picture = Channel16Picture.of(WIDTH, HEIGHT, WIDTH * 2, data, UNNAMED);
        var expected = (Channel16Picture) ImageOps.convertColor(picture, profile, ColorProfile.srgb());
        var decoded = RasterOutput.finish(picture, ImageFormat.JXR, Orientation.NORMAL, profile, icc,
                ImageDecoderOptions.builder().highBitDepth(true).build());
        var rgba = assertInstanceOf(Rgba16Picture.class, decoded.picture());
        for (var p = 0; p < WIDTH * HEIGHT; p++) {
            for (var c = 0; c < 3; c++) {
                assertEquals(expected.pixels()[p * 3 + c], rgba.pixels()[p * 4 + c], "pixel " + p);
            }
        }
        var narrow = RasterOutput.finish(picture, ImageFormat.JXR, Orientation.NORMAL, profile, icc,
                ImageDecoderOptions.defaults());
        assertInstanceOf(RgbaPicture.class, narrow.picture());
    }

    @Test
    @DisplayName("kept separations carry their profile, and separations without a fitting one are unsupported")
    void keptAndUnfit() {
        var icc = icc();
        var profile = ColorProfile.parse(icc);
        var picture = Channel8Picture.allocate(WIDTH, HEIGHT, UNNAMED);
        var kept = RasterOutput.finish(picture, ImageFormat.JXR, Orientation.NORMAL, profile, icc,
                ImageDecoderOptions.builder().keepChannels(true).build());
        assertSame(picture, kept.picture());
        assertEquals(profile, kept.colorProfile().orElseThrow());
        assertInstanceOf(ChannelPicture.class, kept.picture());
        var three = Channel8Picture.allocate(WIDTH, HEIGHT, PixelLayout.ofInks(List.of("", "", "")));
        assertThrows(MediaException.Unsupported.class, () -> RasterOutput.finish(three, ImageFormat.JXR,
                Orientation.NORMAL, profile, icc, ImageDecoderOptions.defaults()));
        assertThrows(MediaException.Unsupported.class, () -> RasterOutput.finish(picture, ImageFormat.JXR,
                Orientation.NORMAL, null, null, ImageDecoderOptions.defaults()));
    }
}
