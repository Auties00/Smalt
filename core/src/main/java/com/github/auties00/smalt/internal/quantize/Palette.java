package com.github.auties00.smalt.internal.quantize;

import java.util.Arrays;

/**
 * Holds the opaque colors of a palette and finds the entry nearest to a color.
 *
 * <p>Colors are packed little-endian RGB {@code int}s: red in bits 0 to 7, green in bits 8 to 15
 * and blue in bits 16 to 23; the upper byte is ignored. The entries keep the order they are given
 * in, which is their index in the color table; a transparent entry, when the table has one, is
 * not part of this palette and takes the index {@link #size()}.
 *
 * <p>{@link #nearest(int)} returns the key {@link PaletteSearchKernel#nearest} would return, the
 * squared distance and index of the nearest entry, ties going to the lowest index. It first probes
 * a direct-mapped cache of the colors searched last, so repeated colors, the common case in
 * synthetic images and in the unchanged areas of an animation, cost one table probe; then it
 * searches only the candidate entries of the color's cell (see {@link #nearest(int)}). An
 * instance is not thread-safe.
 */
public final class Palette {
    /**
     * The number of entries of the lookup cache, a power of two.
     */
    private static final int CACHE_SIZE = 1 << 14;

    /**
     * The bit set in a cache tag to mark the entry valid; colors use only 24 bits.
     */
    private static final int VALID = 1 << 24;

    /**
     * The search kernel.
     */
    private static final PaletteSearchKernel KERNEL = PaletteSearchKernel.INSTANCE;

    /**
     * The number of bits of each channel that select a coarse cell: the color cube is divided
     * into {@code 2^(3 * COARSE_BITS)} coarse cells.
     */
    private static final int COARSE_BITS = 3;

    /**
     * The shift that reduces a channel to its coarse cell coordinate.
     */
    private static final int COARSE_SHIFT = 8 - COARSE_BITS;

    /**
     * The candidate count above which a search runs the kernel over every entry instead of
     * scanning the cell's candidates one by one.
     */
    private static final int KERNEL_CANDIDATES = 48;

    /**
     * The number of bits of each channel that select a fine cell: the color cube is divided into
     * {@code 2^(3 * FINE_BITS)} fine cells, each inside one coarse cell.
     */
    private static final int FINE_BITS = 5;

    /**
     * The shift that reduces a channel to its fine cell coordinate.
     */
    private static final int FINE_SHIFT = 8 - FINE_BITS;

    /**
     * The entries, packed RGB.
     */
    private final int[] colors;

    /**
     * The red channel of each entry, padded with {@link PaletteSearchKernel#SENTINEL}.
     */
    private final int[] reds;

    /**
     * The green channel of each entry, padded with {@link PaletteSearchKernel#SENTINEL}.
     */
    private final int[] greens;

    /**
     * The blue channel of each entry, padded with {@link PaletteSearchKernel#SENTINEL}.
     */
    private final int[] blues;

    /**
     * The padded length of the channel arrays.
     */
    private final int padded;

    /**
     * The squared distance from each entry to its nearest other entry, {@link Integer#MAX_VALUE}
     * for the only entry of a palette.
     */
    private final int[] guards;

    /**
     * The entry the last search found, tried first by the next one.
     */
    private int guess;

    /**
     * The start of each coarse cell's candidate list in {@link #candidates}, or {@code -1} until
     * the cell is first needed.
     */
    private final int[] coarseStarts;

    /**
     * The number of candidates of each coarse cell.
     */
    private final int[] coarseSizes;

    /**
     * The start of each fine cell's candidate list in {@link #candidates}, or {@code -1} until
     * the cell is first searched.
     */
    private final int[] fineStarts;

    /**
     * The number of candidates of each fine cell.
     */
    private final int[] fineSizes;

    /**
     * The candidate entry indices of the cells built so far, each cell's in ascending order.
     */
    private int[] candidates;

    /**
     * The number of valid entries in {@link #candidates}.
     */
    private int candidateCount;

