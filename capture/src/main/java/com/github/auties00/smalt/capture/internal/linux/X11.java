package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Calls Xlib ({@code libX11.so.6}) and, when installed, its MIT-SHM ({@code libXext.so.6}) and
 * RandR ({@code libXrandr.so.2}) extensions through the Foreign Function and Memory API.
 *
 * <p>The libraries are loaded once, on first use, with {@link SymbolLookup#libraryLookup}; a
 * system without {@code libX11.so.6} has no instance. Loading calls {@code XInitThreads}, as
 * libX11 1.8 and later do on their own, because the backend's connections are used from whichever
 * thread reads a session. A wrapper of an optional function the library lacks (MIT-SHM without
 * {@code libXext}, the per-display I/O error exit handler of {@code libX11} before 1.7) does
 * nothing; monitors without {@code libXrandr} 1.5 are reported by {@link #hasMonitors()}.
 *
 * <p>The structure layouts are those of a 64-bit (LP64) client: {@code XID}, {@code Atom},
 * {@code Window}, {@code unsigned long} and pointers are 8 bytes and 8-byte aligned, and they are
 * modeled as {@code long}. Pointers read from them are wrapped with
 * {@link MemorySegment#ofAddress(long)}.
 */
final class X11 {
    /**
     * The {@code ZPixmap} image format.
     */
    static final int Z_PIXMAP = 2;

    /**
     * The {@code AllPlanes} plane mask.
     */
    static final long ALL_PLANES = -1L;

    /**
     * The layout of {@code XImage}, with its function table {@code f}.
     */
    static final StructLayout IMAGE = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("xoffset"),
            JAVA_INT.withName("format"),
            JAVA_LONG.withName("data"),
            JAVA_INT.withName("byte_order"),
            JAVA_INT.withName("bitmap_unit"),
            JAVA_INT.withName("bitmap_bit_order"),
            JAVA_INT.withName("bitmap_pad"),
            JAVA_INT.withName("depth"),
            JAVA_INT.withName("bytes_per_line"),
            JAVA_INT.withName("bits_per_pixel"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("red_mask"),
            JAVA_LONG.withName("green_mask"),
            JAVA_LONG.withName("blue_mask"),
            JAVA_LONG.withName("obdata"),
            MemoryLayout.structLayout(
                    JAVA_LONG.withName("create_image"),
                    JAVA_LONG.withName("destroy_image"),
                    JAVA_LONG.withName("get_pixel"),
                    JAVA_LONG.withName("put_pixel"),
                    JAVA_LONG.withName("sub_image"),
                    JAVA_LONG.withName("add_pixel")).withName("f")).withName("XImage");

    /**
     * The offset of {@code XImage.data}.
     */
    static final long IMAGE_DATA = IMAGE.byteOffset(groupElement("data"));

    /**
     * The offset of {@code XImage.byte_order}.
     */
    static final long IMAGE_BYTE_ORDER = IMAGE.byteOffset(groupElement("byte_order"));

    /**
     * The offset of {@code XImage.bytes_per_line}.
     */
    static final long IMAGE_BYTES_PER_LINE = IMAGE.byteOffset(groupElement("bytes_per_line"));

    /**
     * The offset of {@code XImage.bits_per_pixel}.
     */
    static final long IMAGE_BITS_PER_PIXEL = IMAGE.byteOffset(groupElement("bits_per_pixel"));

    /**
     * The offset of {@code XImage.red_mask}.
     */
    static final long IMAGE_RED_MASK = IMAGE.byteOffset(groupElement("red_mask"));

    /**
     * The offset of {@code XImage.green_mask}.
     */
    static final long IMAGE_GREEN_MASK = IMAGE.byteOffset(groupElement("green_mask"));

    /**
     * The offset of {@code XImage.blue_mask}.
     */
    static final long IMAGE_BLUE_MASK = IMAGE.byteOffset(groupElement("blue_mask"));

    /**
     * The offset of {@code XImage.f.destroy_image}.
     */
    static final long IMAGE_DESTROY = IMAGE.byteOffset(groupElement("f"), groupElement("destroy_image"));

    /**
     * The layout of {@code XShmSegmentInfo}.
     */
    static final StructLayout SHM_SEGMENT_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("shmseg"),
            JAVA_INT.withName("shmid"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("shmaddr"),
            JAVA_INT.withName("readOnly"),
            MemoryLayout.paddingLayout(4)).withName("XShmSegmentInfo");

    /**
     * The offset of {@code XShmSegmentInfo.shmid}.
     */
    static final long SHM_ID = SHM_SEGMENT_INFO.byteOffset(groupElement("shmid"));

    /**
     * The offset of {@code XShmSegmentInfo.shmaddr}.
     */
    static final long SHM_ADDRESS = SHM_SEGMENT_INFO.byteOffset(groupElement("shmaddr"));

    /**
     * The offset of {@code XShmSegmentInfo.readOnly}.
     */
    static final long SHM_READ_ONLY = SHM_SEGMENT_INFO.byteOffset(groupElement("readOnly"));

    /**
     * The layout of {@code XRRMonitorInfo}.
     */
    static final StructLayout MONITOR_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("name"),
            JAVA_INT.withName("primary"),
            JAVA_INT.withName("automatic"),
            JAVA_INT.withName("noutput"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("mwidth"),
            JAVA_INT.withName("mheight"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("outputs")).withName("XRRMonitorInfo");

    /**
     * The offset of {@code XRRMonitorInfo.name}.
     */
    static final long MONITOR_NAME = MONITOR_INFO.byteOffset(groupElement("name"));

    /**
     * The offset of {@code XRRMonitorInfo.primary}.
     */
    static final long MONITOR_PRIMARY = MONITOR_INFO.byteOffset(groupElement("primary"));

    /**
     * The offset of {@code XRRMonitorInfo.x}.
     */
    static final long MONITOR_X = MONITOR_INFO.byteOffset(groupElement("x"));

    /**
     * The offset of {@code XRRMonitorInfo.y}.
     */
    static final long MONITOR_Y = MONITOR_INFO.byteOffset(groupElement("y"));

    /**
     * The offset of {@code XRRMonitorInfo.width}.
     */
    static final long MONITOR_WIDTH = MONITOR_INFO.byteOffset(groupElement("width"));

    /**
     * The offset of {@code XRRMonitorInfo.height}.
     */
    static final long MONITOR_HEIGHT = MONITOR_INFO.byteOffset(groupElement("height"));

    /**
     * The layout of {@code XErrorEvent}.
     */
    static final StructLayout ERROR_EVENT = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("display"),
            JAVA_LONG.withName("resourceid"),
            JAVA_LONG.withName("serial"),
            JAVA_BYTE.withName("error_code"),
            JAVA_BYTE.withName("request_code"),
            JAVA_BYTE.withName("minor_code"),
            MemoryLayout.paddingLayout(5)).withName("XErrorEvent");

    /**
     * The offset of {@code XErrorEvent.error_code}.
     */
    static final long ERROR_CODE = ERROR_EVENT.byteOffset(groupElement("error_code"));

    /**
     * The signature of an {@code XErrorHandler}: {@code int (*)(Display *, XErrorEvent *)}.
     */
    static final FunctionDescriptor ERROR_HANDLER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

    /**
     * The signature of an {@code XIOErrorExitHandler}: {@code void (*)(Display *, void *)}.
     */
    static final FunctionDescriptor IO_ERROR_EXIT_HANDLER = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

    /**
     * {@code Display *XOpenDisplay(char *name)}.
     */
    private final MethodHandle openDisplay;

    /**
     * {@code int XCloseDisplay(Display *display)}.
     */
    private final MethodHandle closeDisplay;

    /**
     * {@code int XDefaultScreen(Display *display)}.
     */
    private final MethodHandle defaultScreen;

    /**
     * {@code Window XRootWindow(Display *display, int screen)}.
     */
    private final MethodHandle rootWindow;

    /**
     * {@code Visual *XDefaultVisual(Display *display, int screen)}.
     */
    private final MethodHandle defaultVisual;

    /**
     * {@code int XDefaultDepth(Display *display, int screen)}.
     */
    private final MethodHandle defaultDepth;

    /**
     * {@code int XDisplayWidth(Display *display, int screen)}.
     */
    private final MethodHandle displayWidth;

    /**
     * {@code int XDisplayHeight(Display *display, int screen)}.
     */
    private final MethodHandle displayHeight;

    /**
     * {@code char *XDisplayString(Display *display)}.
     */
    private final MethodHandle displayString;

    /**
     * {@code Status XGetGeometry(Display *, Drawable, Window *, int *, int *, unsigned *, unsigned *,
     * unsigned *, unsigned *)}.
     */
    private final MethodHandle getGeometry;

    /**
     * {@code XImage *XGetImage(Display *, Drawable, int x, int y, unsigned width, unsigned height,
     * unsigned long planes, int format)}.
     */
    private final MethodHandle getImage;

    /**
     * {@code int XSync(Display *display, Bool discard)}.
     */
    private final MethodHandle sync;

    /**
     * {@code int XFree(void *data)}.
     */
    private final MethodHandle free;

    /**
     * {@code char *XGetAtomName(Display *display, Atom atom)}.
     */
    private final MethodHandle getAtomName;

    /**
     * {@code XErrorHandler XSetErrorHandler(XErrorHandler handler)}.
     */
    private final MethodHandle setErrorHandler;

    /**
     * {@code void XSetIOErrorExitHandler(Display *, XIOErrorExitHandler, void *)}, or {@code null}
     * before libX11 1.7.
     */
    private final MethodHandle setIoErrorExitHandler;

    /**
     * {@code Bool XShmQueryExtension(Display *display)}, or {@code null} without libXext.
     */
    private final MethodHandle shmQueryExtension;

    /**
     * {@code XImage *XShmCreateImage(Display *, Visual *, unsigned depth, int format, char *data,
     * XShmSegmentInfo *, unsigned width, unsigned height)}, or {@code null} without libXext.
     */
    private final MethodHandle shmCreateImage;

    /**
     * {@code Bool XShmAttach(Display *, XShmSegmentInfo *)}, or {@code null} without libXext.
     */
    private final MethodHandle shmAttach;

    /**
     * {@code Bool XShmDetach(Display *, XShmSegmentInfo *)}, or {@code null} without libXext.
     */
    private final MethodHandle shmDetach;

    /**
     * {@code Bool XShmGetImage(Display *, Drawable, XImage *, int x, int y, unsigned long planes)},
     * or {@code null} without libXext.
     */
    private final MethodHandle shmGetImage;

    /**
     * {@code Bool XRRQueryExtension(Display *, int *event_base, int *error_base)}, or {@code null}
     * without libXrandr 1.5.
     */
    private final MethodHandle randrQueryExtension;

    /**
     * {@code Status XRRQueryVersion(Display *, int *major, int *minor)}, or {@code null} without
     * libXrandr 1.5.
     */
    private final MethodHandle randrQueryVersion;

    /**
     * {@code XRRMonitorInfo *XRRGetMonitors(Display *, Window, Bool active, int *count)}, or
     * {@code null} without libXrandr 1.5.
     */
    private final MethodHandle randrGetMonitors;

    /**
     * {@code void XRRFreeMonitors(XRRMonitorInfo *monitors)}, or {@code null} without libXrandr 1.5.
     */
    private final MethodHandle randrFreeMonitors;

    /**
     * Calls an {@code XImage}'s {@code destroy_image} function pointer.
     */
    private final MethodHandle destroyImage;

    /**
     * Links the functions of the libraries.
     *
     * @param x11 the lookup of {@code libX11.so.6}
     * @param xext the lookup of {@code libXext.so.6}, or {@code null}
     * @param xrandr the lookup of {@code libXrandr.so.2}, or {@code null}
     * @throws UnsatisfiedLinkError if {@code libX11} lacks a core function
     */
    private X11(SymbolLookup x11, SymbolLookup xext, SymbolLookup xrandr) {
        this.openDisplay = Native.function(x11, "XOpenDisplay", FunctionDescriptor.of(ADDRESS, ADDRESS));
        this.closeDisplay = Native.function(x11, "XCloseDisplay", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.defaultScreen = Native.function(x11, "XDefaultScreen", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.rootWindow = Native.function(x11, "XRootWindow", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
        this.defaultVisual = Native.function(x11, "XDefaultVisual", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        this.defaultDepth = Native.function(x11, "XDefaultDepth", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.displayWidth = Native.function(x11, "XDisplayWidth", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.displayHeight = Native.function(x11, "XDisplayHeight", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.displayString = Native.function(x11, "XDisplayString", FunctionDescriptor.of(ADDRESS, ADDRESS));
        this.getGeometry = Native.function(x11, "XGetGeometry", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.getImage = Native.function(x11, "XGetImage", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG,
                JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_INT));
        this.sync = Native.function(x11, "XSync", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.free = Native.function(x11, "XFree", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.getAtomName = Native.function(x11, "XGetAtomName", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
        this.setErrorHandler = Native.function(x11, "XSetErrorHandler", FunctionDescriptor.of(ADDRESS, ADDRESS));
        this.setIoErrorExitHandler = optional(x11, "XSetIOErrorExitHandler", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        var initThreads = Native.function(x11, "XInitThreads", FunctionDescriptor.of(JAVA_INT));
        var shmQuery = optional(xext, "XShmQueryExtension", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        var shmCreate = optional(xext, "XShmCreateImage", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS,
                JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT));
        var shmAttachHandle = optional(xext, "XShmAttach", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        var shmDetachHandle = optional(xext, "XShmDetach", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        var shmGet = optional(xext, "XShmGetImage", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS,
                JAVA_INT, JAVA_INT, JAVA_LONG));
        var shm = shmQuery != null && shmCreate != null && shmAttachHandle != null && shmDetachHandle != null && shmGet != null;
        this.shmQueryExtension = shm ? shmQuery : null;
        this.shmCreateImage = shm ? shmCreate : null;
        this.shmAttach = shm ? shmAttachHandle : null;
        this.shmDetach = shm ? shmDetachHandle : null;
        this.shmGetImage = shm ? shmGet : null;
        var randrQuery = optional(xrandr, "XRRQueryExtension", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var randrVersion = optional(xrandr, "XRRQueryVersion", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var randrMonitors = optional(xrandr, "XRRGetMonitors", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        var randrFree = optional(xrandr, "XRRFreeMonitors", FunctionDescriptor.ofVoid(ADDRESS));
        var randr = randrQuery != null && randrVersion != null && randrMonitors != null && randrFree != null;
        this.randrQueryExtension = randr ? randrQuery : null;
        this.randrQueryVersion = randr ? randrVersion : null;
        this.randrGetMonitors = randr ? randrMonitors : null;
        this.randrFreeMonitors = randr ? randrFree : null;
        this.destroyImage = Native.LINKER.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS));
        try {
            var ignored = (int) initThreads.invokeExact();
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the bindings, loading the libraries on first use.
     *
     * @return the bindings, or {@code null} when {@code libX11.so.6} is not installed
     */
    static X11 instance() {
        return Holder.INSTANCE;
    }

    /**
     * Returns whether RandR monitors are available.
     *
     * @return whether {@code libXrandr} provides {@code XRRGetMonitors}
     */
    boolean hasMonitors() {
        return randrGetMonitors != null;
    }

    /**
     * Connects to the display named by {@code DISPLAY}.
     *
     * @return the display, or {@link MemorySegment#NULL} when the connection fails
     */
    MemorySegment openDisplay() {
        return openDisplay(null);
    }

    /**
     * Connects to a named display.
     *
     * @param name the display name, such as {@code :1}, or {@code null} for the one {@code DISPLAY} names
     * @return the display, or {@link MemorySegment#NULL} when the connection fails
     */
    MemorySegment openDisplay(String name) {
        try (var arena = Arena.ofConfined()) {
            var argument = name == null ? MemorySegment.NULL : arena.allocateFrom(name);
            return (MemorySegment) openDisplay.invokeExact(argument);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Closes a display connection.
     */
    void closeDisplay(MemorySegment display) {
        try {
            var ignored = (int) closeDisplay.invokeExact(display);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the default screen number.
     *
     * @return the screen number
     */
    int defaultScreen(MemorySegment display) {
        try {
            return (int) defaultScreen.invokeExact(display);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the root window of a screen.
     *
     * @param screen the screen number
     * @return the window
     */
    long rootWindow(MemorySegment display, int screen) {
        try {
            return (long) rootWindow.invokeExact(display, screen);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the default visual of a screen.
     *
     * @param screen the screen number
     * @return the visual
     */
    MemorySegment defaultVisual(MemorySegment display, int screen) {
        try {
            return (MemorySegment) defaultVisual.invokeExact(display, screen);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the default depth of a screen.
     *
     * @param screen the screen number
     * @return the depth in bits
     */
    int defaultDepth(MemorySegment display, int screen) {
        try {
            return (int) defaultDepth.invokeExact(display, screen);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the width of a screen as of the connection.
     *
     * @param screen the screen number
     * @return the width in pixels
     */
    int displayWidth(MemorySegment display, int screen) {
        try {
            return (int) displayWidth.invokeExact(display, screen);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the height of a screen as of the connection.
     *
     * @param screen the screen number
     * @return the height in pixels
     */
    int displayHeight(MemorySegment display, int screen) {
        try {
            return (int) displayHeight.invokeExact(display, screen);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the name of a display connection.
     *
     * @return the name, for example {@code ":0"}
     */
    String displayString(MemorySegment display) {
        try {
            var name = (MemorySegment) displayString.invokeExact(display);
            return name.equals(MemorySegment.NULL) ? "" : name.reinterpret(Integer.MAX_VALUE).getString(0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the current size of a window with a round trip to the server.
     *
     * @return the width and height, or {@code null} when the request fails
     */
    int[] windowSize(MemorySegment display, long window) {
        try (var arena = Arena.ofConfined()) {
            var values = arena.allocate(JAVA_INT, 8);
            var root = arena.allocate(JAVA_LONG);
            var status = (int) getGeometry.invokeExact(display, window, root, values, values.asSlice(4), values.asSlice(8),
                    values.asSlice(12), values.asSlice(16), values.asSlice(20));
            if (status == 0) {
                return null;
            }
            return new int[]{values.get(JAVA_INT, 8), values.get(JAVA_INT, 12)};
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Copies a rectangle of a drawable into a new image.
     *
     * @param window the drawable
     * @param x the left edge
     * @param y the top edge
     * @return the {@code ZPixmap} image, to destroy with {@link #destroyImage}, or
     * {@link MemorySegment#NULL} on failure
     */
    MemorySegment getImage(MemorySegment display, long window, int x, int y, int width, int height) {
        try {
            return (MemorySegment) getImage.invokeExact(display, window, x, y, width, height, ALL_PLANES, Z_PIXMAP);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Flushes the request buffer and waits until the server processed every request, so their
     * errors have been reported.
     */
    void sync(MemorySegment display) {
        try {
            var ignored = (int) sync.invokeExact(display, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees memory Xlib allocated.
     *
     * @param data the memory; ignored when {@link MemorySegment#NULL}
     */
    void free(MemorySegment data) {
        if (data.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) free.invokeExact(data);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the name of an atom.
     *
     * @return the name, or {@code null} when the atom is not defined
     */
    String atomName(MemorySegment display, long atom) {
        try {
            var name = (MemorySegment) getAtomName.invokeExact(display, atom);
            if (name.equals(MemorySegment.NULL)) {
                return null;
            }
            try {
                return name.reinterpret(Integer.MAX_VALUE).getString(0);
            } finally {
                free(name);
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Installs the process-wide error handler.
     *
     * @param handler the handler's function pointer
     * @return the previous handler's function pointer
     */
    MemorySegment setErrorHandler(MemorySegment handler) {
        try {
            return (MemorySegment) setErrorHandler.invokeExact(handler);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Installs a display's I/O error exit handler; does nothing before libX11 1.7.
     *
     * @param handler the handler's function pointer
     */
    void setIoErrorExitHandler(MemorySegment display, MemorySegment handler) {
        if (setIoErrorExitHandler == null) {
            return;
        }
        try {
            setIoErrorExitHandler.invokeExact(display, handler, MemorySegment.NULL);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether the server supports MIT-SHM.
     *
     * @return whether shared memory images can be used
     */
    boolean shmQueryExtension(MemorySegment display) {
        if (shmQueryExtension == null) {
            return false;
        }
        try {
            return (int) shmQueryExtension.invokeExact(display) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Creates a shared memory image without data.
     *
     * @param info the {@code XShmSegmentInfo} to associate
     * @return the image, or {@link MemorySegment#NULL} on failure
     */
    MemorySegment shmCreateImage(MemorySegment display, MemorySegment visual, int depth, MemorySegment info, int width, int height) {
        try {
            return (MemorySegment) shmCreateImage.invokeExact(display, visual, depth, Z_PIXMAP, MemorySegment.NULL, info, width, height);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Asks the server to attach a shared memory segment.
     *
     * @param info the segment
     * @return whether the request was sent; failures are reported asynchronously
     */
    boolean shmAttach(MemorySegment display, MemorySegment info) {
        try {
            return (int) shmAttach.invokeExact(display, info) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Asks the server to detach a shared memory segment.
     *
     * @param info the segment
     */
    void shmDetach(MemorySegment display, MemorySegment info) {
        try {
            var ignored = (int) shmDetach.invokeExact(display, info);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Copies a rectangle of a drawable into a shared memory image.
     *
     * @param window the drawable
     * @param image the shared memory image, whose size is the rectangle's
     * @param x the left edge
     * @param y the top edge
     * @return whether the copy succeeded
     */
    boolean shmGetImage(MemorySegment display, long window, MemorySegment image, int x, int y) {
        try {
            return (int) shmGetImage.invokeExact(display, window, image, x, y, ALL_PLANES) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the RandR version the server supports.
     *
     * @return {@code major * 1000 + minor}, or {@code 0} without RandR
     */
    int randrVersion(MemorySegment display) {
        if (randrQueryExtension == null) {
            return 0;
        }
        try (var arena = Arena.ofConfined()) {
            var first = arena.allocate(JAVA_INT);
            var second = arena.allocate(JAVA_INT);
            if ((int) randrQueryExtension.invokeExact(display, first, second) == 0) {
                return 0;
            }
            first.set(JAVA_INT, 0, 1);
            second.set(JAVA_INT, 0, 5);
            if ((int) randrQueryVersion.invokeExact(display, first, second) == 0) {
                return 0;
            }
            return first.get(JAVA_INT, 0) * 1000 + second.get(JAVA_INT, 0);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Lists the active monitors of a screen.
     *
     * @param root the root window
     * @param count receives the number of monitors
     * @return the {@code XRRMonitorInfo} array, to free with {@link #freeMonitors}, or
     * {@link MemorySegment#NULL}
     */
    MemorySegment getMonitors(MemorySegment display, long root, MemorySegment count) {
        try {
            return (MemorySegment) randrGetMonitors.invokeExact(display, root, 1, count);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees a monitor array.
     *
     * @param monitors the array; ignored when {@link MemorySegment#NULL}
     */
    void freeMonitors(MemorySegment monitors) {
        if (monitors.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            randrFreeMonitors.invokeExact(monitors);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Destroys an image through its {@code destroy_image} function, as the {@code XDestroyImage}
     * macro does; a shared memory image's destroy function frees only the structure.
     */
    void destroyImage(MemorySegment image) {
        var function = MemorySegment.ofAddress(image.reinterpret(IMAGE.byteSize()).get(JAVA_LONG, IMAGE_DESTROY));
        if (function.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            var ignored = (int) destroyImage.invokeExact(function, image);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Links a function a library may lack.
     *
     * @param lookup the library, or {@code null} when it is not installed
     * @param name the symbol
     * @param descriptor the signature
     * @return the handle, or {@code null}
     */
    private static MethodHandle optional(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup == null ? null : Native.optionalFunction(lookup, name, descriptor).orElse(null);
    }

    /**
     * Holds the bindings, created on first use.
     */
    private static final class Holder {
        /**
         * The bindings, or {@code null} without {@code libX11.so.6}.
         */
        static final X11 INSTANCE = load();

        /**
         * Prevents instantiation.
         */
        private Holder() {
            throw new AssertionError();
        }

        /**
         * Loads the libraries and links the functions.
         *
         * @return the bindings, or {@code null} without {@code libX11.so.6}
         */
        private static X11 load() {
            var x11 = Native.library("libX11.so.6").orElse(null);
            if (x11 == null) {
                return null;
            }
            return new X11(x11, Native.library("libXext.so.6").orElse(null), Native.library("libXrandr.so.2").orElse(null));
        }
    }
}
