package com.github.auties00.smalt.testkit;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes uncompressed HEIF image items ({@code unci}, ISO/IEC 23001-17) of any layout from planes of raw values,
 * independently of the decoder: the {@code uncC}, {@code cmpd}, {@code cpal}, {@code cpat}, {@code cmpC} and
 * {@code icef} properties and the item data of every interleaving, sampling, packing, block and padding combination.
 *
 * <p>Planes are keyed by component type and hold one raw value per sample, of the bits of its component (a palette
 * plane holds the indexes); chroma planes are at the chroma resolution. Padding bits are written as ones and padding
 * bytes as {@code 0xA5}, so a reader that reads them instead of skipping them fails.
 */
public final class UnciWriter {
    public static final int MONO = 0;
    public static final int Y = 1;
    public static final int CB = 2;
    public static final int CR = 3;
    public static final int R = 4;
    public static final int G = 5;
    public static final int B = 6;
    public static final int A = 7;
    public static final int DEPTH = 8;
    public static final int PALETTE = 10;
    public static final int FILTER_ARRAY = 11;
    public static final int PADDED = 12;
    public static final int CYAN = 13;
    public static final int MAGENTA = 14;
    public static final int YELLOW = 15;
    public static final int BLACK = 16;

    public static final int UNSIGNED = 0;
    public static final int FLOAT = 1;
    public static final int COMPLEX = 2;
    public static final int SIGNED = 3;

    /** One uncC component: its component type, bits, {@code component_format} and alignment in bytes. */
    public record Component(int type, int depth, int format, int align) {
        public static Component of(int type, int depth) {
            return new Component(type, depth, UNSIGNED, 0);
        }
    }

