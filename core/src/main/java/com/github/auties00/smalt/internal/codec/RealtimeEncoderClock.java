package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the state that the real-time video encoders keep around their coding engine: the
 * {@link FrameClock}, the key frame request latch and the counters their statistics report.
 *
 * <p>A coding call resolves the frame's timing, takes the key frame request, runs the engine, and
 * then reports the outcome with {@link #coded} or {@link #dropped}, which commit the timestamp. An
 * engine that delays its output commits each timestamp when it accepts the frame and reports each
 * frame it emits later with {@link #delivered}, which leaves the clock as it is.
 *
 * <p>{@link #requestKeyframe()} may be called from any thread at any time; a request is consumed
 * by exactly one {@link #takeKeyframe(boolean)}, so it is never lost between two frames. The other
 * methods belong to the coding thread. Instances are otherwise not thread-safe.
 */
public final class RealtimeEncoderClock {
    /**
     * The timestamps of the coded frames.
     */
    private final FrameClock clock;

    /**
     * Whether a key frame was requested and not yet taken.
     */
    private final AtomicBoolean keyframeRequested = new AtomicBoolean();

    /**
     * The number of coded frames.
     */
    private long framesEncoded;

    /**
     * The number of coded key frames.
     */
    private long keyFramesEncoded;

    /**
     * The number of frames the rate control dropped.
     */
    private long framesDropped;

    /**
     * The total size of the coded frames, in bytes.
     */
    private long bytesEncoded;

    /**
     * The size of the last coded frame, in bytes.
     */
    private int lastFrameBytes;

    /**
     * Constructs the state of an encoder that has coded nothing.
     *
     * @param frameRate the nominal frame rate
     * @param timeBase the time base of the timestamps
     */
    public RealtimeEncoderClock(Rational frameRate, Rational timeBase) {
        this.clock = new FrameClock(frameRate, timeBase);
    }

    /**
     * {@return the timestamps of the coded frames}
     */
    public FrameClock clock() {
        return clock;
    }

    /**
     * Makes the next {@link #takeKeyframe(boolean)} return {@code true}; any thread may call this.
     */
    public void requestKeyframe() {
        keyframeRequested.set(true);
    }

    /**
     * Consumes a pending key frame request.
     *
     * @param forced whether the caller forces this frame to be a key frame
     * @return {@code true} when {@code forced} is or a request was pending; the request is
     * consumed either way
     */
    public boolean takeKeyframe(boolean forced) {
        return keyframeRequested.getAndSet(false) | forced;
    }

    /**
     * Records a coded frame and returns its packet.
     *
     * @param data the coded frame
     * @param timing the frame's resolved timing, whose timestamp is committed
     * @param key whether the frame is a key frame
     * @return the packet of track 0, with the frame's timestamp as both presentation and decoding
     * time
     */
    public Packet coded(byte[] data, FrameClock.Timing timing, boolean key) {
        clock.commit(timing.pts());
        return delivered(data, timing, key);
    }

    /**
     * Records a coded frame whose timestamp was committed when the frame was accepted, and returns
     * its packet; the clock is left as it is.
     *
     * @param data the coded frame
     * @param timing the frame's timing
     * @param key whether the frame is a key frame
     * @return the packet of track 0, with the frame's timestamp as both presentation and decoding
     * time
     */
    public Packet delivered(byte[] data, FrameClock.Timing timing, boolean key) {
        framesEncoded++;
        bytesEncoded += data.length;
        lastFrameBytes = data.length;
        if (key) {
            keyFramesEncoded++;
        }
        return new Packet(0, data, timing.pts(), timing.pts(), timing.duration(), key);
    }

    /**
     * Records a frame the rate control dropped.
     *
     * @param timing the frame's resolved timing, whose timestamp is committed
     */
    public void dropped(FrameClock.Timing timing) {
        clock.commit(timing.pts());
        framesDropped++;
    }

    /**
     * {@return the number of coded frames}
     */
    public long framesEncoded() {
        return framesEncoded;
    }

    /**
     * {@return the number of coded key frames}
     */
    public long keyFramesEncoded() {
        return keyFramesEncoded;
    }

    /**
     * {@return the number of frames the rate control dropped}
     */
    public long framesDropped() {
        return framesDropped;
    }

    /**
     * {@return the total size of the coded frames, in bytes}
     */
    public long bytesEncoded() {
        return bytesEncoded;
    }

    /**
     * {@return the size of the last coded frame in bytes, or 0 before the first}
     */
    public int lastFrameBytes() {
        return lastFrameBytes;
    }
}
