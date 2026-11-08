package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.icns.IcnsEncoder;
import com.github.auties00.smalt.image.icns.IcnsEncoderOptions;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.LosslessDepth;
import com.github.auties00.smalt.io.MediaSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects pages and writes them as one ICNS icon family when finished, one image per page.
 *
 * <p>Each page is prepared as {@link IcnsCodecProvider} prepares a single picture; a page whose image size another
 * page already has is rejected, since a family holds one image per size.
 */
final class IcnsPageWriter implements PageWriter {
    /**
     * The image sizes an icon family holds, in increasing order.
     */
    private static final int[] SIZES = {16, 18, 24, 32, 36, 48, 64, 128, 256, 512, 1024};

    private final MediaSink sink;

    private final ImageEncoderOptions options;

    private final List<RgbaPicture> images = new ArrayList<>();

    private boolean finished;

    /**
     * Creates a writer.
     *
     * @param sink the output
     * @param options the encoder options applied to every page
     */
    IcnsPageWriter(MediaSink sink, ImageEncoderOptions options) {
        this.sink = sink;
        this.options = options;
    }

    /**
     * Converts a picture to the square 8-bit sRGB image an icon family stores.
     *
     * @param picture the picture to store
     * @param options the encoder options: the color profile, the orientation and the lossless choice
     * @return the image: the picture itself when square and of a size an icon family holds, otherwise the picture
     * scaled into the smallest such square at least as large as its longer side, at most 1024 pixels
     * @throws IllegalArgumentException if the profile does not describe the picture, or a lossless request would
     * narrow a deeper picture
     */
    static RgbaPicture prepare(Picture picture, ImageEncoderOptions options) {
        var prepared = RasterEncoding.srgb(picture, options);
        if (options.isLossless()) {
            prepared = LosslessDepth.fit(prepared, 8, "icns");
        }
        var rgba = ImageOps.toRgba(prepared);
        var side = Math.max(rgba.width(), rgba.height());
        var size = SIZES[SIZES.length - 1];
        for (var candidate : SIZES) {
            if (candidate >= side) {
                size = candidate;
                break;
            }
        }
        if (rgba.width() == size && rgba.height() == size) {
            return rgba;
        }
        var filter = side > size ? ResizeFilter.AREA : ResizeFilter.BICUBIC;
        return (RgbaPicture) ImageOps.fitInto(rgba, size, size, filter, 0);
    }

    @Override
    public void write(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        add(picture, options);
    }

    @Override
    public void write(DecodedImage page) {
        Objects.requireNonNull(page, "page");
        add(page.picture(), options.toBuilder().metadataOf(page).build());
    }

    /**
     * Prepares and keeps one page.
     *
     * @param picture the page
     * @param metadata the options for it
     * @throws IllegalStateException if the writer is finished
     * @throws IllegalArgumentException if another page has the same image size
     */
    private void add(Picture picture, ImageEncoderOptions metadata) {
        if (finished) {
            throw new IllegalStateException("the ICNS file is finished");
        }
        var image = prepare(picture, metadata);
        for (var other : images) {
            if (other.width() == image.width()) {
                throw new IllegalArgumentException("the ICNS file already holds an image of " + image.width()
                        + " pixels");
            }
        }
        images.add(image);
    }

    @Override
    public int pageCount() {
        return images.size();
    }

    @Override
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the ICNS file is already finished");
        }
        if (images.isEmpty()) {
            throw new IllegalStateException("an ICNS file needs at least one image");
        }
        finished = true;
        IcnsEncoder.encode(images, IcnsEncoderOptions.defaults(), sink);
        images.clear();
    }

    @Override
    public void close() {
        images.clear();
    }
}
