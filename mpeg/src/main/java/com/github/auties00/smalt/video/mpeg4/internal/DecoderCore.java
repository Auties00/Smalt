package com.github.auties00.smalt.video.mpeg4.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.codec.ConsumerFailure;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Implements the packet-level behavior shared by the MPEG-4 Visual and H.263 decoders: it feeds
 * packets to a {@link Mpeg4DecoderEngine}, converts the pictures it hands out into cropped
 * {@link YuvPicture}s carried by {@link VideoFrame}s, and translates unexpected runtime failures
 * of the engine into {@link MediaException.Malformed}. An exception thrown by the frame consumer
 * reaches the caller unchanged, and the frames that become due after it in the same call are
 * dropped.
 *
 * <p>Instances are not thread-safe.
 */
public final class DecoderCore {
    /**
     * The engine.
     */
    private final Mpeg4DecoderEngine engine;

    /**
     * The name of the format, for error messages.
     */
    private final String name;

    /**
     * The consumer of the call in progress, or {@code null} between calls and once it failed.
     */
    private ConsumerFailure<VideoFrame> output;

    /**
     * The recycled output picture.
     */
    private YuvPicture picture;

    /**
     * The operation the calls of the decoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Whether the decoder was closed.
     */
    private boolean closed;

    /**
     * Constructs the core of a decoder.
     *
     * @param track the track to decode
     * @param limits the resource bounds checked against every picture size
     * @param threads the thread count of the options: 0 for the shared pool, 1 for the calling
     * thread, or the most threads to use
     * @param h263 whether the track is H.263
     * @throws MediaException.Malformed if the track's configuration is corrupt
     * @throws MediaException.Unsupported if the configuration uses a tool the decoder lacks
     */
    public DecoderCore(VideoTrack track, MediaLimits limits, int threads, boolean h263) {
        this.name = h263 ? "H.263" : "MPEG-4";
        var timeBase = track.timeBase();
        var frameDuration = track.frameRate()
                .map(rate -> Math.max(0, rate.invert().rescale(1, timeBase)))
                .orElse(0L);
        this.engine = new Mpeg4DecoderEngine(limits, h263, threads == 0 ? ParallelBands.THREADS : threads,
                threads > 1, frameDuration, (ticks, resolution) -> rescale(ticks, resolution, timeBase), this::emit);
        var config = track.codecConfig().orElse(null);
        if (config != null && config.length > 0 && !h263) {
            try {
                engine.configure(config);
            } catch (MediaException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new MediaException.Malformed("corrupt MPEG-4 decoder configuration", exception);
            }
        }
    }

    /**
     * Converts a tick count to a time base.
     *
     * @param ticks the time, in ticks of {@code resolution} per second
     * @param resolution the ticks per second
     * @param timeBase the time base
     * @return the converted value, or 0 when it cannot be represented
     */
    private static long rescale(long ticks, int resolution, Rational timeBase) {
        try {
            return new Rational(ticks, resolution).rescale(1, timeBase);
        } catch (ArithmeticException | IllegalArgumentException exception) {
            return 0;
        }
    }

    /**
     * Decodes one packet.
     *
     * @param packet the packet of the track
     * @param output the consumer of the frames that became due
     */
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(packet, "packet");
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                engine.decode(packet.data(), packet.pts() == Packet.NO_TIMESTAMP ? Long.MIN_VALUE : packet.pts(),
                        packet.duration());
            } catch (RuntimeException exception) {
                throw handoff.translate(name + " packet", exception);
            } finally {
                this.output = null;
            }
        }
    }

    /**
     * Hands out the frames still held for reordering.
     *
     * @param output the consumer
     */
    public void flush(Consumer<VideoFrame> output) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(output, "output");
            ensureOpen();
            var handoff = ConsumerFailure.wrap(output);
            this.output = handoff;
            try {
                engine.flush();
            } catch (RuntimeException exception) {
                throw handoff.translate(name + " stream", exception);
            } finally {
                this.output = null;
            }
        }
    }

    /**
     * Marks the decoder closed.
     */
    public void close() {
        if (!closed) {
            closed = true;
            engine.close();
        }
    }

    /**
     * Rejects calls after {@link #close()}.
     *
     * @throws IllegalStateException if the decoder is closed
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("decoder is closed");
        }
    }

    /**
     * Hands a picture of the engine to the consumer, converted to a cropped {@link YuvPicture}
     * unless a job already did.
     *
     * @param frame the picture
     * @param layer its layer
     * @param pts its timestamp, or {@code Long.MIN_VALUE}
     * @param duration its duration, zero or less when unknown
     */
    private void emit(Frame frame, VideoObjectLayer layer, long pts, long duration) {
        var target = frame.picture;
        if (target == null) {
            target = render(frame, layer, picture);
            picture = target;
        }
        var consumer = output;
        if (consumer != null) {
            try {
                consumer.accept(new VideoFrame(target, pts == Long.MIN_VALUE ? Packet.NO_TIMESTAMP : pts,
                        duration > 0 ? duration : Track.UNKNOWN_DURATION, frame.keyframe));
            } catch (RuntimeException exception) {
                output = null;
                throw exception;
            }
        }
    }

    /**
     * Copies the displayed rows of a picture of the engine into a {@link YuvPicture}, allocating
     * one when {@code reuse} does not fit.
     *
     * @param frame the picture
     * @param layer its layer
     * @param reuse a picture to overwrite, or {@code null}
     * @return the picture holding the copy
     */
    static YuvPicture render(Frame frame, VideoObjectLayer layer, YuvPicture reuse) {
        var width = layer.width;
        var height = layer.height;
        var color = layer.color();
        var target = reuse;
        if (target == null || target.width() != width || target.height() != height || !target.color().equals(color)) {
            target = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, color);
        }
        Planes.copy(frame.y, frame.lumaOrigin, frame.lumaStride, target.y(), 0, target.yStride(), width, height);
        var chromaWidth = target.chromaWidth();
        var chromaHeight = target.chromaHeight();
        Planes.copy(frame.u, frame.chromaOrigin, frame.chromaStride, target.u(), 0, target.uvStride(), chromaWidth,
                chromaHeight);
        Planes.copy(frame.v, frame.chromaOrigin, frame.chromaStride, target.v(), 0, target.uvStride(), chromaWidth,
                chromaHeight);
        return target;
    }
}
