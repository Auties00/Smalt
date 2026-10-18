package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.jpeg.internal.JpegColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegPlanes;
import com.github.auties00.smalt.image.jpeg.internal.JpegProbe;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.LosslessOutput;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG images (ITU-T T.81, JFIF, EXIF and Adobe variants).
 *
 * <p>Supported: baseline and extended sequential (SOF0, SOF1) and progressive (SOF2) Huffman
 * coding, and sequential and progressive arithmetic coding (SOF9, SOF10), all at 8-bit precision
 * with one, three or four components, any sampling factors from 1 to 4, arbitrary component
 * identifiers and scan order, 16-bit quantization tables, and streams without Huffman tables
 * (which use the tables of T.81 Annex K, as Motion-JPEG frames expect); lossless Huffman coding
 * (SOF3) at any precision from 2 to 16 with one, three or four components, every predictor and
 * point transform, in one interleaved scan; and, for every process, restart intervals and frame
 * heights defined by DNL. Corrupt or truncated entropy-coded data does not fail the decode:
 * damaged restart intervals of a DCT frame decode as gray and decoding resumes at the next restart
 * marker, and a lossless frame whose data ends early decodes as if the data went on with zero
 * bits. Only a DCT frame with no decodable data at all is rejected. Motion-JPEG frames are decoded
 * by {@link MjpegDecoder}.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported}: 12-bit precision
 * ({@code "jpeg 12-bit precision"}) and 16-bit precision ({@code "jpeg 16-bit precision"}) of the
 * DCT processes; lossless coding other than SOF3 (SOF7, SOF11, SOF15), lossless frames with
 * subsampled components, and lossless frames coded in more than one scan ({@code "jpeg lossless"});
 * hierarchical coding ({@code "jpeg hierarchical"}, SOF5, SOF6, SOF13, SOF14, DHP, EXP); JPEG-LS
 * ({@code "jpeg-ls"}, SOF55, LSE); the reserved JPG extension marker ({@code "jpeg extensions"}); and
 * frames with two or more than four components ({@code "jpeg with N components"}). JPEG XT files
 * decode through their backward-compatible base layer; the extension boxes carried in
 * {@code APP11} segments are ignored.
 *
 * <p>Every decoded component plane of a DCT frame is bit-exact with libjpeg-turbo's accurate
 * integer IDCT ({@code djpeg -dct int}; its reduced-size IDCTs at 1/2, 1/4 and 1/8), so converting
 * the output with libjpeg's upsampling and color tables reproduces djpeg's output exactly. At a
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
     * <p>A lossless image decodes to the same picture types, with every sample rescaled to 8 bits
     * as {@code round(v * 255 / (2^P - 1))} when its precision {@code P} is not 8. With
     * {@link ImageDecoderOptions#highBitDepth()} set, a lossless image of more than 8 bits keeps
     * its precision instead: a gray or YCbCr image decodes to a
     * {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture} of bit depth {@code P} with
     * {@link ColorInfo#JPEG}, holding the samples as coded with tight strides, and an RGB image to
     * an opaque {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}, every sample
     * rescaled as {@code round(v * 65535 / (2^P - 1))}; a CMYK or YCCK image is still reduced to
     * 8 bits.
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
     * TIFF block, and the ICC profile reassembled from the {@code APP2 ICC_PROFILE} segments.
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
                var colorSpace = reader.colorSpace();
                Picture wide = null;
                JpegPlanes planes = null;
                if (!reader.lossless()) {
                    planes = reader.decodePlanes(reader.chooseScale(options.minWidth(), options.minHeight()));
                } else if (options.highBitDepth() && LosslessOutput.keepsPrecision(reader, colorSpace)) {
                    wide = LosslessOutput.decodePicture(reader, colorSpace, options.limits());
                } else {
                    planes = LosslessOutput.decodePlanes(reader, options.limits());
                }
                var exif = reader.exif();
                var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
                var icc = reader.iccProfile();
                if (wide != null) {
                    var image = new DecodedImage(wide, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                            Optional.ofNullable(icc), Optional.ofNullable(exif));
                    return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
                }
                var cmyk = colorSpace == JpegColorSpace.CMYK || colorSpace == JpegColorSpace.YCCK;
                if (cmyk && options.convertToSrgb()) {
                    var converted = cmykToSrgb(planes, colorSpace, reader.hasAdobeMarker(), icc, options.limits());
                    if (converted != null) {
                        return new DecodedImage(converted, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                                Optional.empty(), Optional.ofNullable(exif), Optional.of(ColorProfile.srgb()));
                    }
                }
                var picture = JpegOutput.toPicture(planes, colorSpace, options.limits());
                if (cmyk) {
                    return new DecodedImage(picture, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                            Optional.ofNullable(icc), Optional.ofNullable(exif), Optional.empty());
                }
                var image = new DecodedImage(picture, ImageFormat.JPEG, orientation, frame.width(), frame.height(),
                        Optional.ofNullable(icc), Optional.ofNullable(exif));
                return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
            });
        }
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
