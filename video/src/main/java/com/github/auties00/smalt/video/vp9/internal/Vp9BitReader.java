/* Ports libvpx (vpx_dsp/bitreader_buffer.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Reads the plain bits of a VP9 uncompressed frame header, most significant bit first.
 *
 * @implNote This implementation follows libvpx's {@code vpx_dsp/bitreader_buffer.c},
 * whose error handler for reading past the end becomes a
 * {@link MediaException.Malformed}.
 */
final class Vp9BitReader {
    /**
     * The input.
     */
    private final byte[] data;

    /**
     * The index of the first byte.
     */
    private final int start;

    /**
     * The index one past the last byte.
     */
    private final int end;

    /**
     * The number of bits read.
     */
    private int bitOffset;

    /**
     * Creates a reader over a byte range.
     *
     * @param data the input
     * @param start the index of the first byte
     * @param end the index one past the last byte
     */
    public Vp9BitReader(byte[] data, int start, int end) {
        this.data = data;
        this.start = start;
        this.end = end;
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     * @throws MediaException.Malformed if the input is exhausted
     */
    public int readBit() {
        var position = start + (bitOffset >> 3);
        if (position >= end) {
            throw new MediaException.Malformed("truncated vp9 frame header");
        }
        var bit = (data[position] >> (7 - (bitOffset & 7))) & 1;
        bitOffset++;
        return bit;
    }

    /**
     * Reads an unsigned literal.
     *
     * @param bits the number of bits, at most 31
     * @return the literal
     * @throws MediaException.Malformed if the input is exhausted
     */
    public int readLiteral(int bits) {
        var value = 0;
        for (var bit = bits - 1; bit >= 0; bit--) {
            value |= readBit() << bit;
        }
        return value;
    }

    /**
     * Reads a magnitude followed by a sign bit ({@code vpx_rb_read_signed_literal}).
     *
     * @param bits the number of magnitude bits
     * @return the signed value
     * @throws MediaException.Malformed if the input is exhausted
     */
    public int readSignedLiteral(int bits) {
        var value = readLiteral(bits);
        return readBit() != 0 ? -value : value;
    }

    /**
     * Returns the number of whole bytes the bits read so far occupy.
     *
     * @return the number of bytes, rounded up
     */
    public int bytesRead() {
        return (bitOffset + 7) >> 3;
    }
}
