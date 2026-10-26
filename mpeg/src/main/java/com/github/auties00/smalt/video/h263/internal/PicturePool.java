package com.github.auties00.smalt.video.h263.internal;

import java.util.ArrayDeque;

/**
 * Recycles the pictures of the H.263 annex decoder: a picture returns to the pool when its last holder releases it.
 *
 * <p>An instance is not thread-safe.
 */
final class PicturePool {
    /**
     * The free pictures.
     */
    private final ArrayDeque<PlusPicture> free = new ArrayDeque<>();

    /**
     * The most free pictures kept.
     */
    private static final int MAX_FREE = 8;

    /**
     * Creates an empty pool.
     */
    PicturePool() {
    }

    /**
     * Returns a picture of a size with one holder, reusing a free one when possible.
     *
     * @param width the luminance width in samples
     * @param height the luminance height in samples
     * @return the picture, with stale samples
     */
    PlusPicture acquire(int width, int height) {
        for (var iterator = free.iterator(); iterator.hasNext(); ) {
            var picture = iterator.next();
            if (picture.width == width && picture.height == height) {
                iterator.remove();
                reset(picture);
                return picture;
            }
        }
        var picture = new PlusPicture(width, height);
        picture.users = 1;
        return picture;
    }

    /**
     * Prepares a recycled picture for a new use.
     *
     * @param picture the recycled picture
     */
    private static void reset(PlusPicture picture) {
        picture.users = 1;
        picture.damaged = false;
        picture.warping = null;
        picture.keyframe = false;
        picture.layer = 1;
        picture.segmentCount = 0;
    }

    /**
     * Adds a holder to a picture.
     *
     * @param picture the picture that gains a holder
     */
    void retain(PlusPicture picture) {
        picture.users++;
    }

    /**
     * Removes a holder from a picture, recycling it when none remains.
     *
     * @param picture the picture, or {@code null}
     */
    void release(PlusPicture picture) {
        if (picture == null) {
            return;
        }
        if (--picture.users == 0 && free.size() < MAX_FREE) {
            free.add(picture);
        }
    }

    /**
     * Drops the free pictures.
     */
    void clear() {
        free.clear();
    }
}
