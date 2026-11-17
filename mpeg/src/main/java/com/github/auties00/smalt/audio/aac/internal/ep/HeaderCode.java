package com.github.auties00.smalt.audio.aac.internal.ep;

/**
 * Codes the in-band header of an error protected frame with the basic set of FEC codes of ISO/IEC 14496-3 Table
 * 1.59: a threefold repetition decoded by majority for one or two bits, and the shortened BCH(7,4), BCH(15,7),
 * Golay(23,12) and BCH(31,16) codes for up to 4, 7, 12 and 16 bits. Longer headers use the terminated SRCPC code
 * that {@link Srcpc} implements.
 *
 * <p>A shortened codeword sends its {@code l} information bits, then the {@code n - k} parity bits of the
 * information zero-padded at its end to {@code k} bits: the remainder of that padded information times
 * {@code x^(n-k)} divided by the generator, highest order first. Decoding corrects every error pattern of up to
 * {@code t} bits through a syndrome table and reports a pattern it cannot correct.
 */
enum HeaderCode {
    /**
     * BCH(7,4), generator {@code x^3+x+1}, correcting one error.
     */
    BCH_7_4(7, 4, 0xB, 1),

    /**
     * BCH(15,7), generator {@code x^8+x^7+x^6+x^4+1}, correcting two errors.
     */
    BCH_15_7(15, 7, 0x1D1, 2),

    /**
     * Golay(23,12), generator {@code x^11+x^9+x^7+x^6+x^5+x+1}, correcting three errors.
     */
    GOLAY_23_12(23, 12, 0xAE3, 3),

    /**
     * BCH(31,16), generator {@code x^15+x^11+x^10+x^9+x^8+x^7+x^5+x^3+x^2+x+1}, correcting three errors.
     */
    BCH_31_16(31, 16, 0x8FAF, 3);

    /**
     * The codeword length of the unshortened code.
     */
    private final int length;

    /**
     * The number of information bits of the unshortened code.
     */
    private final int information;

    /**
     * The generator polynomial, its leading term included.
     */
    private final int generator;

    /**
     * The number of errors the code corrects.
     */
    private final int correctable;

    /**
     * The error pattern of each syndrome, or 0 for a syndrome no correctable pattern produces; built on first use.
     */
    private volatile int[] patterns;

    /**
     * Constructs a code.
     *
     * @param length the codeword length
     * @param information the number of information bits
     * @param generator the generator polynomial
     * @param correctable the number of correctable errors
     */
    HeaderCode(int length, int information, int generator, int correctable) {
        this.length = length;
        this.information = information;
        this.generator = generator;
        this.correctable = correctable;
    }

    /**
     * Returns the code that protects a header part of a given length under the basic set.
     *
     * @param bits the number of information bits, from 3 to 16
     * @return the code
     */
    static HeaderCode forBits(int bits) {
        if (bits <= 4) {
            return BCH_7_4;
        }
        if (bits <= 7) {
            return BCH_15_7;
        }
        return bits <= 12 ? GOLAY_23_12 : BCH_31_16;
    }

    /**
     * {@return the number of parity bits}
     */
    int parityBits() {
        return length - information;
    }

    /**
     * Computes the remainder of a polynomial modulo the generator.
     *
     * @param value the polynomial, bit {@code i} holding the coefficient of {@code x^i}
     * @return the remainder
     */
    private int reduce(long value) {
        var degree = parityBits();
        for (var bit = length - 1; bit >= degree; bit--) {
            if ((value & (1L << bit)) != 0) {
                value ^= (long) generator << (bit - degree);
            }
        }
        return (int) value;
    }

    /**
     * Packs a run of information bits into the high part of a codeword.
     *
     * @param bits the bits, one per byte
     * @param offset the index of the first information bit
     * @param count the number of information bits
     * @return the codeword with zero parity
     */
    private long pack(byte[] bits, int offset, int count) {
        var value = 0L;
        for (var i = 0; i < count; i++) {
            if (bits[offset + i] != 0) {
                value |= 1L << (length - 1 - i);
            }
        }
        return value;
    }

    /**
     * Writes the parity bits of a run of information bits.
     *
     * @param bits the information bits, one per byte
     * @param offset the index of the first information bit
     * @param count the number of information bits, at most {@link #information}
     * @param out receives the parity bits
     * @param outOffset the index of the first parity bit
     */
    void encode(byte[] bits, int offset, int count, byte[] out, int outOffset) {
        var remainder = reduce(pack(bits, offset, count));
        var degree = parityBits();
        for (var i = 0; i < degree; i++) {
            out[outOffset + i] = (byte) ((remainder >>> (degree - 1 - i)) & 1);
        }
    }

    /**
     * Decodes a shortened codeword in place, correcting up to {@link #correctable} errors.
     *
     * @param bits the codeword, one bit per byte: the information bits then the parity bits
     * @param offset the index of the first bit
     * @param count the number of information bits
     * @return {@code true} when the codeword is valid or was corrected, {@code false} when the errors exceed the
     * code's capability
     */
    boolean decode(byte[] bits, int offset, int count) {
        var degree = parityBits();
        var value = pack(bits, offset, count);
        for (var i = 0; i < degree; i++) {
            if (bits[offset + count + i] != 0) {
                value |= 1L << (degree - 1 - i);
            }
        }
        var syndrome = reduce(value);
        if (syndrome == 0) {
            return true;
        }
        var pattern = table()[syndrome];
        if (pattern == 0 || (pattern & ~((1 << degree) - 1) & ~(((1 << count) - 1) << (length - count))) != 0) {
            return false;
        }
        for (var bit = 0; bit < length; bit++) {
            if ((pattern & (1 << bit)) != 0) {
                bits[bit < degree ? offset + count + (degree - 1 - bit) : offset + (length - 1 - bit)] ^= 1;
            }
        }
        return true;
    }

    /**
     * Returns the syndrome table, building it on first use.
     *
     * @return the error pattern of each syndrome
     */
    private int[] table() {
        var table = patterns;
        if (table == null) {
            table = new int[1 << parityBits()];
            fill(table, 0, 0, correctable);
            patterns = table;
        }
        return table;
    }

    /**
     * Records the syndromes of every error pattern of up to {@code remaining} more bits above a starting bit.
     *
     * @param table the table being built
     * @param pattern the error pattern so far
     * @param from the lowest bit the next error may take
     * @param remaining the number of further errors
     */
    private void fill(int[] table, int pattern, int from, int remaining) {
        if (remaining == 0) {
            return;
        }
        for (var bit = from; bit < length; bit++) {
            var next = pattern | (1 << bit);
            var syndrome = reduce(next);
            if (table[syndrome] == 0) {
                table[syndrome] = next;
            }
            fill(table, next, bit + 1, remaining - 1);
        }
    }
}
