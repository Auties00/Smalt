package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.awt.Rectangle;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.RenderedImage;
import java.awt.image.WritableRaster;
import java.util.Objects;

/**
 * Converts between Smalt pictures and {@link BufferedImage}s.
 *
 * <p>Pictures convert to the 8-bit {@link BufferedImage} type that holds them:
 * {@link BufferedImage#TYPE_INT_ARGB} for a picture with alpha, {@link BufferedImage#TYPE_INT_RGB}
 * for an opaque one, and {@link BufferedImage#TYPE_BYTE_GRAY} for a monochrome {@link YuvPicture} or
 * {@link Yuv16Picture}. A {@link YuvPicture} is converted to RGB with {@link ImageOps#toRgba(Picture)},
 * the conversion every Smalt encoder and operation uses. Alpha is never premultiplied. The conversion
 * is lossless for 8-bit pictures; a 16-bit picture is narrowed to 8 bits per sample.
 *
 * <p>{@link BufferedImage}s convert to an {@link RgbaPicture}, or with
 * {@link #toRgba16Picture(BufferedImage)} to an {@link Rgba16Picture} that keeps the samples of a 16-bit
 * image. The three types above and {@link BufferedImage#TYPE_4BYTE_ABGR} and
 * {@link BufferedImage#TYPE_3BYTE_BGR} are copied sample for sample; every other type, including
 * premultiplied and custom ones, goes through {@link BufferedImage#getRGB(int, int, int, int, int[], int, int)},
 * which converts to non-premultiplied sRGB. Gray samples are copied as stored, as the JDK's own ImageIO plug-ins
 * store them, rather than converted from the linear gray color space of
 * {@link BufferedImage#TYPE_BYTE_GRAY}.
 *
 * <p>This class is thread-safe.
 */
public final class BufferedImages {
    /**
     * Prevents instantiation.
     */
    private BufferedImages() {
        throw new AssertionError();
    }

    /**
     * Returns the 8-bit {@link BufferedImage} type that holds a picture, without loss when the
     * picture has 8 bits per sample.
     *
     * @param picture the picture to describe
     * @return {@link BufferedImage#TYPE_BYTE_GRAY} for a monochrome picture without alpha,
     * {@link BufferedImage#TYPE_INT_ARGB} for a picture with alpha, and
     * {@link BufferedImage#TYPE_INT_RGB} otherwise
     */
    public static int imageTypeOf(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        if (picture.hasAlpha()) {
            return BufferedImage.TYPE_INT_ARGB;
        }
        return isMonochrome(picture) ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_INT_RGB;
    }

    /**
     * Converts a picture to a new {@link BufferedImage} of the type {@link #imageTypeOf(Picture)}
     * chooses.
     *
     * @param picture the picture to convert
     * @return a new image with the picture's pixels
     */
    public static BufferedImage toBufferedImage(Picture picture) {
        return toBufferedImage(picture, imageTypeOf(picture));
    }

    /**
     * Converts a picture to a new {@link BufferedImage} of a given type.
     *
     * <p>Converting a picture with alpha to an opaque type drops the alpha; converting a color
     * picture to {@link BufferedImage#TYPE_BYTE_GRAY} or another type with a different color space
     * uses the JDK's color conversion.
     *
     * @param picture the picture to convert
     * @param imageType a {@link BufferedImage} type constant other than
     * {@link BufferedImage#TYPE_CUSTOM}
     * @return a new image with the picture's pixels
     * @throws IllegalArgumentException if {@code imageType} is not a predefined type
     */
    public static BufferedImage toBufferedImage(Picture picture, int imageType) {
        Objects.requireNonNull(picture, "picture");
        if (imageType == BufferedImage.TYPE_CUSTOM) {
            throw new IllegalArgumentException("a custom image type needs a color model");
        }
        var image = new BufferedImage(picture.width(), picture.height(), imageType);
        var region = new Rectangle(0, 0, picture.width(), picture.height());
        copy(picture, region, 1, 1, image, 0, 0);
        return image;
    }

