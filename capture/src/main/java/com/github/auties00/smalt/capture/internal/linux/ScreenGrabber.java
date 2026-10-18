package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Grabs the current content of a screen on demand.
 *
 * <p>A grabber is used by one thread at a time; {@link PacedCaptureSession} serializes the grabs
 * and the close.
 */
interface ScreenGrabber extends AutoCloseable {
    /**
     * Returns the width of the grabbed pictures.
     *
     * @return the width in pixels, even
     */
    int width();

    /**
     * Returns the height of the grabbed pictures.
     *
     * @return the height in pixels, even
     */
    int height();

    /**
     * Grabs the screen's current content.
     *
     * @return the planar 4:2:0 picture, valid until the next grab, or {@code null} when the screen
     *         no longer exists
     * @throws MediaException.Io if the grab fails
     */
    YuvPicture grab();

    /**
     * Releases the grabber's resources; idempotent.
     */
    @Override
    void close();
}
