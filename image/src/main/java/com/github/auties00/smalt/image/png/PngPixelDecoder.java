package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.internal.zlib.ZlibInflater;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.zip.DataFormatException;

/**
 * Decompresses, unfilters and converts the image data of a PNG static image or APNG frame to
 * RGBA8.
 *
 * <p>The zlib stream is inflated with {@link ZlibInflater} into a staging buffer holding a batch of
 * whole scanlines (filter type byte included), and never beyond the exact number of bytes the
 * image needs ({@code height * (1 + rowBytes)}, summed over the seven passes when interlaced): a
 * stream that would inflate further is simply not read past that point, so no input can make the
 * decoder produce more than the header declares. Data after the end of the image is ignored, and
 * the zlib Adler-32 trailer is not checked (each chunk's CRC-32 already covers the data). A stream
 * that ends early, is corrupt, or needs a preset dictionary raises
 * {@link MediaException.Malformed}.
 *
 * <p>An image whose inflated data exceeds {@link #STAGING_SIZE} is inflated one batch ahead:
 * while the calling thread reconstructs the scanlines of one batch, a helper thread inflates the
 * next into a second staging buffer, so the inflater and the filters run at the same time. Only one thread uses the
 * inflater at any time, and a failure is reported where the sequential order would report it.
 *
 * <p>Each scanline is reconstructed out of place from the staging buffer: non-interlaced 8-bit
 * truecolor with alpha straight into the destination, which already has the RGBA8 layout, every
 * other format into a scanline buffer that a {@link PngRowConverter} then converts. Interlaced
 * images are decoded pass by pass (Adam7, PNG 3rd edition 8.1) and each reduced image's pixels
 * are scattered to their final positions.
 */
final class PngPixelDecoder implements AutoCloseable {
    /**
     * The first column of each Adam7 pass.
     */
    private static final int[] PASS_X = {0, 4, 0, 2, 0, 1, 0};

    /**
     * The first row of each Adam7 pass.
     */
    private static final int[] PASS_Y = {0, 0, 4, 0, 2, 0, 1};

    /**
     * The column step of each Adam7 pass.
     */
    private static final int[] PASS_DX = {8, 8, 4, 4, 2, 2, 1};

    /**
     * The row step of each Adam7 pass.
     */
    private static final int[] PASS_DY = {8, 8, 8, 4, 4, 2, 2};

    /**
     * The size of the compressed-input buffer.
     */
    private static final int INPUT_SIZE = 256 * 1024;

    /**
     * The target size of the staging storage: one buffer of this size for an image that fits in
     * it, otherwise two buffers of half this size, one inflated while the other is
     * reconstructed; a buffer grows to one scanline when a scanline is longer.
     *
     * @implNote This implementation inflates 512 KiB to 1 MiB per {@link ZlibInflater} call: measured
     * with the JDK's zlib, output batches of 1 MiB inflate 5 to 10 percent faster than 256 KiB ones
     * and about 25 percent faster than one scanline at a time. Splitting the storage in two keeps
     * the memory of a single buffer while the inflater runs one batch ahead.
     */
    private static final int STAGING_SIZE = 1024 * 1024;

    /**
     * The number of consecutive inflate calls that may neither produce output nor consume input
     * before the stream is declared stuck.
     */
    private static final int MAX_STALLS = 4;

    /**
     * The image header.
     */
    private final PngHeader header;

    /**
     * The scanline converter.
     */
    private final PngRowConverter converter;

    /**
     * The filter kernel.
     */
    private final PngFilterKernel kernel;

    /**
     * The zlib decompressor, reset for every image.
     */
    private final ZlibInflater inflater;

    /**
     * The compressed-input buffer, sized on first use.
     */
    private byte[] input;

    /**
     * A staging buffer, which holds a batch of inflated scanlines, each preceded by its filter
     * type byte.
     */
    private byte[] staging;

    /**
     * The second staging buffer, of the same size as {@link #staging}, which the next batch is
     * inflated into while the scanlines of the current one are reconstructed, or {@code null}
     * while every image fits in {@link #STAGING_SIZE} bytes.
     */
    private byte[] ahead;

    /**
     * The scanline being reconstructed, for formats converted after reconstruction.
     */
    private byte[] current;

    /**
     * The reconstructed prior scanline, for formats converted after reconstruction.
     */
    private byte[] prior;

    /**
     * A third scanline buffer, so that two scanlines can be reconstructed together while the one
     * above them is still read.
     */
    private byte[] spare;

    /**
     * An all-zero scanline, the prior row of every image's or pass's first scanline.
     */
    private byte[] zero;

    /**
     * Constructs a decoder for images with the given header.
     *
     * @param header    the image header
     * @param converter the scanline converter for the header's color type and depth
     * @param kernel    the filter kernel
     */
    PngPixelDecoder(PngHeader header, PngRowConverter converter, PngFilterKernel kernel) {
        this.header = header;
        this.converter = converter;
        this.kernel = kernel;
        this.inflater = new ZlibInflater();
    }

