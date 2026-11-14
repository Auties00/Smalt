package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the output of the image operations whose kernels have Vector API twins: resampling with
 * every filter at 8 and 16 bits and in linear light, palette quantization, the color conversions between YUV and RGBA, and the color
 * management transforms. Each operation runs on fixed pictures and the SHA-256 of its result must
 * equal a recorded digest, so a kernel rewrite that claims identical output is checked byte for
 * byte. The digests hold for the Vector API kernels and for their scalar twins alike: the suite's
 * scalar leg ({@code -Dsmalt.test.simd=false}) runs the same digests.
 */
@DisplayName("image operation golden outputs")
class ImageOpsGoldenTest {
    private static final RgbaPicture STICKER = sticker(301, 217);
    private static final RgbaPicture OPAQUE = Pictures.smoothRgba(257, 191, false);
    private static final YuvPicture PHOTO = Pictures.randomYuv(new SplittableRandom(11), 322, 182, ChromaFormat.YUV420,
            false, ColorInfo.BT709_LIMITED);
    private static final Rgba16Picture DEEP_STICKER = ImageOps.toSixteenBit(STICKER);
    private static final Yuv16Picture DEEP_PHOTO = HighDepthResizeTest.randomYuv16(new SplittableRandom(12), 322, 182,
            ChromaFormat.YUV420, 10, true, ColorInfo.BT709_LIMITED);
    /** The filters whose 8-bit digests predate the floating-point paths, which leave them unchanged. */
    private static final List<ResizeFilter> FIXED_POINT = List.of(ResizeFilter.NEAREST, ResizeFilter.BILINEAR,
            ResizeFilter.BICUBIC, ResizeFilter.LANCZOS3, ResizeFilter.AREA);
    private static final List<ResizeFilter> ADDED = List.of(ResizeFilter.BOX, ResizeFilter.HAMMING, ResizeFilter.MITCHELL,
            ResizeFilter.LANCZOS2, ResizeFilter.LANCZOS4);

