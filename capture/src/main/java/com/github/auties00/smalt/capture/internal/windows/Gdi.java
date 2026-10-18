package com.github.auties00.smalt.capture.internal.windows;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binds the {@code user32.dll} and {@code gdi32.dll} functions, constants and structure offsets
 * the screen backend uses: display enumeration, display names, the GDI screen copy and the
 * cursor.
 *
 * <p>Structure offsets are those of the 64-bit layouts declared in the Windows SDK headers
 * {@code WinUser.h} and {@code wingdi.h}. Functions return their raw results: {@code BOOL}
 * results are non-zero on success, {@code LONG} results of the display configuration functions
 * are {@code ERROR_SUCCESS} ({@code 0}) on success.
 */
final class Gdi {
    /**
     * The {@code DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2} pseudo handle.
     */
    private static final long PER_MONITOR_AWARE_V2 = -4;

    /**
     * The {@code SRCCOPY} raster operation.
     */
    private static final int SRCCOPY = 0x00CC0020;

    /**
     * The {@code CAPTUREBLT} flag: include layered windows.
     */
    private static final int CAPTUREBLT = 0x40000000;

    /**
     * The {@code DIB_RGB_COLORS} usage of {@code CreateDIBSection}.
     */
    private static final int DIB_RGB_COLORS = 0;

    /**
     * The {@code DI_NORMAL} flag of {@code DrawIconEx}.
     */
    private static final int DI_NORMAL = 0x3;

    /**
     * The {@code ENUM_CURRENT_SETTINGS} mode index of {@code EnumDisplaySettingsExW}.
     */
    static final int ENUM_CURRENT_SETTINGS = -1;

    /**
     * The {@code DISPLAY_DEVICE_ATTACHED_TO_DESKTOP} state flag.
     */
    static final int DISPLAY_ATTACHED_TO_DESKTOP = 0x1;

    /**
     * The {@code DISPLAY_DEVICE_MIRRORING_DRIVER} state flag.
     */
    static final int DISPLAY_MIRRORING_DRIVER = 0x8;

    /**
     * The size of {@code DISPLAY_DEVICEW}.
     */
    static final long DISPLAY_DEVICE_SIZE = 840;

    /**
     * The offset of {@code DISPLAY_DEVICEW.DeviceName} ({@code WCHAR[32]}).
     */
    static final long DISPLAY_DEVICE_NAME = 4;

    /**
     * The offset of {@code DISPLAY_DEVICEW.StateFlags}.
     */
    static final long DISPLAY_DEVICE_STATE = 324;

    /**
     * The size of {@code DEVMODEW}.
     */
    static final int DEVMODE_SIZE = 220;

    /**
     * The offset of {@code DEVMODEW.dmSize}.
     */
    static final long DEVMODE_DM_SIZE = 68;

    /**
     * The offset of {@code DEVMODEW.dmPosition.x}.
     */
    static final long DEVMODE_POSITION_X = 76;

    /**
     * The offset of {@code DEVMODEW.dmPosition.y}.
     */
    static final long DEVMODE_POSITION_Y = 80;

    /**
     * The offset of {@code DEVMODEW.dmPelsWidth}.
     */
    static final long DEVMODE_WIDTH = 172;

    /**
     * The offset of {@code DEVMODEW.dmPelsHeight}.
     */
    static final long DEVMODE_HEIGHT = 176;

    /**
     * The {@code QDC_ONLY_ACTIVE_PATHS} flag of {@code QueryDisplayConfig}.
     */
    static final int QDC_ONLY_ACTIVE_PATHS = 0x2;

    /**
     * The size of {@code DISPLAYCONFIG_PATH_INFO}.
     */
    static final long PATH_INFO_SIZE = 72;

    /**
     * The size of {@code DISPLAYCONFIG_MODE_INFO}.
     */
    static final long MODE_INFO_SIZE = 64;

    /**
     * The offset of {@code DISPLAYCONFIG_PATH_INFO.sourceInfo.adapterId}.
     */
    static final long PATH_SOURCE_ADAPTER = 0;

    /**
     * The offset of {@code DISPLAYCONFIG_PATH_INFO.sourceInfo.id}.
     */
    static final long PATH_SOURCE_ID = 8;

    /**
     * The offset of {@code DISPLAYCONFIG_PATH_INFO.targetInfo.adapterId}.
     */
    static final long PATH_TARGET_ADAPTER = 20;

    /**
     * The offset of {@code DISPLAYCONFIG_PATH_INFO.targetInfo.id}.
     */
    static final long PATH_TARGET_ID = 28;

