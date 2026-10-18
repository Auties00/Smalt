package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;

import java.util.Objects;

/**
 * Assigns the timestamps of the frames a video encoder codes: a frame without a timestamp follows
 * the previous one by the nominal frame duration, a frame without a duration lasts the nominal
 * duration, and timestamps must increase.
 *
 * <p>{@link #resolve} computes a frame's timing without changing the clock, so a frame whose
 * encoding fails leaves the clock as it was; {@link #commit} records the timestamp once the frame
 * is coded (or dropped by the rate control). Instances are not thread-safe.
 */
public final class FrameClock {
    /**
     * The time base of the timestamps.
     */
    private final Rational timeBase;

    /**
     * The nominal frame rate.
     */
    private Rational frameRate;

    /**
     * The timestamp of the last committed frame, or {@link Packet#NO_TIMESTAMP}.
     */
    private long lastPts = Packet.NO_TIMESTAMP;

    /**
     * Constructs a clock that has seen no frame.
     *
     * @param frameRate the nominal frame rate, which gives the default frame duration
     * @param timeBase the time base of the timestamps
     */
    public FrameClock(Rational frameRate, Rational timeBase) {
        this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
        this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
    }

    /**
     * The timestamp and duration of one frame.
     *
     * @param pts the presentation timestamp, in the time base
     * @param duration the duration, in the time base, positive
     */
    public record Timing(long pts, long duration) {
    }

    /**
     * Checks the frame rate and time base of encoder options.
     *
     * @param frameRate the nominal frame rate
     * @param timeBase the time base of the timestamps
     * @throws IllegalArgumentException if {@code frameRate} is outside 0.1 to 1000 frames per
     * second, or {@code timeBase} has a numerator or denominator that is not positive
     */
    public static void checkRates(Rational frameRate, Rational timeBase) {
        if (!(frameRate.toDouble() >= 0.1 && frameRate.toDouble() <= 1000)) {
            throw new IllegalArgumentException("unsupported frame rate " + frameRate);
        }
        if (timeBase.numerator() <= 0 || timeBase.denominator() <= 0) {
            throw new IllegalArgumentException("invalid time base " + timeBase);
        }
    }

    /**
     * Returns the nominal duration of one frame.
     *
     * @param frameRate the nominal frame rate
     * @param timeBase the time base of the duration
     * @return {@code round(1 / (frameRate * timeBase))}, at least 1
     */
    public static long nominalDuration(Rational frameRate, Rational timeBase) {
        return Math.max(1, Math.round(1.0 / (frameRate.toDouble() * timeBase.toDouble())));
    }

    /**
     * {@return the nominal duration of one frame at the current frame rate, at least 1}
     */
    public long nominalDuration() {
        return nominalDuration(frameRate, timeBase);
    }

    /**
     * Changes the nominal frame rate, as a reconfigured encoder does; committed timestamps stay.
     *
     * @param frameRate the new nominal frame rate
     */
    public void setFrameRate(Rational frameRate) {
        this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
    }

    /**
     * Computes the timing of the next frame without changing the clock.
     *
     * @param frame the frame about to be coded
     * @return the frame's timestamp, or the previous one plus the nominal duration (0 for the
     * first frame) when it has none, and its duration, or the nominal one when it has none
     * @throws IllegalArgumentException if the frame's timestamp does not exceed the last
     * committed one
     */
    public Timing resolve(VideoFrame frame) {
        return resolve(frame.pts(), frame.duration());
    }

    /**
     * Computes the timing of the next frame from its fields without changing the clock.
     *
     * @param pts the frame's timestamp, or {@link Packet#NO_TIMESTAMP}
     * @param duration the frame's duration, or zero or a negative value for none
     * @return the resolved timing, as {@link #resolve(VideoFrame)} describes
     * @throws IllegalArgumentException if {@code pts} does not exceed the last committed
     * timestamp
     */
    public Timing resolve(long pts, long duration) {
        var nominal = nominalDuration();
        if (pts == Packet.NO_TIMESTAMP) {
            pts = lastPts == Packet.NO_TIMESTAMP ? 0 : lastPts + nominal;
        } else if (lastPts != Packet.NO_TIMESTAMP && pts <= lastPts) {
            throw new IllegalArgumentException("timestamp " + pts + " does not follow " + lastPts);
        }
        return new Timing(pts, duration > 0 ? duration : nominal);
    }

    /**
     * Records the timestamp of a frame the encoder accepted.
     *
     * @param pts the resolved timestamp
     */
    public void commit(long pts) {
        lastPts = pts;
    }

    /**
     * {@return the timestamp of the last committed frame, or {@link Packet#NO_TIMESTAMP} before
     * the first}
     */
    public long lastPts() {
        return lastPts;
    }

    /**
     * {@return the time base of the timestamps}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * Returns where a frame starts on an encoder's internal clock.
     *
     * @param timing the frame's timing
     * @param ticks the time base of the internal clock
     * @return the start, rescaled to {@code ticks}
     */
    public long startTicks(Timing timing, Rational ticks) {
        return timeBase.rescale(timing.pts(), ticks);
    }

    /**
     * Returns where a frame ends on an encoder's internal clock.
     *
     * @param timing the frame's timing
     * @param ticks the time base of the internal clock
     * @return the end, rescaled to {@code ticks}, at least one tick after {@link #startTicks}
     */
    public long endTicks(Timing timing, Rational ticks) {
        return Math.max(startTicks(timing, ticks) + 1, timeBase.rescale(timing.pts() + timing.duration(), ticks));
    }
}
