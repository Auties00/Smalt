package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AuxiliaryImage;
import com.github.auties00.smalt.image.AuxiliaryImageType;
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
import com.github.auties00.smalt.image.gainmap.GainMap;
import com.github.auties00.smalt.image.jpeg.internal.JpegColorSpace;
import com.github.auties00.smalt.image.jpeg.internal.JpegContainer;
import com.github.auties00.smalt.image.jpeg.internal.JpegFrame;
import com.github.auties00.smalt.image.jpeg.internal.JpegOutput;
import com.github.auties00.smalt.image.jpeg.internal.JpegPlanes;
import com.github.auties00.smalt.image.jpeg.internal.JpegProbe;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.image.jpeg.internal.LosslessOutput;
import com.github.auties00.smalt.image.jpeg.internal.UltraHdr;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.Region;
import com.github.auties00.smalt.internal.gainmap.AppleGainMaps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decodes JPEG images (ITU-T T.81, JFIF, EXIF and Adobe variants), hierarchical JPEG images (T.81 annex J) and
 * JPEG-LS images (ITU-T T.87).
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
 * <p>A hierarchical image (a DHP segment, then a non-differential frame of each component and differential
 * frames, SOF5, SOF6, SOF7, SOF13, SOF14 and SOF15, each after the EXP segment that expands its reference) of any
 * of the coding processes above decodes to the components of its completed image: each component's last
 * reference, the differences of its differential frames added to the reference of the frame before, expanded by
 * the bilinear interpolation of T.81 J.1.1.2. Differential DCT frames are inverse transformed without the level
 * shift; the differences of every differential frame are added modulo {@code 2^16} (J.2.1), and only the
 * non-differential DCT frames and the completed image are clamped to the range of the precision. An image whose
 * last frames are lossless decodes as a lossless image, to exactly the samples coded; any other one as a DCT image.
 * A stream whose frames break the order of annex J (DCT and lossless non-differential frames mixed, a DCT frame
 * over lossless ones, a frame after a lossless frame over DCT ones, or a second non-differential frame of a
 * component) is malformed, as is a differential frame without a DHP segment.
 *
 * <p>A JPEG-LS image (SOF55) of 2 to 16 bits, lossless or near-lossless, in any interleave mode, with any sampling
 * factors, preset coding parameters ({@code LSE} segments), restart intervals, oversize dimensions and point
 * transform, decodes as a lossless image to exactly the samples its decoding process reconstructs. Its sample
 * mapping tables are applied: entries of 1 or 2 bytes map a component to 8 or 16-bit samples, and the 3-byte
 * entries of a one-component frame are the red, green and blue samples of a palette image. The HP color
 * transforms an {@code APP8 "mrfx"} segment declares are inverted. Three components are RGB unless a JFIF
 * segment, a SPIFF header or an Adobe segment declares Y'CbCr.
 *
 * <p>Unsupported, and rejected with {@link MediaException.Unsupported}: 16-bit precision of the
 * DCT processes ({@code "jpeg 16-bit precision"}), which T.81 does not define; JPEG-LS part 2
 * ({@code "jpeg-ls extensions"}, ITU-T T.870, SOF57 and its {@code LSE} parameter types); JPEG-LS mapping tables of
 * other entry sizes or mapping components to different precisions ({@code "jpeg-ls mapping table"}) and color
 * transforms other than HP1 to HP3 ({@code "jpeg-ls color transform"}); and the reserved JPG extension marker
 * ({@code "jpeg extensions"}). A frame of more components than
 * {@link com.github.auties00.smalt.MediaLimits#maxChannels()} is rejected with
 * {@link MediaException.LimitExceeded}. JPEG XT files decode through their backward-compatible
 * base layer; the extension boxes carried in {@code APP11} segments are ignored.
 *
 * <p>The images a file holds after its primary image, which {@link #decode} ignores, are listed by
 * {@link #auxiliaryImages(MediaSource, MediaLimits)} and decode one by one; the gain map of an Ultra HDR or Adobe
 * gain map file decodes with {@link #decodeGainMap(MediaSource, ImageDecoderOptions)}, for
 * {@link com.github.auties00.smalt.image.gainmap.GainMaps#apply}.
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
     * {@link ChromaFormat#YUV422}, {@link ChromaFormat#YUV444} or {@link ChromaFormat#YUV440}); layouts
     * that match none of these (4:1:1, unusual factors, chroma components sampled differently) are
     * upsampled to
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
     * subsampling when that is 4:2:0, 4:2:2 or 4:4:0 and with its components replicated to 4:4:4
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
     * no decodable data, whatever the AC scans hold. A lossless, JPEG-LS or hierarchical image always decodes at
     * full size.
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
                var region = options.region().orElse(null);
                DecodedImage image;
                if (region == null) {
                    image = decodeImage(reader, frame, options, null);
                } else {
                    image = decodeRegion(reader, frame, options, region);
                }
                return image.withDensity(Optional.ofNullable(reader.density()));
            });
        }
    }

    /**
     * Decodes the gain map of a JPEG file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the gain map, or empty when the file has none
     * @throws MediaException.Malformed if the file or its gain map image is invalid
     * @throws MediaException.Unsupported if the gain map image uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the input or the gain map image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeGainMap(MediaSource, ImageDecoderOptions)
     */
    public static Optional<GainMap> decodeGainMap(MediaSource source) {
        return decodeGainMap(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the gain map of a JPEG file: of an Ultra HDR file, or of an Adobe gain map JPEG file.
     *
     * <p>The gain map is the first image after the primary one, among those the Multi-Picture Format index of
     * the primary image's {@code APP2 MPF} segment lists, or without an index those the container directory of
     * its XMP packet lists, whose header holds gain map metadata: an ISO 21496-1 {@code APP2} segment
     * ({@code urn:iso:std:iso:ts:21496:-1}) with more than its version fields, or else the {@code hdrgm}
     * properties of its XMP packet. Metadata that does not parse is skipped as if absent. The gain map image is
     * decoded as {@link #decode(MediaSource, ImageDecoderOptions)} decodes it under the limits and the bit depth
     * choice of the options, without color conversion; its embedded RGB ICC profile, if any, is the
     * {@linkplain GainMap#alternateProfile() color space of the alternate rendition}. Without such an image, an
     * Apple gain map (a secondary image whose XMP declares {@code HDRGainMap:HDRGainMapVersion}) is converted to the
     * ISO 21496-1 model as the HEIC decoder converts Apple gain maps, with the headroom of
     * the Apple maker note of the primary image's EXIF block.
     *
     * @param source the input
     * @param options the limits and the bit depth choice of the gain map image; the size hint and the color
     * conversion are ignored
     * @return the gain map, or empty when the file has none
     * @throws MediaException.Malformed if the file or its gain map image is invalid
     * @throws MediaException.Unsupported if the gain map image uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the input or the gain map image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<GainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, options.limits(), true);
                var secondaries = UltraHdr.secondaries(data, options.limits());
                for (var secondary : secondaries) {
                    if (secondary.metadata() == null) {
                        continue;
                    }
                    var image = secondary.image();
                    var decoded = decode(MediaSource.of(data, image.offset(), image.length()), plain(options));
                    var profile = decoded.colorProfile().filter(value -> value.colorSpace() == ProfileColorSpace.RGB);
                    return Optional.of(new GainMap(secondary.metadata(), decoded.picture(), profile,
                            Optional.empty()));
                }
                for (var secondary : secondaries) {
                    if (!secondary.isApple()) {
                        continue;
                    }
                    var image = secondary.image();
                    var decoded = decode(MediaSource.of(data, image.offset(), image.length()), plain(options));
                    var reader = new JpegReader(data, 0, data.length, options.limits());
                    reader.readHeaders();
                    var primaryXmp = reader.xmp(options.limits());
                    var packets = new ArrayList<XmpPacket>();
                    packets.add(secondary.xmp());
                    packets.add(primaryXmp == null ? null : parseXmp(primaryXmp));
                    return AppleGainMaps.convert(decoded.picture(), reader.exif(), packets);
                }
                return Optional.<GainMap>empty();
            });
        }
    }

    /**
     * Lists the images a JPEG file holds after its primary image, within the
     * {@linkplain MediaLimits#DEFAULT default limits}.
     *
     * @param source the input
     * @return the images, as {@link #auxiliaryImages(MediaSource, MediaLimits)} describes
     * @throws MediaException.Malformed if the input is not a JPEG stream
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<AuxiliaryImage> auxiliaryImages(MediaSource source) {
        return auxiliaryImages(source, MediaLimits.DEFAULT);
    }

    /**
     * Lists the images a JPEG file holds after its primary image: those its Multi-Picture Format index lists
     * (thumbnails, the views of stereo and multi-angle images, disparity images, gain maps), or without an index
     * those its XMP container directory lists (Ultra HDR gain maps, Dynamic Depth images).
     *
     * <p>Each image is listed with the size of its frame header; an index entry whose image does not start
     * with an SOI marker or has no valid frame header is left out. An image whose header holds gain map
     * metadata, or that the container directory names {@code GainMap}, is a
     * {@link AuxiliaryImageType#GAIN_MAP}; one it names {@code Depth} a {@link AuxiliaryImageType#DEPTH}; the
     * others take their role from their Multi-Picture Format type code. The file is read whole.
     *
     * @param source the input
     * @param limits the bounds of the input and of the XMP packets
     * @return the images in file order, indexed from 0; empty for a file of one image
     * @throws MediaException.Malformed if the input is not a JPEG stream
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<AuxiliaryImage> auxiliaryImages(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var data = SourceBytes.readAll(source, limits, true);
                return List.copyOf(listed(data, limits).stream().map(Listed::description).toList());
            });
        }
    }

    /**
     * Decodes an image a JPEG file holds after its primary image under the
     * {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @param index the index of the image in the list of {@link #auxiliaryImages(MediaSource)}
     * @return the decoded image
     * @throws IndexOutOfBoundsException if the file lists no image of that index
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the input or the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #decodeAuxiliaryImage(MediaSource, int, ImageDecoderOptions)
     */
    public static DecodedImage decodeAuxiliaryImage(MediaSource source, int index) {
        return decodeAuxiliaryImage(source, index, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes an image a JPEG file holds after its primary image, as
     * {@link #decode(MediaSource, ImageDecoderOptions)} decodes a JPEG file.
     *
     * @param source the input
     * @param index the index of the image in the list of {@link #auxiliaryImages(MediaSource, MediaLimits)}
     * @param options the limits, the size hint, the bit depth choice and the color conversion
     * @return the decoded image
     * @throws IndexOutOfBoundsException if the file lists no image of that index
     * @throws MediaException.Malformed if the file or the image is invalid
     * @throws MediaException.Unsupported if the image uses a coding feature listed on the class
     * @throws MediaException.LimitExceeded if the input or the image exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decodeAuxiliaryImage(MediaSource source, int index, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var data = Guards.malformed(WHAT, () -> SourceBytes.readAll(source, options.limits(), true));
            var listed = Guards.malformed(WHAT, () -> listed(data, options.limits()));
            Objects.checkIndex(index, listed.size());
            var image = listed.get(index).image();
            return decode(MediaSource.of(data, image.offset(), image.length()), options.toBuilder().wholeImage()
                    .build());
        }
    }

    /**
     * A listed secondary image.
     *
     * @param image the location of the image in the file
     * @param description its description
     */
    private record Listed(JpegContainer.Image image, AuxiliaryImage description) {
    }

    /**
     * Lists the secondary images of a file that have a valid frame header.
     *
     * @param data the file
     * @param limits the bounds of the XMP packets
     * @return the images
     */
    private static List<Listed> listed(byte[] data, MediaLimits limits) {
        var result = new ArrayList<Listed>();
        for (var secondary : UltraHdr.secondaries(data, limits)) {
            var image = secondary.image();
            ImageInfo info;
            try {
                info = JpegProbe.probe(MediaSource.of(data, image.offset(), image.length()));
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                continue;
            }
            result.add(new Listed(image, UltraHdr.describe(result.size(), secondary, info.width(), info.height())));
        }
        return result;
    }

    /**
     * {@return a parsed XMP packet, or {@code null} when it does not parse}
     */
    private static XmpPacket parseXmp(byte[] xmp) {
        try {
            return XmpPacket.parse(xmp);
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * {@return the options of a gain map image: the limits and the bit depth choice of others, without color
     * conversion or size hint}
     */
    private static ImageDecoderOptions plain(ImageDecoderOptions options) {
        return ImageDecoderOptions.builder().limits(options.limits()).highBitDepth(options.highBitDepth()).build();
    }

    /**
     * Decodes a region of the image of a stream whose headers are read.
     *
     * <p>A DCT frame decodes the MCUs covering the region grown by {@link ImageRegions#expand}, at the reduction
     * the hint scaled to the image selects, converts them as the whole image is, and crops the region; a lossless
     * or hierarchical image decodes whole and is cropped.
     *
     * @param reader the reader, its headers read and checked
     * @param frame the frame header
     * @param options the decoder options
     * @param region the region, in pixels of the full-size image
     * @return the decoded region, without its pixel density
     * @throws IllegalArgumentException if the region does not lie inside the image
     */
    private static DecodedImage decodeRegion(JpegReader reader, JpegFrame frame, ImageDecoderOptions options,
                                             Region region) {
        var whole = options.toBuilder().wholeImage().build();
        if (!reader.windowable() || frame.height() == 0) {
            // the size of a hierarchical image, or of a frame whose height a DNL segment gives, is known once decoded
            return ImageRegions.crop(decodeImage(reader, frame, whole.toBuilder().fullSize().build(), null), region);
        }
        ImageRegions.check(region, frame.width(), frame.height());
        var hint = ImageRegions.scaledHint(whole, region, frame.width(), frame.height());
        var scale = reader.chooseScale(hint.minWidth(), hint.minHeight());
        var width = (frame.width() + (1 << scale) - 1) >> scale;
        var height = (frame.height() + (1 << scale) - 1) >> scale;
        var mapped = ImageRegions.map(region, frame.width(), frame.height(), width, height);
        var rectangle = ImageRegions.expand(mapped, width, height);
        var origin = reader.windowOrigin(scale, rectangle);
        var image = decodeImage(reader, frame, hint, rectangle);
        return ImageRegions.withPicture(image, ImageOps.crop(image.picture(), mapped.x() - origin[0],
                mapped.y() - origin[1], mapped.width(), mapped.height()));
    }

    /**
     * Decodes the image of a stream whose headers are read, or the window of whole MCUs of it that covers a
     * rectangle.
     *
     * @param reader the reader, its headers read and checked
     * @param frame the frame header
     * @param options the decoder options; the region is ignored
     * @param rectangle the rectangle, in pixels of the picture at the reduction the hint selects, or {@code null}
     * for the whole image; only for a stream whose {@link JpegReader#windowable()} holds
     * @return the decoded image, or the window, without its pixel density
     */
    private static DecodedImage decodeImage(JpegReader reader, JpegFrame frame, ImageDecoderOptions options,
                                            Region rectangle) {
        var reversible = LosslessOutput.reversibleRgb(reader);
        var colorSpace = reversible ? JpegColorSpace.RGB : reader.colorSpace();
        if (colorSpace == JpegColorSpace.UNKNOWN) {
            return decodeChannels(reader, options, rectangle);
        }
        Picture wide = null;
        JpegPlanes planes = null;
        if (!reader.lossless()) {
            var scale = reader.chooseScale(options.minWidth(), options.minHeight());
            if (options.highBitDepth() && LosslessOutput.keepsPrecision(reader, colorSpace)) {
                wide = JpegOutput.toWidePicture(reader.decodeWidePlanes(scale, rectangle), colorSpace,
                        options.limits());
            } else {
                planes = reader.decodePlanes(scale, rectangle);
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
     * @param rectangle the rectangle of the reduced picture whose MCUs decode, or {@code null} for all of them
     * @return the decoded image
     */
    private static DecodedImage decodeChannels(JpegReader reader, ImageDecoderOptions options, Region rectangle) {
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
                    ? JpegOutput.toWideChannels(reader.decodeWidePlanes(scale, rectangle), layout, limits)
                    : JpegOutput.toChannels(reader.decodePlanes(scale, rectangle), layout, limits);
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
