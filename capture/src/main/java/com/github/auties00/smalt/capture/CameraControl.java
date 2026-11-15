package com.github.auties00.smalt.capture;

/**
 * An image or lens setting of a camera that {@link CameraControls} reads and adjusts.
 *
 * <p>Each control has a unit: a physical one where the operating systems agree on it (kelvin, hertz,
 * microseconds, arc seconds), and otherwise the device's own scale, whose range
 * {@link CameraControls.Range} reports. A camera offers a subset of the controls, which
 * {@link CameraControls#capabilities()} lists.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default}
 * branch.
 */
public enum CameraControl {
    /**
     * The brightness (black level), in the device's units.
     */
    BRIGHTNESS,

    /**
     * The contrast, in the device's units.
     */
    CONTRAST,

    /**
     * The hue, in the device's units (normally degrees, from -180 to 180, or hundredths of them).
     */
    HUE,

    /**
     * The saturation, in the device's units.
     */
    SATURATION,

    /**
     * The sharpness, in the device's units.
     */
    SHARPNESS,

    /**
     * The gamma, in the device's units.
     */
    GAMMA,

    /**
     * The analog or digital gain, in the device's units; the automatic mode is automatic gain control.
     */
    GAIN,

    /**
     * The backlight compensation, in the device's units, {@code 0} for off.
     */
    BACKLIGHT_COMPENSATION,

    /**
     * The white balance color temperature, in kelvin; the automatic mode is automatic white balance.
     */
    WHITE_BALANCE,

    /**
     * The mains frequency whose flicker the exposure avoids, in hertz: {@code 0} for no flicker
     * compensation, {@code 50} or {@code 60}; the automatic mode detects the frequency.
     */
    POWER_LINE_FREQUENCY,

    /**
     * The exposure time of a frame, in microseconds; the automatic mode is automatic exposure.
     */
    EXPOSURE,

    /**
     * The focus distance, in the device's units; the automatic mode is continuous autofocus.
     */
    FOCUS,

    /**
     * The optical or digital zoom, in the device's units, larger values magnifying more.
     */
    ZOOM,

    /**
     * The horizontal rotation of the camera, in arc seconds (1/3600 of a degree), positive to the right.
     */
    PAN,

    /**
     * The vertical rotation of the camera, in arc seconds (1/3600 of a degree), positive upward.
     */
    TILT
}
