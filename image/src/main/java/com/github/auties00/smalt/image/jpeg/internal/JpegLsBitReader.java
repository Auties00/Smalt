/* Ports CharLS (commit 1153c25: scan_decoder.hpp). Copyright (c) 2007 Jan de Vaan and Victor Derks (Team CharLS);
 * BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Reads the entropy-coded bits of a JPEG-LS scan, most significant first: a byte following {@code 0xFF}
 * contributes only its seven low bits, its high bit being the stuffed zero of T.87 C.1.2, and {@code 0xFF}
 * followed by a byte with its high bit set is a marker, past which zero bits are supplied.
 *
 * <p>Instances are not thread-safe.
 */
final class JpegLsBitReader {
    /**
     * The stream.
     */
    private final byte[] data;

    /**
     * The index after the stream.
     */
    private final int end;

    /**
     * The index of the next byte to load.
     */
    private int position;

    /**
     * The buffered bits, right-aligned in the low {@link #count} bits.
     */
    private long buffer;

    /**
     * The number of buffered bits.
     */
    private int count;

    /**
     * Whether the last byte loaded was {@code 0xFF}, so that the next one carries seven bits.
     */
    private boolean stuffed;

    /**
     * The index of the {@code 0xFF} of the marker that ended the data, or {@code -1} while none was reached.
     */
    private int marker = -1;

    /**
     * Constructs a reader.
     *
     * @param data the stream
     * @param start the index of the first entropy-coded byte
     * @param end the index after the stream
     */
    JpegLsBitReader(byte[] data, int start, int end) {
        this.data = data;
        this.position = start;
        this.end = end;
    }

    /**
     * Loads bytes until more than 48 bits are buffered.
     */
    private void fill() {
        while (count <= 48) {
            if (marker >= 0 || position >= end) {
                if (marker < 0) {
                    marker = end;
                }
                buffer <<= 8;
                count += 8;
                continue;
            }
            var next = data[position] & 0xFF;
            if (stuffed) {
                if (next >= 0x80) {
                    marker = position - 1;
                    continue;
                }
                buffer = (buffer << 7) | next;
                count += 7;
                position++;
                stuffed = false;
                continue;
            }
            buffer = (buffer << 8) | next;
            count += 8;
            position++;
            stuffed = next == 0xFF;
        }
    }

    /**
     * Reads bits.
     *
     * @param bits the number of bits, from 1 to 31
     * @return their value
     */
    int bits(int bits) {
        if (count < bits) {
            fill();
        }
        count -= bits;
        return (int) (buffer >>> count) & ((1 << bits) - 1);
    }

    /**
     * {@return the next bit}
     */
    int bit() {
        if (count == 0) {
            fill();
        }
        count--;
        return (int) (buffer >>> count) & 1;
    }

    /**
     * Reads a unary code: zero bits up to a one bit, which is consumed.
     *
     * @param cap the largest count returned; at that count the bit that follows is consumed as the terminating
     * one whatever it is
     * @return the number of zero bits
     */
    int unary(int cap) {
        var zeros = 0;
        while (true) {
            if (count < 32) {
                fill();
            }
            var top = (int) (buffer >>> (count - 32));
            if (top != 0) {
                var leading = Integer.numberOfLeadingZeros(top);
                if (zeros + leading < cap) {
                    count -= leading + 1;
                    return zeros + leading;
                }
                count -= cap - zeros + 1;
                return cap;
            }
            if (zeros + 32 >= cap) {
                count -= cap - zeros;
                bit();
                return cap;
            }
            zeros += 32;
            count -= 32;
        }
    }

    /**
     * Skips to the byte after the next restart marker and empties the buffer.
     */
    void restart() {
        var from = marker >= 0 ? Math.min(marker, end) : stuffed ? position - 1 : position;
        count = 0;
        buffer = 0;
        stuffed = false;
        marker = -1;
        position = from;
        while (position + 1 < end) {
            if ((data[position] & 0xFF) == 0xFF) {
                var following = data[position + 1] & 0xFF;
                if (following >= 0xD0 && following <= 0xD7) {
                    position += 2;
                    return;
                }
                if (following >= 0x80 && following != 0xFF) {
                    return;
                }
            }
            position++;
        }
        position = end;
    }
}
