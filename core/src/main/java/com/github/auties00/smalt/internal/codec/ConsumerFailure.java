package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.MediaException;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Keeps a decoder from reporting a failure of its caller's output consumer as corrupt input.
 *
 * <p>A decoder that hands its output to a caller's {@link Consumer} translates the runtime
 * failures of its own engine into {@link MediaException.Malformed}; an exception the consumer
 * throws is the caller's, so it must reach the caller unchanged. {@link #wrap} returns a
 * consumer that remembers what the caller's consumer threw, and {@link #translate} uses it to tell
 * the two apart; {@link #run} does both around one call. Instances are not thread-safe.
 *
 * @param <T> the type of the decoder's output
 */
public final class ConsumerFailure<T> implements Consumer<T> {
    /**
     * The caller's consumer.
     */
    private final Consumer<? super T> target;

    /**
     * The runtime exception the caller's consumer threw, or {@code null}.
     */
    private RuntimeException failure;

    /**
     * Wraps the caller's consumer.
     *
     * @param target the consumer
     */
    private ConsumerFailure(Consumer<? super T> target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /**
     * Returns a consumer that passes every value to the caller's and remembers the runtime
     * exception it throws.
     *
     * @param target the caller's consumer
     * @param <T> the type of the decoder's output
     * @return the wrapping consumer
     */
    public static <T> ConsumerFailure<T> wrap(Consumer<? super T> target) {
        return new ConsumerFailure<>(target);
    }

    /**
     * Runs a decoding step that feeds the caller's consumer and translates its failures.
     *
     * <p>A {@link MediaException} and an exception of the caller's consumer pass through unchanged;
     * any other runtime exception becomes {@link MediaException.Malformed} with the message
     * {@code "corrupt " + what}.
     *
     * @param what the input, a noun phrase for messages, such as {@code "AAC packet"}
     * @param output the caller's consumer
     * @param step the step, which receives the wrapping consumer to emit through
     * @param <T> the type of the decoder's output
     * @throws MediaException.Malformed if the step fails with a runtime exception that is neither
     * a {@link MediaException} nor the consumer's
     */
    public static <T> void run(String what, Consumer<? super T> output, Consumer<? super ConsumerFailure<T>> step) {
        var handoff = new ConsumerFailure<T>(output);
        try {
            step.accept(handoff);
        } catch (RuntimeException exception) {
            throw handoff.translate(what, exception);
        }
    }

    @Override
    public void accept(T value) {
        try {
            target.accept(value);
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        }
    }

    /**
     * Returns whether an exception is the one the caller's consumer threw.
     *
     * @param exception the exception that escaped the decoding step
     * @return {@code true} when it is the consumer's own
     */
    public boolean isConsumerFailure(Throwable exception) {
        return exception != null && exception == failure;
    }

    /**
     * Translates an exception that escaped a decoding step.
     *
     * @param what the input, a noun phrase for messages
     * @param exception the exception that escaped the decoding step
     * @return {@code exception} itself when it is a {@link MediaException} or the consumer's, a
     * {@link MediaException.Malformed} caused by it otherwise
     */
    public RuntimeException translate(String what, RuntimeException exception) {
        if (exception instanceof MediaException || exception == failure) {
            return exception;
        }
        return new MediaException.Malformed("corrupt " + what, exception);
    }
}
