package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit.Change;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffTypes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntPredicate;

/**
 * Plans the edit of the metadata of a TIFF or BigTIFF file without moving any of its other bytes.
 *
 * <p>IFD0 is rebuilt: its structural entries ({@link TiffFile#isStructural(int)}) are copied as
 * stored, the descriptive ones come from the resolved EXIF block, and the XMP, IPTC, ICC and
 * Photoshop entries follow their changes; the Exif, GPS and interoperability directories are
 * rebuilt when the EXIF block changes. A value equal to the one an entry already stores keeps its
 * offset. Every other byte of the file stays where it is: the strips and tiles, the other pages
 * and their sub-directories, and whatever they or a kept entry reference.
 *
 * <p>The old directories and the values no entry references any more are zeroed. The new
 * directories and values are written over that space where they fit, IFD0 preferably at its old
 * offset, and appended otherwise. When the freed space ends the file it is cut off and the
 * appended data starts there, together with the zeros before it that nothing references (space
 * an earlier edit freed), unless a maker note, an entry of an unknown type or a
 * {@code LONG} or {@code LONG8} entry of an unrecognized tag, which may hold an offset, is kept.
 * Every copied range of the plan therefore lands at its original offset, so the plan is
 * {@linkplain EditPlan#isInPlace() in place}, and the header changes only when IFD0 moves.
 *
 * <p>Instances are single-use and not thread-safe.
 */
final class TiffRewriter {
    /**
     * The tags whose integer values are offsets of directories, besides the entries of the
     * {@code IFD} and {@code IFD8} types.
     */
    private static final Set<Integer> POINTERS = Set.of(TiffFile.SUB_IFDS, ExifData.EXIF_IFD_POINTER,
            ExifData.GPS_IFD_POINTER, ExifData.INTEROP_IFD_POINTER, TiffFile.GLOBAL_PARAMETERS);

    /**
     * The tag of the offsets of the old-style JPEG quantization tables, 64 bytes each.
     */
    private static final int JPEG_Q_TABLES = 519;

    /**
     * The tags of the offsets of the old-style JPEG DC and AC Huffman tables, each 16 code length
     * counts followed by as many values as they sum to.
     */
    private static final int[] JPEG_HUFFMAN_TABLES = {520, 521};

    /**
     * The size of an old-style JPEG quantization table.
     */
    private static final int Q_TABLE_SIZE = 64;

    /**
     * The number of code length counts that start an old-style JPEG Huffman table.
     */
    private static final int HUFFMAN_COUNTS = 16;

    /**
     * The tags of the standard {@code LONG} and {@code LONG8} entries that are neither structural
     * nor pointers and hold values, not offsets: the EXIF sensitivity tags (34865 to 34869),
     * {@code PixelXDimension} and {@code PixelYDimension} (40962, 40963), the interoperability
     * {@code RelatedImageWidth} and {@code RelatedImageLength} (4097, 4098) and the TIFF/EP
     * {@code ImageNumber} (37393).
     */
    private static final Set<Integer> INTEGER_VALUES = Set.of(34865, 34866, 34867, 34868, 34869, 40962, 40963,
            4097, 4098, 37393);

    /**
     * The tags of data offsets, parallel to {@link #LENGTHS}: strips, tiles, free space and the
     * old-style JPEG stream.
     */
    private static final int[] OFFSETS = {273, 324, 288, 513};

    /**
     * The tags of data lengths, parallel to {@link #OFFSETS}.
     */
    private static final int[] LENGTHS = {279, 325, 289, 514};

    /**
     * The largest classic TIFF offset.
     */
    private static final long CLASSIC_LIMIT = 0xFFFF_FFFFL;

    /**
     * The largest run of unreferenced bytes before the end of the file that is checked for zeros
     * and dropped.
     */
    private static final int ZERO_SCAN = 1 << 20;

    /**
     * The zeros written over freed space, shared by every piece that needs them.
     */
    private static final byte[] ZEROS = new byte[1 << 16];

    /**
     * The file.
     */
    private final TiffFile file;

    /**
     * The metadata to write.
     */
    private final Resolved target;

    /**
     * Whether the file is little endian.
     */
    private final boolean little;

    /**
     * Whether the file is a BigTIFF.
     */
    private final boolean big;

    /**
     * The size of the file.
     */
    private final long size;

    /**
     * The alignment of every directory and value placed at a new offset: 2, or 8 in a BigTIFF.
     */
    private final int align;

