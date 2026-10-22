package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the data of an uncompressed image item ({@code unci}, ISO/IEC 23001-17) into its
 * component planes.
 *
 * <p>The layout comes from the item's {@code uncC} property, the meaning of each component from its
 * {@code cmpd} property (implied by the profile of a version 1 {@code uncC}). Supported:
 * <ul>
 *   <li>every interleaving: component, pixel, mixed (planar luma followed by interleaved chroma),
 *       row, tile-component and multi-Y (for 4:2:2 and 4:1:1);</li>
 *   <li>4:4:4, 4:2:2, 4:2:0 and 4:1:1 chroma sampling (row interleaving with vertical chroma
 *       subsampling excepted);</li>
 *   <li>unsigned integer values of 1 to 16 bits, packed bit after bit (most significant bit first,
 *       or least significant byte first for values of whole bytes with little-endian components),
 *       or aligned to {@code component_align_size} bytes, with the value in the least significant
 *       bits;</li>
 *   <li>blocks of 1 to 8 bytes in either byte order, with their padding at either end and their
 *       values in either order; a value starts a new block when it does not fit in the current
 *       one;</li>
 *   <li>pixel padding ({@code pixel_size}), row padding ({@code row_align_size}), tile padding
 *       ({@code tile_align_size}) and equal tiles;</li>
 *   <li>item data compressed as one stream by the generic compression of ISO/IEC 23001-17
 *       Amendment 2 ({@code cmpC}) with {@code defl} or {@code zlib}.</li>
 * </ul>
 * Every row of a component plane, every pixel when {@code pixel_size} is set, and every row of
 * pixels starts on a byte boundary. Padding components ({@code cmpd} type 12) are skipped;
 * components other than monochrome, Y'CbCr, RGB and alpha are read and discarded.
 */
public final class HeifUncompressed {
    // TODO: decode signed, floating-point and complex components, components above 16 bits, CMYK components,
    //  palettes (cpal), filter arrays (cpat), blocks above 8 bytes, row interleaving with vertically subsampled
    //  chroma, multi-Y interleaving of 4:2:0, brotli compression and compressed units indexed by an icef box
    /**
     * The interleaving of component planes one after the other.
     */
    private static final int COMPONENT = 0;

    /**
     * The interleaving of all components of a pixel together.
     */
    private static final int PIXEL = 1;

    /**
     * The interleaving of planar luma followed by one plane of interleaved chroma.
     */
    private static final int MIXED = 2;

    /**
     * The interleaving of the rows of the components.
     */
    private static final int ROW = 3;

    /**
     * The interleaving of each component of every tile, component after component.
     */
    private static final int TILE_COMPONENT = 4;

    /**
     * The interleaving of the luma values of a chroma sample with that sample.
     */
    private static final int MULTI_Y = 5;

    /**
     * The {@code cmpd} type of a padding component.
     */
    private static final int PADDED = 12;

    /**
     * The format name, for messages.
     */
    private final String name;

    /**
     * The item data, decompressed.
     */
    private final byte[] data;

    /**
     * The layout.
     */
    private final HeifProperty.UncompressedConfiguration config;

    /**
     * The component type of each component of the layout.
     */
    private final int[] types;

    /**
     * The bits per value of each component of the layout.
     */
    private final int[] depths;

    /**
     * The {@code component_align_size} of each component of the layout.
     */
    private final int[] aligns;

    /**
     * The depth of each component of the layout as a one-value cycle, for the segments of its
     * planes and rows.
     */
    private final int[][] singles;

    /**
     * The components of the layout that are chroma, in layout order.
     */
    private final int[] chromaComponents;

    /**
     * The depths of {@link #chromaComponents}, the cycle of an interleaved chroma row.
     */
    private final int[] chromaDepths;

    /**
     * The horizontal chroma subsampling factor.
     */
    private final int sx;

    /**
     * The vertical chroma subsampling factor.
     */
    private final int sy;

    /**
     * The output planes by component type, {@code null} for the types not decoded.
     */
    private final short[][] planes = new short[8][];

    /**
     * The width of each output plane.
     */
    private final int[] planeWidths = new int[8];

    /**
     * The height of each output plane.
     */
    private final int[] planeHeights = new int[8];

    /**
     * The bits per value of each output plane.
     */
    private final int[] planeDepths = new int[8];

    /**
     * The byte position of the reader.
     */
    private long pos;

