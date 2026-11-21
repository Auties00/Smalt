package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Reads uncompressed sensor data stored as rows of a fixed number of bytes, bands of rows in
 * parallel; rows past the end of the source are left at zero and a row cut off by it keeps the
 * samples it holds.
 */
final class PackedRows {
    /**
     * The bytes a band of rows reads at a time.
     */
    private static final int CHUNK = 1 << 16;

    /**
     * Prevents instantiation.
     */
    private PackedRows() {
        throw new AssertionError();
    }

    /**
     * Unpacks the samples of one stored row.
     */
    @FunctionalInterface
    interface Row {
        /**
         * Unpacks one row.
         *
         * @param data the bytes
         * @param start the index of the row's first byte
         * @param end the index after the row's last readable byte
         * @param out the samples
         * @param target the index of the row's first sample
         * @param count the samples of a row
         */
        void unpack(byte[] data, int start, int end, short[] out, int target, int count);
    }

    /**
     * {@return a row unpacker of the given layout}
     *
     * @param layout the layout of a row's samples
     * @param bits the bits of each sample
     */
    static Row of(Layout layout, int bits) {
        return switch (layout) {
            case MSB_FIRST -> bits == 12 ? Unpack::msbFirst12
                    : (data, start, end, out, target, count) -> Unpack.msbFirst(data, start, end, bits, out, target, count);
            case LSB_FIRST -> (data, start, end, out, target, count) -> Unpack.lsbFirst(data, start, end, bits, out, target,
                    count);
            case LITTLE_WORDS -> (data, start, end, out, target, count) -> Unpack.words(data, start, end, true, bits, out,
                    target, count);
            case BIG_WORDS -> (data, start, end, out, target, count) -> Unpack.words(data, start, end, false, bits, out,
                    target, count);
            case MSB_FIRST_TEN_IN_SIXTEEN -> (data, start, end, out, target, count) -> tenInSixteen(data, start, end,
                    out, target, count, true);
            case LSB_FIRST_TEN_IN_SIXTEEN -> (data, start, end, out, target, count) -> tenInSixteen(data, start, end,
                    out, target, count, false);
        };
    }

    /**
     * A layout of the samples of a row.
     */
    enum Layout {
        /**
         * Samples most significant bit first, back to back.
         */
        MSB_FIRST,

        /**
         * Samples least significant bit first, back to back.
         */
        LSB_FIRST,

        /**
         * One sample in the low bits of each little-endian 16-bit word.
         */
        LITTLE_WORDS,

        /**
         * One sample in the low bits of each big-endian 16-bit word.
         */
        BIG_WORDS,

        /**
         * Ten 12-bit samples most significant bit first in the first 15 bytes of each 16-byte block.
         */
        MSB_FIRST_TEN_IN_SIXTEEN,

        /**
         * Ten 12-bit samples least significant bit first in the first 15 bytes of each 16-byte block.
         */
        LSB_FIRST_TEN_IN_SIXTEEN
    }

    /**
     * Unpacks a row of 16-byte blocks that each hold ten 12-bit samples followed by a byte of padding.
     *
     * @param data the bytes
     * @param start the index of the row's first byte
     * @param end the index after the row's last readable byte
     * @param out the samples
     * @param target the index of the row's first sample
     * @param count the samples of a row
     * @param msb whether the samples are stored most significant bit first
     */
    private static void tenInSixteen(byte[] data, int start, int end, short[] out, int target, int count,
                                     boolean msb) {
        for (var block = 0; block * 10 < count; block++) {
            var from = start + 16 * block;
            if (from >= end) {
                return;
            }
            var to = Math.min(end, from + 15);
            var samples = Math.min(10, count - 10 * block);
            if (msb) {
                Unpack.msbFirst(data, from, to, 12, out, target + 10 * block, samples);
            } else {
                Unpack.lsbFirst(data, from, to, 12, out, target + 10 * block, samples);
            }
        }
    }

    /**
     * Reads rows of sensor data into a plane.
     *
     * @param source the raw file
     * @param offset the absolute offset of the first row
     * @param rowBytes the bytes of one stored row
     * @param row the unpacker of one row
     * @param plane the plane to fill, one sample per pixel
     * @param limits the resource bounds
     */
    static void read(MediaSource source, long offset, int rowBytes, Row row, SensorPlane plane, MediaLimits limits) {
        var width = plane.width;
        var height = plane.height;
        var out = plane.integers;
        LimitChecks.checkAllocation(limits, Math.max(rowBytes, CHUNK));
        var bands = ParallelBands.bands(height, (long) width * height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            var chunkRows = Math.max(1, Math.min(last - first, CHUNK / rowBytes));
            var buffer = new byte[chunkRows * rowBytes];
            for (var y = first; y < last; y += chunkRows) {
                var rows = Math.min(chunkRows, last - y);
                var read = SourceBytes.readAvailable(source, offset + (long) y * rowBytes, buffer, 0, rows * rowBytes);
                for (var index = 0; index < rows; index++) {
                    var start = index * rowBytes;
                    if (start >= read) {
                        return;
                    }
                    row.unpack(buffer, start, Math.min(read, start + rowBytes), out, (y + index) * width, width);
                }
            }
        });
    }
}
