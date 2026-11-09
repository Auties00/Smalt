package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.internal.NativeCameraControls;

import java.lang.foreign.Arena;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Reads and adjusts the controls of a Video4Linux2 camera with {@code VIDIOC_QUERYCTRL},
 * {@code VIDIOC_QUERYMENU}, {@code VIDIOC_G_CTRL} and {@code VIDIOC_S_CTRL}.
 *
 * <p>Each {@link CameraControl} maps to a value control and, where the driver has one, the control that
 * switches its automatic mode: a boolean ({@code V4L2_CID_AUTOGAIN}, {@code V4L2_CID_AUTO_WHITE_BALANCE},
 * {@code V4L2_CID_FOCUS_AUTO}, {@code V4L2_CID_HUE_AUTO}), the {@code V4L2_CID_EXPOSURE_AUTO} menu, or the
 * automatic entry of the power-line frequency menu. Exposure is converted from the driver's units of 100
 * microseconds; pan and tilt are already in arc seconds. Disabled controls are left out, and so are
 * read-only ones without an automatic mode.
 */
final class V4l2Controls extends NativeCameraControls {
    /**
     * The microseconds of one unit of {@code V4L2_CID_EXPOSURE_ABSOLUTE}.
     */
    private static final long EXPOSURE_UNIT_MICROS = 100;

    /**
     * The frequencies of the power-line menu entries {@code DISABLED}, {@code 50HZ} and {@code 60HZ}.
     */
    private static final long[] FREQUENCIES = {0, 50, 60};

    private final V4l2Device device;

    private final Map<CameraControl, Binding> bindings;

    private V4l2Controls(V4l2Device device, List<Capability> capabilities, Map<CameraControl, Binding> bindings) {
        super(capabilities);
        this.device = device;
        this.bindings = bindings;
    }

    /**
     * Queries the controls of a camera.
     *
     * @param device the open camera
     * @return the controls; a camera whose driver answers no query offers none
     */
    static V4l2Controls query(V4l2Device device) {
        var capabilities = new ArrayList<Capability>();
        var bindings = new EnumMap<CameraControl, Binding>(CameraControl.class);
        try (var arena = Arena.ofConfined()) {
            for (var control : CameraControl.values()) {
                var binding = bind(device, arena, control);
                if (binding != null) {
                    bindings.put(control, binding);
                    capabilities.add(binding.capability());
                }
            }
        }
        return new V4l2Controls(device, capabilities, bindings);
    }

    /**
     * Finds the driver controls of one {@link CameraControl}.
     *
     * @return the binding, or {@code null} when the driver offers neither a value nor an automatic mode
     */
    private static Binding bind(V4l2Device device, Arena arena, CameraControl control) {
        return switch (control) {
            case BRIGHTNESS -> plain(device, arena, control, V4l2.CID_BRIGHTNESS, 0);
            case CONTRAST -> plain(device, arena, control, V4l2.CID_CONTRAST, 0);
            case HUE -> plain(device, arena, control, V4l2.CID_HUE, V4l2.CID_HUE_AUTO);
            case SATURATION -> plain(device, arena, control, V4l2.CID_SATURATION, 0);
            case SHARPNESS -> plain(device, arena, control, V4l2.CID_SHARPNESS, 0);
            case GAMMA -> plain(device, arena, control, V4l2.CID_GAMMA, 0);
            case GAIN -> plain(device, arena, control, V4l2.CID_GAIN, V4l2.CID_AUTOGAIN);
            case BACKLIGHT_COMPENSATION -> plain(device, arena, control, V4l2.CID_BACKLIGHT_COMPENSATION, 0);
            case WHITE_BALANCE -> plain(device, arena, control, V4l2.CID_WHITE_BALANCE_TEMPERATURE, V4l2.CID_AUTO_WHITE_BALANCE);
            case POWER_LINE_FREQUENCY -> powerLine(device, arena);
            case EXPOSURE -> exposure(device, arena);
            case FOCUS -> plain(device, arena, control, V4l2.CID_FOCUS_ABSOLUTE, V4l2.CID_FOCUS_AUTO);
            case ZOOM -> plain(device, arena, control, V4l2.CID_ZOOM_ABSOLUTE, 0);
            case PAN -> plain(device, arena, control, V4l2.CID_PAN_ABSOLUTE, 0);
            case TILT -> plain(device, arena, control, V4l2.CID_TILT_ABSOLUTE, 0);
        };
    }

