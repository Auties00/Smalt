/* Ports jxl-oxide 0.12.6 (crates/jxl-oxide: animation playback). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.jxl.internal.header.FrameHeader;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Plays the displayed frames of a JPEG XL image as an animation.
 *
 * <p>Each {@link #read()} decodes frames up to the next displayed one and returns the canvas as an
 * 8-bit RGBA picture with straight alpha, timed by the frame durations and the animation's ticks
 * per second. A still image is an animation of one frame with a duration of zero.
 *
 * <p>The reader seeks as a reader that composites every frame from the first one would: the next {@link #read()}
 * returns the first frame that ends after the target. A seek starts compositing at the latest frame of the
 * {@code jxli} frame index at or before the target, when the file has one, and otherwise at the first frame (or
 * goes on from the current frame when seeking forwards). An indexed frame serves as a starting point only when
 * neither it nor any frame after it reads a reference slot or LF frame written before it; a frame with patches
 * counts as reading every reference slot.
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
     * The frames of the index a seek may start at, in codestream order.
     */
    private final List<Restart> restarts;

    /**
     * The timestamp of the next frame to decode, in microseconds.
     */
    private long timestamp;

    /**
     * The number of displayed frames decoded.
     */
    private int returned;

    /**
     * The frame a seek decoded and the next {@link #read()} returns, or {@code null}.
     */
    private AnimationFrame pending;

    /**
     * Whether a seek reached the end, so that the next {@link #read()} returns {@code null}.
     */
    private boolean exhausted;

    /**
     * Holds a frame a seek can start compositing at.
     *
     * @param start where the frame starts and the frame counts before it
     * @param timestamp the presentation time of the first displayed frame from it on, in microseconds
     */
    record Restart(JxlCodestream.FrameStart start, long timestamp) {
    }

    /**
     * Creates a reader.
     *
     * @param codestream the decoder, positioned at the first frame
     * @param frameCount the number of displayed frames
     * @param limits the resource bounds
     * @throws MediaException.Malformed if a frame header is invalid
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    public JxlAnimationReader(JxlCodestream codestream, int frameCount, MediaLimits limits) {
        this.codestream = codestream;
        this.frameCount = frameCount;
        this.limits = limits;
        this.restarts = findRestarts(codestream, limits);
    }

    /**
     * {@return the frames of the {@code jxli} index a seek can start at, after the first frame}
     */
    List<Restart> restarts() {
        return restarts;
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
        if (pending != null) {
            var frame = pending;
            pending = null;
            return frame;
        }
        if (exhausted) {
            return null;
        }
        try (var _ = ParallelBands.operation(operation)) {
            var frame = decodeNext();
            if (frame == null) {
                exhausted = true;
            }
            return frame;
        }
    }

    /**
     * Decodes the next displayed frame.
     *
     * @return the frame, or {@code null} after the last one
     * @throws MediaException.Malformed if a frame is invalid
     * @throws MediaException.LimitExceeded if a frame exceeds the limits
     */
    private AnimationFrame decodeNext() {
        try {
            var keyframe = codestream.nextKeyframe();
            if (keyframe == null) {
                return null;
            }
            LimitChecks.checkFrames(limits, ++returned);
            var output = JxlOutput.toPicture(keyframe.canvas(), codestream.header(), codestream.icc(), false);
            var picture = (RgbaPicture) output.picture();
            var duration = micros(keyframe.duration());
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
     * {@return the duration of a frame in microseconds, rounded down}
     *
     * @param ticks the duration in animation ticks, unsigned
     */
    private long micros(int ticks) {
        var animation = codestream.header().animation();
        if (animation == null || animation.tpsNumerator() == 0) {
            return 0;
        }
        return (ticks & 0xFFFF_FFFFL) * 1_000_000L * animation.tpsDenominator() / animation.tpsNumerator();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws MediaException.Malformed if a frame composited while seeking is corrupt
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    @Override
    public void seek(long micros) {
        pending = null;
        try (var _ = ParallelBands.operation(operation)) {
            Restart restart = null;
            for (var candidate : restarts) {
                if (candidate.timestamp() <= micros) {
                    restart = candidate;
                }
            }
            if (micros < timestamp || exhausted) {
                if (restart != null) {
                    restartAt(restart);
                } else {
                    codestream.rewind();
                    timestamp = 0;
                    returned = 0;
                    exhausted = false;
                }
            } else if (restart != null && restart.start().keyframes() > returned) {
                restartAt(restart);
            }
            while (true) {
                var frame = decodeNext();
                if (frame == null) {
                    exhausted = true;
                    return;
                }
                if (timestamp > micros) {
                    pending = frame;
                    return;
                }
            }
        }
    }

    /**
     * Positions the decoder at a frame of the index.
     *
     * @param restart the frame
     */
    private void restartAt(Restart restart) {
        codestream.restart(restart.start());
        timestamp = restart.timestamp();
        returned = restart.start().keyframes();
        exhausted = false;
    }

    /**
     * Lists the frames of the {@code jxli} index a seek can start at: those that start a frame of the
     * codestream and from which no frame reads a reference slot or LF frame written before them.
     *
     * @param codestream the decoder, positioned at the first frame and left there
     * @param limits the resource bounds
     * @return the frames after the first one, in codestream order; empty when the file has no index
     * @throws MediaException.Malformed if a frame header is invalid
     * @throws MediaException.LimitExceeded if the frame count exceeds the limits
     */
    private static List<Restart> findRestarts(JxlCodestream codestream, MediaLimits limits) {
        var index = codestream.container().frameIndex();
        if (index == null || index.entries().size() < 2) {
            return List.of();
        }
        var frames = codestream.scanFrames(limits.maxFrames() * 4L + 16);
        var byOffset = new HashMap<Long, Integer>();
        for (var i = 0; i < frames.size(); i++) {
            byOffset.put(frames.get(i).offset(), i);
        }
        var animation = codestream.header().animation();
        var times = new long[frames.size() + 1];
        for (var i = 0; i < frames.size(); i++) {
            var frame = frames.get(i).header();
            var ticks = frame.isKeyframe() ? frame.duration : 0;
            times[i + 1] = times[i] + (animation == null || animation.tpsNumerator() == 0 ? 0
                    : (ticks & 0xFFFF_FFFFL) * 1_000_000L * animation.tpsDenominator() / animation.tpsNumerator());
        }
        var independent = independentFrames(frames);
        var result = new ArrayList<Restart>();
        for (var entry : index.entries()) {
            var found = byOffset.get(entry.offset());
            if (found == null || found == 0 || !independent[found]) {
                continue;
            }
            result.add(new Restart(frames.get(found), times[found]));
        }
        return List.copyOf(result);
    }

    /**
     * Finds the frames from which the rest of the codestream decodes without the state the frames before them
     * leave.
     *
     * <p>A frame reads the reference slot it blends onto (and those of its extra channels) when it does not
     * replace the whole canvas, every slot when it has patches, and the LF frame it uses. A decode can start at
     * frame {@code i} when every read of a frame from {@code i} on finds a slot written at or after {@code i}, or
     * one never written before it.
     *
     * @param frames every frame of the codestream
     * @return for every frame, whether a decode can start at it
     */
    private static boolean[] independentFrames(List<JxlCodestream.FrameStart> frames) {
        var count = frames.size();
        var lastWrite = new int[4];
        var lastLfWrite = new int[4];
        // a slot never written reads as empty however the decode started
        Arrays.fill(lastWrite, Integer.MAX_VALUE);
        Arrays.fill(lastLfWrite, Integer.MAX_VALUE);
        // the earliest frame whose output a frame reads, Integer.MAX_VALUE when it reads none
        var earliestRead = new int[count];
        for (var i = 0; i < count; i++) {
            var frame = frames.get(i).header();
            var earliest = Integer.MAX_VALUE;
            if (frame.hasFlag(FrameHeader.FLAG_USE_LF_FRAME)) {
                earliest = Math.min(earliest, lastLfWrite[frame.lfLevel]);
            }
            if (frame.hasFlag(FrameHeader.FLAG_PATCHES)) {
                for (var slot : lastWrite) {
                    earliest = Math.min(earliest, slot);
                }
            }
            if (frame.isNormalFrame() && needsBlending(frame)) {
                earliest = Math.min(earliest, lastWrite[frame.blending.source()]);
                for (var info : frame.ecBlending) {
                    earliest = Math.min(earliest, lastWrite[info.source()]);
                }
            }
            earliestRead[i] = earliest;
            if (frame.lfLevel > 0) {
                lastLfWrite[frame.lfLevel - 1] = i;
            } else if (frame.canReference()) {
                lastWrite[frame.saveAsReference] = i;
            }
        }
        var independent = new boolean[count];
        var suffix = Integer.MAX_VALUE;
        for (var i = count - 1; i >= 0; i--) {
            suffix = Math.min(suffix, earliestRead[i]);
            independent[i] = suffix >= i;
        }
        return independent;
    }

    /**
     * {@return whether a frame is composited onto a reference slot rather than replacing the whole canvas}
     *
     * @param frame the frame header
     */
    private static boolean needsBlending(FrameHeader frame) {
        if (frame.haveCrop || frame.blending.mode() != FrameHeader.BLEND_REPLACE) {
            return true;
        }
        for (var info : frame.ecBlending) {
            if (info.mode() != FrameHeader.BLEND_REPLACE) {
                return true;
            }
        }
        return false;
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
