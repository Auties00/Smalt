/* Ports libwebp 1.6.0 (src/dec/webp_dec.c, src/demux/anim_decode.c). Copyright 2010 Google Inc.;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.internal.anim.FrameDecoder;
import com.github.auties00.smalt.image.webp.internal.anim.StillAnimationReader;
import com.github.auties00.smalt.image.webp.internal.anim.WebpAnimationReader;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.image.webp.internal.lossless.Vp8lDecoder;
import com.github.auties00.smalt.image.webp.internal.riff.WebpParser;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.io.SourceReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes WebP images: lossy (VP8) with or without an alpha channel, lossless (VP8L), and
 * animations.
 *
 * <p>Still images decode to their native representation: a lossy image to a limited-range
 * BT.601 4:2:0 {@link com.github.auties00.smalt.image.YuvPicture} (with an alpha plane when
 * the file has an {@code ALPH} chunk), a lossless image to an {@link RgbaPicture}. An animation
 * decodes to its first frame composited on the canvas, as an {@link RgbaPicture};
 * {@link #openAnimation} plays every frame. Every size the file declares is checked against the
 * {@link MediaLimits} of the options before the corresponding allocation, and malformed input
 * raises {@link MediaException.Malformed}, never an unchecked runtime exception.
 *
 * <p>Lossy images of at least a megapixel, and the lossy frames an {@link #openAnimation} reader
 * plays, decode in parallel on the shared threads
 * {@link com.github.auties00.smalt.MediaRuntime MediaRuntime} describes; the pixels do not
 * depend on the thread count. The reduced-size hint of {@link ImageDecoderOptions} is ignored:
 * WebP has no reduced-size decoding, so images always decode at full size.
 *
 * <p>Container acceptance, the decoded pixels and the composited animation frames are
 * byte-identical to libwebp's {@code dwebp} and animation decoder. This class is thread-safe.
 */
public final class WebpDecoder {
    /**
     * Prevents instantiation.
     */
    private WebpDecoder() {
        throw new AssertionError();
    }

