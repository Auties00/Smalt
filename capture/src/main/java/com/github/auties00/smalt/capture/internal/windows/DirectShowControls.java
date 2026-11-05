package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.internal.NativeCameraControls;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Reads and adjusts the controls of a Media Foundation camera through the {@code IAMCameraControl} and
 * {@code IAMVideoProcAmp} interfaces of its media source.
 *
 * <p>The image controls map to the {@code VideoProcAmp} properties (white balance in kelvin, and the
 * power-line frequency as the kernel streaming property {@code KSPROPERTY_VIDEOPROCAMP_POWERLINE_FREQUENCY}
 * whose values 0 to 3 are disabled, 50 Hz, 60 Hz and automatic); the lens controls map to the
 * {@code CameraControl} properties. Exposure is converted from the property's base-2 logarithm of seconds
 * to microseconds, so its accepted values are discrete powers of two; pan and tilt are converted from
 * degrees to arc seconds. A property whose {@code GetRange} fails is not offered.
 *
 * @implNote This implementation calls the interfaces on the caller's thread. The media source lives in
 * the multithreaded apartment its capture thread keeps initialized, so a thread that never initialized
 * COM reaches it through the process's implicit multithreaded apartment.
 */
final class DirectShowControls extends NativeCameraControls {
    /**
     * The interface identifier of {@code IAMCameraControl}.
     */
    static final MemorySegment IID_CAMERA_CONTROL = Com.guid("C6E13370-30AC-11d0-A18C-00A0C9118956");

    /**
     * The interface identifier of {@code IAMVideoProcAmp}.
     */
    static final MemorySegment IID_VIDEO_PROC_AMP = Com.guid("C6E13360-30AC-11d0-A18C-00A0C9118956");

    /**
     * The vtable slot of {@code GetRange}, the same in both interfaces.
     */
    static final int GET_RANGE = 3;

    /**
     * The vtable slot of {@code Set}, the same in both interfaces.
     */
    static final int SET = 4;

    /**
     * The vtable slot of {@code Get}, the same in both interfaces.
     */
    static final int GET = 5;

    /**
     * The {@code CameraControl_Flags_Auto} and {@code VideoProcAmp_Flags_Auto} flag.
     */
    static final int FLAG_AUTO = 0x1;

    /**
     * The {@code CameraControl_Flags_Manual} and {@code VideoProcAmp_Flags_Manual} flag.
     */
    static final int FLAG_MANUAL = 0x2;

    /**
     * The power-line frequency value that selects automatic detection.
     */
    static final int POWER_LINE_AUTO = 3;

    /**
     * The frequencies of the power-line values 0 to 2.
     */
    private static final long[] FREQUENCIES = {0, 50, 60};

    /**
     * The arc seconds of one degree.
     */
    private static final long ARC_SECONDS = 3600;

    private final MemorySegment cameraControl;

    private final MemorySegment procAmp;

    private final Map<CameraControl, Binding> bindings;

    private DirectShowControls(MemorySegment cameraControl, MemorySegment procAmp, List<Capability> capabilities,
                               Map<CameraControl, Binding> bindings) {
        super(capabilities);
        this.cameraControl = cameraControl;
        this.procAmp = procAmp;
        this.bindings = bindings;
    }

    /**
     * Queries the controls of a media source.
     *
     * @param source the {@code IMFMediaSource}
     * @return the controls; a source that implements neither interface offers none
     */
    static DirectShowControls open(MemorySegment source) {
        try (var arena = Arena.ofConfined()) {
            var slot = arena.allocate(ADDRESS);
            return of(Com.query(source, IID_CAMERA_CONTROL, slot), Com.query(source, IID_VIDEO_PROC_AMP, slot));
        }
    }

