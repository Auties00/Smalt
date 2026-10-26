package com.github.auties00.smalt.video;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.track.Packet;

import java.util.Objects;

/**
 * Pairs one picture of a video stream with its timing and display information.
 *
 * <p>Timestamps are in the time base of the stream the frame came from or is headed to. A
 * decoder recycles the picture buffers it hands out: a frame passed to a consumer is valid only
 * until the consumer returns.
 *
 * <p>Instances are not thread-safe; the picture is shared, not copied, and decoders may recycle it,
 * so threads that share a frame while one of them modifies its picture must synchronize externally.
 *
 * @param picture  the pixels
 * @param pts      the presentation timestamp
 * @param duration the presentation duration, zero or more, or {@link Packet#UNKNOWN_DURATION} when unknown
 * @param keyframe whether the frame was coded without reference to other frames
 * @param sideData the display information the stream signals for the frame, {@link FrameSideData#NONE}
 * when it signals none
 */
public record VideoFrame(Picture picture, long pts, long duration, boolean keyframe, FrameSideData sideData) {
    /**
     * Validates the frame.
     *
     * @throws NullPointerException     if {@code picture} or {@code sideData} is {@code null}
     * @throws IllegalArgumentException if {@code duration} is negative and not
     * {@link Packet#UNKNOWN_DURATION}
     */
    public VideoFrame {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(sideData, "sideData");
        if (duration < 0 && duration != Packet.UNKNOWN_DURATION) {
            throw new IllegalArgumentException("negative frame duration " + duration);
        }
    }

    /**
     * Constructs a frame without display information.
     *
     * @param picture  the pixels
     * @param pts      the presentation timestamp
     * @param duration the presentation duration, zero or more, or {@link Packet#UNKNOWN_DURATION} when unknown
     * @param keyframe whether the frame was coded without reference to other frames
     * @throws NullPointerException     if {@code picture} is {@code null}
     * @throws IllegalArgumentException if {@code duration} is negative and not
     * {@link Packet#UNKNOWN_DURATION}
     */
    public VideoFrame(Picture picture, long pts, long duration, boolean keyframe) {
        this(picture, pts, duration, keyframe, FrameSideData.NONE);
    }
}