    /**
     * Decodes one image from the open data stream of {@code reader} into a region of an RGBA8
     * buffer.
     *
     * @param reader       the reader whose open data stream supplies the zlib stream
     * @param width        the image width in pixels
     * @param height       the image height in pixels
     * @param target       the destination buffer
     * @param targetOffset the index of the image's top-left pixel
     * @param targetStride the byte distance between destination rows
     * @throws MediaException.Malformed if the data is truncated, corrupt, uses an invalid filter
     *                                  type, or refers past the palette
     */
    void decode(PngReader reader, int width, int height, byte[] target, int targetOffset, int targetStride) {
        decode(reader, width, height, target, null, targetOffset, targetStride);
    }

    /**
     * Decodes one 16-bit image from the open data stream of {@code reader} into a region of a
     * packed 16-bit RGBA buffer, keeping every sample at full precision.
     *
     * @param reader       the reader whose open data stream supplies the zlib stream
     * @param width        the image width in pixels
     * @param height       the image height in pixels
     * @param target       the destination samples
     * @param targetOffset the index of the image's top-left sample
     * @param targetStride the sample distance between destination rows
     * @throws MediaException.Malformed if the data is truncated, corrupt or uses an invalid filter
     *                                  type
     */
    void decodeWide(PngReader reader, int width, int height, short[] target, int targetOffset, int targetStride) {
        decode(reader, width, height, null, target, targetOffset, targetStride);
    }

    /**
     * Decodes one image into either an RGBA8 or a 16-bit RGBA destination.
     *
     * @param reader       the reader whose open data stream supplies the zlib stream
     * @param width        the image width in pixels
     * @param height       the image height in pixels
     * @param target       the RGBA8 destination, or {@code null}
     * @param wide         the 16-bit destination, or {@code null}
     * @param targetOffset the index of the image's top-left sample
     * @param targetStride the sample distance between destination rows
     */
    private void decode(PngReader reader, int width, int height, byte[] target, short[] wide, int targetOffset,
                        int targetStride) {
        inflater.reset();
        var rowBytes = (int) header.rowBytes(width);
        ensureBuffers(rowBytes, (long) height * (rowBytes + 1));
        if (header.interlaced()) {
            for (var pass = 0; pass < 7; pass++) {
                var passWidth = (width - PASS_X[pass] + PASS_DX[pass] - 1) / PASS_DX[pass];
                var passHeight = (height - PASS_Y[pass] + PASS_DY[pass] - 1) / PASS_DY[pass];
                if (passWidth > 0 && passHeight > 0) {
                    decodeRows(reader, passWidth, passHeight, target, wide,
                            targetOffset + PASS_Y[pass] * targetStride + PASS_X[pass] * 4,
                            targetStride * PASS_DY[pass], PASS_DX[pass] * 4);
                }
            }
        } else {
            decodeRows(reader, width, height, target, wide, targetOffset, targetStride, 4);
        }
    }

    /**
     * Grows the scanline, staging and input buffers for an image.
     *
     * <p>Small images get buffers no larger than their inflated data, so decoding an icon does
     * not allocate megabytes; images larger than {@link #STAGING_SIZE} get two staging buffers of
     * half that size.
     *
     * @param rowBytes   the longest scanline, in bytes
     * @param imageBytes the inflated size of the image (an upper bound when interlaced)
     */
    private void ensureBuffers(int rowBytes, long imageBytes) {
        if (current == null || current.length < rowBytes) {
            current = new byte[rowBytes];
            prior = new byte[rowBytes];
            spare = new byte[rowBytes];
            zero = new byte[rowBytes];
        }
        var pipelined = imageBytes > STAGING_SIZE;
        var target = pipelined ? STAGING_SIZE / 2 : STAGING_SIZE;
        var stagingSize = (int) Math.max(Math.min(target, imageBytes), rowBytes + 1);
        if (staging == null || staging.length < stagingSize) {
            staging = new byte[stagingSize];
            ahead = null;
        }
        if (pipelined && ahead == null) {
            ahead = new byte[staging.length];
        }
        var inputSize = (int) Math.max(4096, Math.min(INPUT_SIZE, imageBytes));
        if (input == null || input.length < inputSize) {
            input = new byte[inputSize];
        }
    }

