package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * Decodes one fully parsed picture on a thread of the module's shared pool while the engine keeps
 * parsing later pictures.
 *
 * <p>The engine appends the slices of the picture as they arrive with {@link #add(SliceTask)}
 * and, once the picture has received all of them, starts the job with {@link #start()}; the job
 * never waits for the engine. Reference pictures are read only once their rows are published as
 * final, so jobs of consecutive pictures overlap safely (frame-level parallelism). A job only
 * ever waits for jobs of the same engine started before it, and the engine waits for its jobs in
 * start order, so every wait ends: see {@link ParallelBands#submit}.
 */
final class PictureJob implements Runnable {
    /**
     * The frame the picture is decoded into.
     */
    final DecoderFrame frame;

    /**
     * The sequence number of the job: its position in the decoding order of the engine.
     */
    final long sequence;

    /**
     * The structure of the picture: {@link DecoderFrame#FRAME}, {@link DecoderFrame#TOP} or
     * {@link DecoderFrame#BOTTOM}.
     */
    private final int structure;

    /**
     * Whether the picture is an MBAFF frame.
     */
    private final boolean mbaff;

    /**
     * The job of the first field of the frame when this job decodes its second field, or
     * {@code null}; the engine reports the failures of both once the frame is output.
     */
    PictureJob previous;

    /**
     * The number of the first slice of the picture.
     */
    private final int firstSliceNum;

    /**
     * The slice number reserved for concealed macroblocks.
     */
    private final int concealSliceNum;

    /**
     * The frame concealed macroblocks are copied from, or {@code null}.
     */
    private final DecoderFrame concealSource;

    /**
     * The kernels the picture is reconstructed and deblocked with.
     */
    private final H264Dsp dsp;

    /**
     * The idle picture decoders shared by the jobs of one engine.
     */
    private final Queue<PictureDecoder> decoders;

    /**
     * The slices of the picture, in arrival order.
     */
    private final List<SliceTask> tasks = new ArrayList<>();

    /**
     * The handle of the started job, or {@code null} before {@link #start()}.
     */
    private ParallelBands.Pending<Void> pending;

    /**
     * The first failure of the picture, read after {@link DecoderFrame#awaitDone()}.
     */
    private volatile MediaException error;

    /**
     * Constructs a job.
     *
     * @param frame the frame to decode into
     * @param structure the structure of the picture: {@link DecoderFrame#FRAME},
     * {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM}
     * @param mbaff whether the picture is an MBAFF frame
     * @param sequence the sequence number of the job
     * @param firstSliceNum the number of the first slice of the picture
     * @param concealSliceNum the slice number reserved for concealed macroblocks
     * @param concealSource the frame to copy concealed macroblocks from, or {@code null}
     * @param dsp the kernels the picture is reconstructed and deblocked with
     * @param decoders the idle picture decoders
     */
    PictureJob(DecoderFrame frame, int structure, boolean mbaff, long sequence, int firstSliceNum, int concealSliceNum,
               DecoderFrame concealSource, H264Dsp dsp, Queue<PictureDecoder> decoders) {
        this.frame = frame;
        this.structure = structure;
        this.mbaff = mbaff;
        this.sequence = sequence;
        this.firstSliceNum = firstSliceNum;
        this.concealSliceNum = concealSliceNum;
        this.concealSource = concealSource;
        this.dsp = dsp;
        this.decoders = decoders;
    }

    /**
     * Appends a slice of the picture; only valid before {@link #start()}.
     *
     * @param task the slice, owning copies of its data
     */
    void add(SliceTask task) {
        tasks.add(task);
    }

    /**
     * Starts decoding the picture, which has received all its slices, on the shared pool.
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
        return pending.started();
    }

    /**
     * Waits until the picture is decoded, decoding it on the calling thread if no other thread
     * has started it; the engine calls it only once every job started before this one has
     * finished or been claimed.
     */
    void join() {
        pending.join();
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
     * Returns whether decoding of the picture finished.
     *
     * @return {@code true} once the fields of the picture are published as done
     */
    boolean isDone() {
        return frame.isDone(structure);
    }

    /**
     * Returns the first failure of the picture; valid once the frame is done.
     *
     * @return the failure, or {@code null}
     */
    MediaException error() {
        return error;
    }

    @Override
    public void run() {
        var decoder = decoders.poll();
        if (decoder == null) {
            decoder = new PictureDecoder();
        }
        try {
            decoder.start(frame, structure, mbaff, firstSliceNum, concealSliceNum, concealSource, dsp);
            for (var task : tasks) {
                decoder.decodeSlice(task);
            }
            error = decoder.finish();
        } catch (MediaException failure) {
            frame.damaged = true;
            error = failure;
        } catch (RuntimeException | Error failure) {
            frame.damaged = true;
            error = new MediaException.Malformed("corrupt picture", failure);
        } finally {
            tasks.clear();
            frame.publishDone(structure);
            decoders.add(decoder);
        }
    }
}
