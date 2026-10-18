package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;
import java.util.zip.Adler32;

/**
 * Encodes pictures as 8-bit PNG datastreams.
 *
 * <p>The color type is chosen from the pixels:
 * <ul>
 *   <li>indexed color ({@code PLTE}, plus {@code tRNS} when some color is translucent) when the
 *       picture has at most 256 distinct RGBA colors and the indexed scanlines plus the palette
 *       chunks are smaller than the truecolor scanlines;</li>
 *   <li>otherwise truecolor when every pixel is opaque;</li>
 *   <li>otherwise truecolor with alpha.</li>
 * </ul>
 * A picture whose {@link RgbaPicture#hasAlpha()} is {@code false} is treated as opaque: its alpha
 * samples are ignored. Every other picture round-trips exactly, including the color of fully
 * transparent pixels. The output is non-interlaced with bit depth 8 and holds only the critical
 * chunks ({@code IHDR}, optional {@code PLTE}, {@code IDAT}, {@code IEND}), {@code tRNS}, and the
 * {@code iCCP} chunk of {@link PngEncoderOptions#colorProfile()} right after {@code IHDR}. The
 * scanlines are filtered as {@link PngEncoderOptions#filter()} selects and compressed into one
 * zlib stream at {@link PngEncoderOptions#compressionLevel()}.
 *
 * <p>Bands of scanlines are compressed in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the datastream does not
 * depend on the thread count. This class is thread-safe.
 *
 * @implNote This implementation compresses bands of about 1 MiB of filtered scanlines
 * independently, each primed with the 32 KiB before it, so the stream is a few bytes per band
 * larger than a single-pass encoding. Whenever a filter other than None is in use it discards
 * matches of five bytes or fewer, as zlib's filtered strategy does, which on filtered scanlines is
 * both faster and smaller.
 */
public final class PngEncoder {
    /**
     * The largest {@code IDAT} chunk written.
     */
    private static final int IDAT_SIZE = 64 * 1024;

    /**
     * The bytes of the {@code IHDR}, {@code IDAT} (per chunk) and {@code IEND} framing counted
     * when comparing the indexed and truecolor sizes.
     */
    private static final int CHUNK_OVERHEAD = 12;

    /**
     * The filtered bytes of one independently compressed band of scanlines, about 1 MiB.
     */
    private static final int BAND_BYTES = 1 << 20;

    /**
     * The deflate window, which primes the compression of each band.
     */
    private static final int WINDOW = 32768;

    /**
     * Prevents instantiation.
     */
    private PngEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture as a PNG datastream with the {@link PngEncoderOptions#defaults()}.
     *
     * @param picture the pixels to encode
     * @return the complete PNG datastream
     * @throws MediaException.Unsupported if a planar picture's color matrix has no conversion to
     * R'G'B'
     * @throws MediaException.LimitExceeded if the converted picture would exceed the default
     * limits, encoding runs out of memory, or the datastream would exceed the largest Java array
     * @see #encode(Picture, PngEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, PngEncoderOptions.defaults());
    }

    /**
     * Encodes a picture as a PNG datastream and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, PngEncoderOptions)} returns.
     *
     * @param picture the pixels to encode
     * @param options the compression level, filter selection and color profile
     * @param sink the output, written from its position and not closed
     * @throws MediaException.Unsupported if a planar picture's color matrix has no conversion to
     * R'G'B'
     * @throws MediaException.LimitExceeded if the converted picture would exceed the default
     * limits, encoding runs out of memory, or the datastream would exceed the largest Java array
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, PngEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture as a PNG datastream.
     *
     * <p>An {@link RgbaPicture} is encoded as it is. Any other picture is first converted with
     * {@link ImageOps#toRgba(Picture)}: a {@link YuvPicture} or {@link Yuv16Picture} through its
     * color matrix, a {@link Rgba16Picture} reduced to 8 bits.
     *
     * @param picture the pixels to encode
     * @param options the compression level, filter selection and color profile
     * @return the complete PNG datastream
     * @throws MediaException.Unsupported if a planar picture's color matrix has no conversion to
     * R'G'B'
     * @throws MediaException.LimitExceeded if the converted picture would exceed the default
     * limits, encoding runs out of memory, or the datastream would exceed the largest Java array
     */
    public static byte[] encode(Picture picture, PngEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        return Guards.encoding("PNG image",
                () -> encode(picture instanceof RgbaPicture rgba ? rgba : ImageOps.toRgba(picture), options));
    }