    /**
     * The old space the edit frees: the rewritten directories and the values they referenced,
     * minus everything still referenced.
     */
    private final TiffRanges freed = new TiffRanges();

    /**
     * The ranges kept because a kept entry references them.
     */
    private final List<long[]> kept = new ArrayList<>();

    /**
     * The byte after the last one the output still references in the old file.
     */
    private long highest;

    /**
     * Whether the output may reference old bytes that the directories do not bound: the data of an
     * entry of an unknown type, what a maker note points at, or what an entry that may hold an
     * offset ({@link #mayPoint(int, int)}) points at.
     */
    private boolean opaque;

    /**
     * The indexes of the IFD0 entries the new IFD0 copies as stored.
     */
    private final Set<Integer> copied = new HashSet<>();

    /**
     * Creates a rewriter.
     *
     * @param file the parsed file
     * @param target the metadata to write
     */
    TiffRewriter(TiffFile file, Resolved target) {
        this.file = file;
        this.target = target;
        this.little = file.header.little();
        this.big = file.header.big();
        this.size = file.source.size();
        this.align = big ? 8 : 2;
    }

    /**
     * Plans the edit.
     *
     * @return the plan of the whole output
     * @throws MediaException.Unsupported if the edit adds an EXIF thumbnail
     * @throws MediaException.Malformed if IFD0 is truncated
     * @throws MediaException.LimitExceeded if a directory has too many entries, the output exceeds
     * what classic TIFF offsets address, or the file has too many directories to check
     */
    EditPlan plan() {
        if (!file.ifd0Complete) {
            throw new MediaException.Malformed("IFD0 is truncated");
        }
        ExifData exif = null;
        if (target.exif() instanceof Change.Replace) {
            exif = ExifReader.parse(target.exifBytes());
            if (exif.thumbnail().isPresent()) {
                throw ImageFiles.unsupported(ImageFormat.TIFF, "an EXIF thumbnail");
            }
        }
        var tables = new ArrayList<Table>();
        var ifd0 = new Table(file.ifd0, file.ifd0.next());
        tables.add(ifd0);
        copyIfd0(ifd0, exif);
        if (exif != null) {
            addExif(tables, ifd0, exif);
        }
        addOwned(ifd0);
        for (var table : tables) {
            if (!big && table.entries.size() > 0xFFFF) {
                throw new MediaException.LimitExceeded("a TIFF directory holds at most 65535 entries");
            }
        }
        free(file.ifd0);
        if (!(target.exif() instanceof Change.Keep)) {
            for (var kind : List.of(ExifData.Directory.EXIF, ExifData.Directory.GPS, ExifData.Directory.INTEROP)) {
                var old = file.directory(kind).ifd();
                if (old != null) {
                    free(old);
                }
            }
        }
        protect();
        return assemble(tables);
    }

    /**
     * Copies the IFD0 entries the edit keeps as stored.
     *
     * @param ifd0 the new IFD0
     * @param exif the new EXIF directories, or {@code null} when the EXIF block is kept or removed
     */
    private void copyIfd0(Table ifd0, ExifData exif) {
        var old = file.ifd0;
        var exifKept = target.exif() instanceof Change.Keep;
        var seen = new HashSet<Integer>();
        for (var index = 0; index < old.count(); index++) {
            var tag = old.tag(index);
            // of the entries of one tag, the copy keeps the one a reader resolves: the first of a known type
            var resolved = old.find(tag);
            if (resolved >= 0 ? resolved != index : !seen.add(tag)) {
                continue;
            }
            var keep = switch (tag) {
                case TiffFile.XMP -> target.xmp() instanceof Change.Keep;
                case TiffFile.IPTC -> target.iptc() instanceof Change.Keep;
                case TiffFile.ICC -> target.icc() instanceof Change.Keep;
                case TiffFile.PHOTOSHOP -> !target.removeOther() && !rewritesPhotoshop();
                default -> {
                    var other = TiffValues.type(old.type(index)) == null && !TiffFile.isStructural(tag)
                            && tag != ExifData.EXIF_IFD_POINTER && tag != ExifData.GPS_IFD_POINTER;
                    if (other && target.removeOther()) {
                        yield false;
                    }
                    if (exifKept || TiffFile.isStructural(tag)) {
                        yield true;
                    }
                    if (TiffFile.REQUIRED.contains(tag)) {
                        yield exif == null || !exif.ifd0().containsKey(tag);
                    }
                    yield other && (exif == null || !exif.ifd0().containsKey(tag));
                }
            };
            if (keep) {
                copied.add(index);
                ifd0.entries.put(tag, Entry.stored(old, index));
                if (!old.isInline(index)) {
                    var length = old.byteLength(index);
                    if (length > 0) {
                        keep(old.valueOffset(index), length);
                    }
                }
            }
        }
    }

