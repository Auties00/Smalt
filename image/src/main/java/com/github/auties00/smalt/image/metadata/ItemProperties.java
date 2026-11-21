package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Rewrites the item properties box ({@code iprp}) of a HEIF file: drops the associations of
 * removed items and replaces, adds or removes the ICC {@code colr} property of the primary item
 * (ISO/IEC 23008-12, 9.3).
 */
final class ItemProperties {
    /**
     * The largest number of properties or associations handled.
     */
    private static final int MAX_ENTRIES = 1 << 16;

    /**
     * The largest property index an association can hold, in its 15-bit form.
     */
    private static final int MAX_INDEX = 0x7FFF;

    /**
     * The property types that transform the image, which descriptive properties precede.
     */
    private static final Set<String> TRANSFORMATIVE = Set.of("irot", "imir", "clap", "iscl");

    /**
     * Prevents instantiation.
     */
    private ItemProperties() {
        throw new AssertionError();
    }

    /**
     * Writes an {@code iprp} box with the associations of removed items dropped and the primary
     * item's ICC profile changed.
     *
     * @param out the destination
     * @param iprp the box
     * @param removed the identifiers of the removed items
     * @param primary the identifier of the primary item
     * @param replaceIcc whether the ICC profile changes
     * @param icc the new profile, or {@code null} to remove it
     * @throws MediaException.Malformed if the box is corrupt
     * @throws MediaException.LimitExceeded if the new property cannot be indexed
     */
    static void encode(ByteWriter out, byte[] iprp, Set<Long> removed, long primary, boolean replaceIcc, byte[] icc) {
        if (removed.isEmpty() && !replaceIcc) {
            out.bytes(iprp);
            return;
        }
        var children = new ArrayList<Object[]>();
        List<byte[]> properties = null;
        var maps = new ArrayList<Ipma>();
        var at = headerSize(iprp);
        while (iprp.length - at >= 8) {
            var size = boxSize(iprp, at);
            var type = new String(iprp, at + 4, 4, StandardCharsets.ISO_8859_1);
            if (type.equals("ipco") && properties == null) {
                properties = parseContainer(Arrays.copyOfRange(iprp, at, at + size));
                children.add(new Object[]{type, null});
            } else if (type.equals("ipma") && size >= 16) {
                maps.add(Ipma.parse(iprp, at, size));
                children.add(new Object[]{type, maps.getLast()});
            } else {
                children.add(new Object[]{type, Arrays.copyOfRange(iprp, at, at + size)});
            }
            at += size;
        }
        if (properties == null) {
            properties = new ArrayList<>();
            if (replaceIcc && icc != null) {
                children.addFirst(new Object[]{"ipco", null});
            }
        }
        for (var map : maps) {
            map.entries().removeIf(entry -> removed.contains(entry.id()));
        }
        if (replaceIcc) {
            changeIcc(properties, maps, primary, icc);
        }
        out.begin(FourCc.of("iprp"));
        for (var child : children) {
            switch (child[1]) {
                case null -> {
                    out.begin(FourCc.of("ipco"));
                    for (var property : properties) {
                        out.bytes(property);
                    }
                    out.end();
                }
                case Ipma map -> map.encode(out);
                case byte[] bytes -> out.bytes(bytes);
                default -> throw new AssertionError();
            }
        }
        out.end();
    }

