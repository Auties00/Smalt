/* Ports Esri LERC 4.2.0 (BitStuffer2.cpp, BitStuffer2.h). Copyright 2015-2026 Esri; Apache License 2.0.
 * Modified for Smalt. */
package com.github.auties00.smalt.image.internal.lerc;

import java.util.Arrays;
import java.util.zip.DataFormatException;

/**
 * Reads the bit-packed unsigned integer arrays of Lerc2, plain or through a lookup table.
 *
 * <p>Instances are not thread-safe.
 */
final class BitStuffer {
    /** The input and the position, advanced by each call. */
    byte[] src;
    int pos;
    int end;

    /** The values of the last call. */
    private int[] values = new int[0];

    /** The lookup table of the last call. */
    private final int[] lut = new int[256];

    /** {@return the values of the last call of {@link #decode}, as unsigned integers} */
    int[] values() {
        return values;
    }

    /**
     * Reads one packed array.
     *
     * @param maxCount the largest number of values allowed
     * @param version the Lerc2 version
     * @return the number of values
     * @throws DataFormatException if the array is invalid or truncated
     */
    int decode(int maxCount, int version) throws DataFormatException {
        need(1);
        var b = src[pos++] & 0xFF;
        var bits67 = b >> 6;
        var nb = bits67 == 0 ? 4 : 3 - bits67;
        var doLut = (b & 0x20) != 0;
        var numBits = b & 31;
        need(nb);
        var count = nb == 1 ? src[pos] & 0xFF : nb == 2 ? (src[pos] & 0xFF) | (src[pos + 1] & 0xFF) << 8
                : LercDecoder.intLE(src, pos);
        pos += nb;
        if (count < 0 || count > maxCount) {
            throw new DataFormatException("too many Lerc2 packed values");
        }
        if (values.length < count) {
            values = new int[Math.max(count, 64)];
        }
        if (!doLut) {
            if (numBits == 0) {
                Arrays.fill(values, 0, count, 0);
            } else {
                unpack(values, 0, count, numBits, version);
            }
            return count;
        }
        if (numBits == 0) {
            throw new DataFormatException("invalid Lerc2 lookup table");
        }
        need(1);
        var lutCount = (src[pos++] & 0xFF) - 1;
        if (lutCount <= 0) {
            throw new DataFormatException("invalid Lerc2 lookup table");
        }
        unpack(lut, 1, lutCount, numBits, version);
        lut[0] = 0;
        var lutBits = 32 - Integer.numberOfLeadingZeros(lutCount);
        unpack(values, 0, count, lutBits, version);
        for (var i = 0; i < count; i++) {
            var index = values[i];
            if (index < 0 || index > lutCount) {
                throw new DataFormatException("invalid Lerc2 lookup index");
            }
            values[i] = lut[index];
        }
        return count;
    }

    /**
     * Unpacks bit-packed unsigned values.
     *
     * @param out the values
     * @param at the first value to write
     * @param count the number of values
     * @param numBits the bits per value, from 1 to 31
     * @param version the Lerc2 version: from 3 on, values are packed from the low bits of
     * little-endian words; before, from the high bits
     * @throws DataFormatException if the count is zero or the input is too short
     */
    private void unpack(int[] out, int at, int count, int numBits, int version) throws DataFormatException {
        if (count == 0 || numBits >= 32) {
            throw new DataFormatException("invalid Lerc2 bit stuffing");
        }
        var totalBits = (long) count * numBits;
        var words = (int) ((totalBits + 31) / 32);
        var tailBytes = (int) (((totalBits & 31) + 7) >> 3);
        var notNeeded = tailBytes > 0 ? 4 - tailBytes : 0;
        var used = version >= 3 ? words * 4 - notNeeded : (int) ((totalBits + 7) / 8);
        need(used);
        var bitPos = 0;
        var word = 0;
        if (version >= 3) {
            var nb = 32 - numBits;
            for (var i = 0; i < count; i++) {
                var current = word(word, used, 0, words);
                if (nb - bitPos >= 0) {
                    out[at + i] = (current << (nb - bitPos)) >>> nb;
                    bitPos += numBits;
                    if (bitPos == 32) {
                        word++;
                        bitPos = 0;
                    }
                } else {
                    var value = current >>> bitPos;
                    word++;
                    value |= (word(word, used, 0, words) << (64 - numBits - bitPos)) >>> nb;
                    out[at + i] = value;
                    bitPos -= nb;
                }
            }
        } else {
            for (var i = 0; i < count; i++) {
                var current = word(word, used, notNeeded, words);
                if (32 - bitPos >= numBits) {
                    out[at + i] = (current << bitPos) >>> (32 - numBits);
                    bitPos += numBits;
                    if (bitPos == 32) {
                        bitPos = 0;
                        word++;
                    }
                } else {
                    var value = (current << bitPos) >>> (32 - numBits);
                    word++;
                    bitPos -= 32 - numBits;
                    value |= word(word, used, notNeeded, words) >>> (32 - bitPos);
                    out[at + i] = value;
                }
            }
        }
        pos += used;
    }

    /**
     * Returns a little-endian word of the packed bytes, zero past the used bytes; the last word of the
     * packing before version 3 is shifted up by its unused bytes.
     */
    private int word(int index, int used, int notNeeded, int words) {
        var value = 0;
        for (var b = 0; b < 4; b++) {
            var at = index * 4 + b;
            if (at < used) {
                value |= (src[pos + at] & 0xFF) << (8 * b);
            }
        }
        return index == words - 1 ? value << (8 * notNeeded) : value;
    }

    private void need(long count) throws DataFormatException {
        if (count < 0 || end - pos < count) {
            throw new DataFormatException("truncated Lerc2 packed values");
        }
    }
}