    /**
     * The bits of the byte at {@link #pos} already read, from 0 to 7.
     */
    private int bit;

    /**
     * The depth of each value of the current segment, repeated cyclically.
     */
    private int[] segmentDepths;

    /**
     * The number of values in the current segment.
     */
    private long segmentLength;

    /**
     * The index in the segment of the next value read.
     */
    private long segmentIndex;

    /**
     * The current block, meaningful while {@link #blockEnd} is after the index of the next value.
     */
    private long block;

    /**
     * The index in the segment after the last value of the current block.
     */
    private long blockEnd;

    /**
     * The bits of the current block holding values.
     */
    private int blockContent;

    /**
     * The bit offset in the current block of the next value, counted from the start of its
     * content.
     */
    private int blockOffset;

    /**
     * Constructs a decoder.
     *
     * @param name the format name, for messages
     * @param data the decompressed item data
     * @param config the layout
     * @param types the component type of each component of the layout
     */
    private HeifUncompressed(String name, byte[] data, HeifProperty.UncompressedConfiguration config, int[] types) {
        this.name = name;
        this.data = data;
        this.config = config;
        this.types = types;
        this.depths = new int[types.length];
        this.aligns = new int[types.length];
        this.singles = new int[types.length][];
        var chroma = 0;
        for (var i = 0; i < types.length; i++) {
            depths[i] = config.components().get(i).bitDepth();
            aligns[i] = config.components().get(i).alignSize();
            singles[i] = new int[]{depths[i]};
            if (isChroma(types[i])) {
                chroma++;
            }
        }
        this.chromaComponents = new int[chroma];
        this.chromaDepths = new int[chroma];
        for (int i = 0, k = 0; i < types.length; i++) {
            if (isChroma(types[i])) {
                chromaComponents[k] = i;
                chromaDepths[k++] = depths[i];
            }
        }
        this.sx = switch (config.samplingType()) {
            case 1, 2 -> 2;
            case 3 -> 4;
            default -> 1;
        };
        this.sy = config.samplingType() == 2 ? 2 : 1;
    }