    /**
     * The {@code DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME} request.
     */
    static final int GET_SOURCE_NAME = 1;

    /**
     * The {@code DISPLAYCONFIG_DEVICE_INFO_GET_TARGET_NAME} request.
     */
    static final int GET_TARGET_NAME = 2;

    /**
     * The size of {@code DISPLAYCONFIG_SOURCE_DEVICE_NAME}.
     */
    static final int SOURCE_NAME_SIZE = 84;

    /**
     * The offset of {@code DISPLAYCONFIG_SOURCE_DEVICE_NAME.viewGdiDeviceName} ({@code WCHAR[32]}).
     */
    static final long SOURCE_NAME_GDI_NAME = 20;

    /**
     * The size of {@code DISPLAYCONFIG_TARGET_DEVICE_NAME}.
     */
    static final int TARGET_NAME_SIZE = 420;

    /**
     * The offset of {@code DISPLAYCONFIG_TARGET_DEVICE_NAME.monitorFriendlyDeviceName}
     * ({@code WCHAR[64]}).
     */
    static final long TARGET_NAME_FRIENDLY = 36;

    /**
     * The size of {@code CURSORINFO}.
     */
    static final int CURSOR_INFO_SIZE = 24;

    /**
     * The {@code CURSOR_SHOWING} flag of {@code CURSORINFO.flags}.
     */
    static final int CURSOR_SHOWING = 0x1;

    /**
     * The size of {@code ICONINFO}.
     */
    static final long ICON_INFO_SIZE = 32;

    /**
     * The size of {@code BITMAPINFO} with one color entry.
     */
    static final long BITMAP_INFO_SIZE = 44;

    /**
     * The bound functions, or {@code null} when a library is missing.
     */
    private static final Functions FUNCTIONS = Functions.load();

    /**
     * Prevents instantiation.
     */
    private Gdi() {
        throw new AssertionError();
    }

