package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.audio.process.AudioFifo;

import java.lang.reflect.Array;

/**
 * Queues planar {@code int} or {@code double} samples, as {@link AudioFifo} queues floats, for the exact output
 * formats of {@link AudioStreamDecoder}.
 *
 * <p>Planes passed in and out are {@code int[][]} or {@code double[][]}, matching the element type the queue was
 * created for. Arguments are not checked. Instances are not thread-safe.
 */
final class SampleFifo {
    /**
     * The initial capacity of each plane, in frames.
     */
    private static final int INITIAL_CAPACITY = 1024;

    private final Class<?> elementType;
    private final int maxFrames;

    /**
     * The sample storage, one {@code int[]} or {@code double[]} per channel; the queued frames occupy
     * {@code [head, head + size)}.
     */
    private final Object[] planes;

    private int capacity;
    private int head;
    private int size;

    /**
     * Creates an empty queue bounded by {@link AudioFifo#DEFAULT_MAX_FRAMES}.
     *
     * @param elementType {@code int.class} or {@code double.class}
     * @param channels the number of channels
     */
    SampleFifo(Class<?> elementType, int channels) {
        this.elementType = elementType;
        this.maxFrames = AudioFifo.DEFAULT_MAX_FRAMES;
        this.planes = new Object[channels];
        this.capacity = INITIAL_CAPACITY;
        for (var c = 0; c < channels; c++) {
            planes[c] = Array.newInstance(elementType, capacity);
        }
    }

    /**
     * {@return the number of queued frames}
     */
    int size() {
        return size;
    }

    /**
     * Appends frames copied from planes.
     *
     * @param input the source planes, one per channel
     * @param offset the index of the first frame to copy
     * @param frames the number of frames to copy
     * @throws IllegalStateException if the queue would exceed its bound
     */
    void write(Object[] input, int offset, int frames) {
        reserve(frames);
        for (var c = 0; c < planes.length; c++) {
            System.arraycopy(input[c], offset, planes[c], head + size, frames);
        }
        size += frames;
    }

    /**
     * Removes up to {@code frames} of the oldest frames, copying them into planes.
     *
     * @param output the destination planes, one per channel
     * @param offset the index of the first frame written
     * @param frames the largest number of frames to remove
     * @return the number of frames removed
     */
    int read(Object[] output, int offset, int frames) {
        var count = Math.min(frames, size);
        for (var c = 0; c < planes.length; c++) {
            System.arraycopy(planes[c], head, output[c], offset, count);
        }
        head += count;
        size -= count;
        if (size == 0) {
            head = 0;
        }
        return count;
    }

    /**
     * Makes room for {@code frames} more frames after the queued ones, compacting or growing the planes.
     *
     * @param frames the number of frames about to be appended
     * @throws IllegalStateException if the queue would exceed its bound
     */
    private void reserve(int frames) {
        if ((long) size + frames > maxFrames) {
            throw new IllegalStateException("FIFO of " + size + " frames cannot take " + frames
                    + " more within its " + maxFrames + " frame bound");
        }
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
        for (var c = 0; c < planes.length; c++) {
            var plane = Array.newInstance(elementType, grown);
            System.arraycopy(planes[c], head, plane, 0, size);
            planes[c] = plane;
        }
        capacity = grown;
        head = 0;
    }
}