    /**
     * Decodes the scanlines of an image or of one Adam7 pass.
     *
     * @param reader       the data source
     * @param width        the width of the (reduced) image in pixels
     * @param height       the height of the (reduced) image in pixels
     * @param target       the RGBA8 destination, or {@code null} when {@code wide} is set
     * @param wide         the 16-bit RGBA destination, or {@code null} when {@code target} is set
     * @param targetOffset the index of the destination pixel of the first scanline's first pixel
     * @param rowStep      the sample distance between the destinations of consecutive scanlines
     * @param pixelStep    the sample distance between the destinations of consecutive pixels
     */
    private void decodeRows(PngReader reader, int width, int height, byte[] target, short[] wide, int targetOffset,
                            int rowStep, int pixelStep) {
        var rowBytes = (int) header.rowBytes(width);
        var distance = header.filterDistance();
        var direct = wide == null && pixelStep == 4 && header.colorType() == PngHeader.RGBA && header.bitDepth() == 8;
        var lineBytes = rowBytes + 1;
        var batchRows = Math.max(1, staging.length / lineBytes);
        var above = zero;
        var aboveOffset = 0;
        var buffer = staging;
        var spareBuffer = ahead;
        inflateFully(reader, buffer, 0, Math.min(batchRows, height) * lineBytes);
        for (var y = 0; y < height; ) {
            var batch = Math.min(batchRows, height - y);
            var following = Math.min(batchRows, height - y - batch);
            ParallelBands.Pending<Void> pending = null;
            if (following > 0 && spareBuffer != null) {
                var nextBuffer = spareBuffer;
                var nextLength = following * lineBytes;
                pending = ParallelBands.start(() -> inflateFully(reader, nextBuffer, 0, nextLength));
            }
            try {
                for (var line = 0; line < batch; line++, y++) {
                    var raw = line * lineBytes;
                    var type = buffer[raw] & 0xFF;
                    var paired = line + 1 < batch && (type == 3 || type == 4)
                            && (buffer[raw + lineBytes] & 0xFF) == type;
                    var destination = targetOffset + y * rowStep;
                    if (direct) {
                        if (paired) {
                            var next = destination + rowStep;
                            unfilterPair(type, buffer, raw + 1, target, destination, target, next, above, aboveOffset,
                                    rowBytes, distance);
                            line++;
                            y++;
                            destination = next;
                        } else {
                            unfilter(type, buffer, raw + 1, target, destination, above, aboveOffset, rowBytes,
                                    distance);
                        }
                        above = target;
                        aboveOffset = destination;
                    } else {
                        var row = free(above, null);
                        if (paired) {
                            var next = free(above, row);
                            unfilterPair(type, buffer, raw + 1, row, 0, next, 0, above, 0, rowBytes, distance);
                            convert(row, width, target, wide, destination, pixelStep);
                            line++;
                            y++;
                            convert(next, width, target, wide, destination + rowStep, pixelStep);
                            above = next;
                        } else {
                            unfilter(type, buffer, raw + 1, row, 0, above, 0, rowBytes, distance);
                            convert(row, width, target, wide, destination, pixelStep);
                            above = row;
                        }
                    }
                }
            } catch (RuntimeException | Error failure) {
                if (pending != null) {
                    pending.cancel();
                }
                throw failure;
            }
            if (pending != null) {
                pending.join();
                var swap = buffer;
                buffer = spareBuffer;
                spareBuffer = swap;
            } else if (following > 0) {
                inflateFully(reader, buffer, 0, following * lineBytes);
            }
        }
    }

    /**
     * Converts a reconstructed scanline into whichever destination is set.
     *
     * @param row         the scanline
     * @param width       the number of pixels
     * @param target      the RGBA8 destination, or {@code null}
     * @param wide        the 16-bit RGBA destination, or {@code null}
     * @param destination the index of the first destination pixel
     * @param pixelStep   the sample distance between destination pixels
     */
    private void convert(byte[] row, int width, byte[] target, short[] wide, int destination, int pixelStep) {
        if (wide != null) {
            converter.convertWide(row, 0, width, wide, destination, pixelStep);
        } else {
            converter.convert(row, 0, width, target, destination, pixelStep);
        }
    }

    /**
     * Returns a scanline buffer that is neither of the given ones.
     *
     * @param busy  a buffer in use, possibly the zero scanline
     * @param other another buffer in use, or {@code null}
     * @return one of the three scanline buffers
     */
    private byte[] free(byte[] busy, byte[] other) {
        if (current != busy && current != other) {
            return current;
        }
        return prior != busy && prior != other ? prior : spare;
    }

