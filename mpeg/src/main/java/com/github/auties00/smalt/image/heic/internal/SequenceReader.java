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

import java.util.ArrayDeque;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Plays the image sequence of a HEIC file ({@code msf1}): the color track, and the alpha track
 * that references it with {@code auxl} when there is one, decoded sample by sample.
 *
 * <p>The color track is the first track with an HEVC ({@code hvc1}, {@code hev1}) or AVC
 * ({@code avc1}, {@code avc3}) sample entry and samples that is neither an auxiliary track nor a
 * thumbnail track ({@code auxl} or {@code thmb}
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
 * with {@link HeifPictures#resampleAlpha}. A frame's timestamp is the presentation time of the
 * sample that carried its picture, relative to the first picture shown, and it lasts until that
 * sample's duration ends.
 *
 * <p>The reader seeks: it restarts both tracks at their last sync samples at or before the
 * requested time, decodes the pictures up to the one shown then, which is the picture a decode
 * from the start shows, and pairs it with the alpha picture that starts last at or before it.
 * That is the alpha picture a read from the start pairs it with whenever the n-th alpha picture
 * starts when the n-th color picture does; for an alpha track timed otherwise the two can differ.
 * Instances are not thread-safe.
 */
public final class SequenceReader implements AnimationReader {
    // TODO: pair the color and alpha pictures by their index after a seek too, which needs the sample index of the
    //  packets the demuxer returns, so that an alpha track timed unlike its color track seeks as it reads
    /**
     * Accepts the sample entries of HEVC and AVC tracks.
     */
    private static final Predicate<String> CODED = entry -> entry.equals("hvc1") || entry.equals("hev1")
            || entry.equals("avc1") || entry.equals("avc3");

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
     * The presentation time of the first color picture shown, in track time base units, or
     * {@link Packet#NO_TIMESTAMP} until it is known.
     */
    private long colorBase = Packet.NO_TIMESTAMP;

    /**
     * The presentation time of the first alpha picture, in track time base units, or
     * {@link Packet#NO_TIMESTAMP} until it is known.
     */
    private long alphaBase = Packet.NO_TIMESTAMP;

    /**
     * The alpha track, or {@code null}.
     */
    private final VideoTrack alphaTrack;

    /**
     * The frame a seek decoded, which the next {@link #read()} returns, or {@code null}.
     */
    private AnimationFrame pending;

    /**
     * Whether a seek went past the last frame, so that the next {@link #read()} returns
     * {@code null}.
     */
    private boolean exhausted;

    /**
     * The number of frames returned since the reader was opened or last sought.
     */
    private int frames;

    /**
     * One decoded color picture, waiting for its alpha plane.
     *
     * @param canvas the planes
     * @param color the color description
     * @param pts the presentation time of the sample that carried it, in track time base units
     * @param duration the duration of the sample that carried it, in track time base units
     */
    private record ColorPicture(HeifCanvas canvas, ColorInfo color, long pts, long duration) {
    }

    /**
     * One decoded alpha picture.
     *
     * @param picture the picture whose luma plane is the alpha plane
     * @param limited whether its samples are limited range
     * @param pts the presentation time of the sample that carried it, in track time base units
     */
    private record AlphaPicture(Picture picture, boolean limited, long pts) {
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
         * Receives the decoded pictures and calls {@link #add}.
         */
        private Consumer<CodedFrame> sink;

        /**
         * The configuration record of the track, or {@code null}.
         */
        private byte[] configuration;

        /**
         * Constructs a lane.
         *
         * @param decoder the decoder of the track
         * @param samples supplies the next sample of the track, or {@code null} at its end
         */
        private Lane(ItemDecoder decoder, Supplier<Packet> samples) {
            this.decoder = decoder;
            this.samples = samples;
        }

        /**
         * Opens the decoder of the track.
         *
         * @param sink receives the decoded pictures and calls {@link #add}
         * @param configuration the configuration record of the track, or {@code null}
         */
        private void start(Consumer<CodedFrame> sink, byte[] configuration) {
            this.sink = sink;
            this.configuration = configuration;
            decoder.open(sink);
            decoder.configure(configuration);
        }

        /**
         * Discards the decoding state and the pictures not read, and opens the decoder again for
         * samples that start at a sync sample.
         */
        private void restart() {
            decoder.open(sink);
            decoder.configure(configuration);
            pictures.clear();
            flushed = false;
        }

        /**
         * {@return the next picture without removing it, decoding samples until one is output, or
         * {@code null} at the end of the track}
         */
        private T peek() {
            var next = next();
            if (next != null) {
                pictures.addFirst(next);
            }
            return next;
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
     * @throws MediaException.Malformed if the file has no color track or its tracks are corrupt
     * @throws MediaException.Unsupported if the color tracks of the file are coded with another
     * codec than HEVC or AVC
     * @throws MediaException.LimitExceeded if the canvas or the sample count exceeds the limits
     */
    public SequenceReader(MediaSource source, HeifFile file, MediaLimits limits, boolean highBitDepth) {
        var movie = HeifMovie.parse(source, file, limits);
        var colorInfo = requireColorTrack(movie);
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
            this.alphaTrack = alphaTrack;
            colorDecoder = ItemDecoder.of(colorTrack.codec() == Codec.H264, limits, ParallelBands.THREADS);
            this.color = new Lane<>(colorDecoder, samples::nextColor);
            color.start(this::acceptColor, colorTrack.codecConfig().orElse(null));
            if (alphaTrack != null) {
                alphaDecoder = ItemDecoder.of(alphaTrack.codec() == Codec.H264, limits, ParallelBands.THREADS);
                this.alpha = new Lane<>(alphaDecoder, samples::nextAlpha);
                alpha.start(this::acceptAlpha, alphaTrack.codecConfig().orElse(null));
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
        return HeifSequence.colorTrack(movie, CODED, true);
    }

    /**
     * Returns the color track of a sequence.
     *
     * @param movie the tracks of the file
     * @return the track
     * @throws MediaException.Malformed if the file has no picture or video track with samples
     * that is not an auxiliary track
     * @throws MediaException.Unsupported if such a track exists but is coded with another codec
     * than HEVC or AVC
     */
    static HeifMovie.Track requireColorTrack(HeifMovie movie) {
        var color = colorTrack(movie);
        if (color != null) {
            return color;
        }
        for (var track : movie.tracks()) {
            if ((track.handler().equals("pict") || track.handler().equals("vide")) && track.sampleCount() > 0
                    && !track.references().containsKey("auxl")) {
                throw new MediaException.Unsupported("heic", "image sequences coded as '" + track.sampleEntry() + "'");
            }
        }
        throw new MediaException.Malformed("heic: the sequence has no color track");
    }

    /**
     * {@return the alpha track of a color track, or {@code null} when there is none}
     *
     * @param movie the tracks of the file
     * @param colorId the color track identifier
     */
    static HeifMovie.Track alphaTrack(HeifMovie movie, int colorId) {
        return HeifSequence.alphaTrack(movie, colorId, CODED);
    }

    /**
     * Returns the demuxer's video track with an identifier.
     *
     * @param id the track identifier
     * @return the track
     * @throws MediaException.Malformed if the demuxer has no such HEVC or AVC track
     */
    private VideoTrack videoTrack(int id) {
        for (var track : demuxer.tracks()) {
            if (track.id() == id && track instanceof VideoTrack video
                    && (video.codec() == Codec.HEVC || video.codec() == Codec.H264)) {
                return video;
            }
        }
        throw new MediaException.Malformed("heic: track " + id + " is not an HEVC or AVC video track");
    }

    /**
     * {@return whether a decoded picture lies before the start of the edit list, that is, the
     * demuxer gave its sample a negative presentation time}
     *
     * @param frame the decoded picture
     */
    private static boolean hidden(CodedFrame frame) {
        return frame.pts() != Packet.NO_TIMESTAMP && frame.pts() < 0;
    }

    /**
     * Copies a decoded color picture out of the decoder.
     *
     * @param frame the decoded picture
     */
    private void acceptColor(CodedFrame frame) {
        if (hidden(frame)) {
            return;
        }
        var canvas = frame.canvas(limits, frame.width(), frame.height(), wide, alpha != null);
        canvas.writeColor(frame, 0, 0, canvas.width(), canvas.height(), true);
        color.add(new ColorPicture(canvas, Colors.override(frame.color(), info), frame.pts(), frame.duration()));
    }

    /**
     * Copies a decoded alpha picture out of the decoder.
     *
     * @param frame the decoded picture
     */
    private void acceptAlpha(CodedFrame frame) {
        if (hidden(frame)) {
            return;
        }
        var canvas = frame.canvas(limits, frame.width(), frame.height(), true, false);
        canvas.writeColor(frame, 0, 0, canvas.width(), canvas.height(), true);
        alpha.add(new AlphaPicture(canvas.toPicture(frame.color()), frame.limitedAlpha(alphaNclx), frame.pts()));
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
        if (pending != null) {
            var frame = pending;
            pending = null;
            return frame;
        }
        if (exhausted) {
            return null;
        }
        try {
            wide = highBitDepth;
            var next = color.next();
            if (next == null) {
                return null;
            }
            LimitChecks.checkFrames(limits, frames + 1L);
            var plane = alpha == null ? null : alpha.next();
            if (alpha != null && plane == null) {
                throw new MediaException.Malformed("heic: the alpha track has fewer pictures than the color track");
            }
            var frame = frame(next, plane);
            frames++;
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
     * Builds the frame of a color picture and its alpha picture.
     *
     * @param next the color picture
     * @param plane its alpha picture, or {@code null} without an alpha track
     * @return the frame
     */
    private AnimationFrame frame(ColorPicture next, AlphaPicture plane) {
        if (colorBase == Packet.NO_TIMESTAMP) {
            colorBase = next.pts();
        }
        var picture = combine(next, plane);
        var start = relative(next.pts());
        var end = relative(next.pts() + Math.max(0, next.duration()));
        var straight = premultiplied ? HeifPictures.unpremultiply(picture) : picture;
        var rgba = straight instanceof Yuv16Picture || straight instanceof Rgba16Picture ? ImageOps.toRgba16(straight)
                : ImageOps.toRgba(straight);
        return new AnimationFrame(rgba, start, Math.max(0, end - start));
    }

    /**
     * {@return a presentation time of the color track relative to the first picture shown, in
     * microseconds}
     *
     * @param pts the presentation time, in track time base units
     */
    private long relative(long pts) {
        return colorTrack.timeBase().toMicros(pts - colorBase);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The frame then returned equals the one a decode from the start returns at that time, for
     * an alpha track timed like the color track (see the class description).
     *
     * @throws MediaException.Malformed if a picture decoded while seeking is corrupt, or the alpha
     * track has no picture for the frame
     * @throws MediaException.LimitExceeded if the samples of the two tracks are too far apart
     */
    @Override
    public void seek(long micros) {
        try {
            seek(Math.max(0, micros), false);
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while seeking a HEIC sequence");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed HEIC sequence", exception);
        }
    }

    /**
     * Seeks to a time at or after the first picture.
     *
     * @param target the time relative to the first picture shown, in microseconds, at least zero
     * @param fromStart whether to restart at the first sample rather than at the last sync sample
     * before the target, which a seek does when the alpha track restarts after its color track
     */
    private void seek(long target, boolean fromStart) {
        pending = null;
        exhausted = false;
        wide = highBitDepth;
        if (colorBase == Packet.NO_TIMESTAMP) {
            var first = color.peek();
            if (first == null) {
                exhausted = true;
                return;
            }
            colorBase = first.pts();
        }
        if (alpha != null && alphaBase == Packet.NO_TIMESTAMP) {
            var first = alpha.peek();
            if (first != null) {
                alphaBase = first.pts();
            }
        }
        samples.seek(colorTrack.timeBase().toMicros(colorBase) + (fromStart ? 0 : target));
        color.restart();
        if (alpha != null) {
            alpha.restart();
        }
        frames = 0;
        while (true) {
            var next = color.next();
            if (next == null) {
                exhausted = true;
                return;
            }
            var start = relative(next.pts());
            var shown = relative(next.pts() + Math.max(0, next.duration())) > target;
            AlphaPicture plane = null;
            if (alpha != null) {
                plane = alphaAt(start);
                if (plane == null && shown) {
                    if (!fromStart) {
                        seek(target, true);
                        return;
                    }
                    throw new MediaException.Malformed("heic: the alpha track has no picture at " + start + " us");
                }
            }
            if (shown) {
                pending = frame(next, plane);
                return;
            }
        }
    }

    /**
     * Takes the alpha picture shown with the color picture that starts at a time: the last alpha
     * picture that starts at or before it, dropping the ones before it.
     *
     * @param start the start of the color picture relative to the first color picture shown, in
     * microseconds
     * @return the alpha picture, or {@code null} when the next one starts later
     */
    private AlphaPicture alphaAt(long start) {
        AlphaPicture chosen = null;
        while (true) {
            var next = alpha.peek();
            if (next == null || alphaTrack.timeBase().toMicros(next.pts() - alphaBase) > start) {
                return chosen;
            }
            chosen = alpha.next();
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
        var plane = alpha == null ? null : alpha.next();
        if (alpha != null && plane == null) {
            throw new MediaException.Malformed("heic: the alpha track has fewer pictures than the color track");
        }
        var picture = combine(next, plane);
        return premultiplied ? HeifPictures.unpremultiply(picture) : picture;
    }

    /**
     * Adds an alpha picture to a color picture.
     *
     * @param next the color picture
     * @param plane the alpha picture, or {@code null} without an alpha track
     * @return the planar picture
     */
    private Picture combine(ColorPicture next, AlphaPicture plane) {
        var canvas = next.canvas();
        if (plane != null) {
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