    /**
     * Encodes packed RGBA pixels as a PNG datastream.
     *
     * @param picture the pixels to encode
     * @param options the compression level, filter selection and color profile
     * @return the complete PNG datastream
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    private static byte[] encode(RgbaPicture picture, PngEncoderOptions options) {
        try (var _ = ParallelBands.operation()) {
            var opaque = ImageOps.isOpaque(picture);
            var width = picture.width();
            var height = picture.height();
            var palette = PngPalette.of(picture, opaque);
            var trueChannels = opaque ? 3 : 4;
            var trueSize = (long) height * (1 + (long) width * trueChannels);
            var indexed = palette != null
                    && (long) height * (1 + width) + CHUNK_OVERHEAD * 2L + palette.size() * 3L + palette.translucentCount()
                    < trueSize;
            var channels = indexed ? 1 : trueChannels;
            var rowBytes = (long) width * channels;
            if (rowBytes + 1 > Integer.MAX_VALUE - 16) {
                throw new MediaException.LimitExceeded("picture of " + width + " pixels per row is too wide to encode");
            }
            var out = new PngChunkWriter((int) Math.min(Integer.MAX_VALUE - 16, Math.max(1024, trueSize / 4)));
            var profile = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
            out.header(width, height, indexed ? PngHeader.PALETTE : opaque ? PngHeader.RGB : PngHeader.RGBA,
                    indexed ? palette : null, profile);
            writeImageData(picture, options, indexed ? palette : null, channels, (int) rowBytes, out);
            out.end();
            return out.toByteArray();
        }
    }

    /**
     * Filters, compresses and writes the scanlines as {@code IDAT} chunks.
     *
     * @param picture the picture whose scanlines are written
     * @param options the encoder options
     * @param palette the palette of an indexed-color image, or {@code null}
     * @param channels the bytes per pixel of the output: 1, 3 or 4
     * @param rowBytes the scanline length in bytes
     * @param out the datastream being written
     */
    private static void writeImageData(RgbaPicture picture, PngEncoderOptions options, PngPalette palette,
                                       int channels, int rowBytes, PngChunkWriter out) {
        var height = picture.height();
        var stride = rowBytes + 1;
        var bandRows = (int) Math.max(1, Math.min(height, BAND_BYTES / stride));
        var bands = (height + bandRows - 1) / bandRows;
        var level = options.compressionLevel();
        var filter = options.filter();
        var type = PngFilters.type(filter);
        var filtered = type < 0 ? palette == null : type != 0;
        var parts = new Band[bands];
        ParallelBands.run(bands, band -> {
            var first = band * bandRows;
            var end = Math.min(height, first + bandRows);
            parts[band] = encodeBand(picture, options, palette, channels, rowBytes, first, end, level, filtered);
        });
        var adler = parts[0].adler();
        for (var band = 1; band < bands; band++) {
            var rows = Math.min(height, (band + 1) * bandRows) - band * bandRows;
            adler = combineAdler(adler, parts[band].adler(), (long) rows * stride);
        }
        var flags = level < 2 ? 0 : level < 6 ? 1 : level == 6 ? 2 : 3;
        var header = (0x78 << 8) | (flags << 6);
        header += 31 - header % 31;
        var idat = new byte[IDAT_SIZE];
        var length = 0;
        var trailer = new byte[4];
        ByteArrays.put32be(trailer, 0, adler);
        var pieces = new byte[bands + 2][];
        pieces[0] = new byte[]{(byte) (header >>> 8), (byte) header};
        for (var band = 0; band < bands; band++) {
            pieces[band + 1] = parts[band].data();
        }
        pieces[bands + 1] = trailer;
        for (var piece : pieces) {
            var offset = 0;
            while (offset < piece.length) {
                var count = Math.min(piece.length - offset, IDAT_SIZE - length);
                System.arraycopy(piece, offset, idat, length, count);
                offset += count;
                length += count;
                if (length == IDAT_SIZE) {
                    out.chunk(PngChunks.IDAT, idat, 0, length);
                    length = 0;
                }
            }
        }
        if (length > 0) {
            out.chunk(PngChunks.IDAT, idat, 0, length);
        }
    }

