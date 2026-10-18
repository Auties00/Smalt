package com.github.auties00.smalt.internal.resize;

import com.github.auties00.smalt.transcode.ScaleMode;

/**
 * Describes how a displayed picture is resized to the output size: it is resampled to the scaled
 * size, then the output rectangle at the crop offset is kept.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param scaledWidth the width the picture is resampled to, in pixels
 * @param scaledHeight the height the picture is resampled to, in pixels
 * @param cropX the left edge of the kept rectangle in the scaled picture
 * @param cropY the top edge of the kept rectangle in the scaled picture
 * @param outputWidth the output width, in pixels
 * @param outputHeight the output height, in pixels
 */
public record SizePlan(int scaledWidth, int scaledHeight, int cropX, int cropY, int outputWidth, int outputHeight) {
    /**
     * Plans the resizing of a picture.
     *
     * <p>The box is applied first (see {@link ScaleMode}), then the edge limits scale the result
     * down with its aspect ratio kept, then the output dimensions are rounded down to even
     * numbers when requested (never below 2). Every dimension is at least 1.
     *
     * @param width the displayed width, positive
     * @param height the displayed height, positive
     * @param boxWidth the box width, or 0 when free
     * @param boxHeight the box height, or 0 when free
     * @param mode how the picture is fitted to the box
     * @param upscale whether {@link ScaleMode#FIT} may enlarge
     * @param maxLongEdge the long-edge limit, or 0 for none
     * @param maxShortEdge the short-edge limit, or 0 for none
     * @param evenDimensions whether the output dimensions are made even
     * @return the plan
     */
    public static SizePlan of(int width, int height, int boxWidth, int boxHeight, ScaleMode mode, boolean upscale,
                       int maxLongEdge, int maxShortEdge, boolean evenDimensions) {
        double scaledW;
        double scaledH;
        double outW;
        double outH;
        switch (mode) {
            case FILL -> {
                outW = boxWidth > 0 ? boxWidth : width;
                outH = boxHeight > 0 ? boxHeight : height;
                var factor = Math.max(outW / width, outH / height);
                scaledW = width * factor;
                scaledH = height * factor;
            }
            case EXACT -> {
                outW = boxWidth > 0 ? boxWidth : width;
                outH = boxHeight > 0 ? boxHeight : height;
                scaledW = outW;
                scaledH = outH;
            }
            default -> {
                var factor = Math.min(boxWidth > 0 ? (double) boxWidth / width : Double.POSITIVE_INFINITY,
                        boxHeight > 0 ? (double) boxHeight / height : Double.POSITIVE_INFINITY);
                if (Double.isInfinite(factor) || (!upscale && factor > 1)) {
                    factor = 1;
                }
                scaledW = width * factor;
                scaledH = height * factor;
                outW = scaledW;
                outH = scaledH;
            }
        }
        var limit = 1.0;
        var longEdge = Math.max(outW, outH);
        var shortEdge = Math.min(outW, outH);
        if (maxLongEdge > 0 && longEdge > maxLongEdge) {
            limit = Math.min(limit, maxLongEdge / longEdge);
        }
        if (maxShortEdge > 0 && shortEdge > maxShortEdge) {
            limit = Math.min(limit, maxShortEdge / shortEdge);
        }
        scaledW *= limit;
        scaledH *= limit;
        outW *= limit;
        outH *= limit;
        var finalW = Math.max(1, (int) Math.round(outW));
        var finalH = Math.max(1, (int) Math.round(outH));
        if (evenDimensions) {
            finalW = Math.max(2, finalW & ~1);
            finalH = Math.max(2, finalH & ~1);
        }
        int resampledW;
        int resampledH;
        if (mode == ScaleMode.FILL) {
            resampledW = Math.max(finalW, (int) Math.round(scaledW));
            resampledH = Math.max(finalH, (int) Math.round(scaledH));
        } else {
            resampledW = finalW;
            resampledH = finalH;
        }
        return new SizePlan(resampledW, resampledH, (resampledW - finalW) / 2, (resampledH - finalH) / 2, finalW, finalH);
    }

    /**
     * {@return whether the plan leaves a picture of a size unchanged: nothing is resampled or
     * cropped}
     *
     * @param width the picture width
     * @param height the picture height
     */
    public boolean isIdentity(int width, int height) {
        return scaledWidth == width && scaledHeight == height && outputWidth == width && outputHeight == height;
    }
}
