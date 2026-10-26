package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.ImageInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.anim.SeekableAnimationReader;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes GIF87a and GIF89a images and animations to packed RGBA8.
 *
 * <p>Supported: global and local color tables, LZW with 2- to 12-bit codes (including the
 * deferred clear, where an encoder keeps emitting 12-bit codes after the table is full),
 * interlaced images, transparency and delays from the graphic control extension, the disposal
 * methods (restore to background clears the rectangle to transparent, restore to previous restores
 * the rectangle; method 4 is read as restore to previous), images extending past the logical
 * screen (clipped), zero-size images (drawn as nothing, still counted as frames with their delay),
 * a zero-size logical screen (sized from the first image's extent), the loop count of the
 * {@code NETSCAPE2.0}/{@code ANIMEXTS1.0} application extensions, the ICC profile of the
 * {@code ICCRGBG1} application extension (ICC.1 Annex B) and the XMP packet of the
 * {@code XMP DataXMP} application extension.
 *
 * <p>Damage is rendered, not rejected: an LZW code past the current table stops
 * that image with the pixels decoded so far; a missing trailer, a truncated last image (drawn
 * partially), or unknown bytes between blocks end the datastream. Only an invalid header, a
 * truncated global color table, or a datastream without any image raises
 * {@link MediaException.Malformed}. Pixel indices past the color table are not drawn.
 *
 * <p>This class is thread-safe.
 */
public final class GifDecoder {
    /**
     * The input named in failure messages.
     */
    static final String WHAT = "GIF datastream";

    /**
     * Prevents instantiation.
     */
    private GifDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the block structure of a GIF datastream without decoding pixels.
     *
     * <p>Every block is visited (image data is skipped), so {@link ImageInfo#frameCount()} is
     * exact and {@link ImageInfo#animated()} is {@code true} when there is more than one image.
     * {@link ImageInfo#hasAlpha()} is {@code true} when an image has a transparent index, the
     * first image does not cover the canvas, an image is disposed to background, or the last
     * image is truncated. The size is the canvas size, reported without checking it against any
     * limit.
     *
     * @param source the input, read from offset zero
     * @return the image summary
     * @throws MediaException.Malformed if the header is invalid or the datastream holds no image
     * @throws MediaException.LimitExceeded if probing runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var summary = GifSummary.scan(source, MediaLimits.DEFAULT);
                return new ImageInfo(ImageFormat.GIF, summary.width(), summary.height(), summary.hasAlpha(),
                        summary.frames() > 1, summary.frames(), Orientation.NORMAL);
            });
        }
    }

    /**
     * Decodes a GIF image at full size under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded image
     * @throws MediaException.Malformed if the header is invalid or the datastream holds no image
     * @throws MediaException.LimitExceeded if the canvas or the image exceeds the default limits, or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first image of a GIF datastream, composited onto a transparent canvas of the
     * logical screen size.
     *
     * <p>The picture's {@link RgbaPicture#hasAlpha()} is {@code true} when the image has a
     * transparent index, does not cover the canvas, or was not fully decoded. The reduced-size
     * hint of {@code options} is ignored.
     *
     * <p>The ICC profile of an {@code ICCRGBG1} application extension preceding the first image
     * is returned in {@link DecodedImage#iccProfile()} and, when it parses and describes an RGB
     * color space, describes the pixels in {@link DecodedImage#colorProfile()}; with
     * {@link ImageDecoderOptions#convertToSrgb()} set, the pixels are converted to sRGB through it.
     * The XMP packet of an {@code XMP DataXMP} application extension preceding the first image is
     * returned in {@link DecodedImage#xmp()}.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the region (cut out of the whole image) and the sRGB conversion; the size hint
     * is ignored
     * @return the decoded image
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the header is invalid or the datastream holds no image
     * @throws MediaException.LimitExceeded if the canvas or the image exceeds the limits, or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ImageRegions.decode(options, null, whole -> decodeWhole(source, whole));
    }

    /**
     * Decodes the whole first frame of a datastream.
     *
     * @param source the datastream
     * @param options the decoding options; the region is ignored
     * @return the decoded image
     */
    private static DecodedImage decodeWhole(MediaSource source, ImageDecoderOptions options) {
        try (var _ = ParallelBands.operation()) {
            return Guards.malformed(WHAT, () -> {
                var limits = options.limits();
                var reader = new GifReader(source, limits);
                reader.readHeader();
                var frame = new GifFrame();
                if (!reader.nextFrame(frame, true)) {
                    throw new MediaException.Malformed("GIF datastream holds no image");
                }
                var width = GifSummary.canvasSide(reader.screenWidth(), frame.left, frame.width);
                var height = GifSummary.canvasSide(reader.screenHeight(), frame.top, frame.height);
                if (width <= 0 || height <= 0) {
                    throw new MediaException.Malformed("GIF canvas is empty");
                }
                LimitChecks.checkPicture(limits, width, height);
                LimitChecks.checkAllocation(limits, (long) width * height * 4);
                var canvas = new GifCanvas(width, height, true, limits);
                var complete = canvas.draw(frame);
                var alpha = frame.transparentIndex >= 0 || !frame.covers(width, height) || !complete;
                var picture = RgbaPicture.of(width, height, width * 4, canvas.picture().pixels(), alpha);
                var icc = reader.iccProfile();
                var image = new DecodedImage(picture, ImageFormat.GIF, Orientation.NORMAL, width, height,
                        Optional.ofNullable(icc), Optional.empty(), Optional.ofNullable(reader.xmp()), rgbProfile(icc));
                return options.convertToSrgb() ? ImageOps.toSrgb(image) : image;
            });
        }
    }

    /**
     * Parses an embedded ICC profile as the color space of the pixels, which are always RGB.
     *
     * @param icc the profile bytes, or {@code null}
     * @return the profile, or empty when absent, invalid or not of the RGB color space
     */
    private static Optional<ColorProfile> rgbProfile(byte[] icc) {
        if (icc == null) {
            return Optional.empty();
        }
        try {
            var profile = ColorProfile.parse(icc);
            return profile.colorSpace() == ProfileColorSpace.RGB ? Optional.of(profile) : Optional.empty();
        } catch (MediaException exception) {
            return Optional.empty();
        }
    }

    /**
     * Opens a GIF image as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero; it must stay open until the reader is closed
     * @return the animation reader
     * @throws MediaException.Malformed if the header is invalid or the datastream holds no image
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the default
     * limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the images of a GIF datastream as a composited animation.
     *
     * <p>The datastream is scanned once first (image data skipped) to size the canvas and count
     * the frames, so {@link AnimationReader#frameCount()} is exact and the frame count is checked
     * against the limits before anything is decoded; then frames are decoded on demand, keeping
     * memory independent of the number of frames. {@link AnimationReader#loopCount()} returns the
     * stored {@code NETSCAPE2.0}/{@code ANIMEXTS1.0} loop count ({@code 0} meaning forever), or
     * {@code 1} when the datastream has none; each frame's
     * {@link AnimationFrame#durationMillis()} is the stored delay times 10. A still GIF yields a
     * one-frame animation.
     *
     * <p>The returned reader reads {@code source} lazily, so the source must stay open until the
     * reader is closed. It can seek: a seek backwards composites again from the first frame.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the size hint is ignored
     * @return the animation reader
     * @throws MediaException.Malformed if the header is invalid or the datastream holds no image
     * @throws MediaException.LimitExceeded if the canvas or the frame count exceeds the limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> {
            var limits = options.limits();
            var summary = GifSummary.scan(source, limits);
            LimitChecks.checkPicture(limits, summary.width(), summary.height());
            LimitChecks.checkAllocation(limits, (long) summary.width() * summary.height() * 4);
            LimitChecks.checkFrames(limits, summary.frames());
            return SeekableAnimationReader.of(() -> {
                var reader = new GifReader(source, limits);
                reader.readHeader();
                return new GifAnimationReader(reader, summary, limits);
            });
        });
    }
}
