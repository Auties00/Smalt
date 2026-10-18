package com.github.auties00.smalt.internal.quantize;

import java.util.Arrays;

/**
 * Collects the distinct colors of a list of pixels when there are few enough of them to form a
 * palette without quantization.
 *
 * <p>Colors are packed little-endian RGB {@code int}s; the upper byte is ignored. The set keeps
 * the order in which the colors first appear, so the palette of an image is deterministic. An
 * instance is reused across images and is not thread-safe.
 */
public final class ExactColors {
    /**
     * The number of slots of the open-addressing table, a power of two above four times the
     * largest set.
     */
    private static final int SLOTS = 1024;

    /**
     * The color of each occupied slot or-ed with bit 24, {@code 0} for an empty slot.
     */
    private final int[] slots = new int[SLOTS];

    /**
     * The colors of the last collection, in first-seen order.
     */
    private final int[] colors = new int[256];

    /**
     * The number of colors of the last collection.
     */
    private int size;

    /**
     * Constructs an empty collector.
     */
    public ExactColors() {
    }

    /**
     * Collects the distinct colors of pixels, stopping once there are more than a limit.
     *
     * @param pixels the colors, packed RGB
     * @param count  the number of pixels
     * @param limit  the largest set size wanted, from 1 to 256
     * @return {@code true} when the pixels have at most {@code limit} distinct colors, which
     *         {@link #size()} and {@link #colors()} then return
     */
    public boolean collect(int[] pixels, int count, int limit) {
        Arrays.fill(slots, 0);
        size = 0;
        var last = -1;
        for (var i = 0; i < count; i++) {
            var color = pixels[i] & 0xFF_FFFF;
            if (color == last) {
                continue;
            }
            last = color;
            var tag = color | 0x100_0000;
            var slot = (color * 0x9E37_79B1) >>> 22;
            while (slots[slot] != 0 && slots[slot] != tag) {
                slot = (slot + 1) & (SLOTS - 1);
            }
            if (slots[slot] == 0) {
                if (size == limit) {
                    return false;
                }
                slots[slot] = tag;
                colors[size++] = color;
            }
        }
        return true;
    }

    /**
     * Returns the number of colors of the last successful collection.
     *
     * @return the set size
     */
    public int size() {
        return size;
    }

    /**
     * Returns the colors of the last successful collection, in first-seen order.
     *
     * @return the backing array, whose first {@link #size()} entries are the colors
     */
    public int[] colors() {
        return colors;
    }
}