    /** The fields of a version 0 uncC box after its components. */
    public record Layout(int sampling, int interleave, int blockSize, boolean componentsLe, boolean padLsb,
                         boolean blockLe, boolean reversed, long pixelSize, long rowAlign, long tileAlign, int columns,
                         int rows) {
        public static Layout of(int sampling, int interleave) {
            return new Layout(sampling, interleave, 0, false, false, false, false, 0, 0, 0, 1, 1);
        }

        public Layout blocks(int size, boolean padLsb, boolean littleEndian, boolean reversed) {
            return new Layout(sampling, interleave, size, componentsLe, padLsb, littleEndian, reversed, pixelSize,
                    rowAlign, tileAlign, columns, rows);
        }

        public Layout littleEndian(boolean littleEndian) {
            return new Layout(sampling, interleave, blockSize, littleEndian, padLsb, blockLe, reversed, pixelSize,
                    rowAlign, tileAlign, columns, rows);
        }

        public Layout pixelSize(long size) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, size, rowAlign,
                    tileAlign, columns, rows);
        }

        public Layout rowAlign(long size) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, pixelSize,
                    size, tileAlign, columns, rows);
        }

        public Layout tiles(int columns, int rows, long tileAlign) {
            return new Layout(sampling, interleave, blockSize, componentsLe, padLsb, blockLe, reversed, pixelSize,
                    rowAlign, tileAlign, columns, rows);
        }

        /** {@return the horizontal chroma subsampling factor} */
        public int sx() {
            return sampling == 3 ? 4 : sampling == 1 || sampling == 2 ? 2 : 1;
        }

        /** {@return the vertical chroma subsampling factor} */
        public int sy() {
            return sampling == 2 ? 2 : 1;
        }
    }

    /** One palette component: its component type, bits and {@code component_format}. */
    public record PaletteComponent(int type, int depth, int format) {
    }

    /**
     * A whole item.
     *
     * @param width the image width
     * @param height the image height
     * @param components the uncC components in storage order
     * @param layout the layout
     * @param planes the raw values by component type
     */
    public record Image(int width, int height, List<Component> components, Layout layout,
                        Map<Integer, BigInteger[]> planes) {
    }

    private UnciWriter() {
        throw new AssertionError();
    }

    /** {@return whether a type is chroma} */
    public static boolean isChroma(int type) {
        return type == CB || type == CR;
    }

    /** {@return the width of the plane of a type} */
    public static int planeWidth(Image image, int type) {
        return isChroma(type) ? ceil(image.width(), image.layout().sx()) : image.width();
    }

    /** {@return the height of the plane of a type} */
    public static int planeHeight(Image image, int type) {
        return isChroma(type) ? ceil(image.height(), image.layout().sy()) : image.height();
    }

    private static int ceil(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /**
     * {@return the component definition: the distinct types of the components in storage order, then the extra
     * types, each once}
     */
    public static int[] definition(List<Component> components, int... extra) {
        var types = new ArrayList<Integer>();
        for (var component : components) {
            if (!types.contains(component.type())) {
                types.add(component.type());
            }
        }
        for (var type : extra) {
            if (!types.contains(type)) {
                types.add(type);
            }
        }
        return types.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int indexOf(int[] definition, int type) {
        for (var i = 0; i < definition.length; i++) {
            if (definition[i] == type) {
                return i;
            }
        }
        throw new IllegalArgumentException("type " + type + " is not defined");
    }

    /** Builds a version 0 uncC box whose components index the given definition. */
    public static byte[] uncC(List<Component> components, Layout layout, int[] definition) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, 0);
        HeifBuilder.u32(out, components.size());
        for (var component : components) {
            HeifBuilder.u16(out, indexOf(definition, component.type()));
            out.write(component.depth() - 1);
            out.write(component.format());
            out.write(component.align());
        }
        out.write(layout.sampling());
        out.write(layout.interleave());
        out.write(layout.blockSize());
        out.write((layout.componentsLe() ? 0x80 : 0) | (layout.padLsb() ? 0x40 : 0) | (layout.blockLe() ? 0x20 : 0)
                | (layout.reversed() ? 0x10 : 0));
        HeifBuilder.u32(out, layout.pixelSize());
        HeifBuilder.u32(out, layout.rowAlign());
        HeifBuilder.u32(out, layout.tileAlign());
        HeifBuilder.u32(out, layout.columns() - 1);
        HeifBuilder.u32(out, layout.rows() - 1);
        return HeifBuilder.fullBox("uncC", 0, 0, out.toByteArray());
    }

    /** Builds a cmpd box. */
    public static byte[] cmpd(int... types) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u32(out, types.length);
        for (var type : types) {
            HeifBuilder.u16(out, type);
            if (type >= 0x8000) {
                out.writeBytes("urn:example:component".getBytes(StandardCharsets.US_ASCII));
                out.write(0);
            }
        }
        return HeifBuilder.box("cmpd", out.toByteArray());
    }

    /** {@return the bytes a cpal value of a depth takes: the smallest of 1, 2, 4, 8, 16 and 32 that holds it} */
    public static int paletteBytes(int depth) {
        return depth <= 8 ? 1 : depth <= 16 ? 2 : depth <= 32 ? 4 : depth <= 64 ? 8 : depth <= 128 ? 16 : 32;
    }

    /**
     * Builds a cpal box.
     *
     * @param components the palette components
     * @param definition the component definition they index
     * @param entries the raw values of each entry, one per component
     */
    public static byte[] cpal(List<PaletteComponent> components, int[] definition, BigInteger[][] entries) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u16(out, components.size());
        for (var component : components) {
            HeifBuilder.u16(out, indexOf(definition, component.type()));
            out.write(component.depth() - 1);
            out.write(component.format());
        }
        HeifBuilder.u32(out, entries.length);
        for (var entry : entries) {
            for (var j = 0; j < components.size(); j++) {
                var bytes = paletteBytes(components.get(j).depth());
                writeBytes(out, entry[j], bytes, false);
            }
        }
        return HeifBuilder.fullBox("cpal", 0, 0, out.toByteArray());
    }

    /** Builds a cpat box of a pattern of component types. */
    public static byte[] cpat(int width, int height, int[] types, float[] gains, int[] definition) {
        var out = new ByteArrayOutputStream();
        HeifBuilder.u16(out, width);
        HeifBuilder.u16(out, height);
        for (var i = 0; i < width * height; i++) {
            HeifBuilder.u32(out, indexOf(definition, types[i]));
            HeifBuilder.u32(out, Float.floatToRawIntBits(gains[i]) & 0xFFFFFFFFL);
        }
        return HeifBuilder.fullBox("cpat", 0, 0, out.toByteArray());
    }

    /** Builds a cmpC box. */
    public static byte[] cmpC(String type, int unitType) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.write(unitType);
        return HeifBuilder.fullBox("cmpC", 0, 0, out.toByteArray());
    }

    /**
     * Builds an icef box.
     *
     * @param offsetCode the unit offset code: 0 for implied offsets, 1 to 4 for fields of 2, 3, 4 or 8 bytes
     * @param sizeCode the unit size code: 0 to 4 for fields of 1, 2, 3, 4 or 8 bytes
     */
    public static byte[] icef(int offsetCode, int sizeCode, long[] offsets, long[] sizes) {
        var out = new ByteArrayOutputStream();
        out.write((offsetCode << 5) | (sizeCode << 2));
        HeifBuilder.u32(out, sizes.length);
        var offsetBytes = new int[]{0, 2, 3, 4, 8}[offsetCode];
        var sizeBytes = new int[]{1, 2, 3, 4, 8}[sizeCode];
        for (var i = 0; i < sizes.length; i++) {
            writeBytes(out, BigInteger.valueOf(offsets[i]), offsetBytes, false);
            writeBytes(out, BigInteger.valueOf(sizes[i]), sizeBytes, false);
        }
        return HeifBuilder.fullBox("icef", 0, 0, out.toByteArray());
    }

    private static void writeBytes(ByteArrayOutputStream out, BigInteger value, int count, boolean littleEndian) {
        for (var i = 0; i < count; i++) {
            var shift = 8 * (littleEndian ? i : count - 1 - i);
            out.write(value.shiftRight(shift).intValue() & 0xFF);
        }
    }

    /** Builds a HEIF file holding one unci item with an ispe and the given properties. */
    public static byte[] file(int width, int height, byte[] data, byte[]... properties) {
        var builder = new HeifBuilder().brands("mif1", "mif1", "miaf");
        var all = new HeifBuilder.Property[properties.length + 1];
        all[0] = HeifBuilder.plain(HeifBuilder.ispe(width, height));
        for (var i = 0; i < properties.length; i++) {
            all[i + 1] = HeifBuilder.essential(properties[i]);
        }
        return builder.primary(builder.item("unci", data, all)).build();
    }

    /** Writes the item data of an image. */
    public static byte[] data(Image image) {
        return new Writer(image).write();
    }

    /** Lays out the values of an image. */
    private static final class Writer {
        private final Image image;
        private final Layout layout;
        private final int sx;
        private final int sy;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int bits;
        private final List<Component> segmentComponents = new ArrayList<>();
        private final List<BigInteger> segmentValues = new ArrayList<>();

        Writer(Image image) {
            this.image = image;
            this.layout = image.layout();
            this.sx = layout.sx();
            this.sy = layout.sy();
        }

        byte[] write() {
            var components = image.components();
            var tileWidth = image.width() / layout.columns();
            var tileHeight = image.height() / layout.rows();
            var tiles = layout.columns() * layout.rows();
            if (layout.interleave() == 4) {
                for (var component : components) {
                    for (var t = 0; t < tiles; t++) {
                        var start = out.size();
                        plane(component, t % layout.columns() * tileWidth, t / layout.columns() * tileHeight,
                                tileWidth, tileHeight);
                        align(start, layout.tileAlign());
                    }
                }
                return out.toByteArray();
            }
            for (var t = 0; t < tiles; t++) {
                var start = out.size();
                var x0 = t % layout.columns() * tileWidth;
                var y0 = t / layout.columns() * tileHeight;
                switch (layout.interleave()) {
                    case 0 -> {
                        for (var component : components) {
                            plane(component, x0, y0, tileWidth, tileHeight);
                        }
                    }
                    case 1 -> pixels(x0, y0, tileWidth, tileHeight);
                    case 2 -> mixed(x0, y0, tileWidth, tileHeight);
                    case 3 -> rows(x0, y0, tileWidth, tileHeight);
                    case 5 -> multiY(x0, y0, tileWidth, tileHeight);
                    default -> throw new IllegalArgumentException("interleave " + layout.interleave());
                }
                align(start, layout.tileAlign());
            }
            return out.toByteArray();
        }

        private BigInteger value(Component component, int x, int y) {
            if (component.type() == PADDED) {
                return BigInteger.ONE.shiftLeft(component.depth()).subtract(BigInteger.ONE);
            }
            var plane = image.planes().get(component.type());
            return plane[y * planeWidth(image, component.type()) + x];
        }

        private void plane(Component component, int x0, int y0, int tileWidth, int tileHeight) {
            var chroma = isChroma(component.type());
            var w = chroma ? ceil(tileWidth, sx) : tileWidth;
            var h = chroma ? ceil(tileHeight, sy) : tileHeight;
            var px = chroma ? x0 / sx : x0;
            var py = chroma ? y0 / sy : y0;
            for (var row = 0; row < h; row++) {
                var start = out.size();
                for (var x = 0; x < w; x++) {
                    add(component, value(component, px + x, py + row));
                }
                flush();
                align(start, layout.rowAlign());
            }
        }

        private void pixels(int x0, int y0, int tileWidth, int tileHeight) {
            for (var row = 0; row < tileHeight; row++) {
                var start = out.size();
                for (var x = 0; x < tileWidth; x++) {
                    var pixel = out.size();
                    for (var component : image.components()) {
                        add(component, value(component, x0 + x, y0 + row));
                    }
                    if (layout.pixelSize() != 0) {
                        flush();
                        pad(pixel, layout.pixelSize());
                    }
                }
                flush();
                align(start, layout.rowAlign());
            }
        }

        private void mixed(int x0, int y0, int tileWidth, int tileHeight) {
            var chroma = new ArrayList<Component>();
            for (var component : image.components()) {
                if (isChroma(component.type())) {
                    chroma.add(component);
                }
            }
            var chromaDone = false;
            for (var component : image.components()) {
                if (!isChroma(component.type())) {
                    plane(component, x0, y0, tileWidth, tileHeight);
                    continue;
                }
                if (chromaDone) {
                    continue;
                }
                chromaDone = true;
                for (var row = 0; row < ceil(tileHeight, sy); row++) {
                    var start = out.size();
                    for (var x = 0; x < ceil(tileWidth, sx); x++) {
                        for (var c : chroma) {
                            add(c, value(c, x0 / sx + x, y0 / sy + row));
                        }
                    }
                    flush();
                    align(start, layout.rowAlign());
                }
            }
        }

        private void rows(int x0, int y0, int tileWidth, int tileHeight) {
            for (var row = 0; row < tileHeight; row++) {
                for (var component : image.components()) {
                    var chroma = isChroma(component.type());
                    if (chroma && row % sy != 0) {
                        continue;
                    }
                    var w = chroma ? ceil(tileWidth, sx) : tileWidth;
                    var px = chroma ? x0 / sx : x0;
                    var py = chroma ? (y0 + row) / sy : y0 + row;
                    var start = out.size();
                    for (var x = 0; x < w; x++) {
                        add(component, value(component, px + x, py));
                    }
                    flush();
                    align(start, layout.rowAlign());
                }
            }
        }

        private void multiY(int x0, int y0, int tileWidth, int tileHeight) {
            for (var unitRow = 0; unitRow < ceil(tileHeight, sy); unitRow++) {
                var start = out.size();
                for (var unit = 0; unit < ceil(tileWidth, sx); unit++) {
                    var unitStart = out.size();
                    var luma = 0;
                    for (var component : image.components()) {
                        if (component.type() == Y) {
                            var x = unit * sx + luma % sx;
                            var y = unitRow * sy + luma / sx;
                            luma++;
                            add(component, x < tileWidth && y < tileHeight ? value(component, x0 + x, y0 + y)
                                    : BigInteger.ZERO);
                        } else {
                            add(component, value(component, x0 / sx + unit, y0 / sy + unitRow));
                        }
                    }
                    if (layout.pixelSize() != 0) {
                        flush();
                        pad(unitStart, layout.pixelSize());
                    }
                }
                flush();
                align(start, layout.rowAlign());
            }
        }

        private void add(Component component, BigInteger value) {
            segmentComponents.add(component);
            segmentValues.add(value);
        }

        /** Writes the values of the current segment and ends it on a byte boundary. */
        private void flush() {
            if (layout.blockSize() != 0) {
                blocks();
            } else {
                for (var i = 0; i < segmentValues.size(); i++) {
                    var component = segmentComponents.get(i);
                    var value = segmentValues.get(i);
                    if (component.align() != 0) {
                        byteAlign();
                        // the bits above the value are padding: ones, which a reader must ignore
                        var container = value.or(BigInteger.ONE.shiftLeft(8 * component.align())
                                .subtract(BigInteger.ONE.shiftLeft(component.depth())));
                        writeBytes(out, container, component.align(), layout.componentsLe());
                    } else if (bits == 0 && component.depth() % 8 == 0) {
                        writeBytes(out, value, component.depth() / 8, layout.componentsLe());
                    } else {
                        for (var b = component.depth() - 1; b >= 0; b--) {
                            bit(value.testBit(b));
                        }
                    }
                }
            }
            byteAlign();
            segmentComponents.clear();
            segmentValues.clear();
        }

        private void blocks() {
            var blockBits = 8 * layout.blockSize();
            var i = 0;
            while (i < segmentValues.size()) {
                var used = 0;
                var end = i;
                while (end < segmentValues.size() && used + segmentComponents.get(end).depth() <= blockBits) {
                    used += segmentComponents.get(end).depth();
                    end++;
                }
                if (end == i) {
                    throw new IllegalArgumentException("a value does not fit a block");
                }
                var content = BigInteger.ZERO;
                var offset = 0;
                for (var k = i; k < end; k++) {
                    var depth = segmentComponents.get(k).depth();
                    var shift = layout.reversed() ? offset : used - offset - depth;
                    content = content.or(segmentValues.get(k).shiftLeft(shift));
                    offset += depth;
                }
                var padding = blockBits - used;
                var block = layout.padLsb() ? content.shiftLeft(padding).or(BigInteger.ONE.shiftLeft(padding)
                        .subtract(BigInteger.ONE)) : content.or(BigInteger.ONE.shiftLeft(blockBits)
                        .subtract(BigInteger.ONE.shiftLeft(used)));
                writeBytes(out, block, layout.blockSize(), layout.blockLe());
                i = end;
            }
        }

        private void bit(boolean set) {
            current = (current << 1) | (set ? 1 : 0);
            if (++bits == 8) {
                out.write(current);
                current = 0;
                bits = 0;
            }
        }

        private void byteAlign() {
            while (bits != 0) {
                bit(true);
            }
        }

        private void align(int start, long align) {
            if (align > 0) {
                while ((out.size() - start) % align != 0) {
                    out.write(0xA5);
                }
            }
        }

        private void pad(int start, long size) {
            if (out.size() - start > size) {
                throw new IllegalArgumentException("the components exceed the pixel size");
            }
            while (out.size() - start < size) {
                out.write(0xA5);
            }
        }
    }

    /**
     * Writes data as a deflate stream (RFC 1951) of stored blocks, wrapped in a zlib stream (RFC 1950) on request:
     * the same bytes whatever zlib the JDK links.
     */
    public static byte[] storedDeflate(byte[] data, boolean zlib) {
        var out = new ByteArrayOutputStream();
        if (zlib) {
            out.write(0x78);
            out.write(0x01);
        }
        var at = 0;
        do {
            var length = Math.min(65535, data.length - at);
            out.write(at + length == data.length ? 1 : 0);
            out.write(length);
            out.write(length >>> 8);
            out.write(~length);
            out.write(~length >>> 8);
            out.write(data, at, length);
            at += length;
        } while (at < data.length);
        if (zlib) {
            var adler = new java.util.zip.Adler32();
            adler.update(data);
            var value = adler.getValue();
            out.write((int) (value >>> 24));
            out.write((int) (value >>> 16));
            out.write((int) (value >>> 8));
            out.write((int) value);
        }
        return out.toByteArray();
    }

    /**
     * Writes data as a Brotli stream of uncompressed meta-blocks (RFC 7932 section 9.2), which every decoder
     * reads.
     */
    public static byte[] storedBrotli(byte[] data) {
        var out = new ByteArrayOutputStream();
        var bits = new long[1];
        var count = new int[1];
        // WBITS 16: a single zero bit
        put(out, bits, count, 0, 1);
        for (var at = 0; at < data.length; at += 65536) {
            var length = Math.min(65536, data.length - at);
            put(out, bits, count, 0, 1);
            put(out, bits, count, 0, 2);
            put(out, bits, count, length - 1, 16);
            put(out, bits, count, 1, 1);
            if (count[0] != 0) {
                put(out, bits, count, 0, 8 - count[0]);
            }
            out.write(data, at, length);
        }
        put(out, bits, count, 1, 1);
        put(out, bits, count, 1, 1);
        if (count[0] != 0) {
            put(out, bits, count, 0, 8 - count[0]);
        }
        return out.toByteArray();
    }

    private static void put(ByteArrayOutputStream out, long[] bits, int[] count, long value, int n) {
        for (var i = 0; i < n; i++) {
            bits[0] |= ((value >>> i) & 1) << count[0];
            if (++count[0] == 8) {
                out.write((int) bits[0]);
                bits[0] = 0;
                count[0] = 0;
            }
        }
    }

    /** {@return a map of planes, for building an {@link Image}} */
    public static Map<Integer, BigInteger[]> planes() {
        return new LinkedHashMap<>();
    }
}