    /**
     * Converts a {@link BufferedImage} to a new {@link RgbaPicture}.
     *
     * @param image the image to convert
     * @return a new picture, with alpha when the image's color model has alpha
     */
    public static RgbaPicture toPicture(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        return toPicture(image, new Rectangle(0, 0, image.getWidth(), image.getHeight()), 1, 1);
    }

    /**
     * Converts a {@link BufferedImage} to a new {@link Rgba16Picture}, keeping the precision of a
     * 16-bit image.
     *
     * <p>An image with 16-bit, non-premultiplied components in the sRGB or a gray color space (for
     * example {@link BufferedImage#TYPE_USHORT_GRAY}, or a 16-bit PNG as the JDK's reader returns it)
     * is copied sample for sample, gray samples as stored. Every other image converts as
     * {@link #toPicture(BufferedImage)} does, each 8-bit sample {@code v} widened to {@code v * 257}, so
     * 0 and 255 map to 0 and 65535.
     *
     * @param image the image to convert
     * @return a new picture, with alpha when the image's color model has alpha
     */
    public static Rgba16Picture toRgba16Picture(BufferedImage image) {
        Objects.requireNonNull(image, "image");
        var width = image.getWidth();
        var height = image.getHeight();
        var model = image.getColorModel();
        var hasAlpha = model.hasAlpha();
        var picture = Rgba16Picture.allocate(width, height, hasAlpha);
        var pixels = picture.pixels();
        var stride = picture.stride();
        if (!isSixteenBitComponent(image)) {
            var narrow = toPicture(image);
            var bytes = narrow.pixels();
            var narrowStride = narrow.stride();
            for (var y = 0; y < height; y++) {
                var from = y * narrowStride;
                var to = y * stride;
                for (var i = 0; i < width * 4; i++) {
                    pixels[to + i] = (short) ((bytes[from + i] & 0xFF) * 257);
                }
            }
            return picture;
        }
        var raster = image.getRaster();
        var bands = raster.getNumBands();
        var gray = model.getColorSpace().getType() == ColorSpace.TYPE_GRAY;
        var samples = new int[width * bands];
        for (var y = 0; y < height; y++) {
            raster.getPixels(raster.getMinX(), raster.getMinY() + y, width, 1, samples);
            var row = y * stride;
            for (var x = 0; x < width; x++) {
                var from = x * bands;
                var at = row + x * 4;
                if (gray) {
                    var value = (short) samples[from];
                    pixels[at] = value;
                    pixels[at + 1] = value;
                    pixels[at + 2] = value;
                    pixels[at + 3] = hasAlpha ? (short) samples[from + 1] : (short) 0xFFFF;
                } else {
                    pixels[at] = (short) samples[from];
                    pixels[at + 1] = (short) samples[from + 1];
                    pixels[at + 2] = (short) samples[from + 2];
                    pixels[at + 3] = hasAlpha ? (short) samples[from + 3] : (short) 0xFFFF;
                }
            }
        }
        return picture;
    }

    /**
     * Returns whether an image stores straight 16-bit components in the sRGB or a gray color space,
     * with one band per component.
     *
     * @param image the image to inspect
     * @return {@code true} when {@link #toRgba16Picture(BufferedImage)} can copy its samples
     */
    static boolean isSixteenBitComponent(BufferedImage image) {
        var model = image.getColorModel();
        if (!(model instanceof ComponentColorModel) || model.isAlphaPremultiplied()
                || model.getTransferType() != DataBuffer.TYPE_USHORT) {
            return false;
        }
        for (var size : model.getComponentSize()) {
            if (size != 16) {
                return false;
            }
        }
        var space = model.getColorSpace();
        var components = model.getNumComponents();
        var colorComponents = model.getNumColorComponents();
        var supported = space.isCS_sRGB() && colorComponents == 3
                || space.getType() == ColorSpace.TYPE_GRAY && colorComponents == 1;
        return supported && image.getRaster().getNumBands() == components;
    }