    /**
     * Summarizes a WebP file from its headers, without decoding pixels.
     *
     * <p>The canvas size, the transparency and animation flags and the frame count come from the
     * {@code VP8X} chunk and the frame headers; the orientation comes from the {@code EXIF} chunk.
     * Every animation frame header is validated. An animation is reported as animated only when
     * it holds more than one frame.
     *
     * @param source the file
     * @return the summary
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.LimitExceeded if probing runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed("WebP file", () -> {
                var layout = WebpParser.parse(source, LimitChecks.UNLIMITED);
                var orientation = Orientation.NORMAL;
                if (layout.exif != null && layout.exif.size() <= 1 << 20) {
                    var exif = FrameDecoder.read(new SourceReader(source, 4096), layout.exif, MediaLimits.DEFAULT);
                    orientation = ExifReader.orientation(TiffExif.stripExifPrefix(exif));
                }
                return new ImageInfo(ImageFormat.WEBP, layout.canvasWidth, layout.canvasHeight, layout.declaresAlpha(),
                        layout.animated && layout.frameCount > 1, layout.frames(), orientation);
            });
        }
    }

    /**
     * Decodes a WebP image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the decoded image
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits, or decoding runs
     * out of memory
     * @throws MediaException.Unsupported if the image is lossy and no VP8 decoder is available
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes a still image, or the first frame of an animation composited on its canvas.
     *
     * @param source the file
     * @param options the limits; the reduced-size hint is ignored
     * @return the decoded image with its orientation, ICC profile and EXIF block
     * @throws MediaException.Malformed if the file is invalid, corrupt or truncated
     * @throws MediaException.LimitExceeded if a declared size exceeds the limits, or decoding runs
     * out of memory
     * @throws MediaException.Unsupported if the image is lossy and no VP8 decoder is available
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed("WebP file", () -> {
                var limits = options.limits();
                var layout = WebpParser.parse(source, limits);
                var in = new SourceReader(source, 16 * 1024);
                Picture picture;
                if (layout.animated) {
                    var reader = new WebpAnimationReader(source, layout, limits);
                    var first = reader.read();
                    if (first == null) {
                        throw new MediaException.Malformed("animation holds no frame");
                    }
                    picture = first.picture();
                } else if (layout.lossless) {
                    picture = decodeLossless(in, layout, limits);
                } else {
                    var vp8 = FrameDecoder.read(in, layout.image, limits);
                    var alph = layout.alpha == null ? null : FrameDecoder.read(in, layout.alpha, limits);
                    picture = FrameDecoder.decodeLossy(vp8, alph, layout.imageWidth, layout.imageHeight, limits);
                }
                var icc = layout.iccp == null ? null : FrameDecoder.read(in, layout.iccp, limits);
                var exif = layout.exif == null ? null : TiffExif.stripExifPrefix(FrameDecoder.read(in, layout.exif, limits));
                var orientation = exif == null ? Orientation.NORMAL : ExifReader.orientation(exif);
                var image = new DecodedImage(picture, ImageFormat.WEBP, orientation, layout.canvasWidth,
                        layout.canvasHeight, Optional.ofNullable(icc), Optional.ofNullable(exif));
                return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
            });
        }
    }

    /**
     * Opens a WebP image as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input
     * @return the animation reader
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens a reader over the frames of an animation.
     *
     * <p>A still image is presented as an animation of one frame with a duration of zero that
     * plays once. The reader can seek: a seek backwards composites again from the first frame.
     *
     * @param source the file
     * @param options the limits; the reduced-size hint is ignored
     * @return the reader; closing it does not close {@code source}
     * @throws MediaException.Malformed if the file structure is invalid or truncated
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed("WebP file", () -> {
            var limits = options.limits();
            var layout = WebpParser.parse(source, limits);
            if (layout.animated) {
                return SeekableAnimationReader.of(() -> new WebpAnimationReader(source, layout, limits));
            }
            return SeekableAnimationReader.of(() -> new StillAnimationReader(layout.canvasWidth, layout.canvasHeight,
                    () -> decodeStillRgba(source, layout, limits)));
        });
    }

    /**
     * Decodes a lossless still image.
     *
     * @param in the reader over the file
     * @param layout the parsed structure
     * @param limits the resource bounds
     * @return the RGBA picture, declaring alpha when the header hints it or a pixel is not opaque
     */
    private static RgbaPicture decodeLossless(SourceReader in, WebpParser.Layout layout, MediaLimits limits) {
        var payload = FrameDecoder.read(in, layout.image, limits);
        var result = Vp8lDecoder.decodeRgba(payload, 0, payload.length, limits);
        var hasAlpha = result.alphaHint() || YuvConverter.hasTransparency(result.rgba(), result.width() * 4,
                result.width(), result.height());
        return RgbaPicture.of(result.width(), result.height(), result.width() * 4, result.rgba(), hasAlpha);
    }

    /**
     * Decodes a still image to RGBA, converting lossy images with fancy upsampling.
     *
     * @param source the file
     * @param layout the parsed structure
     * @param limits the resource bounds
     * @return the RGBA picture
     */
    private static RgbaPicture decodeStillRgba(MediaSource source, WebpParser.Layout layout, MediaLimits limits) {
        return Guards.malformed("WebP file", () -> {
            var in = new SourceReader(source, 16 * 1024);
            if (layout.lossless) {
                return decodeLossless(in, layout, limits);
            }
            var width = layout.imageWidth;
            var height = layout.imageHeight;
            LimitChecks.checkAllocation(limits, (long) width * height * 4);
            var rgba = new byte[width * height * 4];
            FrameDecoder.decodeInto(in, layout.image, false, layout.alpha, width, height, limits, rgba, 0, width * 4);
            return RgbaPicture.of(width, height, width * 4, rgba, layout.alpha != null);
        });
    }
}
