package com.github.auties00.smalt.internal.heif;

/**
 * Lays out component planes as the data of an uncompressed frame (ISO/IEC 23001-17), the inverse
 * of {@link HeifUncompressed} for the layouts without blocks and pixel padding.
 *
 * <p>Values are stored unsigned, bit after bit with the most significant bit first, or in whole
 * bytes (least significant first when the layout is little-endian) when a value of a whole number
 * of bytes starts on a byte boundary or its component is aligned to {@code component_align_size}
 * bytes. Every row of a component plane, every row of pixels and every tile starts on a byte
 * boundary; row and tile padding is zero. Instances are not thread-safe.
 */
public final class HeifUncompressedWriter {
    /**
     * The component interleaving.
     */
    public static final int COMPONENT = 0;

    /**
     * The pixel interleaving.
     */
    public static final int PIXEL = 1;

    /**
     * The mixed interleaving: planar luma followed by one plane of interleaved chroma.
     */
    public static final int MIXED = 2;

    /**
     * The row interleaving.
     */
    public static final int ROW = 3;

    /**
     * The tile-component interleaving.
     */
    public static final int TILE_COMPONENT = 4;

    /**
     * The multi-Y interleaving.
     */
    public static final int MULTI_Y = 5;

    /**
     * The component type of each component of the layout, in storage order.
     */
    private final int[] types;

    /**
     * The bits of each component of the layout.
     */
    private final int[] depths;

    /**
     * The {@code component_align_size} of each component of the layout.
     */
    private final int[] aligns;

    private final int interleave;

    private final boolean littleEndian;

    /**
     * The multiple of bytes a row is padded to, or 0.
     */
    private final int rowAlign;

    /**
     * The multiple of bytes a tile is padded to, or 0.
     */
    private final int tileAlign;

    private final int columns;

    private final int rows;

    private final int width;

    private final int height;

    /**
     * The horizontal chroma subsampling factor.
     */
    private final int sx;

    /**
     * The vertical chroma subsampling factor.
     */
    private final int sy;

    /**
     * The width of the plane of each component type, by type.
     */
    private final int[] planeWidths = new int[8];

    /**
     * The size of a frame in bytes.
     */
    private final long frameSize;

    /**
     * The destination, or {@code null} while the frame size is measured.
     */
    private byte[] out;

    /**
     * The planes being written, by component type.
     */
    private short[][] planes;

    /**
     * The byte position of the writer.
     */
    private long pos;

    /**
     * The bits of the byte at {@link #pos} already written, from 0 to 7.
     */
    private int bit;

    /**
     * Constructs a writer.
     *
     * @param types the component type of each component of the layout, in storage order: monochrome, Y, Cb, Cr,
     * red, green, blue or alpha; a multi-Y layout names Y once per luma sample of a unit
     * @param depths the bits of each component, from 1 to 16
     * @param aligns the bytes each value of a component occupies, or 0 for values packed bit after bit
     * @param samplingType the {@code sampling_type}: 0 none, 1 4:2:2, 2 4:2:0, 3 4:1:1
     * @param interleave the {@code interleave_type}, one of the constants of this class
     * @param littleEndian whether values of several bytes are stored least significant byte first
     * @param rowAlign the multiple of bytes a row is padded to, or 0
     * @param tileAlign the multiple of bytes a tile is padded to, or 0
     * @param columns the tile columns, dividing the width
     * @param rows the tile rows, dividing the height
     * @param width the frame width
     * @param height the frame height
     */
    public HeifUncompressedWriter(int[] types, int[] depths, int[] aligns, int samplingType, int interleave,
                                  boolean littleEndian, int rowAlign, int tileAlign, int columns, int rows, int width,
                                  int height) {
        this.types = types.clone();
        this.depths = depths.clone();
        this.aligns = aligns.clone();
        this.interleave = interleave;
        this.littleEndian = littleEndian;
        this.rowAlign = rowAlign;
        this.tileAlign = tileAlign;
        this.columns = columns;
        this.rows = rows;
        this.width = width;
        this.height = height;
        this.sx = switch (samplingType) {
            case 1, 2 -> 2;
            case 3 -> 4;
            default -> 1;
        };
        this.sy = samplingType == 2 ? 2 : 1;
        for (var type : types) {
            planeWidths[type] = isChroma(type) ? ceil(width, sx) : width;
        }
        traverse();
        this.frameSize = pos;
    }

