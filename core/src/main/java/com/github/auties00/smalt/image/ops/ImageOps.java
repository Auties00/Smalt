/* Ports Pillow (src/libImaging/Resample.c, the resampler structure). Copyright Secret Labs AB, Fredrik Lundh and
 * contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.MediaRuntime;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;
import java.util.Optional;

/**
 * Transforms pictures: color conversion, resampling, orientation, rotation by any angle, affine
 * transforms, cropping, trimming, padding and extending, smart cropping and alpha flattening.
 *
 * <p>Filters, tone adjustments, compositing and palette quantization live in the sibling classes
 * {@link ImageFilters}, {@link ImageAdjustments}, {@link ImageCompositing} and
 * {@link ImageQuantization}.
 *
 * <p>Every method validates its arguments, never modifies its input, and returns either a new
 * picture with tightly packed rows or, where documented, the input itself when the operation
 * would not change it. Inputs may have any row stride. The result does not depend on the number
 * of threads that compute it; {@link MediaRuntime} describes the threading model. This class is
 * thread-safe.
 *
 * <p>A result that is no larger than its input, that is, whose area is at most the input's and
 * whose longer side is at most the input's longer side, is not checked against any
 * {@link MediaLimits}, so a picture decoded under raised limits can be processed; a larger result
 * must fit {@link MediaLimits#DEFAULT}. The sibling classes follow the same rule. A result or
 * working buffer whose samples do not fit one Java array throws
 * {@link MediaException.LimitExceeded}.
 *
 * <p>Conversions between packed RGBA and planar Y'CbCr use the matrix and range of the picture's
 * {@link ColorInfo}; RGBA is always treated as gamma-encoded R'G'B'. Those conversions carry the
 * primaries and the transfer characteristics as labels only; color management, from one
 * {@link ColorProfile} to another and from high-dynamic-range video to sRGB, is what
 * {@link #convertColor(Picture, ColorTransform)} and {@link #toSrgb(Picture)} do.
 *
 * <p>High-bit-depth pictures ({@link Yuv16Picture}, {@link Rgba16Picture}) keep their depth
 * through {@link #crop}, {@link #orient}, the flips and transposes, {@link #rotate},
 * {@link #affine}, {@link #trim}, {@link #extend}, {@link #resizeCanvas} and {@link #isOpaque}
 * (the operations that compute new pixels return an {@link Rgba16Picture}), and change it through
 * {@link #toEightBit(Picture)}, {@link #toBitDepth(YuvPicture, int)},
 * {@link #toBitDepth(Yuv16Picture, int)} and {@link #toSixteenBit(RgbaPicture)}. Every other
 * operation reduces them to 8 bits with {@link #toEightBit(Picture)} first and returns an 8-bit
 * picture.
 *
 * @implNote This implementation runs every hot loop in a kernel with a scalar and a Vector API twin that produce
 * identical results. Color conversion and chroma upsampling follow libjpeg-turbo, and resampling follows the structure
 * of Pillow's {@code Resample.c}.
 */
public final class ImageOps {
    /**
     * The approximate number of operations per pixel of a color conversion, which weighs its
     * work against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long COLOR_WORK = 8;

    /**
     * Prevents instantiation.
     */
    private ImageOps() {
        throw new AssertionError();
    }

    /**
     * Converts a picture to packed RGBA, upsampling subsampled chroma with the triangle filter.
     *
     * @param picture the picture to convert
     * @return the picture itself if it is already an {@link RgbaPicture}, otherwise a new one
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     * @see #toRgba(Picture, ChromaUpsampling)
     */
    public static RgbaPicture toRgba(Picture picture) {
        try (var _ = ParallelBands.operation()) {
            return toRgba(picture, ChromaUpsampling.TRIANGLE);
        }
    }

    /**
     * Converts a picture to packed RGBA.
     *
     * <p>A {@link YuvPicture} is converted with the matrix and range of its {@link ColorInfo}
     * (resolved with {@link MatrixCoefficients#effective()}, so an unspecified matrix is BT.601):
     * BT.601, BT.709, BT.470BG, SMPTE 240M, BT.2020 non-constant luminance, FCC, YCgCo and identity
     * (GBR) matrices, each in limited or full range. Values outside the R'G'B' cube are clamped. Monochrome pictures
     * become gray. Subsampled chroma is reconstructed as {@code upsampling} specifies. The alpha
     * plane, if any, becomes the alpha channel, and the result reports alpha exactly when the
     * input has an alpha plane. For a full-range BT.601 (JPEG) picture, {@link ChromaUpsampling#TRIANGLE} output is
     * bit-identical to libjpeg-turbo's decoder.
     *
     * @param picture the picture to convert
     * @param upsampling how to reconstruct subsampled chroma
     * @return the picture itself if it is already an {@link RgbaPicture}, otherwise a new one
     * @throws MediaException.Unsupported if the picture's matrix coefficients need the transfer function or the
     * primaries (BT.2020 constant luminance, SMPTE 2085, chromaticity-derived, ICtCp)
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static RgbaPicture toRgba(Picture picture, ChromaUpsampling upsampling) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(upsampling, "upsampling");
            try {
                return switch (picture) {
                    case RgbaPicture rgba -> rgba;
                    case Rgba16Picture rgba -> HighDepth.toEightBit(rgba);
                    case YuvPicture yuv -> yuvToRgba(yuv, upsampling, PictureBuffers.FRESH);
                    case Yuv16Picture yuv -> yuvToRgba(HighDepth.toEightBit(yuv), upsampling, PictureBuffers.FRESH);
                };
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Converts a picture to planar Y'CbCr, averaging subsampled chroma with equal weights.
     *
     * @param picture the picture to convert
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @return the picture itself if it already has this layout and color, otherwise a new one
     * @throws MediaException.Unsupported if a matrix is not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     * @see #toYuv(Picture, ChromaFormat, ColorInfo, ChromaDownsampling)
     */
    public static YuvPicture toYuv(Picture picture, ChromaFormat format, ColorInfo color) {
        try (var _ = ParallelBands.operation()) {
            return toYuv(picture, format, color, ChromaDownsampling.AVERAGE);
        }
    }