    /**
     * Combines the Adler-32 checksums of two consecutive byte runs.
     *
     * @implNote This implementation follows zlib's {@code adler32_combine}.
     *
     * @param first the checksum of the first run
     * @param second the checksum of the second run
     * @param length the length of the second run in bytes
     * @return the checksum of the two runs together
     */
    private static int combineAdler(int first, int second, long length) {
        final long base = 65521;
        var rem = length % base;
        var sum1 = first & 0xFFFFL;
        var sum2 = (rem * sum1) % base;
        sum1 += (second & 0xFFFFL) + base - 1;
        sum2 += ((first >>> 16) & 0xFFFFL) + ((second >>> 16) & 0xFFFFL) + base - rem;
        if (sum1 >= base) {
            sum1 -= base;
        }
        if (sum1 >= base) {
            sum1 -= base;
        }
        if (sum2 >= (base << 1)) {
            sum2 -= base << 1;
        }
        if (sum2 >= base) {
            sum2 -= base;
        }
        return (int) (sum1 | (sum2 << 16));
    }

    /**
     * Holds the compressed bytes and the checksum of one band of scanlines.
     *
     * @param data the raw deflate bytes
     * @param adler the Adler-32 checksum of the band's filtered scanlines
     */
    private record Band(byte[] data, int adler) {
    }

    /**
     * Filters the scanlines of a band row by row, choosing each row's filter type.
     */
    private static final class RowFilter {
        /**
         * The filter kernel.
         */
        private final PngFilterKernel kernel = PngFilterKernel.INSTANCE;

        /**
         * The picture.
         */
        private final RgbaPicture picture;

        /**
         * The palette of an indexed image, or {@code null}.
         */
        private final PngPalette palette;

        /**
         * The bytes per pixel of the output.
         */
        private final int channels;

        /**
         * The scanline length in bytes.
         */
        private final int rowBytes;

        /**
         * Whether each filter type is tried and the cheapest kept.
         */
        private final boolean adaptive;

        /**
         * The fixed filter type when not adaptive.
         */
        private final int fixedType;

        /**
         * Whether the picture's pixels are the scanline bytes.
         */
        private final boolean direct;

        /**
         * The filtered candidates of the current scanline, type byte first.
         */
        private final byte[][] candidates;

        /**
         * The all-zero prior scanline of the first row.
         */
        private final byte[] zero;

        /**
         * The converted current scanline of a non-direct picture.
         */
        private byte[] current;

        /**
         * The converted prior scanline of a non-direct picture.
         */
        private byte[] previous;

        /**
         * Creates a filter for a picture.
         *
         * @param picture the picture whose scanlines are filtered
         * @param options the encoder options
         * @param palette the palette of an indexed image, or {@code null}
         * @param channels the bytes per pixel of the output
         * @param rowBytes the scanline length in bytes
         */
        private RowFilter(RgbaPicture picture, PngEncoderOptions options, PngPalette palette, int channels,
                          int rowBytes) {
            var filter = options.filter();
            this.picture = picture;
            this.palette = palette;
            this.channels = channels;
            this.rowBytes = rowBytes;
            this.adaptive = filter == PngEncoderOptions.Filter.ADAPTIVE && palette == null;
            this.fixedType = Math.max(0, PngFilters.type(filter));
            this.direct = channels == 4;
            this.candidates = new byte[adaptive ? PngFilters.TYPES : 1][rowBytes + 1];
            this.zero = new byte[rowBytes];
            this.current = direct ? null : new byte[rowBytes];
            this.previous = direct ? null : new byte[rowBytes];
        }

        /**
         * Converts a scanline of a non-direct picture.
         *
         * @param y the row
         * @param target the destination
         */
        private void convert(int y, byte[] target) {
            if (palette != null) {
                toIndices(picture.pixels(), y * picture.stride(), picture.width(), palette, target);
            } else {
                toRgb(picture.pixels(), y * picture.stride(), picture.width(), target);
            }
        }

        /**
         * Prepares for filtering from a row on, converting the row before it as the prior row.
         *
         * @param y the first row to filter
         */
        private void start(int y) {
            if (!direct && y > 0) {
                convert(y - 1, previous);
            }
        }