    static Stream<Arguments> operations() {
        return Stream.of(
                operation("resize sticker down, every filter", () -> FIXED_POINT.stream()
                        .map(filter -> ImageOps.resize(STICKER, 128, 97, filter)).toList(), "5360e6faf52cad717ee181740862ae500af953af4f4e9b75bc96af01772ceaef"),
                operation("resize sticker up, every filter", () -> FIXED_POINT.stream()
                        .map(filter -> ImageOps.resize(STICKER, 512, 403, filter)).toList(), "041c201911f914cb74671a1a9d951c813331e9be2b4c3cb5b625804c54d2c440"),
                operation("resize photo, every filter", () -> FIXED_POINT.stream()
                        .map(filter -> ImageOps.resize(PHOTO, 211, 117, filter)).toList(), "c414996779b9eedb7b980a72ecef1be8bcd35eeb42c038d88fde764734909d68"),
                operation("resize sticker and photo, the added filters", () -> ADDED.stream()
                        .flatMap(filter -> Stream.of(ImageOps.resize(STICKER, 128, 97, filter), ImageOps.resize(STICKER, 512, 403, filter),
                                ImageOps.resize(PHOTO, 211, 117, filter))).toList(), "7c29f324fd0ebbf760c5eaf859f223cc0ebb70c602d97a096fb4c2014b6f559b"),
                operation("resize 16-bit sticker and 10-bit photo, every filter", () -> List.of(ResizeFilter.values()).stream()
                        .flatMap(filter -> Stream.of(ImageOps.resize(DEEP_STICKER, 128, 97, filter), ImageOps.resize(DEEP_STICKER, 512, 403, filter),
                                ImageOps.resize(DEEP_PHOTO, 211, 117, filter))).toList(), "ef12b16904bc0529191e93b95ac5f7c1b21cd996e0db3bbec3ffef8f61771904"),
                operation("resize in linear light", () -> List.of(
                        ImageOps.resize(STICKER, 128, 97, ResizeOptions.builder().linearLight(true).build()),
                        ImageOps.resize(OPAQUE, 300, 77, ResizeOptions.builder().filter(ResizeFilter.MITCHELL).linearLight(true).build()),
                        ImageOps.resize(DEEP_STICKER, 211, 117, ResizeOptions.builder().filter(ResizeFilter.AREA).linearLight(true).build()),
                        ImageOps.resize(PHOTO, 100, 60, ResizeOptions.builder().filter(ResizeFilter.BOX).linearLight(true).build())), "ef7cde11143d7f36da47ed136c94ca0672fd97a9b7f0c1d5097cf1a876418e13"),
                operation("fit within and fit into", () -> List.of(ImageOps.fitWithin(OPAQUE, 100, ResizeFilter.LANCZOS3),
                        ImageOps.fitInto(STICKER, 200, 120, ResizeFilter.BILINEAR, 0xFF336699)), "42bf2ea40d5d6198f6acf1297100db891f9e463125ace555849fb45f559f1663"),
                operation("YUV and RGBA conversions", () -> List.of(ImageOps.toRgba(PHOTO),
                        ImageOps.toYuv(STICKER, ChromaFormat.YUV420, ColorInfo.BT601_LIMITED),
                        ImageOps.toYuv(OPAQUE, ChromaFormat.YUV444, ColorInfo.JPEG)), "cd301e169b629402a34fce53a18a63af7c78713055bc5405e1b43ea139c7cd4d"),
                operation("color management", () -> List.of(
                        ImageOps.convertColor(OPAQUE, ColorProfile.displayP3(), ColorProfile.srgb()),
                        ImageOps.convertColor(STICKER, ColorProfile.adobeRgb(), ColorProfile.srgb()),
                        ImageOps.convertColor(PHOTO, ColorProfile.proPhotoRgb(), ColorProfile.srgb())), "d343f351dea3fdf7c2b2470efc4d3480d5c2b6b6243500945385cd20b9e8c687"));
    }

    static Stream<Arguments> quantizations() {
        return Stream.of(
                Arguments.of(QuantizeMethod.MEDIAN_CUT, Dithering.NONE, "02704d5ab89554a21fa71210aeeb883ad2589cc889df1a3142419e8c6ec822f5"),
                Arguments.of(QuantizeMethod.MEDIAN_CUT, Dithering.ORDERED, "5fd88290d36c119db91dc759b59eca636d7c7a4a043f385705d499b39770a416"),
                Arguments.of(QuantizeMethod.MEDIAN_CUT, Dithering.FLOYD_STEINBERG, "fd6fdf42be368ba523b515745a45427eedea77a197f803d94b732abb85714465"),
                Arguments.of(QuantizeMethod.MEDIAN_CUT, Dithering.SIERRA, "84e37ea487870fc6c5e0010e2bd9c846273c4d4d0c5e0e0f554de41ef4c0ae9e"),
                Arguments.of(QuantizeMethod.NEUQUANT, Dithering.NONE, "54200221377efcfa97e36401082d4ef5d4145fe26ca4883c47bfa2f7f54201c8"),
                Arguments.of(QuantizeMethod.NEUQUANT, Dithering.ORDERED, "c85cc57723861c7e15b4d9ffa3aa1c9e7115859857fa7b1ae797b5c70bfaa9df"),
                Arguments.of(QuantizeMethod.NEUQUANT, Dithering.FLOYD_STEINBERG, "5f25e44447bc5d77e3aeee7e403e8b47ab636b1b3e80aa4d64e92c2fbd3e4778"),
                Arguments.of(QuantizeMethod.NEUQUANT, Dithering.SIERRA, "582fa89a97f818d0afcef687d2e444b51d82baa6ca53ab95e11b0a0130af4521"));
    }

