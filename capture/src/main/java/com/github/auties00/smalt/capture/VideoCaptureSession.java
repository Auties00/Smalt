package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;

/**
 * Delivers the frames of an open capture device.
 *
 * <p>Frames are pulled with {@link #read()}, which blocks until the device produces the next one.
 * Each frame is a planar 4:2:0 {@link YuvPicture} of {@link #width()} by {@link #height()} pixels
 * whose {@link YuvPicture#color()} describes its matrix and range, stamped with the capture time
 * on a monotonic microsecond clock that starts near zero when the session opens. The picture may
 * be recycled by the session, so it is valid only until the next call to {@link #read()} or
 * {@link #close()}. A session delivers frames at the device's pace; a caller slower than the
 * device receives the newest frame and skips the older ones rather than falling behind.
 *
 * <p>Like the readers of the core module, a session pulls: {@link #read()} returns {@code null}
 * at the end, here once the session is closed or the device stopped, and every later call
 * returns {@code null} too. A live device cannot seek. A session is {@link AutoCloseable} and is
 * closed in a {@code try}-with-resources block:
 * {@snippet :
 * try (var session = VideoDevices.openCapture(camera)) {
 *     for (var frame = session.read(); frame != null; frame = session.read()) {
 *         show(frame.picture(), frame.timestampMicros());
 *     }
 * }
 * }
 *
 * <p>A session is read by one thread at a time; {@link #close()} may be called from any thread,
 * also while another thread is blocked in {@link #read()}, and the other methods are safe from
 * any thread.
 *
 * @implSpec
 * Implementations must be safe for a {@link #close()} from any thread while another thread is
 * blocked in {@link #read()}, which then returns {@code null}; {@link #close()} must be idempotent
 * and release the device so another session can open it.
 */
public interface VideoCaptureSession extends AutoCloseable {
    /**
     * Returns the device this session captures.
     *
     * @return the device
     */
    CaptureDevice device();

    /**
     * Returns the width of the delivered pictures.
     *
     * @return the width in pixels, even
     */
    int width();

    /**
     * Returns the height of the delivered pictures.
     *
     * @return the height in pixels, even
     */
    int height();

    /**
     * Returns the next frame, blocking until the device produces it.
     *
     * @return the frame, valid until the next call, or {@code null} once the session is closed or
     *         the device stopped
     * @throws InterruptedException if the calling thread is interrupted while waiting
     * @throws MediaException.Io    if the device fails
     */
    Frame read() throws InterruptedException;

    /**
     * Stops capturing and releases the device; a {@link #read()} blocked in another thread returns
     * {@code null}. Closing a closed session does nothing.
     */
    @Override
    void close();

    /**
     * Pairs a captured picture with its capture time.
     *
     * <p>The record is immutable, but its picture belongs to the session, which may overwrite it
     * after the next {@link VideoCaptureSession#read()} or {@link VideoCaptureSession#close()}.
     *
     * @param picture         the planar 4:2:0 picture
     * @param timestampMicros the capture time on the session's monotonic clock, in microseconds
     */
    record Frame(YuvPicture picture, long timestampMicros) {
    }
}
