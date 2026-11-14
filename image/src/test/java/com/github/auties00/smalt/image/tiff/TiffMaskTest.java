package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers transparency masks (photometric interpretation 4): a mask page after the page it masks, or in its
 * {@code SubIFDs}, at the page's resolution, above it and below it, applied to opaque, alpha, 16-bit and channel
 * pictures and to reduced-resolution levels. The expected alpha is computed here from the mask bits.
 */
@DisplayName("TIFF transparency masks")
class TiffMaskTest {
    private static final int WIDTH = 10;
    private static final int HEIGHT = 8;

    static TiffFiles rgb(long seed) {
        var values = TiffFiles.pattern(WIDTH, HEIGHT, 3, 255, seed);
        return TiffFiles.of(WIDTH, HEIGHT, 3, 8).data(TiffFiles.pack(values, WIDTH, HEIGHT, 3, 8, true));
    }

    static long[] bits(int width, int height, long seed) {
        var random = new Random(seed);
        var values = new long[width * height];
        for (var i = 0; i < values.length; i++) {
            values[i] = random.nextInt(3) == 0 ? 0 : 1;
        }
        return values;
    }

    static TiffFiles mask(long[] bits, int width, int height) {
        return TiffFiles.of(width, height, 1, 1).photometric(4).longTag(254, 4)
                .data(TiffFiles.pack(bits, width, height, 1, 1, true));
    }

    /** The mask coverage of each picture pixel, 0 to 255, from the spans the decoder documents. */
    static double[] coverage(long[] bits, int maskWidth, int maskHeight, int width, int height) {
        var result = new double[width * height];
        for (var y = 0; y < height; y++) {
            int y0;
            int y1;
            if (maskHeight >= height) {
                y0 = y * maskHeight / height;
                y1 = (y + 1) * maskHeight / height;
            } else {
                y0 = (2 * y + 1) * maskHeight / (2 * height);
                y1 = y0 + 1;
            }
            for (var x = 0; x < width; x++) {
                int x0;
                int x1;
                if (maskWidth >= width) {
                    x0 = x * maskWidth / width;
                    x1 = (x + 1) * maskWidth / width;
                } else {
                    x0 = (2 * x + 1) * maskWidth / (2 * width);
                    x1 = x0 + 1;
                }
                var sum = 0;
                for (var my = y0; my < y1; my++) {
                    for (var mx = x0; mx < x1; mx++) {
                        sum += (int) bits[my * maskWidth + mx];
                    }
                }
                result[y * width + x] = 255.0 * sum / ((y1 - y0) * (x1 - x0));
            }
        }
        return result;
    }

