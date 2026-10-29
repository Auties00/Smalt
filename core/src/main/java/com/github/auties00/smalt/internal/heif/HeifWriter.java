/* Ports libavif (src/write.c: the box layout of avifEncoderFinish and avifImageExifOrientationToIrotImir).
   Copyright 2019 Joe Drago; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;

/**
 * Writes the boxes of a HEIF file (ISO/IEC 23008-12 over the ISO base media file format, ISO/IEC 14496-12),
 * whatever the coding of its images: the {@code ftyp} box, a {@code meta} box
 * describing the items and their properties, an optional {@code moov} box of an image sequence,
 * and an {@code mdat} box holding the coded items and samples.
 *
 * <p>Items are added with their data and properties; identical properties are stored once in the
 * {@code ipco} box and shared. Item data goes to the {@code mdat} box (construction method 0), or
 * to the {@code idat} box of the {@code meta} box (construction method 1) for small items such as
 * grid descriptors. The {@code iloc} box locates every item with absolute file offsets, which the
 * writer computes once the size of the {@code meta} box is known. Instances are not thread-safe.
 *
 * @implNote This implementation orders the boxes as libavif's {@code avifEncoderFinish} does:
 * {@code hdlr}, {@code pitm}, {@code iloc}, {@code iinf}, {@code iref}, {@code iprp} and
 * {@code idat} inside {@code meta}, then {@code moov} and {@code mdat}.
 */
public final class HeifWriter {
    /**
     * The most items a file holds, and the most items one reference names: the item identifiers and reference
     * counts of the {@code pitm}, {@code iloc}, {@code iinf}, {@code iref} and {@code ipma} boxes this writer
     * emits are 16-bit.
     */
    public static final int MAX_ITEMS = 0xFFFF;

    /**
     * One property: its serialized box and whether readers must understand it.
     *
     * @param box the serialized box
     * @param essential whether the property is essential
     */
    public record Property(byte[] box, boolean essential) {
    }

    /**
     * One item.
     */
    private static final class Item {
        /**
         * The item identifier.
         */
        final int id;

        /**
         * The item type, four characters.
         */
        final String type;

        /**
         * The item data.
         */
        final byte[] data;

        /**
         * The content type of a {@code mime} item, or {@code null}.
         */
        final String contentType;

        /**
         * Whether the data goes to the {@code idat} box.
         */
        final boolean inIdat;

        /**
         * Whether the item is hidden.
         */
        final boolean hidden;

        /**
         * The offset of the item's data within the movie samples, or {@code -1} when the item
         * holds its own data.
         */
        long sampleOffset = -1;

        /**
         * The length of the item's data within the movie samples.
         */
        int sampleLength;

        /**
         * The property indices, one-based, with the essential flag in bit 15.
         */
        final List<Integer> associations = new ArrayList<>();

        /**
         * Constructs an item.
         *
         * @param id the identifier, from 1
         * @param type the four-character type
         * @param data the item data, empty for an item located in the movie samples
         * @param contentType the content type of a {@code mime} item, or {@code null}
         * @param inIdat whether the data goes to {@code idat}
         * @param hidden whether the item is hidden
         */
        Item(int id, String type, byte[] data, String contentType, boolean inIdat, boolean hidden) {
            this.id = id;
            this.type = type;
            this.data = data;
            this.contentType = contentType;
            this.inIdat = inIdat;
            this.hidden = hidden;
        }
    }

    /**
     * One item reference.
     *
     * @param type the four-character reference type
     * @param from the referencing item
     * @param to the referenced items, in order
     */
    private record Reference(String type, int from, int[] to) {
    }

    /**
     * The items in identifier order.
     */
    private final List<Item> items = new ArrayList<>();

    /**
     * The distinct properties of the {@code ipco} box, in order.
     */
    private final List<byte[]> properties = new ArrayList<>();

    /**
     * The references, in order.
     */
    private final List<Reference> references = new ArrayList<>();

    /**
     * The alternatives groups ({@code altr}), each its items in order of preference.
     */
    private final List<int[]> alternatives = new ArrayList<>();

