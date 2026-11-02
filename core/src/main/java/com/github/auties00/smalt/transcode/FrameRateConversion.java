package com.github.auties00.smalt.transcode;

/**
 * Enumerates how the frames of a stream are mapped to the ticks of a constant output frame rate.
 *
 * <p>Output frame {@code k} is shown at tick {@code k / rate}, counted from the start of the kept
 * range.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum FrameRateConversion {
    /**
     * Shows at each tick the last input frame presented at or before it: frames are dropped when the
     * input is faster and repeated when it is slower, and every output frame is an input frame.
     */
    DROP_REPEAT,

    /**
     * Shows at each tick a blend of the input frames presented at or before it and after it, each
     * weighted by its closeness in time, {@code (1 - w) * before + w * after} with
     * {@code w = (tick - before) / (after - before)} quantized to 1/256: motion is smoother at the
     * cost of ghosting. A tick that falls on an input frame, and every tick after the last frame,
     * shows that frame unchanged.
     */
    BLEND
}
