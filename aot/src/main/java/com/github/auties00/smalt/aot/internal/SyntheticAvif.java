package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the AVIF files of derived images the training workloads decode, which no encoder writes:
 * an image overlay of an identity derivation and of a coded image, and a 16-bit sample transform
 * of two coded images preferred to the first one by an alternatives group. The coded images are
 * the primary items of AVIF files the AVIF encoder wrote.
 */
final class SyntheticAvif {
    /**
     * One coded AV1 image.
     *
     * @param payload the item data
     * @param config the {@code av1C} record
     * @param width the width in pixels
     * @param height the height in pixels
     */
    record Coded(byte[] payload, byte[] config, int width, int height) {
        /**
         * {@return the primary item of an AVIF file the encoder wrote}
         *
         * @param avif the file
         */
        static Coded of(byte[] avif) {
            var source = MediaSource.of(avif);
            var file = HeifFile.parse(source, MediaLimits.DEFAULT);
            var primary = file.primaryItem();
            var extent = primary.property(HeifProperty.SpatialExtent.class);
            return new Coded(file.readItem(source, primary, MediaLimits.DEFAULT),
                    primary.property(HeifProperty.Av1Configuration.class).record(), (int) extent.width(),
                    (int) extent.height());
        }
    }

    /**
     * One item of a file being built.
     *
     * @param type the item type
     * @param data the item data
     * @param properties the property boxes, each with its essential flag in {@code essential}
     * @param essential which properties are essential
     * @param hidden whether the item is hidden
     */
    private record Item(String type, byte[] data, List<byte[]> properties, List<Boolean> essential, boolean hidden) {
    }

    /**
     * Prevents instantiation.
     */
    private SyntheticAvif() {
        throw new AssertionError();
    }

    /**
     * Builds an image overlay ({@code iovl}) of an identity derivation ({@code iden}) of a rotated
     * image and of a second image, over a fill color.
     *
     * @param rotated the image the identity derivation turns a quarter turn
     * @param other the second image
     * @return the file
     */
    static byte[] overlay(Coded rotated, Coded other) {
        var items = new ArrayList<Item>();
        items.add(coded(rotated, true, box("irot", new byte[]{1})));
        items.add(coded(other, true));
        items.add(new Item("iden", new byte[0], List.of(ispe(rotated.height(), rotated.width())), List.of(false),
                true));
        var width = rotated.height() + other.width();
        var height = Math.max(rotated.width(), other.height());
        var payload = new ByteArrayOutputStream();
        payload.write(0);
        payload.write(0);
        for (var value : new int[]{0x2000, 0x4000, 0x6000, 0xFFFF, width, height, 0, 0, rotated.height(), 0}) {
            u16(payload, value);
        }
        items.add(new Item("iovl", payload.toByteArray(), List.of(ispe(width, height)), List.of(false), false));
        var references = new ByteArrayOutputStream();
        references.writeBytes(reference("dimg", 3, 1));
        references.writeBytes(reference("dimg", 4, 3, 2));
        return file(items, 4, references.toByteArray(), new byte[0]);
    }

    /**
     * Builds a sample transform ({@code sato}) to 16 bits of two images of one size, the 8 most and
     * the 8 least significant bits of each sample, shown in place of the first through an
     * alternatives group ({@code altr}).
     *
     * @param high the most significant bits, the primary item
     * @param low the least significant bits, hidden
     * @return the file
     */
    static byte[] sampleTransform(Coded high, Coded low) {
        var items = new ArrayList<Item>();
        items.add(coded(high, false));
        items.add(coded(low, true));
        var expression = new byte[]{2, 5, 0, 0, 0, 1, 0, 1, (byte) 130, 2, (byte) 133};
        var pixi = fullBox("pixi", 0, new byte[]{3, 16, 16, 16});
        items.add(new Item("sato", expression, List.of(ispe(high.width(), high.height()), pixi), List.of(false, false),
                false));
        var groups = new ByteArrayOutputStream();
        for (var value : new int[]{1, 2, 3, 1}) {
            u32(groups, value);
        }
        return file(items, 1, reference("dimg", 3, 1, 2), box("grpl", fullBox("altr", 0, groups.toByteArray())));
    }

    /**
     * {@return the item of a coded image}
     *
     * @param image the coded image
     * @param hidden whether the item is hidden
     * @param extra essential transformative properties
     */
    private static Item coded(Coded image, boolean hidden, byte[]... extra) {
        var properties = new ArrayList<>(List.of(ispe(image.width(), image.height()), box("av1C", image.config())));
        var essential = new ArrayList<>(List.of(false, true));
        for (var property : extra) {
            properties.add(property);
            essential.add(true);
        }
        return new Item("av01", image.payload(), properties, essential, hidden);
    }

