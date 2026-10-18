package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.internal.resize.SizePlan;

/**
 * Holds the geometry settings that {@link ImageTranscodeOptions} and {@link VideoTranscodeOptions}
 * share: the target box and how the picture is fitted to it, the edge limits, the resampling
 * filter and the crop.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param width the box width, or 0 when free
 * @param height the box height, or 0 when free
 * @param scaleMode how the picture is fitted to the box
 * @param upscale whether {@link ScaleMode#FIT} may enlarge
 * @param maxLongEdge the long-edge limit, or 0 for none
 * @param maxShortEdge the short-edge limit, or 0 for none
 * @param filter the resampling filter
 * @param crop the displayed region kept, or {@code null} for the whole picture
 */
record ResizeSettings(int width, int height, ScaleMode scaleMode, boolean upscale, int maxLongEdge, int maxShortEdge,
                      ResizeFilter filter, Region crop) {
    /**
     * Returns checked settings, as an options builder holds them.
     *
     * @param width the box width, or 0 when free
     * @param height the box height, or 0 when free
     * @param scaleMode how the picture is fitted to the box
     * @param upscale whether {@link ScaleMode#FIT} may enlarge
     * @param longEdgeLimited whether {@code maxLongEdge} applies
     * @param maxLongEdge the long-edge limit
     * @param shortEdgeLimited whether {@code maxShortEdge} applies
     * @param maxShortEdge the short-edge limit
     * @param filter the resampling filter
     * @param crop the displayed region kept, or {@code null} for the whole picture
     * @return the settings
     * @throws IllegalArgumentException if a box dimension is negative or an applied edge limit is
     * not positive
     */
    static ResizeSettings checked(int width, int height, ScaleMode scaleMode, boolean upscale, boolean longEdgeLimited,
                                  int maxLongEdge, boolean shortEdgeLimited, int maxShortEdge, ResizeFilter filter,
                                  Region crop) {
        Checks.nonNegative("box width", width);
        Checks.nonNegative("box height", height);
        return new ResizeSettings(width, height, scaleMode, upscale,
                longEdgeLimited ? Checks.positive("maximum long edge", maxLongEdge) : 0,
                shortEdgeLimited ? Checks.positive("maximum short edge", maxShortEdge) : 0, filter, crop);
    }

    /**
     * Plans the scaling of a displayed picture.
     *
     * @param displayWidth the displayed width, after any crop
     * @param displayHeight the displayed height, after any crop
     * @param evenDimensions whether the output dimensions are rounded down to even numbers
     * @return the plan
     */
    SizePlan sizePlan(int displayWidth, int displayHeight, boolean evenDimensions) {
        return SizePlan.of(displayWidth, displayHeight, width, height, scaleMode, upscale, maxLongEdge, maxShortEdge,
                evenDimensions);
    }

    /**
     * {@return whether any setting can change the picture: a box, an edge limit or a crop}
     */
    boolean resizes() {
        return width != 0 || height != 0 || maxLongEdge != 0 || maxShortEdge != 0 || crop != null;
    }

    /**
     * {@return the settings as {@code toString} of the options lists them}
     */
    String describe() {
        return "size=" + width + "x" + height + " " + scaleMode + ", upscale=" + upscale + ", maxLongEdge=" + maxLongEdge
                + ", maxShortEdge=" + maxShortEdge;
    }
}