    /**
     * Binds an integer control and an optional boolean that switches its automatic mode.
     *
     * @param valueId the integer control
     * @param autoId the boolean control, or {@code 0} for none
     */
    private static Binding plain(V4l2Device device, Arena arena, CameraControl control, int valueId, int autoId) {
        var value = describe(device, arena, valueId);
        var auto = autoId == 0 ? null : describe(device, arena, autoId);
        if (value != null && value.type() != V4l2.CTRL_TYPE_INTEGER) {
            value = null;
        }
        if (auto != null && (auto.type() != V4l2.CTRL_TYPE_BOOLEAN || auto.readOnly())) {
            auto = null;
        }
        Optional<CameraControls.Range> range = Optional.empty();
        if (value != null && !value.readOnly()) {
            range = Optional.of(new CameraControls.Range(value.minimum(), value.maximum(), Math.max(1, value.step()),
                    clamp(value.defaultValue(), value.minimum(), value.maximum()), List.of()));
        }
        if (range.isEmpty() && auto == null) {
            return null;
        }
        return new Binding(new Capability(control, range, auto != null), value == null ? 0 : valueId,
                auto == null ? 0 : autoId, Kind.BOOLEAN_AUTO, 1);
    }

    /**
     * Binds the power-line frequency menu, whose automatic entry is the automatic mode.
     */
    private static Binding powerLine(V4l2Device device, Arena arena) {
        var menu = describe(device, arena, V4l2.CID_POWER_LINE_FREQUENCY);
        if (menu == null || menu.type() != V4l2.CTRL_TYPE_MENU || menu.readOnly()) {
            return null;
        }
        var values = new ArrayList<Long>();
        for (var index = 0; index < FREQUENCIES.length; index++) {
            if (menuHas(device, arena, menu, index)) {
                values.add(FREQUENCIES[index]);
            }
        }
        var automatic = menuHas(device, arena, menu, V4l2.PLF_AUTO);
        Optional<CameraControls.Range> range = Optional.empty();
        if (!values.isEmpty()) {
            var factory = menu.defaultValue() >= 0 && menu.defaultValue() < FREQUENCIES.length
                    ? FREQUENCIES[menu.defaultValue()] : values.getFirst();
            if (!values.contains(factory)) {
                factory = values.getFirst();
            }
            range = Optional.of(new CameraControls.Range(values.getFirst(), values.getLast(), 0, factory, values));
        }
        if (range.isEmpty() && !automatic) {
            return null;
        }
        return new Binding(new Capability(CameraControl.POWER_LINE_FREQUENCY, range, automatic),
                V4l2.CID_POWER_LINE_FREQUENCY, automatic ? V4l2.CID_POWER_LINE_FREQUENCY : 0, Kind.POWER_LINE, 1);
    }

