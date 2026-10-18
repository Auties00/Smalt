package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.internal.LosslessJpeg;
import com.github.auties00.smalt.image.jxl.JxlDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.internal.Predictor;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.zip.DataFormatException;

/**
 * Reads the sensor samples a TIFF directory stores in strips or tiles, with the compressions and
 * predictors of TIFF 6.0 and DNG: none, lossless JPEG (7), Deflate (8), lossy JPEG (34892) and JPEG
 * XL (52546, DNG 1.7, decoded by {@link JxlDecoder}), with the integer predictors (2, and DNG's
 * 34892 and 34893, which predict from 2 and 4 pixels back) on integer samples and the
 * floating-point predictors (3, and DNG's 34894 and 34895) on floating-point samples.
 *
 * <p>Integer samples of 1 to 16 bits are unpacked most significant bit first with every row of a
 * strip or tile starting on a byte boundary, multi-byte samples in the file's byte order; floating
 * point samples of 16, 24 and 32 bits follow DNG. Strips and tiles decode in parallel.
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
     */
    record Block(int x, int y, int width, int height, long offset, long length) {
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
        if (floating ? bits != 16 && bits != 24 && bits != 32 : bits < 1 || bits > 16) {
            if (!floating && bits == 32) {
                throw new MediaException.Unsupported("raw", "32-bit integer sensor samples are not supported");
            }
            throw new MediaException.Unsupported("raw", bits + "-bit " + (floating ? "floating-point" : "integer")
                    + " sensor samples are not supported");
        }
        if (samples > 1 && ifd.intValue(PLANAR_CONFIGURATION, 1) != 1) {
            throw new MediaException.Unsupported("raw", "planar sensor samples are not supported");
        }
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
        if (compression == COMPRESSION_JPEG_XL && ((floating ? bits != 16 : bits < 8) || samples == 2 || samples == 4)) {
            throw new MediaException.Unsupported("raw", "JPEG XL sensor data of " + samples + " " + bits + "-bit "
                    + (floating ? "floating-point" : "integer") + " samples is not supported");
        }
        if (compression != 1 && compression != 7 && compression != 8 && compression != COMPRESSION_LOSSY_JPEG
                && compression != COMPRESSION_JPEG_XL) {
            throw new MediaException.Unsupported("raw", "TIFF compression " + compression
                    + " is not supported for sensor data");
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
        var blocks = blocks(ifd, width, height);
        var little = ifd.reader().little();
        var source = ifd.reader().source();
        return (plane, limits) -> read(source, little, blocks, compression, predictor, plane, bits, floating, limits);
    }

    /**
     * Lists the strips or tiles of a directory.
     *
     * @param ifd the directory
     * @param width the image width
     * @param height the image height
     * @return the blocks
     */
    static Block[] blocks(Ifd ifd, int width, int height) {
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
        var across = (width + blockWidth - 1) / blockWidth;
        var down = (height + blockHeight - 1) / blockHeight;
        if ((long) across * down > offsets.length) {
            throw new MediaException.Malformed("raw image declares " + offsets.length + " blocks for "
                    + (long) across * down);
        }
        var base = ifd.reader().base();
        var size = ifd.reader().size();
        var blocks = new Block[across * down];
        for (var index = 0; index < blocks.length; index++) {
            var x = (index % across) * blockWidth;
            var y = (index / across) * blockHeight;
            var offset = base + offsets[index];
            var length = counts[index];
            if (offset < 0 || length < 0 || (size >= 0 && offset > size)) {
                throw new MediaException.Malformed("raw image block " + index + " lies outside the file");
            }
            if (size >= 0) {
                length = Math.min(length, size - offset);
            }
            var rows = tiled ? blockHeight : Math.min(blockHeight, height - y);
            blocks[index] = new Block(x, y, blockWidth, rows, offset, length);
        }
        return blocks;
    }

    /**
     * Reads every block into the plane.
     *
     * @param source the raw file the blocks lie in
     * @param little whether multi-byte samples are little endian
     * @param blocks the strips or tiles, in raster order
     * @param compression the TIFF {@code Compression} code
     * @param predictor the TIFF {@code Predictor} code
     * @param plane the plane to fill
     * @param bits the bits per sample
     * @param floating whether samples are floating-point
     * @param limits the resource bounds
     */
    private static void read(MediaSource source, boolean little, Block[] blocks, int compression, int predictor,
                             SensorPlane plane, int bits, boolean floating, MediaLimits limits) {
        var bands = ParallelBands.bands(blocks.length, (long) plane.width * plane.height * plane.samplesPerPixel);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, blocks.length);
            var last = ParallelBands.start(band + 1, bands, blocks.length);
            for (var index = first; index < last; index++) {
                readBlock(source, little, blocks[index], compression, predictor, plane, bits, floating, limits);
            }
        });
    }

    /**
     * Reads one block into the plane.
     *
     * @param source the raw file the block lies in
     * @param little whether multi-byte samples are little endian
     * @param block the strip or tile, with its offset, length and position in the plane
     * @param compression the TIFF {@code Compression} code
     * @param predictor the TIFF {@code Predictor} code
     * @param plane the plane to fill
     * @param bits the bits per sample
     * @param floating whether samples are floating-point
     * @param limits the resource bounds
     */
    private static void readBlock(MediaSource source, boolean little, Block block, int compression, int predictor,
                                  SensorPlane plane, int bits, boolean floating, MediaLimits limits) {
        LimitChecks.checkAllocation(limits, block.length);
        if (block.length > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("raw image block of " + block.length + " bytes");
        }
        var data = new byte[(int) block.length];
        source.readFully(block.offset, data, 0, data.length);
        switch (compression) {
            case 7 -> losslessJpeg(data, block, plane);
            case COMPRESSION_LOSSY_JPEG -> lossyJpeg(data, block, plane, limits);
            case COMPRESSION_JPEG_XL -> jpegXl(data, block, plane, bits, floating, limits);
            default -> {
                var spp = plane.samplesPerPixel;
                var bytesPerSample = floating ? bits / 8 : 0;
                var rowBytes = floating ? (long) block.width * spp * bytesPerSample
                        : ((long) block.width * spp * bits + 7) / 8;
                var expected = rowBytes * block.height;
                if (expected > Integer.MAX_VALUE - 16) {
                    throw new MediaException.LimitExceeded("raw image block of " + expected + " bytes");
                }
                if (compression == 8) {
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
                    undoDifferences(block, plane, spp * pixelsBack(predictor));
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
     * @param little whether 16-bit samples are little endian
     * @param rowBytes the bytes of one stored row
     */
    private static void integers(byte[] data, Block block, SensorPlane plane, int bits, boolean little, int rowBytes) {
        var spp = plane.samplesPerPixel;
        var rows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x);
        var count = columns * spp;
        var out = plane.integers;
        for (var row = 0; row < rows; row++) {
            var start = (long) row * rowBytes;
            var target = ((block.y + row) * plane.width + block.x) * spp;
            if (start >= data.length) {
                return;
            }
            var at = (int) start;
            if (bits == 16) {
                var available = Math.min(count, (data.length - at) / 2);
                for (var index = 0; index < available; index++) {
                    var a = data[at + 2 * index] & 0xFF;
                    var b = data[at + 2 * index + 1] & 0xFF;
                    out[target + index] = (short) (little ? a | (b << 8) : (a << 8) | b);
                }
            } else if (bits == 8) {
                var available = Math.min(count, data.length - at);
                for (var index = 0; index < available; index++) {
                    out[target + index] = (short) (data[at + index] & 0xFF);
                }
            } else {
                Unpack.msbFirst(data, at, data.length, bits, out, target, count);
            }
        }
    }

    /**
     * Undoes horizontal differencing over the block's rows.
     *
     * @param block the strip or tile whose rows are undone
     * @param plane the plane holding the block's differences
     * @param stride the distance between a sample and the one it is predicted from
     */
    private static void undoDifferences(Block block, SensorPlane plane, int stride) {
        var spp = plane.samplesPerPixel;
        var rows = Math.min(block.height, plane.height - block.y);
        var count = Math.min(block.width, plane.width - block.x) * spp;
        var out = plane.integers;
        for (var row = 0; row < rows; row++) {
            var start = ((block.y + row) * plane.width + block.x) * spp;
            for (var index = stride; index < count; index++) {
                out[start + index] = (short) (out[start + index] + out[start + index - stride]);
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
        var spp = plane.samplesPerPixel;
        var rows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x);
        var rowBytes = block.width * spp * bytesPerSample;
        var out = plane.floats;
        for (var row = 0; row < rows; row++) {
            var target = ((block.y + row) * plane.width + block.x) * spp;
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
                out[target + index] = switch (bytesPerSample) {
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
            var value = mantissa / 65536.0f * (float) Math.pow(2, -62);
            return sign != 0 ? -value : value;
        }
        return Float.intBitsToFloat((sign << 31) | ((exponent - 64 + 127) << 23) | (mantissa << 7));
    }

    /**
     * Decodes a lossless JPEG block, laying its samples out in the block's raster order.
     *
     * @param data the block bytes
     * @param block the strip or tile the samples belong to
     * @param plane the plane receiving the samples
     */
    private static void losslessJpeg(byte[] data, Block block, SensorPlane plane) {
        var jpeg = new LosslessJpeg(data, 0, data.length);
        var spp = plane.samplesPerPixel;
        var blockRow = (long) block.width * spp;
        var rowSamples = (long) jpeg.width() * jpeg.components();
        if (rowSamples * jpeg.height() < blockRow * block.height) {
            throw new MediaException.Malformed("lossless JPEG of " + jpeg.width() + "x" + jpeg.height() + "x"
                    + jpeg.components() + " is smaller than its " + block.width + "x" + block.height + " block");
        }
        var out = plane.integers;
        var limitRows = Math.min(block.height, plane.height - block.y);
        var columns = Math.min(block.width, plane.width - block.x) * spp;
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
                    var target = ((block.y + y) * plane.width + block.x) * spp + x;
                    for (var k = 0; k < copy; k++) {
                        out[target + k] = (short) samples[index + k];
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
     * count differs from the plane's samples per pixel
     */
    private static void jpegXl(byte[] data, Block block, SensorPlane plane, int bits, boolean floating, MediaLimits limits) {
        var image = JxlDecoder.decodeFloat(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits).build());
        var spp = plane.samplesPerPixel;
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
                var target = ((block.y + row) * plane.width + block.x) * spp + sample;
                if (floating) {
                    for (var column = 0; column < columns; column++) {
                        plane.floats[target + column * spp] = channel[source + column];
                    }
                } else {
                    for (var column = 0; column < columns; column++) {
                        var value = Math.round(channel[source + column] * maximum);
                        plane.integers[target + column * spp] = (short) Math.clamp(value, 0, (1 << bits) - 1);
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
        var spp = plane.samplesPerPixel;
        var rows = Math.min(Math.min(block.height, picture.height()), plane.height - block.y);
        var columns = Math.min(Math.min(block.width, picture.width()), plane.width - block.x);
        var pixels = picture.pixels();
        var out = plane.integers;
        for (var row = 0; row < rows; row++) {
            for (var column = 0; column < columns; column++) {
                var source = row * picture.stride() + column * 4;
                var target = ((block.y + row) * plane.width + block.x + column) * spp;
                for (var sample = 0; sample < spp; sample++) {
                    out[target + sample] = (short) (pixels[source + Math.min(sample, 2)] & 0xFF);
                }
            }
        }
    }
}
