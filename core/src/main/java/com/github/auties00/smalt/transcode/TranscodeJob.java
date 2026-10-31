package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds one audio/video transcode that a thread runs and any thread may cancel.
 *
 * <p>{@link #run()} performs the transcode on the calling thread and returns when the output is
 * complete; a job with several outputs runs through {@link #runAll()}, which returns when every output is
 * complete. {@link #cancel()}, from any thread, makes a running or later {@link #run()} stop at
 * the next packet or frame with a {@link CancellationException}; interrupting the running thread
 * does the same. A canceled or failed run leaves the output incomplete: the sink is never closed
 * by the job, and what it holds is undefined, so the caller discards it.
 *
 * <p>A job runs at most once. Its methods are thread-safe.
 */
public final class TranscodeJob {
    private final List<TranscodeInput> inputs;
    private final List<TranscodeOutput> outputs;

    /**
     * The cancellation shared with the run.
     */
    private final Cancellation cancellation;

    /**
     * Whether {@link #run()} was called.
     */
    private final AtomicBoolean ran;

    /**
     * Constructs a job.
     *
     * @param input   the input; not closed
     * @param output  the output, written from its position; not closed
     * @param options the job description
     */
    TranscodeJob(MediaSource input, MediaSink output, TranscodeOptions options) {
        this(List.of(TranscodeInput.of(Objects.requireNonNull(input, "input"))),
                List.of(new TranscodeOutput(output, options)));
    }

    /**
     * Constructs a job of several inputs or outputs.
     *
     * @param inputs the inputs, at least one
     * @param outputs the outputs, at least one
     * @throws NullPointerException if an element is {@code null}
     * @throws IllegalArgumentException if a list is empty
     */
    TranscodeJob(List<TranscodeInput> inputs, List<TranscodeOutput> outputs) {
        this.inputs = List.copyOf(inputs);
        this.outputs = List.copyOf(outputs);
        if (this.inputs.isEmpty() || this.outputs.isEmpty()) {
            throw new IllegalArgumentException("a transcode needs at least one input and one output");
        }
        this.cancellation = new Cancellation();
        this.ran = new AtomicBoolean();
    }

    /**
     * Returns the job description of the first output.
     *
     * @return the options
     */
    public TranscodeOptions options() {
        return outputs.getFirst().options();
    }

    /**
     * {@return the inputs, in the order the options' input indices count them}
     */
    public List<TranscodeInput> inputs() {
        return inputs;
    }

    /**
     * {@return the outputs, in the order {@link #runAll()} returns their results}
     */
    public List<TranscodeOutput> outputs() {
        return outputs;
    }

    /**
     * Runs the transcode on the calling thread.
     *
     * @return what was written
     * @throws IllegalStateException        if the job already ran, or has several outputs
     * @throws CancellationException        if the job was canceled or the thread interrupted
     * @throws IllegalArgumentException     if the options select a track the input lacks, or ask
     *                                      for a codec or a stream the container cannot carry
     * @throws MediaException.Unsupported   if the input is not recognized, a stream must be copied
     *                                      but cannot be, or a needed decoder or encoder is
     *                                      missing (the exception names the artifact to add)
     * @throws MediaException.Malformed     if the input is corrupt
     * @throws MediaException.LimitExceeded if the input exceeds the limits
     * @throws MediaException.Io            if reading the input or writing the output fails
     */
    public TranscodeResult run() {
        if (outputs.size() > 1) {
            throw new IllegalStateException("the job has " + outputs.size() + " outputs: run it with runAll()");
        }
        return runAll().getFirst();
    }

    /**
     * Runs the transcode of every output on the calling thread, reading the inputs once.
     *
     * @return what was written, one result per output in order
     * @throws IllegalStateException        if the job already ran
     * @throws CancellationException        if the job was canceled or the thread interrupted
     * @throws IllegalArgumentException     if the options select a track or an input the job lacks, ask
     *                                      for a codec or a stream the container cannot carry, or the outputs
     *                                      differ in a setting of the input side
     * @throws MediaException.Unsupported   if an input is not recognized, a stream must be copied
     *                                      but cannot be, or a needed decoder or encoder is
     *                                      missing (the exception names the artifact to add)
     * @throws MediaException.Malformed     if an input is corrupt
     * @throws MediaException.LimitExceeded if an input exceeds the limits
     * @throws MediaException.Io            if reading an input or writing an output fails
     */
    public List<TranscodeResult> runAll() {
        if (!ran.compareAndSet(false, true)) {
            throw new IllegalStateException("the job already ran");
        }
        return new AvTranscode(inputs, outputs, cancellation).runAll();
    }

    /**
     * Requests cancellation: a running {@link #run()} stops at the next packet or frame, and a
     * later one stops before reading the input.
     */
    public void cancel() {
        cancellation.cancel();
    }

    /**
     * Returns whether cancellation was requested.
     *
     * @return {@code true} once {@link #cancel()} ran
     */
    public boolean isCanceled() {
        return cancellation.isCanceled();
    }
}
