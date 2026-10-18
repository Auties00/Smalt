package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BOOLEAN;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls CoreGraphics: display enumeration, the Screen Recording permission, and the display
 * snapshot fallback.
 *
 * <p>This class loads the framework when it is initialized, which only happens on macOS.
 */
@SuppressWarnings("restricted")
final class CoreGraphics {
    /**
     * The framework.
     */
    private static final SymbolLookup LIBRARY = Frameworks.load(Frameworks.CORE_GRAPHICS);

    /**
     * {@code kCGErrorSuccess} ({@code CGError.h}).
     */
    static final int SUCCESS = 0;

    /**
     * {@code kCGImageAlphaNoneSkipFirst | kCGBitmapByteOrder32Little} ({@code CGImage.h}): 32-bit
     * little-endian XRGB, which is B, G, R, X in memory.
     */
    static final int BITMAP_BGRX = 6 | (2 << 12);

    /**
     * The {@code kCGColorSpaceSRGB} color space name.
     */
    static final MemorySegment SRGB = Frameworks.requiredConstant(LIBRARY, "kCGColorSpaceSRGB");

    /**
     * The {@code kCGDisplayStreamYCbCrMatrix_ITU_R_709_2} matrix name accepted by
     * {@code SCStreamConfiguration.colorMatrix}.
     */
    static final MemorySegment YCBCR_MATRIX_709 = Frameworks.requiredConstant(LIBRARY, "kCGDisplayStreamYCbCrMatrix_ITU_R_709_2");

    /**
     * {@code CGDirectDisplayID CGMainDisplayID(void)}.
     */
    private static final MethodHandle MAIN_DISPLAY = Native.function(LIBRARY, "CGMainDisplayID",
            FunctionDescriptor.of(JAVA_INT));

    /**
     * {@code CGError CGGetActiveDisplayList(uint32_t maxDisplays, CGDirectDisplayID *activeDisplays,
     * uint32_t *displayCount)}.
     */
    private static final MethodHandle ACTIVE_DISPLAYS = Native.function(LIBRARY, "CGGetActiveDisplayList",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code CGRect CGDisplayBounds(CGDirectDisplayID display)}.
     */
    private static final MethodHandle BOUNDS = Native.function(LIBRARY, "CGDisplayBounds",
            FunctionDescriptor.of(NativeLayouts.CG_RECT, JAVA_INT));

