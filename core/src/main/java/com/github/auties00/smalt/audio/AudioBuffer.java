package com.github.auties00.smalt.audio;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a block of planar 32-bit float audio samples.
 *
 * <p>Channel {@code c} is {@code plane(c)}; its first {@link #frames()} elements are valid, the
 * rest of the array is spare capacity. Samples are nominally in {@code [-1, 1]}; values outside
 * that range are legal intermediate results and are clipped only by encoders that need to.
 * A float holds every integer sample of up to 24 bits exactly, so samples decoded from such
 * sources convert back without loss; samples of wider integer sources (32-bit PCM, FLAC or ALAC)
 * are rounded to 24 significant bits, and a decoder's {@link IntegerAudioBuffer} or
 * {@link DoubleAudioBuffer} output keeps them exactly. Decoders reuse one buffer across calls, so a buffer handed
 * to a consumer is valid only until the consumer returns; {@link #copy()} keeps its samples.
 *
 * <p>Instances are not thread-safe; confine a buffer to one thread at a time or synchronize
 * externally.
 */
public final class AudioBuffer {
    /**
     * The speaker fed by each channel.
     */
    private final ChannelLayout layout;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The per-channel sample arrays, all of the same length.
     */
    private final float[][] planes;

    /**
     * The number of valid samples in each plane.
     */
    private int frames;

    /**
     * Allocates an empty buffer.
     *
     * @param layout     the channel layout
     * @param sampleRate the sampling rate in hertz; positive
     * @param capacity   the number of samples each plane can hold; non-negative
     * @throws NullPointerException     if {@code layout} is {@code null}
     * @throws IllegalArgumentException if {@code sampleRate} is not positive or {@code capacity}
     *                                  is negative
     */
    public AudioBuffer(ChannelLayout layout, int sampleRate, int capacity) {
        this(layout, sampleRate, allocate(layout, capacity), 0);
    }

    /**
     * Wraps existing planes without copying them.
     *
     * @param layout     the channel layout
     * @param sampleRate the sampling rate in hertz; positive
     * @param planes     one array per channel, all of the same length
     * @param frames     the number of valid samples per plane; at most the plane length
     * @throws NullPointerException     if {@code layout}, {@code planes}, or a plane is
     *                                  {@code null}
     * @throws IllegalArgumentException if the plane count differs from the layout, the planes
     *                                  differ in length, {@code sampleRate} is not positive, or
     *                                  {@code frames} is out of range
     */
    public AudioBuffer(ChannelLayout layout, int sampleRate, float[][] planes, int frames) {
        this.layout = Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(planes, "planes");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("invalid sample rate " + sampleRate);
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
        this.planes = planes;
        setFrames(frames);
    }

    /**
     * Allocates the planes of a buffer.
     *
     * @param layout   the channel layout
     * @param capacity the per-plane capacity
     * @return the planes
     * @throws NullPointerException     if {@code layout} is {@code null}
     * @throws IllegalArgumentException if {@code capacity} is negative
     */
    private static float[][] allocate(ChannelLayout layout, int capacity) {
        Objects.requireNonNull(layout, "layout");
        if (capacity < 0) {
            throw new IllegalArgumentException("negative capacity " + capacity);
        }
        return new float[layout.channels()][capacity];
    }

    /**
     * Returns the channel layout.
     *
     * @return the layout
     */
    public ChannelLayout layout() {
        return layout;
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count
     */
    public int channels() {
        return planes.length;
    }

    /**
     * Returns the sampling rate.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the number of samples each plane can hold.
     *
     * @return the capacity
     */
    public int capacity() {
        return planes[0].length;
    }

    /**
     * Returns the number of valid samples in each plane.
     *
     * @return the frame count
     */
    public int frames() {
        return frames;
    }

    /**
     * Sets the number of valid samples in each plane.
     *
     * @param frames the frame count; between zero and {@link #capacity()}
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
    public float[] plane(int channel) {
        return planes[channel];
    }

    /**
     * Returns the sample arrays of every channel, not copies.
     *
     * @return the planes, indexed by channel
     */
    public float[][] planes() {
        return planes;
    }

    /**
     * Returns a copy of the valid samples, in a new buffer of the same layout and rate whose planes
     * hold exactly {@link #frames()} samples.
     *
     * @return the copy, which shares no array with this buffer
     */
    public AudioBuffer copy() {
        return copy(frames);
    }

    /**
     * Returns a copy of the first valid samples, in a new buffer of the same layout and rate whose
     * planes hold exactly {@code frames} samples.
     *
     * @param frames the number of samples per plane to copy; between zero and {@link #frames()}
     * @return the copy, which shares no array with this buffer
     * @throws IllegalArgumentException if {@code frames} is out of range
     */
    public AudioBuffer copy(int frames) {
        if (frames < 0 || frames > this.frames) {
            throw new IllegalArgumentException("frame count " + frames + " is outside 0.." + this.frames);
        }
        var copy = new float[planes.length][];
        for (var channel = 0; channel < planes.length; channel++) {
            copy[channel] = Arrays.copyOf(planes[channel], frames);
        }
        return new AudioBuffer(layout, sampleRate, copy, frames);
    }

    /**
     * Returns the duration of the valid samples.
     *
     * @return the duration in microseconds, rounded down
     */
    public long durationMicros() {
        return frames * 1_000_000L / sampleRate;
    }
}
