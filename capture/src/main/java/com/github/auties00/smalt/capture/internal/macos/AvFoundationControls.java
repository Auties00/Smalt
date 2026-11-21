package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.internal.NativeCameraControls;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Reads and adjusts the controls an {@code AVCaptureDevice} offers on macOS.
 *
 * <p>macOS exposes the focus, exposure and white balance of a camera only as modes, so those controls
 * have no range: their automatic mode is the continuous one ({@code AVCaptureFocusModeContinuousAutoFocus},
 * {@code AVCaptureExposureModeContinuousAutoExposure}, {@code AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance})
 * and switching it off locks the current setting. Zoom is offered where the device answers
 * {@code videoZoomFactor}, as hundredths of the zoom factor. Every change is made between
 * {@code lockForConfiguration:} and {@code unlockForConfiguration}.
 */
final class AvFoundationControls extends NativeCameraControls {
    /**
     * The {@code Locked} value of the focus, exposure and white balance modes.
     */
    static final long MODE_LOCKED = 0;

    /**
     * The {@code ContinuousAuto} value of the focus, exposure and white balance modes.
     */
    static final long MODE_CONTINUOUS = 2;

    /**
     * The zoom units of a factor of 1.
     */
    static final long ZOOM_UNIT = 100;

    private static final MemorySegment RESPONDS_TO_SELECTOR = ObjC.selector("respondsToSelector:");

    private static final MemorySegment FOCUS_SUPPORTED = ObjC.selector("isFocusModeSupported:");

    private static final MemorySegment FOCUS_MODE = ObjC.selector("focusMode");

    private static final MemorySegment SET_FOCUS_MODE = ObjC.selector("setFocusMode:");

    private static final MemorySegment EXPOSURE_SUPPORTED = ObjC.selector("isExposureModeSupported:");

    private static final MemorySegment EXPOSURE_MODE = ObjC.selector("exposureMode");

    private static final MemorySegment SET_EXPOSURE_MODE = ObjC.selector("setExposureMode:");

    private static final MemorySegment WHITE_BALANCE_SUPPORTED = ObjC.selector("isWhiteBalanceModeSupported:");

    private static final MemorySegment WHITE_BALANCE_MODE = ObjC.selector("whiteBalanceMode");

    private static final MemorySegment SET_WHITE_BALANCE_MODE = ObjC.selector("setWhiteBalanceMode:");

    private static final MemorySegment ZOOM_FACTOR = ObjC.selector("videoZoomFactor");

    private static final MemorySegment SET_ZOOM_FACTOR = ObjC.selector("setVideoZoomFactor:");

    private static final MemorySegment MIN_ZOOM_FACTOR = ObjC.selector("minAvailableVideoZoomFactor");

    private static final MemorySegment MAX_ZOOM_FACTOR = ObjC.selector("maxAvailableVideoZoomFactor");

    /**
     * The retained {@code AVCaptureDevice}.
     */
    private final MemorySegment device;

    private AvFoundationControls(MemorySegment device, List<Capability> capabilities) {
        super(capabilities);
        this.device = device;
    }

    /**
     * Queries the controls of a camera.
     *
     * @param device the {@code AVCaptureDevice}, retained by the controls until they close
     * @return the controls
     */
    static AvFoundationControls open(MemorySegment device) {
        var capabilities = new ArrayList<Capability>();
        if (supportsModes(device, FOCUS_SUPPORTED)) {
            capabilities.add(new Capability(CameraControl.FOCUS, Optional.empty(), true));
        }
        if (supportsModes(device, EXPOSURE_SUPPORTED)) {
            capabilities.add(new Capability(CameraControl.EXPOSURE, Optional.empty(), true));
        }
        if (supportsModes(device, WHITE_BALANCE_SUPPORTED)) {
            capabilities.add(new Capability(CameraControl.WHITE_BALANCE, Optional.empty(), true));
        }
        zoom(device).ifPresent(range -> capabilities.add(new Capability(CameraControl.ZOOM, Optional.of(range), false)));
        return new AvFoundationControls(ObjC.retain(device), capabilities);
    }

