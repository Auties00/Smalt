package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/**
 * Runs the calls of an intra-only video decoder (ProRes and VC-3): every packet holds one
 * independent frame, decoded on the calling thread or, with more than one thread, several at once
 * through an {@link IntraFramePipeline}.
 *
 * <p>A failure of the frame decoder that is not a {@link MediaException} is reported as
 * {@link MediaException.Malformed}. Instances are not thread-safe.
 */
public final class IntraDecoderCore {
    /**
     * The format name failures report, for example {@code "ProRes"}.
     */
    private final String format;

    /**
     * The decoder of the frames handed out at once.
     */
    private final IntraFramePipeline.FrameDecoder frames;

    /**
     * The pipeline that decodes several frames at once, or {@code null} when frames decode one at
     * a time.
     */
    private final IntraFramePipeline pipeline;

    /**
     * The operation the calls of the decoder form.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * Constructs the core of a decoder.
     *
     * @param format the format name failures report
     * @param threads the thread count of the decoder options: {@code 1} for the calling thread,
     * more for that many frames at once, {@code 0} for the shared pool without a cap
     * @param factory creates a frame decoder for a thread count, as the frame decoders take it
     */
    public IntraDecoderCore(String format, int threads, IntFunction<IntraFramePipeline.FrameDecoder> factory) {
        this.format = format;
        this.frames = factory.apply(threads);
        if (threads <= 1) {
            this.pipeline = null;
        } else {
            this.pipeline = new IntraFramePipeline(threads, () -> {
                var single = factory.apply(1);
                return (data, offset, length) -> decodeChecked(single, data, offset, length);
            });
        }
    }

    /**
     * Decodes a frame, reporting unexpected failures on corrupt data as malformed input.
     *
     * @param decoder the frame decoder
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the picture
     */
    private Picture decodeChecked(IntraFramePipeline.FrameDecoder decoder, byte[] data, int offset, int length) {
        try {
            return decoder.decode(data, offset, length);
        } catch (MediaException exception) {
            throw exception;
        } catch (RuntimeException | StackOverflowError exception) {
            throw new MediaException.Malformed("invalid " + format + " frame", exception);
        }
    }

    /**
     * Decodes the frame of a packet and hands out the frames that finished.
     *
     * @param packet the packet holding one coded frame
     * @param output receives key frames carrying their packets' timestamps, valid only during the call
     */
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(output, "output");
        if (pipeline != null) {
            try (var _ = ParallelBands.operation(operation)) {
                pipeline.decode(packet, output);
            }
            return;
        }
        var data = packet.data();
        var picture = decodeFrame(data, 0, data.length);
        output.accept(new VideoFrame(picture, packet.pts(),
                packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, true));
    }

    /**
     * Decodes one frame held in part of an array, on the calling thread's operation.
     *
     * @param data the array holding the frame
     * @param offset the index of the frame's first byte
     * @param length the number of bytes of the frame
     * @return the picture, whose planes the next call may overwrite
     */
    public Picture decodeFrame(byte[] data, int offset, int length) {
        try (var _ = ParallelBands.operation(operation)) {
            Objects.requireNonNull(data, "data");
            Objects.checkFromIndexSize(offset, length, data.length);
            return decodeChecked(frames, data, offset, length);
        }
    }

    /**
     * Hands out the frames still being decoded; there are none without a pipeline.
     *
     * @param output receives the frames, valid only during the call
     */
    public void flush(Consumer<VideoFrame> output) {
        Objects.requireNonNull(output, "output");
        if (pipeline != null) {
            try (var _ = ParallelBands.operation(operation)) {
                pipeline.flush(output);
            }
        }
    }

    /**
     * Waits for the frames still being decoded and drops them.
     */
    public void close() {
        if (pipeline != null) {
            pipeline.close();
        }
    }
}
