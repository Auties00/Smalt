package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Enumerates the monitors attached to the desktop.
 *
 * <p>Monitors are listed from the DXGI outputs of every adapter, identified by their GDI device
 * name ({@code \\.\DISPLAY1}, ...) and placed by their desktop coordinates, which are physical
 * pixels. When DXGI lists nothing (for example in some remote sessions) the GDI display devices
 * are listed instead. Each monitor is labeled with the friendly name its EDID reports, read with
 * the display configuration API, or with its device name when it reports none. The primary
 * monitor is the one whose top-left corner is the origin of the virtual desktop.
 */
final class Displays {
    /**
     * The largest number of adapters, outputs or GDI display devices visited, a bound that makes
     * every enumeration loop terminate.
     */
    private static final int MAX_DEVICES = 64;

    /**
     * The {@code ERROR_INSUFFICIENT_BUFFER} result of {@code QueryDisplayConfig} when the
     * configuration changed between sizing and querying.
     */
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;

    /**
     * The prefix of GDI display device names.
     */
    private static final String DEVICE_PREFIX = "\\\\.\\";

    /**
     * Prevents instantiation.
     */
    private Displays() {
        throw new AssertionError();
    }

    /**
     * Places a monitor on the virtual desktop.
     *
     * @param deviceName the GDI device name
     * @param x          the left edge
     * @param y          the top edge
     * @param width      the width in pixels
     * @param height     the height in pixels
     */
    record Bounds(String deviceName, int x, int y, int width, int height) {
    }

    /**
     * Holds a DXGI output and the adapter it is connected to, each with its own reference.
     *
     * @param adapter  the {@code IDXGIAdapter1}
     * @param output   the {@code IDXGIOutput}
     * @param bounds   the output's placement
     * @param attached whether the output is part of the desktop
     * @param rotation the {@code DXGI_MODE_ROTATION} of the output
     */
    record Output(MemorySegment adapter, MemorySegment output, Bounds bounds, boolean attached, int rotation)
            implements AutoCloseable {
        /**
         * Releases both references.
         */
        @Override
        public void close() {
            Com.release(output);
            Com.release(adapter);
        }
    }

    /**
     * Lists the monitors attached to the desktop; must run on a thread that uses physical pixels
     * (see {@link Gdi#usePhysicalPixels()}).
     *
     * @return the screens, the primary one first, then from left to right and top to bottom
     * @throws MediaException.Io if both DXGI and GDI enumeration fail
     */
    static List<CaptureDevice.Screen> list() {
        try (var arena = Arena.ofConfined()) {
            var bounds = new LinkedHashMap<String, Bounds>();
            if (Dxgi.available()) {
                try {
                    var factory = Dxgi.createFactory(arena.allocate(ADDRESS));
                    try {
                        for (var output : outputs(factory, arena)) {
                            try (output) {
                                if (output.attached() && output.bounds().width() > 0 && output.bounds().height() > 0) {
                                    bounds.putIfAbsent(output.bounds().deviceName(), output.bounds());
                                }
                            }
                        }
                    } finally {
                        Com.release(factory);
                    }
                } catch (MediaException exception) {
                    bounds.clear();
                }
            }
            if (bounds.isEmpty() && Gdi.available()) {
                for (var display : gdiDisplays(arena)) {
                    bounds.putIfAbsent(display.deviceName(), display);
                }
            }
            var names = Gdi.available() ? friendlyNames(arena) : Map.<String, String>of();
            var screens = new ArrayList<CaptureDevice.Screen>();
            for (var display : bounds.values()) {
                var name = names.getOrDefault(display.deviceName(), shortName(display.deviceName()));
                screens.add(new CaptureDevice.Screen(display.deviceName(), name, display.x(), display.y(),
                        display.width(), display.height(), display.x() == 0 && display.y() == 0));
            }
            screens.sort(Comparator.comparing((CaptureDevice.Screen screen) -> !screen.primary())
                    .thenComparingInt(CaptureDevice.Screen::x)
                    .thenComparingInt(CaptureDevice.Screen::y));
            return List.copyOf(screens);
        }
    }

    /**
     * Returns a device name without its {@code \\.\} prefix.
     *
     * @param deviceName the GDI device name
     * @return the short name, for example {@code DISPLAY1}
     */
    private static String shortName(String deviceName) {
        return deviceName.startsWith(DEVICE_PREFIX) ? deviceName.substring(DEVICE_PREFIX.length()) : deviceName;
    }

