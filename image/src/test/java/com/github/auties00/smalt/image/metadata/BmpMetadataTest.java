package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.bmp.BmpDecoder;
import com.github.auties00.smalt.image.bmp.BmpEncoder;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.HeaderVersion;
import com.github.auties00.smalt.image.bmp.BmpEncoderOptions.PixelFormat;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the ICC profile of BMP files in {@link ImageMetadata} and {@link ImageMetadataEditor}: reading it from a V5
 * header, replacing it in files of every header version and layout (the header widened to V5, the color table and
 * pixels kept), removing it, and rejecting what a BMP file cannot store; ffmpeg reads every edited file to the
 * pixels it reads from the original.
 */
class BmpMetadataTest {
    private static RgbaPicture picture(int width, int height, int colors, long seed) {
        var random = new Random(seed);
        var palette = new int[Math.max(colors, 1)];
        for (var i = 0; i < palette.length; i++) {
            palette[i] = random.nextInt();
        }
        var pixels = new byte[width * height * 4];
        for (var i = 0; i < width * height; i++) {
            var color = colors == 0 ? random.nextInt() : palette[random.nextInt(colors)];
            pixels[i * 4] = (byte) (color >>> 16);
            pixels[i * 4 + 1] = (byte) (color >>> 8);
            pixels[i * 4 + 2] = (byte) color;
            pixels[i * 4 + 3] = (byte) 0xFF;
        }
        return RgbaPicture.of(width, height, width * 4, pixels, false);
    }

    private static byte[] pixels(byte[] bmp) {
        return RasterTesting.tight(BmpDecoder.decode(MediaSource.of(bmp)).picture());
    }

