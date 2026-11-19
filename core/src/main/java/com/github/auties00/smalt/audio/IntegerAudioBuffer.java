package com.github.auties00.smalt.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a block of planar signed integer audio samples of a known sample size.
 *
 * <p>Channel {@code c} is {@code plane(c)}; its first {@link #frames()} elements are valid, the rest of the array is
 * spare capacity. A sample of {@link #bitsPerSample()} bits {@code n} lies in {@code [-2^(n-1), 2^(n-1) - 1]}, and
 * stands for the float sample {@code value * 2^-(n-1)} of an {@link AudioBuffer}: decoders that offer both outputs
 * produce floats that equal their integers scaled that way, rounded to the nearest float above 24 bits. Unsigned
 * sources are offset to signed values ({@code 0x80} of unsigned 8-bit samples is zero). A buffer does not check
 * that its samples fit the sample size; producers guarantee it. Decoders reuse one buffer across calls, so a buffer
 * handed to a consumer is valid only until the consumer returns; {@link #copy()} keeps its samples.
 *
 * <p>Instances are not thread-safe.
 */
public final class IntegerAudioBuffer {
    /**
     * The speaker fed by each channel.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The sample size in bits.
     */
    private final int bitsPerSample;

    /**
     * The per-channel sample arrays, all of the same length.
     */
    private final int[][] planes;

    /**
     * The number of valid samples in each plane.
     */
    private int frames;

    /**
     * Constructs an empty buffer.
     *
     * @param layout the channel layout
     * @param sampleRate the sampling rate in hertz, positive
     * @param bitsPerSample the sample size, from 1 to 32
     * @param capacity the number of samples each plane can hold, non-negative
     * @throws IllegalArgumentException if {@code sampleRate} is not positive, {@code bitsPerSample} is out of range
     * or {@code capacity} is negative
     */
    public IntegerAudioBuffer(ChannelLayout layout, int sampleRate, int bitsPerSample, int capacity) {
        this(layout, sampleRate, bitsPerSample, allocate(layout, capacity), 0);
    }

    /**
     * Constructs a buffer over existing planes without copying them.
     *
     * @param layout the channel layout
     * @param sampleRate the sampling rate in hertz, positive
     * @param bitsPerSample the sample size, from 1 to 32
     * @param planes one array per channel, all of the same length
     * @param frames the number of valid samples per plane, at most the plane length
     * @throws NullPointerException if a plane is {@code null}
     * @throws IllegalArgumentException if the plane count differs from the layout's channel count, the planes differ
     * in length, {@code sampleRate} is not positive, {@code bitsPerSample} is out of range or {@code frames} is out of
     * range
     */
    public IntegerAudioBuffer(ChannelLayout layout, int sampleRate, int bitsPerSample, int[][] planes, int frames) {
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(planes, "planes");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
        }
        if (bitsPerSample < 1 || bitsPerSample > 32) {
            throw new IllegalArgumentException("sample size " + bitsPerSample + " is outside 1..32");
        }
        if (planes.length != layout.channels()) {
            throw new IllegalArgumentException(planes.length + " planes for a " + layout.channels() + "-channel layout");
        }
        var length = Objects.requireNonNull(planes[0], "planes[0]").length;
        for (var plane : planes) {
            if (Objects.requireNonNull(plane, "plane").length != length) {
                throw new IllegalArgumentException("planes differ in length");
            }
        }
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.planes = planes;
        setFrames(frames);
    }

    /**
     * Allocates the planes of a buffer.
     *
     * @param layout the channel layout
     * @param capacity the per-plane capacity
     * @return the planes
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    private static int[][] allocate(ChannelLayout layout, int capacity) {
        Objects.requireNonNull(layout, "layout");
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity " + capacity);
        }
        return new int[layout.channels()][capacity];
    }

    /**
     * {@return the speaker fed by each channel}
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return planes.length;
    }

    /**
     * {@return the sampling rate in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the sample size in bits, from 1 to 32}
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * {@return the number of samples each plane can hold}
     */
    public int capacity() {
        return planes[0].length;
    }

    /**
     * {@return the number of valid samples in each plane}
     */
    public int frames() {
        return frames;
    }

    /**
     * Sets the number of valid samples in each plane.
     *
     * @param frames the frame count, between zero and {@link #capacity()}
     * @throws IllegalArgumentException if {@code frames} is out of range
     */
    public void setFrames(int frames) {
        if (frames < 0 || frames > planes[0].length) {
            throw new IllegalArgumentException("frame count " + frames + " is outside 0.." + planes[0].length);
        }
        this.frames = frames;
    }

    /**
     * Returns the sample array of one channel, not a copy.
     *
     * @param channel the channel index
     * @return the samples
     * @throws IndexOutOfBoundsException if {@code channel} is out of range
     */
    public int[] plane(int channel) {
        return planes[channel];
    }

    /**
     * {@return the sample arrays of every channel, indexed by channel, not copies}
     */
    public int[][] planes() {
        return planes;
    }

    /**
     * Returns a copy of the valid samples, in a new buffer of the same layout, rate and sample size whose planes
     * hold exactly {@link #frames()} samples.
     *
     * @return the copy, which shares no array with this buffer
     */
    public IntegerAudioBuffer copy() {
        return copy(frames);
    }

    /**
     * Returns a copy of the first valid samples, in a new buffer of the same layout, rate and sample size whose
     * planes hold exactly {@code frames} samples.
     *
     * @param frames the number of samples per plane to copy, between zero and {@link #frames()}
     * @return the copy, which shares no array with this buffer
     * @throws IllegalArgumentException if {@code frames} is out of range
     */
    public IntegerAudioBuffer copy(int frames) {
        if (frames < 0 || frames > this.frames) {
            throw new IllegalArgumentException("frame count " + frames + " is outside 0.." + this.frames);
        }
        var copy = new int[planes.length][];
        for (var channel = 0; channel < planes.length; channel++) {
            copy[channel] = Arrays.copyOf(planes[channel], frames);
        }
        return new IntegerAudioBuffer(layout, sampleRate, bitsPerSample, copy, frames);
    }

    /**
     * Returns the valid samples as floats, each scaled by {@code 2^-(bitsPerSample - 1)} and rounded to the nearest
     * float, in a new buffer of the same layout and rate.
     *
     * @return the float samples, exact for sample sizes of up to 24 bits
     */
    public AudioBuffer toFloat() {
        var scale = Math.scalb(1.0, 1 - bitsPerSample);
        var result = new AudioBuffer(layout, sampleRate, frames);
        for (var channel = 0; channel < planes.length; channel++) {
            var source = planes[channel];
            var target = result.plane(channel);
            for (var i = 0; i < frames; i++) {
                target[i] = (float) (source[i] * scale);
            }
        }
        result.setFrames(frames);
        return result;
    }

    /**
     * {@return the duration of the valid samples in microseconds, rounded down}
     */
    public long durationMicros() {
        return frames * 1_000_000L / sampleRate;
    }
}