    /**
     * Queries the controls of two interface pointers, taking over their references.
     *
     * @param cameraControl the {@code IAMCameraControl}, or {@link MemorySegment#NULL}
     * @param procAmp the {@code IAMVideoProcAmp}, or {@link MemorySegment#NULL}
     * @return the controls, which release both interfaces when closed
     */
    static DirectShowControls of(MemorySegment cameraControl, MemorySegment procAmp) {
        var capabilities = new ArrayList<Capability>();
        var bindings = new EnumMap<CameraControl, Binding>(CameraControl.class);
        try (var arena = Arena.ofConfined()) {
            var values = arena.allocate(JAVA_INT, 5);
            for (var control : CameraControl.values()) {
                var target = target(control);
                var self = target.camera() ? cameraControl : procAmp;
                if (self.equals(MemorySegment.NULL)) {
                    continue;
                }
                var hr = Com.call(self, GET_RANGE, target.property(), values, values.asSlice(4), values.asSlice(8),
                        values.asSlice(12), values.asSlice(16));
                if (hr < 0) {
                    continue;
                }
                var binding = bind(control, target, values.get(JAVA_INT, 0), values.get(JAVA_INT, 4),
                        values.get(JAVA_INT, 8), values.get(JAVA_INT, 12), values.get(JAVA_INT, 16));
                if (binding != null) {
                    bindings.put(control, binding);
                    capabilities.add(binding.capability());
                }
            }
        }
        return new DirectShowControls(cameraControl, procAmp, capabilities, bindings);
    }

    /**
     * Returns the interface and property of a control.
     */
    static Target target(CameraControl control) {
        return switch (control) {
            case BRIGHTNESS -> new Target(false, 0);
            case CONTRAST -> new Target(false, 1);
            case HUE -> new Target(false, 2);
            case SATURATION -> new Target(false, 3);
            case SHARPNESS -> new Target(false, 4);
            case GAMMA -> new Target(false, 5);
            case WHITE_BALANCE -> new Target(false, 7);
            case BACKLIGHT_COMPENSATION -> new Target(false, 8);
            case GAIN -> new Target(false, 9);
            case POWER_LINE_FREQUENCY -> new Target(false, 13);
            case PAN -> new Target(true, 0);
            case TILT -> new Target(true, 1);
            case ZOOM -> new Target(true, 3);
            case EXPOSURE -> new Target(true, 4);
            case FOCUS -> new Target(true, 6);
        };
    }

    /**
     * Describes a control from the answer of {@code GetRange}.
     *
     * @param minimum the smallest raw value
     * @param maximum the largest raw value
     * @param step the raw stepping delta
     * @param defaultValue the raw default value
     * @param flags the {@code FLAG_*} capability flags
     * @return the binding, or {@code null} when the answer offers nothing usable
     */
    static Binding bind(CameraControl control, Target target, int minimum, int maximum, int step, int defaultValue,
                        int flags) {
        if (maximum < minimum) {
            return null;
        }
        var automatic = (flags & FLAG_AUTO) != 0;
        var manual = (flags & FLAG_MANUAL) != 0 || flags == 0;
        Optional<CameraControls.Range> range = Optional.empty();
        switch (control) {
            case EXPOSURE -> {
                if (manual && (long) maximum - minimum <= 64) {
                    var values = new ArrayList<Long>();
                    var delta = Math.max(1, step);
                    for (long raw = minimum; raw <= maximum; raw += delta) {
                        values.add(exposureMicros((int) raw));
                    }
                    var distinct = values.stream().distinct().sorted().toList();
                    var factory = exposureMicros(Math.max(minimum, Math.min(maximum, defaultValue)));
                    if (!distinct.contains(factory)) {
                        factory = distinct.getFirst();
                    }
                    range = Optional.of(new CameraControls.Range(distinct.getFirst(), distinct.getLast(), 0, factory, distinct));
                }
            }
            case POWER_LINE_FREQUENCY -> {
                automatic = automatic || maximum >= POWER_LINE_AUTO;
                var values = new ArrayList<Long>();
                for (var raw = Math.max(0, minimum); raw <= Math.min(2, maximum); raw++) {
                    values.add(FREQUENCIES[raw]);
                }
                if (!values.isEmpty()) {
                    var factory = defaultValue >= 0 && defaultValue <= 2 ? FREQUENCIES[defaultValue] : values.getFirst();
                    if (!values.contains(factory)) {
                        factory = values.getFirst();
                    }
                    range = Optional.of(new CameraControls.Range(values.getFirst(), values.getLast(), 0, factory, values));
                }
            }
            case PAN, TILT -> {
                if (manual) {
                    range = Optional.of(new CameraControls.Range(minimum * ARC_SECONDS, maximum * ARC_SECONDS,
                            Math.max(1, step) * ARC_SECONDS, clamp(defaultValue, minimum, maximum) * ARC_SECONDS, List.of()));
                }
            }
            default -> {
                if (manual) {
                    range = Optional.of(new CameraControls.Range(minimum, maximum, Math.max(1, step),
                            clamp(defaultValue, minimum, maximum), List.of()));
                }
            }
        }
        if (range.isEmpty() && !automatic) {
            return null;
        }
        return new Binding(new Capability(control, range, automatic), target);
    }

