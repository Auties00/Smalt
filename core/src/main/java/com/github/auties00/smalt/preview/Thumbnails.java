package com.github.auties00.smalt.preview;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.internal.resize.SizePlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
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
 * scale); an animated image gives the frame displayed at the requested time. A video picture is
 * stretched horizontally by the track's sample aspect ratio (the one the container declares, else
 * the one the codec configuration signals), so that anamorphic video gets its display shape: a
 * picture {@code width} samples wide becomes {@code round(width * sampleAspectRatio)} pixels wide.
 * The picture is then turned upright according to the track's or the image's orientation, scaled
 * down to fit the box keeping its displayed aspect (never up), and converted to 8-bit RGBA.
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
     * The first probe and the precision of the search for the end of a video that declares no
     * duration, in microseconds.
     */
    private static final long TAIL_PROBE_MICROS = 1_000_000;

    /**
     * The latest time the search for the end of a video probes, in microseconds (about 12 days).
     *
     * @implNote This implementation keeps the probe small enough that converting it to a time base
     * of a nanosecond still fits a {@code long}; a longer video is read from this time on.
     */
    private static final long TAIL_PROBE_LIMIT_MICROS = 1L << 40;

    /**
     * How far back each seek for the last frame of a video goes, in microseconds, when a time past
     * its end shows no frame.
     */
    private static final long LAST_FRAME_STEP_MICROS = 1_000_000;

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
     * @throws MediaException.Unsupported if the input is neither a video nor an image, its codec is
     * not decodable, or the time lies past the last frame of a video that declares a longer
     * duration and is read from a source that reads its input once
     * @throws MediaException.LimitExceeded if the input exceeds the default limits
     * @throws MediaException.Io if reading the source fails
     */
    public static Thumbnail at(MediaSource source, long micros) {
        return at(source, micros, ThumbnailOptions.defaults());
    }

    /**
     * Takes the picture displayed at a time.
     *
     * <p>A video read from a source that reads its input once
     * ({@link MediaSource#of(java.io.InputStream, long)}) and that declares no duration is decoded
     * from its start up to the time instead of seeking to it, since such a source could not seek back
     * to the last frame when the time lies past the end.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param micros the presentation time in microseconds; a negative time means the start
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, its codec is
     * not decodable, or the time lies past the last frame of a video that declares a longer
     * duration and is read from a source that reads its input once
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
            return video(reader, micros, reader.durationMicros(), source, options);
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
     * not decodable, or it declares no duration and either has no timestamps to measure one from or
     * is a source that reads its input once
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
     * not decodable, or it declares no duration and has no timestamps to measure one from
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
     * not decodable, or it declares no duration and has no timestamps to measure one from
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
     * those times. A video that cannot seek is read once, front to back. The duration is the one the
     * video declares ({@link VideoReader#durationMicros()}); the duration of a video that declares
     * none, such as a live recording, is measured from its packets as the end of its last frame,
     * which needs a second reading of the input, so a source that reads its input once
     * ({@link MediaSource#of(java.io.InputStream, long)}) cannot be measured.
     *
     * @param source the video or image, read from offset zero; not closed
     * @param count the number of pictures, positive
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnails in time order
     * @throws IllegalArgumentException if {@code count} is not positive
     * @throws MediaException.Malformed if the input is corrupt or holds no frame
     * @throws MediaException.Unsupported if the input is neither a video nor an image, its codec
     * is not decodable, or it declares no duration and either has no timestamps to measure one from
     * or is a source that reads its input once, the video cannot seek while
     * {@link ThumbnailOptions#bestFrame()} examines frames up to or past the time of the next picture,
     * or a picture's time lies past the last frame of a video that declares a longer duration and is
     * read from a source that reads its input once
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
            var duration = reader.durationMicros();
            if (duration <= 0) {
                duration = measureDuration(source, reader.track().id(), options);
            }
            if (duration <= 0) {
                throw new MediaException.Unsupported("unknown duration", "the video declares no duration and has no"
                        + " timestamps to measure one from");
            }
            checkForwardReading(reader.isSeekable(), count, duration, options);
            for (var index = 0; index < count; index++) {
                result.add(video(reader, (long) ((index + 0.5) * duration / count), duration, source, options));
            }
        }
        return result;
    }

    /**
     * Measures the duration of a video track that declares none, as the end of its last frame.
     *
     * <p>A demuxer that can seek first finds, by doubling probes and then bisection to within
     * {@value #TAIL_PROBE_MICROS} microseconds, a late time from which a seek still returns
     * packets, so that only the end of the input is read; it reads every packet when that end
     * holds no packet of the track, and so does any other demuxer. A packet without a duration
     * lasts one frame of the track's declared frame rate, else the distance between the last two
     * presentation times.
     *
     * @param source the video
     * @param trackId the identifier of the video track
     * @param options the limits applied while demuxing
     * @return the end of the last frame of the track in microseconds, or {@code -1} when no packet
     * of the track has a timestamp
     * @throws MediaException.Unsupported if the source reads its input once, so that measuring it
     * would release the bytes the reader of the video still needs
     */
    static long measureDuration(MediaSource source, int trackId, ThumbnailOptions options) {
        if (ForwardOnlySource.isForwardOnly(source)) {
            throw new MediaException.Unsupported("unknown duration", "the video declares no duration, and a source"
                    + " read once cannot be read a second time to measure it");
        }
        try (var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build())) {
            Track track = null;
            for (var candidate : demuxer.tracks()) {
                if (candidate.id() == trackId) {
                    track = candidate;
                }
            }
            if (track == null) {
                return -1;
            }
            if (demuxer.isSeekable()) {
                var good = 0L;
                var bad = -1L;
                for (var probe = TAIL_PROBE_MICROS; bad < 0 && probe <= TAIL_PROBE_LIMIT_MICROS; probe *= 2) {
                    if (readsAfterSeek(demuxer, probe)) {
                        good = probe;
                    } else {
                        bad = probe;
                    }
                }
                while (bad > 0 && bad - good > TAIL_PROBE_MICROS) {
                    var middle = good + (bad - good) / 2;
                    if (readsAfterSeek(demuxer, middle)) {
                        good = middle;
                    } else {
                        bad = middle;
                    }
                }
                demuxer.seek(good);
                var end = lastFrameEnd(demuxer, track);
                if (end >= 0 || good == 0) {
                    return end;
                }
                // the end of the input holds packets of other tracks only, such as audio outlasting the video
                demuxer.seek(0);
            }
            return lastFrameEnd(demuxer, track);
        }
    }

    /**
     * Reads a demuxer to its end and returns the end of the last frame of a track.
     *
     * @param demuxer the demuxer, positioned where the reading starts
     * @param track the video track
     * @return the end of the last frame of the track read, in microseconds, or {@code -1} when no
     * packet of the track read has a timestamp
     */
    private static long lastFrameEnd(Demuxer demuxer, Track track) {
        var timeBase = track.timeBase();
        var timed = Long.MIN_VALUE;
        var last = Long.MIN_VALUE;
        var previous = Long.MIN_VALUE;
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            var pts = packet.pts();
            if (packet.trackId() != track.id() || pts == Packet.NO_TIMESTAMP) {
                continue;
            }
            if (packet.duration() > 0) {
                timed = Math.max(timed, pts + packet.duration());
            }
            if (pts > last) {
                previous = last;
                last = pts;
            } else if (pts < last && pts > previous) {
                previous = pts;
            }
        }
        if (last == Long.MIN_VALUE) {
            return -1;
        }
        var frame = 0L;
        if (track instanceof VideoTrack video && video.frameRate().isPresent()
                && video.frameRate().get().numerator() > 0) {
            frame = video.frameRate().get().invert().rescale(1, timeBase);
        } else if (previous != Long.MIN_VALUE) {
            frame = last - previous;
        }
        return timeBase.toMicros(Math.max(timed, last + frame));
    }

    /**
     * {@return whether a demuxer returns a packet after a seek to a time}
     *
     * @param demuxer the demuxer, which can seek
     * @param micros the time in microseconds
     */
    private static boolean readsAfterSeek(Demuxer demuxer, long micros) {
        demuxer.seek(micros);
        return demuxer.read() != null;
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
     * @param duration the duration of the video in microseconds, or {@code -1} when it declares none
     * and it was not measured
     * @param source the video, whose duration is measured when it declares none and the time is
     * past its last frame
     * @param options the box, filter, orientation and frame choice
     * @return the thumbnail
     */
    private static Thumbnail video(VideoReader reader, long micros, long duration, MediaSource source,
                                   ThumbnailOptions options) {
        var track = reader.track();
        var sampleAspect = VideoConfigs.sampleAspect(track);
        var timeBase = track.timeBase();
        var target = Math.max(0, micros);
        if (duration > 0) {
            target = Math.min(target, duration - 1);
        }
        // a source read once cannot seek back to the last frame of a video whose end is unknown, so
        // such a video is read forward from its start, keeping the last frame passed over
        var forwardOnly = ForwardOnlySource.isForwardOnly(source);
        var seeks = reader.isSeekable() && !(forwardOnly && duration < 0);
        if (seeks) {
            reader.seek(target);
        }
        var skipped = seeks ? null : new SkippedFrame();
        Thumbnail best = null;
        var bestScore = -1.0;
        var start = Long.MIN_VALUE;
        for (var count = 0; count < (options.bestFrame() ? BEST_FRAME_COUNT : 1); count++) {
            var frame = nextFrame(reader, target, skipped);
            if (frame == null) {
                break;
            }
            var timestamp = frame.pts() == Packet.NO_TIMESTAMP ? target : timeBase.toMicros(frame.pts());
            if (start == Long.MIN_VALUE) {
                start = timestamp;
            } else if (timestamp - start > BEST_FRAME_WINDOW) {
                break;
            }
            var thumbnail = new Thumbnail(finish(frame.picture(), track.orientation(), sampleAspect, options),
                    timestamp);
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
            if (skipped != null && skipped.picture != null) {
                return new Thumbnail(finish(skipped.picture, track.orientation(), sampleAspect, options),
                        skipped.timestampMicros);
            }
            if (target > 0 && seeks) {
                if (duration < 0) {
                    var known = measureDuration(source, track.id(), options);
                    if (known > 0 && target >= known) {
                        return video(reader, known - 1, known, source, options);
                    }
                }
                return lastFrame(reader, target, forwardOnly, options);
            }
            throw new MediaException.Malformed("the video holds no frame");
        }
        return best;
    }

    /**
     * Takes the last frame of a video that shows no frame at or after a time: steps back from that
     * time {@value #LAST_FRAME_STEP_MICROS} microseconds at a time until a seek finds frames, then
     * reads them to the end.
     *
     * @param reader the reader of the video, which can seek
     * @param micros the time in microseconds, past the last frame
     * @param forwardOnly whether the source reads its input once, so that it cannot seek back
     * @param options the box, filter and orientation
     * @return the thumbnail of the last frame
     * @throws MediaException.Malformed if no seek finds a frame
     * @throws MediaException.Unsupported if the source reads its input once
     */
    private static Thumbnail lastFrame(VideoReader reader, long micros, boolean forwardOnly, ThumbnailOptions options) {
        if (forwardOnly) {
            throw new MediaException.Unsupported("seek", "the video ends before its declared duration, and a source"
                    + " read once cannot seek back to its last frame");
        }
        var track = reader.track();
        var timeBase = track.timeBase();
        Picture kept = null;
        var timestamp = 0L;
        for (var from = micros; kept == null && from > 0; ) {
            from = Math.max(0, from - LAST_FRAME_STEP_MICROS);
            reader.seek(from);
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                kept = PictureCopy.copy(frame.picture(), kept);
                timestamp = frame.pts() == Packet.NO_TIMESTAMP ? from : timeBase.toMicros(frame.pts());
            }
        }
        if (kept == null) {
            throw new MediaException.Malformed("the video holds no frame");
        }
        return new Thumbnail(finish(kept, track.orientation(), VideoConfigs.sampleAspect(track), options), timestamp);
    }

    /**
     * Holds the last frame a reader that did not seek skipped, so that a time past the end of the
     * video still gives its last frame.
     */
    private static final class SkippedFrame {
        /**
         * A copy of the picture of the last skipped frame, or {@code null} before the first.
         */
        private Picture picture;

        /**
         * The presentation time of the last skipped frame in microseconds.
         */
        private long timestampMicros;
    }

    /**
     * Reads the next frame, skipping the frames that end before the target when the reader did
     * not seek to it.
     *
     * @param reader the reader of the video
     * @param target the target time in microseconds
     * @param skipped receives a copy of each skipped frame, or {@code null} when the reader seeked to
     * the target
     * @return the frame, or {@code null} at the end
     */
    private static VideoFrame nextFrame(VideoReader reader, long target, SkippedFrame skipped) {
        var timeBase = reader.track().timeBase();
        while (true) {
            var frame = reader.read();
            if (frame == null || skipped == null || frame.pts() == Packet.NO_TIMESTAMP) {
                return frame;
            }
            var end = timeBase.toMicros(frame.pts() + Math.max(1, frame.duration()));
            if (end > target) {
                return frame;
            }
            skipped.picture = PictureCopy.copy(frame.picture(), skipped.picture);
            skipped.timestampMicros = timeBase.toMicros(frame.pts());
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
                return new Thumbnail(finish(frame.picture(), info.orientation(), null, options),
                        frame.timestampMicros());
            }
        }
        if (options.maxWidth() > 0 && options.maxHeight() > 0) {
            var swaps = options.upright() && info.orientation().swapsDimensions();
            decoderOptions = decoderOptions.toBuilder().minSize(swaps ? options.maxHeight() : options.maxWidth(),
                    swaps ? options.maxWidth() : options.maxHeight()).build();
        }
        var decoded = Images.decode(source, decoderOptions);
        return new Thumbnail(finish(decoded.picture(), decoded.orientation(), null, options), 0);
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
     * Converts a decoded picture to 8-bit RGBA, stretches it by its sample aspect ratio, scales it
     * down to fit the box and turns it upright, returning a new picture the caller owns.
     *
     * @param picture the decoded picture, possibly a reader's reused buffer
     * @param orientation the orientation of the picture
     * @param sampleAspect the sample aspect ratio in coded orientation, or {@code null} for square samples
     * @param options the box, filter and orientation policy
     * @return the thumbnail picture
     * @throws MediaException.LimitExceeded if the thumbnail of a stretched picture exceeds the options' limits
     */
    static RgbaPicture finish(Picture picture, Orientation orientation, Rational sampleAspect, ThumbnailOptions options) {
        var applied = options.upright() ? orientation : Orientation.NORMAL;
        var source = picture;
        picture = switch (picture) {
            case Yuv16Picture deep when YcgcoR.applies(deep.color().matrix()) -> ImageOps.toRgba(deep);
            case Yuv16Picture deep -> ImageOps.toRgba(ImageOps.toEightBit(deep));
            case Rgba16Picture deep -> ImageOps.toEightBit(deep);
            case YuvPicture yuv -> ImageOps.toRgba(yuv);
            case RgbaPicture rgba -> rgba;
            case ChannelPicture channels -> ImageOps.toRgba(channels);
        };
        var swaps = applied.swapsDimensions();
        var stretchedWidth = stretchedWidth(picture.width(), sampleAspect);
        var displayWidth = swaps ? picture.height() : stretchedWidth;
        var displayHeight = swaps ? stretchedWidth : picture.height();
        var plan = SizePlan.of(displayWidth, displayHeight, options.maxWidth(), options.maxHeight(), ScaleMode.FIT, false,
                0, 0, false);
        var width = swaps ? plan.outputHeight() : plan.outputWidth();
        var height = swaps ? plan.outputWidth() : plan.outputHeight();
        if (stretchedWidth != picture.width()) {
            LimitChecks.checkPicture(options.limits(), width, height);
        }
        if (width != picture.width() || height != picture.height()) {
            picture = ImageOps.resize(picture, width, height, options.filter());
        }
        picture = ImageOps.orient(picture, applied);
        var result = ImageOps.toRgba(picture);
        return result == source ? (RgbaPicture) PictureCopy.copy(result, null) : result;
    }

    /**
     * {@return the width of a picture stretched by a sample aspect ratio, rounded to nearest and at
     * least 1}
     *
     * @param width the coded width
     * @param sampleAspect the sample aspect ratio, or {@code null} for square samples
     */
    static int stretchedWidth(int width, Rational sampleAspect) {
        if (sampleAspect == null || sampleAspect.numerator() == sampleAspect.denominator()) {
            return width;
        }
        var stretched = Math.round(width * (double) sampleAspect.numerator() / sampleAspect.denominator());
        return (int) Math.clamp(stretched, 1, Integer.MAX_VALUE);
    }
}
