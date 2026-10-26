package com.github.auties00.smalt.capture.internal.linux;

import com.github.auties00.smalt.capture.internal.Native;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Calls the window functions of Xlib ({@code libX11.so.6}) and, when installed, the Composite
 * ({@code libXcomposite.so.1}) and XFixes ({@code libXfixes.so.3}) extensions, which window capture and
 * the pointer overlay use.
 *
 * <p>The layouts are those of a 64-bit (LP64) client, as in {@link X11}. A system without the Composite
 * or XFixes library reports the extension as missing.
 */
final class X11Windowing {
    /**
     * The {@code IsViewable} map state.
     */
    static final int IS_VIEWABLE = 2;

    /**
     * The {@code InputOutput} window class.
     */
    static final int INPUT_OUTPUT = 1;

    /**
     * The predefined {@code XA_CARDINAL} atom.
     */
    static final long XA_CARDINAL = 6;

    /**
     * The predefined {@code XA_STRING} atom.
     */
    static final long XA_STRING = 31;

    /**
     * The predefined {@code XA_WINDOW} atom.
     */
    static final long XA_WINDOW = 33;

    /**
     * The predefined {@code XA_WM_CLASS} atom.
     */
    static final long XA_WM_CLASS = 67;

    /**
     * The {@code CompositeRedirectAutomatic} update mode.
     */
    static final int REDIRECT_AUTOMATIC = 0;

    /**
     * The bytes of the scratch memory of {@link #origin(MemorySegment, long, long, MemorySegment)}.
     */
    static final long TRANSLATION_SIZE = 16;

