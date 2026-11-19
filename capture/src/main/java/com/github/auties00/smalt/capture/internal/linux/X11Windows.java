package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lists the top-level windows of an X11 display.
 *
 * <p>Under a window manager that follows the Extended Window Manager Hints, the windows are its client
 * list ({@code _NET_CLIENT_LIST_STACKING}, or {@code _NET_CLIENT_LIST}); without one they are the children
 * of the root window, leaving out the override-redirect ones (menus, tooltips). Either list is reported
 * front to back and keeps only viewable {@code InputOutput} windows of at least 2x2 pixels. A window's
 * title is its {@code _NET_WM_NAME}, or its {@code WM_NAME}; its application is the command name of its
 * {@code _NET_WM_PID} process, or the class of its {@code WM_CLASS}.
 */
final class X11Windows {
    /**
     * The most 32-bit units read of a window list or a title.
     */
    private static final long PROPERTY_LIMIT = 1 << 16;

    private X11Windows() {
        throw new AssertionError();
    }

    /**
     * Lists the windows of the display named by {@code DISPLAY}.
     *
     * @return the windows, front to back; empty without a display or Xlib
     * @throws MediaException.Io if the X server cannot be reached
     */
    static List<CaptureDevice.Window> list() {
        var name = System.getenv("DISPLAY");
        var windowing = X11Windowing.instance();
        if (name == null || name.isEmpty() || windowing == null) {
            return List.of();
        }
        var x11 = windowing.x11();
        var display = x11.openDisplay();
        if (display.equals(MemorySegment.NULL)) {
            throw new MediaException.Io("cannot connect to the X server " + name,
                    new IOException("XOpenDisplay(\"" + name + "\") failed"));
        }
        X11ErrorTrap.register(x11, display);
        try {
            return list(windowing, display);
        } finally {
            x11.closeDisplay(display);
            X11ErrorTrap.unregister(display);
        }
    }

    /**
     * Returns the window with an identifier.
     *
     * @param id the identifier
     * @return the window, or empty when it is not a listed window
     * @throws MediaException.Io if the X server cannot be reached
     */
    static Optional<CaptureDevice.Window> find(String id) {
        return list().stream().filter(window -> window.id().equals(id)).findFirst();
    }

