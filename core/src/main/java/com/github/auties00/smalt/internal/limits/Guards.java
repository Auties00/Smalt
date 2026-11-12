package com.github.auties00.smalt.internal.limits;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.region.RegionOutsideImageException;

import java.util.function.Supplier;

/**
 * Runs the body of a public decoder or encoder entry point and translates the failures that
 * escape it into the {@link MediaException} the entry point documents.
 *
 * <p>Decoders read untrusted input, so any failure of their body is the input's fault:
 * <ul>
 *   <li>a {@link MediaException} passes through unchanged, and so does the {@link IllegalArgumentException}
 *       of a decoding region outside the image ({@link RegionOutsideImageException});</li>
 *   <li>an {@link OutOfMemoryError} or a {@link StackOverflowError} becomes
 *       {@link MediaException.LimitExceeded};</li>
 *   <li>any other {@link RuntimeException} becomes {@link MediaException.Malformed} with the
 *       failure as its cause.</li>
 * </ul>
 * Encoders read the caller's own pictures and samples, so only an {@link OutOfMemoryError} is
 * translated; any other failure is a programming error and propagates unchanged.
 *
 * <p>Messages name the input with the {@code what} argument, a noun phrase such as
 * {@code "JPEG stream"}:
 * {@snippet :
 * corrupt JPEG stream
 * JPEG stream needs more memory than is available
 * JPEG stream nests too deeply
 * }
 * This class is thread-safe.
 */
public final class Guards {
    /**
     * Prevents instantiation.
     */
    private Guards() {
        throw new AssertionError();
    }

    /**
     * Runs a decoding body under the decoder policy.
     *
     * @param what the input, a noun phrase for messages
     * @param body the work to run
     * @param <T> the result type
     * @return the result of {@code body}
     * @throws MediaException.Malformed if {@code body} throws a runtime exception that is not a
     * {@link MediaException}
     * @throws MediaException.LimitExceeded if {@code body} runs out of memory or stack
     */
    public static <T> T malformed(String what, Supplier<T> body) {
        try {
            return body.get();
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw outOfMemory(what);
        } catch (StackOverflowError error) {
            throw tooDeep(what);
        } catch (RegionOutsideImageException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt " + what, exception);
        }
    }

    /**
     * Runs a decoding body under the decoder policy, except that an
     * {@link IllegalArgumentException} passes through unchanged, for entry points whose body
     * validates caller arguments that only the input makes checkable.
     *
     * @param what the input, a noun phrase for messages
     * @param body the work to run
     * @param <T> the result type
     * @return the result of {@code body}
     * @throws IllegalArgumentException if {@code body} throws it
     * @throws MediaException.Malformed if {@code body} throws a runtime exception that is neither
     * a {@link MediaException} nor an {@link IllegalArgumentException}
     * @throws MediaException.LimitExceeded if {@code body} runs out of memory or stack
     */
    public static <T> T malformedExceptArguments(String what, Supplier<T> body) {
        try {
            return body.get();
        } catch (MediaException | IllegalArgumentException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw outOfMemory(what);
        } catch (StackOverflowError error) {
            throw tooDeep(what);
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("corrupt " + what, exception);
        }
    }

    /**
     * Runs a body that reads untrusted input and already reports its own failures, translating only the resource
     * failures that escape it: an {@link OutOfMemoryError} or a {@link StackOverflowError} becomes
     * {@link MediaException.LimitExceeded}, and every other failure propagates unchanged.
     *
     * @param what the input, a noun phrase for messages
     * @param body the work to run
     * @param <T> the result type
     * @return the result of {@code body}
     * @throws MediaException.LimitExceeded if {@code body} runs out of memory or stack
     */
    public static <T> T bounded(String what, Supplier<T> body) {
        try {
            return body.get();
        } catch (OutOfMemoryError error) {
            throw outOfMemory(what);
        } catch (StackOverflowError error) {
            throw tooDeep(what);
        }
    }

    /**
     * Runs an encoding body under the encoder policy.
     *
     * @param what the output, a noun phrase for messages
     * @param body the work to run
     * @param <T> the result type
     * @return the result of {@code body}
     * @throws MediaException.LimitExceeded if {@code body} runs out of memory
     */
    public static <T> T encoding(String what, Supplier<T> body) {
        try {
            return body.get();
        } catch (OutOfMemoryError error) {
            throw outOfMemory(what);
        }
    }

    /**
     * Translates a runtime failure under the decoder policy, for entry points that catch it
     * themselves, such as inside a {@code try}-with-resources block.
     *
     * @param what the input, a noun phrase for messages
     * @param failure the caught failure
     * @return {@code failure} itself when it is a {@link MediaException}, otherwise a
     * {@link MediaException.Malformed} caused by it
     */
    public static MediaException translate(String what, RuntimeException failure) {
        if (failure instanceof MediaException media) {
            return media;
        }
        return new MediaException.Malformed("corrupt " + what, failure);
    }

    /**
     * {@return the failure reported for a body that ran out of memory}
     *
     * @param what the input or output, a noun phrase for messages
     */
    private static MediaException.LimitExceeded outOfMemory(String what) {
        return new MediaException.LimitExceeded(what + " needs more memory than is available");
    }

    /**
     * {@return the failure reported for a body that ran out of stack}
     *
     * @param what the input, a noun phrase for messages
     */
    private static MediaException.LimitExceeded tooDeep(String what) {
        return new MediaException.LimitExceeded(what + " nests too deeply");
    }
}
