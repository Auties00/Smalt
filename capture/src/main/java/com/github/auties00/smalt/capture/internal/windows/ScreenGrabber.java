package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Captures the image of one monitor or window on the thread that created it.
 *
 * <p>A grabber is created, used and closed on the capture thread of its session, because the
 * device contexts and Direct3D objects behind it must not be shared between threads.
 */
sealed interface ScreenGrabber extends AutoCloseable permits DuplicationGrabber, GdiGrabber, PrintWindowGrabber,
        WgcGrabber {
    /**
     * Reports the outcome of one {@link #grab(YuvPicture)}.
     */
    enum Result {
        /**
         * The picture was written with a new image.
         */
        CHANGED,

        /**
         * The screen did not change, or cannot be read right now; the previous image stands.
         */
        UNCHANGED,

        /**
         * This grabber cannot capture the source; the session should switch to its fallback.
         */
        FALLBACK,

        /**
         * The monitor or window no longer exists.
         */
        ENDED
    }

    /**
     * Returns the width of the source's image when the grabber was opened.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the height of the source's image when the grabber was opened.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Sets the part of the source's image to deliver and whether the pointer is drawn; until called, the
     * whole image is delivered with the pointer.
     *
     * @param area the rectangle of the upright image, or {@code null} for all of it
     * @param cursor whether the pointer is drawn
     */
    void configure(CaptureArea area, boolean cursor);

    /**
     * Captures the source if its image changed since the last call.
     *
     * @param out the picture receiving the captured area, of the session's size
     * @return the outcome; {@code out} was written only for {@link Result#CHANGED}
     */
    Result grab(YuvPicture out);

    /**
     * Releases the grabber's operating system resources.
     */
    @Override
    void close();
}
