package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraCaptureSession;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.CaptureMode;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Captures a camera with AVFoundation.
 *
 * <p>Opening a camera checks the camera permission (asking the user when it was never asked),
 * finds the device by its {@code uniqueID}, and builds an {@code AVCaptureSession} with an
 * {@code AVCaptureDeviceInput} and an {@code AVCaptureVideoDataOutput}. The output delivers NV12
 * ({@code '420v'}) when the device offers it, else BGRA, discards late frames, and calls the
 * {@link ObjcBridge} delegate on a private serial queue; the delegate forwards each sample buffer
 * to {@link #sampleBuffer(MemorySegment)}, which copies it into the
 * {@linkplain com.github.auties00.smalt.capture.internal.FrameExchange frame exchange}. The device's
 * active format and frame duration are set from the requested mode while the device is locked for
 * configuration. A session whose {@code AVCaptureSession} stops running (the camera
 * was unplugged or taken over) ends, and {@link #read()} returns {@code null}.
 */
final class MacCameraSession extends MacCaptureSession implements CameraCaptureSession, DelegateSink {
    /**
     * How long the user may take to answer the camera permission prompt.
     */
    private static final long ACCESS_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(2);

    /**
     * The bridge serving the delegate.
     */
    private final ObjcBridge bridge;

    /**
     * The retained {@code AVCaptureDevice}.
     */
    private final MemorySegment device;

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
     * The controls of the camera, set once the capture started.
     */
    private volatile AvFoundationControls controls;

    /**
     * The retained {@code AVCaptureDeviceInput}, or {@code NULL}.
     */
    private MemorySegment input;

    /**
     * The owned {@code AVCaptureSession}, or {@code NULL}.
     */
    private MemorySegment captureSession;

    /**
     * The owned {@code AVCaptureVideoDataOutput}, or {@code NULL}.
     */
    private MemorySegment output;

    /**
     * The owned delegate instance, or {@code NULL}.
     */
    private MemorySegment delegate;

    /**
     * The owned serial queue receiving the frames, or {@code NULL}.
     */
    private MemorySegment queue;

    /**
     * Creates a session that owns a retained device.
     *
     * @param camera the camera description
     * @param device the retained device, released by this session
     * @param width the picture width, even
     * @param height the picture height, even
     */
    private MacCameraSession(ObjcBridge bridge, CaptureDevice.Camera camera, MemorySegment device, int width, int height) {
        super(camera, width, height, 0);
        this.bridge = bridge;
        this.device = device;
        this.reader = new PixelBufferReader();
        this.key = bridge.register(this);
        this.input = MemorySegment.NULL;
        this.captureSession = MemorySegment.NULL;
        this.output = MemorySegment.NULL;
        this.delegate = MemorySegment.NULL;
        this.queue = MemorySegment.NULL;
    }

    /**
     * Opens a camera.
     *
     * <p>Runs on a platform thread inside an autorelease pool.
     *
     * @param mode the requested mode, or {@code null} for the device's active one
     * @return the running session
     * @throws MediaException.Io if access is denied, the camera is gone or does not start
     * @throws MediaException.Unsupported if the camera cannot capture in {@code mode}
     */
    static MacCameraSession open(ObjcBridge bridge, CaptureDevice.Camera camera, CaptureMode mode) {
        authorize(bridge);
        var id = CoreFoundation.createString(camera.id());
        MemorySegment device;
        try {
            device = ObjC.retain(ObjC.id(AvFoundation.CAPTURE_DEVICE, AvFoundation.DEVICE_WITH_UNIQUE_ID, id));
        } finally {
            CoreFoundation.release(id);
        }
        if (ObjC.isNull(device)) {
            throw new MediaException.Io("the camera " + camera.name() + " is not connected",
                    new IOException("no AVCaptureDevice has the unique id " + camera.id()));
        }
        MacCameraSession session = null;
        try {
            var formatArray = ObjC.id(device, AvFoundation.FORMATS);
            var formats = MacCameras.formats(formatArray);
            var selection = mode == null ? activeSelection(camera, device, formatArray, formats)
                    : CameraFormats.select(formats, mode).orElseThrow(() -> new MediaException.Unsupported("capture mode",
                    "the camera " + camera.name() + " cannot capture at " + mode.width() + "x" + mode.height()
                            + " and " + mode.frameRate() + " frames per second"));
            var width = selection.mode().width() & ~1;
            var height = selection.mode().height() & ~1;
            if (width == 0 || height == 0) {
                throw new MediaException.Unsupported("capture mode", "the camera " + camera.name()
                        + " reports a " + selection.mode().width() + "x" + selection.mode().height() + " format");
            }
            session = new MacCameraSession(bridge, camera, device, width, height);
            session.start(ObjC.element(formatArray, selection.index()), selection.frameDuration());
            session.controls = AvFoundationControls.open(device);
            return session;
        } catch (Throwable failure) {
            if (session != null) {
                session.close();
            } else {
                ObjC.release(device);
            }
            throw failure;
        }
    }

    /**
     * Checks the camera permission, asking the user when it was never asked.
     *
     * @throws MediaException.Io if access is denied or restricted, or the prompt is not answered
     */
    private static void authorize(ObjcBridge bridge) {
        var status = ObjC.integer(AvFoundation.CAPTURE_DEVICE, AvFoundation.AUTHORIZATION_STATUS, AvFoundation.MEDIA_TYPE_VIDEO);
        if (status == AvFoundation.AUTHORIZATION_NOT_DETERMINED) {
            var completion = bridge.await(ObjcBridge.Handler.FLAG, ACCESS_TIMEOUT_MILLIS, "the camera permission prompt",
                    block -> ObjC.call(AvFoundation.CAPTURE_DEVICE, AvFoundation.REQUEST_ACCESS, AvFoundation.MEDIA_TYPE_VIDEO, block));
            if (!completion.flag()) {
                throw denied();
            }
        } else if (status == AvFoundation.AUTHORIZATION_DENIED) {
            throw denied();
        } else if (status == AvFoundation.AUTHORIZATION_RESTRICTED) {
            throw new MediaException.Io("camera access is restricted on this Mac by a management profile or parental controls",
                    new IOException("AVAuthorizationStatusRestricted"));
        }
    }

    /**
     * Builds the failure raised when the user denied camera access.
     *
     * @return the failure
     */
    private static MediaException.Io denied() {
        return new MediaException.Io("camera access is denied to this application; allow it in System Settings"
                + " > Privacy & Security > Camera", new IOException("AVAuthorizationStatusDenied"));
    }

    /**
     * Describes the device's active format and frame duration as a selection.
     *
     * @param camera the camera, for failure messages
     * @param formatArray the device's {@code formats} array
     * @param formats the formats read from it
     * @return the selection
     * @throws MediaException.Io if the device reports no usable format
     */
    private static CameraFormats.Selection activeSelection(CaptureDevice.Camera camera, MemorySegment device,
                                                           MemorySegment formatArray, List<CameraFormats.Format> formats) {
        var active = ObjC.id(device, AvFoundation.ACTIVE_FORMAT);
        var index = ObjC.isNull(active) ? -1 : MacCameras.indexOf(formatArray, active);
        var duration = ObjC.time(device, AvFoundation.ACTIVE_MIN_FRAME_DURATION);
        var selection = CameraFormats.active(formats, index, duration);
        if (selection.isEmpty()) {
            var modes = CameraFormats.modes(formats);
            if (!modes.isEmpty()) {
                selection = CameraFormats.select(formats, modes.getFirst());
            }
        }
        return selection.orElseThrow(() -> new MediaException.Io("the camera " + camera.name() + " reports no usable format",
                new IOException("no AVCaptureDeviceFormat has a size and a frame rate")));
    }

    /**
     * Builds and starts the capture session.
     *
     * @param format the {@code AVCaptureDeviceFormat} to activate
     * @param duration the frame duration to configure
     * @throws MediaException.Io if a step fails or the session does not start
     */
    private void start(MemorySegment format, CmTime duration) {
        try (var arena = Arena.ofConfined()) {
            var error = arena.allocate(ADDRESS);
            input = ObjC.retain(ObjC.id(AvFoundation.DEVICE_INPUT, AvFoundation.INPUT_WITH_DEVICE, device, error));
            if (ObjC.isNull(input)) {
                throw failure("cannot open the camera", error);
            }
            captureSession = ObjC.newInstance(AvFoundation.CAPTURE_SESSION);
            output = ObjC.newInstance(AvFoundation.VIDEO_DATA_OUTPUT);
            if (ObjC.isNull(captureSession) || ObjC.isNull(output)) {
                throw new MediaException.Io("cannot create the capture session", new IOException("AVCaptureSession init failed"));
            }
            var locked = false;
            ObjC.call(captureSession, AvFoundation.BEGIN_CONFIGURATION);
            try {
                if (!ObjC.bool(captureSession, AvFoundation.CAN_ADD_INPUT, input)) {
                    throw new MediaException.Io("the camera cannot be added to a capture session",
                            new IOException("canAddInput: returned NO"));
                }
                ObjC.call(captureSession, AvFoundation.ADD_INPUT, input);
                if (!ObjC.bool(captureSession, AvFoundation.CAN_ADD_OUTPUT, output)) {
                    throw new MediaException.Io("the camera output cannot be added to a capture session",
                            new IOException("canAddOutput: returned NO"));
                }
                ObjC.call(captureSession, AvFoundation.ADD_OUTPUT, output);
                configureOutput();
                error.set(ADDRESS, 0, MemorySegment.NULL);
                locked = ObjC.bool(device, AvFoundation.LOCK_FOR_CONFIGURATION, error);
                if (!locked) {
                    throw failure("cannot configure the camera", error);
                }
                ObjC.call(device, AvFoundation.SET_ACTIVE_FORMAT, format);
                setFrameDuration(duration);
                applyVideoSettings();
            } catch (Throwable failure) {
                if (locked) {
                    ObjC.call(device, AvFoundation.UNLOCK_FOR_CONFIGURATION);
                }
                throw failure;
            } finally {
                ObjC.call(captureSession, AvFoundation.COMMIT_CONFIGURATION);
            }
            try {
                clock = new CaptureClock(CoreMedia.hostTime().toMicros(), exchange.micros(System.nanoTime()));
                ObjC.call(captureSession, AvFoundation.START_RUNNING);
            } finally {
                ObjC.call(device, AvFoundation.UNLOCK_FOR_CONFIGURATION);
            }
            if (!ObjC.bool(captureSession, AvFoundation.IS_RUNNING)) {
                throw new MediaException.Io("the camera did not start", new IOException("AVCaptureSession is not running"));
            }
        }
    }

    /**
     * Sets both frame durations of the device to one value.
     *
     * <p>The limit that moves away from the other is set first, so the minimum duration never
     * exceeds the maximum one in between.
     *
     * @param duration the frame duration, admitted by the active format
     */
    private void setFrameDuration(CmTime duration) {
        var currentMax = ObjC.time(device, AvFoundation.ACTIVE_MAX_FRAME_DURATION);
        if (currentMax.isNumeric() && duration.compareDuration(currentMax) > 0) {
            ObjC.callWithTime(device, AvFoundation.SET_ACTIVE_MAX_FRAME_DURATION, duration);
            ObjC.callWithTime(device, AvFoundation.SET_ACTIVE_MIN_FRAME_DURATION, duration);
        } else {
            ObjC.callWithTime(device, AvFoundation.SET_ACTIVE_MIN_FRAME_DURATION, duration);
            ObjC.callWithTime(device, AvFoundation.SET_ACTIVE_MAX_FRAME_DURATION, duration);
        }
    }

    /**
     * Configures the video data output's late frame policy, delegate and queue.
     */
    private void configureOutput() {
        ObjC.callWithBool(output, AvFoundation.SET_DISCARDS_LATE_FRAMES, true);
        queue = Dispatch.createSerialQueue();
        delegate = bridge.newDelegate(key);
        ObjC.call(output, AvFoundation.SET_SAMPLE_BUFFER_DELEGATE, delegate, queue);
    }

    /**
     * Requests the output's pixel format and size, once the device's format is active.
     *
     * <p>The size is requested explicitly ({@code kCVPixelBufferWidthKey},
     * {@code kCVPixelBufferHeightKey}) so that the output matches the session size even if the
     * capture session reconfigures the device's format; it never exceeds the active format, which
     * AVFoundation rejects.
     */
    private void applyVideoSettings() {
        var pixelFormat = preferredPixelFormat();
        try (var arena = Arena.ofConfined()) {
            var keys = arena.allocate(ADDRESS, 3);
            var values = arena.allocate(ADDRESS, 3);
            var count = 0;
            keys.setAtIndex(ADDRESS, count, CoreVideo.WIDTH_KEY);
            values.setAtIndex(ADDRESS, count++, ObjC.idWithInt(AvFoundation.NS_NUMBER, AvFoundation.NUMBER_WITH_UNSIGNED_INT, width()));
            keys.setAtIndex(ADDRESS, count, CoreVideo.HEIGHT_KEY);
            values.setAtIndex(ADDRESS, count++, ObjC.idWithInt(AvFoundation.NS_NUMBER, AvFoundation.NUMBER_WITH_UNSIGNED_INT, height()));
            if (pixelFormat != 0) {
                keys.setAtIndex(ADDRESS, count, CoreVideo.PIXEL_FORMAT_KEY);
                values.setAtIndex(ADDRESS, count++, ObjC.idWithInt(AvFoundation.NS_NUMBER, AvFoundation.NUMBER_WITH_UNSIGNED_INT, pixelFormat));
            }
            var settings = ObjC.idWithLong(AvFoundation.NS_DICTIONARY, AvFoundation.DICTIONARY_WITH_OBJECTS, values, keys, count);
            ObjC.call(output, AvFoundation.SET_VIDEO_SETTINGS, settings);
        }
    }

    /**
     * Picks the pixel format to request from the output.
     *
     * @return {@code '420v'} when available, else {@code 'BGRA'} when available, else {@code 0} to
     * keep the output's default
     */
    private int preferredPixelFormat() {
        var available = ObjC.id(output, AvFoundation.AVAILABLE_PIXEL_FORMATS);
        var count = ObjC.count(available);
        var bgra = false;
        for (var index = 0; index < count; index++) {
            var format = ObjC.unsignedInt(ObjC.element(available, index), AvFoundation.UNSIGNED_INT_VALUE);
            if (format == FourCc.NV12_VIDEO_RANGE) {
                return format;
            }
            bgra |= format == FourCc.BGRA;
        }
        return bgra ? FourCc.BGRA : 0;
    }

    @Override
    public CaptureDevice.Camera device() {
        return (CaptureDevice.Camera) super.device();
    }

    @Override
    public CameraControls controls() {
        return controls;
    }

    /**
     * Builds the failure of a call that reported an {@code NSError}.
     *
     * @param message what failed
     * @param error the out-parameter the call wrote
     * @return the failure
     */
    private static MediaException.Io failure(String message, MemorySegment error) {
        var nsError = NsError.read(error.get(ADDRESS, 0));
        return nsError != null ? nsError.toException(message) : new MediaException.Io(message, new IOException(message));
    }

    @Override
    public void sampleBuffer(MemorySegment sampleBuffer) {
        var mapping = clock;
        if (mapping == null || exchange.isClosed()) {
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
        exchange.finish();
    }

    @Override
    public void failed(Throwable failure) {
        exchange.fail(failure instanceof MediaException.Io io ? io
                : new MediaException.Io("camera capture failed", new IOException(String.valueOf(failure), failure)));
    }

    @Override
    boolean alive() {
        return ObjC.isNull(captureSession) || ObjC.bool(captureSession, AvFoundation.IS_RUNNING);
    }

    @Override
    void release() {
        var opened = controls;
        if (opened != null) {
            opened.close();
        }
        if (!ObjC.isNull(captureSession)) {
            ObjC.call(captureSession, AvFoundation.STOP_RUNNING);
        }
        if (!ObjC.isNull(output)) {
            ObjC.call(output, AvFoundation.SET_SAMPLE_BUFFER_DELEGATE, MemorySegment.NULL, MemorySegment.NULL);
        }
        if (!ObjC.isNull(queue)) {
            bridge.drain(queue);
        }
        bridge.unregister(key);
        bridge.detachDelegate(delegate);
        ObjC.release(delegate);
        ObjC.release(output);
        ObjC.release(input);
        ObjC.release(captureSession);
        ObjC.release(device);
        Dispatch.release(queue);
        delegate = MemorySegment.NULL;
        output = MemorySegment.NULL;
        input = MemorySegment.NULL;
        captureSession = MemorySegment.NULL;
        queue = MemorySegment.NULL;
    }
}
