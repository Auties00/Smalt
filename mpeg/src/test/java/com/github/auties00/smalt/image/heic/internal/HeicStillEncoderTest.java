package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.heic.HeicEncoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the decisions of the HEIC still encoder that need no coding: the quality scale, the automatic grid, which
 * splits only the pictures over HEVC level 5.1, the grid size limits, and the automatic bit depth.
 */
@DisplayName("HEIC still encoder decisions")
class HeicStillEncoderTest {
    @ParameterizedTest(name = "quality {0} -> quantizer {1}")
    @CsvSource({"0, 51", "1, 50", "50, 26", "80, 10", "99, 1", "100, 0"})
    @DisplayName("the quality maps linearly onto the HEVC quantizer, rounded to nearest")
    void quantizer(int quality, int quantizer) {
        assertEquals(quantizer, HeicStillEncoder.quantizer(quality));
    }

    @ParameterizedTest(name = "{0}x{1} -> {2}")
    @CsvSource({
            "4096, 2176, 0", "4096, 2177, 512", "8444, 1055, 0", "8445, 64, 512", "64, 8445, 512",
            "2985, 2985, 0", "2986, 2986, 512", "1, 1, 0"
    })
    @DisplayName("an automatic grid splits only pictures over the HEVC level 5.1 size into 512-sample images")
    void automaticGrid(int width, int height, int tile) {
        assertEquals(tile, HeicStillEncoder.gridTile(width, height, HeicEncoderOptions.defaults()));
    }

    @Test
    @DisplayName("an explicit grid applies to every picture")
    void explicitGrid() {
        var options = HeicEncoderOptions.builder().gridTileSize(128).build();
        assertEquals(128, HeicStillEncoder.gridTile(16, 16, options));
        assertEquals(128, HeicStillEncoder.gridTile(10_000, 10_000, options));
    }

    @Test
    @DisplayName("a grid is rejected when it exceeds 256 images per side or the file's 65535 items")
    void gridFits() {
        HeicStillEncoder.checkGrid(256, 255, false);
        HeicStillEncoder.checkGrid(255, 128, true);
        assertThrows(MediaException.LimitExceeded.class, () -> HeicStillEncoder.checkGrid(257, 1, false));
        assertThrows(MediaException.LimitExceeded.class, () -> HeicStillEncoder.checkGrid(1, 257, false));
        // 65536 images, one more than the 16-bit item identifiers number
        assertThrows(MediaException.LimitExceeded.class, () -> HeicStillEncoder.checkGrid(256, 256, false));
        // 2 x 32768 images with alpha: the 16384x8192 picture at the smallest tile side and the default limits
        assertThrows(MediaException.LimitExceeded.class, () -> HeicStillEncoder.checkGrid(256, 128, true));
    }

    @Test
    @DisplayName("the automatic depth is 8 for 8-bit pictures and 10 for deeper ones, unless the options set it")
    void bitDepth() {
        var defaults = HeicEncoderOptions.defaults();
        assertEquals(8, HeicStillEncoder.bitDepth(RgbaPicture.allocate(4, 4, false), defaults));
        assertEquals(10, HeicStillEncoder.bitDepth(Rgba16Picture.allocate(4, 4, false), defaults));
        assertEquals(8, HeicStillEncoder.bitDepth(Yuv16Picture.allocate(4, 4, ChromaFormat.YUV420, 8, false,
                ColorInfo.JPEG), defaults));
        assertEquals(10, HeicStillEncoder.bitDepth(Yuv16Picture.allocate(4, 4, ChromaFormat.YUV420, 12, false,
                ColorInfo.JPEG), defaults));
        var eight = HeicEncoderOptions.builder().bitDepth(8).build();
        assertEquals(8, HeicStillEncoder.bitDepth(Rgba16Picture.allocate(4, 4, false), eight));
    }
}