    private static Arguments operation(String name, Supplier<List<Picture>> run, String expected) {
        return Arguments.of(name, run, expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    @DisplayName("produce exactly the recorded pictures")
    void unchanged(String name, Supplier<List<Picture>> run, String expected) {
        assertDigest(name, expected, () -> {
            var sha = sha256();
            for (var picture : run.get()) {
                update(sha, picture);
            }
            return HexFormat.of().formatHex(sha.digest());
        });
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("quantizations")
    @DisplayName("quantizes to exactly the recorded palette and indices")
    void quantized(QuantizeMethod method, Dithering dithering, String expected) {
        var options = QuantizeOptions.builder().colors(48).method(method).dithering(dithering).build();
        assertDigest(method + ", " + dithering, expected, () -> {
            var sha = sha256();
            for (var picture : new RgbaPicture[]{STICKER, OPAQUE}) {
                var quantized = ImageQuantization.quantize(picture, options);
                var palette = ByteBuffer.allocate(4 * quantized.palette().length + 4);
                for (var color : quantized.palette()) {
                    palette.putInt(color);
                }
                palette.putInt(quantized.transparentIndex().orElse(-1));
                sha.update(palette.array());
                sha.update(quantized.indices());
            }
            return HexFormat.of().formatHex(sha.digest());
        });
    }

    private static void assertDigest(String name, String expected, Supplier<String> run) {
        assertEquals(expected, run.get(), name);
    }

    private static RgbaPicture sticker(int width, int height) {
        var random = new SplittableRandom(7);
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var o = y * picture.stride() + 4 * x;
                var alpha = Math.clamp((long) ((100 - Math.hypot(x - width / 2.0, y - height / 2.0)) * 6), 0, 255);
                pixels[o] = (byte) (128 + 90 * Math.sin(x / 13.0) + random.nextInt(9));
                pixels[o + 1] = (byte) (x ^ y);
                pixels[o + 2] = (byte) ((x * y) >> 7);
                pixels[o + 3] = (byte) alpha;
            }
        }
        return picture;
    }

    private static void update(MessageDigest sha, Picture picture) {
        sha.update(ByteBuffer.allocate(8).putInt(picture.width()).putInt(picture.height()).array());
        switch (picture) {
            case ChannelPicture channels -> throw new AssertionError("unexpected " + channels);
            case RgbaPicture rgba -> {
                for (var y = 0; y < rgba.height(); y++) {
                    sha.update(rgba.pixels(), y * rgba.stride(), 4 * rgba.width());
                }
            }
            case YuvPicture yuv -> {
                for (var y = 0; y < yuv.height(); y++) {
                    sha.update(yuv.y(), y * yuv.yStride(), yuv.width());
                }
                for (var y = 0; y < yuv.chromaHeight(); y++) {
                    sha.update(yuv.u(), y * yuv.uvStride(), yuv.chromaWidth());
                    sha.update(yuv.v(), y * yuv.uvStride(), yuv.chromaWidth());
                }
            }
            case Rgba16Picture rgba -> {
                var row = ByteBuffer.allocate(8 * rgba.width());
                for (var y = 0; y < rgba.height(); y++) {
                    row.clear();
                    for (var i = 0; i < 4 * rgba.width(); i++) {
                        row.putShort(rgba.pixels()[y * rgba.stride() + i]);
                    }
                    sha.update(row.array());
                }
            }
            case Yuv16Picture yuv -> {
                update(sha, yuv.y(), yuv.yStride(), yuv.width(), yuv.height());
                update(sha, yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                update(sha, yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight());
                if (yuv.hasAlpha()) {
                    update(sha, yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height());
                }
            }
        }
    }

    private static void update(MessageDigest sha, short[] plane, int stride, int width, int height) {
        var row = ByteBuffer.allocate(2 * width);
        for (var y = 0; y < height; y++) {
            row.clear();
            for (var x = 0; x < width; x++) {
                row.putShort(plane[y * stride + x]);
            }
            sha.update(row.array());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
