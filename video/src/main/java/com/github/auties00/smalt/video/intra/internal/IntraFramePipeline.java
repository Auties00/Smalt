package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Decodes the frames of an intra-only codec (ProRes and VC-3) concurrently, one frame per helper
 * thread, and hands them out in decoding order.
 *
 * <p>Every frame of these codecs decodes from its own bytes alone, so up to {@code threads}
 * frames are in flight at once, each on its own {@link FrameDecoder} (which owns the planes of
 * its picture, so a decoder rests until its picture has been handed out). A frame reaches the
 * consumer during the {@link #decode} or {@link #flush} call after it finished, which may follow
 * the call that received its packet; failures of a frame are thrown by that call, after the frame
 * of the call's own packet has been started, so that a failure never loses a later frame.
 *
 * <p>Instances are not thread-safe.
 */
public final class IntraFramePipeline {
    /**
     * Decodes one frame on a single thread.
     */
    @FunctionalInterface
    public interface FrameDecoder {
        /**
         * Decodes one frame.
         *
         * @param data   the array holding the frame
         * @param offset the index of the frame's first byte
         * @param length the number of bytes of the frame
         * @return the picture, whose planes the next call of this decoder overwrites
         */
        Picture decode(byte[] data, int offset, int length);
    }

    /**
     * A frame in flight.
     *
     * @param pending the handle of the job decoding it
     * @param decoder the decoder the job runs on
     * @param packet  the packet the frame came from
     * @param done    whether the job finished, set by the job
     */
    private record Slot(ParallelBands.Pending<Picture> pending, FrameDecoder decoder, Packet packet,
                        AtomicBoolean done) {
    }

    /**
     * The most frames in flight.
     */
    private final int maxInFlight;

    /**
     * Creates the decoder of a frame in flight.
     */
    private final Supplier<FrameDecoder> factory;

    /**
     * The decoders no frame in flight uses.
     */
    private final ConcurrentLinkedQueue<FrameDecoder> idle = new ConcurrentLinkedQueue<>();

    /**
     * The frames in flight, in decoding order.
     */
    private final ArrayDeque<Slot> slots = new ArrayDeque<>();

    /**
     * Creates a pipeline.
     *
     * @param threads the most frames in flight, at least 2
     * @param factory creates a single-threaded decoder
     */
    public IntraFramePipeline(int threads, Supplier<FrameDecoder> factory) {
        this.maxInFlight = Math.max(2, threads);
        this.factory = factory;
    }

    /**
     * Starts the decoding of a packet's frame and hands out the frames that finished.
     *
     * @param packet the packet holding one coded frame
     * @param output receives the finished frames, valid only during the call
     */
    public void decode(Packet packet, Consumer<VideoFrame> output) {
        var decoder = idle.poll();
        if (decoder == null) {
            decoder = factory.get();
        }
        var worker = decoder;
        var data = packet.data();
        var done = new AtomicBoolean();
        var pending = ParallelBands.submit(() -> {
            try {
                return worker.decode(data, 0, data.length);
            } finally {
                done.set(true);
            }
        });
        slots.addLast(new Slot(pending, decoder, packet, done));
        while (slots.size() > maxInFlight) {
            emit(slots.pollFirst(), output);
        }
        drain(output, false);
    }

    /**
     * Hands out every frame in flight.
     *
     * @param output receives the frames, valid only during the call
     */
    public void flush(Consumer<VideoFrame> output) {
        drain(output, true);
    }

    /**
     * Waits for the frames in flight and drops them.
     */
    public void close() {
        while (!slots.isEmpty()) {
            var slot = slots.pollFirst();
            try {
                slot.pending().join();
            } catch (RuntimeException _) {
                // the frame is abandoned
            }
        }
    }

    /**
     * Hands out the frames at the head of the queue that finished, or all of them.
     *
     * @param output receives the frames
     * @param all    whether to wait for the frames still running
     */
    private void drain(Consumer<VideoFrame> output, boolean all) {
        while (!slots.isEmpty() && (all || slots.peekFirst().done().get())) {
            emit(slots.pollFirst(), output);
        }
    }

    /**
     * Waits for a frame and hands it out, then frees its decoder.
     *
     * @param slot   the frame
     * @param output receives the frame
     */
    private void emit(Slot slot, Consumer<VideoFrame> output) {
        try {
            var picture = slot.pending().join();
            var packet = slot.packet();
            output.accept(new VideoFrame(picture, packet.pts(),
                    packet.duration() > 0 ? packet.duration() : Track.UNKNOWN_DURATION, true));
        } finally {
            idle.add(slot.decoder());
        }
    }
}
