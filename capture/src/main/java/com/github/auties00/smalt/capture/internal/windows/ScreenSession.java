package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.VideoCaptureSession;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.capture.internal.FrameExchange;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.capture.internal.PumpThread;
import com.github.auties00.smalt.capture.internal.convert.YuvCoefficients;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Captures a monitor or a window at a fixed frame rate.
 *
 * <p>The session owns a platform thread in the COM multithreaded apartment that uses physical pixel
 * coordinates, opens the preferred {@link ScreenGrabber} of the source (desktop duplication for a monitor,
 * Windows Graphics Capture for a window) or its fallback when that is unavailable or stops working (GDI,
 * {@code PrintWindow}), and wakes at the requested rate: at every tick it asks the grabber for the
 * source's image, publishing a new picture when the image changed and the previous picture again, with a
 * new timestamp, when it did not, so a reader receives frames at the requested pace even while nothing
 * moves. Ticks follow an absolute schedule; a capture thread that falls more than one tick behind skips
 * the missed ticks instead of bursting. Pictures have the size of the captured area when the session
 * opened (the source, or the part of the options' region on it), cropped to even dimensions, and are
 * converted from sRGB to BT.709 limited range ({@link YuvCoefficients#BT709_LIMITED}); the pointer is drawn
 * unless the options leave it out.
 */
final class ScreenSession implements VideoCaptureSession {
    /**
     * The longest wait for the capture thread to open the source.
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
     * The monitor or window.
     */
    private final CaptureDevice device;

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
     * @param device the monitor or window
     * @param worker the capture thread's state
     * @param pump the capture thread
     */
    private ScreenSession(CaptureDevice device, Worker worker, PumpThread pump) {
        this.device = device;
        this.worker = worker;
        this.pump = pump;
    }

    /**
     * Opens a monitor with desktop duplication, falling back to GDI.
     *
     * @param screen the monitor
     * @param options the frame rate, region and pointer settings
     * @return the session
     * @throws MediaException.Io if the monitor does not exist or cannot be captured
     * @throws MediaException.Unsupported if the captured area is smaller than 2x2 pixels
     */
    static VideoCaptureSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        return open(screen, options, false);
    }

    /**
     * Opens a monitor.
     *
     * @param screen the monitor
     * @param options the frame rate, served at 1000 when higher, region and pointer settings
     * @param gdi whether to use GDI without trying desktop duplication
     * @return the session
     * @throws MediaException.Io if the monitor does not exist or cannot be captured
     * @throws MediaException.Unsupported if the captured area is smaller than 2x2 pixels
     */
    static ScreenSession open(CaptureDevice.Screen screen, ScreenCaptureOptions options, boolean gdi) {
        Supplier<ScreenGrabber> primary = gdi || !Dxgi.available() ? null : () -> new DuplicationGrabber(screen.id());
        return start(screen, "the screen " + screen.name(), options, primary, () -> new GdiGrabber(screen.id(), null));
    }

    /**
     * Opens a window with Windows Graphics Capture, falling back to {@code PrintWindow}.
     *
     * @param window the window to capture, as listed
     * @param options the frame rate, served at 1000 when higher, region and pointer settings
     * @param print whether to use {@code PrintWindow} without trying Windows Graphics Capture
     * @return the session
     * @throws MediaException.Io if the window no longer exists or cannot be captured
     * @throws MediaException.Unsupported if the captured area is smaller than 2x2 pixels
     */
    static ScreenSession open(CaptureDevice.Window window, ScreenCaptureOptions options, boolean print) {
        var handle = TopWindows.handle(window.id());
        if (handle.equals(MemorySegment.NULL) || !TopWindows.exists(handle)) {
            throw new MediaException.Io("the window " + window.name() + " no longer exists",
                    new IOException("IsWindow(" + window.id() + ") is false"));
        }
        Supplier<ScreenGrabber> primary = print || !GraphicsCapture.linked() ? null : () -> new WgcGrabber(handle);
        return start(window, "the window " + window.name(), options, primary, () -> new PrintWindowGrabber(handle));
    }

    /**
     * Starts the capture thread and waits for it to open the source.
     *
     * @param what the source, named in failures
     * @param primary opens the preferred grabber, or {@code null} to use the fallback only
     * @param fallback opens the fallback grabber
     */
    private static ScreenSession start(CaptureDevice device, String what, ScreenCaptureOptions options,
                                       Supplier<ScreenGrabber> primary, Supplier<ScreenGrabber> fallback) {
        var pump = new PumpThread("smalt-screen-capture", Thread.NORM_PRIORITY);
        var worker = new Worker(what, options, primary, fallback, pump);
        pump.start(worker);
        pump.awaitOpen(OPEN_TIMEOUT, what, worker::abandon);
        return new ScreenSession(device, worker, pump);
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
     * @return {@code true} for desktop duplication
     */
    boolean duplicating() {
        return worker.grabber instanceof DuplicationGrabber;
    }

    /**
     * Returns whether the session captures with Windows Graphics Capture.
     *
     * @return {@code true} for Windows Graphics Capture
     */
    boolean graphicsCapture() {
        return worker.grabber instanceof WgcGrabber;
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
         * The source, named in failures.
         */
        private final String what;

        /**
         * The frames per second, at most {@link #MAX_RATE}.
         */
        private final Rational rate;

        /**
         * The tick period in nanoseconds, rounded down.
         */
        private final long periodNanos;

        /**
         * The region, frame rate and pointer settings.
         */
        private final ScreenCaptureOptions options;

        /**
         * Opens the preferred grabber, or {@code null}.
         */
        private final Supplier<ScreenGrabber> primary;

        /**
         * Opens the fallback grabber.
         */
        private final Supplier<ScreenGrabber> fallback;

        /**
         * The capture thread, told when the source is open.
         */
        private final PumpThread pump;

        /**
         * The captured area, set when the source is open.
         */
        private CaptureArea area;

        /**
         * The exchange of the session's pictures, set before the pump is told the source is open.
         */
        private volatile FrameExchange exchange;

        /**
         * The grabber in use; replaced by the capture thread when it falls back.
         */
        private volatile ScreenGrabber grabber;

        /**
         * Whether the opener gave up waiting.
         */
        private volatile boolean abandoned;

        /**
         * Creates the state of a capture.
         *
         * @param what the source, named in failures
         * @param options the frame rate, region and pointer settings
         * @param primary opens the preferred grabber, or {@code null}
         * @param fallback opens the fallback grabber
         * @param pump the capture thread
         */
        Worker(String what, ScreenCaptureOptions options, Supplier<ScreenGrabber> primary,
               Supplier<ScreenGrabber> fallback, PumpThread pump) {
            this.what = what;
            this.options = options;
            this.rate = FramePacing.atMost(options.frameRate(), MAX_RATE);
            this.periodNanos = FramePacing.periodNanos(rate);
            this.primary = primary;
            this.fallback = fallback;
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
            var initialized = false;
            try {
                try {
                    initialized = Com.available() && Com.initialize();
                } catch (MediaException exception) {
                    // without an apartment only Windows Graphics Capture fails, and its fallback takes over
                }
                runInApartment();
            } finally {
                if (initialized) {
                    Com.uninitialize();
                }
            }
        }

        /**
         * Opens the source and captures it; runs on the capture thread in the multithreaded apartment.
         */
        private void runInApartment() {
            long start;
            try {
                Gdi.usePhysicalPixels();
                grabber = openGrabber();
                if ((grabber.width() & ~1) == 0 || (grabber.height() & ~1) == 0) {
                    throw Com.failure(what + " is " + grabber.width() + "x" + grabber.height(), 0);
                }
                area = CaptureArea.of(options, grabber.width(), grabber.height(), what);
                grabber.configure(area, options.cursor());
                LimitChecks.checkPicture(MediaLimits.DEFAULT, area.width(), area.height());
                exchange = new FrameExchange(area.width(), area.height(), YuvCoefficients.BT709_LIMITED.color());
                start = System.nanoTime();
            } catch (MediaException exception) {
                pump.failOpen(exception);
                closeGrabber();
                return;
            } catch (RuntimeException exception) {
                var message = "cannot capture " + what;
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
                var message = "the capture of " + what + " failed";
                exchange.fail(new MediaException.Io(message, new IOException(message, exception)));
            } finally {
                closeGrabber();
            }
        }

        /**
         * Opens the grabber of the source.
         *
         * @return the preferred grabber when it opens, otherwise the fallback
         * @throws MediaException.Io if the fallback cannot capture the source either
         */
        private ScreenGrabber openGrabber() {
            if (primary != null) {
                try {
                    return primary.get();
                } catch (MediaException exception) {
                    // The preferred API is unavailable for this source: the fallback captures it instead
                }
            }
            return fallback.get();
        }

        /**
         * Captures at the requested rate until the session closes or the source disappears.
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
                    grabber = fallback.get();
                    grabber.configure(area, options.cursor());
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
