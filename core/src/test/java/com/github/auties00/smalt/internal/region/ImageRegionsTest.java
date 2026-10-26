package com.github.auties00.smalt.internal.region;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.testkit.image.PictureDump;
import com.github.auties00.smalt.testkit.image.RegionAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the region arithmetic of {@link ImageDecoderOptions#region()}: the mapping onto a reduced picture against a
 * brute-force coverage test, the hint scaling, the whole-image decode and crop, and the bounds check that the decoder
 * guards pass through.
 */
@DisplayName("Image regions")
class ImageRegionsTest {
    private static RgbaPicture picture(int width, int height, long seed) {
        var picture = RgbaPicture.allocate(width, height, true);
        new Random(seed).nextBytes(picture.pixels());
        return picture;
    }

    private static DecodedImage image(RgbaPicture picture, int sourceWidth, int sourceHeight) {
        return new DecodedImage(picture, ImageFormat.PNG, Orientation.ROTATE_90, sourceWidth, sourceHeight,
                Optional.of(new byte[]{1, 2}), Optional.empty(), Optional.empty());
    }

    @Test
    @DisplayName("the mapped rectangle holds exactly the reduced pixels whose area meets the region")
    void mapCoversExactly() {
        var random = new Random(1);
        for (var trial = 0; trial < 5000; trial++) {
            var width = 1 + random.nextInt(300);
            var height = 1 + random.nextInt(300);
            var pictureWidth = 1 + random.nextInt(width);
            var pictureHeight = 1 + random.nextInt(height);
            var x = random.nextInt(width);
            var y = random.nextInt(height);
            var region = new Region(x, y, 1 + random.nextInt(width - x), 1 + random.nextInt(height - y));
            var mapped = ImageRegions.map(region, width, height, pictureWidth, pictureHeight);
            assertEquals(RegionAsserts.map(region, width, height, pictureWidth, pictureHeight), mapped);
            assertTrue(mapped.fitsIn(pictureWidth, pictureHeight), mapped.toString());
            for (var i = 0; i < pictureWidth; i++) {
                // reduced pixel i spans [i * W / w', (i + 1) * W / w') of the image
                var meets = (long) i * width < ((long) x + region.width()) * pictureWidth
                        && ((long) i + 1) * width > (long) x * pictureWidth;
                var inside = i >= mapped.x() && i < mapped.x() + mapped.width();
                assertEquals(meets, inside, region + " -> " + mapped + " column " + i);
            }
        }
    }

    @Test
    @DisplayName("a full-size picture maps a region onto itself")
    void mapIdentity() {
        var region = new Region(3, 5, 7, 11);
        assertSame(region, ImageRegions.map(region, 40, 30, 40, 30));
    }

    @Test
    @DisplayName("the hint scales from the region to the image, rounding up")
    void scaledHint() {
        assertEquals(30, ImageRegions.scaledMinimum(10, 300, 100));
        assertEquals(31, ImageRegions.scaledMinimum(10, 301, 100));
        assertEquals(0, ImageRegions.scaledMinimum(0, 301, 100));
        assertEquals(Integer.MAX_VALUE, ImageRegions.scaledMinimum(Integer.MAX_VALUE, 1 << 20, 1));
        var options = ImageDecoderOptions.builder().minSize(10, 4).region(0, 0, 100, 50).build();
        var scaled = ImageRegions.scaledHint(options, options.region().orElseThrow(), 1000, 125);
        assertEquals(100, scaled.minWidth());
        assertEquals(10, scaled.minHeight());
        var plain = ImageDecoderOptions.defaults();
        assertSame(plain, ImageRegions.scaledHint(plain, new Region(0, 0, 1, 1), 10, 10));
    }

    @Test
    @DisplayName("a region outside the image is an argument error that the decoder guards pass through")
    void outside() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> ImageRegions.check(new Region(5, 0, 6, 1), 10, 10));
        assertInstanceOf(RegionOutsideImageException.class, failure);
        ImageRegions.check(new Region(5, 9, 5, 1), 10, 10);
        assertThrows(RegionOutsideImageException.class, () -> Guards.malformed("test", () -> {
            ImageRegions.check(new Region(0, 10, 1, 1), 10, 10);
            return null;
        }));
        assertThrows(MediaException.Malformed.class, () -> Guards.malformed("test", () -> {
            throw new IllegalArgumentException("a corrupt input");
        }));
    }

    @Test
    @DisplayName("the whole-image decode passes the options through without a region")
    void decodeWithoutRegion() {
        var image = image(picture(8, 6, 2), 8, 6);
        var options = ImageDecoderOptions.defaults();
        var result = ImageRegions.decode(options, () -> {
            throw new AssertionError("probed");
        }, given -> {
            assertSame(options, given);
            return image;
        });
        assertSame(image, result);
    }

    @Test
    @DisplayName("the whole-image decode crops the region and keeps the metadata")
    void decodeCrops() {
        var image = image(picture(20, 12, 3), 20, 12);
        var region = new Region(3, 2, 9, 7);
        var options = ImageDecoderOptions.builder().region(region).build();
        var result = ImageRegions.decode(options, () -> {
            throw new AssertionError("probed");
        }, given -> {
            assertEquals(Optional.empty(), given.region());
            return image;
        });
        assertEquals(PictureDump.picture(ImageOps.crop(image.picture(), region)),
                PictureDump.picture(result.picture()));
        assertEquals(PictureDump.image(image).substring(PictureDump.image(image).indexOf('\n')),
                PictureDump.image(result).substring(PictureDump.image(result).indexOf('\n')));
        assertThrows(IllegalArgumentException.class, () -> ImageRegions.decode(
                ImageDecoderOptions.builder().region(15, 0, 6, 1).build(), () -> null, given -> image));
    }

    @Test
    @DisplayName("the whole-image decode with a hint probes the size, scales the hint and maps the region")
    void decodeScalesHint() {
        var probes = new AtomicInteger();
        var hints = new ArrayList<Integer>();
        var reduced = image(picture(25, 15, 4), 100, 60);
        var region = new Region(10, 10, 50, 30);
        var options = ImageDecoderOptions.builder().minSize(10, 5).region(region).build();
        var result = ImageRegions.decode(options, () -> {
            probes.incrementAndGet();
            return new ImageInfo(ImageFormat.PNG, 100, 60, true, false, 1, Orientation.NORMAL);
        }, given -> {
            hints.add(given.minWidth());
            hints.add(given.minHeight());
            return reduced;
        });
        assertEquals(1, probes.get());
        assertEquals(java.util.List.of(20, 10), hints);
        var mapped = RegionAsserts.map(region, 100, 60, 25, 15);
        assertEquals(PictureDump.picture(ImageOps.crop(reduced.picture(), mapped)),
                PictureDump.picture(result.picture()));
        assertEquals(new Region(2, 2, 13, 8), mapped);
    }
}
