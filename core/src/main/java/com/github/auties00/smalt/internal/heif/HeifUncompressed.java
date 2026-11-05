package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.compress.BrotliDecoder;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.Arrays;
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
 *       row, tile-component and multi-Y (for 4:2:2, 4:2:0 and 4:1:1);</li>
 *   <li>4:4:4, 4:2:2, 4:2:0 and 4:1:1 chroma sampling;</li>
 *   <li>values of 1 to 256 bits, packed bit after bit (most significant bit first, or least
 *       significant byte first for values of whole bytes with little-endian components), or aligned
 *       to {@code component_align_size} bytes, with the value in the least significant bits;</li>
 *   <li>unsigned and signed (two's complement) integers, IEEE 754 binary16, binary32, binary64,
 *       binary128 and binary256 floating-point values, and complex values of two such numbers;</li>
 *   <li>blocks of any size in either byte order, with their padding at either end and their values
 *       in either order; a value starts a new block when it does not fit in the current one;</li>
 *   <li>pixel padding ({@code pixel_size}), row padding ({@code row_align_size}), tile padding
 *       ({@code tile_align_size}) and equal tiles;</li>
 *   <li>palette components, whose values index the entries of the item's {@code cpal} property,
 *       and filter array components, sampling the pattern of the item's {@code cpat} property;</li>
 *   <li>item data compressed by the generic compression of ISO/IEC 23001-17 Amendment 2
 *       ({@code cmpC}) with {@code defl}, {@code zlib} or {@code brot}, as one stream or as the
 *       units of an {@code icef} property, decompressed one by one and joined in their order.</li>
 * </ul>
 * Every row of a component plane, every pixel when {@code pixel_size} is set, and every row of
 * pixels starts on a byte boundary. Row interleaving with vertically subsampled chroma gives the row
 * of each chroma component along with every luma row whose index is a multiple of the vertical
 * subsampling factor; multi-Y interleaving of 4:2:0 gives units of the four luma values of a 2x2
 * block, left to right and top to bottom, and its chroma, one row of units for every two luma rows.
 *
 * <p>Samples become unsigned planes of at most 16 bits: unsigned integers of up to 16 bits are kept,
 * signed integers are offset by {@code 2^(n-1)} so that their range maps onto the unsigned one,
 * integers of more than 16 bits are rescaled to 16 bits, {@code round(v * 65535 / (2^n - 1))},
 * floating-point values (binary128 and binary256 ones first rounded to the nearest double) map 0 to
 * 1 linearly onto 0 to 65535, {@code round(v * 65535)} clamped to the range with NaN becoming 0, and
 * complex values contribute their magnitude, mapped the same way.
 * Palette indexes are replaced by their entries, converted the same way. Padding components
 * ({@code cmpd} type 12) are skipped; components other than monochrome, Y'CbCr, RGB, alpha, filter
 * array and CMYK are read and discarded.
 */
public final class HeifUncompressed {
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
     * The {@code cmpd} type of a palette component.
     */
    private static final int PALETTE = 10;

    /**
     * The {@code cmpd} type of a padding component.
     */
    private static final int PADDED = 12;

    /**
     * The {@code component_format} of unsigned integers.
     */
    private static final int UNSIGNED = 0;

    /**
     * The {@code component_format} of IEEE 754 floating-point values.
     */
    private static final int FLOAT = 1;

    /**
     * The {@code component_format} of complex values: two floating-point values, the real part first.
     */
    private static final int COMPLEX = 2;

    /**
     * The {@code component_format} of two's complement signed integers.
     */
    private static final int SIGNED = 3;

    /**
     * The number of plane slots: the component types 0 to 16.
     */
    private static final int TYPES = 17;

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
     * The {@code component_format} of each component of the layout.
     */
    private final int[] formats;

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
    private final short[][] planes = new short[TYPES][];

    /**
     * The width of each output plane.
     */
    private final int[] planeWidths = new int[TYPES];

    /**
     * The height of each output plane.
     */
    private final int[] planeHeights = new int[TYPES];

    /**
     * The bits per value of each output plane, 0 for the types not decoded.
     */
    private final int[] planeDepths = new int[TYPES];

    /**
     * Whether each output plane is at full resolution although its type is chroma, because a
     * palette gives it.
     */
    private final boolean[] fullResolution = new boolean[TYPES];

    /**
     * The component type of each palette component, -1 for those discarded, or {@code null}
     * without a palette.
     */
    private int[] paletteTypes;

    /**
     * The converted palette values, entry after entry.
     */
    private short[] paletteValues;

    /**
     * The number of palette entries.
     */
    private int paletteCount;

    /**
     * The byte position of the reader.
     */
    private long pos;

    /**
     * The bits of the byte at {@link #pos} already read, from 0 to 7.
     */
    private int bit;

    /**
     * The value last read, least significant 64 bits first; bits above its depth are zero.
     */
    private final long[] wide = new long[4];

    /**
     * Scratch values for the rescaling of integers deeper than 64 bits.
     */
    private final long[] scratchA = new long[4];

    /**
     * Scratch values for the rescaling of integers deeper than 64 bits.
     */
    private final long[] scratchB = new long[4];

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
     * The current block of at most 8 bytes, meaningful while {@link #blockEnd} is after the index
     * of the next value.
     */
    private long block;

    /**
     * The current block of more than 8 bytes, as stored.
     */
    private final byte[] blockBytes;

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
        this.formats = new int[types.length];
        this.aligns = new int[types.length];
        this.singles = new int[types.length][];
        var chroma = 0;
        for (var i = 0; i < types.length; i++) {
            depths[i] = config.components().get(i).bitDepth();
            formats[i] = config.components().get(i).format();
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
        this.blockBytes = config.blockSize() > 8 ? new byte[config.blockSize()] : null;
    }

    /**
     * Returns whether an item is an uncompressed image whose components include alpha.
     *
     * @param item the item to test, of any type
     * @return {@code true} for an {@code unci} item with an alpha component, or with a palette
     * component whose palette gives alpha
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
        var palette = item.property(HeifProperty.ComponentPalette.class);
        for (var component : config.components()) {
            var type = config.shorthand() ? component.index() : typeOf(definition, component.index());
            if (type == HeifComponents.ALPHA) {
                return true;
            }
            if (type == PALETTE && palette != null) {
                for (var entry : palette.components()) {
                    if (typeOf(definition, entry.index()) == HeifComponents.ALPHA) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@return the component type a {@code cmpd} entry defines, or -1 when there is no such entry}
     *
     * @param definition the component definition, or {@code null} when the item has none
     * @param index the index of the entry
     */
    private static int typeOf(HeifProperty.ComponentDefinition definition, int index) {
        return definition != null && index < definition.types().length ? definition.types()[index] : -1;
    }

    /**
     * Decodes an uncompressed image item.
     *
     * @param name the format name, for messages, such as {@code "heic"}
     * @param payload the item data as stored
     * @param item the item, with its {@code uncC}, {@code cmpd} and optional {@code cpal},
     * {@code cpat}, {@code cmpC} and {@code icef} properties
     * @param width the image width, from the item's {@code ispe} property
     * @param height the image height, from the item's {@code ispe} property
     * @param limits the bounds of the planes and of the decompressed data
     * @return the decoded components
     * @throws MediaException.Malformed if a property is missing or inconsistent, the data is too
     * short for the layout, or a palette index has no entry
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
            checkFormat(name, "uncC component " + i, component.format(), component.bitDepth());
            if (types[i] == PALETTE && component.format() != UNSIGNED) {
                throw new MediaException.Malformed(name + ": palette component " + i + " of format "
                        + component.format());
            }
            if (component.alignSize() != 0 && component.alignSize() * 8 < component.bitDepth()) {
                throw new MediaException.Malformed(name + ": uncC component " + i + " of " + component.bitDepth()
                        + " bits is aligned to " + component.alignSize() + " bytes");
            }
            if (types[i] == HeifComponents.FILTER_ARRAY) {
                checkPattern(name, item, definition);
            }
        }
        var data = decompress(name, payload, item, limits);
        var decoder = new HeifUncompressed(name, data, config, types);
        decoder.palette(item, definition, limits);
        return decoder.run(width, height, limits);
    }

    /**
     * Checks that a component format and depth form a value this decoder reads.
     *
     * @param name the format name, for messages
     * @param what the component, for messages
     * @param format the {@code component_format}
     * @param depth the bits per value
     * @throws MediaException.Malformed if a floating-point or complex depth is not one of those
     * IEEE 754 defines
     * @throws MediaException.Unsupported if the format is reserved
     */
    private static void checkFormat(String name, String what, int format, int depth) {
        switch (format) {
            case UNSIGNED, SIGNED -> {
            }
            case FLOAT -> {
                if (depth != 16 && depth != 32 && depth != 64 && depth != 128 && depth != 256) {
                    throw new MediaException.Malformed(name + ": " + what + " is a floating-point value of "
                            + depth + " bits");
                }
            }
            case COMPLEX -> {
                if (depth != 32 && depth != 64 && depth != 128 && depth != 256) {
                    throw new MediaException.Malformed(name + ": " + what + " is a complex value of " + depth + " bits");
                }
            }
            default -> throw new MediaException.Unsupported(name, "uncompressed components of format " + format);
        }
    }

    /**
     * Checks the pattern a filter array component samples.
     *
     * @param name the format name, for messages
     * @param item the filter array item, with its {@code cpat} property
     * @param definition the component definition the pattern refers to
     * @throws MediaException.Malformed if the item has no {@code cpat} property or the pattern names
     * a component the definition lacks
     */
    private static void checkPattern(String name, HeifItem item, HeifProperty.ComponentDefinition definition) {
        var pattern = item.property(HeifProperty.ComponentPattern.class);
        if (pattern == null) {
            throw new MediaException.Malformed(name + ": filter array item " + item.id() + " has no cpat property");
        }
        for (var index : pattern.components()) {
            if (index >= definition.types().length) {
                throw new MediaException.Malformed(name + ": cpat names cmpd entry " + index + " of "
                        + definition.types().length);
            }
        }
    }

    /**
     * Undoes the generic compression of the item data, if any.
     *
     * @param name the format name, for messages
     * @param payload the item data as stored
     * @param item the uncompressed item, with its optional {@code cmpC} and {@code icef} properties
     * @param limits the bound of the decompressed size
     * @return the decompressed data
     */
    private static byte[] decompress(String name, byte[] payload, HeifItem item, MediaLimits limits) {
        var compression = item.property(HeifProperty.CompressionConfiguration.class);
        if (compression == null) {
            return payload;
        }
        var kind = compression.compressionType();
        if (!kind.equals("defl") && !kind.equals("zlib") && !kind.equals("brot")) {
            throw new MediaException.Unsupported(name, "uncompressed data compressed with '" + kind + "'");
        }
        if (compression.unitType() > 4) {
            throw new MediaException.Unsupported(name, "uncompressed data compressed in units of type "
                    + compression.unitType());
        }
        var units = item.property(HeifProperty.CompressedUnits.class);
        if (units == null) {
            if (compression.unitType() > 1) {
                throw new MediaException.Malformed(name + ": item " + item.id() + " is compressed in units of type "
                        + compression.unitType() + " without an icef property");
            }
            return decompress(name, kind, payload, 0, payload.length, item, limits.maxBytes());
        }
        var parts = new byte[units.offsets().length][];
        var total = 0L;
        for (var i = 0; i < parts.length; i++) {
            var offset = units.offsets()[i];
            var size = units.sizes()[i];
            if (offset > payload.length || size > payload.length - offset) {
                throw new MediaException.Malformed(name + ": compressed unit " + i + " of item " + item.id()
                        + " lies outside its " + payload.length + " bytes of data");
            }
            parts[i] = decompress(name, kind, payload, (int) offset, (int) size, item, limits.maxBytes() - total);
            total += parts[i].length;
        }
        LimitChecks.checkAllocation(limits, total);
        var out = new byte[(int) total];
        var at = 0;
        for (var part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /**
     * Decompresses one compressed stream.
     *
     * @param name the format name, for messages
     * @param kind the compression type: {@code defl}, {@code zlib} or {@code brot}
     * @param payload the item data
     * @param offset the start of the stream in the data
     * @param length the length of the stream
     * @param item the item, for messages
     * @param maxBytes the largest output accepted
     * @return the decompressed bytes
     */
    private static byte[] decompress(String name, String kind, byte[] payload, int offset, int length, HeifItem item,
                                     long maxBytes) {
        if (kind.equals("brot")) {
            return BrotliDecoder.decompress(payload, offset, length, Math.max(0, maxBytes));
        }
        var inflater = new Inflater(kind.equals("defl"));
        try {
            inflater.setInput(payload, offset, length);
            var limit = Math.min(maxBytes, Integer.MAX_VALUE - 64);
            var out = new byte[(int) Math.max(1024, Math.min(length * 4L, Math.min(1 << 24, limit)))];
            var size = 0;
            while (!inflater.finished()) {
                if (size == out.length) {
                    if (out.length >= limit) {
                        throw new MediaException.LimitExceeded(name + ": the compressed data of item " + item.id()
                                + " inflates past " + maxBytes + " bytes");
                    }
                    out = Arrays.copyOf(out, (int) Math.min(2L * out.length, limit));
                }
                var count = inflater.inflate(out, size, out.length - size);
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new MediaException.Malformed(name + ": the compressed data of item " + item.id()
                            + " is truncated");
                }
                size += count;
            }
            if (size > maxBytes) {
                throw new MediaException.LimitExceeded(name + ": the compressed data of item " + item.id()
                        + " inflates past " + maxBytes + " bytes");
            }
            return Arrays.copyOf(out, size);
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed(name + ": the compressed data of item " + item.id() + " is corrupt",
                    exception);
        } finally {
            inflater.end();
        }
    }

    /**
     * Reads the palette of the item, when a component of the layout indexes one.
     *
     * @param item the item, with its {@code cpal} property
     * @param definition the component definition the palette refers to
     * @param limits the bound of the converted palette
     */
    private void palette(HeifItem item, HeifProperty.ComponentDefinition definition, MediaLimits limits) {
        var indexed = false;
        for (var type : types) {
            indexed |= type == PALETTE;
        }
        if (!indexed) {
            return;
        }
        var palette = item.property(HeifProperty.ComponentPalette.class);
        if (palette == null) {
            throw new MediaException.Malformed(name + ": palette item " + item.id() + " has no cpal property");
        }
        var components = palette.components();
        var count = components.size();
        paletteTypes = new int[count];
        var paletteDepths = new int[count];
        for (var j = 0; j < count; j++) {
            var component = components.get(j);
            checkFormat(name, "cpal component " + j, component.format(), component.bitDepth());
            if (component.index() >= definition.types().length) {
                throw new MediaException.Malformed(name + ": cpal component " + j + " names cmpd entry "
                        + component.index() + " of " + definition.types().length);
            }
            var type = definition.types()[component.index()];
            if (type == PALETTE || type == HeifComponents.FILTER_ARRAY) {
                throw new MediaException.Malformed(name + ": cpal component " + j + " is of type " + type);
            }
            if (isChroma(type) && config.samplingType() != 0) {
                throw new MediaException.Malformed(name + ": a palette gives chroma of a subsampled image");
            }
            paletteTypes[j] = decoded(type) ? type : -1;
            paletteDepths[j] = outputDepth(component.format(), component.bitDepth());
        }
        LimitChecks.checkAllocation(limits, 2L * palette.count() * count);
        paletteCount = palette.count();
        paletteValues = new short[paletteCount * count];
        var values = palette.values();
        var at = 0;
        for (var e = 0; e < paletteCount; e++) {
            for (var j = 0; j < count; j++) {
                var component = components.get(j);
                var bytes = component.alignSize();
                Arrays.fill(wide, 0);
                for (var k = 0; k < bytes; k++) {
                    var shift = 8 * (bytes - 1 - k);
                    wide[shift >> 6] |= (values[at + k] & 0xFFL) << (shift & 63);
                }
                at += bytes;
                mask(component.bitDepth());
                paletteValues[e * count + j] = (short) sample(component.format(), component.bitDepth());
            }
        }
        for (var j = 0; j < count; j++) {
            var type = paletteTypes[j];
            if (type >= 0) {
                fullResolution[type] = true;
                if (planeDepths[type] == 0) {
                    planeDepths[type] = paletteDepths[j];
                }
            }
        }
    }

    /**
     * {@return whether the samples of a component type make an output plane}
     *
     * @param type the component type
     */
    private static boolean decoded(int type) {
        return type >= 0 && type < TYPES && type != 8 && type != 9 && type != PALETTE && type != PADDED;
    }

    /**
     * {@return the depth of the plane a component's values become: their own for integers of up to
     * 16 bits, 16 otherwise}
     *
     * @param format the {@code component_format}
     * @param depth the bits per value
     */
    private static int outputDepth(int format, int depth) {
        return (format == UNSIGNED || format == SIGNED) && depth <= 16 ? depth : 16;
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
            case COMPONENT, TILE_COMPONENT, ROW -> {
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
            case MULTI_Y -> {
                if (sx == 1) {
                    throw new MediaException.Malformed(name + ": multi-Y interleaving without subsampled chroma");
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
                var chroma = subsampled(type);
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
        for (var i = 0; i < types.length; i++) {
            var type = types[i];
            if (decoded(type) && fullResolution[type]) {
                throw new MediaException.Malformed(name + ": both a palette and component " + i + " give components of "
                        + "type " + type);
            }
            if (decoded(type) && planeDepths[type] == 0) {
                planeDepths[type] = outputDepth(formats[i], depths[i]);
            }
        }
        long total = 0;
        for (var type = 0; type < TYPES; type++) {
            if (planeDepths[type] != 0) {
                var chroma = subsampled(type);
                planeWidths[type] = chroma ? ceil(width, sx) : width;
                planeHeights[type] = chroma ? ceil(height, sy) : height;
                total += (long) planeWidths[type] * planeHeights[type];
            }
        }
        LimitChecks.checkAllocation(limits, 2 * total);
        for (var type = 0; type < TYPES; type++) {
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
     * {@return whether the output plane of a component type is subsampled}
     *
     * @param type the component type
     */
    private boolean subsampled(int type) {
        return isChroma(type) && !fullResolution[type];
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
                readStore(c, px + x, py + row);
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
                    readStore(c, x0 + x, y0 + row);
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
                        readStore(component, x0 / sx + x, y0 / sy + row);
                    }
                }
                endSegment();
                alignTo(start, config.rowAlignSize());
            }
        }
    }

    /**
     * Reads one tile of row-interleaved components: for every luma row, the row of each component,
     * chroma rows only along with the luma rows whose index is a multiple of the vertical
     * subsampling factor.
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
                if (chroma && row % sy != 0) {
                    continue;
                }
                var w = chroma ? ceil(tileWidth, sx) : tileWidth;
                var px = chroma ? x0 / sx : x0;
                var py = chroma ? (y0 + row) / sy : y0 + row;
                var start = pos;
                beginSegment(singles[c], w);
                for (var x = 0; x < w; x++) {
                    readStore(c, px + x, py);
                }
                endSegment();
                alignTo(start, config.rowAlignSize());
            }
        }
    }

    /**
     * Reads one tile of multi-Y interleaved components: units of one chroma sample of each chroma
     * component and the luma values that share it, the n-th luma component of the layout giving
     * the n-th luma sample of the unit's block, left to right and top to bottom.
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
        if (lumaCount != sx * sy) {
            throw new MediaException.Malformed(name + ": multi-Y units of " + lumaCount + " luma values for "
                    + sx * sy + " luma samples per chroma sample");
        }
        var units = ceil(tileWidth, sx);
        var unitRows = ceil(tileHeight, sy);
        var pixelSize = config.pixelSize();
        for (var unitRow = 0; unitRow < unitRows; unitRow++) {
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
                    if (types[c] == HeifComponents.Y) {
                        var x = unit * sx + lumaIndex[c] % sx;
                        var y = unitRow * sy + lumaIndex[c] / sx;
                        if (x < tileWidth && y < tileHeight) {
                            readStore(c, x0 + x, y0 + y);
                        } else {
                            readStore(c, -1, -1);
                        }
                    } else {
                        readStore(c, x0 / sx + unit, y0 / sy + unitRow);
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
     * Reads the next value of a component and stores it, or the palette entry it indexes, in the
     * planes of their types.
     *
     * @param c the component of the layout
     * @param x the column in the component's plane, or -1 to discard the value
     * @param y the row in the component's plane
     */
    private void readStore(int c, int x, int y) {
        var type = types[c];
        readRaw(c);
        if (x < 0) {
            return;
        }
        if (type == PALETTE) {
            var index = wide[0];
            if (index < 0 || index >= paletteCount || (wide[1] | wide[2] | wide[3]) != 0) {
                throw new MediaException.Malformed(name + ": palette index " + Long.toUnsignedString(index)
                        + " of " + paletteCount + " entries");
            }
            var count = paletteTypes.length;
            var base = (int) index * count;
            for (var j = 0; j < count; j++) {
                if (paletteTypes[j] >= 0) {
                    store(paletteTypes[j], x, y, paletteValues[base + j]);
                }
            }
            return;
        }
        if (decoded(type)) {
            store(type, x, y, (short) sample(formats[c], depths[c]));
        }
    }

    /**
     * Stores one value in the plane of its type.
     *
     * @param type the component type, of a decoded plane
     * @param x the column in the plane
     * @param y the row in the plane
     * @param value the sample
     */
    private void store(int type, int x, int y, short value) {
        var plane = planes[type];
        if (x < planeWidths[type] && y < planeHeights[type]) {
            plane[y * planeWidths[type] + x] = value;
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
     * Reads the next value of the current segment into {@link #wide}.
     *
     * @param c the component of the layout the value belongs to
     */
    private void readRaw(int c) {
        var depth = depths[c];
        if (config.blockSize() != 0) {
            readBlock(depth);
            return;
        }
        segmentIndex++;
        var align = aligns[c];
        if (align != 0) {
            if (bit != 0) {
                bit = 0;
                pos++;
            }
            readBytes(align, config.componentsLittleEndian());
            mask(depth);
        } else if (bit == 0 && (depth & 7) == 0) {
            readBytes(depth >> 3, config.componentsLittleEndian());
        } else {
            readBits(depth);
        }
    }

    /**
     * Reads whole bytes as one unsigned integer into {@link #wide}.
     *
     * @param count the number of bytes, from 1 to 255
     * @param littleEndian whether the least significant byte comes first
     * @return the 64 least significant bits of the integer
     */
    private long readBytes(int count, boolean littleEndian) {
        require(count);
        var at = (int) pos;
        pos += count;
        if (count <= 8) {
            long value = 0;
            for (var i = 0; i < count; i++) {
                var b = data[at + i] & 0xFFL;
                value = littleEndian ? value | b << (8 * i) : (value << 8) | b;
            }
            wide[0] = value;
            wide[1] = 0;
            wide[2] = 0;
            wide[3] = 0;
            return value;
        }
        Arrays.fill(wide, 0);
        // byte j counted from the least significant end holds bits 8j to 8j + 7; bytes beyond the
        // 32 that hold 256 bits are padding of an aligned value
        for (var j = 0; j < Math.min(count, 32); j++) {
            var b = data[littleEndian ? at + j : at + count - 1 - j] & 0xFFL;
            wide[j >> 3] |= b << (8 * (j & 7));
        }
        return wide[0];
    }

    /**
     * Reads bits most significant first into {@link #wide}.
     *
     * @param count the number of bits, from 1 to 256
     */
    private void readBits(int count) {
        if (count <= 64) {
            long value = 0;
            var left = count;
            while (left > 0) {
                require(1);
                var available = 8 - bit;
                var take = Math.min(available, left);
                var current = data[(int) pos] & 0xFF;
                value = (value << take) | ((current >> (available - take)) & ((1 << take) - 1));
                left -= take;
                bit += take;
                if (bit == 8) {
                    bit = 0;
                    pos++;
                }
            }
            wide[0] = value;
            wide[1] = 0;
            wide[2] = 0;
            wide[3] = 0;
            return;
        }
        Arrays.fill(wide, 0);
        var left = count;
        while (left > 0) {
            require(1);
            var available = 8 - bit;
            var take = Math.min(available, left);
            var current = data[(int) pos] & 0xFF;
            shiftIn((current >> (available - take)) & ((1 << take) - 1), take);
            left -= take;
            bit += take;
            if (bit == 8) {
                bit = 0;
                pos++;
            }
        }
    }

    /**
     * Shifts {@link #wide} left and appends bits below it.
     *
     * @param bits the bits to append
     * @param count the number of bits, from 1 to 8
     */
    private void shiftIn(long bits, int count) {
        for (var i = 3; i > 0; i--) {
            wide[i] = (wide[i] << count) | (wide[i - 1] >>> (64 - count));
        }
        wide[0] = (wide[0] << count) | bits;
    }

    /**
     * Clears the bits of {@link #wide} at and above a depth.
     *
     * @param depth the bits to keep, from 1 to 256
     */
    private void mask(int depth) {
        for (var i = 0; i < 4; i++) {
            var low = 64 * i;
            if (depth <= low) {
                wide[i] = 0;
            } else if (depth < low + 64) {
                wide[i] &= (1L << (depth - low)) - 1;
            }
        }
    }

    /**
     * Reads the next value of the segment from its block into {@link #wide}, starting a block when
     * needed.
     *
     * @param depth the bits of the value
     */
    private void readBlock(int depth) {
        if (segmentIndex >= blockEnd) {
            startBlock();
        }
        var shift = config.blockReversed() ? blockOffset : blockContent - blockOffset - depth;
        var blockBits = config.blockSize() * 8;
        var contentLsb = config.blockPadLsb() ? blockBits - blockContent : 0;
        blockOffset += depth;
        segmentIndex++;
        var lsb = contentLsb + shift;
        if (blockBytes == null) {
            wide[0] = (block >>> lsb) & (depth == 64 ? -1L : (1L << depth) - 1);
            wide[1] = 0;
            wide[2] = 0;
            wide[3] = 0;
            return;
        }
        Arrays.fill(wide, 0);
        var littleEndian = config.blockLittleEndian();
        var size = blockBytes.length;
        var p = lsb + depth - 1;
        while (p >= lsb) {
            var low = Math.max(lsb, p & ~7);
            var count = p - low + 1;
            var index = p >> 3;
            var b = blockBytes[littleEndian ? index : size - 1 - index] & 0xFF;
            shiftIn((b >> (low & 7)) & ((1 << count) - 1), count);
            p = low - 1;
        }
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
        if (blockBytes == null) {
            block = readBytes(config.blockSize(), config.blockLittleEndian());
        } else {
            require(blockBytes.length);
            System.arraycopy(data, (int) pos, blockBytes, 0, blockBytes.length);
            pos += blockBytes.length;
        }
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

    /**
     * Converts the value in {@link #wide} to an unsigned sample of the depth
     * {@link #outputDepth(int, int)} gives, as the class describes.
     *
     * @param format the {@code component_format}
     * @param depth the bits of the value
     * @return the sample
     */
    private int sample(int format, int depth) {
        return switch (format) {
            case UNSIGNED -> depth <= 16 ? (int) wide[0] : rescale(depth);
            case SIGNED -> {
                wide[(depth - 1) >> 6] ^= 1L << ((depth - 1) & 63);
                yield depth <= 16 ? (int) wide[0] : rescale(depth);
            }
            case FLOAT -> quantize(floatAt(0, depth));
            default -> quantize(StrictMath.hypot(floatAt(depth / 2, depth / 2), floatAt(0, depth / 2)));
        };
    }

    /**
     * {@return a value of {@code round(v * 65535)} clamped to 0 to 65535, rounding halves up, and 0
     * for NaN}
     *
     * @param value the floating-point value
     */
    private static int quantize(double value) {
        var scaled = value * 65535.0;
        if (!(scaled > 0)) {
            return 0;
        }
        if (scaled >= 65535) {
            return 65535;
        }
        // the product is scaled + error exactly; the fraction of scaled is exact, and a nonzero
        // distance from one half is at least an ulp, more than the error, which decides only a tie
        var error = Math.fma(value, 65535.0, -scaled);
        var whole = Math.floor(scaled);
        var half = scaled - whole - 0.5;
        return (int) whole + (half > 0 || (half == 0 && error >= 0) ? 1 : 0);
    }

    /**
     * Returns bits of {@link #wide}.
     *
     * @param from the first bit, counted from the least significant
     * @param count the number of bits, from 1 to 64
     * @return the bits
     */
    private long bits(int from, int count) {
        var limb = from >> 6;
        var shift = from & 63;
        var value = wide[limb] >>> shift;
        if (shift != 0 && limb < 3) {
            value |= wide[limb + 1] << (64 - shift);
        }
        return count == 64 ? value : value & ((1L << count) - 1);
    }

    /**
     * Decodes an IEEE 754 binary interchange value held in {@link #wide}.
     *
     * @param from the first bit of the value, counted from the least significant
     * @param width the width of the value: 16, 32, 64, 128 or 256
     * @return the value, rounded to the nearest double
     */
    private double floatAt(int from, int width) {
        return switch (width) {
            case 16 -> Float.float16ToFloat((short) bits(from, 16));
            case 32 -> Float.intBitsToFloat((int) bits(from, 32));
            case 64 -> Double.longBitsToDouble(bits(from, 64));
            default -> {
                var exponentBits = width == 128 ? 15 : 19;
                var mantissaBits = width - 1 - exponentBits;
                var negative = bits(from + width - 1, 1) != 0;
                var exponent = (int) bits(from + mantissaBits, exponentBits);
                var bias = (1 << (exponentBits - 1)) - 1;
                double magnitude;
                if (exponent == (1 << exponentBits) - 1) {
                    var nan = false;
                    for (var at = 0; at < mantissaBits && !nan; at += 64) {
                        nan = bits(from + at, Math.min(64, mantissaBits - at)) != 0;
                    }
                    magnitude = nan ? Double.NaN : Double.POSITIVE_INFINITY;
                } else if (exponent == 0) {
                    // subnormals of binary128 and binary256 are far below the smallest double
                    magnitude = 0;
                } else {
                    var significand = (1L << 63) | bits(from + mantissaBits - 63, 63);
                    var sticky = false;
                    for (var at = 0; at < mantissaBits - 63 && !sticky; at += 64) {
                        sticky = bits(from + at, Math.min(64, mantissaBits - 63 - at)) != 0;
                    }
                    if (sticky) {
                        significand |= 1;
                    }
                    // the low bit keeps the rounding of the unsigned conversion exact
                    var halved = (double) ((significand >>> 1) | (significand & 1));
                    magnitude = Math.scalb(halved, exponent - bias - 62);
                }
                yield negative ? -magnitude : magnitude;
            }
        };
    }

    /**
     * Rescales the unsigned integer in {@link #wide} to 16 bits, {@code round(v * 65535 / (2^n - 1))}.
     *
     * <p>The quotient {@code v / (2^n - 1)} is the binary fraction that repeats the {@code n} bits of
     * {@code v}, so {@code 65536 v / (2^n - 1)} has the 16 most significant bits of {@code v} as its
     * integer part and the repetition of {@code v} rotated left by 16 bits as its fraction {@code f}.
     * The result is those 16 bits, plus one when {@code f - x + 1/2 >= 1} and minus one when it is
     * below 0, for {@code x = v / (2^n - 1)}; both comparisons are between repeating fractions,
     * decided by their first two periods.
     *
     * @param n the bits of the value, from 17 to 256
     * @return the sample
     */
    private int rescale(int n) {
        if (n <= 64) {
            var all = n == 64 ? -1L : (1L << n) - 1;
            var v = wide[0];
            if (v == all) {
                return 65535;
            }
            var top = (int) (v >>> (n - 16));
            var rotated = ((v << 16) | (v >>> (n - 16))) & all;
            var high = 1L << (n - 1);
            if ((v & high) == 0) {
                var order = Long.compareUnsigned(rotated, v | high);
                if (order > 0 || (order == 0 && Long.compareUnsigned(rotated, v) >= 0)) {
                    return top + 1;
                }
            } else if ((rotated & high) == 0) {
                var order = Long.compareUnsigned(rotated | high, v);
                if (order < 0 || (order == 0 && Long.compareUnsigned(rotated, v) < 0)) {
                    return top - 1;
                }
            }
            return top;
        }
        var allOnes = true;
        for (var at = 0; at < n && allOnes; at += 64) {
            var count = Math.min(64, n - at);
            allOnes = bits(at, count) == (count == 64 ? -1L : (1L << count) - 1);
        }
        if (allOnes) {
            return 65535;
        }
        var top = (int) bits(n - 16, 16);
        var rotated = scratchA;
        for (var i = 3; i > 0; i--) {
            rotated[i] = (wide[i] << 16) | (wide[i - 1] >>> 48);
        }
        rotated[0] = (wide[0] << 16) | top;
        for (var i = 0; i < 4; i++) {
            var low = 64 * i;
            if (n <= low) {
                rotated[i] = 0;
            } else if (n < low + 64) {
                rotated[i] &= (1L << (n - low)) - 1;
            }
        }
        var highLimb = (n - 1) >> 6;
        var highBit = 1L << ((n - 1) & 63);
        var other = scratchB;
        if ((wide[highLimb] & highBit) == 0) {
            System.arraycopy(wide, 0, other, 0, 4);
            other[highLimb] |= highBit;
            var order = compare(rotated, other);
            if (order > 0 || (order == 0 && compare(rotated, wide) >= 0)) {
                return top + 1;
            }
        } else if ((rotated[highLimb] & highBit) == 0) {
            System.arraycopy(rotated, 0, other, 0, 4);
            other[highLimb] |= highBit;
            var order = compare(other, wide);
            if (order < 0 || (order == 0 && compare(rotated, wide) < 0)) {
                return top - 1;
            }
        }
        return top;
    }

    /**
     * Compares two unsigned 256-bit integers.
     *
     * @param a the first integer, least significant 64 bits first
     * @param b the second integer, least significant 64 bits first
     * @return a negative value, zero or a positive value as {@code a} is below, equal to or above
     * {@code b}
     */
    private static int compare(long[] a, long[] b) {
        for (var i = 3; i >= 0; i--) {
            var order = Long.compareUnsigned(a[i], b[i]);
            if (order != 0) {
                return order;
            }
        }
        return 0;
    }
}