    /**
     * Converts a {@code CameraControl_Exposure} value, the base-2 logarithm of seconds, to microseconds.
     *
     * @param log2Seconds the value
     * @return the exposure time in microseconds, rounded, at least 1
     */
    static long exposureMicros(int log2Seconds) {
        return Math.max(1, Math.round(StrictMath.scalb(1_000_000.0, log2Seconds)));
    }

    /**
     * Converts microseconds to the nearest {@code CameraControl_Exposure} value.
     *
     * @param micros the exposure time, positive
     * @return the base-2 logarithm of seconds
     */
    static int exposureLog2(long micros) {
        return (int) Math.round(StrictMath.log(micros / 1_000_000.0) / StrictMath.log(2));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    protected State read(Capability capability) {
        var binding = bindings.get(capability.control());
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(JAVA_INT);
            var flags = arena.allocate(JAVA_INT);
            var self = self(binding);
            Com.check(Com.call(self, GET, binding.target().property(), value, flags), "Get " + capability.control());
            var raw = value.get(JAVA_INT, 0);
            var automatic = (flags.get(JAVA_INT, 0) & FLAG_AUTO) != 0;
            return switch (capability.control()) {
                case EXPOSURE -> new State(OptionalLong.of(exposureMicros(raw)), automatic);
                case PAN, TILT -> new State(OptionalLong.of(raw * ARC_SECONDS), automatic);
                case POWER_LINE_FREQUENCY -> raw >= 0 && raw <= 2
                        ? new State(OptionalLong.of(FREQUENCIES[raw]), automatic)
                        : new State(OptionalLong.empty(), true);
                default -> new State(OptionalLong.of(raw), automatic);
            };
        }
    }

    @Override
    protected void write(Capability capability, long value) {
        var binding = bindings.get(capability.control());
        var raw = switch (capability.control()) {
            case EXPOSURE -> exposureLog2(value);
            case PAN, TILT -> (int) Math.round(value / (double) ARC_SECONDS);
            case POWER_LINE_FREQUENCY -> value == 50 ? 1 : value == 60 ? 2 : 0;
            default -> (int) value;
        };
        Com.check(Com.call(self(binding), SET, binding.target().property(), raw, FLAG_MANUAL), "Set " + capability.control());
    }

    @Override
    protected void writeAutomatic(Capability capability, boolean automatic) {
        var binding = bindings.get(capability.control());
        var self = self(binding);
        var property = binding.target().property();
        if (capability.control() == CameraControl.POWER_LINE_FREQUENCY) {
            if (automatic) {
                Com.check(Com.call(self, SET, property, POWER_LINE_AUTO, FLAG_AUTO), "Set " + capability.control());
                return;
            }
            var state = read(capability);
            if (state.value().isEmpty()) {
                write(capability, capability.range().map(CameraControls.Range::defaultValue).orElse(0L));
            }
            return;
        }
        try (var arena = Arena.ofConfined()) {
            var value = arena.allocate(JAVA_INT);
            var flags = arena.allocate(JAVA_INT);
            Com.check(Com.call(self, GET, property, value, flags), "Get " + capability.control());
            Com.check(Com.call(self, SET, property, value.get(JAVA_INT, 0), automatic ? FLAG_AUTO : FLAG_MANUAL),
                    "Set " + capability.control());
        }
    }

    /**
     * Returns the interface of a binding.
     */
    private MemorySegment self(Binding binding) {
        return binding.target().camera() ? cameraControl : procAmp;
    }

    @Override
    protected void release() {
        Com.release(cameraControl);
        Com.release(procAmp);
    }

    /**
     * The interface and property of a control.
     *
     * @param camera whether the property belongs to {@code IAMCameraControl} rather than {@code IAMVideoProcAmp}
     * @param property the {@code CameraControlProperty} or {@code VideoProcAmpProperty}
     */
    record Target(boolean camera, int property) {
    }

    /**
     * A control's capability and target.
     *
     * @param capability what is offered
     * @param target where it is
     */
    record Binding(Capability capability, Target target) {
    }
}
