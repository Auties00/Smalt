package com.github.auties00.smalt.audio.process;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Filters planar audio through a cascade of {@link Biquad} sections, in place.
 *
 * <p>Every channel runs the sections in list order with its own state, in the transposed direct form II with 64-bit
 * floating-point state, so the output of a block does not depend on how the stream is split into blocks. The filter
 * has no delay: each output sample corresponds to the input sample at the same position. {@link #reset()} clears the
 * state, as if the stream started again.
 *
 * <p>Instances are not thread-safe.
 */
public final class BiquadFilter {
    /**
     * The coefficients of every section, {@code b0, b1, b2, a1, a2} per section.
     */
    private final double[] coefficients;

    /**
     * The two state values of every section of every channel, channel-major.
     */
    private final double[] state;

    private final int channels;
    private final int sampleRate;
    private final List<Biquad> sections;

    /**
     * Constructs a filter.
     *
     * @param sections the sections, run in order; empty for a filter that leaves the samples unchanged
     * @param channels the number of channels, positive
     * @param sampleRate the sampling rate in hertz, more than twice every section's frequency
     * @throws NullPointerException if a section is {@code null}
     * @throws IllegalArgumentException if the channel count is not positive, or a section's frequency is not below
     * half the sampling rate
     */
    public BiquadFilter(List<Biquad> sections, int channels, int sampleRate) {
        this.sections = List.copyOf(sections);
        if (channels <= 0) {
            throw new IllegalArgumentException("channel count " + channels + " is not positive");
        }
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.coefficients = new double[5 * this.sections.size()];
        for (var index = 0; index < this.sections.size(); index++) {
            System.arraycopy(this.sections.get(index).coefficients(sampleRate), 0, coefficients, 5 * index, 5);
        }
        this.state = new double[2 * this.sections.size() * channels];
    }

    /**
     * {@return the sections, in the order they run}
     */
    public List<Biquad> sections() {
        return sections;
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the sampling rate in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Filters a block of every channel in place.
     *
     * @param planes the samples, one plane per channel
     * @param offset the index in each plane of the first sample
     * @param frames the number of samples per channel
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()}
     * @throws IndexOutOfBoundsException if a plane is shorter than {@code offset + frames}
     */
    public void process(float[][] planes, int offset, int frames) {
        Objects.requireNonNull(planes, "planes");
        if (planes.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + planes.length);
        }
        for (var channel = 0; channel < channels; channel++) {
            Objects.checkFromIndexSize(offset, frames, planes[channel].length);
        }
        var count = sections.size();
        for (var channel = 0; channel < channels; channel++) {
            var plane = planes[channel];
            for (var section = 0; section < count; section++) {
                var c = 5 * section;
                var b0 = coefficients[c];
                var b1 = coefficients[c + 1];
                var b2 = coefficients[c + 2];
                var a1 = coefficients[c + 3];
                var a2 = coefficients[c + 4];
                var s = 2 * (channel * count + section);
                var z1 = state[s];
                var z2 = state[s + 1];
                for (var index = offset; index < offset + frames; index++) {
                    double x = plane[index];
                    var y = b0 * x + z1;
                    z1 = b1 * x - a1 * y + z2;
                    z2 = b2 * x - a2 * y;
                    plane[index] = (float) y;
                }
                // a decaying state below the float range is flushed, so silence does not run on subnormals
                state[s] = Math.abs(z1) < 1e-30 ? 0 : z1;
                state[s + 1] = Math.abs(z2) < 1e-30 ? 0 : z2;
            }
        }
    }

    /**
     * Clears the state of every section, so the next block filters as the start of a stream.
     */
    public void reset() {
        Arrays.fill(state, 0);
    }
}
