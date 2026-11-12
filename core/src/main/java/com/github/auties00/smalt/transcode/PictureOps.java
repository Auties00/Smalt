package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.track.Rational;

import java.math.BigInteger;

/**
 * Applies the geometric and pixel-format operations of a transcode to each picture of a stream.
 *
 * <p>The operations are planned once from the coded size of the stream: the crop region and the
 * target box are given in displayed coordinates, and are mapped to coded coordinates when the
 * orientation is kept as metadata, so the pictures are never turned. Each picture is then cropped,
 * oriented (when the orientation is applied), resampled, cropped to the box (for
 * {@link ScaleMode#FILL}), flattened when it has transparency and a background is set, and
 * converted to the requested chroma format and color description.
 *
 * <p>A stream of non-square samples either has its samples made square, the box then fitting the
 * displayed shape, or keeps them: the box then applies to the stored samples and the output
 * declares the sample aspect ratio that keeps the displayed shape after any uneven scaling.
 * Square samples stay square whatever the scaling.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class PictureOps {
    /**
     * Plans the scaling of a displayed picture, as the transcode options do.
     */
    @FunctionalInterface
    interface SizePlanner {
        /**
         * Plans the scaling of a displayed picture.
         *
         * @param displayWidth the displayed width, after any crop
         * @param displayHeight the displayed height, after any crop
         * @return the plan
         */
        SizePlan plan(int displayWidth, int displayHeight);
    }

    /**
     * A rectangle of a picture, in pixels.
     *
     * @param x the left edge
     * @param y the top edge
     * @param width the number of columns
     * @param height the number of rows
     */
    record Rect(int x, int y, int width, int height) {
    }

    /**
     * The orientation applied to the pixels, or {@link Orientation#NORMAL} when it is kept as
     * metadata.
     */
    private final Orientation applied;

    /**
     * The orientation the output records.
     */
    private final Orientation output;

    /**
     * The coded crop applied first, or {@code null} for none.
     */
    private final Rect crop;

    /**
     * The width the picture is resampled to, in the picture's own (possibly oriented)
     * coordinates.
     */
    private final int scaledWidth;

    private final int scaledHeight;

    /**
     * The crop applied after resampling, or {@code null} for none.
     */
    private final Rect boxCrop;

    private final ResizeFilter filter;

    /**
     * The background transparent pixels are flattened on, as {@code 0xRRGGBB}, or {@code -1} to
     * keep transparency.
     */
    private final int background;

    /**
     * The chroma format of the output, or {@code null} to keep the picture's representation.
     */
    private final ChromaFormat chromaFormat;

    /**
     * The color description of the output, or {@code null} to keep the picture's.
     */
    private final ColorInfo color;

    /**
     * The coded width of the output.
     */
    private final int outputWidth;

    /**
     * The coded height of the output.
     */
    private final int outputHeight;

    /**
     * The sample aspect ratio the output declares in its coded orientation, or {@code null} for none.
     */
    private final Rational outputAspect;

    private PictureOps(Orientation applied, Orientation output, Rect crop, int scaledWidth, int scaledHeight, Rect boxCrop,
                       ResizeFilter filter, int background, ChromaFormat chromaFormat, ColorInfo color, int outputWidth,
                       int outputHeight, Rational outputAspect) {
        this.applied = applied;
        this.output = output;
        this.crop = crop;
        this.scaledWidth = scaledWidth;
        this.scaledHeight = scaledHeight;
        this.boxCrop = boxCrop;
        this.filter = filter;
        this.background = background;
        this.chromaFormat = chromaFormat;
        this.color = color;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
        this.outputAspect = outputAspect;
    }

    /**
     * Plans the operations for a stream.
     *
     * @param codedWidth the coded width of the pictures
     * @param codedHeight the coded height of the pictures
     * @param orientation the display orientation of the stream
     * @param apply whether the orientation is applied to the pixels rather than kept
     * @param region the displayed region kept, or {@code null} for the whole picture
     * @param sizing plans the scaling of the displayed (cropped) picture
     * @param filter the resampling filter
     * @param background the flattening background as {@code 0xRRGGBB}, or {@code -1} to keep transparency
     * @param chromaFormat the output chroma format, or {@code null} to keep the picture's representation
     * @param color the output color description, or {@code null} to keep the picture's
     * @return the operations
     * @throws IllegalArgumentException if the region is not inside the displayed picture
     */
    static PictureOps plan(int codedWidth, int codedHeight, Orientation orientation, boolean apply, Region region,
                           SizePlanner sizing, ResizeFilter filter, int background, ChromaFormat chromaFormat,
                           ColorInfo color) {
        return plan(codedWidth, codedHeight, orientation, apply, region, sizing, filter, background, chromaFormat, color,
                null, false);
    }

    /**
     * Plans the operations for a stream of possibly non-square samples.
     *
     * <p>The region is given in displayed orientation and stored samples. When the sample aspect
     * ratio is applied, the box is fitted to the cropped picture stretched to square samples and
     * the output declares none; otherwise the box applies to the stored samples and the output
     * declares the ratio scaled by the uneven scaling of the plan (a square ratio stays square).
     *
     * @param codedWidth the coded width of the pictures
     * @param codedHeight the coded height of the pictures
     * @param orientation the display orientation of the stream
     * @param apply whether the orientation is applied to the pixels rather than kept
     * @param region the displayed region kept, or {@code null} for the whole picture
     * @param sizing plans the scaling of the displayed (cropped) picture
     * @param filter the resampling filter
     * @param background the flattening background as {@code 0xRRGGBB}, or {@code -1} to keep transparency
     * @param chromaFormat the output chroma format, or {@code null} to keep the picture's representation
     * @param color the output color description, or {@code null} to keep the picture's
     * @param sampleAspect the sample aspect ratio of the stream in coded orientation, or {@code null} when
     * undeclared
     * @param applyAspect whether the samples are made square rather than the ratio kept
     * @return the operations
     * @throws IllegalArgumentException if the region is not inside the displayed picture
     */
    static PictureOps plan(int codedWidth, int codedHeight, Orientation orientation, boolean apply, Region region,
                           SizePlanner sizing, ResizeFilter filter, int background, ChromaFormat chromaFormat,
                           ColorInfo color, Rational sampleAspect, boolean applyAspect) {
        var swaps = orientation.swapsDimensions();
        var displayWidth = swaps ? codedHeight : codedWidth;
        var displayHeight = swaps ? codedWidth : codedHeight;
        Rect crop = null;
        var croppedWidth = displayWidth;
        var croppedHeight = displayHeight;
        if (region != null) {
            if (!region.fitsIn(displayWidth, displayHeight)) {
                throw new IllegalArgumentException("crop region " + region + " is outside the " + displayWidth + "x"
                        + displayHeight + " picture");
            }
            if (region.x() != 0 || region.y() != 0 || region.width() != displayWidth || region.height() != displayHeight) {
                crop = toCoded(region.x(), region.y(), region.width(), region.height(), orientation, displayWidth, displayHeight);
            }
            croppedWidth = region.width();
            croppedHeight = region.height();
        }
        var displayAspect = sampleAspect == null ? null : swaps ? sampleAspect.invert() : sampleAspect;
        var stretch = applyAspect && displayAspect != null && displayAspect.numerator() != displayAspect.denominator();
        var plan = stretch ? sizing.plan(stretched(croppedWidth, displayAspect), croppedHeight)
                : sizing.plan(croppedWidth, croppedHeight);
        Rational outputAspect = null;
        if (displayAspect != null && !applyAspect) {
            outputAspect = displayAspect.numerator() == displayAspect.denominator() ? displayAspect
                    : scaled(displayAspect, croppedWidth, croppedHeight, plan.scaledWidth(), plan.scaledHeight());
        }
        int scaledWidth;
        int scaledHeight;
        Rect boxCrop = null;
        var hasBoxCrop = plan.cropX() != 0 || plan.cropY() != 0 || plan.outputWidth() != plan.scaledWidth()
                || plan.outputHeight() != plan.scaledHeight();
        Orientation applied;
        Orientation recorded;
        int outputWidth;
        int outputHeight;
        if (apply || orientation == Orientation.NORMAL) {
            applied = orientation;
            recorded = Orientation.NORMAL;
            scaledWidth = plan.scaledWidth();
            scaledHeight = plan.scaledHeight();
            if (hasBoxCrop) {
                boxCrop = new Rect(plan.cropX(), plan.cropY(), plan.outputWidth(), plan.outputHeight());
            }
            outputWidth = plan.outputWidth();
            outputHeight = plan.outputHeight();
        } else {
            applied = Orientation.NORMAL;
            recorded = orientation;
            scaledWidth = swaps ? plan.scaledHeight() : plan.scaledWidth();
            scaledHeight = swaps ? plan.scaledWidth() : plan.scaledHeight();
            if (hasBoxCrop) {
                boxCrop = toCoded(plan.cropX(), plan.cropY(), plan.outputWidth(), plan.outputHeight(), orientation,
                        plan.scaledWidth(), plan.scaledHeight());
            }
            outputWidth = swaps ? plan.outputHeight() : plan.outputWidth();
            outputHeight = swaps ? plan.outputWidth() : plan.outputHeight();
            if (outputAspect != null && swaps) {
                outputAspect = outputAspect.invert();
            }
        }
        return new PictureOps(applied, recorded, crop, scaledWidth, scaledHeight, boxCrop, filter, background, chromaFormat,
                color, outputWidth, outputHeight, outputAspect);
    }

    /**
     * {@return the width of a picture stretched by a sample aspect ratio, rounded to nearest and at
     * least 1}
     *
     * @param width the width in samples
     * @param aspect the sample aspect ratio
     */
    static int stretched(int width, Rational aspect) {
        return (int) Math.clamp(Math.round(width * (double) aspect.numerator() / aspect.denominator()), 1,
                Integer.MAX_VALUE);
    }

    /**
     * Returns the sample aspect ratio that keeps the displayed shape of a picture resampled from one
     * size to another: {@code aspect * (width * scaledHeight) / (height * scaledWidth)}.
     *
     * @param aspect the sample aspect ratio before resampling
     * @param width the width before resampling
     * @param height the height before resampling
     * @param scaledWidth the width after resampling
     * @param scaledHeight the height after resampling
     * @return the ratio in lowest terms, or {@code null} when its terms do not fit in a {@code long}
     */
    static Rational scaled(Rational aspect, int width, int height, int scaledWidth, int scaledHeight) {
        var numerator = BigInteger.valueOf(aspect.numerator()).multiply(BigInteger.valueOf(width))
                .multiply(BigInteger.valueOf(scaledHeight));
        var denominator = BigInteger.valueOf(aspect.denominator()).multiply(BigInteger.valueOf(height))
                .multiply(BigInteger.valueOf(scaledWidth));
        var gcd = numerator.gcd(denominator);
        numerator = numerator.divide(gcd);
        denominator = denominator.divide(gcd);
        if (numerator.bitLength() > 63 || denominator.bitLength() > 63) {
            return null;
        }
        return new Rational(numerator.longValue(), denominator.longValue());
    }

    /**
     * Maps a rectangle of a displayed picture to the stored picture it is displayed from.
     *
     * @param x the left edge in displayed coordinates
     * @param y the top edge in displayed coordinates
     * @param width the displayed width of the rectangle
     * @param height the displayed height of the rectangle
     * @param orientation the transform that displays the stored picture
     * @param displayWidth the width of the displayed picture
     * @param displayHeight the height of the displayed picture
     * @return the stored rectangle
     */
    static Rect toCoded(int x, int y, int width, int height, Orientation orientation, int displayWidth, int displayHeight) {
        var swaps = orientation.swapsDimensions();
        var codedWidth = swaps ? displayHeight : displayWidth;
        var codedHeight = swaps ? displayWidth : displayHeight;
        var first = toCodedPoint(x, y, orientation, codedWidth, codedHeight);
        var last = toCodedPoint(x + width - 1, y + height - 1, orientation, codedWidth, codedHeight);
        var left = Math.min(first[0], last[0]);
        var top = Math.min(first[1], last[1]);
        return new Rect(left, top, Math.abs(first[0] - last[0]) + 1, Math.abs(first[1] - last[1]) + 1);
    }

    /**
     * Maps a displayed pixel to the stored pixel it is displayed from.
     *
     * @param x the displayed column
     * @param y the displayed row
     * @param orientation the transform that displays the stored picture
     * @param codedWidth the stored width
     * @param codedHeight the stored height
     * @return the stored pixel as {@code {x, y}}
     */
    private static int[] toCodedPoint(int x, int y, Orientation orientation, int codedWidth, int codedHeight) {
        return switch (orientation) {
            case NORMAL -> new int[]{x, y};
            case FLIP_HORIZONTAL -> new int[]{codedWidth - 1 - x, y};
            case ROTATE_180 -> new int[]{codedWidth - 1 - x, codedHeight - 1 - y};
            case FLIP_VERTICAL -> new int[]{x, codedHeight - 1 - y};
            case TRANSPOSE -> new int[]{y, x};
            case ROTATE_90 -> new int[]{y, codedHeight - 1 - x};
            case TRANSVERSE -> new int[]{codedWidth - 1 - y, codedHeight - 1 - x};
            case ROTATE_270 -> new int[]{codedWidth - 1 - y, x};
        };
    }

    /**
     * {@return the width pictures are resampled to before the box crop, in pixels}
     */
    int resampledWidth() {
        return scaledWidth;
    }

    /**
     * {@return the height pictures are resampled to before the box crop, in pixels}
     */
    int resampledHeight() {
        return scaledHeight;
    }

    /**
     * {@return the coded width of the output pictures, in pixels}
     */
    int outputWidth() {
        return outputWidth;
    }

    /**
     * {@return the coded height of the output pictures, in pixels}
     */
    int outputHeight() {
        return outputHeight;
    }

    /**
     * {@return the orientation the output records, {@link Orientation#NORMAL} when it was applied
     * to the pixels}
     */
    Orientation outputOrientation() {
        return output;
    }

    /**
     * {@return the sample aspect ratio the output declares in its coded orientation, or
     * {@code null} when it declares none}
     */
    Rational outputSampleAspect() {
        return outputAspect;
    }

    /**
     * Applies the operations to one picture.
     *
     * @param picture the picture, of the coded size the operations were planned for
     * @return the processed picture, which is {@code picture} itself when no operation changes it
     */
    Picture apply(Picture picture) {
        var result = picture;
        if (crop != null) {
            result = ImageOps.crop(result, crop.x(), crop.y(), crop.width(), crop.height());
        }
        if (applied != Orientation.NORMAL) {
            result = ImageOps.orient(result, applied);
        }
        result = ImageOps.resize(result, scaledWidth, scaledHeight, filter);
        if (boxCrop != null) {
            result = ImageOps.crop(result, boxCrop.x(), boxCrop.y(), boxCrop.width(), boxCrop.height());
        }
        if (background >= 0 && result.hasAlpha() && !ImageOps.isOpaque(result)) {
            result = ImageOps.flatten(result, background);
        }
        if (chromaFormat != null || color != null) {
            var format = chromaFormat != null ? chromaFormat : currentFormat(result);
            var target = color != null ? color : currentColor(result);
            if (!(result instanceof YuvPicture yuv) || yuv.chromaFormat() != format || !yuv.color().equals(target)) {
                result = ImageOps.toYuv(result, format, target);
            }
        }
        return result;
    }

    /**
     * {@return the chroma format a picture keeps when only its color description changes: its own,
     * or 4:2:0 for an RGBA picture}
     *
     * @param picture the picture to inspect
     */
    private static ChromaFormat currentFormat(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat();
            case Yuv16Picture yuv -> yuv.chromaFormat();
            case RgbaPicture _, Rgba16Picture _, ChannelPicture _ -> ChromaFormat.YUV420;
        };
    }

    /**
     * {@return the color description a picture keeps when only its chroma format changes: its own,
     * or BT.709 limited range for an RGBA picture}
     *
     * @param picture the picture to inspect
     */
    private static ColorInfo currentColor(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            case RgbaPicture _, Rgba16Picture _, ChannelPicture _ -> ColorInfo.BT709_LIMITED;
        };
    }
}