    /**
     * Converts a {@link RenderedImage} to a {@link BufferedImage}, returning a
     * {@link BufferedImage} unchanged.
     *
     * @param image the image to convert
     * @return {@code image} itself, or a copy of its pixels with its color model
     */
    static BufferedImage toBufferedImage(RenderedImage image) {
        Objects.requireNonNull(image, "image");
        if (image instanceof BufferedImage buffered) {
            return buffered;
        }
        ColorModel model = image.getColorModel();
        WritableRaster raster = model.createCompatibleWritableRaster(image.getWidth(), image.getHeight());
        image.copyData(raster.createWritableTranslatedChild(image.getMinX(), image.getMinY()));
        return new BufferedImage(model, raster, model.isAlphaPremultiplied(), null);
    }

    /**
     * Samples a region of a {@link BufferedImage} into a new {@link RgbaPicture}.
     *
     * <p>Output pixel {@code (x, y)} is the image pixel {@code (region.x + x * xStep, region.y + y *
     * yStep)}, so the picture is {@code ceil(region.width / xStep)} by {@code ceil(region.height /
     * yStep)} pixels.
     *
     * @param image the image to read
     * @param region the region to read, inside the image
     * @param xStep the horizontal sampling step; positive
     * @param yStep the vertical sampling step; positive
     * @return a new picture
     */
    static RgbaPicture toPicture(BufferedImage image, Rectangle region, int xStep, int yStep) {
        var width = (region.width + xStep - 1) / xStep;
        var height = (region.height + yStep - 1) / yStep;
        var hasAlpha = image.getColorModel().hasAlpha();
        var picture = RgbaPicture.allocate(width, height, hasAlpha);
        var pixels = picture.pixels();
        var stride = picture.stride();
        var raster = image.getRaster();
        var type = image.getType();
        var rowWidth = region.width;
        int[] ints = null;
        byte[] bytes = null;
        for (var y = 0; y < height; y++) {
            var sourceY = region.y + y * yStep;
            var row = y * stride;
            switch (type) {
                case BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB -> {
                    ints = (int[]) raster.getDataElements(region.x, sourceY, rowWidth, 1, ints);
                    var opaque = type == BufferedImage.TYPE_INT_RGB;
                    for (var x = 0; x < width; x++) {
                        var argb = ints[x * xStep];
                        var at = row + x * 4;
                        pixels[at] = (byte) (argb >>> 16);
                        pixels[at + 1] = (byte) (argb >>> 8);
                        pixels[at + 2] = (byte) argb;
                        pixels[at + 3] = opaque ? (byte) 0xFF : (byte) (argb >>> 24);
                    }
                }
                case BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_BYTE_GRAY -> {
                    var bands = type == BufferedImage.TYPE_4BYTE_ABGR ? 4 : type == BufferedImage.TYPE_3BYTE_BGR ? 3 : 1;
                    bytes = (byte[]) raster.getDataElements(region.x, sourceY, rowWidth, 1, bytes);
                    for (var x = 0; x < width; x++) {
                        var from = x * xStep * bands;
                        var at = row + x * 4;
                        if (bands == 1) {
                            pixels[at] = bytes[from];
                            pixels[at + 1] = bytes[from];
                            pixels[at + 2] = bytes[from];
                            pixels[at + 3] = (byte) 0xFF;
                        } else {
                            pixels[at] = bytes[from];
                            pixels[at + 1] = bytes[from + 1];
                            pixels[at + 2] = bytes[from + 2];
                            pixels[at + 3] = bands == 4 ? bytes[from + 3] : (byte) 0xFF;
                        }
                    }
                }
                default -> {
                    if (ints == null) {
                        ints = new int[rowWidth];
                    }
                    image.getRGB(region.x, sourceY, rowWidth, 1, ints, 0, rowWidth);
                    for (var x = 0; x < width; x++) {
                        var argb = ints[x * xStep];
                        var at = row + x * 4;
                        pixels[at] = (byte) (argb >>> 16);
                        pixels[at + 1] = (byte) (argb >>> 8);
                        pixels[at + 2] = (byte) argb;
                        pixels[at + 3] = hasAlpha ? (byte) (argb >>> 24) : (byte) 0xFF;
                    }
                }
            }
        }
        return picture;
    }

