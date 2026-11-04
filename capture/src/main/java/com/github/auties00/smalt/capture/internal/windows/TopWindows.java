package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.CaptureDevice;
import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Lists the top-level windows of the desktop and calls the window functions of {@code user32.dll},
 * {@code dwmapi.dll} and {@code kernel32.dll} that window capture uses.
 *
 * <p>The windows are walked in z-order, front to back ({@code GetTopWindow}, {@code GetWindow}), and the
 * ones a user can switch to are kept: visible, not minimized, not cloaked by the window manager (a window
 * of another virtual desktop or a suspended application), with a title, and either unowned and not a tool
 * window, or marked as an application window. The shell's desktop window is left out. A window's bounds
 * are its extended frame bounds, the rectangle a user sees without the invisible resize borders, in
 * physical pixels; its application is its process's executable name without the {@code .exe} extension.
 */
final class TopWindows {
    private static final int GW_HWNDNEXT = 2;

    private static final int GW_OWNER = 4;

    private static final int GWL_EXSTYLE = -20;

    private static final long WS_EX_TOOLWINDOW = 0x00000080L;

    private static final long WS_EX_APPWINDOW = 0x00040000L;

    private static final int DWMWA_EXTENDED_FRAME_BOUNDS = 9;

    private static final int DWMWA_CLOAKED = 14;

    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

    /**
     * The {@code PW_RENDERFULLCONTENT} flag of {@code PrintWindow}, which captures windows drawn with
     * DirectComposition.
     */
    static final int PW_RENDERFULLCONTENT = 0x2;

    /**
     * The most windows walked, a guard against a z-order that cycles while it is walked.
     */
    private static final int MAX_WINDOWS = 1 << 14;

    /**
     * The most characters read of a title or a path.
     */
    private static final int MAX_TEXT = 1024;

    private static final Functions FUNCTIONS = Functions.load();

    private TopWindows() {
        throw new AssertionError();
    }

