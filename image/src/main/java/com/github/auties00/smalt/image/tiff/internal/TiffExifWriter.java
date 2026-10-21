package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * Writes EXIF metadata into the directory of a TIFF page: the descriptive entries of the first
 * EXIF directory into the page's directory, and the Exif, GPS and interoperability directories as
 * sub-directories it points to.
 *
 * <p>Entries of the first directory that describe the image structure are left out, since the
 * encoder writes them; so are the sub-directory pointers, which the sub-directories replace, and
 * the thumbnail entries. Values are written in the page's byte order.
 */
public final class TiffExifWriter {
    /**
     * The tags of the first directory that the encoder writes or that describe the image data,
     * never copied from EXIF metadata.
     */
    private static final Set<Integer> STRUCTURAL = Set.of(
            TiffTags.NEW_SUBFILE_TYPE, 255, TiffTags.IMAGE_WIDTH, TiffTags.IMAGE_LENGTH, TiffTags.BITS_PER_SAMPLE,
            TiffTags.COMPRESSION, TiffTags.PHOTOMETRIC, 263, 264, 265, TiffTags.FILL_ORDER, TiffTags.STRIP_OFFSETS,
            TiffTags.ORIENTATION, TiffTags.SAMPLES_PER_PIXEL, TiffTags.ROWS_PER_STRIP, TiffTags.STRIP_BYTE_COUNTS,
            280, 281, TiffTags.X_RESOLUTION, TiffTags.Y_RESOLUTION, TiffTags.PLANAR_CONFIGURATION, 288, 289,
            TiffTags.T4_OPTIONS, TiffTags.T6_OPTIONS, TiffTags.RESOLUTION_UNIT, TiffTags.PREDICTOR,
            TiffTags.COLOR_MAP, TiffTags.TILE_WIDTH, TiffTags.TILE_LENGTH, TiffTags.TILE_OFFSETS,
            TiffTags.TILE_BYTE_COUNTS, TiffTags.SUB_IFDS, TiffTags.INK_SET, TiffTags.INK_NAMES,
            TiffTags.NUMBER_OF_INKS, TiffTags.EXTRA_SAMPLES, TiffTags.SAMPLE_FORMAT, TiffTags.S_MIN_SAMPLE_VALUE,
            TiffTags.S_MAX_SAMPLE_VALUE, TiffTags.JPEG_TABLES, TiffTags.DECODE, 512, TiffTags.JPEG_INTERCHANGE_FORMAT,
            TiffTags.JPEG_INTERCHANGE_FORMAT_LENGTH, TiffTags.JPEG_RESTART_INTERVAL, 517, 518,
            TiffTags.JPEG_Q_TABLES, TiffTags.JPEG_DC_TABLES, TiffTags.JPEG_AC_TABLES, TiffTags.Y_CB_CR_COEFFICIENTS,
            TiffTags.Y_CB_CR_SUBSAMPLING, TiffTags.Y_CB_CR_POSITIONING, TiffTags.REFERENCE_BLACK_WHITE, TiffTags.XMP,
            TiffTags.IPTC, 34377, 301, 336, 342,
            TiffTags.EXIF_IFD, TiffTags.ICC_PROFILE, TiffTags.GPS_IFD, TiffTags.INTEROPERABILITY_IFD,
            TiffTags.LERC_PARAMETERS);

    /**
     * Prevents instantiation.
     */
    private TiffExifWriter() {
        throw new AssertionError();
    }

    /**
     * Writes EXIF metadata into a page's directory.
     *
     * @param exif the metadata
     * @param page the page's directory, updated
     * @param little whether values are little-endian
     * @param big whether the directories are BigTIFF directories
     */
    public static void write(ExifData exif, IfdBuilder page, boolean little, boolean big) {
        for (var entry : exif.ifd0().entrySet()) {
            var tag = entry.getKey();
            if (tag >= 0 && tag <= 0xFFFF && !STRUCTURAL.contains(tag)) {
                put(page, tag, entry.getValue(), little);
            }
        }
        var interop = directory(exif.interop(), Set.of(), little, big);
        var exifDirectory = directory(exif.exif(), Set.of(ExifData.INTEROP_IFD_POINTER), little, big);
        if (!interop.isEmpty()) {
            exifDirectory.child(TiffTags.INTEROPERABILITY_IFD, interop);
        }
        if (!exifDirectory.isEmpty()) {
            page.child(TiffTags.EXIF_IFD, exifDirectory);
        }
        var gps = directory(exif.gps(), Set.of(), little, big);
        if (!gps.isEmpty()) {
            page.child(TiffTags.GPS_IFD, gps);
        }
    }