    /**
     * Samples a region of a picture into a {@link BufferedImage}.
     *
     * <p>The region is given in the coordinates of a picture of {@code fullWidth} by
     * {@code fullHeight} pixels, which is the decoded picture itself or the full-size picture that
     * a reduced-size decode stands for: destination pixel {@code (destX + x, destY + y)} takes the
     * picture pixel nearest to {@code (region.x + x * xStep, region.y + y * yStep)} scaled by the
     * ratio of the picture's size to the full size. Destination pixels outside the image are
     * skipped.
     *
     * @param picture the decoded picture
     * @param fullWidth the width the region coordinates refer to
     * @param fullHeight the height the region coordinates refer to
     * @param region the region to read, in full-size coordinates
     * @param xStep the horizontal sampling step; positive
     * @param yStep the vertical sampling step; positive
     * @param destination the image to write
     * @param destX the destination column of the first sample
     * @param destY the destination row of the first sample
     * @param progress receives each completed destination row index, or {@code null}; returns
     * {@code false} to stop early
     */
    static void copy(Picture picture, int fullWidth, int fullHeight, Rectangle region, int xStep, int yStep,
                     BufferedImage destination, int destX, int destY, RowListener progress) {
        if (isSixteenBitComponent(destination)) {
            var model = destination.getColorModel();
            var grayDestination = model.getNumColorComponents() == 1;
            if (!grayDestination || isGray(picture)) {
                var channels = channelsOf(grayDestination, model.hasAlpha());
                var bands = new int[channels.length];
                for (var band = 0; band < bands.length; band++) {
                    bands[band] = band;
                }
                copyBands(picture, fullWidth, fullHeight, region, xStep, yStep, channels, 16, bands,
                        destination.getRaster(), destX, destY, progress);
                return;
            }
        }
        var rgba = toEightBitRgba(picture);
        var pixels = rgba.pixels();
        var stride = rgba.stride();
        var pictureWidth = rgba.width();
        var pictureHeight = rgba.height();
        var width = Math.min((region.width + xStep - 1) / xStep, destination.getWidth() - destX);
        var height = Math.min((region.height + yStep - 1) / yStep, destination.getHeight() - destY);
        if (width <= 0 || height <= 0) {
            return;
        }
        var columns = new int[width];
        for (var x = 0; x < width; x++) {
            var full = (long) region.x + (long) x * xStep;
            columns[x] = (int) Math.min(pictureWidth - 1, full * pictureWidth / fullWidth) * 4;
        }
        var raster = destination.getRaster();
        var type = destination.getType();
        var monochrome = isMonochrome(picture) || picture instanceof Rgba16Picture && isGray(picture);
        int[] ints = null;
        byte[] bytes = null;
        for (var y = 0; y < height; y++) {
            var full = (long) region.y + (long) y * yStep;
            var row = (int) Math.min(pictureHeight - 1, full * pictureHeight / fullHeight) * stride;
            switch (type) {
                case BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB -> {
                    if (ints == null) {
                        ints = new int[width];
                    }
                    packArgb(pixels, row, columns, ints);
                    raster.setDataElements(destX, destY + y, width, 1, ints);
                }
                case BufferedImage.TYPE_4BYTE_ABGR, BufferedImage.TYPE_3BYTE_BGR -> {
                    var bands = type == BufferedImage.TYPE_4BYTE_ABGR ? 4 : 3;
                    if (bytes == null) {
                        bytes = new byte[width * bands];
                    }
                    for (var x = 0; x < width; x++) {
                        var from = row + columns[x];
                        var at = x * bands;
                        bytes[at] = pixels[from];
                        bytes[at + 1] = pixels[from + 1];
                        bytes[at + 2] = pixels[from + 2];
                        if (bands == 4) {
                            bytes[at + 3] = pixels[from + 3];
                        }
                    }
                    raster.setDataElements(destX, destY + y, width, 1, bytes);
                }
                default -> {
                    if (type == BufferedImage.TYPE_BYTE_GRAY && monochrome) {
                        if (bytes == null) {
                            bytes = new byte[width];
                        }
                        for (var x = 0; x < width; x++) {
                            bytes[x] = pixels[row + columns[x]];
                        }
                        raster.setDataElements(destX, destY + y, width, 1, bytes);
                    } else {
                        if (ints == null) {
                            ints = new int[width];
                        }
                        packArgb(pixels, row, columns, ints);
                        destination.setRGB(destX, destY + y, width, 1, ints, 0, width);
                    }
                }
            }
            if (progress != null && !progress.rowDone(y, height)) {
                return;
            }
        }
    }

