package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.capture.CaptureDevice;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lists the screens of the X11 display named by the {@code DISPLAY} environment variable.
 *
 * <p>With RandR 1.5 each active monitor ({@code XRRGetMonitors}) is a screen, identified by its
 * monitor name (for example {@code DP-1}); otherwise the whole default screen is one screen
 * identified by the display name and screen number (for example {@code :0.0}). Monitor
 * rectangles are clipped to the root window. The primary monitor is listed first, the others by
 * position.
 *
 * <p>Without {@code DISPLAY}, as in a pure Wayland session, or without {@code libX11.so.6}, there
 * is no screen: capturing a Wayland desktop requires the {@code xdg-desktop-portal} screen cast
 * portal and PipeWire, which this backend does not implement. Under XWayland only the content of
 * X11 clients is visible to X11 capture.
 */
final class X11Screens {
    /**
     * Prevents instantiation.
     */
    private X11Screens() {
        throw new AssertionError();
    }

    /**
     * Lists the screens.
     *
     * @return the screens, the primary one first; empty without {@code DISPLAY} or libX11
     * @throws MediaException.Io if {@code DISPLAY} is set but the X server cannot be reached
     */
    static List<CaptureDevice.Screen> list() {
        var name = System.getenv("DISPLAY");
        if (name == null || name.isEmpty()) {
            return List.of();
        }
        var x11 = X11.instance();
        if (x11 == null) {
            return List.of();
        }
        var display = x11.openDisplay();
        if (display.equals(MemorySegment.NULL)) {
            throw new MediaException.Io("cannot connect to the X server " + name,
                    new IOException("XOpenDisplay(\"" + name + "\") failed"));
        }
        X11ErrorTrap.register(x11, display);
        try {
            var screen = x11.defaultScreen(display);
            var root = x11.rootWindow(display, screen);
            var rootWidth = x11.displayWidth(display, screen);
            var rootHeight = x11.displayHeight(display, screen);
            var screens = monitors(x11, display, root, rootWidth, rootHeight);
            if (screens.isEmpty() && rootWidth > 0 && rootHeight > 0) {
                var id = x11.displayString(display) + "." + screen;
                screens.add(new CaptureDevice.Screen(id, "X11 screen " + id, 0, 0, rootWidth, rootHeight, true));
            }
            screens.sort(order());
            return List.copyOf(screens);
        } finally {
            x11.closeDisplay(display);
            X11ErrorTrap.unregister(display);
        }
    }

    /**
     * Returns the order of the listed screens.
     *
     * @return primary first, then top to bottom and left to right
     */
    static Comparator<CaptureDevice.Screen> order() {
        return Comparator.comparing((CaptureDevice.Screen screen) -> !screen.primary())
                .thenComparingInt(CaptureDevice.Screen::y)
                .thenComparingInt(CaptureDevice.Screen::x);
    }

    /**
     * Lists the RandR monitors.
     *
     * @param x11 the bindings
     * @param root the root window
     * @param rootWidth the root window width
     * @param rootHeight the root window height
     * @return the monitors clipped to the root window; empty without RandR 1.5
     */
    private static List<CaptureDevice.Screen> monitors(X11 x11, MemorySegment display, long root, int rootWidth, int rootHeight) {
        var screens = new ArrayList<CaptureDevice.Screen>();
        if (!x11.hasMonitors() || x11.randrVersion(display) < 1005) {
            return screens;
        }
        try (var arena = Arena.ofConfined()) {
            var count = arena.allocate(JAVA_INT);
            var monitors = x11.getMonitors(display, root, count);
            if (monitors.equals(MemorySegment.NULL)) {
                return screens;
            }
            try {
                var total = Math.min(count.get(JAVA_INT, 0), 64);
                var array = monitors.reinterpret(Math.max(total, 0) * X11.MONITOR_INFO.byteSize());
                for (var index = 0; index < total; index++) {
                    var base = index * X11.MONITOR_INFO.byteSize();
                    var x = array.get(JAVA_INT, base + X11.MONITOR_X);
                    var y = array.get(JAVA_INT, base + X11.MONITOR_Y);
                    var left = Math.max(x, 0);
                    var top = Math.max(y, 0);
                    var right = (int) Math.min((long) x + array.get(JAVA_INT, base + X11.MONITOR_WIDTH), rootWidth);
                    var bottom = (int) Math.min((long) y + array.get(JAVA_INT, base + X11.MONITOR_HEIGHT), rootHeight);
                    if (right <= left || bottom <= top) {
                        continue;
                    }
                    var monitorName = x11.atomName(display, array.get(JAVA_LONG, base + X11.MONITOR_NAME));
                    var id = monitorName == null || monitorName.isEmpty() ? "monitor-" + index : monitorName;
                    var primary = array.get(JAVA_INT, base + X11.MONITOR_PRIMARY) != 0;
                    screens.add(new CaptureDevice.Screen(id, id, left, top, right - left, bottom - top, primary));
                }
            } finally {
                x11.freeMonitors(monitors);
            }
        }
        return screens;
    }
}
