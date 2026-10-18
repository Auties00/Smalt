package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.image.tiff.internal.LzwEncoder;
import com.github.auties00.smalt.image.tiff.internal.PackBits;
import com.github.auties00.smalt.image.tiff.internal.Predictor;
import com.github.auties00.smalt.image.tiff.internal.TiffTags;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.List;
import java.util.Objects;

/**
 * Encodes pictures as TIFF or BigTIFF files, one page per picture.
 *
 * <p>Each page is written with the samples {@link TiffEncoderOptions#layout()} and
 * {@link TiffEncoderOptions#depth()} select (gray or RGB, with or without an unassociated alpha
 * sample, 8 or 16 bits), interleaved ({@code PlanarConfiguration} 1), in strips or tiles,
 * compressed with no compression, PackBits, LZW or Deflate and, for LZW and Deflate, the
 * horizontal predictor. Every page records a resolution of 72 dots per inch and, when the options
 * carry one, an ICC profile. Y'CbCr pictures are converted to RGB first.
 *
 * <p>Pages are streamed: {@link #addPage(Picture)} compresses a page and writes it, keeping only
 * the directory of the previous page until the next page or {@link #finish()} fixes its link, so a
 * sink that cannot seek works and memory use does not grow with the page count. Strips and tiles
 * are compressed in parallel on the shared pool.
 *
 * <p>An encoder is not thread-safe. The static methods are thread-safe.
 *
 * @implNote This implementation's LZW coder follows libtiff's code-width schedule (see
 * {@code LzwEncoder}); the Deflate streams are zlib streams from {@link ZlibDeflater}, as libtiff
 * writes them under compression 8.
 */
public final class TiffEncoder {
    /**
     * The target size of an automatic strip, in bytes.
     */
    private static final int STRIP_BYTES = 64 * 1024;

    /**
     * The options.
     */
    private final TiffEncoderOptions options;

    /**
     * The output.
     */
    private final MediaSink sink;

    /**
     * Whether values are little-endian.
     */
    private final boolean little;

    /**
     * Whether the file is a BigTIFF.
     */
    private final boolean big;

    /**
     * The number of bytes written, the offset of the next byte from the start of the file.
     */
    private long position;

    /**
     * The directory of the last page, waiting for its next pointer, or {@code null}.
     */
    private IfdBuilder pending;

    /**
     * The offset at which {@link #pending} is written.
     */
    private long pendingOffset;