    /**
     * The smallest squared distance of each entry (or coarse candidate) to the cell being built.
     */
    private int[] near;

    /**
     * The largest squared distance of each entry (or coarse candidate) to the cell being built.
     */
    private int[] far;

    /**
     * The color of each cache entry or-ed with {@link #VALID}, {@code 0} for an empty entry.
     */
    private final int[] cacheTags;

    /**
     * The search key of each cache entry.
     */
    private final int[] cacheKeys;

    /**
     * Constructs a palette.
     *
     * @param colors the entries, packed RGB, from 1 to 256 of them; the array is copied
     * @throws IllegalArgumentException if there are no entries or more than 256
     */
    public Palette(int[] colors) {
        this(colors, colors.length);
    }

    /**
     * Constructs a palette from the first entries of an array.
     *
     * @param colors the entries, packed RGB
     * @param count the number of entries, from 1 to 256; the entries are copied
     * @throws IllegalArgumentException if {@code count} is outside 1..256
     */
    public Palette(int[] colors, int count) {
        if (count < 1 || count > 256) {
            throw new IllegalArgumentException("palette of " + count + " colors");
        }
        this.colors = new int[count];
        this.padded = (count + PaletteSearchKernel.BLOCK - 1) / PaletteSearchKernel.BLOCK * PaletteSearchKernel.BLOCK;
        this.reds = new int[padded];
        this.greens = new int[padded];
        this.blues = new int[padded];
        Arrays.fill(reds, count, padded, PaletteSearchKernel.SENTINEL);
        Arrays.fill(greens, count, padded, PaletteSearchKernel.SENTINEL);
        Arrays.fill(blues, count, padded, PaletteSearchKernel.SENTINEL);
        for (var i = 0; i < count; i++) {
            var color = colors[i] & 0xFF_FFFF;
            this.colors[i] = color;
            reds[i] = color & 0xFF;
            greens[i] = (color >>> 8) & 0xFF;
            blues[i] = color >>> 16;
        }
        this.guards = new int[count];
        for (var i = 0; i < count; i++) {
            var best = Integer.MAX_VALUE;
            for (var j = 0; j < count; j++) {
                if (i != j) {
                    best = Math.min(best, distance(reds[i], greens[i], blues[i], j));
                }
            }
            guards[i] = best;
        }
        this.coarseStarts = new int[1 << (3 * COARSE_BITS)];
        this.coarseSizes = new int[coarseStarts.length];
        Arrays.fill(coarseStarts, -1);
        this.fineStarts = new int[1 << (3 * FINE_BITS)];
        this.fineSizes = new int[fineStarts.length];
        Arrays.fill(fineStarts, -1);
        this.candidates = new int[Math.max(64, count * 4)];
        this.cacheTags = new int[CACHE_SIZE];
        this.cacheKeys = new int[CACHE_SIZE];
    }

    /**
     * Returns the squared distance between a color and an entry.
     *
     * @param red the red channel
     * @param green the green channel
     * @param blue the blue channel
     * @param index the entry index
     * @return the squared distance
     */
    private int distance(int red, int green, int blue, int index) {
        var dr = red - reds[index];
        var dg = green - greens[index];
        var db = blue - blues[index];
        return dr * dr + dg * dg + db * db;
    }

    /**
     * Returns the number of entries.
     *
     * @return the entry count, from 1 to 256
     */
    public int size() {
        return colors.length;
    }

    /**
     * Returns an entry.
     *
     * @param index the entry index
     * @return the color, packed RGB
     */
    public int color(int index) {
        return colors[index];
    }

    /**
     * Returns the red channel of an entry.
     *
     * @param index the entry index
     * @return the channel, from 0 to 255
     */
    public int red(int index) {
        return reds[index];
    }

    /**
     * Returns the green channel of an entry.
     *
     * @param index the entry index
     * @return the channel, from 0 to 255
     */
    public int green(int index) {
        return greens[index];
    }

    /**
     * Returns the blue channel of an entry.
     *
     * @param index the entry index
     * @return the channel, from 0 to 255
     */
    public int blue(int index) {
        return blues[index];
    }

