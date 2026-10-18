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
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.jpeg.JpegReconstructor;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.io.SourceBytes;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.ArrayList;
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
 * asks for sRGB. {@link #decodeFloat(MediaSource, ImageDecoderOptions)} returns every channel at
 * full precision. Pixels are not oriented: {@link DecodedImage#orientation()} is the codestream's
 * orientation. The EXIF block is the payload of the container's {@code Exif} box, decompressed
 * when the box is stored Brotli-compressed in a {@code brob} box; other compressed boxes are
 * ignored.
 *
 * <p>Every size the file declares is checked against the {@link MediaLimits} of the options
 * before the corresponding allocation, and malformed input raises
 * {@link MediaException.Malformed}. Groups of a frame decode in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the pixels do not depend
 * on the thread count. The reduced-size hint of {@link ImageDecoderOptions} is ignored: images
 * always decode at full size.
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
     * @param source the file
     * @param options the limits, the bit depth choice and the sRGB conversion; the reduced-size
     * hint is ignored
     * @return the decoded image with its orientation, color profile and EXIF block
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.Unsupported if a CMYK profile cannot be applied
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits or decoding runs
     * out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
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
            var output = JxlOutput.toPicture(keyframe.canvas(), header, codestream.icc(), options.highBitDepth());
            var image = new DecodedImage(output.picture(), ImageFormat.JXL, Orientation.ofExif(header.orientation()),
                    header.width(), header.height(), output.icc(), Optional.ofNullable(codestream.container().exif()),
                    output.profile());
            return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
        });
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
     * reader can seek: a seek backwards decodes again from the first frame.
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
            return SeekableAnimationReader.of(() -> new JxlAnimationReader(JxlCodestream.open(data, limits), count,
                    limits));
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
