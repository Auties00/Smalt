package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Arrays;
import java.util.Objects;
import java.util.zip.Adler32;

/**
 * Encodes pictures as PNG datastreams.
 *
 * <p>The color type and bit depth are chosen from the pixels, losslessly:
 * <ul>
 *   <li>an {@link RgbaPicture} becomes indexed color ({@code PLTE}, plus {@code tRNS} when some
 *       color is translucent) of 1, 2, 4 or 8 bits when it has at most 256 distinct RGBA colors,
 *       grayscale of 1, 2, 4 or 8 bits when its red, green and blue samples are equal (a depth
 *       below 8 when every gray level is a multiple of {@code 255 / (2^depth - 1)}), grayscale
 *       with alpha, truecolor, or truecolor with alpha;</li>
 *   <li>an {@link Rgba16Picture} keeps 16 bits per sample, as grayscale, grayscale with alpha,
 *       truecolor or truecolor with alpha;</li>
 *   <li>a format without an alpha channel is used when every pixel is opaque, or when every
 *       pixel is either opaque or fully transparent, every transparent pixel has the same color
 *       and no opaque pixel has it, which a {@code tRNS} key chunk then marks;</li>
 *   <li>among the eligible formats the one whose scanlines and format chunks take the fewest
 *       bytes is written; ties go to grayscale, then truecolor, then indexed color.</li>
 * </ul>
 * Planar and channel pictures are first converted to one of the two, as
 * {@link #encode(Picture, PngEncoderOptions)} describes. An ICC profile in
 * {@link PngEncoderOptions#colorProfile()} restricts the choice: an RGB profile excludes grayscale
 * and a gray profile requires it; a high-dynamic-range profile does not. A picture whose
 * {@code hasAlpha()} is {@code false} is treated as opaque: its alpha samples are ignored. Every
 * other {@link RgbaPicture} or {@link Rgba16Picture} round-trips exactly through
 * {@link PngDecoder}, including the color of fully transparent pixels, a 16-bit one when decoded with
 * {@link com.github.auties00.smalt.image.ImageDecoderOptions#highBitDepth()}.
 *
 * <p>After {@code IHDR} the datastream holds, in this order and only when the options or the
 * format ask for them: the color space chunks of {@link PngEncoderOptions#colorProfile()}
 * ({@code cICP}, {@code mDCV} and {@code cLLI} for a high-dynamic-range profile, then {@code iCCP}),
 * {@link PngEncoderOptions#srgbIntent()}, {@link PngEncoderOptions#gamma()} and
 * {@link PngEncoderOptions#chromaticities()}; {@code sBIT}; {@code pHYs}; {@code PLTE} and
 * {@code tRNS}; then the {@code IDAT} chunks and {@code IEND}. The scanlines are stored in the
 * Adam7 order when {@link PngEncoderOptions#isInterlaced()} is set, filtered as
 * {@link PngEncoderOptions#filter()} selects and compressed into one zlib stream at
 * {@link PngEncoderOptions#compressionLevel()}.
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
     * The filtered bytes of one independently compressed band of scanlines, about 1 MiB.
     */
    private static final int BAND_BYTES = 1 << 20;

    /**
     * The deflate window, which primes the compression of each band.
     */
    private static final int WINDOW = 32768;

    /**
     * The Adam7 passes (PNG 3rd edition 8.2) as first column, first row, column step and row step.
     */
    private static final int[][] ADAM7 = {
            {0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}
    };

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
     * @param options the format and compression settings
     * @param sink the output, written from its position and not closed
     * @throws IllegalArgumentException if the options hold a gray ICC profile and the picture's
     * red, green and blue samples are not all equal
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
     * <p>An {@link RgbaPicture} is encoded with 8 bits per sample or fewer and an
     * {@link Rgba16Picture} with 16. A {@link ChannelPicture} of 16-bit or floating-point samples and a
     * {@link Yuv16Picture} deeper than 8 bits are first converted with
     * {@link ImageOps#toRgba16(Picture)}, so they are written with 16 bits per sample (a
     * {@link Yuv16Picture} through its color matrix, with its samples rescaled to 16 bits). Any other
     * picture, a {@link YuvPicture} or an 8-bit {@link Yuv16Picture} among them, is first converted
     * with {@link ImageOps#toRgba(Picture)}, to 8 bits.
     *
     * @param picture the pixels to encode
     * @param options the format and compression settings
     * @return the complete PNG datastream
     * @throws IllegalArgumentException if the options hold a gray ICC profile and the picture's
     * red, green and blue samples are not all equal
     * @throws MediaException.LimitExceeded if the converted picture would exceed the default
     * limits, encoding runs out of memory, or the datastream would exceed the largest Java array
     */
    public static byte[] encode(Picture picture, PngEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        return Guards.encoding("PNG image", () -> {
            try (var _ = ParallelBands.operation()) {
                var profile = options.colorProfile().orElse(null);
                var layout = switch (picture) {
                    case RgbaPicture rgba -> PngLayout.of(rgba, profile);
                    case Rgba16Picture wide -> PngLayout.of(wide, profile);
                    case ChannelPicture channels when channels.bitDepth() > 8 ->
                            PngLayout.of(ImageOps.toRgba16(channels), profile);
                    case Yuv16Picture yuv when yuv.bitDepth() > 8 -> PngLayout.of(ImageOps.toRgba16(yuv), profile);
                    default -> PngLayout.of(ImageOps.toRgba(picture), profile);
                };
                return encode(layout, picture.width(), picture.height(), options);
            }
        });
    }

    /**
     * Encodes a picture in a chosen format.
     *
     * @param layout the format and the picture
     * @param width the picture width
     * @param height the picture height
     * @param options the encoder options
     * @return the complete PNG datastream
     * @throws MediaException.LimitExceeded if the datastream would exceed the largest Java array
     */
    private static byte[] encode(PngLayout layout, int width, int height, PngEncoderOptions options) {
        var scan = new Scan(layout, width, height, options.isInterlaced());
        var out = new PngChunkWriter((int) Math.min(Integer.MAX_VALUE - 16, Math.max(1024, scan.totalBytes / 4)));
        out.header(width, height, layout.bitDepth(), layout.colorType(), options.isInterlaced());
        out.colorSpace(options.colorProfile().orElse(null), options.srgbIntent().orElse(null),
                options.gamma().isPresent() ? options.gamma().getAsDouble() : null,
                options.chromaticities().orElse(null));
        if (options.significantBits().isPresent()) {
            out.significantBits(layout.significantBits(options.significantBits().getAsInt()));
        }
        options.density().ifPresent(out::physicalDimensions);
        if (layout.palette() != null) {
            out.palette(layout.palette());
        } else if (layout.key() != null) {
            out.transparencyKey(layout.key());
        }
        writeImageData(layout, scan, options, out);
        out.end();
        return out.toByteArray();
    }

    /**
     * Filters, compresses and writes the scanlines as {@code IDAT} chunks.
     *
     * @param layout the format and the picture
     * @param scan the order of the scanlines
     * @param options the encoder options
     * @param out the datastream being written
     */
    private static void writeImageData(PngLayout layout, Scan scan, PngEncoderOptions options, PngChunkWriter out) {
        var starts = scan.bands();
        var bands = starts.length - 1;
        var level = options.compressionLevel();
        var type = PngFilters.type(options.filter());
        var adaptive = type < 0 && layout.filtersAdaptively();
        var fixedType = Math.max(0, type);
        var parts = new Band[bands];
        ParallelBands.run(bands, band -> parts[band] = encodeBand(layout.copy(), scan, adaptive, fixedType,
                starts[band], starts[band + 1], level, adaptive || fixedType != 0));
        var adler = parts[0].adler();
        for (var band = 1; band < bands; band++) {
            adler = combineAdler(adler, parts[band].adler(), scan.bytes(starts[band], starts[band + 1]));
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
     * Lists the scanlines of the image data in stored order: the rows of the image, or the rows
     * of each non-empty Adam7 pass in turn. A scanline is addressed by its index in that order.
     */
    private static final class Scan {
        /**
         * The format and the picture.
         */
        private final PngLayout layout;

        /**
         * The number of non-empty passes, 1 without interlacing.
         */
        private final int passes;

        /**
         * The index of the first scanline of each pass, and the total count after the last.
         */
        private final int[] first;

        /**
         * The first column of each pass.
         */
        private final int[] x0;

        /**
         * The first row of each pass.
         */
        private final int[] y0;

        /**
         * The column step of each pass.
         */
        private final int[] dx;

        /**
         * The row step of each pass.
         */
        private final int[] dy;

        /**
         * The pixels per scanline of each pass.
         */
        private final int[] widths;

        /**
         * The scanline length of each pass, filter type byte included.
         */
        private final int[] strides;

        /**
         * The longest scanline, filter type byte excluded.
         */
        private final int maxRowBytes;

        /**
         * The filtered bytes of all scanlines.
         */
        private final long totalBytes;

        /**
         * Lists the scanlines of an image.
         *
         * @param layout the format and the picture
         * @param width the image width
         * @param height the image height
         * @param interlaced whether the Adam7 order is used
         * @throws MediaException.LimitExceeded if a scanline is too long to encode
         */
        private Scan(PngLayout layout, int width, int height, boolean interlaced) {
            this.layout = layout;
            var geometry = interlaced ? ADAM7 : new int[][]{{0, 0, 1, 1}};
            first = new int[geometry.length + 1];
            x0 = new int[geometry.length];
            y0 = new int[geometry.length];
            dx = new int[geometry.length];
            dy = new int[geometry.length];
            widths = new int[geometry.length];
            strides = new int[geometry.length];
            var count = 0;
            var total = 0L;
            var longest = 0L;
            var used = 0;
            for (var pass : geometry) {
                var w = (width - pass[0] + pass[2] - 1) / pass[2];
                var h = (height - pass[1] + pass[3] - 1) / pass[3];
                if (w <= 0 || h <= 0) {
                    continue;
                }
                var rowBytes = layout.rowBytes(w);
                if (rowBytes + 1 > Integer.MAX_VALUE - 16 - WINDOW) {
                    throw new MediaException.LimitExceeded("picture of " + width + " pixels per row is too wide to encode");
                }
                longest = Math.max(longest, rowBytes);
                first[used] = count;
                x0[used] = pass[0];
                y0[used] = pass[1];
                dx[used] = pass[2];
                dy[used] = pass[3];
                widths[used] = w;
                strides[used] = (int) rowBytes + 1;
                count += h;
                total += (long) h * (rowBytes + 1);
                used++;
            }
            first[used] = count;
            passes = used;
            maxRowBytes = (int) longest;
            totalBytes = total;
        }

        /**
         * {@return the pass of a scanline}
         *
         * @param k the scanline index
         */
        private int pass(int k) {
            var p = 0;
            while (k >= first[p + 1]) {
                p++;
            }
            return p;
        }

        /**
         * {@return the filtered bytes of a run of scanlines}
         *
         * @param from the first scanline
         * @param to the scanline after the last
         */
        private long bytes(int from, int to) {
            var total = 0L;
            for (var p = 0; p < passes; p++) {
                var lo = Math.max(from, first[p]);
                var hi = Math.min(to, first[p + 1]);
                if (hi > lo) {
                    total += (long) (hi - lo) * strides[p];
                }
            }
            return total;
        }

        /**
         * Splits the scanlines into bands of at most {@link #BAND_BYTES} filtered bytes, or of one
         * scanline when a scanline is longer.
         *
         * @return the first scanline of each band, then the scanline count
         */
        private int[] bands() {
            var count = first[passes];
            var starts = new int[count + 1];
            var bands = 0;
            var filled = 0L;
            for (var k = 0; k < count; k++) {
                var stride = strides[pass(k)];
                if (k == 0 || filled + stride > BAND_BYTES) {
                    starts[bands++] = k;
                    filled = 0;
                }
                filled += stride;
            }
            starts[bands] = count;
            return Arrays.copyOf(starts, bands + 1);
        }
    }

    /**
     * Filters scanlines one after the other, choosing each one's filter type.
     */
    private static final class RowFilter {
        /**
         * The filter kernel.
         */
        private final PngFilterKernel kernel = PngFilterKernel.INSTANCE;

        /**
         * The format and the picture, owned by this filter.
         */
        private final PngLayout layout;

        /**
         * The order of the scanlines.
         */
        private final Scan scan;

        /**
         * The 8-bit picture whose rows are the scanlines, or {@code null}.
         */
        private final RgbaPicture direct;

        /**
         * Whether each filter type is tried and the cheapest kept.
         */
        private final boolean adaptive;

        /**
         * The fixed filter type when not adaptive.
         */
        private final int fixedType;

        /**
         * The filter distance in bytes.
         */
        private final int distance;

        /**
         * The filtered candidates of the current scanline, type byte first.
         */
        private final byte[][] candidates;

        /**
         * The all-zero prior scanline of the first row of a pass.
         */
        private final byte[] zero;

        /**
         * The packed current scanline of a non-direct picture.
         */
        private byte[] current;

        /**
         * The packed prior scanline of a non-direct picture.
         */
        private byte[] previous;

        /**
         * Creates a filter.
         *
         * @param layout the format and the picture, owned by the filter
         * @param scan the order of the scanlines
         * @param picture the 8-bit picture whose rows are the scanlines, or {@code null}
         * @param adaptive whether each filter type is tried
         * @param fixedType the filter type when not adaptive
         */
        private RowFilter(PngLayout layout, Scan scan, RgbaPicture picture, boolean adaptive, int fixedType) {
            this.layout = layout;
            this.scan = scan;
            this.direct = picture;
            this.adaptive = adaptive;
            this.fixedType = fixedType;
            this.distance = layout.distance();
            this.candidates = new byte[adaptive ? PngFilters.TYPES : 1][scan.maxRowBytes + 1];
            this.zero = new byte[scan.maxRowBytes];
            this.current = direct != null ? null : new byte[scan.maxRowBytes];
            this.previous = direct != null ? null : new byte[scan.maxRowBytes];
        }

        /**
         * Packs a scanline of a non-direct picture.
         *
         * @param p the pass of the scanline
         * @param k the scanline index
         * @param target the destination
         */
        private void pack(int p, int k, byte[] target) {
            var y = scan.y0[p] + (k - scan.first[p]) * scan.dy[p];
            layout.pack(y, scan.x0[p], scan.dx[p], scan.widths[p], target);
        }

        /**
         * Prepares for filtering from a scanline on, packing the one before it as the prior
         * scanline when it belongs to the same pass.
         *
         * @param k the first scanline to filter
         */
        private void start(int k) {
            var p = scan.pass(k);
            if (direct == null && k > scan.first[p]) {
                pack(p, k - 1, previous);
            }
        }

        /**
         * Filters a scanline, which must follow the previous call or {@link #start(int)}.
         *
         * @param k the scanline index
         * @return the filtered scanline, type byte first, valid until the next call
         */
        private byte[] next(int k) {
            var p = scan.pass(k);
            var top = k == scan.first[p];
            var rowBytes = scan.strides[p] - 1;
            byte[] row;
            int rowOffset;
            byte[] prior;
            int priorOffset;
            if (direct != null) {
                var stride = direct.stride();
                row = direct.pixels();
                rowOffset = k * stride;
                prior = top ? zero : row;
                priorOffset = top ? 0 : rowOffset - stride;
            } else {
                pack(p, k, current);
                row = current;
                rowOffset = 0;
                prior = top ? zero : previous;
                priorOffset = 0;
            }
            byte[] chosen;
            if (adaptive) {
                chosen = PngFilters.filterAdaptive(kernel, row, rowOffset, prior, priorOffset, rowBytes, distance,
                        candidates);
            } else {
                PngFilters.filter(kernel, fixedType, row, rowOffset, prior, priorOffset, rowBytes, distance,
                        candidates[0]);
                chosen = candidates[0];
            }
            if (direct == null) {
                var swap = previous;
                previous = current;
                current = swap;
            }
            return chosen;
        }
    }

    /**
     * Filters and compresses a band of scanlines into a raw deflate segment that ends on a byte
     * boundary (or, for the last band, ends the stream), primed with the 32 KiB of filtered data
     * before the band so that the segments join into one deflate stream.
     *
     * @param layout the format and the picture, owned by this call
     * @param scan the order of the scanlines
     * @param adaptive whether each filter type is tried
     * @param fixedType the filter type when not adaptive
     * @param from the first scanline of the band
     * @param to the scanline after the last of the band
     * @param level the compression level, from 0 to 9
     * @param filtered whether matches of five bytes or fewer are discarded
     * @return the compressed band and the checksum of its filtered scanlines
     */
    private static Band encodeBand(PngLayout layout, Scan scan, boolean adaptive, int fixedType, int from, int to,
                                   int level, boolean filtered) {
        var picture = layout.direct() && scan.passes == 1 && scan.dx[0] == 1 ? layout.directPicture() : null;
        var filter = new RowFilter(layout, scan, picture, adaptive, fixedType);
        var back = from;
        var collected = 0L;
        while (back > 0 && collected < WINDOW) {
            back--;
            collected += scan.strides[scan.pass(back)];
        }
        var used = (int) Math.min(WINDOW, collected);
        var length = (int) scan.bytes(from, to);
        var buffer = new byte[used + length + 16];
        filter.start(back);
        var skip = collected - used;
        var position = 0;
        for (var k = back; k < from; k++) {
            var row = filter.next(k);
            var stride = scan.strides[scan.pass(k)];
            var start = (int) Math.min(stride, skip);
            skip -= start;
            System.arraycopy(row, start, buffer, position, stride - start);
            position += stride - start;
        }
        for (var k = from; k < to; k++) {
            var row = filter.next(k);
            var stride = scan.strides[scan.pass(k)];
            System.arraycopy(row, 0, buffer, position, stride);
            position += stride;
        }
        var adler = new Adler32();
        adler.update(buffer, used, length);
        return new Band(ZlibDeflater.segment(buffer, used, length, level, filtered, to == scan.first[scan.passes]),
                (int) adler.getValue());
    }
}
