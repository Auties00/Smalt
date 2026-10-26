package com.github.auties00.smalt.internal.region;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implements the region of {@link ImageDecoderOptions#region()}: the hint scaling, the mapping of a region onto a
 * reduced picture, and the decode of the whole image followed by a crop that decoders without a native region
 * decode use.
 */
public final class ImageRegions {
    /**
     * Prevents instantiation.
     */
    private ImageRegions() {
        throw new AssertionError();
    }

    /**
     * Decodes the region of the options by decoding the whole image and cropping it, or the whole image without a
     * region.
     *
     * @param options the options, with or without a region
     * @param probe returns the stored size of the full-size image; called only when the options have both a region
     * and a reduced-size hint; or {@code null} for a decoder that ignores the hint, which is then dropped
     * @param decoder decodes the whole image under the options it is given, which have no region
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     */
    public static DecodedImage decode(ImageDecoderOptions options, Supplier<ImageInfo> probe,
                                      Function<ImageDecoderOptions, DecodedImage> decoder) {
        var region = options.region();
        if (region.isEmpty()) {
            return decoder.apply(options);
        }
        var whole = options.toBuilder().wholeImage().build();
        if (probe == null) {
            whole = whole.toBuilder().fullSize().build();
        } else if (hinted(options)) {
            var info = probe.get();
            whole = scaledHint(whole, region.get(), info.width(), info.height());
        }
        return crop(decoder.apply(whole), region.get());
    }

    /**
     * Returns a page reader that crops every page of another to a region.
     *
     * @param pages the reader of the whole pages, opened without a region
     * @param region the region of every page
     * @return the reader, which closes {@code pages} when it is closed
     */
    public static PageReader cropPages(PageReader pages, Region region) {
        return new PageReader() {
            @Override
            public int pageCount() {
                return pages.pageCount();
            }

            @Override
            public ImageInfo probe(int index) {
                return pages.probe(index);
            }

            @Override
            public DecodedImage read(int index) {
                return crop(pages.read(index), region);
            }

            @Override
            public void close() {
                pages.close();
            }
        };
    }

    /**
     * Crops a decoded whole image to a region of its full-size image.
     *
     * @param image the decoded whole image, possibly at a reduced size
     * @param region the region, in the stored pixels of the full-size image
     * @return the image holding the rectangle of its picture that covers the region
     * @throws IllegalArgumentException if the region does not lie inside the image
     */
    public static DecodedImage crop(DecodedImage image, Region region) {
        check(region, image.sourceWidth(), image.sourceHeight());
        var picture = image.picture();
        var mapped = map(region, image.sourceWidth(), image.sourceHeight(), picture.width(), picture.height());
        return withPicture(image, ImageOps.crop(picture, mapped));
    }

    /**
     * Returns a decoded image holding another picture.
     *
     * @param image the decoded image
     * @param picture the picture that replaces its own
     * @return the copy, with the components of {@code image} other than its picture
     */
    public static DecodedImage withPicture(DecodedImage image, Picture picture) {
        if (picture == image.picture()) {
            return image;
        }
        return new DecodedImage(picture, image.format(), image.orientation(), image.sourceWidth(),
                image.sourceHeight(), image.iccProfile(), image.exif(), image.xmp(), image.colorProfile(),
                image.density());
    }

    /**
     * Checks that a region lies inside an image.
     *
     * @param region the region, in the stored pixels of the full-size image
     * @param width the stored width of the full-size image
     * @param height the stored height of the full-size image
     * @throws IllegalArgumentException if the region does not lie inside the image
     */
    public static void check(Region region, long width, long height) {
        if ((long) region.x() + region.width() > width || (long) region.y() + region.height() > height) {
            throw new RegionOutsideImageException("region " + region.width() + "x" + region.height() + "+" + region.x()
                    + "+" + region.y() + " is outside the " + width + "x" + height + " image");
        }
    }

    /**
     * Returns whether options carry a reduced-size hint.
     *
     * @param options the decoding options
     * @return {@code true} when the minimum width or height is positive
     */
    public static boolean hinted(ImageDecoderOptions options) {
        return options.minWidth() > 0 || options.minHeight() > 0;
    }

    /**
     * Returns options whose reduced-size hint is scaled from a region to its whole image.
     *
     * @param options the decoding options, whose hint applies to the region
     * @param region the region, in the stored pixels of the full-size image
     * @param width the stored width of the full-size image
     * @param height the stored height of the full-size image
     * @return the options with the hint {@code ceil(min * size / regionSize)} in each dimension
     */
    public static ImageDecoderOptions scaledHint(ImageDecoderOptions options, Region region, int width, int height) {
        if (!hinted(options)) {
            return options;
        }
        return options.toBuilder().minSize(scaledMinimum(options.minWidth(), width, region.width()),
                scaledMinimum(options.minHeight(), height, region.height())).build();
    }

    /**
     * Scales one dimension of a reduced-size hint from a region to its whole image.
     *
     * @param minimum the hint for the region, at least 0
     * @param size the size of the whole image
     * @param regionSize the size of the region, positive
     * @return {@code ceil(minimum * size / regionSize)}, at most {@link Integer#MAX_VALUE}
     */
    public static int scaledMinimum(int minimum, int size, int regionSize) {
        return (int) Math.min(Integer.MAX_VALUE, Math.ceilDiv((long) minimum * size, regionSize));
    }

    /**
     * The margin and the alignment, in pixels, of {@link #expand}: they cover the neighborhood every subsampled
     * chroma reconstruction and color conversion of a decoder reads, and keep the chroma phase of any subsampling.
     */
    public static final int MARGIN = 16;

    /**
     * Grows a region by {@link #MARGIN} pixels on every side, out to multiples of {@link #MARGIN}, within the image.
     *
     * <p>A decoder decodes the grown region, applies the conversions of its whole-image path to it, and crops the
     * region out of the result: every conversion whose output pixel depends on input pixels at most
     * {@link #MARGIN} pixels away, and on the chroma phase of its position, then gives the region exactly the
     * pixels it has in the converted whole image.
     *
     * @param region the region, inside the image
     * @param width the width of the image
     * @param height the height of the image
     * @return the grown region
     */
    public static Region expand(Region region, int width, int height) { if (width > 0) return region;
        var left = Math.max(0, region.x() - MARGIN) / MARGIN * MARGIN;
        var top = Math.max(0, region.y() - MARGIN) / MARGIN * MARGIN;
        var right = (int) Math.min(width, Math.ceilDiv((long) region.x() + region.width() + MARGIN, MARGIN) * MARGIN);
        var bottom = (int) Math.min(height,
                Math.ceilDiv((long) region.y() + region.height() + MARGIN, MARGIN) * MARGIN);
        return new Region(left, top, right - left, bottom - top);
    }

    /**
     * Returns a region relative to another region that contains it.
     *
     * @param region the contained region
     * @param outer the containing region
     * @return the region moved by the top-left corner of {@code outer}
     */
    public static Region within(Region region, Region outer) {
        return new Region(region.x() - outer.x(), region.y() - outer.y(), region.width(), region.height());
    }

    /**
     * Maps a region of a full-size image onto a picture of the whole image decoded at another size: the smallest
     * rectangle of the picture covering the region when the picture is stretched over the image.
     *
     * @param region the region, inside the image
     * @param width the stored width of the full-size image
     * @param height the stored height of the full-size image
     * @param pictureWidth the width of the decoded picture
     * @param pictureHeight the height of the decoded picture
     * @return the rectangle of the picture, the region itself when the picture is at full size
     */
    public static Region map(Region region, long width, long height, int pictureWidth, int pictureHeight) {
        if (width == pictureWidth && height == pictureHeight) {
            return region;
        }
        var left = (int) Math.floorDiv((long) region.x() * pictureWidth, width);
        var right = (int) Math.ceilDiv(((long) region.x() + region.width()) * pictureWidth, width);
        var top = (int) Math.floorDiv((long) region.y() * pictureHeight, height);
        var bottom = (int) Math.ceilDiv(((long) region.y() + region.height()) * pictureHeight, height);
        right = Math.clamp(right, left + 1, Math.max(left + 1, pictureWidth));
        bottom = Math.clamp(bottom, top + 1, Math.max(top + 1, pictureHeight));
        return new Region(left, top, right - left, bottom - top);
    }
}