        /**
         * Filters a row, which must follow the previous call or {@link #start(int)}.
         *
         * @param y the row
         * @return the filtered row, type byte first, valid until the next call
         */
        private byte[] next(int y) {
            byte[] row;
            int rowOffset;
            byte[] prior;
            int priorOffset;
            var stride = picture.stride();
            if (direct) {
                row = picture.pixels();
                rowOffset = y * stride;
                prior = y == 0 ? zero : row;
                priorOffset = y == 0 ? 0 : rowOffset - stride;
            } else {
                convert(y, current);
                row = current;
                rowOffset = 0;
                prior = y == 0 ? zero : previous;
                priorOffset = 0;
            }
            byte[] chosen;
            if (adaptive) {
                chosen = PngFilters.filterAdaptive(kernel, row, rowOffset, prior, priorOffset, rowBytes, channels,
                        candidates);
            } else {
                PngFilters.filter(kernel, fixedType, row, rowOffset, prior, priorOffset, rowBytes, channels, candidates[0]);
                chosen = candidates[0];
            }
            if (!direct) {
                var swap = previous;
                previous = current;
                current = swap;
            }
            return chosen;
        }
    }

    /**
     * Filters and compresses the scanlines of one band into a raw deflate segment that ends on a
     * byte boundary (or, for the last band, ends the stream), primed with the 32 KiB of filtered
     * data before the band so that the segments join into one deflate stream.
     *
     * @param picture the picture whose scanlines are compressed
     * @param options the encoder options
     * @param palette the palette of an indexed image, or {@code null}
     * @param channels the bytes per pixel of the output
     * @param rowBytes the scanline length in bytes
     * @param first the first row of the band
     * @param end the row after the last of the band
     * @param level the compression level, from 0 to 9
     * @param filtered whether matches of five bytes or fewer are discarded
     * @return the compressed band and the checksum of its filtered scanlines
     */
    private static Band encodeBand(RgbaPicture picture, PngEncoderOptions options, PngPalette palette, int channels,
                                   int rowBytes, int first, int end, int level, boolean filtered) {
        var stride = rowBytes + 1;
        var filter = new RowFilter(picture, options, palette, channels, rowBytes);
        var used = 0;
        byte[] dictionary = null;
        if (first > 0) {
            var rows = Math.min(first, (WINDOW + stride - 1) / stride);
            dictionary = new byte[rows * stride];
            filter.start(first - rows);
            for (var i = 0; i < rows; i++) {
                System.arraycopy(filter.next(first - rows + i), 0, dictionary, i * stride, stride);
            }
            used = Math.min(WINDOW, dictionary.length);
        } else {
            filter.start(0);
        }
        var length = (end - first) * stride;
        var buffer = new byte[used + length + 16];
        if (used > 0) {
            System.arraycopy(dictionary, dictionary.length - used, buffer, 0, used);
        }
        for (var y = first; y < end; y++) {
            System.arraycopy(filter.next(y), 0, buffer, used + (y - first) * stride, stride);
        }
        var adler = new Adler32();
        adler.update(buffer, used, length);
        return new Band(ZlibDeflater.segment(buffer, used, length, level, filtered, end == picture.height()),
                (int) adler.getValue());
    }

    /**
     * Converts a row of RGBA pixels to RGB samples.
     *
     * @param pixels the picture samples
     * @param offset the index of the row's first pixel
     * @param width the number of pixels
     * @param target the destination, {@code width * 3} bytes
     */
    private static void toRgb(byte[] pixels, int offset, int width, byte[] target) {
        for (var x = 0; x < width; x++) {
            var s = offset + x * 4;
            var t = x * 3;
            target[t] = pixels[s];
            target[t + 1] = pixels[s + 1];
            target[t + 2] = pixels[s + 2];
        }
    }

    /**
     * Converts a row of RGBA pixels to palette indices.
     *
     * @param pixels the picture samples
     * @param offset the index of the row's first pixel
     * @param width the number of pixels
     * @param palette the ordered palette holding every color of the row
     * @param target the destination, {@code width} bytes
     */
    private static void toIndices(byte[] pixels, int offset, int width, PngPalette palette, byte[] target) {
        var opaque = palette.translucentCount() == 0 ? 0xFF00_0000 : 0;
        var lastColor = 0;
        var lastIndex = -1;
        for (var x = 0; x < width; x++) {
            var color = ByteArrays.i32le(pixels, offset + x * 4) | opaque;
            if (color != lastColor || lastIndex < 0) {
                lastColor = color;
                lastIndex = palette.indexOf(color);
            }
            target[x] = (byte) lastIndex;
        }
    }
}