    /**
     * Adds the descriptive IFD0 entries and the Exif, GPS and interoperability directories of a new
     * EXIF block.
     *
     * @param tables the new directories, IFD0 first, updated
     * @param ifd0 the new IFD0
     * @param exif the new EXIF directories
     */
    private void addExif(List<Table> tables, Table ifd0, ExifData exif) {
        for (var entry : exif.ifd0().entrySet()) {
            var tag = entry.getKey();
            if (tag < 0 || tag > 0xFFFF || TiffFile.isStructural(tag) || TiffFile.isOwned(tag)
                    || tag == ExifData.EXIF_IFD_POINTER || tag == ExifData.GPS_IFD_POINTER) {
                continue;
            }
            ifd0.entries.put(tag, value(ExifData.Directory.IFD0, tag, entry.getValue()));
        }
        var interop = values(ExifData.Directory.INTEROP, exif.interop(), Set.of());
        var exifTable = values(ExifData.Directory.EXIF, exif.exif(), Set.of(ExifData.INTEROP_IFD_POINTER));
        var gps = values(ExifData.Directory.GPS, exif.gps(), Set.of());
        if (!interop.entries.isEmpty()) {
            exifTable.entries.put(ExifData.INTEROP_IFD_POINTER, Entry.pointer(ExifData.INTEROP_IFD_POINTER, interop, big));
        }
        if (!exifTable.entries.isEmpty()) {
            ifd0.entries.put(ExifData.EXIF_IFD_POINTER, Entry.pointer(ExifData.EXIF_IFD_POINTER, exifTable, big));
            tables.add(exifTable);
        }
        if (!interop.entries.isEmpty()) {
            tables.add(interop);
        }
        if (!gps.entries.isEmpty()) {
            ifd0.entries.put(ExifData.GPS_IFD_POINTER, Entry.pointer(ExifData.GPS_IFD_POINTER, gps, big));
            tables.add(gps);
        }
    }

    /**
     * Builds a sub-directory from EXIF entries.
     *
     * @param kind the directory
     * @param entries the EXIF entries, keyed by tag
     * @param computed the tags the rewriter computes, left out
     * @return the directory
     */
    private Table values(ExifData.Directory kind, Map<Integer, ExifValue> entries, Set<Integer> computed) {
        var table = new Table(file.directory(kind).ifd(), 0);
        for (var entry : entries.entrySet()) {
            var tag = entry.getKey();
            if (tag >= 0 && tag <= 0xFFFF && !computed.contains(tag)) {
                table.entries.put(tag, value(kind, tag, entry.getValue()));
            }
        }
        return table;
    }

    /**
     * Adds the replaced XMP, IPTC, ICC and Photoshop entries.
     *
     * @param ifd0 the new IFD0
     */
    private void addOwned(Table ifd0) {
        var xmp = target.xmpPacket();
        if (xmp != null) {
            var type = ownedType(TiffFile.XMP, TiffTypes.BYTE, TiffTypes.UNDEFINED);
            ifd0.entries.put(TiffFile.XMP, owned(TiffFile.XMP, type, xmp.toBytes()));
        }
        var iptc = target.iptcData();
        if (iptc != null) {
            var type = ownedType(TiffFile.IPTC, TiffTypes.LONG, TiffTypes.UNDEFINED);
            var bytes = iptc.toBytes();
            if (type == TiffTypes.LONG) {
                bytes = Arrays.copyOf(bytes, (bytes.length + 3) & ~3);
            }
            ifd0.entries.put(TiffFile.IPTC, owned(TiffFile.IPTC, type, bytes));
        }
        var icc = target.iccProfile();
        if (icc != null) {
            ifd0.entries.put(TiffFile.ICC, owned(TiffFile.ICC, TiffTypes.UNDEFINED, icc));
        }
        if (!target.removeOther() && rewritesPhotoshop()) {
            var resources = PhotoshopResources.withIptc(file.photoshopResources(), iptc);
            if (!resources.isEmpty()) {
                var type = ownedType(TiffFile.PHOTOSHOP, TiffTypes.BYTE, TiffTypes.UNDEFINED);
                ifd0.entries.put(TiffFile.PHOTOSHOP, owned(TiffFile.PHOTOSHOP, type, PhotoshopResources.encode(resources)));
            }
        }
    }

