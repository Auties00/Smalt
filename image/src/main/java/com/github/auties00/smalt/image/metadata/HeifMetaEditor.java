package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Replaces the EXIF and XMP items of a HEIC or AVIF file (ISO/IEC 23008-12).
 *
 * <p>The top-level {@code meta} box is rebuilt: the item information ({@code iinf}), locations
 * ({@code iloc}), references ({@code iref}) and property associations ({@code ipma}) lose the
 * removed items and gain the new ones, which describe the primary item through {@code cdsc}
 * references and keep their data in a new {@code mdat} box appended to the file. The absolute
 * offsets of the other items move with the change in size of {@code meta}; the bytes of the
 * removed items are zeroed, so a removed GPS position leaves no trace. The image data is copied
 * unchanged.
 */
final class HeifMetaEditor {
    /**
     * The content type of XMP items.
     */
    static final String XMP_TYPE = "application/rdf+xml";

    /**
     * The largest number of top-level boxes read.
     */
    private static final int MAX_BOXES = 1 << 12;

    /**
     * The largest number of items handled.
     */
    private static final int MAX_ITEMS = 1 << 16;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The offset of the {@code meta} box.
     */
    private final long metaOffset;

    /**
     * The size of the {@code meta} box.
     */
    private final long metaSize;

    /**
     * The bytes of the {@code meta} box.
     */
    private final byte[] meta;

    /**
     * The children of {@code meta}: type, offset within {@link #meta} and size.
     */
    private final List<Object[]> children;