    /**
     * Writes a file: the {@code ftyp} box, the {@code meta} box with every item stored in the
     * {@code mdat} box, and the {@code mdat} box.
     *
     * @param items the items, identified from 1 in order
     * @param primary the primary item
     * @param references the {@code iref} children
     * @param groups the {@code grpl} box, or nothing
     * @return the file
     */
    private static byte[] file(List<Item> items, int primary, byte[] references, byte[] groups) {
        var ftyp = box("ftyp", "avif\0\0\0\0avifmif1miaf".getBytes(StandardCharsets.ISO_8859_1));
        var hdlr = new ByteArrayOutputStream();
        u32(hdlr, 0);
        hdlr.writeBytes("pict".getBytes(StandardCharsets.ISO_8859_1));
        hdlr.writeBytes(new byte[13]);
        var pitm = new ByteArrayOutputStream();
        u16(pitm, primary);
        var infe = new ByteArrayOutputStream();
        u16(infe, items.size());
        var ipco = new ByteArrayOutputStream();
        var ipma = new ByteArrayOutputStream();
        u32(ipma, items.size());
        var count = 0;
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var entry = new ByteArrayOutputStream();
            u16(entry, index + 1);
            u16(entry, 0);
            entry.writeBytes((item.type() + "\0").getBytes(StandardCharsets.ISO_8859_1));
            infe.writeBytes(fullBox("infe", (2 << 24) | (item.hidden() ? 1 : 0), entry.toByteArray()));
            u16(ipma, index + 1);
            ipma.write(item.properties().size());
            for (var i = 0; i < item.properties().size(); i++) {
                ipco.writeBytes(item.properties().get(i));
                ipma.write((item.essential().get(i) ? 0x80 : 0) | ++count);
            }
        }
        var properties = box("iprp", concat(box("ipco", ipco.toByteArray()), fullBox("ipma", 0, ipma.toByteArray())));
        var head = concat(fullBox("hdlr", 0, hdlr.toByteArray()), fullBox("pitm", 0, pitm.toByteArray()));
        var tail = concat(fullBox("iinf", 0, infe.toByteArray()), fullBox("iref", 0, references), properties, groups);
        var ilocSize = 8 + 4 + 2 + 2 + items.size() * 16;
        var metaSize = 12 + head.length + ilocSize + tail.length;
        var offset = (long) ftyp.length + metaSize + 8;
        var iloc = new ByteArrayOutputStream();
        iloc.write(0x44);
        iloc.write(0);
        u16(iloc, items.size());
        var mdat = new ByteArrayOutputStream();
        for (var index = 0; index < items.size(); index++) {
            var data = items.get(index).data();
            u16(iloc, index + 1);
            u16(iloc, 0);
            u16(iloc, 0);
            u16(iloc, 1);
            u32(iloc, offset + mdat.size());
            u32(iloc, data.length);
            mdat.writeBytes(data);
        }
        var meta = fullBox("meta", 0, concat(head, fullBox("iloc", 1 << 24, iloc.toByteArray()), tail));
        return concat(ftyp, meta, box("mdat", mdat.toByteArray()));
    }

    /**
     * {@return an {@code ispe} property}
     *
     * @param width the image width in pixels
     * @param height the image height in pixels
     */
    private static byte[] ispe(int width, int height) {
        var out = new ByteArrayOutputStream();
        u32(out, width);
        u32(out, height);
        return fullBox("ispe", 0, out.toByteArray());
    }

    /**
     * {@return an item reference box of version 0}
     *
     * @param type the reference type
     * @param from the referencing item
     * @param to the referenced items
     */
    private static byte[] reference(String type, int from, int... to) {
        var out = new ByteArrayOutputStream();
        u16(out, from);
        u16(out, to.length);
        for (var target : to) {
            u16(out, target);
        }
        return box(type, out.toByteArray());
    }

    /**
     * {@return a box}
     *
     * @param type the box type
     * @param payload the box content after its header
     */
    private static byte[] box(String type, byte[] payload) {
        var out = new ByteArrayOutputStream();
        u32(out, 8L + payload.length);
        out.writeBytes(type.getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /**
     * {@return a full box}
     *
     * @param type the box type
     * @param versionAndFlags the version in the top 8 bits and the flags in the low 24
     * @param payload the payload after the version and flags
     */
    private static byte[] fullBox(String type, int versionAndFlags, byte[] payload) {
        var out = new ByteArrayOutputStream();
        u32(out, versionAndFlags & 0xFFFFFFFFL);
        out.writeBytes(payload);
        return box(type, out.toByteArray());
    }

    /**
     * {@return the parts one after the other}
     *
     * @param parts the byte arrays joined
     */
    private static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Writes a big-endian 16-bit value.
     *
     * @param out the destination
     * @param value the value, its low 16 bits written
     */
    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Writes a big-endian 32-bit value.
     *
     * @param out the destination
     * @param value the value, its low 32 bits written
     */
    private static void u32(ByteArrayOutputStream out, long value) {
        out.write((int) (value >>> 24));
        out.write((int) (value >>> 16));
        out.write((int) (value >>> 8));
        out.write((int) value);
    }
}
