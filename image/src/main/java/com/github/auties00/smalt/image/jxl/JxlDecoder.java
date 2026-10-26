/* Ports jxl-oxide 0.12.6 (the jxl-oxide crate: image loading and frame rendering). Copyright Wonwoo Choi and the
   jxl-oxide contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.jxl.internal.JxlAnimationReader;
import com.github.auties00.smalt.image.jxl.internal.JxlCodestream;
import com.github.auties00.smalt.image.jxl.internal.JxlOutput;
import com.github.auties00.smalt.image.jxl.internal.bitstream.GainMapBundle;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlContainer;
import com.github.auties00.smalt.image.jxl.internal.color.IccSynthesizer;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.jpeg.JpegReconstructor;
import com.github.auties00.smalt.image.jxl.internal.render.FrameImage;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decodes JPEG XL images (ISO/IEC 18181-1 and -2): bare codestreams and ISO-BMFF containers,
 * lossless (modular) and lossy (VarDCT) frames, animations, and recompressed JPEG files.
 *
 * <p>The decoder implements the whole codestream: entropy coding with ANS and prefix codes,
 * hybrid integers, LZ77 and context clustering; modular images with meta-adaptive trees, every
 * predictor including the weighted one, and the RCT, palette and squeeze transforms; VarDCT with
 * every transform type, adaptive quantization, chroma from luma, adaptive LF smoothing, LF frames
 * and progressive passes; Gaborish and the edge-preserving filter; patches, splines and noise;
 * upsampling; blending, reference frames and animation; and the XYB, YCbCr and enumerated color
 * encodings, with embedded ICC profiles. Every displayed frame is composited in full precision and
 * converted to the requested sample depth only at the end. Decoded images match the reference
 * images of the JPEG XL conformance suite within the tolerances the suite publishes.
 *
 * <p>{@link #decode(MediaSource, ImageDecoderOptions)} returns 8-bit RGBA by default, and 16-bit
 * RGBA when {@link ImageDecoderOptions#highBitDepth()} is set and the image is deeper than 8 bits;
 * grayscale images are replicated into the color channels, premultiplied alpha is converted to
 * straight alpha, and CMYK images are converted to sRGB through their profile. The picture is in
 * the color space {@link DecodedImage#iccProfile()} describes, the embedded profile or one
 * synthesized from the enumerated encoding, unless {@link ImageDecoderOptions#convertToSrgb()}
 * asks for sRGB. With {@link ImageDecoderOptions#keepChannels()} set (and {@code convertToSrgb} not set), a
 * CMYK image, an image of floating-point samples, or one with extra channels other than a single alpha
 * channel decodes to a {@link com.github.auties00.smalt.image.ChannelPicture ChannelPicture} instead: gray,
 * RGB or CMYK (ink amounts, 0 being no ink) followed by the other extra channels in file order, the alpha
 * channel straight or premultiplied as stored, in the color space of the color planes
 * {@link #decodeFloat(MediaSource, ImageDecoderOptions)} describes; floating-point images keep their values
 * unclamped, sample for sample equal to the planes {@code decodeFloat} returns, and integer images are
 * quantized as the RGBA output is. {@link #decodeFloat(MediaSource, ImageDecoderOptions)} returns every
 * channel at full precision, as separate planes. Pixels are not oriented: {@link DecodedImage#orientation()} is
 * the codestream's orientation. The EXIF block is the payload of the container's {@code Exif} box, decompressed
 * when the box is stored Brotli-compressed in a {@code brob} box; {@link #readBoxes(MediaSource)} lists every
 * box, compressed ones decompressed.
 *
 * <p>Spot color channels are composited into the color channels of the RGBA pictures that
 * {@code decode}, {@link #decodePreview(MediaSource, ImageDecoderOptions)} and the animation readers return:
 * each one, in file order, blends its color with the weight of its solidity times its sample (a gray image takes
 * the red component of the color, as libjxl renders gray images), in linear light
 * for an XYB image whose displayed frame is neither blended nor kept as a reference after its color transform,
 * and in the output color space otherwise. Channel pictures and {@code decodeFloat} keep the spot channels
 * apart, uncomposited. An XYB image whose original color space is an embedded ICC profile is converted to that
 * profile, gray or RGB, when Smalt can apply it, and to sRGB otherwise.
 *
 * <p>Every size the file declares is checked against the {@link MediaLimits} of the options
 * before the corresponding allocation, and malformed input raises
 * {@link MediaException.Malformed}. Groups of a frame decode in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the pixels do not depend
 * on the thread count. Under the reduced-size hint of {@link ImageDecoderOptions}, {@code decode} may return the
 * preview frame or a reduced decode of a progressive image, as {@link #decode(MediaSource, ImageDecoderOptions)}
 * describes. {@link #decodeGainMap(MediaSource, ImageDecoderOptions)} decodes the gain map of the
 * {@code jhgm} box.
 *
 * <p>This class is thread-safe.
 */