    /**
     * Applies the change of the primary item's ICC profile to the parsed properties and
     * associations.
     *
     * @param properties the properties of {@code ipco}, updated
     * @param maps the association boxes, updated
     * @param primary the identifier of the primary item
     * @param icc the new profile, or {@code null} to remove it
     * @throws MediaException.LimitExceeded if the new property cannot be indexed
     */
    private static void changeIcc(List<byte[]> properties, List<Ipma> maps, long primary, byte[] icc) {
        var entry = primaryEntry(maps, primary, icc != null);
        if (entry == null) {
            return;
        }
        var iccAssociations = new ArrayList<int[]>();
        for (var association : entry.associations()) {
            if (isIcc(properties, association[0])) {
                iccAssociations.add(association);
            }
        }
        entry.associations().removeAll(iccAssociations);
        if (icc != null) {
            var box = colr(icc);
            var shared = iccAssociations.isEmpty() || referenced(maps, iccAssociations.getFirst()[0]);
            int index;
            if (!shared) {
                index = iccAssociations.getFirst()[0];
                properties.set(index - 1, box);
                iccAssociations.removeFirst();
            } else {
                if (properties.size() >= MAX_INDEX) {
                    throw new MediaException.LimitExceeded("a HEIF ipco box with " + properties.size() + " properties");
                }
                properties.add(box);
                index = properties.size();
            }
            var position = entry.associations().size();
            for (var i = 0; i < entry.associations().size(); i++) {
                if (TRANSFORMATIVE.contains(type(properties, entry.associations().get(i)[0]))) {
                    position = i;
                    break;
                }
            }
            entry.associations().add(position, new int[]{index, 0});
        }
        var unused = new ArrayList<Integer>();
        for (var association : iccAssociations) {
            if (!referenced(maps, association[0]) && !unused.contains(association[0])) {
                unused.add(association[0]);
            }
        }
        unused.sort((a, b) -> Integer.compare(b, a));
        for (var index : unused) {
            properties.remove(index - 1);
            for (var map : maps) {
                for (var other : map.entries()) {
                    for (var association : other.associations()) {
                        if (association[0] > index) {
                            association[0]--;
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns the association entry of the primary item, creating one when a profile is added to
     * an item that has none.
     *
     * @param maps the association boxes
     * @param primary the identifier of the primary item
     * @param create whether to create a missing entry
     * @return the entry, or {@code null} when it is missing and not created
     */
    private static Entry primaryEntry(List<Ipma> maps, long primary, boolean create) {
        for (var map : maps) {
            for (var entry : map.entries()) {
                if (entry.id() == primary) {
                    return entry;
                }
            }
        }
        if (!create) {
            return null;
        }
        if (maps.isEmpty()) {
            throw new MediaException.Unsupported("heif", "a HEIF file without property associations cannot gain an ICC "
                    + "profile");
        }
        var map = maps.getFirst();
        var entry = new Entry(primary, new ArrayList<>());
        var position = 0;
        while (position < map.entries().size() && map.entries().get(position).id() < primary) {
            position++;
        }
        map.entries().add(position, entry);
        return entry;
    }

    /**
     * Returns whether any item is associated with a property.
     *
     * @param maps the association boxes
     * @param index the one-based property index
     * @return {@code true} when an association refers to it
     */
    private static boolean referenced(List<Ipma> maps, int index) {
        for (var map : maps) {
            for (var entry : map.entries()) {
                for (var association : entry.associations()) {
                    if (association[0] == index) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns whether a property is an ICC {@code colr} property.
     *
     * @param properties the properties of {@code ipco}
     * @param index the one-based property index
     * @return {@code true} for a {@code colr} box of colour type {@code prof} or {@code rICC}
     */
    private static boolean isIcc(List<byte[]> properties, int index) {
        if (index < 1 || index > properties.size()) {
            return false;
        }
        var box = properties.get(index - 1);
        if (box.length < 12 || !type(properties, index).equals("colr")) {
            return false;
        }
        var colourType = new String(box, 8, 4, StandardCharsets.ISO_8859_1);
        return colourType.equals("prof") || colourType.equals("rICC");
    }

    /**
     * Returns the type of a property.
     *
     * @param properties the properties of {@code ipco}
     * @param index the one-based property index
     * @return the four-character type, or an empty string for an index out of range
     */
    private static String type(List<byte[]> properties, int index) {
        if (index < 1 || index > properties.size()) {
            return "";
        }
        return new String(properties.get(index - 1), 4, 4, StandardCharsets.ISO_8859_1);
    }

    /**
     * Builds a {@code colr} property holding an unrestricted ICC profile.
     *
     * @param icc the profile
     * @return the box
     */
    private static byte[] colr(byte[] icc) {
        var out = new ByteWriter(icc.length + 12);
        out.begin(FourCc.of("colr"));
        out.fourCc("prof");
        out.bytes(icc);
        out.end();
        return out.toByteArray();
    }

    /**
     * Splits the children of a container box.
     *
     * @param box the container box
     * @return the child boxes, in order
     * @throws MediaException.Malformed if a child is corrupt
     */
    private static List<byte[]> parseContainer(byte[] box) {
        var result = new ArrayList<byte[]>();
        var at = headerSize(box);
        while (box.length - at >= 8) {
            if (result.size() >= MAX_ENTRIES) {
                throw new MediaException.Malformed("more than " + MAX_ENTRIES + " HEIF item properties");
            }
            var size = boxSize(box, at);
            result.add(Arrays.copyOfRange(box, at, at + size));
            at += size;
        }
        return result;
    }

    /**
     * Returns the header size of a box with a 32-bit size field.
     *
     * @param box the box bytes, starting with its size field
     * @return 8
     * @throws MediaException.Malformed if the box uses a 64-bit size, which property boxes never
     * need
     */
    private static int headerSize(byte[] box) {
        if (box.length >= 4 && ByteArrays.u32be(box, 0) == 1) {
            throw new MediaException.Malformed("a HEIF property box with a 64-bit size");
        }
        return 8;
    }

    /**
     * Returns the size of a child box, checked against its parent.
     *
     * @param data the parent box
     * @param at the offset of the child
     * @return the size
     * @throws MediaException.Malformed if the size is invalid
     */
    private static int boxSize(byte[] data, int at) {
        var size = ByteArrays.u32be(data, at);
        if (size < 8 || size > data.length - at) {
            throw new MediaException.Malformed("invalid HEIF property box size " + size);
        }
        return (int) size;
    }

    /**
     * Holds the associations of one item.
     *
     * @param id the item identifier
     * @param associations the associations, in order: one-based property index and essential flag
     */
    private record Entry(long id, List<int[]> associations) {
    }

    /**
     * Holds a parsed item property association box.
     *
     * @param version the box version
     * @param flags the box flags; bit 0 selects 15-bit property indices
     * @param entries the entries, in order
     */
    private record Ipma(int version, int flags, List<Entry> entries) {
        /**
         * Parses a box.
         *
         * @param data the array holding the box
         * @param at the offset of the box
         * @param size the size of the box
         * @return the parsed box
         * @throws MediaException.Malformed if the box is truncated
         */
        static Ipma parse(byte[] data, int at, int size) {
            var version = data[at + 8] & 0xFF;
            var flags = (int) (ByteArrays.u32be(data, at + 8) & 0xFFFFFF);
            var count = ByteArrays.u32be(data, at + 12);
            if (count > MAX_ENTRIES) {
                throw new MediaException.Malformed("a HEIF ipma box with " + count + " entries");
            }
            var position = at + 16;
            var end = at + size;
            var idSize = version < 1 ? 2 : 4;
            var wide = (flags & 1) != 0;
            var entries = new ArrayList<Entry>();
            for (var i = 0L; i < count; i++) {
                if (position + idSize + 1 > end) {
                    throw new MediaException.Malformed("truncated HEIF ipma box");
                }
                var id = idSize == 2 ? ByteArrays.u16be(data, position) : ByteArrays.u32be(data, position);
                position += idSize;
                var associationCount = data[position++] & 0xFF;
                if (position + associationCount * (wide ? 2 : 1) > end) {
                    throw new MediaException.Malformed("truncated HEIF ipma box");
                }
                var associations = new ArrayList<int[]>();
                for (var a = 0; a < associationCount; a++) {
                    if (wide) {
                        var value = ByteArrays.u16be(data, position);
                        associations.add(new int[]{value & 0x7FFF, value >>> 15});
                        position += 2;
                    } else {
                        var value = data[position++] & 0xFF;
                        associations.add(new int[]{value & 0x7F, value >>> 7});
                    }
                }
                entries.add(new Entry(id, associations));
            }
            return new Ipma(version, flags, entries);
        }

        /**
         * Writes the box, widening its identifiers or property indices when an entry needs it.
         *
         * @param out the destination
         * @throws MediaException.LimitExceeded if an entry has more than 255 associations
         */
        void encode(ByteWriter out) {
            var newVersion = version;
            var newFlags = flags;
            for (var entry : entries) {
                if (entry.id() > 0xFFFF) {
                    newVersion = Math.max(newVersion, 1);
                }
                if (entry.associations().size() > 0xFF) {
                    throw new MediaException.LimitExceeded("a HEIF item with more than 255 property associations");
                }
                for (var association : entry.associations()) {
                    if (association[0] > 0x7F) {
                        newFlags |= 1;
                    }
                }
            }
            var wide = (newFlags & 1) != 0;
            out.beginFull(FourCc.of("ipma"), newVersion, newFlags);
            out.u32be(entries.size());
            for (var entry : entries) {
                if (newVersion < 1) {
                    out.u16be((int) entry.id());
                } else {
                    out.u32be(entry.id());
                }
                out.u8(entry.associations().size());
                for (var association : entry.associations()) {
                    if (wide) {
                        out.u16be(association[1] << 15 | association[0]);
                    } else {
                        out.u8(association[1] << 7 | association[0]);
                    }
                }
            }
            out.end();
        }
    }
}
