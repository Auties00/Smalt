package com.github.auties00.smalt.image.ico;

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
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.region.ImageRegions;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.Optional;

/**
 * Decodes Windows animated cursors (ANI) to packed RGBA8.
 *
 * <p>An animated cursor is a RIFF file of form type {@code ACON}: an {@code anih} chunk declares the
 * frame and step counts and the default display time, optional {@code rate} and {@code seq } chunks
 * give each step its display time and frame, an optional {@code INFO} list names the title and the
 * author, and a {@code fram} list holds the frames, each a complete icon or cursor file that
 * {@link IcoDecoder} decodes. {@link #header(MediaSource)} returns these declarations as an
 * {@link AniHeader}.
 *
 * <p>The animation plays the steps in order and forever. Each step is drawn alone onto a canvas as
 * large as the largest frame, at its top-left corner, on a transparent background; from each frame
 * the largest entry is shown, or, when the options carry a size hint, the smallest entry at least
 * that large, as {@link IcoDecoder#decode(MediaSource, ImageDecoderOptions)} chooses it. A step's
 * {@link AnimationFrame#durationMicros()} is its display time in jiffies (sixtieths of a second)
 * converted to microseconds, with the timeline rounded down so that the rounding does not
 * accumulate.
 *
 * <p>Damage is tolerated where the intent is clear: the RIFF size is clipped to the input, unknown
 * chunks are skipped, the first chunk of each kind wins, the {@code anih} frame count yields to the
 * number of frames present, a zero step count means one step per frame (or per {@code seq } entry),
 * a {@code seq } chunk shorter than the step count shortens it, without a {@code seq } chunk the
 * steps never outnumber the frames, and a step past the end of a short {@code rate} chunk is shown
 * for the default display time. Frames stored as raw data rather than icon files (an {@code anih}
 * without its icon flag, whose frames do not start with an icon directory) raise
 * {@link MediaException.Unsupported}. The frame and step counts are checked against
 * {@link MediaLimits#maxFrames()}.
 *
 * <p>This class is thread-safe.
 */
public final class AniDecoder {
    /**
     * The input named in failure messages.
     */
    static final String WHAT = "animated cursor";

    /**
     * Prevents instantiation.
     */
    private AniDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads the declarations of an animated cursor under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the header and steps
     * @throws MediaException.Malformed if the RIFF header, the {@code anih} chunk or the frames are
     * missing, or a step names no frame
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the frame or step count exceeds the default frame limit
     * @throws MediaException.Io if reading the source fails
     * @see #header(MediaSource, ImageDecoderOptions)
     */
    public static AniHeader header(MediaSource source) {
        return header(source, ImageDecoderOptions.defaults());
    }

