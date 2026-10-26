/* Ports OpenJPEG 2.5.3 (src/bin/jp2/opj_compress.c, the precinct sizes of its -c option; j2k.c
   opj_j2k_setup_encoder, opj_j2k_is_cinema_compliant and opj_j2k_is_imf_compliant). Copyright (c) 2002-2014,
   Universite catholique de Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.jp2.internal.EncoderConfig;
import com.github.auties00.smalt.image.jp2.internal.Jp2Writer;
import com.github.auties00.smalt.image.jp2.internal.ProgressionChange;
import com.github.auties00.smalt.image.metadata.IptcData;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Encodes pictures and channels as JPEG 2000 (ISO/IEC 15444-1): a JP2 file or a raw codestream,
 * lossless with the reversible 5-3 wavelet or lossy with the irreversible 9-7 wavelet, in one or
 * more quality layers, in one tile or many, with the block coder of ISO/IEC 15444-1 or the
 * high-throughput block coder of ISO/IEC 15444-15 (HTJ2K, {@link Jp2EncoderOptions#highThroughput()}).
 *
 * <p>A picture is coded as one component per channel at its own depth: an
 * {@link RgbaPicture} with 8 bits, an {@link Rgba16Picture} with 16 bits, a planar picture after
 * conversion to RGBA, a CMYK {@link ChannelPicture} as its four inks (8 bits for 8-bit samples, 16
 * bits for 16-bit and floating-point ones) in the enumerated CMYK color space, followed by its alpha
 * channel declared as opacity or premultiplied opacity and without its other extra channels, so that
 * {@link Jp2Decoder} with {@link com.github.auties00.smalt.image.ImageDecoderOptions#keepChannels()}
 * returns the same samples from a lossless file, and any other {@link ChannelPicture} after
 * conversion to RGBA of 16 bits for 16-bit and floating-point samples, of 8 bits otherwise. A
 * picture whose red, green and blue are equal everywhere is coded as one
 * gray component, an opaque picture without its alpha channel, and a picture with alpha with an
 * opacity channel the JP2 channel definitions declare. The JP2 file declares sRGB (or grayscale or CMYK),
 * or embeds the options' color profile, and holds the options' EXIF, XMP and IPTC-IIM metadata in
 * {@code uuid} boxes. {@link #encode(Jp2Image, Jp2EncoderOptions)} codes
 * channels of any precision from 1 to 31 bits, signed or unsigned and subsampled, at the image's
 * origin on the reference grid, such as the channels {@link Jp2Decoder#decodeChannels} returns; the
 * high-throughput block coder takes channels whose sub-bands need at most 30 magnitude bit-planes,
 * as lossless channels of up to 27 bits do.
 *
 * <p>Under a codestream profile ({@link Jp2EncoderOptions#profile()}) the image must meet the
 * profile: its component count, precision, subsampling and size, and the decomposition levels and
 * tiles the profile allows for that size. A picture is coded under the digital cinema profiles as
 * three 12-bit components (a gray picture repeated in each, samples rescaled to 12 bits), and under
 * any profile only without an alpha channel.
 *
 * <p>A codestream holds at most 65535 tiles and 16384 components; an image or options beyond
 * either are rejected with {@link IllegalArgumentException} before anything is coded. Lossless
 * output of the ISO/IEC 15444-1 block coder is byte-identical to OpenJPEG's {@code opj_compress}
 * given the same parameters and comment.
 *
 * <p>Motion JPEG 2000 video is encoded by {@link Mj2Encoder}.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation ports OpenJPEG 2.5's encoder, including the rate-distortion
 * optimization that allocates the coding passes of lossy output to layers. Code-blocks and
 * wavelet rows and columns are coded in parallel on the shared pool that
 * {@link com.github.auties00.smalt.MediaRuntime} describes.
 */
public final class Jp2Encoder {
    /**
     * The largest number of tiles of a codestream, the range of the {@code Isot} field.
     */
    private static final long MAX_TILES = 65535;

    /**
     * The largest number of components of a codestream (ISO/IEC 15444-1 Table A.9).
     */
    private static final int MAX_COMPONENTS = 16384;

    /**
     * The {@code SPcod} code-block style bit of the high-throughput block coder (ISO/IEC 15444-15 A.4).
     */
    private static final int HIGH_THROUGHPUT = 0x40;

