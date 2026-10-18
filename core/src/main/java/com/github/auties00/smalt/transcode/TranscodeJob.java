package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds one audio/video transcode that a thread runs and any thread may cancel.
 *
 * <p>{@link #run()} performs the transcode on the calling thread and returns when the output is
 * complete. {@link #cancel()}, from any thread, makes a running or later {@link #run()} stop at
 * the next packet or frame with a {@link CancellationException}; interrupting the running thread
 * does the same. A canceled or failed run leaves the output incomplete: the sink is never closed
 * by the job, and what it holds is undefined, so the caller discards it.
 *
 * <p>A job runs at most once. Its methods are thread-safe.
 */
public final class TranscodeJob {
    /**
     * The input.
     */
    private final MediaSource input;

    /**
     * The output.
     */
    private final MediaSink output;

    /**
     * The job description.
     */
    private final TranscodeOptions options;

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
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.options = Objects.requireNonNull(options, "options");
        this.cancellation = new Cancellation();
        this.ran = new AtomicBoolean();
    }

    /**
     * Returns the job description.
     *
     * @return the options
     */
    public TranscodeOptions options() {
        return options;
    }

    /**
     * Runs the transcode on the calling thread.
     *
     * @return what was written
     * @throws IllegalStateException        if the job already ran
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
        if (!ran.compareAndSet(false, true)) {
            throw new IllegalStateException("the job already ran");
        }
        return new AvTranscode(input, output, options, cancellation).run();
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
