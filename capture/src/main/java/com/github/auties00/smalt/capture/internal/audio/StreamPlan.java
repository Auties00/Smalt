package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.capture.audio.AudioStreamOptions;

import java.time.Duration;

/**
 * Holds the geometry of a stream once its options are resolved against the device: the caller's
 * format, the period, the session's queue and the latency asked of the device.
 *
 * @param sampleRate the caller's sampling rate in hertz
 * @param channels the caller's channel count
 * @param periodFrames the frames of one period at {@code sampleRate}; positive
 * @param bufferFrames the capacity of the session's queue, at least two periods
 * @param latencyNanos the device buffer to ask for, at least one period, in nanoseconds
 */
public record StreamPlan(int sampleRate, int channels, int periodFrames, int bufferFrames, long latencyNanos) {
    /**
     * Resolves options against the device's own format.
     *
     * @param nativeRate the device's sampling rate, used when the options leave it open
     * @param nativeChannels the device's channel count, used when the options leave it open
     * @return the plan
     */
    public static StreamPlan resolve(AudioStreamOptions options, int nativeRate, int nativeChannels) {
        var rate = options.sampleRate().orElse(nativeRate);
        var channels = options.channels().orElse(nativeChannels);
        var period = Math.max(1, frames(options.period(), rate));
        var buffer = Math.max(2 * period, frames(options.bufferDuration(), rate));
        var latency = Math.max(options.period().toNanos(), options.latency().toNanos());
        return new StreamPlan(rate, channels, period, buffer, latency);
    }

    /**
     * Converts a duration to a frame count, rounding to the nearest frame.
     *
     * @param rate the sampling rate in hertz
     * @return the frames
     */
    public static int frames(Duration duration, int rate) {
        return (int) Math.min(Integer.MAX_VALUE / 64, (duration.toNanos() * rate + 500_000_000L) / 1_000_000_000L);
    }

    /**
     * Returns the duration of a number of frames at this plan's rate.
     *
     * @return the duration in nanoseconds
     */
    public long nanos(long frames) {
        return frames * 1_000_000_000L / sampleRate;
    }
}
