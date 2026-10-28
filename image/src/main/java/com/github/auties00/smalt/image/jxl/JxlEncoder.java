package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jxl.internal.encode.CodestreamWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.HeaderWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.JpegRecompressor;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlBitWriter;
import com.github.auties00.smalt.image.jxl.internal.encode.JxlImageEncoder;
import com.github.auties00.smalt.image.jxl.internal.encode.PictureSource;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.MediaLimits;

import java.util.Objects;

/**
 * Encodes JPEG XL images (ISO/IEC 18181-1 and -2) and recompresses JPEG files losslessly into JPEG XL.
 *
 * <p>Lossless coding writes a modular frame: palettes, reversible color transforms and a meta-adaptive context
 * tree learned from the image, every sample decoding to its exact value. Lossy coding converts the color to XYB
 * and writes a VarDCT frame at the requested {@linkplain JxlEncoderOptions#distance() distance}; alpha and other
 * extra channels are coded losslessly as modular channels of the same frame.
 *
 * <p>An {@link com.github.auties00.smalt.image.RgbaPicture RgbaPicture} codes alpha only when
 * {@link Picture#hasAlpha()} is set; one whose color samples are all gray, without a color profile or with a gray
 * one, is coded as a gray image. A {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture} or a
 * 16-bit channel picture is coded at 16 bits, an {@code Rgba16Picture} of gray samples with a gray profile as a
 * gray image. A {@link ChannelPicture} keeps its channels: gray, RGB
 * or CMYK (the ink amounts, black as an extra channel) followed by alpha, depth, spot color, selection mask,
 * thermal and other channels, at 8 or 16 bits or as 32-bit floating-point samples; CMYK is always coded
 * losslessly. YUV pictures are converted with {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(Picture)}
 * first.
 *
 * <p>The colors are sRGB unless the options give a {@linkplain JxlEncoderOptions#colorProfile() profile}: an RGB
 * sRGB profile, and a high-dynamic-range PQ or HLG profile with BT.709, BT.2020 or Display P3 primaries, are
 * written as enumerated color encodings, any other profile is embedded. A file holds a bare codestream unless EXIF
 * or XMP metadata, the options or conformance level 10 (see {@link JxlEncoderOptions#container()}) ask for an ISO
 * base media file format container.
 *
 * <p>Groups of the image are transformed, quantized and tokenized on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the file does not depend on the thread
 * count. This class is thread-safe.
 */
public final class JxlEncoder {
    private static final int MAX_SIDE = 1 << 30;

