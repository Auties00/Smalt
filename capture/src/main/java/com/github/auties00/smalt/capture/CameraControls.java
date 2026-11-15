package com.github.auties00.smalt.capture;

import com.github.auties00.smalt.MediaException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Reads and adjusts the image and lens settings of an open camera: exposure, focus, zoom, white balance,
 * power-line frequency and the others of {@link CameraControl}.
 *
 * <p>The controls belong to a {@link CameraCaptureSession} and work while it is open; once it is closed
 * every method except {@link #capabilities()} and {@link #capability(CameraControl)} throws
 * {@link IllegalStateException}. A change applies to the camera itself, so it may outlive the session
 * and affect other applications using the camera, as the operating system's own camera settings do.
 * Setting a manual value switches the control out of its automatic mode; switching the automatic mode
 * off keeps the value the camera was using. This class is thread-safe.
 */
public interface CameraControls {
    /**
     * Lists the controls the camera offers.
     *
     * @return the capabilities, in the order of {@link CameraControl}, without duplicates; empty when the
     * camera or the platform offers no control
     */
    List<Capability> capabilities();

    /**
     * Returns what the camera offers for a control.
     *
     * @implSpec The default implementation searches {@link #capabilities()}.
     *
     * @param control the setting to describe, read or change
     * @return the capability, or empty when the camera does not offer the control
     */
    default Optional<Capability> capability(CameraControl control) {
        Objects.requireNonNull(control, "control");
        return capabilities().stream().filter(capability -> capability.control() == control).findFirst();
    }

    /**
     * Reads the current state of a control.
     *
     * @param control the setting to describe, read or change
     * @return the state
     * @throws IllegalStateException if the session is closed
     * @throws MediaException.Unsupported if the camera does not offer the control
     * @throws MediaException.Io if the camera refuses the request
     */
    State get(CameraControl control);

    /**
     * Sets a control to a manual value, leaving its automatic mode.
     *
     * <p>A continuous control may round the value to its {@link Range#step() step}; a control with
     * {@link Range#values() discrete values} takes exactly one of them.
     *
     * @param control the setting to describe, read or change
     * @param value the value, in the control's unit
     * @throws IllegalArgumentException if {@code value} lies outside the control's range or is not one of
     * its discrete values
     * @throws IllegalStateException if the session is closed
     * @throws MediaException.Unsupported if the camera does not offer a manual value for the control
     * @throws MediaException.Io if the camera refuses the request
     */
    void set(CameraControl control, long value);

    /**
     * Switches the automatic mode of a control on or off.
     *
     * @param control the setting to describe, read or change
     * @param automatic whether the camera adjusts the control itself; {@code false} keeps the value the
     * camera is using
     * @throws IllegalStateException if the session is closed
     * @throws MediaException.Unsupported if the camera has no automatic mode for the control
     * @throws MediaException.Io if the camera refuses the request
     */
    void setAutomatic(CameraControl control, boolean automatic);

    /**
     * Describes what a camera offers for one control.
     *
     * @param control the setting to describe, read or change
     * @param range the values a manual setting accepts, or empty when the control has only its automatic
     * mode to switch, as the focus, exposure and white balance of a camera on macOS
     * @param automatic whether the control has an automatic mode
     */
    record Capability(CameraControl control, Optional<Range> range, boolean automatic) {
        /**
         * Validates the capability.
         *
         * @throws IllegalArgumentException if the control has neither a range nor an automatic mode
         */
        public Capability {
            Objects.requireNonNull(control, "control");
            Objects.requireNonNull(range, "range");
            if (range.isEmpty() && !automatic) {
                throw new IllegalArgumentException(control + " offers neither a value nor an automatic mode");
            }
        }
    }

    /**
     * The values a control accepts, in its unit.
     *
     * @param minimum the smallest value
     * @param maximum the largest value, at least {@code minimum}
     * @param step the distance between two accepted values of a continuous range, positive; {@code 0}
     * when {@code values} lists them
     * @param defaultValue the value of the camera's factory setting
     * @param values the accepted values in ascending order when the control takes only some values of the
     * range (the power-line frequency, the exposure of a camera that steps it in powers of two), empty
     * for a continuous range
     */
    record Range(long minimum, long maximum, long step, long defaultValue, List<Long> values) {
        /**
         * Validates the range and copies the values.
         *
         * @throws IllegalArgumentException if {@code maximum} is less than {@code minimum}, a continuous range
         * has no positive step, discrete values come with a step other than {@code 0}, the values are not
         * ascending within the range, or the range does not accept the default value
         * @throws NullPointerException if a value is {@code null}
         */
        public Range {
            values = List.copyOf(values);
            if (maximum < minimum) {
                throw new IllegalArgumentException("maximum " + maximum + " is less than minimum " + minimum);
            }
            if (values.isEmpty() ? step <= 0 : step != 0) {
                throw new IllegalArgumentException("invalid step " + step);
            }
            for (var index = 0; index < values.size(); index++) {
                long value = values.get(index);
                if (value < minimum || value > maximum || (index > 0 && value <= values.get(index - 1))) {
                    throw new IllegalArgumentException("values " + values + " are not ascending within the range");
                }
            }
            if (defaultValue < minimum || defaultValue > maximum
                    || (!values.isEmpty() && !values.contains(defaultValue))) {
                throw new IllegalArgumentException("the default " + defaultValue + " is not an accepted value");
            }
        }

        /**
         * Returns whether a manual setting accepts a value.
         *
         * @param value the value to test, in the control's unit
         * @return whether it lies within the range and, for discrete values, is one of them
         */
        public boolean accepts(long value) {
            return value >= minimum && value <= maximum && (values.isEmpty() || values.contains(value));
        }
    }

    /**
     * The current state of a control.
     *
     * @param value the value the camera reports, in the control's unit, or empty when the platform does
     * not report it (a control offered only as an automatic mode)
     * @param automatic whether the control is in its automatic mode
     */
    record State(OptionalLong value, boolean automatic) {
        /**
         * Validates the state.
         */
        public State {
            Objects.requireNonNull(value, "value");
        }
    }
}