    /**
     * Converts a picture to planar Y'CbCr.
     *
     * <p>An {@link RgbaPicture} is converted with the matrix and range of {@code color}; each
     * chroma sample of a subsampled layout is computed from the exact average (or, with
     * {@link ChromaDownsampling#ALPHA_WEIGHTED}, the alpha-weighted average) of the R'G'B' values
     * of the pixels it covers, rounded once. Pixels past an odd right or bottom edge are not
     * invented: a chroma sample at the edge averages only the pixels that exist. The alpha
     * channel becomes an alpha plane when the picture reports alpha, and is dropped otherwise.
     *
     * <p>A {@link YuvPicture} with the same layout and color is returned unchanged. With the same
     * matrix and range, only the chroma planes are resampled: reduced by box averaging (weighted
     * by the alpha plane with {@link ChromaDownsampling#ALPHA_WEIGHTED}) and enlarged with the
     * triangle filter. With only the range changed, every sample is remapped exactly between the
     * ranges. With the matrix changed (and the range changed or not) in an unchanged 4:2:0, 4:2:2 or
     * 4:4:4 layout, and neither matrix {@link MatrixCoefficients#IDENTITY}, the samples are converted
     * directly in Y'CbCr: the chroma planes sample by sample at their own resolution, each luma sample
     * from itself and the chroma at its position (reconstructed with the triangle filter), rounded
     * once. For colors inside the R'G'B' cube this is within one code value of a conversion through
     * RGBA; colors outside the cube are not clamped to it first. Otherwise the picture is converted
     * through RGBA. Matrices are resolved with {@link MatrixCoefficients#effective()}. The alpha plane
     * is kept. The primaries and transfer of {@code color} are assigned without conversion.
     *
     * @param picture the picture to convert
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param downsampling how to average the pixels a subsampled chroma sample covers
     * @return the picture itself if it already has this layout and color, otherwise a new one
     * @throws MediaException.Unsupported if a matrix is not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static YuvPicture toYuv(Picture picture, ChromaFormat format, ColorInfo color, ChromaDownsampling downsampling) {
        return toYuv(picture, format, color, downsampling, PictureBuffers.FRESH);
    }

    /**
     * Converts a picture to planar Y'CbCr as {@link #toYuv(Picture, ChromaFormat, ColorInfo, ChromaDownsampling)}
     * does, writing the result and the RGBA picture a conversion between matrices passes through
     * into pictures from {@code buffers}.
     *
     * @param picture the picture to convert
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param downsampling how to average the pixels a subsampled chroma sample covers
     * @param buffers the source of the pictures written
     * @return the picture itself if it already has this layout and color, otherwise a picture from
     * {@code buffers}, or a new one for a 16-bit picture
     */
    static YuvPicture toYuv(Picture picture, ChromaFormat format, ColorInfo color, ChromaDownsampling downsampling,
                            PictureBuffers buffers) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(downsampling, "downsampling");
            try {
                return switch (picture) {
                    case RgbaPicture rgba -> rgbaToYuv(rgba, format, color, downsampling, buffers);
                    case Rgba16Picture rgba -> rgbaToYuv(HighDepth.toEightBit(rgba), format, color, downsampling,
                            PictureBuffers.FRESH);
                    case YuvPicture yuv -> yuvToYuv(yuv, format, color, downsampling, buffers);
                    case Yuv16Picture yuv -> yuvToYuv(HighDepth.toEightBit(yuv), format, color, downsampling,
                            PictureBuffers.FRESH);
                };
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Resamples a picture to a new size.
     *
     * <p>The picture is filtered separably with {@code filter}, stretched by the reduction factor
     * of each axis that shrinks (see {@link ResizeFilter}); the aspect ratio is not preserved
     * unless the caller preserves it. The result has the input's representation:
     * <ul>
     *   <li>an {@link RgbaPicture} that reports alpha and is not fully opaque is filtered in
     *       premultiplied alpha, so the color of transparent pixels never bleeds into visible
     *       ones, and divided back with exact rounding; other RGBA pictures are filtered
     *       directly (and, when they report no alpha, get opaque alpha);</li>
     *   <li>each plane of a {@link YuvPicture} is filtered independently: the chroma planes to
     *       the size its {@link ChromaFormat} implies for the new size (odd sizes round up),
     *       using the same source-to-output mapping as luma so chroma stays registered with
     *       luma, and the alpha plane like luma (straight alpha; convert to RGBA first for
     *       premultiplied filtering).</li>
     * </ul>
     *
     * @param picture the picture to resample
     * @param width the new width; positive
     * @param height the new height; positive
     * @param filter the reconstruction filter
     * @return the picture itself if it already has this size, otherwise a new picture
     * @throws IllegalArgumentException if {@code width} or {@code height} is not positive
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture resize(Picture picture, int width, int height, ResizeFilter filter) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(filter, "filter");
            requirePositive(width, height);
            OpChecks.checkResult(picture, width, height);
            if (width == picture.width() && height == picture.height()) {
                return picture;
            }
            try {
                return resample(picture, 0, 0, picture.width(), picture.height(), width, height, filter);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Applies an orientation transform, turning stored pixels upright.
     *
     * <p>{@link Orientation#NORMAL} returns the input. The quarter-turn transforms swap width and
     * height. Planes of a {@link YuvPicture} are transformed independently; a 4:2:0 picture stays
     * 4:2:0 and a 4:4:4 or monochrome picture keeps its layout, but a 4:2:2 picture turned by a
     * quarter turn becomes 4:4:4, because its chroma, halved horizontally, would end up halved
     * vertically, a layout {@link ChromaFormat} cannot describe: its chroma is first widened by
     * replicating each sample, which loses nothing (averaging the pairs back restores it).
     *
     * @param picture the picture to transform
     * @param orientation the transform to apply
     * @return the input for {@link Orientation#NORMAL}, otherwise a new picture
     */
    public static Picture orient(Picture picture, Orientation orientation) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(orientation, "orientation");
            if (orientation == Orientation.NORMAL) {
                return picture;
            }
            try {
                return Orienter.orient(picture, orientation);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Copies a rectangle of a picture.
     *
     * <p>Luma, alpha and RGBA pixels are cropped exactly. Subsampled chroma cannot be split, so
     * each chroma sample of the result is the source chroma sample that covers the top-left
     * pixel of the block it represents; with an odd offset this shifts chroma by one pixel
     * relative to luma.
     *
     * @param picture the picture to copy from
     * @param x the left edge of the rectangle; non-negative
     * @param y the top edge of the rectangle; non-negative
     * @param width the width of the rectangle; positive
     * @param height the height of the rectangle; positive
     * @return the input if the rectangle is the whole picture, otherwise a new picture
     * @throws IllegalArgumentException if the rectangle is empty or not inside the picture
     */
    public static Picture crop(Picture picture, int x, int y, int width, int height) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requirePositive(width, height);
            if (x < 0 || y < 0 || (long) x + width > picture.width() || (long) y + height > picture.height()) {
                throw new IllegalArgumentException("crop rectangle " + width + "x" + height + "+" + x + "+" + y
                        + " is outside the " + picture.width() + "x" + picture.height() + " picture");
            }
            if (x == 0 && y == 0 && width == picture.width() && height == picture.height()) {
                return picture;
            }
            try {
                return Geometry.crop(picture, x, y, width, height);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Copies a rectangle of a picture, with chroma handled as in
     * {@link #crop(Picture, int, int, int, int)}.
     *
     * @param picture the picture to crop
     * @param region the rectangle to keep
     * @return the input if the rectangle is the whole picture, otherwise a new picture
     * @throws IllegalArgumentException if the rectangle is not inside the picture
     */
    public static Picture crop(Picture picture, Region region) {
        Objects.requireNonNull(region, "region");
        return crop(picture, region.x(), region.y(), region.width(), region.height());
    }

    /**
     * Places a picture on a canvas filled with a background color.
     *
     * <p>The picture is converted to RGBA and its pixels, including their alpha, replace the
     * canvas pixels they cover (they are copied, not blended). The top-left corner goes to
     * {@code (x, y)}, which may be negative or place the picture partly or entirely outside the
     * canvas; only the overlapping part is copied. The result reports alpha when the background
     * is not opaque or the picture reports alpha.
     *
     * @param picture the picture to place
     * @param canvasWidth the canvas width; positive
     * @param canvasHeight the canvas height; positive
     * @param x the canvas column of the picture's left edge
     * @param y the canvas row of the picture's top edge
     * @param argbBackground the background as {@code 0xAARRGGBB} (straight alpha)
     * @return a new picture
     * @throws IllegalArgumentException if a canvas dimension is not positive
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the canvas is larger than the picture and exceeds the default limits
     */
    public static RgbaPicture pad(Picture picture, int canvasWidth, int canvasHeight, int x, int y, int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requirePositive(canvasWidth, canvasHeight);
            OpChecks.checkResult(picture, canvasWidth, canvasHeight);
            try {
                return Geometry.pad(toRgba(picture), canvasWidth, canvasHeight, x, y, argbBackground);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Composites a picture's straight alpha over an opaque background color.
     *
     * <p>Each sample becomes {@code round((c * a + k * (255 - a)) / 255)}, with {@code k} the
     * background's sample, and the result reports no alpha. An {@link RgbaPicture} is composited
     * in R'G'B'. A {@link YuvPicture} is composited directly in Y'CbCr, which is exact because
     * the conversion is affine: luma is blended per pixel with the background's luma, and each
     * chroma sample with the background's chroma by the average alpha of the pixels it covers
     * (a monochrome picture keeps only the background's luma). The result keeps the input's
     * representation, layout and color description.
     *
     * @param picture the picture to flatten
     * @param rgbBackground the background as {@code 0xRRGGBB}; the top byte is ignored
     * @return the input if it reports no alpha, otherwise a new opaque picture
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     */
    public static Picture flatten(Picture picture, int rgbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            if (!picture.hasAlpha()) {
                return picture;
            }
            try {
                return Geometry.flatten(picture, rgbBackground & 0xFFFFFF);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Returns whether every pixel of a picture is fully opaque.
     *
     * <p>A picture that reports no alpha is opaque without a scan; otherwise every alpha value is
     * checked.
     *
     * @param picture the picture to inspect
     * @return whether no pixel is even partly transparent
     */
    public static boolean isOpaque(Picture picture) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            try {
                return Geometry.isOpaque(picture);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Shrinks a picture, keeping its aspect ratio, so that neither side exceeds a bound.
     *
     * <p>The longer side becomes {@code maxEdge} and the shorter one is scaled by the same factor
     * and rounded to the nearest integer (halves up), but never below 1.
     *
     * @param picture the picture to scale
     * @param maxEdge the largest allowed width and height; positive
     * @param filter the reconstruction filter
     * @return the input if both sides are already within the bound, otherwise a new picture
     * @throws IllegalArgumentException if {@code maxEdge} is not positive
     */
    public static Picture fitWithin(Picture picture, int maxEdge, ResizeFilter filter) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(filter, "filter");
            if (maxEdge <= 0) {
                throw new IllegalArgumentException("maximum edge " + maxEdge + " is not positive");
            }
            var width = picture.width();
            var height = picture.height();
            if (width <= maxEdge && height <= maxEdge) {
                return picture;
            }
            if (width >= height) {
                return resize(picture, maxEdge, scaleEdge(height, maxEdge, width), filter);
            }
            return resize(picture, scaleEdge(width, maxEdge, height), maxEdge, filter);
        }
    }

    /**
     * Scales a picture, keeping its aspect ratio, to the largest size that fits a box, and
     * centers it on a canvas of the box's size.
     *
     * <p>The picture is enlarged or reduced until one side equals the box's and the other is at
     * most the box's (rounded to the nearest integer, at least 1). It is converted to RGBA,
     * resized (premultiplied when it has transparency), and placed as {@link #pad} does at
     * {@code ((boxWidth - w) / 2, (boxHeight - h) / 2)}, rounding down.
     *
     * @param picture the picture to scale
     * @param boxWidth the box width; positive
     * @param boxHeight the box height; positive
     * @param filter the reconstruction filter
     * @param argbBackground the canvas color as {@code 0xAARRGGBB}, typically transparent
     * @return a new picture of the box's size
     * @throws IllegalArgumentException if a box dimension is not positive
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the box is larger than the picture and exceeds the default limits
     */
    public static RgbaPicture fitInto(Picture picture, int boxWidth, int boxHeight, ResizeFilter filter, int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(filter, "filter");
            requirePositive(boxWidth, boxHeight);
            OpChecks.checkResult(picture, boxWidth, boxHeight);
            var width = picture.width();
            var height = picture.height();
            int fittedWidth;
            int fittedHeight;
            if ((long) width * boxHeight >= (long) height * boxWidth) {
                fittedWidth = boxWidth;
                fittedHeight = Math.min(boxHeight, scaleEdge(height, boxWidth, width));
            } else {
                fittedHeight = boxHeight;
                fittedWidth = Math.min(boxWidth, scaleEdge(width, boxHeight, height));
            }
            var fitted = (RgbaPicture) resize(toRgba(picture), fittedWidth, fittedHeight, filter);
            try {
                return Geometry.pad(fitted, boxWidth, boxHeight, (boxWidth - fittedWidth) / 2,
                        (boxHeight - fittedHeight) / 2, argbBackground);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Crops the largest centered square out of a picture and resizes it.
     *
     * <p>The square's side is the shorter side of the picture, and its offset along the longer
     * side is half the difference, rounded down. The square is then resized to {@code side}
     * (enlarged or reduced) with {@code filter}. Cropping and resizing happen in one pass, so
     * chroma is resampled at its exact position even for odd offsets, and taps that reach past the
     * square's edges read the pixels next to it.
     *
     * @param picture the picture to crop and scale
     * @param side the side of the result; positive
     * @param filter the reconstruction filter
     * @return the input if it is already a square of that side, otherwise a new picture with the input's representation
     * @throws IllegalArgumentException if {@code side} is not positive
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture coverSquare(Picture picture, int side, ResizeFilter filter) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(filter, "filter");
            requirePositive(side, side);
            OpChecks.checkResult(picture, side, side);
            var square = Math.min(picture.width(), picture.height());
            var x = (picture.width() - square) / 2;
            var y = (picture.height() - square) / 2;
            if (square == side) {
                return crop(picture, x, y, square, square);
            }
            try {
                return resample(picture, x, y, square, square, side, side, filter);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Rotates a picture clockwise with bicubic interpolation onto a canvas that fits it, filling
     * the uncovered corners with transparent black.
     *
     * @param picture the picture to rotate
     * @param degrees the angle in degrees; positive turns clockwise, negative counter-clockwise
     * @return the rotated picture, as {@link #rotate(Picture, double, Interpolation, RotationCanvas, int)} describes
     * @throws IllegalArgumentException if {@code degrees} is not finite
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture rotate(Picture picture, double degrees) {
        return rotate(picture, degrees, Interpolation.BICUBIC, RotationCanvas.EXPAND, 0);
    }

    /**
     * Rotates a picture clockwise by an arbitrary angle.
     *
     * <p>The picture turns about its center, and the center of the result is the center of the
     * rotated picture. The size of the result depends on {@code canvas}:
     * <ul>
     *   <li>{@link RotationCanvas#EXPAND}: the bounding box of the rotated picture, each side
     *       rounded up to whole pixels;</li>
     *   <li>{@link RotationCanvas#KEEP}: the size of the input;</li>
     *   <li>{@link RotationCanvas#INSCRIBED}: the largest rectangle with the input's aspect ratio
     *       whose pixels are all computed from the picture alone (so for an interpolation that
     *       reads neighboring pixels it keeps one or two pixels away from the rotated edges),
     *       rounded down to whole pixels and at least 1x1.</li>
     * </ul>
     *
     * <p>Angles that are whole multiples of 90 degrees move pixels without interpolating: the
     * result equals {@link #orient} with the matching {@link Orientation} (keeping the input's
     * representation and depth), cropped or extended about its center to the size the canvas
     * mode asks for. Other angles resample the picture as {@link #affine} does, in premultiplied
     * alpha with the background color read wherever the rotated picture does not reach, so its
     * edges are antialiased against the background; the result is an {@link RgbaPicture}, or an
     * {@link Rgba16Picture} for a 16-bit input, and reports alpha when the input does or when the
     * background is not opaque (except on an inscribed canvas, where no background shows).
     *
     * @param picture the picture to rotate
     * @param degrees the angle in degrees; positive turns clockwise, negative counter-clockwise
     * @param interpolation how to sample the picture between its pixels
     * @param canvas the size of the result
     * @param argbBackground the color of the areas the picture does not cover, as {@code 0xAARRGGBB} with straight
     * alpha
     * @return the input for a multiple of 360 degrees, otherwise a new picture
     * @throws IllegalArgumentException if {@code degrees} is not finite
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture rotate(Picture picture, double degrees, Interpolation interpolation, RotationCanvas canvas,
                                 int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(interpolation, "interpolation");
            Objects.requireNonNull(canvas, "canvas");
            if (!Double.isFinite(degrees)) {
                throw new IllegalArgumentException("angle " + degrees + " is not finite");
            }
            try {
                return Rotation.rotate(picture, degrees, interpolation, canvas, argbBackground);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Mirrors a picture left to right.
     *
     * @param picture the picture to mirror
     * @return a new picture with the input's representation and depth
     * @see #orient(Picture, Orientation)
     */
    public static Picture flipHorizontal(Picture picture) {
        return orient(picture, Orientation.FLIP_HORIZONTAL);
    }

    /**
     * Mirrors a picture top to bottom.
     *
     * @param picture the picture to mirror
     * @return a new picture with the input's representation and depth
     * @see #orient(Picture, Orientation)
     */
    public static Picture flipVertical(Picture picture) {
        return orient(picture, Orientation.FLIP_VERTICAL);
    }

    /**
     * Mirrors a picture across its top-left to bottom-right diagonal, swapping rows and columns.
     *
     * @param picture the picture to mirror
     * @return a new picture with the input's representation and depth (a 4:2:2 picture becomes 4:4:4, as
     * {@link #orient} describes)
     * @see #orient(Picture, Orientation)
     */
    public static Picture transpose(Picture picture) {
        return orient(picture, Orientation.TRANSPOSE);
    }

    /**
     * Mirrors a picture across its top-right to bottom-left diagonal.
     *
     * @param picture the picture to mirror
     * @return a new picture with the input's representation and depth (a 4:2:2 picture becomes 4:4:4, as
     * {@link #orient} describes)
     * @see #orient(Picture, Orientation)
     */
    public static Picture transverse(Picture picture) {
        return orient(picture, Orientation.TRANSVERSE);
    }

    /**
     * Maps a picture through an affine transform onto a canvas of the given size.
     *
     * <p>{@code transform} maps the coordinates of the input to those of the result (see
     * {@link AffineTransform} for the coordinate system). Each pixel of the result samples the
     * input, with {@code interpolation}, at the point the inverse transform maps its center to.
     * Interpolation happens in premultiplied alpha; neighboring pixels that lie outside the input
     * read the background color, so the edges of the mapped picture are antialiased against the
     * background, and pixels whose center maps outside the input's reach take the background. The
     * result is an {@link RgbaPicture}, or an {@link Rgba16Picture} for a 16-bit input, and
     * reports alpha when the input does or the background is not opaque.
     *
     * @apiNote The interpolation is not filtered: a transform that shrinks the picture by more than about half aliases.
     * Reduce the picture with {@link #resize} first.
     * @param picture the picture to map
     * @param transform the map from input to output coordinates; invertible
     * @param width the width of the result; positive
     * @param height the height of the result; positive
     * @param interpolation how to sample the picture between its pixels
     * @param argbBackground the color of the areas the picture does not cover, as {@code 0xAARRGGBB} with straight
     * alpha
     * @return a new picture
     * @throws IllegalArgumentException if a dimension is not positive or {@code transform} is not invertible
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture affine(Picture picture, AffineTransform transform, int width, int height,
                                 Interpolation interpolation, int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(transform, "transform");
            Objects.requireNonNull(interpolation, "interpolation");
            requirePositive(width, height);
            OpChecks.checkResult(picture, width, height);
            if (!transform.isInvertible()) {
                throw new IllegalArgumentException("the affine transform " + transform + " is not invertible");
            }
            try {
                var packed = Canvas.toPacked(picture);
                return Rotation.warp(packed, transform.inverse(), width, height, interpolation, argbBackground,
                        packed.hasAlpha() || argbBackground >>> 24 != 255);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Finds the content of a picture inside a uniform border colored like its top-left pixel.
     *
     * @param picture the picture to search
     * @param tolerance the largest difference of a channel from the border color that still counts as border, from
     * {@code 0} to {@code 255} (in 8-bit units, scaled by 257 for 16-bit pictures)
     * @return the bounds, as {@link #trimBounds(Picture, int, int)} describes
     * @throws IllegalArgumentException if {@code tolerance} is outside {@code 0..255}
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Optional<Region> trimBounds(Picture picture, int tolerance) {
        return findBounds(picture, -1L, tolerance);
    }

    /**
     * Finds the content of a picture inside a uniform border of the given color.
     *
     * <p>The bounds are the smallest rectangle outside of which every pixel is within
     * {@code tolerance} of the border color in each of red, green, blue and alpha (a picture
     * that reports no alpha is opaque). Y'CbCr pictures are compared after conversion to RGBA.
     *
     * @param picture the picture to search
     * @param argbBorder the border color as {@code 0xAARRGGBB}, with straight alpha
     * @param tolerance the largest difference of a channel from the border color that still counts as border, from
     * {@code 0} to {@code 255} (in 8-bit units, scaled by 257 for 16-bit pictures)
     * @return the bounds, or an empty optional when every pixel belongs to the border
     * @throws IllegalArgumentException if {@code tolerance} is outside {@code 0..255}
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Optional<Region> trimBounds(Picture picture, int argbBorder, int tolerance) {
        return findBounds(picture, argbBorder & 0xFFFFFFFFL, tolerance);
    }

    /**
     * Removes the uniform border, colored like the top-left pixel, from a picture.
     *
     * @param picture the picture to trim
     * @param tolerance the largest difference of a channel from the border color that still counts as border, from
     * {@code 0} to {@code 255}
     * @return the picture cropped to {@link #trimBounds(Picture, int)}, with its representation and depth; the input
     * itself when it has no border or is entirely border
     * @throws IllegalArgumentException if {@code tolerance} is outside {@code 0..255}
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture trim(Picture picture, int tolerance) {
        return trimBounds(picture, tolerance)
                .map(bounds -> crop(picture, bounds.x(), bounds.y(), bounds.width(), bounds.height()))
                .orElse(picture);
    }

    /**
     * Removes a uniform border of the given color from a picture.
     *
     * @param picture the picture to trim
     * @param argbBorder the border color as {@code 0xAARRGGBB}, with straight alpha
     * @param tolerance the largest difference of a channel from the border color that still counts as border, from
     * {@code 0} to {@code 255}
     * @return the picture cropped to {@link #trimBounds(Picture, int, int)}, with its representation and depth; the
     * input itself when it has no border or is entirely border
     * @throws IllegalArgumentException if {@code tolerance} is outside {@code 0..255}
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture trim(Picture picture, int argbBorder, int tolerance) {
        return trimBounds(picture, argbBorder, tolerance)
                .map(bounds -> crop(picture, bounds.x(), bounds.y(), bounds.width(), bounds.height()))
                .orElse(picture);
    }

    /**
     * Adds a border around a picture.
     *
     * <p>The result is {@code left + width + right} by {@code top + height + bottom} pixels with
     * the picture's pixels at {@code (left, top)}; the border is filled as {@code mode} describes
     * ({@link ExtendMode#BACKGROUND} with {@code argbBackground}). The result is an
     * {@link RgbaPicture}, or an {@link Rgba16Picture} for a 16-bit input, and reports alpha when
     * the input does or when a background border is not opaque.
     *
     * @param picture the picture to extend
     * @param top the rows added above; non-negative
     * @param right the columns added on the right; non-negative
     * @param bottom the rows added below; non-negative
     * @param left the columns added on the left; non-negative
     * @param mode how to fill the border
     * @param argbBackground the border color for {@link ExtendMode#BACKGROUND}, as {@code 0xAARRGGBB} with straight
     * alpha; ignored by the other modes
     * @return the input when every border is zero, otherwise a new picture
     * @throws IllegalArgumentException if a border is negative
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture extend(Picture picture, int top, int right, int bottom, int left, ExtendMode mode,
                                 int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(mode, "mode");
            if (top < 0 || right < 0 || bottom < 0 || left < 0) {
                throw new IllegalArgumentException("negative border " + top + "," + right + "," + bottom + "," + left);
            }
            if (top == 0 && right == 0 && bottom == 0 && left == 0) {
                return picture;
            }
            var width = (long) left + picture.width() + right;
            var height = (long) top + picture.height() + bottom;
            if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
                throw new MediaException.LimitExceeded("extended picture " + width + "x" + height + " is too large");
            }
            OpChecks.checkResult(picture, (int) width, (int) height);
            try {
                return Canvas.place(Canvas.toPacked(picture), (int) width, (int) height, left, top, mode, argbBackground);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Changes the canvas size of a picture without scaling it.
     *
     * <p>The picture is anchored inside the new canvas as {@code anchor} describes (centered
     * offsets round down): a smaller canvas crops it and a larger one extends it with the
     * background color. A canvas that only crops keeps the input's representation and depth, as
     * {@link #crop} does; otherwise the result is an {@link RgbaPicture}, or an
     * {@link Rgba16Picture} for a 16-bit input.
     *
     * @param picture the picture whose canvas changes
     * @param width the canvas width; positive
     * @param height the canvas height; positive
     * @param anchor where the picture sits on the canvas
     * @param argbBackground the color of the added areas, as {@code 0xAARRGGBB} with straight alpha
     * @return the input when the size is unchanged, otherwise a new picture
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture resizeCanvas(Picture picture, int width, int height, Anchor anchor, int argbBackground) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(anchor, "anchor");
            requirePositive(width, height);
            OpChecks.checkResult(picture, width, height);
            if (width == picture.width() && height == picture.height()) {
                return picture;
            }
            var x = anchor.x(width, picture.width(), 0);
            var y = anchor.y(height, picture.height(), 0);
            if (x <= 0 && y <= 0 && (long) width - x <= picture.width() && (long) height - y <= picture.height()) {
                return crop(picture, -x, -y, width, height);
            }
            try {
                return Canvas.place(Canvas.toPacked(picture), width, height, x, y, ExtendMode.BACKGROUND, argbBackground);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Crops a picture to the aspect ratio of a size around its most interesting region, and
     * resizes the crop to that size.
     *
     * <p>The crop is the rectangle {@link #findSmartCrop} returns for the aspect ratio
     * {@code width:height}; it is then resized to exactly {@code width} by {@code height} with
     * {@code filter}, as {@link #resize} does (cropping and resizing in one pass, so the result
     * keeps the input's representation; 16-bit pictures are reduced to 8 bits, as by
     * {@link #resize}).
     *
     * @param picture the picture to crop
     * @param width the width of the result; positive
     * @param height the height of the result; positive
     * @param strategy how to choose the region
     * @param filter the reconstruction filter of the resize
     * @return a new picture, or the input when it already has this size and the crop is the whole picture
     * @throws IllegalArgumentException if a dimension is not positive
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds the default limits
     */
    public static Picture smartCrop(Picture picture, int width, int height, SmartCropStrategy strategy,
                                    ResizeFilter filter) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(filter, "filter");
            requirePositive(width, height);
            OpChecks.checkResult(picture, width, height);
            var region = findSmartCrop(picture, width, height, strategy);
            if (region.width() == width && region.height() == height) {
                return crop(picture, region.x(), region.y(), width, height);
            }
            try {
                return resample(picture, region.x(), region.y(), region.width(), region.height(), width, height, filter);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Chooses the most interesting region of a picture with a given aspect ratio.
     *
     * <p>The region is the largest rectangle of aspect ratio {@code aspectWidth:aspectHeight} that
     * fits the picture (its long side spanning the picture, rounded down, at least 1 pixel), placed
     * as {@code strategy} decides:
     * <ul>
     *   <li>{@link SmartCropStrategy#CENTER} centers it;</li>
     *   <li>{@link SmartCropStrategy#ENTROPY} keeps the area with the most detail;</li>
     *   <li>{@link SmartCropStrategy#ATTENTION} keeps the area that edges, saturated color and
     *       skin tones draw the eye to, favoring the rule of thirds.</li>
     * </ul>
     * The analysis runs on a copy reduced to at most 256 pixels on its short side, so its cost is
     * independent of the picture's size. The result is deterministic.
     *
     * @param picture the picture to search
     * @param aspectWidth the width part of the aspect ratio; positive
     * @param aspectHeight the height part of the aspect ratio; positive
     * @param strategy how to choose the region
     * @return the region, inside the picture
     * @throws IllegalArgumentException if a part of the aspect ratio is not positive
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Region findSmartCrop(Picture picture, int aspectWidth, int aspectHeight, SmartCropStrategy strategy) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(strategy, "strategy");
            requirePositive(aspectWidth, aspectHeight);
            try {
                return SmartCrop.find(picture, aspectWidth, aspectHeight, strategy);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Reduces a picture to 8-bit samples.
     *
     * <p>8-bit pictures are returned unchanged. A {@link Yuv16Picture} becomes a
     * {@link YuvPicture} with the same layout and color description, each Y'CbCr sample
     * {@code v} of depth {@code d} rounded to nearest as {@code min((v + 2^(d - 9)) >> (d - 8), 255)}
     * (which maps limited range onto limited range exactly, and equals the output of the AV1 and
     * VP9 decoders' default 8-bit mode) and each alpha sample rescaled as
     * {@code round(v * 255 / (2^d - 1))}. An {@link Rgba16Picture} becomes an {@link RgbaPicture}
     * with every sample reduced as {@code (v * 255 + 32767) / 65535}, which equals the output of
     * the PNG decoder's default 8-bit mode.
     *
     * @param picture the picture to reduce
     * @return the picture itself if it has 8-bit storage, otherwise a new picture
     */
    public static Picture toEightBit(Picture picture) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            return switch (picture) {
                case RgbaPicture rgba -> rgba;
                case YuvPicture yuv -> yuv;
                case Rgba16Picture rgba -> toEightBit(rgba);
                case Yuv16Picture yuv -> toEightBit(yuv);
            };
        }
    }

    /**
     * Reduces a planar picture to 8-bit samples, as {@link #toEightBit(Picture)} describes.
     *
     * @param picture the picture to reduce
     * @return a new picture
     */
    public static YuvPicture toEightBit(Yuv16Picture picture) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            try {
                return HighDepth.toEightBit(picture);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Reduces a packed picture to 8-bit samples, as {@link #toEightBit(Picture)} describes.
     *
     * @param picture the picture to reduce
     * @return a new picture
     */
    public static RgbaPicture toEightBit(Rgba16Picture picture) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            try {
                return HighDepth.toEightBit(picture);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Widens an 8-bit planar picture to the given bit depth.
     *
     * <p>Each Y'CbCr sample {@code v} becomes {@code v << (bitDepth - 8)}, which maps limited range
     * onto limited range exactly, and each alpha sample is rescaled as
     * {@code round(v * (2^bitDepth - 1) / 255)}, so opaque stays opaque. Reducing the result with
     * {@link #toEightBit(Yuv16Picture)} restores the input exactly.
     *
     * @param picture the picture to widen
     * @param bitDepth the depth of the result; 8 to 16
     * @return a new picture
     * @throws IllegalArgumentException if {@code bitDepth} is outside 8 to 16
     */
    public static Yuv16Picture toBitDepth(YuvPicture picture, int bitDepth) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requireBitDepth(bitDepth);
            try {
                return HighDepth.toBitDepth(picture, bitDepth);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Converts a planar picture to another bit depth.
     *
     * <p>Y'CbCr samples are shifted left to gain bits and rounded to nearest to lose them,
     * {@code min((v + 2^(n - 1)) >> n, 2^bitDepth - 1)} for {@code n} lost bits; alpha samples are
     * rescaled as {@code round(v * (2^bitDepth - 1) / (2^d - 1))}.
     *
     * @param picture the picture to convert
     * @param bitDepth the depth of the result; 8 to 16
     * @return the picture itself if it already has this depth, otherwise a new picture
     * @throws IllegalArgumentException if {@code bitDepth} is outside 8 to 16
     */
    public static Yuv16Picture toBitDepth(Yuv16Picture picture, int bitDepth) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            requireBitDepth(bitDepth);
            if (picture.bitDepth() == bitDepth) {
                return picture;
            }
            try {
                return HighDepth.toBitDepth(picture, bitDepth);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Widens a packed 8-bit picture to 16-bit samples, each becoming {@code v * 257}, so that
     * {@code 255} becomes {@code 65535}. Reducing the result with
     * {@link #toEightBit(Rgba16Picture)} restores the input exactly.
     *
     * @param picture the picture to widen
     * @return a new picture
     */
    public static Rgba16Picture toSixteenBit(RgbaPicture picture) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            try {
                return HighDepth.toSixteenBit(picture);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Converts a picture from one color profile to another with the default options (perceptual
     * intent).
     *
     * @param picture the picture, whose values are in {@code source}
     * @param source the profile of the picture, RGB or gray
     * @param target the profile to convert to, RGB or gray
     * @return the converted picture, as {@link #convertColor(Picture, ColorTransform)} describes
     * @throws IllegalArgumentException if a profile is neither RGB nor gray
     * @throws MediaException.Malformed if a profile is damaged
     * @throws MediaException.Unsupported if a profile cannot be used as source or target, or the picture's matrix
     * coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     * @see ColorTransform
     */
    public static Picture convertColor(Picture picture, ColorProfile source, ColorProfile target) {
        return convertColor(picture, source, target, ColorTransformOptions.defaults());
    }

    /**
     * Converts a picture from one color profile to another.
     *
     * @param picture the picture, whose values are in {@code source}
     * @param source the profile of the picture, RGB or gray
     * @param target the profile to convert to, RGB or gray
     * @param options the rendering intent, black point compensation and tone mapping
     * @return the converted picture, as {@link #convertColor(Picture, ColorTransform)} describes
     * @throws IllegalArgumentException if a profile is neither RGB nor gray
     * @throws MediaException.Malformed if a profile is damaged
     * @throws MediaException.Unsupported if a profile cannot be used as source or target, or the picture's matrix
     * coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture convertColor(Picture picture, ColorProfile source, ColorProfile target,
                                       ColorTransformOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            return convertColor(picture, ColorTransform.create(source, target, options));
        }
    }

    /**
     * Applies a color transform to a picture.
     *
     * <p>The picture keeps its depth: an 8-bit picture becomes an {@link RgbaPicture}, a 16-bit one
     * an {@link Rgba16Picture}, and alpha is copied unchanged. Y'CbCr pictures are first converted
     * to R'G'B' with the matrix and range of their {@link ColorInfo} (16-bit ones in floating point,
     * keeping their precision). A gray source profile reads the red channel; a gray target profile
     * writes the same value to red, green and blue.
     *
     * @apiNote Create the transform once with {@link ColorTransform#create} and reuse it for every picture with the
     * same pair of profiles: creating one reads and links the profiles.
     * @param picture the picture, whose values are in the transform's source profile
     * @param transform the transform, between RGB or gray profiles
     * @return the converted picture
     * @throws IllegalArgumentException if a profile of the transform is neither RGB nor gray
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static Picture convertColor(Picture picture, ColorTransform transform) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(transform, "transform");
            try {
                return ManagedColor.convert(picture, transform);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Converts a picture to 8-bit sRGB with the default options.
     *
     * <p>A Y'CbCr picture is converted from the color its {@link ColorInfo} describes, through
     * {@link ColorProfile#of(ColorInfo)}: wide-gamut primaries such as BT.2020 or Display P3 are
     * mapped into sRGB, and PQ or HLG high-dynamic-range video is tone-mapped as
     * {@link com.github.auties00.smalt.image.color.ToneMapping#BT2390} describes. A picture whose
     * color is already sRGB (BT.709 primaries with an SDR transfer, the color of JPEG and of most
     * video) converts exactly as {@link #toRgba(Picture)} does. RGBA pictures carry no color
     * description and are taken as sRGB: they are only reduced to 8 bits; convert them with
     * {@link #convertColor(Picture, ColorProfile, ColorProfile)} or {@link #toSrgb(DecodedImage)}.
     *
     * @apiNote This is the conversion for thumbnails and previews of video frames, including HDR video.
     * @param picture the picture to convert
     * @return the sRGB picture; the picture itself if it is an {@link RgbaPicture}
     * @throws MediaException.Unsupported if the picture's color description is not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static RgbaPicture toSrgb(Picture picture) {
        return toSrgb(picture, ColorTransformOptions.defaults());
    }

    /**
     * Converts a picture to 8-bit sRGB, as {@link #toSrgb(Picture)} describes.
     *
     * @param picture the picture to convert
     * @param options the rendering intent, black point compensation and tone mapping
     * @return the sRGB picture; the picture itself if it is an {@link RgbaPicture}
     * @throws MediaException.Unsupported if the picture's color description is not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     */
    public static RgbaPicture toSrgb(Picture picture, ColorTransformOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(options, "options");
            try {
                return ManagedColor.toSrgb(picture, options);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Converts a decoded image to sRGB with the profile it declares and the default options.
     *
     * <p>The image is returned unchanged when {@link DecodedImage#colorProfile()} is empty or sRGB,
     * when the profile is neither RGB nor gray, or when it cannot be applied (damaged tags, an
     * unsupported profile class). Otherwise the result holds the converted picture, as
     * {@link #convertColor(Picture, ColorTransform)} returns it, an empty
     * {@link DecodedImage#iccProfile()} and {@link ColorProfile#srgb()} as its color profile;
     * the other components are kept.
     *
     * @param image the decoded image
     * @return the sRGB image, or {@code image} itself
     * @throws MediaException.Unsupported if the picture's matrix coefficients are not supported
     * @throws MediaException.LimitExceeded if the RGBA form of the picture does not fit one Java array
     * @see ImageDecoderOptions#convertToSrgb()
     */
    public static DecodedImage toSrgb(DecodedImage image) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(image, "image");
            try {
                return ManagedColor.toSrgb(image, ColorTransformOptions.defaults());
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Finds the content of a picture inside a uniform border.
     *
     * @param picture the picture to search
     * @param argb the border color as an unsigned {@code 0xAARRGGBB}, or {@code -1L} for the color of the top-left
     * pixel
     * @param tolerance the largest difference of a channel that still counts as border
     * @return the bounds, or an empty optional when every pixel belongs to the border
     */
    private static Optional<Region> findBounds(Picture picture, long argb, int tolerance) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            if (tolerance < 0 || tolerance > 255) {
                throw new IllegalArgumentException("tolerance " + tolerance + " is outside 0..255");
            }
            try {
                return Canvas.contentBounds(Canvas.toPacked(picture), argb, tolerance);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Checks that a bit depth is one a {@link Yuv16Picture} supports.
     *
     * @param bitDepth the depth
     * @throws IllegalArgumentException if {@code bitDepth} is outside 8 to 16
     */
    private static void requireBitDepth(int bitDepth) {
        if (bitDepth < 8 || bitDepth > 16) {
            throw new IllegalArgumentException("unsupported bit depth " + bitDepth);
        }
    }

    /**
     * Scales a side by the ratio {@code numerator / denominator}, rounding halves up, with a
     * minimum of 1.
     *
     * @param side the side to scale
     * @param numerator the numerator of the ratio
     * @param denominator the denominator of the ratio; positive
     * @return the scaled side
     */
    private static int scaleEdge(int side, int numerator, int denominator) {
        var scaled = (2L * side * numerator + denominator) / (2L * denominator);
        return (int) Math.max(1, scaled);
    }

    /**
     * Resamples a rectangle of a picture to a new size.
     *
     * @param picture the picture to resample
     * @param x the rectangle's left edge
     * @param y the rectangle's top edge
     * @param width the rectangle's width
     * @param height the rectangle's height
     * @param outWidth the output width
     * @param outHeight the output height
     * @param filter the resampling filter
     * @return the resampled picture
     */
    private static Picture resample(Picture picture, int x, int y, int width, int height, int outWidth, int outHeight,
                                    ResizeFilter filter) {
        var windowX = new Resampler.Window(x, width, 1);
        var windowY = new Resampler.Window(y, height, 1);
        return switch (picture) {
            // TODO: resample 16-bit pictures at their own depth instead of reducing them first
            case Rgba16Picture rgba -> resample(HighDepth.toEightBit(rgba), x, y, width, height, outWidth, outHeight, filter);
            case Yuv16Picture yuv -> resample(HighDepth.toEightBit(yuv), x, y, width, height, outWidth, outHeight, filter);
            case RgbaPicture rgba -> {
                OpChecks.checkArray(4L * outWidth * outHeight);
                var out = RgbaPicture.allocate(outWidth, outHeight, rgba.hasAlpha());
                var premultiplied = rgba.hasAlpha() && !Geometry.isOpaque(rgba);
                Resampler.rgba(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height(), windowX, windowY,
                        out.pixels(), out.stride(), outWidth, outHeight, filter, premultiplied, !rgba.hasAlpha());
                yield out;
            }
            case YuvPicture yuv -> {
                var format = yuv.chromaFormat();
                var out = YuvPicture.allocate(outWidth, outHeight, format, yuv.hasAlpha(), yuv.color());
                Resampler.plane(yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), windowX, windowY,
                        out.y(), out.yStride(), outWidth, outHeight, filter);
                if (format.hasChroma()) {
                    var chromaWidth = out.chromaWidth();
                    var chromaHeight = out.chromaHeight();
                    var chromaX = chromaWindow(windowX, format.shiftX(), outWidth, chromaWidth);
                    var chromaY = chromaWindow(windowY, format.shiftY(), outHeight, chromaHeight);
                    Resampler.plane(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), chromaX, chromaY,
                            out.u(), out.uvStride(), chromaWidth, chromaHeight, filter);
                    Resampler.plane(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), chromaX, chromaY,
                            out.v(), out.uvStride(), chromaWidth, chromaHeight, filter);
                }
                if (yuv.hasAlpha()) {
                    Resampler.plane(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height(), windowX, windowY,
                            out.alpha(), out.alphaStride(), outWidth, outHeight, filter);
                }
                yield out;
            }
        };
    }

    /**
     * Returns the chroma window whose output samples sit exactly at the positions of the luma
     * pixels they cover.
     *
     * <p>A chroma output sample spans {@code 2^shift} output pixels, so it spans
     * {@code length / outputLuma} source chroma samples; the window is that span times the
     * number of chroma output samples, which exceeds half the luma window by up to one sample
     * when the output size is odd.
     *
     * @param luma the luma window
     * @param shift the binary logarithm of the chroma subsampling factor
     * @param outputLuma the number of output pixels along the axis
     * @param outputChroma the number of output chroma samples along the axis
     * @return the chroma window
     */
    private static Resampler.Window chromaWindow(Resampler.Window luma, int shift, int outputLuma, int outputChroma) {
        return new Resampler.Window(
                luma.offsetNumerator() * outputLuma,
                luma.lengthNumerator() * outputChroma << shift,
                luma.denominator() * outputLuma << shift);
    }

    /**
     * Converts a planar picture to packed RGBA.
     *
     * @param yuv the picture
     * @param upsampling how to reconstruct subsampled chroma
     * @param buffers the source of the result
     * @return the RGBA picture
     */
    private static RgbaPicture yuvToRgba(YuvPicture yuv, ChromaUpsampling upsampling, PictureBuffers buffers) {
        var width = yuv.width();
        var height = yuv.height();
        OpChecks.checkArray(4L * width * height);
        var matrix = ColorMatrix.of(yuv.color());
        var out = buffers.rgba(width, height, yuv.hasAlpha());
        var format = yuv.chromaFormat();
        int mode;
        if (!format.hasChroma()) {
            mode = -1;
        } else if (format.shiftX() == 0) {
            mode = ColorKernel.CHROMA_FULL;
        } else if (upsampling == ChromaUpsampling.NEAREST) {
            mode = ColorKernel.CHROMA_NEAREST;
        } else {
            mode = format.shiftY() == 1 ? ColorKernel.CHROMA_FANCY_HV : ColorKernel.CHROMA_FANCY_H;
        }
        var bands = ParallelBands.bands(height, COLOR_WORK * width * height);
        ParallelBands.run(bands, band -> yuvToRgbaRows(yuv, mode, matrix, out, ParallelBands.start(band, bands, height),
                ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Converts the rows {@code [top, bottom)} of a planar picture to packed RGBA.
     *
     * @param yuv the picture
     * @param mode the chroma sampling mode of {@link ColorKernel}, or {@code -1} for a monochrome picture
     * @param matrix the conversion coefficients
     * @param out the RGBA destination
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void yuvToRgbaRows(YuvPicture yuv, int mode, ColorMatrix matrix, RgbaPicture out, int top, int bottom) {
        var kernel = ColorKernel.INSTANCE;
        var width = yuv.width();
        var pixels = out.pixels();
        var alpha = yuv.alpha();
        if (mode < 0) {
            for (var row = top; row < bottom; row++) {
                kernel.grayToRgba(yuv.y(), row * yuv.yStride(), alpha, row * yuv.alphaStride(), pixels, row * out.stride(), width, matrix);
            }
            return;
        }
        var shiftY = yuv.chromaFormat().shiftY();
        var chromaHeight = yuv.chromaHeight();
        for (var row = top; row < bottom; row++) {
            var near = row >> shiftY;
            var far = near;
            if (mode == ColorKernel.CHROMA_FANCY_HV) {
                far = (row & 1) == 0 ? Math.max(near - 1, 0) : Math.min(near + 1, chromaHeight - 1);
            }
            kernel.yuvToRgba(yuv.y(), row * yuv.yStride(), yuv.u(), yuv.v(), near * yuv.uvStride(), far * yuv.uvStride(),
                    alpha, row * yuv.alphaStride(), pixels, row * out.stride(), width, mode, matrix);
        }
    }

    /**
     * Converts a packed RGBA picture to planar Y'CbCr.
     *
     * @param rgba the picture
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param downsampling how to average covered pixels
     * @param buffers the source of the result
     * @return the planar picture
     */
    private static YuvPicture rgbaToYuv(RgbaPicture rgba, ChromaFormat format, ColorInfo color,
                                        ChromaDownsampling downsampling, PictureBuffers buffers) {
        var width = rgba.width();
        var height = rgba.height();
        var matrix = ColorMatrix.of(color);
        var out = buffers.yuv(width, height, format, rgba.hasAlpha(), color);
        var weighted = downsampling == ChromaDownsampling.ALPHA_WEIGHTED && rgba.hasAlpha();
        var groups = format.hasChroma() ? out.chromaHeight() : height;
        var bands = ParallelBands.bands(groups, COLOR_WORK * width * height);
        ParallelBands.run(bands, band -> rgbaToYuvRows(rgba, matrix, weighted, out, ParallelBands.start(band, bands, groups),
                ParallelBands.start(band + 1, bands, groups)));
        return out;
    }

    /**
     * Converts the row groups {@code [first, last)} of a packed RGBA picture to planar Y'CbCr: the
     * rows of chroma rows {@code [first, last)} of a picture with chroma, the rows themselves of a
     * monochrome one.
     *
     * @param rgba the picture
     * @param matrix the conversion coefficients
     * @param weighted whether to weight chroma by alpha
     * @param out the planar destination
     * @param first the first row group
     * @param last the row group after the last one
     */
    private static void rgbaToYuvRows(RgbaPicture rgba, ColorMatrix matrix, boolean weighted, YuvPicture out, int first, int last) {
        var kernel = ColorKernel.INSTANCE;
        var width = rgba.width();
        var height = rgba.height();
        var format = out.chromaFormat();
        var pixels = rgba.pixels();
        var stride = rgba.stride();
        var shiftY = format.hasChroma() ? format.shiftY() : 0;
        var lumaEnd = Math.min(height, last << shiftY);
        for (var row = first << shiftY; row < lumaEnd; row++) {
            kernel.rgbaToLuma(pixels, row * stride, width, out.y(), row * out.yStride(), out.alpha(), row * out.alphaStride(), matrix);
        }
        if (!format.hasChroma()) {
            return;
        }
        var pairX = format.shiftX() == 1;
        var pairY = format.shiftY() == 1;
        for (var chromaRow = first; chromaRow < last; chromaRow++) {
            var top = chromaRow << format.shiftY();
            var bottom = pairY ? Math.min(top + 1, height - 1) : top;
            if (weighted) {
                AlphaWeighting.rgbaToChroma(pixels, top * stride, bottom * stride, width, pairX, pairY,
                        out.u(), out.v(), chromaRow * out.uvStride(), matrix);
            } else {
                kernel.rgbaToChroma(pixels, top * stride, bottom * stride, width, pairX, pairY,
                        out.u(), out.v(), chromaRow * out.uvStride(), matrix);
            }
        }
    }

    /**
     * Converts a planar picture to another layout or color description.
     *
     * @param yuv the picture
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param downsampling how to average covered samples
     * @param buffers the source of the result and of the intermediate RGBA picture
     * @return the converted picture, or the input when nothing changes
     */
    private static YuvPicture yuvToYuv(YuvPicture yuv, ChromaFormat format, ColorInfo color,
                                       ChromaDownsampling downsampling, PictureBuffers buffers) {
        if (yuv.chromaFormat() == format && yuv.color().equals(color)) {
            return yuv;
        }
        var source = ColorMatrix.of(yuv.color());
        var target = ColorMatrix.of(color);
        var identityInvolved = source.matrix == MatrixCoefficients.IDENTITY || target.matrix == MatrixCoefficients.IDENTITY;
        var monochromeInvolved = !yuv.chromaFormat().hasChroma() || !format.hasChroma();
        if (source.matrix == target.matrix && !(identityInvolved && monochromeInvolved)) {
            return PlaneConversion.convert(yuv, format, color, source, target, downsampling, buffers);
        }
        if (yuv.chromaFormat() == format && format.hasChroma() && MatrixConversion.applies(source, target)) {
            return MatrixConversion.convert(yuv, color, source, target, buffers);
        }
        return rgbaToYuv(yuvToRgba(yuv, ChromaUpsampling.TRIANGLE, buffers), format, color, downsampling, buffers);
    }

    /**
     * Checks that output dimensions are positive.
     *
     * @param width the output width
     * @param height the output height
     * @throws IllegalArgumentException if a dimension is not positive
     */
    private static void requirePositive(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid picture size " + width + "x" + height);
        }
    }
}