    @ParameterizedTest(name = "{0}x{1} mask")
    @CsvSource({"10, 8", "20, 16", "25, 19", "5, 4", "3, 11"})
    @DisplayName("a following mask page multiplies the alpha by its coverage, leaving the colors")
    void following(int maskWidth, int maskHeight) {
        var bits = bits(maskWidth, maskHeight, maskWidth * 31L + maskHeight);
        var page = rgb(1);
        var file = TiffFiles.chain(page, mask(bits, maskWidth, maskHeight));
        var plain = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(rgb(1).bytes())).picture());
        var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
        assertTrue(picture.hasAlpha());
        var pixels = RasterTesting.tight(picture);
        var expected = coverage(bits, maskWidth, maskHeight, WIDTH, HEIGHT);
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            for (var k = 0; k < 3; k++) {
                assertEquals(plain[i * 4 + k], pixels[i * 4 + k]);
            }
            assertEquals(Math.round(expected[i]), pixels[i * 4 + 3] & 0xFF, "alpha of pixel " + i);
        }
        try (var pages = TiffDecoder.openPages(MediaSource.of(file))) {
            assertEquals(2, pages.pageCount());
            assertTrue(pages.probe(0).hasAlpha());
            assertFalse(pages.probe(1).hasAlpha());
            assertTrue(pages.page(1).isMask());
            // the mask page itself decodes as black-is-zero gray
            var gray = RasterTesting.tight((RgbaPicture) pages.read(1).picture());
            for (var i = 0; i < maskWidth * maskHeight; i++) {
                assertEquals(bits[i] == 1 ? (byte) 0xFF : 0, gray[i * 4]);
            }
        }
    }

    @Test
    @DisplayName("an exact-size 1-bit mask makes the exterior transparent and keeps the interior opaque")
    void exact() {
        var bits = bits(WIDTH, HEIGHT, 7);
        var file = TiffFiles.chain(rgb(2), mask(bits, WIDTH, HEIGHT));
        var pixels = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture());
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            assertEquals(bits[i] == 1 ? (byte) 0xFF : 0, pixels[i * 4 + 3]);
        }
    }

    @Test
    @DisplayName("an existing alpha and a 16-bit picture are multiplied")
    void alphaAndDepth() {
        var bits = bits(20, 16, 3);
        var values = TiffFiles.pattern(WIDTH, HEIGHT, 4, 65535, 4);
        var page = TiffFiles.of(WIDTH, HEIGHT, 4, 16).extra(2).data(TiffFiles.pack(values, WIDTH, HEIGHT, 4, 16, true));
        var file = TiffFiles.chain(page, mask(bits, 20, 16));
        var expected = coverage(bits, 20, 16, WIDTH, HEIGHT);
        var wide = (Rgba16Picture) TiffDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().highBitDepth(true).build()).picture();
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var want = Math.round(values[i * 4 + 3] * expected[i] / 255);
            assertEquals(want, wide.pixels()[i * 4 + 3] & 0xFFFF, 1, "alpha of pixel " + i);
            assertEquals(values[i * 4], wide.pixels()[i * 4] & 0xFFFF);
        }
        var narrow = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            var alpha8 = (values[i * 4 + 3] * 255 + 32767) / 65535;
            assertEquals(Math.round(alpha8 * expected[i] / 255), narrow.pixels()[i * 4 + 3] & 0xFF, 1);
        }
    }

    @Test
    @DisplayName("a channel picture gains an alpha channel")
    void channels() {
        var bits = bits(WIDTH, HEIGHT, 5);
        var values = TiffFiles.pattern(WIDTH, HEIGHT, 4, 255, 6);
        var cmyk = TiffFiles.of(WIDTH, HEIGHT, 4, 8).photometric(5)
                .data(TiffFiles.pack(values, WIDTH, HEIGHT, 4, 8, true));
        var file = TiffFiles.chain(cmyk, mask(bits, WIDTH, HEIGHT));
        var picture = (Channel8Picture) TiffDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().keepChannels(true).build()).picture();
        assertEquals(5, picture.layout().count());
        assertEquals(ChannelType.ALPHA, picture.layout().channel(4).type());
        for (var i = 0; i < WIDTH * HEIGHT; i++) {
            for (var k = 0; k < 4; k++) {
                assertEquals((byte) values[i * 4 + k], picture.pixels()[i * 5 + k]);
            }
            assertEquals(bits[i] == 1 ? (byte) 0xFF : 0, picture.pixels()[i * 5 + 4]);
        }
    }

    @Test
    @DisplayName("a mask in SubIFDs applies, and reduced-resolution levels between the page and its mask are skipped")
    void placement() {
        var bits = bits(WIDTH, HEIGHT, 8);
        var withSub = rgb(3).sub(mask(bits, WIDTH, HEIGHT)).bytes();
        var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(withSub)).picture();
        assertTrue(picture.hasAlpha());
        assertEquals(bits[0] == 1 ? (byte) 0xFF : 0, picture.pixels()[3]);
        var levelValues = TiffFiles.pattern(5, 4, 3, 255, 9);
        var level = TiffFiles.of(5, 4, 3, 8).longTag(254, 1).data(TiffFiles.pack(levelValues, 5, 4, 3, 8, true));
        var file = TiffFiles.chain(rgb(3), level, mask(bits, WIDTH, HEIGHT));
        var full = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file)).picture();
        assertArrayEquals(RasterTesting.tight(picture), RasterTesting.tight(full));
        // the size hint selects the level, which the page's mask covers too
        var small = (RgbaPicture) TiffDecoder.decode(MediaSource.of(file),
                ImageDecoderOptions.builder().minSize(5, 4).build()).picture();
        assertEquals(5, small.width());
        var expected = coverage(bits, WIDTH, HEIGHT, 5, 4);
        for (var i = 0; i < 20; i++) {
            assertEquals(Math.round(expected[i]), small.pixels()[i * 4 + 3] & 0xFF);
        }
    }

    @Test
    @DisplayName("pages without a following mask, and masks that do not decode, leave the picture unchanged")
    void unmasked() {
        var plain = RasterTesting.tight((RgbaPicture) TiffDecoder.decode(MediaSource.of(rgb(4).bytes())).picture());
        var twoPages = TiffFiles.chain(rgb(4), rgb(5));
        var first = (RgbaPicture) TiffDecoder.decode(MediaSource.of(twoPages)).picture();
        assertFalse(first.hasAlpha());
        assertArrayEquals(plain, RasterTesting.tight(first));
        var broken = TiffFiles.of(WIDTH, HEIGHT, 1, 1).photometric(4).tag(259, 32908).data(new byte[20]);
        var withBroken = TiffFiles.chain(rgb(4), broken);
        var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(withBroken)).picture();
        assertFalse(picture.hasAlpha());
        assertArrayEquals(plain, RasterTesting.tight(picture));
        assertFalse(TiffDecoder.probe(MediaSource.of(withBroken)).hasAlpha());
        assertThrows(MediaException.Unsupported.class, () -> {
            try (var pages = TiffDecoder.openPages(MediaSource.of(withBroken))) {
                pages.read(1);
            }
        });
    }

    @Test
    @DisplayName("a reduced-resolution page read by itself is not masked by the mask of the page it reduces")
    void reducedPage() {
        var bits = bits(WIDTH, HEIGHT, 10);
        var levelValues = TiffFiles.pattern(5, 4, 3, 255, 11);
        var level = TiffFiles.of(5, 4, 3, 8).longTag(254, 1).data(TiffFiles.pack(levelValues, 5, 4, 3, 8, true));
        var file = TiffFiles.chain(rgb(6), level, mask(bits, WIDTH, HEIGHT));
        try (var pages = TiffDecoder.openPages(MediaSource.of(file))) {
            assertTrue(pages.probe(0).hasAlpha());
            assertFalse(pages.probe(1).hasAlpha());
            var reduced = (RgbaPicture) pages.read(1).picture();
            assertFalse(reduced.hasAlpha());
            var pixels = RasterTesting.tight(reduced);
            for (var i = 0; i < 20; i++) {
                assertEquals((byte) 0xFF, pixels[i * 4 + 3]);
            }
        }
    }

    @Test
    @DisplayName("a mask of many samples per pixel sums its coverage without overflow")
    void largeMask() {
        // 3200x3000 samples over one pixel: 9.6 million samples of 255 exceed a 32-bit sum
        var maskWidth = 3200;
        var maskHeight = 3000;
        var rowBytes = maskWidth / 8;
        var packed = new byte[rowBytes * maskHeight];
        for (var y = 0; y < maskHeight; y++) {
            Arrays.fill(packed, y * rowBytes + rowBytes / 2, (y + 1) * rowBytes, (byte) 0xFF);
        }
        var maskPage = TiffFiles.of(maskWidth, maskHeight, 1, 1).photometric(4).longTag(254, 4).data(packed);
        var page = TiffFiles.of(1, 1, 3, 8).data(new byte[]{10, 20, 30});
        var picture = (RgbaPicture) TiffDecoder.decode(MediaSource.of(TiffFiles.chain(page, maskPage))).picture();
        assertTrue(picture.hasAlpha());
        // half the samples are interior: round(255 * 0.5)
        assertEquals(128, picture.pixels()[3] & 0xFF);
        assertEquals(10, picture.pixels()[0]);
    }
}