    /**
     * {@code size_t CGDisplayPixelsWide(CGDirectDisplayID display)}.
     */
    private static final MethodHandle PIXELS_WIDE = Native.function(LIBRARY, "CGDisplayPixelsWide",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT));

    /**
     * {@code size_t CGDisplayPixelsHigh(CGDirectDisplayID display)}.
     */
    private static final MethodHandle PIXELS_HIGH = Native.function(LIBRARY, "CGDisplayPixelsHigh",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT));

    /**
     * {@code CGDisplayModeRef CGDisplayCopyDisplayMode(CGDirectDisplayID display)}.
     */
    private static final MethodHandle COPY_MODE = Native.function(LIBRARY, "CGDisplayCopyDisplayMode",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));

    /**
     * {@code size_t CGDisplayModeGetPixelWidth(CGDisplayModeRef mode)}.
     */
    private static final MethodHandle MODE_PIXEL_WIDTH = Native.function(LIBRARY, "CGDisplayModeGetPixelWidth",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code size_t CGDisplayModeGetPixelHeight(CGDisplayModeRef mode)}.
     */
    private static final MethodHandle MODE_PIXEL_HEIGHT = Native.function(LIBRARY, "CGDisplayModeGetPixelHeight",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    /**
     * {@code void CGDisplayModeRelease(CGDisplayModeRef mode)}.
     */
    private static final MethodHandle MODE_RELEASE = Native.function(LIBRARY, "CGDisplayModeRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code boolean_t CGDisplayIsBuiltin(CGDirectDisplayID display)}, a 32-bit integer.
     */
    private static final MethodHandle IS_BUILTIN = Native.function(LIBRARY, "CGDisplayIsBuiltin",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /**
     * {@code CGDirectDisplayID CGDisplayMirrorsDisplay(CGDirectDisplayID display)}.
     */
    private static final MethodHandle MIRRORS = Native.function(LIBRARY, "CGDisplayMirrorsDisplay",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /**
     * {@code bool CGPreflightScreenCaptureAccess(void)} (macOS 10.15).
     */
    private static final MethodHandle PREFLIGHT = Native.function(LIBRARY, "CGPreflightScreenCaptureAccess",
            FunctionDescriptor.of(JAVA_BOOLEAN));

    /**
     * {@code bool CGRequestScreenCaptureAccess(void)} (macOS 10.15).
     */
    private static final MethodHandle REQUEST = Native.function(LIBRARY, "CGRequestScreenCaptureAccess",
            FunctionDescriptor.of(JAVA_BOOLEAN));

    /**
     * {@code CGImageRef CGDisplayCreateImage(CGDirectDisplayID displayID)}, obsoleted in macOS 15.
     */
    private static final Optional<MethodHandle> CREATE_IMAGE = Native.optionalFunction(LIBRARY, "CGDisplayCreateImage",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));

    /**
     * {@code void CGImageRelease(CGImageRef image)}.
     */
    private static final MethodHandle IMAGE_RELEASE = Native.function(LIBRARY, "CGImageRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code CGColorSpaceRef CGColorSpaceCreateWithName(CFStringRef name)}.
     */
    private static final MethodHandle COLOR_SPACE = Native.function(LIBRARY, "CGColorSpaceCreateWithName",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /**
     * {@code void CGColorSpaceRelease(CGColorSpaceRef space)}.
     */
    private static final MethodHandle COLOR_SPACE_RELEASE = Native.function(LIBRARY, "CGColorSpaceRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * {@code CGContextRef CGBitmapContextCreate(void *data, size_t width, size_t height,
     * size_t bitsPerComponent, size_t bytesPerRow, CGColorSpaceRef space, uint32_t bitmapInfo)}.
     */
    private static final MethodHandle BITMAP_CONTEXT = Native.function(LIBRARY, "CGBitmapContextCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT));

    /**
     * {@code void CGContextDrawImage(CGContextRef c, CGRect rect, CGImageRef image)}.
     */
    private static final MethodHandle DRAW_IMAGE = Native.function(LIBRARY, "CGContextDrawImage",
            FunctionDescriptor.ofVoid(ADDRESS, NativeLayouts.CG_RECT, ADDRESS));

    /**
     * {@code void CGContextRelease(CGContextRef c)}.
     */
    private static final MethodHandle CONTEXT_RELEASE = Native.function(LIBRARY, "CGContextRelease",
            FunctionDescriptor.ofVoid(ADDRESS));

    /**
     * Prevents instantiation.
     */
    private CoreGraphics() {
        throw new AssertionError();
    }

    /**
     * Returns the main display, the one with the menu bar.
     *
     * @return its identifier
     */
    static int mainDisplay() {
        try {
            return (int) MAIN_DISPLAY.invokeExact();
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Lists the active displays.
     *
     * @return their identifiers, the main display first
     * @throws IllegalStateException if CoreGraphics reports an error
     */
    static int[] activeDisplays() {
        try (var arena = Arena.ofConfined()) {
            var count = arena.allocate(JAVA_INT);
            var error = (int) ACTIVE_DISPLAYS.invokeExact(0, MemorySegment.NULL, count);
            if (error != SUCCESS) {
                throw new IllegalStateException("CGGetActiveDisplayList failed with CGError " + error);
            }
            var capacity = count.get(JAVA_INT, 0);
            if (capacity <= 0) {
                return new int[0];
            }
            capacity = Math.min(capacity, 256);
            var displays = arena.allocate(JAVA_INT, capacity);
            error = (int) ACTIVE_DISPLAYS.invokeExact(capacity, displays, count);
            if (error != SUCCESS) {
                throw new IllegalStateException("CGGetActiveDisplayList failed with CGError " + error);
            }
            var listed = Math.clamp(count.get(JAVA_INT, 0), 0, capacity);
            return displays.asSlice(0, (long) listed * JAVA_INT.byteSize()).toArray(JAVA_INT);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the bounds of a display in the global display space, measured in points.
     *
     * @return {@code x, y, width, height}
     */
    static double[] bounds(int display) {
        try (var arena = Arena.ofConfined()) {
            var rect = (MemorySegment) BOUNDS.invokeExact((SegmentAllocator) arena, display);
            return rect.toArray(JAVA_DOUBLE);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns the size of a display's current mode in pixels.
     *
     * <p>On a Retina display this is the backing resolution, twice the size in points; when the
     * mode cannot be read the size in points is returned.
     *
     * @return {@code width, height}
     */
    static long[] pixelSize(int display) {
        try {
            var mode = (MemorySegment) COPY_MODE.invokeExact(display);
            if (!ObjC.isNull(mode)) {
                try {
                    var width = (long) MODE_PIXEL_WIDTH.invokeExact(mode);
                    var height = (long) MODE_PIXEL_HEIGHT.invokeExact(mode);
                    if (width > 0 && height > 0) {
                        return new long[]{width, height};
                    }
                } finally {
                    MODE_RELEASE.invokeExact(mode);
                }
            }
            return new long[]{(long) PIXELS_WIDE.invokeExact(display), (long) PIXELS_HIGH.invokeExact(display)};
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns whether a display is built into the computer.
     *
     * @return whether it is a built-in panel
     */
    static boolean isBuiltin(int display) {
        try {
            return (int) IS_BUILTIN.invokeExact(display) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns whether a display mirrors another one in software.
     *
     * @return whether it shows the content of another display
     */
    static boolean isMirror(int display) {
        try {
            return (int) MIRRORS.invokeExact(display) != 0;
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns whether this process may record the screen.
     *
     * @return whether the Screen Recording permission is granted
     */
    static boolean screenCaptureAllowed() {
        try {
            return (boolean) PREFLIGHT.invokeExact();
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Asks the user for the Screen Recording permission, showing the system prompt the first time.
     *
     * <p>The answer takes effect only after the process restarts.
     */
    static void requestScreenCapture() {
        try {
            var ignored = (boolean) REQUEST.invokeExact();
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Returns whether this system still offers display snapshots.
     *
     * @return whether {@code CGDisplayCreateImage} is exported
     */
    static boolean canSnapshot() {
        return CREATE_IMAGE.isPresent();
    }

    /**
     * Takes a snapshot of a display.
     *
     * @return the {@code CGImageRef}, owned by the caller, or {@code NULL} on failure (including a
     * missing permission)
     * @throws IllegalStateException if this system offers no snapshots
     */
    static MemorySegment snapshot(int display) {
        var create = CREATE_IMAGE.orElseThrow(() -> new IllegalStateException("CGDisplayCreateImage is not available"));
        try {
            return (MemorySegment) create.invokeExact(display);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Releases an image; {@code NULL} is ignored.
     */
    static void releaseImage(MemorySegment image) {
        try {
            IMAGE_RELEASE.invokeExact(image);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Creates the sRGB color space.
     *
     * @return the color space, owned by the caller, or {@code NULL} on failure
     */
    static MemorySegment createSrgb() {
        try {
            return (MemorySegment) COLOR_SPACE.invokeExact(SRGB);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Releases a color space; {@code NULL} is ignored.
     *
     * @param space the color space
     */
    static void releaseColorSpace(MemorySegment space) {
        try {
            COLOR_SPACE_RELEASE.invokeExact(space);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Creates a bitmap context drawing into caller-owned memory as {@link #BITMAP_BGRX}.
     *
     * @param data the pixel memory, at least {@code bytesPerRow * height} bytes, which
     * must outlive the context
     * @param width the width in pixels
     * @param height the height in pixels
     * @param bytesPerRow the row stride in bytes
     * @param space the color space
     * @return the context, owned by the caller, or {@code NULL} on failure
     */
    static MemorySegment createBitmapContext(MemorySegment data, long width, long height, long bytesPerRow,
                                             MemorySegment space) {
        try {
            return (MemorySegment) BITMAP_CONTEXT.invokeExact(data, width, height, 8L, bytesPerRow, space, BITMAP_BGRX);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Draws an image scaled into a rectangle of a context.
     *
     * @param width the rectangle width
     * @param height the rectangle height
     */
    static void drawImage(MemorySegment context, double width, double height, MemorySegment image) {
        try (var arena = Arena.ofConfined()) {
            var rect = arena.allocate(NativeLayouts.CG_RECT);
            rect.set(JAVA_DOUBLE, 0, 0.0);
            rect.set(JAVA_DOUBLE, 8, 0.0);
            rect.set(JAVA_DOUBLE, 16, width);
            rect.set(JAVA_DOUBLE, 24, height);
            DRAW_IMAGE.invokeExact(context, rect, image);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }

    /**
     * Releases a context; {@code NULL} is ignored.
     */
    static void releaseContext(MemorySegment context) {
        try {
            CONTEXT_RELEASE.invokeExact(context);
        } catch (Throwable failure) {
            throw Native.rethrow(failure);
        }
    }
}