    /**
     * Binds the exported functions.
     *
     * @param getDc {@code HDC GetDC(HWND)}
     * @param releaseDc {@code int ReleaseDC(HWND, HDC)}
     * @param setDpiContext {@code DPI_AWARENESS_CONTEXT SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT)},
     * or {@code null} before Windows 10 1607
     * @param enumDisplayDevices {@code BOOL EnumDisplayDevicesW(LPCWSTR, DWORD, PDISPLAY_DEVICEW, DWORD)}
     * @param enumDisplaySettings {@code BOOL EnumDisplaySettingsExW(LPCWSTR, DWORD, DEVMODEW*, DWORD)}
     * @param configBufferSizes {@code LONG GetDisplayConfigBufferSizes(UINT32, UINT32*, UINT32*)}
     * @param queryConfig {@code LONG QueryDisplayConfig(UINT32, UINT32*, PATH_INFO*, UINT32*, MODE_INFO*,
     * TOPOLOGY_ID*)}
     * @param configDeviceInfo {@code LONG DisplayConfigGetDeviceInfo(DISPLAYCONFIG_DEVICE_INFO_HEADER*)}
     * @param getCursorInfo {@code BOOL GetCursorInfo(PCURSORINFO)}
     * @param getIconInfo {@code BOOL GetIconInfo(HICON, PICONINFO)}
     * @param drawIcon {@code BOOL DrawIconEx(HDC, int, int, HICON, int, int, UINT, HBRUSH, UINT)}
     * @param createCompatibleDc {@code HDC CreateCompatibleDC(HDC)}
     * @param createDibSection {@code HBITMAP CreateDIBSection(HDC, const BITMAPINFO*, UINT, void**, HANDLE, DWORD)}
     * @param selectObject {@code HGDIOBJ SelectObject(HDC, HGDIOBJ)}
     * @param deleteObject {@code BOOL DeleteObject(HGDIOBJ)}
     * @param deleteDc {@code BOOL DeleteDC(HDC)}
     * @param bitBlt {@code BOOL BitBlt(HDC, int, int, int, int, HDC, int, int, DWORD)}
     * @param flush {@code BOOL GdiFlush()}
     */
    private record Functions(MethodHandle getDc, MethodHandle releaseDc, MethodHandle setDpiContext,
                             MethodHandle enumDisplayDevices, MethodHandle enumDisplaySettings,
                             MethodHandle configBufferSizes, MethodHandle queryConfig, MethodHandle configDeviceInfo,
                             MethodHandle getCursorInfo, MethodHandle getIconInfo, MethodHandle drawIcon,
                             MethodHandle createCompatibleDc, MethodHandle createDibSection, MethodHandle selectObject,
                             MethodHandle deleteObject, MethodHandle deleteDc, MethodHandle bitBlt, MethodHandle flush) {
        /**
         * Loads the libraries and binds the functions.
         *
         * @return the bindings, or {@code null} when a library or a required function is missing
         */
        static Functions load() {
            var user = Native.library("user32").orElse(null);
            var gdi = Native.library("gdi32").orElse(null);
            if (user == null || gdi == null) {
                return null;
            }
            try {
                MethodHandle setDpiContext;
                try {
                    setDpiContext = Native.function(user, "SetThreadDpiAwarenessContext", FunctionDescriptor.of(ADDRESS, ADDRESS));
                } catch (RuntimeException exception) {
                    setDpiContext = null;
                }
                return new Functions(
                        Native.function(user, "GetDC", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(user, "ReleaseDC", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        setDpiContext,
                        Native.function(user, "EnumDisplayDevicesW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(user, "EnumDisplaySettingsExW", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(user, "GetDisplayConfigBufferSizes", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(user, "QueryDisplayConfig", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(user, "DisplayConfigGetDeviceInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "GetCursorInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(user, "GetIconInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        Native.function(user, "DrawIconEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS,
                                JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
                        Native.function(gdi, "CreateCompatibleDC", FunctionDescriptor.of(ADDRESS, ADDRESS)),
                        Native.function(gdi, "CreateDIBSection", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)),
                        Native.function(gdi, "SelectObject", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
                        Native.function(gdi, "DeleteObject", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(gdi, "DeleteDC", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                        Native.function(gdi, "BitBlt", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                                ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT)),
                        Native.function(gdi, "GdiFlush", FunctionDescriptor.of(JAVA_INT)));
            } catch (RuntimeException | UnsatisfiedLinkError exception) {
                return null;
            }
        }
    }

    /**
     * Returns whether the GDI functions are available.
     *
     * @return whether every required function was bound
     */
    static boolean available() {
        return FUNCTIONS != null;
    }

    /**
     * Makes the calling thread per-monitor DPI aware, so that coordinates and sizes are physical
     * pixels.
     *
     * <p>Does nothing on systems without per-thread DPI awareness.
     */
    static void usePhysicalPixels() {
        if (FUNCTIONS == null || FUNCTIONS.setDpiContext() == null) {
            return;
        }
        try {
            var ignored = (MemorySegment) FUNCTIONS.setDpiContext().invokeExact(MemorySegment.ofAddress(PER_MONITOR_AWARE_V2));
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the device context of the whole virtual screen.
     *
     * @return the {@code HDC}, or {@link MemorySegment#NULL} on failure
     */
    static MemorySegment screenDc() {
        try {
            return (MemorySegment) FUNCTIONS.getDc().invokeExact(MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Releases the device context of the virtual screen.
     *
     * @param dc the {@code HDC} returned by {@link #screenDc()}
     */
    static void releaseScreenDc(MemorySegment dc) {
        try {
            var ignored = (int) FUNCTIONS.releaseDc().invokeExact(MemorySegment.NULL, dc);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Describes a display device.
     *
     * @param index the device's index
     * @param device a {@code DISPLAY_DEVICEW} whose {@code cb} field is set
     * @return whether a device exists at {@code index}
     */
    static boolean enumDisplayDevice(int index, MemorySegment device) {
        try {
            return (int) FUNCTIONS.enumDisplayDevices().invokeExact(MemorySegment.NULL, index, device, 0) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the current mode of a display device.
     *
     * @param name the device name, a null-terminated UTF-16 string
     * @param mode a {@code DEVMODEW} whose {@code dmSize} field is set
     * @return whether the mode was read
     */
    static boolean currentSettings(MemorySegment name, MemorySegment mode) {
        try {
            return (int) FUNCTIONS.enumDisplaySettings().invokeExact(name, ENUM_CURRENT_SETTINGS, mode, 0) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the buffer sizes {@code QueryDisplayConfig} needs.
     *
     * @param paths a 32-bit slot receiving the number of paths
     * @param modes a 32-bit slot receiving the number of modes
     * @return the Win32 error code
     */
    static int displayConfigBufferSizes(MemorySegment paths, MemorySegment modes) {
        try {
            return (int) FUNCTIONS.configBufferSizes().invokeExact(QDC_ONLY_ACTIVE_PATHS, paths, modes);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the active display paths.
     *
     * @param pathCount the capacity of {@code paths} on input, the number read on output
     * @param paths the {@code DISPLAYCONFIG_PATH_INFO} array
     * @param modeCount the capacity of {@code modes} on input, the number read on output
     * @param modes the {@code DISPLAYCONFIG_MODE_INFO} array
     * @return the Win32 error code
     */
    static int queryDisplayConfig(MemorySegment pathCount, MemorySegment paths, MemorySegment modeCount, MemorySegment modes) {
        try {
            return (int) FUNCTIONS.queryConfig().invokeExact(QDC_ONLY_ACTIVE_PATHS, pathCount, paths, modeCount, modes, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a property of a display path element.
     *
     * @param request a structure starting with a filled {@code DISPLAYCONFIG_DEVICE_INFO_HEADER}
     * @return the Win32 error code
     */
    static int displayConfigDeviceInfo(MemorySegment request) {
        try {
            return (int) FUNCTIONS.configDeviceInfo().invokeExact(request);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the global cursor.
     *
     * @param info a {@code CURSORINFO} whose {@code cbSize} field is set
     * @return whether the cursor was read
     */
    static boolean cursorInfo(MemorySegment info) {
        try {
            return (int) FUNCTIONS.getCursorInfo().invokeExact(info) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the hot spot and bitmaps of a cursor.
     *
     * @param cursor the {@code HCURSOR}
     * @param info an {@code ICONINFO} receiving the description; the caller deletes its bitmaps
     * @return whether the description was read
     */
    static boolean iconInfo(MemorySegment cursor, MemorySegment info) {
        try {
            return (int) FUNCTIONS.getIconInfo().invokeExact(cursor, info) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Draws a cursor at its natural size.
     *
     * @param dc the target {@code HDC}
     * @param x the left edge of the cursor image
     * @param y the top edge of the cursor image
     * @param cursor the {@code HCURSOR}
     * @return whether the cursor was drawn
     */
    static boolean drawCursor(MemorySegment dc, int x, int y, MemorySegment cursor) {
        try {
            return (int) FUNCTIONS.drawIcon().invokeExact(dc, x, y, cursor, 0, 0, 0, MemorySegment.NULL, DI_NORMAL) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a memory device context compatible with another.
     *
     * @param dc the reference {@code HDC}
     * @return the new {@code HDC}, or {@link MemorySegment#NULL} on failure
     */
    static MemorySegment createCompatibleDc(MemorySegment dc) {
        try {
            return (MemorySegment) FUNCTIONS.createCompatibleDc().invokeExact(dc);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a top-down 32-bit device-independent bitmap whose pixels the process can read.
     *
     * @param dc a device context
     * @param info a {@code BITMAPINFO} describing the bitmap
     * @param pixels a pointer-sized slot receiving the address of the pixels
     * @return the {@code HBITMAP}, or {@link MemorySegment#NULL} on failure
     */
    static MemorySegment createDibSection(MemorySegment dc, MemorySegment info, MemorySegment pixels) {
        try {
            return (MemorySegment) FUNCTIONS.createDibSection().invokeExact(dc, info, DIB_RGB_COLORS, pixels, MemorySegment.NULL, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Selects an object into a device context.
     *
     * @param dc the {@code HDC}
     * @param object the {@code HGDIOBJ}
     * @return the previously selected object
     */
    static MemorySegment selectObject(MemorySegment dc, MemorySegment object) {
        try {
            return (MemorySegment) FUNCTIONS.selectObject().invokeExact(dc, object);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Deletes a GDI object.
     *
     * @param object the {@code HGDIOBJ}; {@link MemorySegment#NULL} is ignored
     */
    static void deleteObject(MemorySegment object) {
        if (object.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) FUNCTIONS.deleteObject().invokeExact(object);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Deletes a memory device context.
     *
     * @param dc the {@code HDC}; {@link MemorySegment#NULL} is ignored
     */
    static void deleteDc(MemorySegment dc) {
        if (dc.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) FUNCTIONS.deleteDc().invokeExact(dc);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Copies a rectangle of the screen, including layered windows, into a memory device context.
     *
     * @param target the memory {@code HDC}
     * @param width the width of the rectangle
     * @param height the height of the rectangle
     * @param screen the screen {@code HDC}
     * @param x the left edge of the rectangle on the virtual screen
     * @param y the top edge of the rectangle on the virtual screen
     * @return whether the copy succeeded
     */
    static boolean copyScreen(MemorySegment target, int width, int height, MemorySegment screen, int x, int y) {
        try {
            return (int) FUNCTIONS.bitBlt().invokeExact(target, 0, 0, width, height, screen, x, y, SRCCOPY | CAPTUREBLT) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Completes the pending GDI drawing of the calling thread.
     */
    static void flush() {
        try {
            var ignored = (int) FUNCTIONS.flush().invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }
}