    /**
     * {@return the size of a frame in bytes}
     */
    public long frameSize() {
        return frameSize;
    }

    /**
     * {@return the width of the plane of a component type}
     *
     * @param type the component type
     */
    public int planeWidth(int type) {
        return planeWidths[type];
    }

    /**
     * {@return the height of the plane of a component type}
     *
     * @param type the component type
     */
    public int planeHeight(int type) {
        return isChroma(type) ? ceil(height, sy) : height;
    }

    /**
     * Writes one frame.
     *
     * @param planes the unsigned samples of each component type of the layout, by type, row-major
     * without padding at the size of {@link #planeWidth} and {@link #planeHeight}, each below
     * {@code 2^depth}
     * @param destination the frame, at least {@link #frameSize()} bytes, zero where padding goes
     */
    public void write(short[][] planes, byte[] destination) {
        this.planes = planes;
        this.out = destination;
        try {
            traverse();
        } finally {
            this.planes = null;
            this.out = null;
        }
    }

    /**
     * Walks the layout, writing the values when {@link #out} is set and measuring it otherwise.
     */
    private void traverse() {
        pos = 0;
        bit = 0;
        var tileWidth = width / columns;
        var tileHeight = height / rows;
        var tiles = columns * rows;
        if (interleave == TILE_COMPONENT) {
            for (var c = 0; c < types.length; c++) {
                for (var t = 0; t < tiles; t++) {
                    var start = pos;
                    componentPlane(c, (t % columns) * tileWidth, (t / columns) * tileHeight, tileWidth, tileHeight);
                    alignTo(start, tileAlign);
                }
            }
            return;
        }
        for (var t = 0; t < tiles; t++) {
            var start = pos;
            var x0 = (t % columns) * tileWidth;
            var y0 = (t / columns) * tileHeight;
            switch (interleave) {
                case COMPONENT -> {
                    for (var c = 0; c < types.length; c++) {
                        componentPlane(c, x0, y0, tileWidth, tileHeight);
                    }
                }
                case PIXEL -> pixels(x0, y0, tileWidth, tileHeight);
                case MIXED -> mixed(x0, y0, tileWidth, tileHeight);
                case ROW -> rows(x0, y0, tileWidth, tileHeight);
                default -> multiY(x0, y0, tileWidth, tileHeight);
            }
            alignTo(start, tileAlign);
        }
    }

    /**
     * Writes the plane of one component of one tile.
     *
     * @param c the component of the layout
     * @param x0 the left edge of the tile in luma samples
     * @param y0 the top edge of the tile in luma samples
     * @param tileWidth the tile width in luma samples
     * @param tileHeight the tile height in luma samples
     */
    private void componentPlane(int c, int x0, int y0, int tileWidth, int tileHeight) {
        var chroma = isChroma(types[c]);
        var w = chroma ? ceil(tileWidth, sx) : tileWidth;
        var h = chroma ? ceil(tileHeight, sy) : tileHeight;
        var px = chroma ? x0 / sx : x0;
        var py = chroma ? y0 / sy : y0;
        for (var row = 0; row < h; row++) {
            var start = pos;
            for (var x = 0; x < w; x++) {
                put(c, sample(types[c], px + x, py + row));
            }
            endSegment();
            alignTo(start, rowAlign);
        }
    }

