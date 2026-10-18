package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Checks the sizes of operation results and translates unexpected operation failures, for every class of this
 * package.
 *
 * <p>A result that is no larger than its input, that is, whose area does not exceed the input's and whose longer
 * side does not exceed the input's longer side, is not checked against any {@link MediaLimits}: the input already
 * passed the limits it was decoded or allocated under. A larger result must fit {@link MediaLimits#DEFAULT}. Every
 * array must fit the largest Java array.
 */
final class OpChecks {
    /**
     * The largest array length the virtual machine reliably allocates.
     */
    private static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 16;

    /**
     * Prevents instantiation.
     */
    private OpChecks() {
        throw new AssertionError();
    }

    /**
     * Checks the size of a result made from a picture, as the class documentation describes.
     *
     * @param source the input of the operation
     * @param width the result width in pixels, positive
     * @param height the result height in pixels, positive
     * @throws MediaException.LimitExceeded if the result is larger than {@code source} and exceeds
     * {@link MediaLimits#DEFAULT}
     */
    static void checkResult(Picture source, int width, int height) {
        checkResult(source.width(), source.height(), width, height);
    }

    /**
     * Checks the size of a result made from a picture of the given size, as the class documentation describes.
     *
     * @param sourceWidth the input width in pixels
     * @param sourceHeight the input height in pixels
     * @param width the result width in pixels, positive
     * @param height the result height in pixels, positive
     * @throws MediaException.LimitExceeded if the result is larger than the input and exceeds
     * {@link MediaLimits#DEFAULT}
     */
    static void checkResult(int sourceWidth, int sourceHeight, int width, int height) {
        var grows = (long) width * height > (long) sourceWidth * sourceHeight
                || Math.max(width, height) > Math.max(sourceWidth, sourceHeight);
        if (grows) {
            LimitChecks.checkPicture(MediaLimits.DEFAULT, width, height);
        }
    }

    /**
     * Checks that an array of the given length can be allocated.
     *
     * @param length the number of array elements, not negative
     * @throws MediaException.LimitExceeded if {@code length} exceeds the largest Java array
     */
    static void checkArray(long length) {
        if (length > MAX_ARRAY_LENGTH) {
            throw new MediaException.LimitExceeded("an array of " + length + " elements exceeds the largest Java array");
        }
    }

    /**
     * Translates an unexpected runtime failure of an operation into {@link MediaException.Malformed}, passing
     * through {@link MediaException}, {@link IllegalArgumentException} and {@link NullPointerException}.
     *
     * @param exception the failure
     * @return the exception to throw
     */
    static RuntimeException translate(RuntimeException exception) {
        if (exception instanceof MediaException
                || exception instanceof IllegalArgumentException
                || exception instanceof NullPointerException) {
            return exception;
        }
        return new MediaException.Malformed("picture processing failed: " + exception, exception);
    }
}