    /**
     * Reads the declarations of an animated cursor: the frame count, the steps with their frames and
     * display times, the default display time, the title and the author.
     *
     * @param source the input, read from offset zero
     * @param options the limits; the other options are ignored
     * @return the header and steps
     * @throws MediaException.Malformed if the RIFF header, the {@code anih} chunk or the frames are
     * missing, or a step names no frame
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the frame or step count exceeds the frame limit
     * @throws MediaException.Io if reading the source fails
     */
    public static AniHeader header(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> AniFile.read(source, options.limits()).header());
    }

    /**
     * Returns the icon or cursor file of a stored frame, which {@link IcoDecoder} reads with every entry
     * and cursor hotspot.
     *
     * @param source the input, read from offset zero
     * @param index the frame index, from zero to {@link AniHeader#frameCount()} minus one
     * @param options the limits; the other options are ignored
     * @return a copy of the frame's bytes
     * @throws IndexOutOfBoundsException if {@code index} is out of range
     * @throws MediaException.Malformed if the RIFF header, the {@code anih} chunk or the frames are
     * missing, or a step names no frame
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the frame or step count exceeds the frame limit, or the
     * frame exceeds the allocation limit
     * @throws MediaException.Io if reading the source fails
     */
    public static byte[] frame(MediaSource source, int index, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var file = Guards.malformed(WHAT, () -> AniFile.read(source, options.limits()));
        Objects.checkIndex(index, file.frameCount());
        return file.frameBytes(index);
    }

    /**
     * Summarizes an animated cursor under the {@link ImageDecoderOptions#defaults()}.
     *
     * <p>{@link ImageInfo#hasAlpha()} is {@code true}: any frame may carry transparency, which only
     * decoding confirms. {@link ImageInfo#frameCount()} is the number of steps, and the size is the
     * canvas size.
     *
     * @param source the input, read from offset zero
     * @return the summary
     * @throws MediaException.Malformed if the file or a frame's directory is invalid
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the canvas, or the frame or step count, exceeds the
     * default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static ImageInfo probe(MediaSource source) {
        Objects.requireNonNull(source, "source");
        return Guards.malformed(WHAT, () -> {
            var file = AniFile.read(source, MediaLimits.DEFAULT);
            var size = AniAnimationReader.canvasSize(file, ImageDecoderOptions.defaults());
            var steps = file.header().steps().size();
            return new ImageInfo(ImageFormat.ANI, size[0], size[1], true, steps > 1, steps, Orientation.NORMAL);
        });
    }

    /**
     * Decodes the first step of an animated cursor under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero
     * @return the decoded step
     * @throws MediaException.Malformed if the file or the frame is invalid
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files, or the
     * frame uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the counts exceed the default limits, or
     * decoding runs out of memory
     * @throws MediaException.Io if reading the source fails
     * @see #decode(MediaSource, ImageDecoderOptions)
     */
    public static DecodedImage decode(MediaSource source) {
        return decode(source, ImageDecoderOptions.defaults());
    }

    /**
     * Decodes the first step of an animated cursor, drawn onto the canvas as
     * {@link #openAnimation(MediaSource, ImageDecoderOptions)} draws it.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint choosing each frame's entry, the region (cut out of the whole
     * step) and the bit depth choice (for PNG entries)
     * @return the decoded step, a canvas-sized {@link RgbaPicture}
     * @throws IllegalArgumentException if the region does not lie inside the image
     * @throws MediaException.Malformed if the file or the frame is invalid
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files, or the
     * frame uses a feature its decoder lacks
     * @throws MediaException.LimitExceeded if the canvas or the counts exceed the limits, or decoding
     * runs out of memory
     * @throws MediaException.Io if reading the source fails
     */
    public static DecodedImage decode(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return ImageRegions.decode(options, () -> probe(source), whole -> Guards.malformed(WHAT, () -> {
            var reader = new AniAnimationReader(AniFile.read(source, whole.limits()), whole);
            var picture = (RgbaPicture) reader.read().picture();
            return new DecodedImage(picture, ImageFormat.ANI, Orientation.NORMAL, picture.width(), picture.height(),
                    Optional.empty(), Optional.empty(), Optional.empty());
        }));
    }

    /**
     * Opens an animated cursor as an animation under the {@link ImageDecoderOptions#defaults()}.
     *
     * @param source the input, read from offset zero; it must stay open until the reader is closed
     * @return the animation reader
     * @throws MediaException.Malformed if the file or a frame's directory is invalid
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the canvas, or the frame or step count, exceeds the
     * default limits
     * @throws MediaException.Io if reading the source fails
     * @see #openAnimation(MediaSource, ImageDecoderOptions)
     */
    public static AnimationReader openAnimation(MediaSource source) {
        return openAnimation(source, ImageDecoderOptions.defaults());
    }

    /**
     * Opens the steps of an animated cursor as an animation.
     *
     * <p>{@link AnimationReader#loopCount()} is {@code 0} (forever) and
     * {@link AnimationReader#frameCount()} the number of steps. The reader keeps only the frame on
     * the canvas and decodes a frame whenever a step shows a frame other than that one,
     * so memory does not grow with the number of frames, and it seeks directly to any step. The
     * source must stay open until the reader is closed.
     *
     * @param source the input, read from offset zero
     * @param options the limits, the size hint choosing each frame's entry, and the bit depth choice
     * (for PNG entries)
     * @return the animation reader
     * @throws MediaException.Malformed if the file or a frame's directory is invalid
     * @throws MediaException.Unsupported if the frames are raw data rather than icon files
     * @throws MediaException.LimitExceeded if the canvas, or the frame or step count, exceeds the
     * limits
     * @throws MediaException.Io if reading the source fails
     */
    public static AnimationReader openAnimation(MediaSource source, ImageDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(WHAT, () -> new AniAnimationReader(AniFile.read(source, options.limits()), options));
    }
}
