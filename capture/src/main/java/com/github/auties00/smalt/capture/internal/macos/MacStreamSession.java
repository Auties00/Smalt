package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.internal.FramePacing;
import com.github.auties00.smalt.track.Rational;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Captures a display with ScreenCaptureKit (macOS 12.3 and later).
 *
 * <p>Opening a screen checks the Screen Recording permission, finds the display among the
 * {@code SCShareableContent} displays, and starts an {@code SCStream} over an
 * {@code SCContentFilter} of the whole display. The stream is configured for the display's pixel
 * size, NV12 ({@code '420v'}) output converted by the system with the BT.709 matrix in the sRGB
 * color space, the cursor drawn, and a minimum frame interval of one frame at the requested rate.
 * ScreenCaptureKit sends a frame only when the display changes, so the session repeats the last
 * frame when none arrives within a frame interval, keeping the requested rate. Frames whose
 * {@code SCStreamFrameInfoStatus} is not complete are skipped. A stream stopped by the user ends
 * the session; a stream stopped by an error fails it.
 */
final class MacStreamSession extends MacCaptureSession implements DelegateSink {
    /**
     * How long the shareable content, which may show the permission prompt, may take.
     */
    private static final long CONTENT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /**
     * How long starting the stream may take.
     */
    private static final long START_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(15);

    /**
     * How long stopping the stream may take.
     */
    private static final long STOP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(5);

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
     * Creates a session.
     *
     * @param width the picture width, even
     * @param height the picture height, even
     * @param frameRate the frames per second
     */
    private MacStreamSession(ObjcBridge bridge, CaptureDevice.Screen screen, int width, int height, Rational frameRate) {
        super(screen, width, height, FramePacing.periodNanos(frameRate));
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
     * @param frameRate the frames per second
     * @return the running session
     * @throws MediaException.Io if the permission is missing, the screen is gone, or the
     * stream does not start
     * @throws MediaException.Unsupported if the screen is smaller than 2x2 pixels
     */
    static MacStreamSession open(ObjcBridge bridge, CaptureDevice.Screen screen, Rational frameRate) {
        MacDisplays.requireCapturePermission();
        var displayId = MacDisplays.displayId(screen);
        var width = screen.width() & ~1;
        var height = screen.height() & ~1;
        if (width == 0 || height == 0) {
            throw new MediaException.Unsupported("capture size", "the screen " + screen.name() + " is smaller than 2x2 pixels");
        }
        var session = new MacStreamSession(bridge, screen, width, height, frameRate);
        try {
            session.start(displayId, frameRate);
            return session;
        } catch (Throwable failure) {
            session.close();
            throw failure;
        }
    }

    /**
     * Finds the display, configures the stream and starts it.
     *
     * @param displayId the display
     * @param frameRate the frames per second
     * @throws MediaException.Io if a step fails
     */
    private void start(int displayId, Rational frameRate) {
        var listed = bridge.await(ObjcBridge.Handler.OBJECT_ERROR, CONTENT_TIMEOUT_MILLIS, "listing the screens",
                block -> ObjC.call(ScreenCaptureKit.SHAREABLE_CONTENT, ScreenCaptureKit.GET_SHAREABLE_CONTENT, block));
        content = listed.object();
        if (listed.error() != null || ObjC.isNull(content)) {
            throw failure("cannot list the screens available for capture", listed.error());
        }
        var display = find(content, displayId);
        if (ObjC.isNull(display)) {
            throw new MediaException.Io("the screen " + device().name() + " is not available for capture",
                    new IOException("no SCDisplay has the id " + Integer.toUnsignedString(displayId)));
        }
        var excluded = ObjC.id(ScreenCaptureKit.NS_ARRAY, ScreenCaptureKit.ARRAY);
        filter = ObjC.id(ObjC.id(ScreenCaptureKit.CONTENT_FILTER, ObjC.ALLOC), ScreenCaptureKit.INIT_WITH_DISPLAY, display, excluded);
        configuration = ObjC.newInstance(ScreenCaptureKit.STREAM_CONFIGURATION);
        if (ObjC.isNull(filter) || ObjC.isNull(configuration)) {
            throw new MediaException.Io("cannot configure the screen capture", new IOException("SCContentFilter or SCStreamConfiguration init failed"));
        }
        ObjC.callWithLong(configuration, ScreenCaptureKit.SET_WIDTH, width());
        ObjC.callWithLong(configuration, ScreenCaptureKit.SET_HEIGHT, height());
        ObjC.callWithTime(configuration, ScreenCaptureKit.SET_MINIMUM_FRAME_INTERVAL, CmTime.frameDuration(frameRate));
        ObjC.callWithInt(configuration, ScreenCaptureKit.SET_PIXEL_FORMAT, FourCc.NV12_VIDEO_RANGE);
        ObjC.callWithBool(configuration, ScreenCaptureKit.SET_SHOWS_CURSOR, true);
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
        if (reason == null || reason.is(errorDomain(), ScreenCaptureKit.ERROR_USER_STOPPED)) {
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