    /**
     * The layout of {@code XWindowAttributes}.
     */
    static final StructLayout WINDOW_ATTRIBUTES = MemoryLayout.structLayout(
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("border_width"),
            JAVA_INT.withName("depth"),
            JAVA_LONG.withName("visual"),
            JAVA_LONG.withName("root"),
            JAVA_INT.withName("class"),
            JAVA_INT.withName("bit_gravity"),
            JAVA_INT.withName("win_gravity"),
            JAVA_INT.withName("backing_store"),
            JAVA_LONG.withName("backing_planes"),
            JAVA_LONG.withName("backing_pixel"),
            JAVA_INT.withName("save_under"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("colormap"),
            JAVA_INT.withName("map_installed"),
            JAVA_INT.withName("map_state"),
            JAVA_LONG.withName("all_event_masks"),
            JAVA_LONG.withName("your_event_mask"),
            JAVA_LONG.withName("do_not_propagate_mask"),
            JAVA_INT.withName("override_redirect"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("screen")).withName("XWindowAttributes");

    /**
     * The offset of {@code XWindowAttributes.width}.
     */
    static final long ATTRIBUTES_WIDTH = WINDOW_ATTRIBUTES.byteOffset(groupElement("width"));

    /**
     * The offset of {@code XWindowAttributes.height}.
     */
    static final long ATTRIBUTES_HEIGHT = WINDOW_ATTRIBUTES.byteOffset(groupElement("height"));

    /**
     * The offset of {@code XWindowAttributes.border_width}.
     */
    static final long ATTRIBUTES_BORDER = WINDOW_ATTRIBUTES.byteOffset(groupElement("border_width"));

    /**
     * The offset of {@code XWindowAttributes.visual}.
     */
    static final long ATTRIBUTES_VISUAL = WINDOW_ATTRIBUTES.byteOffset(groupElement("visual"));

    /**
     * The offset of {@code XWindowAttributes.class}.
     */
    static final long ATTRIBUTES_CLASS = WINDOW_ATTRIBUTES.byteOffset(groupElement("class"));

    /**
     * The offset of {@code XWindowAttributes.map_state}.
     */
    static final long ATTRIBUTES_MAP_STATE = WINDOW_ATTRIBUTES.byteOffset(groupElement("map_state"));

    /**
     * The offset of {@code XWindowAttributes.override_redirect}.
     */
    static final long ATTRIBUTES_OVERRIDE_REDIRECT = WINDOW_ATTRIBUTES.byteOffset(groupElement("override_redirect"));

    /**
     * The layout of the start of {@code Visual}, up to its color masks.
     */
    static final StructLayout VISUAL = MemoryLayout.structLayout(
            JAVA_LONG.withName("ext_data"),
            JAVA_LONG.withName("visualid"),
            JAVA_INT.withName("class"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("red_mask"),
            JAVA_LONG.withName("green_mask"),
            JAVA_LONG.withName("blue_mask")).withName("Visual");

    /**
     * The offset of {@code Visual.red_mask}.
     */
    static final long VISUAL_RED_MASK = VISUAL.byteOffset(groupElement("red_mask"));

    /**
     * The offset of {@code Visual.green_mask}.
     */
    static final long VISUAL_GREEN_MASK = VISUAL.byteOffset(groupElement("green_mask"));

    /**
     * The offset of {@code Visual.blue_mask}.
     */
    static final long VISUAL_BLUE_MASK = VISUAL.byteOffset(groupElement("blue_mask"));

    /**
     * The layout of {@code XFixesCursorImage}.
     */
    static final StructLayout CURSOR_IMAGE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("x"),
            JAVA_SHORT.withName("y"),
            JAVA_SHORT.withName("width"),
            JAVA_SHORT.withName("height"),
            JAVA_SHORT.withName("xhot"),
            JAVA_SHORT.withName("yhot"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("cursor_serial"),
            JAVA_LONG.withName("pixels"),
            JAVA_LONG.withName("atom"),
            JAVA_LONG.withName("name")).withName("XFixesCursorImage");

    private final MethodHandle queryTree;

    private final MethodHandle getWindowAttributes;

    private final MethodHandle translateCoordinates;

    private final MethodHandle internAtom;

    private final MethodHandle getWindowProperty;

    private final MethodHandle freePixmap;

    private final MethodHandle compositeQueryExtension;

    private final MethodHandle compositeQueryVersion;

    private final MethodHandle compositeRedirectWindow;

    private final MethodHandle compositeUnredirectWindow;

    private final MethodHandle compositeNameWindowPixmap;

    private final MethodHandle fixesQueryExtension;

    private final MethodHandle fixesGetCursorImage;

    private final X11 x11;

    private X11Windowing(X11 x11, SymbolLookup lib, SymbolLookup composite, SymbolLookup fixes) {
        this.x11 = x11;
        this.queryTree = Native.function(lib, "XQueryTree", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.getWindowAttributes = Native.function(lib, "XGetWindowAttributes", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, ADDRESS));
        this.translateCoordinates = Native.function(lib, "XTranslateCoordinates", FunctionDescriptor.of(JAVA_INT,
                ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        this.internAtom = Native.function(lib, "XInternAtom", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
        this.getWindowProperty = Native.function(lib, "XGetWindowProperty", FunctionDescriptor.of(JAVA_INT, ADDRESS,
                JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.freePixmap = Native.function(lib, "XFreePixmap", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        var cQuery = optional(composite, "XCompositeQueryExtension", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var cVersion = optional(composite, "XCompositeQueryVersion", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var cRedirect = optional(composite, "XCompositeRedirectWindow", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT));
        var cUnredirect = optional(composite, "XCompositeUnredirectWindow", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT));
        var cName = optional(composite, "XCompositeNameWindowPixmap", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));
        var hasComposite = cQuery != null && cVersion != null && cRedirect != null && cUnredirect != null && cName != null;
        this.compositeQueryExtension = hasComposite ? cQuery : null;
        this.compositeQueryVersion = hasComposite ? cVersion : null;
        this.compositeRedirectWindow = hasComposite ? cRedirect : null;
        this.compositeUnredirectWindow = hasComposite ? cUnredirect : null;
        this.compositeNameWindowPixmap = hasComposite ? cName : null;
        var fQuery = optional(fixes, "XFixesQueryExtension", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        var fCursor = optional(fixes, "XFixesGetCursorImage", FunctionDescriptor.of(ADDRESS, ADDRESS));
        var hasFixes = fQuery != null && fCursor != null;
        this.fixesQueryExtension = hasFixes ? fQuery : null;
        this.fixesGetCursorImage = hasFixes ? fCursor : null;
    }

    /**
     * Returns the bindings, loading the libraries on first use.
     *
     * @return the bindings, or {@code null} when {@code libX11.so.6} is not installed
     */
    static X11Windowing instance() {
        return Holder.INSTANCE;
    }

    /**
     * {@return the Xlib bindings these functions extend}
     */
    X11 x11() {
        return x11;
    }

    /**
     * Lists the children of a window, bottom to top.
     *
     * @return the children, or {@code null} when the request fails
     */
    long[] children(MemorySegment display, long window) {
        try (var arena = Arena.ofConfined()) {
            var root = arena.allocate(JAVA_LONG);
            var parent = arena.allocate(JAVA_LONG);
            var list = arena.allocate(ADDRESS);
            var count = arena.allocate(JAVA_INT);
            var status = (int) queryTree.invokeExact(display, window, root, parent, list, count);
            if (status == 0) {
                return null;
            }
            var pointer = list.get(ADDRESS, 0);
            var total = Math.max(0, count.get(JAVA_INT, 0));
            if (pointer.equals(MemorySegment.NULL)) {
                return new long[0];
            }
            try {
                return pointer.reinterpret(8L * total).toArray(JAVA_LONG);
            } finally {
                x11.free(pointer);
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the attributes of a window into a {@link #WINDOW_ATTRIBUTES} segment.
     *
     * @param attributes the segment receiving them
     * @return whether the request succeeded
     */
    boolean attributes(MemorySegment display, long window, MemorySegment attributes) {
        try {
            return (int) getWindowAttributes.invokeExact(display, window, attributes) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Translates the origin of a window to the coordinates of another window.
     *
     * @param source the window whose origin is translated
     * @param target the window whose coordinates are returned, normally the root
     * @return the left and top edge, or {@code null} when the windows are on different screens or the
     * request fails
     */
    int[] origin(MemorySegment display, long source, long target) {
        try (var arena = Arena.ofConfined()) {
            var scratch = arena.allocate(TRANSLATION_SIZE, 8);
            return origin(display, source, target, scratch)
                    ? new int[]{scratch.get(JAVA_INT, 0), scratch.get(JAVA_INT, 4)} : null;
        }
    }

    /**
     * Translates the origin of a window into caller memory, without allocating.
     *
     * @param source the window whose origin is translated
     * @param target the window whose coordinates are returned, normally the root
     * @param scratch at least {@link #TRANSLATION_SIZE} bytes, 8-byte aligned, receiving the left edge at
     * offset 0 and the top edge at offset 4
     * @return whether the translation succeeded
     */
    boolean origin(MemorySegment display, long source, long target, MemorySegment scratch) {
        try {
            return (int) translateCoordinates.invokeExact(display, source, target, 0, 0, scratch,
                    scratch.asSlice(4, 4), scratch.asSlice(8, 8)) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns the atom of a name if the server defines it.
     *
     * @return the atom, or {@code 0} when the server does not know the name
     */
    long atom(MemorySegment display, String name) {
        try (var arena = Arena.ofConfined()) {
            return (long) internAtom.invokeExact(display, arena.allocateFrom(name), 1);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads a property of a window.
     *
     * @param property the property's atom
     * @param type the requested type, or {@code 0} for any
     * @param limit the largest number of 32-bit units to read
     * @return the property, or {@code null} when the window lacks it or the request fails
     */
    Property property(MemorySegment display, long window, long property, long type, long limit) {
        if (property == 0) {
            return null;
        }
        try (var arena = Arena.ofConfined()) {
            var actualType = arena.allocate(JAVA_LONG);
            var format = arena.allocate(JAVA_INT);
            var items = arena.allocate(JAVA_LONG);
            var after = arena.allocate(JAVA_LONG);
            var data = arena.allocate(ADDRESS);
            var status = (int) getWindowProperty.invokeExact(display, window, property, 0L, limit, 0, type, actualType,
                    format, items, after, data);
            var pointer = data.get(ADDRESS, 0);
            try {
                if (status != 0 || actualType.get(JAVA_LONG, 0) == 0 || pointer.equals(MemorySegment.NULL)) {
                    return null;
                }
                var bits = format.get(JAVA_INT, 0);
                var count = items.get(JAVA_LONG, 0);
                var unit = switch (bits) {
                    case 8 -> 1;
                    case 16 -> 2;
                    case 32 -> 8;
                    default -> 0;
                };
                if (unit == 0 || count < 0 || count > (1 << 24)) {
                    return null;
                }
                var bytes = pointer.reinterpret(count * unit).toArray(JAVA_BYTE);
                return new Property(actualType.get(JAVA_LONG, 0), bits, (int) count, bytes);
            } finally {
                x11.free(pointer);
            }
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Frees a pixmap.
     */
    void freePixmap(MemorySegment display, long pixmap) {
        try {
            var ignored = (int) freePixmap.invokeExact(display, pixmap);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether the server offers Composite 0.2 or later, which names window pixmaps.
     *
     * @return whether window pixmaps can be named
     */
    boolean hasComposite(MemorySegment display) {
        if (compositeQueryExtension == null) {
            return false;
        }
        try (var arena = Arena.ofConfined()) {
            var first = arena.allocate(JAVA_INT);
            var second = arena.allocate(JAVA_INT);
            if ((int) compositeQueryExtension.invokeExact(display, first, second) == 0) {
                return false;
            }
            first.set(JAVA_INT, 0, 0);
            second.set(JAVA_INT, 0, 4);
            if ((int) compositeQueryVersion.invokeExact(display, first, second) == 0) {
                return false;
            }
            return first.get(JAVA_INT, 0) > 0 || second.get(JAVA_INT, 0) >= 2;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Redirects a window and its descendants to off-screen storage, updated by the server.
     */
    void redirect(MemorySegment display, long window) {
        try {
            compositeRedirectWindow.invokeExact(display, window, REDIRECT_AUTOMATIC);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Ends a redirection made by {@link #redirect}.
     */
    void unredirect(MemorySegment display, long window) {
        try {
            compositeUnredirectWindow.invokeExact(display, window, REDIRECT_AUTOMATIC);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Names the off-screen storage of a redirected window as a pixmap.
     *
     * @return the pixmap, to free with {@link #freePixmap}
     */
    long nameWindowPixmap(MemorySegment display, long window) {
        try {
            return (long) compositeNameWindowPixmap.invokeExact(display, window);
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Returns whether the server offers XFixes, which reports the pointer's image.
     *
     * @return whether the pointer can be read
     */
    boolean hasFixes(MemorySegment display) {
        if (fixesQueryExtension == null) {
            return false;
        }
        try (var arena = Arena.ofConfined()) {
            return (int) fixesQueryExtension.invokeExact(display, arena.allocate(JAVA_INT), arena.allocate(JAVA_INT)) != 0;
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Reads the pointer's image.
     *
     * @return the {@code XFixesCursorImage}, to free with {@link X11#free}, or {@link MemorySegment#NULL}
     */
    MemorySegment cursorImage(MemorySegment display) {
        try {
            var image = (MemorySegment) fixesGetCursorImage.invokeExact(display);
            return image.equals(MemorySegment.NULL) ? image : image.reinterpret(CURSOR_IMAGE.byteSize());
        } catch (Throwable throwable) {
            throw Native.rethrow(throwable);
        }
    }

    /**
     * Links a function a library may lack.
     *
     * @param lookup the library, or {@code null} when it is not installed
     * @return the handle, or {@code null}
     */
    private static MethodHandle optional(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return lookup == null ? null : Native.optionalFunction(lookup, name, descriptor).orElse(null);
    }

    /**
     * A window property.
     *
     * @param type the property's type atom
     * @param format the size of its items in bits: 8, 16 or 32
     * @param count the number of items
     * @param data the items; a 32-bit item takes 8 bytes, as Xlib stores it in a {@code long}
     */
    record Property(long type, int format, int count, byte[] data) {
        /**
         * Returns the 32-bit items as numbers.
         *
         * @return the items, or an empty array when the format is not 32
         */
        long[] longs() {
            if (format != 32) {
                return new long[0];
            }
            return MemorySegment.ofArray(data).toArray(JAVA_LONG.withByteAlignment(1));
        }

        /**
         * Returns the 8-bit items as text, up to the first {@code NUL}.
         *
         * @return the text, or an empty string when the format is not 8
         */
        String text() {
            if (format != 8) {
                return "";
            }
            var end = 0;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            return new String(data, 0, end, StandardCharsets.UTF_8);
        }
    }

    /**
     * Holds the bindings, created on first use.
     */
    private static final class Holder {
        static final X11Windowing INSTANCE = load();

        private Holder() {
            throw new AssertionError();
        }

        private static X11Windowing load() {
            var x11 = X11.instance();
            var lib = Native.library("libX11.so.6").orElse(null);
            if (x11 == null || lib == null) {
                return null;
            }
            return new X11Windowing(x11, lib, Native.library("libXcomposite.so.1").orElse(null),
                    Native.library("libXfixes.so.3").orElse(null));
        }
    }
}
