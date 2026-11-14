package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes every kind of picture through the image providers of this artifact, lossy and lossless, with no color
 * profile, an RGB profile and a gray profile, and decodes the result: each file must decode to a picture of the same
 * size, and a profile the file records must be the requested one or, where the format converts the samples, sRGB.
 */
@DisplayName("image providers across picture kinds and color profiles")
class ProfileCodingMatrixTest {
    private static final int WIDTH = 24;
    private static final int HEIGHT = 18;

    private static Map<String, Supplier<Picture>> pictures() {
        return Map.of(
                "rgb", () -> {
                    var picture = RgbaPicture.allocate(WIDTH, HEIGHT, false);
                    var pixels = picture.pixels();
                    for (var index = 0; index < pixels.length; index++) {
                        pixels[index] = (byte) (index * 7 + index / 97);
                    }
                    return picture;
                },
                "rgba", () -> {
                    var picture = RgbaPicture.allocate(WIDTH, HEIGHT, true);
                    var pixels = picture.pixels();
                    for (var index = 0; index < pixels.length; index++) {
                        pixels[index] = (byte) (index % 4 == 3 ? 200 : index * 5);
                    }
                    return picture;
                },
                "yuv-mono", () -> yuv(ChromaFormat.MONOCHROME),
                "yuv-420", () -> yuv(ChromaFormat.YUV420),
                "yuv16-mono", () -> {
                    var picture = Yuv16Picture.allocate(WIDTH, HEIGHT, ChromaFormat.MONOCHROME, 12, false, ColorInfo.JPEG);
                    var y = picture.y();
                    for (var index = 0; index < y.length; index++) {
                        y[index] = (short) ((index * 37) & 0xFFF);
                    }
                    return picture;
                },
                "gray8", () -> {
                    var picture = Channel8Picture.allocate(WIDTH, HEIGHT, PixelLayout.GRAY);
                    var pixels = picture.pixels();
                    for (var index = 0; index < pixels.length; index++) {
                        pixels[index] = (byte) (index * 11);
                    }
                    return picture;
                },
                "gray16", () -> {
                    var picture = Channel16Picture.allocate(WIDTH, HEIGHT, PixelLayout.GRAY);
                    var pixels = picture.pixels();
                    for (var index = 0; index < pixels.length; index++) {
                        pixels[index] = (short) (index * 257);
                    }
                    return picture;
                });
    }

    private static YuvPicture yuv(ChromaFormat format) {
        var picture = YuvPicture.allocate(WIDTH, HEIGHT, format, false, ColorInfo.JPEG);
        var y = picture.y();
        for (var index = 0; index < y.length; index++) {
            y[index] = (byte) (index * 13);
        }
        return picture;
    }

    @TestFactory
    @DisplayName("encode and decode")
    Stream<DynamicTest> matrix() {
        var formats = List.of(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.WEBP, ImageFormat.TIFF, ImageFormat.JP2,
                ImageFormat.JXL, ImageFormat.BMP, ImageFormat.GIF, ImageFormat.ICO, ImageFormat.ICNS, ImageFormat.TGA,
                ImageFormat.PNM, ImageFormat.QOI, ImageFormat.PSD, ImageFormat.EXR, ImageFormat.HDR);
        var profiles = new java.util.LinkedHashMap<String, ColorProfile>();
        profiles.put("none", null);
        profiles.put("srgb", ColorProfile.srgb());
        profiles.put("gray", ColorProfile.grayGamma22());
        var tests = new ArrayList<DynamicTest>();
        for (var format : formats) {
            if (!Images.isEncodable(format)) {
                continue;
            }
            for (var picture : new java.util.TreeMap<>(pictures()).entrySet()) {
                for (var profile : profiles.entrySet()) {
                    for (var lossless : new boolean[]{false, true}) {
                        tests.add(DynamicTest.dynamicTest(format + " " + picture.getKey() + " " + profile.getKey()
                                + (lossless ? " lossless" : " lossy"), () -> check(format, picture.getValue().get(),
                                profile.getValue(), lossless)));
                    }
                }
            }
        }
        return tests.stream();
    }

    private static void check(ImageFormat format, Picture picture, ColorProfile profile, boolean lossless) {
        var builder = ImageEncoderOptions.builder().lossless(lossless);
        if (profile != null) {
            builder.colorProfile(profile);
        }
        byte[] data;
        try {
            data = Images.encode(picture, format, builder.build());
        } catch (com.github.auties00.smalt.MediaException.Unsupported | IllegalArgumentException refused) {
            return;
        }
        var decoded = Images.decode(MediaSource.of(data));
        if (format != ImageFormat.ICNS) {
            assertEquals(WIDTH, decoded.picture().width());
            assertEquals(HEIGHT, decoded.picture().height());
        }
        if (profile != null && decoded.colorProfile().isPresent()) {
            var space = decoded.colorProfile().get().colorSpace();
            // a format that cannot record the requested profile converts the samples to sRGB
            assertTrue(space == profile.colorSpace() || space == ProfileColorSpace.RGB, format + " "
                    + picture.getClass().getSimpleName() + " records the requested profile or sRGB, not " + space);
        }
    }
}
