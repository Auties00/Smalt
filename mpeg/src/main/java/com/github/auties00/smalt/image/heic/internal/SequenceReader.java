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
import com.github.auties00.smalt.internal.heif.HeifGeometry;
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
import java.util.List;
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
 * reordering play in display order. The alpha plane of the picture of the n-th color sample, in
 * decoding order, is the picture of the n-th alpha sample, whatever the timing of the two tracks.
 * Color pictures the stream marks as not output, and color pictures whose presentation time falls
 * before the start of the edit list, are skipped.
 *
 * <p>Each frame is converted to RGBA: 16-bit RGBA when the reader keeps a source depth above 8
 * bits, 8-bit RGBA otherwise, cropped to the clean aperture ({@code clap}) of the color track's sample entry
 * when it has one. Color premultiplied by alpha ({@code prem} track reference) is divided back,
 * and alpha pictures of another size than the color pictures are resampled to it with
 * {@link HeifPictures#resampleAlpha}. A frame's timestamp is the presentation time of the
 * sample that carried its picture, relative to the first picture shown, and it lasts until that
 * sample's duration ends.
 *
 * <p>The reader seeks: it restarts both tracks at their last sync samples at or before the
 * requested time, decodes the pictures up to the one shown then, which is the picture a decode
 * from the start shows, and pairs it with the picture of the alpha sample of the same index, so
 * a seek returns the frames a read from the start does. When the alpha track restarts after that
 * sample, or its picture cannot be decoded from where the alpha track restarts, the reader
 * restarts both tracks at earlier times, down to the start, until it can. Instances are not
 * thread-safe.
 */
public final class SequenceReader implements AnimationReader {
    /**
     * How far, in samples, the index of a picture output may lie from the index of the last sample fed to its
     * decoder: the 16 pictures a decoded picture buffer holds at most (ITU-T H.264 and H.265 Annex A), with
     * margin, plus the samples the frame-threaded engine takes ahead of its output.
     */
    private static final int WINDOW = 32 + 2 * ParallelBands.THREADS;

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
     * The clean aperture of the color track's sample entry, or {@code null}.
     */
    private final HeifProperty.CleanAperture aperture;

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
     * A decoded picture and the sample that carried it.
     */
    private sealed interface Decoded permits ColorPicture, AlphaPicture {
        /**
         * {@return the index of the sample that carried the picture in its track, or
         * {@link Packet#NO_SAMPLE_INDEX} when no sample fed to the decoder has the presentation time it returned}
         */
        long index();
    }

    /**
     * One decoded color picture, waiting for its alpha plane.
     *
     * @param canvas the planes
     * @param color the color description
     * @param pts the presentation time of the sample that carried it, in track time base units
     * @param duration the duration of the sample that carried it, in track time base units
     * @param index the index of the sample that carried it, or {@link Packet#NO_SAMPLE_INDEX}
     */
    private record ColorPicture(HeifCanvas canvas, ColorInfo color, long pts, long duration, long index)
            implements Decoded {
    }

    /**
     * One decoded alpha picture.
     *
     * @param picture the picture whose luma plane is the alpha plane
     * @param limited whether its samples are limited range
     * @param index the index of the sample that carried it, or {@link Packet#NO_SAMPLE_INDEX}
     */
    private record AlphaPicture(Picture picture, boolean limited, long index) implements Decoded {
    }

    /**
     * Decodes the samples of one track into the pictures it holds until they are read.
     *
     * <p>The lane remembers the presentation time and the index of the last {@code 2 * WINDOW}
     * samples it fed, so that a decoded picture, which carries the presentation time of its
     * sample, learns the index of that sample.
     *
     * @param <T> the type of the pictures
     */
    private static final class Lane<T extends Decoded> {
        /**
         * The decoder of the track.
         */
        private final ItemDecoder decoder;

        /**
         * Supplies the next sample of the track, or {@code null} at its end.
         */
        private final Supplier<Packet> samples;

        /**
         * The bounds of the pictures held.
         */
        private final MediaLimits limits;

        /**
         * The pictures decoded and not yet read.
         */
        private final ArrayDeque<T> pictures = new ArrayDeque<>();

        /**
         * The presentation times of the samples fed last, in a ring indexed by the feed count.
         */
        private final long[] fedPts = new long[2 * WINDOW];

        /**
         * The indexes of the samples fed last, in the ring of {@link #fedPts}.
         */
        private final long[] fedIndexes = new long[2 * WINDOW];

        /**
         * Whether the decoder output the picture of each sample fed last, in the ring of {@link #fedPts}.
         */
        private final boolean[] fedOutput = new boolean[2 * WINDOW];

        /**
         * The number of samples fed since the decoder was last opened.
         */
        private int fed;

        /**
         * The index of the last sample fed, or {@link Packet#NO_SAMPLE_INDEX} before the first.
         */
        private long lastFed = Packet.NO_SAMPLE_INDEX;

        /**
         * Whether the decoder was flushed.
         */
        private boolean flushed;

        /**
         * The error of the sample whose reading or feeding failed, raised once the pictures of the samples before it
         * are read, or {@code null}.
         */
        private MediaException.Malformed failure;

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
         * @param limits the bounds; at most {@link MediaLimits#maxFrames()} pictures are held
         */
        private Lane(ItemDecoder decoder, Supplier<Packet> samples, MediaLimits limits) {
            this.decoder = decoder;
            this.samples = samples;
            this.limits = limits;
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
            failure = null;
            fed = 0;
            lastFed = Packet.NO_SAMPLE_INDEX;
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
         * @throws MediaException.LimitExceeded if more than {@link MediaLimits#maxFrames()} pictures
         * would be held
         */
        private void add(T picture) {
            if (pictures.size() >= limits.maxFrames()) {
                throw new MediaException.LimitExceeded("heic: the color and alpha pictures are too far apart");
            }
            pictures.add(picture);
        }

        /**
         * Returns the index of the sample a decoded picture came from, and marks the picture of
         * that sample output.
         *
         * @param pts the presentation time the decoder returned with the picture
         * @return the index of the oldest sample fed with that presentation time whose picture was
         * not output yet, or {@link Packet#NO_SAMPLE_INDEX} when there is none
         */
        private long indexOf(long pts) {
            for (var count = Math.max(0, fed - fedPts.length); count < fed; count++) {
                var slot = count % fedPts.length;
                if (!fedOutput[slot] && fedPts[slot] == pts) {
                    fedOutput[slot] = true;
                    return fedIndexes[slot];
                }
            }
            return Packet.NO_SAMPLE_INDEX;
        }

        /**
         * {@return the lowest index of the samples whose pictures the lane may still return: the
         * pictures it holds and the samples it fed whose picture was not output yet, or
         * {@link Long#MAX_VALUE} when there are none}
         */
        private long lowestPending() {
            var lowest = Long.MAX_VALUE;
            for (var picture : pictures) {
                if (picture.index() >= 0) {
                    lowest = Math.min(lowest, picture.index());
                }
            }
            for (var count = Math.max(0, fed - fedPts.length); count < fed; count++) {
                var slot = count % fedPts.length;
                if (!fedOutput[slot] && fedIndexes[slot] >= 0) {
                    lowest = Math.min(lowest, fedIndexes[slot]);
                }
            }
            return lowest;
        }

        /**
         * Feeds the next sample to the decoder, or flushes the decoder at the end of the track or after a sample
         * that cannot be read or fed.
         *
         * @return {@code false} when the decoder was already flushed at the end of the track
         * @throws MediaException.Malformed if the decoder was flushed after a sample that cannot be read or fed
         */
        private boolean step() {
            if (flushed) {
                if (failure != null) {
                    throw failure;
                }
                return false;
            }
            try {
                var packet = samples.get();
                if (packet == null) {
                    flushed = true;
                    decoder.flush();
                    return true;
                }
                var slot = fed % fedPts.length;
                fedPts[slot] = packet.pts();
                fedIndexes[slot] = packet.sampleIndex();
                fedOutput[slot] = false;
                fed++;
                lastFed = packet.sampleIndex();
                decoder.feed(packet.data(), packet.pts(), packet.duration());
            } catch (MediaException.Malformed exception) {
                // how many samples the frame-threaded engine takes ahead of its output depends on its timing, so
                // the pictures of the samples before a malformed one are output first and the error raised after
                failure = exception;
                flushed = true;
                try {
                    decoder.flush();
                } catch (MediaException.Malformed ignored) {
                    // the first error is the one reported
                }
            }
            return true;
        }

        /**
         * {@return the next picture, decoding samples until one is output, or {@code null} at the
         * end of the track}
         */
        private T next() {
            var more = true;
            while (pictures.isEmpty() && more) {
                more = step();
            }
            return pictures.poll();
        }

        /**
         * Takes the picture of a sample, decoding samples until it is output, and drops the
         * pictures of samples more than {@link #WINDOW} samples before it, which no later picture
         * of the other track asks for, since a picture is output within that many samples of its
         * own.
         *
         * @param index the sample index, or {@link Packet#NO_SAMPLE_INDEX} to take the next picture
         * @return the picture, or {@code null} when the track ends, or its decoder moves more than
         * {@link #WINDOW} samples past it, without outputting it
         */
        private T take(long index) {
            if (index < 0) {
                return next();
            }
            while (true) {
                T found = null;
                var iterator = pictures.iterator();
                while (iterator.hasNext()) {
                    var picture = iterator.next();
                    if (picture.index() == index && found == null) {
                        found = picture;
                        iterator.remove();
                    } else if (picture.index() < index - WINDOW) {
                        iterator.remove();
                    }
                }
                if (found != null) {
                    return found;
                }
                if (lastFed > index + WINDOW || !step()) {
                    return null;
                }
            }
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
        this.aperture = colorInfo.property(HeifProperty.CleanAperture.class);
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
            colorDecoder = ItemDecoder.of(colorTrack.codec() == Codec.H264, limits, ParallelBands.THREADS);
            this.color = new Lane<>(colorDecoder, samples::nextColor, limits);
            color.start(this::acceptColor, colorTrack.codecConfig().orElse(null));
            if (alphaTrack != null) {
                alphaDecoder = ItemDecoder.of(alphaTrack.codec() == Codec.H264, limits, ParallelBands.THREADS);
                this.alpha = new Lane<>(alphaDecoder, samples::nextAlpha, limits);
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
        var index = color.indexOf(frame.pts());
        if (hidden(frame)) {
            return;
        }
        var canvas = frame.canvas(limits, frame.width(), frame.height(), wide, alpha != null);
        canvas.writeColor(frame, 0, 0, canvas.width(), canvas.height(), true);
        color.add(new ColorPicture(canvas, Colors.override(frame.color(), info), frame.pts(), frame.duration(), index));
    }

    /**
     * Copies a decoded alpha picture out of the decoder. Pictures hidden by the alpha track's
     * edit list are kept too, since a color picture pairs with its alpha picture by sample index.
     *
     * @param frame the decoded picture
     */
    private void acceptAlpha(CodedFrame frame) {
        var index = alpha.indexOf(frame.pts());
        var canvas = frame.canvas(limits, frame.width(), frame.height(), true, false);
        canvas.writeColor(frame, 0, 0, canvas.width(), canvas.height(), true);
        alpha.add(new AlphaPicture(canvas.toPicture(frame.color()), frame.limitedAlpha(alphaNclx), index));
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
            var frame = frame(next, alphaOf(next));
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
     * Returns the alpha picture of a color picture: the picture of the alpha sample of the same
     * index.
     *
     * @param next the color picture
     * @return the alpha picture, or {@code null} without an alpha track
     * @throws MediaException.Malformed if the alpha track has no picture for that sample
     */
    private AlphaPicture alphaOf(ColorPicture next) {
        if (alpha == null) {
            return null;
        }
        var plane = alpha.take(next.index());
        if (plane == null) {
            throw new MediaException.Malformed("heic: the alpha track has no picture for color sample " + next.index());
        }
        return plane;
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
     * <p>The frame then returned equals the one a decode from the start returns at that time
     * (see the class description).
     *
     * @throws MediaException.Malformed if a picture decoded while seeking is corrupt, or the alpha
     * track has no picture for the frame
     * @throws MediaException.LimitExceeded if the samples of the two tracks are too far apart
     */
    @Override
    public void seek(long micros) {
        try {
            seekFrom(Math.max(0, micros));
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
     * <p>The color picture shown at the target is found from the last sync samples at or before
     * it. The alpha track must restart at or before the lowest sample of the color pictures shown
     * from then on, which with reordering can come before the sample of the picture shown first.
     * When it restarts later, or its decoder cannot output the alpha picture of that sample from
     * there, both tracks restart at half the time, then a quarter, down to the start of the
     * tracks, and the color track is decoded again up to the same sample.
     *
     * @param target the time relative to the first picture shown, in microseconds, at least zero
     */
    private void seekFrom(long target) {
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
        var base = colorTrack.timeBase().toMicros(colorBase);
        restart(base + target);
        ColorPicture next;
        while (true) {
            next = color.next();
            if (next == null) {
                exhausted = true;
                return;
            }
            if (relative(next.pts() + Math.max(0, next.duration())) > target) {
                break;
            }
        }
        AlphaPicture plane = null;
        if (alpha != null) {
            var index = next.index();
            var needed = Math.min(index, color.lowestPending());
            var time = target;
            var fromStart = false;
            while (true) {
                if (alphaRestartsBy(needed)) {
                    plane = alpha.take(index);
                    if (plane != null) {
                        break;
                    }
                    if (alpha.flushed && alpha.lastFed < index) {
                        fromStart = true;
                    }
                }
                if (fromStart) {
                    throw new MediaException.Malformed("heic: the alpha track has no picture for color sample " + index);
                }
                do {
                    time /= 2;
                    fromStart = time == 0;
                    // the last attempt restarts every track at its first sync sample, which an alpha track timed
                    // faster than its color track may hold before the first color picture shown
                    restart(fromStart ? Math.min(0, base) : base + time);
                } while (!fromStart && !alphaRestartsBy(needed));
                next = colorPicture(index);
            }
        }
        pending = frame(next, plane);
    }

    /**
     * Seeks the demuxer and restarts the decoding lanes.
     *
     * @param micros the presentation time the demuxer seeks to, in microseconds
     */
    private void restart(long micros) {
        samples.seek(micros);
        color.restart();
        if (alpha != null) {
            alpha.restart();
        }
        frames = 0;
    }

    /**
     * {@return whether the alpha samples the lane has not fed yet start at or before a sample, so
     * that its picture can be decoded}
     *
     * @param index the index of the sample
     */
    private boolean alphaRestartsBy(long index) {
        if (alpha.lastFed != Packet.NO_SAMPLE_INDEX) {
            return true;
        }
        var first = samples.peekAlpha();
        return first != null && (index < 0 || first.sampleIndex() <= index);
    }

    /**
     * Decodes color pictures until the one of a sample, dropping the pictures output before it.
     *
     * @param index the index of the sample
     * @return the picture
     * @throws MediaException.Malformed if the color track ends without outputting it
     */
    private ColorPicture colorPicture(long index) {
        while (true) {
            var next = color.next();
            if (next == null) {
                throw new MediaException.Malformed("heic: color sample " + index + " shows no picture");
            }
            if (next.index() == index) {
                return next;
            }
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
        var picture = combine(next, alphaOf(next));
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
        var picture = canvas.toPicture(next.color());
        if (aperture != null) {
            var geometry = HeifGeometry.resolve(List.of(aperture), picture.width(), picture.height());
            if (geometry.crops(picture.width(), picture.height())) {
                picture = HeifPictures.crop(picture, geometry.x(), geometry.y(), geometry.width(), geometry.height());
            }
        }
        return picture;
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
