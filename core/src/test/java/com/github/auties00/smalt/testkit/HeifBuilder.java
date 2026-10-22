package com.github.auties00.smalt.testkit;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes synthetic HEIF/AVIF files box by box, so that tests can build valid files from coded
 * payloads (grids, alpha, transforms) and hostile ones (wrong counts, cycles, truncation) that no
 * encoder produces.
 *
 * <p>Items are stored in an {@code mdat} box (construction method 0) or in the {@code idat} box
 * (method 1); {@link #extents} gives an item several extents or makes it point into another
 * item's data (method 2). Every box helper is public so tests can add boxes the builder does not
 * model.
 */
public final class HeifBuilder {
    /** One property association. */
    public record Property(byte[] box, boolean essential) {
    }

    /** One extent: an offset and a length into the item's data source. */
    public record Extent(long offset, long length, int referenceIndex) {
    }

    private static final class Item {
        int id;
        String type;
        String contentType;
        String contentEncoding;
        byte[] data;
        int method;
        List<Property> properties = new ArrayList<>();
        List<Extent> extents;
        boolean hidden;
    }

    private String majorBrand = "avif";
    private List<String> compatible = new ArrayList<>(List.of("avif", "mif1", "miaf"));
    private final List<Item> items = new ArrayList<>();
    private final List<byte[]> references = new ArrayList<>();
    private final List<byte[]> extraMeta = new ArrayList<>();
    private final List<byte[]> trailing = new ArrayList<>();
    private int primary;
    private boolean wideIndex;
    private int ilocVersion = 1;

    /** Sets the brands. */
    public HeifBuilder brands(String major, String... compatibleBrands) {
        this.majorBrand = major;
        this.compatible = new ArrayList<>(List.of(compatibleBrands));
        return this;
    }

    /** Adds an item stored in the {@code mdat} box and returns its identifier. */
    public int item(String type, byte[] data, Property... properties) {
        return add(type, data, 0, properties);
    }

    /** Adds an item stored in the {@code idat} box and returns its identifier. */
    public int idatItem(String type, byte[] data, Property... properties) {
        return add(type, data, 1, properties);
    }

    /** Adds a {@code mime} item. */
    public int mimeItem(String contentType, byte[] data) {
        var id = add("mime", data, 0);
        items.get(id - 1).contentType = contentType;
        return id;
    }

    /** Adds a {@code mime} item whose {@code infe} entry names a content encoding. */
    public int mimeItem(String contentType, String contentEncoding, byte[] data) {
        var id = mimeItem(contentType, data);
        items.get(id - 1).contentEncoding = contentEncoding;
        return id;
    }

    private int add(String type, byte[] data, int method, Property... properties) {
        var item = new Item();
        item.id = items.size() + 1;
        item.type = type;
        item.data = data;
        item.method = method;
        item.properties.addAll(List.of(properties));
        items.add(item);
        return item.id;
    }

    /** Overrides the extents of an item; construction method 2 resolves them in the referenced item's data. */
    public HeifBuilder extents(int id, int method, Extent... extents) {
        var item = items.get(id - 1);
        item.method = method;
        item.extents = List.of(extents);
        return this;
    }

    /** Marks an item hidden. */
    public HeifBuilder hidden(int id) {
        items.get(id - 1).hidden = true;
        return this;
    }

    /** Sets the primary item. */
    public HeifBuilder primary(int id) {
        this.primary = id;
        return this;
    }

    /** Adds an item reference box ({@code dimg}, {@code auxl}, {@code thmb}, {@code cdsc}, {@code prem}, {@code iloc}). */
    public HeifBuilder reference(String type, int from, int... to) {
        var out = new ByteArrayOutputStream();
        u16(out, from);
        u16(out, to.length);
        for (var target : to) {
            u16(out, target);
        }
        references.add(box(type, out.toByteArray()));
        return this;
    }

    /** Uses 15-bit property indexes in {@code ipma}. */
    public HeifBuilder wideIndex() {
        this.wideIndex = true;
        return this;
    }

    /** Selects the {@code iloc} version (1 or 2; 0 forbids construction methods). */
    public HeifBuilder ilocVersion(int version) {
        this.ilocVersion = version;
        return this;
    }

    /** Appends a raw child box to the {@code meta} box. */
    public HeifBuilder metaBox(byte[] box) {
        extraMeta.add(box);
        return this;
    }

    /** Appends a raw top-level box after the {@code mdat} box. */
    public HeifBuilder topLevel(byte[] box) {
        trailing.add(box);
        return this;
    }

    /** Builds the file. */
    public byte[] build() {
        var idat = new ByteArrayOutputStream();
        var mdat = new ByteArrayOutputStream();
        var offsets = new long[items.size()];
        for (var item : items) {
            if (item.method == 1 && item.extents == null) {
                offsets[item.id - 1] = idat.size();
                idat.writeBytes(item.data);
            } else if (item.method == 0) {
                offsets[item.id - 1] = mdat.size();
                mdat.writeBytes(item.data);
            }
        }
        var ftyp = ftyp();
        var meta = meta(offsets, 0, idat.toByteArray());
        var mdatStart = ftyp.length + meta.length + 8L;
        meta = meta(offsets, mdatStart, idat.toByteArray());
        var out = new ByteArrayOutputStream();
        out.writeBytes(ftyp);
        out.writeBytes(meta);
        out.writeBytes(box("mdat", mdat.toByteArray()));
        for (var box : trailing) {
            out.writeBytes(box);
        }
        return out.toByteArray();
    }

    private byte[] ftyp() {
        var out = new ByteArrayOutputStream();
        out.writeBytes(majorBrand.getBytes(StandardCharsets.ISO_8859_1));
        u32(out, 0);
        for (var brand : compatible) {
            out.writeBytes(brand.getBytes(StandardCharsets.ISO_8859_1));
        }
        return box("ftyp", out.toByteArray());
    }

    private byte[] meta(long[] offsets, long mdatStart, byte[] idat) {
        var children = new ByteArrayOutputStream();
        children.writeBytes(hdlr("pict"));
        if (primary != 0) {
            var pitm = new ByteArrayOutputStream();
            u16(pitm, primary);
            children.writeBytes(fullBox("pitm", 0, 0, pitm.toByteArray()));
        }
        var iinf = new ByteArrayOutputStream();
        u16(iinf, items.size());
        for (var item : items) {
            var infe = new ByteArrayOutputStream();
            u16(infe, item.id);
            u16(infe, 0);
            infe.writeBytes(item.type.getBytes(StandardCharsets.ISO_8859_1));
            infe.write(0);
            if (item.contentType != null) {
                infe.writeBytes(item.contentType.getBytes(StandardCharsets.UTF_8));
                infe.write(0);
                if (item.contentEncoding != null) {
                    infe.writeBytes(item.contentEncoding.getBytes(StandardCharsets.UTF_8));
                    infe.write(0);
                }
            }
            iinf.writeBytes(fullBox("infe", 2, item.hidden ? 1 : 0, infe.toByteArray()));
        }
        children.writeBytes(fullBox("iinf", 0, 0, iinf.toByteArray()));
        children.writeBytes(iloc(offsets, mdatStart));
        var ipco = new ByteArrayOutputStream();
        var ipma = new ByteArrayOutputStream();
        var index = 0;
        u32(ipma, items.size());
        for (var item : items) {
            if (ilocVersion == 2) {
                u32(ipma, item.id);
            } else {
                u16(ipma, item.id);
            }
            ipma.write(item.properties.size());
            for (var property : item.properties) {
                ipco.writeBytes(property.box());
                index++;
                if (wideIndex) {
                    u16(ipma, (property.essential() ? 0x8000 : 0) | index);
                } else {
                    ipma.write((property.essential() ? 0x80 : 0) | index);
                }
            }
        }
        var iprp = new ByteArrayOutputStream();
        iprp.writeBytes(box("ipco", ipco.toByteArray()));
        iprp.writeBytes(fullBox("ipma", ilocVersion == 2 ? 1 : 0, wideIndex ? 1 : 0, ipma.toByteArray()));
        children.writeBytes(box("iprp", iprp.toByteArray()));
        if (!references.isEmpty()) {
            var iref = new ByteArrayOutputStream();
            for (var reference : references) {
                iref.writeBytes(reference);
            }
            children.writeBytes(fullBox("iref", 0, 0, iref.toByteArray()));
        }
        if (idat.length > 0) {
            children.writeBytes(box("idat", idat));
        }
        for (var box : extraMeta) {
            children.writeBytes(box);
        }
        return fullBox("meta", 0, 0, children.toByteArray());
    }

    private byte[] iloc(long[] offsets, long mdatStart) {
        var out = new ByteArrayOutputStream();
        out.write(0x44);
        out.write(ilocVersion == 0 ? 0x00 : 0x04);
        if (ilocVersion == 2) {
            u32(out, items.size());
        } else {
            u16(out, items.size());
        }
        for (var item : items) {
            if (ilocVersion == 2) {
                u32(out, item.id);
            } else {
                u16(out, item.id);
            }
            if (ilocVersion != 0) {
                u16(out, item.method);
            }
            u16(out, 0);
            var extents = item.extents != null ? item.extents
                    : List.of(new Extent((item.method == 0 ? mdatStart : 0) + offsets[item.id - 1], item.data.length, 1));
            u16(out, extents.size());
            for (var extent : extents) {
                if (ilocVersion != 0) {
                    u32(out, extent.referenceIndex());
                }
                u32(out, extent.offset());
                u32(out, extent.length());
            }
        }
        return fullBox("iloc", ilocVersion, 0, out.toByteArray());
    }

    /** Builds a {@code hdlr} box. */
    public static byte[] hdlr(String handler) {
        var out = new ByteArrayOutputStream();
        u32(out, 0);
        out.writeBytes(handler.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[12]);
        out.write(0);
        return fullBox("hdlr", 0, 0, out.toByteArray());
    }

    /** Builds a box. */
    public static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        u32(out, 8L + payload.length);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Builds a full box. */
    public static byte[] fullBox(String type, int version, int flags, byte[] payload) {
        var out = new ByteArrayOutputStream();
        u32(out, ((long) version << 24) | flags);
        out.writeBytes(payload);
        return box(type, out.toByteArray());
    }

    /** An essential property. */
    public static Property essential(byte[] box) {
        return new Property(box, true);
    }

    /** A non-essential property. */
    public static Property plain(byte[] box) {
        return new Property(box, false);
    }

    /** Builds an {@code ispe} box. */
    public static byte[] ispe(int width, int height) {
        var out = new ByteArrayOutputStream();
        u32(out, width);
        u32(out, height);
        return fullBox("ispe", 0, 0, out.toByteArray());
    }

    /** Builds an {@code av1C} box around a configuration record. */
    public static byte[] av1C(byte[] record) {
        return box("av1C", record);
    }

    /** Builds an {@code nclx} {@code colr} box. */
    public static byte[] nclx(int primaries, int transfer, int matrix, boolean full) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("nclx".getBytes(StandardCharsets.ISO_8859_1));
        u16(out, primaries);
        u16(out, transfer);
        u16(out, matrix);
        out.write(full ? 0x80 : 0);
        return box("colr", out.toByteArray());
    }

    /** Builds an ICC {@code colr} box. */
    public static byte[] icc(byte[] profile) {
        var out = new ByteArrayOutputStream();
        out.writeBytes("prof".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(profile);
        return box("colr", out.toByteArray());
    }

    /** Builds an {@code irot} box. */
    public static byte[] irot(int quarterTurns) {
        return box("irot", new byte[]{(byte) quarterTurns});
    }

    /** Builds an {@code imir} box. */
    public static byte[] imir(int axis) {
        return box("imir", new byte[]{(byte) axis});
    }

    /** Builds a {@code clap} box. */
    public static byte[] clap(int widthN, int widthD, int heightN, int heightD, int horizontalN, int horizontalD,
                              int verticalN, int verticalD) {
        var out = new ByteArrayOutputStream();
        for (var value : new int[]{widthN, widthD, heightN, heightD, horizontalN, horizontalD, verticalN, verticalD}) {
            u32(out, value & 0xFFFFFFFFL);
        }
        return box("clap", out.toByteArray());
    }

    /** Builds a {@code pixi} box. */
    public static byte[] pixi(int... depths) {
        var out = new ByteArrayOutputStream();
        out.write(depths.length);
        for (var depth : depths) {
            out.write(depth);
        }
        return fullBox("pixi", 0, 0, out.toByteArray());
    }

    /** Builds an {@code auxC} box. */
    public static byte[] auxC(String urn) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(urn.getBytes(StandardCharsets.UTF_8));
        out.write(0);
        return fullBox("auxC", 0, 0, out.toByteArray());
    }

    /** Builds the alpha {@code auxC} box. */
    public static byte[] alpha() {
        return auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha");
    }

    /** Builds a {@code clli} box. */
    public static byte[] clli(int maxCll, int maxFall) {
        var out = new ByteArrayOutputStream();
        u16(out, maxCll);
        u16(out, maxFall);
        return box("clli", out.toByteArray());
    }

    /** Builds an image grid payload. */
    public static byte[] grid(int rows, int columns, int width, int height) {
        var wide = width > 0xFFFF || height > 0xFFFF;
        var out = new ByteArrayOutputStream();
        out.write(0);
        out.write(wide ? 1 : 0);
        out.write(rows - 1);
        out.write(columns - 1);
        if (wide) {
            u32(out, width);
            u32(out, height);
        } else {
            u16(out, width);
            u16(out, height);
        }
        return out.toByteArray();
    }

    /** Builds an image overlay payload with 16-bit or, when {@code wide}, 32-bit sizes and offsets. */
    public static byte[] overlay(boolean wide, int[] fill, int width, int height, int[]... offsets) {
        var out = new ByteArrayOutputStream();
        out.write(0);
        out.write(wide ? 1 : 0);
        for (var value : fill) {
            u16(out, value);
        }
        for (var value : new int[]{width, height}) {
            if (wide) {
                u32(out, value & 0xFFFFFFFFL);
            } else {
                u16(out, value);
            }
        }
        for (var offset : offsets) {
            for (var value : offset) {
                if (wide) {
                    u32(out, value & 0xFFFFFFFFL);
                } else {
                    u16(out, value & 0xFFFF);
                }
            }
        }
        return out.toByteArray();
    }

    /**
     * Builds a sample transform payload ({@code sato}) of version 0. {@code tokens} lists the token values in
     * order; each constant token, value 0, is followed in the array by its constant, written with
     * {@code 8 << bitDepthCode} bits.
     */
    public static byte[] sato(int bitDepthCode, long... tokens) {
        var body = new ByteArrayOutputStream();
        var count = 0;
        for (var i = 0; i < tokens.length; i++, count++) {
            body.write((int) tokens[i]);
            if (tokens[i] == 0) {
                var constant = tokens[++i];
                for (var shift = (8 << bitDepthCode) - 8; shift >= 0; shift -= 8) {
                    body.write((int) (constant >>> shift));
                }
            }
        }
        var out = new ByteArrayOutputStream();
        out.write(bitDepthCode & 3);
        out.write(count);
        out.writeBytes(body.toByteArray());
        return out.toByteArray();
    }

    /** Builds a {@code grpl} box holding entity groups built with {@link #entityGroup}. */
    public static byte[] grpl(byte[]... groups) {
        var out = new ByteArrayOutputStream();
        for (var group : groups) {
            out.writeBytes(group);
        }
        return box("grpl", out.toByteArray());
    }

    /** Builds an {@code EntityToGroupBox}, such as an {@code altr} group. */
    public static byte[] entityGroup(String type, long groupId, int... entities) {
        var out = new ByteArrayOutputStream();
        u32(out, groupId);
        u32(out, entities.length);
        for (var entity : entities) {
            u32(out, entity);
        }
        return fullBox(type, 0, 0, out.toByteArray());
    }

    /** Writes a big-endian 16-bit value. */
    public static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /** Writes a big-endian 32-bit value. */
    public static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }
}
