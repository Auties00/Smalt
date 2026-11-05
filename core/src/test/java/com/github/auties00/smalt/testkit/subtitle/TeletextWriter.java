package com.github.auties00.smalt.testkit.subtitle;

import java.io.ByteArrayOutputStream;

/**
 * Writes DVB teletext test packets (ETSI EN 300 706 packets in ETSI EN 300 472 data units): page headers, display
 * rows, X/26 enhancement and X/28 designation packets, bit-reversed as a PES packet carries them.
 */
public final class TeletextWriter {
    private final ByteArrayOutputStream units = new ByteArrayOutputStream();

    /**
     * Returns the Hamming 8/4 code of four bits in transmission order (bit 1 the least significant).
     *
     * @param value the data bits, D1 the least significant
     * @return the coded byte
     */
    public static int hamming84(int value) {
        var d1 = value & 1;
        var d2 = value >> 1 & 1;
        var d3 = value >> 2 & 1;
        var d4 = value >> 3 & 1;
        var p1 = 1 ^ d1 ^ d3 ^ d4;
        var p2 = 1 ^ d1 ^ d2 ^ d4;
        var p3 = 1 ^ d1 ^ d2 ^ d3;
        var p4 = 1 ^ p1 ^ d1 ^ p2 ^ d2 ^ p3 ^ d3 ^ d4;
        return p1 | d1 << 1 | p2 << 2 | d2 << 3 | p3 << 4 | d3 << 5 | p4 << 6 | d4 << 7;
    }

    /**
     * Returns the Hamming 24/18 code of 18 bits in transmission order.
     *
     * @param value the data bits, D1 the least significant
     * @return the three coded bytes, the first transmitted first
     */
    public static int[] hamming2418(int value) {
        var bits = new int[25];
        int[] positions = {3, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 23};
        for (var index = 0; index < positions.length; index++) {
            bits[positions[index]] = value >> index & 1;
        }
        for (var parity = 1; parity <= 16; parity <<= 1) {
            var total = 1;
            for (var position = 1; position < 24; position++) {
                if ((position & parity) != 0 && position != parity) {
                    total ^= bits[position];
                }
            }
            bits[parity] = total;
        }
        var overall = 1;
        for (var position = 1; position < 24; position++) {
            overall ^= bits[position];
        }
        bits[24] = overall;
        var word = 0;
        for (var position = 1; position <= 24; position++) {
            word |= bits[position] << (position - 1);
        }
        return new int[]{word & 0xFF, word >> 8 & 0xFF, word >> 16 & 0xFF};
    }

    /**
     * Returns a seven-bit character with its odd parity bit.
     *
     * @param value the character
     * @return the byte
     */
    public static int odd(int value) {
        return Integer.bitCount(value & 0x7F) % 2 == 1 ? value & 0x7F : value | 0x80;
    }

    /**
     * Adds a page header.
     *
     * @param magazine the magazine, 1 to 8
     * @param page the page number within the magazine, two hexadecimal digits
     * @param erase whether the erase page bit C4 is set
     * @param subtitle whether the subtitle bit C6 is set
     * @param serial whether the magazine serial bit C11 is set
     * @param national the national option bits C12, C13 and C14 as {@code C12 << 2 | C13 << 1 | C14}
     * @return this writer
     */
    public TeletextWriter header(int magazine, int page, boolean erase, boolean subtitle, boolean serial, int national) {
        var payload = new int[38];
        payload[0] = hamming84(page & 0xF);
        payload[1] = hamming84(page >> 4 & 0xF);
        payload[2] = hamming84(0);
        payload[3] = hamming84(erase ? 8 : 0);
        payload[4] = hamming84(0);
        payload[5] = hamming84(subtitle ? 8 : 0);
        payload[6] = hamming84(1);
        payload[7] = hamming84((serial ? 1 : 0) | (national >> 2 & 1) << 1 | (national >> 1 & 1) << 2
                | (national & 1) << 3);
        for (var index = 8; index < 38; index++) {
            payload[index] = odd(' ');
        }
        return unit(magazine, 0, payload);
    }

    /**
     * Adds a display row.
     *
     * @param magazine the magazine, 1 to 8
     * @param row the row, 1 to 25
     * @param codes the character and attribute codes, at most 40, padded with spaces
     * @return this writer
     */
    public TeletextWriter row(int magazine, int row, int... codes) {
        var payload = new int[40];
        for (var index = 0; index < 40; index++) {
            payload[index] = odd(index < codes.length ? codes[index] : ' ');
        }
        return unit(magazine, row, payload);
    }

    /**
     * Returns the codes of a text, for {@link #row(int, int, int...)}.
     *
     * @param prefix the codes before the text
     * @param text the characters
     * @param suffix the codes after the text
     * @return the codes
     */
    public static int[] codes(int[] prefix, String text, int... suffix) {
        var codes = new int[prefix.length + text.length() + suffix.length];
        System.arraycopy(prefix, 0, codes, 0, prefix.length);
        for (var index = 0; index < text.length(); index++) {
            codes[prefix.length + index] = text.charAt(index);
        }
        System.arraycopy(suffix, 0, codes, prefix.length + text.length(), suffix.length);
        return codes;
    }

    /**
     * Adds an X/26 packet of enhancement triplets, filled with termination markers.
     *
     * @param magazine the magazine, 1 to 8
     * @param triplets the triplets, address in bits 0 to 5, mode in bits 6 to 10, data in bits 11 to 17
     * @return this writer
     */
    public TeletextWriter enhancements(int magazine, int... triplets) {
        return triplets(magazine, 26, triplets);
    }

    /**
     * Adds an X/28/0 format 1 packet designating a character set.
     *
     * @param magazine the magazine, 1 to 8
     * @param designation the 7-bit designation code of table 32
     * @return this writer
     */
    public TeletextWriter designation(int magazine, int designation) {
        return triplets(magazine, 28, designation << 7);
    }

    private TeletextWriter triplets(int magazine, int row, int... triplets) {
        var payload = new int[40];
        payload[0] = hamming84(0);
        for (var index = 0; index < 13; index++) {
            var coded = hamming2418(index < triplets.length ? triplets[index] : 63 | 0x1F << 6);
            payload[1 + index * 3] = coded[0];
            payload[2 + index * 3] = coded[1];
            payload[3 + index * 3] = coded[2];
        }
        return unit(magazine, row, payload);
    }

    private TeletextWriter unit(int magazine, int row, int[] payload) {
        units.write(0x03);
        units.write(44);
        units.write(0xE7);
        units.write(0xE4);
        units.write(reverse(hamming84((magazine & 7) | (row & 1) << 3)));
        units.write(reverse(hamming84(row >> 1)));
        for (var index = 0; index < 40; index++) {
            units.write(reverse(index < payload.length ? payload[index] : odd(' ')));
        }
        return this;
    }

    /**
     * Returns the PES data field: the {@code data_identifier} and the data units added so far, and starts afresh.
     *
     * @return the data field
     */
    public byte[] packet() {
        var out = new ByteArrayOutputStream();
        out.write(0x10);
        out.writeBytes(units.toByteArray());
        units.reset();
        return out.toByteArray();
    }

    private static int reverse(int value) {
        return Integer.reverse(value & 0xFF) >>> 24;
    }
}
