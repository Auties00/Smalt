package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;

/**
 * Finds the thumbnail of an EXIF block: the image its thumbnail directory ({@code IFD1}) locates.
 *
 * <p>A JPEG thumbnail ({@code JPEGInterchangeFormat} and {@code JPEGInterchangeFormatLength}) and an
 * uncompressed chunky 8-bit RGB thumbnail (compression 1, photometric interpretation 2) are found; a block
 * without a thumbnail directory, a thumbnail of another kind and a damaged directory give none.
 */
final class ExifThumbnails {
    /**
     * The {@code ImageWidth} tag.
     */
    private static final int IMAGE_WIDTH = 0x0100;

    /**
     * The {@code ImageLength} tag.
     */
    private static final int IMAGE_LENGTH = 0x0101;

    /**
     * The {@code BitsPerSample} tag.
     */
    private static final int BITS_PER_SAMPLE = 0x0102;

    /**
     * The {@code Compression} tag.
     */
    private static final int COMPRESSION = 0x0103;

    /**
     * The {@code PhotometricInterpretation} tag.
     */
    private static final int PHOTOMETRIC = 0x0106;

    /**
     * The {@code StripOffsets} tag.
     */
    private static final int STRIP_OFFSETS = 0x0111;

    /**
     * The {@code SamplesPerPixel} tag.
     */
    private static final int SAMPLES_PER_PIXEL = 0x0115;

    /**
     * The {@code StripByteCounts} tag.
     */
    private static final int STRIP_BYTE_COUNTS = 0x0117;

    /**
     * The {@code PlanarConfiguration} tag.
     */
    private static final int PLANAR_CONFIGURATION = 0x011C;

    /**
     * The {@code JPEGInterchangeFormat} tag.
     */
    private static final int JPEG_OFFSET = 0x0201;

    /**
     * The {@code JPEGInterchangeFormatLength} tag.
     */
    private static final int JPEG_LENGTH = 0x0202;

    /**
     * The most entries a directory may declare.
     */
    private static final int MAX_ENTRIES = 1024;

    /**
     * The block.
     */
    private final byte[] tiff;

    /**
     * Whether the block is big-endian.
     */
    private final boolean bigEndian;

    /**
     * Constructs a reader of a block.
     *
     * @param tiff the block
     * @param bigEndian whether the block is big-endian
     */
    private ExifThumbnails(byte[] tiff, boolean bigEndian) {
        this.tiff = tiff;
        this.bigEndian = bigEndian;
    }

    /**
     * Returns the thumbnail of an EXIF block.
     *
     * @param exif the block, a TIFF structure starting with its byte-order mark
     * @return the thumbnail, or empty when the block has none the class finds
     */
    static Optional<ImageThumbnail> find(byte[] exif) {
        if (exif.length < 8) {
            return Optional.empty();
        }
        boolean bigEndian;
        if (exif[0] == 'I' && exif[1] == 'I') {
            bigEndian = false;
        } else if (exif[0] == 'M' && exif[1] == 'M') {
            bigEndian = true;
        } else {
            return Optional.empty();
        }
        try {
            return new ExifThumbnails(exif, bigEndian).thumbnail();
        } catch (IndexOutOfBoundsException | ArithmeticException exception) {
            return Optional.empty();
        }
    }

