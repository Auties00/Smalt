package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.internal.field.FieldOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Applies 2:3 pulldown, top field first: every four frames {@code A B C D} become the five frames
 * {@code AA BB BC CD DD}, where {@code XY} weaves the top field of {@code X} with the bottom field of
 * {@code Y}.
 *
 * <p>The frames are expected at a constant rate, one per tick; output frame {@code m} is shown at
 * {@code m} ticks of five fourths of that rate. A stream that ends after the mixed frame {@code BC}
 * ends with {@code CC}, so that every frame is shown whole. An instance is not thread-safe.
 */
final class TelecineStep implements FrameSink {
    private final FrameSink next;

    /**
     * The duration of one output frame, as a rate.
     */
    private final Rational tick;

    private final Rational timeBase;

    /**
     * The number of input frames received.
     */
    private long received;

    /**
     * The number of output frames sent.
     */
    private long sent;

    /**
     * A copy of the frame whose top field the next mixed frame takes, or {@code null}.
     */
    private Picture held;

    /**
     * The picture mixed frames are woven into, or {@code null} before the first.
     */
    private Picture woven;

    /**
     * Whether the last output frame mixed two input frames, its bottom field taken from {@link #held}.
     */
    private boolean mixedLast;

    /**
     * Constructs a step.
     *
     * @param next the receiver of the interlaced frames
     * @param rate the rate of the progressive frames received, positive
     * @param timeBase the time base of the frame times
     */
    TelecineStep(FrameSink next, Rational rate, Rational timeBase) {
        this.next = next;
        this.tick = new Rational(Math.multiplyExact(rate.numerator(), 5), Math.multiplyExact(rate.denominator(), 4))
                .invert();
        this.timeBase = timeBase;
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        var phase = (int) (received++ % 4);
        switch (phase) {
            case 0 -> emit(picture, false);
            case 1 -> {
                emit(picture, false);
                held = PictureCopy.copy(picture, held);
            }
            case 2 -> {
                emit(weave(held, picture), true);
                held = PictureCopy.copy(picture, held);
            }
            default -> {
                emit(weave(held, picture), false);
                emit(picture, false);
            }
        }
    }

    /**
     * Weaves the top field of one frame with the bottom field of another.
     *
     * @param top the frame of the top field
     * @param bottom the frame of the bottom field
     * @return the woven frame, valid until the next weave
     */
    private Picture weave(Picture top, Picture bottom) {
        woven = PictureCopy.copy(top, woven);
        var tops = Plane.of(top);
        var bottoms = Plane.of(bottom);
        var targets = Plane.of(woven);
        for (var index = 0; index < targets.length; index++) {
            FieldOps.weave(tops[index], bottoms[index], targets[index]);
        }
        return woven;
    }

    /**
     * Sends one output frame at the next output tick.
     *
     * @param picture the frame
     * @param mixed whether it is the mixed frame {@code BC}
     */
    private void emit(Picture picture, boolean mixed) {
        var time = tick.rescale(sent, timeBase);
        var length = Math.max(1, tick.rescale(sent + 1, timeBase) - time);
        next.accept(picture, time, length, FieldOrder.TOP_FIRST);
        sent++;
        mixedLast = mixed;
    }

    @Override
    public void finish(long end) {
        if (mixedLast && held != null) {
            emit(held, false);
        }
        next.finish(Math.max(end, tick.rescale(sent, timeBase)));
    }
}