    /**
     * {@return whether the Photoshop entry is rebuilt because the IPTC data it carries changes}
     */
    private boolean rewritesPhotoshop() {
        return !(target.iptc() instanceof Change.Keep) && file.owned(TiffFile.PHOTOSHOP) != null
                && file.photoshopResources().stream().anyMatch(resource -> resource.signature().equals("8BIM")
                        && resource.id() == PhotoshopResources.IPTC);
    }

    /**
     * Returns the type of a replaced entry: the stored type when it is one of the two the entry
     * may take, otherwise the preferred one.
     *
     * @param tag the tag number of the entry
     * @param preferred the type of a new entry
     * @param alternative the other type the entry may take
     * @return the type code
     */
    private int ownedType(int tag, int preferred, int alternative) {
        var index = TiffFile.first(file.ifd0, tag);
        return index >= 0 && file.ifd0.type(index) == alternative ? alternative : preferred;
    }

    /**
     * Returns an entry of an owned kind, keeping the stored value's offset when it is unchanged.
     *
     * @param tag the tag number of the entry
     * @param type the field type, of size 1 or 4
     * @param bytes the value bytes
     * @return the entry
     */
    private Entry owned(int tag, int type, byte[] bytes) {
        var entry = new Entry(tag, type, bytes.length / TiffTypes.size(type), -1, bytes, null);
        reuse(file.ifd0, entry);
        return entry;
    }

    /**
     * Returns an entry holding an EXIF value, keeping the stored value's offset when it is
     * unchanged.
     *
     * @param kind the directory
     * @param tag the tag number of the entry
     * @param value the value to store
     * @return the entry
     */
    private Entry value(ExifData.Directory kind, int tag, ExifValue value) {
        var bytes = TiffValues.encode(value, little);
        var entry = new Entry(tag, value.type().code(), bytes.length / value.type().size(), -1, bytes, null);
        var old = file.directory(kind).ifd();
        if (old != null) {
            reuse(old, entry);
        }
        return entry;
    }

    /**
     * Points an out-of-line entry at the old value when the old entry with its tag stores the same
     * type, count and bytes.
     *
     * @param old the old directory
     * @param entry the new entry, updated
     */
    private void reuse(Ifd old, Entry entry) {
        if (entry.value.length <= file.header.fieldSize()) {
            return;
        }
        var index = TiffFile.first(old, entry.tag);
        if (index < 0 || old.type(index) != entry.type || old.valueCount(index) != entry.count || old.isInline(index)) {
            return;
        }
        var at = old.valueOffset(index);
        if (at < 0 || at > size - entry.value.length) {
            return;
        }
        var stored = new byte[entry.value.length];
        if (file.input.readAvailable(at, stored, 0, stored.length) == stored.length && Arrays.equals(stored, entry.value)) {
            entry.offset = at;
            keep(at, stored.length);
        }
    }

    /**
     * Records a range that stays referenced.
     *
     * @param start the first byte
     * @param length the number of bytes
     */
    private void keep(long start, long length) {
        kept.add(new long[]{start, start + length});
    }

    /**
     * Frees an old directory's table and the values of its entries.
     *
     * @param ifd the old directory
     */
    private void free(Ifd ifd) {
        freed.add(ifd.offset(), Math.min(size, file.endOf(ifd)));
        for (var index = 0; index < ifd.count(); index++) {
            var length = ifd.byteLength(index);
            if (length > 0 && !ifd.isInline(index)) {
                var at = ifd.valueOffset(index);
                if (at >= 0 && at < size) {
                    freed.add(at, Math.min(size, at + length));
                }
            }
        }
    }