    /**
     * Locates the thumbnail directory and reads its thumbnail.
     *
     * @return the thumbnail, or empty
     */
    private Optional<ImageThumbnail> thumbnail() {
        if (u16(2) != 42) {
            return Optional.empty();
        }
        var ifd0 = u32(4);
        var count0 = u16(Math.toIntExact(ifd0));
        if (count0 > MAX_ENTRIES) {
            return Optional.empty();
        }
        var ifd1 = u32(Math.toIntExact(ifd0 + 2 + 12L * count0));
        if (ifd1 == 0 || ifd1 == ifd0) {
            return Optional.empty();
        }
        var directory = Math.toIntExact(ifd1);
        var count = u16(directory);
        if (count > MAX_ENTRIES) {
            return Optional.empty();
        }
        var values = new long[0x0203];
        Arrays.fill(values, -1);
        long[] stripOffsets = null;
        long[] stripCounts = null;
        var bitsAllEight = true;
        for (var i = 0; i < count; i++) {
            var entry = directory + 2 + 12 * i;
            var tag = u16(entry);
            var type = u16(entry + 2);
            var length = u32(entry + 4);
            if (tag == STRIP_OFFSETS || tag == STRIP_BYTE_COUNTS) {
                var array = values(entry, type, length);
                if (tag == STRIP_OFFSETS) {
                    stripOffsets = array;
                } else {
                    stripCounts = array;
                }
            } else if (tag == BITS_PER_SAMPLE) {
                for (var bits : values(entry, type, length)) {
                    bitsAllEight &= bits == 8;
                }
            } else if (tag < values.length && length >= 1) {
                // entries of other types, such as the RATIONAL resolutions of every thumbnail directory, are skipped
                var value = values(entry, type, length);
                if (value.length >= 1) {
                    values[tag] = value[0];
                }
            }
        }
        if (values[JPEG_OFFSET] >= 0 && values[JPEG_LENGTH] > 0) {
            var jpeg = slice(values[JPEG_OFFSET], values[JPEG_LENGTH]);
            if (jpeg == null) {
                return Optional.empty();
            }
            try {
                var source = MediaSource.of(jpeg);
                if (Images.detect(source).filter(ImageFormat.JPEG::equals).isEmpty()) {
                    return Optional.empty();
                }
                var info = Images.probe(source);
                return Optional.of(new ImageThumbnail.Jpeg(jpeg, info.width(), info.height()));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        }
        var width = values[IMAGE_WIDTH];
        var height = values[IMAGE_LENGTH];
        if (values[COMPRESSION] == 1 && values[PHOTOMETRIC] == 2 && width > 0 && height > 0 && bitsAllEight
                && (values[SAMPLES_PER_PIXEL] == 3 || values[SAMPLES_PER_PIXEL] == -1)
                && (values[PLANAR_CONFIGURATION] == 1 || values[PLANAR_CONFIGURATION] == -1)
                && stripOffsets != null && stripCounts != null && stripOffsets.length == stripCounts.length) {
            var size = Math.multiplyExact(Math.multiplyExact(width, height), 3);
            var samples = new ByteArrayOutputStream();
            for (var strip = 0; strip < stripOffsets.length && samples.size() < size; strip++) {
                var data = slice(stripOffsets[strip], stripCounts[strip]);
                if (data == null) {
                    return Optional.empty();
                }
                samples.writeBytes(data);
            }
            if (samples.size() < size) {
                return Optional.empty();
            }
            return Optional.of(new ImageThumbnail.Rgb(Arrays.copyOf(samples.toByteArray(), (int) size), (int) width,
                    (int) height));
        }
        return Optional.empty();
    }

    /**
     * Reads the values of a directory entry of the SHORT or LONG type.
     *
     * @param entry the offset of the entry
     * @param type the field type
     * @param count the number of values to read
     * @return the values, empty for another type
     */
    private long[] values(int entry, int type, long count) {
        var size = switch (type) {
            case 3 -> 2;
            case 4 -> 4;
            default -> 0;
        };
        if (size == 0 || count > MAX_ENTRIES * 64L) {
            return new long[0];
        }
        var total = size * count;
        var at = total <= 4 ? entry + 8 : Math.toIntExact(u32(entry + 8));
        var result = new long[(int) count];
        for (var i = 0; i < count; i++) {
            result[i] = size == 2 ? u16(at + 2 * i) : u32(at + 4 * i);
        }
        return result;
    }

    /**
     * Returns a range of the block.
     *
     * @param offset the offset of the first byte
     * @param length the number of bytes
     * @return a copy of the range, or {@code null} when it is not inside the block
     */
    private byte[] slice(long offset, long length) {
        if (offset < 0 || length <= 0 || offset + length > tiff.length) {
            return null;
        }
        return Arrays.copyOfRange(tiff, (int) offset, (int) (offset + length));
    }

    /**
     * Reads an unsigned 16-bit value in the block's byte order.
     *
     * @param offset the offset of the first byte
     * @return the value
     */
    private int u16(int offset) {
        var first = tiff[offset] & 0xFF;
        var second = tiff[offset + 1] & 0xFF;
        return bigEndian ? first << 8 | second : second << 8 | first;
    }

    /**
     * Reads an unsigned 32-bit value in the block's byte order.
     *
     * @param offset the offset of the first byte
     * @return the value
     */
    private long u32(int offset) {
        var first = u16(offset);
        var second = u16(offset + 2);
        return bigEndian ? (long) first << 16 | second : (long) second << 16 | first;
    }
}
