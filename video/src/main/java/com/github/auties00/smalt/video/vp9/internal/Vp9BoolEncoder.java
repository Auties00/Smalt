package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Encodes booleans with the VP9 boolean entropy coder into a growable buffer.
 *
 * <p>A partition starts with {@link #start()}, which writes the marker bit the decoder checks,
 * and ends with {@link #stop()}, which pads the code so the decoder never reads past it and
 * avoids a final byte that a superframe index could be mistaken for. The bytes of the partition
 * are final only after {@link #stop()}.
 *
 * @implNote This implementation produces the output of libvpx's {@code vpx_writer}
 *           ({@code vpx_dsp/bitwriter.h} and {@code bitwriter.c}, BSD-3) byte for byte: the code
 *           is the binary expansion of the interval's low end, of which libvpx emits a byte as
 *           soon as 24 bits are pending. This implementation keeps up to 54 pending bits in a
 *           {@code long} and emits them 3 bytes at a time, never leaving fewer than 24 bits
 *           pending (so it never emits a byte libvpx would not have emitted by the end of the
 *           partition), propagates a carry into the bytes already emitted the same way, selects
 *           the new interval without a branch, and at {@link #stop()} emits exactly the bytes
 *           libvpx would have emitted by then.
 */
sealed class Vp9BoolEncoder permits Vp9SymbolRecorder {
    /**
     * The number of leading zero bits of each 8-bit range value, used to renormalize the range
     * ({@code vpx_norm}).
     */
    private static final byte[] NORM = norm();

    /**
     * The number of pending bits from which {@link #write} emits 3 bytes.
     */
    private static final int FLUSH_BITS = 48;

    /**
     * The pending low-order bits of the interval's low end, {@link #pending} bits plus a carry
     * into the emitted bytes at bit {@link #pending}.
     */
    private long low;

    /**
     * The width of the coding interval, from 128 to 255 after renormalization.
     */
    private int range;

    /**
     * The number of bits of the interval's low end not yet emitted: the 8 bits of the range plus
     * every renormalization shift, less 8 for each byte emitted.
     */
    private int pending;

    /**
     * The output bytes.
     */
    private byte[] buffer;

    /**
     * The number of bytes written to {@link #buffer}.
     */
    private int pos;

    /**
     * Creates an encoder with an initial capacity.
     *
     * @param capacity the initial buffer size in bytes
     */
    Vp9BoolEncoder(int capacity) {
        buffer = new byte[Math.max(256, capacity)];
    }

    /**
     * Builds the renormalization shift table.
     *
     * @return the shift of each range value
     */
    private static byte[] norm() {
        var table = new byte[256];
        table[0] = 0;
        for (var i = 1; i < 256; i++) {
            table[i] = (byte) (Integer.numberOfLeadingZeros(i) - 24);
        }
        return table;
    }

    /**
     * Starts a new partition, discarding any previous output, and writes the zero marker bit
     * ({@code vpx_start_encode}).
     */
    void start() {
        low = 0;
        range = 255;
        pending = 8;
        pos = 0;
        writeBit(0);
    }

    /**
     * Pads the code with 32 zero bits, emits the bytes libvpx emits by then and, when the last byte
     * could be read as a superframe marker, appends a zero byte ({@code vpx_stop_encode}).
     */
    void stop() {
        for (var i = 0; i < 32; i++) {
            writeBit(0);
        }
        var bytes = (pending >> 3) - 3;
        ensure(bytes + 1);
        carry();
        for (var i = 0; i < bytes; i++) {
            pending -= 8;
            buffer[pos++] = (byte) (low >>> pending);
        }
        if (pos > 0 && (buffer[pos - 1] & 0xE0) == 0xC0) {
            buffer[pos++] = 0;
        }
    }

    /**
     * Makes room for more output bytes.
     *
     * @param extra the number of bytes about to be written
     */
    private void ensure(int extra) {
        var needed = (long) pos + extra;
        if (needed > buffer.length) {
            if (needed > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("vp9 partition too large");
            }
            buffer = Arrays.copyOf(buffer, (int) Math.max(needed, Math.min(Integer.MAX_VALUE - 16, 2L * buffer.length)));
        }
    }

    /**
     * Adds the carry pending above {@link #pending} bits to the bytes already emitted and clears
     * it.
     */
    private void carry() {
        if ((low >>> pending) != 0) {
            var x = pos - 1;
            while (x >= 0 && buffer[x] == (byte) 0xFF) {
                buffer[x] = 0;
                x--;
            }
            if (x >= 0) {
                buffer[x]++;
            }
            low &= (1L << pending) - 1;
        }
    }

    /**
     * Encodes one boolean ({@code vpx_write}).
     *
     * @param bit         the value, {@code 0} or {@code 1}
     * @param probability the probability of a zero, from 1 to 255
     */
    void write(int bit, int probability) {
        encode(bit, probability);
    }

    /**
     * Encodes the booleans a {@link Vp9SymbolRecorder} recorded, in order, up to the first mark
     * ({@link Vp9SymbolRecorder#mark}).
     *
     * @param symbols the recorded symbols, as {@link Vp9SymbolRecorder#symbols()} holds them
     * @param from    the index of the first symbol
     * @param to      the index after the last symbol
     * @return the index of the first mark, or {@code to} when there is none
     */
    final int replay(int[] symbols, int from, int to) {
        for (var i = from; i < to; i++) {
            var symbol = symbols[i];
            if (symbol < 2) {
                return i;
            }
            encode(symbol & 1, symbol >>> 1);
        }
        return to;
    }

    /**
     * Encodes one boolean; {@link #write} delegates here, so that {@link #replay} codes without a
     * virtual call.
     *
     * @param bit         the value, {@code 0} or {@code 1}
     * @param probability the probability of a zero, from 1 to 255
     */
    private void encode(int bit, int probability) {
        var split = 1 + (((range - 1) * probability) >> 8);
        var taken = (-bit | bit) >> 31;
        var r = split + ((range - (split << 1)) & taken);
        var shift = NORM[r];
        low = (low + (split & taken)) << shift;
        range = r << shift;
        var p = pending + shift;
        pending = p;
        if (p >= FLUSH_BITS) {
            flush();
        }
    }

    /**
     * Emits the 3 oldest pending bytes, after adding the pending carry to the bytes already
     * emitted.
     */
    private void flush() {
        if (pos + 3 > buffer.length) {
            ensure(3);
        }
        carry();
        var p = pending;
        var l = low;
        buffer[pos] = (byte) (l >>> (p - 8));
        buffer[pos + 1] = (byte) (l >>> (p - 16));
        buffer[pos + 2] = (byte) (l >>> (p - 24));
        pos += 3;
        p -= 24;
        low = l & ((1L << p) - 1);
        pending = p;
    }

    /**
     * Encodes one boolean with probability one half ({@code vpx_write_bit}).
     *
     * @param bit the value, {@code 0} or {@code 1}
     */
    void writeBit(int bit) {
        write(bit, 128);
    }

    /**
     * Encodes an unsigned value, most significant bit first, each bit with probability one half
     * ({@code vpx_write_literal}).
     *
     * @param data the value
     * @param bits the number of bits
     */
    void writeLiteral(int data, int bits) {
        for (var bit = bits - 1; bit >= 0; bit--) {
            writeBit((data >> bit) & 1);
        }
    }

    /**
     * Encodes a symbol of a binary tree by walking the path its value describes
     * ({@code vp9_write_tree}).
     *
     * @param tree     the tree, in libvpx's layout: entry {@code i + bit} of node {@code i} is the
     *                 next node, or the negated symbol for a leaf
     * @param probs    the array holding the node probabilities
     * @param probBase the index of the root node's probability; node {@code i} uses entry
     *                 {@code probBase + i / 2}
     * @param bits     the path, most significant bit first
     * @param length   the number of bits of the path
     */
    void writeTree(int[] tree, int[] probs, int probBase, int bits, int length) {
        var i = 0;
        do {
            var bit = (bits >> --length) & 1;
            write(bit, probs[probBase + (i >> 1)]);
            i = tree[i + bit];
        } while (length > 0);
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the size of the partition so far
     */
    int size() {
        return pos;
    }

    /**
     * Returns the buffer holding the written bytes, valid until the next write.
     *
     * @return the buffer; its first {@link #size()} bytes are the partition
     */
    byte[] buffer() {
        return buffer;
    }
}