public final class JxlDecoder {
    /**
     * Prevents instantiation.
     */
    private JxlDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a JPEG XL file from its headers, without decoding pixels.
     *
     * <p>The size is the stored size; the frame count is the number of displayed frames, found by
     * reading every frame header; an image is animated when it has an animation header and more
     * than one displayed frame. The file is read whole, so a file larger than the
     * {@link MediaLimits#maxBytes() byte limit} of {@link MediaLimits#DEFAULT} cannot be probed; no
     * other limit applies.
     *
     * @param source the file
     * @return the summary
     * @throws MediaException.Malformed if the headers are invalid or truncated
     * @throws MediaException.LimitExceeded if the file exceeds the default byte limit or probing runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return run(() -> {
            var data = SourceBytes.readAll(source, MediaLimits.DEFAULT, false);
            var codestream = JxlCodestream.open(data, LimitChecks.UNLIMITED);
            var header = codestream.header();
            var frames = JxlAnimationReader.countKeyframes(codestream, LimitChecks.UNLIMITED);
            return new ImageInfo(ImageFormat.JXL, header.width(), header.height(), header.alphaChannel() >= 0,
                    header.animation() != null && frames > 1, Math.max(frames, 1), Orientation.ofExif(header.orientation()));
        });
    }

    /**
     * Decodes a JPEG XL image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a still image, or the first displayed frame of an animation, composited.
     *
     * <p>Under a reduced-size hint ({@link ImageDecoderOptions#minWidth()}), the decoder picks the smallest of
     * these renditions that is at least as large as the hint and smaller than the image: the preview frame, at
     * its own size; and the first displayed frame reduced by 2, 4 or 8, each dimension rounded up, when that frame
     * replaces the whole canvas and only LF and reference-only frames come before it. A reduction by 8 needs no
     * pass: it is the LF image of a VarDCT frame (about the average of every 8 by 8 block), or a modular frame
     * decoded from its LF channels (squeezed, or small enough to be stored whole with its global data); a
     * reduction by 2 or 4 needs a frame with progressive passes that describe it. A
     * reduced frame is decoded from the passes its reduction needs, the later ones left out, and its samples,
     * clamped to their nominal range unless they are floating-point, are averaged over boxes of the reduction's
     * size. When the modular channels of the frame that are not squeeze residuals (such as an unsqueezed alpha
     * channel) are complete only after its last pass, no reduced decode is cheaper than a full one, and the
     * preview, when it qualifies, or the full image is returned. The preview wins a tie.
     *
     * @param source the file
     * @param options the limits, the bit depth choice, the channel choice, the sRGB conversion and the
     * reduced-size hint
     * @return the decoded image with its orientation, color profile, EXIF block and XMP packet (the
     * {@code xml } box of the container); its source size is the full size even when the picture is reduced
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var region = options.region().orElse(null);
        if (region == null) {
            return run(() -> {
                var limits = options.limits();
                return decodeImage(JxlCodestream.open(SourceBytes.readAll(source, limits, true), limits), options);
            });
        }
        var image = run(() -> {
            var limits = options.limits();
            var codestream = JxlCodestream.open(SourceBytes.readAll(source, limits, true), limits);
            var header = codestream.header();
            ImageRegions.check(region, header.width(), header.height());
            var whole = ImageRegions.scaledHint(options.toBuilder().wholeImage().build(), region, header.width(),
                    header.height());
            return decodeImage(codestream, whole);
        });
        return ImageRegions.crop(image, region);
    }

    /**
     * Decodes the preview frame of a JPEG XL image under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the preview, or empty when the file has none
     * @throws MediaException.Malformed if the file or its preview frame is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decodePreview(MediaSource, ImageDecoderOptions)
     */
    public static Optional<DecodedImage> decodePreview(MediaSource source) {
        return decodePreview(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the preview frame of a JPEG XL image: a small rendition of the image, stored before its frames,
     * that a viewer can show while the image loads.
     *
     * <p>The preview is converted as {@link #decode(MediaSource, ImageDecoderOptions)} converts the image: in
     * the same color space, with the same orientation and metadata.
     *
     * @param source the file
     * @param options the limits, the bit depth choice, the channel choice and the sRGB conversion; the
     * reduced-size hint is ignored
     * @return the preview, whose source size is the size of the full image, or empty when the file has none
     * @throws MediaException.Malformed if the file or its preview frame is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<DecodedImage> decodePreview(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var codestream = JxlCodestream.open(SourceBytes.readAll(source, limits, true), limits);
            if (!codestream.hasPreview()) {
                return Optional.empty();
            }
            codestream.setRenderSpotColors(!keepsChannels(options));
            return Optional.of(image(codestream, codestream.decodePreview().canvas(), options));
        });
    }

    /**
     * Decodes the gain map of a JPEG XL file under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the gain map, or empty when the file has no {@code jhgm} box
     * @throws MediaException.Malformed if the file, its {@code jhgm} box or the gain map codestream is invalid,
     * corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile of the gain map cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decodeGainMap(MediaSource, ImageDecoderOptions)
     */
    public static Optional<JxlGainMap> decodeGainMap(MediaSource source) {
        return decodeGainMap(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the gain map of a JPEG XL file: the content of its first {@code jhgm} box, stored as it is or
     * Brotli-compressed in a {@code brob} box.
     *
     * <p>The gain map image is decoded from its codestream as {@link #decode(MediaSource, ImageDecoderOptions)}
     * decodes an image, under the same options; it has no metadata boxes of its own.
     *
     * @param source the file
     * @param options the options the gain map image is decoded under
     * @return the gain map, or empty when the file has no {@code jhgm} box
     * @throws MediaException.Malformed if the file, its {@code jhgm} box or the gain map codestream is invalid,
     * corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile of the gain map cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<JxlGainMap> decodeGainMap(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var container = JxlContainer.parse(SourceBytes.readAll(source, limits, true), limits);
            var box = container.box("jhgm");
            if (box == null) {
                return Optional.empty();
            }
            var bundle = GainMapBundle.parse(box.content(limits), limits);
            var codestream = bundle.codestream();
            if (codestream.length < 2 || (codestream[0] & 0xFF) != 0xFF || codestream[1] != 0x0A) {
                throw new MediaException.Malformed("the JPEG XL gain map is not a bare codestream");
            }
            var image = decodeImage(JxlCodestream.open(codestream, limits), options);
            return Optional.of(new JxlGainMap(bundle.version(), bundle.metadata(),
                    Optional.ofNullable(alternateIcc(bundle)), codestream, image));
        });
    }

    /**
     * {@return the ICC profile of the alternate rendition of a gain map: the stored one, else one synthesized
     * from the stored color encoding, else {@code null}}
     *
     * @param bundle the {@code jhgm} content
     */
    private static byte[] alternateIcc(GainMapBundle bundle) {
        if (bundle.icc() != null) {
            return bundle.icc();
        }
        var encoding = bundle.colorEncoding();
        if (encoding == null || encoding.wantIcc()) {
            return null;
        }
        try {
            return IccSynthesizer.synthesize(encoding);
        } catch (MediaException exception) {
            return null;
        }
    }

    /**
     * Lists the boxes of a JPEG XL file other than its codestream under the
     * {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the boxes in file order
     * @throws MediaException.Malformed if the box structure is invalid or a {@code brob} box is corrupt
     * @throws MediaException.LimitExceeded if the file or a decompressed box exceeds the default byte limit
     * @throws MediaException.Io if reading the source fails
     * @see #readBoxes(MediaSource, ImageDecoderOptions)
     */
    public static List<JxlBox> readBoxes(MediaSource source) {
        return readBoxes(source, ImageDecoderOptions.defaults());
    }

    /**
     * Lists the boxes of a JPEG XL file other than its codestream: every box of the container except the
     * signature, {@code ftyp}, {@code jxll}, {@code jxlc} and {@code jxlp} boxes, in file order.
     *
     * <p>A {@code brob} box is listed as the box it compresses, decompressed; one that compresses a box whose
     * type starts with {@code jxl}, a {@code jbrd} box or another {@code brob} box is left out. A bare codestream
     * has no boxes.
     *
     * @param source the file
     * @param options the limits; the other options are ignored
     * @return the boxes in file order
     * @throws MediaException.Malformed if the box structure is invalid or a {@code brob} box is corrupt
     * @throws MediaException.LimitExceeded if the file or a decompressed box exceeds the byte limit
     * @throws MediaException.Io if reading the source fails
     */
    public static List<JxlBox> readBoxes(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var container = JxlContainer.parse(SourceBytes.readAll(source, limits, true), limits);
            var boxes = new ArrayList<JxlBox>();
            for (var box : container.boxes()) {
                boxes.add(new JxlBox(box.type(), box.content(limits), box.compressed()));
            }
            return List.copyOf(boxes);
        });
    }

