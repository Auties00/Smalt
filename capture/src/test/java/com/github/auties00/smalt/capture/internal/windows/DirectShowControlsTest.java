package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CameraControl;
import com.github.auties00.smalt.capture.CameraControls;
import com.github.auties00.smalt.capture.internal.Native;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link DirectShowControls} against simulated {@code IAMCameraControl} and {@code IAMVideoProcAmp}
 * objects: COM objects built from upcall stubs whose six-slot virtual function tables ({@code QueryInterface},
 * {@code AddRef}, {@code Release}, {@code GetRange}, {@code Set}, {@code Get}) answer from a table of properties
 * and record every call, so the slots, argument order and unit conversions are checked on every operating system.
 */
@DisplayName("DirectShow camera controls")
class DirectShowControlsTest {
    private static final int E_PROP_ID_UNSUPPORTED = 0x80070490;

    private final Arena arena = Arena.ofShared();

    @AfterEach
    void close() {
        arena.close();
    }

    /**
     * One simulated interface.
     */
    final class FakeInterface {
        record Property(int minimum, int maximum, int step, int defaultValue, int flags) {
        }

        final Map<Integer, Property> properties = new HashMap<>();
        final Map<Integer, int[]> values = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        int releases;
        final MemorySegment object;

        FakeInterface() {
            try {
                var lookup = MethodHandles.lookup();
                var table = arena.allocate(ADDRESS, 6);
                table.setAtIndex(ADDRESS, 0, stub(lookup, "queryInterface", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)));
                table.setAtIndex(ADDRESS, 1, stub(lookup, "addRef", FunctionDescriptor.of(JAVA_INT, ADDRESS)));
                table.setAtIndex(ADDRESS, 2, stub(lookup, "release", FunctionDescriptor.of(JAVA_INT, ADDRESS)));
                table.setAtIndex(ADDRESS, 3, stub(lookup, "getRange", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT,
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)));
                table.setAtIndex(ADDRESS, 4, stub(lookup, "set", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)));
                table.setAtIndex(ADDRESS, 5, stub(lookup, "get", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)));
                object = arena.allocate(ADDRESS);
                object.set(ADDRESS, 0, table);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }

        private MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor descriptor)
                throws ReflectiveOperationException {
            var type = descriptor.toMethodType();
            var handle = lookup.findVirtual(FakeInterface.class, name, type).bindTo(this);
            return Native.LINKER.upcallStub(handle, descriptor, arena);
        }

        FakeInterface property(int id, int minimum, int maximum, int step, int defaultValue, int flags, int value, int valueFlags) {
            properties.put(id, new Property(minimum, maximum, step, defaultValue, flags));
            values.put(id, new int[]{value, valueFlags});
            return this;
        }

        int queryInterface(MemorySegment self, MemorySegment iid, MemorySegment result) {
            result.reinterpret(ADDRESS.byteSize()).set(ADDRESS, 0, MemorySegment.NULL);
            return Com.E_NOINTERFACE;
        }

        int addRef(MemorySegment self) {
            return 2;
        }

        int release(MemorySegment self) {
            releases++;
            return 1;
        }

        int getRange(MemorySegment self, int property, MemorySegment minimum, MemorySegment maximum, MemorySegment step,
                     MemorySegment defaultValue, MemorySegment flags) {
            var described = properties.get(property);
            if (described == null) {
                return E_PROP_ID_UNSUPPORTED;
            }
            minimum.reinterpret(4).set(JAVA_INT, 0, described.minimum());
            maximum.reinterpret(4).set(JAVA_INT, 0, described.maximum());
            step.reinterpret(4).set(JAVA_INT, 0, described.step());
            defaultValue.reinterpret(4).set(JAVA_INT, 0, described.defaultValue());
            flags.reinterpret(4).set(JAVA_INT, 0, described.flags());
            return 0;
        }

        int set(MemorySegment self, int property, int value, int flags) {
            calls.add("Set " + property + " " + value + " " + flags);
            values.put(property, new int[]{value, flags});
            return 0;
        }

        int get(MemorySegment self, int property, MemorySegment value, MemorySegment flags) {
            calls.add("Get " + property);
            var current = values.get(property);
            if (current == null) {
                return E_PROP_ID_UNSUPPORTED;
            }
            value.reinterpret(4).set(JAVA_INT, 0, current[0]);
            flags.reinterpret(4).set(JAVA_INT, 0, current[1]);
            return 0;
        }
    }

    private static final int AUTO = DirectShowControls.FLAG_AUTO;

    private static final int MANUAL = DirectShowControls.FLAG_MANUAL;

    /**
     * A webcam's lens controls: exposure from 2^-11 to 2^-2 seconds, zoom, pan in degrees, and autofocus.
     */
    private FakeInterface camera() {
        return new FakeInterface()
                .property(4, -11, -2, 1, -5, AUTO | MANUAL, -5, AUTO)
                .property(3, 100, 500, 10, 100, MANUAL, 100, MANUAL)
                .property(0, -180, 180, 1, 0, MANUAL, 3, MANUAL)
                .property(6, 0, 255, 5, 0, AUTO | MANUAL, 40, AUTO);
    }

    /**
     * A webcam's image controls: brightness, white balance in kelvin and a power-line frequency without the
     * automatic entry.
     */
    private FakeInterface procAmp() {
        return new FakeInterface()
                .property(0, -64, 64, 1, 0, MANUAL, 0, MANUAL)
                .property(7, 2800, 6500, 10, 4600, AUTO | MANUAL, 5000, AUTO)
                .property(13, 0, 2, 1, 1, MANUAL, 2, MANUAL);
    }

    @Test
    @DisplayName("GetRange answers become capabilities in the controls' units")
    void capabilities() {
        var controls = DirectShowControls.of(camera().object, procAmp().object);
        assertEquals(List.of(CameraControl.BRIGHTNESS, CameraControl.WHITE_BALANCE, CameraControl.POWER_LINE_FREQUENCY,
                        CameraControl.EXPOSURE, CameraControl.FOCUS, CameraControl.ZOOM, CameraControl.PAN),
                controls.capabilities().stream().map(CameraControls.Capability::control).toList());
        var exposure = controls.capability(CameraControl.EXPOSURE).orElseThrow();
        assertTrue(exposure.automatic());
        assertEquals(new CameraControls.Range(488, 250_000, 0, 31_250, List.of(488L, 977L, 1953L, 3906L, 7813L, 15_625L,
                31_250L, 62_500L, 125_000L, 250_000L)), exposure.range().orElseThrow());
        assertEquals(new CameraControls.Range(-648_000, 648_000, 3600, 0, List.of()),
                controls.capability(CameraControl.PAN).orElseThrow().range().orElseThrow());
        assertEquals(new CameraControls.Range(0, 60, 0, 50, List.of(0L, 50L, 60L)),
                controls.capability(CameraControl.POWER_LINE_FREQUENCY).orElseThrow().range().orElseThrow());
        assertFalse(controls.capability(CameraControl.POWER_LINE_FREQUENCY).orElseThrow().automatic());
        assertEquals(new CameraControls.Capability(CameraControl.WHITE_BALANCE,
                Optional.of(new CameraControls.Range(2800, 6500, 10, 4600, List.of())), true),
                controls.capability(CameraControl.WHITE_BALANCE).orElseThrow());
    }

    @Test
    @DisplayName("Get and Set convert exposure, pan and the power-line frequency and pass the mode flags")
    void readWrite() {
        var lens = camera();
        var image = procAmp();
        var controls = DirectShowControls.of(lens.object, image.object);
        assertEquals(new CameraControls.State(OptionalLong.of(31_250), true), controls.get(CameraControl.EXPOSURE));
        assertEquals(new CameraControls.State(OptionalLong.of(10_800), false), controls.get(CameraControl.PAN));
        assertEquals(new CameraControls.State(OptionalLong.of(60), false), controls.get(CameraControl.POWER_LINE_FREQUENCY));
        assertEquals(new CameraControls.State(OptionalLong.of(5000), true), controls.get(CameraControl.WHITE_BALANCE));
        controls.set(CameraControl.EXPOSURE, 7813);
        controls.set(CameraControl.PAN, -7200);
        controls.set(CameraControl.ZOOM, 250);
        controls.set(CameraControl.POWER_LINE_FREQUENCY, 50);
        controls.set(CameraControl.WHITE_BALANCE, 3200);
        controls.setAutomatic(CameraControl.FOCUS, false);
        controls.setAutomatic(CameraControl.EXPOSURE, true);
        assertEquals(List.of("Get 4", "Get 0", "Set 4 -7 " + MANUAL, "Set 0 -2 " + MANUAL, "Set 3 250 " + MANUAL,
                "Get 6", "Set 6 40 " + MANUAL, "Get 4", "Set 4 -7 " + AUTO), lens.calls);
        assertEquals(List.of("Get 13", "Get 7", "Set 13 1 " + MANUAL, "Set 7 3200 " + MANUAL), image.calls);
        assertThrows(IllegalArgumentException.class, () -> controls.set(CameraControl.EXPOSURE, 7000));
        assertThrows(MediaException.Unsupported.class, () -> controls.setAutomatic(CameraControl.ZOOM, true));
        assertThrows(MediaException.Unsupported.class, () -> controls.get(CameraControl.TILT));
    }

    @Test
    @DisplayName("a power-line property that reaches the automatic value offers the automatic mode")
    void automaticPowerLine() {
        var image = new FakeInterface().property(13, 0, 3, 1, 3, MANUAL, 3, MANUAL);
        var controls = DirectShowControls.of(MemorySegment.NULL, image.object);
        var capability = controls.capability(CameraControl.POWER_LINE_FREQUENCY).orElseThrow();
        assertTrue(capability.automatic());
        assertEquals(new CameraControls.Range(0, 60, 0, 0, List.of(0L, 50L, 60L)), capability.range().orElseThrow());
        assertEquals(new CameraControls.State(OptionalLong.empty(), true), controls.get(CameraControl.POWER_LINE_FREQUENCY));
        controls.setAutomatic(CameraControl.POWER_LINE_FREQUENCY, false);
        controls.set(CameraControl.POWER_LINE_FREQUENCY, 60);
        controls.setAutomatic(CameraControl.POWER_LINE_FREQUENCY, true);
        assertEquals(List.of("Get 13", "Get 13", "Set 13 0 " + MANUAL, "Set 13 2 " + MANUAL, "Set 13 3 " + AUTO), image.calls);
    }

    @Test
    @DisplayName("closing releases both interfaces once, and missing interfaces offer nothing")
    void release() {
        var lens = camera();
        var image = procAmp();
        var controls = DirectShowControls.of(lens.object, image.object);
        controls.close();
        controls.close();
        assertEquals(1, lens.releases);
        assertEquals(1, image.releases);
        assertThrows(IllegalStateException.class, () -> controls.get(CameraControl.ZOOM));
        assertEquals(List.of(), DirectShowControls.of(MemorySegment.NULL, MemorySegment.NULL).capabilities());
    }

    @Test
    @DisplayName("exposure converts between base-2 logarithms of seconds and microseconds")
    void exposureUnits() {
        assertEquals(1_000_000, DirectShowControls.exposureMicros(0));
        assertEquals(2_000_000, DirectShowControls.exposureMicros(1));
        assertEquals(15_625, DirectShowControls.exposureMicros(-6));
        assertEquals(1, DirectShowControls.exposureMicros(-30));
        for (var log2 = -13; log2 <= 3; log2++) {
            assertEquals(log2, DirectShowControls.exposureLog2(DirectShowControls.exposureMicros(log2)));
        }
        assertEquals(-6, DirectShowControls.exposureLog2(17_000));
    }
}
