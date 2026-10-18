package com.github.auties00.smalt.internal.tiff;

import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.util.Arrays;

/**
 * Reads the EXIF facts that the image readers of every artifact need without parsing a whole EXIF
 * block: the {@code Orientation} tag of the first directory, and the TIFF structure behind the
 * {@code "Exif\0\0"} identifier some containers keep in front of it.
 *
 * <p>This class is thread-safe.
 */
public final class TiffExif {
    /**
     * The {@code Orientation} tag.
     */
    public static final int ORIENTATION = 0x0112;

    /**
     * The identifier JPEG {@code APP1} segments put before the TIFF structure.
     */
    private static final byte[] PREFIX = {'E', 'x', 'i', 'f', 0, 0};

    /**
     * Prevents instantiation.
     */
    private TiffExif() {
        throw new AssertionError();
    }

    /**
     * Reads the {@code Orientation} tag of the first directory of a TIFF structure.
     *
     * <p>The rule: the first entry with the tag decides; it must be of type {@code SHORT} or
     * {@code LONG}, hold at least one value, and its first value must be 1 to 8. A value stored
     * outside the entry is followed. Anything else, including a structure that is not TIFF or is
     * cut off, gives {@link Orientation#NORMAL}. The directory offset is relative to
     * {@code offset}; entries cut off by the end of {@code data} are ignored.
     *
     * @param data the bytes holding the structure
     * @param offset the index of its byte order mark
     * @return the orientation
     */
    public static Orientation orientation(byte[] data, int offset) {
        var header = TiffHeader.parse(data, offset, data.length - offset);
        if (header == null || header.big()) {
            return Orientation.NORMAL;
        }
        var little = header.little();
        var ifd = offset + header.firstIfd();
        if (header.firstIfd() < TiffHeader.SIZE || ifd + 2 > data.length) {
            return Orientation.NORMAL;
        }
        var entries = ByteArrays.u16(data, (int) ifd, little);
        for (var i = 0; i < entries; i++) {
            var entry = ifd + 2 + 12L * i;
            if (entry + 12 > data.length) {
                break;
            }
            var at = (int) entry;
            if (ByteArrays.u16(data, at, little) != ORIENTATION) {
                continue;
            }
            var type = ByteArrays.u16(data, at + 2, little);
            var count = ByteArrays.u32(data, at + 4, little);
            if (count < 1 || (type != TiffTypes.SHORT && type != TiffTypes.LONG)) {
                return Orientation.NORMAL;
            }
            var size = TiffTypes.size(type);
            var position = count * size <= 4 ? entry + 8 : offset + ByteArrays.u32(data, at + 8, little);
            if (position < 0 || position + size > data.length) {
                return Orientation.NORMAL;
            }
            var code = type == TiffTypes.SHORT ? ByteArrays.u16(data, (int) position, little)
                    : ByteArrays.u32(data, (int) position, little);
            return code >= 1 && code <= 8 ? Orientation.ofExif((int) code) : Orientation.NORMAL;
        }
        return Orientation.NORMAL;
    }

    /**
     * {@return the length of the {@code "Exif\0\0"} identifier at an offset: 6 when present,
     * otherwise 0}
     *
     * @param data the bytes
     * @param offset the index where the identifier would start
     */
    public static int prefixLength(byte[] data, int offset) {
        return offset >= 0 && data.length - offset >= PREFIX.length
                && Arrays.equals(data, offset, offset + PREFIX.length, PREFIX, 0, PREFIX.length) ? PREFIX.length : 0;
    }

    /**
     * Returns an EXIF block without the {@code "Exif\0\0"} identifier.
     *
     * @param exif the block, with or without the identifier
     * @return {@code exif} itself when it has no identifier, otherwise a copy of the bytes after it
     */
    public static byte[] stripExifPrefix(byte[] exif) {
        var length = prefixLength(exif, 0);
        return length == 0 ? exif : Arrays.copyOfRange(exif, length, exif.length);
    }
}
