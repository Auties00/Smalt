package com.github.auties00.smalt.internal.limits;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

/**
 * Checks the sizes an input declares against a {@link MediaLimits} before a decoder allocates.
 *
 * <p>This class is internal to Smalt: the decoders of every artifact call it, and it is not part
 * of the public API. Every method is a pure function of its arguments and is thread-safe.
 */
public final class LimitChecks {
    /**
     * Limits that accept every input, for header probes that allocate no pixel or sample memory
     * and must describe a file whatever its size.
     */
    public static final MediaLimits UNLIMITED = MediaLimits.builder().maxDimension(Integer.MAX_VALUE)
            .maxPixels(Long.MAX_VALUE).maxFrames(Integer.MAX_VALUE).maxChannels(Integer.MAX_VALUE)
            .maxBytes(Long.MAX_VALUE).build();

    /**
     * Prevents instantiation.
     */
    private LimitChecks() {
        throw new AssertionError();
    }

    /**
     * Checks that a picture of the given size is within the limits.
     *
     * @param limits the bounds
     * @param width the declared width in pixels
     * @param height the declared height in pixels
     * @throws MediaException.Malformed if {@code width} or {@code height} is not positive
     * @throws MediaException.LimitExceeded if a side exceeds {@link MediaLimits#maxDimension()} or the area exceeds
     * {@link MediaLimits#maxPixels()}
     */
    public static void checkPicture(MediaLimits limits, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new MediaException.Malformed("invalid picture size " + width + "x" + height);
        }
        var maxDimension = limits.maxDimension();
        if (width > maxDimension || height > maxDimension) {
            throw new MediaException.LimitExceeded("picture size " + width + "x" + height
                    + " exceeds the " + maxDimension + " pixel side limit");
        }
        if ((long) width * height > limits.maxPixels()) {
            throw new MediaException.LimitExceeded("picture size " + width + "x" + height
                    + " exceeds the " + limits.maxPixels() + " pixel area limit");
        }
    }

    /**
     * Checks that a frame count is within the limits.
     *
     * @param limits the bounds
     * @param frames the declared or observed number of frames
     * @throws MediaException.LimitExceeded if {@code frames} exceeds {@link MediaLimits#maxFrames()}
     */
    public static void checkFrames(MediaLimits limits, long frames) {
        if (frames > limits.maxFrames()) {
            throw new MediaException.LimitExceeded("frame count " + frames + " exceeds the " + limits.maxFrames()
                    + " frame limit");
        }
    }

    /**
     * Checks that a channel count is within the limits.
     *
     * @param limits the bounds
     * @param channels the declared number of audio channels
     * @throws MediaException.Malformed if {@code channels} is not positive
     * @throws MediaException.LimitExceeded if {@code channels} exceeds {@link MediaLimits#maxChannels()}
     */
    public static void checkChannels(MediaLimits limits, int channels) {
        if (channels <= 0) {
            throw new MediaException.Malformed("invalid channel count " + channels);
        }
        if (channels > limits.maxChannels()) {
            throw new MediaException.LimitExceeded("channel count " + channels + " exceeds the "
                    + limits.maxChannels() + " channel limit");
        }
    }

    /**
     * Checks that a single allocation is within the limits.
     *
     * @param limits the bounds
     * @param bytes the number of bytes about to be allocated
     * @throws MediaException.Malformed if {@code bytes} is negative
     * @throws MediaException.LimitExceeded if {@code bytes} exceeds {@link MediaLimits#maxBytes()} or the largest Java
     * array
     */
    public static void checkAllocation(MediaLimits limits, long bytes) {
        if (bytes < 0) {
            throw new MediaException.Malformed("negative allocation size " + bytes);
        }
        if (bytes > limits.maxBytes() || bytes > Integer.MAX_VALUE - 16) {
            throw new MediaException.LimitExceeded("allocation of " + bytes + " bytes exceeds the "
                    + limits.maxBytes() + " byte limit");
        }
    }
}
