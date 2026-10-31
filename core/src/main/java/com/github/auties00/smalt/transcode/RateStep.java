package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.field.FieldOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Converts frames to a constant rate: tick {@code k} is output at {@code k / rate}, showing the last
 * frame at or before it ({@link FrameRateConversion#DROP_REPEAT}) or the blend of the frames on either
 * side of it ({@link FrameRateConversion#BLEND}).
 *
 * <p>Ticks before the first frame show the first frame, and ticks after the last frame, up to the end
 * of the timeline, the last frame. An instance is not thread-safe.
 */
final class RateStep implements FrameSink {
    private final FrameSink next;
    private final Rational tick;
    private final Rational timeBase;
    private final boolean blend;

    /**
     * A copy of the last frame received, or {@code null} before the first.
     */
    private Picture last;

    /**
     * The time of {@link #last}.
     */
    private long lastTime;

    private FieldOrder lastScan = FieldOrder.PROGRESSIVE;

    /**
     * The picture blends are written to, or {@code null} before the first blend.
     */
    private Picture blended;

    /**
     * The index of the next tick to output.
     */
    private long nextTick;

    /**
     * Constructs a step.
     *
     * @param next the receiver of the frames at the constant rate
     * @param rate the output rate, positive
     * @param timeBase the time base of the frame times
     * @param conversion how frames map to ticks
     */
    RateStep(FrameSink next, Rational rate, Rational timeBase, FrameRateConversion conversion) {
        this.next = next;
        this.tick = rate.invert();
        this.timeBase = timeBase;
        this.blend = conversion == FrameRateConversion.BLEND;
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        if (last == null) {
            last = PictureCopy.copy(picture, null);
            lastTime = time;
            lastScan = scan;
            emitUpTo(time + 1, null);
            return;
        }
        emitUpTo(time, blend ? picture : null);
        last = PictureCopy.copy(picture, last);
        lastTime = time;
        lastScan = scan;
    }

    /**
     * Outputs every tick before a time, showing {@link #last} or its blend with the frame that follows.
     *
     * @param time the time the ticks end at, exclusive
     * @param following the frame presented at {@code time}, or {@code null} to repeat {@link #last}
     */
    private void emitUpTo(long time, Picture following) {
        while (true) {
            var tickTime = tick.rescale(nextTick, timeBase);
            if (tickTime >= time) {
                return;
            }
            var length = Math.max(1, tick.rescale(nextTick + 1, timeBase) - tickTime);
            var shown = last;
            if (following != null && tickTime > lastTime) {
                var weight = (int) Math.round(256.0 * (tickTime - lastTime) / (time - lastTime));
                if (weight > 0) {
                    shown = blend(following, weight);
                }
            }
            next.accept(shown, tickTime, length, lastScan);
            nextTick++;
        }
    }

    /**
     * Blends {@link #last} with a frame.
     *
     * @param following the frame after {@link #last}
     * @param weight the weight of {@code following}, from 1 to 256
     * @return the blend, valid until the next blend
     */
    private Picture blend(Picture following, int weight) {
        blended = PictureCopy.copy(last, blended);
        var a = Plane.of(last);
        var b = Plane.of(following);
        var target = Plane.of(blended);
        for (var index = 0; index < target.length; index++) {
            FieldOps.blend(a[index], b[index], weight, target[index]);
        }
        return blended;
    }

    @Override
    public void finish(long end) {
        if (last != null) {
            emitUpTo(end, null);
        }
        next.finish(end);
    }
}
