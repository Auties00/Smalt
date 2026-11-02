package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jxs.internal.JxsFile;
import com.github.auties00.smalt.image.jxs.internal.JxsFrameDecoder;
import com.github.auties00.smalt.image.jxs.internal.JxsHeader;
import com.github.auties00.smalt.image.jxs.internal.JxsTransforms;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG XS images (ISO/IEC 21122): JXS files (ISO/IEC 21122-3) and raw codestreams.
 *
 * <p>The decoder implements the coding of ISO/IEC 21122-1 that the profiles of ISO/IEC 21122-2 use: 1 to 8 components
 * of 1 to 16 bits with 4:4:4, 4:2:2 and 4:2:0 sampling, up to 15 horizontal and 2 vertical decomposition levels of the
 * 5-3 wavelet, components without decomposition ({@code CWD}), precincts narrower than the picture, slices, every
 * bit-plane count coding mode (raw, prediction from zero, vertical prediction, significance flags, both run modes), the
 * deadzone and uniform quantizers, signs coded with the data or separately, long and short packet headers, the
 * reversible color transform, the Star-Tetrix transform of color filter array data with its in-line and full extents,
 * and the linear, quadratic and extended output nonlinearities ({@code NLT}). Decoded samples are bit-identical to the
 * reference decoder of ISO/IEC 21122-5 on the conformance codestreams of ISO/IEC 21122-4.
 *
 * <p>{@link #decodeImage(MediaSource, MediaLimits)} returns the components at their stored precision and
 * sampling. {@link #decode(MediaSource, ImageDecoderOptions)} returns a picture:
 * <ul>
 *   <li>a codestream that applies the reversible color transform, or whose JXS color specification declares R'G'B'
 *       samples (the identity matrix), decodes to an {@link RgbaPicture}, the fourth component being alpha;</li>
 *   <li>a Bayer codestream decodes to its color filter array mosaic as a gray {@link YuvPicture} of
 *       {@link ChromaFormat#MONOCHROME} format, twice the sampling grid in each direction;</li>
 *   <li>any other codestream decodes to a {@link YuvPicture}: one component is gray, three are Y', Cb and Cr in the
 *       chroma format their sampling factors give, and a fourth is alpha; components after the fourth are left
 *       out.</li>
 * </ul>
 * With {@link ImageDecoderOptions#highBitDepth()} set, an image of more than 8 bits decodes to the 16-bit picture of
 * the same kind ({@link Rgba16Picture} or {@link Yuv16Picture}) at its precision; otherwise samples are reduced to 8
 * bits as {@link ImageOps#toEightBit(com.github.auties00.smalt.image.Picture)} does. The color description of a
 * Y'CbCr picture is that of the JXS color specification, or BT.709 limited range without one. A codestream of
 * temporal differential coding (the TDC profiles) is decoded as the first frame of its sequence, predicting from a
 * zero frame buffer, which gives the picture of an intra-coded frame; the frames after it need the ones before and are
 * decoded by {@link JpegXsVideoDecoder}.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation ports SVT-JPEG-XS. Slices are entropy decoded in parallel and the wavelet rows and
 * columns on the shared pool that {@link com.github.auties00.smalt.MediaRuntime} describes.
 */
public final class JpegXsDecoder {
    private static final String WHAT = "JPEG XS image";
    private static final int PROBE_BYTES = 1 << 16;

    private JpegXsDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a JPEG XS image from its boxes and main header, without decoding slices.
     *
     * <p>The size is that of the decoded picture; {@link ImageInfo#hasAlpha()} is {@code true} for four components
     * other than a Bayer image.
     *
     * @param source the input
     * @return the summary
     * @throws MediaException.Malformed if the file or the main header is invalid
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var prefix = new byte[PROBE_BYTES];
                var length = SourceBytes.readAvailable(source, 0, prefix, 0, prefix.length);
                JxsHeader header;
                try {
                    var file = JxsFile.parse(prefix, length);
                    header = JxsHeader.parse(prefix, file.codestreamStart(), file.codestreamEnd());
                } catch (MediaException.Malformed truncated) {
                    if (length < prefix.length) {
                        throw truncated;
                    }
                    var data = SourceBytes.readAll(source, MediaLimits.defaults(), true);
                    var file = JxsFile.parse(data, data.length);
                    header = JxsHeader.parse(data, file.codestreamStart(), file.codestreamEnd());
                }
                var bayer = header.colorTransform == 3;
                var width = bayer ? header.width * 2 : header.width;
                var height = bayer ? header.height * 2 : header.height;
                return new ImageInfo(ImageFormat.JPEG_XS, width, height, !bayer && header.components == 4, false, 1,
                        Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes the components of a JPEG XS image under the {@link MediaLimits#defaults()}.
     *
     * @param source the input
     * @return the components
     * @throws MediaException.Malformed if the file or codestream is invalid
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeImage(MediaSource, MediaLimits)
     */
    public static JpegXsImage decodeImage(MediaSource source) {
        return decodeImage(source, MediaLimits.defaults());
    }

    /**
     * Decodes the components of a JPEG XS image, keeping each component's precision and sampling.
     *
     * @param source the input
     * @param limits the limits on the input size, the picture size and the allocation
     * @return the components
     * @throws MediaException.Malformed if the file or codestream is invalid
     * @throws MediaException.LimitExceeded if the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static JpegXsImage decodeImage(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, limits, true);
                return decodeImage(data, data.length, limits);
            });
        }
    }

    /**
     * Decodes the components of a JXS file or raw codestream held in memory.
     *
     * @param data the bytes
     * @param length the number of valid bytes
     * @param limits the limits on the picture size and the allocation
     * @return the components
     */
    static JpegXsImage decodeImage(byte[] data, int length, MediaLimits limits) {
        var file = JxsFile.parse(data, length);
        var frame = JxsFrameDecoder.decode(data, file.codestreamStart(), file.codestreamEnd(), limits);
        return image(frame, file.color(), file.icc());
    }

    /**
     * Assembles the public image of a decoded frame.
     *
     * @param frame the decoded frame
     * @param color the color description of the file, or {@code null}
     * @param icc the ICC profile of the file, or {@code null}
     * @return the image
     */
    static JpegXsImage image(JxsFrameDecoder.Frame frame, ColorInfo color, byte[] icc) {
        var header = frame.header();
        var layout = frame.layout();
        var components = new ArrayList<JpegXsImage.Component>(layout.components);
        var bayer = header.colorTransform == 3;
        var type = bayer ? JxsTransforms.cfaType(header) : 0;
        for (var c = 0; c < layout.components; c++) {
            components.add(new JpegXsImage.Component(layout.componentWidth[c], layout.componentHeight[c],
                    header.precision[c], header.sx[c], header.sy[c], bayer ? JxsTransforms.mosaicX(type, c) : 0,
                    bayer ? JxsTransforms.mosaicY(type, c) : 0, frame.samples()[c]));
        }
        var transform = switch (header.colorTransform) {
            case 1 -> JpegXsImage.ColorTransform.RCT;
            case 3 -> JpegXsImage.ColorTransform.STAR_TETRIX;
            default -> JpegXsImage.ColorTransform.NONE;
        };
        var width = bayer ? header.width * 2 : header.width;
        var height = bayer ? header.height * 2 : header.height;
        return new JpegXsImage(width, height, components, transform,
                bayer ? Optional.of(pattern(header)) : Optional.empty(), header.profile, header.level,
                Optional.ofNullable(color), Optional.ofNullable(icc));
    }

    /**
     * Returns the CFA pattern a Star-Tetrix codestream registers its components with.
     */
    private static JpegXsImage.CfaPattern pattern(JxsHeader header) {
        var x = header.registrationX;
        var y = header.registrationY;
        var h = 32768;
        if (matches(x, y, 0, h, 0, h, 0, 0, h, h)) {
            return JpegXsImage.CfaPattern.RGGB;
        }
        if (matches(x, y, h, h, 0, 0, h, 0, h, 0)) {
            return JpegXsImage.CfaPattern.BGGR;
        }
        if (matches(x, y, h, 0, h, 0, 0, 0, h, h)) {
            return JpegXsImage.CfaPattern.GRBG;
        }
        if (matches(x, y, 0, 0, h, h, h, 0, h, 0)) {
            return JpegXsImage.CfaPattern.GBRG;
        }
        throw new MediaException.Malformed("the JPEG XS component registration matches no Bayer pattern");
    }

    private static boolean matches(int[] x, int[] y, int x0, int x1, int x2, int x3, int y0, int y1, int y2, int y3) {
        return x[0] == x0 && x[1] == x1 && x[2] == x2 && x[3] == x3 && y[0] == y0 && y[1] == y1 && y[2] == y2
                && y[3] == y3;
    }

    /**
     * Decodes a JPEG XS image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the file or codestream is invalid
     * @throws MediaException.Unsupported if the components form no picture
     * @throws MediaException.LimitExceeded if the image exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a JPEG XS image to a picture as the class describes.
     *
     * <p>The size hint and the channel choice of {@code options} are ignored; a region is cut from the whole decoded
     * picture.
     *
     * @param source the input
     * @param options the limits, the bit depth, the region and the sRGB conversion choice
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file or codestream is invalid
     * @throws MediaException.Unsupported if the components form no picture
     * @throws MediaException.LimitExceeded if the image exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return ImageRegions.decode(options, null, whole -> Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, whole.limits(), true);
                var image = decodeImage(data, data.length, whole.limits());
                var picture = picture(image, whole.highBitDepth());
                var decoded = new DecodedImage(picture, ImageFormat.JPEG_XS, Orientation.NORMAL, image.width(),
                        image.height(), image.iccProfile(), Optional.empty(), Optional.empty(),
                        image.iccProfile().map(ColorProfile::parse), Optional.empty());
                return whole.convertToSrgb() ? ImageOps.toSrgb(decoded) : decoded;
            }));
        }
    }

    /**
     * Converts decoded components to a picture as the class describes.
     *
     * @param image the components
     * @param highBitDepth whether a deeper image keeps its precision in a 16-bit picture
     * @return the picture
     * @throws MediaException.Unsupported if the components do not form a picture (two components, or sampling
     * factors no chroma format describes)
     */
    static Picture picture(JpegXsImage image, boolean highBitDepth) {
        var components = image.components();
        var depth = 0;
        for (var c = 0; c < Math.min(components.size(), 4); c++) {
            depth = Math.max(depth, components.get(c).precision());
        }
        var wide = highBitDepth && depth > 8;
        var bitDepth = Math.max(depth, 8);
        if (image.cfaPattern().isPresent()) {
            var mosaic = image.mosaic().orElseThrow();
            var color = new ColorInfo(MatrixCoefficients.UNSPECIFIED, ColorRange.FULL, ColorPrimaries.UNSPECIFIED,
                    TransferCharacteristics.LINEAR);
            var gray = Yuv16Picture.builder().size(image.width(), image.height()).chromaFormat(ChromaFormat.MONOCHROME)
                    .bitDepth(bitDepth).color(color)
                    .luma(scale(mosaic, components.getFirst().precision(), bitDepth), image.width()).build();
            return narrow(gray, wide);
        }
        var rgb = image.colorTransform() == JpegXsImage.ColorTransform.RCT
                || image.color().map(info -> info.matrix() == MatrixCoefficients.IDENTITY).orElse(false)
                && components.size() >= 3 && sameSampling(components, 0, 3);
        if (rgb) {
            if (components.size() < 3 || !sameSampling(components, 0, Math.min(components.size(), 4))) {
                throw new MediaException.Unsupported("JPEG XS", "JPEG XS R'G'B' components of different sampling");
            }
            var alpha = components.size() >= 4;
            var picture = Rgba16Picture.allocate(image.width(), image.height(), alpha);
            var pixels = picture.pixels();
            var count = image.width() * image.height();
            for (var c = 0; c < (alpha ? 4 : 3); c++) {
                var component = components.get(c);
                var samples = component.samples();
                var max = (1 << component.precision()) - 1;
                for (var i = 0; i < count; i++) {
                    pixels[i * 4 + c] = (short) (((samples[i] & 0xFFFF) * 65535L + (max >> 1)) / max);
                }
            }
            if (!alpha) {
                for (var i = 0; i < count; i++) {
                    pixels[i * 4 + 3] = (short) 0xFFFF;
                }
            }
            return wide ? picture : ImageOps.toEightBit(picture);
        }
        var color = image.color().orElse(ColorInfo.BT709_LIMITED);
        var first = components.getFirst();
        if (first.sx() != 1 || first.sy() != 1) {
            throw new MediaException.Unsupported("JPEG XS", "JPEG XS first component is subsampled");
        }
        var builder = Yuv16Picture.builder().size(first.width(), first.height()).bitDepth(bitDepth).color(color)
                .luma(scale(first.samples(), first.precision(), bitDepth), first.width());
        int alphaIndex;
        if (components.size() == 1) {
            builder.chromaFormat(ChromaFormat.MONOCHROME);
            alphaIndex = -1;
        } else if (components.size() == 2) {
            builder.chromaFormat(ChromaFormat.MONOCHROME);
            alphaIndex = 1;
        } else {
            var u = components.get(1);
            var v = components.get(2);
            if (u.sx() != v.sx() || u.sy() != v.sy()) {
                throw new MediaException.Unsupported("JPEG XS", "JPEG XS chroma components of different sampling");
            }
            var format = u.sx() == 1 ? u.sy() == 1 ? ChromaFormat.YUV444 : ChromaFormat.YUV440
                    : u.sy() == 1 ? ChromaFormat.YUV422 : ChromaFormat.YUV420;
            builder.chromaFormat(format).chroma(scale(u.samples(), u.precision(), bitDepth),
                    scale(v.samples(), v.precision(), bitDepth), u.width());
            alphaIndex = components.size() >= 4 ? 3 : -1;
        }
        if (alphaIndex >= 0) {
            var alpha = components.get(alphaIndex);
            if (alpha.sx() != 1 || alpha.sy() != 1) {
                throw new MediaException.Unsupported("JPEG XS", "JPEG XS alpha component is subsampled");
            }
            builder.alpha(opacity(alpha.samples(), alpha.precision(), bitDepth), alpha.width());
        }
        return narrow(builder.build(), wide);
    }

    private static boolean sameSampling(java.util.List<JpegXsImage.Component> components, int from, int to) {
        for (var c = from; c < to; c++) {
            if (components.get(c).sx() != 1 || components.get(c).sy() != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns samples of {@code precision} bits at {@code bitDepth} bits: the same array when the two agree, the
     * samples shifted left otherwise.
     */
    private static short[] scale(short[] samples, int precision, int bitDepth) {
        if (precision == bitDepth) {
            return samples;
        }
        var out = new short[samples.length];
        if (precision < 8 && bitDepth == 8) {
            var max = (1 << precision) - 1;
            for (var i = 0; i < samples.length; i++) {
                out[i] = (short) (((samples[i] & 0xFFFF) * 255 + (max >> 1)) / max);
            }
            return out;
        }
        var shift = bitDepth - precision;
        for (var i = 0; i < samples.length; i++) {
            out[i] = (short) ((samples[i] & 0xFFFF) << shift);
        }
        return out;
    }

    /**
     * Returns alpha samples of {@code precision} bits at {@code bitDepth} bits, rescaled so that the largest value of
     * one depth is the largest of the other.
     */
    private static short[] opacity(short[] samples, int precision, int bitDepth) {
        if (precision == bitDepth) {
            return samples;
        }
        var out = new short[samples.length];
        var max = (1L << precision) - 1;
        var target = (1L << bitDepth) - 1;
        for (var i = 0; i < samples.length; i++) {
            out[i] = (short) (((samples[i] & 0xFFFF) * target + (max >> 1)) / max);
        }
        return out;
    }

    private static Picture narrow(Yuv16Picture picture, boolean wide) {
        return wide ? picture : ImageOps.toEightBit(picture);
    }
}
