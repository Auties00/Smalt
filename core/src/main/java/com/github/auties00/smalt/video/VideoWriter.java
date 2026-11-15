package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.codec.WriterSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Encodes pictures into the video track of a new media file, the writing counterpart of
 * {@link VideoReader}.
 *
 * <p>The writer opens the encoder and the muxer, declares the track and keeps its timing: pictures
 * are passed in presentation order through {@code write} and {@link #finish()} drains the encoder
 * and completes the file. {@link #write(Picture)} places a frame right after the previous one, at
 * the nominal {@link VideoWriterOptions#frameRate()}, so a constant-rate stream needs no
 * timestamps; {@link #write(Picture, long)} and {@link #write(Picture, long, long)} place a frame
 * at a presentation time, for a variable-rate stream or a gap. The timestamps a
 * {@link VideoReader} of the file returns are those of the frames written, rounded to the track's
 * time base ({@link VideoWriterOptions#timeBase()}), shifted together where the container makes
 * the presentation start at its first frame. The encoder and the track are created at the first
 * picture when the options leave the size to it. An instance that is closed without finishing
 * leaves the output incomplete, so it is typically used as:
 * {@snippet :
 * try (var writer = VideoWriter.open(sink, OutputContainer.MP4)) {
 *     for (var frame = reader.read(); frame != null; frame = reader.read()) {
 *         writer.write(frame.picture());
 *     }
 *     writer.finish();
 * }
 * }
 *
 * <p>Instances are not thread-safe.
 */
public final class VideoWriter implements AutoCloseable {
    /**
     * The identifier of the written track.
     */
    private static final int TRACK_ID = 1;

    /**
     * The bits per pixel and frame of the default bitrate.
     */
    private static final double DEFAULT_BITS_PER_PIXEL = 0.1;

    /**
     * The lowest default bitrate, in bits per second.
     */
    private static final long MIN_DEFAULT_BITRATE = 100_000;

    /**
     * The highest default bitrate, in bits per second.
     */
    private static final long MAX_DEFAULT_BITRATE = 50_000_000;

    /**
     * The number of recent frames whose times are kept to restamp the encoder's packets, more than
     * any encoder holds back.
     */
    private static final int TIMING_WINDOW = 4096;

    private final Muxer muxer;
    private final Codec codec;
    private final VideoWriterOptions options;

    /**
     * The duration of one frame at the nominal rate, in seconds.
     */
    private final Rational interval;

    /**
     * The encoder, or {@code null} before the coded size is known.
     */
    private VideoEncoder encoder;

    /**
     * The declared track, or {@code null} before the coded size is known.
     */
    private VideoTrack track;

    /**
     * The time base of the track, or {@code null} before the coded size is known.
     */
    private Rational timeBase;

    /**
     * The interval between the timestamps the encoder receives, in encoder time base units, or 0
     * when the encoder receives the track's own timestamps.
     */
    private long step;

    /**
     * The presentation times of the last {@value #TIMING_WINDOW} frames in track time base units,
     * indexed by frame number modulo the window, or {@code null} when {@link #step} is 0.
     */
    private long[] starts;

    /**
     * The end times matching {@link #starts}.
     */
    private long[] ends;

    /**
     * The start of the current run of back-to-back nominal frames, in track time base units.
     */
    private long anchor;

    /**
     * The number of nominal frames in the current run.
     */
    private long run;

    /**
     * The presentation timestamp of the last frame, in track time base units, or
     * {@link Long#MIN_VALUE} before the first.
     */
    private long lastPts = Long.MIN_VALUE;

    /**
     * The end of the last frame, in track time base units.
     */
    private long end;

    private long framesWritten;

    /**
     * The number of frames passed to the encoder, counting the one being encoded.
     */
    private long framesFed;

    /**
     * Whether a key frame was requested before the encoder existed.
     */
    private boolean keyframePending;

    private boolean finished;

    /**
     * Whether a call failed after it started encoding, which leaves the output unusable.
     */
    private boolean failed;

    /**
     * Receives the encoder's packets and writes them to the track.
     */
    private final Consumer<Packet> packets = this::writePacket;

    /**
     * Constructs a writer, creating the encoder and declaring the track when the size is known.
     *
     * @param muxer the muxer, owned by the writer, to which nothing was written
     * @param codec the codec to encode
     * @param options the settings
     */
    private VideoWriter(Muxer muxer, Codec codec, VideoWriterOptions options) {
        this.muxer = muxer;
        this.codec = codec;
        this.options = options;
        this.interval = options.frameRate().invert();
        if (options.alpha() && !VideoEncoders.encodesAlpha(codec)) {
            throw new MediaException.Unsupported("alpha", codec.displayName() + " cannot code alpha as a second stream");
        }
        WriterSupport.requireTags(muxer, options.tags());
        if (options.width().isPresent()) {
            start(options.width().getAsInt(), options.height().getAsInt());
        }
    }

    /**
     * Creates a writer of a file of a container profile, coded with the profile's default codec at
     * the size of the first picture and 30 frames per second.
     *
     * @param output the sink, written from its position and not closed by the writer
     * @param container the file profile
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the profile carries no video
     * @throws MediaException.Unsupported if no encoder is available for a default codec of the
     * profile
     * @throws MediaException.Io if writing the sink fails
     * @see #open(MediaSink, OutputContainer, VideoWriterOptions)
     */
    public static VideoWriter open(MediaSink output, OutputContainer container) {
        return open(output, container, VideoWriterOptions.defaults());
    }

    /**
     * Creates a writer of a file of a container profile.
     *
     * <p>The codec is {@link VideoWriterOptions#codec()}, or else the first codec of
     * {@link OutputContainer#videoCodecs()} that an encoder is available for. The muxer is the one
     * {@link Containers#openMuxer(OutputContainer, MediaSink)} returns.
     *
     * @param output the sink, written from its position and not closed by the writer
     * @param container the file profile
     * @param options the codec, size, frame rate and coding settings
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the profile cannot carry the codec or carries no video,
     * the orientation is not {@link Orientation#NORMAL} and the profile stores none, the options ask
     * for alpha and the profile stores none, or the codec cannot code the size, rate or bitrate
     * @throws MediaException.Unsupported if no encoder is available for the codec, the options ask for
     * alpha and the codec cannot code it, or the tags are not empty and the profile cannot store tags
     * @throws MediaException.Io if writing the sink fails
     */
    public static VideoWriter open(MediaSink output, OutputContainer container, VideoWriterOptions options) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(options, "options");
        if (options.alpha() && !container.storesAlpha()) {
            throw new IllegalArgumentException(container + " files store no alpha stream");
        }
        var codec = WriterSupport.codec(options.codec(), container, container.videoCodecs(), VideoEncoders::isSupported,
                "video");
        if (options.orientation() != Orientation.NORMAL && !container.storesOrientation()) {
            throw new IllegalArgumentException(container + " files store no display orientation");
        }
        var muxer = Containers.openMuxer(container, output);
        try {
            return new VideoWriter(muxer, codec, options);
        } catch (RuntimeException exception) {
            muxer.close();
            throw exception;
        }
    }

    /**
     * Creates a writer that writes its track to a caller's muxer, for muxer options the profiles of
     * {@link OutputContainer} do not choose.
     *
     * <p>The track is declared with the identifier 1. The writer owns the muxer from the moment
     * this method returns: {@link #finish()} finishes it and {@link #close()} closes it. When this
     * method fails, the muxer stays with the caller.
     *
     * @param muxer the muxer, to which nothing was written yet
     * @param options the codec, size, frame rate and coding settings; the codec is required
     * @return the writer, which the caller finishes and closes
     * @throws IllegalArgumentException if the options name no codec, or the muxer cannot carry the
     * codec, the options ask for alpha and the muxer stores none ({@link Muxer#supportsAlpha()}), or
     * the codec cannot code the size, rate or bitrate
     * @throws IllegalStateException if a packet was already written to the muxer
     * @throws MediaException.Unsupported if no encoder is available for the codec, the options ask for
     * alpha and the codec cannot code it, or the tags are not empty and the muxer cannot store tags
     */
    public static VideoWriter open(Muxer muxer, VideoWriterOptions options) {
        Objects.requireNonNull(muxer, "muxer");
        Objects.requireNonNull(options, "options");
        if (options.alpha() && !muxer.supportsAlpha()) {
            throw new IllegalArgumentException(muxer.getClass().getSimpleName() + " stores no alpha stream");
        }
        return new VideoWriter(muxer, WriterSupport.codec(options.codec(), VideoEncoders::isSupported), options);
    }

    /**
     * Creates the encoder for the coded size and declares the track.
     *
     * @param width the coded width
     * @param height the coded height
     */
    private void start(int width, int height) {
        var rate = options.frameRate();
        var bitrate = options.bitrate().orElseGet(() -> Math.clamp(
                Math.round(DEFAULT_BITS_PER_PIXEL * width * height * rate.toDouble()),
                MIN_DEFAULT_BITRATE, MAX_DEFAULT_BITRATE));
        var builder = VideoEncoderOptions.of(width, height, rate, bitrate).toBuilder()
                .realtime(options.realtime())
                .threads(options.threads())
                .alpha(options.alpha());
        if (!options.periodicKeyframes()) {
            builder.noPeriodicKeyframes();
        } else {
            options.keyframeInterval().ifPresent(builder::keyframeInterval);
        }
        var created = VideoEncoders.create(codec, builder.build());
        try {
            var trackTimeBase = options.timeBase().orElse(created.timeBase());
            var declared = VideoTrack.builder()
                    .id(TRACK_ID)
                    .codec(created.codec())
                    .timeBase(trackTimeBase)
                    .codecConfig(created.codecConfig().orElse(null))
                    .width(width)
                    .height(height)
                    .frameRate(rate)
                    .orientation(options.orientation())
                    .color(options.color().orElse(null))
                    .language(options.language().orElse(null))
                    .alpha(options.alpha())
                    .build();
            muxer.addTrack(declared);
            WriterSupport.writeTags(muxer, options.tags());
            if (keyframePending) {
                created.requestKeyframe();
                keyframePending = false;
            }
            if (!trackTimeBase.reduce().equals(created.timeBase().reduce())) {
                step = Math.max(1, interval.rescale(1, created.timeBase()));
                starts = new long[TIMING_WINDOW];
                ends = new long[TIMING_WINDOW];
            }
            timeBase = trackTimeBase;
            track = declared;
            encoder = created;
        } catch (RuntimeException exception) {
            created.close();
            throw exception;
        }
    }

    /**
     * {@return the codec of the written track}
     */
    public Codec codec() {
        return codec;
    }

    /**
     * {@return the track as declared to the muxer: its codec, coded size, frame rate, time base
     * and the encoder's configuration, or empty while the size waits for the first picture}
     */
    public Optional<VideoTrack> track() {
        return Optional.ofNullable(track);
    }

    /**
     * {@return the number of frames written so far}
     */
    public long framesWritten() {
        return framesWritten;
    }

    /**
     * {@return the end of the last frame written, in microseconds, or 0 before the first}
     */
    public long durationMicros() {
        return track == null ? 0 : timeBase.toMicros(end);
    }

    /**
     * Encodes a frame that starts where the previous one ends, or at zero for the first, and lasts
     * one interval of the nominal frame rate.
     *
     * <p>A run of such frames after a timed one is placed on the nominal rate's grid from that
     * frame, so the timestamps do not drift however long the run. The picture is not retained.
     *
     * @param picture the frame, of the coded size, in any representation
     * @throws IllegalArgumentException if the picture size differs from the coded size
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Io if writing the output fails
     */
    public void write(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        requireOpen();
        prepare(picture);
        var pts = anchor + interval.rescale(run, timeBase);
        var frameEnd = anchor + interval.rescale(run + 1, timeBase);
        encode(picture, pts, frameEnd);
        run++;
    }

    /**
     * Encodes a frame presented at a time, lasting one interval of the nominal frame rate.
     *
     * <p>Later {@link #write(Picture)} calls continue the nominal grid from this frame. The
     * picture is not retained.
     *
     * @param picture the frame, of the coded size, in any representation
     * @param micros the presentation time in microseconds, non-negative and, in the track's time
     * base, after the previous frame's
     * @throws IllegalArgumentException if the picture size differs from the coded size, or the time
     * is negative or not after the previous frame's
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Io if writing the output fails
     */
    public void write(Picture picture, long micros) {
        Objects.requireNonNull(picture, "picture");
        requireOpen();
        nonNegative(micros);
        prepare(picture);
        var pts = Rational.MICROSECONDS.rescale(micros, timeBase);
        requireAfterPrevious(pts, micros);
        anchor = pts;
        run = 0;
        encode(picture, pts, pts + interval.rescale(1, timeBase));
        run = 1;
    }

    /**
     * Encodes a frame presented at a time for a duration.
     *
     * <p>Later {@link #write(Picture)} calls place their frames from the end of this one. The
     * picture is not retained.
     *
     * @param picture the frame, of the coded size, in any representation
     * @param micros the presentation time in microseconds, non-negative and, in the track's time
     * base, after the previous frame's
     * @param durationMicros the display duration in microseconds, positive
     * @throws IllegalArgumentException if the picture size differs from the coded size, the time is
     * negative or not after the previous frame's, or the duration is not positive
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Io if writing the output fails
     */
    public void write(Picture picture, long micros, long durationMicros) {
        Objects.requireNonNull(picture, "picture");
        requireOpen();
        nonNegative(micros);
        if (durationMicros <= 0) {
            throw new IllegalArgumentException("frame duration " + durationMicros + " is not positive");
        }
        prepare(picture);
        var pts = Rational.MICROSECONDS.rescale(micros, timeBase);
        requireAfterPrevious(pts, micros);
        var frameEnd = Math.max(pts + 1,
                Rational.MICROSECONDS.rescale(Math.addExact(micros, durationMicros), timeBase));
        encode(picture, pts, frameEnd);
        anchor = frameEnd;
        run = 0;
    }

    /**
     * Requests that the next frame be coded as a key frame, from which a decoder can start without
     * any earlier packet.
     *
     * <p>Requests made before that frame collapse into one key frame. Before the encoder exists
     * (while the size waits for the first picture) the request is held for the first frame, which
     * is a key frame anyway.
     *
     * @throws IllegalStateException if the writer is finished or an earlier call failed
     * @throws MediaException.Unsupported if the encoder cannot force a key frame
     * @see VideoEncoder#requestKeyframe()
     */
    public void requestKeyframe() {
        requireOpen();
        if (encoder == null) {
            keyframePending = true;
        } else {
            encoder.requestKeyframe();
        }
    }

    /**
     * Rejects a call once the writer is finished or failed.
     *
     * @throws IllegalStateException if the writer is finished or failed
     */
    private void requireOpen() {
        if (finished) {
            throw new IllegalStateException("the writer is finished");
        }
        if (failed) {
            throw new IllegalStateException("an earlier call failed; the output is incomplete");
        }
    }

    /**
     * Rejects a negative presentation time.
     *
     * @param micros the time in microseconds
     * @throws IllegalArgumentException if the time is negative
     */
    private static void nonNegative(long micros) {
        if (micros < 0) {
            throw new IllegalArgumentException("presentation time " + micros + " is negative");
        }
    }

    /**
     * Creates the encoder at the first picture when the size was left to it, and checks the
     * picture's size.
     *
     * @param picture the next picture
     * @throws IllegalArgumentException if the picture size differs from the coded size
     */
    private void prepare(Picture picture) {
        if (encoder == null) {
            start(picture.width(), picture.height());
        }
        if (picture.width() != encoder.width() || picture.height() != encoder.height()) {
            throw new IllegalArgumentException("picture of " + picture.width() + "x" + picture.height()
                    + " does not match the coded size " + encoder.width() + "x" + encoder.height());
        }
    }

    /**
     * Rejects a timestamp at or before the previous frame's.
     *
     * @param pts the timestamp in track time base units
     * @param micros the requested time, for the message
     * @throws IllegalArgumentException if the timestamp is not after the previous frame's
     */
    private void requireAfterPrevious(long pts, long micros) {
        if (pts <= lastPts) {
            throw new IllegalArgumentException("presentation time " + micros + " us is not after the previous frame "
                    + "in the track time base " + timeBase);
        }
    }

    /**
     * Encodes a frame and writes the packets it completes.
     *
     * @param picture the picture to encode
     * @param pts the presentation timestamp in track time base units
     * @param frameEnd the end of the frame in track time base units, after {@code pts}
     */
    private void encode(Picture picture, long pts, long frameEnd) {
        try {
            if (step == 0) {
                encoder.encode(new VideoFrame(picture, pts, frameEnd - pts, false), packets);
            } else {
                var frame = framesFed++;
                starts[(int) (frame % TIMING_WINDOW)] = pts;
                ends[(int) (frame % TIMING_WINDOW)] = frameEnd;
                encoder.encode(new VideoFrame(picture, frame * step, step, false), packets);
            }
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
        lastPts = pts;
        end = frameEnd;
        framesWritten++;
    }

    /**
     * Writes an encoder packet to the track.
     *
     * @param packet a packet the encoder produced
     */
    private void writePacket(Packet packet) {
        if (step != 0) {
            var pts = restamp(packet.pts());
            var duration = packet.duration();
            if (packet.pts() != Packet.NO_TIMESTAMP && Math.floorMod(packet.pts(), step) == 0
                    && packet.pts() / step < framesFed) {
                duration = ends[slot(packet.pts() / step)] - pts;
            } else if (duration > 0) {
                duration = Math.max(1, interval.rescale(1, timeBase) * duration / step);
            }
            packet = packet.withTiming(pts, restamp(packet.dts()), duration);
        }
        muxer.writePacket(packet.withTrackId(TRACK_ID));
    }

    /**
     * Converts an encoder timestamp to the track's time base through the times of the frames.
     *
     * <p>Encoder timestamps of frame {@code k} are {@code k * step}; a timestamp between two
     * frames (a decoding timestamp shifted by the encoder's reordering delay) is placed between
     * their times in proportion, and one before the first frame is extrapolated back from it at the
     * nominal rate.
     *
     * @param value the encoder timestamp, or {@link Packet#NO_TIMESTAMP}
     * @return the track timestamp, or {@link Packet#NO_TIMESTAMP}
     */
    private long restamp(long value) {
        if (value == Packet.NO_TIMESTAMP) {
            return value;
        }
        var frame = Math.floorDiv(value, step);
        var offset = value - frame * step;
        var nominal = interval.rescale(1, timeBase);
        if (frame < 0) {
            return starts[0] + Math.multiplyExact(frame, nominal) + nominal * offset / step;
        }
        var start = starts[slot(frame)];
        if (offset == 0) {
            return start;
        }
        var next = frame + 1 < framesFed ? starts[slot(frame + 1)] : ends[slot(frame)];
        return start + (next - start) * offset / step;
    }

    /**
     * Returns the slot of a frame in the timing window.
     *
     * @param frame the frame number, of one of the last {@value #TIMING_WINDOW} frames
     * @return the slot
     * @throws IllegalStateException if the frame left the window
     */
    private int slot(long frame) {
        if (frame >= framesFed || frame < framesFed - TIMING_WINDOW) {
            throw new IllegalStateException("encoder timestamp of frame " + frame + " is outside the last "
                    + TIMING_WINDOW + " frames");
        }
        return (int) (frame % TIMING_WINDOW);
    }

    /**
     * Drains the encoder and completes the file.
     *
     * <p>A codec configuration the encoder reports differently once drained replaces the declared
     * one where the container can store it.
     *
     * @throws IllegalStateException if the writer is already finished, an earlier call failed, or
     * no picture was written and the options left the size to the first picture
     * @throws MediaException.Io if writing the output fails
     */
    public void finish() {
        requireOpen();
        if (encoder == null) {
            throw new IllegalStateException("no picture was written, so the coded size is unknown");
        }
        try {
            encoder.flush(packets);
            var config = encoder.codecConfig();
            if (config.isPresent() && !Arrays.equals(config.get(), track.codecConfig().orElse(null))) {
                muxer.updateCodecConfig(TRACK_ID, config.get());
            }
            muxer.finish();
            finished = true;
        } catch (RuntimeException exception) {
            failed = true;
            throw exception;
        }
    }

    /**
     * Releases the encoder and the muxer; the sink is not closed, and an unfinished file is left
     * incomplete.
     */
    @Override
    public void close() {
        try {
            if (encoder != null) {
                encoder.close();
            }
        } finally {
            muxer.close();
        }
    }
}