    /**
     * Samples selected channels of a region of a picture into bands of a raster.
     *
     * <p>The positions are those of {@link #copy(Picture, int, int, Rectangle, int, int, BufferedImage, int, int,
     * RowListener)}. Source band {@code b} of the image is the RGBA channel {@code channels[b]} of the
     * picture at {@code depth} bits (gray is the red channel of a gray picture); target band {@code k}
     * receives source band {@code sourceBands[k]}, rescaled as {@code round(v * (2^t - 1) / (2^depth - 1))}
     * when the target band holds {@code t} bits. Target positions outside the raster are skipped.
     *
     * @param picture the decoded picture
     * @param fullWidth the width the region coordinates refer to
     * @param fullHeight the height the region coordinates refer to
     * @param region the region to read, in full-size coordinates
     * @param xStep the horizontal sampling step; positive
     * @param yStep the vertical sampling step; positive
     * @param channels the RGBA channel of each source band
     * @param depth the source sample depth, 8 or 16
     * @param sourceBands the source band of each target band
     * @param target the raster to write, with one band per entry of {@code sourceBands}
     * @param destX the target column of the first sample
     * @param destY the target row of the first sample
     * @param progress receives each completed row index, or {@code null}; returns {@code false} to stop
     */
    static void copyBands(Picture picture, int fullWidth, int fullHeight, Rectangle region, int xStep, int yStep,
                          int[] channels, int depth, int[] sourceBands, WritableRaster target, int destX, int destY,
                          RowListener progress) {
        short[] deep = null;
        byte[] narrow = null;
        int stride;
        int pictureWidth;
        int pictureHeight;
        if (depth == 16) {
            var wide = ImageOps.toRgba16(picture);
            deep = wide.pixels();
            stride = wide.stride();
            pictureWidth = wide.width();
            pictureHeight = wide.height();
        } else {
            var rgba = toEightBitRgba(picture);
            narrow = rgba.pixels();
            stride = rgba.stride();
            pictureWidth = rgba.width();
            pictureHeight = rgba.height();
        }
        var width = Math.min((region.width + xStep - 1) / xStep, target.getMinX() + target.getWidth() - destX);
        var height = Math.min((region.height + yStep - 1) / yStep, target.getMinY() + target.getHeight() - destY);
        if (width <= 0 || height <= 0) {
            return;
        }
        var bands = sourceBands.length;
        var offsets = new int[bands];
        var sizes = new int[bands];
        var sampleModel = target.getSampleModel();
        for (var k = 0; k < bands; k++) {
            offsets[k] = channels[sourceBands[k]];
            sizes[k] = sampleModel.getSampleSize(k);
        }
        var columns = new int[width];
        for (var x = 0; x < width; x++) {
            var full = (long) region.x + (long) x * xStep;
            columns[x] = (int) Math.min(pictureWidth - 1, full * pictureWidth / fullWidth) * 4;
        }
        var maximum = (1L << depth) - 1;
        var samples = new int[width * bands];
        for (var y = 0; y < height; y++) {
            var full = (long) region.y + (long) y * yStep;
            var row = (int) Math.min(pictureHeight - 1, full * pictureHeight / fullHeight) * stride;
            for (var x = 0; x < width; x++) {
                var from = row + columns[x];
                for (var k = 0; k < bands; k++) {
                    var value = deep != null ? deep[from + offsets[k]] & 0xFFFF : narrow[from + offsets[k]] & 0xFF;
                    var size = sizes[k];
                    if (size != depth) {
                        var top = size >= 32 ? 0xFFFFFFFFL : (1L << size) - 1;
                        value = (int) ((value * top + maximum / 2) / maximum);
                    }
                    samples[x * bands + k] = value;
                }
            }
            target.setPixels(destX, destY + y, width, 1, samples);
            if (progress != null && !progress.rowDone(y, height)) {
                return;
            }
        }
    }

