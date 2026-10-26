package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.ScreenCaptureOptions;
import com.github.auties00.smalt.capture.internal.CaptureArea;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Captures a display or a window with ScreenCaptureKit (macOS 12.3 and later).
 *
 * <p>Opening a screen checks the Screen Recording permission, finds the display among the
 * {@code SCShareableContent} displays, and starts an {@code SCStream} over an
 * {@code SCContentFilter} of the whole display; opening a window finds it among the content's windows and
 * filters on that window alone ({@code initWithDesktopIndependentWindow:}), so other windows never cover
 * it. The stream is configured for the pixel size of the captured area (with a {@code sourceRect} in
 * points for a region), NV12 ({@code '420v'}) output converted by the system with the BT.709 matrix in the
 * sRGB color space, the cursor drawn as the options ask, and a minimum frame interval of one frame at the
 * requested rate.
 * ScreenCaptureKit sends a frame only when the display changes, so the session repeats the last
 * frame when none arrives within a frame interval, keeping the requested rate. Frames whose
 * {@code SCStreamFrameInfoStatus} is not complete are skipped. A stream stopped by the user ends
 * the session; a stream stopped by an error fails a screen session and ends a window session, whose
 * window was closed.
 */
final class MacStreamSession extends MacCaptureSession implements DelegateSink {
    /**
     * How long starting the stream may take.
     */
    private static final long START_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15);

    /**
     * How long stopping the stream may take.
     */
    private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

    /**
     * The highest frame rate a session paces itself at; higher requests are served at this rate.
     */
    private static final int MAX_RATE = 1_000;

    /**
     * The bridge serving the delegate and the completion handlers.
     */
    private final ObjcBridge bridge;

    /**
     * The key of this session in the bridge.
     */
    private final long key;

    /**
     * The reader copying pixel buffers.
     */
    private final PixelBufferReader reader;

    /**
     * The mapping from capture times to session timestamps, set when capture starts.
     */
    private volatile CaptureClock clock;

    /**
     * The retained {@code SCShareableContent}, or {@code NULL}.
     */
    private MemorySegment content;

    /**
     * The owned {@code SCContentFilter}, or {@code NULL}.
     */
    private MemorySegment filter;

    /**
     * The owned {@code SCStreamConfiguration}, or {@code NULL}.
     */
    private MemorySegment configuration;

    /**
     * The owned {@code SCStream}, or {@code NULL}.
     */
    private MemorySegment stream;

    /**
     * The owned delegate instance, or {@code NULL}.
     */
    private MemorySegment delegate;

    /**
     * The owned serial queue receiving the frames, or {@code NULL}.
     */
    private MemorySegment queue;

    /**
     * Whether the delegate was added as a stream output.
     */
    private boolean outputAdded;

    /**
     * Whether starting the stream was requested, so that it must be stopped.
     */
    private boolean started;

    /**
     * Whether the session captures a window rather than a display.
     */
    private final boolean window;

    /**
     * Creates a session.
     *
     * @param source the screen or window
     * @param width the picture width, even
     * @param height the picture height, even
     * @param frameRate the frames per second
     */
    private MacStreamSession(ObjcBridge bridge, CaptureDevice source, int width, int height, Rational frameRate) {
        super(source, width, height, FramePacing.periodNanos(frameRate));
        this.window = source instanceof CaptureDevice.Window;
        this.bridge = bridge;
        this.reader = new PixelBufferReader();
        this.key = bridge.register(this);
        this.content = MemorySegment.NULL;
        this.filter = MemorySegment.NULL;
        this.configuration = MemorySegment.NULL;
        this.stream = MemorySegment.NULL;
        this.delegate = MemorySegment.NULL;
        this.queue = MemorySegment.NULL;
    }

    /**
     * Opens a screen.
     *
     * <p>Runs on a platform thread inside an autorelease pool.
     *
     * @param options the frame rate, region and pointer settings
     * @return the running session
     * @throws MediaException.Io if the permission is missing, the screen is gone, or the
     * stream does not start
     * @throws MediaException.Unsupported if the captured area is smaller than 2x2 pixels
     */
    static MacStreamSession open(ObjcBridge bridge, CaptureDevice.Screen screen, ScreenCaptureOptions options) {
        MacDisplays.requireCapturePermission();
        var displayId = MacDisplays.displayId(screen);
        var area = CaptureArea.of(options, screen.width(), screen.height(), "the screen " + screen.name());
        var bounds = CoreGraphics.bounds(displayId);
        var scale = bounds[2] > 0 ? screen.width() / bounds[2] : 1;
        var rate = FramePacing.atMost(options.frameRate(), MAX_RATE);
        var session = new MacStreamSession(bridge, screen, area.width(), area.height(), rate);
        try {
            session.content = MacWindows.content(bridge);
            var display = find(session.content, displayId);
            if (ObjC.isNull(display)) {
                throw new MediaException.Io("the screen " + screen.name() + " is not available for capture",
                        new IOException("no SCDisplay has the id " + Integer.toUnsignedString(displayId)));
            }
            var excluded = ObjC.id(ScreenCaptureKit.NS_ARRAY, ScreenCaptureKit.ARRAY);
            session.filter = ObjC.id(ObjC.id(ScreenCaptureKit.CONTENT_FILTER, ObjC.ALLOC), ScreenCaptureKit.INIT_WITH_DISPLAY,
                    display, excluded);
            session.start(rate, options, area, screen.width(), screen.height(), scale);
            return session;
        } catch (Throwable failure) {
            session.close();
            throw failure;
        }
    }

    /**
     * Opens a window.
     *
     * <p>Runs on a platform thread inside an autorelease pool.
     *
     * @param options the frame rate, region and pointer settings
     * @return the running session
     * @throws MediaException.Io if the permission is missing, the window is gone, or the stream does not
     * start
     * @throws MediaException.Unsupported if the captured area is smaller than 2x2 pixels
     */
    static MacStreamSession open(ObjcBridge bridge, CaptureDevice.Window window, ScreenCaptureOptions options) {
        MacDisplays.requireCapturePermission();
        int windowId;
        try {
            windowId = Integer.parseUnsignedInt(window.id());
        } catch (NumberFormatException invalid) {
            throw new MediaException.Io("the window " + window.name() + " is not a macOS window",
                    new IOException("invalid window id " + window.id()));
        }
        var content = MacWindows.content(bridge);
        MacStreamSession session = null;
        try {
            var found = MacWindows.find(content, windowId);
            if (ObjC.isNull(found)) {
                throw new MediaException.Io("the window " + window.name() + " no longer exists",
                        new IOException("no SCWindow has the id " + window.id()));
            }
            var frame = ObjC.rect(found, ScreenCaptureKit.FRAME);
            var scale = MacWindows.scale(MacWindows.displays(), frame[0] + frame[2] / 2, frame[1] + frame[3] / 2);
            var width = (int) Math.min(Integer.MAX_VALUE, Math.round(frame[2] * scale));
            var height = (int) Math.min(Integer.MAX_VALUE, Math.round(frame[3] * scale));
            var area = CaptureArea.of(options, width, height, "the window " + window.name());
            var rate = FramePacing.atMost(options.frameRate(), MAX_RATE);
            session = new MacStreamSession(bridge, window, area.width(), area.height(), rate);
            session.content = content;
            content = MemorySegment.NULL;
            session.filter = ObjC.id(ObjC.id(ScreenCaptureKit.CONTENT_FILTER, ObjC.ALLOC), ScreenCaptureKit.INIT_WITH_WINDOW,
                    found);
            session.start(rate, options, area, width, height, scale);
            return session;
        } catch (Throwable failure) {
            if (session != null) {
                session.close();
            }
            ObjC.release(content);
            throw failure;
        }
    }

    /**
     * Configures the stream over the filter and starts it.
     *
     * @param frameRate the frames per second
     * @param options the pointer settings
     * @param area the captured area, in pixels of the source
     * @param sourceWidth the width of the source, in pixels
     * @param sourceHeight the height of the source, in pixels
     * @param scale the pixels per point of the source
     * @throws MediaException.Io if a step fails
     */
    private void start(Rational frameRate, ScreenCaptureOptions options, CaptureArea area, int sourceWidth,
                       int sourceHeight, double scale) {
        configuration = ObjC.newInstance(ScreenCaptureKit.STREAM_CONFIGURATION);
        if (ObjC.isNull(filter) || ObjC.isNull(configuration)) {
            throw new MediaException.Io("cannot configure the screen capture", new IOException("SCContentFilter or SCStreamConfiguration init failed"));
        }
        ObjC.callWithLong(configuration, ScreenCaptureKit.SET_WIDTH, width());
        ObjC.callWithLong(configuration, ScreenCaptureKit.SET_HEIGHT, height());
        ObjC.callWithTime(configuration, ScreenCaptureKit.SET_MINIMUM_FRAME_INTERVAL, CmTime.frameDuration(frameRate));
        ObjC.callWithInt(configuration, ScreenCaptureKit.SET_PIXEL_FORMAT, FourCc.NV12_VIDEO_RANGE);
        ObjC.callWithBool(configuration, ScreenCaptureKit.SET_SHOWS_CURSOR, options.cursor());
        if (!area.covers(sourceWidth, sourceHeight)) {
            ObjC.callWithRect(configuration, ScreenCaptureKit.SET_SOURCE_RECT, area.x() / scale, area.y() / scale,
                    area.width() / scale, area.height() / scale);
        }
        ObjC.call(configuration, ScreenCaptureKit.SET_COLOR_MATRIX, CoreGraphics.YCBCR_MATRIX_709);
        ObjC.call(configuration, ScreenCaptureKit.SET_COLOR_SPACE_NAME, CoreGraphics.SRGB);
        queue = Dispatch.createSerialQueue();
        delegate = bridge.newDelegate(key);
        stream = ObjC.id(ObjC.id(ScreenCaptureKit.STREAM, ObjC.ALLOC), ScreenCaptureKit.INIT_WITH_FILTER, filter, configuration, delegate);
        if (ObjC.isNull(stream)) {
            throw new MediaException.Io("cannot create the screen capture stream", new IOException("SCStream init failed"));
        }
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(ADDRESS);
            outputAdded = ObjC.bool(stream, ScreenCaptureKit.ADD_STREAM_OUTPUT, delegate, ScreenCaptureKit.OUTPUT_SCREEN, queue, error);
            if (!outputAdded) {
                throw failure("cannot receive the screen frames", NsError.read(error.get(ADDRESS, 0)));
            }
        }
        clock = new CaptureClock(CoreMedia.hostTime().toMicros(), exchange.micros(System.nanoTime()));
        // set before the request, so that a start completing after a timeout is still stopped
        started = true;
        var start = bridge.await(ObjcBridge.Handler.ERROR, START_TIMEOUT_MILLIS, "starting the screen capture",
                block -> ObjC.call(stream, ScreenCaptureKit.START_CAPTURE, block));
        if (start.error() != null) {
            throw failure("cannot start the screen capture", start.error());
        }
    }

    /**
     * Finds a display of the shareable content.
     *
     * @param content the {@code SCShareableContent}
     * @param displayId the display
     * @return the {@code SCDisplay}, or {@code NULL}
     */
    private static MemorySegment find(MemorySegment content, int displayId) {
        var displays = ObjC.id(content, ScreenCaptureKit.DISPLAYS);
        var count = ObjC.count(displays);
        for (var index = 0; index < count; index++) {
            var display = ObjC.element(displays, index);
            if (ObjC.unsignedInt(display, ScreenCaptureKit.DISPLAY_ID) == displayId) {
                return display;
            }
        }
        return MemorySegment.NULL;
    }

    /**
     * Builds the failure of a ScreenCaptureKit call.
     *
     * @param message what failed
     * @param error the error it reported, or {@code null}
     * @return the failure; the permission failure when the user declined capture
     */
    private static MediaException.Io failure(String message, NsError error) {
        if (error == null) {
            return new MediaException.Io(message, new IOException(message));
        }
        if (error.is(errorDomain(), ScreenCaptureKit.ERROR_USER_DECLINED)) {
            return MacDisplays.permissionDenied();
        }
        return error.toException(message);
    }

    /**
     * Returns the {@code SCStreamErrorDomain} string.
     *
     * @return the domain, or an empty string when the constant is missing
     */
    private static String errorDomain() {
        var domain = CoreFoundation.string(ScreenCaptureKit.ERROR_DOMAIN);
        return domain == null ? "" : domain;
    }

    /**
     * Returns whether a sample buffer carries a new frame, according to its
     * {@code SCStreamFrameInfoStatus} attachment.
     *
     * @param sampleBuffer the sample buffer
     * @return whether the status is complete or started, or absent
     */
    private static boolean carriesFrame(MemorySegment sampleBuffer) {
        if (ObjC.isNull(ScreenCaptureKit.FRAME_STATUS_KEY)) {
            return true;
        }
        var attachments = CoreMedia.sampleAttachments(sampleBuffer);
        if (CoreFoundation.arrayCount(attachments) < 1) {
            return true;
        }
        var status = CoreFoundation.dictionaryValue(CoreFoundation.arrayValue(attachments, 0), ScreenCaptureKit.FRAME_STATUS_KEY);
        if (ObjC.isNull(status)) {
            return true;
        }
        var value = ObjC.integer(status, ScreenCaptureKit.INTEGER_VALUE);
        return value == ScreenCaptureKit.FRAME_COMPLETE || value == ScreenCaptureKit.FRAME_STARTED;
    }

    @Override
    public void sampleBuffer(MemorySegment sampleBuffer) {
        var mapping = clock;
        if (mapping == null || exchange.isClosed() || !carriesFrame(sampleBuffer)) {
            return;
        }
        var image = CoreMedia.imageBuffer(sampleBuffer);
        if (ObjC.isNull(image)) {
            return;
        }
        var timestamp = mapping.timestamp(CoreMedia.presentationTime(sampleBuffer), exchange.micros(System.nanoTime()));
        var picture = reader.read(image, exchange.acquire());
        if (picture != null) {
            exchange.publish(picture, timestamp);
        }
    }

    @Override
    public void stopped(MemorySegment error) {
        var reason = NsError.read(error);
        if (window || reason == null || reason.is(errorDomain(), ScreenCaptureKit.ERROR_USER_STOPPED)) {
            exchange.finish();
        } else {
            exchange.fail(failure("the screen capture stopped", reason));
        }
    }

    @Override
    public void failed(Throwable failure) {
        exchange.fail(failure instanceof MediaException.Io io ? io
                : new MediaException.Io("screen capture failed", new IOException(String.valueOf(failure), failure)));
    }

    @Override
    void release() {
        if (started) {
            try {
                bridge.await(ObjcBridge.Handler.ERROR, STOP_TIMEOUT_MILLIS, "stopping the screen capture",
                        block -> ObjC.call(stream, ScreenCaptureKit.STOP_CAPTURE, block));
            } catch (MediaException.Io timedOut) {
                // the stream is torn down below either way; late callbacks find no target
            }
        }
        if (outputAdded) {
            try (var arena = Arena.ofConfined()) {
                ObjC.bool(stream, ScreenCaptureKit.REMOVE_STREAM_OUTPUT, delegate, ScreenCaptureKit.OUTPUT_SCREEN, arena.allocate(ADDRESS));
            }
        }
        if (!ObjC.isNull(queue)) {
            bridge.drain(queue);
        }
        bridge.unregister(key);
        bridge.detachDelegate(delegate);
        ObjC.release(stream);
        ObjC.release(delegate);
        ObjC.release(configuration);
        ObjC.release(filter);
        ObjC.release(content);
        Dispatch.release(queue);
        stream = MemorySegment.NULL;
        delegate = MemorySegment.NULL;
        configuration = MemorySegment.NULL;
        filter = MemorySegment.NULL;
        content = MemorySegment.NULL;
        queue = MemorySegment.NULL;
        started = false;
        outputAdded = false;
    }
}
