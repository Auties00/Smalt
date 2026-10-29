package com.github.auties00.smalt.capture;

import java.util.Objects;
import java.util.Optional;

/**
 * A change of the cameras or screens, reported to the {@link VideoDeviceListener}s.
 *
 * <p>Events carry device identifiers rather than {@link CaptureDevice} snapshots, because a removed
 * device can no longer be described; a listener that needs the new state lists the devices again with
 * {@link VideoDevices#cameras()} or {@link VideoDevices#screens()}. Camera and screen identifiers are
 * unique only among the devices of their kind, so each kind has its own events. Instances are immutable
 * and thread-safe.
 */
public sealed interface VideoDeviceEvent permits VideoDeviceEvent.CameraAdded, VideoDeviceEvent.CameraRemoved,
        VideoDeviceEvent.DefaultCameraChanged, VideoDeviceEvent.ScreenAdded, VideoDeviceEvent.ScreenRemoved,
        VideoDeviceEvent.ScreenChanged {
    /**
     * A camera became available: it was plugged in or enabled.
     *
     * @param cameraId the identifier of the camera, as {@link CaptureDevice#id()} returns it
     */
    record CameraAdded(String cameraId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public CameraAdded {
            Objects.requireNonNull(cameraId, "cameraId");
        }
    }

    /**
     * A camera became unavailable: it was unplugged or disabled. A session open on it ends: its
     * {@link VideoCaptureSession#read()} returns {@code null} or fails with
     * {@link com.github.auties00.smalt.MediaException.Io}.
     *
     * @param cameraId the identifier of the camera
     */
    record CameraRemoved(String cameraId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public CameraRemoved {
            Objects.requireNonNull(cameraId, "cameraId");
        }
    }

    /**
     * The camera that {@link VideoDevices#defaultCamera()} returns changed.
     *
     * <p>A session keeps the camera it opened; an application that follows the default reopens its
     * capture on this event.
     *
     * @param cameraId the identifier of the new default camera, or empty when no camera is left
     */
    record DefaultCameraChanged(Optional<String> cameraId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public DefaultCameraChanged {
            Objects.requireNonNull(cameraId, "cameraId");
        }
    }

    /**
     * A screen became available: a monitor was connected or enabled.
     *
     * @param screenId the identifier of the screen
     */
    record ScreenAdded(String screenId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public ScreenAdded {
            Objects.requireNonNull(screenId, "screenId");
        }
    }

    /**
     * A screen became unavailable: a monitor was disconnected or disabled. A session open on it ends.
     *
     * @param screenId the identifier of the screen
     */
    record ScreenRemoved(String screenId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public ScreenRemoved {
            Objects.requireNonNull(screenId, "screenId");
        }
    }

    /**
     * A screen that stays available changed its description: its position, size, name or primary
     * flag. A session open on it keeps the picture size it opened with and rescales the new image.
     *
     * @param screenId the identifier of the screen
     */
    record ScreenChanged(String screenId) implements VideoDeviceEvent {
        /**
         * Validates the event.
         */
        public ScreenChanged {
            Objects.requireNonNull(screenId, "screenId");
        }
    }
}
