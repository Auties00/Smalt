package com.github.auties00.smalt.image.tiff;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.internal.zlib.ZlibDeflater;
import com.github.auties00.smalt.image.internal.zstd.ZstdEncoder;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Encoder;
import com.github.auties00.smalt.image.jp2.Jp2EncoderOptions;
import com.github.auties00.smalt.image.jp2.Jp2Format;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.tiff.internal.IfdBuilder;
import com.github.auties00.smalt.image.tiff.internal.LzwEncoder;
import com.github.auties00.smalt.image.tiff.internal.PackBits;
import com.github.auties00.smalt.image.tiff.internal.Predictor;
import com.github.auties00.smalt.image.tiff.internal.TiffTags;
import com.github.auties00.smalt.image.webp.WebpEncoder;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes pictures as TIFF or BigTIFF files, one page per picture.
 *
 * <p>Each page is written with the samples {@link TiffEncoderOptions#layout()} and
 * {@link TiffEncoderOptions#depth()} select (gray or RGB, with or without an unassociated alpha
 * sample, 8 or 16 bits), interleaved ({@code PlanarConfiguration} 1), in strips or tiles,
 * compressed with no compression, PackBits, LZW, Deflate, Zstandard, WebP or JPEG 2000 and, for
 * LZW, Deflate and Zstandard, the horizontal predictor on integer samples. WebP holds 8-bit RGB or RGBA samples only,
 * as {@link TiffEncoderOptions#compression()} describes. Every page records a resolution of 72 dots per inch and,
 * when the options carry one, an ICC profile. Y'CbCr pictures are converted to RGB first.
 *
 * <p>A {@link ChannelPicture} is written with its own channels, whatever the layout option: gray
 * (black-is-zero), RGB, CMYK (separated, {@code InkSet} 1) or named inks (separated, {@code InkSet}
 * 2 with {@code NumberOfInks} and, when any ink is named, {@code InkNames}, each name up to its
 * first NUL character), each extra channel an
 * extra sample (associated alpha, unassociated alpha, or unspecified). Its samples are written as
 * stored: 8 or 16-bit integers, or 32-bit IEEE floating point ({@code SampleFormat} 3) for a
 * {@link ChannelFloatPicture}; {@link TiffEncoderOptions.Depth#EIGHT} and
 * {@link TiffEncoderOptions.Depth#SIXTEEN} convert them first, as
 * {@link ImageOps#toChannel8(Picture)} and {@link ImageOps#toChannel16(Picture)} do. Under WebP
 * compression it is converted to RGBA first; otherwise, with lossless settings, decoding such a page
 * with {@link com.github.auties00.smalt.image.ImageDecoderOptions#keepChannels()} (and
 * {@link com.github.auties00.smalt.image.ImageDecoderOptions#highBitDepth()} for 16-bit samples)
 * returns the same picture, sample for sample.
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
 * writes them under compression 8. The pixels of a partial WebP or JPEG 2000 tile beyond the image
 * repeat its last column and row, which keeps lossy coding from ringing at the image edges.
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
     * The WebP settings of a strip or tile, without a color profile.
     */
    private final WebpEncoderOptions webpOptions;

    /**
     * The JPEG 2000 settings of a strip or tile: a raw codestream.
     */
    private final Jp2EncoderOptions jpeg2000Options;

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
        this.webpOptions = options.webpOptions().toBuilder().colorProfile(null).build();
        this.jpeg2000Options = options.jpeg2000Options().toBuilder().format(Jp2Format.CODESTREAM).colorProfile(null)
                .build();
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
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point
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
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point
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
     * @throws IllegalArgumentException if {@code pictures} is empty, or the compression is
     * {@link TiffCompression#JPEG_2000} and a picture is a {@link ChannelFloatPicture} that the
     * depth option keeps floating point
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
     * @throws IllegalArgumentException if {@code pictures} is empty, or the compression is
     * {@link TiffCompression#JPEG_2000} and a picture is a {@link ChannelFloatPicture} that the
     * depth option keeps floating point
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
     * Compresses a picture and writes it as the next page.
     *
     * @param picture the picture to add as the next page
     * @throws NullPointerException if {@code picture} is {@code null}
     * @throws IllegalArgumentException if the compression is {@link TiffCompression#JPEG_2000} and
     * the picture is a {@link ChannelFloatPicture} that the depth option keeps floating point
     * @throws IllegalStateException if {@link #finish()} already ran
     * @throws MediaException.LimitExceeded if a classic file would exceed 4 GiB, or a WebP strip or tile
     * would exceed 16383 pixels per side or a JPEG 2000 one the limits of the JPEG 2000 options
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
     * @param channels the samples per pixel
     * @param bits the bits per sample: 8, 16 or 32
     * @param floating whether the samples are IEEE floating point
     * @param photometric the {@code PhotometricInterpretation} code: 1, 2 or 5
     * @param extras the {@code ExtraSamples} codes, one per extra sample
     * @param inks the number of inks of an ink set other than CMYK, or 0
     * @param inkNames the {@code InkNames} text, or {@code null}
     * @param data the rows, {@code width * channels * bits / 8} bytes each
     */
    private record Samples(int width, int height, int channels, int bits, boolean floating, int photometric,
                           int[] extras, int inks, String inkNames, byte[] data) {
        /**
         * Converts a picture to the samples the options select.
         *
         * @param picture the picture to convert
         * @param options the encoder settings
         * @param little whether 16-bit samples are little-endian
         * @return the samples
         */
        static Samples of(Picture picture, TiffEncoderOptions options, boolean little) {
            if (options.compression() == TiffCompression.WEBP) {
                return narrow(ImageOps.toRgba(picture), options.layout(), true);
            }
            if (picture instanceof ChannelPicture channels) {
                var samples = channels(switch (options.depth()) {
                    case AUTO -> channels;
                    case EIGHT -> ImageOps.toChannel8(channels);
                    case SIXTEEN -> ImageOps.toChannel16(channels);
                }, little);
                if (samples.floating() && options.compression() == TiffCompression.JPEG_2000) {
                    throw new IllegalArgumentException("JPEG 2000 cannot code floating-point samples");
                }
                return samples;
            }
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
            return narrow(ImageOps.toRgba(picture), options.layout(), false);
        }

        /**
         * Builds 8-bit samples.
         *
         * @param picture the pixels
         * @param layout the requested layout
         * @param color whether gray pixels are written as RGB too
         * @return the samples
         */
        private static Samples narrow(RgbaPicture picture, TiffEncoderOptions.Layout layout, boolean color) {
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
            color |= layout == TiffEncoderOptions.Layout.RGB || layout == TiffEncoderOptions.Layout.RGBA
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
            return new Samples(width, height, channels, 8, false, color ? 2 : 1, alpha ? new int[]{2} : new int[0],
                    0, null, data);
        }

        /**
         * Builds the samples of a channel picture, as stored.
         *
         * @param picture the pixels
         * @param little whether multi-byte samples are little-endian
         * @return the samples
         */
        private static Samples channels(ChannelPicture picture, boolean little) {
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
            var rowSamples = width * count;
            var data = new byte[Math.multiplyExact(Math.multiplyExact(rowSamples, height), bits / 8)];
            var stride = picture.stride();
            var out = 0;
            switch (picture) {
                case Channel8Picture eight -> {
                    for (var y = 0; y < height; y++, out += rowSamples) {
                        System.arraycopy(eight.pixels(), y * stride, data, out, rowSamples);
                    }
                }
                case Channel16Picture sixteen -> {
                    var pixels = sixteen.pixels();
                    for (var y = 0; y < height; y++) {
                        for (var i = 0; i < rowSamples; i++) {
                            out = put16(data, out, pixels[y * stride + i] & 0xFFFF, little);
                        }
                    }
                }
                case ChannelFloatPicture floating -> {
                    var pixels = floating.pixels();
                    for (var y = 0; y < height; y++) {
                        for (var i = 0; i < rowSamples; i++) {
                            var value = Float.floatToRawIntBits(pixels[y * stride + i]);
                            out = put16(data, out, little ? value & 0xFFFF : value >>> 16, little);
                            out = put16(data, out, little ? value >>> 16 : value & 0xFFFF, little);
                        }
                    }
                }
            }
            return new Samples(width, height, count, bits, picture instanceof ChannelFloatPicture, photometric,
                    extras, inks, inkNames, data);
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
            return new Samples(width, height, channels, 16, false, color ? 2 : 1, alpha ? new int[]{2} : new int[0],
                    0, null, data);
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
        // TODO: palette pages, the floating-point predictor and JPEG compression
        var tiled = options.tileWidth().isPresent();
        var codec = options.compression() == TiffCompression.WEBP || options.compression() == TiffCompression.JPEG_2000;
        var rowBytes = page.rowBytes();
        int chunkWidth;
        int chunkHeight;
        if (tiled) {
            chunkWidth = options.tileWidth().getAsInt();
            chunkHeight = options.tileHeight().getAsInt();
        } else {
            chunkWidth = page.width();
            var rows = options.rawRowsPerStrip();
            var automatic = Math.max(1, STRIP_BYTES / Math.max(1, rowBytes));
            if (options.compression() == TiffCompression.WEBP) {
                automatic = Math.min(automatic, WEBP_MAX_SIDE);
            }
            chunkHeight = Math.min(page.height(), rows > 0 ? rows : automatic);
        }
        var across = (page.width() + chunkWidth - 1) / chunkWidth;
        var down = (page.height() + chunkHeight - 1) / chunkHeight;
        var count = Math.multiplyExact(across, down);
        var predicted = options.predictor() == TiffPredictor.HORIZONTAL && !page.floating()
                && (options.compression() == TiffCompression.LZW || options.compression() == TiffCompression.DEFLATE
                || options.compression() == TiffCompression.ZSTD);
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
                if (codec) {
                    replicateEdges(chunk, chunkRow, rows, copyRows, copyBytes, bytesPerPixel);
                }
                if (predicted) {
                    Predictor.applyHorizontal(chunk, 0, chunkRow, rows, page.channels(), chunkWidth, page.bits() / 8, little);
                }
                data[index] = switch (options.compression()) {
                    case PACKBITS -> PackBits.encode(chunk, 0, chunkRow, rows);
                    case LZW -> lzw.encode(chunk, 0, chunk.length);
                    case DEFLATE -> ZlibDeflater.zlib(chunk, 0, chunk.length, options.compressionLevel());
                    case ZSTD -> ZstdEncoder.compress(chunk, 0, chunk.length, options.compressionLevel());
                    case WEBP -> webp(chunk, chunkWidth, rows, page.channels());
                    case JPEG_2000 -> jpeg2000(chunk, chunkWidth, rows, page);
                    default -> chunk;
                };
            }
        });
        return new Chunks(data, chunkWidth, chunkHeight, tiled, predicted);
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
     * Codes one strip or tile of 8-bit RGB or RGBA samples as a WebP file.
     *
     * @param chunk the interleaved samples
     * @param width the width of the strip or tile
     * @param rows the number of rows
     * @param channels the samples per pixel, 3 or 4
     * @return the WebP file
     */
    private byte[] webp(byte[] chunk, int width, int rows, int channels) {
        var pixels = chunk;
        if (channels == 3) {
            pixels = new byte[width * rows * 4];
            for (int i = 0, o = 0; o < pixels.length; i += 3, o += 4) {
                pixels[o] = chunk[i];
                pixels[o + 1] = chunk[i + 1];
                pixels[o + 2] = chunk[i + 2];
                pixels[o + 3] = (byte) 0xFF;
            }
        }
        return WebpEncoder.encode(RgbaPicture.of(width, rows, width * 4, pixels, channels == 4), webpOptions);
    }

    /**
     * Codes one strip or tile as a JPEG 2000 codestream, one component per sample.
     *
     * @param chunk the interleaved samples
     * @param width the width of the strip or tile
     * @param rows the number of rows
     * @param page the page the samples belong to
     * @return the codestream
     */
    private byte[] jpeg2000(byte[] chunk, int width, int rows, Samples page) {
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
        directory.shorts(TiffTags.PHOTOMETRIC, page.photometric());
        directory.shorts(TiffTags.SAMPLES_PER_PIXEL, page.channels());
        directory.rational(TiffTags.X_RESOLUTION, 72, 1);
        directory.rational(TiffTags.Y_RESOLUTION, 72, 1);
        directory.shorts(TiffTags.RESOLUTION_UNIT, 2);
        directory.shorts(TiffTags.PLANAR_CONFIGURATION, 1);
        if (chunks.predicted()) {
            directory.shorts(TiffTags.PREDICTOR, 2);
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
            java.util.Arrays.fill(formats, 3);
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