    /**
     * The identifier of the primary item, 0 when unset.
     */
    private int primary;

    /**
     * The format name that prefixes error messages, for example {@code "avif"}.
     */
    private final String format;

    /**
     * The major brand.
     */
    private final String majorBrand;

    /**
     * The compatible brands.
     */
    private final List<String> brands;

    /**
     * The builder of the {@code moov} box given the file offset of the samples, or {@code null}.
     */
    private LongFunction<byte[]> movie;

    /**
     * The movie samples, stored after the items in the {@code mdat} box.
     */
    private byte[] samples = new byte[0];

    /**
     * Constructs a writer for a file with the given brands.
     *
     * @param format the format name that prefixes error messages, for example {@code "avif"}
     * @param majorBrand the major brand
     * @param brands the compatible brands
     */
    public HeifWriter(String format, String majorBrand, List<String> brands) {
        this.format = format;
        this.majorBrand = majorBrand;
        this.brands = List.copyOf(brands);
    }

    /**
     * Adds an item whose data goes to the {@code mdat} box.
     *
     * @param type the item type, for example {@code "hvc1"} or {@code "av01"}
     * @param data the item data
     * @param hidden whether the item is hidden
     * @param properties the item's properties, in association order
     * @return the item identifier
     * @throws MediaException.LimitExceeded if the file already holds {@value #MAX_ITEMS} items
     */
    public int addItem(String type, byte[] data, boolean hidden, Property... properties) {
        return add(new Item(items.size() + 1, type, data, null, false, hidden), properties);
    }

    /**
     * Adds an item whose data is part of the movie samples.
     *
     * @param type the item type
     * @param offset the offset of the data within the samples
     * @param length the length of the data
     * @param properties the item's properties, in association order
     * @return the item identifier
     * @throws MediaException.LimitExceeded if the file already holds {@value #MAX_ITEMS} items
     */
    public int addSampleItem(String type, long offset, int length, Property... properties) {
        var item = new Item(items.size() + 1, type, new byte[0], null, false, false);
        item.sampleOffset = offset;
        item.sampleLength = length;
        return add(item, properties);
    }

    /**
     * Sets the movie of an image sequence.
     *
     * <p>The samples are stored in the {@code mdat} box after the items' data; the {@code moov} box
     * goes between the {@code meta} and {@code mdat} boxes. The builder receives the file offset of
     * the first sample and must return a box whose size does not depend on it.
     *
     * @param samples the coded samples of every track, in file order
     * @param movie the {@code moov} builder
     */
    public void movie(byte[] samples, LongFunction<byte[]> movie) {
        this.samples = samples;
        this.movie = movie;
    }

    /**
     * Adds an item whose data goes to the {@code idat} box of the {@code meta} box.
     *
     * @param type the item type, for example {@code "grid"}
     * @param data the item data
     * @param properties the item's properties, in association order
     * @return the item identifier
     * @throws MediaException.LimitExceeded if the file already holds {@value #MAX_ITEMS} items
     */
    public int addIdatItem(String type, byte[] data, Property... properties) {
        return add(new Item(items.size() + 1, type, data, null, true, false), properties);
    }

    /**
     * Adds a {@code mime} item whose data goes to the {@code mdat} box.
     *
     * @param contentType the content type, for example {@code "application/rdf+xml"}
     * @param data the item data
     * @return the item identifier
     * @throws MediaException.LimitExceeded if the file already holds {@value #MAX_ITEMS} items
     */
    public int addMimeItem(String contentType, byte[] data) {
        return add(new Item(items.size() + 1, "mime", data, contentType, false, false));
    }

    /**
     * Adds an item and associates its properties, storing each distinct property once.
     *
     * @param item the new item, whose identifier follows the last one
     * @param properties the properties, in association order
     * @return the item identifier
     * @throws MediaException.LimitExceeded if the file already holds {@value #MAX_ITEMS} items
     */
    private int add(Item item, Property... properties) {
        if (item.id > MAX_ITEMS) {
            throw new MediaException.LimitExceeded(format + ": a file holds at most " + MAX_ITEMS + " items");
        }
        items.add(item);
        for (var property : properties) {
            var index = -1;
            for (var i = 0; i < this.properties.size(); i++) {
                if (Arrays.equals(this.properties.get(i), property.box())) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                this.properties.add(property.box());
                index = this.properties.size() - 1;
            }
            item.associations.add((index + 1) | (property.essential() ? 1 << 15 : 0));
        }
        return item.id;
    }

