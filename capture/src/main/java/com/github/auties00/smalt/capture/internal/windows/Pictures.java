package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.internal.plane.Planes;

/**
 * Copies and rescales planar 4:2:0 pictures into the pictures a session delivers.
 */
final class Pictures {
    /**
     * Prevents instantiation.
     */
    private Pictures() {
        throw new AssertionError();
    }

    /**
     * Returns a planar 4:2:0 picture of a size and color, reusing a previous one when it matches.
     *
     * @param previous the previous picture, or {@code null}
     * @param width the width; positive
     * @param height the height; positive
     * @param color the color description
     * @return {@code previous} when it has this size and color, otherwise a new picture
     */
    static YuvPicture reuse(YuvPicture previous, int width, int height, ColorInfo color) {
        if (previous != null && previous.width() == width && previous.height() == height && previous.color().equals(color)) {
            return previous;
        }
        return YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
    }

    /**
     * Copies the top-left part of one planar 4:2:0 picture into another.
     *
     * <p>The copied region is the smaller of the two sizes; the rest of the target is unchanged.
     *
     * @param source the picture to copy
     * @param target the picture receiving the samples
     */
    static void copy(YuvPicture source, YuvPicture target) {
        var width = Math.min(source.width(), target.width());
        var height = Math.min(source.height(), target.height());
        Planes.copy(source.y(), 0, source.yStride(), target.y(), 0, target.yStride(), width, height);
        var chromaWidth = Math.min(source.chromaWidth(), target.chromaWidth());
        var chromaHeight = Math.min(source.chromaHeight(), target.chromaHeight());
        Planes.copy(source.u(), 0, source.uvStride(), target.u(), 0, target.uvStride(), chromaWidth, chromaHeight);
        Planes.copy(source.v(), 0, source.uvStride(), target.v(), 0, target.uvStride(), chromaWidth, chromaHeight);
    }

    /**
     * Rescales a planar 4:2:0 picture to the size of another and copies the result into it.
     *
     * <p>Used when a device changes its resolution while a session is open, so the session keeps
     * delivering pictures of the size it announced.
     *
     * @param source the picture to rescale
     * @param target the picture receiving the rescaled samples
     */
    static void fit(YuvPicture source, YuvPicture target) {
        if (source.width() == target.width() && source.height() == target.height()) {
            copy(source, target);
            return;
        }
        var scaled = (YuvPicture) ImageOps.resize(source, target.width(), target.height(), ResizeFilter.BILINEAR);
        copy(scaled, target);
    }
}
