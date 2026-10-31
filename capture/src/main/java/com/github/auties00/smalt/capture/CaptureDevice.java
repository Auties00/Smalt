package com.github.auties00.smalt.capture;

import java.util.List;
import java.util.Objects;

/**
 * A video capture device: a camera, a screen or an application window.
 *
 * <p>Devices are listed by {@link VideoDevices#cameras()}, {@link VideoDevices#screens()} and
 * {@link VideoDevices#windows()} and opened by {@link VideoDevices#openCapture(Camera, CaptureMode)},
 * {@link VideoDevices#openCapture(Screen, ScreenCaptureOptions)} and
 * {@link VideoDevices#openCapture(Window, ScreenCaptureOptions)}. The {@link #id()} is the operating
 * system's identifier of the device: stable for cameras and screens, suitable for remembering a user's
 * choice, and valid for a window only while the window exists. The {@link #name()} is its human-readable
 * label, which several devices may share. Instances are immutable and thread-safe.
 */
public sealed interface CaptureDevice permits CaptureDevice.Camera, CaptureDevice.Screen, CaptureDevice.Window {
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
     * @param primary whether the screen is the one the operating system designates as its main display;
     * on Wayland, which designates none, the first output
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

    /**
     * A top-level window of an application, and its place on the virtual desktop when it was listed.
     *
     * <p>The bounds are those the window had when {@link VideoDevices#windows()} listed it; a window moves
     * and resizes freely, and a capture follows it. They are in the same units as the bounds of a
     * {@link Screen} on the same platform.
     *
     * @param id the operating system's identifier of the window: the window handle on Windows, the
     * {@code CGWindowID} on macOS, the X11 window id on Linux, as a decimal number
     * @param name the window's title, empty when it has none
     * @param application the name of the application that owns the window, empty when unknown
     * @param x the left edge on the virtual desktop
     * @param y the top edge on the virtual desktop
     * @param width the width, positive
     * @param height the height, positive
     */
    record Window(String id, String name, String application, int x, int y, int width, int height)
            implements CaptureDevice {
        /**
         * Validates the description.
         *
         * @throws IllegalArgumentException if a dimension is not positive
         */
        public Window {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(application, "application");
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("invalid window size " + width + "x" + height);
            }
        }
    }
}
