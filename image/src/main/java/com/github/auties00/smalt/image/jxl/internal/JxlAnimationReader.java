/* Ports jxl-oxide 0.12.6 (crates/jxl-oxide: animation playback). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Plays the displayed frames of a JPEG XL image as an animation.
 *
 * <p>Each {@link #read()} decodes frames up to the next displayed one and returns the canvas as an
 * 8-bit RGBA picture with straight alpha, timed by the frame durations and the animation's ticks
 * per second. A still image is an animation of one frame with a duration of zero.
 */
public final class JxlAnimationReader implements AnimationReader {
    /**
     * The codestream decoder.
     */
    private final JxlCodestream codestream;

    /**
     * The number of displayed frames.
     */
    private final int frameCount;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The decoder operation that budgets the calls of this reader.
     */
    private final ParallelBands.Operation operation = new ParallelBands.Operation();

    /**
     * The timestamp of the next frame, in microseconds.
     */
    private long timestamp;

    /**
     * The number of frames returned.
     */
    private int returned;

    /**
     * Creates a reader.
     *
     * @param codestream the decoder, positioned at the first frame
     * @param frameCount the number of displayed frames
     * @param limits the resource bounds
     */
    public JxlAnimationReader(JxlCodestream codestream, int frameCount, MediaLimits limits) {
        this.codestream = codestream;
        this.frameCount = frameCount;
        this.limits = limits;
    }

    @Override
    public int width() {
        return codestream.header().width();
    }

    @Override
    public int height() {
        return codestream.header().height();
    }

    @Override
    public int loopCount() {
        var animation = codestream.header().animation();
        return animation == null ? 1 : animation.numLoops();
    }

    @Override
    public int frameCount() {
        return frameCount;
    }

    @Override
    public AnimationFrame read() {
        try (var _ = ParallelBands.operation(operation)) {
            var keyframe = codestream.nextKeyframe();
            if (keyframe == null) {
                return null;
            }
            LimitChecks.checkFrames(limits, ++returned);
            var output = JxlOutput.toPicture(keyframe.canvas(), codestream.header(), codestream.icc(), false);
            var picture = (RgbaPicture) output.picture();
            var animation = codestream.header().animation();
            long duration = 0;
            if (animation != null && animation.tpsNumerator() != 0) {
                duration = (keyframe.duration() & 0xFFFF_FFFFL) * 1_000_000L * animation.tpsDenominator()
                        / animation.tpsNumerator();
            }
            var frame = new AnimationFrame(picture, timestamp, duration);
            timestamp += duration;
            return frame;
        } catch (MediaException exception) {
            throw exception;
        } catch (OutOfMemoryError error) {
            throw new MediaException.LimitExceeded("out of memory while decoding a JPEG XL frame");
        } catch (RuntimeException exception) {
            throw new MediaException.Malformed("malformed JPEG XL frame", exception);
        }
    }

    /**
     * Counts the displayed frames of a codestream by reading the frame headers.
     *
     * @param codestream the decoder, positioned at the first frame; rewound afterwards
     * @param limits the resource bounds
     * @return the number of displayed frames
     * @throws MediaException.Malformed if a frame header is invalid
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    public static int countKeyframes(JxlCodestream codestream, MediaLimits limits) {
        var count = 0;
        var frames = 0L;
        while (!codestream.finished()) {
            var frame = codestream.skipFrame();
            if (++frames > limits.maxFrames() * 4L + 16) {
                throw new MediaException.LimitExceeded("too many JPEG XL frames");
            }
            if (frame.isKeyframe()) {
                count++;
                LimitChecks.checkFrames(limits, count);
            }
        }
        codestream.rewind();
        return count;
    }
}