    /**
     * Returns the RGBA channel of each band of an image of a color type, as
     * {@link #copyBands(Picture, int, int, Rectangle, int, int, int[], int, int[], WritableRaster, int, int,
     * RowListener)} takes them.
     *
     * @param gray whether the image has one color band
     * @param alpha whether it has an alpha band after its color bands
     * @return the channel of each band: {@code 0} for gray or red, {@code 1} green, {@code 2} blue,
     * {@code 3} alpha
     */
    static int[] channelsOf(boolean gray, boolean alpha) {
        if (gray) {
            return alpha ? new int[]{0, 3} : new int[]{0};
        }
        return alpha ? new int[]{0, 1, 2, 3} : new int[]{0, 1, 2};
    }

    /**
     * Returns whether a picture holds more than 8 bits per sample.
     *
     * @param picture the picture to inspect
     * @return {@code true} for an {@link Rgba16Picture}, a {@link Yuv16Picture} deeper than 8 bits, and a
     * 16-bit or floating-point channel picture
     */
    static boolean isDeep(Picture picture) {
        return switch (picture) {
            case Rgba16Picture _ -> true;
            case Yuv16Picture yuv -> yuv.bitDepth() > 8;
            case Channel16Picture _, ChannelFloatPicture _ -> true;
            default -> false;
        };
    }