    /**
     * Removes from the freed space everything the output still references: the header, the kept
     * values, the data and directories the kept IFD0 entries point at, and every directory
     * reachable from them or from the next-directory pointer of IFD0, with the values and data of
     * those directories.
     *
     * <p>Zero bytes between two freed ranges, fewer than the alignment, are freed with them.
     *
     * @throws MediaException.LimitExceeded if more directories are reachable than the frame limit
     * allows
     */
    private void protect() {
        bridge();
        reference(0, file.headerSize());
        for (var range : kept) {
            reference(range[0], range[1]);
        }
        var work = new ArrayDeque<Long>();
        references(file.ifd0, copied::contains, work);
        if (file.ifd0.next() != 0) {
            work.push(file.ifd0.next());
        }
        var visited = new HashSet<Long>();
        var budget = Math.max(1024, file.limits.maxFrames());
        while (!work.isEmpty()) {
            var offset = work.pop();
            if (offset < file.headerSize() || offset >= size || !visited.add(offset)) {
                continue;
            }
            if (visited.size() > budget) {
                throw new MediaException.LimitExceeded("the TIFF file has more than " + budget + " directories");
            }
            Ifd ifd;
            try {
                ifd = Ifd.read(file.input, 0, file.header, offset, TiffFile.POLICY);
            } catch (MediaException.Malformed exception) {
                continue;
            }
            reference(offset, file.endOf(ifd));
            for (var index = 0; index < ifd.count(); index++) {
                var length = ifd.byteLength(index);
                if (length > 0 && !ifd.isInline(index)) {
                    var at = ifd.valueOffset(index);
                    reference(at, at + length);
                }
            }
            references(ifd, _ -> true, work);
            if (ifd.next() != 0) {
                work.push(ifd.next());
            }
        }
    }

    /**
     * Protects the data and queues the directories that the entries of a directory point at.
     *
     * <p>The data are the strips, tiles, free space and old-style JPEG stream bounded by their
     * offset and length entries, and the old-style JPEG quantization and Huffman tables; the
     * directories are those of the {@code SubIFDs}, Exif, GPS, interoperability and
     * {@code GlobalParametersIFD} pointers and of every entry of the {@code IFD} and {@code IFD8}
     * types.
     *
     * @param ifd the directory
     * @param include selects the entry indexes to follow
     * @param work the offsets of the directories still to visit, updated
     */
    private void references(Ifd ifd, IntPredicate include, ArrayDeque<Long> work) {
        for (var index = 0; index < ifd.count(); index++) {
            if (!include.test(index)) {
                continue;
            }
            var type = ifd.type(index);
            if (ifd.byteLength(index) < 0 || ifd.tag(index) == ExifData.MAKER_NOTE || mayPoint(ifd.tag(index), type)) {
                opaque = true;
            }
            if ((POINTERS.contains(ifd.tag(index)) || type == TiffTypes.IFD || type == TiffTypes.IFD8)
                    && TiffTypes.isInteger(type)) {
                for (var offset : integers(ifd, index)) {
                    work.push(offset);
                }
            }
        }
        for (var pair = 0; pair < OFFSETS.length; pair++) {
            var offsets = TiffFile.first(ifd, OFFSETS[pair]);
            var lengths = TiffFile.first(ifd, LENGTHS[pair]);
            if (offsets < 0 || lengths < 0 || !include.test(offsets)) {
                continue;
            }
            var starts = integers(ifd, offsets);
            var counts = integers(ifd, lengths);
            for (var i = 0; i < Math.min(starts.length, counts.length); i++) {
                if (starts[i] >= 0 && counts[i] > 0) {
                    reference(starts[i], starts[i] + counts[i]);
                }
            }
        }
        var quantization = TiffFile.first(ifd, JPEG_Q_TABLES);
        if (quantization >= 0 && include.test(quantization)) {
            for (var start : integers(ifd, quantization)) {
                if (start >= 0) {
                    reference(start, start + Q_TABLE_SIZE);
                }
            }
        }
        for (var tag : JPEG_HUFFMAN_TABLES) {
            var huffman = TiffFile.first(ifd, tag);
            if (huffman >= 0 && include.test(huffman)) {
                for (var start : integers(ifd, huffman)) {
                    if (start >= 0) {
                        reference(start, start + huffmanTableSize(start));
                    }
                }
            }
        }
    }

    /**
     * Returns whether an entry that is neither structural, nor a pointer, nor of an owned kind may
     * hold the offset of data the directories do not bound, as private tags of the {@code LONG}
     * and {@code LONG8} types do.
     *
     * @param tag the tag number of the entry
     * @param type the field type code
     * @return {@code true} for a {@code LONG} or {@code LONG8} entry whose tag is not known to hold a
     * value ({@link #INTEGER_VALUES})
     */
    private static boolean mayPoint(int tag, int type) {
        return (type == TiffTypes.LONG || type == TiffTypes.LONG8) && !POINTERS.contains(tag)
                && !TiffFile.isStructural(tag) && !TiffFile.isOwned(tag) && !INTEGER_VALUES.contains(tag);
    }