    /**
     * Writes one entry of EXIF metadata into a directory when the metadata holds it.
     *
     * @param exif the metadata
     * @param tag the tag of the first directory
     * @param page the directory, updated
     * @param little whether values are little-endian
     * @return whether the metadata holds the entry
     */
    public static boolean copy(ExifData exif, int tag, IfdBuilder page, boolean little) {
        var value = exif.ifd0().get(tag);
        if (value == null) {
            return false;
        }
        put(page, tag, value, little);
        return true;
    }

    /**
     * Builds a sub-directory from EXIF entries.
     *
     * @param entries the entries by tag
     * @param skipped the tags left out
     * @param little whether values are little-endian
     * @param big whether the directory is a BigTIFF directory
     * @return the directory, possibly empty
     */
    private static IfdBuilder directory(Map<Integer, ExifValue> entries, Set<Integer> skipped, boolean little,
                                        boolean big) {
        var directory = new IfdBuilder(little, big);
        for (var entry : entries.entrySet()) {
            var tag = entry.getKey();
            if (tag >= 0 && tag <= 0xFFFF && !skipped.contains(tag)) {
                put(directory, tag, entry.getValue(), little);
            }
        }
        return directory;
    }

    /**
     * Adds an EXIF value to a directory.
     *
     * @param directory the directory, updated
     * @param tag the tag number
     * @param value the value to store
     * @param little whether values are little-endian
     */
    private static void put(IfdBuilder directory, int tag, ExifValue value, boolean little) {
        var bytes = encode(value, little);
        directory.raw(tag, value.type().code(), bytes.length / value.type().size(), bytes);
    }

    /**
     * Encodes a value as an entry stores it; text gains its terminating NUL.
     *
     * @param value the value to encode
     * @param little whether to write integers little-endian
     * @return the bytes, a whole number of elements of the value's type
     */
    static byte[] encode(ExifValue value, boolean little) {
        return switch (value) {
            case ExifValue.Ascii ascii -> {
                var text = ascii.value().getBytes(StandardCharsets.ISO_8859_1);
                yield Arrays.copyOf(text, text.length + 1);
            }
            case ExifValue.Undefined undefined -> undefined.bytes();
            case ExifValue.Integers integers -> {
                var size = integers.type().size();
                var values = integers.values();
                var bytes = new byte[values.length * size];
                for (var i = 0; i < values.length; i++) {
                    switch (size) {
                        case 1 -> bytes[i] = (byte) values[i];
                        case 2 -> ByteArrays.put16(bytes, 2 * i, (int) values[i], little);
                        default -> ByteArrays.put32(bytes, 4 * i, values[i], little);
                    }
                }
                yield bytes;
            }
            case ExifValue.Rationals rationals -> {
                var numerators = rationals.numerators();
                var denominators = rationals.denominators();
                var bytes = new byte[numerators.length * 8];
                for (var i = 0; i < numerators.length; i++) {
                    ByteArrays.put32(bytes, 8 * i, numerators[i], little);
                    ByteArrays.put32(bytes, 8 * i + 4, denominators[i], little);
                }
                yield bytes;
            }
            case ExifValue.Reals reals -> {
                var values = reals.values();
                var single = reals.type() == ExifValue.Type.FLOAT;
                var bytes = new byte[values.length * (single ? 4 : 8)];
                for (var i = 0; i < values.length; i++) {
                    if (single) {
                        ByteArrays.put32(bytes, 4 * i, Float.floatToRawIntBits((float) values[i]) & 0xFFFFFFFFL, little);
                    } else if (little) {
                        ByteArrays.put64le(bytes, 8 * i, Double.doubleToRawLongBits(values[i]));
                    } else {
                        ByteArrays.put64be(bytes, 8 * i, Double.doubleToRawLongBits(values[i]));
                    }
                }
                yield bytes;
            }
        };
    }
}
