package com.github.auties00.smalt.audio.voice.internal.aecm;

import java.util.Arrays;

/**
 * Buffers 16-bit samples in a fixed-capacity ring, with the read and write semantics of WebRTC's
 * ring buffer.
 *
 * <p>The ring keeps its storage between reads, so moving the read position backwards
 * ({@link #moveReadPointer(int)} with a negative count) makes already consumed or zero samples
 * readable again, which the mobile echo canceller relies on to pad its output and its far-end
 * stream. Writes beyond the free space and reads beyond the available samples are truncated.
 *
 * @implNote This implementation ports {@code common_audio/ring_buffer.c} of
 * webrtc-audio-processing 2.1 for 16-bit elements, including its corner cases: the read and
 * write positions may rest at the capacity rather than at zero, and the wrap indicator decides
 * how the available samples are counted. Reads always copy into the caller's array; the
 * reference may instead return a pointer into its storage, which yields the same samples.
 */
final class AecmRingBuffer {
    /**
     * The storage of the ring.
     */
    private final short[] data;

    /**
     * The number of samples the ring holds at most.
     */
    private final int capacity;

    /**
     * The index of the next sample to read.
     */
    private int readPosition;

    /**
     * The index of the next sample to write.
     */
    private int writePosition;

    /**
     * Whether the write position has wrapped around once more than the read position.
     */
    private boolean differentWrap;

    /**
     * Creates an empty ring ({@code WebRtc_CreateBuffer}).
     *
     * @param capacity the number of samples the ring holds at most
     */
    AecmRingBuffer(int capacity) {
        this.capacity = capacity;
        this.data = new short[capacity];
    }

    /**
     * Empties the ring and zeroes its storage ({@code WebRtc_InitBuffer}).
     */
    void init() {
        readPosition = 0;
        writePosition = 0;
        differentWrap = false;
        Arrays.fill(data, (short) 0);
    }

    /**
     * Returns the number of samples available to read ({@code WebRtc_available_read}).
     *
     * @return the number of readable samples
     */
    int availableRead() {
        return differentWrap ? capacity - readPosition + writePosition : writePosition - readPosition;
    }

    /**
     * Returns the number of samples that can be written ({@code WebRtc_available_write}).
     *
     * @return the free space
     */
    int availableWrite() {
        return capacity - availableRead();
    }

    /**
     * Reads samples into an array and advances the read position ({@code WebRtc_ReadBuffer}).
     *
     * <p>When fewer samples are available than requested, only the available ones are read;
     * the rest of the destination receives the samples that follow in storage up to its end,
     * as a pointer into the reference's storage would expose them.
     *
     * @param out    the destination
     * @param offset the index of the first destination sample
     * @param count  the number of samples requested
     * @return the number of samples read
     */
    int read(short[] out, int offset, int count) {
        var readable = availableRead();
        var readCount = Math.min(readable, count);
        var margin = capacity - readPosition;
        if (readCount > margin) {
            System.arraycopy(data, readPosition, out, offset, margin);
            System.arraycopy(data, 0, out, offset + margin, readCount - margin);
        } else {
            System.arraycopy(data, readPosition, out, offset, Math.min(count, margin));
        }
        moveReadPointer(readCount);
        return readCount;
    }

    /**
     * Writes samples and advances the write position ({@code WebRtc_WriteBuffer}).
     *
     * @param in     the source
     * @param offset the index of the first source sample
     * @param count  the number of samples to write
     * @return the number of samples written, at most the free space
     */
    int write(short[] in, int offset, int count) {
        var writeCount = Math.min(availableWrite(), count);
        var n = writeCount;
        var margin = capacity - writePosition;
        if (writeCount > margin) {
            System.arraycopy(in, offset, data, writePosition, margin);
            writePosition = 0;
            n -= margin;
            differentWrap = true;
        }
        System.arraycopy(in, offset + writeCount - n, data, writePosition, n);
        writePosition += n;
        return writeCount;
    }

    /**
     * Moves the read position forwards or, for a negative count, backwards
     * ({@code WebRtc_MoveReadPtr}).
     *
     * @param count the number of samples to skip, negative to step back
     * @return the number of samples actually moved, limited by the readable samples forwards and
     *         by the free space backwards
     */
    int moveReadPointer(int count) {
        var free = availableWrite();
        var readable = availableRead();
        var position = readPosition;
        if (count > readable) {
            count = readable;
        }
        if (count < -free) {
            count = -free;
        }
        position += count;
        if (position > capacity) {
            position -= capacity;
            differentWrap = false;
        }
        if (position < 0) {
            position += capacity;
            differentWrap = true;
        }
        readPosition = position;
        return count;
    }
}