    // an OS/2 1.x bitmap: 12-byte header and a 3-byte color table
    private static byte[] core(RgbaPicture picture) {
        var eightBit = BmpEncoder.encode(picture, BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8).build());
        var colors = ByteArrays.i32le(eightBit, 46);
        var stride = (picture.width() + 3) / 4 * 4;
        var data = new byte[14 + 12 + colors * 3 + stride * picture.height()];
        data[0] = 'B';
        data[1] = 'M';
        ByteArrays.put32le(data, 2, data.length);
        ByteArrays.put32le(data, 10, 14 + 12 + colors * 3);
        ByteArrays.put32le(data, 14, 12);
        ByteArrays.put16le(data, 18, picture.width());
        ByteArrays.put16le(data, 20, picture.height());
        ByteArrays.put16le(data, 22, 1);
        ByteArrays.put16le(data, 24, 8);
        for (var i = 0; i < colors; i++) {
            System.arraycopy(eightBit, 54 + i * 4, data, 26 + i * 3, 3);
        }
        System.arraycopy(eightBit, ByteArrays.i32le(eightBit, 10), data, 26 + colors * 3, stride * picture.height());
        // a core header holds no color count: the data offset bounds the color table
        return data;
    }

    static Stream<Arguments> files() {
        var picture = picture(19, 7, 0, 1);
        var paletted = picture(21, 5, 256, 2);
        var full = picture(16, 16, 256, 3);
        return Stream.of(
                Arguments.of("24-bit, BITMAPINFOHEADER", BmpEncoder.encode(picture, BmpEncoderOptions.builder()
                        .pixelFormat(PixelFormat.RGB_888).build())),
                Arguments.of("5-6-5 with masks after a BITMAPINFOHEADER", BmpEncoder.encode(picture,
                        BmpEncoderOptions.builder().pixelFormat(PixelFormat.RGB_565).build())),
                Arguments.of("8-bit palette, V4", BmpEncoder.encode(paletted, BmpEncoderOptions.builder()
                        .pixelFormat(PixelFormat.PALETTE_8).headerVersion(HeaderVersion.V4).build())),
                Arguments.of("RLE8", BmpEncoder.encode(paletted, BmpEncoderOptions.builder().pixelFormat(PixelFormat.PALETTE_8)
                        .runLength(true).build())),
                Arguments.of("RLE4", BmpEncoder.encode(picture(30, 9, 16, 4), BmpEncoderOptions.builder()
                        .pixelFormat(PixelFormat.PALETTE_4).runLength(true).build())),
                Arguments.of("1-bit palette, top-down", BmpEncoder.encode(picture(33, 3, 2, 5), BmpEncoderOptions.builder()
                        .pixelFormat(PixelFormat.PALETTE_1).topDown(true).build())),
                Arguments.of("ARGB, V5 with sRGB", BmpEncoder.encode(picture, BmpEncoderOptions.builder()
                        .pixelFormat(PixelFormat.ARGB_8888).build())),
                Arguments.of("24-bit, V5 with a profile", BmpEncoder.encode(picture, BmpEncoderOptions.builder()
                        .colorProfile(ColorProfile.adobeRgb()).build())),
                Arguments.of("OS/2 1.x core header", core(full)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("replaces the profile in a V5 header, keeping the color table and the pixels")
    void replace(String name, byte[] bmp) {
        var profile = ColorProfile.displayP3();
        var edited = ImageMetadataEditor.apply(bmp, ImageMetadataEdit.builder().colorProfile(profile).build());
        assertEquals(124, ByteArrays.i32le(edited, 14), name);
        assertEquals(0x4D424544, ByteArrays.i32le(edited, 14 + 56), name);
        assertEquals(edited.length, ByteArrays.i32le(edited, 2), name + ": file size field");
        var icc = profile.toByteArray();
        assertEquals(edited.length - icc.length, ByteArrays.i32le(edited, 14 + 112) + 14, name + ": the profile ends the file");
        assertArrayEquals(pixels(bmp), pixels(edited), name);
        var metadata = ImageMetadata.read(edited);
        assertEquals(ImageFormat.BMP, metadata.format());
        assertArrayEquals(icc, metadata.iccProfile().orElseThrow());
        assertArrayEquals(icc, BmpDecoder.decode(MediaSource.of(edited)).iccProfile().orElseThrow());
        var removed = ImageMetadataEditor.apply(edited, ImageMetadataEdit.builder().removeIccProfile().build());
        assertEquals(edited.length - icc.length, removed.length, name);
        assertEquals(0x73524742, ByteArrays.i32le(removed, 14 + 56), name);
        assertTrue(ImageMetadata.read(removed).iccProfile().isEmpty(), name);
        assertArrayEquals(pixels(bmp), pixels(removed), name);
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var label = name.replaceAll("[^A-Za-z0-9]", "_");
            var original = RasterTesting.write("bmp-edit-" + label + "-original.bmp", bmp);
            var path = RasterTesting.write("bmp-edit-" + label + ".bmp", edited);
            assertArrayEquals(RasterTesting.ffmpegFrames(original, "rgba"), RasterTesting.ffmpegFrames(path, "rgba"),
                    name + ": ffmpeg");
        }
    }

    @Test
    @DisplayName("reads the embedded profile and nothing else")
    void read() {
        var profile = ColorProfile.adobeRgb();
        var bmp = BmpEncoder.encode(picture(5, 5, 0, 7), BmpEncoderOptions.builder().colorProfile(profile).build());
        var metadata = ImageMetadata.read(bmp);
        assertArrayEquals(profile.toByteArray(), metadata.iccProfile().orElseThrow());
        assertEquals(profile, metadata.colorProfile().orElseThrow());
        assertTrue(metadata.exifBytes().isEmpty());
        assertTrue(metadata.xmp().isEmpty());
        assertTrue(ImageMetadata.read(BmpEncoder.encode(picture(5, 5, 0, 7))).iccProfile().isEmpty());
        assertSame(bmp, ImageMetadataEditor.apply(bmp, ImageMetadataEdit.stripAll()), "a BMP file holds nothing else to strip");
        var plain = BmpEncoder.encode(picture(5, 5, 0, 7));
        assertSame(plain, ImageMetadataEditor.apply(plain, ImageMetadataEdit.builder().removeIccProfile().build()),
                "removing an absent profile changes nothing");
    }

    @Test
    @DisplayName("leaves out an old profile stored between the color table and the pixels")
    void profileBeforePixels() {
        var picture = picture(9, 4, 0, 8);
        var bmp = BmpEncoder.encode(picture, BmpEncoderOptions.builder().colorProfile(ColorProfile.adobeRgb()).build());
        var icc = ColorProfile.adobeRgb().toByteArray();
        var dataOffset = ByteArrays.i32le(bmp, 10);
        var dataSize = ByteArrays.i32le(bmp, 34);
        var moved = new byte[bmp.length];
        System.arraycopy(bmp, 0, moved, 0, dataOffset);
        System.arraycopy(icc, 0, moved, dataOffset, icc.length);
        System.arraycopy(bmp, dataOffset, moved, dataOffset + icc.length, dataSize);
        ByteArrays.put32le(moved, 10, dataOffset + icc.length);
        ByteArrays.put32le(moved, 14 + 112, dataOffset - 14);
        assertArrayEquals(icc, ImageMetadata.read(moved).iccProfile().orElseThrow());
        assertArrayEquals(pixels(bmp), pixels(moved));
        var replaced = ImageMetadataEditor.apply(moved, ImageMetadataEdit.builder().colorProfile(ColorProfile.displayP3())
                .build());
        assertEquals(dataOffset, ByteArrays.i32le(replaced, 10), "the old profile no longer precedes the pixels");
        assertEquals(dataOffset + dataSize + ColorProfile.displayP3().toByteArray().length, replaced.length);
        assertArrayEquals(pixels(bmp), pixels(replaced));
    }

    @Test
    @DisplayName("rejects metadata other than a profile, and profiles in bitmap arrays and OS/2 compressions")
    void unsupported() {
        var bmp = BmpEncoder.encode(picture(4, 4, 0, 9));
        assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(bmp,
                ImageMetadataEdit.builder().exif(ExifData.EMPTY).orientation(Orientation.values()[1]).build()));
        assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(bmp,
                ImageMetadataEdit.builder().comments(java.util.List.of("comment")).build()));
        var array = new byte[14 + bmp.length];
        array[0] = 'B';
        array[1] = 'A';
        System.arraycopy(bmp, 0, array, 14, bmp.length);
        ByteArrays.put32le(array, 14 + 10, ByteArrays.i32le(bmp, 10) + 14);
        assertEquals(ImageFormat.BMP, ImageMetadata.read(array).format());
        assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(array,
                ImageMetadataEdit.builder().colorProfile(ColorProfile.srgb()).build()));
        var rle24 = BmpEncoder.encode(picture(4, 4, 0, 9), BmpEncoderOptions.builder().pixelFormat(PixelFormat.RGB_888)
                .build());
        ByteArrays.put32le(rle24, 30, 4);
        assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(rle24,
                ImageMetadataEdit.builder().colorProfile(ColorProfile.srgb()).build()));
    }
}
