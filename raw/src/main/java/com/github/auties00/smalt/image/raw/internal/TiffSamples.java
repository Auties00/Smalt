package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.internal.JpegLsDecoder;
import com.github.auties00.smalt.image.jpeg.internal.LosslessJpeg;
import com.github.auties00.smalt.image.jpeg.internal.LosslessSamples;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.internal.Predictor;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.zip.DataFormatException;

/**
 * Reads the sensor samples a TIFF directory stores in strips or tiles, with the compressions and
 * predictors of TIFF 6.0 and DNG: none, lossless JPEG (7, also JPEG-LS codestreams in its place), Deflate (8),
 * lossy JPEG (34892) and JPEG
 * XL (52546, DNG 1.7, decoded by {@link JxlDecoder}), with the integer predictors (2, and DNG's
 * 34892 and 34893, which predict from 2 and 4 pixels back) on integer samples and the
 * floating-point predictors (3, and DNG's 34894 and 34895) on floating-point samples.
 *
 * <p>Integer samples of 1 to 32 bits are unpacked most significant bit first with every row of a
 * strip or tile starting on a byte boundary, except that 16 and 32-bit samples are in the file's
 * byte order; floating point samples of 16, 24 and 32 bits follow DNG. Samples are interleaved or,
 * with {@code PlanarConfiguration} 2, stored one plane after the other. The DNG layouts that
 * reorder the stored samples are undone: {@code SubTileBlockSize} (the pixels of each tile grouped
 * in blocks), {@code RowInterleaveFactor} and {@code ColumnInterleaveFactor} (the rows or columns
 * of the image stored field by field). Strips and tiles decode in parallel.
 */
final class TiffSamples {
    /**
     * The tag of the image width.
     */
    static final int IMAGE_WIDTH = 256;

    /**
     * The tag of the image height.
     */
    static final int IMAGE_LENGTH = 257;

    /**
     * The tag of the bits per sample.
     */
    static final int BITS_PER_SAMPLE = 258;

    /**
     * The tag of the compression.
     */
    static final int COMPRESSION = 259;

    /**
     * The tag of the photometric interpretation.
     */
    static final int PHOTOMETRIC = 262;

    /**
     * The tag of the strip offsets.
     */
    static final int STRIP_OFFSETS = 273;

    /**
     * The tag of the samples per pixel.
     */
    static final int SAMPLES_PER_PIXEL = 277;

    /**
     * The tag of the rows per strip.
     */
    static final int ROWS_PER_STRIP = 278;

    /**
     * The tag of the strip byte counts.
     */
    static final int STRIP_BYTE_COUNTS = 279;

    /**
     * The tag of the planar configuration.
     */
    static final int PLANAR_CONFIGURATION = 284;

    /**
     * The tag of the predictor.
     */
    static final int PREDICTOR = 317;

    /**
     * The tag of the tile width.
     */
    static final int TILE_WIDTH = 322;

    /**
     * The tag of the tile height.
     */
    static final int TILE_LENGTH = 323;

    /**
     * The tag of the tile offsets.
     */
    static final int TILE_OFFSETS = 324;

    /**
     * The tag of the tile byte counts.
     */
    static final int TILE_BYTE_COUNTS = 325;

    /**
     * The tag of the sample format.
     */
    static final int SAMPLE_FORMAT = 339;

    /**
     * The tag of the size of the blocks the pixels of a tile are grouped in (DNG 1.2).
     */
    static final int SUB_TILE_BLOCK_SIZE = 50974;

    /**
     * The tag of the number of fields the rows of the image are stored in (DNG 1.2).
     */
    static final int ROW_INTERLEAVE_FACTOR = 50975;

    /**
     * The tag of the number of fields the columns of the image are stored in (DNG 1.7.1).
     */
    static final int COLUMN_INTERLEAVE_FACTOR = 52547;

    /**
     * The JPEG XL compression of DNG 1.7.
     */
    static final int COMPRESSION_JPEG_XL = 52546;

    /**
     * The lossy JPEG compression of DNG 1.4.
     */
    static final int COMPRESSION_LOSSY_JPEG = 34892;

    /**
     * The most strips or tiles a directory may declare.
     */
    private static final int MAX_BLOCKS = 1 << 20;

    /**
     * Prevents instantiation.
     */
    private TiffSamples() {
        throw new AssertionError();
    }

    /**
     * One strip or tile.
     *
     * @param x the first column
     * @param y the first row
     * @param width the stored width, which may extend past the image for tiles
     * @param height the stored height
     * @param offset the absolute offset of its data
     * @param length the length of its data
     * @param sample the first sample of each pixel the block holds: 0, or the plane of a block of
     * separately stored planes
     * @param samples the samples of each pixel the block holds
     */
    record Block(int x, int y, int width, int height, long offset, long length, int sample, int samples) {
    }

