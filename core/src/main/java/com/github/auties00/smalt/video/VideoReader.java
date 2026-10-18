package com.github.auties00.smalt.video;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.container.internal.SeekTimes;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Decodes the first video track of any supported media file, frame by frame in presentation
 * order.
 *
 * <p>The reader recognizes the container from the content and decodes the track; frames are
 * pulled with {@link #read()}, which returns {@code null} at the end. Frames the container places
 * before the start of the presentation (a negative presentation timestamp, as an MP4 edit list
 * produces for the frames preceding its first key frame) are decoded, because later frames
 * reference them, but not returned. A packet the decoder rejects as corrupt is skipped, as players
 * do; a run of {@value #MAX_CONSECUTIVE_FAILURES} corrupt packets in a row fails the read. The
 * track's {@link VideoTrack#orientation()} is reported but not applied: frames are returned as
 * coded. {@link #seek(long)} follows the contract shared by every Smalt reader: the next
 * {@link #read()} returns the frame displayed at the requested time.
 *
 * <p>A reader is not thread-safe, and the picture of a returned frame is valid only until the
 * next call to {@link #read()}, {@link #seek(long)} or {@link #close()}.
 *
 * @implNote This implementation copies every frame the decoder hands out into a picture owned by the reader, recycled
 * once the caller moves past it: a codec decoder may reuse the buffer of a frame as soon as its consumer returns, even
 * for the next frame of the same packet, so a frame must leave the decoder's buffers before the reader can hold it.
 */
public final class VideoReader implements AutoCloseable {
    /**
     * The number of consecutive corrupt packets after which reading fails.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 16;

    /**
     * The demuxer reading the container.
     */
    private final Demuxer demuxer;

    /**
     * The track being decoded.
     */
    private final VideoTrack track;

    /**
     * The options the decoder was created with.
     */
    private final VideoDecoderOptions options;

    /**
     * The frames decoded but not returned yet, in presentation order.
     */
    private final ArrayDeque<VideoFrame> ready;

    /**
     * The pictures of returned frames, free for reuse.
     */
    private final ArrayDeque<Picture> spare;

    /**
     * The decoder of {@link #track}, replaced by every seek.
     */
    private VideoDecoder decoder;

    /**
     * The picture of the frame returned last, recycled by the next call.
     */
    private Picture lent;

    /**
     * The presentation timestamp before which frames are dropped after a seek, in track time base
     * units, or {@link Long#MIN_VALUE} when none is pending.
     */
    private long dropBefore;

    /**
     * The last frame without a duration that starts at or before the seek target, held until the
     * next frame shows whether it is the one displayed at the target, or {@code null}.
     */
    private VideoFrame candidate;

    /**
     * The number of consecutive packets the decoder rejected.
     */
    private int failures;

    /**
     * Whether the input ended and the decoder was flushed.
     */
    private boolean ended;

    /**
     * Constructs a reader over an open demuxer.
     *
     * @param demuxer the demuxer, owned by the reader
     * @param track the track to decode
     * @param decoder the decoder of {@code track}, owned by the reader
     * @param options the options the decoder was created with
     */
    private VideoReader(Demuxer demuxer, VideoTrack track, VideoDecoder decoder, VideoDecoderOptions options) {
        this.demuxer = demuxer;
        this.track = track;
        this.decoder = decoder;
        this.options = options;
        this.ready = new ArrayDeque<>();
        this.spare = new ArrayDeque<>();
        this.dropBefore = Long.MIN_VALUE;
    }

    /**
     * Opens the first video track of a media file under the default limits.
     *
     * @param source the input; read by the reader and not closed by it
     * @return the reader, which the caller closes
     * @throws MediaException.Unsupported if the container is not recognized, has no video track (reported as
     * {@code "no video"}), or its codec is not decodable
     * @throws MediaException.Malformed if the container or the codec configuration is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public static VideoReader open(MediaSource source) {
        return open(source, VideoDecoderOptions.defaults());
    }

    /**
     * Opens the first video track of a media file with decoder options.
     *
     * <p>The demuxer enforces {@link VideoDecoderOptions#limits()} too, so a single set of options
     * bounds the whole read.
     *
     * @param source the input; read by the reader and not closed by it
     * @param options the limits the demuxer and decoder enforce and the decoder's settings
     * @return the reader, which the caller closes
     * @throws MediaException.Unsupported if the container is not recognized, has no video track (reported as
     * {@code "no video"}), or its codec is not decodable
     * @throws MediaException.Malformed if the container or the codec configuration is corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public static VideoReader open(MediaSource source, VideoDecoderOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        var demuxer = Containers.openDemuxer(source, DemuxerOptions.builder().limits(options.limits()).build());
        try {
            var track = demuxer.tracks().stream()
                    .filter(VideoTrack.class::isInstance)
                    .map(VideoTrack.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new MediaException.Unsupported("no video", "the input has no video track"));
            return new VideoReader(demuxer, track, VideoDecoders.create(track, options), options);
        } catch (RuntimeException exception) {
            demuxer.close();
            throw exception;
        }
    }

    /**
     * Returns the recognized container format.
     *
     * @return the format
     */
    public ContainerFormat format() {
        return demuxer.format();
    }

    /**
     * Returns the track being decoded.
     *
     * @return the track
     */
    public VideoTrack track() {
        return track;
    }

    /**
     * Returns the next frame in presentation order.
     *
     * @return the frame, with timestamps in the track's time base and a picture valid until the next call, or
     * {@code null} at the end of the track
     * @throws MediaException.Malformed if {@value #MAX_CONSECUTIVE_FAILURES} consecutive packets are corrupt
     * @throws MediaException.Io if reading the source fails
     */
    public VideoFrame read() {
        if (lent != null) {
            spare.add(lent);
            lent = null;
        }
        while (ready.isEmpty() && !ended) {
            pump();
        }
        var frame = ready.poll();
        if (frame != null) {
            lent = frame.picture();
        }
        return frame;
    }

    /**
     * Returns whether {@link #seek(long)} is supported, which depends on the container.
     *
     * @return {@code true} when the reader can seek
     */
    public boolean isSeekable() {
        return demuxer.isSeekable();
    }

    /**
     * Positions the reader so that the next {@link #read()} returns the frame displayed at a time.
     *
     * <p>The demuxer seeks to the key frame at or before {@code micros}, the codec decoder is
     * recreated, and the frames that end before {@code micros} are decoded but not returned. A
     * frame whose decoder reports no duration ends where the next frame starts, so the last such
     * frame starting at or before {@code micros} is returned. A time before zero seeks to the
     * start, and a time at or past the end makes the next {@link #read()} return {@code null}.
     *
     * @param micros the presentation time in microseconds
     * @throws MediaException.Unsupported if the container cannot seek ({@link #isSeekable()} is {@code false})
     * @throws MediaException.Malformed if the index needed to seek is corrupt or the track's configuration is
     * @throws MediaException.Io if reading the source fails
     */
    public void seek(long micros) {
        demuxer.seek(micros);
        var replacement = VideoDecoders.create(track, options);
        decoder.close();
        decoder = replacement;
        if (lent != null) {
            spare.add(lent);
            lent = null;
        }
        for (var frame : ready) {
            spare.add(frame.picture());
        }
        ready.clear();
        if (candidate != null) {
            spare.add(candidate.picture());
            candidate = null;
        }
        failures = 0;
        ended = false;
        dropBefore = SeekTimes.floor(Math.max(0, micros), track.timeBase());
    }

    /**
     * Reads the next packet of the track and decodes it, or flushes the decoder at the end of the
     * input.
     */
    private void pump() {
        var packet = demuxer.read();
        if (packet == null) {
            decoder.flush(this::accept);
            if (candidate != null && dropBefore != Long.MIN_VALUE && candidate.pts() + frameInterval() > dropBefore) {
                ready.add(candidate);
            } else if (candidate != null) {
                spare.add(candidate.picture());
            }
            candidate = null;
            dropBefore = Long.MIN_VALUE;
            ended = true;
            return;
        }
        if (packet.trackId() != track.id()) {
            return;
        }
        try {
            decoder.decode(packet, this::accept);
            failures = 0;
        } catch (MediaException.Malformed exception) {
            if (++failures >= MAX_CONSECUTIVE_FAILURES) {
                throw new MediaException.Malformed(failures + " consecutive corrupt video packets", exception);
            }
        }
    }

    /**
     * Queues a copy of a decoded frame unless it precedes the presentation or the seek target.
     *
     * @param frame the decoded frame, valid only during the call
     */
    private void accept(VideoFrame frame) {
        var pts = frame.pts();
        if (pts != Packet.NO_TIMESTAMP) {
            if (pts < 0) {
                return;
            }
            if (dropBefore != Long.MIN_VALUE) {
                if (frame.duration() > 0 && pts + frame.duration() <= dropBefore) {
                    return;
                }
                if (frame.duration() <= 0 && pts <= dropBefore) {
                    var recycle = candidate != null ? candidate.picture() : spare.poll();
                    var held = PictureCopy.copy(frame.picture(), recycle);
                    candidate = new VideoFrame(held, pts, frame.duration(), frame.keyframe());
                    return;
                }
                if (candidate != null) {
                    if (pts > dropBefore) {
                        ready.add(candidate);
                    } else {
                        spare.add(candidate.picture());
                    }
                    candidate = null;
                }
                dropBefore = Long.MIN_VALUE;
            }
        }
        var copy = PictureCopy.copy(frame.picture(), spare.poll());
        ready.add(new VideoFrame(copy, pts, frame.duration(), frame.keyframe()));
    }

    /**
     * Returns the nominal interval between frames, which a frame without a duration is taken to
     * last when it is the last frame of the track.
     *
     * @return the interval in track time base units, at least one
     */
    private long frameInterval() {
        var rate = track.frameRate();
        if (rate.isEmpty() || rate.get().numerator() <= 0) {
            return 1;
        }
        try {
            return Math.max(1, new Rational(rate.get().denominator(), rate.get().numerator()).rescale(1, track.timeBase()));
        } catch (ArithmeticException exception) {
            return 1;
        }
    }

    /**
     * Closes the decoder and the demuxer; the source is not closed.
     */
    @Override
    public void close() {
        try {
            decoder.close();
        } finally {
            demuxer.close();
        }
    }
}