    /**
     * Decodes the first displayed frame of a codestream, or a smaller rendition under a reduced-size hint, as
     * {@link #decode(MediaSource, ImageDecoderOptions)} describes.
     *
     * @param codestream the codestream, positioned at its first frame
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage decodeImage(JxlCodestream codestream, ImageDecoderOptions options) {
        codestream.setRenderSpotColors(!keepsChannels(options));
        FrameImage canvas = null;
        if (options.minWidth() > 0 || options.minHeight() > 0) {
            canvas = reducedCanvas(codestream, options);
        }
        if (canvas == null) {
            var keyframe = codestream.nextKeyframe();
            if (keyframe == null) {
                throw new MediaException.Malformed("JPEG XL file without a displayed frame");
            }
            canvas = keyframe.canvas();
        }
        return image(codestream, canvas, options);
    }

    /**
     * Decodes the smallest rendition of the first displayed frame that satisfies a reduced-size hint.
     *
     * @param codestream the codestream, positioned at its first frame and left there
     * @param options the decoding options, with a reduced-size hint
     * @return the canvas of the preview, of a reduced frame or of the full frame, or {@code null} when no
     * smaller rendition satisfies the hint
     */
    private static FrameImage reducedCanvas(JxlCodestream codestream, ImageDecoderOptions options) {
        var header = codestream.header();
        var minWidth = options.minWidth();
        var minHeight = options.minHeight();
        var best = (long) header.width() * header.height();
        var preview = false;
        if (codestream.hasPreview() && header.previewWidth() >= minWidth && header.previewHeight() >= minHeight
                && (long) header.previewWidth() * header.previewHeight() < best) {
            best = (long) header.previewWidth() * header.previewHeight();
            preview = true;
        }
        var factor = 1;
        for (var candidate : new int[]{2, 4, 8}) {
            var width = (header.width() + candidate - 1) / candidate;
            var height = (header.height() + candidate - 1) / candidate;
            if (width >= minWidth && height >= minHeight && (long) width * height < best
                    && codestream.reductionPasses(candidate) >= 0) {
                best = (long) width * height;
                factor = candidate;
            }
        }
        if (factor > 1) {
            var reduced = codestream.decodeReduced(factor);
            if (reduced.factor() > 1 || !preview) {
                return reduced.canvas();
            }
        }
        return preview ? codestream.decodePreview().canvas() : null;
    }

