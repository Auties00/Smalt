package com.github.auties00.smalt.audio.process;

import com.github.auties00.smalt.audio.AudioBuffer;

import java.util.Objects;

/**
 * Queues planar float samples between a producer and a consumer that work in different block
 * sizes, such as a decoder emitting 1152-sample frames and an encoder consuming 960-sample ones.
 *
 * <p>Frames are read back in the order they were written. The queue grows as needed up to a
 * maximum fixed at construction, so a producer that runs ahead of its consumer cannot exhaust
 * memory: a write that would exceed the maximum fails and leaves the queue unchanged. A FIFO is
 * not thread-safe.
 */
public final class AudioFifo {
    /**
     * The maximum size of a FIFO created without an explicit bound: 2^24 frames, about 5.8
     * minutes at 48 kHz.
     */
    public static final int DEFAULT_MAX_FRAMES = 1 << 24;

    /**
     * The initial capacity of each plane, in frames.
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The largest array length the JVM reliably allocates.
     */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 16;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The largest number of frames the FIFO may hold.
     */
    private final int maxFrames;

    /**
     * The sample storage, one plane per channel; the queued frames occupy
     * {@code [head, head + size)}.
     */
    private float[][] planes;

    /**
     * The index of the oldest queued frame.
     */
    private int head;

    /**
     * The number of queued frames.
     */
    private int size;

    /**
     * Creates an empty FIFO bounded by {@link #DEFAULT_MAX_FRAMES}.
     *
     * @param channels the number of channels; positive
     * @throws IllegalArgumentException if {@code channels} is not positive
     */
    public AudioFifo(int channels) {
        this(channels, DEFAULT_MAX_FRAMES);
    }

    /**
     * Creates an empty FIFO.
     *
     * @param channels  the number of channels; positive
     * @param maxFrames the largest number of frames the FIFO may hold; positive
     * @throws IllegalArgumentException if {@code channels} or {@code maxFrames} is not positive
     */
    public AudioFifo(int channels, int maxFrames) {
        if (channels <= 0) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("invalid maximum size " + maxFrames);
        }
        this.channels = channels;
        this.maxFrames = Math.min(maxFrames, MAX_ARRAY);
        this.planes = new float[channels][Math.min(INITIAL_CAPACITY, this.maxFrames)];
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Returns the number of queued frames.
     *
     * @return the frame count
     */
    public int size() {
        return size;
    }

    /**
     * Returns the largest number of frames the FIFO may hold.
     *
     * @return the bound
     */
    public int maxFrames() {
        return maxFrames;
    }

    /**
     * Returns whether no frame is queued.
     *
     * @return {@code true} when {@link #size()} is zero
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Appends frames copied from the given planes.
     *
     * @param input  the source planes, one per channel
     * @param offset the index of the first frame to copy
     * @param frames the number of frames to copy; non-negative
     * @throws NullPointerException      if an array is {@code null}
     * @throws IllegalArgumentException  if the plane count differs from {@link #channels()}
     * @throws IndexOutOfBoundsException if the range exceeds a source plane
     * @throws IllegalStateException     if the FIFO would exceed {@link #maxFrames()}
     */
    public void write(float[][] input, int offset, int frames) {
        Objects.requireNonNull(input, "input");
        checkPlanes(input.length);
        for (var plane : input) {
            Objects.checkFromIndexSize(offset, frames, Objects.requireNonNull(plane, "plane").length);
        }
        reserve(frames);
        for (var c = 0; c < channels; c++) {
            System.arraycopy(input[c], offset, planes[c], head + size, frames);
        }
        size += frames;
    }

    /**
     * Appends the valid frames of a buffer.
     *
     * @param buffer the source buffer
     * @throws NullPointerException     if {@code buffer} is {@code null}
     * @throws IllegalArgumentException if the buffer's channel count differs from
     *                                  {@link #channels()}
     * @throws IllegalStateException    if the FIFO would exceed {@link #maxFrames()}
     */
    public void write(AudioBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        write(buffer.planes(), 0, buffer.frames());
    }

    /**
     * Removes up to {@code frames} of the oldest frames, copying them into the given planes.
     *
     * @param output the destination planes, one per channel
     * @param offset the index of the first frame written
     * @param frames the largest number of frames to remove; non-negative
     * @return the number of frames removed, {@code min(frames, size())}
     * @throws NullPointerException      if an array is {@code null}
     * @throws IllegalArgumentException  if the plane count differs from {@link #channels()}
     * @throws IndexOutOfBoundsException if a destination plane cannot hold the frames
     */
    public int read(float[][] output, int offset, int frames) {
        var count = peek(output, offset, frames);
        discard(count);
        return count;
    }

    /**
     * Copies up to {@code frames} of the oldest frames into the given planes without removing
     * them.
     *
     * @param output the destination planes, one per channel
     * @param offset the index of the first frame written
     * @param frames the largest number of frames to copy; non-negative
     * @return the number of frames copied, {@code min(frames, size())}
     * @throws NullPointerException      if an array is {@code null}
     * @throws IllegalArgumentException  if the plane count differs from {@link #channels()} or
     *                                   {@code frames} is negative
     * @throws IndexOutOfBoundsException if a destination plane cannot hold the frames
     */
    public int peek(float[][] output, int offset, int frames) {
        Objects.requireNonNull(output, "output");
        checkPlanes(output.length);
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count " + frames);
        }
        var count = Math.min(frames, size);
        for (var plane : output) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(plane, "plane").length);
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(planes[c], head, output[c], offset, count);
        }
        return count;
    }

    /**
     * Removes up to {@code frames} of the oldest frames without copying them.
     *
     * @param frames the largest number of frames to remove; non-negative
     * @return the number of frames removed, {@code min(frames, size())}
     * @throws IllegalArgumentException if {@code frames} is negative
     */
    public int discard(int frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count " + frames);
        }
        var count = Math.min(frames, size);
        head += count;
        size -= count;
        if (size == 0) {
            head = 0;
        }
        return count;
    }

    /**
     * Removes every queued frame.
     */
    public void clear() {
        head = 0;
        size = 0;
    }

    /**
     * Checks a plane count against the channel count.
     *
     * @param count the number of planes supplied
     * @throws IllegalArgumentException if it differs from {@link #channels()}
     */
    private void checkPlanes(int count) {
        if (count != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + count);
        }
    }

    /**
     * Makes room for {@code frames} more frames after the queued ones, compacting or growing the
     * planes.
     *
     * @param frames the number of frames about to be appended
     * @throws IllegalStateException if the FIFO would exceed {@link #maxFrames()}
     */
    private void reserve(int frames) {
        if ((long) size + frames > maxFrames) {
            throw new IllegalStateException("FIFO of " + size + " frames cannot take " + frames
                    + " more within its " + maxFrames + " frame bound");
        }
        var capacity = planes[0].length;
        if (head + size + frames <= capacity) {
            return;
        }
        var needed = size + frames;
        if (needed <= capacity) {
            for (var plane : planes) {
                System.arraycopy(plane, head, plane, 0, size);
            }
            head = 0;
            return;
        }
        var grown = (int) Math.min(maxFrames, Math.max(needed, 2L * capacity));
        for (var c = 0; c < channels; c++) {
            var plane = new float[grown];
            System.arraycopy(planes[c], head, plane, 0, size);
            planes[c] = plane;
        }
        head = 0;
    }
}
