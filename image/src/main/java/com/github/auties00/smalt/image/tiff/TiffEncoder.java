package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifValue;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.internal.zstd.ZstdEncoder;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.jpeg.internal.JpegWriter;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ImageQuantization;
import com.github.auties00.smalt.image.ops.QuantizeOptions;
import com.github.auties00.smalt.image.tiff.internal.CcittFaxEncoder;
import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.image.tiff.internal.LzwEncoder;
import com.github.auties00.smalt.image.tiff.internal.PackBits;
import com.github.auties00.smalt.image.tiff.internal.Predictor;
import com.github.auties00.smalt.image.tiff.internal.TiffExifWriter;
import com.github.auties00.smalt.image.tiff.internal.TiffTags;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes pictures as TIFF or BigTIFF files, one page per picture.
 *
 * <p>Each page is written with the samples {@link TiffEncoderOptions#layout()} and
 * {@link TiffEncoderOptions#depth()} select: gray, RGB or CMYK, with or without an unassociated
 * alpha sample, at 8 or 16 bits or as 32-bit floating point; palette indexes of 1 to 8 bits with a
 * color map; bilevel pixels of one bit; or Y'CbCr, subsampled or not. Samples are interleaved
 * ({@code PlanarConfiguration} 1) or, with {@link TiffEncoderOptions#isPlanar()}, in separate
 * planes, in strips or tiles, compressed with no compression, PackBits, LZW, Deflate, Zstandard,
 * the CCITT fax codings (bilevel pages), JPEG (TIFF Technical Note 2), WebP or JPEG 2000, as
 * {@link TiffEncoderOptions#compression()} describes, and, for LZW, Deflate and Zstandard, the
 * horizontal or floating-point predictor. Every page records its pixel density (72 dots per inch
 * unless the options or the EXIF metadata give one), the orientation when one is given, and the
 * ICC profile, EXIF metadata, XMP packet and IPTC data the options carry. Y'CbCr pictures are
 * converted to RGB first, except under the {@link TiffEncoderOptions.Layout#YCBCR} layout.
 *
 * <p>A {@link ChannelPicture} is written with its own channels under the
 * {@link TiffEncoderOptions.Layout#AUTO} layout, and under a layout naming exactly its channels
 * ({@link TiffEncoderOptions.Layout#CMYK} for a CMYK picture, for example): gray (black-is-zero),
 * RGB, CMYK (separated, {@code InkSet} 1) or named inks (separated, {@code InkSet} 2 with
 * {@code NumberOfInks} and, when
 * any ink is named, {@code InkNames}, each name up to its first NUL character), each extra channel
 * an extra sample (associated alpha, unassociated alpha, or unspecified). Its samples are written as
 * stored: 8 or 16-bit integers, or 32-bit IEEE floating point ({@code SampleFormat} 3) for a
 * {@link ChannelFloatPicture}; {@link TiffEncoderOptions.Depth#EIGHT},
 * {@link TiffEncoderOptions.Depth#SIXTEEN} and {@link TiffEncoderOptions.Depth#FLOAT} convert them
 * first, as {@link ImageOps#toChannel8(Picture)}, {@link ImageOps#toChannel16(Picture)} and
 * {@link ImageOps#toChannelFloat(Picture)} do. Under WebP or JPEG compression it is converted to
 * RGBA first; otherwise, with lossless settings, decoding such a page with
 * {@link com.github.auties00.smalt.image.ImageDecoderOptions#keepChannels()} (and
 * {@link com.github.auties00.smalt.image.ImageDecoderOptions#highBitDepth()} for 16-bit samples)
 * returns the same picture, sample for sample.
 *
 * <p>Pages are streamed: {@link #addPage(Picture)} compresses a page and writes it, keeping only
 * the directory of the previous page until the next page or {@link #finish()} fixes its link, so a
 * sink that cannot seek works and memory use does not grow with the page count. Strips and tiles
 * are compressed in parallel on the shared pool; the output does not depend on the thread count.
 * CCITT data is byte-identical to libtiff's for the same pixels and options.
 *
 * <p>An encoder is not thread-safe. The static methods are thread-safe.
 *
 * @implNote This implementation's LZW coder follows libtiff's code-width schedule (see
 * {@code LzwEncoder}); the Deflate streams are zlib streams from {@link ZlibDeflater}, as libtiff
 * writes them under compression 8. The pixels of a partial WebP, JPEG or JPEG 2000 tile beyond the
 * image repeat its last column and row, which keeps lossy coding from ringing at the image edges.
 */
public final class TiffEncoder {
    /**
     * The target size of an automatic strip, in bytes.
     */
    private static final int STRIP_BYTES = 64 * 1024;

    /**
     * The largest side of a WebP image, which bounds the height of an automatic WebP strip.
     */
    private static final int WEBP_MAX_SIDE = 16383;

    /**
     * The 8x8 Bayer threshold matrix of ordered bilevel dithering, values 0 to 63.
     */
    private static final int[] BAYER = {
            0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21
    };

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
     * The byte order and the BigTIFF choice of {@code options} apply to the whole file.
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
     * @throws IllegalArgumentException if the compression is {@link TiffCompression#JPEG_2000} and
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point, or a
     * strip is wider or taller than 65535 pixels under {@link TiffCompression#JPEG}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * @throws IllegalArgumentException if the compression is {@link TiffCompression#JPEG_2000} and
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point, or a
     * strip is wider or taller than 65535 pixels under {@link TiffCompression#JPEG}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * @throws IllegalArgumentException if {@code pictures} is empty, the compression is
     * {@link TiffCompression#JPEG_2000} and a picture is a {@link ChannelFloatPicture} that the
     * depth option keeps floating point, or a strip is wider or taller than 65535 pixels under
     * {@link TiffCompression#JPEG}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * @throws IllegalArgumentException if {@code pictures} is empty, the compression is
     * {@link TiffCompression#JPEG_2000} and a picture is a {@link ChannelFloatPicture} that the
     * depth option keeps floating point, or a strip is wider or taller than 65535 pixels under
     * {@link TiffCompression#JPEG}
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * Compresses a picture and writes it as the next page, with the encoder's options.
     *
     * @param picture the picture to add as the next page
     * @throws NullPointerException if {@code picture} is {@code null}
     * @throws IllegalArgumentException if the compression is {@link TiffCompression#JPEG_2000} and
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point, or a
     * strip is wider or taller than 65535 pixels under {@link TiffCompression#JPEG}
     * @throws IllegalStateException if {@link #finish()} already ran
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
     * @throws MediaException.Io if writing the sink fails
     */
    public void addPage(Picture picture) {
        addPage(picture, options);
    }

    /**
     * Compresses a picture and writes it as the next page, with settings of its own.
     *
     * <p>Every setting of {@code pageOptions} applies to the page except the byte order and the
     * BigTIFF choice, which are those of the encoder's options; so pages of one file can differ in
     * compression, layout and metadata.
     *
     * @param picture the picture to add as the next page
     * @param pageOptions the settings of this page
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the compression is {@link TiffCompression#JPEG_2000} and
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point, or a
     * strip is wider or taller than 65535 pixels under {@link TiffCompression#JPEG}
     * @throws IllegalStateException if {@link #finish()} already ran
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
     * @throws MediaException.Io if writing the sink fails
     */
    public void addPage(Picture picture, TiffEncoderOptions pageOptions) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(pageOptions, "pageOptions");
        if (finished) {
            throw new IllegalStateException("the TIFF file is finished");
        }
        try (var _ = ParallelBands.operation()) {
            var page = Samples.of(picture, pageOptions, little);
            var chunks = compress(page, pageOptions);
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
            var directory = directory(page, chunks, offsets, counts, pageOptions);
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
     * @throws MediaException.Io if writing the sink fails
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
     * Holds the stored samples of one plane: rows of units, a unit being a pixel of interleaved
     * samples, one sample of a separate plane, or a Y'CbCr data unit.
     *
     * @param data the rows, {@link #rowBytes()} bytes each
     * @param unitsWide the units per row
     * @param unitRows the number of rows of units
     * @param unitBits the bits of one unit
     * @param subsampleX the pixels across one unit
     * @param subsampleY the pixel rows of one unit
     */
    private record Plane(byte[] data, int unitsWide, int unitRows, int unitBits, int subsampleX, int subsampleY) {
        /**
         * {@return the size of one row of units, padded to a byte}
         */
        int rowBytes() {
            return (int) (((long) unitsWide * unitBits + 7) / 8);
        }
    }

    /**
     * Holds the samples of one page, ready to be cut into strips or tiles.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param channels the samples per pixel
     * @param bits the bits per sample: 1, 2, 4, 8, 16 or 32
     * @param floating whether the samples are IEEE floating point
     * @param photometric the {@code PhotometricInterpretation} code
     * @param extras the {@code ExtraSamples} codes, one per extra sample
     * @param inks the number of inks of an ink set other than CMYK, or 0
     * @param inkNames the {@code InkNames} text, or {@code null}
     * @param colorMap the {@code ColorMap} values, or {@code null}
     * @param ycbcr the Y'CbCr description, or {@code null}
     * @param jpeg the chroma layout of the JPEG streams, or {@code null} for another compression
     * @param planes the planes: one of interleaved samples, or one per sample
     */
    private record Samples(int width, int height, int channels, int bits, boolean floating, int photometric,
                           int[] extras, int inks, String inkNames, int[] colorMap, YCbCr ycbcr, ChromaFormat jpeg,
                           Plane[] planes) {
        /**
         * Converts a picture to the samples the options select.
         *
         * @param picture the picture to convert
         * @param options the encoder settings
         * @param little whether multi-byte samples are little-endian
         * @return the samples
         */
        static Samples of(Picture picture, TiffEncoderOptions options, boolean little) {
            var compression = options.compression();
            var layout = options.layout();
            var planar = options.isPlanar();
            if (compression == TiffCompression.WEBP) {
                return channels(packed(ImageOps.toRgba(picture), layout, true), little, false);
            }
            if (compression == TiffCompression.JPEG) {
                return jpeg(picture, options);
            }
            if (layout == TiffEncoderOptions.Layout.BILEVEL || isFax(compression)) {
                return bilevel(picture, options.bilevelDithering());
            }
            if (layout == TiffEncoderOptions.Layout.PALETTE) {
                return palette(picture, options.quantizeOptions());
            }
            if (layout == TiffEncoderOptions.Layout.YCBCR) {
                return ycbcr(picture, options.chromaFormat(), planar);
            }
            var depth = options.depth();
            ChannelPicture source;
            if (picture instanceof ChannelPicture channels && (layout == TiffEncoderOptions.Layout.AUTO
                    || channels.layout().equals(target(layout, true, true)))) {
                source = channels;
            } else {
                var wide = switch (depth) {
                    case AUTO -> picture.bitDepth() > 8;
                    case EIGHT -> false;
                    case SIXTEEN, FLOAT -> true;
                };
                source = wide ? packed16(picture instanceof Rgba16Picture rgba ? rgba : ImageOps.toRgba16(picture), layout)
                        : packed(ImageOps.toRgba(picture), layout, false);
                if (depth == TiffEncoderOptions.Depth.AUTO && picture instanceof ChannelFloatPicture) {
                    depth = TiffEncoderOptions.Depth.FLOAT;
                }
            }
            var converted = switch (depth) {
                case AUTO -> source;
                case EIGHT -> ImageOps.toChannel8(source);
                case SIXTEEN -> ImageOps.toChannel16(source);
                case FLOAT -> ImageOps.toChannelFloat(source);
            };
            var samples = channels(converted, little, planar);
            if (samples.floating() && compression == TiffCompression.JPEG_2000) {
                throw new IllegalArgumentException("JPEG 2000 cannot code floating-point samples");
            }
            return samples;
        }

        /**
         * {@return whether a compression is one of the CCITT fax codings}
         *
         * @param compression the TIFF compression scheme
         */
        static boolean isFax(TiffCompression compression) {
            return compression == TiffCompression.CCITT_RLE || compression == TiffCompression.CCITT_RLEW
                    || compression == TiffCompression.CCITT_T4 || compression == TiffCompression.CCITT_T6;
        }

        /**
         * Builds the 8-bit channel picture of a packed layout.
         *
         * @param picture the pixels
         * @param layout the requested layout
         * @param color whether gray pixels are written as RGB too
         * @return the channel picture
         */
        private static Channel8Picture packed(RgbaPicture picture, TiffEncoderOptions.Layout layout, boolean color) {
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
            var target = target(layout, color || !gray, translucent);
            var count = target.count();
            var out = new byte[Math.multiplyExact(Math.multiplyExact(width, height), count)];
            var cmyk = target.colorModel() == ColorModel.CMYK;
            var colors = target.colorChannels();
            var alpha = count > colors;
            var o = 0;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    var r = pixels[at] & 0xFF;
                    var g = pixels[at + 1] & 0xFF;
                    var b = pixels[at + 2] & 0xFF;
                    if (cmyk) {
                        var k = 255 - Math.max(r, Math.max(g, b));
                        out[o] = (byte) ink(r, k, 255);
                        out[o + 1] = (byte) ink(g, k, 255);
                        out[o + 2] = (byte) ink(b, k, 255);
                        out[o + 3] = (byte) k;
                    } else if (colors == 3) {
                        out[o] = (byte) r;
                        out[o + 1] = (byte) g;
                        out[o + 2] = (byte) b;
                    } else {
                        out[o] = (byte) luma(r, g, b);
                    }
                    if (alpha) {
                        out[o + colors] = pixels[at + 3];
                    }
                    o += count;
                }
            }
            return Channel8Picture.of(width, height, width * count, out, target);
        }

        /**
         * Builds the 16-bit channel picture of a packed layout.
         *
         * @param picture the pixels
         * @param layout the requested layout
         * @return the channel picture
         */
        private static Channel16Picture packed16(Rgba16Picture picture, TiffEncoderOptions.Layout layout) {
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
            var target = target(layout, !gray, translucent);
            var count = target.count();
            var out = new short[Math.multiplyExact(Math.multiplyExact(width, height), count)];
            var cmyk = target.colorModel() == ColorModel.CMYK;
            var colors = target.colorChannels();
            var alpha = count > colors;
            var o = 0;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    var r = pixels[at] & 0xFFFF;
                    var g = pixels[at + 1] & 0xFFFF;
                    var b = pixels[at + 2] & 0xFFFF;
                    if (cmyk) {
                        var k = 65535 - Math.max(r, Math.max(g, b));
                        out[o] = (short) ink(r, k, 65535);
                        out[o + 1] = (short) ink(g, k, 65535);
                        out[o + 2] = (short) ink(b, k, 65535);
                        out[o + 3] = (short) k;
                    } else if (colors == 3) {
                        out[o] = (short) r;
                        out[o + 1] = (short) g;
                        out[o + 2] = (short) b;
                    } else {
                        out[o] = (short) luma(r, g, b);
                    }
                    if (alpha) {
                        out[o + colors] = pixels[at + 3];
                    }
                    o += count;
                }
            }
            return Channel16Picture.of(width, height, width * count, out, target);
        }

        /**
         * Resolves the channels of a packed layout.
         *
         * @param layout the requested layout
         * @param color whether the {@code AUTO} layout writes color
         * @param translucent whether the {@code AUTO} layout writes alpha
         * @return the channels
         */
        private static PixelLayout target(TiffEncoderOptions.Layout layout, boolean color, boolean translucent) {
            return switch (layout) {
                case GRAY -> PixelLayout.GRAY;
                case GRAY_ALPHA -> PixelLayout.GRAY_ALPHA;
                case RGB -> PixelLayout.RGB;
                case RGBA -> PixelLayout.RGBA;
                case CMYK -> PixelLayout.CMYK;
                case CMYK_ALPHA -> PixelLayout.CMYKA;
                default -> color ? (translucent ? PixelLayout.RGBA : PixelLayout.RGB)
                        : (translucent ? PixelLayout.GRAY_ALPHA : PixelLayout.GRAY);
            };
        }

        /**
         * Returns the ink amount that turns back into a color component exactly.
         *
         * <p>The inverse conversion is {@code round((max - ink) * (max - black) / max)}; its steps
         * are at most 1 since {@code max - black} is at least the component, so the smallest ink
         * giving the component exists and is found from the estimate by stepping.
         *
         * @param component the red, green or blue value, at most {@code max - black}
         * @param black the black amount
         * @param max the largest sample value, 255 or 65535
         * @return the ink amount
         */
        private static int ink(int component, int black, int max) {
            var white = max - black;
            if (white == 0) {
                return 0;
            }
            var ink = (int) Math.clamp(max - ((long) component * max + white / 2) / white, 0, max);
            while (ink > 0 && back(ink - 1, black, max) <= component) {
                ink--;
            }
            while (ink < max && back(ink, black, max) > component) {
                ink++;
            }
            while (ink > 0 && back(ink - 1, black, max) == component) {
                ink--;
            }
            return ink;
        }

        /**
         * {@return the color component an ink and black amount convert to}
         *
         * @param ink the ink amount
         * @param black the black amount
         * @param max the largest sample value
         */
        private static int back(int ink, int black, int max) {
            return (int) (((long) (max - ink) * (max - black) + max / 2) / max);
        }

        /**
         * Builds bilevel samples, one bit per pixel with 1 for black.
         *
         * @param picture the pixels
         * @param dithering how luma becomes black or white
         * @return the samples
         */
        private static Samples bilevel(Picture picture, Dithering dithering) {
            var rgba = ImageOps.toRgba(picture);
            var width = rgba.width();
            var height = rgba.height();
            var pixels = rgba.pixels();
            var stride = rgba.stride();
            var rowBytes = (width + 7) >>> 3;
            var data = new byte[Math.multiplyExact(rowBytes, height)];
            var luma = new int[width];
            var error = dithering == Dithering.FLOYD_STEINBERG || dithering == Dithering.SIERRA
                    ? new int[2][width + 4] : null;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    luma[x] = luma(pixels[at] & 0xFF, pixels[at + 1] & 0xFF, pixels[at + 2] & 0xFF);
                }
                var row = y * rowBytes;
                switch (dithering) {
                    case NONE -> {
                        for (var x = 0; x < width; x++) {
                            if (luma[x] < 128) {
                                data[row + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                            }
                        }
                    }
                    case ORDERED -> {
                        for (var x = 0; x < width; x++) {
                            if (luma[x] < BAYER[(y & 7) * 8 + (x & 7)] * 4 + 2) {
                                data[row + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                            }
                        }
                    }
                    default -> diffuse(luma, error, y, width, dithering == Dithering.SIERRA, data, row);
                }
            }
            var plane = new Plane(data, width, height, 1, 1, 1);
            return new Samples(width, height, 1, 1, false, 0, new int[0], 0, null, null, null, null,
                    new Plane[]{plane});
        }

        /**
         * Thresholds one row with error diffusion in serpentine order: even rows left to right, odd
         * rows right to left.
         *
         * @param luma the luma of the row
         * @param error the error carried into this row (index 0) and the next (index 1), offset by 2
         * pixels; rotated by this method
         * @param y the row index
         * @param width the pixels per row
         * @param sierra whether the two-row Sierra weights apply rather than Floyd-Steinberg's
         * @param data the bits, updated
         * @param row the index of the row's first byte
         */
        private static void diffuse(int[] luma, int[][] error, int y, int width, boolean sierra, byte[] data,
                                    int row) {
            var current = error[0];
            var next = error[1];
            Arrays.fill(next, 0);
            var forward = (y & 1) == 0;
            for (var i = 0; i < width; i++) {
                var x = forward ? i : width - 1 - i;
                var direction = forward ? 1 : -1;
                var value = luma[x] + current[x + 2] / 16;
                var black = value < 128;
                if (black) {
                    data[row + (x >>> 3)] |= (byte) (0x80 >>> (x & 7));
                }
                var e = value - (black ? 0 : 255);
                if (sierra) {
                    current[x + 2 + direction] += e * 4;
                    current[x + 2 + 2 * direction] += e * 3;
                    next[x + 2 - 2 * direction] += e;
                    next[x + 2 - direction] += e * 2;
                    next[x + 2] += e * 3;
                    next[x + 2 + direction] += e * 2;
                    next[x + 2 + 2 * direction] += e;
                } else {
                    current[x + 2 + direction] += e * 7;
                    next[x + 2 - direction] += e * 3;
                    next[x + 2] += e * 5;
                    next[x + 2 + direction] += e;
                }
            }
            error[0] = next;
            error[1] = current;
        }

        /**
         * Builds palette samples and their color map.
         *
         * @param picture the pixels
         * @param settings the palette settings
         * @return the samples
         */
        private static Samples palette(Picture picture, QuantizeOptions settings) {
            var rgba = ImageOps.toRgba(picture);
            var width = rgba.width();
            var height = rgba.height();
            var opaque = RgbaPicture.allocate(width, height, false);
            var source = rgba.pixels();
            var target = opaque.pixels();
            for (var y = 0; y < height; y++) {
                System.arraycopy(source, y * rgba.stride(), target, y * opaque.stride(), width * 4);
                for (var x = 0; x < width; x++) {
                    target[y * opaque.stride() + x * 4 + 3] = (byte) 0xFF;
                }
            }
            var quantized = ImageQuantization.quantize(opaque, settings);
            var palette = quantized.palette();
            var bits = palette.length <= 2 ? 1 : palette.length <= 4 ? 2 : palette.length <= 16 ? 4 : 8;
            var indices = quantized.indices();
            var rowBytes = (width * bits + 7) >>> 3;
            var data = new byte[Math.multiplyExact(rowBytes, height)];
            var perByte = 8 / bits;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var index = indices[y * width + x] & 0xFF;
                    var shift = 8 - bits - (x % perByte) * bits;
                    data[y * rowBytes + x / perByte] |= (byte) (index << shift);
                }
            }
            var entries = 1 << bits;
            var colorMap = new int[3 * entries];
            for (var i = 0; i < palette.length; i++) {
                colorMap[i] = (palette[i] >>> 16 & 0xFF) * 257;
                colorMap[entries + i] = (palette[i] >>> 8 & 0xFF) * 257;
                colorMap[2 * entries + i] = (palette[i] & 0xFF) * 257;
            }
            var plane = new Plane(data, width, height, bits, 1, 1);
            return new Samples(width, height, 1, bits, false, 3, new int[0], 0, null, colorMap, null, null,
                    new Plane[]{plane});
        }

        /**
         * Builds Y'CbCr samples.
         *
         * @param picture the pixels
         * @param format the chroma layout
         * @param planar whether the planes are written separately
         * @return the samples
         */
        private static Samples ycbcr(Picture picture, ChromaFormat format, boolean planar) {
            var color = ColorInfo.JPEG;
            if (picture instanceof YuvPicture source && source.color().matrix().effective().kr().isPresent()) {
                color = source.color().withMatrix(source.color().matrix().effective());
            }
            var yuv = ImageOps.toYuv(picture, format, color);
            var width = yuv.width();
            var height = yuv.height();
            var sx = format == ChromaFormat.YUV444 ? 1 : 2;
            var sy = format == ChromaFormat.YUV420 ? 2 : 1;
            var chromaWidth = (width + sx - 1) / sx;
            var chromaHeight = (height + sy - 1) / sy;
            var luma = yuv.y();
            var cb = yuv.u();
            var cr = yuv.v();
            var lumaStride = yuv.yStride();
            var chromaStride = yuv.uvStride();
            Plane[] planes;
            if (planar) {
                planes = new Plane[]{
                        new Plane(tight(luma, lumaStride, width, height), width, height, 8, 1, 1),
                        new Plane(tight(cb, chromaStride, chromaWidth, chromaHeight), chromaWidth, chromaHeight, 8, sx, sy),
                        new Plane(tight(cr, chromaStride, chromaWidth, chromaHeight), chromaWidth, chromaHeight, 8, sx, sy)
                };
            } else {
                var unit = sx * sy + 2;
                var data = new byte[Math.multiplyExact(Math.multiplyExact(chromaWidth, chromaHeight), unit)];
                var o = 0;
                for (var cy = 0; cy < chromaHeight; cy++) {
                    for (var cx = 0; cx < chromaWidth; cx++) {
                        for (var dy = 0; dy < sy; dy++) {
                            var y = Math.min(cy * sy + dy, height - 1);
                            for (var dx = 0; dx < sx; dx++) {
                                var x = Math.min(cx * sx + dx, width - 1);
                                data[o++] = luma[y * lumaStride + x];
                            }
                        }
                        data[o++] = cb[cy * chromaStride + cx];
                        data[o++] = cr[cy * chromaStride + cx];
                    }
                }
                planes = new Plane[]{new Plane(data, chromaWidth, chromaHeight, unit * 8, sx, sy)};
            }
            var matrix = color.matrix();
            var kr = matrix.kr().orElseThrow();
            var kb = matrix.kb().orElseThrow();
            var full = color.range() == ColorRange.FULL;
            var siting = color.chromaSiting();
            var cosited = siting == ChromaSiting.TOP_LEFT || (siting == ChromaSiting.LEFT && sy == 1 && sx == 2);
            var description = new YCbCr(sx, sy, new double[]{kr, 1 - kr - kb, kb},
                    full ? new int[]{0, 255, 128, 255, 128, 255} : new int[]{16, 235, 128, 240, 128, 240},
                    cosited ? 2 : 1);
            return new Samples(width, height, 3, 8, false, 6, new int[0], 0, null, null, description, null, planes);
        }

        /**
         * Builds the samples of a JPEG page: 8-bit gray, or 8-bit RGB that each strip or tile
         * converts to Y'CbCr.
         *
         * @param picture the pixels
         * @param options the encoder settings
         * @return the samples
         */
        private static Samples jpeg(Picture picture, TiffEncoderOptions options) {
            var format = options.jpegOptions().chromaFormat();
            var layout = options.layout();
            var rgba = ImageOps.toRgba(picture);
            var gray = format == ChromaFormat.MONOCHROME || layout == TiffEncoderOptions.Layout.GRAY;
            if (!gray && layout == TiffEncoderOptions.Layout.AUTO) {
                gray = isGray(rgba);
            }
            if (gray) {
                var width = rgba.width();
                var height = rgba.height();
                var pixels = rgba.pixels();
                var luma = new byte[Math.multiplyExact(width, height)];
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var at = y * rgba.stride() + x * 4;
                        // libjpeg's RGB-to-gray weights, so that the strips code what a JPEG encoder codes
                        luma[y * width + x] = (byte) ((19595 * (pixels[at] & 0xFF) + 38470 * (pixels[at + 1] & 0xFF)
                                + 7471 * (pixels[at + 2] & 0xFF) + 32768) >> 16);
                    }
                }
                return new Samples(width, height, 1, 8, false, 1, new int[0], 0, null, null, null,
                        ChromaFormat.MONOCHROME, new Plane[]{new Plane(luma, width, height, 8, 1, 1)});
            }
            var plain = channels(packed(rgba, TiffEncoderOptions.Layout.RGB, false), true, false);
            var sx = format == ChromaFormat.YUV444 ? 1 : 2;
            var sy = format == ChromaFormat.YUV420 ? 2 : 1;
            var description = new YCbCr(sx, sy, new double[]{0.299, 0.587, 0.114}, new int[]{0, 255, 128, 255, 128, 255}, 1);
            return new Samples(plain.width(), plain.height(), 3, 8, false, 6, new int[0], 0, null, null, description,
                    format, plain.planes());
        }

        /**
         * {@return whether every pixel of a picture is gray}
         *
         * @param picture the pixels
         */
        private static boolean isGray(RgbaPicture picture) {
            var pixels = picture.pixels();
            for (var y = 0; y < picture.height(); y++) {
                for (var x = 0; x < picture.width(); x++) {
                    var at = y * picture.stride() + x * 4;
                    if (pixels[at] != pixels[at + 1] || pixels[at] != pixels[at + 2]) {
                        return false;
                    }
                }
            }
            return true;
        }

        /**
         * Copies a plane region into a tightly packed array.
         *
         * @param plane the samples
         * @param stride the distance between rows
         * @param width the samples per row
         * @param height the number of rows
         * @return the packed samples
         */
        private static byte[] tight(byte[] plane, int stride, int width, int height) {
            var out = new byte[Math.multiplyExact(width, height)];
            for (var y = 0; y < height; y++) {
                System.arraycopy(plane, y * stride, out, y * width, width);
            }
            return out;
        }

        /**
         * Builds the samples of a channel picture, as stored.
         *
         * @param picture the pixels
         * @param little whether multi-byte samples are little-endian
         * @param planar whether each sample is written as its own plane
         * @return the samples
         */
        private static Samples channels(ChannelPicture picture, boolean little, boolean planar) {
            var width = picture.width();
            var height = picture.height();
            var layout = picture.layout();
            var count = layout.count();
            var colors = layout.colorChannels();
            var model = layout.colorModel();
            var photometric = switch (model) {
                case GRAY -> 1;
                case RGB -> 2;
                default -> 5;
            };
            var inks = 0;
            String inkNames = null;
            if (model == ColorModel.INK) {
                inks = colors;
                var names = new StringBuilder();
                var named = false;
                for (var i = 0; i < colors; i++) {
                    var name = layout.channel(i).name();
                    var end = name.indexOf(0);
                    if (end >= 0) {
                        name = name.substring(0, end);
                    }
                    named |= !name.isEmpty();
                    names.append(name).append('\0');
                }
                inkNames = named ? names.substring(0, names.length() - 1) : null;
            }
            var extras = new int[count - colors];
            for (var i = colors; i < count; i++) {
                extras[i - colors] = switch (layout.channel(i).type()) {
                    case PREMULTIPLIED_ALPHA -> 1;
                    case ALPHA -> 2;
                    default -> 0;
                };
            }
            var bits = picture.bitDepth();
            var bytes = bits / 8;
            var separate = planar && count > 1;
            var planeCount = separate ? count : 1;
            var perPixel = separate ? 1 : count;
            var datas = new byte[planeCount][Math.multiplyExact(Math.multiplyExact(width * perPixel, height), bytes)];
            var stride = picture.stride();
            var rowSamples = width * count;
            for (var plane = 0; plane < planeCount; plane++) {
                var data = datas[plane];
                var first = separate ? plane : 0;
                var step = separate ? count : 1;
                var out = 0;
                switch (picture) {
                    case Channel8Picture eight when !separate -> {
                        for (var y = 0; y < height; y++, out += rowSamples) {
                            System.arraycopy(eight.pixels(), y * stride, data, out, rowSamples);
                        }
                    }
                    case Channel8Picture eight -> {
                        var pixels = eight.pixels();
                        for (var y = 0; y < height; y++) {
                            for (int i = y * stride + first, end = y * stride + rowSamples; i < end; i += step) {
                                data[out++] = pixels[i];
                            }
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var pixels = sixteen.pixels();
                        for (var y = 0; y < height; y++) {
                            for (int i = y * stride + first, end = y * stride + rowSamples; i < end; i += step) {
                                put16(data, out, pixels[i] & 0xFFFF, little);
                                out += 2;
                            }
                        }
                    }
                    case ChannelFloatPicture floating -> {
                        var pixels = floating.pixels();
                        for (var y = 0; y < height; y++) {
                            for (int i = y * stride + first, end = y * stride + rowSamples; i < end; i += step) {
                                var value = Float.floatToRawIntBits(pixels[i]);
                                put16(data, out, little ? value & 0xFFFF : value >>> 16, little);
                                put16(data, out + 2, little ? value >>> 16 : value & 0xFFFF, little);
                                out += 4;
                            }
                        }
                    }
                }
            }
            var planes = new Plane[planeCount];
            for (var p = 0; p < planeCount; p++) {
                planes[p] = new Plane(datas[p], width, height, perPixel * bits, 1, 1);
            }
            return new Samples(width, height, count, bits, picture instanceof ChannelFloatPicture, photometric,
                    extras, inks, inkNames, null, null, null, planes);
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
         * @param data the destination
         * @param at the index of the sample
         * @param value the sample
         * @param little whether the sample is little-endian
         */
        private static void put16(byte[] data, int at, int value, boolean little) {
            if (little) {
                data[at] = (byte) value;
                data[at + 1] = (byte) (value >>> 8);
            } else {
                data[at] = (byte) (value >>> 8);
                data[at + 1] = (byte) value;
            }
        }

        /**
         * {@return whether interleaved samples are grouped into subsampled Y'CbCr data units}
         */
        boolean subsampledUnits() {
            return ycbcr != null && jpeg == null && planes.length == 1 && (ycbcr.subsampleX() != 1
                    || ycbcr.subsampleY() != 1);
        }

        /**
         * {@return the bytes of one row of pixels over all planes, for sizing automatic strips}
         */
        long pixelRowBytes() {
            var total = 0L;
            for (var plane : planes) {
                total += (plane.rowBytes() + plane.subsampleY() - 1) / plane.subsampleY();
            }
            return total;
        }
    }

    /**
     * Describes the Y'CbCr samples of a page.
     *
     * @param subsampleX the horizontal chroma subsampling, 1 or 2
     * @param subsampleY the vertical chroma subsampling, 1 or 2
     * @param coefficients the luma weights of red, green and blue
     * @param referenceBlackWhite the reference black and white of Y', Cb and Cr
     * @param positioning the {@code YCbCrPositioning}: 1 for centered, 2 for cosited
     */
    private record YCbCr(int subsampleX, int subsampleY, double[] coefficients, int[] referenceBlackWhite,
                         int positioning) {
    }

    /**
     * Holds the compressed strips or tiles of one page.
     *
     * @param data the compressed strips or tiles, plane by plane, in order
     * @param chunkWidth the tile width, or the image width for strips
     * @param chunkHeight the tile height, or the rows per strip
     * @param tiled whether the chunks are tiles
     * @param predictor the {@code Predictor} code applied, 1 for none
     * @param jpegTables the {@code JPEGTables} stream, or {@code null}
     */
    private record Chunks(byte[][] data, int chunkWidth, int chunkHeight, boolean tiled, int predictor,
                          byte[] jpegTables) {
    }

    /**
     * Cuts a page into strips or tiles and compresses them.
     *
     * @param page the samples
     * @param pageOptions the settings of the page
     * @return the compressed chunks
     */
    private Chunks compress(Samples page, TiffEncoderOptions pageOptions) {
        var compression = pageOptions.compression();
        var tiled = pageOptions.tileWidth().isPresent();
        var codec = compression == TiffCompression.WEBP || compression == TiffCompression.JPEG_2000
                || compression == TiffCompression.JPEG;
        int chunkWidth;
        int chunkHeight;
        if (tiled) {
            chunkWidth = pageOptions.tileWidth().getAsInt();
            chunkHeight = pageOptions.tileHeight().getAsInt();
        } else {
            chunkWidth = page.width();
            var group = page.jpeg() != null ? 8 * (page.jpeg() == ChromaFormat.YUV420 ? 2 : 1)
                    : page.ycbcr() != null ? page.ycbcr().subsampleY() : 1;
            var rows = pageOptions.rawRowsPerStrip();
            var automatic = (int) Math.max(1, STRIP_BYTES / Math.max(1, page.pixelRowBytes()));
            if (compression == TiffCompression.WEBP) {
                automatic = Math.min(automatic, WEBP_MAX_SIDE);
            }
            automatic = Math.max(group, automatic / group * group);
            chunkHeight = Math.min(page.height(), rows > 0 ? rows : automatic);
        }
        var across = (page.width() + chunkWidth - 1) / chunkWidth;
        var down = (page.height() + chunkHeight - 1) / chunkHeight;
        var perPlane = Math.multiplyExact(across, down);
        var count = Math.multiplyExact(perPlane, page.planes().length);
        var lossless = compression == TiffCompression.LZW || compression == TiffCompression.DEFLATE
                || compression == TiffCompression.ZSTD;
        var predictor = 1;
        if (lossless && pageOptions.predictor() == TiffPredictor.HORIZONTAL && !page.floating() && page.bits() >= 8
                && page.photometric() != 3 && !page.subsampledUnits()) {
            predictor = 2;
        } else if (lossless && pageOptions.predictor() == TiffPredictor.FLOATING_POINT && page.floating()) {
            predictor = 3;
        }
        var predictorCode = predictor;
        var stride = page.planes().length > 1 ? 1 : page.channels();
        var jpeg = pageOptions.jpegOptions();
        var jpegTables = page.jpeg() == null ? null
                : JpegWriter.tables(jpeg.quality(), page.jpeg(), !jpeg.optimizeHuffman());
        var webpOptions = pageOptions.webpOptions().toBuilder().colorProfile(null).build();
        var jpeg2000Options = pageOptions.jpeg2000Options().toBuilder().format(Jp2Format.CODESTREAM).colorProfile(null)
                .build();
        var fax = switch (compression) {
            case CCITT_RLE -> CcittFaxEncoder.RLE;
            case CCITT_RLEW -> CcittFaxEncoder.RLEW;
            case CCITT_T4 -> CcittFaxEncoder.T4;
            case CCITT_T6 -> CcittFaxEncoder.T6;
            default -> 0;
        };
        var k = CcittFaxEncoder.parameterK(verticalDpi(pageOptions));
        var data = new byte[count][];
        var bands = ParallelBands.bands(count, (long) page.width() * page.height() * page.channels());
        ParallelBands.run(bands, band -> {
            var lzw = compression == TiffCompression.LZW ? new LzwEncoder() : null;
            var faxEncoder = fax != 0 ? new CcittFaxEncoder(fax, pageOptions.isFaxTwoDimensional(),
                    pageOptions.isFaxFillBits(), k) : null;
            byte[] scratch = null;
            var first = ParallelBands.start(band, bands, count);
            var last = ParallelBands.start(band + 1, bands, count);
            for (var index = first; index < last; index++) {
                var plane = page.planes()[index / perPlane];
                var local = index % perPlane;
                var x0 = (local % across) * chunkWidth;
                var y0 = (local / across) * chunkHeight;
                var pixelRows = tiled ? chunkHeight : Math.min(chunkHeight, page.height() - y0);
                var units = (chunkWidth + plane.subsampleX() - 1) / plane.subsampleX();
                var rows = (pixelRows + plane.subsampleY() - 1) / plane.subsampleY();
                var unitX = x0 / plane.subsampleX();
                var unitY = y0 / plane.subsampleY();
                var chunkRow = (int) (((long) units * plane.unitBits() + 7) / 8);
                var chunk = new byte[Math.multiplyExact(chunkRow, rows)];
                var copyRows = Math.min(rows, plane.unitRows() - unitY);
                var copyUnits = Math.min(units, plane.unitsWide() - unitX);
                var copyBytes = (int) (((long) copyUnits * plane.unitBits() + 7) / 8);
                var source = (int) ((long) unitX * plane.unitBits() / 8);
                var rowBytes = plane.rowBytes();
                for (var row = 0; row < copyRows; row++) {
                    System.arraycopy(plane.data(), (unitY + row) * rowBytes + source, chunk, row * chunkRow, copyBytes);
                }
                if (codec) {
                    replicateEdges(chunk, chunkRow, rows, copyRows, copyBytes, plane.unitBits() / 8);
                }
                if (predictorCode == 2) {
                    Predictor.applyHorizontal(chunk, 0, chunkRow, rows, stride, units, page.bits() / 8, little);
                } else if (predictorCode == 3) {
                    var needed = units * stride * 4;
                    if (scratch == null || scratch.length < needed) {
                        scratch = new byte[needed];
                    }
                    Predictor.applyFloatingPoint(chunk, 0, chunkRow, rows, stride, units * stride, 4, little, scratch);
                }
                data[index] = switch (compression) {
                    case PACKBITS -> PackBits.encode(chunk, 0, chunkRow, rows);
                    case LZW -> lzw.encode(chunk, 0, chunk.length);
                    case DEFLATE -> ZlibDeflater.zlib(chunk, 0, chunk.length, pageOptions.compressionLevel());
                    case ZSTD -> ZstdEncoder.compress(chunk, 0, chunk.length, pageOptions.compressionLevel());
                    case CCITT_RLE, CCITT_RLEW, CCITT_T4, CCITT_T6 -> faxEncoder.encode(chunk, 0, chunkRow, units, rows);
                    case WEBP -> webp(chunk, units, rows, page.channels(), webpOptions);
                    case JPEG_2000 -> jpeg2000(chunk, units, rows, page, jpeg2000Options);
                    case JPEG -> JpegWriter.encodeAbbreviated(rgba(chunk, units, rows, page.channels()), jpeg.quality(),
                            page.jpeg(), jpeg.optimizeHuffman(), jpeg.restartInterval());
                    default -> chunk;
                };
            }
        });
        return new Chunks(data, chunkWidth, chunkHeight, tiled, predictorCode, jpegTables);
    }

    /**
     * Fills the part of a strip or tile beyond the image with copies of its last column and row.
     *
     * @param chunk the samples of the strip or tile
     * @param chunkRow the size of one row
     * @param rows the number of rows
     * @param copyRows the number of rows inside the image, at least 1
     * @param copyBytes the size of the part of a row inside the image, at least one pixel
     * @param bytesPerPixel the size of one pixel
     */
    private static void replicateEdges(byte[] chunk, int chunkRow, int rows, int copyRows, int copyBytes,
                                       int bytesPerPixel) {
        if (copyBytes < chunkRow) {
            for (var row = 0; row < copyRows; row++) {
                var start = row * chunkRow;
                var last = start + copyBytes - bytesPerPixel;
                for (var at = start + copyBytes; at < start + chunkRow; at += bytesPerPixel) {
                    System.arraycopy(chunk, last, chunk, at, bytesPerPixel);
                }
            }
        }
        for (var row = copyRows; row < rows; row++) {
            System.arraycopy(chunk, (copyRows - 1) * chunkRow, chunk, row * chunkRow, chunkRow);
        }
    }

    /**
     * Wraps one strip or tile of 8-bit gray, RGB or RGBA samples as an RGBA picture.
     *
     * @param chunk the interleaved samples
     * @param width the width of the strip or tile
     * @param rows the number of rows
     * @param channels the samples per pixel, 1, 3 or 4
     * @return the picture, sharing {@code chunk} when it holds RGBA
     */
    private static RgbaPicture rgba(byte[] chunk, int width, int rows, int channels) {
        if (channels == 4) {
            return RgbaPicture.of(width, rows, width * 4, chunk, true);
        }
        var pixels = new byte[width * rows * 4];
        for (int i = 0, o = 0; o < pixels.length; i += channels, o += 4) {
            pixels[o] = chunk[i];
            pixels[o + 1] = chunk[i + (channels == 3 ? 1 : 0)];
            pixels[o + 2] = chunk[i + (channels == 3 ? 2 : 0)];
            pixels[o + 3] = (byte) 0xFF;
        }
        return RgbaPicture.of(width, rows, width * 4, pixels, false);
    }

    /**
     * Codes one strip or tile of 8-bit RGB or RGBA samples as a WebP file.
     *
     * @param chunk the interleaved samples
     * @param width the width of the strip or tile
     * @param rows the number of rows
     * @param channels the samples per pixel, 3 or 4
     * @param webpOptions the WebP settings, without a color profile
     * @return the WebP file
     */
    private static byte[] webp(byte[] chunk, int width, int rows, int channels, WebpEncoderOptions webpOptions) {
        return WebpEncoder.encode(rgba(chunk, width, rows, channels), webpOptions);
    }

    /**
     * Codes one strip or tile as a JPEG 2000 codestream, one component per sample.
     *
     * @param chunk the interleaved samples
     * @param width the width of the strip or tile
     * @param rows the number of rows
     * @param page the page the samples belong to
     * @param jpeg2000Options the JPEG 2000 settings, writing a codestream without a color profile
     * @return the codestream
     */
    private byte[] jpeg2000(byte[] chunk, int width, int rows, Samples page, Jp2EncoderOptions jpeg2000Options) {
        var count = page.channels();
        var bytes = page.bits() / 8;
        var channels = new ArrayList<Jp2Channel>(count);
        for (var c = 0; c < count; c++) {
            var values = new int[width * rows];
            for (int i = 0, at = c * bytes; i < values.length; i++, at += count * bytes) {
                values[i] = bytes == 1 ? chunk[at] & 0xFF
                        : little ? (chunk[at] & 0xFF) | (chunk[at + 1] & 0xFF) << 8
                        : (chunk[at] & 0xFF) << 8 | (chunk[at + 1] & 0xFF);
            }
            channels.add(new Jp2Channel(0, 0, width, rows, 1, 1, page.bits(), false, Jp2ChannelType.COLOR, -1, values));
        }
        var image = new Jp2Image(0, 0, width, rows, width, rows, 0, channels, Jp2ColorSpace.UNSPECIFIED,
                Optional.empty(), Optional.empty(), Optional.empty());
        return Jp2Encoder.encode(image, jpeg2000Options);
    }

    /**
     * Returns the vertical resolution a page records, in rows per inch, as the CCITT
     * two-dimensional parameter reads it.
     *
     * @param pageOptions the settings of the page
     * @return the resolution, or the value as stored when the unit is not absolute
     */
    private static double verticalDpi(TiffEncoderOptions pageOptions) {
        var density = pageOptions.density().orElse(null);
        if (density != null) {
            return density.isPhysical() ? density.to(PixelDensity.Unit.DOTS_PER_INCH).vertical() : density.vertical();
        }
        var exif = pageOptions.exif().orElse(null);
        // the directory takes the EXIF resolution only when both of its entries are present
        if (exif != null && exif.ifd0().containsKey(TiffTags.X_RESOLUTION)
                && exif.ifd0().get(TiffTags.Y_RESOLUTION) instanceof ExifValue.Rationals value && value.count() > 0) {
            var unit = exif.ifd0().get(TiffTags.RESOLUTION_UNIT) instanceof ExifValue.Integers units
                    && units.count() > 0 ? units.get(0) : 2;
            return value.doubleValue(0) * (unit == 3 ? 2.54 : 1);
        }
        return 72;
    }

    /**
     * Builds the directory of a page.
     *
     * @param page the samples
     * @param chunks the compressed chunks
     * @param offsets the offset of each chunk
     * @param counts the size of each chunk
     * @param pageOptions the settings of the page
     * @return the directory
     */
    private IfdBuilder directory(Samples page, Chunks chunks, long[] offsets, long[] counts,
                                 TiffEncoderOptions pageOptions) {
        var directory = new IfdBuilder(little, big);
        var exif = pageOptions.exif().orElse(null);
        if (exif != null) {
            TiffExifWriter.write(exif, directory, little, big);
        }
        directory.longs(TiffTags.IMAGE_WIDTH, page.width());
        directory.longs(TiffTags.IMAGE_LENGTH, page.height());
        var bits = new int[page.channels()];
        Arrays.fill(bits, page.bits());
        directory.shorts(TiffTags.BITS_PER_SAMPLE, bits);
        directory.shorts(TiffTags.COMPRESSION, pageOptions.compression().code());
        directory.shorts(TiffTags.PHOTOMETRIC, page.photometric());
        var orientation = pageOptions.orientation();
        if (orientation.isPresent()) {
            directory.shorts(TiffTags.ORIENTATION, orientation.get().exifCode());
        } else if (exif != null) {
            TiffExifWriter.copy(exif, TiffTags.ORIENTATION, directory, little);
        }
        directory.shorts(TiffTags.SAMPLES_PER_PIXEL, page.channels());
        var density = pageOptions.density().orElse(null);
        if (density != null) {
            var unit = switch (density.unit()) {
                case ASPECT_RATIO -> 1;
                case DOTS_PER_INCH -> 2;
                case DOTS_PER_CENTIMETER -> 3;
                case DOTS_PER_METER -> {
                    density = density.to(PixelDensity.Unit.DOTS_PER_CENTIMETER);
                    yield 3;
                }
            };
            var x = rational(density.horizontal());
            var y = rational(density.vertical());
            directory.rational(TiffTags.X_RESOLUTION, x[0], x[1]);
            directory.rational(TiffTags.Y_RESOLUTION, y[0], y[1]);
            directory.shorts(TiffTags.RESOLUTION_UNIT, unit);
        } else if (exif == null || !TiffExifWriter.copy(exif, TiffTags.X_RESOLUTION, directory, little)
                || !TiffExifWriter.copy(exif, TiffTags.Y_RESOLUTION, directory, little)) {
            directory.rational(TiffTags.X_RESOLUTION, 72, 1);
            directory.rational(TiffTags.Y_RESOLUTION, 72, 1);
            directory.shorts(TiffTags.RESOLUTION_UNIT, 2);
        } else if (!TiffExifWriter.copy(exif, TiffTags.RESOLUTION_UNIT, directory, little)) {
            directory.shorts(TiffTags.RESOLUTION_UNIT, 2);
        }
        directory.shorts(TiffTags.PLANAR_CONFIGURATION, page.planes().length > 1 ? 2 : 1);
        if (pageOptions.compression() == TiffCompression.CCITT_T4) {
            directory.longs(TiffTags.T4_OPTIONS, (pageOptions.isFaxTwoDimensional() ? 1 : 0)
                    | (pageOptions.isFaxFillBits() ? 4 : 0));
        }
        if (chunks.predictor() != 1) {
            directory.shorts(TiffTags.PREDICTOR, chunks.predictor());
        }
        if (page.colorMap() != null) {
            directory.shorts(TiffTags.COLOR_MAP, page.colorMap());
        }
        if (page.photometric() == 5) {
            directory.shorts(TiffTags.INK_SET, page.inks() > 0 ? 2 : 1);
            if (page.inks() > 0) {
                directory.shorts(TiffTags.NUMBER_OF_INKS, page.inks());
            }
            if (page.inkNames() != null) {
                directory.ascii(TiffTags.INK_NAMES, page.inkNames());
            }
        }
        if (page.extras().length > 0) {
            directory.shorts(TiffTags.EXTRA_SAMPLES, page.extras());
        }
        if (page.floating()) {
            var formats = new int[page.channels()];
            Arrays.fill(formats, 3);
            directory.shorts(TiffTags.SAMPLE_FORMAT, formats);
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
        if (chunks.jpegTables() != null) {
            directory.undefined(TiffTags.JPEG_TABLES, chunks.jpegTables());
        }
        var ycbcr = page.ycbcr();
        if (ycbcr != null) {
            var coefficients = new long[6];
            for (var i = 0; i < 3; i++) {
                var value = rational(ycbcr.coefficients()[i]);
                coefficients[2 * i] = value[0];
                coefficients[2 * i + 1] = value[1];
            }
            directory.rationals(TiffTags.Y_CB_CR_COEFFICIENTS, coefficients);
            directory.shorts(TiffTags.Y_CB_CR_SUBSAMPLING, ycbcr.subsampleX(), ycbcr.subsampleY());
            directory.shorts(TiffTags.Y_CB_CR_POSITIONING, ycbcr.positioning());
            var reference = new long[12];
            for (var i = 0; i < 6; i++) {
                reference[2 * i] = ycbcr.referenceBlackWhite()[i];
                reference[2 * i + 1] = 1;
            }
            directory.rationals(TiffTags.REFERENCE_BLACK_WHITE, reference);
        }
        var xmp = pageOptions.xmp();
        if (xmp.isPresent()) {
            var packet = xmp.get().toBytes();
            directory.raw(TiffTags.XMP, 1, packet.length, packet);
        }
        var iptc = pageOptions.iptc();
        if (iptc.isPresent()) {
            var record = iptc.get().toBytes();
            var padded = Arrays.copyOf(record, (record.length + 3) & ~3);
            directory.raw(TiffTags.IPTC, 4, padded.length / 4, padded);
        }
        var profile = pageOptions.iccProfileBytes();
        if (profile != null) {
            directory.undefined(TiffTags.ICC_PROFILE, profile);
        }
        return directory;
    }

    /**
     * Approximates a positive number by a fraction of 32-bit unsigned terms, by continued
     * fractions.
     *
     * @param value the number, positive and finite
     * @return the numerator and the denominator, both positive
     */
    static long[] rational(double value) {
        var limit = 0xFFFF_FFFFL;
        if (value >= limit) {
            return new long[]{limit, 1};
        }
        if (value == Math.rint(value)) {
            return new long[]{(long) value, 1};
        }
        long p0 = 0;
        long q0 = 1;
        long p1 = 1;
        long q1 = 0;
        var x = value;
        long bestP = Math.round(value);
        long bestQ = 1;
        for (var i = 0; i < 64; i++) {
            var a = (long) Math.floor(x);
            var p2 = a * p1 + p0;
            var q2 = a * q1 + q0;
            if (p2 > limit || q2 > limit) {
                break;
            }
            bestP = p2;
            bestQ = q2;
            if ((double) p2 / q2 == value) {
                break;
            }
            var fraction = x - a;
            if (fraction == 0) {
                break;
            }
            x = 1 / fraction;
            p0 = p1;
            q0 = q1;
            p1 = p2;
            q1 = q2;
        }
        // a value below 2^-32 keeps the smallest positive fraction rather than becoming zero
        return bestP == 0 ? new long[]{1, limit} : new long[]{bestP, bestQ};
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
