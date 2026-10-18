package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.FrameExchange;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.capture.internal.PumpThread;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures a monitor at a fixed frame rate.
 *
 * <p>The session owns a platform thread that uses physical pixel coordinates, opens a
 * {@link DuplicationGrabber} for the monitor (or a {@link GdiGrabber} when desktop duplication is
 * unavailable, or stops working, for this monitor) and wakes at the requested rate: at every tick
 * it asks the grabber for the monitor's image, publishing a new picture when the image changed
 * and the previous picture again, with a new timestamp, when it did not, so a reader receives
 * frames at the requested pace even while nothing on the screen moves. Ticks follow an absolute
 * schedule; a capture thread that falls more than one tick behind skips the missed ticks instead
 * of bursting. Pictures have the monitor's size when the session opened, cropped to even
 * dimensions, and are converted from sRGB to BT.709 limited range
 * ({@link YuvCoefficients#BT709_LIMITED}).
 */
final class ScreenSession implements VideoCaptureSession {
    /**
     * The longest wait for the capture thread to open the monitor.
     */
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The longest wait for the capture thread to stop in {@link #close()}.
     */
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The highest frame rate a session paces itself at; higher requests are served at this rate.
     */
    private static final int MAX_RATE = 1_000;

    /**
     * The monitor.
     */
    private final CaptureDevice.Screen device;

    /**
     * The capture thread's state.
     */
    private final Worker worker;

    /**
     * The capture thread.
     */
    private final PumpThread pump;

    /**
     * Whether {@link #close()} was called.
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a session over an opened capture thread.
     *
     * @param device the monitor
     * @param worker the capture thread's state
     * @param pump the capture thread
     */
    private ScreenSession(CaptureDevice.Screen device, Worker worker, PumpThread pump) {
        this.device = device;
        this.worker = worker;
        this.pump = pump;
    }

    /**
     * Opens a monitor with desktop duplication, falling back to GDI.
     *
     * @param screen the monitor
     * @param frameRate the frames per second
     * @return the session
     * @throws MediaException.Io if the monitor does not exist or cannot be captured
     */
    static VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate) {
        return open(screen, frameRate, false);
    }

    /**
     * Opens a monitor.
     *
     * @param screen the monitor
     * @param frameRate the frames per second, served at 1000 when higher
     * @param gdi whether to use GDI without trying desktop duplication
     * @return the session
     * @throws MediaException.Io if the monitor does not exist or cannot be captured
     */
    static VideoCaptureSession open(CaptureDevice.Screen screen, Rational frameRate, boolean gdi) {
        var pump = new PumpThread("smalt-screen-capture", Thread.NORM_PRIORITY);
        var worker = new Worker(screen, frameRate, gdi, pump);
        pump.start(worker);
        pump.awaitOpen(OPEN_TIMEOUT, "the screen " + screen.name(), worker::abandon);
        return new ScreenSession(screen, worker, pump);
    }

    @Override
    public CaptureDevice device() {
        return device;
    }

    @Override
    public int width() {
        return worker.exchange.width();
    }

    @Override
    public int height() {
        return worker.exchange.height();
    }

    @Override
    public Frame read() throws InterruptedException {
        return worker.exchange.take();
    }

    /**
     * Returns whether the session captures with desktop duplication.
     *
     * @return {@code true} for desktop duplication, {@code false} for GDI
     */
    boolean duplicating() {
        return worker.grabber instanceof DuplicationGrabber;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.exchange.close();
        pump.join(CLOSE_TIMEOUT);
    }

    /**
     * Runs the capture on the session's thread and holds its state.
     */
    private static final class Worker implements Runnable {
        /**
         * The monitor.
         */
        private final CaptureDevice.Screen screen;

        /**
         * The frames per second, at most {@link #MAX_RATE}.
         */
        private final Rational rate;

        /**
         * The tick period in nanoseconds, rounded down.
         */
        private final long periodNanos;

        /**
         * Whether to use GDI without trying desktop duplication.
         */
        private final boolean gdi;

        /**
         * The capture thread, told when the monitor is open.
         */
        private final PumpThread pump;

        /**
         * The exchange of the session's pictures, set before the pump is told the monitor is open.
         */
        private volatile FrameExchange exchange;

        /**
         * The grabber in use; replaced by the capture thread when it falls back to GDI.
         */
        private volatile ScreenGrabber grabber;

        /**
         * Whether the opener gave up waiting.
         */
        private volatile boolean abandoned;

        /**
         * Creates the state of a capture.
         *
         * @param screen the monitor
         * @param frameRate the frames per second
         * @param gdi whether to use GDI without trying desktop duplication
         * @param pump the capture thread
         */
        Worker(CaptureDevice.Screen screen, Rational frameRate, boolean gdi, PumpThread pump) {
            this.screen = screen;
            this.rate = FramePacing.atMost(frameRate, MAX_RATE);
            this.periodNanos = FramePacing.periodNanos(rate);
            this.gdi = gdi;
            this.pump = pump;
        }

        /**
         * Makes the capture thread stop as soon as it is open.
         */
        private void abandon() {
            abandoned = true;
            var pending = exchange;
            if (pending != null) {
                pending.close();
            }
        }

        @Override
        public void run() {
            long start;
            try {
                Gdi.usePhysicalPixels();
                grabber = openGrabber();
                var width = grabber.width() & ~1;
                var height = grabber.height() & ~1;
                if (width == 0 || height == 0) {
                    throw Com.failure("the screen " + screen.name() + " is " + grabber.width() + "x" + grabber.height(), 0);
                }
                LimitChecks.checkPicture(MediaLimits.DEFAULT, width, height);
                exchange = new FrameExchange(width, height, YuvCoefficients.BT709_LIMITED.color());
                start = System.nanoTime();
            } catch (MediaException exception) {
                pump.failOpen(exception);
                closeGrabber();
                return;
            } catch (RuntimeException exception) {
                var message = "cannot capture the screen " + screen.name();
                pump.failOpen(new MediaException.Io(message, new IOException(message, exception)));
                closeGrabber();
                return;
            } finally {
                pump.markOpen();
            }
            try {
                if (!abandoned) {
                    capture(start);
                }
            } catch (MediaException exception) {
                exchange.fail(exception);
            } catch (RuntimeException exception) {
                var message = "the capture of the screen " + screen.name() + " failed";
                exchange.fail(new MediaException.Io(message, new IOException(message, exception)));
            } finally {
                closeGrabber();
            }
        }

        /**
         * Opens the grabber of the monitor.
         *
         * @return desktop duplication when available, otherwise GDI
         * @throws MediaException.Io if the monitor does not exist or GDI cannot capture it
         */
        private ScreenGrabber openGrabber() {
            if (!gdi && Dxgi.available()) {
                try {
                    return new DuplicationGrabber(screen.id());
                } catch (MediaException exception) {
                    // Desktop duplication is unavailable for this monitor: GDI captures it instead
                }
            }
            return new GdiGrabber(screen.id(), null);
        }

        /**
         * Captures at the requested rate until the session closes or the monitor disappears.
         *
         * @param start the {@link System#nanoTime()} of the first tick
         */
        private void capture(long start) {
            var tick = 0L;
            while (exchange.sleepUntil(tickTime(start, tick))) {
                var picture = exchange.acquire();
                var timestamp = (System.nanoTime() - start) / 1_000;
                var result = grabber.grab(picture);
                if (result == ScreenGrabber.Result.FALLBACK) {
                    grabber.close();
                    grabber = new GdiGrabber(screen.id(), null);
                    result = grabber.grab(picture);
                }
                switch (result) {
                    case CHANGED -> exchange.publish(picture, timestamp);
                    case ENDED -> {
                        exchange.finish();
                        return;
                    }
                    default -> {
                        if (!exchange.republish(timestamp)) {
                            exchange.publish(picture, timestamp);
                        }
                    }
                }
                tick++;
                var now = System.nanoTime();
                if (now - tickTime(start, tick) > periodNanos) {
                    tick = FramePacing.ticksElapsed(rate, now - start) + 1;
                }
            }
        }

        /**
         * Returns the time of a tick of the schedule.
         *
         * @param start the time of the first tick
         * @param tick the tick's index
         * @return the tick's time on the {@link System#nanoTime()} clock
         */
        private long tickTime(long start, long tick) {
            return start + FramePacing.tickNanos(rate, tick);
        }

        /**
         * Closes the grabber, if any.
         */
        private void closeGrabber() {
            var current = grabber;
            if (current != null) {
                current.close();
            }
        }
    }
}