    /**
     * Returns whether a device can switch a control between its locked and continuous modes.
     */
    private static boolean supportsModes(MemorySegment device, MemorySegment supported) {
        return responds(device, supported) && ObjC.bool(device, supported, MODE_LOCKED)
               && ObjC.bool(device, supported, MODE_CONTINUOUS);
    }

    /**
     * Returns the zoom range of a device that answers the zoom factor messages.
     */
    private static Optional<CameraControls.Range> zoom(MemorySegment device) {
        if (!responds(device, ZOOM_FACTOR) || !responds(device, SET_ZOOM_FACTOR) || !responds(device, MIN_ZOOM_FACTOR)
                || !responds(device, MAX_ZOOM_FACTOR)) {
            return Optional.empty();
        }
        var minimum = Math.round(ObjC.real(device, MIN_ZOOM_FACTOR) * ZOOM_UNIT);
        var maximum = Math.round(ObjC.real(device, MAX_ZOOM_FACTOR) * ZOOM_UNIT);
        if (minimum <= 0 || maximum <= minimum) {
            return Optional.empty();
        }
        return Optional.of(new CameraControls.Range(minimum, maximum, 1, Math.max(minimum, Math.min(maximum, ZOOM_UNIT)),
                List.of()));
    }

    /**
     * Returns whether an object answers a message.
     */
    private static boolean responds(MemorySegment object, MemorySegment selector) {
        return ObjC.bool(object, RESPONDS_TO_SELECTOR, selector);
    }

    @Override
    protected State read(Capability capability) {
        return PlatformThreads.call(() -> switch (capability.control()) {
            case FOCUS -> new State(OptionalLong.empty(), ObjC.integer(device, FOCUS_MODE) != MODE_LOCKED);
            case EXPOSURE -> new State(OptionalLong.empty(), ObjC.integer(device, EXPOSURE_MODE) != MODE_LOCKED);
            case WHITE_BALANCE -> new State(OptionalLong.empty(), ObjC.integer(device, WHITE_BALANCE_MODE) != MODE_LOCKED);
            case ZOOM -> new State(OptionalLong.of(Math.round(ObjC.real(device, ZOOM_FACTOR) * ZOOM_UNIT)), false);
            default -> throw new AssertionError(capability.control());
        });
    }

    @Override
    protected void write(Capability capability, long value) {
        configure(() -> ObjC.callWithDouble(device, SET_ZOOM_FACTOR, value / (double) ZOOM_UNIT));
    }

    @Override
    protected void writeAutomatic(Capability capability, boolean automatic) {
        var mode = automatic ? MODE_CONTINUOUS : MODE_LOCKED;
        var setter = switch (capability.control()) {
            case FOCUS -> SET_FOCUS_MODE;
            case EXPOSURE -> SET_EXPOSURE_MODE;
            case WHITE_BALANCE -> SET_WHITE_BALANCE_MODE;
            default -> throw new AssertionError(capability.control());
        };
        configure(() -> ObjC.callWithLong(device, setter, mode));
    }

    /**
     * Runs a change with the device locked for configuration.
     *
     * @throws MediaException.Io if the device cannot be locked
     */
    private void configure(Runnable change) {
        PlatformThreads.run(() -> {
            try (var arena = Arena.ofConfined()) {
                var error = arena.allocate(ADDRESS);
                if (!ObjC.bool(device, AvFoundation.LOCK_FOR_CONFIGURATION, error)) {
                    var reason = NsError.read(error.get(ADDRESS, 0));
                    throw reason == null
                            ? new MediaException.Io("cannot configure the camera", new IOException("lockForConfiguration: failed"))
                            : reason.toException("cannot configure the camera");
                }
            }
            try {
                change.run();
            } finally {
                ObjC.call(device, AvFoundation.UNLOCK_FOR_CONFIGURATION);
            }
        });
    }

    @Override
    protected void release() {
        ObjC.release(device);
    }
}