    /**
     * Writes one tile of pixel-interleaved components.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void pixels(int x0, int y0, int tileWidth, int tileHeight) {
        for (var row = 0; row < tileHeight; row++) {
            var start = pos;
            for (var x = 0; x < tileWidth; x++) {
                for (var c = 0; c < types.length; c++) {
                    put(c, sample(types[c], x0 + x, y0 + row));
                }
            }
            endSegment();
            alignTo(start, rowAlign);
        }
    }

    /**
     * Writes one tile of planar full-resolution components and interleaved chroma, the chroma
     * plane in the place of the first chroma component.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void mixed(int x0, int y0, int tileWidth, int tileHeight) {
        var chromaDone = false;
        for (var c = 0; c < types.length; c++) {
            if (!isChroma(types[c])) {
                componentPlane(c, x0, y0, tileWidth, tileHeight);
                continue;
            }
            if (chromaDone) {
                continue;
            }
            chromaDone = true;
            var w = ceil(tileWidth, sx);
            var h = ceil(tileHeight, sy);
            for (var row = 0; row < h; row++) {
                var start = pos;
                for (var x = 0; x < w; x++) {
                    for (var k = 0; k < types.length; k++) {
                        if (isChroma(types[k])) {
                            put(k, sample(types[k], x0 / sx + x, y0 / sy + row));
                        }
                    }
                }
                endSegment();
                alignTo(start, rowAlign);
            }
        }
    }

    /**
     * Writes one tile of row-interleaved components.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void rows(int x0, int y0, int tileWidth, int tileHeight) {
        for (var row = 0; row < tileHeight; row++) {
            for (var c = 0; c < types.length; c++) {
                var chroma = isChroma(types[c]);
                var w = chroma ? ceil(tileWidth, sx) : tileWidth;
                var px = chroma ? x0 / sx : x0;
                var start = pos;
                for (var x = 0; x < w; x++) {
                    put(c, sample(types[c], px + x, y0 + row));
                }
                endSegment();
                alignTo(start, rowAlign);
            }
        }
    }

    /**
     * Writes one tile of multi-Y units: one sample of each chroma component and the luma samples
     * that share it, the n-th luma component of the layout the n-th sample from the left, zero past
     * the right edge.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void multiY(int x0, int y0, int tileWidth, int tileHeight) {
        var units = ceil(tileWidth, sx);
        for (var row = 0; row < tileHeight; row++) {
            var start = pos;
            for (var unit = 0; unit < units; unit++) {
                var luma = 0;
                for (var c = 0; c < types.length; c++) {
                    if (isChroma(types[c])) {
                        put(c, sample(types[c], x0 / sx + unit, y0 + row));
                    } else {
                        var x = unit * sx + luma++;
                        put(c, x < tileWidth ? sample(types[c], x0 + x, y0 + row) : 0);
                    }
                }
            }
            endSegment();
            alignTo(start, rowAlign);
        }
    }

    /**
     * {@return the sample of a component type at a place, or 0 while measuring}
     *
     * @param type the component type
     * @param x the column in its plane
     * @param y the row in its plane
     */
    private int sample(int type, int x, int y) {
        return planes == null ? 0 : planes[type][y * planeWidths[type] + x] & 0xFFFF;
    }

    /**
     * Writes the next value.
     *
     * @param c the component of the layout the value belongs to
     * @param value the unsigned value
     */
    private void put(int c, int value) {
        var depth = depths[c];
        var align = aligns[c];
        if (align != 0) {
            endSegment();
            putBytes(align, value);
        } else if (bit == 0 && (depth & 7) == 0) {
            putBytes(depth >> 3, value);
        } else {
            putBits(depth, value);
        }
    }

    /**
     * Writes whole bytes of one unsigned value.
     *
     * @param count the number of bytes
     * @param value the unsigned value, below {@code 2^(8 * count)}
     */
    private void putBytes(int count, int value) {
        if (out != null) {
            var at = (int) pos;
            for (var i = 0; i < count; i++) {
                var shift = 8 * (littleEndian ? i : count - 1 - i);
                out[at + i] = (byte) (shift >= 32 ? 0 : value >>> shift);
            }
        }
        pos += count;
    }

    /**
     * Writes bits most significant first.
     *
     * @param count the number of bits, from 1 to 16
     * @param value the unsigned value, below {@code 2^count}
     */
    private void putBits(int count, int value) {
        var left = count;
        while (left > 0) {
            var available = 8 - bit;
            var take = Math.min(available, left);
            if (out != null) {
                var bits = (value >>> (left - take)) & ((1 << take) - 1);
                out[(int) pos] |= (byte) (bits << (available - take));
            }
            left -= take;
            bit += take;
            if (bit == 8) {
                bit = 0;
                pos++;
            }
        }
    }

    /**
     * Ends a run of values: the next value starts on the next byte boundary.
     */
    private void endSegment() {
        if (bit != 0) {
            bit = 0;
            pos++;
        }
    }

    /**
     * Moves past the padding that makes a region a multiple of a size.
     *
     * @param start the byte position of the region
     * @param align the size, or 0 for no padding
     */
    private void alignTo(long start, long align) {
        if (align > 0) {
            pos = start + (pos - start + align - 1) / align * align;
        }
    }

    /**
     * {@return whether a component type is a chroma type}
     *
     * @param type the component type
     */
    private static boolean isChroma(int type) {
        return type == HeifComponents.CB || type == HeifComponents.CR;
    }

    /**
     * {@return {@code value / divisor} rounded up}
     *
     * @param value the dividend, not negative
     * @param divisor the divisor, positive
     */
    private static int ceil(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