    /**
     * Lists the outputs of every adapter of a factory.
     *
     * <p>The caller owns the returned outputs and closes each of them.
     *
     * @param factory the {@code IDXGIFactory1}
     * @param arena   the arena of the temporary structures
     * @return the outputs, in adapter and output order
     * @throws MediaException.Io if an enumeration call fails
     */
    static List<Output> outputs(MemorySegment factory, Arena arena) {
        var outputs = new ArrayList<Output>();
        var slot = arena.allocate(ADDRESS);
        var description = arena.allocate(Dxgi.OUTPUT_DESC_SIZE, 8);
        try {
            for (var adapterIndex = 0; adapterIndex < MAX_DEVICES; adapterIndex++) {
                var hr = Com.call(factory, Dxgi.FACTORY_ENUM_ADAPTERS1, adapterIndex, slot);
                if (hr == Dxgi.ERROR_NOT_FOUND) {
                    break;
                }
                Com.check(hr, "IDXGIFactory1::EnumAdapters1");
                var adapter = slot.get(ADDRESS, 0);
                try {
                    for (var outputIndex = 0; outputIndex < MAX_DEVICES; outputIndex++) {
                        hr = Com.call(adapter, Dxgi.ADAPTER_ENUM_OUTPUTS, outputIndex, slot);
                        if (hr == Dxgi.ERROR_NOT_FOUND) {
                            break;
                        }
                        Com.check(hr, "IDXGIAdapter::EnumOutputs");
                        var output = slot.get(ADDRESS, 0);
                        hr = Com.call(output, Dxgi.OUTPUT_GET_DESC, description);
                        if (hr < 0) {
                            Com.release(output);
                            continue;
                        }
                        var left = description.get(JAVA_INT, Dxgi.OUTPUT_DESC_COORDINATES);
                        var top = description.get(JAVA_INT, Dxgi.OUTPUT_DESC_COORDINATES + 4);
                        var right = description.get(JAVA_INT, Dxgi.OUTPUT_DESC_COORDINATES + 8);
                        var bottom = description.get(JAVA_INT, Dxgi.OUTPUT_DESC_COORDINATES + 12);
                        var name = Com.wideString(description.asSlice(0, 64));
                        Com.addRef(adapter);
                        outputs.add(new Output(adapter, output, new Bounds(name, left, top, right - left, bottom - top),
                                description.get(JAVA_INT, Dxgi.OUTPUT_DESC_ATTACHED) != 0,
                                description.get(JAVA_INT, Dxgi.OUTPUT_DESC_ROTATION)));
                    }
                } finally {
                    Com.release(adapter);
                }
            }
            return outputs;
        } catch (RuntimeException exception) {
            for (var output : outputs) {
                output.close();
            }
            throw exception;
        }
    }

    /**
     * Finds the DXGI output of a monitor.
     *
     * @param factory    the {@code IDXGIFactory1}
     * @param deviceName the monitor's GDI device name
     * @param arena      the arena of the temporary structures
     * @return the output, which the caller closes, or {@code null} when no attached output has
     *         that name
     * @throws MediaException.Io if an enumeration call fails
     */
    static Output find(MemorySegment factory, String deviceName, Arena arena) {
        Output found = null;
        for (var output : outputs(factory, arena)) {
            if (found == null && output.attached() && output.bounds().deviceName().equalsIgnoreCase(deviceName)) {
                found = output;
            } else {
                output.close();
            }
        }
        return found;
    }

    /**
     * Lists the GDI display devices attached to the desktop.
     *
     * @param arena the arena of the temporary structures
     * @return the displays, in device order
     */
    private static List<Bounds> gdiDisplays(Arena arena) {
        var displays = new ArrayList<Bounds>();
        var device = arena.allocate(Gdi.DISPLAY_DEVICE_SIZE, 4);
        for (var index = 0; index < MAX_DEVICES; index++) {
            device.fill((byte) 0);
            device.set(JAVA_INT, 0, (int) Gdi.DISPLAY_DEVICE_SIZE);
            if (!Gdi.enumDisplayDevice(index, device)) {
                break;
            }
            var state = device.get(JAVA_INT, Gdi.DISPLAY_DEVICE_STATE);
            if ((state & Gdi.DISPLAY_ATTACHED_TO_DESKTOP) == 0 || (state & Gdi.DISPLAY_MIRRORING_DRIVER) != 0) {
                continue;
            }
            var name = Com.wideString(device.asSlice(Gdi.DISPLAY_DEVICE_NAME, 64));
            var bounds = gdiBounds(name, arena);
            if (bounds != null) {
                displays.add(bounds);
            }
        }
        return displays;
    }

