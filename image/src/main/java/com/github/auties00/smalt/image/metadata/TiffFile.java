package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.Ifd;
import com.github.auties00.smalt.internal.tiff.TiffHeader;
import com.github.auties00.smalt.internal.tiff.TiffInput;
import com.github.auties00.smalt.internal.tiff.TiffTypes;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.ByteOrder;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the metadata of a TIFF or BigTIFF file, whose first directory (IFD0) is itself an EXIF
 * directory, and plans the edits of it.
 *
 * <p>The descriptive entries of IFD0, with the Exif, GPS and interoperability directories, form
 * the EXIF block; the entries that lay out and describe the coded image ({@link #isStructural(int)})
 * are left out of it. The XMP packet (tag 700), the IPTC-IIM data (tag 33723, or else the IPTC
 * resource of the Photoshop image resources in tag 34377) and the ICC profile (tag 34675) come
 * from their IFD0 entries. Only IFD0 and its sub-directories are read; the directories of the
 * other pages are reached only by {@link TiffRewriter}, which keeps them.
 */
final class TiffFile implements ImageFile {
    /**
     * The tag of the XMP packet.
     */
    static final int XMP = 700;

    /**
     * The tag of the IPTC-IIM data.
     */
    static final int IPTC = 33723;

    /**
     * The tag of the ICC profile.
     */
    static final int ICC = 34675;

    /**
     * The tag of the Photoshop image resources.
     */
    static final int PHOTOSHOP = 34377;

    /**
     * The tag of the offsets of the child directories ({@code SubIFDs}).
     */
    static final int SUB_IFDS = 330;

    /**
     * The tags of IFD0 that TIFF 6.0 requires and EXIF counts as descriptive: {@code XResolution},
     * {@code YResolution} and {@code ResolutionUnit}.
     */
    static final Set<Integer> REQUIRED = Set.of(282, 283, 296);

    /**
     * The directory policy: up to 65536 entries, a directory cut off by the end of the file
     * clamped to its whole entries, unknown types kept with an unknown size.
     */
    static final Ifd.Policy POLICY = new Ifd.Policy(1 << 16, true, 0, Long.MAX_VALUE);

    /**
     * The tags of TIFF 6.0, its technical notes and the extensions in common use that lay out or
     * describe the coded image, below the DNG range ({@link #isStructural(int)}).
     */
    private static final Set<Integer> STRUCTURE = Set.of(
            254, 255, 256, 257, 258, 259, 262, 263, 264, 265, 266, 273, 277, 278, 279, 280, 281, 284, 286, 287,
            288, 289, 290, 291, 292, 293, 297, 301, 317, 318, 319, 320, 321, 322, 323, 324, 325, 326, 327, 328,
            SUB_IFDS, 332, 333, 334, 336, 338, 339, 340, 341, 342, 343, 344, 345, 346, 347, 351, 400, 401, 402,
            403, 404, 405, 433, 434, 512, 513, 514, 515, 517, 518, 519, 520, 521, 529, 530, 531, 532, 559,
            32995, 32996, 32997, 32998, 33421, 33422, 33550, 33922, 34264, 34735, 34736, 34737, 37724, 42112,
            42113);

    /**
     * The first tag of the DNG range, every tag of which is structural.
     */
    private static final int DNG_FIRST = 50706;

    /**
     * The last tag of the DNG range.
     */
    private static final int DNG_LAST = 52600;

    /**
     * The file.
     */
    final MediaSource source;

    /**
     * The resource bounds.
     */
    final MediaLimits limits;

    /**
     * The file, as the directory reader sees it.
     */
    final TiffInput input;

    /**
     * The header.
     */
    final TiffHeader header;

    /**
     * The first directory.
     */
    final Ifd ifd0;

    /**
     * Whether IFD0 holds every entry it declares and its next-directory pointer.
     */
    final boolean ifd0Complete;

    /**
     * The decoded IFD0, Exif, GPS and interoperability directories, keyed by kind; a directory
     * that is absent or unreadable is missing.
     */
    final Map<ExifData.Directory, Directory> directories;

    /**
     * Reads the header and the metadata directories of a file.
     *
     * @param source the TIFF or BigTIFF file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the header is invalid or IFD0 cannot be read
     * @throws MediaException.LimitExceeded if the metadata values exceed the allocation limit
     */
    TiffFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        this.input = TiffInput.of(source);
        var header = TiffHeader.read(input, 0);
        if (header == null) {
            throw new MediaException.Malformed("not a TIFF file: missing byte order mark or version");
        }
        this.header = header;
        var first = header.firstIfd();
        if (first < headerSize() || first >= source.size()) {
            throw new MediaException.Malformed("IFD0 offset " + first + " lies outside the " + source.size()
                    + " byte file");
        }
        this.ifd0 = Ifd.read(input, 0, header, first, POLICY);
        this.ifd0Complete = declaredEntries(first) == ifd0.count() && endOf(ifd0) <= source.size();
        var budget = new long[]{0};
        var result = new HashMap<ExifData.Directory, Directory>();
        var visited = new HashSet<Long>();
        visited.add(first);
        result.put(ExifData.Directory.IFD0, read(ExifData.Directory.IFD0, ifd0, budget));
        var exif = child(ifd0, ExifData.EXIF_IFD_POINTER, visited);
        if (exif != null) {
            result.put(ExifData.Directory.EXIF, read(ExifData.Directory.EXIF, exif, budget));
        }
        var gps = child(ifd0, ExifData.GPS_IFD_POINTER, visited);
        if (gps != null) {
            result.put(ExifData.Directory.GPS, read(ExifData.Directory.GPS, gps, budget));
        }
        var interop = exif == null ? null : child(exif, ExifData.INTEROP_IFD_POINTER, visited);
        if (interop != null) {
            result.put(ExifData.Directory.INTEROP, read(ExifData.Directory.INTEROP, interop, budget));
        }
        this.directories = Collections.unmodifiableMap(result);
    }

    /**
     * Returns whether an IFD0 tag lays out or describes the coded image, so that it is never part
     * of the EXIF block and an edit keeps its entry as stored.
     *
     * @param tag the IFD0 tag number
     * @return {@code true} for the layout, sample format, color, compression, fax, tiling, JPEG,
     * YCbCr, GeoTIFF, color filter array, layer and DNG tags
     */
    static boolean isStructural(int tag) {
        return STRUCTURE.contains(tag) || (tag >= DNG_FIRST && tag <= DNG_LAST);
    }

    /**
     * Returns whether an IFD0 tag is kept out of the EXIF block because the metadata of another
     * kind owns it.
     *
     * @param tag the IFD0 tag number
     * @return {@code true} for the XMP, IPTC, ICC and Photoshop entries
     */
    static boolean isOwned(int tag) {
        return tag == XMP || tag == IPTC || tag == ICC || tag == PHOTOSHOP;
    }

    @Override
    public ImageMetadata metadata() {
        var ifd0 = directory(ExifData.Directory.IFD0).values();
        var exifData = new ExifData(ifd0, directory(ExifData.Directory.EXIF).values(),
                directory(ExifData.Directory.GPS).values(), directory(ExifData.Directory.INTEROP).values(), Map.of(),
                Optional.empty());
        byte[] exif = null;
        if (!exifData.ifd0().isEmpty() || !exifData.exif().isEmpty() || !exifData.gps().isEmpty()
                || !exifData.interop().isEmpty()) {
            exif = ExifWriter.write(exifData, header.little() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        }
        XmpPacket xmp = null;
        var xmpBytes = owned(XMP);
        if (xmpBytes != null) {
            try {
                xmp = XmpPacket.parse(xmpBytes);
            } catch (MediaException.Malformed ignored) {
                // a packet that does not parse is left out
            }
        }
        var iptc = iptc();
        return new ImageMetadata(ImageFormat.TIFF, exif, xmp, iptc != null && !iptc.isEmpty() ? iptc : null, owned(ICC),
                List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (target.comments() instanceof ImageMetadataEdit.Change.Replace && !target.commentList().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.TIFF, "comments");
        }
        if (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.TIFF, "text");
        }
        if (target.keepsAll()) {
            return EditPlan.builder().copy(0, source.size()).build();
        }
        return new TiffRewriter(this, target).plan();
    }

    /**
     * Returns the IPTC-IIM data: the IPTC entry, or else the IPTC resource of the Photoshop image
     * resources.
     *
     * @return the data, or {@code null} when there is none or it does not parse
     */
    private IptcData iptc() {
        var bytes = owned(IPTC);
        try {
            if (bytes != null) {
                return IptcData.parse(bytes);
            }
            var resources = photoshopResources();
            return resources.isEmpty() ? null : PhotoshopResources.iptc(resources);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * {@return the Photoshop image resources of IFD0, empty when the file has none}
     */
    List<PhotoshopResources.Resource> photoshopResources() {
        var bytes = owned(PHOTOSHOP);
        return bytes == null ? List.of() : PhotoshopResources.parse(bytes, 0, bytes.length);
    }

    /**
     * Returns the stored value of an IFD0 entry that the metadata of another kind owns.
     *
     * @param tag the tag, as {@link #isOwned(int)} accepts
     * @return the value bytes, or {@code null} when the entry is absent or unreadable
     */
    byte[] owned(int tag) {
        return directory(ExifData.Directory.IFD0).owned().get(tag);
    }

    /**
     * Returns a decoded directory.
     *
     * @param kind the directory
     * @return the directory, or an empty one when the file has none
     */
    Directory directory(ExifData.Directory kind) {
        return directories.getOrDefault(kind, Directory.EMPTY);
    }

    /**
     * {@return the size of the header: 8, or 16 for a BigTIFF}
     */
    int headerSize() {
        return header.big() ? TiffHeader.BIG_SIZE : TiffHeader.SIZE;
    }

    /**
     * Returns the byte after the last one of a directory's table: the entry count, the entries and
     * the next-directory pointer.
     *
     * @param ifd the directory
     * @return the end offset
     */
    long endOf(Ifd ifd) {
        return ifd.offset() + (header.big() ? 8 : 2) + (long) ifd.count() * header.entrySize() + header.fieldSize();
    }

    /**
     * Returns the first entry of a directory with a tag, whatever its type.
     *
     * @param ifd the directory
     * @param tag the tag to look for
     * @return the entry index, or {@code -1}
     */
    static int first(Ifd ifd, int tag) {
        for (var index = 0; index < ifd.count(); index++) {
            if (ifd.tag(index) == tag) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Returns the number of entries a directory declares.
     *
     * @param offset the offset of the directory
     * @return the declared count, or {@code -1} when it cannot be read
     */
    private long declaredEntries(long offset) {
        var bytes = new byte[header.big() ? 8 : 2];
        if (input.readAvailable(offset, bytes, 0, bytes.length) < bytes.length) {
            return -1;
        }
        if (header.big()) {
            return header.little() ? ByteArrays.i64le(bytes, 0) : ByteArrays.i64be(bytes, 0);
        }
        return ByteArrays.u16(bytes, 0, header.little());
    }

    /**
     * Reads the directory a pointer entry points at.
     *
     * @param parent the directory holding the pointer
     * @param tag the tag of the pointer
     * @param visited the offsets of the directories already read, updated
     * @return the directory, or {@code null} when the pointer is absent, invalid, points at a
     * directory already read or at one that cannot be read
     */
    private Ifd child(Ifd parent, int tag, Set<Long> visited) {
        var offset = pointer(parent, tag);
        if (offset < headerSize() || offset >= source.size() || !visited.add(offset)) {
            return null;
        }
        try {
            return Ifd.read(input, 0, header, offset, POLICY);
        } catch (MediaException.Malformed exception) {
            return null;
        }
    }

    /**
     * Returns the offset a pointer entry holds.
     *
     * @param ifd the directory
     * @param tag the tag of the pointer
     * @return the offset, or {@code -1} when the entry is absent, not an integer or unreadable
     */
    static long pointer(Ifd ifd, int tag) {
        var index = first(ifd, tag);
        if (index < 0 || !TiffTypes.isInteger(ifd.type(index)) || ifd.valueCount(index) < 1) {
            return -1;
        }
        try {
            return ifd.firstInteger(index, -1);
        } catch (MediaException.Malformed exception) {
            return -1;
        }
    }

    /**
     * Decodes the entries of a directory that the EXIF model represents.
     *
     * <p>A repeated tag keeps its first entry. Entries of other types, and entries whose value lies
     * outside the file, are skipped. In IFD0 the structural entries are skipped and the entries of
     * {@link #isOwned(int)} tags are kept as stored bytes, whatever their type.
     *
     * @param kind the directory
     * @param ifd the directory as read
     * @param budget a one-element array holding the bytes of values read so far, updated
     * @return the directory
     * @throws MediaException.LimitExceeded if the values read exceed the allocation limit
     */
    private Directory read(ExifData.Directory kind, Ifd ifd, long[] budget) {
        var values = new LinkedHashMap<Integer, ExifValue>();
        var stored = new HashMap<Integer, byte[]>();
        var owned = new HashMap<Integer, byte[]>();
        var seen = new HashSet<Integer>();
        for (var index = 0; index < ifd.count(); index++) {
            var tag = ifd.tag(index);
            if (!seen.add(tag) || (kind == ExifData.Directory.IFD0 && isStructural(tag))) {
                continue;
            }
            var ownedTag = kind == ExifData.Directory.IFD0 && isOwned(tag);
            var type = TiffValues.type(ifd.type(index));
            var length = ifd.byteLength(index);
            if ((type == null && !ownedTag) || length < 0) {
                continue;
            }
            budget[0] += length;
            LimitChecks.checkAllocation(limits, budget[0]);
            byte[] bytes;
            try {
                bytes = ifd.value(index, limits);
            } catch (MediaException.Malformed exception) {
                continue;
            }
            if (ownedTag) {
                owned.put(tag, bytes);
            } else {
                values.put(tag, TiffValues.decode(type, bytes, header.little()));
                stored.put(tag, bytes);
            }
        }
        return new Directory(ifd, Collections.unmodifiableMap(values), stored, owned);
    }

    /**
     * Holds one decoded directory.
     *
     * @param ifd the directory as read, or {@code null} for an absent one
     * @param values the entries the EXIF model represents, in file order
     * @param stored the stored bytes of each of those entries
     * @param owned the stored bytes of the {@link #isOwned(int)} entries of IFD0
     */
    record Directory(Ifd ifd, Map<Integer, ExifValue> values, Map<Integer, byte[]> stored,
                     Map<Integer, byte[]> owned) {
        /**
         * An absent directory.
         */
        static final Directory EMPTY = new Directory(null, Map.of(), Map.of(), Map.of());
    }
}
