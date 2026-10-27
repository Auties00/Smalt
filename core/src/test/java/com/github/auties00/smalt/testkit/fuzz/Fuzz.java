package com.github.auties00.smalt.testkit.fuzz;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import java.util.Optional;

/**
 * The contract every fuzz target checks, and the record of the last run on each thread.
 *
 * <p>A fuzz target hands an arbitrary input to one Smalt entry point through {@link FuzzRun#step}. The only
 * exception the entry point may throw is a {@link com.github.auties00.smalt.MediaException}; anything else
 * (an unchecked exception, an {@link Error} such as {@link OutOfMemoryError} or {@link StackOverflowError})
 * propagates out of the target and is a finding. Smalt's entry points turn the internal failures of their
 * bodies into {@code MediaException}s, so a rejection that only hides one is a finding too ({@link #check}).
 * The targets decode under {@link #LIMITS}, so a valid
 * input never needs more than a few hundred megabytes, and they cap the packets, frames and pages they
 * read, so that every input finishes in bounded time; the fuzzer's per-input timeout and the replay
 * tests' deadline turn a hang into a finding.
 *
 * <p>While fuzzing, a target that would decode the same input several ways runs only the ways
 * {@link FuzzRun#sampled} selects from a hash of the input, which keeps the cost of an input close to one
 * decode; a replay ({@link #thorough(boolean)}) runs every way.
 *
 * <p>The record of the last run ({@link #last()}) lets {@link FuzzRegressions} require that a seed named
 * {@code valid-*} is accepted by every step.
 */
public final class Fuzz {
    /**
     * The limits the targets decode under: 8192 pixels per side, 1 megapixel, 32 frames, 16 channels and
     * 64 MiB per allocation.
     */
    public static final MediaLimits LIMITS = MediaLimits.builder()
            .maxDimension(8192)
            .maxPixels(1L << 20)
            .maxFrames(32)
            .maxChannels(16)
            .maxBytes(64L << 20)
            .build();

    /** The most packets a target reads from a demuxer or hands to a decoder. */
    public static final int MAX_PACKETS = 1024;

    /** The largest picture, in pixels, a target converts to RGBA to check the decoder output. */
    public static final long MAX_CONVERTED_PIXELS = 1L << 20;

    private static final ThreadLocal<FuzzRun> LAST = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> THOROUGH = ThreadLocal.withInitial(() -> false);

    private Fuzz() {
        throw new AssertionError();
    }

    /**
     * Starts the run of a target on the calling thread.
     *
     * @param target the name of the target, for messages
     * @return the run, which also becomes {@link #last()}
     */
    public static FuzzRun start(String target) {
        var run = new FuzzRun(target, THOROUGH.get());
        LAST.set(run);
        return run;
    }

    /**
     * Returns the last run started on the calling thread.
     *
     * @return the run, or empty when no target ran on this thread
     */
    public static Optional<FuzzRun> last() {
        return Optional.ofNullable(LAST.get());
    }

    /**
     * Sets whether the runs started on the calling thread run every sampled step.
     *
     * @param thorough {@code true} to run every step, as a replay does; {@code false}, the default, to sample
     */
    public static void thorough(boolean thorough) {
        THOROUGH.set(thorough);
    }

    /**
     * Fails when a {@link MediaException} only reports an internal failure of the entry point that threw it.
     *
     * <p>The entry points run their bodies under a guard that turns any other runtime exception into a
     * {@link MediaException.Malformed} caused by it, and running out of memory or stack into a
     * {@link MediaException.LimitExceeded}, so that a caller only ever sees {@code MediaException}s. For a fuzz
     * target such a rejection is a finding when its cause chain holds a failure no check of the input throws: an
     * {@link Error}, an {@link IndexOutOfBoundsException}, a {@link NegativeArraySizeException}, a
     * {@link NullPointerException}, a {@link ClassCastException}, an {@link ArrayStoreException}, a buffer underflow
     * or overflow, a {@link java.util.NoSuchElementException}, a
     * {@link java.util.ConcurrentModificationException}, an {@link UnsupportedOperationException} or a division by
     * zero; running out of memory under {@link #LIMITS} or out of stack is one too. An
     * {@link IllegalArgumentException} or {@link IllegalStateException} in the chain is a rejection: Smalt throws
     * them on purpose, for example when a muxer refuses a packet the input timed wrongly, and its entry points
     * translate them knowingly.
     *
     * @param step the target and step, for the message
     * @param exception the exception the step threw
     * @throws AssertionError if the exception hides an internal failure
     */
    public static void check(String step, MediaException exception) {
        var message = exception.getMessage();
        if (message != null && (message.endsWith(" needs more memory than is available")
                || message.startsWith("out of memory") || message.endsWith(" nests too deeply"))) {
            throw new AssertionError(step + " ran out of memory or stack: " + message, exception);
        }
        var depth = 0;
        for (var cause = exception.getCause(); cause != null && depth < 16; cause = cause.getCause(), depth++) {
            if (internalFailure(cause)) {
                throw new AssertionError(step + " hid an internal failure in " + exception, exception);
            }
        }
    }

    private static boolean internalFailure(Throwable cause) {
        return cause instanceof Error
                || cause instanceof IndexOutOfBoundsException
                || cause instanceof NegativeArraySizeException
                || cause instanceof NullPointerException
                || cause instanceof ClassCastException
                || cause instanceof ArrayStoreException
                || cause instanceof java.nio.BufferUnderflowException
                || cause instanceof java.nio.BufferOverflowException
                || cause instanceof java.util.NoSuchElementException
                || cause instanceof java.util.ConcurrentModificationException
                || cause instanceof UnsupportedOperationException
                || cause instanceof ArithmeticException && "/ by zero".equals(cause.getMessage());
    }

    /**
     * Forgets the last run of the calling thread.
     */
    public static void clear() {
        LAST.remove();
    }
}