    /**
     * Returns the size of an old-style JPEG Huffman table: its 16 code length counts and the
     * values they count.
     *
     * @param start the offset of the table, not negative
     * @return the size in bytes, or the size of the counts alone when they are cut off by the
     * end of the file
     */
    private long huffmanTableSize(long start) {
        var counts = new byte[HUFFMAN_COUNTS];
        if (file.input.readAvailable(start, counts, 0, counts.length) < counts.length) {
            return HUFFMAN_COUNTS;
        }
        var size = (long) HUFFMAN_COUNTS;
        for (var count : counts) {
            size += count & 0xFF;
        }
        return size;
    }

    /**
     * Records a range the output still references: it is not freed, and it bounds the bytes the
     * output may drop from the end.
     *
     * @param start the first byte
     * @param end the byte after the last one
     */
    private void reference(long start, long end) {
        if (end <= start || start >= size) {
            return;
        }
        freed.subtract(start, end);
        highest = Math.max(highest, Math.min(end, size));
    }

    /**
     * Reads the integer values of an entry.
     *
     * @param ifd the directory
     * @param index the entry index
     * @return the values, empty when the entry is not an integer entry or cannot be read
     * @throws MediaException.LimitExceeded if the values exceed the allocation limit
     */
    private long[] integers(Ifd ifd, int index) {
        if (!TiffTypes.isInteger(ifd.type(index))) {
            return new long[0];
        }
        try {
            return ifd.integers(index, file.limits);
        } catch (MediaException.Malformed exception) {
            return new long[0];
        }
    }

    /**
     * Joins freed ranges separated by fewer zero bytes than the alignment, which padding leaves.
     */
    private void bridge() {
        var ranges = new ArrayList<long[]>();
        for (var range : freed.asMap().entrySet()) {
            ranges.add(new long[]{range.getKey(), range.getValue()});
        }
        for (var i = 1; i < ranges.size(); i++) {
            var from = ranges.get(i - 1)[1];
            var gap = ranges.get(i)[0] - from;
            if (gap > 0 && gap < align) {
                var bytes = new byte[(int) gap];
                if (file.input.readAvailable(from, bytes, 0, bytes.length) == bytes.length
                        && Arrays.equals(bytes, new byte[bytes.length])) {
                    freed.add(from, from + gap);
                }
            }
        }
    }

    /**
     * Places the new directories and values and assembles the plan.
     *
     * @param tables the new directories, IFD0 first
     * @return the plan
     * @throws MediaException.LimitExceeded if the output exceeds what classic TIFF offsets address
     */
    private EditPlan assemble(List<Table> tables) {
        var tail = freed.last();
        var end = tail != null && tail.getValue() == size ? tail.getKey() : size;
        for (var table : tables) {
            for (var entry : table.entries.values()) {
                opaque |= entry.tag == ExifData.MAKER_NOTE || (entry.pointer == null && mayPoint(entry.tag, entry.type));
            }
        }
        var unreferenced = Math.max(highest, file.headerSize());
        if (!opaque && unreferenced < end && end - unreferenced <= ZERO_SCAN && unreferencedZeros(unreferenced, end)) {
            end = unreferenced;
        }
        var holes = new TiffRanges();
        for (var range : freed.asMap().entrySet()) {
            if (range.getKey() < end) {
                holes.add(range.getKey(), Math.min(end, range.getValue()));
            }
        }
        var dirty = new TiffRanges();
        for (var range : holes.asMap().entrySet()) {
            dirty.add(range.getKey(), range.getValue());
        }
        var allocator = new Allocator(holes, end);
        for (var table : tables) {
            var preferred = table.old != null ? table.old.offset() : -1;
            table.offset = allocator.allocate(table.size(this), preferred);
        }
        var total = 0L;
        for (var table : tables) {
            for (var entry : table.entries.values()) {
                if (entry.value != null && entry.value.length > file.header.fieldSize() && entry.offset < 0) {
                    entry.offset = allocator.allocate(entry.value.length, -1);
                    total += entry.value.length;
                }
            }
        }
        LimitChecks.checkAllocation(file.limits, total);
        var length = Math.max(end, allocator.end);
        if (!big && length - 1 > CLASSIC_LIMIT) {
            throw new MediaException.LimitExceeded("the edited file needs offsets beyond the 4 GiB a classic TIFF addresses");
        }
        var writes = new TreeMap<Long, byte[]>();
        for (var table : tables) {
            writes.put(table.offset, table.serialize(this));
            for (var entry : table.entries.values()) {
                if (entry.value != null && entry.value.length > file.header.fieldSize() && !isStored(entry)) {
                    writes.put(entry.offset, entry.value);
                }
            }
        }
        if (tables.getFirst().offset != file.ifd0.offset()) {
            var pointer = new byte[file.header.fieldSize()];
            putField(pointer, 0, tables.getFirst().offset);
            writes.put(big ? 8L : 4L, pointer);
            dirty.add(big ? 8 : 4, big ? 16 : 8);
        }
        dirty.add(end, length);
        var builder = EditPlan.builder();
        var position = 0L;
        for (var range : dirty.asMap().entrySet()) {
            var start = range.getKey();
            var stop = range.getValue();
            builder.copy(position, Math.min(start, end) - position);
            position = start;
            for (var write : writes.subMap(start, stop).entrySet()) {
                zeros(builder, write.getKey() - position);
                builder.bytes(write.getValue());
                position = write.getKey() + write.getValue().length;
            }
            zeros(builder, stop - position);
            position = stop;
        }
        builder.copy(position, end - position);
        var plan = builder.build();
        return plan.reproduces(file.source) ? EditPlan.builder().copy(0, size).build() : plan;
    }