    /**
     * The stored layout of a directory's samples, as read from its tags.
     *
     * @param compression the TIFF {@code Compression} code
     * @param predictor the TIFF {@code Predictor} code
     * @param bits the bits per sample
     * @param floating whether samples are floating-point
     * @param subTileRows the rows of a sub-tile block, 1 without one
     * @param subTileColumns the columns of a sub-tile block, 1 without one
     * @param rowFactor the number of fields the rows are stored in, 1 when not interleaved
     * @param columnFactor the number of fields the columns are stored in, 1 when not interleaved
     */
    private record Layout(int compression, int predictor, int bits, boolean floating, int subTileRows,
                          int subTileColumns, int rowFactor, int columnFactor) {
        /**
         * {@return whether the pixels of a block are grouped in sub-tile blocks}
         */
        boolean subTiled() {
            return subTileRows != 1 || subTileColumns != 1;
        }
    }

    /**
     * Describes the image of a directory and returns the reader of its samples.
     *
     * @param ifd the directory
     * @param sensor the sensor description, whose size, sample count, bit depth and floating flag
     * are set from the directory
     * @return the reader
     * @throws MediaException.Malformed if the layout is corrupt
     * @throws MediaException.Unsupported if the compression or layout is not supported
     */
    static Sensor.Reader describe(Ifd ifd, Sensor sensor) {
        var width = ifd.intValue(IMAGE_WIDTH, 0);
        var height = ifd.intValue(IMAGE_LENGTH, 0);
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("raw image of " + width + "x" + height);
        }
        var samples = ifd.intValue(SAMPLES_PER_PIXEL, 1);
        if (samples < 1 || samples > 4) {
            throw new MediaException.Unsupported("raw", samples + " samples per pixel are not supported");
        }
        var bits = ifd.intValue(BITS_PER_SAMPLE, 1);
        var format = ifd.intValue(SAMPLE_FORMAT, 1);
        var floating = format == 3;
        if (floating ? bits != 16 && bits != 24 && bits != 32 : bits < 1 || bits > 32) {
            throw new MediaException.Unsupported("raw", bits + "-bit " + (floating ? "floating-point" : "integer")
                    + " sensor samples are not supported");
        }
        var planarConfiguration = samples > 1 ? ifd.intValue(PLANAR_CONFIGURATION, 1) : 1;
        if (planarConfiguration != 1 && planarConfiguration != 2) {
            throw new MediaException.Malformed("planar configuration " + planarConfiguration);
        }
        var planar = planarConfiguration == 2;
        var compression = ifd.intValue(COMPRESSION, 1);
        var predictor = ifd.intValue(PREDICTOR, 1);
        sensor.width = width;
        sensor.height = height;
        sensor.samplesPerPixel = samples;
        sensor.bitsPerSample = bits;
        sensor.floating = floating;
        sensor.compression = switch (compression) {
            case 1 -> "uncompressed";
            case 7 -> "lossless JPEG";
            case 8 -> "deflate";
            case COMPRESSION_LOSSY_JPEG -> "lossy JPEG";
            case COMPRESSION_JPEG_XL -> "JPEG XL";
            default -> "compression " + compression;
        };
        var blockSamples = planar ? 1 : samples;
        if (compression == COMPRESSION_JPEG_XL && ((floating ? bits != 16 : bits < 8 || bits > 16)
                || blockSamples == 2 || blockSamples == 4)) {
            throw new MediaException.Unsupported("raw", "JPEG XL sensor data of " + samples + " " + bits + "-bit "
                    + (floating ? "floating-point" : "integer") + " samples is not supported");
        }
        if (compression != 1 && compression != 7 && compression != 8 && compression != COMPRESSION_LOSSY_JPEG
                && compression != COMPRESSION_JPEG_XL) {
            throw new MediaException.Unsupported("raw", "TIFF compression " + compression
                    + " is not supported for sensor data");
        }
        if (compression == 7 && (floating || bits > 16)) {
            throw new MediaException.Unsupported("raw", "lossless JPEG sensor data of " + bits + "-bit "
                    + (floating ? "floating-point" : "integer") + " samples is not supported");
        }
        if (predictor != 1 && predictor != 2 && predictor != 3 && (predictor < 34892 || predictor > 34895)) {
            throw new MediaException.Unsupported("raw", "TIFF predictor " + predictor + " is not supported");
        }
        if (predictor != 1 && floatingPredictor(predictor) != floating) {
            throw new MediaException.Unsupported("raw", "TIFF predictor " + predictor + " on "
                    + (floating ? "floating-point" : "integer") + " sensor samples is not supported");
        }
        if (compression == COMPRESSION_LOSSY_JPEG) {
            sensor.bitsPerSample = 8;
        }
        sensor.wide = !floating && sensor.bitsPerSample > 16;
        var subTile = ifd.longs(SUB_TILE_BLOCK_SIZE);
        var subTileRows = 1;
        var subTileColumns = 1;
        if (subTile != null && subTile.length >= 2) {
            if (subTile[0] < 1 || subTile[1] < 1 || subTile[0] > height || subTile[1] > width) {
                throw new MediaException.Malformed("sub-tile blocks of " + subTile[0] + "x" + subTile[1]);
            }
            subTileRows = (int) subTile[0];
            subTileColumns = (int) subTile[1];
        }
        var rowFactor = interleave(ifd, ROW_INTERLEAVE_FACTOR, height);
        var columnFactor = interleave(ifd, COLUMN_INTERLEAVE_FACTOR, width);
        var layout = new Layout(compression, predictor, bits, floating, subTileRows, subTileColumns, rowFactor,
                columnFactor);
        var blocks = blocks(ifd, width, height, planar ? samples : 1);
        if (layout.subTiled()) {
            for (var block : blocks) {
                if (block.width % subTileColumns != 0 || block.height % subTileRows != 0) {
                    throw new MediaException.Malformed("a block of " + block.width + "x" + block.height
                            + " is not a whole number of " + subTileRows + "x" + subTileColumns + " sub-tile blocks");
                }
            }
        }
        var little = ifd.reader().little();
        var source = ifd.reader().source();
        return (plane, limits) -> {
            read(source, little, blocks, layout, plane, limits);
            if (rowFactor > 1 || columnFactor > 1) {
                deinterleave(plane, rowFactor, columnFactor, limits);
            }
        };
    }

    /**
     * Reads an interleave factor.
     *
     * @param ifd the directory
     * @param tag the tag of the factor
     * @param size the image's extent in the interleaved direction
     * @return the number of fields, 1 when the tag is absent
     * @throws MediaException.Malformed if the factor is 0 or exceeds the image
     */
    private static int interleave(Ifd ifd, int tag, int size) {
        var factor = ifd.longValue(tag, 1);
        if (factor < 1 || factor > size) {
            throw new MediaException.Malformed("interleave factor " + factor + " for " + size + " lines");
        }
        return (int) factor;
    }

    /**
     * Lists the strips or tiles of a directory whose samples are interleaved.
     *
     * @param ifd the directory
     * @param width the image width
     * @param height the image height
     * @return the blocks
     */
    static Block[] blocks(Ifd ifd, int width, int height) {
        return blocks(ifd, width, height, 1);
    }

    /**
     * Lists the strips or tiles of a directory.
     *
     * @param ifd the directory
     * @param width the image width
     * @param height the image height
     * @param planes the number of separately stored planes: 1 for interleaved samples, the samples
     * per pixel for {@code PlanarConfiguration} 2
     * @return the blocks, those of each plane after the previous plane's
     */
    static Block[] blocks(Ifd ifd, int width, int height, int planes) {
        var tiled = ifd.has(TILE_OFFSETS);
        var offsets = ifd.longs(tiled ? TILE_OFFSETS : STRIP_OFFSETS);
        var counts = ifd.longs(tiled ? TILE_BYTE_COUNTS : STRIP_BYTE_COUNTS);
        if (offsets == null || offsets.length == 0) {
            throw new MediaException.Malformed("raw image without data offsets");
        }
        if (counts == null || counts.length < offsets.length) {
            throw new MediaException.Malformed("raw image without data byte counts");
        }
        if (offsets.length > MAX_BLOCKS) {
            throw new MediaException.LimitExceeded("raw image of " + offsets.length + " blocks");
        }
        int blockWidth;
        int blockHeight;
        if (tiled) {
            blockWidth = ifd.intValue(TILE_WIDTH, 0);
            blockHeight = ifd.intValue(TILE_LENGTH, 0);
        } else {
            blockWidth = width;
            blockHeight = (int) Math.min(ifd.longValue(ROWS_PER_STRIP, height), height);
        }
        if (blockWidth <= 0 || blockHeight <= 0) {
            throw new MediaException.Malformed("raw image blocks of " + blockWidth + "x" + blockHeight);
        }
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("raw image of " + width + "x" + height);
        }
        var blocksAcross = (width + (long) blockWidth - 1) / blockWidth;
        var blocksDown = (height + (long) blockHeight - 1) / blockHeight;
        if (blocksAcross * blocksDown * planes > offsets.length) {
            throw new MediaException.Malformed("raw image declares " + offsets.length + " blocks for "
                    + blocksAcross * blocksDown * planes);
        }
        var across = (int) blocksAcross;
        var perPlane = across * (int) blocksDown;
        var base = ifd.reader().base();
        var size = ifd.reader().size();
        var blocks = new Block[perPlane * planes];
        for (var index = 0; index < blocks.length; index++) {
            var inPlane = index % perPlane;
            var x = (inPlane % across) * blockWidth;
            var y = (inPlane / across) * blockHeight;
            var offset = base + offsets[index];
            var length = counts[index];
            if (offset < 0 || length < 0 || (size >= 0 && offset > size)) {
                throw new MediaException.Malformed("raw image block " + index + " lies outside the file");
            }
            if (size >= 0) {
                length = Math.min(length, size - offset);
            }
            var rows = tiled ? blockHeight : Math.min(blockHeight, height - y);
            blocks[index] = planes == 1 ? new Block(x, y, blockWidth, rows, offset, length, 0, -1)
                    : new Block(x, y, blockWidth, rows, offset, length, index / perPlane, 1);
        }
        return blocks;
    }

    /**
     * Reads every block into the plane.
     *
     * @param source the raw file the blocks lie in
     * @param little whether multi-byte samples are little endian
     * @param blocks the strips or tiles, in raster order
     * @param layout the stored layout
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void read(MediaSource source, boolean little, Block[] blocks, Layout layout, SensorPlane plane,
                             MediaLimits limits) {
        var bands = ParallelBands.bands(blocks.length, (long) plane.width * plane.height * plane.samplesPerPixel);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, blocks.length);
            var last = ParallelBands.start(band + 1, bands, blocks.length);
            for (var index = first; index < last; index++) {
                var block = blocks[index];
                if (block.samples < 0) {
                    block = new Block(block.x, block.y, block.width, block.height, block.offset, block.length, 0,
                            plane.samplesPerPixel);
                }
                if (layout.subTiled()) {
                    readSubTiled(source, little, block, layout, plane, limits);
                } else {
                    readBlock(source, little, block, layout, plane, limits);
                }
            }
        });
    }

    /**
     * Reads one block whose pixels are grouped in sub-tile blocks: decodes it as stored, then moves
     * each pixel to its place.
     *
     * @param source the raw file the block lies in
     * @param little whether multi-byte samples are little endian
     * @param block the strip or tile
     * @param layout the stored layout
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void readSubTiled(MediaSource source, boolean little, Block block, Layout layout, SensorPlane plane,
                                     MediaLimits limits) {
        var samples = block.samples;
        var stored = new SensorPlane(block.width, block.height, samples, plane.floating(), plane.wide != null, limits);
        readBlock(source, little, new Block(0, 0, block.width, block.height, block.offset, block.length, 0, samples),
                layout, stored, limits);
        var blockRows = layout.subTileRows;
        var blockColumns = layout.subTileColumns;
        var perBlock = blockRows * blockColumns;
        var blocksAcross = block.width / blockColumns;
        var rows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x);
        var stride = plane.samplesPerPixel;
        var pixels = block.width * block.height;
        for (var pixel = 0; pixel < pixels; pixel++) {
            var group = pixel / perBlock;
            var within = pixel % perBlock;
            var y = (group / blocksAcross) * blockRows + within / blockColumns;
            var x = (group % blocksAcross) * blockColumns + within % blockColumns;
            if (y >= rows || x >= columns) {
                continue;
            }
            var from = pixel * samples;
            var to = ((block.y + y) * plane.width + block.x + x) * stride + block.sample;
            for (var sample = 0; sample < samples; sample++) {
                copy(stored, from + sample, plane, to + sample);
            }
        }
    }

    /**
     * Copies one sample between planes of the same storage.
     *
     * @param from the source plane
     * @param fromIndex the source index
     * @param to the target plane
     * @param toIndex the target index
     */
    private static void copy(SensorPlane from, int fromIndex, SensorPlane to, int toIndex) {
        if (from.integers != null) {
            to.integers[toIndex] = from.integers[fromIndex];
        } else if (from.wide != null) {
            to.wide[toIndex] = from.wide[fromIndex];
        } else {
            to.floats[toIndex] = from.floats[fromIndex];
        }
    }

    /**
     * Reads one block into the plane.
     *
     * @param source the raw file the block lies in
     * @param little whether multi-byte samples are little endian
     * @param block the strip or tile, with its offset, length, position in the plane and samples
     * @param layout the stored layout
     * @param plane the plane to fill
     * @param limits the resource bounds
     */
    private static void readBlock(MediaSource source, boolean little, Block block, Layout layout, SensorPlane plane,
                                  MediaLimits limits) {
        LimitChecks.checkAllocation(limits, block.length);
        if (block.length > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("raw image block of " + block.length + " bytes");
        }
        var data = new byte[(int) block.length];
        source.readFully(block.offset, data, 0, data.length);
        var bits = layout.bits;
        var floating = layout.floating;
        var predictor = layout.predictor;
        switch (layout.compression) {
            case 7 -> losslessJpeg(data, block, plane, limits);
            case COMPRESSION_LOSSY_JPEG -> lossyJpeg(data, block, plane, limits);
            case COMPRESSION_JPEG_XL -> jpegXl(data, block, plane, bits, floating, limits);
            default -> {
                var spp = block.samples;
                var bytesPerSample = floating ? bits / 8 : 0;
                var rowBytes = floating ? (long) block.width * spp * bytesPerSample
                        : ((long) block.width * spp * bits + 7) / 8;
                var expected = rowBytes * block.height;
                if (expected > Integer.MAX_VALUE - 16) {
                    throw new MediaException.LimitExceeded("raw image block of " + expected + " bytes");
                }
                if (layout.compression == 8) {
                    LimitChecks.checkAllocation(limits, expected);
                    data = inflate(data, (int) expected);
                }
                if (floating) {
                    if (predictor != 1) {
                        var rows = (int) Math.min(block.height, data.length / rowBytes);
                        Predictor.undoFloatingPoint(data, 0, (int) rowBytes, rows, spp * pixelsBack(predictor),
                                block.width * spp, bytesPerSample, new byte[(int) rowBytes]);
                        floats(data, block, plane, bytesPerSample, false);
                    } else {
                        floats(data, block, plane, bytesPerSample, little);
                    }
                    return;
                }
                integers(data, block, plane, bits, little, (int) rowBytes);
                if (predictor != 1) {
                    undoDifferences(block, plane, spp * pixelsBack(predictor), bits);
                }
            }
        }
    }

    /**
     * {@return whether a predictor code is a floating-point predictor (3, 34894 or 34895)}
     *
     * @param predictor a supported predictor code other than 1
     */
    private static boolean floatingPredictor(int predictor) {
        return predictor == 3 || predictor == 34894 || predictor == 34895;
    }

    /**
     * {@return the pixels between a sample and the one a predictor predicts it from: 2 for 34892
     * and 34894, 4 for 34893 and 34895, 1 otherwise}
     *
     * @param predictor a supported predictor code other than 1
     */
    private static int pixelsBack(int predictor) {
        return switch (predictor) {
            case 34892, 34894 -> 2;
            case 34893, 34895 -> 4;
            default -> 1;
        };
    }

    /**
     * Inflates a Deflate block.
     *
     * @param data the zlib stream
     * @param expected the decompressed size
     * @return the decompressed bytes, zero-filled past the end of a short stream
     * @throws MediaException.Malformed if the stream is corrupt
     */
    static byte[] inflate(byte[] data, int expected) {
        var output = new byte[expected];
        var inflater = new ZlibInflater();
        inflater.setInput(data, 0, data.length);
        try {
            var done = 0;
            while (done < expected) {
                var produced = inflater.inflate(output, done, expected - done);
                if (produced == 0) {
                    break;
                }
                done += produced;
            }
        } catch (DataFormatException exception) {
            throw new MediaException.Malformed("corrupt deflate raw data", exception);
        }
        return output;
    }

    /**
     * Unpacks integer samples into the plane.
     *
     * @param data the bytes
     * @param block the strip or tile the samples belong to
     * @param plane the plane receiving the samples
     * @param bits the bits per sample
     * @param little whether 16 and 32-bit samples are little endian
     * @param rowBytes the bytes of one stored row
     */
    private static void integers(byte[] data, Block block, SensorPlane plane, int bits, boolean little, int rowBytes) {
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        var rows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x);
        var count = columns * spp;
        var interleaved = spp == stride;
        var row16 = interleaved ? null : new short[count];
        var rowWide = interleaved ? null : new int[count];
        for (var row = 0; row < rows; row++) {
            var start = (long) row * rowBytes;
            var target = ((block.y + row) * plane.width + block.x) * stride + block.sample;
            if (start >= data.length) {
                return;
            }
            var at = (int) start;
            if (plane.wide != null) {
                var out = interleaved ? plane.wide : rowWide;
                var to = interleaved ? target : 0;
                if (bits == 32) {
                    var available = Math.min(count, (data.length - at) / 4);
                    for (var index = 0; index < available; index++) {
                        var p = at + 4 * index;
                        var a = data[p] & 0xFF;
                        var b = data[p + 1] & 0xFF;
                        var c = data[p + 2] & 0xFF;
                        var d = data[p + 3] & 0xFF;
                        out[to + index] = little ? a | (b << 8) | (c << 16) | (d << 24) : (a << 24) | (b << 16) | (c << 8) | d;
                    }
                } else {
                    Unpack.msbFirstWide(data, at, data.length, bits, out, to, count);
                }
                if (!interleaved) {
                    for (var index = 0; index < count; index++) {
                        plane.wide[target + index * stride] = rowWide[index];
                    }
                }
                continue;
            }
            var out = interleaved ? plane.integers : row16;
            var to = interleaved ? target : 0;
            if (bits == 16) {
                var available = Math.min(count, (data.length - at) / 2);
                for (var index = 0; index < available; index++) {
                    var a = data[at + 2 * index] & 0xFF;
                    var b = data[at + 2 * index + 1] & 0xFF;
                    out[to + index] = (short) (little ? a | (b << 8) : (a << 8) | b);
                }
            } else if (bits == 8) {
                var available = Math.min(count, data.length - at);
                for (var index = 0; index < available; index++) {
                    out[to + index] = (short) (data[at + index] & 0xFF);
                }
            } else {
                Unpack.msbFirst(data, at, data.length, bits, out, to, count);
            }
            if (!interleaved) {
                for (var index = 0; index < count; index++) {
                    plane.integers[target + index * stride] = row16[index];
                }
            }
        }
    }

    /**
     * Undoes horizontal differencing over the block's rows, modulo the sample range.
     *
     * @param block the strip or tile whose rows are undone
     * @param plane the plane holding the block's differences
     * @param distance the distance, in the block's samples, between a sample and the one it is
     * predicted from
     * @param bits the bits per sample
     */
    private static void undoDifferences(Block block, SensorPlane plane, int distance, int bits) {
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        var rows = Math.min(block.height, plane.height - block.y);
        var count = Math.min(block.width, plane.width - block.x) * spp;
        // a block of one plane of several holds every stride-th sample of the plane
        var step = spp == stride ? 1 : stride;
        var mask = bits >= 32 ? 0xFFFFFFFFL : (1L << bits) - 1;
        for (var row = 0; row < rows; row++) {
            var start = ((block.y + row) * plane.width + block.x) * stride + block.sample;
            if (plane.wide != null) {
                var out = plane.wide;
                for (var index = distance; index < count; index++) {
                    var at = start + index * step;
                    out[at] = (int) ((out[at] + (long) out[at - distance * step]) & mask);
                }
            } else {
                var out = plane.integers;
                for (var index = distance; index < count; index++) {
                    var at = start + index * step;
                    out[at] = (short) (out[at] + out[at - distance * step]);
                }
            }
        }
    }

    /**
     * Converts floating-point samples into the plane.
     *
     * @param data the bytes
     * @param block the strip or tile the samples belong to
     * @param plane the plane receiving the samples
     * @param bytesPerSample 2, 3 or 4
     * @param little whether the samples are little endian
     */
    private static void floats(byte[] data, Block block, SensorPlane plane, int bytesPerSample, boolean little) {
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        var rows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x);
        var rowBytes = block.width * spp * bytesPerSample;
        var step = spp == stride ? 1 : stride;
        var out = plane.floats;
        for (var row = 0; row < rows; row++) {
            var target = ((block.y + row) * plane.width + block.x) * stride + block.sample;
            var at = row * rowBytes;
            for (var index = 0; index < columns * spp; index++) {
                var position = at + index * bytesPerSample;
                if (position + bytesPerSample > data.length) {
                    return;
                }
                var bits = 0;
                for (var b = 0; b < bytesPerSample; b++) {
                    var value = data[position + (little ? bytesPerSample - 1 - b : b)] & 0xFF;
                    bits = (bits << 8) | value;
                }
                out[target + index * step] = switch (bytesPerSample) {
                    case 2 -> Float.float16ToFloat((short) bits);
                    case 3 -> float24(bits);
                    default -> Float.intBitsToFloat(bits);
                };
            }
        }
    }

    /**
     * Converts a DNG 24-bit float (1 sign bit, 7 exponent bits biased by 64, 16 mantissa bits).
     *
     * @param bits the 24 bits
     * @return the value
     */
    static float float24(int bits) {
        var sign = (bits >>> 23) & 1;
        var exponent = (bits >>> 16) & 0x7F;
        var mantissa = bits & 0xFFFF;
        if (exponent == 0 && mantissa == 0) {
            return sign != 0 ? -0f : 0f;
        }
        if (exponent == 0x7F) {
            return mantissa != 0 ? Float.NaN : sign != 0 ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
        }
        if (exponent == 0) {
            var value = mantissa / 65536.0f * (float) StrictMath.pow(2, -62);
            return sign != 0 ? -value : value;
        }
        return Float.intBitsToFloat((sign << 31) | ((exponent - 64 + 127) << 23) | (mantissa << 7));
    }

    /**
     * Decodes a lossless JPEG block, laying its samples out in the block's raster order.
     *
     * <p>A block holding a JPEG-LS codestream ({@code SOF55}) instead, as DICOM allows where it allows lossless
     * JPEG, decodes through the JPEG-LS decoder the same way.
     *
     * @param data the block bytes
     * @param block the strip or tile the samples belong to
     * @param plane the plane receiving the samples, of at most 16-bit integers
     * @param limits the resource bounds, which the frame size the JPEG declares must respect
     */
    private static void losslessJpeg(byte[] data, Block block, SensorPlane plane, MediaLimits limits) {
        LosslessSamples jpeg;
        int width;
        int height;
        int components;
        if (JpegLsDecoder.isJpegLs(data, 0, data.length)) {
            var jpegLs = new JpegLsDecoder(data, 0, data.length, limits);
            if (!jpegLs.fullResolution()) {
                throw new MediaException.Unsupported("dng jpeg-ls subsampled",
                        "JPEG-LS raw blocks of subsampled components are not supported");
            }
            jpeg = jpegLs;
            width = jpegLs.width();
            height = jpegLs.height();
            components = jpegLs.outputComponents();
        } else {
            var lossless = new LosslessJpeg(data, 0, data.length);
            jpeg = lossless;
            width = lossless.width();
            height = lossless.height();
            components = lossless.components();
        }
        // the decoder runs over every sample the frame header declares, so a huge declared frame costs time
        // although the block keeps only its own samples
        LimitChecks.checkPicture(limits, width, height);
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        var blockRow = (long) block.width * spp;
        var rowSamples = (long) width * components;
        if (rowSamples * height < blockRow * block.height) {
            throw new MediaException.Malformed("lossless JPEG of " + width + "x" + height + "x"
                    + components + " is smaller than its " + block.width + "x" + block.height + " block");
        }
        var out = plane.integers;
        var limitRows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x) * spp;
        var interleaved = spp == stride;
        jpeg.decode((row, samples) -> {
            var first = row * rowSamples;
            for (var index = 0; index < rowSamples; ) {
                var position = first + index;
                var y = (int) (position / blockRow);
                var x = (int) (position % blockRow);
                if (y >= limitRows) {
                    return;
                }
                var run = (int) Math.min(rowSamples - index, blockRow - x);
                var copy = Math.min(run, columns - x);
                if (copy > 0) {
                    var target = ((block.y + y) * plane.width + block.x) * stride + block.sample;
                    if (interleaved) {
                        for (var k = 0; k < copy; k++) {
                            out[target + x + k] = (short) samples[index + k];
                        }
                    } else {
                        for (var k = 0; k < copy; k++) {
                            out[target + (x + k) * stride] = (short) samples[index + k];
                        }
                    }
                }
                index += run;
            }
        });
    }

    /**
     * Decodes a JPEG XL block (DNG 1.7, a bare codestream or a container) into the plane.
     *
     * <p>Each channel of the image fills one sample of the plane's pixels. The decoder gives
     * integer images as nominal values from 0 to 1, which are scaled back to the {@code bits}-bit
     * range and rounded; floating-point images keep their values.
     *
     * @param data the block
     * @param block the block's place in the plane
     * @param plane the plane to fill
     * @param bits the bits per sample
     * @param floating whether the samples are floating-point
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the block is not a valid JPEG XL image or its channel
     * count differs from the block's samples per pixel
     */
    private static void jpegXl(byte[] data, Block block, SensorPlane plane, int bits, boolean floating, MediaLimits limits) {
        var image = JxlDecoder.decodeFloat(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits).build());
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        if (image.planes().length < spp) {
            throw new MediaException.Malformed("JPEG XL raw block of " + image.planes().length + " channels for "
                    + spp + " samples per pixel");
        }
        var rows = Math.min(Math.min(block.height, image.height()), plane.height - block.y);
        var columns = Math.min(Math.min(block.width, image.width()), plane.width - block.x);
        var maximum = (float) ((1 << bits) - 1);
        for (var sample = 0; sample < spp; sample++) {
            var channel = image.planes()[sample];
            for (var row = 0; row < rows; row++) {
                var source = row * image.width();
                var target = ((block.y + row) * plane.width + block.x) * stride + block.sample + sample;
                if (floating) {
                    for (var column = 0; column < columns; column++) {
                        plane.floats[target + column * stride] = channel[source + column];
                    }
                } else {
                    for (var column = 0; column < columns; column++) {
                        var value = Math.round(channel[source + column] * maximum);
                        plane.integers[target + column * stride] = (short) Math.clamp(value, 0, (1 << bits) - 1);
                    }
                }
            }
        }
    }

    /**
     * Decodes a lossy JPEG block (DNG 1.4) through the baseline JPEG decoder; each pixel gets the
     * 8-bit samples the decoder returns.
     *
     * @param data the block bytes
     * @param block the strip or tile the samples belong to
     * @param plane the plane receiving the samples
     * @param limits the resource bounds
     */
    private static void lossyJpeg(byte[] data, Block block, SensorPlane plane, MediaLimits limits) {
        var decoded = JpegDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits).build());
        var picture = ImageOps.toRgba(decoded.picture());
        var stride = plane.samplesPerPixel;
        var spp = block.samples;
        var rows = Math.min(Math.min(block.height, picture.height()), plane.height - block.y);
        var columns = Math.min(Math.min(block.width, picture.width()), plane.width - block.x);
        var pixels = picture.pixels();
        var out = plane.integers;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var source = row * picture.stride() + column * 4;
                var target = ((block.y + row) * plane.width + block.x + column) * stride + block.sample;
                for (var sample = 0; sample < spp; sample++) {
                    out[target + sample] = (short) (pixels[source + Math.min(sample, 2)] & 0xFF);
                }
            }
        }
    }

    /**
     * Moves the rows and columns of a plane stored field by field to their places: with {@code n}
     * fields, the first field holds rows (or columns) 0, {@code n}, {@code 2n} and so on, the
     * second rows 1, {@code n + 1}, and each field follows the previous one.
     *
     * @param plane the plane, reordered in place
     * @param rowFactor the number of row fields
     * @param columnFactor the number of column fields
     * @param limits the resource bounds, which the working copy must respect
     */
    static void deinterleave(SensorPlane plane, int rowFactor, int columnFactor, MediaLimits limits) {
        var width = plane.width;
        var height = plane.height;
        var spp = plane.samplesPerPixel;
        var rowOf = fieldOrder(height, rowFactor);
        var columnOf = columnFactor > 1 ? fieldOrder(width, columnFactor) : null;
        var length = plane.length();
        LimitChecks.checkAllocation(limits, (long) length * (plane.integers != null ? 2 : 4));
        Object samples = plane.integers != null ? plane.integers : plane.wide != null ? plane.wide : plane.floats;
        var stored = plane.integers != null ? (Object) plane.integers.clone()
                : plane.wide != null ? plane.wide.clone() : plane.floats.clone();
        var bands = ParallelBands.bands(height, length);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            switch (samples) {
                case short[] out -> permute((short[]) stored, out, first, last, width, spp, rowOf, columnOf);
                case int[] out -> permute((int[]) stored, out, first, last, width, spp, rowOf, columnOf);
                default -> permute((float[]) stored, (float[]) samples, first, last, width, spp, rowOf, columnOf);
            }
        });
    }

    /**
     * Moves the stored rows of a range from their stored places to their places in the image.
     *
     * @param stored the samples in stored order
     * @param out the samples in image order
     * @param first the first stored row
     * @param last the stored row after the last
     * @param width the columns
     * @param spp the samples of each pixel
     * @param rowOf the image row of each stored row
     * @param columnOf the image column of each stored column, or {@code null} when the columns are in order
     */
    private static void permute(short[] stored, short[] out, int first, int last, int width, int spp, int[] rowOf,
                                int[] columnOf) {
        var stride = width * spp;
        for (var row = first; row < last; row++) {
            var from = row * stride;
            var to = rowOf[row] * stride;
            if (columnOf == null) {
                System.arraycopy(stored, from, out, to, stride);
                continue;
            }
            for (var column = 0; column < width; column++) {
                var source = from + column * spp;
                var target = to + columnOf[column] * spp;
                for (var sample = 0; sample < spp; sample++) {
                    out[target + sample] = stored[source + sample];
                }
            }
        }
    }

    /**
     * Moves the stored rows of a range from their stored places to their places in the image.
     *
     * @param stored the samples in stored order
     * @param out the samples in image order
     * @param first the first stored row
     * @param last the stored row after the last
     * @param width the columns
     * @param spp the samples of each pixel
     * @param rowOf the image row of each stored row
     * @param columnOf the image column of each stored column, or {@code null} when the columns are in order
     */
    private static void permute(int[] stored, int[] out, int first, int last, int width, int spp, int[] rowOf,
                                int[] columnOf) {
        var stride = width * spp;
        for (var row = first; row < last; row++) {
            var from = row * stride;
            var to = rowOf[row] * stride;
            if (columnOf == null) {
                System.arraycopy(stored, from, out, to, stride);
                continue;
            }
            for (var column = 0; column < width; column++) {
                var source = from + column * spp;
                var target = to + columnOf[column] * spp;
                for (var sample = 0; sample < spp; sample++) {
                    out[target + sample] = stored[source + sample];
                }
            }
        }
    }

    /**
     * Moves the stored rows of a range from their stored places to their places in the image.
     *
     * @param stored the samples in stored order
     * @param out the samples in image order
     * @param first the first stored row
     * @param last the stored row after the last
     * @param width the columns
     * @param spp the samples of each pixel
     * @param rowOf the image row of each stored row
     * @param columnOf the image column of each stored column, or {@code null} when the columns are in order
     */
    private static void permute(float[] stored, float[] out, int first, int last, int width, int spp, int[] rowOf,
                                int[] columnOf) {
        var stride = width * spp;
        for (var row = first; row < last; row++) {
            var from = row * stride;
            var to = rowOf[row] * stride;
            if (columnOf == null) {
                System.arraycopy(stored, from, out, to, stride);
                continue;
            }
            for (var column = 0; column < width; column++) {
                var source = from + column * spp;
                var target = to + columnOf[column] * spp;
                for (var sample = 0; sample < spp; sample++) {
                    out[target + sample] = stored[source + sample];
                }
            }
        }
    }

    /**
     * Returns where each stored line of an interleaved dimension belongs.
     *
     * @param size the number of lines
     * @param factor the number of fields
     * @return for each stored line, its line in the image
     */
    static int[] fieldOrder(int size, int factor) {
        var order = new int[size];
        var stored = 0;
        for (var field = 0; field < factor; field++) {
            for (var line = field; line < size; line += factor) {
                order[stored++] = line;
            }
        }
        return order;
    }
}
