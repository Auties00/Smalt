/* Ports OpenJPEG 2.5.3 (src/bin/jp2/opj_compress.c, the precinct sizes of its -c option). Copyright (c)
   2002-2014, Universite catholique de Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jp2;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.jp2.internal.EncoderConfig;
import com.github.auties00.smalt.image.jp2.internal.Jp2Writer;
import com.github.auties00.smalt.image.jp2.internal.ProgressionChange;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Arrays;
import java.util.Objects;

/**
 * Encodes pictures and channels as JPEG 2000 (ISO/IEC 15444-1): a JP2 file or a raw codestream,
 * lossless with the reversible 5-3 wavelet or lossy with the irreversible 9-7 wavelet, in one or
 * more quality layers, in one tile or many.
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
 * or embeds the options' color profile. {@link #encode(Jp2Image, Jp2EncoderOptions)} codes
 * channels of any precision from 1 to 31 bits, signed or unsigned and subsampled, such as the
 * channels {@link Jp2Decoder#decodeChannels} returns.
 *
 * <p>A codestream holds at most 65535 tiles and 16384 components; an image or options beyond
 * either are rejected with {@link IllegalArgumentException} before anything is coded. Lossless
 * output is byte-identical to OpenJPEG's {@code opj_compress} given the same parameters and
 * comment.
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
     * 65535 tiles
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
     * 65535 tiles
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
     * @param image the channels; its origin must be zero
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
     * @param image the channels; its origin must be zero
     * @param options the encoding options
     * @param sink the sink receiving the JP2 file or codestream
     * @throws IllegalArgumentException if a channel does not cover the image, its samples exceed
     * its precision, the image has more than 16384 channels, or the options' tiles divide it into
     * more than 65535 tiles
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
     * <p>Every channel must cover the image from its origin: a channel subsampled by
     * {@code (dx, dy)} holds {@code ceil(width / dx)} by {@code ceil(height / dy)} samples. The
     * color space and ICC profile of the image are declared in a JP2 file (the options' color
     * profile takes precedence), and its opacity channel, if any, is declared as such.
     *
     * @param image the channels; its origin must be zero
     * @param options the encoding options
     * @return the JP2 file or codestream
     * @throws IllegalArgumentException if a channel does not cover the image, its samples exceed
     * its precision, the image has more than 16384 channels, or the options' tiles divide it into
     * more than 65535 tiles
     * @throws MediaException.LimitExceeded if the image exceeds the options' limits, its channel
     * limit included
     */
    public static byte[] encode(Jp2Image image, Jp2EncoderOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        if (image.x0() != 0 || image.y0() != 0) {
            throw new IllegalArgumentException("the image origin must be zero");
        }
        var channels = image.channels();
        var count = channels.size();
        checkComponents(count, options);
        checkTiles(image.width(), image.height(), options);
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
            if (channel.x0() != 0 || channel.y0() != 0 || channel.width() != Math.ceilDiv(image.width(), dx[c])
                    || channel.height() != Math.ceilDiv(image.height(), dy[c])) {
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
        try (var _ = ParallelBands.operation()) {
            return write(image.width(), image.height(), precisions, signed, dx, dy, planes,
                    count >= 3 && !subsampled && colorSpace != Jp2ColorSpace.SYCC.code(), colorSpace, icc,
                    alpha, alpha >= 0 && channels.get(alpha).type() == Jp2ChannelType.PREMULTIPLIED_OPACITY, options);
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
        checkTiles(width, height, options);
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
        var gray = Arrays.equals(channels[0], channels[1]) && Arrays.equals(channels[0], channels[2]);
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
        return write(width, height, precisions, new boolean[count], ones, ones, planes, !gray,
                gray ? Jp2ColorSpace.GRAYSCALE.code() : Jp2ColorSpace.SRGB.code(), icc, alpha ? count - 1 : -1,
                false, options);
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
        return write(width, height, precisions, new boolean[count], ones, ones, planes, false,
                Jp2ColorSpace.CMYK.code(), icc, alphaIndex >= 0 ? 4 : -1, layout.isAlphaPremultiplied(), options);
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
     * Checks that the options' tiles divide an image into no more tiles than a codestream numbers.
     *
     * @param width the image width
     * @param height the image height
     * @param options the encoding options
     * @throws IllegalArgumentException if the tile count is above 65535
     */
    private static void checkTiles(int width, int height, Jp2EncoderOptions options) {
        var tiles = (options.tileWidth() == 0 ? 1L : Math.ceilDiv(width, options.tileWidth()))
                * (options.tileHeight() == 0 ? 1L : Math.ceilDiv(height, options.tileHeight()));
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
     * @param options the encoding options
     * @return the JP2 file or codestream
     */
    private static byte[] write(int width, int height, int[] precisions, boolean[] signed, int[] dx, int[] dy,
                                int[][] planes, boolean transform, int colorSpace, byte[] icc, int alpha,
                                boolean premultiplied, Jp2EncoderOptions options) {
        LimitChecks.checkPicture(options.limits(), width, height);
        var resolutions = options.decompositionLevels() + 1;
        int[] precinctWidths = null;
        int[] precinctHeights = null;
        if (options.precinctWidth() != 0) {
            precinctWidths = new int[resolutions];
            precinctHeights = new int[resolutions];
            for (var r = resolutions - 1; r >= 0; r--) {
                var p = resolutions - 1 - r;
                precinctWidths[r] = precinctExponent(options.precinctWidth() >> p, r);
                precinctHeights[r] = precinctExponent(options.precinctHeight() >> p, r);
            }
        }
        var style = 0;
        for (var option : options.codeBlockStyles()) {
            style |= option.bit();
        }
        var ratios = options.compressionRatios();
        var qualities = options.psnrTargets();
        if (ratios.length > 0) {
            for (var i = 0; i < ratios.length; i++) {
                if (ratios[i] <= 1.0) {
                    ratios[i] = 0;
                }
            }
        }
        var config = new EncoderConfig(width, height, options.tileWidth() == 0 ? width : options.tileWidth(),
                options.tileHeight() == 0 ? height : options.tileHeight(), precisions, signed, dx, dy, resolutions,
                Integer.numberOfTrailingZeros(options.codeBlockWidth()),
                Integer.numberOfTrailingZeros(options.codeBlockHeight()), style, options.isReversible(), precinctWidths,
                precinctHeights, progressionCode(options.progressionOrder()), ratios.length > 0 ? ratios : null,
                ratios.length > 0 ? null : qualities, transform && options.colorTransform(), options.sopMarkers(),
                options.ephMarkers(), options.comment().orElse(null));
        var codestream = Jp2Writer.codestream(config, planes, options.limits());
        if (options.format() == Jp2Format.CODESTREAM) {
            return codestream;
        }
        return Jp2Writer.jp2(codestream, config, colorSpace, icc, alpha, premultiplied);
    }
}
