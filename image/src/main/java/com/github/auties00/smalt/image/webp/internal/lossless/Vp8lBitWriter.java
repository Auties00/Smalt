package com.github.auties00.smalt.image.webp.internal.lossless;

import java.util.Arrays;

/**
 * Writes a least-significant-bit-first bit stream for the lossless encoder, with the cheap
 * snapshot, rewind and swap operations the encoder needs to try several codings and keep the
 * smallest.
 *
 * <p>Bits accumulate in a 64-bit word and are flushed 32 bits at a time.
 */
public final class Vp8lBitWriter {
    /**
     * The completed bytes.
     */
    private byte[] buffer;

    /**
     * The number of completed bytes.
     */
    private int size;

    /**
     * The pending bits, bit 0 first.
     */
    private long bits;

    /**
     * The number of pending bits, below 32 between calls.
     */
    private int used;

    /**
     * Constructs a writer.
     *
     * @param capacity the initial capacity in bytes
     */
    public Vp8lBitWriter(int capacity) {
        this.buffer = new byte[Math.max(capacity, 64)];
    }

    /**
     * Writes the low {@code nBits} bits of a value.
     *
     * @param value the value; bits above {@code nBits} are ignored
     * @param nBits the number of bits, from 0 to 32
     */
    public void putBits(int value, int nBits) {
        if (nBits > 0) {
            if (used >= 32) {
                flush32();
            }
            bits |= (value & ((1L << nBits) - 1)) << used;
            used += nBits;
        }
    }

    /**
     * Moves 32 pending bits to the byte buffer.
     */
    private void flush32() {
        if (size + 4 > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + 1024));
        }
        var b = bits;
        buffer[size] = (byte) b;
        buffer[size + 1] = (byte) (b >>> 8);
        buffer[size + 2] = (byte) (b >>> 16);
        buffer[size + 3] = (byte) (b >>> 24);
        size += 4;
        bits >>>= 32;
        used -= 32;
    }

    /**
     * Returns the number of bytes written, counting a partial last byte.
     *
     * @return the byte count
     */
    public long numBytes() {
        return size + ((used + 7) >> 3);
    }

    /**
     * Returns the written bytes, padding a partial last byte with zeros.
     *
     * @return a copy of the stream
     */
    public byte[] toByteArray() {
        var out = Arrays.copyOf(buffer, (int) numBytes());
        var b = bits;
        for (var i = size; i < out.length; i++) {
            out[i] = (byte) b;
            b >>>= 8;
        }
        return out;
    }

    /**
     * Captures the writer state so it can be restored with {@link #reset(Mark)}.
     *
     * @return the state
     */
    public Mark mark() {
        return new Mark(size, bits, used);
    }

    /**
     * Rewinds the writer to a captured state; bytes written since are discarded.
     *
     * @param mark the state captured by {@link #mark()} on this writer, or on a writer whose
     *             content up to the mark is identical
     */
    public void reset(Mark mark) {
        size = mark.size();
        bits = mark.bits();
        used = mark.used();
    }

    /**
     * Copies another writer's content and state into this one.
     *
     * @param source the writer to copy
     */
    public void copyFrom(Vp8lBitWriter source) {
        if (buffer.length < source.size) {
            buffer = new byte[source.buffer.length];
        }
        System.arraycopy(source.buffer, 0, buffer, 0, source.size);
        size = source.size;
        bits = source.bits;
        used = source.used;
    }

    /**
     * Exchanges the content and state of two writers.
     *
     * @param other the other writer
     */
    public void swap(Vp8lBitWriter other) {
        var buffer = this.buffer;
        var size = this.size;
        var bits = this.bits;
        var used = this.used;
        this.buffer = other.buffer;
        this.size = other.size;
        this.bits = other.bits;
        this.used = other.used;
        other.buffer = buffer;
        other.size = size;
        other.bits = bits;
        other.used = used;
    }

    /**
     * Holds a captured writer state.
     *
     * @param size the number of completed bytes
     * @param bits the pending bits
     * @param used the number of pending bits
     */
    public record Mark(int size, long bits, int used) {
    }
}