    /**
     * Binds the absolute exposure time and the {@code V4L2_CID_EXPOSURE_AUTO} menu.
     */
    private static Binding exposure(V4l2Device device, Arena arena) {
        var value = describe(device, arena, V4l2.CID_EXPOSURE_ABSOLUTE);
        var mode = describe(device, arena, V4l2.CID_EXPOSURE_AUTO);
        if (value != null && (value.type() != V4l2.CTRL_TYPE_INTEGER || value.readOnly())) {
            value = null;
        }
        var automatic = false;
        if (mode != null && mode.type() == V4l2.CTRL_TYPE_MENU && !mode.readOnly()) {
            automatic = menuHas(device, arena, mode, V4l2.EXPOSURE_AUTO)
                    || menuHas(device, arena, mode, V4l2.EXPOSURE_APERTURE_PRIORITY);
        }
        Optional<CameraControls.Range> range = Optional.empty();
        if (value != null) {
            range = Optional.of(new CameraControls.Range(value.minimum() * EXPOSURE_UNIT_MICROS,
                    value.maximum() * EXPOSURE_UNIT_MICROS, Math.max(1, value.step()) * EXPOSURE_UNIT_MICROS,
                    clamp(value.defaultValue(), value.minimum(), value.maximum()) * EXPOSURE_UNIT_MICROS, List.of()));
        }
        if (range.isEmpty() && !automatic) {
            return null;
        }
        var autoEntry = automatic && menuHas(device, arena, mode, V4l2.EXPOSURE_AUTO)
                ? V4l2.EXPOSURE_AUTO : V4l2.EXPOSURE_APERTURE_PRIORITY;
        return new Binding(new Capability(CameraControl.EXPOSURE, range, automatic),
                value == null ? 0 : V4l2.CID_EXPOSURE_ABSOLUTE, automatic ? V4l2.CID_EXPOSURE_AUTO : 0, Kind.EXPOSURE,
                autoEntry);
    }

    /**
     * Queries one driver control.
     *
     * @return its description, or {@code null} when the driver lacks it or disabled it
     */
    private static Description describe(V4l2Device device, Arena arena, int id) {
        var query = arena.allocate(V4l2.QUERYCTRL);
        query.set(JAVA_INT, V4l2.QUERYCTRL_ID, id);
        if (device.ioctl(V4l2.VIDIOC_QUERYCTRL, query) != 0 || query.get(JAVA_INT, V4l2.QUERYCTRL_ID) != id) {
            return null;
        }
        var flags = query.get(JAVA_INT, V4l2.QUERYCTRL_FLAGS);
        if ((flags & V4l2.CTRL_FLAG_DISABLED) != 0) {
            return null;
        }
        var minimum = query.get(JAVA_INT, V4l2.QUERYCTRL_MINIMUM);
        var maximum = query.get(JAVA_INT, V4l2.QUERYCTRL_MAXIMUM);
        if (maximum < minimum) {
            return null;
        }
        return new Description(id, query.get(JAVA_INT, V4l2.QUERYCTRL_TYPE), minimum, maximum,
                query.get(JAVA_INT, V4l2.QUERYCTRL_STEP), query.get(JAVA_INT, V4l2.QUERYCTRL_DEFAULT),
                (flags & V4l2.CTRL_FLAG_READ_ONLY) != 0);
    }