    /**
     * Returns whether every byte of a range that is not freed is zero.
     *
     * @param start the first byte
     * @param end the byte after the last one, at most {@link #ZERO_SCAN} bytes after {@code start}
     * @return {@code true} when the range holds only freed space and zeros
     */
    private boolean unreferencedZeros(long start, long end) {
        var bytes = new byte[(int) (end - start)];
        if (file.input.readAvailable(start, bytes, 0, bytes.length) < bytes.length) {
            return false;
        }
        var ranges = freed.asMap();
        var position = start;
        while (position < end) {
            var range = ranges.floorEntry(position);
            if (range != null && range.getValue() > position) {
                position = range.getValue();
                continue;
            }
            var next = ranges.ceilingKey(position);
            var stop = next == null ? end : Math.min(end, next);
            for (var at = position; at < stop; at++) {
                if (bytes[(int) (at - start)] != 0) {
                    return false;
                }
            }
            position = stop;
        }
        return true;
    }

    /**
     * Returns whether an out-of-line entry's value already lies at its offset in the file.
     *
     * @param entry the out-of-line entry
     * @return {@code true} for a reused value
     */
    private boolean isStored(Entry entry) {
        for (var range : kept) {
            if (range[0] == entry.offset && range[1] == entry.offset + entry.value.length) {
                return true;
            }
        }
        return false;
    }

    /**
     * Appends zeros.
     *
     * @param builder the plan
     * @param count the number of zeros, possibly zero
     */
    private static void zeros(EditPlan.Builder builder, long count) {
        while (count > 0) {
            var chunk = (int) Math.min(ZEROS.length, count);
            builder.bytes(chunk == ZEROS.length ? ZEROS : new byte[chunk]);
            count -= chunk;
        }
    }

    /**
     * Writes an offset or an integer value field: 32 bits, or 64 in a BigTIFF.
     *
     * @param data the destination
     * @param at the index of the first byte
     * @param value the offset or integer to write
     */
    private void putField(byte[] data, int at, long value) {
        if (big) {
            TiffValues.put64(data, at, value, little);
        } else {
            ByteArrays.put32(data, at, value, little);
        }
    }

    /**
     * Places blocks in the freed space or after the end of the output.
     */
    private final class Allocator {
        /**
         * The free space left inside the output.
         */
        private final TiffRanges holes;

        /**
         * The end of the output so far.
         */
        private long end;

        /**
         * Creates an allocator.
         *
         * @param holes the free space inside the output
         * @param end the length of the output before anything is appended
         */
        private Allocator(TiffRanges holes, long end) {
            this.holes = holes;
            this.end = end;
        }

        /**
         * Places a block.
         *
         * @param length the length of the block
         * @param preferred the offset to use, whatever its alignment, when it is free or is where
         * the appended data starts (the old offset of the directory the block replaces), or
         * {@code -1}
         * @return the offset of the block
         */
        private long allocate(long length, long preferred) {
            if (preferred >= 0 && holes.covers(preferred, preferred + length)) {
                holes.subtract(preferred, preferred + length);
                return preferred;
            }
            if (preferred >= 0 && preferred == end) {
                end = preferred + length;
                return preferred;
            }
            for (var range : holes.asMap().entrySet()) {
                var start = (range.getKey() + align - 1) / align * align;
                if (start + length <= range.getValue()) {
                    holes.subtract(start, start + length);
                    return start;
                }
            }
            var start = (end + align - 1) / align * align;
            end = start + length;
            return start;
        }
    }

