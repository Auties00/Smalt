package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.transcode.ScaleMode;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.VideoReader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Takes still pictures of videos and images: a frame at a time, or several frames spread over the
 * whole video, upright and scaled to fit a box.
 *
 * <p>For a video, the reader seeks to the key frame at or before the requested time and decodes
 * forward to the frame displayed at that time (the last frame whose presentation time is at or
 * before it); a time past the end gives the last frame. For an image, the picture is decoded at
 * the smallest size the decoder can produce that still fills the box (JPEG decodes at a reduced
 * scale); an animated image gives the frame displayed at the requested time. The picture is then
 * turned upright according to the track's or the image's orientation, scaled down to fit the box
 * keeping its aspect (never up), and converted to 8-bit RGBA.
 *
 * <p>Decoders are found through the registries, so the artifacts of the codecs must be present at
 * run time. The class is stateless and thread-safe.
 *
 * @implNote This implementation converts a Y'CbCr picture to RGBA at full size before scaling it,
 * so that chroma is interpolated at full resolution; scaling the subsampled planes first is
 * cheaper but blurs color edges into the neighboring pixels.
 */
public final class Thumbnails {
    /**
     * The longest span of frames examined for a representative frame, in microseconds.
     */
    private static final long BEST_FRAME_WINDOW = 2_000_000;

    /**
     * The largest number of frames examined for a representative frame.
     */
    private static final int BEST_FRAME_COUNT = 60;

    /**
     * The mean luma, out of 255, below which a frame is nearly black.
     */
    private static final double DARK_MEAN = 24;

    /**
     * The luma standard deviation, out of 255, below which a frame is nearly flat.
     */
    private static final double FLAT_DEVIATION = 12;

    /**
     * Prevents instantiation.
     */
    private Thumbnails() {
        throw new AssertionError();
    }

