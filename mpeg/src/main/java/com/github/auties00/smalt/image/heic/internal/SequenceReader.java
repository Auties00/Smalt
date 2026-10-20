package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.heif.HeifCanvas;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifMovie;
import com.github.auties00.smalt.internal.heif.HeifPictures;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifSequence;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.hevc.internal.HevcFrame;

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Plays the image sequence of a HEIC file ({@code msf1}): the color track, and the alpha track
 * that references it with {@code auxl} when there is one, decoded sample by sample.
 *
 * <p>The color track is the first track with an {@code hvc1} or {@code hev1} sample entry and
 * samples that is neither an auxiliary track nor a thumbnail track ({@code auxl} or {@code thmb}
 * reference); the alpha track is the first such track that references it with {@code auxl} and
 * whose {@code auxi} box, when present, names an alpha URN. The samples are read through
 * {@link Mp4Demuxer} and decoded in output order, so sequences with inter prediction and
 * reordering play in display order; the n-th alpha picture is the alpha plane of the n-th color
 * picture. Pictures the stream marks as not output, and pictures whose presentation time falls
 * before the start of the edit list, are skipped.
 *
 * <p>Each frame is converted to RGBA: 16-bit RGBA when the reader keeps a source depth above 8
 * bits, 8-bit RGBA otherwise. Color premultiplied by alpha ({@code prem} track reference) is
 * divided back, and alpha pictures of another size than the color pictures are resampled to it
 * with {@link HeifPictures#resampleAlpha}. Timestamps are the sums of the durations of the
 * samples that carried the pictures. Instances are not thread-safe.
 */
public final class SequenceReader implements AnimationReader {
    /**
     * Accepts the sample entries of HEVC tracks.
     */
    private static final Predicate<String> HEVC = entry -> entry.equals("hvc1") || entry.equals("hev1");

    /**
     * The demuxer of the file.
     */
    private final Mp4Demuxer demuxer;

    /**
     * The color track.
     */
    private final VideoTrack colorTrack;

    /**
     * The color track's description from its sample entry.
     */
    private final HeifMovie.Track info;

    /**
     * The {@code nclx} box of the alpha track's sample entry, or {@code null}.
     */
    private final HeifProperty.ColorNclx alphaNclx;

    /**
     * Whether the color is premultiplied by the alpha.
     */
    private final boolean premultiplied;

    /**
     * The bounds of the frames.
     */
    private final MediaLimits limits;

    /**
     * The samples of the color and alpha tracks, in track order.
     */
    private final HeifSequence samples;

    /**
     * The decoding lane of the color track.
     */
    private final Lane<ColorPicture> color;

    /**
     * The decoding lane of the alpha track, or {@code null}.
     */
    private final Lane<AlphaPicture> alpha;

    /**
     * The canvas width.
     */
    private final int width;

    /**
     * The canvas height.
     */
    private final int height;

    /**
     * Whether color pictures being decoded keep a source depth above 8 bits.
     */
    private boolean wide;

    /**
     * Whether the frames {@link #read()} returns keep a source depth above 8 bits.
     */
    private final boolean highBitDepth;

    /**
     * The presentation time of the next frame, in microseconds.
     */
    private long timestamp;

    /**
     * The number of frames returned.
     */
    private int frames;

    /**
     * One decoded color picture, waiting for its alpha plane.
     *
     * @param canvas the planes
     * @param color the color description
     * @param duration the duration of the sample that carried it, in track time base units
     */
    private record ColorPicture(HeifCanvas canvas, ColorInfo color, long duration) {
    }

    /**
     * One decoded alpha picture.
     *
     * @param picture the picture whose luma plane is the alpha plane
     * @param limited whether its samples are limited range
     */
    private record AlphaPicture(Picture picture, boolean limited) {
    }

    /**
     * Decodes the samples of one track into the pictures it holds until they are read.
     *
     * @param <T> the type of the pictures
     */
    private static final class Lane<T> {
        /**
         * The decoder of the track.
         */
        private final ItemDecoder decoder;

        /**
         * Supplies the next sample of the track, or {@code null} at its end.
         */
        private final Supplier<Packet> samples;

        /**
         * The pictures decoded and not yet read.
         */
        private final ArrayDeque<T> pictures = new ArrayDeque<>();

        /**
         * Whether the decoder was flushed.
         */
        private boolean flushed;

        /**
         * Constructs a lane.
         *
         * @param decoder the decoder of the track, open with a sink that calls {@link #add}
         * @param samples supplies the next sample of the track, or {@code null} at its end
         */
        private Lane(ItemDecoder decoder, Supplier<Packet> samples) {
            this.decoder = decoder;
            this.samples = samples;
        }

        /**
         * Keeps a decoded picture until it is read.
         *
         * @param picture the decoded picture
         */
        private void add(T picture) {
            pictures.add(picture);
        }

        /**
         * {@return the next picture, decoding samples until one is output, or {@code null} at the
         * end of the track}
         */
        private T next() {
            while (pictures.isEmpty() && !flushed) {
                var packet = samples.get();
                if (packet == null) {
                    flushed = true;
                    decoder.flush();
                } else {
                    decoder.feed(packet.data(), packet.pts(), packet.duration());
                }
            }
            return pictures.poll();
        }

        /**
         * Discards the decoding state and the pictures not read.
         */
        private void close() {
            decoder.close();
            pictures.clear();
        }
    }

    /**
     * Opens the sequence of a file.
     *
     * @param source the file
     * @param file the parsed file, which has a {@code moov} box
     * @param limits the bounds of the frames and of the samples held back
     * @param highBitDepth whether the frames {@link #read()} returns keep a source depth above 8
     * bits, as {@link Rgba16Picture} pictures
     * @throws MediaException.Malformed if the file has no HEVC color track or its tracks are
     * corrupt
     * @throws MediaException.LimitExceeded if the canvas or the sample count exceeds the limits
     */
    public SequenceReader(MediaSource source, HeifFile file, MediaLimits limits, boolean highBitDepth) {
        var movie = HeifMovie.parse(source, file, limits);
        var colorInfo = colorTrack(movie);
        if (colorInfo == null) {
            throw new MediaException.Malformed("heic: the sequence has no HEVC color track");
        }
        var alphaInfo = alphaTrack(movie, colorInfo.id());
        this.info = colorInfo;
        this.limits = limits;
        this.highBitDepth = highBitDepth;
        this.premultiplied = alphaInfo != null && colorInfo.references("prem", alphaInfo.id());
        this.alphaNclx = alphaInfo == null ? null : alphaInfo.property(HeifProperty.ColorNclx.class);
        this.demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().limits(limits).build());
        ItemDecoder colorDecoder = null;
        ItemDecoder alphaDecoder = null;
        try {
            this.colorTrack = videoTrack(colorInfo.id());
            var alphaTrack = alphaInfo == null ? null : videoTrack(alphaInfo.id());
            this.width = colorInfo.width() > 0 ? colorInfo.width() : colorTrack.width();
            this.height = colorInfo.height() > 0 ? colorInfo.height() : colorTrack.height();
            LimitChecks.checkPicture(limits, width, height);
            this.samples = new HeifSequence(demuxer, colorTrack.id(), alphaTrack == null ? -1 : alphaTrack.id(), limits,
                    "heic");
            colorDecoder = new ItemDecoder(limits, ParallelBands.THREADS);
            this.color = new Lane<>(colorDecoder, samples::nextColor);
            colorDecoder.open(this::acceptColor);
            colorDecoder.configure(colorTrack.codecConfig().orElse(null));
            if (alphaTrack != null) {
                alphaDecoder = new ItemDecoder(limits, ParallelBands.THREADS);
                this.alpha = new Lane<>(alphaDecoder, samples::nextAlpha);
                alphaDecoder.open(this::acceptAlpha);
                alphaDecoder.configure(alphaTrack.codecConfig().orElse(null));
            } else {
                this.alpha = null;
            }
        } catch (RuntimeException exception) {
            if (alphaDecoder != null) {
                alphaDecoder.close();
            }
            if (colorDecoder != null) {
                colorDecoder.close();
            }
            demuxer.close();
            throw exception;
        }
    }

    /**
     * {@return the color track of a sequence, or {@code null} when there is none}
     *
     * @param movie the tracks of the file
     */
    static HeifMovie.Track colorTrack(HeifMovie movie) {
        return HeifSequence.colorTrack(movie, HEVC, true);
    }

    /**
     * {@return the alpha track of a color track, or {@code null} when there is none}
     *
     * @param movie the tracks of the file
     * @param colorId the color track identifier
     */
    static HeifMovie.Track alphaTrack(HeifMovie movie, int colorId) {
        return HeifSequence.alphaTrack(movie, colorId, HEVC);
    }

    /**
     * Returns the demuxer's video track with an identifier.
     *
     * @param id the track identifier
     * @return the track
     * @throws MediaException.Malformed if the demuxer has no such HEVC track
     */
    private VideoTrack videoTrack(int id) {
        for (var track : demuxer.tracks()) {
            if (track.id() == id && track instanceof VideoTrack video && video.codec() == Codec.HEVC) {
                return video;
            }
        }
        throw new MediaException.Malformed("heic: track " + id + " is not an HEVC video track");
    }

    /**
     * {@return whether a decoded picture lies before the start of the edit list, that is, the
     * demuxer gave its sample a negative presentation time}
     *
     * @param frame the decoded picture
     */
    private static boolean hidden(HevcFrame frame) {
        return frame.pts != Packet.NO_TIMESTAMP && frame.pts < 0;
    }

    /**
     * Copies a decoded color picture out of the decoder.
     *
     * @param frame the decoded picture
     */
    private void acceptColor(HevcFrame frame) {
        if (hidden(frame)) {
            return;
        }
        var canvas = HevcTile.canvas(limits, frame, wide, alpha != null);
        canvas.writeColor(HevcTile.of(frame), 0, 0, canvas.width(), canvas.height(), true);
        color.add(new ColorPicture(canvas, Colors.override(Colors.of(frame), info), frame.duration));
    }

    /**
     * Copies a decoded alpha picture out of the decoder.
     *
     * @param frame the decoded picture
     */
    private void acceptAlpha(HevcFrame frame) {
        if (hidden(frame)) {
            return;
        }
        var canvas = HevcTile.canvas(limits, frame, true, false);
        canvas.writeColor(HevcTile.of(frame), 0, 0, canvas.width(), canvas.height(), true);
        alpha.add(new AlphaPicture(canvas.toPicture(Colors.of(frame)), Colors.limitedAlpha(frame, alphaNclx)));
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int loopCount() {
        return info.loopCount();
    }

    @Override
    public int frameCount() {
        return (int) Math.min(Integer.MAX_VALUE, info.sampleCount());
    }

    /**
     * {@return the orientation the color track's matrix declares}
     */
    public Orientation orientation() {
        return colorTrack.orientation();
    }

    @Override
    public AnimationFrame read() {
        try {
            wide = highBitDepth;
            var next = color.next();
            if (next == null) {
                return null;
            }
            LimitChecks.checkFrames(limits, frames + 1L);
            var picture = combine(next);
            frames++;
            var duration = Math.max(0, colorTrack.timeBase().toMicros(Math.max(0, next.duration())));
            var straight = premultiplied ? HeifPictures.unpremultiply(picture) : picture;
            var rgba = straight instanceof Yuv16Picture || straight instanceof Rgba16Picture ? ImageOps.toRgba16(straight)
                    : ImageOps.toRgba(straight);
            var frame = new AnimationFrame(rgba, timestamp, duration);
            timestamp += duration;
            return frame;
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding a HEIC sequence");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC sequence", exception);
        }
    }

    /**
     * Decodes the first frame as a planar picture, for still decoding.
     *
     * @param highBitDepth whether a source of more than 8 bits keeps its depth
     * @return the picture of the first frame
     * @throws MediaException.Malformed if the sequence has no frame
     */
    public Picture readFirstPicture(boolean highBitDepth) {
        wide = highBitDepth;
        var next = color.next();
        if (next == null) {
            throw new MediaException.Malformed("heic: the sequence has no frame");
        }
        var picture = combine(next);
        return premultiplied ? HeifPictures.unpremultiply(picture) : picture;
    }

    /**
     * Adds the next alpha picture to a color picture.
     *
     * @param next the color picture
     * @return the planar picture
     * @throws MediaException.Malformed if the alpha track has fewer pictures
     */
    private Picture combine(ColorPicture next) {
        var canvas = next.canvas();
        if (alpha != null) {
            var plane = alpha.next();
            if (plane == null) {
                throw new MediaException.Malformed("heic: the alpha track has fewer pictures than the color track");
            }
            canvas.writeAlpha(HeifPictures.resampleAlpha(plane.picture(), canvas.width(), canvas.height()),
                    plane.limited());
        }
        return canvas.toPicture(next.color());
    }

    @Override
    public void close() {
        color.close();
        if (alpha != null) {
            alpha.close();
        }
        demuxer.close();
        samples.clear();
    }
}