    /**
     * Returns the search key of the entry nearest to a color.
     *
     * <p>The result is the key {@link PaletteSearchKernel#nearest} returns for the color.
     *
     * @implNote This implementation probes a cache, then the entry the previous search found, and otherwise scans the
     * candidates of the color's cell (Heckbert's locally sorted search, "Color image quantization for frame buffer
     * display", SIGGRAPH 1982); the method body explains why each step returns the kernel's key.
     * @param color the color, packed RGB; the upper byte is ignored
     * @return the squared distance shifted left by 8, or-ed with the entry index (see {@link PaletteSearchKernel})
     */
    public int nearest(int color) {
        /*
         * The previous entry is strictly the nearest when the color lies closer to it than half the distance from
         * that entry to its nearest other entry (triangle inequality); neighboring pixels, which dithering visits in
         * turn, usually pass this test. A cell's candidates are the entries whose smallest distance to the cell does
         * not exceed the smallest, over all entries, of the largest distance to the cell: every entry nearest to some
         * color of the cell, ties included, is a candidate. Cells come in two levels built on first use, coarse cubes
         * whose candidates PaletteSearchKernel.bounds selects among all entries and fine cubes whose candidates are
         * selected among those of their coarse cube; that is exact because a fine cube lies inside its coarse cube.
         * A fine cell with more than KERNEL_CANDIDATES candidates is searched over every entry instead.
         */
        var rgb = color & 0xFF_FFFF;
        var slot = (rgb * 0x9E37_79B1) >>> 18;
        if (cacheTags[slot] == (rgb | VALID)) {
            return cacheKeys[slot];
        }
        var red = rgb & 0xFF;
        var green = (rgb >>> 8) & 0xFF;
        var blue = rgb >>> 16;
        var candidate = guess;
        var near = distance(red, green, blue, candidate);
        int key;
        if (4L * near < guards[candidate]) {
            key = near << 8 | candidate;
        } else {
            key = search(red, green, blue);
            guess = key & 0xFF;
        }
        cacheTags[slot] = rgb | VALID;
        cacheKeys[slot] = key;
        return key;
    }

    /**
     * Returns the search key of the entry nearest to a color by scanning the candidates of its
     * fine cell.
     *
     * @param red the red channel
     * @param green the green channel
     * @param blue the blue channel
     * @return the search key
     */
    private int search(int red, int green, int blue) {
        var cell = red >>> FINE_SHIFT | (green >>> FINE_SHIFT) << FINE_BITS | (blue >>> FINE_SHIFT) << (2 * FINE_BITS);
        var start = fineStarts[cell];
        if (start < 0) {
            start = buildFine(cell);
        }
        var size = fineSizes[cell];
        if (size > KERNEL_CANDIDATES) {
            return KERNEL.nearest(red, green, blue, reds, greens, blues, padded);
        }
        var end = start + size;
        var best = Integer.MAX_VALUE;
        var candidates = this.candidates;
        for (var i = start; i < end; i++) {
            var index = candidates[i];
            best = Math.min(best, distance(red, green, blue, index) << 8 | index);
        }
        return best;
    }

    /**
     * Computes the candidate entries of a coarse cell among every entry.
     *
     * @param cell the coarse cell index
     * @return the start of the cell's candidate list
     */
    private int buildCoarse(int cell) {
        if (near == null) {
            near = new int[padded];
            far = new int[padded];
        }
        var mask = (1 << COARSE_BITS) - 1;
        var red = (cell & mask) << COARSE_SHIFT;
        var green = ((cell >>> COARSE_BITS) & mask) << COARSE_SHIFT;
        var blue = (cell >>> (2 * COARSE_BITS)) << COARSE_SHIFT;
        KERNEL.bounds(red, green, blue, 1 << COARSE_SHIFT, reds, greens, blues, padded, near, far);
        var count = colors.length;
        var threshold = Integer.MAX_VALUE;
        for (var i = 0; i < count; i++) {
            threshold = Math.min(threshold, far[i]);
        }
        reserve(count);
        var start = candidateCount;
        for (var i = 0; i < count; i++) {
            if (near[i] <= threshold) {
                candidates[candidateCount++] = i;
            }
        }
        coarseStarts[cell] = start;
        coarseSizes[cell] = candidateCount - start;
        return start;
    }