    /**
     * Parses the top-level boxes and the {@code meta} box.
     *
     * @param source the HEIF file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the boxes are corrupt or {@code meta} is missing
     * @throws MediaException.Unsupported if the file is an image sequence ({@code moov})
     */
    HeifMetaEditor(MediaSource source, MediaLimits limits) {
        this.source = source;
        var size = source.size();
        var position = 0L;
        var count = 0;
        long found = -1;
        long foundSize = -1;
        while (size - position >= 8) {
            if (++count > MAX_BOXES) {
                throw new MediaException.Malformed("more than " + MAX_BOXES + " top-level HEIF boxes");
            }
            var header = ImageFiles.readAvailable(source, position, 16);
            long boxSize = u32(header, 0);
            if (boxSize == 1 && header.length == 16) {
                boxSize = ByteArrays.i64be(header, 8);
            } else if (boxSize == 0) {
                boxSize = size - position;
            }
            if (boxSize < 8 || boxSize > size - position) {
                throw new MediaException.Malformed("invalid HEIF box size " + boxSize + " at offset " + position);
            }
            var type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("moov")) {
                throw new MediaException.Unsupported("heif", "editing the metadata of HEIF image sequences is not supported");
            }
            if (type.equals("meta") && found < 0) {
                found = position;
                foundSize = boxSize;
            }
            position += boxSize;
        }
        if (found < 0) {
            throw new MediaException.Malformed("HEIF file without a meta box");
        }
        this.metaOffset = found;
        this.metaSize = foundSize;
        this.meta = ImageFiles.read(source, found, foundSize, limits);
        var headerSize = u32(meta, 0) == 1 ? 16 : 8;
        this.children = new ArrayList<>();
        var at = headerSize + 4;
        while (meta.length - at >= 8 && children.size() < MAX_BOXES) {
            long childSize = u32(meta, at);
            if (childSize < 8 || childSize > meta.length - at) {
                throw new MediaException.Malformed("invalid HEIF meta child size " + childSize);
            }
            children.add(new Object[]{new String(meta, at + 4, 4, StandardCharsets.ISO_8859_1), at, (int) childSize});
            at += (int) childSize;
        }
    }

    /**
     * Plans the output with the EXIF and XMP items replaced.
     *
     * @param replaceExif whether the EXIF items change
     * @param exif the new EXIF block, or {@code null} to remove the items
     * @param replaceXmp whether the XMP items change
     * @param xmp the new XMP packet bytes, or {@code null} to remove the items
     * @return the plan
     * @throws MediaException.Malformed if an item box is corrupt
     * @throws MediaException.Unsupported if the file has no primary item
     */
    EditPlan plan(boolean replaceExif, byte[] exif, boolean replaceXmp, byte[] xmp) {
        var iloc = Iloc.parse(child("iloc"));
        var infos = parseInfos(child("iinf"));
        var pitm = child("pitm");
        if (pitm == null || pitm.length < 14) {
            throw new MediaException.Unsupported("heif", "a HEIF file without a primary item cannot be edited");
        }
        var primary = (pitm[8] & 0xFF) == 0 ? u16(pitm, 12) : u32(pitm, 12);
        var removed = new HashSet<Long>();
        var maxId = 0L;
        for (var info : infos) {
            maxId = Math.max(maxId, info.id());
            if ((replaceExif && info.type().equals("Exif")) || (replaceXmp && info.type().equals("mime")
                    && info.contentType().toLowerCase(Locale.ROOT).startsWith(XMP_TYPE))) {
                removed.add(info.id());
            }
        }
        var added = new ArrayList<Object[]>();
        if (replaceExif && exif != null) {
            var payload = new byte[4 + exif.length];
            System.arraycopy(exif, 0, payload, 4, exif.length);
            added.add(new Object[]{++maxId, "Exif", "", payload});
        }
        if (replaceXmp && xmp != null) {
            added.add(new Object[]{++maxId, "mime", XMP_TYPE, xmp});
        }
        var wide = maxId > 0xFFFF || primary > 0xFFFF;
        var zeros = new ArrayList<long[]>();
        var idatZeros = new ArrayList<long[]>();
        for (var item : iloc.items()) {
            if (!removed.contains(item.id())) {
                continue;
            }
            for (var extent : item.extents()) {
                var range = new long[]{item.baseOffset() + extent[0], extent[1]};
                if (item.method() == 0) {
                    zeros.add(range);
                } else if (item.method() == 1) {
                    idatZeros.add(range);
                }
            }
        }
        var newDataLength = 0L;
        for (var item : added) {
            newDataLength += ((byte[]) item[3]).length;
        }
        var draft = buildMeta(iloc, infos, removed, added, primary, wide, 0, 0, idatZeros);
        var delta = draft.length - metaSize;
        var mdatStart = source.size() + delta;
        var built = buildMeta(iloc, infos, removed, added, primary, wide, delta, mdatStart + 8, idatZeros);
        zeros.sort((a, b) -> Long.compare(a[0], b[0]));
        var plan = EditPlan.builder();
        copyZeroed(plan, 0, metaOffset, zeros);
        plan.bytes(built);
        copyZeroed(plan, metaOffset + metaSize, source.size(), zeros);
        if (!added.isEmpty()) {
            var mdat = new ByteWriter((int) Math.min(Integer.MAX_VALUE - 8, 8 + newDataLength));
            mdat.begin(FourCc.of("mdat"));
            for (var item : added) {
                mdat.bytes((byte[]) item[3]);
            }
            mdat.end();
            plan.bytes(mdat.toByteArray());
        }
        return plan.build();
    }

    /**
     * Copies a range of the original, writing zeros over the parts of removed items.
     *
     * @param plan the plan receiving the pieces
     * @param start the offset of the range
     * @param end the end of the range
     * @param zeros the ranges of removed items, sorted by offset
     */
    private static void copyZeroed(EditPlan.Builder plan, long start, long end, List<long[]> zeros) {
        var position = start;
        for (var range : zeros) {
            var from = Math.max(position, range[0]);
            var to = Math.min(end, range[0] + range[1]);
            if (to <= from) {
                continue;
            }
            plan.copy(position, from - position);
            for (var at = from; at < to; at += Integer.MAX_VALUE / 2) {
                plan.bytes(new byte[(int) Math.min(Integer.MAX_VALUE / 2, to - at)]);
            }
            position = to;
        }
        plan.copy(position, end - position);
    }

    /**
     * Builds the new {@code meta} box.
     *
     * @param iloc the parsed locations
     * @param infos the parsed item information
     * @param removed the identifiers of the removed items
     * @param added the new items: identifier, type, content type and data
     * @param primary the primary item identifier
     * @param wide whether identifiers need 32 bits
     * @param delta the shift of the data after the old {@code meta}
     * @param dataStart the offset of the new items' data
     * @param idatZeros the ranges of the {@code idat} data to zero
     * @return the box
     */
    private byte[] buildMeta(Iloc iloc, List<Info> infos, Set<Long> removed, List<Object[]> added, long primary, boolean wide,
                             long delta, long dataStart, List<long[]> idatZeros) {
        var metaEnd = metaOffset + metaSize;
        var body = new ByteWriter(meta.length + 256);
        var headerSize = u32(meta, 0) == 1 ? 16 : 8;
        body.begin(FourCc.of("meta"));
        body.bytes(meta, headerSize, 4);
        var irefWritten = false;
        for (var child : children) {
            var type = (String) child[0];
            var offset = (int) child[1];
            var size = (int) child[2];
            switch (type) {
                case "iloc" -> iloc.encode(body, removed, added, metaEnd, delta, dataStart);
                case "iinf" -> encodeInfos(body, meta, offset, infos, removed, added);
                case "iref" -> {
                    encodeReferences(body, Arrays.copyOfRange(meta, offset, offset + size), removed, added, primary, wide);
                    irefWritten = true;
                }
                case "iprp" -> filterAssociations(body, Arrays.copyOfRange(meta, offset, offset + size), removed);
                case "idat" -> {
                    var idat = Arrays.copyOfRange(meta, offset, offset + size);
                    for (var range : idatZeros) {
                        var from = 8 + range[0];
                        for (var at = from; at < from + range[1] && at < idat.length; at++) {
                            if (at >= 8) {
                                idat[(int) at] = 0;
                            }
                        }
                    }
                    body.bytes(idat);
                }
                default -> body.bytes(meta, offset, size);
            }
            if (type.equals("iinf") && !added.isEmpty() && child("iref") == null && !irefWritten) {
                encodeReferences(body, null, removed, added, primary, wide);
                irefWritten = true;
            }
        }
        body.end();
        return body.toByteArray();
    }

    /**
     * Returns the bytes of the first child of {@code meta} with a type.
     *
     * @param type the four-character box type
     * @return the box, or {@code null}
     */
    private byte[] child(String type) {
        for (var child : children) {
            if (child[0].equals(type)) {
                var offset = (int) child[1];
                return Arrays.copyOfRange(meta, offset, offset + (int) child[2]);
            }
        }
        return null;
    }

    /**
     * Parses the item information entries.
     *
     * @param iinf the {@code iinf} box
     * @return the entries
     * @throws MediaException.Malformed if the box is missing or corrupt
     */
    private static List<Info> parseInfos(byte[] iinf) {
        if (iinf == null || iinf.length < 14) {
            throw new MediaException.Malformed("HEIF file without an iinf box");
        }
        var version = iinf[8] & 0xFF;
        var at = version == 0 ? 14 : 16;
        var result = new ArrayList<Info>();
        while (iinf.length - at >= 8 && result.size() < MAX_ITEMS) {
            var size = (int) u32(iinf, at);
            if (size < 8 || size > iinf.length - at) {
                throw new MediaException.Malformed("invalid HEIF infe box size " + size);
            }
            var infeVersion = iinf[at + 8] & 0xFF;
            if (infeVersion < 2 || size < 20) {
                result.add(new Info(-1, "", "", at, size));
                at += size;
                continue;
            }
            var idSize = infeVersion == 2 ? 2 : 4;
            var position = at + 12;
            var id = idSize == 2 ? u16(iinf, position) : u32(iinf, position);
            position += idSize + 2;
            if (position + 4 > at + size) {
                throw new MediaException.Malformed("truncated HEIF infe box");
            }
            var type = new String(iinf, position, 4, StandardCharsets.ISO_8859_1);
            position += 4;
            var contentType = "";
            var nameEnd = terminator(iinf, position, at + size);
            if (type.equals("mime") && nameEnd + 1 < at + size) {
                var typeEnd = terminator(iinf, nameEnd + 1, at + size);
                contentType = new String(iinf, nameEnd + 1, typeEnd - nameEnd - 1, StandardCharsets.UTF_8);
            }
            result.add(new Info(id, type, contentType, at, size));
            at += size;
        }
        return result;
    }

    /**
     * Writes the item information box without the removed items and with the new ones.
     *
     * @param out the destination
     * @param meta the bytes of {@code meta}
     * @param offset the offset of {@code iinf} within {@code meta}
     * @param infos the parsed entries
     * @param removed the removed identifiers
     * @param added the new items
     */
    private static void encodeInfos(ByteWriter out, byte[] meta, int offset, List<Info> infos, Set<Long> removed,
                                    List<Object[]> added) {
        var version = meta[offset + 8] & 0xFF;
        var count = (long) added.size();
        for (var info : infos) {
            if (!removed.contains(info.id())) {
                count++;
            }
        }
        var wideCount = version != 0 || count > 0xFFFF;
        out.beginFull(FourCc.of("iinf"), wideCount ? 1 : 0, 0);
        uint(out, count, wideCount ? 4 : 2);
        for (var info : infos) {
            if (!removed.contains(info.id())) {
                out.bytes(meta, offset + info.offset(), info.size());
            }
        }
        for (var item : added) {
            var id = (long) item[0];
            var type = (String) item[1];
            var contentType = (String) item[2];
            out.beginFull(FourCc.of("infe"), id > 0xFFFF ? 3 : 2, 0);
            uint(out, id, id > 0xFFFF ? 4 : 2);
            out.u16be(0);
            out.bytes(type.getBytes(StandardCharsets.ISO_8859_1));
            out.u8(0);
            if (!contentType.isEmpty()) {
                out.cString(contentType);
            }
            out.end();
        }
        out.end();
    }

    /**
     * Writes the item reference box without references from or to removed items and with a
     * {@code cdsc} reference from every new item to the primary item; nothing is written when no
     * reference remains.
     *
     * @param out the destination
     * @param iref the old box, or {@code null}
     * @param removed the removed identifiers
     * @param added the new items
     * @param primary the primary item identifier
     * @param wide whether identifiers need 32 bits
     * @throws MediaException.Malformed if the old box is corrupt
     */
    private static void encodeReferences(ByteWriter out, byte[] iref, Set<Long> removed, List<Object[]> added,
                                         long primary, boolean wide) {
        var references = new ArrayList<Object[]>();
        var version = 0;
        if (iref != null) {
            if (iref.length < 12) {
                throw new MediaException.Malformed("truncated HEIF iref box");
            }
            version = iref[8] & 0xFF;
            var idSize = version == 0 ? 2 : 4;
            var at = 12;
            while (iref.length - at >= 8 + idSize + 2 && references.size() < MAX_ITEMS) {
                var size = (int) u32(iref, at);
                if (size < 8 + idSize + 2 || size > iref.length - at) {
                    throw new MediaException.Malformed("invalid HEIF reference box size " + size);
                }
                var type = new String(iref, at + 4, 4, StandardCharsets.ISO_8859_1);
                var from = idSize == 2 ? u16(iref, at + 8) : u32(iref, at + 8);
                var count = u16(iref, at + 8 + idSize);
                var targets = new ArrayList<Long>();
                var position = at + 8 + idSize + 2;
                for (var i = 0; i < count && position + idSize <= at + size; i++) {
                    var to = idSize == 2 ? u16(iref, position) : u32(iref, position);
                    if (!removed.contains(to)) {
                        targets.add(to);
                    }
                    position += idSize;
                }
                if (!removed.contains(from) && !targets.isEmpty()) {
                    references.add(new Object[]{type, from, targets});
                }
                at += size;
            }
        }
        for (var item : added) {
            references.add(new Object[]{"cdsc", item[0], List.of(primary)});
        }
        if (references.isEmpty()) {
            return;
        }
        var idSize = version == 1 || wide ? 4 : 2;
        out.beginFull(FourCc.of("iref"), idSize == 4 ? 1 : 0, 0);
        for (var reference : references) {
            @SuppressWarnings("unchecked")
            var targets = (List<Long>) reference[2];
            out.begin(FourCc.of((String) reference[0]));
            uint(out, (long) reference[1], idSize);
            out.u16be(targets.size());
            for (var target : targets) {
                uint(out, target, idSize);
            }
            out.end();
        }
        out.end();
    }

    /**
     * Writes an {@code iprp} box without the property associations of removed items.
     *
     * @param out the destination
     * @param iprp the box
     * @param removed the removed identifiers
     * @throws MediaException.Malformed if the box is corrupt
     */
    private static void filterAssociations(ByteWriter out, byte[] iprp, Set<Long> removed) {
        if (removed.isEmpty()) {
            out.bytes(iprp);
            return;
        }
        var at = 8;
        out.begin(FourCc.of("iprp"));
        while (iprp.length - at >= 8) {
            var size = (int) u32(iprp, at);
            if (size < 8 || size > iprp.length - at) {
                throw new MediaException.Malformed("invalid HEIF iprp child size " + size);
            }
            var type = new String(iprp, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (!type.equals("ipma") || size < 16) {
                out.bytes(iprp, at, size);
                at += size;
                continue;
            }
            var version = iprp[at + 8] & 0xFF;
            var flags = (int) (u32(iprp, at + 8) & 0xFFFFFF);
            var count = u32(iprp, at + 12);
            var position = at + 16;
            out.begin(FourCc.of("ipma"));
            out.bytes(iprp, at + 8, 4);
            var countAt = out.size();
            out.u32be(0);
            var kept = 0L;
            for (var i = 0L; i < count; i++) {
                var start = position;
                var idSize = version < 1 ? 2 : 4;
                if (position + idSize + 1 > at + size) {
                    throw new MediaException.Malformed("truncated HEIF ipma box");
                }
                var id = idSize == 2 ? u16(iprp, position) : u32(iprp, position);
                position += idSize;
                var associations = iprp[position++] & 0xFF;
                position += associations * ((flags & 1) != 0 ? 2 : 1);
                if (position > at + size) {
                    throw new MediaException.Malformed("truncated HEIF ipma box");
                }
                if (!removed.contains(id)) {
                    out.bytes(iprp, start, position - start);
                    kept++;
                }
            }
            out.patch32be(countAt, kept);
            out.end();
            at += size;
        }
        out.end();
    }

    /**
     * Returns the index of the next NUL byte.
     *
     * @param data the bytes
     * @param start the index to search from
     * @param end the end of the region
     * @return the index, or {@code end} when there is none
     */
    private static int terminator(byte[] data, int start, int end) {
        for (var i = start; i < end; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return end;
    }

    /**
     * Writes a big-endian unsigned integer of a size.
     *
     * @param out the destination
     * @param value the value; bits above the field are dropped
     * @param size the field size in bytes
     */
    private static void uint(ByteWriter out, long value, int size) {
        for (var i = size - 1; i >= 0; i--) {
            out.u8((int) (value >>> (8 * i)));
        }
    }

    /**
     * {@return a big-endian unsigned 16-bit integer}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     */
    private static long u16(byte[] data, int offset) {
        return ByteArrays.u16be(data, offset);
    }

    /**
     * {@return a big-endian unsigned 32-bit integer}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     */
    private static long u32(byte[] data, int offset) {
        return ByteArrays.u32be(data, offset);
    }

    /**
     * Reads a big-endian unsigned integer of a size.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param size the size in bytes, 0, 4 or 8
     * @return the value, {@code 0} for size 0
     */
    private static long uint(byte[] data, int offset, int size) {
        return switch (size) {
            case 0 -> 0;
            case 4 -> u32(data, offset);
            case 8 -> ByteArrays.i64be(data, offset);
            default -> throw new MediaException.Malformed("invalid HEIF iloc field size " + size);
        };
    }

    /**
     * Holds one item information entry.
     *
     * @param id the item identifier, {@code -1} for an entry of an old version
     * @param type the item type
     * @param contentType the content type of a {@code mime} item
     * @param offset the offset of the {@code infe} box within {@code iinf}
     * @param size the size of the {@code infe} box
     */
    private record Info(long id, String type, String contentType, int offset, int size) {
    }

    /**
     * Holds a parsed item location box.
     *
     * @param version the box version
     * @param offsetSize the size of offset fields
     * @param lengthSize the size of length fields
     * @param baseOffsetSize the size of base offset fields
     * @param indexSize the size of extent index fields
     * @param items the item locations, in box order
     */
    private record Iloc(int version, int offsetSize, int lengthSize, int baseOffsetSize, int indexSize, List<Item> items) {
        /**
         * Parses a box.
         *
         * @param iloc the box
         * @return the parsed box
         * @throws MediaException.Malformed if the box is missing or corrupt
         */
        static Iloc parse(byte[] iloc) {
            if (iloc == null || iloc.length < 16) {
                throw new MediaException.Malformed("HEIF file without an iloc box");
            }
            var version = iloc[8] & 0xFF;
            if (version > 2) {
                throw new MediaException.Unsupported("heif", "iloc version " + version);
            }
            var offsetSize = (iloc[12] & 0xFF) >>> 4;
            var lengthSize = iloc[12] & 0x0F;
            var baseOffsetSize = (iloc[13] & 0xFF) >>> 4;
            var indexSize = version >= 1 ? iloc[13] & 0x0F : 0;
            var at = 14;
            long count;
            if (version < 2) {
                count = u16(iloc, at);
                at += 2;
            } else {
                count = u32(iloc, at);
                at += 4;
            }
            if (count > MAX_ITEMS) {
                throw new MediaException.Malformed("HEIF iloc box with " + count + " items");
            }
            var items = new ArrayList<Item>();
            for (var i = 0L; i < count; i++) {
                var need = (version < 2 ? 2 : 4) + (version >= 1 ? 2 : 0) + 2 + baseOffsetSize + 2;
                if (at + need > iloc.length) {
                    throw new MediaException.Malformed("truncated HEIF iloc box");
                }
                var id = version < 2 ? u16(iloc, at) : u32(iloc, at);
                at += version < 2 ? 2 : 4;
                var method = 0;
                if (version >= 1) {
                    method = (int) (u16(iloc, at) & 0x0F);
                    at += 2;
                }
                var reference = (int) u16(iloc, at);
                at += 2;
                var base = uint(iloc, at, baseOffsetSize);
                at += baseOffsetSize;
                var extentCount = (int) u16(iloc, at);
                at += 2;
                var extents = new ArrayList<long[]>();
                for (var e = 0; e < extentCount; e++) {
                    if (at + indexSize + offsetSize + lengthSize > iloc.length) {
                        throw new MediaException.Malformed("truncated HEIF iloc extent");
                    }
                    var index = indexSize > 0 ? uint(iloc, at, indexSize) : 0;
                    at += indexSize;
                    var offset = uint(iloc, at, offsetSize);
                    at += offsetSize;
                    var length = uint(iloc, at, lengthSize);
                    at += lengthSize;
                    extents.add(new long[]{offset, length, index});
                }
                items.add(new Item(id, method, reference, base, extents));
            }
            return new Iloc(version, offsetSize, lengthSize, baseOffsetSize, indexSize, items);
        }

        /**
         * Writes the box without the removed items, with the new ones, and with the absolute
         * offsets past the old {@code meta} shifted.
         *
         * @param out the destination
         * @param removed the removed identifiers
         * @param added the new items: identifier, type, content type and data
         * @param metaEnd the end of the old {@code meta}
         * @param delta the shift
         * @param dataStart the offset of the new items' data
         * @throws MediaException.LimitExceeded if a shifted offset does not fit its field
         */
        void encode(ByteWriter out, Set<Long> removed, List<Object[]> added, long metaEnd, long delta,
                    long dataStart) {
            var wideIds = version == 2;
            for (var item : added) {
                wideIds |= (long) item[0] > 0xFFFF;
            }
            var newVersion = wideIds ? 2 : version;
            var newOffsetSize = Math.max(offsetSize, 4);
            var newLengthSize = Math.max(lengthSize, 4);
            var kept = 0L;
            for (var item : items) {
                if (!removed.contains(item.id())) {
                    kept++;
                }
            }
            var countSize = newVersion < 2 ? 2 : 4;
            out.beginFull(FourCc.of("iloc"), newVersion, 0);
            out.u8(newOffsetSize << 4 | newLengthSize);
            out.u8(baseOffsetSize << 4 | (newVersion >= 1 ? indexSize : 0));
            uint(out, kept + added.size(), countSize);
            for (var item : items) {
                if (removed.contains(item.id())) {
                    continue;
                }
                var base = item.baseOffset();
                var shiftBase = item.method() == 0 && baseOffsetSize > 0 && base >= metaEnd;
                writeItemHeader(out, item.id(), newVersion, item.method(), item.reference(), shiftBase ? base + delta : base,
                        item.extents().size());
                for (var extent : item.extents()) {
                    if (indexSize > 0) {
                        uint(out, extent[2], indexSize);
                    }
                    var offset = extent[0];
                    if (item.method() == 0 && !shiftBase && base + offset >= metaEnd && !(offsetSize == 0)) {
                        offset += delta;
                    }
                    writeChecked(out, offset, newOffsetSize);
                    writeChecked(out, extent[1], newLengthSize);
                }
            }
            for (var item : added) {
                var data = (byte[]) item[3];
                writeItemHeader(out, (long) item[0], newVersion, 0, 0, 0, 1);
                if (indexSize > 0) {
                    uint(out, 0, indexSize);
                }
                writeChecked(out, dataStart, newOffsetSize);
                writeChecked(out, data.length, newLengthSize);
                dataStart += data.length;
            }
            out.end();
        }

        /**
         * Writes the fixed part of an item entry.
         *
         * @param out the destination
         * @param id the item identifier
         * @param version the box version
         * @param method the construction method
         * @param reference the data reference index
         * @param base the base offset
         * @param extentCount the number of extents
         */
        private void writeItemHeader(ByteWriter out, long id, int version, int method, int reference, long base,
                                     int extentCount) {
            uint(out, id, version < 2 ? 2 : 4);
            if (version >= 1) {
                out.u16be(method);
            }
            out.u16be(reference);
            if (baseOffsetSize > 0) {
                writeChecked(out, base, baseOffsetSize);
            }
            out.u16be(extentCount);
        }

        /**
         * Writes an unsigned value that must fit its field.
         *
         * @param out the destination
         * @param value the unsigned value
         * @param size the field size, 4 or 8
         * @throws MediaException.LimitExceeded if the value does not fit
         */
        private static void writeChecked(ByteWriter out, long value, int size) {
            if (size == 4 && (value < 0 || value > 0xFFFFFFFFL)) {
                throw new MediaException.LimitExceeded("HEIF offset " + value + " does not fit 32 bits");
            }
            uint(out, value, size);
        }
    }

    /**
     * Holds one item location.
     *
     * @param id the item identifier
     * @param method the construction method: 0 file offsets, 1 {@code idat} offsets
     * @param reference the data reference index
     * @param baseOffset the base offset
     * @param extents the extents: offset, length and index
     */
    private record Item(long id, int method, int reference, long baseOffset, List<long[]> extents) {
    }
}