    /**
     * Sets the primary item.
     *
     * @param id the item identifier
     */
    public void primary(int id) {
        this.primary = id;
    }

    /**
     * Adds a reference from one item to others.
     *
     * @param type the reference type, for example {@code "auxl"}, {@code "dimg"}, {@code "cdsc"} or
     * {@code "prem"}
     * @param from the referencing item
     * @param to the referenced items, in order
     * @throws MediaException.LimitExceeded if more than {@value #MAX_ITEMS} items are referenced
     */
    public void reference(String type, int from, int... to) {
        if (to.length > MAX_ITEMS) {
            throw new MediaException.LimitExceeded(format + ": a reference names at most " + MAX_ITEMS + " items");
        }
        references.add(new Reference(type, from, to.clone()));
    }

    /**
     * Adds an alternatives group ({@code altr}) of items, written in the {@code grpl} box with a group identifier
     * above every item identifier.
     *
     * @param ids the items, the most preferred first
     */
    public void alternatives(int... ids) {
        alternatives.add(ids.clone());
    }

    /**
     * Adds the metadata items that describe an item: an {@code Exif} item and an XMP {@code mime}
     * item, each with a {@code cdsc} reference to it.
     *
     * @param described the described item, normally the primary item
     * @param exif the Exif metadata, a TIFF header optionally preceded by other bytes such as the
     * {@code Exif\0\0} marker, or {@code null} for none
     * @param xmp the XMP packet, or {@code null} for none
     */
    public void addMetadata(int described, byte[] exif, byte[] xmp) {
        if (exif != null) {
            var exifId = addItem("Exif", exifPayload(exif), false);
            reference("cdsc", exifId, described);
        }
        if (xmp != null) {
            var xmpId = addMimeItem("application/rdf+xml", xmp);
            reference("cdsc", xmpId, described);
        }
    }

    /**
     * Returns the data of an Exif item: the offset of the TIFF header within the metadata,
     * followed by the metadata.
     *
     * @param exif the metadata, a TIFF header optionally preceded by other bytes
     * @return the item data; the offset is 0 when no TIFF header is found
     */
    public static byte[] exifPayload(byte[] exif) {
        var offset = 0;
        for (var i = 0; i + 3 < exif.length; i++) {
            if ((exif[i] == 'I' && exif[i + 1] == 'I' && exif[i + 2] == 42 && exif[i + 3] == 0)
                    || (exif[i] == 'M' && exif[i + 1] == 'M' && exif[i + 2] == 0 && exif[i + 3] == 42)) {
                offset = i;
                break;
            }
        }
        var out = new ByteWriter(exif.length + 4);
        out.u32be(offset);
        out.bytes(exif);
        return out.toByteArray();
    }

    /**
     * Writes the file.
     *
     * @return the file bytes
     * @throws MediaException.LimitExceeded if the file would exceed 2 GiB
     */
    public byte[] write() {
        var ftyp = ftyp();
        long itemData = 0;
        for (var item : items) {
            if (!item.inIdat) {
                itemData += item.data.length;
            }
        }
        var moovSize = movie == null ? 0 : movie.apply(0).length;
        var base = ftyp.length + meta(0, 0).length + moovSize + 8L;
        var sampleBase = base + itemData;
        var mdatSize = itemData + samples.length;
        var total = base + mdatSize;
        if (total > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded(format + ": the file would exceed 2 GiB");
        }
        var out = new ByteWriter((int) total);
        out.bytes(ftyp);
        out.bytes(meta(base, sampleBase));
        if (movie != null) {
            out.bytes(movie.apply(sampleBase));
        }
        out.u32be(mdatSize + 8);
        out.fourCc("mdat");
        for (var item : items) {
            if (!item.inIdat) {
                out.bytes(item.data);
            }
        }
        out.bytes(samples);
        return out.toByteArray();
    }

