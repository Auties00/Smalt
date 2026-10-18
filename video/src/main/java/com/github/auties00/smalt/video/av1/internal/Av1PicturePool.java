package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayDeque;

/**
 * Recycles the planes of released pictures, so that a stream of equally sized frames allocates no
 * new planes once the reference slots are full.
 *
 * <p>The pool keeps at most {@link #MAX_FREE} free pictures; the free pictures of another geometry
 * are dropped when a new geometry is requested. Instances are not thread-safe; the decoding
 * thread owns them.
 */
public final class Av1PicturePool {
    /**
     * The most free pictures kept.
     */
    private static final int MAX_FREE = 10;

    /**
     * The free pictures, most recently released first.
     */
    private final ArrayDeque<Av1Picture> free = new ArrayDeque<>();

    /**
     * The resource limits checked before every allocation.
     */
    private final MediaLimits limits;

    /**
     * Constructs an empty pool.
     *
     * @param limits the resource limits checked before every allocation
     */
    public Av1PicturePool(MediaLimits limits) {
        this.limits = limits;
    }

    /**
     * Returns a picture of a geometry, reusing free planes when possible.
     *
     * @param w the frame width
     * @param h the frame height
     * @param layout the pixel layout, a {@code SequenceHeader.LAYOUT_} constant
     * @param bpc the bits per sample
     * @return the picture, with one holder and unspecified samples
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the picture exceeds the
     * limits
     */
    public Av1Picture obtain(int w, int h, int layout, int bpc) {
        LimitChecks.checkPicture(limits, w, h);
        for (var it = free.iterator(); it.hasNext(); ) {
            var picture = it.next();
            if (picture.fits(w, h, layout)) {
                it.remove();
                picture.reset(w, h, bpc);
                return picture;
            }
        }
        free.clear();
        LimitChecks.checkAllocation(limits, Av1Picture.samples(w, h, layout) * 2);
        return new Av1Picture(this, w, h, layout, bpc);
    }

    /**
     * Takes back a picture that no holder references any more.
     *
     * @param picture the released picture, whose planes this pool may hand out again
     */
    void recycle(Av1Picture picture) {
        if (free.size() < MAX_FREE) {
            free.addFirst(picture);
        }
    }
}
