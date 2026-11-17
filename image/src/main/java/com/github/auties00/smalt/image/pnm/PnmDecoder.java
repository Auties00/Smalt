package com.github.auties00.smalt.image.pnm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageReader;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.image.pnm.internal.PnmHeader;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decodes Netpbm images (PBM, PGM, PPM and PAM, plain and raw) and portable float maps (PFM and PHM).
 *
 * <p>A file may hold several images one after another, separated by optional white space; each is a page of
 * {@link #openPages(MediaSource, ImageDecoderOptions)}, and {@link #decode(MediaSource, ImageDecoderOptions)}
 * decodes the first. Comments ({@code #} to the end of the line) are allowed wherever white space is in the header
 * and in a plain raster. A sample above maxval is taken as maxval.
 *
 * <p>Integer samples are scaled to the full range: a raster whose maxval is at most 255 to 8 bits, {@code v} mapping
 * to {@code (v * 255 + maxval / 2) / maxval}, and a deeper one to 16 bits, {@code v} mapping to
 * {@code (v * 65535 + maxval / 2) / maxval}. A PBM pixel is black (0) for 1 and white (255) for 0; a PAM
 * {@code BLACKANDWHITE} sample is black for 0 and white for 1, as for any other maxval. The pixels decode to packed
 * RGBA; with {@link ImageDecoderOptions#highBitDepth()}, a raster deeper than 8 bits decodes to an
 * {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}, and without it the 16-bit samples are reduced
 * as {@link com.github.auties00.smalt.image.ops.ImageOps#toEightBit(Picture)} reduces them. PAM tuple types
 * {@code GRAYSCALE}, {@code BLACKANDWHITE}, {@code RGB}, {@code CMYK} and their {@code _ALPHA} forms name the
 * channels; another tuple type is read by its depth as gray, gray and alpha, RGB or RGBA, and a depth beyond those
 * channels adds unspecified channels. With {@link ImageDecoderOptions#keepChannels()}, a CMYK raster or one with
 * unspecified channels decodes to a {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} of its
 * samples, of 8 or 16 bits as {@code highBitDepth} selects; without it CMYK is converted to RGB as
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture)} converts it and unspecified channels are
 * dropped.
 *
 * <p>Float map samples are kept as stored (the scale factor of the header is not applied) and taken as linear light
 * in the BT.709 primaries, which {@link DecodedImage#colorProfile()} reports; rows are stored bottom to top and
 * returned top to bottom. With {@code keepChannels} they decode to a
 * {@link ChannelFloatPicture} of one gray or three color channels; otherwise to packed RGBA of 8 bits, or 16 bits
 * with {@code highBitDepth}, values clamped to 0..1 and scaled without a transfer function.
 * {@link ImageDecoderOptions#convertToSrgb()} converts the floating-point samples to sRGB first.
 *
 * <p>This class is thread-safe.
 */
public final class PnmDecoder {
    private static final String WHAT = "Netpbm image";

    private PnmDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes the first image of a Netpbm or float map file without decoding its pixels.
     *
     * <p>{@link ImageInfo#frameCount()} is the number of images the file holds, found by reading every header (and,
     * for plain rasters, every sample); {@link ImageInfo#animated()} is {@code false}. {@link ImageInfo#hasAlpha()}
     * is {@code true} for a PAM tuple type with alpha.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if a header is invalid or a raster is truncated
     * @throws MediaException.LimitExceeded if the file is larger than the default byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var headers = headers(data, MediaLimits.DEFAULT);
                return info(headers.getFirst(), headers.size());
            });
        }
    }

    /**
     * Decodes the first image of a file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header is invalid or the raster is truncated
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first image of a file.
     *
     * <p>The size hint of {@code options} is ignored.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region, the bit depth, the channel and the sRGB conversion choices
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header is invalid or the raster is truncated
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                return decodeImage(data, PnmHeader.parse(data, 0), whole);
            }));
        }
    }

    /**
     * Opens the images of a file as pages, in file order.
     *
     * <p>Each page decodes as {@link #decode(MediaSource, ImageDecoderOptions)} decodes the first image. The file is
     * read once, when the reader is opened.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of every page), the bit depth, the channel and the sRGB
     * conversion choices applied to every page; a page that the region does not lie inside raises
     * {@link IllegalArgumentException} when it is read
     * @return the page reader
     * @throws MediaException.Malformed if a header is invalid or a raster is truncated
     * @throws MediaException.LimitExceeded if the file exceeds the byte limit or holds more images than the frame
     * limit
     * @throws MediaException.Io if reading the source fails
     */
    public static PageReader openPages(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var region = options.region();
        if (region.isPresent()) {
            return ImageRegions.cropPages(openPages(source, options.toBuilder().wholeImage().build()), region.get());
        }
        try (var _ = ParallelBands.operation()) {
            var data = Guards.malformed(WHAT, () -> SourceBytes.readAll(source, options.limits(), false));
            var headers = Guards.malformed(WHAT, () -> headers(data, options.limits()));
            return new PageReader() {
                @Override
                public int pageCount() {
                    return headers.size();
                }

                @Override
                public ImageInfo probe(int index) {
                    return info(headers.get(Objects.checkIndex(index, headers.size())), headers.size());
                }

                @Override
                public DecodedImage read(int index) {
                    var header = headers.get(Objects.checkIndex(index, headers.size()));
                    try (var _ = ParallelBands.operation()) {
                        return Guards.malformed(WHAT, () -> decodeImage(data, header, options));
                    }
                }
            };
        }
    }

    /**
     * Opens the images of a file as pages under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the page reader
     * @throws MediaException.Malformed if a header is invalid or a raster is truncated
     * @throws MediaException.LimitExceeded if the file holds more images than the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #openPages(MediaSource, ImageDecoderOptions)
     */
    public static PageReader openPages(MediaSource source) {
        return openPages(source, ImageDecoderOptions.defaults());
    }

    /**
     * Lists the headers of every image of a file.
     */
    private static List<PnmHeader> headers(byte[] data, MediaLimits limits) {
        var headers = new ArrayList<PnmHeader>();
        var offset = 0;
        while (offset >= 0) {
            var header = PnmHeader.parse(data, offset);
            headers.add(header);
            LimitChecks.checkFrames(limits, headers.size());
            offset = PnmHeader.next(data, header.end());
        }
        return List.copyOf(headers);
    }

    /**
     * Summarizes one image.
     */
    private static ImageInfo info(PnmHeader header, int count) {
        return new ImageInfo(ImageFormat.PNM, header.width(), header.height(), header.layout().hasAlpha(), false,
                count, Orientation.NORMAL);
    }

    /**
     * Decodes one image.
     */
    private static DecodedImage decodeImage(byte[] data, PnmHeader header, ImageDecoderOptions options) {
        var limits = options.limits();
        var width = header.width();
        var height = header.height();
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkChannels(limits, header.depth());
        var samples = (long) width * height * header.depth();
        if (header.isFloat()) {
            LimitChecks.checkAllocation(limits, samples * 4);
            var picture = ChannelFloatPicture.allocate(width, height, header.layout());
            readFloats(data, header, picture.pixels());
            return RasterOutput.finish(picture, ImageFormat.PNM, Orientation.NORMAL,
                    RasterOutput.linearFor(header.layout()), null, options);
        }
        Picture picture;
        if (header.maxval() > 255) {
            LimitChecks.checkAllocation(limits, samples * 2);
            var wide = Channel16Picture.allocate(width, height, header.layout());
            readWide(data, header, wide.pixels());
            picture = wide;
        } else {
            LimitChecks.checkAllocation(limits, samples);
            var narrow = Channel8Picture.allocate(width, height, header.layout());
            if (header.variant() == PnmVariant.PBM) {
                readBits(data, header, narrow.pixels());
            } else {
                readNarrow(data, header, narrow.pixels());
            }
            picture = narrow;
        }
        return RasterOutput.finish(picture, ImageFormat.PNM, Orientation.NORMAL, null, null, options);
    }

    /**
     * Reads a bitmap, 1 black and 0 white.
     */
    private static void readBits(byte[] data, PnmHeader header, byte[] pixels) {
        var width = header.width();
        var height = header.height();
        if (header.plain()) {
            var position = (int) header.raster();
            for (var index = 0; index < pixels.length; index++) {
                position = PnmHeader.skipSpace(data, position);
                pixels[index] = data[position++] == '1' ? 0 : (byte) 255;
            }
            return;
        }
        var row = (int) header.rowBytes();
        var start = (int) header.raster();
        for (var y = 0; y < height; y++) {
            var base = start + y * row;
            var out = y * width;
            for (var x = 0; x < width; x++) {
                var bit = (data[base + (x >>> 3)] >>> (7 - (x & 7))) & 1;
                pixels[out + x] = bit != 0 ? 0 : (byte) 255;
            }
        }
    }

    /**
     * Reads samples of maxval at most 255 into 8 bits.
     */
    private static void readNarrow(byte[] data, PnmHeader header, byte[] pixels) {
        var maxval = header.maxval();
        var table = new byte[256];
        for (var value = 0; value < 256; value++) {
            var clamped = Math.min(value, maxval);
            table[value] = (byte) ((clamped * 255 + maxval / 2) / maxval);
        }
        if (header.plain()) {
            var position = (int) header.raster();
            for (var index = 0; index < pixels.length; index++) {
                position = PnmHeader.skipSpace(data, position);
                var value = 0;
                while (position < data.length && data[position] >= '0' && data[position] <= '9') {
                    value = Math.min(256, value * 10 + (data[position++] - '0'));
                }
                pixels[index] = table[Math.min(value, 255)];
            }
            return;
        }
        var start = (int) header.raster();
        for (var index = 0; index < pixels.length; index++) {
            pixels[index] = table[data[start + index] & 0xFF];
        }
    }

    /**
     * Reads samples of maxval above 255 into 16 bits.
     */
    private static void readWide(byte[] data, PnmHeader header, short[] pixels) {
        var maxval = header.maxval();
        if (header.plain()) {
            var position = (int) header.raster();
            for (var index = 0; index < pixels.length; index++) {
                position = PnmHeader.skipSpace(data, position);
                var value = 0;
                while (position < data.length && data[position] >= '0' && data[position] <= '9') {
                    value = Math.min(65536, value * 10 + (data[position++] - '0'));
                }
                pixels[index] = scaleWide(value, maxval);
            }
            return;
        }
        var start = (int) header.raster();
        for (var index = 0; index < pixels.length; index++) {
            var offset = start + 2 * index;
            pixels[index] = scaleWide((data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF), maxval);
        }
    }

    /**
     * Scales a sample of a maxval above 255 to 16 bits.
     */
    private static short scaleWide(int value, int maxval) {
        var clamped = Math.min(value, maxval);
        return (short) ((clamped * 65535L + maxval / 2) / maxval);
    }

    /**
     * Reads float map samples, turning the bottom-to-top rows upright.
     */
    private static void readFloats(byte[] data, PnmHeader header, float[] pixels) {
        var rowSamples = header.width() * header.depth();
        var height = header.height();
        var start = (int) header.raster();
        var little = header.littleEndian();
        var half = header.variant() == PnmVariant.PHM;
        for (var y = 0; y < height; y++) {
            var out = (height - 1 - y) * rowSamples;
            var base = start + (long) y * rowSamples * (half ? 2 : 4);
            for (var x = 0; x < rowSamples; x++) {
                float value;
                if (half) {
                    var at = (int) base + 2 * x;
                    var bits = little ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8
                            : (data[at] & 0xFF) << 8 | (data[at + 1] & 0xFF);
                    value = Float.float16ToFloat((short) bits);
                } else {
                    var at = (int) base + 4 * x;
                    var bits = little
                            ? (data[at] & 0xFF) | (data[at + 1] & 0xFF) << 8 | (data[at + 2] & 0xFF) << 16
                            | (data[at + 3] & 0xFF) << 24
                            : (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                            | (data[at + 3] & 0xFF);
                    value = Float.intBitsToFloat(bits);
                }
                pixels[out + x] = value;
            }
        }
    }
}
