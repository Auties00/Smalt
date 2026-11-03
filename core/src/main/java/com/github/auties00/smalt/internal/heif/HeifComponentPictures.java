package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Assembles the component planes of an image item ({@link HeifComponents}) into a planar
 * picture.
 *
 * <p>The color model follows the component types: red, green and blue make a 4:4:4 picture with
 * the identity matrix (green in the luma plane, blue and red in the chroma planes, as H.273 codes
 * RGB); luma with both chroma components make a Y'CbCr picture in the chroma format their
 * subsampling gives, 4:1:1 being widened to 4:4:4 by repeating each chroma sample; luma or a
 * monochrome component alone makes a monochrome picture. An alpha component, straight or
 * premultiplied, becomes the alpha plane. Components of up to 8 bits make an 8-bit picture and
 * deeper ones a picture of the depth of the deepest color component; every plane of another depth
 * is rescaled to the picture depth, {@code (v * max + m / 2) / m} for a sample {@code v} of
 * largest value {@code m}, so that an exact 8-bit or deeper source keeps its samples.
 */
public final class HeifComponentPictures {
    /**
     * Prevents instantiation.
     */
    private HeifComponentPictures() {
        throw new AssertionError();
    }

    /**
     * {@return whether the components hold alpha the color is premultiplied by}
     *
     * @param components the decoded planes of an item
     */
    public static boolean premultiplied(HeifComponents components) {
        return components.plane(HeifComponents.PREMULTIPLIED_ALPHA) != null;
    }

    /**
     * Assembles components into a planar picture.
     *
     * @param name the format name, for messages
     * @param components the decoded planes of an item
     * @param color the color description of Y'CbCr and monochrome pictures; an RGB picture takes it
     * with the identity matrix and full range
     * @param limits the bounds of the picture
     * @return a {@link YuvPicture}, or a {@link Yuv16Picture} for components deeper than 8 bits
     * @throws MediaException.Malformed if the planes do not fit the image size or a chroma
     * component lacks its pair
     * @throws MediaException.Unsupported if the components hold no color, or chroma of an
     * unsupported subsampling
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Picture toPicture(String name, HeifComponents components, ColorInfo color, MediaLimits limits) {
        var width = components.width();
        var height = components.height();
        LimitChecks.checkPicture(limits, width, height);
        var red = components.plane(HeifComponents.RED);
        var green = components.plane(HeifComponents.GREEN);
        var blue = components.plane(HeifComponents.BLUE);
        var luma = components.plane(HeifComponents.Y);
        var cb = components.plane(HeifComponents.CB);
        var cr = components.plane(HeifComponents.CR);
        var alpha = components.plane(HeifComponents.ALPHA);
        if (alpha == null) {
            alpha = components.plane(HeifComponents.PREMULTIPLIED_ALPHA);
        }
        HeifComponents.Plane first;
        HeifComponents.Plane second = null;
        HeifComponents.Plane third = null;
        ChromaFormat format;
        if (red != null && green != null && blue != null) {
            first = green;
            second = blue;
            third = red;
            format = ChromaFormat.YUV444;
            color = color.withMatrix(MatrixCoefficients.IDENTITY).withRange(ColorRange.FULL);
        } else if (luma != null && (cb != null || cr != null)) {
            if (cb == null || cr == null) {
                throw new MediaException.Malformed(name + ": a chroma component lacks its pair");
            }
            first = luma;
            second = cb;
            third = cr;
            if (cb.subsamplingX() != cr.subsamplingX() || cb.subsamplingY() != cr.subsamplingY()) {
                throw new MediaException.Malformed(name + ": the chroma components differ in subsampling");
            }
            format = switch (cb.subsamplingX() * 8 + cb.subsamplingY()) {
                case 9 -> ChromaFormat.YUV444;
                case 17 -> ChromaFormat.YUV422;
                case 18 -> ChromaFormat.YUV420;
                case 33 -> ChromaFormat.YUV444;
                default -> throw new MediaException.Unsupported(name, "chroma subsampled " + cb.subsamplingX() + "x"
                        + cb.subsamplingY());
            };
        } else if (luma != null || components.plane(HeifComponents.MONOCHROME) != null) {
            first = luma != null ? luma : components.plane(HeifComponents.MONOCHROME);
            format = ChromaFormat.MONOCHROME;
        } else {
            throw new MediaException.Unsupported(name, "images without luma, monochrome or RGB components");
        }
        check(name, first, width, height, 1, 1);
        if (second != null) {
            check(name, second, width, height, second.subsamplingX(), second.subsamplingY());
            check(name, third, width, height, third.subsamplingX(), third.subsamplingY());
        }
        if (alpha != null) {
            check(name, alpha, width, height, 1, 1);
        }
        var depth = first.bitDepth();
        if (second != null) {
            depth = Math.max(depth, Math.max(second.bitDepth(), third.bitDepth()));
        }
        var deep = depth > 8;
        var target = deep ? depth : 8;
        var chromaWidth = format.chromaWidth(width);
        var chromaHeight = format.chromaHeight(height);
        var samples = (long) width * height * (alpha != null ? 2 : 1)
                + (format.hasChroma() ? 2L * chromaWidth * chromaHeight : 0);
        LimitChecks.checkAllocation(limits, samples * (deep ? 2 : 1));
        if (deep) {
            var builder = Yuv16Picture.builder().size(width, height).chromaFormat(format).bitDepth(target).color(color)
                    .luma(wide(first, target, width, height), width);
            if (second != null) {
                builder.chroma(wide(second, target, chromaWidth, chromaHeight),
                        wide(third, target, chromaWidth, chromaHeight), chromaWidth);
            }
            if (alpha != null) {
                builder.alpha(wide(alpha, target, width, height), width);
            }
            return builder.build();
        }
        var builder = YuvPicture.builder().size(width, height).chromaFormat(format).color(color)
                .luma(narrow(first, width, height), width);
        if (second != null) {
            builder.chroma(narrow(second, chromaWidth, chromaHeight), narrow(third, chromaWidth, chromaHeight),
                    chromaWidth);
        }
        if (alpha != null) {
            builder.alpha(narrow(alpha, width, height), width);
        }
        return builder.build();
    }

    /**
     * Checks that a plane covers the image at its subsampling.
     *
     * @param name the format name, for messages
     * @param plane the component plane to check
     * @param width the image width
     * @param height the image height
     * @param subsamplingX the horizontal subsampling the plane must have
     * @param subsamplingY the vertical subsampling the plane must have
     * @throws MediaException.Malformed if the plane has another size or subsampling
     */
    private static void check(String name, HeifComponents.Plane plane, int width, int height, int subsamplingX,
                              int subsamplingY) {
        var w = (width + subsamplingX - 1) / subsamplingX;
        var h = (height + subsamplingY - 1) / subsamplingY;
        if (plane.subsamplingX() != subsamplingX || plane.subsamplingY() != subsamplingY || plane.width() != w
                || plane.height() != h || plane.samples().length < (long) w * h || plane.bitDepth() < 1
                || plane.bitDepth() > 16) {
            throw new MediaException.Malformed(name + ": a component plane of " + plane.width() + "x" + plane.height()
                    + " does not cover a " + width + "x" + height + " image");
        }
    }