    /**
     * Returns whether a menu control offers an entry.
     */
    private static boolean menuHas(V4l2Device device, Arena arena, Description menu, int index) {
        if (menu == null || index < menu.minimum() || index > menu.maximum()) {
            return false;
        }
        var query = arena.allocate(V4l2.QUERYMENU);
        query.set(JAVA_INT, V4l2.QUERYMENU_ID, menu.id());
        query.set(JAVA_INT, V4l2.QUERYMENU_INDEX, index);
        return device.ioctl(V4l2.VIDIOC_QUERYMENU, query) == 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    protected State read(Capability capability) {
        var binding = bindings.get(capability.control());
        var automatic = false;
        if (binding.autoId() != 0) {
            var mode = getControl(binding.autoId());
            automatic = switch (binding.kind()) {
                case BOOLEAN_AUTO -> mode != 0;
                case POWER_LINE -> mode == V4l2.PLF_AUTO;
                case EXPOSURE -> mode == V4l2.EXPOSURE_AUTO || mode == V4l2.EXPOSURE_APERTURE_PRIORITY;
            };
        }
        if (binding.valueId() == 0) {
            return new State(OptionalLong.empty(), automatic);
        }
        var raw = getControl(binding.valueId());
        return switch (binding.kind()) {
            case BOOLEAN_AUTO -> new State(OptionalLong.of(raw), automatic);
            case EXPOSURE -> new State(OptionalLong.of(raw * EXPOSURE_UNIT_MICROS), automatic);
            case POWER_LINE -> raw >= 0 && raw < FREQUENCIES.length
                    ? new State(OptionalLong.of(FREQUENCIES[raw]), false)
                    : new State(OptionalLong.empty(), automatic);
        };
    }

    @Override
    protected void write(Capability capability, long value) {
        var binding = bindings.get(capability.control());
        switch (binding.kind()) {
            case BOOLEAN_AUTO -> {
                if (binding.autoId() != 0) {
                    setControl(binding.autoId(), 0);
                }
                setControl(binding.valueId(), (int) value);
            }
            case EXPOSURE -> {
                if (binding.autoId() != 0) {
                    setControl(binding.autoId(), V4l2.EXPOSURE_MANUAL);
                }
                setControl(binding.valueId(), (int) Math.round(value / (double) EXPOSURE_UNIT_MICROS));
            }
            case POWER_LINE -> {
                for (var index = 0; index < FREQUENCIES.length; index++) {
                    if (FREQUENCIES[index] == value) {
                        setControl(binding.valueId(), index);
                        return;
                    }
                }
                throw new IllegalArgumentException("no power-line entry for " + value);
            }
        }
    }

    @Override
    protected void writeAutomatic(Capability capability, boolean automatic) {
        var binding = bindings.get(capability.control());
        switch (binding.kind()) {
            case BOOLEAN_AUTO -> setControl(binding.autoId(), automatic ? 1 : 0);
            case EXPOSURE -> setControl(binding.autoId(), automatic ? binding.autoEntry() : V4l2.EXPOSURE_MANUAL);
            case POWER_LINE -> {
                if (automatic) {
                    setControl(binding.autoId(), V4l2.PLF_AUTO);
                } else if (getControl(binding.autoId()) == V4l2.PLF_AUTO) {
                    var fallback = capability.range().map(CameraControls.Range::defaultValue).orElse(0L);
                    write(capability, fallback);
                }
            }
        }
    }

    /**
     * Reads a driver control.
     *
     * @throws MediaException.Io if the driver refuses
     */
    private int getControl(int id) {
        try (var arena = Arena.ofConfined()) {
            var control = arena.allocate(V4l2.CONTROL);
            control.set(JAVA_INT, V4l2.CONTROL_ID, id);
            var error = device.ioctl(V4l2.VIDIOC_G_CTRL, control);
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_G_CTRL " + Integer.toHexString(id), error);
            }
            return control.get(JAVA_INT, V4l2.CONTROL_VALUE);
        }
    }

    /**
     * Writes a driver control.
     *
     * @throws MediaException.Io if the driver refuses
     */
    private void setControl(int id, int value) {
        try (var arena = Arena.ofConfined()) {
            var control = arena.allocate(V4l2.CONTROL);
            control.set(JAVA_INT, V4l2.CONTROL_ID, id);
            control.set(JAVA_INT, V4l2.CONTROL_VALUE, value);
            var error = device.ioctl(V4l2.VIDIOC_S_CTRL, control);
            if (error != 0) {
                throw V4l2Formats.failure("VIDIOC_S_CTRL " + Integer.toHexString(id) + " = " + value, error);
            }
        }
    }

    /**
     * How a binding switches its automatic mode and converts its value.
     */
    private enum Kind {
        /**
         * An integer value and a boolean automatic switch.
         */
        BOOLEAN_AUTO,

        /**
         * The power-line frequency menu, one control for both.
         */
        POWER_LINE,

        /**
         * The absolute exposure in 100 microsecond units and the exposure mode menu.
         */
        EXPOSURE
    }

    /**
     * The driver controls behind one {@link CameraControl}.
     *
     * @param capability what is offered
     * @param valueId the value control, or {@code 0}
     * @param autoId the control switching the automatic mode, or {@code 0}
     * @param kind how the controls are used
     * @param autoEntry the menu entry of the automatic mode of an exposure binding
     */
    private record Binding(Capability capability, int valueId, int autoId, Kind kind, int autoEntry) {
    }

    /**
     * The answer of {@code VIDIOC_QUERYCTRL} for one control.
     *
     * @param id the control
     * @param type the {@code V4L2_CTRL_TYPE_*}
     * @param readOnly whether the control is read-only
     */
    private record Description(int id, int type, int minimum, int maximum, int step, int defaultValue, boolean readOnly) {
    }
}
