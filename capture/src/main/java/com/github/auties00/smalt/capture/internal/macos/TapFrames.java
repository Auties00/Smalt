package com.github.auties00.smalt.capture.internal.macos;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.process.ChannelMixer;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Converts the {@code AudioBufferList} of 32-bit floats a process tap delivers to interleaved frames
 * of the session's channel count.
 *
 * <p>The tap's buffers may be interleaved (one buffer of all channels) or planar (one buffer per
 * channel), so the channels are found by walking the buffers. A tap whose channel count differs from
 * the session's goes through a {@link ChannelMixer} between the default layouts of the two counts.
 * An instance belongs to one thread and allocates only when a block is longer than any before.
 */
@SuppressWarnings("restricted")
final class TapFrames {
    /**
     * The size of the header of {@code AudioBufferList}, up to its first {@code AudioBuffer}.
     */
    private static final long LIST_HEADER = 8;

    /**
     * The size of {@code AudioBuffer}.
     */
    private static final long BUFFER_SIZE = 16;

    /**
     * The offset of {@code mDataByteSize} in {@code AudioBuffer}.
     */
    private static final long BUFFER_BYTES = 4;

    /**
     * The offset of {@code mData} in {@code AudioBuffer}.
     */
    private static final long BUFFER_DATA = 8;

    /**
     * The most buffers read from a list, a guard against a corrupt count.
     */
    private static final int MAX_BUFFERS = 256;

    /**
     * The channel count of the session.
     */
    private final int targetChannels;

    /**
     * The mixer, or {@code null} when the tap has the session's channel count.
     */
    private final ChannelMixer mixer;

    /**
     * The tap's channels of the current block.
     */
    private final float[][] source;

    /**
     * The session's channels of the current block; {@link #source} without a mixer.
     */
    private final float[][] target;

    /**
     * The interleaved frames of the current block.
     */
    private float[] output = new float[0];

    /**
     * Creates a converter.
     *
     * @param sourceChannels the channel count of the tap
     * @param targetChannels the channel count of the session
     */
    TapFrames(int sourceChannels, int targetChannels) {
        this.targetChannels = targetChannels;
        this.mixer = sourceChannels == targetChannels ? null
                : new ChannelMixer(ChannelLayout.defaultFor(sourceChannels), ChannelLayout.defaultFor(targetChannels));
        this.source = new float[sourceChannels][0];
        this.target = mixer == null ? source : new float[targetChannels][0];
    }

    /**
     * Converts one block.
     *
     * @param list the {@code AudioBufferList}, or the null pointer CoreAudio may pass when there is
     * no input
     * @return the number of frames now in {@link #output()}; {@code 0} for a list without audio. A
     * channel the list lacks is silent, and buffers beyond the tap's channels are ignored.
     */
    int convert(MemorySegment list) {
        if (list.address() == 0) {
            return 0;
        }
        var buffers = Math.min(list.reinterpret(LIST_HEADER).get(JAVA_INT, 0), MAX_BUFFERS);
        if (buffers <= 0) {
            return 0;
        }
        var whole = list.reinterpret(LIST_HEADER + buffers * BUFFER_SIZE);
        var frames = frames(whole, buffers);
        if (frames == 0) {
            return 0;
        }
        grow(source, frames);
        var channel = 0;
        for (var i = 0; i < buffers && channel < source.length; i++) {
            var offset = LIST_HEADER + i * BUFFER_SIZE;
            var channels = whole.get(JAVA_INT, offset);
            var data = whole.get(ADDRESS, offset + BUFFER_DATA);
            if (channels <= 0) {
                continue;
            }
            if (data.address() == 0) {
                channel = silence(channel, channels, frames);
                continue;
            }
            var samples = data.reinterpret(frames * 4L * channels);
            for (var c = 0; c < channels && channel < source.length; c++, channel++) {
                var plane = source[channel];
                for (var f = 0; f < frames; f++) {
                    plane[f] = samples.getAtIndex(JAVA_FLOAT, (long) f * channels + c);
                }
            }
        }
        silence(channel, source.length - channel, frames);
        if (mixer != null) {
            grow(target, frames);
            mixer.process(source, target, frames);
        }
        interleave(frames);
        return frames;
    }

    /**
     * {@return the interleaved frames of the last block, owned by this instance}
     */
    float[] output() {
        return output;
    }

    /**
     * Returns the frames every buffer holds.
     *
     * @param list the list, with its buffers in bounds
     * @param buffers the number of buffers
     * @return the smallest frame count of the buffers with data
     */
    private static int frames(MemorySegment list, int buffers) {
        var frames = Integer.MAX_VALUE;
        for (var i = 0; i < buffers; i++) {
            var offset = LIST_HEADER + i * BUFFER_SIZE;
            var channels = list.get(JAVA_INT, offset);
            if (channels > 0 && list.get(ADDRESS, offset + BUFFER_DATA).address() != 0) {
                frames = Math.min(frames, Integer.divideUnsigned(list.get(JAVA_INT, offset + BUFFER_BYTES), 4 * channels));
            }
        }
        return frames == Integer.MAX_VALUE ? 0 : frames;
    }

    /**
     * Silences channels of the current block.
     *
     * @param first the first channel
     * @param count the number of channels; those beyond the tap's are ignored
     * @param frames the frames of the block
     * @return the channel after the last silenced one
     */
    private int silence(int first, int count, int frames) {
        var end = Math.min(source.length, first + count);
        for (var channel = first; channel < end; channel++) {
            Arrays.fill(source[channel], 0, frames, 0f);
        }
        return end;
    }

    /**
     * Writes the session's channels of the current block to {@link #output}.
     *
     * @param frames the frames of the block
     */
    private void interleave(int frames) {
        var count = frames * targetChannels;
        if (output.length < count) {
            output = new float[count];
        }
        for (var channel = 0; channel < targetChannels; channel++) {
            var plane = target[channel];
            for (int f = 0, i = channel; f < frames; f++, i += targetChannels) {
                output[i] = plane[f];
            }
        }
    }

    /**
     * Makes planes hold at least a number of frames.
     *
     * @param planes one array per channel, replaced when too short
     * @param frames the frames each array must hold
     */
    private static void grow(float[][] planes, int frames) {
        for (var i = 0; i < planes.length; i++) {
            if (planes[i].length < frames) {
                planes[i] = new float[frames];
            }
        }
    }
}
