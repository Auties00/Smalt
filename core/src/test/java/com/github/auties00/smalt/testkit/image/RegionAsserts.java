package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the region contract of {@link ImageDecoderOptions#region()}: a region decode equals, sample for sample, the
 * crop of the whole decode under the same options, with the reduced-size hint scaled from the region to the image and
 * the region mapped onto the reduced picture as the contract states, and the metadata of the whole decode. The
 * expected rectangle is computed here, independently of the decoders.
 */
public final class RegionAsserts {
    private RegionAsserts() {
        throw new AssertionError();
    }

    /**
     * Returns the regions worth decoding of an image: the whole image, its corners, its last row and column, single
     * rows and columns, small and odd-sized rectangles at odd offsets, and random rectangles.
     *
     * @param width the stored width of the image
     * @param height the stored height of the image
     * @param random the number of random rectangles
     * @param seed the seed of the random rectangles
     * @return the regions, without duplicates
     */
    public static List<Region> regions(int width, int height, int random, long seed) {
        var regions = new LinkedHashSet<Region>();
        regions.add(new Region(0, 0, width, height));
        regions.add(new Region(0, 0, 1, 1));
        regions.add(new Region(width - 1, height - 1, 1, 1));
        regions.add(new Region(width - 1, 0, 1, height));
        regions.add(new Region(0, height - 1, width, 1));
        regions.add(new Region(width / 2, 0, width - width / 2, height));
        regions.add(new Region(0, height / 3, width, Math.max(1, height / 3)));
        if (width > 3 && height > 3) {
            regions.add(new Region(1, 1, width - 2, height - 2));
            regions.add(new Region(width / 3 | 1, height / 3 | 1, Math.max(1, Math.min(width - (width / 3 | 1), 7)),
                    Math.max(1, Math.min(height - (height / 3 | 1), 5))));
        }
        var generator = new Random(seed);
        for (var i = 0; i < random; i++) {
            var x = generator.nextInt(width);
            var y = generator.nextInt(height);
            var w = 1 + generator.nextInt(width - x);
            var h = 1 + generator.nextInt(height - y);
            regions.add(new Region(x, y, w, h));
        }
        return new ArrayList<>(regions);
    }

    /**
     * Asserts the region contract for every region of a list.
     *
     * @param name the input, for messages
     * @param decoder decodes the input under the options it is given
     * @param options the options without a region, possibly with a reduced-size hint
     * @param regions the regions, in the stored pixels of the full-size image
     */
    public static void assertRegions(String name, Function<ImageDecoderOptions, DecodedImage> decoder,
                                     ImageDecoderOptions options, List<Region> regions) {
        var hinted = options.minWidth() > 0 || options.minHeight() > 0;
        var whole = hinted ? null : decoder.apply(options);
        for (var region : regions) {
            assertRegion(name, decoder, options, region, whole);
        }
    }

    /**
     * Asserts the region contract for one region.
     *
     * @param name the input, for messages
     * @param decoder decodes the input under the options it is given
     * @param options the options without a region, possibly with a reduced-size hint
     * @param region the region, in the stored pixels of the full-size image
     * @param whole the whole decode under {@code options}, or {@code null} to decode it here (required with a hint)
     */
    public static void assertRegion(String name, Function<ImageDecoderOptions, DecodedImage> decoder,
                                    ImageDecoderOptions options, Region region, DecodedImage whole) {
        var what = name + " region " + region.width() + "x" + region.height() + "+" + region.x() + "+" + region.y();
        DecodedImage reference;
        if (options.minWidth() > 0 || options.minHeight() > 0) {
            var probe = whole != null ? whole : decoder.apply(options.toBuilder().fullSize().build());
            var hintWidth = (int) Math.min(Integer.MAX_VALUE,
                    Math.ceilDiv((long) options.minWidth() * probe.sourceWidth(), region.width()));
            var hintHeight = (int) Math.min(Integer.MAX_VALUE,
                    Math.ceilDiv((long) options.minHeight() * probe.sourceHeight(), region.height()));
            reference = decoder.apply(options.toBuilder().minSize(hintWidth, hintHeight).build());
        } else {
            reference = whole != null ? whole : decoder.apply(options);
        }
        var picture = reference.picture();
        var mapped = map(region, reference.sourceWidth(), reference.sourceHeight(), picture.width(),
                picture.height());
        var expected = ImageOps.crop(picture, mapped);
        var actual = decoder.apply(options.toBuilder().region(region).build());
        assertEquals(PictureDump.picture(expected), PictureDump.picture(actual.picture()), what);
        assertEquals(metadata(reference), metadata(actual), what + " metadata");
    }

    /**
     * Returns damaged variants of a file, by name: truncations at 30, 55, 80 and 97 percent of its length and one
     * byte short of it, and runs of 24 random bytes written at 45 and 75 percent of its length.
     *
     * @param data the intact file
     * @return the damaged files
     */
    public static Map<String, byte[]> damaged(byte[] data) {
        var variants = new LinkedHashMap<String, byte[]>();
        for (var percent : List.of(30, 55, 80, 97)) {
            variants.put("truncated at " + percent + "%",
                    Arrays.copyOf(data, (int) ((long) data.length * percent / 100)));
        }
        variants.put("missing its last byte", Arrays.copyOf(data, data.length - 1));
        for (var percent : List.of(45, 75)) {
            var copy = data.clone();
            var random = new Random(percent);
            var from = (int) ((long) data.length * percent / 100);
            for (var i = from; i < Math.min(copy.length, from + 24); i++) {
                copy[i] = (byte) random.nextInt(256);
            }
            variants.put("corrupted at " + percent + "%", copy);
        }
        return variants;
    }

    /**
     * Returns regions worth decoding of a damaged image: a pixel and a band at the top, a rectangle in the middle, a
     * rectangle at the bottom-right corner and the last row.
     *
     * @param width the stored width of the intact image, at least 9
     * @param height the stored height of the intact image, at least 7
     * @return the regions
     */
    public static List<Region> damageRegions(int width, int height) {
        return List.of(new Region(0, 0, 1, 1), new Region(0, 0, width, Math.max(1, height / 5)),
                new Region(width / 3, height / 3, Math.max(1, width / 4), Math.max(1, height / 4)),
                new Region(width - 9, height - 7, 9, 7), new Region(0, height - 1, width, 1));
    }

    /**
     * Asserts the region contract on a damaged file: when the file still decodes whole, every region equals the crop
     * of that decode; when it does not, a region decode either fails with a {@link MediaException} or, when the
     * damage lies in data the region does not need, returns a picture of the region's size.
     *
     * @param name the input, for messages
     * @param decoder decodes the damaged file under the options it is given
     * @param regions the regions, inside the intact image
     */
    public static void assertDamaged(String name, Function<ImageDecoderOptions, DecodedImage> decoder,
                                     List<Region> regions) {
        DecodedImage whole;
        try {
            whole = decoder.apply(ImageDecoderOptions.defaults());
        } catch (MediaException failure) {
            whole = null;
        }
        for (var region : regions) {
            if (whole != null) {
                assertRegion(name, decoder, ImageDecoderOptions.defaults(), region, whole);
                continue;
            }
            DecodedImage decoded;
            try {
                decoded = decoder.apply(ImageDecoderOptions.builder().region(region).build());
            } catch (MediaException failure) {
                continue;
            }
            assertEquals(region.width() + "x" + region.height(), decoded.picture().width() + "x"
                    + decoded.picture().height(), name + " " + region);
        }
    }

    /**
     * Runs the I/O behavior harness ({@link ImageDecoderAsserts}) over the decode of a region of an image: the
     * middle of its image, half its width and height. The pictures are compared between the source behaviors,
     * without a golden dump; the pages of a multi-page image, which may be smaller than the region, are not read.
     *
     * @param name the input, for messages
     * @param data the image file
     */
    public static void behaviors(String name, byte[] data) {
        var info = Images.probe(MediaSource.of(data));
        var region = new Region(info.width() / 4, info.height() / 3, Math.max(1, info.width() / 2),
                Math.max(1, info.height() / 2));
        ImageDecoderAsserts.of(name, data).options(ImageDecoderOptions.builder().region(region).build()).noScaled()
                .noPages().noDump().run();
    }

    /**
     * Asserts that decoding a region that leaves the image raises {@link IllegalArgumentException}, at its right
     * edge and at its bottom edge.
     *
     * @param decoder decodes the input under the options it is given
     * @param options the options without a region
     * @param width the stored width of the image
     * @param height the stored height of the image
     */
    public static void assertOutsideRejected(Function<ImageDecoderOptions, DecodedImage> decoder,
                                             ImageDecoderOptions options, int width, int height) {
        assertThrows(IllegalArgumentException.class,
                () -> decoder.apply(options.toBuilder().region(width - 1, 0, 2, 1).build()));
        assertThrows(IllegalArgumentException.class,
                () -> decoder.apply(options.toBuilder().region(0, height, 1, 1).build()));
    }

    /**
     * Maps a region onto a picture of the whole image decoded at another size, as
     * {@link ImageDecoderOptions#region()} states.
     *
     * @param region the region
     * @param width the stored width of the full-size image
     * @param height the stored height of the full-size image
     * @param pictureWidth the width of the decoded picture
     * @param pictureHeight the height of the decoded picture
     * @return the rectangle of the picture
     */
    public static Region map(Region region, int width, int height, int pictureWidth, int pictureHeight) {
        var left = Math.floorDiv((long) region.x() * pictureWidth, width);
        var right = Math.ceilDiv(((long) region.x() + region.width()) * pictureWidth, width);
        var top = Math.floorDiv((long) region.y() * pictureHeight, height);
        var bottom = Math.ceilDiv(((long) region.y() + region.height()) * pictureHeight, height);
        return new Region((int) left, (int) top, (int) (right - left), (int) (bottom - top));
    }

    private static String metadata(DecodedImage image) {
        var dump = PictureDump.image(image);
        return dump.substring(dump.indexOf('\n') + 1);
    }
}
