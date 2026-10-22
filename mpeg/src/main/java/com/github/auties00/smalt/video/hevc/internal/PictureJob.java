package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.video.h264.internal.H264BitReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Decodes one picture whose slice segments have all been received, on the calling thread or on a
 * thread of the module's shared pool while the engine keeps parsing later pictures.
 *
 * <p>The engine parses the slice segment headers, manages the decoded picture buffer and appends
 * the slice segments of the picture with {@link #add(Slice)}; once the picture has received all of
 * them it runs the job, or starts it with {@link #start()}, and the job never waits for the
 * engine. Reference pictures are read only once their rows are published as final, so jobs of
 * consecutive pictures overlap safely (frame-level parallelism). A job only ever waits for jobs of
 * the same engine started before it, and the engine waits for its jobs in start order, so every
 * wait ends: see {@link ParallelBands#submit}.
 */
final class PictureJob implements Runnable {
    /**
     * One slice segment of the picture, owning a copy of its payload.
     *
     * @param header     the slice segment header
     * @param refList    the reference picture lists of the slice, or {@code null} for an intra
     *                   slice
     * @param sliceIndex the index of the slice in the picture's slice tables
     * @param data       the unescaped NAL unit payload, followed by {@link H264BitReader#PADDING}
     *                   zero bytes
     * @param size       the payload size
     * @param substreams the start of each wavefront substream in {@code data}, when the rows of the
     *                   slice segment may be decoded concurrently, or {@code null}
     */
    record Slice(HevcSliceHeader header, HevcFrame[][] refList, int sliceIndex, byte[] data, int size,
                 int[] substreams) {
    }

    /**
     * The picture being decoded.
     */
    final HevcFrame frame;

    /**
     * The coding information of the picture: one entry, or one per color plane with separate color
     * planes, indexed by {@code colour_plane_id}.
     */
    final PictureState[] states;

    /**
     * The sequence number of the job: its position in the decoding order of the engine.
     */
    final long sequence;

    /**
     * The slice segments of the picture, in decoding order.
     */
    final List<Slice> slices = new ArrayList<>();

    /**
     * The type of the decoded picture hash to check, as {@link HevcSei#hashType}, or -1.
     */
    int hashType = -1;

    /**
     * The decoded picture hash of each color component, valid when {@link #hashType} is not -1.
     */
    byte[][] hash;

    /**
     * The idle picture decoders shared by the jobs of one engine.
     */
    private final Queue<PictureDecoder> decoders;

    /**
     * Whether the work of the picture may run on several threads: the color planes of a picture
     * coded with separate color planes are then decoded concurrently.
     */
    final boolean parallel;

    /**
     * The handle of the started job, or {@code null} when it was not started on the pool.
     */
    private ParallelBands.Pending<Void> pending;

    /**
     * The first failure of the picture, read after {@link HevcFrame#awaitDone()}.
     */
    private volatile MediaException error;

    /**
     * Constructs a job.
     *
     * @param frame    the picture to decode into
     * @param states   the coding information of the picture, one per color plane decoded apart
     * @param sequence the sequence number of the job
     * @param decoders the idle picture decoders
     * @param parallel whether the work of the picture may run on several threads
     */
    PictureJob(HevcFrame frame, PictureState[] states, long sequence, Queue<PictureDecoder> decoders,
               boolean parallel) {
        this.frame = frame;
        this.states = states;
        this.sequence = sequence;
        this.decoders = decoders;
        this.parallel = parallel;
    }

    /**
     * Takes an idle picture decoder, or creates one when none is idle.
     *
     * @return the decoder, to be handed back with {@link #release(PictureDecoder)}
     */
    PictureDecoder borrow() {
        var decoder = decoders.poll();
        return decoder != null ? decoder : new PictureDecoder();
    }

    /**
     * Hands back a picture decoder taken with {@link #borrow()}.
     *
     * @param decoder the decoder, which no longer decodes anything
     */
    void release(PictureDecoder decoder) {
        decoders.add(decoder);
    }

    /**
     * Appends a slice segment of the picture; only valid before the job runs.
     *
     * @param slice the slice segment
     */
    void add(Slice slice) {
        slices.add(slice);
    }

    /**
     * Starts decoding the picture on the shared pool.
     */
    void start() {
        pending = ParallelBands.start(this);
    }

    /**
     * Returns whether a thread has started or finished decoding the picture.
     *
     * @return {@code true} once some thread claimed the job
     */
    boolean claimed() {
        return pending == null || pending.started();
    }

    /**
     * Waits until the picture is decoded, decoding it on the calling thread if no other thread has
     * started it; the engine calls it only once every job started before this one has finished or
     * been claimed.
     */
    void join() {
        if (pending != null) {
            pending.join();
        }
        frame.awaitDone();
    }

    /**
     * Prevents the job from running if no thread has started it.
     *
     * @return {@code true} if the job will never run, {@code false} if a thread started it
     */
    boolean cancel() {
        return pending == null || pending.tryCancel();
    }

    /**
     * Returns the first failure of the picture; valid once the picture is done.
     *
     * @return the failure, or {@code null}
     */
    MediaException error() {
        return error;
    }

    @Override
    public void run() {
        var decoder = borrow();
        try {
            error = decoder.decode(this);
        } catch (MediaException failure) {
            frame.damaged = true;
            error = failure;
        } catch (RuntimeException | Error failure) {
            frame.damaged = true;
            error = new MediaException.Malformed("corrupt HEVC picture", failure);
        } finally {
            slices.clear();
            frame.publishDone();
            release(decoder);
        }
    }
}
