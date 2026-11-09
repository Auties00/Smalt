package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.field.FieldOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Removes 2:3 pulldown (inverse telecine): field matching, then decimation.
 *
 * <p>Each frame keeps its first field (the top one unless the frames are bottom field first) and
 * takes the second field of the same frame, the previous one or the next one, whichever weaves with
 * the fewest combed luma samples (those more than 1/32 of the sample range from both rows around them, in the
 * same direction, {@link FieldOps#combedSamples}); the same frame wins unless another combs less than
 * three quarters as much. Of every five matched frames, counted from the first, the one whose luma
 * differs least from the matched frame before it is dropped; a last group of fewer than five keeps
 * every frame. The remaining frames are shown one per tick of four fifths of the source rate, from
 * the time of the first frame. A matched frame still combed (a quarter of the luma samples of some 16 by 16
 * window combed by more than 1/16 of the sample range, {@link FieldOps#densestCombing}) leaves with the scan
 * of the source, so a following
 * deinterlacer rebuilds it; the others leave progressive.
 *
 * <p>An instance is not thread-safe.
 */
final class FieldMatcher implements FrameSink {
    /**
     * The frames of a decimation group.
     */
    private static final int CYCLE = 5;

    /**
     * The combed samples of a 16 by 16 window that make a frame combed.
     */
    private static final int DENSE_COMBING = 64;

    private final FrameSink next;

    /**
     * The duration of one output frame, as a rate.
     */
    private final Rational tick;

    private final Rational timeBase;

    /**
     * The previous, current and next input frames, as copies; {@code null} where absent.
     */
    private Picture previous;
    private Picture current;
    private Picture following;

    /**
     * Spare copies recycled into the window.
     */
    private Picture spare;

    /**
     * The scan of the current frame.
     */
    private FieldOrder currentScan;

    private FieldOrder followingScan;

    /**
     * The matched frames of the current group, and the difference of each from the matched frame
     * before it.
     */
    private final Picture[] group = new Picture[CYCLE];
    private final long[] differences = new long[CYCLE];
    private final boolean[] combed = new boolean[CYCLE];
    private int grouped;

    /**
     * A copy of the last matched frame of the previous group, the reference of the first difference
     * of the next one, or {@code null} before the first group ends.
     */
    private Picture reference;

    /**
     * The time of the first frame, or {@link Long#MIN_VALUE} before it.
     */
    private long origin = Long.MIN_VALUE;

    /**
     * The number of frames sent.
     */
    private long sent;

    /**
     * The scan assumed for frames that report a progressive one: the first interlaced scan seen, top
     * field first until then.
     */
    private FieldOrder order = FieldOrder.TOP_FIRST;

    /**
     * The scratch of {@link FieldOps#densestCombing}, grown to the frame size.
     */
    private int[] cells = new int[0];

    /**
     * The end of the last frame sent, on the timeline of the frames received.
     */
    private long sentEnd;

    /**
     * Constructs a step.
     *
     * @param next the receiver of the progressive frames
     * @param sourceRate the rate of the interlaced frames, positive
     * @param timeBase the time base of the frame times
     */
    FieldMatcher(FrameSink next, Rational sourceRate, Rational timeBase) {
        this.next = next;
        this.tick = new Rational(Math.multiplyExact(sourceRate.numerator(), 4),
                Math.multiplyExact(sourceRate.denominator(), 5)).invert();
        this.timeBase = timeBase;
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        if (origin == Long.MIN_VALUE) {
            origin = time;
        }
        if (scan.isInterlaced()) {
            order = scan;
        }
        var copy = PictureCopy.copy(picture instanceof ChannelPicture ? ImageOps.toRgba16(picture) : picture, spare);
        spare = previous;
        previous = current;
        current = following;
        currentScan = followingScan;
        following = copy;
        followingScan = order;
        if (current != null) {
            match();
        }
    }

    /**
     * Matches the current frame and adds it to the group.
     */
    private void match() {
        var bottomFirst = currentScan == FieldOrder.BOTTOM_FIRST;
        var kept = Plane.of(current)[0];
        var depth = depth(current);
        var limit = 1 << (depth - 5);
        var threshold = combing(kept, kept, bottomFirst, limit) * 3 / 4;
        var best = current;
        var bestCombing = Long.MAX_VALUE;
        if (previous != null) {
            var value = combing(kept, Plane.of(previous)[0], bottomFirst, limit);
            if (value < threshold) {
                best = previous;
                bestCombing = value;
            }
        }
        if (following != null) {
            var value = combing(kept, Plane.of(following)[0], bottomFirst, limit);
            if (value < threshold && value < bestCombing) {
                best = following;
            }
        }
        var slot = group[grouped];
        slot = PictureCopy.copy(current, slot);
        if (best != current) {
            var keptPlanes = Plane.of(current);
            var otherPlanes = Plane.of(best);
            var targets = Plane.of(slot);
            for (var index = 0; index < targets.length; index++) {
                if (bottomFirst) {
                    FieldOps.weave(otherPlanes[index], keptPlanes[index], targets[index]);
                } else {
                    FieldOps.weave(keptPlanes[index], otherPlanes[index], targets[index]);
                }
            }
        }
        group[grouped] = slot;
        var luma = Plane.of(slot)[0];
        var before = grouped > 0 ? group[grouped - 1] : reference;
        differences[grouped] = before == null ? Long.MAX_VALUE : FieldOps.difference(luma, Plane.of(before)[0]);
        var needed = FieldOps.combingCells(luma.width(), luma.height());
        if (cells.length < needed) {
            cells = new int[needed];
        }
        combed[grouped] = FieldOps.densestCombing(luma, 1 << (depth - 4), cells) >= DENSE_COMBING;
        grouped++;
        if (grouped == CYCLE) {
            flushGroup(true);
        }
    }

    /**
     * {@return the bits of the samples of a frame}
     *
     * @param picture the frame
     */
    private static int depth(Picture picture) {
        return switch (picture) {
            case Yuv16Picture yuv -> yuv.bitDepth();
            case Rgba16Picture _ -> 16;
            default -> 8;
        };
    }

    /**
     * {@return the combed samples of the frame that weaves a kept field with the other field of a candidate}
     *
     * @param kept the luma of the frame whose field is kept
     * @param candidate the luma of the frame giving the other field
     * @param bottomFirst whether the kept field is the bottom one
     * @param limit the smallest difference that counts as combing
     */
    private static long combing(Plane kept, Plane candidate, boolean bottomFirst, int limit) {
        return bottomFirst ? FieldOps.combedSamples(candidate, kept, limit) : FieldOps.combedSamples(kept, candidate, limit);
    }

    /**
     * Sends the frames of the group, without the one most like its predecessor when the group is
     * complete.
     *
     * @param decimate whether one frame is dropped
     */
    private void flushGroup(boolean decimate) {
        var dropped = -1;
        if (decimate) {
            var least = Long.MAX_VALUE;
            for (var index = 0; index < grouped; index++) {
                if (differences[index] < least) {
                    least = differences[index];
                    dropped = index;
                }
            }
        }
        for (var index = 0; index < grouped; index++) {
            if (index == dropped) {
                continue;
            }
            var time = origin + tick.rescale(sent, timeBase);
            var length = Math.max(1, origin + tick.rescale(sent + 1, timeBase) - time);
            next.accept(group[index], time, length, combed[index] ? order : FieldOrder.PROGRESSIVE);
            sent++;
            sentEnd = time + length;
        }
        if (grouped > 0) {
            reference = PictureCopy.copy(group[grouped - 1], reference);
        }
        grouped = 0;
    }

    @Override
    public void finish(long end) {
        if (following != null) {
            spare = previous;
            previous = current;
            current = following;
            currentScan = followingScan;
            following = null;
            match();
        }
        if (grouped > 0) {
            flushGroup(false);
        }
        next.finish(Math.max(end, sentEnd));
    }
}
