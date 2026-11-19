package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.field.FieldOps;
import com.github.auties00.smalt.internal.field.Plane;
import com.github.auties00.smalt.internal.plane.PictureCopy;
import com.github.auties00.smalt.video.FieldOrder;

/**
 * Makes interlaced frames progressive with a {@link Deinterlacer}.
 *
 * <p>At the frame rate, an interlaced frame becomes the frame of its first field in display order;
 * at the field rate, it becomes two frames, the first field's at its time and the second field's
 * half its duration later, and a progressive frame is shown twice in the same way. Progressive
 * frames otherwise pass unchanged. Every frame leaves progressive.
 *
 * <p>An instance is not thread-safe.
 */
final class DeinterlaceStep implements FrameSink {
    private final FrameSink next;

    /**
     * The method, one of the {@link FieldOps} constants.
     */
    private final int method;

    /**
     * Whether each field becomes a frame.
     */
    private final boolean fieldRate;

    /**
     * The picture the rebuilt frames are written to, or {@code null} before the first.
     */
    private Picture buffer;

    /**
     * Constructs a step.
     *
     * @param next the receiver of the progressive frames
     * @param deinterlacer the method
     * @param fieldRate whether each field becomes a frame
     */
    DeinterlaceStep(FrameSink next, Deinterlacer deinterlacer, boolean fieldRate) {
        this.next = next;
        this.method = switch (deinterlacer) {
            case BOB -> FieldOps.BOB;
            case LINEAR -> FieldOps.LINEAR;
            case ELA -> FieldOps.ELA;
        };
        this.fieldRate = fieldRate;
    }

    @Override
    public void accept(Picture picture, long time, long duration, FieldOrder scan) {
        var half = fieldRate ? Math.max(1, duration / 2) : duration;
        if (!scan.isInterlaced()) {
            next.accept(picture, time, half, FieldOrder.PROGRESSIVE);
            if (fieldRate) {
                next.accept(picture, time + half, Math.max(1, duration - half), FieldOrder.PROGRESSIVE);
            }
            return;
        }
        var source = picture instanceof ChannelPicture ? ImageOps.toRgba16(picture) : picture;
        var bottomFirst = scan == FieldOrder.BOTTOM_FIRST;
        next.accept(field(source, bottomFirst), time, half, FieldOrder.PROGRESSIVE);
        if (fieldRate) {
            next.accept(field(source, !bottomFirst), time + half, Math.max(1, duration - half), FieldOrder.PROGRESSIVE);
        }
    }

    /**
     * Rebuilds the frame of one field.
     *
     * @param picture the woven frame
     * @param bottom whether the bottom field is kept
     * @return the rebuilt frame, valid until the next call
     */
    private Picture field(Picture picture, boolean bottom) {
        buffer = PictureCopy.copy(picture, buffer);
        var sources = Plane.of(picture);
        var targets = Plane.of(buffer);
        for (var index = 0; index < sources.length; index++) {
            FieldOps.deinterlace(sources[index], targets[index], bottom, method);
        }
        return buffer;
    }

    @Override
    public void finish(long end) {
        next.finish(end);
    }
}
