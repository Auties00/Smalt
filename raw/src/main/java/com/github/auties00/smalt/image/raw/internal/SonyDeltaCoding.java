package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the lossless variable-length coding of the 12-bit sensor data of the Sony DSLR-A100 to A390, TIFF
 * compression 32767 in the files that have a raw directory.
 *
 * <p>The stream is read most significant bit first. It holds whole columns from the rightmost to the leftmost, each
 * of them its even rows from top to bottom and then its odd rows, and its columns are eight rows taller than the
 * directory's image. Every sample is a difference from the sample before it in the stream, whichever color and column
 * that one has, starting from zero and without any reset. A difference is a category code followed by that many bits,
 * negative when the first of them is clear, a value {@code v} of {@code c} bits then standing for
 * {@code v - (2^c - 1)}:
 * <ul>
 * <li>{@code 11}: category 1;</li>
 * <li>{@code 10}: category 2;</li>
 * <li>{@code 011}: category 0, a difference of zero without any further bits;</li>
 * <li>{@code 010}: category 3;</li>
 * <li>{@code 001}: category 4, and one more leading zero for each category more, up to the ten zeros and a one of
 * category 12.</li>
 * </ul>
 *
 * @implNote This implementation's coding was derived without any description of it, from the CC0 samples of
 * raw.pixls.us and the photosites Adobe DNG Converter, run as a black box, stores for them. The long repetitions at
 * the start of a stream give the code of category 0, the places of the saturated runs they encode in the converter's
 * image give the order of the samples, and comparing the bits with the converter's differences gives the other codes.
 * The stream's length in samples gives the height of its columns. Decoding is sequential because the only way to find
 * a sample is to decode the ones before it; the bits after the last sample the image uses are not read, since a
 * stream's end holds padding.
 */
final class SonyDeltaCoding {
    /**
     * The rows each column of a stream has below the directory's image.
     */
    static final int EXTRA_ROWS = 8;

    /**
     * The largest category a 12-bit sample can need.
     */
    private static final int MAX_CATEGORY = 12;

    /**
     * The largest sample.
     */
    private static final int MAX_SAMPLE = 4095;

    /**
     * The bits one sample takes at most: the longest category code and the bits of its category.
     */
    private static final int MAX_SAMPLE_BITS = MAX_CATEGORY - 1 + MAX_CATEGORY;

    /**
     * The first size of the buffer of a stream from a source of unknown size.
     */
    private static final int INITIAL_CAPACITY = 1 << 20;

    /**
     * Prevents instantiation.
     */
    private SonyDeltaCoding() {
        throw new AssertionError();
    }

    /**
     * Returns the reader of a stream.
     *
     * @param reader the TIFF structure
     * @param offset the absolute offset of the stream
     * @param length the length of the stream in bytes, cut at the end of the file
     * @param streamWidth the columns of the stream, at least the plane's width; those to the right of the plane are
     * dropped
     * @return the reader
     */
    static Sensor.Reader reader(TiffReader reader, long offset, long length, int streamWidth) {
        return (plane, limits) -> read(reader, offset, length, streamWidth, plane, limits);
    }

    /**
     * Reads a stream into a plane.
     *
     * @param reader the TIFF structure
     * @param offset the absolute offset of the stream
     * @param length the length of the stream in bytes, cut at the end of the file
     * @param streamWidth the columns of the stream
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void read(TiffReader reader, long offset, long length, int streamWidth, SensorPlane plane,
                             MediaLimits limits) {
        var size = reader.size();
        var remaining = size < 0 ? length : Math.min(length, Math.max(size - offset, 0));
        remaining = Math.min(remaining, Integer.MAX_VALUE - 16);
        // a source of unknown size may end long before the length a file states, so the buffer grows as it is read
        var capacity = (int) (size < 0 ? Math.min(remaining, INITIAL_CAPACITY) : remaining);
        LimitChecks.checkAllocation(limits, capacity);
        var data = new byte[capacity];
        var read = Math.max(reader.readAvailable(offset, data, 0, capacity), 0);
        while (read == data.length && read < remaining) {
            var grown = (int) Math.min(remaining, data.length * 2L);
            LimitChecks.checkAllocation(limits, grown);
            data = Arrays.copyOf(data, grown);
            read += Math.max(reader.readAvailable(offset + read, data, read, grown - read), 0);
        }
        if (read == 0) {
            throw new MediaException.Malformed("Sony sensor data at " + offset + " is outside the file");
        }
        decode(data, read, streamWidth, plane);
    }

    /**
     * Decodes a stream into a plane. A stream that ends early leaves the sample it cuts and those after it at zero.
     *
     * @param data the bytes
     * @param end the index after the last byte of the stream
     * @param streamWidth the columns of the stream
     * @param plane the plane to fill
     * @throws MediaException.Malformed if the stream holds a code of no category or a sample out of range
     */
    static void decode(byte[] data, int end, int streamWidth, SensorPlane plane) {
        var out = plane.integers;
        var width = plane.width;
        var height = plane.height;
        var streamHeight = height + EXTRA_ROWS;
        var limitBits = end * 8L;
        var position = 0;
        var window = 0L;
        var count = 0;
        var sample = 0;
        for (var column = 0; column < streamWidth; column++) {
            var x = streamWidth - 1 - column;
            var visible = x < width;
            for (var phase = 0; phase < 2; phase++) {
                var rows = column == streamWidth - 1 && phase == 1 ? height : streamHeight;
                for (var y = phase; y < rows; y += 2) {
                    if (count < MAX_SAMPLE_BITS) {
                        while (count <= 56) {
                            var next = position < end ? data[position] & 0xFFL : 0;
                            position++;
                            window |= next << (56 - count);
                            count += 8;
                        }
                    }
                    var consumed = position * 8L - count;
                    if (consumed >= limitBits) {
                        return;
                    }
                    var zeros = Long.numberOfLeadingZeros(window);
                    int category;
                    int length;
                    if (zeros == 0) {
                        category = window << 1 < 0 ? 1 : 2;
                        length = 2;
                    } else if (zeros == 1) {
                        category = window << 2 < 0 ? 0 : 3;
                        length = 3;
                    } else {
                        category = zeros + 2;
                        length = zeros + 1;
                        if (category > MAX_CATEGORY) {
                            if (consumed + length > limitBits) {
                                return;
                            }
                            throw new MediaException.Malformed("invalid Sony sensor data code of " + zeros
                                    + " leading zeros");
                        }
                    }
                    if (consumed + length + category > limitBits) {
                        return;
                    }
                    window <<= length;
                    count -= length;
                    if (category != 0) {
                        var difference = (int) (window >>> (64 - category));
                        window <<= category;
                        count -= category;
                        if (difference >>> (category - 1) == 0) {
                            difference -= (1 << category) - 1;
                        }
                        sample += difference;
                        if (sample < 0 || sample > MAX_SAMPLE) {
                            throw new MediaException.Malformed("Sony sensor sample " + sample + " out of range");
                        }
                    }
                    if (visible && y < height) {
                        out[y * width + x] = (short) sample;
                    }
                }
            }
        }
    }
}