    /**
     * Reconstructs two consecutive scanlines that use the same filter type, 3 or 4.
     *
     * @param type         the filter type of both scanlines
     * @param raw          the array holding the filtered scanlines
     * @param firstOffset  the index of the first filtered scanline
     * @param first        the destination of the first scanline
     * @param firstOut     the index of its first byte
     * @param second       the destination of the second scanline
     * @param secondOut    the index of its first byte
     * @param above        the reconstructed scanline above the first one
     * @param aboveOffset  the index of its first byte
     * @param length       the scanline length
     * @param distance     the filter distance
     */
    private void unfilterPair(int type, byte[] raw, int firstOffset, byte[] first, int firstOut, byte[] second,
                              int secondOut, byte[] above, int aboveOffset, int length, int distance) {
        var secondOffset = firstOffset + length + 1;
        if (type == 3) {
            kernel.unfilterAveragePair(raw, firstOffset, secondOffset, first, firstOut, second, secondOut, above,
                    aboveOffset, length, distance);
        } else {
            kernel.unfilterPaethPair(raw, firstOffset, secondOffset, first, firstOut, second, secondOut, above,
                    aboveOffset, length, distance);
        }
    }

    /**
     * Reconstructs one scanline.
     *
     * @param type        the filter type byte
     * @param raw         the array holding the filtered scanline
     * @param rawOffset   the index of its first byte
     * @param out         the destination
     * @param outOffset   the index of the first reconstructed byte
     * @param above       the reconstructed prior scanline
     * @param aboveOffset the index of its first byte
     * @param length      the scanline length
     * @param distance    the filter distance
     * @throws MediaException.Malformed if the filter type is not 0 to 4
     */
    private void unfilter(int type, byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] above,
                          int aboveOffset, int length, int distance) {
        switch (type) {
            case 0 -> System.arraycopy(raw, rawOffset, out, outOffset, length);
            case 1 -> kernel.unfilterSub(raw, rawOffset, out, outOffset, length, distance);
            case 2 -> kernel.unfilterUp(raw, rawOffset, out, outOffset, above, aboveOffset, length);
            case 3 -> kernel.unfilterAverage(raw, rawOffset, out, outOffset, above, aboveOffset, length, distance);
            case 4 -> kernel.unfilterPaeth(raw, rawOffset, out, outOffset, above, aboveOffset, length, distance);
            default -> throw new MediaException.Malformed("invalid PNG filter type " + type);
        }
    }

    /**
     * Inflates exactly {@code length} bytes, feeding compressed data from the reader as needed.
     *
     * @param reader the data source
     * @param buffer the destination
     * @param offset the index of the first byte written
     * @param length the number of bytes to produce
     * @throws MediaException.Malformed if the stream ends early, is corrupt, needs a dictionary,
     *                                  or makes no progress
     */
    private void inflateFully(PngReader reader, byte[] buffer, int offset, int length) {
        var stalls = 0;
        while (length > 0) {
            int count;
            var before = inflater.getRemaining();
            try {
                count = inflater.inflate(buffer, offset, length);
            } catch (DataFormatException exception) {
                throw new MediaException.Malformed("corrupt PNG image data: " + exception.getMessage(), exception);
            }
            if (count > 0) {
                offset += count;
                length -= count;
                stalls = 0;
                continue;
            }
            if (inflater.finished()) {
                throw new MediaException.Malformed("PNG image data ends before the image is complete");
            }
            if (inflater.needsDictionary()) {
                throw new MediaException.Malformed("PNG image data requires a preset dictionary");
            }
            if (inflater.needsInput()) {
                var read = reader.readImageData(input, 0, input.length);
                if (read < 0) {
                    throw new MediaException.Malformed("PNG image data is truncated");
                }
                inflater.setInput(input, 0, read);
                continue;
            }
            if (inflater.getRemaining() == before && ++stalls > MAX_STALLS) {
                throw new MediaException.Malformed("PNG image data makes no progress");
            }
        }
    }

    /**
     * Checks that the zlib stream of the image just decoded holds no further image data.
     *
     * <p>Compressed data is read until the stream ends or the data stream is exhausted; a stream
     * whose end is missing is accepted, but one that inflates to even one more byte is not.
     *
     * @param reader the data source
     * @throws MediaException.Malformed if the stream holds more data than the image, or is corrupt
     */
    void checkEnd(PngReader reader) {
        var probe = new byte[1];
        var stalls = 0;
        while (true) {
            int count;
            var before = inflater.getRemaining();
            try {
                count = inflater.inflate(probe, 0, 1);
            } catch (DataFormatException exception) {
                throw new MediaException.Malformed("corrupt PNG image data: " + exception.getMessage(), exception);
            }
            if (count > 0) {
                throw new MediaException.Malformed("PNG image data holds more scanlines than the image");
            }
            if (inflater.finished() || inflater.needsDictionary()) {
                return;
            }
            if (inflater.needsInput()) {
                var read = reader.readImageData(input, 0, input.length);
                if (read < 0) {
                    return;
                }
                inflater.setInput(input, 0, read);
                continue;
            }
            if (inflater.getRemaining() == before && ++stalls > MAX_STALLS) {
                throw new MediaException.Malformed("PNG image data makes no progress");
            }
        }
    }

    /**
     * Releases the decoder; the inflater holds no native state.
     */
    @Override
    public void close() {
    }
}
