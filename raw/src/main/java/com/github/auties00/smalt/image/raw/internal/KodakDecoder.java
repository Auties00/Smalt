package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Decodes Kodak's compression 65000 of the DCS sensor data.
 *
 * <p>Each row is coded in groups of up to 256 photosites, each group starting on a 4-byte boundary of the strip.
 * A group holds first the bit length of each photosite's difference, a 4-bit number per photosite, two to a byte,
 * the low half first; then the differences, read least significant bit first from big-endian 16-bit words, each
 * of its bit length and signed as in T.81 (a length {@code n} difference below {@code 2^(n-1)} is negative,
 * {@code v - 2^n + 1}). The first two photosites of a group are their differences; each other photosite is its
 * difference added to the photosite two columns to its left.
 *
 * <p>The raw directory's tag 65001 lists where each group but the first starts, relative to the strip, which lets
 * the rows decode in parallel; without it the rows decode in order.
 *
 * @implNote This implementation's layout was worked out from CC0 sample files, against the photosites Adobe DNG
 * Converter, run as a black box, stores for them, which it reproduces exactly.
 */
final class KodakDecoder {
    /**
     * The tag of the raw directory listing the starts of the groups.
     */
    static final int GROUP_OFFSETS = 65001;

    /**
     * The most photosites of a group.
     */
    static final int GROUP = 256;

    /**
     * Prevents instantiation.
     */
    private KodakDecoder() {
        throw new AssertionError();
    }

    /**
     * Returns the reader of a raw directory's compression 65000 sensor data.
     *
     * @param raw the raw directory, of one strip
     * @param width the width in photosites
     * @param height the height in photosites
     * @return the reader
     * @throws MediaException.Malformed if the directory has no single strip
     */
    static Sensor.Reader reader(Ifd raw, int width, int height) {
        var offsets = raw.longs(TiffSamples.STRIP_OFFSETS);
        var counts = raw.longs(TiffSamples.STRIP_BYTE_COUNTS);
        if (offsets == null || counts == null || offsets.length != 1 || counts.length != 1 || counts[0] <= 0) {
            throw new MediaException.Malformed("Kodak compressed sensor data without a single strip");
        }
        var reader = raw.reader();
        var source = reader.source();
        var offset = reader.base() + offsets[0];
        var known = source.size();
        var length = known >= 0 ? Math.min(counts[0], Math.max(0, known - offset)) : counts[0];
        var groups = (width + GROUP - 1) / GROUP;
        var listed = raw.longs(GROUP_OFFSETS);
        var rowStarts = listed != null && listed.length == (long) groups * height - 1 ? rowStarts(listed, groups,
                height, length) : null;
        return (plane, limits) -> decode(reader, offset, length, rowStarts, plane, limits);
    }

    /**
     * Returns the start of each row from the group starts, or {@code null} when they are not increasing within the
     * strip.
     *
     * @param listed the starts of the groups but the first
     * @param groups the groups of a row
     * @param height the number of rows
     * @param length the strip's length
     * @return the starts of the rows
     */
    private static long[] rowStarts(long[] listed, int groups, int height, long length) {
        var starts = new long[height];
        for (var row = 1; row < height; row++) {
            starts[row] = listed[row * groups - 1];
            if (starts[row] <= starts[row - 1] || starts[row] >= length || (starts[row] & 3) != 0) {
                return null;
            }
        }
        return starts;
    }

    /**
     * Decodes the strip into the plane.
     *
     * <p>A strip that ends early decodes its missing photosites from zero bits.
     *
     * @param reader the TIFF structure
     * @param offset the strip's absolute offset
     * @param length the strip's stated length
     * @param rowStarts the start of each row in the strip, or {@code null} to decode the rows in order
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void decode(TiffReader reader, long offset, long length, long[] rowStarts, SensorPlane plane,
                               MediaLimits limits) {
        LimitChecks.checkAllocation(limits, length);
        if (length > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("Kodak sensor data of " + length + " bytes");
        }
        var data = new byte[(int) length];
        reader.readAvailable(offset, data, 0, data.length);
        if (rowStarts == null) {
            var position = 0;
            var row = new int[plane.width];
            var lengths = new int[GROUP];
            for (var y = 0; y < plane.height; y++) {
                position = row(data, position, row, lengths, plane, y);
            }
            return;
        }
        var bands = ParallelBands.bands(plane.height, (long) plane.width * plane.height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, plane.height);
            var last = ParallelBands.start(band + 1, bands, plane.height);
            var row = new int[plane.width];
            var lengths = new int[GROUP];
            for (var y = first; y < last; y++) {
                row(data, (int) rowStarts[y], row, lengths, plane, y);
            }
        });
    }

    /**
     * Decodes one row.
     *
     * @param data the strip
     * @param start the index of the row's first group
     * @param row a scratch row of the plane's width
     * @param lengths a scratch array of {@link #GROUP} bit lengths
     * @param plane the plane to fill
     * @param y the row's index
     * @return the index after the row's last group
     */
    private static int row(byte[] data, int start, int[] row, int[] lengths, SensorPlane plane, int y) {
        var width = plane.width;
        var position = start;
        for (var x = 0; x < width; x += GROUP) {
            var count = Math.min(GROUP, width - x);
            for (var index = 0; index < count; index++) {
                var at = position + index / 2;
                var value = at < data.length ? data[at] & 0xFF : 0;
                lengths[index] = (index & 1) == 0 ? value & 0x0F : value >>> 4;
            }
            position += (count + 1) / 2;
            var buffer = 0L;
            var bits = 0;
            for (var index = 0; index < count; index++) {
                var size = lengths[index];
                while (bits < size) {
                    var word = position + 1 < data.length
                            ? (data[position] & 0xFF) << 8 | data[position + 1] & 0xFF : 0;
                    position += 2;
                    buffer |= (long) word << bits;
                    bits += 16;
                }
                var value = (int) (buffer & ((1L << size) - 1));
                buffer >>>= size;
                bits -= size;
                var difference = size == 0 ? 0 : value >= 1 << (size - 1) ? value : value - (1 << size) + 1;
                row[x + index] = index < 2 ? difference : row[x + index - 2] + difference;
            }
            position = position + 3 & ~3;
        }
        var target = y * width;
        for (var x = 0; x < width; x++) {
            plane.integers[target + x] = (short) Math.clamp(row[x], 0, 65535);
        }
        return position;
    }
}