    /**
     * The linked functions.
     */
    private record Functions(MethodHandle getTopWindow, MethodHandle getWindow, MethodHandle isWindowVisible,
                             MethodHandle isIconic, MethodHandle isWindow, MethodHandle getWindowLong,
                             MethodHandle getWindowTextLength, MethodHandle getWindowText, MethodHandle getProcessId,
                             MethodHandle getShellWindow, MethodHandle getWindowRect, MethodHandle printWindow,
                             MethodHandle getWindowDc, MethodHandle releaseDc, MethodHandle dwmAttribute,
                             MethodHandle openProcess, MethodHandle processImageName, MethodHandle closeHandle) {
        static Functions load() {
            var user = Native.library("user32").orElse(null);
            var kernel = Native.library("kernel32").orElse(null);
            if (user == null || kernel == null) {
                return null;
            }
            try {
                var dwm = Native.library("dwmapi").flatMap(library -> Native.optionalFunction(library,
                        "DwmGetWindowAttribute", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)))
                        .orElse(null);
                return new Functions(
                        Native.function(user, "GetTopWindow", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(user, "GetWindow", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(user, "IsWindowVisible", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "IsIconic", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "IsWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "GetWindowLongPtrW", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT)),
                        Native.function(user, "GetWindowTextLengthW", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "GetWindowTextW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(user, "GetWindowThreadProcessId", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(user, "GetShellWindow", FunctionDescriptor.of(ADDRESS)),
                        Native.function(user, "GetWindowRect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(user, "PrintWindow", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(user, "GetWindowDC", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(user, "ReleaseDC", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        dwm,
                        Native.function(kernel, "OpenProcess", FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
                        Native.function(kernel, "QueryFullProcessImageNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS,
                                JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(kernel, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * {@return whether the window functions are available}
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Lists the windows.
     *
     * <p>Runs on a thread that uses physical pixels ({@link Gdi#usePhysicalPixels()}).
     *
     * @return the windows, front to back; empty when the functions are unavailable
     */
    static List<CaptureDevice.Window> list() {
        if (FUNCTIONS == null) {
            return List.of();
        }
        var windows = new ArrayList<CaptureDevice.Window>();
        try (var arena = Arena.ofConfined()) {
            var rect = arena.allocate(JAVA_INT, 4);
            var number = arena.allocate(JAVA_INT);
            var text = arena.allocate(2L * (MAX_TEXT + 1), 2);
            var shell = shellWindow();
            var handle = topWindow();
            for (var walked = 0; !handle.equals(MemorySegment.NULL) && walked < MAX_WINDOWS; walked++) {
                var described = describe(handle, shell, rect, number, text);
                if (described != null) {
                    windows.add(described);
                }
                handle = next(handle);
            }
        }
        return List.copyOf(windows);
    }

    /**
     * Returns the window with an identifier, as it is now.
     *
     * @param id the identifier, a window handle in decimal
     * @return the window, or empty when it is not a listed window
     */
    static Optional<CaptureDevice.Window> find(String id) {
        return list().stream().filter(window -> window.id().equals(id)).findFirst();
    }

    /**
     * Parses the window handle of an identifier.
     *
     * @param id the identifier
     * @return the handle, or {@link MemorySegment#NULL} when the identifier is not a decimal handle
     */
    static MemorySegment handle(String id) {
        try {
            return MemorySegment.ofAddress(Long.parseUnsignedLong(id));
        } catch (NumberFormatException exception) {
            return MemorySegment.NULL;
        }
    }

    /**
     * Describes a window, or returns {@code null} for one that is not listed.
     */
    private static CaptureDevice.Window describe(MemorySegment handle, MemorySegment shell, MemorySegment rect,
                                                 MemorySegment number, MemorySegment text) {
        if (handle.equals(shell) || !call(FUNCTIONS.isWindowVisible(), handle) || call(FUNCTIONS.isIconic(), handle)) {
            return null;
        }
        var style = exStyle(handle);
        var owned = !owner(handle).equals(MemorySegment.NULL);
        var app = (style & WS_EX_APPWINDOW) != 0;
        if (!app && (owned || (style & WS_EX_TOOLWINDOW) != 0)) {
            return null;
        }
        if (cloaked(handle, number)) {
            return null;
        }
        var title = title(handle, text);
        if (title.isEmpty() || !bounds(handle, rect)) {
            return null;
        }
        var left = rect.getAtIndex(JAVA_INT, 0);
        var top = rect.getAtIndex(JAVA_INT, 1);
        var width = rect.getAtIndex(JAVA_INT, 2) - left;
        var height = rect.getAtIndex(JAVA_INT, 3) - top;
        if (width < 2 || height < 2) {
            return null;
        }
        return new CaptureDevice.Window(Long.toUnsignedString(handle.address()), title, application(handle, number, text),
                left, top, width, height);
    }

    /**
     * Reads the visible bounds of a window: its extended frame bounds, or its window rectangle without DWM.
     *
     * @param rect four integers receiving left, top, right and bottom
     * @return whether the bounds were read
     */
    static boolean bounds(MemorySegment handle, MemorySegment rect) {
        if (FUNCTIONS.dwmAttribute() != null) {
            try {
                if ((int) FUNCTIONS.dwmAttribute().invokeExact(handle, DWMWA_EXTENDED_FRAME_BOUNDS, rect, 16) >= 0) {
                    return true;
                }
            } catch (Throwable throwable) {
                throw Native.rethrow(throwable);
            }
        }
        return windowRect(handle, rect);
    }

    /**
     * Reads the window rectangle, which includes the invisible resize borders.
     *
     * @param rect four integers receiving left, top, right and bottom
     * @return whether the rectangle was read
     */
    static boolean windowRect(MemorySegment handle, MemorySegment rect) {
        try {
            return (int) FUNCTIONS.getWindowRect().invokeExact(handle, rect) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether a handle names an existing window.
     */
    static boolean exists(MemorySegment handle) {
        return FUNCTIONS != null && call(FUNCTIONS.isWindow(), handle);
    }

    /**
     * Returns whether a window is minimized.
     */
    static boolean minimized(MemorySegment handle) {
        return call(FUNCTIONS.isIconic(), handle);
    }

    /**
     * Draws a window, its frame included, into a device context.
     *
     * @param dc the target {@code HDC}, whose origin receives the window's top-left corner
     * @return whether the window was drawn
     */
    static boolean print(MemorySegment handle, MemorySegment dc) {
        try {
            return (int) FUNCTIONS.printWindow().invokeExact(handle, dc, PW_RENDERFULLCONTENT) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the device context of a whole window, its frame included.
     *
     * @return the {@code HDC}, or {@link MemorySegment#NULL} on failure
     */
    static MemorySegment windowDc(MemorySegment handle) {
        try {
            return (MemorySegment) FUNCTIONS.getWindowDc().invokeExact(handle);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Releases a device context returned by {@link #windowDc}.
     */
    static void releaseWindowDc(MemorySegment handle, MemorySegment dc) {
        try {
            var ignored = (int) FUNCTIONS.releaseDc().invokeExact(handle, dc);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    private static MemorySegment topWindow() {
        try {
            return (MemorySegment) FUNCTIONS.getTopWindow().invokeExact(MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    private static MemorySegment next(MemorySegment handle) {
        try {
            return (MemorySegment) FUNCTIONS.getWindow().invokeExact(handle, GW_HWNDNEXT);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    private static MemorySegment owner(MemorySegment handle) {
        try {
            return (MemorySegment) FUNCTIONS.getWindow().invokeExact(handle, GW_OWNER);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    private static MemorySegment shellWindow() {
        try {
            return (MemorySegment) FUNCTIONS.getShellWindow().invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    private static long exStyle(MemorySegment handle) {
        try {
            return (long) FUNCTIONS.getWindowLong().invokeExact(handle, GWL_EXSTYLE);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether the window manager cloaks a window.
     *
     * @param number an integer of scratch memory
     */
    private static boolean cloaked(MemorySegment handle, MemorySegment number) {
        if (FUNCTIONS.dwmAttribute() == null) {
            return false;
        }
        number.set(JAVA_INT, 0, 0);
        try {
            var hr = (int) FUNCTIONS.dwmAttribute().invokeExact(handle, DWMWA_CLOAKED, number, 4);
            return hr >= 0 && number.get(JAVA_INT, 0) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a window's title.
     *
     * @param text scratch memory of {@code MAX_TEXT + 1} characters
     * @return the title, or an empty string
     */
    private static String title(MemorySegment handle, MemorySegment text) {
        try {
            var length = (int) FUNCTIONS.getWindowTextLength().invokeExact(handle);
            if (length <= 0) {
                return "";
            }
            var read = (int) FUNCTIONS.getWindowText().invokeExact(handle, text, MAX_TEXT + 1);
            return read <= 0 ? "" : utf16(text, Math.min(read, MAX_TEXT));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the executable name of a window's process.
     *
     * @param number an integer of scratch memory
     * @param text scratch memory of {@code MAX_TEXT + 1} characters
     * @return the name without its directory and {@code .exe} extension, or an empty string
     */
    private static String application(MemorySegment handle, MemorySegment number, MemorySegment text) {
        try {
            number.set(JAVA_INT, 0, 0);
            var ignored = (int) FUNCTIONS.getProcessId().invokeExact(handle, number);
            var pid = number.get(JAVA_INT, 0);
            if (pid == 0) {
                return "";
            }
            var process = (MemorySegment) FUNCTIONS.openProcess().invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if (process.equals(MemorySegment.NULL)) {
                return "";
            }
            try {
                number.set(JAVA_INT, 0, MAX_TEXT);
                if ((int) FUNCTIONS.processImageName().invokeExact(process, 0, text, number) == 0) {
                    return "";
                }
                return executableName(utf16(text, Math.min(number.get(JAVA_INT, 0), MAX_TEXT)));
            } finally {
                var released = (int) FUNCTIONS.closeHandle().invokeExact(process);
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the file name of a path without its {@code .exe} extension.
     *
     * @param path the executable's path
     * @return the name
     */
    static String executableName(String path) {
        var name = path.substring(Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1);
        return name.toLowerCase(Locale.ROOT).endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static String utf16(MemorySegment text, int characters) {
        return new String(text.asSlice(0, 2L * characters).toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
    }

    private static boolean call(MethodHandle function, MemorySegment handle) {
        try {
            return (int) function.invokeExact(handle) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }
}