    /**
     * Returns whether an item is an uncompressed image whose components include alpha.
     *
     * @param item the item to test, of any type
     * @return {@code true} for an {@code unci} item with an alpha component
     */
    public static boolean hasAlpha(HeifItem item) {
        if (!item.type().equals("unci")) {
            return false;
        }
        var config = item.property(HeifProperty.UncompressedConfiguration.class);
        if (config == null) {
            return false;
        }
        var definition = item.property(HeifProperty.ComponentDefinition.class);
        for (var component : config.components()) {
            var type = config.shorthand() ? component.index()
                    : definition != null && component.index() < definition.types().length
                    ? definition.types()[component.index()] : -1;
            if (type == HeifComponents.ALPHA) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decodes an uncompressed image item.
     *
     * @param name the format name, for messages, such as {@code "heic"}
     * @param payload the item data as stored
     * @param item the item, with its {@code uncC}, {@code cmpd} and optional {@code cmpC}
     * properties
     * @param width the image width, from the item's {@code ispe} property
     * @param height the image height, from the item's {@code ispe} property
     * @param limits the bounds of the planes and of the decompressed data
     * @return the decoded components
     * @throws MediaException.Malformed if a property is missing or inconsistent, or the data is too
     * short for the layout
     * @throws MediaException.Unsupported if the layout uses a feature this decoder lacks
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     */
    public static HeifComponents decode(String name, byte[] payload, HeifItem item, int width, int height,
                                        MediaLimits limits) {
        var config = item.property(HeifProperty.UncompressedConfiguration.class);
        if (config == null) {
            for (var property : item.properties()) {
                if (property instanceof HeifProperty.Unknown unknown && unknown.type().equals("uncC")) {
                    throw new MediaException.Unsupported(name, "uncC boxes of a version or profile other than "
                            + "version 0 and the version 1 profiles rgb3, rgba and abgr");
                }
            }
            throw new MediaException.Malformed(name + ": uncompressed item " + item.id() + " has no uncC property");
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed(name + ": uncompressed item " + item.id() + " has no size");
        }
        LimitChecks.checkPicture(limits, width, height);
        var components = config.components();
        if (components.isEmpty()) {
            throw new MediaException.Malformed(name + ": uncompressed item " + item.id() + " has no component");
        }
        var types = new int[components.size()];
        var definition = item.property(HeifProperty.ComponentDefinition.class);
        for (var i = 0; i < types.length; i++) {
            var component = components.get(i);
            if (config.shorthand()) {
                types[i] = component.index();
            } else {
                if (definition == null) {
                    throw new MediaException.Malformed(name + ": uncompressed item " + item.id() + " has no cmpd property");
                }
                if (component.index() >= definition.types().length) {
                    throw new MediaException.Malformed(name + ": uncC component " + i + " names cmpd entry "
                            + component.index() + " of " + definition.types().length);
                }
                types[i] = definition.types()[component.index()];
            }
            if (component.format() != 0) {
                throw new MediaException.Unsupported(name, "uncompressed components of format " + component.format());
            }
            if (component.bitDepth() > 16) {
                throw new MediaException.Unsupported(name, "uncompressed components of " + component.bitDepth() + " bits");
            }
            switch (types[i]) {
                case 10 -> throw new MediaException.Unsupported(name, "uncompressed palette images");
                case 11 -> throw new MediaException.Unsupported(name, "uncompressed filter array (Bayer) images");
                case 13, 14, 15, 16 -> throw new MediaException.Unsupported(name, "uncompressed CMYK images");
                default -> {
                }
            }
            if (component.alignSize() != 0 && component.alignSize() * 8 < component.bitDepth()) {
                throw new MediaException.Malformed(name + ": uncC component " + i + " of " + component.bitDepth()
                        + " bits is aligned to " + component.alignSize() + " bytes");
            }
        }
        if (config.blockSize() > 8) {
            throw new MediaException.Unsupported(name, "uncompressed blocks of " + config.blockSize() + " bytes");
        }
        var data = decompress(name, payload, item, limits);
        var decoder = new HeifUncompressed(name, data, config, types);
        return decoder.run(width, height, limits);
    }

    /**
     * Undoes the generic compression of the item data, if any.
     *
     * @param name the format name, for messages
     * @param payload the item data as stored
     * @param item the uncompressed item, with its optional {@code cmpC} property
     * @param limits the bound of the decompressed size
     * @return the decompressed data
     */
    private static byte[] decompress(String name, byte[] payload, HeifItem item, MediaLimits limits) {
        var compression = item.property(HeifProperty.CompressionConfiguration.class);
        if (compression == null) {
            return payload;
        }
        if (compression.entityType() != 0) {
            throw new MediaException.Unsupported(name, "uncompressed data compressed in units of type "
                    + compression.entityType());
        }
        var nowrap = switch (compression.compressionType()) {
            case "defl" -> true;
            case "zlib" -> false;
            default -> throw new MediaException.Unsupported(name, "uncompressed data compressed with '"
                    + compression.compressionType() + "'");
        };
        var inflater = new Inflater(nowrap);
        try {
            inflater.setInput(payload);
            var initial = (int) Math.max(1024, Math.min(payload.length * 4L, 1 << 24));
            LimitChecks.checkAllocation(limits, initial);
            var out = new byte[initial];
            var size = 0;
            while (!inflater.finished()) {
                if (size == out.length) {
                    LimitChecks.checkAllocation(limits, 2L * out.length);
                    out = java.util.Arrays.copyOf(out, out.length * 2);
                }
                var count = inflater.inflate(out, size, out.length - size);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new MediaException.Malformed(name + ": the compressed data of item " + item.id()
                            + " is truncated");
                }
                size += count;
            }
            return java.util.Arrays.copyOf(out, size);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed(name + ": the compressed data of item " + item.id() + " is corrupt",
                    exception);
        } finally {
            inflater.end();
        }
    }

    /**
     * Decodes every tile.
     *
     * @param width the image width
     * @param height the image height
     * @param limits the bounds of the planes
     * @return the components
     */
    private HeifComponents run(int width, int height, MediaLimits limits) {
        var columns = config.tileColumns();
        var rows = config.tileRows();
        if (columns > width || rows > height || width % columns != 0 || height % rows != 0) {
            throw new MediaException.Malformed(name + ": " + columns + "x" + rows + " uncompressed tiles do not divide "
                    + width + "x" + height);
        }
        var tileWidth = (int) (width / columns);
        var tileHeight = (int) (height / rows);
        if ((columns > 1 && tileWidth % sx != 0) || (rows > 1 && tileHeight % sy != 0)) {
            throw new MediaException.Malformed(name + ": uncompressed tiles of " + tileWidth + "x" + tileHeight
                    + " split the subsampled chroma");
        }
        var interleave = config.interleaveType();
        switch (interleave) {
            case COMPONENT, TILE_COMPONENT -> {
            }
            case PIXEL -> {
                if (sx != 1 || sy != 1) {
                    throw new MediaException.Malformed(name + ": pixel interleaving with subsampled chroma");
                }
            }
            case MIXED -> {
                if (sx == 1) {
                    throw new MediaException.Malformed(name + ": mixed interleaving without subsampled chroma");
                }
            }
            case ROW -> {
                if (sy != 1) {
                    throw new MediaException.Unsupported(name, "row interleaving with vertically subsampled chroma");
                }
            }
            case MULTI_Y -> {
                if (sx == 1 || sy != 1) {
                    throw new MediaException.Unsupported(name, "multi-Y interleaving of sampling type "
                            + config.samplingType());
                }
            }
            default -> throw new MediaException.Unsupported(name, "uncompressed interleave type " + interleave);
        }
        allocate(width, height, limits);
        var tileCount = columns * rows;
        if (interleave == TILE_COMPONENT) {
            for (var c = 0; c < types.length; c++) {
                for (var t = 0; t < tileCount; t++) {
                    var start = pos;
                    var x0 = (int) (t % columns) * tileWidth;
                    var y0 = (int) (t / columns) * tileHeight;
                    componentPlane(c, x0, y0, tileWidth, tileHeight);
                    alignTo(start, config.tileAlignSize());
                }
            }
        } else {
            for (var t = 0; t < tileCount; t++) {
                var start = pos;
                var x0 = (int) (t % columns) * tileWidth;
                var y0 = (int) (t / columns) * tileHeight;
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
                alignTo(start, config.tileAlignSize());
            }
        }
        var result = new ArrayList<HeifComponents.Plane>();
        for (var type = 0; type < planes.length; type++) {
            if (planes[type] != null) {
                var chroma = isChroma(type);
                result.add(new HeifComponents.Plane(type, planeWidths[type], planeHeights[type], chroma ? sx : 1,
                        chroma ? sy : 1, planeDepths[type], planes[type]));
            }
        }
        return new HeifComponents(width, height, result);
    }

    /**
     * Allocates the output planes of the decoded component types.
     *
     * @param width the image width
     * @param height the image height
     * @param limits the bound of the allocation
     */
    private void allocate(int width, int height, MediaLimits limits) {
        long total = 0;
        for (var i = 0; i < types.length; i++) {
            var type = types[i];
            if (type < 0 || type >= planes.length || planeDepths[type] != 0) {
                continue;
            }
            var chroma = isChroma(type);
            planeWidths[type] = chroma ? ceil(width, sx) : width;
            planeHeights[type] = chroma ? ceil(height, sy) : height;
            planeDepths[type] = depths[i];
            total += (long) planeWidths[type] * planeHeights[type];
        }
        LimitChecks.checkAllocation(limits, 2 * total);
        for (var type = 0; type < planes.length; type++) {
            if (planeDepths[type] != 0) {
                planes[type] = new short[planeWidths[type] * planeHeights[type]];
            }
        }
    }

    /**
     * {@return whether a component type is a chroma type, subsampled by the sampling type}
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

    /**
     * Reads the plane of one component of one tile.
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
            beginSegment(singles[c], w);
            for (var x = 0; x < w; x++) {
                store(types[c], px + x, py + row, read(c));
            }
            endSegment();
            alignTo(start, config.rowAlignSize());
        }
    }

    /**
     * Reads one tile of pixel-interleaved components.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void pixels(int x0, int y0, int tileWidth, int tileHeight) {
        var count = types.length;
        var pixelSize = config.pixelSize();
        for (var row = 0; row < tileHeight; row++) {
            var rowStart = pos;
            if (pixelSize == 0) {
                beginSegment(depths, (long) tileWidth * count);
            }
            for (var x = 0; x < tileWidth; x++) {
                var pixelStart = pos;
                if (pixelSize != 0) {
                    beginSegment(depths, count);
                }
                for (var c = 0; c < count; c++) {
                    store(types[c], x0 + x, y0 + row, read(c));
                }
                if (pixelSize != 0) {
                    endSegment();
                    padTo(pixelStart, pixelSize, "pixel");
                }
            }
            if (pixelSize == 0) {
                endSegment();
            }
            alignTo(rowStart, config.rowAlignSize());
        }
    }

    /**
     * Reads one tile of planar luma (and any other full-resolution component) and interleaved
     * chroma, in the order of the layout: the chroma plane takes the place of the first chroma
     * component.
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
                beginSegment(chromaDepths, (long) w * chromaDepths.length);
                for (var x = 0; x < w; x++) {
                    for (var component : chromaComponents) {
                        store(types[component], x0 / sx + x, y0 / sy + row, read(component));
                    }
                }
                endSegment();
                alignTo(start, config.rowAlignSize());
            }
        }
    }

    /**
     * Reads one tile of row-interleaved components: for every row, the row of each component.
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
                beginSegment(singles[c], w);
                for (var x = 0; x < w; x++) {
                    store(types[c], px + x, y0 + row, read(c));
                }
                endSegment();
                alignTo(start, config.rowAlignSize());
            }
        }
    }

    /**
     * Reads one tile of multi-Y interleaved components: units of one chroma sample of each chroma
     * component and the luma values that share it, the n-th luma component of the layout giving
     * the n-th luma sample from the left.
     *
     * @param x0 the left edge of the tile
     * @param y0 the top edge of the tile
     * @param tileWidth the tile width
     * @param tileHeight the tile height
     */
    private void multiY(int x0, int y0, int tileWidth, int tileHeight) {
        var lumaIndex = new int[types.length];
        var lumaCount = 0;
        for (var c = 0; c < types.length; c++) {
            var type = types[c];
            if (type == HeifComponents.Y) {
                lumaIndex[c] = lumaCount++;
            } else if (!isChroma(type) && type != PADDED) {
                throw new MediaException.Malformed(name + ": multi-Y interleaving with a component of type " + type);
            }
        }
        if (lumaCount != sx) {
            throw new MediaException.Malformed(name + ": multi-Y units of " + lumaCount + " luma values for "
                    + sx + " luma samples per chroma sample");
        }
        var units = ceil(tileWidth, sx);
        var pixelSize = config.pixelSize();
        for (var row = 0; row < tileHeight; row++) {
            var rowStart = pos;
            if (pixelSize == 0) {
                beginSegment(depths, (long) units * types.length);
            }
            for (var unit = 0; unit < units; unit++) {
                var unitStart = pos;
                if (pixelSize != 0) {
                    beginSegment(depths, types.length);
                }
                for (var c = 0; c < types.length; c++) {
                    var value = read(c);
                    if (types[c] == HeifComponents.Y) {
                        var x = unit * sx + lumaIndex[c];
                        if (x < tileWidth) {
                            store(HeifComponents.Y, x0 + x, y0 + row, value);
                        }
                    } else {
                        store(types[c], x0 / sx + unit, y0 + row, value);
                    }
                }
                if (pixelSize != 0) {
                    endSegment();
                    padTo(unitStart, pixelSize, "multi-Y unit");
                }
            }
            if (pixelSize == 0) {
                endSegment();
            }
            alignTo(rowStart, config.rowAlignSize());
        }
    }

    /**
     * Stores one value in the plane of its type, if that plane is decoded.
     *
     * @param type the component type
     * @param x the column in the plane
     * @param y the row in the plane
     * @param value the unsigned value read
     */
    private void store(int type, int x, int y, int value) {
        if (type < 0 || type >= planes.length) {
            return;
        }
        var plane = planes[type];
        if (plane != null && x < planeWidths[type] && y < planeHeights[type]) {
            plane[y * planeWidths[type] + x] = (short) value;
        }
    }

    /**
     * Starts a run of values that begins on a byte boundary.
     *
     * @param cycle the depths of the values, repeated cyclically
     * @param length the number of values in the run
     */
    private void beginSegment(int[] cycle, long length) {
        segmentDepths = cycle;
        segmentLength = length;
        segmentIndex = 0;
        blockEnd = 0;
    }

    /**
     * Ends a run of values: the next value starts on the next byte boundary.
     */
    private void endSegment() {
        if (bit != 0) {
            bit = 0;
            pos++;
        }
        blockEnd = 0;
    }

    /**
     * Moves past the padding that makes a region a multiple of a size.
     *
     * @param start the byte position of the region
     * @param align the size, or 0 for no padding
     */
    private void alignTo(long start, long align) {
        if (align > 0) {
            var length = pos - start;
            var padded = (length + align - 1) / align * align;
            pos = start + padded;
        }
    }

    /**
     * Moves to the end of a region of a fixed size.
     *
     * @param start the byte position of the region
     * @param size the region size
     * @param what the region, for messages
     */
    private void padTo(long start, long size, String what) {
        if (pos - start > size) {
            throw new MediaException.Malformed(name + ": the components of a " + what + " take " + (pos - start)
                    + " bytes, more than its " + size);
        }
        pos = start + size;
    }

    /**
     * Reads the next value of the current segment.
     *
     * @param c the component of the layout the value belongs to
     * @return the value
     */
    private int read(int c) {
        var depth = depths[c];
        if (config.blockSize() != 0) {
            return readBlock(depth);
        }
        segmentIndex++;
        var align = aligns[c];
        if (align != 0) {
            if (bit != 0) {
                bit = 0;
                pos++;
            }
            var raw = readBytes(align, config.componentsLittleEndian());
            return (int) (raw & ((1L << depth) - 1));
        }
        if (bit == 0 && (depth & 7) == 0) {
            return (int) readBytes(depth >> 3, config.componentsLittleEndian());
        }
        return readBits(depth);
    }

    /**
     * Reads whole bytes as one unsigned integer.
     *
     * @param count the number of bytes, from 1 to 255
     * @param littleEndian whether the least significant byte comes first
     * @return the integer, or its 64 least significant bits for more than 8 bytes
     */
    private long readBytes(int count, boolean littleEndian) {
        require(count);
        long value = 0;
        var at = (int) pos;
        for (var i = 0; i < count; i++) {
            var b = data[at + i] & 0xFFL;
            if (littleEndian) {
                if (i < 8) {
                    value |= b << (8 * i);
                }
            } else {
                value = (value << 8) | b;
            }
        }
        pos += count;
        return value;
    }

    /**
     * Reads bits most significant first.
     *
     * @param count the number of bits, from 1 to 16
     * @return the value
     */
    private int readBits(int count) {
        var value = 0;
        var left = count;
        while (left > 0) {
            require(1);
            var available = 8 - bit;
            var take = Math.min(available, left);
            var current = data[(int) pos] & 0xFF;
            var bits = (current >> (available - take)) & ((1 << take) - 1);
            value = (value << take) | bits;
            left -= take;
            bit += take;
            if (bit == 8) {
                bit = 0;
                pos++;
            }
        }
        return value;
    }

    /**
     * Reads the next value of the segment from its block, starting a block when needed.
     *
     * @param depth the bits of the value
     * @return the value
     */
    private int readBlock(int depth) {
        if (segmentIndex >= blockEnd) {
            startBlock();
        }
        var shift = config.blockReversed() ? blockOffset : blockContent - blockOffset - depth;
        var blockBits = config.blockSize() * 8;
        var contentLsb = config.blockPadLsb() ? blockBits - blockContent : 0;
        blockOffset += depth;
        segmentIndex++;
        return (int) ((block >>> (contentLsb + shift)) & ((1L << depth) - 1));
    }

    /**
     * Reads the next block and determines which values of the segment it holds.
     */
    private void startBlock() {
        if (bit != 0) {
            bit = 0;
            pos++;
        }
        var blockBits = config.blockSize() * 8;
        var used = 0;
        var index = segmentIndex;
        while (index < segmentLength) {
            var depth = segmentDepths[(int) (index % segmentDepths.length)];
            if (used + depth > blockBits) {
                break;
            }
            used += depth;
            index++;
        }
        if (index == segmentIndex) {
            throw new MediaException.Malformed(name + ": a value of " + segmentDepths[(int) (index % segmentDepths.length)]
                    + " bits does not fit a block of " + config.blockSize() + " bytes");
        }
        block = readBytes(config.blockSize(), config.blockLittleEndian());
        blockEnd = index;
        blockContent = used;
        blockOffset = 0;
    }

    /**
     * Checks that bytes remain to be read.
     *
     * @param count the number of bytes needed at the position
     * @throws MediaException.Malformed if the data ends before them
     */
    private void require(int count) {
        if (pos + count > data.length) {
            throw new MediaException.Malformed(name + ": the uncompressed data is shorter than its layout ("
                    + data.length + " bytes)");
        }
    }
}