    /**
     * Reads the placement of a GDI display device from its current mode.
     *
     * @param deviceName the GDI device name
     * @param arena      the arena of the temporary structures
     * @return the placement, or {@code null} when the device has no current mode
     */
    static Bounds gdiBounds(String deviceName, Arena arena) {
        var mode = arena.allocate(Gdi.DEVMODE_SIZE, 4);
        mode.set(JAVA_SHORT, Gdi.DEVMODE_DM_SIZE, (short) Gdi.DEVMODE_SIZE);
        if (!Gdi.currentSettings(arena.allocateFrom(deviceName, StandardCharsets.UTF_16LE), mode)) {
            return null;
        }
        var width = mode.get(JAVA_INT, Gdi.DEVMODE_WIDTH);
        var height = mode.get(JAVA_INT, Gdi.DEVMODE_HEIGHT);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new Bounds(deviceName, mode.get(JAVA_INT, Gdi.DEVMODE_POSITION_X), mode.get(JAVA_INT, Gdi.DEVMODE_POSITION_Y),
                width, height);
    }

    /**
     * Reads the EDID friendly name of every active monitor.
     *
     * @param arena the arena of the temporary structures
     * @return the names by GDI device name; empty when the display configuration cannot be read
     */
    private static Map<String, String> friendlyNames(Arena arena) {
        var pathCount = arena.allocate(JAVA_INT);
        var modeCount = arena.allocate(JAVA_INT);
        for (var attempt = 0; attempt < 3; attempt++) {
            if (Gdi.displayConfigBufferSizes(pathCount, modeCount) != 0) {
                return Map.of();
            }
            var paths = pathCount.get(JAVA_INT, 0);
            var modes = modeCount.get(JAVA_INT, 0);
            if (paths <= 0 || paths > MAX_DEVICES * 4 || modes < 0 || modes > MAX_DEVICES * 8) {
                return Map.of();
            }
            var pathInfo = arena.allocate(Gdi.PATH_INFO_SIZE * paths, 8);
            var modeInfo = arena.allocate(Gdi.MODE_INFO_SIZE * Math.max(1, modes), 8);
            var result = Gdi.queryDisplayConfig(pathCount, pathInfo, modeCount, modeInfo);
            if (result == ERROR_INSUFFICIENT_BUFFER) {
                continue;
            }
            if (result != 0) {
                return Map.of();
            }
            return names(pathInfo, Math.min(paths, pathCount.get(JAVA_INT, 0)), arena);
        }
        return Map.of();
    }

    /**
     * Resolves the GDI device name and the friendly name of every display path.
     *
     * @param paths the {@code DISPLAYCONFIG_PATH_INFO} array
     * @param count the number of paths
     * @param arena the arena of the temporary structures
     * @return the non-empty friendly names by GDI device name
     */
    private static Map<String, String> names(MemorySegment paths, int count, Arena arena) {
        var names = new HashMap<String, String>();
        var source = arena.allocate(Gdi.SOURCE_NAME_SIZE, 8);
        var target = arena.allocate(Gdi.TARGET_NAME_SIZE, 8);
        for (var i = 0; i < count; i++) {
            var path = paths.asSlice(i * Gdi.PATH_INFO_SIZE, Gdi.PATH_INFO_SIZE);
            header(source, Gdi.GET_SOURCE_NAME, Gdi.SOURCE_NAME_SIZE, path, Gdi.PATH_SOURCE_ADAPTER, Gdi.PATH_SOURCE_ID);
            if (Gdi.displayConfigDeviceInfo(source) != 0) {
                continue;
            }
            header(target, Gdi.GET_TARGET_NAME, Gdi.TARGET_NAME_SIZE, path, Gdi.PATH_TARGET_ADAPTER, Gdi.PATH_TARGET_ID);
            if (Gdi.displayConfigDeviceInfo(target) != 0) {
                continue;
            }
            var deviceName = Com.wideString(source.asSlice(Gdi.SOURCE_NAME_GDI_NAME, 64));
            var friendly = Com.wideString(target.asSlice(Gdi.TARGET_NAME_FRIENDLY, 128)).strip();
            if (!deviceName.isEmpty() && !friendly.isEmpty()) {
                names.putIfAbsent(deviceName, friendly);
            }
        }
        return names;
    }

    /**
     * Fills the {@code DISPLAYCONFIG_DEVICE_INFO_HEADER} of a request from a display path.
     *
     * @param request       the request structure
     * @param type          the request type
     * @param size          the size of the request structure
     * @param path          the {@code DISPLAYCONFIG_PATH_INFO}
     * @param adapterOffset the offset of the path element's adapter LUID
     * @param idOffset      the offset of the path element's identifier
     */
    private static void header(MemorySegment request, int type, int size, MemorySegment path, long adapterOffset, long idOffset) {
        request.fill((byte) 0);
        request.set(JAVA_INT, 0, type);
        request.set(JAVA_INT, 4, size);
        request.set(JAVA_LONG_UNALIGNED, 8, path.get(JAVA_LONG_UNALIGNED, adapterOffset));
        request.set(JAVA_INT, 16, path.get(JAVA_INT, idOffset));
    }
}
