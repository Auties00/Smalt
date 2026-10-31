/* Ports OpenEXR 3.3.5 (src/lib/OpenEXRCore decoding and unpacking,
   ImfDeepCompositing.cpp). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Decodes the samples of one level of a part into one floating-point plane per channel.
 *
 * <p>A plane holds the channel's samples of the level, row after row: for a subsampled channel, one sample per
 * sampling period in each direction. Deep parts are flattened: each pixel's samples are sorted front to back by their
 * {@code Z} (then {@code ZBack}) and composited with the {@code A} channel, the {@code Z} planes keeping the nearest
 * depth.
 */
public final class ExrSamples {
    private ExrSamples() {
        throw new AssertionError();
    }

    /**
     * Holds the decoded planes of a level.
     *
     * @param width the level width
     * @param height the level height
     * @param widths the samples per row of each channel
     * @param heights the rows of each channel
     * @param planes the samples of each channel
     */
    public record Planes(int width, int height, int[] widths, int[] heights, float[][] planes) {
    }

    /**
     * Decodes one level of a part.
     *
     * @param file the parsed OpenEXR file
     * @param part the part index
     * @param levelX the horizontal level, 0 for a scanline part
     * @param levelY the vertical level, 0 for a scanline part
     * @param limits the resource bounds
     * @return the planes
     * @throws MediaException.Malformed if a chunk is invalid
     * @throws MediaException.LimitExceeded if the level exceeds the limits
     */
    public static Planes read(ExrFile file, int part, int levelX, int levelY, MediaLimits limits) {
        var header = file.parts().get(part);
        var width = header.levelSize(header.width(), levelX);
        var height = header.levelSize(header.height(), levelY);
        if (!header.tiled()) {
            width = header.width();
            height = header.height();
        }
        LimitChecks.checkPicture(limits, width, height);
        var channels = header.channels();
        var widths = new int[channels.size()];
        var heights = new int[channels.size()];
        var total = 0L;
        for (var index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            widths[index] = ExrHeader.samples(channel.xSampling(), header.xMin(), width);
            heights[index] = ExrHeader.samples(channel.ySampling(), header.yMin(), height);
            total += (long) widths[index] * heights[index];
        }
        LimitChecks.checkAllocation(limits, total * 4);
        var planes = new float[channels.size()][];
        for (var index = 0; index < channels.size(); index++) {
            planes[index] = new float[widths[index] * heights[index]];
        }
        var result = new Planes(width, height, widths, heights, planes);
        if (header.deep()) {
            readDeep(file, part, header, levelX, levelY, result, limits);
            return result;
        }
        var data = file.data();
        int chunks;
        int columns;
        if (header.tiled()) {
            columns = (int) ExrHeader.tiles(width, header.tileWidth());
            chunks = (int) (columns * ExrHeader.tiles(height, header.tileHeight()));
        } else {
            columns = 1;
            chunks = (int) ((height + (long) header.linesPerChunk() - 1) / header.linesPerChunk());
        }
        var finalWidth = width;
        var finalHeight = height;
        var bands = ParallelBands.bands(chunks, (long) width * height * channels.size());
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, chunks);
            var to = ParallelBands.start(band + 1, bands, chunks);
            for (var chunk = from; chunk < to; chunk++) {
                int x;
                int y;
                int blockWidth;
                int blockHeight;
                long index;
                int at;
                int size;
                if (header.tiled()) {
                    var tx = chunk % columns;
                    var ty = chunk / columns;
                    index = ExrFile.tileIndex(header, tx, ty, levelX, levelY);
                    at = file.chunk(part, index);
                    if (at + 20 > data.length || ExrHeader.i32(data, at) != tx || ExrHeader.i32(data, at + 4) != ty
                            || ExrHeader.i32(data, at + 8) != levelX || ExrHeader.i32(data, at + 12) != levelY) {
                        throw new MediaException.Malformed("the OpenEXR tile " + tx + "," + ty + " of level "
                                + levelX + "," + levelY + " has another header");
                    }
                    size = ExrHeader.i32(data, at + 16);
                    at += 20;
                    x = tx * header.tileWidth();
                    y = ty * header.tileHeight();
                    blockWidth = Math.min(header.tileWidth(), finalWidth - x);
                    blockHeight = Math.min(header.tileHeight(), finalHeight - y);
                } else {
                    at = file.chunk(part, chunk);
                    y = chunk * header.linesPerChunk();
                    if (at + 8 > data.length || ExrHeader.i32(data, at) != header.yMin() + y) {
                        throw new MediaException.Malformed("the OpenEXR chunk " + chunk + " starts at another row");
                    }
                    size = ExrHeader.i32(data, at + 4);
                    at += 8;
                    x = 0;
                    blockWidth = finalWidth;
                    blockHeight = Math.min(header.linesPerChunk(), finalHeight - y);
                }
                if (size < 0 || at + (long) size > data.length) {
                    throw new MediaException.Malformed("an OpenEXR chunk runs past the file");
                }
                var block = new ExrBlock(channels, header.xMin() + x, header.yMin() + y, blockWidth, blockHeight);
                var raw = ExrCodecs.decompress(header.compression(), data, at, size, block, limits);
                scatter(raw, block, header, x, y, result);
            }
        });
        return result;
    }

    /**
     * Copies the samples of an uncompressed chunk into the planes.
     *
     * @param raw the uncompressed chunk
     * @param block the chunk's pixels
     * @param header the part
     * @param x the left column of the chunk in the level
     * @param y the top row of the chunk in the level
     * @param result the planes
     */
    private static void scatter(byte[] raw, ExrBlock block, ExrHeader header, int x, int y, Planes result) {
        var channels = header.channels();
        var position = 0;
        var firstColumn = new int[channels.size()];
        var firstRow = new int[channels.size()];
        for (var index = 0; index < channels.size(); index++) {
            var channel = channels.get(index);
            firstColumn[index] = ExrHeader.samples(channel.xSampling(), header.xMin(), x);
            firstRow[index] = ExrHeader.samples(channel.ySampling(), header.yMin(), y);
        }
        var rowsDone = new int[channels.size()];
        for (var row = 0; row < block.height(); row++) {
            var absolute = block.y() + row;
            for (var index = 0; index < channels.size(); index++) {
                var count = block.channelWidth(index);
                if (!block.samples(index, absolute) || count == 0) {
                    continue;
                }
                var type = channels.get(index).pixelType();
                var plane = result.planes()[index];
                var target = (firstRow[index] + rowsDone[index]++) * result.widths()[index] + firstColumn[index];
                var bytes = type == 1 ? 2 : 4;
                if (position + (long) count * bytes > raw.length) {
                    throw new MediaException.Malformed("an OpenEXR chunk holds fewer samples than its rows");
                }
                for (var sample = 0; sample < count; sample++) {
                    plane[target + sample] = sample(raw, position, type);
                    position += bytes;
                }
            }
        }
    }

    /**
     * Converts one stored sample to a float.
     */
    private static float sample(byte[] raw, int position, int type) {
        return switch (type) {
            case 0 -> (float) (ExrHeader.i32(raw, position) & 0xFFFFFFFFL);
            case 1 -> Float.float16ToFloat((short) ((raw[position] & 0xFF) | (raw[position + 1] & 0xFF) << 8));
            default -> Float.intBitsToFloat(ExrHeader.i32(raw, position));
        };
    }

    /**
     * Decodes and flattens one level of a deep part.
     */
    private static void readDeep(ExrFile file, int part, ExrHeader header, int levelX, int levelY, Planes result,
                                 MediaLimits limits) {
        var data = file.data();
        var channels = header.channels();
        var width = result.width();
        var height = result.height();
        var compression = header.compression();
        if (compression > 3 && compression != 13) {
            throw new MediaException.Malformed("deep OpenEXR data with compression method " + compression);
        }
        int chunks;
        int columns;
        if (header.tiled()) {
            columns = (int) ExrHeader.tiles(width, header.tileWidth());
            chunks = (int) (columns * ExrHeader.tiles(height, header.tileHeight()));
        } else {
            columns = 1;
            chunks = (int) ((height + (long) header.linesPerChunk() - 1) / header.linesPerChunk());
        }
        var alpha = -1;
        var depth = -1;
        var depthBack = -1;
        for (var index = 0; index < channels.size(); index++) {
            switch (channels.get(index).name()) {
                case "A" -> alpha = index;
                case "Z" -> depth = index;
                case "ZBack" -> depthBack = index;
                default -> {
                }
            }
        }
        var budget = new long[]{0};
        for (var chunk = 0; chunk < chunks; chunk++) {
            int x;
            int y;
            int blockWidth;
            int blockHeight;
            int at;
            if (header.tiled()) {
                var tx = chunk % columns;
                var ty = chunk / columns;
                at = file.chunk(part, ExrFile.tileIndex(header, tx, ty, levelX, levelY));
                if (at + 16 > data.length || ExrHeader.i32(data, at) != tx || ExrHeader.i32(data, at + 4) != ty) {
                    throw new MediaException.Malformed("the OpenEXR deep tile " + tx + "," + ty
                            + " has another header");
                }
                at += 16;
                x = tx * header.tileWidth();
                y = ty * header.tileHeight();
                blockWidth = Math.min(header.tileWidth(), width - x);
                blockHeight = Math.min(header.tileHeight(), height - y);
            } else {
                at = file.chunk(part, chunk);
                y = chunk * header.linesPerChunk();
                if (at + 4 > data.length || ExrHeader.i32(data, at) != header.yMin() + y) {
                    throw new MediaException.Malformed("the OpenEXR deep chunk " + chunk + " starts at another row");
                }
                at += 4;
                x = 0;
                blockWidth = width;
                blockHeight = Math.min(header.linesPerChunk(), height - y);
            }
            if (at + 24 > data.length) {
                throw new MediaException.Malformed("the OpenEXR deep chunk header ends early");
            }
            var tableSize = ExrHeader.i64(data, at);
            var packedSize = ExrHeader.i64(data, at + 8);
            var unpackedSize = ExrHeader.i64(data, at + 16);
            at += 24;
            if (tableSize < 0 || packedSize < 0 || unpackedSize < 0 || at + tableSize + packedSize > data.length
                    || unpackedSize > Integer.MAX_VALUE) {
                throw new MediaException.Malformed("an OpenEXR deep chunk runs past the file");
            }
            var pixels = blockWidth * blockHeight;
            var countBlock = new ExrBlock(java.util.List.of(new ExrHeader.Channel("", 0, false, 1, 1)), 0, 0,
                    blockWidth, blockHeight);
            var counts = compression == 13 && tableSize != 4L * pixels
                    ? ExrZstd.sampleCounts(data, at, (int) tableSize, blockWidth, blockHeight)
                    : ExrCodecs.decompress(compression, data, at, (int) tableSize, countBlock, limits);
            // each row's counts run cumulatively from the start of the row
            var offsets = new int[pixels + 1];
            var rowStart = 0L;
            for (var row = 0; row < blockHeight; row++) {
                var previous = 0;
                for (var column = 0; column < blockWidth; column++) {
                    var index = row * blockWidth + column;
                    var cumulative = ExrHeader.i32(counts, 4 * index);
                    if (cumulative < previous) {
                        throw new MediaException.Malformed("the OpenEXR deep sample counts decrease");
                    }
                    previous = cumulative;
                    if (rowStart + cumulative > Integer.MAX_VALUE) {
                        throw new MediaException.LimitExceeded("an OpenEXR deep chunk of more than 2^31 samples");
                    }
                    offsets[index + 1] = (int) (rowStart + cumulative);
                }
                rowStart += previous;
            }
            var samples = offsets[pixels];
            var sampleBytes = 0L;
            for (var channel : channels) {
                sampleBytes += (long) channel.bytes() * samples;
            }
            if (sampleBytes != unpackedSize) {
                throw new MediaException.Malformed("the OpenEXR deep chunk declares " + unpackedSize + " sample bytes"
                        + " for " + samples + " samples");
            }
            budget[0] += sampleBytes;
            LimitChecks.checkAllocation(limits, budget[0]);
            byte[] raw;
            if (unpackedSize == packedSize) {
                raw = Arrays.copyOfRange(data, (int) (at + tableSize), (int) (at + tableSize + packedSize));
            } else if (compression == 13) {
                var rowSamples = new int[blockHeight];
                for (var row = 0; row < blockHeight; row++) {
                    rowSamples[row] = offsets[(row + 1) * blockWidth] - offsets[row * blockWidth];
                }
                var widths = new int[channels.size()];
                for (var index = 0; index < widths.length; index++) {
                    widths[index] = channels.get(index).bytes();
                }
                raw = ExrZstd.deep(data, (int) (at + tableSize), (int) packedSize, (int) unpackedSize, rowSamples,
                        widths);
            } else {
                raw = unpack(compression, data, (int) (at + tableSize), (int) packedSize, (int) unpackedSize);
            }
            // the samples are stored row by row, each row channel after channel
            var values = new float[channels.size()][samples];
            var position = 0;
            for (var row = 0; row < blockHeight; row++) {
                var first = offsets[row * blockWidth];
                var last = offsets[(row + 1) * blockWidth];
                for (var index = 0; index < channels.size(); index++) {
                    var type = channels.get(index).pixelType();
                    var bytes = type == 1 ? 2 : 4;
                    for (var sample = first; sample < last; sample++) {
                        values[index][sample] = sample(raw, position, type);
                        position += bytes;
                    }
                }
            }
            flatten(values, offsets, blockWidth, blockHeight, x, y, alpha, depth, depthBack, result);
        }
    }

    /**
     * Decompresses the sample data of a deep chunk.
     */
    private static byte[] unpack(int compression, byte[] data, int offset, int length, int size) {
        return switch (compression) {
            case 1 -> ExrCodecs.unpredict(ExrCodecs.rle(data, offset, length, size));
            case 2, 3 -> ExrCodecs.unpredict(ExrCodecs.inflate(data, offset, length, size, true));
            default -> throw new MediaException.Malformed("the OpenEXR deep sample data is compressed by method "
                    + compression);
        };
    }

    /**
     * Composites the samples of each pixel of a deep chunk front to back into the planes.
     */
    private static void flatten(float[][] values, int[] offsets, int width, int height, int x, int y, int alpha,
                                int depth, int depthBack, Planes result) {
        var channels = values.length;
        var order = new int[0];
        var scratch = new int[0];
        var sums = new float[channels];
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                var pixel = row * width + column;
                var first = offsets[pixel];
                var count = offsets[pixel + 1] - first;
                var target = (y + row) * result.widths()[0] + x + column;
                if (count == 0) {
                    continue;
                }
                if (order.length < count) {
                    order = new int[count];
                    scratch = new int[count];
                }
                for (var index = 0; index < count; index++) {
                    order[index] = first + index;
                }
                if (depth >= 0) {
                    sortByDepth(order, scratch, count, values[depth], depthBack >= 0 ? values[depthBack] : null);
                }
                var accumulatedAlpha = 0.0f;
                Arrays.fill(sums, 0);
                for (var index = 0; index < count; index++) {
                    var sample = order[index];
                    var weight = 1 - accumulatedAlpha;
                    for (var channel = 0; channel < channels; channel++) {
                        if (channel != depth && channel != depthBack) {
                            sums[channel] += weight * values[channel][sample];
                        }
                    }
                    accumulatedAlpha = alpha >= 0 ? sums[alpha] : 1;
                    if (accumulatedAlpha >= 1) {
                        break;
                    }
                }
                for (var channel = 0; channel < channels; channel++) {
                    if (channel == depth || channel == depthBack) {
                        result.planes()[channel][target] = values[channel][order[0]];
                    } else {
                        result.planes()[channel][target] = sums[channel];
                    }
                }
            }
        }
    }

    /**
     * Sorts sample indices front to back by depth, then by back depth, keeping the stored order of equal samples
     * (a bottom-up merge sort through a scratch array).
     *
     * @param order the sample indices, sorted in place
     * @param scratch an array at least as long as {@code count}
     * @param count the number of indices
     * @param front the depth of each sample
     * @param back the back depth of each sample, or {@code null}
     */
    private static void sortByDepth(int[] order, int[] scratch, int count, float[] front, float[] back) {
        var source = order;
        var target = scratch;
        for (var width = 1; width < count; width *= 2) {
            for (var low = 0; low < count; low += 2 * width) {
                var middle = Math.min(low + width, count);
                var high = Math.min(low + 2 * width, count);
                var left = low;
                var right = middle;
                for (var out = low; out < high; out++) {
                    if (left < middle
                            && (right >= high || compareDepth(source[left], source[right], front, back) <= 0)) {
                        target[out] = source[left++];
                    } else {
                        target[out] = source[right++];
                    }
                }
            }
            var swap = source;
            source = target;
            target = swap;
        }
        if (source != order) {
            System.arraycopy(source, 0, order, 0, count);
        }
    }

    /**
     * Compares two samples by depth, then by back depth.
     */
    private static int compareDepth(int a, int b, float[] front, float[] back) {
        var compare = Float.compare(front[a], front[b]);
        return compare != 0 || back == null ? compare : Float.compare(back[a], back[b]);
    }
}
