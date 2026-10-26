package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Maps the offsets of a file with compressed top-level boxes ({@code !mov}, {@code !mof}, {@code !six} and
 * {@code !ssx}, ISO/IEC 14496-12 clause 8.20) to offsets in the file as it is.
 *
 * <p>A compressed box holds the gzip-compressed payload of the box it replaces, and every offset the file
 * stores (chunk offsets, base data offsets, fragment data offsets) counts in the file as it would be with
 * each compressed box replaced by its uncompressed form, an 8-byte header (16 bytes for a box of more than
 * {@code 2^32 - 1} bytes) and the payload. The boxes are added in file order, so that an offset in that
 * uncompressed file maps to the file by subtracting what the compressed boxes before it saved.
 */
final class CompressedBoxes {
    /**
     * The offset, in the uncompressed file, just past each added box, ascending.
     */
    private long[] ends = new long[4];

    /**
     * The bytes saved by each added box and every box before it, parallel to {@link #ends}; negative when the
     * compressed boxes are larger than their uncompressed forms.
     */
    private long[] saved = new long[4];

    /**
     * The number of added boxes.
     */
    private int count;

    /**
     * Returns the size of a box holding a payload, with the shortest header that can state it.
     *
     * @param payload the size of the payload
     * @return the size of the box
     */
    static long boxSize(long payload) {
        return payload + (payload + 8 > 0xFFFFFFFFL ? 16 : 8);
    }

    /**
     * Returns the offset in the uncompressed file of a position of the file that lies after every added box.
     *
     * @param position the offset in the file
     * @return the offset in the uncompressed file
     */
    long uncompressed(long position) {
        return position + (count == 0 ? 0 : saved[count - 1]);
    }

    /**
     * Adds the next compressed box of the file, which lies after every box added before it.
     *
     * @param position the offset of the box in the file
     * @param size the size of the box in the file
     * @param uncompressedSize the size of the box it replaces, header included
     */
    void add(long position, long size, long uncompressedSize) {
        var end = uncompressed(position) + uncompressedSize;
        if (count == ends.length) {
            ends = Arrays.copyOf(ends, count * 2);
            saved = Arrays.copyOf(saved, count * 2);
        }
        ends[count] = end;
        saved[count] = (count == 0 ? 0 : saved[count - 1]) + uncompressedSize - size;
        count++;
    }

    /**
     * Maps an offset of the uncompressed file to the file: an offset at or past the end of a compressed box
     * moves back by the bytes that box and those before it saved.
     *
     * @param offset the offset in the uncompressed file
     * @return the offset in the file
     */
    long toFile(long offset) {
        var low = 0;
        var high = count - 1;
        var found = -1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (ends[middle] <= offset) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found < 0 ? offset : offset - saved[found];
    }

    /**
     * Maps the offsets of a range of samples of a sample table into the file.
     *
     * @param samples the sample table
     * @param from the first sample to move
     * @param to the sample after the last one to move
     */
    void relocate(SampleTable samples, int from, int to) {
        for (var i = from; i < to; i++) {
            samples.offsets[i] = toFile(samples.offsets[i]);
        }
    }

    /**
     * Decompresses the payload of a compressed box, a gzip stream of the payload of the box it replaces,
     * within the byte limit.
     *
     * @param compressed the payload of the compressed box
     * @param type the type of the compressed box, for diagnostics
     * @param limits the resource bounds
     * @return the payload of the box it replaces
     * @throws MediaException.Malformed if the payload is not a gzip stream
     * @throws MediaException.LimitExceeded if the box it replaces is larger than the byte limit
     */
    static byte[] inflate(byte[] compressed, String type, MediaLimits limits) {
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            var output = new ByteArrayOutputStream((int) Math.min(compressed.length * 4L, 1 << 20));
            var buffer = new byte[8192];
            for (var read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                LimitChecks.checkAllocation(limits, output.size() + (long) read);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new MediaException.Malformed("the compressed box " + type + " is not a gzip stream: "
                    + exception.getMessage());
        }
    }
}
