package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Objects;

/**
 * Composites pictures over one another: alpha compositing with blend modes, and watermarks.
 *
 * <p>Both pictures are converted to packed RGBA first ({@link YuvPicture}s with
 * {@link ImageOps#toRgba(Picture)}), and the result is an {@link RgbaPicture}, or an
 * {@link Rgba16Picture} when either input is 16-bit (the other is widened), with the size and
 * alpha flag of the backdrop. Inputs are never modified, the result does not depend on the number of
 * threads that compute it, and results follow the limits rule of {@link ImageOps}. This class is
 * thread-safe.
 *
 * <p>Compositing follows the W3C Compositing and Blending Level 1 model with straight (not
 * premultiplied) alpha in and out: with backdrop color {@code cb} and alpha {@code ab}, overlay
 * color {@code cs} and alpha {@code as} (already multiplied by the opacity), and the blend mode's
 * function {@code B}, the overlay color is first mixed with the blended color,
 * {@code cs' = (1 - ab) * cs + ab * B(cb, cs)}, then composited source-over: the result alpha is
 * {@code ao = as + ab * (1 - as)} and the result color
 * {@code (as * cs' + ab * (1 - as) * cb) / ao}. This is the premultiplied source-over operator
 * expressed on straight colors, so transparent pixels never tint the result. Values are computed
 * in single-precision floats normalized to {@code [0, 1]} and rounded to the result's depth.
 *
 * @implNote This implementation runs the blend modes and the source-over operator in a kernel with identical scalar and
 * Vector API twins, on planar rows of floats.
 */
public final class ImageCompositing {
    /**
     * Prevents instantiation.
     */
    private ImageCompositing() {
        throw new AssertionError();
    }

    /**
     * Composites an overlay over a backdrop with {@link BlendMode#NORMAL} at full opacity.
     *
     * @param base the backdrop, which sizes the result
     * @param overlay the picture composited over {@code base}
     * @param x the backdrop column of the overlay's left edge; may be negative
     * @param y the backdrop row of the overlay's top edge; may be negative
     * @return the composited picture, as {@link #composite(Picture, Picture, int, int, CompositeOptions)} describes
     * @throws MediaException.LimitExceeded if the RGBA form of a picture does not fit one Java array
     */
    public static Picture composite(Picture base, Picture overlay, int x, int y) {
        return composite(base, overlay, x, y, CompositeOptions.defaults());
    }

    /**
     * Composites an overlay over a backdrop.
     *
     * <p>The overlay's top-left corner goes to {@code (x, y)} of the backdrop; only the part that
     * overlaps the backdrop is composited, and the rest of the backdrop is copied. Where the
     * backdrop is opaque the result is opaque; where the overlay is transparent, or the opacity is
     * zero, the backdrop shows unchanged.
     *
     * @param base the backdrop, which sizes the result
     * @param overlay the picture composited over {@code base}
     * @param x the backdrop column of the overlay's left edge; may be negative
     * @param y the backdrop row of the overlay's top edge; may be negative
     * @param options the blend mode, opacity and linear-light option
     * @return a new picture with the size of the backdrop
     * @throws MediaException.LimitExceeded if the RGBA form of a picture does not fit one Java array
     */
    public static Picture composite(Picture base, Picture overlay, int x, int y, CompositeOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(overlay, "overlay");
            Objects.requireNonNull(options, "options");
            try {
                return Blender.composite(Canvas.toPacked(base), Canvas.toPacked(overlay), x, y, options);
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }

    /**
     * Places a watermark on a picture.
     *
     * <p>When the options give a {@link WatermarkOptions#scale() scale}, the mark is first resized
     * so that its width is that fraction of the picture's width (rounded, at least 1 pixel),
     * keeping its aspect ratio, with the options' filter, at its depth; a mark with transparency is
     * resized in premultiplied alpha. It is then anchored as {@link Anchor} describes, keeping the margins
     * from the anchored edges, and composited as {@link #composite(Picture, Picture, int, int, CompositeOptions)}
     * does with the options' blending and opacity. A mark larger than the picture is clipped.
     *
     * @param picture the picture to mark
     * @param mark the watermark, typically with transparency
     * @param options the placement, size and blending
     * @return a new picture with the size of {@code picture}
     * @throws MediaException.LimitExceeded if the resized mark is larger than the mark and exceeds the default
     * limits, or the RGBA form of a picture does not fit one Java array
     */
    public static Picture watermark(Picture picture, Picture mark, WatermarkOptions options) {
        try (var _ = ParallelBands.operation()) {
            Objects.requireNonNull(picture, "picture");
            Objects.requireNonNull(mark, "mark");
            Objects.requireNonNull(options, "options");
            try {
                var overlay = Canvas.toPacked(mark);
                if (options.scale() > 0) {
                    var width = (int) Math.max(1, Math.round(picture.width() * options.scale()));
                    var height = (int) Math.max(1, Math.round((double) width * overlay.height() / overlay.width()));
                    overlay = ImageOps.resize(overlay, width, height, options.filter());
                }
                var x = options.anchor().x(picture.width(), overlay.width(), options.marginX());
                var y = options.anchor().y(picture.height(), overlay.height(), options.marginY());
                return Blender.composite(Canvas.toPacked(picture), overlay, x, y, options.composite());
            } catch (RuntimeException exception) {
                throw OpChecks.translate(exception);
            }
        }
    }
}