    /**
     * Returns whether a picture is gray: a picture without chroma planes, or a deep picture whose three
     * color samples are equal in every pixel.
     *
     * @param picture the picture to inspect
     * @return {@code true} when the picture is gray
     */
    static boolean isGray(Picture picture) {
        if (isMonochrome(picture)) {
            return true;
        }
        if (!isDeep(picture)) {
            return false;
        }
        var wide = ImageOps.toRgba16(picture);
        var pixels = wide.pixels();
        var stride = wide.stride();
        for (var y = 0; y < wide.height(); y++) {
            var end = y * stride + wide.width() * 4;
            for (var at = y * stride; at < end; at += 4) {
                if (pixels[at] != pixels[at + 1] || pixels[at] != pixels[at + 2]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns the 8-bit RGBA form of a picture, reduced to 8 bits per sample before any color conversion so
     * that it equals the decoders' default 8-bit output.
     *
     * @param picture the picture to convert
     * @return {@code picture} itself when it is an {@link RgbaPicture}, otherwise a new picture
     */
    static RgbaPicture toEightBitRgba(Picture picture) {
        return picture instanceof RgbaPicture packed ? packed : ImageOps.toRgba(ImageOps.toEightBit(picture));
    }

    /**
     * Converts the samples of a raster of one to four bands (gray, gray and alpha, RGB, RGBA) to a new
     * picture, sampling a region.
     *
     * <p>Integer samples of {@code s} bits are rescaled to the picture's depth as
     * {@code round(v * (2^d - 1) / (2^s - 1))}; float and double samples are read as the range 0 to 1. The
     * picture is an {@link RgbaPicture} when every band holds at most 8 bits, and an {@link Rgba16Picture}
     * otherwise.
     *
     * @param raster the samples
     * @param region the region to read, in the raster's coordinates
     * @param xStep the horizontal sampling step; positive
     * @param yStep the vertical sampling step; positive
     * @return a new picture
     * @throws IllegalArgumentException if the raster has no band or more than four, or holds signed 16-bit
     * samples
     */
    static Picture toPicture(Raster raster, Rectangle region, int xStep, int yStep) {
        var bands = raster.getNumBands();
        if (bands < 1 || bands > 4) {
            throw new IllegalArgumentException("a raster of " + bands + " bands is not gray, gray and alpha, RGB or RGBA");
        }
        var dataType = raster.getTransferType();
        if (dataType == DataBuffer.TYPE_SHORT) {
            throw new IllegalArgumentException("signed 16-bit samples are not supported");
        }
        var floating = dataType == DataBuffer.TYPE_FLOAT || dataType == DataBuffer.TYPE_DOUBLE;
        var sizes = raster.getSampleModel().getSampleSize();
        var deep = floating;
        for (var size : sizes) {
            deep |= size > 8;
        }
        var depth = deep ? 16 : 8;
        var target = (1L << depth) - 1;
        var width = (region.width + xStep - 1) / xStep;
        var height = (region.height + yStep - 1) / yStep;
        var alpha = bands == 2 || bands == 4;
        var gray = bands <= 2;
        Rgba16Picture wide = deep ? Rgba16Picture.allocate(width, height, alpha) : null;
        RgbaPicture narrow = deep ? null : RgbaPicture.allocate(width, height, alpha);
        var stride = deep ? wide.stride() : narrow.stride();
        var rowWidth = region.width;
        var ints = floating ? null : new int[rowWidth * bands];
        var doubles = floating ? new double[rowWidth * bands] : null;
        var scale = new long[bands];
        for (var b = 0; b < bands; b++) {
            scale[b] = (1L << Math.min(sizes[b], 32)) - 1;
        }
        for (var y = 0; y < height; y++) {
            var sourceY = region.y + y * yStep;
            if (floating) {
                raster.getPixels(region.x, sourceY, rowWidth, 1, doubles);
            } else {
                raster.getPixels(region.x, sourceY, rowWidth, 1, ints);
            }
            var row = y * stride;
            for (var x = 0; x < width; x++) {
                var from = x * xStep * bands;
                var at = row + x * 4;
                for (var channel = 0; channel < 4; channel++) {
                    var band = gray ? (channel < 3 ? 0 : 1) : channel;
                    long value;
                    if (channel == 3 && !alpha) {
                        value = target;
                    } else if (floating) {
                        var sample = doubles[from + band];
                        value = Math.round(Math.clamp(Double.isNaN(sample) ? 0 : sample, 0.0, 1.0) * target);
                    } else {
                        var sample = ints[from + band] & 0xFFFFFFFFL;
                        var top = scale[band];
                        value = top == target ? sample : (Math.min(sample, top) * target + top / 2) / top;
                    }
                    if (deep) {
                        wide.pixels()[at + channel] = (short) value;
                    } else {
                        narrow.pixels()[at + channel] = (byte) value;
                    }
                }
            }
        }
        return deep ? wide : narrow;
    }

    /**
     * Copies a region of a picture at full size into a {@link BufferedImage}.
     *
     * @param picture the picture to read
     * @param region the region to read
     * @param xStep the horizontal sampling step; positive
     * @param yStep the vertical sampling step; positive
     * @param destination the image to write
     * @param destX the destination column of the first sample
     * @param destY the destination row of the first sample
     */
    private static void copy(Picture picture, Rectangle region, int xStep, int yStep, BufferedImage destination,
                             int destX, int destY) {
        copy(picture, picture.width(), picture.height(), region, xStep, yStep, destination, destX, destY, null);
    }

    /**
     * Packs selected RGBA pixels of a row into ARGB integers.
     *
     * @param pixels the RGBA samples
     * @param row the index of the row's first sample
     * @param columns the sample offset in the row of each output pixel
     * @param output receives one ARGB value per column
     */
    private static void packArgb(byte[] pixels, int row, int[] columns, int[] output) {
        for (var x = 0; x < columns.length; x++) {
            var from = row + columns[x];
            output[x] = (pixels[from + 3] & 0xFF) << 24 | (pixels[from] & 0xFF) << 16
                    | (pixels[from + 1] & 0xFF) << 8 | (pixels[from + 2] & 0xFF);
        }
    }

    /**
     * {@return whether a picture is a {@link YuvPicture} or {@link Yuv16Picture} without chroma
     * planes}
     *
     * @param picture the picture to inspect
     */
    static boolean isMonochrome(Picture picture) {
        return picture instanceof YuvPicture yuv && !yuv.chromaFormat().hasChroma()
                || picture instanceof Yuv16Picture deep && !deep.chromaFormat().hasChroma();
    }

    /**
     * Receives the progress of {@link #copy(Picture, int, int, Rectangle, int, int, BufferedImage,
     * int, int, RowListener)}.
     */
    @FunctionalInterface
    interface RowListener {
        /**
         * Notes that a destination row is complete.
         *
         * @param row the index of the row, from {@code 0}
         * @param total the number of rows
         * @return {@code false} to stop copying
         */
        boolean rowDone(int row, int total);
    }
}
