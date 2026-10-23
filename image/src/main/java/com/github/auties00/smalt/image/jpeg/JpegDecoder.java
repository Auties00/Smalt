package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.jpeg.internal.JpegColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegFrame;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegPlanes;
import com.github.auties00.smalt.image.jpeg.internal.JpegProbe;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.LosslessOutput;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG images (ITU-T T.81, JFIF, EXIF and Adobe variants).
 *
 * <p>Supported: baseline and extended sequential (SOF0, SOF1) and progressive (SOF2) Huffman
 * coding, and sequential and progressive arithmetic coding (SOF9, SOF10), all at 8-bit and 12-bit
 * precision, any sampling factors from 1 to 4, arbitrary component identifiers and scan order,
 * 16-bit quantization tables, and streams without Huffman tables (which use the tables of T.81
 * Annex K, as Motion-JPEG frames expect); lossless Huffman and arithmetic coding (SOF3, SOF11) at
 * any precision from 2 to 16, any sampling factors from 1 to 4, every predictor and point
 * transform, in one scan or several, each interleaving up to four components or coding one, with
 * tables and restart intervals that may change between scans; frames of 1 to 255 components; and,
 * for every process, restart intervals and frame heights defined by DNL. A lossless restart
 * interval that starts within a row of minimum coded units, which T.81 does not allow, restarts
 * the prediction at its first unit, the rest of that row being the first line of the interval. A
 * lossless JFIF image of 9-bit precision whose three or four components are not subsampled holds
 * RGB samples (and alpha) through the reversible transform ffmpeg's lossless JPEG encoder applies,
 * and decodes to them:
 * {@code G = Y - ((U + V - 512) >> 2)}, {@code B = U - 256 + G}, {@code R = V - 256 + G} for the
 * components {@code Y}, {@code U}, {@code V} of each sample. Corrupt or truncated entropy-coded
 * data does not fail the decode: damaged restart intervals of a DCT frame decode as gray and
 * decoding resumes at the next restart marker, and a lossless frame whose data ends early decodes
 * as if the data went on with zero bits. Only a DCT frame with no decodable data at all is
 * rejected. Motion-JPEG frames are decoded by {@link MjpegDecoder}.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported}: 16-bit precision of the
 * DCT processes ({@code "jpeg 16-bit precision"}), which T.81 does not define; the differential
 * lossless frames of hierarchical coding (SOF7, SOF15, {@code "jpeg lossless"}); hierarchical
 * coding ({@code "jpeg hierarchical"}, SOF5, SOF6, SOF13, SOF14, DHP, EXP); JPEG-LS
 * ({@code "jpeg-ls"}, SOF55, LSE); and the reserved JPG extension marker
 * ({@code "jpeg extensions"}). A frame of more components than
 * {@link com.github.auties00.smalt.MediaLimits#maxChannels()} is rejected with
 * {@link MediaException.LimitExceeded}. JPEG XT files decode through their backward-compatible
 * base layer; the extension boxes carried in {@code APP11} segments are ignored.
 *
 * <p>Every decoded component plane of a DCT frame is bit-exact with libjpeg-turbo's accurate
 * integer IDCT ({@code djpeg -dct int}; its reduced-size IDCTs at 1/2, 1/4 and 1/8), at 8 and at
 * 12 bits, so converting the output with libjpeg's upsampling and color tables reproduces djpeg's
 * output exactly. At a
 * reduced size every component is reduced by the same factor, so chroma keeps its stored
 * subsampling. A lossless frame decodes to exactly the samples it codes.
 *
 * <p>This class is thread-safe.
 */
public final class JpegDecoder {
    /**
     * The input, named in failure messages.
     */
    private static final String WHAT = "JPEG stream";

    /**
     * Prevents instantiation.
     */
    private JpegDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the size and orientation of a JPEG image from its headers.
     *
     * <p>Only the header segments are read: the SOF segment for the size and the first
     * {@code APP1 Exif} segment for the orientation. Frames whose coding this decoder does not
     * support are still probed, since their headers are well defined.
     *
     * @param source the input
     * @return the summary; a JPEG has one frame and no alpha
     * @throws MediaException.Malformed if the input is not a JPEG stream with a valid frame header
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> JpegProbe.probe(source));
        }
    }

    /**
     * Decodes a JPEG image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the stream is invalid or holds no decodable data
     * @throws MediaException.Unsupported if the stream uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a JPEG image.
     *
     * <p>Gray and YCbCr images decode to a {@link YuvPicture} with {@link ColorInfo#JPEG} in their
     * stored subsampling ({@link ChromaFormat#MONOCHROME}, {@link ChromaFormat#YUV420},
     * {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}); layouts that match none of these
     * (4:1:1, 4:4:0, unusual factors, chroma components sampled differently) are upsampled to
     * {@link ChromaFormat#YUV444} with libjpeg's triangle filter. Images coded as RGB (an Adobe
     * transform of 0, or component identifiers {@code R}, {@code G}, {@code B}), CMYK and YCCK
     * decode to an opaque {@link RgbaPicture}; CMYK samples follow the inverted Adobe convention.
     * The planes of a {@link YuvPicture} are MCU-padded, so their strides may exceed the width.
     *
     * <p>A 12-bit image and a lossless image decode to the same picture types, with every sample
     * rescaled to 8 bits as {@code round(v * 255 / (2^P - 1))} when its precision {@code P} is not 8;
     * the samples of a 12-bit image are rescaled before any upsampling or color conversion. With
     * {@link ImageDecoderOptions#highBitDepth()} set, such an image of more than 8 bits keeps its
     * precision instead: a gray or YCbCr image decodes to a
     * {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture} of bit depth {@code P} with
     * {@link ColorInfo#JPEG}, holding the samples as decoded (in its stored subsampling, or upsampled
     * to 4:4:4 at 12 bits, for a 12-bit image; with tight strides for a lossless one, in its stored
     * subsampling when that is 4:2:0 or 4:2:2 and with its components replicated to 4:4:4
     * otherwise), and an RGB image to an opaque
     * {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}, every sample rescaled as
     * {@code round(v * 65535 / (2^P - 1))}; a CMYK or YCCK image is still reduced to 8 bits, except
     * as the next paragraph describes. A lossless image holding RGB samples through the reversible
     * transform the class description gives decodes to an {@link RgbaPicture}, with alpha when it
     * has a fourth component, whatever the options.
     *
     * <p>With {@link ImageDecoderOptions#keepChannels()} set (and {@link ImageDecoderOptions#convertToSrgb()}
     * not set), a CMYK or YCCK image decodes to a {@link Channel8Picture} of the {@link PixelLayout#CMYK} layout
     * instead, YCCK being converted to CMYK as libjpeg-turbo does. Its samples follow the picture convention, 0
     * being no ink: they are the stored samples inverted when an Adobe {@code APP14} segment is present (the
     * convention of Adobe applications) and the stored samples otherwise. The result carries the embedded
     * profile as its {@link DecodedImage#colorProfile()} when that profile is a CMYK one, so
     * {@link ImageOps#toSrgb(DecodedImage)} converts it as the {@code convertToSrgb} option does. A lossless
     * CMYK image of more than 8 bits decodes, with {@link ImageDecoderOptions#highBitDepth()} also set, to a
     * {@link com.github.auties00.smalt.image.Channel16Picture Channel16Picture} with every sample rescaled as
     * {@code round(v * 65535 / (2^P - 1))}. For an image with an Adobe segment, converting the channel picture
     * with {@link ImageOps#toRgba(Picture)} gives exactly the default output.
     *
     * <p>With {@link ImageDecoderOptions#keepChannels()} set (and {@code convertToSrgb} not set), an image
     * of two or more than four components, to which JPEG gives no color meaning, decodes to a channel
     * picture: a {@link Channel8Picture}, or, with {@link ImageDecoderOptions#highBitDepth()} also set and
     * a precision {@code P} above 8, a {@link Channel16Picture} with every sample rescaled as
     * {@code round(v * 65535 / (2^P - 1))}. It has one channel per component in frame order, at the image
     * size: subsampled components are upsampled as the chroma of a Y'CbCr image is, replicated for a
     * lossless image. The channels take their meaning from the embedded ICC profile: a profile of as
     * many channels as the image has makes them unnamed inks ({@link ColorModel#INK}); a gray, RGB or
     * CMYK profile of fewer channels names the leading ones (CMYK samples inverted when an Adobe
     * {@code APP14} segment is present) and leaves the others {@link ChannelType#UNSPECIFIED}; with any
     * other profile or none, the first channel is gray and the others unspecified. The result carries
     * the profile as its {@link DecodedImage#colorProfile()} when it describes the leading channels.
     * Without the option, such an image decodes to the picture {@link ImageOps#toRgba(Picture)} makes of
     * that channel picture ({@link ImageOps#toRgba16(Picture)} for a 16-bit one), the extra channels
     * dropped, with the profile only when it is gray or RGB; an image of inks, and any of these images
     * when {@link ImageDecoderOptions#convertToSrgb()} is set, is converted with
     * {@link ImageOps#toSrgb(DecodedImage)}, and an image of inks whose profile does not convert to sRGB
     * is rejected with {@link MediaException.Unsupported} ({@code "jpeg ink set"}).
     *
     * <p>When the options ask for a minimum size, the image is decoded at the smallest of 1/1,
     * 1/2, 1/4 and 1/8 of its stored size that still covers it, with reduced-size inverse DCTs, so
     * the work saved is real; the result reports the full size through
     * {@link DecodedImage#sourceWidth()} and {@link DecodedImage#sourceHeight()}. At 1/8 a
     * progressive image is rebuilt from its DC coefficients alone and its AC scans are not
     * decoded, so such a decode fails as {@link MediaException.Malformed} when the DC scans hold
     * no decodable data, whatever the AC scans hold. A lossless image always decodes at full size.
     *
     * <p>The result carries the orientation of the first {@code APP1 Exif} segment, that segment's
     * TIFF block, the ICC profile reassembled from the {@code APP2 ICC_PROFILE} segments, and the
     * packet of the first {@code APP1} XMP segment, with the properties of the Extended XMP packet
     * it names merged into it when its segments hold the whole extended packet. Its
     * {@link DecodedImage#density()} is the density of the first JFIF {@code APP0} segment or, when that
     * segment is absent, holds a zero density or an unknown unit, or gives only an aspect ratio, the resolution
     * of the EXIF {@code IFD0} when it has a physical unit; failing both, the aspect ratio either declares.
     *
     * <p>A CMYK or YCCK image is returned as RGBA converted without color management, from the
     * samples taken as stored inverted (the convention of Adobe applications):
     * {@code R = round(C * K / 255)}, likewise for G and B, YCCK being converted to CMYK first. Its
     * {@link DecodedImage#colorProfile()} is then empty, since its embedded CMYK profile does not
     * describe those pixels. With {@link ImageDecoderOptions#convertToSrgb()} set, such an image
     * with an embedded CMYK profile is converted to sRGB through that profile instead, its samples
     * taken as stored inverted when an Adobe {@code APP14} segment is present; every other image
     * with a profile is converted by {@link ImageOps#toSrgb(DecodedImage)}.
     *
     * @param source the input
     * @param options the limits and the size hint
     * @return the decoded image
     * @throws MediaException.Malformed if the stream is invalid or holds no decodable data
     * @throws MediaException.Unsupported if the stream uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the image or the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, options.limits(), true);
                var reader = new JpegReader(data, 0, data.length, options.limits());
                var frame = reader.readHeaders();
                reader.checkSupported();
                return decodeImage(reader, frame, options).withDensity(Optional.ofNullable(reader.density()));
            });
        }
    }

    /**
     * Decodes the image of a stream whose headers are read.
     *
     * @param reader the reader, its headers read and checked
     * @param frame the frame header
     * @param options the decoder options
     * @return the decoded image, without its pixel density
     */
    private static DecodedImage decodeImage(JpegReader reader, JpegFrame frame, ImageDecoderOptions options) {
        var reversible = LosslessOutput.reversibleRgb(reader);
        var colorSpace = reversible ? JpegColorSpace.RGB : reader.colorSpace();
        if (colorSpace == JpegColorSpace.UNKNOWN) {
            return decodeChannels(reader, options);
        }
        Picture wide = null;
        JpegPlanes planes = null;
        if (!reader.lossless()) {
            var scale = reader.chooseScale(options.minWidth(), options.minHeight());
            if (options.highBitDepth() && LosslessOutput.keepsPrecision(reader, colorSpace)) {
                wide = JpegOutput.toWidePicture(reader.decodeWidePlanes(scale), colorSpace, options.limits());
            } else {
                planes = reader.decodePlanes(scale);
            }
        } else if (reversible) {
            wide = LosslessOutput.decodeReversibleRgb(reader, options.limits());
        } else if (options.highBitDepth() && LosslessOutput.keepsPrecision(reader, colorSpace)) {
            wide = LosslessOutput.decodePicture(reader, colorSpace, options.limits());
        } else if (options.highBitDepth() && options.keepChannels() && !options.convertToSrgb()
                && LosslessOutput.keepsCmykPrecision(reader, colorSpace)) {
            wide = LosslessOutput.decodeCmyk(reader, reader.hasAdobeMarker(), options.limits());
        } else {
            planes = LosslessOutput.decodePlanes(reader, options.limits());
        }
        var exif = reader.exif();
        var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
        var icc = reader.iccProfile();
        var xmp = Optional.ofNullable(reader.xmp(options.limits()));
        if (wide != null) {
            var image = new DecodedImage(wide, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                    Optional.ofNullable(icc), Optional.ofNullable(exif)).withXmp(xmp);
            if (colorSpace == JpegColorSpace.CMYK) {
                return new DecodedImage(wide, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                        Optional.ofNullable(icc), Optional.ofNullable(exif), xmp, cmykProfile(image));
            }
            return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
        }
        var cmyk = colorSpace == JpegColorSpace.CMYK || colorSpace == JpegColorSpace.YCCK;
        if (cmyk && options.convertToSrgb()) {
            var converted = cmykToSrgb(planes, colorSpace, reader.hasAdobeMarker(), icc, options.limits());
            if (converted != null) {
                return new DecodedImage(converted, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                        Optional.empty(), Optional.ofNullable(exif), xmp, Optional.of(ColorProfile.srgb()));
            }
        }
        if (cmyk && options.keepChannels() && !options.convertToSrgb()) {
            var kept = keptCmyk(planes, colorSpace, reader.hasAdobeMarker(), options.limits());
            var image = new DecodedImage(kept, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                    Optional.ofNullable(icc), Optional.ofNullable(exif));
            return new DecodedImage(kept, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                    Optional.ofNullable(icc), Optional.ofNullable(exif), xmp, cmykProfile(image));
        }
        var picture = JpegOutput.toPicture(planes, colorSpace, options.limits());
        if (cmyk) {
            return new DecodedImage(picture, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                    Optional.ofNullable(icc), Optional.ofNullable(exif), xmp, Optional.empty());
        }
        var image = new DecodedImage(picture, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                Optional.ofNullable(icc), Optional.ofNullable(exif)).withXmp(xmp);
        return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
    }

    /**
     * Decodes a frame of two or more than four components to a channel picture.
     *
     * @param reader the reader, its headers read
     * @param options the decoder options
     * @return the decoded image
     */
    private static DecodedImage decodeChannels(JpegReader reader, ImageDecoderOptions options) {
        var frame = reader.frame();
        var count = frame.components().size();
        var limits = options.limits();
        var icc = reader.iccProfile();
        ColorProfile profile = null;
        if (icc != null) {
            try {
                profile = ColorProfile.parse(icc);
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // a profile that does not parse leaves the channels without a color meaning
            }
        }
        var layout = channelLayout(count, profile);
        var colors = layout.colorChannels();
        var wide = options.highBitDepth() && frame.precision() > 8;
        Picture picture;
        if (reader.lossless()) {
            picture = wide ? LosslessOutput.decodeChannels(reader, layout, limits)
                    : JpegOutput.toChannels(LosslessOutput.decodePlanes(reader, limits), layout, limits);
        } else {
            var scale = reader.chooseScale(options.minWidth(), options.minHeight());
            picture = wide && frame.precision() == 12
                    ? JpegOutput.toWideChannels(reader.decodeWidePlanes(scale), layout, limits)
                    : JpegOutput.toChannels(reader.decodePlanes(scale), layout, limits);
        }
        if (layout.colorModel() == ColorModel.CMYK && reader.hasAdobeMarker()) {
            invertInks(picture, count);
        }
        var exif = reader.exif();
        var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
        var xmp = Optional.ofNullable(reader.xmp(limits));
        var declared = profile != null && profile.channels() == colors ? Optional.of(profile)
                : Optional.<ColorProfile>empty();
        var image = new DecodedImage(picture, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                Optional.ofNullable(icc), Optional.ofNullable(exif), xmp, declared);
        if (options.keepChannels() && !options.convertToSrgb()) {
            return image;
        }
        var model = layout.colorModel();
        if (options.convertToSrgb() || model == ColorModel.INK) {
            try {
                return ImageOps.toSrgb(image);
            } catch (MediaException.Unsupported exception) {
                if (model != ColorModel.INK) {
                    throw exception;
                }
                throw new MediaException.Unsupported("jpeg ink set", "the embedded ICC profile of the JPEG image of "
                        + count + " inks does not convert to sRGB");
            }
        }
        var packed = picture instanceof Channel16Picture ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture);
        var describes = model == ColorModel.RGB || model == ColorModel.GRAY ? declared : Optional.<ColorProfile>empty();
        return new DecodedImage(packed, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                Optional.ofNullable(icc), Optional.ofNullable(exif), xmp, describes);
    }

    /**
     * Returns the layout of a frame of two or more than four components: as many leading channels
     * as the embedded profile has, of its color model (gray, RGB, CMYK, or unnamed inks for an
     * N-color profile of every component), followed by unspecified extra channels; the first
     * component gray when no profile fits.
     *
     * @param count the number of components
     * @param profile the embedded profile, or {@code null}
     * @return the layout
     */
    private static PixelLayout channelLayout(int count, ColorProfile profile) {
        if (profile == null) {
            return JpegOutput.rawLayout(count);
        }
        var colors = profile.channels();
        PixelLayout layout;
        if (colors == count && profile.colorSpace() == ProfileColorSpace.OTHER) {
            return PixelLayout.ofInks(Collections.nCopies(count, ""));
        } else if (colors < count && profile.colorSpace() == ProfileColorSpace.RGB) {
            layout = PixelLayout.RGB;
        } else if (colors < count && profile.colorSpace() == ProfileColorSpace.CMYK) {
            layout = PixelLayout.CMYK;
        } else {
            return JpegOutput.rawLayout(count);
        }
        for (var c = layout.count(); c < count; c++) {
            layout = layout.withChannel(Channel.of(ChannelType.UNSPECIFIED));
        }
        return layout;
    }

    /**
     * Inverts the four leading ink channels of a channel picture, stored inverted as Adobe
     * applications write them.
     *
     * @param picture the 8-bit or 16-bit channel picture
     * @param count the number of channels
     */
    private static void invertInks(Picture picture, int count) {
        switch (picture) {
            case Channel8Picture channels -> {
                var pixels = channels.pixels();
                for (var i = 0; i < pixels.length; i += count) {
                    for (var c = 0; c < 4; c++) {
                        pixels[i + c] = (byte) ~pixels[i + c];
                    }
                }
            }
            case Channel16Picture channels -> {
                var pixels = channels.pixels();
                for (var i = 0; i < pixels.length; i += count) {
                    for (var c = 0; c < 4; c++) {
                        pixels[i + c] = (short) ~pixels[i + c];
                    }
                }
            }
            default -> throw new IllegalStateException("not a channel picture: " + picture);
        }
    }

    /**
     * Returns the CMYK samples of a CMYK or YCCK frame in the picture convention, 0 being no ink.
     *
     * @param planes the decoded planes
     * @param colorSpace {@link JpegColorSpace#CMYK} or {@link JpegColorSpace#YCCK}
     * @param inverted whether the samples are stored inverted (Adobe convention)
     * @param limits the resource bounds checked before every allocation
     * @return the picture of the {@link PixelLayout#CMYK} layout
     */
    private static Channel8Picture keptCmyk(JpegPlanes planes, JpegColorSpace colorSpace, boolean inverted,
                                            MediaLimits limits) {
        var samples = JpegOutput.toCmyk(planes, colorSpace, limits);
        if (inverted) {
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (byte) ~samples[i];
            }
        }
        var width = planes.width();
        return Channel8Picture.of(width, planes.height(), width * 4, samples, PixelLayout.CMYK);
    }

    /**
     * Returns the color profile of a decoded CMYK image: its parsed profile when that is a CMYK one.
     *
     * @param image the image, its profile parsed from the embedded bytes
     * @return the CMYK profile, or empty
     */
    private static Optional<ColorProfile> cmykProfile(DecodedImage image) {
        return image.colorProfile().filter(profile -> profile.colorSpace() == ProfileColorSpace.CMYK);
    }

    /**
     * Converts the samples of a CMYK or YCCK frame to sRGB through an embedded CMYK profile.
     *
     * @param planes the decoded planes
     * @param colorSpace {@link JpegColorSpace#CMYK} or {@link JpegColorSpace#YCCK}
     * @param inverted whether the samples are stored inverted (Adobe convention)
     * @param icc the embedded ICC profile, or {@code null}
     * @param limits the resource bounds checked before every allocation
     * @return the opaque sRGB picture, or {@code null} when there is no usable CMYK profile
     */
    private static RgbaPicture cmykToSrgb(JpegPlanes planes, JpegColorSpace colorSpace, boolean inverted, byte[] icc,
                                          MediaLimits limits) {
        if (icc == null) {
            return null;
        }
        ColorTransform transform;
        try {
            var profile = ColorProfile.parse(icc);
            if (profile.colorSpace() != ProfileColorSpace.CMYK) {
                return null;
            }
            transform = ColorTransform.create(profile, ColorProfile.srgb());
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return null;
        }
        var samples = JpegOutput.toCmyk(planes, colorSpace, limits);
        var width = planes.width();
        var height = planes.height();
        var bands = ParallelBands.bands(height, 32L * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var start = top * width * 4;
            var end = bottom * width * 4;
            if (inverted) {
                for (var i = start; i < end; i++) {
                    samples[i] = (byte) ~samples[i];
                }
            }
            transform.transform(samples, start, 4, samples, start, 4, (bottom - top) * width);
            for (var i = start + 3; i < end; i += 4) {
                samples[i] = (byte) 0xFF;
            }
        });
        return RgbaPicture.of(width, height, width * 4, samples, false);
    }
}