    /**
     * Computes the candidate entries of a fine cell among those of its coarse cell.
     *
     * @param cell the fine cell index
     * @return the start of the cell's candidate list
     */
    private int buildFine(int cell) {
        var mask = (1 << FINE_BITS) - 1;
        var fineRed = cell & mask;
        var fineGreen = (cell >>> FINE_BITS) & mask;
        var fineBlue = cell >>> (2 * FINE_BITS);
        var down = FINE_BITS - COARSE_BITS;
        var coarse = fineRed >>> down | (fineGreen >>> down) << COARSE_BITS | (fineBlue >>> down) << (2 * COARSE_BITS);
        var coarseStart = coarseStarts[coarse];
        if (coarseStart < 0) {
            coarseStart = buildCoarse(coarse);
        }
        var coarseSize = coarseSizes[coarse];
        if (near == null) {
            near = new int[padded];
            far = new int[padded];
        }
        reserve(coarseSize);
        var candidates = this.candidates;
        var lowRed = fineRed << FINE_SHIFT;
        var lowGreen = fineGreen << FINE_SHIFT;
        var lowBlue = fineBlue << FINE_SHIFT;
        var last = (1 << FINE_SHIFT) - 1;
        var threshold = Integer.MAX_VALUE;
        for (var i = 0; i < coarseSize; i++) {
            var index = candidates[coarseStart + i];
            var r = reds[index];
            var g = greens[index];
            var b = blues[index];
            var nr = Math.max(0, Math.max(lowRed - r, r - (lowRed + last)));
            var ng = Math.max(0, Math.max(lowGreen - g, g - (lowGreen + last)));
            var nb = Math.max(0, Math.max(lowBlue - b, b - (lowBlue + last)));
            var fr = Math.max(r - lowRed, lowRed + last - r);
            var fg = Math.max(g - lowGreen, lowGreen + last - g);
            var fb = Math.max(b - lowBlue, lowBlue + last - b);
            near[i] = nr * nr + ng * ng + nb * nb;
            far[i] = fr * fr + fg * fg + fb * fb;
            threshold = Math.min(threshold, far[i]);
        }
        var start = candidateCount;
        for (var i = 0; i < coarseSize; i++) {
            if (near[i] <= threshold) {
                candidates[candidateCount++] = candidates[coarseStart + i];
            }
        }
        fineStarts[cell] = start;
        fineSizes[cell] = candidateCount - start;
        return start;
    }

    /**
     * Grows {@link #candidates} to hold more entries.
     *
     * @param extra the number of entries about to be appended
     */
    private void reserve(int extra) {
        if (candidateCount + extra > candidates.length) {
            candidates = Arrays.copyOf(candidates, Math.max(candidates.length * 2, candidateCount + extra));
        }
    }

    /**
     * Returns the index of the entry nearest to a color.
     *
     * @param color the color, packed RGB; the upper byte is ignored
     * @return the entry index
     */
    public int indexOf(int color) {
        return nearest(color) & 0xFF;
    }

    /**
     * Returns whether every color of a set is an entry of this palette.
     *
     * @param set the colors, packed RGB
     * @param count the number of colors
     * @return {@code true} when each color has an entry at distance 0
     */
    public boolean containsAll(int[] set, int count) {
        for (var i = 0; i < count; i++) {
            if (nearest(set[i]) >>> 8 != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the mean distance between neighboring entries: the square root of the squared
     * distance from each entry to its nearest other entry, averaged over the entries.
     *
     * @return the mean spacing in channel units, {@code 0} for a single entry
     */
    public double spacing() {
        var count = colors.length;
        if (count < 2) {
            return 0;
        }
        var total = 0.0;
        for (var guard : guards) {
            total += Math.sqrt(guard);
        }
        return total / count;
    }
}