    /**
     * Takes the picture displayed at a time, with {@link ThumbnailOptions#defaults()}.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param micros the presentation time in microseconds; a negative time means the start
     * @return the thumbnail
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, or its
     * codec is not decodable
     * @throws MediaException.LimitExceeded if the input exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Thumbnail at(MediaSource source, long micros) {
        return at(source, micros, ThumbnailOptions.defaults());
    }

    /**
     * Takes the picture displayed at a time.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param micros the presentation time in microseconds; a negative time means the start
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, or its
     * codec is not decodable
     * @throws MediaException.LimitExceeded if the input exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static Thumbnail at(MediaSource source, long micros, ThumbnailOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        if (Images.detect(source).isPresent()) {
            return image(source, micros, options);
        }
        try (var reader = VideoReader.open(source, VideoDecoderOptions.builder().limits(options.limits()).build())) {
            return video(reader, micros, options);
        }
    }

    /**
     * Takes the picture displayed at a time in a file on disk, with
     * {@link ThumbnailOptions#defaults()}; the file is opened and closed by this method.
     *
     * @param file the video or image
     * @param micros the presentation time in microseconds; a negative time means the start
     * @return the thumbnail
     * @throws MediaException.Malformed if the file is corrupt or holds no frame
     * @throws MediaException.Unsupported if the file is neither a video nor an image, or its codec
     * is not decodable
     * @throws MediaException.LimitExceeded if the file exceeds the default limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static Thumbnail at(Path file, long micros) {
        return at(file, micros, ThumbnailOptions.defaults());
    }

    /**
     * Takes the picture displayed at a time in a file on disk; the file is opened and closed by
     * this method.
     *
     * @param file the video or image
     * @param micros the presentation time in microseconds; a negative time means the start
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     * @throws MediaException.Malformed if the file is corrupt or holds no frame
     * @throws MediaException.Unsupported if the file is neither a video nor an image, or its codec
     * is not decodable
     * @throws MediaException.LimitExceeded if the file exceeds the limits of {@code options}
     * @throws MediaException.Io if the file cannot be read
     */
    public static Thumbnail at(Path file, long micros, ThumbnailOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return at(source, micros, options);
        }
    }

    /**
     * Takes pictures spread evenly over a video, with {@link ThumbnailOptions#defaults()}, as
     * {@link #evenly(MediaSource, int, ThumbnailOptions)} describes.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param count the number of pictures, positive
     * @return the thumbnails in time order
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, its codec is
     * not decodable, or its duration is unknown
     * @throws MediaException.LimitExceeded if the input exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static List<Thumbnail> evenly(MediaSource source, int count) {
        return evenly(source, count, ThumbnailOptions.defaults());
    }

    /**
     * Takes pictures spread evenly over a video file on disk, with
     * {@link ThumbnailOptions#defaults()}; the file is opened and closed by this method.
     *
     * @param file the video or image
     * @param count the number of pictures, positive
     * @return the thumbnails in time order
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws MediaException.Malformed if the file is corrupt or holds no frame
     * @throws MediaException.Unsupported if the file is neither a video nor an image, its codec is
     * not decodable, or its duration is unknown
     * @throws MediaException.LimitExceeded if the file exceeds the default limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static List<Thumbnail> evenly(Path file, int count) {
        return evenly(file, count, ThumbnailOptions.defaults());
    }

    /**
     * Takes pictures spread evenly over a video file on disk, as
     * {@link #evenly(MediaSource, int, ThumbnailOptions)} describes; the file is opened and closed
     * by this method.
     *
     * @param file the video or image
     * @param count the number of pictures, positive
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnails in time order
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws MediaException.Malformed if the file is corrupt or holds no frame
     * @throws MediaException.Unsupported if the file is neither a video nor an image, its codec is
     * not decodable, or its duration is unknown
     * @throws MediaException.LimitExceeded if the file exceeds the limits of {@code options}
     * @throws MediaException.Io if the file cannot be read
     */
    public static List<Thumbnail> evenly(Path file, int count, ThumbnailOptions options) {
        Objects.requireNonNull(options, "options");
        if (count <= 0) {
            throw new IllegalArgumentException("thumbnail count must be positive: " + count);
        }
        try (var source = MediaSource.of(file)) {
            return evenly(source, count, options);
        }
    }

    /**
     * Takes pictures spread evenly over a video: picture {@code i} of {@code count} shows the
     * frame displayed at {@code (i + 0.5) * duration / count}, as a contact sheet or a seek bar
     * preview would.
     *
     * <p>An image gives {@code count} copies of its picture, or of the frames of its animation at
     * those times. A video that cannot seek is read once, front to back.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param count the number of pictures, positive
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnails in time order
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, its codec
     * is not decodable, or its duration is unknown, or the video cannot seek while
     * {@link ThumbnailOptions#bestFrame()} examines frames up to or past the time of the next picture
     * @throws MediaException.LimitExceeded if the input exceeds the limits of {@code options}
     * @throws MediaException.Io if reading the source fails
     */
    public static List<Thumbnail> evenly(MediaSource source, int count, ThumbnailOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        if (count <= 0) {
            throw new IllegalArgumentException("thumbnail count must be positive: " + count);
        }
        var result = new ArrayList<Thumbnail>(count);
        if (Images.detect(source).isPresent()) {
            var duration = animationDuration(source, options);
            for (var index = 0; index < count; index++) {
                result.add(image(source, duration <= 0 ? 0 : (long) ((index + 0.5) * duration / count), options));
            }
            return result;
        }
        try (var reader = VideoReader.open(source, VideoDecoderOptions.builder().limits(options.limits()).build())) {
            var track = reader.track();
            if (track.duration() <= 0) {
                throw new MediaException.Unsupported("unknown duration", "the video does not declare its duration");
            }
            var duration = track.timeBase().toMicros(track.duration());
            checkForwardReading(reader.isSeekable(), count, duration, options);
            for (var index = 0; index < count; index++) {
                result.add(video(reader, (long) ((index + 0.5) * duration / count), options));
            }
        }
        return result;
    }

    /**
     * Checks that pictures spread over a video can be taken in one forward pass when the video
     * cannot seek: the frames examined for one picture must end before the time of the next.
     *
     * @param seekable whether the video can seek
     * @param count the number of pictures
     * @param durationMicros the duration of the video in microseconds
     * @param options the frame choice
     * @throws MediaException.Unsupported if the video cannot seek and
     * {@link ThumbnailOptions#bestFrame()} examines frames up to or past the time of the next picture
     */
    static void checkForwardReading(boolean seekable, int count, long durationMicros, ThumbnailOptions options) {
        if (count > 1 && !seekable && options.bestFrame() && durationMicros / count <= BEST_FRAME_WINDOW) {
            throw new MediaException.Unsupported("seek", "this video cannot seek back to the time of the next"
                    + " picture after choosing a representative frame");
        }
    }

    /**
     * Takes the picture of a video displayed at a time.
     *
     * @param reader the reader of the video
     * @param micros the time in microseconds
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     */
    private static Thumbnail video(VideoReader reader, long micros, ThumbnailOptions options) {
        var track = reader.track();
        var timeBase = track.timeBase();
        var target = Math.max(0, micros);
        var duration = track.duration() > 0 ? timeBase.toMicros(track.duration()) : -1;
        if (duration > 0) {
            target = Math.min(target, duration - 1);
        }
        if (reader.isSeekable()) {
            reader.seek(target);
        }
        Thumbnail best = null;
        var bestScore = -1.0;
        var start = Long.MIN_VALUE;
        for (var count = 0; count < (options.bestFrame() ? BEST_FRAME_COUNT : 1); count++) {
            var frame = nextFrame(reader, target);
            if (frame == null) {
                break;
            }
            var timestamp = frame.pts() == Packet.NO_TIMESTAMP ? target : timeBase.toMicros(frame.pts());
            if (start == Long.MIN_VALUE) {
                start = timestamp;
            } else if (timestamp - start > BEST_FRAME_WINDOW) {
                break;
            }
            var thumbnail = new Thumbnail(finish(frame.picture(), track.orientation(), options), timestamp);
            if (!options.bestFrame()) {
                return thumbnail;
            }
            var score = score(thumbnail.picture());
            if (score > bestScore) {
                best = thumbnail;
                bestScore = score;
            }
            if (score >= FLAT_DEVIATION * 1000) {
                break;
            }
        }
        if (best == null) {
            if (target > 0 && reader.isSeekable()) {
                return video(reader, target - Math.max(1, Math.min(target, 1_000_000)), options);
            }
            throw new MediaException.Malformed("the video holds no frame");
        }
        return best;
    }

    /**
     * Reads the next frame, skipping the frames that end before the target when the reader
     * cannot seek.
     *
     * @param reader the reader of the video
     * @param target the target time in microseconds
     * @return the frame, or {@code null} at the end
     */
    private static VideoFrame nextFrame(VideoReader reader, long target) {
        var timeBase = reader.track().timeBase();
        while (true) {
            var frame = reader.read();
            if (frame == null || reader.isSeekable() || frame.pts() == Packet.NO_TIMESTAMP) {
                return frame;
            }
            var end = timeBase.toMicros(frame.pts() + Math.max(1, frame.duration()));
            if (end > target) {
                return frame;
            }
        }
    }

    /**
     * Scores a picture for the representative frame choice: frames that are neither nearly black
     * nor nearly flat score above every frame that is, and among each group more luma contrast
     * scores higher.
     *
     * @param picture the candidate frame
     * @return the score, higher for a better frame
     */
    private static double score(RgbaPicture picture) {
        var pixels = picture.pixels();
        var stride = picture.stride();
        var sum = 0.0;
        var squares = 0.0;
        var count = 0;
        var step = Math.max(1, Math.min(picture.width(), picture.height()) / 64);
        for (var y = 0; y < picture.height(); y += step) {
            for (var x = 0; x < picture.width(); x += step) {
                var offset = y * stride + x * 4;
                var luma = 0.299 * (pixels[offset] & 0xFF) + 0.587 * (pixels[offset + 1] & 0xFF)
                        + 0.114 * (pixels[offset + 2] & 0xFF);
                sum += luma;
                squares += luma * luma;
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        var mean = sum / count;
        var deviation = Math.sqrt(Math.max(0, squares / count - mean * mean));
        var usable = mean >= DARK_MEAN && deviation >= FLAT_DEVIATION;
        return usable ? FLAT_DEVIATION * 1000 + deviation : deviation;
    }

    /**
     * Takes the picture of an image, or the frame of an animation displayed at a time.
     *
     * @param source the image
     * @param micros the time in microseconds
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     */
    private static Thumbnail image(MediaSource source, long micros, ThumbnailOptions options) {
        var decoderOptions = ImageDecoderOptions.builder().limits(options.limits()).build();
        var info = Images.probe(source);
        if (info.animated()) {
            try (var reader = Images.openAnimation(source, decoderOptions)) {
                if (micros > 0 && reader.isSeekable()) {
                    reader.seek(micros);
                }
                var frame = reader.read();
                if (frame == null) {
                    reader.seek(0);
                    frame = reader.read();
                }
                if (frame == null) {
                    throw new MediaException.Malformed("the animation holds no frame");
                }
                return new Thumbnail(finish(frame.picture(), info.orientation(), options), frame.timestampMicros());
            }
        }
        if (options.maxWidth() > 0 && options.maxHeight() > 0) {
            var swaps = options.upright() && info.orientation().swapsDimensions();
            decoderOptions = decoderOptions.toBuilder().minSize(swaps ? options.maxHeight() : options.maxWidth(),
                    swaps ? options.maxWidth() : options.maxHeight()).build();
        }
        var decoded = Images.decode(source, decoderOptions);
        return new Thumbnail(finish(decoded.picture(), decoded.orientation(), options), 0);
    }

    /**
     * {@return the duration of an animated image in microseconds, or 0 for a still image}
     *
     * @param source the image
     * @param options the limits applied while reading the animation
     */
    private static long animationDuration(MediaSource source, ThumbnailOptions options) {
        if (!Images.probe(source).animated()) {
            return 0;
        }
        var total = 0L;
        try (var reader = Images.openAnimation(source, ImageDecoderOptions.builder().limits(options.limits())
                .build())) {
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                total = frame.timestampMicros() + frame.durationMicros();
            }
        }
        return total;
    }

    /**
     * Converts a decoded picture to 8-bit RGBA, scales it down to fit the box and turns it upright,
     * returning a new picture the caller owns.
     *
     * @param picture the decoded picture, possibly a reader's reused buffer
     * @param orientation the orientation of the picture
     * @param options the box, filter and orientation policy
     * @return the thumbnail picture
     */
    static RgbaPicture finish(Picture picture, Orientation orientation, ThumbnailOptions options) {
        // TODO: stretch anamorphic video by its sample aspect ratio once VideoTrack carries it
        var applied = options.upright() ? orientation : Orientation.NORMAL;
        var source = picture;
        picture = switch (picture) {
            case Yuv16Picture deep -> ImageOps.toRgba(ImageOps.toEightBit(deep));
            case Rgba16Picture deep -> ImageOps.toEightBit(deep);
            case YuvPicture yuv -> ImageOps.toRgba(yuv);
            case RgbaPicture rgba -> rgba;
        };
        var swaps = applied.swapsDimensions();
        var displayWidth = swaps ? picture.height() : picture.width();
        var displayHeight = swaps ? picture.width() : picture.height();
        var plan = SizePlan.of(displayWidth, displayHeight, options.maxWidth(), options.maxHeight(), ScaleMode.FIT, false,
                0, 0, false);
        if (!plan.isIdentity(displayWidth, displayHeight)) {
            var width = plan.outputWidth();
            var height = plan.outputHeight();
            picture = ImageOps.resize(picture, swaps ? height : width, swaps ? width : height, options.filter());
        }
        picture = ImageOps.orient(picture, applied);
        var result = ImageOps.toRgba(picture);
        return result == source ? (RgbaPicture) PictureCopy.copy(result, null) : result;
    }
}