    /**
     * Returns the samples of a plane at 8 bits, at the size of a picture plane.
     *
     * @param plane the component plane to convert
     * @param width the picture plane width: the plane width, or the image width for a plane widened from 4:1:1
     * @param height the picture plane height
     * @return the samples
     */
    private static byte[] narrow(HeifComponents.Plane plane, int width, int height) {
        var out = new byte[width * height];
        var max = (1 << plane.bitDepth()) - 1;
        var repeat = plane.width() == width ? 1 : plane.subsamplingX();
        var src = plane.samples();
        for (var y = 0; y < height; y++) {
            var in = y * plane.width();
            var at = y * width;
            for (var x = 0; x < width; x++) {
                var v = Math.min(src[in + x / repeat] & 0xFFFF, max);
                out[at + x] = (byte) (max == 255 ? v : (v * 255 + max / 2) / max);
            }
        }
        return out;
    }

    /**
     * Returns the samples of a plane at a depth above 8 bits, at the size of a picture plane.
     *
     * @param plane the component plane to convert
     * @param depth the picture depth
     * @param width the picture plane width: the plane width, or the image width for a plane widened from 4:1:1
     * @param height the picture plane height
     * @return the samples
     */
    private static short[] wide(HeifComponents.Plane plane, int depth, int width, int height) {
        var out = new short[width * height];
        var max = (1 << plane.bitDepth()) - 1;
        var target = (1 << depth) - 1;
        var repeat = plane.width() == width ? 1 : plane.subsamplingX();
        var src = plane.samples();
        for (var y = 0; y < height; y++) {
            var in = y * plane.width();
            var at = y * width;
            for (var x = 0; x < width; x++) {
                long v = Math.min(src[in + x / repeat] & 0xFFFF, max);
                out[at + x] = (short) (max == target ? v : (v * target + max / 2) / max);
            }
        }
        return out;
    }
}
