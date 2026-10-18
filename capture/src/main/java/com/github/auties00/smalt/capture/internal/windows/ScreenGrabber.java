package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.image.YuvPicture;

/**
 * Captures the image of one monitor on the thread that created it.
 *
 * <p>A grabber is created, used and closed on the capture thread of its session, because the
 * device contexts and Direct3D objects behind it must not be shared between threads.
 */
sealed interface ScreenGrabber extends AutoCloseable permits DuplicationGrabber, GdiGrabber {
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
         * This grabber cannot capture the monitor; the session should switch to GDI.
         */
        FALLBACK,

        /**
         * The monitor no longer exists.
         */
        ENDED
    }

    /**
     * Returns the width of the monitor's desktop image when the grabber was opened.
     *
     * @return the width in pixels
     */
    int width();

    /**
     * Returns the height of the monitor's desktop image when the grabber was opened.
     *
     * @return the height in pixels
     */
    int height();

    /**
     * Captures the monitor if its image changed since the last call.
     *
     * @param out the picture receiving the image, of the session's size
     * @return the outcome; {@code out} was written only for {@link Result#CHANGED}
     */
    Result grab(YuvPicture out);

    /**
     * Releases the grabber's operating system resources.
     */
    @Override
    void close();
}
