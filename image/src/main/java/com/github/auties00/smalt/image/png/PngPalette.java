package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Collects up to 256 distinct colors and maps each to its palette index.
 *
 * <p>Colors are packed little-endian RGBA {@code int}s. Colors are added until the palette
 * overflows, then {@link #order()} fixes the indices: entries whose alpha is below 255 come
 * first, so the {@code tRNS} chunk only needs {@link #translucentCount()} entries, and within
 * each group entries keep the order in which they were first added, so the palette of a picture
 * is deterministic. Instances are not thread-safe while colors are added; once ordered, the
 * lookups only read.
 */
final class PngPalette {
    /**
     * The largest number of entries of an 8-bit palette.
     */
    static final int MAX_COLORS = 256;

    /**
     * The number of slots of the open-addressing table, a power of two above four times
     * {@link #MAX_COLORS}.
     */
    private static final int SLOTS = 1024;

    /**
     * The color stored in each occupied slot.
     */
    private final int[] keys = new int[SLOTS];

    /**
     * The palette index plus one of each slot, 0 for an empty slot.
     */
    private final int[] values = new int[SLOTS];

    /**
     * The palette entries, in palette order.
     */
    private final int[] colors = new int[MAX_COLORS];

    /**
     * The number of palette entries.
     */
    private int size;

    /**
     * The number of leading entries whose alpha is below 255, known once ordered.
     */
    private int translucent;

    /**
     * Whether a color was added to a full palette.
     */
    private boolean overflowed;

    /**
     * The last color added, valid when {@link #hasLast} is set.
     */
    private int last;

    /**
     * Whether {@link #last} holds a color.
     */
    private boolean hasLast;

    /**
     * Constructs an empty palette.
     */
    PngPalette() {
    }

    /**
     * Collects and orders the palette of a picture.
     *
     * @param picture the picture whose colors are collected
     * @param opaque whether alpha is ignored (every color gets alpha 255)
     * @return the palette, or {@code null} when the picture has more than 256 distinct colors
     */
    static PngPalette of(RgbaPicture picture, boolean opaque) {
        var palette = new PngPalette();
        var pixels = picture.pixels();
        var stride = picture.stride();
        var width = picture.width();
        var force = opaque ? 0xFF00_0000 : 0;
        for (var y = 0; y < picture.height(); y++) {
            var row = y * stride;
            for (var x = 0; x < width; x++) {
                if (!palette.add(ByteArrays.i32le(pixels, row + x * 4) | force)) {
                    return null;
                }
            }
        }
        palette.order();
        return palette;
    }

    /**
     * {@return the slot to start probing for a color}
     *
     * @param color the packed color
     */
    private static int hash(int color) {
        return (color * 0x9E37_79B9) >>> 22;
    }

    /**
     * Adds a color if it is new, unless the palette has overflowed.
     *
     * @param color the packed color to add
     * @return {@code false} once a new color was added to a full palette
     */
    boolean add(int color) {
        if (overflowed || (hasLast && color == last)) {
            return !overflowed;
        }
        last = color;
        hasLast = true;
        var slot = hash(color);
        while (values[slot] != 0) {
            if (keys[slot] == color) {
                return true;
            }
            slot = (slot + 1) & (SLOTS - 1);
        }
        if (size == MAX_COLORS) {
            overflowed = true;
            return false;
        }
        keys[slot] = color;
        colors[size] = color;
        values[slot] = ++size;
        return true;
    }

    /**
     * {@return whether a color was added to a full palette}
     */
    boolean overflowed() {
        return overflowed;
    }

    /**
     * Moves translucent entries to the front, keeping first-seen order within each group, and
     * reassigns the indices.
     */
    void order() {
        var ordered = new int[size];
        var next = 0;
        for (var i = 0; i < size; i++) {
            if ((colors[i] >>> 24) != 0xFF) {
                ordered[next++] = colors[i];
            }
        }
        translucent = next;
        for (var i = 0; i < size; i++) {
            if ((colors[i] >>> 24) == 0xFF) {
                ordered[next++] = colors[i];
            }
        }
        for (var i = 0; i < size; i++) {
            colors[i] = ordered[i];
            values[find(ordered[i])] = i + 1;
        }
    }

    /**
     * {@return the number of palette entries, from 0 to 256}
     */
    int size() {
        return size;
    }

    /**
     * {@return the number of leading entries whose alpha is below 255, the length of the
     * {@code tRNS} chunk; 0 when every entry is opaque or the palette is not ordered}
     */
    int translucentCount() {
        return translucent;
    }

    /**
     * {@return the packed little-endian RGBA color of an entry}
     *
     * @param index the palette index
     */
    int color(int index) {
        return colors[index];
    }

    /**
     * {@return the palette index of a color, or -1 for a color not in the palette}
     *
     * @param color the packed color to look up
     */
    int indexOf(int color) {
        var slot = find(color);
        return slot < 0 ? -1 : values[slot] - 1;
    }

    /**
     * {@return the slot holding a color, or -1 when the color is absent}
     *
     * @param color the packed color to look up
     */
    private int find(int color) {
        var slot = hash(color);
        while (values[slot] != 0) {
            if (keys[slot] == color) {
                return slot;
            }
            slot = (slot + 1) & (SLOTS - 1);
        }
        return -1;
    }
}
