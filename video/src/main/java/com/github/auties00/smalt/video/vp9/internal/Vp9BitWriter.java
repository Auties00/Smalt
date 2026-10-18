/* Ports libvpx 1.17.0 (vpx_dsp/bitwriter_buffer.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Writes the uncompressed header of a VP9 frame, most significant bit first.
 *
 * @implNote This implementation follows libvpx's {@code vpx_write_bit_buffer}
 * ({@code vpx_dsp/bitwriter_buffer.c}).
 */
final class Vp9BitWriter {
    /**
     * The output bytes.
     */
    private byte[] buffer = new byte[64];

    /**
     * The number of bits written.
     */
    private int bitOffset;

    /**
     * Creates an empty writer.
     */
    Vp9BitWriter() {
    }

    /**
     * Discards every written bit.
     */
    void reset() {
        bitOffset = 0;
        Arrays.fill(buffer, (byte) 0);
    }

    /**
     * Writes one bit ({@code vpx_wb_write_bit}).
     *
     * @param bit the bit, {@code 0} or {@code 1}
     */
    void writeBit(int bit) {
        var p = bitOffset >> 3;
        if (p >= buffer.length) {
            buffer = Arrays.copyOf(buffer, buffer.length * 2);
        }
        var q = 7 - (bitOffset & 7);
        if (q == 7) {
            buffer[p] = (byte) (bit << 7);
        } else {
            buffer[p] |= (byte) (bit << q);
        }
        bitOffset++;
    }

    /**
     * Writes an unsigned value, most significant bit first ({@code vpx_wb_write_literal}).
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
     * Overwrites 16 bits at a bit position written earlier, used for the size of the compressed
     * header once it is known.
     *
     * @param position the bit position of the field
     * @param value the 16-bit field, most significant bit first
     */
    void patch16(int position, int value) {
        for (var i = 0; i < 16; i++) {
            var bitPos = position + i;
            var p = bitPos >> 3;
            var q = 7 - (bitPos & 7);
            var bit = (value >> (15 - i)) & 1;
            buffer[p] = (byte) ((buffer[p] & ~(1 << q)) | (bit << q));
        }
    }

    /**
     * Returns the number of bits written.
     *
     * @return the bit position
     */
    int bitPosition() {
        return bitOffset;
    }

    /**
     * Returns the number of bytes the written bits occupy ({@code vpx_wb_bytes_written}).
     *
     * @return the size in bytes
     */
    int bytesWritten() {
        return (bitOffset + 7) >> 3;
    }

    /**
     * Returns the buffer holding the written bits.
     *
     * @return the buffer; its first {@link #bytesWritten()} bytes hold the header
     */
    byte[] buffer() {
        return buffer;
    }
}