    /**
     * Lists the windows over an open display.
     */
    private static List<CaptureDevice.Window> list(X11Windowing windowing, MemorySegment display) {
        var x11 = windowing.x11();
        var root = x11.rootWindow(display, x11.defaultScreen(display));
        var managed = clientList(windowing, display, root);
        var candidates = managed;
        if (candidates == null) {
            var children = windowing.children(display, root);
            X11ErrorTrap.takeError(display);
            candidates = children == null ? new long[0] : children;
        }
        var ordered = new LinkedHashSet<Long>();
        for (var index = candidates.length - 1; index >= 0; index--) {
            ordered.add(candidates[index]);
        }
        var windows = new ArrayList<CaptureDevice.Window>();
        try (var arena = Arena.ofConfined()) {
            var attributes = arena.allocate(X11Windowing.WINDOW_ATTRIBUTES);
            for (long window : ordered) {
                attributes.fill((byte) 0);
                var ok = windowing.attributes(display, window, attributes);
                if (X11ErrorTrap.takeError(display) != 0 || !ok) {
                    continue;
                }
                var width = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_WIDTH);
                var height = attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_HEIGHT);
                if (attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_MAP_STATE) != X11Windowing.IS_VIEWABLE
                        || attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_CLASS) != X11Windowing.INPUT_OUTPUT
                        || (managed == null && attributes.get(JAVA_INT, X11Windowing.ATTRIBUTES_OVERRIDE_REDIRECT) != 0)
                        || width < 2 || height < 2) {
                    continue;
                }
                var origin = windowing.origin(display, window, root);
                if (X11ErrorTrap.takeError(display) != 0 || origin == null) {
                    continue;
                }
                windows.add(new CaptureDevice.Window(Long.toUnsignedString(window), title(windowing, display, window),
                        application(windowing, display, window), origin[0], origin[1], width, height));
            }
        }
        return List.copyOf(windows);
    }

    /**
     * Reads the window manager's client list, bottom to top.
     *
     * @return the windows, or {@code null} without a window manager that publishes one
     */
    private static long[] clientList(X11Windowing windowing, MemorySegment display, long root) {
        for (var property : new String[]{"_NET_CLIENT_LIST_STACKING", "_NET_CLIENT_LIST"}) {
            var atom = windowing.atom(display, property);
            var value = windowing.property(display, root, atom, X11Windowing.XA_WINDOW, PROPERTY_LIMIT);
            X11ErrorTrap.takeError(display);
            if (value != null && value.format() == 32) {
                return value.longs();
            }
        }
        return null;
    }

    /**
     * Reads a window's title.
     *
     * @return the title, or an empty string
     */
    private static String title(X11Windowing windowing, MemorySegment display, long window) {
        var utf8 = windowing.atom(display, "UTF8_STRING");
        var name = windowing.property(display, window, windowing.atom(display, "_NET_WM_NAME"), utf8, PROPERTY_LIMIT);
        X11ErrorTrap.takeError(display);
        if (name != null && !name.text().isEmpty()) {
            return name.text();
        }
        var legacy = windowing.property(display, window, windowing.atom(display, "WM_NAME"), 0, PROPERTY_LIMIT);
        X11ErrorTrap.takeError(display);
        return legacy == null ? "" : legacy.text();
    }

    /**
     * Reads the name of a window's application.
     *
     * @return the name, or an empty string
     */
    private static String application(X11Windowing windowing, MemorySegment display, long window) {
        var pid = windowing.property(display, window, windowing.atom(display, "_NET_WM_PID"), X11Windowing.XA_CARDINAL, 1);
        X11ErrorTrap.takeError(display);
        if (pid != null && pid.count() == 1) {
            var process = pid.longs()[0] & 0xFFFFFFFFL;
            try {
                var command = Files.readString(Path.of("/proc", Long.toString(process), "comm"), StandardCharsets.UTF_8).strip();
                if (!command.isEmpty()) {
                    return command;
                }
            } catch (IOException | RuntimeException exception) {
                // a process of another host or already gone: the window class names the application instead
            }
        }
        var hint = windowing.property(display, window, X11Windowing.XA_WM_CLASS, X11Windowing.XA_STRING, PROPERTY_LIMIT);
        X11ErrorTrap.takeError(display);
        return hint == null ? "" : className(hint.data());
    }

    /**
     * Extracts the class from a {@code WM_CLASS} value, two {@code NUL}-terminated strings.
     *
     * @param data the property's bytes
     * @return the second string, or the first when there is no second, or an empty string
     */
    static String className(byte[] data) {
        var end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        var instance = new String(data, 0, end, StandardCharsets.UTF_8);
        if (end + 1 >= data.length) {
            return instance;
        }
        var start = end + 1;
        var stop = start;
        while (stop < data.length && data[stop] != 0) {
            stop++;
        }
        var type = new String(data, start, stop - start, StandardCharsets.UTF_8);
        return type.isEmpty() ? instance : type;
    }

    /**
     * Reads the color masks of a visual.
     *
     * @param visual the address of the {@code Visual}
     * @return the red, green and blue masks, or {@code null} for a null visual
     */
    static long[] masks(long visual) {
        if (visual == 0) {
            return null;
        }
        var segment = MemorySegment.ofAddress(visual).reinterpret(X11Windowing.VISUAL.byteSize());
        return new long[]{
                segment.get(JAVA_LONG, X11Windowing.VISUAL_RED_MASK),
                segment.get(JAVA_LONG, X11Windowing.VISUAL_GREEN_MASK),
                segment.get(JAVA_LONG, X11Windowing.VISUAL_BLUE_MASK)};
    }
}