    private JxlEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link JxlEncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the JPEG XL file
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels or encoding runs out of memory
     * @see #encode(Picture, JxlEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, JxlEncoderOptions.defaults());
    }

    /**
     * Encodes a picture and writes the file to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, JxlEncoderOptions)} returns.
     *
     * @param picture the picture to encode
     * @param options the encoder settings
     * @param sink the output, written from its position and not closed
     * @throws IllegalArgumentException if the color profile does not describe the picture's channels
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels or encoding runs out of memory
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, JxlEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to encode
     * @param options the encoder settings
     * @return the JPEG XL file
     * @throws IllegalArgumentException if the color profile does not describe the picture's channels, or a CMYK
     * picture has no CMYK profile
     * @throws MediaException.LimitExceeded if a side exceeds 2^30 pixels or encoding runs out of memory
     */
    public static byte[] encode(Picture picture, JxlEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        checkSize(picture.width(), picture.height());
        try (var _ = ParallelBands.operation()) {
            return Guards.encoding("JPEG XL image", () -> {
                var codestream = codestream(picture, options);
                return wrap(codestream, options, null);
            });
        }
    }

    /**
     * Recompresses a JPEG file losslessly with the {@link JxlEncoderOptions#defaults()}.
     *
     * @param jpeg the JPEG file
     * @return the JPEG XL file
     * @throws MediaException.Malformed if the JPEG file is invalid or truncated
     * @throws MediaException.Unsupported if the JPEG file uses a coding process that JPEG XL cannot hold
     * @throws MediaException.LimitExceeded if the file exceeds the default limits or encoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #recompressJpeg(MediaSource, JxlEncoderOptions)
     */
    public static byte[] recompressJpeg(MediaSource jpeg) {
        return recompressJpeg(jpeg, JxlEncoderOptions.defaults());
    }

    /**
     * Recompresses a JPEG file losslessly: the quantized DCT coefficients are coded as a VarDCT frame and the
     * file's markers, tables and scan layout in a {@code jbrd} box, so that
     * {@link JxlDecoder#reconstructJpeg(MediaSource)} rebuilds the original file byte for byte.
     *
     * <p>The ICC profile, EXIF and XMP segments of the JPEG file are moved to the codestream and to the
     * {@code Exif} and {@code xml } boxes, and decoders show the image as the JPEG decodes. The options supply
     * only the {@linkplain JxlEncoderOptions#effort() effort}; their metadata and color profile are ignored, the
     * file's own being kept.
     *
     * <p>Baseline, extended and progressive Huffman-coded JPEG files of 8-bit samples with one or three
     * components are supported; arithmetic coding, lossless, hierarchical and 12-bit JPEG files, and files with
     * other component counts or sampling layouts that JPEG XL cannot hold, are not.
     *
     * @param jpeg the JPEG file
     * @param options the encoder settings
     * @return the JPEG XL file
     * @throws MediaException.Malformed if the JPEG file is invalid or truncated
     * @throws MediaException.Unsupported if the JPEG file uses a coding process that JPEG XL cannot hold
     * @throws MediaException.LimitExceeded if the file exceeds the default limits or encoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] recompressJpeg(MediaSource jpeg, JxlEncoderOptions options) {
        return recompressJpeg(jpeg, options, MediaLimits.DEFAULT);
    }

    /**
     * Recompresses a JPEG file losslessly under resource limits; otherwise as
     * {@link #recompressJpeg(MediaSource, JxlEncoderOptions)} does.
     *
     * @param jpeg the JPEG file
     * @param options the encoder settings
     * @param limits the bounds of the file size, the image size and the memory the coefficients take
     * @return the JPEG XL file
     * @throws MediaException.Malformed if the JPEG file is invalid or truncated
     * @throws MediaException.Unsupported if the JPEG file uses a coding process that JPEG XL cannot hold
     * @throws MediaException.LimitExceeded if the file or the image exceeds the limits, or encoding runs out of
     * memory
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] recompressJpeg(MediaSource jpeg, JxlEncoderOptions options, MediaLimits limits) {
        Objects.requireNonNull(jpeg, "jpeg");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(limits, "limits");
        try (var _ = ParallelBands.operation()) {
            var data = SourceBytes.readAll(jpeg, limits, false);
            return Guards.encoding("JPEG XL recompressed JPEG", () -> JpegRecompressor.recompress(data,
                    options.effort(), limits));
        }
    }

    private static void checkSize(int width, int height) {
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new MediaException.LimitExceeded("JPEG XL image of " + width + "x" + height
                    + " exceeds 2^30 pixels per side");
        }
    }

    /**
     * Encodes the codestream of a still picture.
     *
     * @param picture the picture to encode
     * @param options the settings
     * @return the codestream
     */
    static byte[] codestream(Picture picture, JxlEncoderOptions options) {
        var lossless = isLossless(picture, options);
        var source = source(picture, options, lossless, true);
        var header = JxlImageEncoder.header(source, !lossless, null, toneOf(source.colorEncoding()));
        var out = new JxlBitWriter(4096);
        JxlImageEncoder.writeHeaders(out, source, header);
        if (lossless) {
            JxlImageEncoder.writeLosslessFrame(out, source, header, new HeaderWriter.Frame(), options.effort());
        } else {
            JxlImageEncoder.writeLossyFrame(out, coded(source, options, false), header, new HeaderWriter.Frame(),
                    options.distance(), options.effort());
        }
        return out.toByteArray();
    }

    /**
     * Returns whether a picture is coded losslessly: when the options say so, and always for CMYK.
     *
     * @param picture the picture to encode
     * @param options the settings
     * @return whether the color channels are coded losslessly
     */
    static boolean isLossless(Picture picture, JxlEncoderOptions options) {
        return options.isLossless()
                || picture instanceof ChannelPicture c && c.layout().colorModel() == ColorModel.CMYK;
    }

    /**
     * Converts a picture to the source the image header describes: its samples in the color space of the options,
     * which a high-dynamic-range profile JPEG XL enumerates describes as an enumerated encoding.
     *
     * @param picture the picture to encode
     * @param options the settings
     * @param lossless whether the color channels are coded losslessly
     * @param allowGray whether an RGBA picture of gray samples may be coded as gray
     * @return the source
     */
    static JxlImageEncoder.Source source(Picture picture, JxlEncoderOptions options, boolean lossless,
                                         boolean allowGray) {
        var profile = options.colorProfile().orElse(null);
        var hdr = profile != null ? hdrEncoding(profile) : null;
        var source = PictureSource.of(picture, hdr != null ? null : profile, options.orientation().exifCode(),
                lossless, allowGray);
        return hdr != null ? withEncoding(source, hdr) : source;
    }

    /**
     * Returns the samples a frame codes from a source: the source itself, or for lossy coding of an embedded
     * profile its colors converted to linear BT.2020, which the XYB conversion starts from.
     *
     * @param source the source the image header describes
     * @param options the settings
     * @param lossless whether the color channels are coded losslessly
     * @return the samples to code
     */
    static JxlImageEncoder.Source coded(JxlImageEncoder.Source source, JxlEncoderOptions options, boolean lossless) {
        if (lossless || source.icc() == null) {
            return source;
        }
        return PictureSource.linearized(source, options.colorProfile().orElseThrow());
    }

    /**
     * Wraps a codestream in a container when the options, the metadata or its conformance level need one.
     *
     * @param codestream the bare codestream
     * @param options the settings
     * @param jbrd the JPEG reconstruction box, or {@code null}
     * @return the file
     */
    static byte[] wrap(byte[] codestream, JxlEncoderOptions options, byte[] jbrd) {
        var exif = options.exif().orElse(null);
        var xmp = options.xmp().orElse(null);
        var iccSize = options.colorProfile().filter(p -> hdrEncoding(p) == null).map(p -> p.toByteArray().length)
                .orElse(0);
        var level = CodestreamWriter.level(codestream, iccSize);
        if (exif == null && xmp == null && jbrd == null && level == 5 && !options.container()) {
            return codestream;
        }
        return CodestreamWriter.container(codestream, exif, xmp, level, null, jbrd);
    }

    static ImageHeader.ToneMapping toneOf(ColorEncoding encoding) {
        if (encoding.wantIcc()) {
            return ImageHeader.ToneMapping.DEFAULT;
        }
        return switch (encoding.transfer()) {
            case ColorEncoding.TF_PQ -> new ImageHeader.ToneMapping(10000, 0, false, 0);
            case ColorEncoding.TF_HLG -> new ImageHeader.ToneMapping(1000, 0, false, 0);
            default -> ImageHeader.ToneMapping.DEFAULT;
        };
    }

    /**
     * Returns the enumerated encoding of a high-dynamic-range profile, or {@code null} when the profile is not
     * one or JPEG XL cannot enumerate it.
     *
     * @param profile the color profile to describe
     * @return the encoding, or {@code null}
     */
    static ColorEncoding hdrEncoding(ColorProfile profile) {
        var info = profile.colorInfo().orElse(null);
        if (info == null) {
            return null;
        }
        var transfer = switch (info.transfer()) {
            case PQ -> ColorEncoding.TF_PQ;
            case HLG -> ColorEncoding.TF_HLG;
            default -> -1;
        };
        var primaries = info.primaries() == ColorPrimaries.BT2020 ? ColorEncoding.PRIMARIES_BT2100
                : info.primaries() == ColorPrimaries.SMPTE432 ? ColorEncoding.PRIMARIES_P3
                : info.primaries() == ColorPrimaries.BT709 || info.primaries() == ColorPrimaries.UNSPECIFIED
                ? ColorEncoding.PRIMARIES_SRGB : -1;
        if (transfer < 0 || primaries < 0 || info.transfer() == TransferCharacteristics.UNSPECIFIED) {
            return null;
        }
        return new ColorEncoding(false, ColorEncoding.RGB, ColorEncoding.WHITE_D65, null, primaries, null, transfer,
                0, 0);
    }

    private static JxlImageEncoder.Source withEncoding(JxlImageEncoder.Source s, ColorEncoding encoding) {
        var color = s.color();
        if (color.size() == 1) {
            color = java.util.List.of(color.getFirst(), color.getFirst().clone(), color.getFirst().clone());
        }
        return new JxlImageEncoder.Source(s.width(), s.height(), color, s.depth(), s.extra(), s.extraInfo(),
                encoding, null, s.orientation());
    }
}