    /**
     * {@return whether decoding returns channel pictures for images a packed RGBA picture cannot hold}
     *
     * @param options the decoding options
     */
    private static boolean keepsChannels(ImageDecoderOptions options) {
        return options.keepChannels() && !options.convertToSrgb();
    }

    /**
     * Converts a displayed canvas to the decoded image the options ask for.
     *
     * @param codestream the codestream the canvas comes from
     * @param canvas the canvas, in the output color space
     * @param options the decoding options
     * @return the decoded image
     */
    private static DecodedImage image(JxlCodestream codestream, FrameImage canvas, ImageDecoderOptions options) {
        var header = codestream.header();
        var container = codestream.container();
        var orientation = Orientation.ofExif(header.orientation());
        if (keepsChannels(options)) {
            var channels = JxlOutput.toChannels(canvas, header, codestream.icc(), options.highBitDepth());
            if (channels != null) {
                return new DecodedImage(channels.picture(), ImageFormat.JXL, orientation, header.width(),
                        header.height(), channels.icc(), Optional.ofNullable(container.exif()),
                        Optional.ofNullable(container.xmp()), channels.profile());
            }
        }
        var output = JxlOutput.toPicture(canvas, header, codestream.icc(), options.highBitDepth());
        var image = new DecodedImage(output.picture(), ImageFormat.JXL, orientation, header.width(), header.height(),
                output.icc(), Optional.ofNullable(container.exif()), Optional.ofNullable(container.xmp()),
                output.profile());
        return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
    }