    /**
     * The largest coordinate of the reference grid, the range of the {@code Xsiz} and {@code Ysiz} fields.
     */
    private static final long MAX_GRID = 0xFFFF_FFFFL;

    /**
     * The precision of the components of the digital cinema profiles.
     */
    private static final int CINEMA_PRECISION = 12;

    /**
     * Prevents instantiation.
     */
    private Jp2Encoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture losslessly as a JP2 file, under {@link Jp2EncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the file
     * @throws MediaException.LimitExceeded if the picture exceeds the default limits
     * @see #encode(Picture, Jp2EncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, Jp2EncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to encode
     * @param options the encoding options
     * @return the JP2 file or codestream
     * @throws IllegalArgumentException if the options' tiles divide the picture into more than
     * 65535 tiles or a tile into more than 255 tile-parts, the picture does not meet the options'
     * profile, or the options' region of interest or a progression change names a component the
     * picture does not have
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     */
    public static byte[] encode(Picture picture, Jp2EncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return encodePicture(picture, options);
        }
    }

    /**
     * Encodes a picture into a sink.
     *
     * <p>Nothing is written to the sink when encoding fails.
     *
     * @param picture the picture to encode
     * @param options the encoding options
     * @param sink the sink receiving the JP2 file or codestream
     * @throws IllegalArgumentException if the options' tiles divide the picture into more than
     * 65535 tiles or a tile into more than 255 tile-parts, the picture does not meet the options'
     * profile, or the options' region of interest or a progression change names a component the
     * picture does not have
     * @throws MediaException.LimitExceeded if the picture exceeds the options' limits
     * @throws MediaException.Io if writing fails
     */
    public static void encode(Picture picture, Jp2EncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes channels losslessly as a JP2 file, under {@link Jp2EncoderOptions#defaults()}.
     *
     * @param image the channels
     * @return the file
     * @throws IllegalArgumentException if a channel does not cover the image, its samples exceed
     * its precision, or the image has more than 16384 channels
     * @throws MediaException.LimitExceeded if the image exceeds the default limits, its channel
     * limit included
     * @see #encode(Jp2Image, Jp2EncoderOptions)
     */
    public static byte[] encode(Jp2Image image) {
        return encode(image, Jp2EncoderOptions.defaults());
    }

    /**
     * Encodes channels into a sink.
     *
     * <p>Nothing is written to the sink when encoding fails.
     *
     * @param image the channels
     * @param options the encoding options
     * @param sink the sink receiving the JP2 file or codestream
     * @throws IllegalArgumentException for the reasons {@link #encode(Jp2Image, Jp2EncoderOptions)}
     * lists
     * @throws MediaException.LimitExceeded if the image exceeds the options' limits, its channel
     * limit included
     * @throws MediaException.Io if writing fails
     * @see #encode(Jp2Image, Jp2EncoderOptions)
     */
    public static void encode(Jp2Image image, Jp2EncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(image, options));
    }

    /**
     * Encodes channels.
     *
     * <p>The image is placed on the reference grid at its own origin ({@link Jp2Image#x0()},
     * {@link Jp2Image#y0()}), not at the options' image origin. Every channel must cover the image:
     * a channel subsampled by {@code (dx, dy)} starts at {@code ceil(x0 / dx)},
     * {@code ceil(y0 / dy)} and holds {@code ceil((x0 + width) / dx) - ceil(x0 / dx)} by
     * {@code ceil((y0 + height) / dy) - ceil(y0 / dy)} samples. The color space and ICC profile of
     * the image are declared in a JP2 file (the options' color profile takes precedence), its
     * opacity channel, if any, is declared as such, and its EXIF block and XMP packet are written
     * unless the options carry their own.
     *
     * @param image the channels
     * @param options the encoding options
     * @return the JP2 file or codestream
     * @throws IllegalArgumentException if a channel does not cover the image, its samples exceed
     * its precision, the image has more than 16384 channels or extends beyond 4294967295 on the
     * reference grid, the options' tile origin lies right of or below the image origin or its first
     * tile does not reach it, the options' tiles divide the image into more than 65535 tiles or a
     * tile into more than 255 tile-parts, the image does not meet the options' profile, a progression
     * change starts at a component the image does not have, the options' region of interest names a
     * component the image does not have or scales a coefficient beyond 25
     * magnitude bit-planes, the options' guard bits leave a lossless sub-band fewer bit-planes than
     * its coefficients need, or the options select the high-throughput block coder and a sub-band of
     * a channel needs more than 30 magnitude bit-planes (lossless channels deeper than 27 bits, or 29
     * bits without decomposition levels)
     * @throws MediaException.LimitExceeded if the image exceeds the options' limits, its channel
     * limit included
     */
    public static byte[] encode(Jp2Image image, Jp2EncoderOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        var channels = image.channels();
        var count = channels.size();
        checkComponents(count, options);
        var x0 = image.x0();
        var y0 = image.y0();
        checkOrigin(x0, y0, image.width(), image.height(), options);
        var precisions = new int[count];
        var signed = new boolean[count];
        var dx = new int[count];
        var dy = new int[count];
        var planes = new int[count][];
        var alpha = -1;
        for (var c = 0; c < count; c++) {
            var channel = channels.get(c);
            precisions[c] = channel.precision();
            signed[c] = channel.signed();
            dx[c] = channel.subsamplingX();
            dy[c] = channel.subsamplingY();
            if (precisions[c] < 1 || precisions[c] > 31) {
                throw new IllegalArgumentException("unsupported precision " + precisions[c]);
            }
            var cx0 = Math.ceilDiv(x0, dx[c]);
            var cy0 = Math.ceilDiv(y0, dy[c]);
            if (channel.x0() != cx0 || channel.y0() != cy0
                    || channel.width() != Math.ceilDiv(x0 + image.width(), dx[c]) - cx0
                    || channel.height() != Math.ceilDiv(y0 + image.height(), dy[c]) - cy0) {
                throw new IllegalArgumentException("channel " + c + " does not cover the image");
            }
            var min = signed[c] ? -(1L << (precisions[c] - 1)) : 0;
            var max = signed[c] ? (1L << (precisions[c] - 1)) - 1 : (1L << precisions[c]) - 1;
            for (var sample : channel.samples()) {
                if (sample < min || sample > max) {
                    throw new IllegalArgumentException("sample " + sample + " of channel " + c
                            + " exceeds its precision");
                }
            }
            planes[c] = channel.samples();
            if (alpha < 0 && (channel.type() == Jp2ChannelType.OPACITY
                    || channel.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY)) {
                alpha = c;
            }
        }
        var colorSpace = image.colorSpace().code() >= 0 ? image.colorSpace().code()
                : count - (alpha >= 0 ? 1 : 0) >= 3 ? Jp2ColorSpace.SRGB.code() : Jp2ColorSpace.GRAYSCALE.code();
        var icc = options.colorProfile().map(ColorProfile::toByteArray).orElse(image.iccProfile().orElse(null));
        var subsampled = false;
        for (var c = 0; c < Math.min(count, 3); c++) {
            subsampled |= dx[c] != dx[0] || dy[c] != dy[0];
        }
        var exif = options.exif().map(ExifData::toBytes).orElse(image.exif().orElse(null));
        var xmp = options.xmp().map(XmpPacket::toBytes).orElse(image.xmp().orElse(null));
        try (var _ = ParallelBands.operation()) {
            return write(x0, y0, image.width(), image.height(), precisions, signed, dx, dy, planes,
                    count >= 3 && !subsampled && colorSpace != Jp2ColorSpace.SYCC.code(), colorSpace, icc,
                    alpha, alpha >= 0 && channels.get(alpha).type() == Jp2ChannelType.PREMULTIPLIED_OPACITY, exif,
                    xmp, options);
        }
    }

    /**
     * Encodes a picture after splitting it into components.
     *
     * @param picture the picture to encode
     * @param options the encoding options
     * @return the JP2 file or codestream
     */
    private static byte[] encodePicture(Picture picture, Jp2EncoderOptions options) {
        var width = picture.width();
        var height = picture.height();
        LimitChecks.checkPicture(options.limits(), width, height);
        checkOrigin(options.imageOriginX(), options.imageOriginY(), width, height, options);
        var profile = options.profile().orElse(null);
        if (profile != null && picture.hasAlpha()) {
            throw new IllegalArgumentException("the " + profile + " profile codes no alpha channel");
        }
        if (picture instanceof ChannelPicture channels && channels.layout().colorModel() == ColorModel.CMYK) {
            return encodeCmyk(channels, options);
        }
        LimitChecks.checkAllocation(options.limits(), (long) width * height * 4 * 4);
        int[][] channels;
        int precision;
        var alpha = picture.hasAlpha();
        if (picture instanceof Rgba16Picture wide) {
            precision = 16;
            channels = split16(wide);
        } else if (picture instanceof ChannelPicture deep && deep.bitDepth() > 8) {
            precision = 16;
            channels = split16(ImageOps.toRgba16(deep));
        } else {
            precision = 8;
            channels = split8(picture instanceof RgbaPicture rgba ? rgba : ImageOps.toRgba(picture));
        }
        if (profile != null && profile.isCinema()) {
            for (var c = 0; c < 3; c++) {
                rescale(channels[c], precision, CINEMA_PRECISION);
            }
            precision = CINEMA_PRECISION;
        }
        var gray = Arrays.equals(channels[0], channels[1]) && Arrays.equals(channels[0], channels[2])
                && (profile == null || !profile.isCinema());
        var count = (gray ? 1 : 3) + (alpha ? 1 : 0);
        var planes = new int[count][];
        planes[0] = channels[0];
        if (!gray) {
            planes[1] = channels[1];
            planes[2] = channels[2];
        }
        if (alpha) {
            planes[count - 1] = channels[3];
        }
        var precisions = new int[count];
        Arrays.fill(precisions, precision);
        var ones = new int[count];
        Arrays.fill(ones, 1);
        var icc = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
        return write(options.imageOriginX(), options.imageOriginY(), width, height, precisions, new boolean[count],
                ones, ones, planes, !gray, gray ? Jp2ColorSpace.GRAYSCALE.code() : Jp2ColorSpace.SRGB.code(), icc,
                alpha ? count - 1 : -1, false, options.exif().map(ExifData::toBytes).orElse(null),
                options.xmp().map(XmpPacket::toBytes).orElse(null), options);
    }

    /**
     * Rescales samples from one unsigned precision to another, rounding to the nearest value.
     *
     * @param samples the samples, rescaled in place
     * @param from the precision of the samples
     * @param to the target precision
     */
    private static void rescale(int[] samples, int from, int to) {
        var source = (1L << from) - 1;
        var target = (1L << to) - 1;
        for (var i = 0; i < samples.length; i++) {
            samples[i] = (int) ((samples[i] * target + source / 2) / source);
        }
    }

    /**
     * Encodes a CMYK channel picture as four ink components and, when its layout has one, an
     * opacity component, declaring the enumerated CMYK color space.
     *
     * @param picture the CMYK picture; floating-point samples are first converted to 16 bits
     * @param options the encoding options
     * @return the JP2 file or codestream
     */
    private static byte[] encodeCmyk(ChannelPicture picture, Jp2EncoderOptions options) {
        var width = picture.width();
        var height = picture.height();
        var layout = picture.layout();
        var alphaIndex = layout.alphaIndex();
        var count = alphaIndex >= 0 ? 5 : 4;
        LimitChecks.checkAllocation(options.limits(), (long) width * height * count * 4);
        var integer = picture instanceof ChannelFloatPicture ? ImageOps.toChannel16(picture) : picture;
        var precision = integer instanceof Channel8Picture ? 8 : 16;
        var planes = new int[count][width * height];
        var stride = integer.stride();
        var samples = layout.count();
        var offsets = new int[]{0, 1, 2, 3, alphaIndex};
        for (var c = 0; c < count; c++) {
            var plane = planes[c];
            var offset = offsets[c];
            switch (integer) {
                case Channel8Picture eight -> {
                    var pixels = eight.pixels();
                    for (var y = 0; y < height; y++) {
                        for (int x = 0, at = y * stride + offset, i = y * width; x < width; x++, at += samples, i++) {
                            plane[i] = pixels[at] & 0xFF;
                        }
                    }
                }
                case Channel16Picture sixteen -> {
                    var pixels = sixteen.pixels();
                    for (var y = 0; y < height; y++) {
                        for (int x = 0, at = y * stride + offset, i = y * width; x < width; x++, at += samples, i++) {
                            plane[i] = pixels[at] & 0xFFFF;
                        }
                    }
                }
                case ChannelFloatPicture _ -> throw new AssertionError();
            }
        }
        var precisions = new int[count];
        Arrays.fill(precisions, precision);
        var ones = new int[count];
        Arrays.fill(ones, 1);
        var icc = options.colorProfile().map(ColorProfile::toByteArray).orElse(null);
        return write(options.imageOriginX(), options.imageOriginY(), width, height, precisions, new boolean[count],
                ones, ones, planes, false, Jp2ColorSpace.CMYK.code(), icc, alphaIndex >= 0 ? 4 : -1,
                layout.isAlphaPremultiplied(), options.exif().map(ExifData::toBytes).orElse(null),
                options.xmp().map(XmpPacket::toBytes).orElse(null), options);
    }

    /**
     * Checks that a component count fits a codestream and the options' limits.
     *
     * @param count the number of components
     * @param options the encoding options
     * @throws IllegalArgumentException if {@code count} is above 16384
     * @throws MediaException.LimitExceeded if {@code count} is above the limits' channel count
     */
    private static void checkComponents(int count, Jp2EncoderOptions options) {
        if (count > MAX_COMPONENTS) {
            throw new IllegalArgumentException(count + " channels exceed the " + MAX_COMPONENTS
                    + " components of a JPEG 2000 codestream");
        }
        LimitChecks.checkChannels(options.limits(), count);
    }

    /**
     * Checks that an image fits the reference grid at its origin, that the options' tile grid starts at or before
     * the origin and reaches it, and that the tiles divide the image into no more tiles than a codestream numbers.
     *
     * @param x0 the left edge of the image on the reference grid
     * @param y0 the top edge of the image on the reference grid
     * @param width the image width
     * @param height the image height
     * @param options the encoding options
     * @throws IllegalArgumentException if the image extends beyond 4294967295, the tile origin lies right of or
     * below the image origin or its first tile does not reach it, or the tile count is above 65535
     */
    private static void checkOrigin(long x0, long y0, int width, int height, Jp2EncoderOptions options) {
        if (x0 + width > MAX_GRID || y0 + height > MAX_GRID) {
            throw new IllegalArgumentException("a " + width + "x" + height + " image at " + x0 + "," + y0
                    + " extends beyond the reference grid's " + MAX_GRID);
        }
        var tx0 = options.tileOriginX();
        var ty0 = options.tileOriginY();
        if (tx0 > x0 || ty0 > y0 || (options.tileWidth() != 0 && (tx0 + options.tileWidth() <= x0
                || ty0 + options.tileHeight() <= y0))) {
            throw new IllegalArgumentException("the tile origin " + tx0 + "," + ty0 + " does not start the tiles at "
                    + "or before the image origin " + x0 + "," + y0 + " with a tile that reaches it");
        }
        var tiles = (options.tileWidth() == 0 ? 1L : Math.ceilDiv(x0 + width - tx0, options.tileWidth()))
                * (options.tileHeight() == 0 ? 1L : Math.ceilDiv(y0 + height - ty0, options.tileHeight()));
        if (tiles > MAX_TILES) {
            throw new IllegalArgumentException("tiles of " + options.tileWidth() + "x" + options.tileHeight()
                    + " divide a " + width + "x" + height + " image into " + tiles + " tiles, above " + MAX_TILES);
        }
    }

    /**
     * Splits an 8-bit picture into channels.
     *
     * @param picture the picture whose channels are split
     * @return the red, green, blue and alpha samples
     */
    private static int[][] split8(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var channels = new int[4][width * height];
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            var row = y * picture.stride();
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var p = row + 4 * x;
                channels[0][i] = pixels[p] & 0xFF;
                channels[1][i] = pixels[p + 1] & 0xFF;
                channels[2][i] = pixels[p + 2] & 0xFF;
                channels[3][i] = pixels[p + 3] & 0xFF;
            }
        }
        return channels;
    }

    /**
     * Splits a 16-bit picture into channels.
     *
     * @param picture the picture whose channels are split
     * @return the red, green, blue and alpha samples
     */
    private static int[][] split16(Rgba16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        var channels = new int[4][width * height];
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            var row = y * picture.stride();
            for (var x = 0; x < width; x++) {
                var i = y * width + x;
                var p = row + 4 * x;
                channels[0][i] = pixels[p] & 0xFFFF;
                channels[1][i] = pixels[p + 1] & 0xFFFF;
                channels[2][i] = pixels[p + 2] & 0xFFFF;
                channels[3][i] = pixels[p + 3] & 0xFFFF;
            }
        }
        return channels;
    }

    /**
     * Returns the precinct size exponent of a resolution, as {@code opj_compress} derives it from
     * the size halved once per level: 1 below a size of 1, the size's base-2 logarithm otherwise,
     * at least 1 above the lowest resolution.
     *
     * @param size the halved size
     * @param resolution the resolution level
     * @return the exponent
     */
    private static int precinctExponent(int size, int resolution) {
        if (size < 1) {
            return 1;
        }
        var exponent = 31 - Integer.numberOfLeadingZeros(size);
        return resolution > 0 ? Math.max(1, exponent) : exponent;
    }

    /**
     * {@return the {@code Progression order} code of a progression order (ISO/IEC 15444-1 Table A.16)}
     *
     * @param order the progression order
     */
    private static int progressionCode(Jp2ProgressionOrder order) {
        return switch (order) {
            case LRCP -> ProgressionChange.LRCP;
            case RLCP -> ProgressionChange.RLCP;
            case RPCL -> ProgressionChange.RPCL;
            case PCRL -> ProgressionChange.PCRL;
            case CPRL -> ProgressionChange.CPRL;
        };
    }

    /**
     * Encodes components.
     *
     * @param x0 the left edge of the image on the reference grid
     * @param y0 the top edge of the image on the reference grid
     * @param width the image width
     * @param height the image height
     * @param precisions the precision of each component
     * @param signed whether each component is signed
     * @param dx the horizontal subsampling of each component
     * @param dy the vertical subsampling of each component
     * @param planes the samples of each component
     * @param transform whether the first three components may go through the component transform
     * @param colorSpace the enumerated color space of a JP2 file
     * @param icc the ICC profile of a JP2 file, or {@code null}
     * @param alpha the opacity component, or {@code -1}
     * @param premultiplied whether the opacity component is premultiplied opacity
     * @param exif the EXIF block of a JP2 file, or {@code null}
     * @param xmp the XMP packet of a JP2 file, or {@code null}
     * @param options the encoding options
     * @return the JP2 file or codestream
     */
    private static byte[] write(long x0, long y0, int width, int height, int[] precisions, boolean[] signed, int[] dx,
                                int[] dy, int[][] planes, boolean transform, int colorSpace, byte[] icc, int alpha,
                                boolean premultiplied, byte[] exif, byte[] xmp, Jp2EncoderOptions options) {
        LimitChecks.checkPicture(options.limits(), width, height);
        var count = precisions.length;
        var profile = options.profile().orElse(null);
        if (profile != null) {
            checkProfile(profile, options, x0, y0, width, height, precisions, signed, dx, dy);
        }
        var resolutions = options.decompositionLevels() + 1;
        int[] precinctWidths = null;
        int[] precinctHeights = null;
        var widths = options.precinctWidths();
        if (widths.length > 0) {
            var heights = options.precinctHeights();
            precinctWidths = new int[resolutions];
            precinctHeights = new int[resolutions];
            for (var r = resolutions - 1; r >= 0; r--) {
                var p = resolutions - 1 - r;
                precinctWidths[r] = precinctExponent(precinctSize(widths, p), r);
                precinctHeights[r] = precinctExponent(precinctSize(heights, p), r);
            }
        }
        var style = options.highThroughput() ? HIGH_THROUGHPUT : 0;
        for (var option : options.codeBlockStyles()) {
            style |= option.bit();
        }
        var ratios = options.compressionRatios();
        var qualities = options.psnrTargets();
        for (var i = 0; i < ratios.length; i++) {
            if (ratios[i] <= 1.0) {
                ratios[i] = 0;
            }
        }
        var capRatio = 0.0;
        var maxBytes = options.maxCodestreamBytes().orElse(0);
        if (maxBytes > 0) {
            var componentWidth = Math.ceilDiv(x0 + width, dx[0]) - Math.ceilDiv(x0, dx[0]);
            var componentHeight = Math.ceilDiv(y0 + height, dy[0]) - Math.ceilDiv(y0, dy[0]);
            capRatio = ((double) count * componentWidth * componentHeight * precisions[0])
                    / ((double) maxBytes * 8 * dx[0] * dy[0]);
            for (var i = 0; i < ratios.length; i++) {
                ratios[i] = Math.max(ratios[i], capRatio);
            }
        }
        var changes = new ArrayList<ProgressionChange>();
        for (var change : options.progressionChanges()) {
            if (change.componentStart() >= count) {
                throw new IllegalArgumentException("a progression change starts at component " + change.componentStart()
                        + " of an image of " + count + " components");
            }
            changes.add(new ProgressionChange(change.resolutionStart(), change.componentStart(), change.layerEnd(),
                    change.resolutionEnd(), change.componentEnd(), progressionCode(change.order())));
        }
        var tileX0 = options.tileOriginX();
        var tileY0 = options.tileOriginY();
        var tileWidth = options.tileWidth() == 0 ? x0 + width - tileX0 : options.tileWidth();
        var tileHeight = options.tileHeight() == 0 ? y0 + height - tileY0 : options.tileHeight();
        var config = new EncoderConfig(x0, y0, width, height, tileX0, tileY0, tileWidth, tileHeight, precisions,
                signed, dx, dy, resolutions, Integer.numberOfTrailingZeros(options.codeBlockWidth()),
                Integer.numberOfTrailingZeros(options.codeBlockHeight()), style, options.isReversible(),
                options.guardBits(), precinctWidths, precinctHeights, progressionCode(options.progressionOrder()),
                List.copyOf(changes), profile == Jp2Profile.CINEMA_4K,
                options.tilePartDivision().map(Jp2TilePartDivision::letter).orElse((char) 0),
                ratios.length > 0 ? ratios : null, ratios.length > 0 ? null : qualities, capRatio,
                options.maxComponentBytes().orElse(0), transform && options.colorTransform(),
                region(options, x0, y0, width, height, count), options.profileCode(), options.sopMarkers(),
                options.ephMarkers(), options.tlmMarkers(), options.pltMarkers(), options.comment().orElse(null));
        var codestream = Jp2Writer.codestream(config, planes, options.limits());
        if (options.format() == Jp2Format.CODESTREAM) {
            return codestream;
        }
        var density = options.density().filter(PixelDensity::isPhysical).orElse(null);
        var iptc = options.iptc().filter(data -> !data.isEmpty()).map(IptcData::toBytes).orElse(null);
        return Jp2Writer.jp2(codestream, config, colorSpace, icc, alpha, premultiplied, density, exif, xmp, iptc);
    }

    /**
     * Returns the precinct size a list of sizes gives the resolution {@code p} levels below the highest: the
     * listed size, or the last listed size halved once per level beyond the list.
     *
     * @param sizes the sizes, from the highest resolution down
     * @param p the number of levels below the highest resolution
     * @return the size, possibly 0
     */
    private static int precinctSize(int[] sizes, int p) {
        return p < sizes.length ? sizes[p] : sizes[sizes.length - 1] >> Math.min(31, p - sizes.length + 1);
    }

    /**
     * Converts the options' region of interest to the reference grid.
     *
     * @param options the encoding options
     * @param x0 the left edge of the image on the reference grid
     * @param y0 the top edge of the image on the reference grid
     * @param width the image width
     * @param height the image height
     * @param components the number of components
     * @return the region, or {@code null} for none
     * @throws IllegalArgumentException if the region names a component the image does not have
     */
    private static EncoderConfig.Region region(Jp2EncoderOptions options, long x0, long y0, int width, int height,
                                               int components) {
        var region = options.regionOfInterest().orElse(null);
        return switch (region) {
            case null -> null;
            case Jp2RegionOfInterest.Component(var component, var shift) -> {
                if (component >= components) {
                    throw new IllegalArgumentException("the region of interest names component " + component
                            + " of an image of " + components + " components");
                }
                yield new EncoderConfig.Region(component, shift, 0, 0, 0, 0);
            }
            case Jp2RegionOfInterest.Rectangle(var x, var y, var w, var h) -> new EncoderConfig.Region(-1, 0,
                    x0 + Math.min(x, width), y0 + Math.min(y, height), x0 + Math.min((long) x + w, width),
                    y0 + Math.min((long) y + h, height));
        };
    }

    /**
     * Checks an image against the rules of a codestream profile that depend on it (ISO/IEC 15444-1 Tables A.48 to
     * A.52).
     *
     * @param profile the profile the options select
     * @param options the encoding options
     * @param x0 the left edge of the image on the reference grid
     * @param y0 the top edge of the image on the reference grid
     * @param width the image width
     * @param height the image height
     * @param precisions the precision of each component
     * @param signed whether each component is signed
     * @param dx the horizontal subsampling of each component
     * @param dy the vertical subsampling of each component
     * @throws IllegalArgumentException if the image breaks a rule
     */
    private static void checkProfile(Jp2Profile profile, Jp2EncoderOptions options, long x0, long y0, int width,
                                     int height, int[] precisions, boolean[] signed, int[] dx, int[] dy) {
        var name = profile.name();
        var count = precisions.length;
        require(x0 == 0 && y0 == 0, name + " places the image at the origin");
        if (profile.isCinema()) {
            require(count == 3, name + " codes three components, not " + count);
            for (var c = 0; c < count; c++) {
                require(precisions[c] == CINEMA_PRECISION && !signed[c] && dx[c] == 1 && dy[c] == 1,
                        name + " codes unsigned 12-bit components without subsampling");
            }
            var maxWidth = profile == Jp2Profile.CINEMA_2K ? 2048 : 4096;
            var maxHeight = profile == Jp2Profile.CINEMA_2K ? 1080 : 2160;
            require(width <= maxWidth && height <= maxHeight, name + " codes at most " + maxWidth + "x" + maxHeight
                    + " samples, not " + width + "x" + height);
            return;
        }
        require(count <= 3, name + " codes at most three components, not " + count);
        for (var c = 0; c < count; c++) {
            require(precisions[c] >= 8 && precisions[c] <= 16 && !signed[c],
                    name + " codes unsigned components of 8 to 16 bits");
            require(dy[c] == 1 && (c == 0 ? dx[c] == 1 : c == 1 ? dx[c] <= 2 : dx[c] == dx[c - 1]),
                    name + " subsamples only the second and third components, horizontally by 2");
        }
        var scale = switch (profile) {
            case IMF_2K, IMF_2K_REVERSIBLE -> 1;
            case IMF_4K, IMF_4K_REVERSIBLE -> 2;
            default -> 4;
        };
        require(width <= 2048 * scale && height <= 1556 * scale, name + " codes at most " + 2048 * scale + "x"
                + 1556 * scale + " samples, not " + width + "x" + height);
        var tileWidth = options.tileWidth() == 0 ? width : options.tileWidth();
        var tileHeight = options.tileHeight() == 0 ? height : options.tileHeight();
        var covering = tileWidth >= width && tileHeight >= height;
        if (!profile.isReversibleImf()) {
            require(covering, name + " codes one tile");
            return;
        }
        require(covering || (tileWidth == tileHeight && (tileWidth == 1024 || (tileWidth == 2048 && scale >= 2)
                || (tileWidth == 4096 && scale >= 4))), name + " codes one tile or square tiles of 1024"
                + (scale >= 2 ? ", 2048" : "") + (scale >= 4 ? " or 4096" : "") + " samples");
        var levels = options.decompositionLevels();
        var maxLevels = tileWidth >= 8192 ? 7 : tileWidth >= 4096 ? 6 : tileWidth >= 2048 ? 5 : 4;
        require(tileWidth < 1024 || levels <= Math.min(maxLevels, 4 + Integer.numberOfTrailingZeros(scale) + 1),
                name + " allows at most " + Math.min(maxLevels, 4 + Integer.numberOfTrailingZeros(scale) + 1)
                        + " decomposition levels for tiles " + tileWidth + " samples wide");
    }

    /**
     * Throws when a rule of a profile is broken.
     *
     * @param rule whether the rule holds
     * @param message the description of the rule
     * @throws IllegalArgumentException if {@code rule} is {@code false}
     */
    private static void require(boolean rule, String message) {
        if (!rule) {
            throw new IllegalArgumentException(message);
        }
    }
}
