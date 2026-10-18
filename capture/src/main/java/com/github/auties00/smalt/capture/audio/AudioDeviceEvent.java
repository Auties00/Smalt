package com.github.auties00.smalt.capture.audio;

import java.util.Objects;
import java.util.Optional;

/**
 * A change of the audio devices, reported to the {@link AudioDeviceListener}s.
 *
 * <p>Events carry device identifiers rather than {@link AudioDevice} snapshots, because a removed
 * device can no longer be described; a listener that needs the new state lists the devices again
 * with {@link AudioDevices#inputs()} or {@link AudioDevices#outputs()}. Instances are immutable
 * and thread-safe.
 */
public sealed interface AudioDeviceEvent permits AudioDeviceEvent.DefaultChanged, AudioDeviceEvent.DeviceAdded, AudioDeviceEvent.DeviceRemoved {
    /**
     * The system's default device of a direction changed.
     *
     * <p>A session keeps the device it opened; an application that follows the default reopens its
     * stream on this event.
     *
     * @param direction the direction whose default changed
     * @param deviceId the identifier of the new default device, or empty when no device of that
     * direction is left
     */
    record DefaultChanged(AudioDevice.Direction direction, Optional<String> deviceId) implements AudioDeviceEvent {
        /**
         * Validates the event.
         */
        public DefaultChanged {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(deviceId, "deviceId");
        }
    }

    /**
     * A device became available: it was plugged in or enabled.
     *
     * @param deviceId the identifier of the device
     */
    record DeviceAdded(String deviceId) implements AudioDeviceEvent {
        /**
         * Validates the event.
         */
        public DeviceAdded {
            Objects.requireNonNull(deviceId, "deviceId");
        }
    }

    /**
     * A device became unavailable: it was unplugged or disabled. A session open on it fails with
     * {@link com.github.auties00.smalt.MediaException.Io}.
     *
     * @param deviceId the identifier of the device
     */
    record DeviceRemoved(String deviceId) implements AudioDeviceEvent {
        /**
         * Validates the event.
         */
        public DeviceRemoved {
            Objects.requireNonNull(deviceId, "deviceId");
        }
    }
}