    /**
     * Decodes the first displayed frame of a JPEG XL image at full floating-point precision under
     * the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file
     * @return the planes of every channel
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decodeFloat(MediaSource, ImageDecoderOptions)
     */
    public static JxlFloatImage decodeFloat(MediaSource source) {
        return decodeFloat(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first displayed frame of a JPEG XL image at full floating-point precision.
     *
     * <p>The planes keep every channel the image has, in the color space
     * {@link JxlFloatImage#iccProfile()} describes, with neither clamping nor conversion.
     *
     * @param source the file
     * @param options the limits; the other options are ignored
     * @return the planes of every channel
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static JxlFloatImage decodeFloat(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var codestream = JxlCodestream.open(SourceBytes.readAll(source, limits, true), limits);
            var keyframe = codestream.nextKeyframe();
            if (keyframe == null) {
                throw new MediaException.Malformed("JPEG XL file without a displayed frame");
            }
            var header = codestream.header();
            var canvas = keyframe.canvas();
            var types = new ArrayList<JxlChannelType>();
            for (var info : header.extraChannels()) {
                types.add(channelType(info));
            }
            var alpha = header.alphaChannel();
            var premultiplied = alpha >= 0 && header.extraChannels().get(alpha).alphaAssociated();
            return new JxlFloatImage(canvas.width, canvas.height, canvas.colorChannels, types, canvas.planes, premultiplied,
                    JxlOutput.canvasIcc(header, codestream.icc()), Orientation.ofExif(header.orientation()));
        });
    }

    /**
     * Opens a JPEG XL image as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the file, read whole when the reader opens
     * @return the reader; closing it does not close {@code source}
     * @throws MediaException.Malformed if the headers are invalid or truncated
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens a reader over the displayed frames of a JPEG XL image, as 8-bit RGBA canvases.
     *
     * <p>A still image is presented as an animation of one frame with a duration of zero. The
     * reader can seek: a seek starts compositing at the latest frame of the file's {@code jxli} frame index at
     * or before the target whose frames do not depend on the frames before it, and otherwise at the first frame,
     * or, when seeking forwards, goes on from the current frame. Spot colors are composited as {@code decode}
     * composites them.
     *
     * @param source the file, read whole when the reader opens
     * @param options the limits; the reduced-size hint is ignored
     * @return the reader; closing it does not close {@code source}
     * @throws MediaException.Malformed if the headers are invalid or truncated
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var data = SourceBytes.readAll(source, limits, true);
            var count = JxlAnimationReader.countKeyframes(JxlCodestream.open(data, limits), limits);
            var codestream = JxlCodestream.open(data, limits);
            codestream.setRenderSpotColors(true);
            return new JxlAnimationReader(codestream, count, limits);
        });
    }

    /**
     * Rebuilds the original JPEG file of a JPEG XL file that recompressed one losslessly, under
     * the {@link ImageDecoderOptions#defaults()}.
     *
     * <p>A JPEG recompressed by a JPEG XL encoder keeps, in its {@code jbrd} box, everything the
     * JPEG bitstream had beyond its DCT coefficients (markers, Huffman tables, the scan layout);
     * together with the coefficients of the codestream it gives back the original file byte for
     * byte.
     *
     * @param source the file
     * @return the original JPEG file, or empty when the file is not a recompressed JPEG
     * @throws MediaException.Malformed if the file or its reconstruction data is invalid
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #reconstructJpeg(MediaSource, ImageDecoderOptions)
     */
    public static Optional<byte[]> reconstructJpeg(MediaSource source) {
        return reconstructJpeg(source, ImageDecoderOptions.defaults());
    }

    /**
     * Rebuilds the original JPEG file of a JPEG XL file that recompressed one losslessly, reading
     * it under the limits of {@code options}.
     *
     * <p>The result is the one {@link #reconstructJpeg(MediaSource)} gives.
     *
     * @param source the file
     * @param options the limits; the other options are ignored
     * @return the original JPEG file, or empty when the file is not a recompressed JPEG
     * @throws MediaException.Malformed if the file or its reconstruction data is invalid
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Optional<byte[]> reconstructJpeg(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return run(() -> {
            var limits = options.limits();
            var codestream = JxlCodestream.open(SourceBytes.readAll(source, limits, true), limits);
            if (codestream.container().jbrd() == null) {
                return Optional.empty();
            }
            return Optional.of(JpegReconstructor.reconstruct(codestream, limits));
        });
    }

    /**
     * {@return the public type of an extra channel}
     *
     * @param info the channel description
     */
    private static JxlChannelType channelType(ExtraChannelInfo info) {
        return switch (info.type()) {
            case ExtraChannelInfo.TYPE_ALPHA -> JxlChannelType.ALPHA;
            case ExtraChannelInfo.TYPE_DEPTH -> JxlChannelType.DEPTH;
            case ExtraChannelInfo.TYPE_SPOT_COLOR -> JxlChannelType.SPOT_COLOR;
            case ExtraChannelInfo.TYPE_SELECTION_MASK -> JxlChannelType.SELECTION_MASK;
            case ExtraChannelInfo.TYPE_BLACK -> JxlChannelType.BLACK;
            case ExtraChannelInfo.TYPE_CFA -> JxlChannelType.CFA;
            case ExtraChannelInfo.TYPE_THERMAL -> JxlChannelType.THERMAL;
            case ExtraChannelInfo.TYPE_NON_OPTIONAL -> JxlChannelType.NON_OPTIONAL;
            default -> JxlChannelType.OPTIONAL;
        };
    }

    /**
     * Runs the body of an entry point as one operation of the shared threads, under the decoder
     * failure policy of {@link Guards#malformed}.
     *
     * @param body the work to run
     * @param <T> the result type
     * @return the result of {@code body}
     */
    private static <T> T run(Supplier<T> body) {
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed("JPEG XL file", body);
        }
    }
}