    /**
     * Serializes the {@code ftyp} box.
     *
     * @return the box
     */
    private byte[] ftyp() {
        var out = new ByteWriter(16 + 4 * brands.size());
        out.begin(FourCc.of("ftyp"));
        out.fourCc(majorBrand);
        out.u32be(0);
        for (var brand : brands) {
            out.fourCc(brand);
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Serializes the {@code meta} box with absolute item offsets.
     *
     * @param mdatData the file offset of the first byte of the {@code mdat} data
     * @param sampleData the file offset of the first movie sample
     * @return the box
     */
    private byte[] meta(long mdatData, long sampleData) {
        var out = new ByteWriter(1024);
        out.beginFull(FourCc.of("meta"), 0, 0);
        hdlr(out, "pict");
        out.beginFull(FourCc.of("pitm"), 0, 0);
        out.u16be(primary);
        out.end();
        iloc(out, mdatData, sampleData);
        iinf(out);
        if (!references.isEmpty()) {
            iref(out);
        }
        iprp(out);
        if (!alternatives.isEmpty()) {
            out.begin(FourCc.of("grpl"));
            var groupId = items.size() + 1L;
            for (var group : alternatives) {
                out.beginFull(FourCc.of("altr"), 0, 0);
                out.u32be(groupId++);
                out.u32be(group.length);
                for (var id : group) {
                    out.u32be(id);
                }
                out.end();
            }
            out.end();
        }
        var idat = 0;
        for (var item : items) {
            if (item.inIdat) {
                idat += item.data.length;
            }
        }
        if (idat > 0) {
            out.begin(FourCc.of("idat"));
            for (var item : items) {
                if (item.inIdat) {
                    out.bytes(item.data);
                }
            }
            out.end();
        }
        out.end();
        return out.toByteArray();
    }

    /**
     * Writes the {@code iloc} box.
     *
     * @param out the output
     * @param mdatData the file offset of the first byte of the {@code mdat} data
     * @param sampleData the file offset of the first movie sample
     */
    private void iloc(ByteWriter out, long mdatData, long sampleData) {
        var idat = false;
        for (var item : items) {
            idat |= item.inIdat;
        }
        out.beginFull(FourCc.of("iloc"), idat ? 1 : 0, 0);
        out.u8(4 << 4 | 4);
        out.u8(0);
        out.u16be(items.size());
        var mdatOffset = mdatData;
        long idatOffset = 0;
        for (var item : items) {
            out.u16be(item.id);
            if (idat) {
                out.u16be(item.inIdat ? 1 : 0);
            }
            out.u16be(0);
            out.u16be(1);
            long offset;
            var length = item.data.length;
            if (item.sampleOffset >= 0) {
                offset = sampleData + item.sampleOffset;
                length = item.sampleLength;
            } else if (item.inIdat) {
                offset = idatOffset;
                idatOffset += item.data.length;
            } else {
                offset = mdatOffset;
                mdatOffset += item.data.length;
            }
            out.u32be(offset);
            out.u32be(length);
        }
        out.end();
    }

    /**
     * Writes the {@code iinf} box.
     *
     * @param out the output
     */
    private void iinf(ByteWriter out) {
        out.beginFull(FourCc.of("iinf"), 0, 0);
        out.u16be(items.size());
        for (var item : items) {
            out.beginFull(FourCc.of("infe"), 2, item.hidden ? 1 : 0);
            out.u16be(item.id);
            out.u16be(0);
            out.fourCc(item.type);
            out.u8(0);
            if (item.contentType != null) {
                out.bytes(item.contentType.getBytes(StandardCharsets.US_ASCII));
                out.u8(0);
            }
            out.end();
        }
        out.end();
    }

    /**
     * Writes the {@code iref} box.
     *
     * @param out the output
     */
    private void iref(ByteWriter out) {
        out.beginFull(FourCc.of("iref"), 0, 0);
        for (var reference : references) {
            out.begin(FourCc.of(reference.type()));
            out.u16be(reference.from());
            out.u16be(reference.to().length);
            for (var id : reference.to()) {
                out.u16be(id);
            }
            out.end();
        }
        out.end();
    }

    /**
     * Writes the {@code iprp} box with its {@code ipco} and {@code ipma} boxes.
     *
     * @param out the output
     */
    private void iprp(ByteWriter out) {
        out.begin(FourCc.of("iprp"));
        out.begin(FourCc.of("ipco"));
        for (var property : properties) {
            out.bytes(property);
        }
        out.end();
        var wide = properties.size() > 127;
        var count = 0;
        for (var item : items) {
            if (!item.associations.isEmpty()) {
                count++;
            }
        }
        out.beginFull(FourCc.of("ipma"), 0, wide ? 1 : 0);
        out.u32be(count);
        for (var item : items) {
            if (item.associations.isEmpty()) {
                continue;
            }
            out.u16be(item.id);
            out.u8(item.associations.size());
            for (int association : item.associations) {
                var essential = (association >> 15) & 1;
                var index = association & 0x7FFF;
                if (wide) {
                    out.u16be(essential << 15 | index);
                } else {
                    out.u8(essential << 7 | index);
                }
            }
        }
        out.end();
        out.end();
    }

    /**
     * Writes a {@code hdlr} box.
     *
     * @param out the output
     * @param handler the four-character handler type
     */
    public static void hdlr(ByteWriter out, String handler) {
        out.beginFull(FourCc.of("hdlr"), 0, 0);
        out.u32be(0);
        out.fourCc(handler);
        out.zeros(12);
        out.u8(0);
        out.end();
    }

    /**
     * Wraps the one box a writer holds as a property.
     *
     * @param out the output holding exactly one box
     * @param essential whether the property is essential
     * @return the property
     */
    private static Property property(ByteWriter out, boolean essential) {
        return new Property(out.toByteArray(), essential);
    }

    /**
     * Returns an image spatial extents property.
     *
     * @param width the image width, in pixels
     * @param height the image height, in pixels
     * @return the property, not essential
     */
    public static Property ispe(int width, int height) {
        var out = new ByteWriter(20);
        out.beginFull(FourCc.of("ispe"), 0, 0);
        out.u32be(width);
        out.u32be(height);
        out.end();
        return property(out, false);
    }

    /**
     * Returns a pixel information property.
     *
     * @param depth the bit depth of every channel
     * @param channels the number of channels
     * @return the property, not essential
     */
    public static Property pixi(int depth, int channels) {
        var out = new ByteWriter(16);
        out.beginFull(FourCc.of("pixi"), 0, 0);
        out.u8(channels);
        for (var channel = 0; channel < channels; channel++) {
            out.u8(depth);
        }
        out.end();
        return property(out, false);
    }

    /**
     * Returns a codec configuration property, for example an {@code hvcC} or {@code av1C} box.
     *
     * @param type the four-character box type
     * @param record the configuration record the box holds
     * @return the property, essential
     */
    public static Property configuration(String type, byte[] record) {
        var out = new ByteWriter(record.length + 8);
        box(out, type, record);
        return property(out, true);
    }

    /**
     * Writes a box holding a payload as it is.
     *
     * @param out the output
     * @param type the four-character box type
     * @param payload the box payload
     */
    public static void box(ByteWriter out, String type, byte[] payload) {
        out.begin(FourCc.of(type));
        out.bytes(payload);
        out.end();
    }

    /**
     * Returns a color property of type {@code nclx}.
     *
     * @param primaries the color primaries code
     * @param transfer the transfer characteristics code
     * @param matrix the matrix coefficients code
     * @param fullRange whether the samples are full range
     * @return the property, not essential
     */
    public static Property nclx(int primaries, int transfer, int matrix, boolean fullRange) {
        var out = new ByteWriter(19);
        nclx(out, primaries, transfer, matrix, fullRange);
        return property(out, false);
    }

    /**
     * Writes a {@code colr} box of type {@code nclx}.
     *
     * @param out the output
     * @param primaries the color primaries code
     * @param transfer the transfer characteristics code
     * @param matrix the matrix coefficients code
     * @param fullRange whether the samples are full range
     */
    public static void nclx(ByteWriter out, int primaries, int transfer, int matrix, boolean fullRange) {
        out.begin(FourCc.of("colr"));
        out.fourCc("nclx");
        out.u16be(primaries);
        out.u16be(transfer);
        out.u16be(matrix);
        out.u8(fullRange ? 0x80 : 0);
        out.end();
    }

    /**
     * Returns a color property holding an ICC profile.
     *
     * @param profile the serialized ICC profile
     * @return the property, not essential
     */
    public static Property icc(byte[] profile) {
        var out = new ByteWriter(profile.length + 12);
        icc(out, profile);
        return property(out, false);
    }

    /**
     * Writes a {@code colr} box holding an ICC profile.
     *
     * @param out the output
     * @param profile the serialized ICC profile
     */
    public static void icc(ByteWriter out, byte[] profile) {
        out.begin(FourCc.of("colr"));
        out.fourCc("prof");
        out.bytes(profile);
        out.end();
    }

    /**
     * Returns a clean aperture property that keeps the top left {@code width} by {@code height} samples of an
     * image of {@code imageWidth} by {@code imageHeight} samples.
     *
     * @param width the width kept, from 1 to {@code imageWidth}
     * @param height the height kept, from 1 to {@code imageHeight}
     * @param imageWidth the width of the reconstructed image
     * @param imageHeight the height of the reconstructed image
     * @return the property, essential
     */
    public static Property clap(int width, int height, int imageWidth, int imageHeight) {
        var out = new ByteWriter(40);
        out.begin(FourCc.of("clap"));
        out.u32be(width);
        out.u32be(1);
        out.u32be(height);
        out.u32be(1);
        // the offsets place the aperture's center relative to the image's: (kept - full) / 2, so the left and top
        // edges stay at 0
        out.u32be((width - imageWidth) & 0xFFFFFFFFL);
        out.u32be(2);
        out.u32be((height - imageHeight) & 0xFFFFFFFFL);
        out.u32be(2);
        out.end();
        return property(out, true);
    }

    /**
     * Returns an auxiliary type property.
     *
     * @param urn the auxiliary type
     * @return the property, essential
     */
    public static Property auxC(String urn) {
        var out = new ByteWriter(urn.length() + 13);
        out.beginFull(FourCc.of("auxC"), 0, 0);
        out.bytes(urn.getBytes(StandardCharsets.US_ASCII));
        out.u8(0);
        out.end();
        return property(out, true);
    }

    /**
     * Returns an image rotation property.
     *
     * @param quarterTurns the anticlockwise rotation in quarter turns, from 0 to 3
     * @return the property, essential
     */
    public static Property irot(int quarterTurns) {
        var out = new ByteWriter(9);
        out.begin(FourCc.of("irot"));
        out.u8(quarterTurns & 3);
        out.end();
        return property(out, true);
    }

    /**
     * Returns an image mirroring property.
     *
     * @param axis 0 to swap the top and bottom, 1 to swap the left and right
     * @return the property, essential
     */
    public static Property imir(int axis) {
        var out = new ByteWriter(9);
        out.begin(FourCc.of("imir"));
        out.u8(axis & 1);
        out.end();
        return property(out, true);
    }

    /**
     * Returns a content light level property.
     *
     * @param maxContentLightLevel the maximum light level of any sample, in candelas per square
     * meter
     * @param maxPictureAverageLightLevel the maximum average light level of a picture, in candelas
     * per square meter
     * @return the property, not essential
     */
    public static Property clli(int maxContentLightLevel, int maxPictureAverageLightLevel) {
        var out = new ByteWriter(12);
        out.begin(FourCc.of("clli"));
        out.u16be(maxContentLightLevel);
        out.u16be(maxPictureAverageLightLevel);
        out.end();
        return property(out, false);
    }

    /**
     * Returns a mastering display color volume property.
     *
     * @param primaries the green, blue and red primaries' chromaticities, x then y, in units of
     * 0.00002
     * @param whiteX the white point's x chromaticity, in units of 0.00002
     * @param whiteY the white point's y chromaticity, in units of 0.00002
     * @param maxLuminance the maximum luminance, in units of 0.0001 candelas per square meter
     * @param minLuminance the minimum luminance, in units of 0.0001 candelas per square meter
     * @return the property, not essential
     */
    public static Property mdcv(int[] primaries, int whiteX, int whiteY, long maxLuminance, long minLuminance) {
        var out = new ByteWriter(32);
        out.begin(FourCc.of("mdcv"));
        for (var v : primaries) {
            out.u16be(v);
        }
        out.u16be(whiteX);
        out.u16be(whiteY);
        out.u32be(maxLuminance);
        out.u32be(minLuminance);
        out.end();
        return property(out, false);
    }

    /**
     * Returns the color properties of a color image: the {@code nclx} description, the ICC profile, and the
     * content light level and mastering display of high dynamic range content.
     *
     * @param color the color description of the samples
     * @param profile the ICC profile to embed, or {@code null} for none
     * @return the properties, in that order
     */
    public static List<Property> colorProperties(ColorInfo color, ColorProfile profile) {
        var props = new ArrayList<Property>();
        props.add(nclx(color.primaries().code(), color.transfer().code(), color.matrix().code(),
                color.range() == ColorRange.FULL));
        if (profile != null) {
            props.add(icc(profile.toByteArray()));
        }
        color.contentLightLevel().ifPresent(level -> props.add(clli(
                Math.clamp(level.maxContentLightLevel(), 0, 65535), Math.clamp(level.maxFrameAverageLightLevel(), 0,
                        65535))));
        color.masteringDisplay().ifPresent(display -> props.add(mdcv(new int[]{
                chromaticity(display.green().x()), chromaticity(display.green().y()),
                chromaticity(display.blue().x()), chromaticity(display.blue().y()),
                chromaticity(display.red().x()), chromaticity(display.red().y())},
                chromaticity(display.whitePoint().x()), chromaticity(display.whitePoint().y()),
                Math.clamp(Math.round(display.maxLuminance() * 10000), 0, 0xFFFFFFFFL),
                Math.clamp(Math.round(display.minLuminance() * 10000), 0, 0xFFFFFFFFL))));
        return props;
    }

    /**
     * Returns a chromaticity coordinate in units of 0.00002.
     *
     * @param value the coordinate
     * @return the coded value
     */
    private static int chromaticity(double value) {
        return (int) Math.clamp(Math.round(value / 0.00002), 0, 65535);
    }

    /**
     * Returns the rotation and mirroring properties that display an image in an orientation
     * ({@code avifImageExifOrientationToIrotImir}).
     *
     * @param orientation the display orientation
     * @return the properties, rotation first
     */
    public static List<Property> orientation(Orientation orientation) {
        return switch (orientation) {
            case NORMAL -> List.of();
            case FLIP_HORIZONTAL -> List.of(imir(1));
            case ROTATE_180 -> List.of(irot(2));
            case FLIP_VERTICAL -> List.of(imir(0));
            case TRANSPOSE -> List.of(irot(1), imir(0));
            case ROTATE_90 -> List.of(irot(3));
            case TRANSVERSE -> List.of(irot(3), imir(0));
            case ROTATE_270 -> List.of(irot(1));
        };
    }

    /**
     * Returns the data of an image grid item ({@code ImageGrid}).
     *
     * @param rows the number of tile rows, from 1 to 256
     * @param columns the number of tile columns, from 1 to 256
     * @param width the output width
     * @param height the output height
     * @return the data, with 32-bit sizes when a size exceeds 65535
     */
    public static byte[] grid(int rows, int columns, int width, int height) {
        var wide = width > 0xFFFF || height > 0xFFFF;
        var out = new ByteWriter(12);
        out.u8(0);
        out.u8(wide ? 1 : 0);
        out.u8(rows - 1);
        out.u8(columns - 1);
        if (wide) {
            out.u32be(width);
            out.u32be(height);
        } else {
            out.u16be(width);
            out.u16be(height);
        }
        return out.toByteArray();
    }
}