    /**
     * One directory of the output.
     */
    private static final class Table {
        /**
         * The directory it replaces, or {@code null}.
         */
        private final Ifd old;

        /**
         * The offset of the next directory.
         */
        private final long next;

        /**
         * The entries by tag.
         */
        private final TreeMap<Integer, Entry> entries = new TreeMap<>();

        /**
         * The offset the directory is written at.
         */
        private long offset = -1;

        /**
         * Creates a directory.
         *
         * @param old the directory it replaces, or {@code null}
         * @param next the offset of the next directory
         */
        private Table(Ifd old, long next) {
            this.old = old;
            this.next = next;
        }

        /**
         * {@return the size of the directory's table}
         *
         * @param rewriter the rewriter, for the format
         */
        private long size(TiffRewriter rewriter) {
            return (rewriter.big ? 16L : 6L) + (long) entries.size() * rewriter.file.header.entrySize();
        }

        /**
         * Serializes the table; every out-of-line value and every pointed directory is placed.
         *
         * @param rewriter the rewriter, for the format
         * @return the bytes
         */
        private byte[] serialize(TiffRewriter rewriter) {
            var big = rewriter.big;
            var little = rewriter.little;
            var entrySize = rewriter.file.header.entrySize();
            var fieldSize = rewriter.file.header.fieldSize();
            var countSize = big ? 8 : 2;
            var data = new byte[(int) size(rewriter)];
            if (big) {
                TiffValues.put64(data, 0, entries.size(), little);
            } else {
                ByteArrays.put16(data, 0, entries.size(), little);
            }
            var at = countSize;
            for (var entry : entries.values()) {
                ByteArrays.put16(data, at, entry.tag, little);
                ByteArrays.put16(data, at + 2, entry.type, little);
                if (big) {
                    TiffValues.put64(data, at + 4, entry.count, little);
                } else {
                    ByteArrays.put32(data, at + 4, entry.count, little);
                }
                var field = at + (big ? 12 : 8);
                if (entry.pointer != null) {
                    rewriter.putField(data, field, entry.pointer.offset);
                } else if (entry.value == null) {
                    rewriter.putField(data, field, entry.field);
                } else if (entry.value.length <= fieldSize) {
                    System.arraycopy(entry.value, 0, data, field, entry.value.length);
                } else {
                    rewriter.putField(data, field, entry.offset);
                }
                at += entrySize;
            }
            rewriter.putField(data, at, next);
            return data;
        }
    }

    /**
     * One entry of an output directory: an old entry copied as stored, a new value, or a pointer
     * to a new directory.
     */
    private static final class Entry {
        /**
         * The tag.
         */
        private final int tag;

        /**
         * The field type code.
         */
        private final int type;

        /**
         * The number of values.
         */
        private final long count;

        /**
         * The value field of an entry copied as stored, as an integer of the field size.
         */
        private final long field;

        /**
         * The value bytes of a new value, or {@code null}.
         */
        private final byte[] value;

        /**
         * The directory a pointer entry points at, or {@code null}.
         */
        private final Table pointer;

        /**
         * The offset of an out-of-line value, or {@code -1} before it is placed.
         */
        private long offset = -1;

        /**
         * Creates an entry.
         *
         * @param tag the tag number of the entry
         * @param type the field type code
         * @param count the number of values
         * @param field the stored value field
         * @param value the new value bytes, or {@code null}
         * @param pointer the pointed directory, or {@code null}
         */
        private Entry(int tag, int type, long count, long field, byte[] value, Table pointer) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.field = field;
            this.value = value;
            this.pointer = pointer;
        }

        /**
         * Returns an old entry copied as stored.
         *
         * @param ifd the old directory
         * @param index the entry index
         * @return the entry
         */
        private static Entry stored(Ifd ifd, int index) {
            return new Entry(ifd.tag(index), ifd.type(index), ifd.valueCount(index), ifd.valueOffset(index), null, null);
        }

        /**
         * Returns a pointer to a new directory.
         *
         * @param tag the tag number of the entry
         * @param table the directory
         * @param big whether the file is a BigTIFF, whose pointers are {@code IFD8}
         * @return the entry
         */
        private static Entry pointer(int tag, Table table, boolean big) {
            return new Entry(tag, big ? TiffTypes.IFD8 : TiffTypes.LONG, 1, 0, null, table);
        }
    }
}
