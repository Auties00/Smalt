package com.github.auties00.smalt.image.exif;

import com.github.auties00.smalt.image.Orientation;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the entries of the EXIF image file directories that describe a picture, and its
 * thumbnail.
 *
 * <p>Each map goes from the 16-bit tag number to the typed value, in the order the entries appear
 * in the directory. The pointer entries that link the directories ({@link #EXIF_IFD_POINTER},
 * {@link #GPS_IFD_POINTER} in {@link #ifd0()}, {@link #INTEROP_IFD_POINTER} in {@link #exif()})
 * and the thumbnail location ({@link #THUMBNAIL_OFFSET}, {@link #THUMBNAIL_LENGTH} in
 * {@link #thumbnailIfd()}) stay in the maps as read; {@link ExifWriter} recomputes them from the
 * directories and the thumbnail it writes, so their values are ignored. The maps are
 * unmodifiable.
 *
 * <p>Instances are immutable and thread-safe; {@link #thumbnail()} returns a copy.
 *
 * @param ifd0         the entries of the primary image directory (IFD0)
 * @param exif         the entries of the Exif private directory, empty when absent
 * @param gps          the entries of the GPS directory, empty when absent
 * @param interop      the entries of the interoperability directory, empty when absent
 * @param thumbnailIfd the entries of the thumbnail directory (IFD1), empty when absent
 * @param thumbnail    the JPEG thumbnail IFD1 points at, empty when absent
 */
public record ExifData(Map<Integer, ExifValue> ifd0, Map<Integer, ExifValue> exif, Map<Integer, ExifValue> gps,
                       Map<Integer, ExifValue> interop, Map<Integer, ExifValue> thumbnailIfd,
                       Optional<byte[]> thumbnail) {
    /**
     * The tag of the {@code Orientation} entry of IFD0.
     */
    public static final int ORIENTATION = 0x0112;

    /**
     * The tag of the IFD0 entry holding the offset of the Exif private directory.
     */
    public static final int EXIF_IFD_POINTER = 0x8769;

    /**
     * The tag of the IFD0 entry holding the offset of the GPS directory.
     */
    public static final int GPS_IFD_POINTER = 0x8825;

    /**
     * The tag of the Exif directory entry holding the offset of the interoperability directory.
     */
    public static final int INTEROP_IFD_POINTER = 0xA005;

    /**
     * The tag of the IFD1 entry holding the offset of the JPEG thumbnail
     * ({@code JPEGInterchangeFormat}).
     */
    public static final int THUMBNAIL_OFFSET = 0x0201;

    /**
     * The tag of the IFD1 entry holding the size of the JPEG thumbnail
     * ({@code JPEGInterchangeFormatLength}).
     */
    public static final int THUMBNAIL_LENGTH = 0x0202;

    /**
     * The tag of the Exif directory entry holding the manufacturer's maker note.
     */
    public static final int MAKER_NOTE = 0x927C;

    /**
     * An instance without any entry.
     */
    public static final ExifData EMPTY = new ExifData(Map.of(), Map.of(), Map.of());

    /**
     * Validates the directories and makes unmodifiable, order-preserving copies of them.
     *
     * @throws NullPointerException if a map, key or value, or the thumbnail, is {@code null}
     */
    public ExifData {
        ifd0 = copy(ifd0, "ifd0");
        exif = copy(exif, "exif");
        gps = copy(gps, "gps");
        interop = copy(interop, "interop");
        thumbnailIfd = copy(thumbnailIfd, "thumbnailIfd");
        Objects.requireNonNull(thumbnail, "thumbnail");
        thumbnail = thumbnail.map(byte[]::clone);
    }

    /**
     * Creates an instance with the three main directories and no interoperability directory or
     * thumbnail.
     *
     * @param ifd0 the entries of IFD0
     * @param exif the entries of the Exif private directory
     * @param gps  the entries of the GPS directory
     * @throws NullPointerException if a map, key or value is {@code null}
     */
    public ExifData(Map<Integer, ExifValue> ifd0, Map<Integer, ExifValue> exif, Map<Integer, ExifValue> gps) {
        this(ifd0, exif, gps, Map.of(), Map.of(), Optional.empty());
    }

    /**
     * Copies a directory into an unmodifiable map that keeps the iteration order.
     *
     * @param source the directory to copy
     * @param name   the component name used in error messages
     * @return the copy
     * @throws NullPointerException if the map, a key or a value is {@code null}
     */
    private static Map<Integer, ExifValue> copy(Map<Integer, ExifValue> source, String name) {
        Objects.requireNonNull(source, name);
        var copy = new LinkedHashMap<Integer, ExifValue>(Math.max(4, source.size() * 2));
        for (var entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), name + " key"),
                    Objects.requireNonNull(entry.getValue(), name + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the thumbnail.
     *
     * @return a copy of the JPEG thumbnail, or empty when absent
     */
    @Override
    public Optional<byte[]> thumbnail() {
        return thumbnail.map(byte[]::clone);
    }

    /**
     * Returns the entries of a directory.
     *
     * @param directory the directory whose entries are returned
     * @return the entries; unmodifiable
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    public Map<Integer, ExifValue> directory(Directory directory) {
        return switch (directory) {
            case IFD0 -> ifd0;
            case EXIF -> exif;
            case GPS -> gps;
            case INTEROP -> interop;
            case THUMBNAIL -> thumbnailIfd;
        };
    }

    /**
     * Returns the value of an entry.
     *
     * @param directory the directory to look in
     * @param tag the 16-bit tag number
     * @return the value, or empty when absent
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    public Optional<ExifValue> get(Directory directory, int tag) {
        return Optional.ofNullable(directory(directory).get(tag));
    }

    /**
     * Returns a copy with an entry set, replacing the old value in place or adding it last.
     *
     * @param directory the directory to change
     * @param tag the tag, from 0 to 65535
     * @param value the new value of the entry
     * @return the new instance
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if {@code tag} is out of range
     */
    public ExifData with(Directory directory, int tag, ExifValue value) {
        Objects.requireNonNull(value, "value");
        if (tag < 0 || tag > 0xFFFF) {
            throw new IllegalArgumentException("invalid EXIF tag " + tag);
        }
        var map = new LinkedHashMap<>(directory(directory));
        map.put(tag, value);
        return replace(directory, map);
    }

    /**
     * Returns a copy without an entry.
     *
     * @param directory the directory to change
     * @param tag the 16-bit tag number of the entry to remove
     * @return the new instance
     * @throws NullPointerException if {@code directory} is {@code null}
     */
    public ExifData without(Directory directory, int tag) {
        var map = new LinkedHashMap<>(directory(directory));
        map.remove(tag);
        return replace(directory, map);
    }

    /**
     * Returns a copy with a directory replaced.
     *
     * @param directory the directory to replace
     * @param entries the new entries
     * @return the new instance
     */
    private ExifData replace(Directory directory, Map<Integer, ExifValue> entries) {
        return new ExifData(directory == Directory.IFD0 ? entries : ifd0, directory == Directory.EXIF ? entries : exif,
                directory == Directory.GPS ? entries : gps, directory == Directory.INTEROP ? entries : interop,
                directory == Directory.THUMBNAIL ? entries : thumbnailIfd, thumbnail);
    }

    /**
     * Returns a copy whose IFD0 {@code Orientation} entry holds an orientation, as a single
     * {@code SHORT}.
     *
     * @param orientation the orientation to record
     * @return the new instance
     * @throws NullPointerException if {@code orientation} is {@code null}
     */
    public ExifData withOrientation(Orientation orientation) {
        return with(Directory.IFD0, ORIENTATION,
                new ExifValue.Integers(ExifValue.Type.SHORT, new long[]{orientation.exifCode()}));
    }

    /**
     * Returns a copy without the GPS directory, which records where the picture was taken.
     *
     * @return the new instance
     */
    public ExifData withoutGps() {
        var map = new LinkedHashMap<>(ifd0);
        map.remove(GPS_IFD_POINTER);
        return new ExifData(map, exif, Map.of(), interop, thumbnailIfd, thumbnail);
    }

    /**
     * Returns a copy without the thumbnail and its directory.
     *
     * @return the new instance
     */
    public ExifData withoutThumbnail() {
        return new ExifData(ifd0, exif, gps, interop, Map.of(), Optional.empty());
    }

    /**
     * Returns a copy with another JPEG thumbnail.
     *
     * <p>The thumbnail directory keeps its entries; the writer records the thumbnail's location
     * and size in it.
     *
     * @param jpeg the thumbnail, a complete JPEG file
     * @return the new instance
     * @throws NullPointerException if {@code jpeg} is {@code null}
     */
    public ExifData withThumbnail(byte[] jpeg) {
        Objects.requireNonNull(jpeg, "jpeg");
        return new ExifData(ifd0, exif, gps, interop, thumbnailIfd, Optional.of(jpeg));
    }

    /**
     * Returns whether the GPS directory records a position.
     *
     * @return {@code true} when the GPS directory has an entry other than its version
     */
    public boolean hasLocation() {
        for (var tag : gps.keySet()) {
            if (tag != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the display orientation recorded in IFD0.
     *
     * @return the orientation, or {@link Orientation#NORMAL} when the entry is absent, not an
     *         integer, or outside 1..8
     */
    public Orientation orientation() {
        if (ifd0.get(ORIENTATION) instanceof ExifValue.Integers integers && integers.count() > 0) {
            var code = integers.get(0);
            return code >= 1 && code <= 8 ? Orientation.ofExif((int) code) : Orientation.NORMAL;
        }
        return Orientation.NORMAL;
    }

    /**
     * Parses an EXIF block into its directories and thumbnail.
     *
     * <p>The result is the one {@link ExifReader#parse(byte[])} gives: unreadable entries and
     * directories are skipped rather than rejected.
     *
     * @param tiff the EXIF block, starting with its byte-order mark
     * @return the parsed directories
     * @throws com.github.auties00.smalt.MediaException.Malformed if the block has no valid TIFF header
     * or its IFD0 offset lies outside the block
     */
    public static ExifData parse(byte[] tiff) {
        return ExifReader.parse(tiff);
    }

    /**
     * Writes these directories and thumbnail as a fresh big-endian EXIF block.
     *
     * @return the block, starting with its byte-order mark
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the block exceeds what TIFF offsets address
     * @see ExifWriter#write(ExifData)
     */
    public byte[] toBytes() {
        return ExifWriter.write(this);
    }

    /**
     * Writes these directories and thumbnail as a fresh EXIF block in a byte order.
     *
     * @param order the byte order
     * @return the block, starting with its byte-order mark
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the block exceeds what TIFF offsets address
     * @see ExifWriter#write(ExifData, ByteOrder)
     */
    public byte[] toBytes(ByteOrder order) {
        return ExifWriter.write(this, order);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ExifData that && ifd0.equals(that.ifd0) && exif.equals(that.exif) && gps.equals(that.gps)
                && interop.equals(that.interop) && thumbnailIfd.equals(that.thumbnailIfd)
                && thumbnail.isPresent() == that.thumbnail.isPresent()
                && (thumbnail.isEmpty() || Arrays.equals(thumbnail.get(), that.thumbnail.get()));
    }

    @Override
    public int hashCode() {
        return Objects.hash(ifd0, exif, gps, interop, thumbnailIfd, thumbnail.map(Arrays::hashCode).orElse(0));
    }

    @Override
    public String toString() {
        return "ExifData[ifd0=" + ifd0 + ", exif=" + exif + ", gps=" + gps + ", interop=" + interop + ", thumbnailIfd="
                + thumbnailIfd + ", thumbnail=" + thumbnail.map(bytes -> bytes.length + " bytes").orElse("none") + "]";
    }

    /**
     * Names the directories of an EXIF block.
     */
    public enum Directory {
        /**
         * The primary image directory, IFD0.
         */
        IFD0,

        /**
         * The Exif private directory.
         */
        EXIF,

        /**
         * The GPS directory.
         */
        GPS,

        /**
         * The interoperability directory, linked from the Exif directory.
         */
        INTEROP,

        /**
         * The thumbnail directory, IFD1, chained after IFD0.
         */
        THUMBNAIL
    }
}