    /**
     * The number of pages written.
     */
    private int pages;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * Opens an encoder writing to a sink with the {@link TiffEncoderOptions#defaults()}.
     *
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if {@code sink} is {@code null}
     */
    public TiffEncoder(MediaSink sink) {
        this(TiffEncoderOptions.defaults(), sink);
    }

    /**
     * Opens an encoder writing to a sink.
     *
     * <p>Offsets in the file are counted from the sink's position when the first page is added.
     *
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if an argument is {@code null}
     */
    public TiffEncoder(TiffEncoderOptions options, MediaSink sink) {
        this.options = Objects.requireNonNull(options, "options");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.little = !options.isBigEndian();
        this.big = options.isBigTiff();
    }

    /**
     * Encodes a picture as a one-page TIFF with the {@link TiffEncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the complete file
     * @throws NullPointerException if {@code picture} is {@code null}
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, TiffEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a one-page TIFF.
     *
     * @param picture the picture to encode
     * @param options the encoder settings
     * @return the complete file
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     */
    public static byte[] encode(Picture picture, TiffEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture as a one-page TIFF and writes it to a sink.
     *
     * @param picture the picture to encode
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, TiffEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        var encoder = new TiffEncoder(options, sink);
        encoder.addPage(picture);
        encoder.finish();
    }

    /**
     * Encodes pictures as a multi-page TIFF with the {@link TiffEncoderOptions#defaults()}.
     *
     * @param pictures the pictures, one page each, in order
     * @return the complete file
     * @throws NullPointerException if a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     */
    public static byte[] encode(List<? extends Picture> pictures) {
        return encode(pictures, TiffEncoderOptions.defaults());
    }

    /**
     * Encodes pictures as a multi-page TIFF.
     *
     * @param pictures the pictures, one page each, in order
     * @param options the encoder settings
     * @return the complete file
     * @throws NullPointerException if an argument or a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     */
    public static byte[] encode(List<? extends Picture> pictures, TiffEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(pictures, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes pictures as a multi-page TIFF and writes it to a sink.
     *
     * @param pictures the pictures, one page each, in order
     * @param options the encoder settings
     * @param sink the output, written from its position; not closed
     * @throws NullPointerException if an argument or a picture is {@code null}
     * @throws IllegalArgumentException if {@code pictures} is empty
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(List<? extends Picture> pictures, TiffEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(pictures, "pictures");
        if (pictures.isEmpty()) {
            throw new IllegalArgumentException("a TIFF file needs at least one page");
        }
        var encoder = new TiffEncoder(options, sink);
        for (var picture : pictures) {
            encoder.addPage(picture);
        }
        encoder.finish();
    }

    /**
     * Compresses a picture and writes it as the next page.
     *
     * @param picture the picture to add as the next page
     * @throws NullPointerException if {@code picture} is {@code null}
     * @throws IllegalStateException if {@link #finish()} already ran
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB
     * @throws MediaException.Io if writing the sink fails
     */
    public void addPage(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        if (finished) {
            throw new IllegalStateException("the TIFF file is finished");
        }
        try (var _ = ParallelBands.operation()) {
            var page = Samples.of(picture, options, little);
            var chunks = compress(page);
            var total = 0L;
            for (var chunk : chunks.data) {
                total += chunk.length;
            }
            long dataStart;
            if (pending == null) {
                dataStart = big ? 16 : 8;
            } else {
                dataStart = pendingOffset + pending.size();
            }
            var ifdOffset = (dataStart + total + 1) & ~1L;
            var counts = new long[chunks.data.length];
            var offsets = new long[chunks.data.length];
            var at = dataStart;
            for (var i = 0; i < counts.length; i++) {
                counts[i] = chunks.data[i].length;
                offsets[i] = at;
                at += counts[i];
            }
            var directory = directory(page, chunks, offsets, counts);
            checkOffset(ifdOffset + directory.size());
            if (pending == null) {
                writeHeader(ifdOffset);
            } else {
                write(pending.write(pendingOffset, ifdOffset));
            }
            for (var chunk : chunks.data) {
                write(chunk);
            }
            if ((position & 1) != 0) {
                write(new byte[1]);
            }
            pending = directory;
            pendingOffset = ifdOffset;
            pages++;
        }
    }

    /**
     * Writes the last page's directory, completing the file.
     *
     * @throws IllegalStateException if no page was added or the file is already finished
     * @throws MediaException.Io     if writing the sink fails
     */
    public void finish() {
        if (finished) {
            throw new IllegalStateException("the TIFF file is already finished");
        }
        if (pending == null) {
            throw new IllegalStateException("a TIFF file needs at least one page");
        }
        write(pending.write(pendingOffset, 0));
        pending = null;
        finished = true;
    }

    /**
     * Returns the number of pages written so far.
     *
     * @return the page count
     */
    public int pageCount() {
        return pages;
    }

    /**
     * Holds the samples of one page, ready to be cut into strips or tiles.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples per pixel: 1 to 4
     * @param bits the bits per sample: 8 or 16
     * @param alpha whether the last sample is alpha
     * @param data the rows, {@code width * channels * bits / 8} bytes each
     */
    private record Samples(int width, int height, int channels, int bits, boolean alpha, byte[] data) {
        /**
         * Converts a picture to the samples the options select.
         *
         * @param picture the picture to convert
         * @param options the encoder settings
         * @param little whether 16-bit samples are little-endian
         * @return the samples
         */
        static Samples of(Picture picture, TiffEncoderOptions options, boolean little) {
            var sixteen = switch (options.depth()) {
                case AUTO -> picture instanceof Rgba16Picture;
                case EIGHT -> false;
                case SIXTEEN -> true;
            };
            if (sixteen) {
                var wide = switch (picture) {
                    case Rgba16Picture rgba -> rgba;
                    default -> ImageOps.toSixteenBit(ImageOps.toRgba(picture));
                };
                return wide(wide, options.layout(), little);
            }
            return narrow(ImageOps.toRgba(picture), options.layout());
        }

        /**
         * Builds 8-bit samples.
         *
         * @param picture the pixels
         * @param layout  the requested layout
         * @return the samples
         */
        private static Samples narrow(RgbaPicture picture, TiffEncoderOptions.Layout layout) {
            var width = picture.width();
            var height = picture.height();
            var pixels = picture.pixels();
            var stride = picture.stride();
            var gray = true;
            var translucent = false;
            if (layout == TiffEncoderOptions.Layout.AUTO) {
                for (var y = 0; y < height && (gray || !translucent); y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * stride + x * 4;
                        gray &= pixels[at] == pixels[at + 1] && pixels[at] == pixels[at + 2];
                        translucent |= picture.hasAlpha() && pixels[at + 3] != (byte) 0xFF;
                    }
                }
            }
            var color = layout == TiffEncoderOptions.Layout.RGB || layout == TiffEncoderOptions.Layout.RGBA
                    || (layout == TiffEncoderOptions.Layout.AUTO && !gray);
            var alpha = layout == TiffEncoderOptions.Layout.RGBA || layout == TiffEncoderOptions.Layout.GRAY_ALPHA
                    || (layout == TiffEncoderOptions.Layout.AUTO && translucent);
            var channels = (color ? 3 : 1) + (alpha ? 1 : 0);
            var data = new byte[Math.multiplyExact(Math.multiplyExact(width, height), channels)];
            var out = 0;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    if (color) {
                        data[out++] = pixels[at];
                        data[out++] = pixels[at + 1];
                        data[out++] = pixels[at + 2];
                    } else {
                        data[out++] = (byte) luma(pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF);
                    }
                    if (alpha) {
                        data[out++] = pixels[at + 3];
                    }
                }
            }
            return new Samples(width, height, channels, 8, alpha, data);
        }

        /**
         * Builds 16-bit samples.
         *
         * @param picture the pixels
         * @param layout  the requested layout
         * @param little  whether samples are little-endian
         * @return the samples
         */
        private static Samples wide(Rgba16Picture picture, TiffEncoderOptions.Layout layout, boolean little) {
            var width = picture.width();
            var height = picture.height();
            var pixels = picture.pixels();
            var stride = picture.stride();
            var gray = true;
            var translucent = false;
            if (layout == TiffEncoderOptions.Layout.AUTO) {
                for (var y = 0; y < height && (gray || !translucent); y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * stride + x * 4;
                        gray &= pixels[at] == pixels[at + 1] && pixels[at] == pixels[at + 2];
                        translucent |= picture.hasAlpha() && pixels[at + 3] != (short) 0xFFFF;
                    }
                }
            }
            var color = layout == TiffEncoderOptions.Layout.RGB || layout == TiffEncoderOptions.Layout.RGBA
                    || (layout == TiffEncoderOptions.Layout.AUTO && !gray);
            var alpha = layout == TiffEncoderOptions.Layout.RGBA || layout == TiffEncoderOptions.Layout.GRAY_ALPHA
                    || (layout == TiffEncoderOptions.Layout.AUTO && translucent);
            var channels = (color ? 3 : 1) + (alpha ? 1 : 0);
            var data = new byte[Math.multiplyExact(Math.multiplyExact(width, height), channels * 2)];
            var out = 0;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    if (color) {
                        out = put16(data, out, pixels[at] & 0xFFFF, little);
                        out = put16(data, out, pixels[at + 1] & 0xFFFF, little);
                        out = put16(data, out, pixels[at + 2] & 0xFFFF, little);
                    } else {
                        out = put16(data, out, luma(pixels[at] & 0xFFFF, pixels[at + 1] & 0xFFFF, pixels[at + 2] & 0xFFFF), little);
                    }
                    if (alpha) {
                        out = put16(data, out, pixels[at + 3] & 0xFFFF, little);
                    }
                }
            }
            return new Samples(width, height, channels, 16, alpha, data);
        }

        /**
         * Computes the BT.601 luma of an RGB value with 8-bit fixed-point weights, exact for gray.
         *
         * @param r the red value
         * @param g the green value
         * @param b the blue value
         * @return the luma
         */
        private static int luma(int r, int g, int b) {
            return (77 * r + 150 * g + 29 * b + 128) >> 8;
        }

        /**
         * Writes one 16-bit sample.
         *
         * @param data   the destination
         * @param at     the index of the sample
         * @param value  the sample
         * @param little whether the sample is little-endian
         * @return the index after the sample
         */
        private static int put16(byte[] data, int at, int value, boolean little) {
            if (little) {
                data[at] = (byte) value;
                data[at + 1] = (byte) (value >>> 8);
            } else {
                data[at] = (byte) (value >>> 8);
                data[at + 1] = (byte) value;
            }
            return at + 2;
        }

        /**
         * Returns the size of one row.
         *
         * @return the size in bytes
         */
        int rowBytes() {
            return width * channels * bits / 8;
        }
    }

    /**
     * Holds the compressed strips or tiles of one page.
     *
     * @param data         the compressed strips or tiles, in order
     * @param chunkWidth   the tile width, or the image width for strips
     * @param chunkHeight  the tile height, or the rows per strip
     * @param tiled        whether the chunks are tiles
     * @param predicted    whether the horizontal predictor was applied
     */
    private record Chunks(byte[][] data, int chunkWidth, int chunkHeight, boolean tiled, boolean predicted) {
    }

    /**
     * Cuts a page into strips or tiles and compresses them.
     *
     * @param page the samples
     * @return the compressed chunks
     */
    private Chunks compress(Samples page) {
        // TODO: CMYK, palette and floating-point pages, and JPEG compression
        var tiled = options.tileWidth().isPresent();
        var rowBytes = page.rowBytes();
        int chunkWidth;
        int chunkHeight;
        if (tiled) {
            chunkWidth = options.tileWidth().getAsInt();
            chunkHeight = options.tileHeight().getAsInt();
        } else {
            chunkWidth = page.width();
            var rows = options.rawRowsPerStrip();
            chunkHeight = Math.min(page.height(), rows > 0 ? rows : Math.max(1, STRIP_BYTES / Math.max(1, rowBytes)));
        }
        var across = (page.width() + chunkWidth - 1) / chunkWidth;
        var down = (page.height() + chunkHeight - 1) / chunkHeight;
        var count = Math.multiplyExact(across, down);
        var predicted = options.predictor() == TiffPredictor.HORIZONTAL
                && (options.compression() == TiffCompression.LZW || options.compression() == TiffCompression.DEFLATE);
        var data = new byte[count][];
        var bytesPerPixel = page.channels() * page.bits() / 8;
        var bands = ParallelBands.bands(count, (long) page.width() * page.height() * page.channels());
        ParallelBands.run(bands, band -> {
            var lzw = options.compression() == TiffCompression.LZW ? new LzwEncoder() : null;
            var first = ParallelBands.start(band, bands, count);
            var last = ParallelBands.start(band + 1, bands, count);
            for (var index = first; index < last; index++) {
                var x0 = (index % across) * chunkWidth;
                var y0 = (index / across) * chunkHeight;
                var rows = tiled ? chunkHeight : Math.min(chunkHeight, page.height() - y0);
                var chunkRow = chunkWidth * bytesPerPixel;
                var chunk = new byte[chunkRow * rows];
                var copyRows = Math.min(rows, page.height() - y0);
                var copyBytes = Math.min(chunkWidth, page.width() - x0) * bytesPerPixel;
                for (var row = 0; row < copyRows; row++) {
                    System.arraycopy(page.data(), (y0 + row) * rowBytes + x0 * bytesPerPixel, chunk, row * chunkRow, copyBytes);
                }
                if (predicted) {
                    Predictor.applyHorizontal(chunk, 0, chunkRow, rows, page.channels(), chunkWidth, page.bits() / 8, little);
                }
                data[index] = switch (options.compression()) {
                    case PACKBITS -> PackBits.encode(chunk, 0, chunkRow, rows);
                    case LZW -> lzw.encode(chunk, 0, chunk.length);
                    case DEFLATE -> ZlibDeflater.zlib(chunk, 0, chunk.length, options.compressionLevel());
                    default -> chunk;
                };
            }
        });
        return new Chunks(data, chunkWidth, chunkHeight, tiled, predicted);
    }

    /**
     * Builds the directory of a page.
     *
     * @param page    the samples
     * @param chunks  the compressed chunks
     * @param offsets the offset of each chunk
     * @param counts  the size of each chunk
     * @return the directory
     */
    private IfdBuilder directory(Samples page, Chunks chunks, long[] offsets, long[] counts) {
        var directory = new IfdBuilder(little, big);
        directory.longs(TiffTags.IMAGE_WIDTH, page.width());
        directory.longs(TiffTags.IMAGE_LENGTH, page.height());
        var bits = new int[page.channels()];
        java.util.Arrays.fill(bits, page.bits());
        directory.shorts(TiffTags.BITS_PER_SAMPLE, bits);
        directory.shorts(TiffTags.COMPRESSION, options.compression().code());
        directory.shorts(TiffTags.PHOTOMETRIC, page.channels() >= 3 ? 2 : 1);
        directory.shorts(TiffTags.SAMPLES_PER_PIXEL, page.channels());
        directory.rational(TiffTags.X_RESOLUTION, 72, 1);
        directory.rational(TiffTags.Y_RESOLUTION, 72, 1);
        directory.shorts(TiffTags.RESOLUTION_UNIT, 2);
        directory.shorts(TiffTags.PLANAR_CONFIGURATION, 1);
        if (chunks.predicted()) {
            directory.shorts(TiffTags.PREDICTOR, 2);
        }
        if (page.alpha()) {
            directory.shorts(TiffTags.EXTRA_SAMPLES, 2);
        }
        if (chunks.tiled()) {
            directory.longs(TiffTags.TILE_WIDTH, chunks.chunkWidth());
            directory.longs(TiffTags.TILE_LENGTH, chunks.chunkHeight());
            directory.offsets(TiffTags.TILE_OFFSETS, offsets);
            directory.offsets(TiffTags.TILE_BYTE_COUNTS, counts);
        } else {
            directory.longs(TiffTags.ROWS_PER_STRIP, chunks.chunkHeight());
            directory.offsets(TiffTags.STRIP_OFFSETS, offsets);
            directory.offsets(TiffTags.STRIP_BYTE_COUNTS, counts);
        }
        var profile = options.iccProfileBytes();
        if (profile != null) {
            directory.undefined(TiffTags.ICC_PROFILE, profile);
        }
        return directory;
    }

    /**
     * Writes the file header.
     *
     * @param firstDirectory the offset of the first directory
     */
    private void writeHeader(long firstDirectory) {
        var header = new byte[big ? 16 : 8];
        header[0] = header[1] = (byte) (little ? 'I' : 'M');
        put(header, 2, big ? 43 : 42, 2);
        if (big) {
            put(header, 4, 8, 2);
            put(header, 8, firstDirectory, 8);
        } else {
            put(header, 4, firstDirectory, 4);
        }
        write(header);
    }

    /**
     * Requires an offset to fit a classic file.
     *
     * @param end the offset of the end of the data
     * @throws MediaException.LimitExceeded if a classic file cannot address it
     */
    private void checkOffset(long end) {
        if (!big && end > 0xFFFF_FFFFL) {
            throw new MediaException.LimitExceeded("the TIFF file exceeds 4 GiB; write a BigTIFF instead");
        }
    }

    /**
     * Writes bytes to the sink.
     *
     * @param bytes the bytes to write
     */
    private void write(byte[] bytes) {
        sink.write(bytes);
        position += bytes.length;
    }

    /**
     * Writes an unsigned value in the file's byte order.
     *
     * @param out the destination
     * @param at the index of the first byte
     * @param value the number to store
     * @param bytes the number of bytes
     */
    private void put(byte[] out, int at, long value, int bytes) {
        for (var i = 0; i < bytes; i++) {
            var shift = little ? 8 * i : 8 * (bytes - 1 - i);
            out[at + i] = (byte) (value >>> shift);
        }
    }
}
