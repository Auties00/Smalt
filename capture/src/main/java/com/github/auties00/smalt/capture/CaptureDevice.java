package com.github.auties00.smalt.capture;

import java.util.List;
import java.util.Objects;

/**
 * A video capture device: a camera or a screen.
 *
 * <p>Devices are listed by {@link VideoDevices#cameras()} and {@link VideoDevices#screens()} and opened
 * by {@link VideoDevices#openCapture(Camera, CaptureMode)} and
 * {@link VideoDevices#openCapture(Screen, int)}. The {@link #id()} is the operating system's stable
 * identifier of the device, suitable for remembering a user's choice; the {@link #name()} is its
 * human-readable label, which several devices may share. Instances are immutable and thread-safe.
 */
public sealed interface CaptureDevice permits CaptureDevice.Camera, CaptureDevice.Screen {
    /**
     * {@return the operating system's identifier of the device, unique among the devices of its kind}
     */
    String id();

    /**
     * {@return the human-readable label of the device}
     */
    String name();

    /**
     * A camera and the capture modes it offers.
     *
     * @param id the operating system's identifier of the camera: a symbolic link on Windows, a unique
     * id on macOS, a device path on Linux
     * @param name the camera's label
     * @param modes the capture modes the camera offers, largest first; empty when the driver does not
     * enumerate them
     */
    record Camera(String id, String name, List<CaptureMode> modes) implements CaptureDevice {
        /**
         * Validates the description and copies the modes.
         *
         * @throws NullPointerException if a mode is {@code null}
         */
        public Camera {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            modes = List.copyOf(modes);
        }
    }

    /**
     * A screen (a monitor) and its position on the virtual desktop.
     *
     * @param id the operating system's identifier of the screen
     * @param name the screen's label
     * @param x the left edge on the virtual desktop, in pixels
     * @param y the top edge on the virtual desktop, in pixels
     * @param width the width in pixels, positive
     * @param height the height in pixels, positive
     * @param primary whether the screen is the one the operating system designates as its main display
     */
    record Screen(String id, String name, int x, int y, int width, int height, boolean primary) implements CaptureDevice {
        /**
         * Validates the description.
         *
         * @throws IllegalArgumentException if a dimension is not positive
         */
        public Screen {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid screen size " + width + "x" + height);
            }
        }
    }
}
