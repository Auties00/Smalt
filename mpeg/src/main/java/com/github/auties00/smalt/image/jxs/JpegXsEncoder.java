package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jxs.internal.JxsEncoderCore;
import com.github.auties00.smalt.image.jxs.internal.JxsHeader;
import com.github.auties00.smalt.image.jxs.internal.JxsProfiles;
import com.github.auties00.smalt.image.jxs.internal.JxsTransforms;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encodes pictures and component images into JPEG XS codestreams (ISO/IEC 21122-1), bare or in a JXS file
 * (ISO/IEC 21122-3).
 *
 * <p>Pictures map to components as their samples are stored: R, G and B (and alpha) of an {@link RgbaPicture} or
 * {@link Rgba16Picture}, coded through the reversible color transform unless
 * {@link JpegXsEncoderOptions#colorTransform()} is cleared; the planes of a {@link YuvPicture} or {@link Yuv16Picture}
 * at their chroma format (4:4:4, 4:2:2, 4:2:0 or gray, alpha as a further component at full resolution); and the
 * channels of a {@link Channel8Picture} or {@link Channel16Picture}, one component each. The precision is the
 * picture's bit depth. A {@link JpegXsImage} codes its components as given, and a Bayer image (one with a CFA
 * pattern) through the Star-Tetrix transform; {@link JpegXsDecoder#decodeImage} reproduces the components exactly
 * after lossless coding.
 *
 * <p>Lossy codestreams respect the rate of {@link JpegXsEncoderOptions#bitsPerPixel()}; every precinct is quantized
 * as finely as its share of the budget allows. The codestreams conform to the profile they declare and decode with
 * the reference decoder of ISO/IEC 21122-5 to the same samples as with {@link JpegXsDecoder}.
 *
 * <p>This class is thread-safe.
 *
 * @implNote This implementation follows the coding of SVT-JPEG-XS; precincts are rate controlled one by one, carrying
 * the unused budget forward within a slice, and slices are coded in parallel on the shared pool.
 */
public final class JpegXsEncoder {
    private JpegXsEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to code, of any picture type but floating-point RGBA
     * @param options the settings
     * @return the JXS file or codestream
     * @throws IllegalArgumentException if a subsampled picture has an odd width (4:2:2, 4:2:0) or height (4:2:0)
     * @throws MediaException.Unsupported if the picture has floating-point channels, a 4:4:0 chroma format, more
     * than 8 channels, or a precision the requested profile does not allow
     */
    public static byte[] encode(Picture picture, JpegXsEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        return encode(components(picture, options), options);
    }

    /**
     * Encodes a picture into a sink.
     *
     * @param picture the picture to code, of any picture type but floating-point RGBA
     * @param options the settings
     * @param sink the output, written from its current position
     * @throws MediaException.Unsupported if the picture cannot be coded, as {@link #encode(Picture,
     * JpegXsEncoderOptions)} describes
     * @throws MediaException.Io if writing to the sink fails
     */
    public static void encode(Picture picture, JpegXsEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes the components of an image.
     *
     * @param image the components, at most 8; a Bayer image has four components of equal size in the CFA component
     * order of {@link JpegXsDecoder#decodeImage}
     * @param options the settings
     * @return the JXS file or codestream
     * @throws IllegalArgumentException if the components do not form a JPEG XS picture: the first component is
     * subsampled, the picture size is not a multiple of the sampling factors, a component's size does not match its
     * sampling factors, or a Bayer image does not have four components of equal size placed as its pattern places
     * them
     * @throws MediaException.Unsupported if there are more than 8 components, or the requested profile does not allow
     * the image
     */
    public static byte[] encode(JpegXsImage image, JpegXsEncoderOptions options) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var plan = plan(image, options);
            var samples = new short[image.components().size()][];
            for (var c = 0; c < samples.length; c++) {
                samples[c] = image.components().get(c).samples();
            }
            var codestream = JxsEncoderCore.encode(plan, samples);
            var level = sublevelFor(codestream, plan);
            if (level != plan.level()) {
                var pih = 4 + JxsHeader.u16(codestream, 4);
                codestream[pih + 10] = (byte) (level >>> 8);
                codestream[pih + 11] = (byte) level;
            }
            if (!options.fileFormat()) {
                return codestream;
            }
            return file(codestream, image, plan, options);
        }
    }

    /**
     * Returns the level and sublevel {@code Plev} a coded codestream signals: the planned one, with the sublevel raised
     * to the smallest that admits the rate the codestream reached (its whole length over the samples of the picture,
     * as ISO/IEC 21122-2 measures it) when the planned sublevel is a rate one and the codestream exceeds it.
     *
     * @param codestream the coded codestream
     * @param plan the coding parameters
     * @return the {@code Plev} to signal
     */
    private static int sublevelFor(byte[] codestream, JxsEncoderCore.Params plan) {
        var planned = plan.level() & 0xFF;
        if (planned == 0 || planned >= 0x80) {
            return plan.level();
        }
        var samples = (double) plan.width() * plan.height() * (plan.colorTransform() == 3 ? 4 : 1);
        var reached = JxsEncoderCore.sublevel(codestream.length * 8.0 / samples);
        return reached > planned ? plan.level() & 0xFF00 | reached : plan.level();
    }

    /**
     * Returns the components of a picture as the class describes.
     */
    static JpegXsImage components(Picture picture, JpegXsEncoderOptions options) {
        var list = new ArrayList<JpegXsImage.Component>();
        var transform = JpegXsImage.ColorTransform.NONE;
        ColorInfo color = null;
        switch (picture) {
            case RgbaPicture rgba -> {
                var channels = rgba.hasAlpha() ? 4 : 3;
                var pixels = rgba.pixels();
                for (var c = 0; c < channels; c++) {
                    var plane = new short[rgba.width() * rgba.height()];
                    for (var y = 0; y < rgba.height(); y++) {
                        var row = y * rgba.stride();
                        for (var x = 0; x < rgba.width(); x++) {
                            plane[y * rgba.width() + x] = (short) (pixels[row + x * 4 + c] & 0xFF);
                        }
                    }
                    list.add(component(rgba.width(), rgba.height(), 8, 1, 1, plane));
                }
                transform = options.colorTransform() ? JpegXsImage.ColorTransform.RCT
                        : JpegXsImage.ColorTransform.NONE;
                color = srgb();
            }
            case Rgba16Picture rgba -> {
                var channels = rgba.hasAlpha() ? 4 : 3;
                var pixels = rgba.pixels();
                for (var c = 0; c < channels; c++) {
                    var plane = new short[rgba.width() * rgba.height()];
                    for (var y = 0; y < rgba.height(); y++) {
                        var row = y * rgba.stride();
                        for (var x = 0; x < rgba.width(); x++) {
                            plane[y * rgba.width() + x] = pixels[row + x * 4 + c];
                        }
                    }
                    list.add(component(rgba.width(), rgba.height(), 16, 1, 1, plane));
                }
                transform = options.colorTransform() ? JpegXsImage.ColorTransform.RCT
                        : JpegXsImage.ColorTransform.NONE;
                color = srgb();
            }
            case YuvPicture yuv -> {
                var format = yuv.chromaFormat();
                list.add(plane8(yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), 1, 1));
                if (format != ChromaFormat.MONOCHROME) {
                    var sx = sx(format);
                    var sy = sy(format);
                    list.add(plane8(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), sx, sy));
                    list.add(plane8(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), sx, sy));
                }
                if (yuv.hasAlpha()) {
                    list.add(plane8(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height(), 1, 1));
                }
                color = yuv.color();
            }
            case Yuv16Picture yuv -> {
                var format = yuv.chromaFormat();
                var depth = yuv.bitDepth();
                list.add(plane16(yuv.y(), yuv.yStride(), yuv.width(), yuv.height(), 1, 1, depth));
                if (format != ChromaFormat.MONOCHROME) {
                    var sx = sx(format);
                    var sy = sy(format);
                    list.add(plane16(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), sx, sy, depth));
                    list.add(plane16(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight(), sx, sy, depth));
                }
                if (yuv.hasAlpha()) {
                    list.add(plane16(yuv.alpha(), yuv.alphaStride(), yuv.width(), yuv.height(), 1, 1, depth));
                }
                color = yuv.color();
            }
            case Channel8Picture channels -> {
                var pixels = channels.pixels();
                var count = channels.channelCount();
                for (var c = 0; c < count; c++) {
                    var plane = new short[channels.width() * channels.height()];
                    for (var y = 0; y < channels.height(); y++) {
                        var row = y * channels.stride();
                        for (var x = 0; x < channels.width(); x++) {
                            plane[y * channels.width() + x] = (short) (pixels[row + x * count + c] & 0xFF);
                        }
                    }
                    list.add(component(channels.width(), channels.height(), 8, 1, 1, plane));
                }
            }
            case Channel16Picture channels -> {
                var pixels = channels.pixels();
                var count = channels.channelCount();
                for (var c = 0; c < count; c++) {
                    var plane = new short[channels.width() * channels.height()];
                    for (var y = 0; y < channels.height(); y++) {
                        var row = y * channels.stride();
                        for (var x = 0; x < channels.width(); x++) {
                            plane[y * channels.width() + x] = pixels[row + x * count + c];
                        }
                    }
                    list.add(component(channels.width(), channels.height(), channels.bitDepth(), 1, 1, plane));
                }
            }
            default -> throw new MediaException.Unsupported("JPEG XS", "JPEG XS does not code "
                    + picture.getClass().getSimpleName() + " samples");
        }
        if (list.size() > 8) {
            throw new MediaException.Unsupported("JPEG XS", "JPEG XS codes at most 8 components, the picture has "
                    + list.size());
        }
        return new JpegXsImage(picture.width(), picture.height(), list, transform, Optional.empty(), 0, 0,
                Optional.ofNullable(color), Optional.empty());
    }

    private static ColorInfo srgb() {
        return new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
    }

    private static int sx(ChromaFormat format) {
        return switch (format) {
            case YUV420, YUV422 -> 2;
            case YUV444, MONOCHROME -> 1;
            case YUV440 -> throw new MediaException.Unsupported("JPEG XS", "JPEG XS does not code 4:4:0 pictures");
        };
    }

    private static int sy(ChromaFormat format) {
        return format == ChromaFormat.YUV420 ? 2 : 1;
    }

    private static JpegXsImage.Component component(int width, int height, int precision, int sx, int sy,
                                                   short[] samples) {
        return new JpegXsImage.Component(width, height, precision, sx, sy, 0, 0, samples);
    }

    private static JpegXsImage.Component plane8(byte[] plane, int stride, int width, int height, int sx, int sy) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                out[y * width + x] = (short) (plane[y * stride + x] & 0xFF);
            }
        }
        return component(width, height, 8, sx, sy, out);
    }

    private static JpegXsImage.Component plane16(short[] plane, int stride, int width, int height, int sx, int sy,
                                                 int depth) {
        var out = new short[width * height];
        for (var y = 0; y < height; y++) {
            System.arraycopy(plane, y * stride, out, y * width, width);
        }
        return component(width, height, depth, sx, sy, out);
    }

    /**
     * Chooses the coding parameters of an image.
     */
    static JxsEncoderCore.Params plan(JpegXsImage image, JpegXsEncoderOptions options) {
        var components = image.components();
        var count = components.size();
        if (count > 8) {
            throw new MediaException.Unsupported("JPEG XS", "JPEG XS codes at most 8 components, the image has "
                    + count);
        }
        var bayer = image.cfaPattern().isPresent();
        var gridWidth = bayer ? image.width() / 2 : image.width();
        var gridHeight = bayer ? image.height() / 2 : image.height();
        var precision = new int[count];
        var sx = new int[count];
        var sy = new int[count];
        var maxPrecision = 0;
        var maxSx = 1;
        var maxSy = 1;
        for (var c = 0; c < count; c++) {
            var component = components.get(c);
            precision[c] = component.precision();
            sx[c] = component.sx();
            sy[c] = component.sy();
            maxPrecision = Math.max(maxPrecision, precision[c]);
            maxSx = Math.max(maxSx, sx[c]);
            maxSy = Math.max(maxSy, sy[c]);
            if (component.width() != gridWidth / sx[c] || component.height() != gridHeight / sy[c]
                    || sy[c] > sx[c]) {
                throw new IllegalArgumentException("component " + c + " is " + component.width() + "x"
                        + component.height() + " with sampling " + sx[c] + "x" + sy[c] + " in a " + gridWidth + "x"
                        + gridHeight + " grid");
            }
        }
        if (sx[0] != 1 || sy[0] != 1) {
            throw new IllegalArgumentException("the first component is subsampled");
        }
        if (gridWidth % maxSx != 0 || gridHeight % maxSy != 0) {
            throw new IllegalArgumentException("a " + gridWidth + "x" + gridHeight + " picture is not a whole number "
                    + "of subsampled samples");
        }
        if (bayer) {
            if (count != 4 || maxSx != 1 || maxSy != 1 || image.width() % 2 != 0 || image.height() % 2 != 0) {
                throw new IllegalArgumentException("a Bayer image needs four components of equal size and an even "
                        + "mosaic");
            }
            var type = typeOf(image.cfaPattern().get());
            for (var c = 0; c < 4; c++) {
                var component = components.get(c);
                if (component.mosaicX() != JxsTransforms.mosaicX(type, c)
                        || component.mosaicY() != JxsTransforms.mosaicY(type, c)) {
                    throw new IllegalArgumentException("component " + c + " of the Bayer image is not where its "
                            + "pattern places it");
                }
            }
        }
        var lossless = options.lossless();
        var code = options.profile().isPresent() ? options.profile().get().code()
                : automaticProfile(lossless, bayer, maxPrecision, count, sx, sy);
        var profile = JxsProfiles.of(code);
        if (profile == null) {
            throw new MediaException.Unsupported("JPEG XS", "the JPEG XS encoder does not code profile 0x"
                    + Integer.toHexString(code));
        }
        if (maxPrecision > profile.maxPrecision() || count > profile.maxComponents()
                || (profile.colorTransform() == 3) != bayer) {
            throw new MediaException.Unsupported("JPEG XS", "JPEG XS profile " + JpegXsProfile.of(code)
                    .map(Enum::name).orElse("0x" + Integer.toHexString(code)) + " does not code " + count
                    + " components of " + maxPrecision + " bits" + (bayer ? " of Bayer data" : ""));
        }
        lossless |= profile.lossless();
        var colorTransform = switch (image.colorTransform()) {
            case RCT -> 1;
            case STAR_TETRIX -> 3;
            case NONE -> bayer ? 3 : 0;
        };
        if (colorTransform == 1 && (count < 3 || sx[1] != 1 || sx[2] != 1 || sy[1] != 1 || sy[2] != 1
                || profile.colorTransform() == 0)) {
            colorTransform = 0;
        }
        var nlx = options.horizontalLevels();
        while (nlx > 1 && gridWidth < (long) maxSx << nlx) {
            nlx--;
        }
        var nly = options.verticalLevels().orElse(profile.maxVerticalLevels());
        nly = Math.min(Math.min(nly, profile.maxVerticalLevels()), nlx);
        if (maxSy == 2) {
            nly = Math.max(nly, 1);
        }
        while (nly > (maxSy == 2 ? 1 : 0) && gridHeight < (long) maxSy << nly) {
            nly--;
        }
        if (gridWidth < (long) maxSx << nlx || gridHeight < (long) maxSy << nly) {
            throw new IllegalArgumentException("a " + gridWidth + "x" + gridHeight + " picture is too small for "
                    + "its sampling");
        }
        var unit = 1 << nly;
        var sliceLines = Math.min((options.sliceHeight() + unit - 1) / unit * unit, 65535 / unit * unit);
        var cw = options.precinctWidth();
        var columnUnit = 8L * maxSx << nlx;
        if (cw == 0 && profile.maxColumnWidth() > 0 && gridWidth > profile.maxColumnWidth()) {
            cw = (int) Math.max(1, profile.maxColumnWidth() / columnUnit);
        }
        if (cw > 0 && cw * columnUnit >= gridWidth) {
            cw = 0;
        }
        if (cw > 0 && gridWidth % (cw * columnUnit) != 0 && gridWidth % (cw * columnUnit) < (maxSx << nlx)) {
            throw new IllegalArgumentException("precinct width " + cw + " leaves a last precinct narrower than "
                    + (maxSx << nlx) + " samples");
        }
        var uniform = options.quantizer().map(q -> q == JpegXsEncoderOptions.Quantizer.UNIFORM)
                .orElse(profile.uniform());
        var bw = lossless ? maxPrecision : profile.waveletPrecision();
        var fq = lossless ? 0 : profile.fractionalBits();
        var br = maxPrecision > 15 || profile.rawBits() == 5 ? 5 : 4;
        int[] registrationX = null;
        int[] registrationY = null;
        var cf = 0;
        if (colorTransform == 3) {
            var pattern = image.cfaPattern().orElseThrow();
            var crg = registration(pattern);
            registrationX = crg[0];
            registrationY = crg[1];
            cf = profile.inlineTetrix() ? 3 : 0;
        }
        var rate = Math.min(options.bitsPerPixel(), JxsProfiles.fullBitsPerPixel(code) * (bayer ? 4.0 : 1.0));
        var budget = lossless ? -1L : (long) Math.ceil(rate * gridWidth * gridHeight / 8);
        var level = JxsEncoderCore.level(gridWidth, gridHeight)
                | (lossless ? 0x0080 : JxsEncoderCore.sublevel(bayer ? rate / 4 : rate));
        var sd = bayer ? profile.suppressed() : 0;
        return new JxsEncoderCore.Params(gridWidth, gridHeight, precision, sx, sy, nlx, nly, sd, colorTransform, cf,
                0, 0, registrationX, registrationY, code, level, bw, fq, br, uniform, 1, profile.longHeaders(),
                profile.rawPerPacket() || lossless, sliceLines, cw, budget);
    }

    private static int automaticProfile(boolean lossless, boolean bayer, int precision, int count, int[] sx,
                                        int[] sy) {
        if (bayer) {
            return JpegXsProfile.MAIN_BAYER.code();
        }
        if (lossless) {
            return count <= 4 ? precision <= 12 ? JpegXsProfile.MLS_12.code() : JpegXsProfile.MLS_16.code()
                    : JpegXsProfile.UNRESTRICTED.code();
        }
        var chroma420 = count >= 3 && sx[1] == 2 && sy[1] == 2;
        var chroma422 = count >= 3 && sx[1] == 2 && sy[1] == 1;
        if (count == 3 && chroma420 && precision <= 12) {
            return JpegXsProfile.MAIN_420_12.code();
        }
        if (count == 3 && chroma422 && precision <= 10) {
            return JpegXsProfile.MAIN_422_10.code();
        }
        if ((count == 1 || count == 3 && !chroma420 && !chroma422) && precision <= 12) {
            return JpegXsProfile.MAIN_444_12.code();
        }
        if (count == 4 && precision <= 12 && sy[1] == 1) {
            return JpegXsProfile.MAIN_4444_12.code();
        }
        return JpegXsProfile.UNRESTRICTED.code();
    }

    private static int typeOf(JpegXsImage.CfaPattern pattern) {
        return pattern == JpegXsImage.CfaPattern.RGGB || pattern == JpegXsImage.CfaPattern.BGGR ? 0 : 1;
    }

    /**
     * Returns the component registration of a CFA pattern.
     */
    private static int[][] registration(JpegXsImage.CfaPattern pattern) {
        var h = 32768;
        return switch (pattern) {
            case RGGB -> new int[][]{{0, h, 0, h}, {0, 0, h, h}};
            case BGGR -> new int[][]{{h, h, 0, 0}, {h, 0, h, 0}};
            case GRBG -> new int[][]{{h, 0, h, 0}, {0, 0, h, h}};
            case GBRG -> new int[][]{{0, 0, h, h}, {h, 0, h, 0}};
        };
    }

    /**
     * Returns the components of a Bayer image from its mosaic.
     *
     * @param mosaic the mosaic, row by row, unsigned 16-bit values
     * @param width the mosaic width, even
     * @param height the mosaic height, even
     * @param precision the sample precision, from 1 to 16
     * @param pattern the CFA pattern
     * @return the image
     * @throws IllegalArgumentException if a dimension is odd or not positive, the precision is out of range or the
     * mosaic is shorter than {@code width * height}
     */
    public static JpegXsImage bayer(short[] mosaic, int width, int height, int precision,
                                    JpegXsImage.CfaPattern pattern) {
        Objects.requireNonNull(mosaic, "mosaic");
        Objects.requireNonNull(pattern, "pattern");
        if (width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0 || precision < 1 || precision > 16
                || mosaic.length < (long) width * height) {
            throw new IllegalArgumentException("invalid " + width + "x" + height + " mosaic of " + precision
                    + " bits");
        }
        var type = typeOf(pattern);
        var w = width / 2;
        var h = height / 2;
        var list = new ArrayList<JpegXsImage.Component>(4);
        for (var c = 0; c < 4; c++) {
            var ox = JxsTransforms.mosaicX(type, c);
            var oy = JxsTransforms.mosaicY(type, c);
            var samples = new short[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    samples[y * w + x] = mosaic[(2 * y + oy) * width + 2 * x + ox];
                }
            }
            list.add(new JpegXsImage.Component(w, h, precision, 1, 1, ox, oy, samples));
        }
        return new JpegXsImage(width, height, list, JpegXsImage.ColorTransform.STAR_TETRIX, Optional.of(pattern), 0,
                0, Optional.empty(), Optional.empty());
    }

    /**
     * Wraps a codestream in a JXS file: the signature, file type and header boxes and the codestream box.
     */
    private static byte[] file(byte[] codestream, JpegXsImage image, JxsEncoderCore.Params plan,
                               JpegXsEncoderOptions options) {
        var out = new ByteArrayOutputStream(codestream.length + 128);
        write(out, new byte[]{0, 0, 0, 12, 'J', 'X', 'S', ' ', 0x0D, 0x0A, (byte) 0x87, 0x0A});
        box(out, "ftyp", new byte[]{'j', 'x', 's', ' ', 0, 0, 0, 0, 'j', 'x', 's', ' '});
        var header = new ByteArrayOutputStream();
        var ihdr = new byte[14];
        u32(ihdr, 0, plan.height());
        u32(ihdr, 4, plan.width());
        ihdr[8] = (byte) (plan.precision().length >> 8);
        ihdr[9] = (byte) plan.precision().length;
        var same = true;
        for (var p : plan.precision()) {
            same &= p == plan.precision()[0];
        }
        ihdr[10] = (byte) (same ? plan.precision()[0] : 0xFF);
        ihdr[11] = 0x0C;
        box(header, "ihdr", ihdr);
        var color = options.color().orElse(image.color().orElse(null));
        if (color != null) {
            var colr = new byte[10];
            colr[0] = 5;
            colr[3] = (byte) (color.primaries().code() >> 8);
            colr[4] = (byte) color.primaries().code();
            colr[5] = (byte) (color.transfer().code() >> 8);
            colr[6] = (byte) color.transfer().code();
            colr[7] = (byte) (color.matrix().code() >> 8);
            colr[8] = (byte) color.matrix().code();
            colr[9] = (byte) (color.range() == ColorRange.FULL ? 0x80 : 0);
            box(header, "colr", colr);
        }
        box(out, "jp2h", header.toByteArray());
        var profile = new byte[4];
        profile[0] = (byte) (plan.profile() >> 8);
        profile[1] = (byte) plan.profile();
        var pih = 4 + JxsHeader.u16(codestream, 4);
        profile[2] = codestream[pih + 10];
        profile[3] = codestream[pih + 11];
        var pl = new ByteArrayOutputStream();
        box(pl, "jxpl", profile);
        box(out, "jpvs", pl.toByteArray());
        box(out, "jp2c", codestream);
        return out.toByteArray();
    }

    private static void box(ByteArrayOutputStream out, String type, byte[] content) {
        var header = new byte[8];
        u32(header, 0, 8 + content.length);
        for (var i = 0; i < 4; i++) {
            header[4 + i] = (byte) type.charAt(i);
        }
        write(out, header);
        write(out, content);
    }

    private static void write(ByteArrayOutputStream out, byte[] data) {
        out.write(data, 0, data.length);
    }

    private static void u32(byte[] target, int offset, long value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    /**
     * {@return the profiles this encoder codes}
     */
    static List<JpegXsProfile> encodableProfiles() {
        var list = new ArrayList<JpegXsProfile>();
        for (var profile : JpegXsProfile.values()) {
            if (JxsProfiles.of(profile.code()) != null) {
                list.add(profile);
            }
        }
        return list;
    }
}
