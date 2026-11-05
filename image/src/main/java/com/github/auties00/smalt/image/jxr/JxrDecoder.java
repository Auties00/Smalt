package com.github.auties00.smalt.image.jxr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelDensity;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.image.jxr.internal.JxrCodestream;
import com.github.auties00.smalt.image.jxr.internal.JxrContainer;
import com.github.auties00.smalt.image.jxr.internal.JxrHeader;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG XR images (ITU-T T.832, ISO/IEC 29199-2; the HD Photo and Windows Media Photo formats).
 *
 * <p>Supported: the container of Annex A (or a bare codestream), its image codestream and planar alpha codestream,
 * and every coding tool of the codestream: the three overlap modes, the frequency and spatial bitstream orders,
 * tiles with soft and hard boundaries and an index table, windowing, the trimmed and absent flexbits and the
 * absent highpass and lowpass bands, uniform and per-tile and per-macroblock quantization, the scaled arithmetic of
 * lossless coding, the codec subversions 0, 1 and 9, interleaved alpha planes; the internal color formats gray,
 * YUV 4:2:0, 4:2:2 and 4:4:4, CMYK and up to 16 independent channels; and every output format: black and white,
 * 8 and 16-bit unsigned, 16 and 32-bit fixed point, 16 and 32-bit floating point, RGBE, 5-bit RGB, RGB565 and
 * 10-bit RGB, in gray, RGB, CMYK, independent channels and raw YUV. Samples decode bit for bit as the reference
 * jxrlib decoder decodes them.
 *
 * <p>Gray, RGB and CMYK images of up to 8 bits per sample decode to packed RGBA (CMYK converted, or kept as inks
 * with {@link ImageDecoderOptions#keepChannels()}); 10 and 16-bit ones to packed RGBA of 8 bits, or of 16 bits with
 * {@link ImageDecoderOptions#highBitDepth()}; fixed-point, floating-point and RGBE ones, linear-light (scRGB), to
 * packed RGBA, or to a {@link ChannelFloatPicture} with {@code keepChannels}; independent channels keep their
 * order as unnamed {@link ChannelType#INK} channels; raw YUV output decodes to a {@link YuvPicture} or
 * {@link Yuv16Picture}. Premultiplied alpha is reported as such. The orientation of the container (of the image
 * header for a bare codestream) is reported, not applied. The embedded ICC profile, EXIF and XMP metadata and the
 * resolution are reported.
 *
 * <p>This class is thread-safe.
 */
public final class JxrDecoder {
    private static final String WHAT = "JPEG XR image";

    private JxrDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a JPEG XR image from its headers.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the codestream is of a codec version this decoder does not know
     * @throws MediaException.LimitExceeded if the file exceeds the default byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
                var container = JxrContainer.read(data);
                var header = JxrHeader.parse(data, container.imageOffset(), container.imageEnd());
                var alpha = header.alpha() || container.alphaOffset() >= 0;
                return new ImageInfo(ImageFormat.JXR, header.width(), header.height(), alpha, false, 1,
                        orientation(container, header));
            });
        }
    }

    /**
     * Decodes a JPEG XR image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the codestream uses a combination of formats the reference decoder
     * does not decode
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a JPEG XR image.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of the decoded image), the bit depth, the channel and the sRGB
     * conversion choices; the size hint is ignored
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the codestream uses a combination of formats the reference decoder
     * does not decode
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, () -> probe(source), whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), false);
                return decode(data, whole);
            }));
        }
    }

    /**
     * Decodes the image codestream and the planar alpha codestream.
     */
    private static JxrCodestream.Samples samples(byte[] data, JxrContainer container, MediaLimits limits) {
        var image = JxrCodestream.decode(data, container.imageOffset(), container.imageEnd(), limits);
        if (container.alphaOffset() < 0 || image.alpha()) {
            return image;
        }
        var alpha = JxrCodestream.decode(data, container.alphaOffset(), container.alphaEnd(), limits);
        if (alpha.width() != image.width() || alpha.height() != image.height()
                || alpha.format() != JxrCodestream.GRAY) {
            throw new MediaException.Malformed("the JPEG XR alpha codestream does not match the image");
        }
        var channels = java.util.Arrays.copyOf(image.channels(), image.channels().length + 1);
        channels[image.channels().length] = alpha.channels()[0];
        return new JxrCodestream.Samples(image.header(), image.format(), image.width(), image.height(), channels,
                true);
    }

    /**
     * Decodes a whole file.
     */
    private static DecodedImage decode(byte[] data, ImageDecoderOptions options) {
        var container = JxrContainer.read(data);
        var samples = samples(data, container, options.limits());
        var header = samples.header();
        var picture = picture(samples, container.premultiplied());
        ColorProfile profile = null;
        byte[] icc = container.icc().orElse(null);
        if (icc != null) {
            profile = parse(icc);
        } else if (floating(header.outputBitDepth(), samples.format()) && picture instanceof ChannelFloatPicture floats
                && (samples.format() == JxrCodestream.RGB || samples.format() == JxrCodestream.GRAY)) {
            profile = RasterOutput.linearFor(floats.layout());
        }
        var orientation = orientation(container, header);
        if (picture instanceof YuvPicture || picture instanceof Yuv16Picture) {
            return new DecodedImage(picture, ImageFormat.JXR, orientation, samples.width(), samples.height(),
                    container.icc(), container.exif(), container.xmp(), Optional.empty(), density(container));
        }
        var decoded = RasterOutput.finish(picture, ImageFormat.JXR, orientation, profile, icc, options);
        return new DecodedImage(decoded.picture(), ImageFormat.JXR, orientation, samples.width(), samples.height(),
                decoded.iccProfile(), container.exif(), container.xmp(), decoded.colorProfile(), density(container));
    }

    /**
     * {@return the resolution of the container}
     */
    private static Optional<PixelDensity> density(JxrContainer container) {
        var dpi = container.dotsPerInch();
        return dpi == null ? Optional.empty() : Optional.of(PixelDensity.ofDotsPerInch(dpi[0], dpi[1]));
    }

    /**
     * Parses an embedded ICC profile, returning {@code null} when it does not parse.
     */
    private static ColorProfile parse(byte[] icc) {
        try {
            return ColorProfile.parse(icc);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * {@return the display orientation: the container's transformation; for a bare codestream, the image header's}
     *
     * <p>A file's container decides alone, as for the reference decoder and Windows, which ignore the orientation of
     * the image header of a file that has a container.
     */
    private static Orientation orientation(JxrContainer container, JxrHeader header) {
        var bare = container.imageOffset() == 0;
        var code = container.orientation() >= 0 ? container.orientation() : bare ? header.orientation() : 0;
        return switch (code) {
            case 1 -> Orientation.FLIP_VERTICAL;
            case 2 -> Orientation.FLIP_HORIZONTAL;
            case 3 -> Orientation.ROTATE_180;
            case 4 -> Orientation.ROTATE_90;
            case 5 -> Orientation.TRANSVERSE;
            case 6 -> Orientation.TRANSPOSE;
            case 7 -> Orientation.ROTATE_270;
            default -> Orientation.NORMAL;
        };
    }

    /**
     * {@return whether an output format holds floating-point or fixed-point samples}
     */
    private static boolean floating(int bitDepth, int format) {
        return format == JxrCodestream.RGBE || bitDepth == JxrHeader.BD_16S || bitDepth == JxrHeader.BD_16F
                || bitDepth == JxrHeader.BD_32S || bitDepth == JxrHeader.BD_32F;
    }

    /**
     * Builds the picture of decoded samples.
     */
    private static Picture picture(JxrCodestream.Samples samples, boolean premultiplied) {
        var header = samples.header();
        var width = samples.width();
        var height = samples.height();
        var channels = samples.channels();
        var bitDepth = header.outputBitDepth();
        var format = samples.format();
        if (format == JxrCodestream.YUV420 || format == JxrCodestream.YUV422 || format == JxrCodestream.YUV444) {
            return yuv(samples);
        }
        if (format == JxrCodestream.RGBE) {
            var pixels = new float[width * height * 3];
            for (var index = 0; index < width * height; index++) {
                var exponent = channels[3][index];
                var scale = exponent == 0 ? 0 : (float) Math.scalb(1.0, exponent - 128 - 8);
                for (var c = 0; c < 3; c++) {
                    pixels[index * 3 + c] = channels[c][index] * scale;
                }
            }
            return ChannelFloatPicture.of(width, height, width * 3, pixels, PixelLayout.RGB);
        }
        var color = channels.length - (samples.alpha() ? 1 : 0);
        var list = new ArrayList<Channel>();
        switch (format) {
            case JxrCodestream.RGB -> {
                list.add(Channel.of(ChannelType.RED));
                list.add(Channel.of(ChannelType.GREEN));
                list.add(Channel.of(ChannelType.BLUE));
            }
            case JxrCodestream.CMYK -> {
                list.add(Channel.of(ChannelType.CYAN));
                list.add(Channel.of(ChannelType.MAGENTA));
                list.add(Channel.of(ChannelType.YELLOW));
                list.add(Channel.of(ChannelType.BLACK));
            }
            case JxrCodestream.GRAY -> list.add(Channel.of(ChannelType.GRAY));
            default -> {
                for (var c = 0; c < color; c++) {
                    list.add(Channel.ofInk(""));
                }
            }
        }
        if (samples.alpha()) {
            list.add(Channel.of(premultiplied ? ChannelType.PREMULTIPLIED_ALPHA : ChannelType.ALPHA));
        }
        var layout = PixelLayout.of(list.toArray(Channel[]::new));
        var count = channels.length;
        var pixels = width * height;
        switch (bitDepth) {
            case JxrHeader.BD_1, JxrHeader.BD_8, JxrHeader.BD_5, JxrHeader.BD_565 -> {
                var out = new byte[pixels * count];
                for (var c = 0; c < count; c++) {
                    var values = channels[c];
                    var bits = bitDepth == JxrHeader.BD_1 ? 1 : bitDepth == JxrHeader.BD_8 ? 8
                            : bitDepth == JxrHeader.BD_565 && c == 1 ? 6 : c < color ? 5 : 8;
                    for (var index = 0; index < pixels; index++) {
                        out[index * count + c] = (byte) widen(values[index], bits, 8);
                    }
                }
                return Channel8Picture.of(width, height, width * count, out, layout);
            }
            case JxrHeader.BD_16, JxrHeader.BD_10, JxrHeader.BD_32 -> {
                var out = new short[pixels * count];
                for (var c = 0; c < count; c++) {
                    var values = channels[c];
                    for (var index = 0; index < pixels; index++) {
                        var value = values[index];
                        out[index * count + c] = (short) (bitDepth == JxrHeader.BD_32 ? value >>> 16
                                : bitDepth == JxrHeader.BD_10 && c < color ? widen(value, 10, 16) : value);
                    }
                }
                return Channel16Picture.of(width, height, width * count, out, layout);
            }
            default -> {
                var out = new float[pixels * count];
                for (var c = 0; c < count; c++) {
                    var values = channels[c];
                    for (var index = 0; index < pixels; index++) {
                        var value = values[index];
                        out[index * count + c] = switch (bitDepth) {
                            case JxrHeader.BD_16S -> value / 8192f;
                            case JxrHeader.BD_32S -> value / 16777216f;
                            case JxrHeader.BD_16F -> Float.float16ToFloat((short) value);
                            default -> Float.intBitsToFloat(value);
                        };
                    }
                }
                return ChannelFloatPicture.of(width, height, width * count, out, layout);
            }
        }
    }

    /**
     * {@return a sample widened to more bits by replicating its high bits}
     */
    private static int widen(int value, int bits, int target) {
        if (bits == target) {
            return value;
        }
        if (bits == 1) {
            return value != 0 ? (1 << target) - 1 : 0;
        }
        return (value << (target - bits)) | (value >> (2 * bits - target));
    }

    /**
     * Builds the YUV picture of raw YUV output.
     */
    private static Picture yuv(JxrCodestream.Samples samples) {
        var header = samples.header();
        var width = samples.width();
        var height = samples.height();
        var channels = samples.channels();
        var chroma = switch (samples.format()) {
            case JxrCodestream.YUV420 -> ChromaFormat.YUV420;
            case JxrCodestream.YUV422 -> ChromaFormat.YUV422;
            default -> ChromaFormat.YUV444;
        };
        if (header.outputBitDepth() == JxrHeader.BD_8) {
            var picture = YuvPicture.allocate(width, height, chroma, samples.alpha(), ColorInfo.JPEG);
            copy(channels[0], picture.y());
            copy(channels[1], picture.u());
            copy(channels[2], picture.v());
            if (samples.alpha()) {
                copy(channels[3], picture.alpha());
            }
            return picture;
        }
        if (header.outputBitDepth() != JxrHeader.BD_16 && header.outputBitDepth() != JxrHeader.BD_10) {
            throw new MediaException.Unsupported("jxr", "raw YUV output of bit depth " + header.outputBitDepth());
        }
        var picture = Yuv16Picture.allocate(width, height, chroma, 16, samples.alpha(), ColorInfo.JPEG);
        copy(channels[0], picture.y());
        copy(channels[1], picture.u());
        copy(channels[2], picture.v());
        if (samples.alpha()) {
            copy(channels[3], picture.alpha());
        }
        return picture;
    }

    private static void copy(int[] values, byte[] target) {
        for (var index = 0; index < target.length; index++) {
            target[index] = (byte) values[index];
        }
    }

    private static void copy(int[] values, short[] target) {
        for (var index = 0; index < target.length; index++) {
            target[index] = (short) values[index];
        }
    }
}
