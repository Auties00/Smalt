package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.dds.DdsDecoder;
import com.github.auties00.smalt.image.psd.PsdDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Checks that the Photoshop documents and DDS textures of the image training workloads decode to the picture they
 * were written from, which also checks the PSD and DDS decoders against independent writers.
 */
@DisplayName("synthetic PSD and DDS files of the training workloads")
class SyntheticImageFilesTest {
    private static RgbaPicture picture(int width, int height) {
        return SyntheticImages.rgba(SyntheticVideo.natural(width, height, 1).getFirst(), true);
    }

    @ParameterizedTest(name = "mode {0}, {1} bits, compression {2}, large {3}")
    @CsvSource({"3, 8, 1, false", "3, 8, 0, false", "3, 8, 2, true", "3, 16, 3, false", "3, 16, 1, true",
            "1, 8, 2, false", "1, 16, 3, false"})
    @DisplayName("an RGB or gray document decodes to the picture's channels and its transparency")
    void psd(int mode, int depth, int compression, boolean large) {
        var source = picture(320, 240);
        var file = SyntheticImageFiles.psd(source, mode, depth, compression, large);
        var options = ImageDecoderOptions.builder().highBitDepth(depth == 16).build();
        var picture = PsdDecoder.decode(MediaSource.of(file), options).picture();
        assertEquals(320, picture.width());
        assertEquals(240, picture.height());
        for (var y = 0; y < 240; y++) {
            for (var x = 0; x < 320; x++) {
                var at = y * source.stride() + x * 4;
                for (var c = 0; c < 4; c++) {
                    var channel = mode == SyntheticImageFiles.PSD_GRAY && c < 3 ? 1 : c;
                    var expected = source.pixels()[at + channel] & 0xFF;
                    int actual;
                    if (depth == 16) {
                        var wide = assertInstanceOf(Rgba16Picture.class, picture);
                        actual = wide.pixels()[y * wide.stride() + x * 4 + c] & 0xFFFF;
                        expected *= 257;
                    } else {
                        var rgba = assertInstanceOf(RgbaPicture.class, picture);
                        actual = rgba.pixels()[y * rgba.stride() + x * 4 + c] & 0xFF;
                    }
                    assertEquals(expected, actual, "channel " + c + " at " + x + "," + y);
                }
            }
        }
    }

    @Test
    @DisplayName("a CMYK document decodes")
    void cmyk() {
        var file = SyntheticImageFiles.psd(picture(64, 40), SyntheticImageFiles.PSD_CMYK, 8, 0, false);
        assertEquals(64, PsdDecoder.decode(MediaSource.of(file)).picture().width());
    }

    @Test
    @DisplayName("a texture of 32-bit texels decodes to the picture and has every mipmap level")
    void ddsMasks() {
        var source = picture(64, 32);
        var file = SyntheticImageFiles.ddsMasks(source);
        try (var pages = DdsDecoder.openPages(MediaSource.of(file))) {
            assertEquals(7, pages.pageCount());
            var top = assertInstanceOf(RgbaPicture.class, pages.read(0).picture());
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 64; x++) {
                    for (var c = 0; c < 4; c++) {
                        assertEquals(source.pixels()[y * source.stride() + x * 4 + c],
                                top.pixels()[y * top.stride() + x * 4 + c], "channel " + c + " at " + x + "," + y);
                    }
                }
            }
            var last = pages.read(6).picture();
            assertEquals(1, last.width());
            assertEquals(1, last.height());
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"DXT1, 8", "DXT5, 16", "ATI2, 16"})
    @DisplayName("a block-compressed texture of a legacy code decodes to its size")
    void ddsFourCc(String code, int bytes) {
        var picture = DdsDecoder.decode(MediaSource.of(SyntheticImageFiles.ddsFourCc(64, 32, code, bytes, 1)))
                .picture();
        assertEquals(64, picture.width());
        assertEquals(32, picture.height());
    }

    @ParameterizedTest(name = "DXGI {0}")
    @CsvSource({"80, 8, true", "95, 16, true", "96, 16, true", "98, 16, true", "10, 8, false", "24, 4, false",
            "26, 4, false"})
    @DisplayName("a texture of a DX10 format decodes to its size")
    void ddsDx10(int dxgi, int bytes, boolean blocks) {
        var file = SyntheticImageFiles.ddsDx10(64, 32, dxgi, bytes, blocks, dxgi);
        var picture = DdsDecoder.decode(MediaSource.of(file)).picture();
        assertEquals(64, picture.width());
        assertEquals(32, picture.height());
        var channels = DdsDecoder.decode(MediaSource.of(file), ImageDecoderOptions.builder().keepChannels(true)
                .build()).picture();
        assertEquals(64, channels.width());
    }
}
