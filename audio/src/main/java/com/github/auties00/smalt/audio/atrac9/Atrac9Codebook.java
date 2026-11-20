/* Ports LibAtrac9 (C/src/huffCodes.c, C/src/huffCodes.h) at commit efca2e3. Copyright 2018 Alex Barney; MIT
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.atrac9;

import java.util.Arrays;

/**
 * A prefix codebook of ATRAC9, decoded through a lookup table indexed by the next {@code maxBitSize} bits.
 *
 * <p>A symbol packs {@code valueCount} values of {@code valueBits} bits each, the first value in the lowest bits.
 * Instances are immutable and thread-safe.
 */
final class Atrac9Codebook {
    /**
     * The code length of each symbol, 0 for a symbol without a code.
     */
    final byte[] bits;

    /**
     * The code of each symbol, right-aligned.
     */
    final int[] codes;

    final int valueCount;

    /**
     * The base-2 logarithm of {@link #valueCount}.
     */
    final int valueCountPower;

    final int valueBits;

    /**
     * The number of symbols; the deltas of an unsigned scale factor codebook wrap modulo this count.
     */
    final int valueMax;

    final int maxBitSize;

    /**
     * The symbol of each {@code maxBitSize}-bit window; a window no code prefixes reads as symbol 0.
     */
    private final byte[] lookup;

    Atrac9Codebook(byte[] bits, int[] codes, int valueCount, int valueCountPower, int valueBits, int valueMax,
                   int maxBitSize) {
        this.bits = bits;
        this.codes = codes;
        this.valueCount = valueCount;
        this.valueCountPower = valueCountPower;
        this.valueBits = valueBits;
        this.valueMax = valueMax;
        this.maxBitSize = maxBitSize;
        this.lookup = new byte[1 << maxBitSize];
        for (var symbol = 0; symbol < bits.length; symbol++) {
            if (bits[symbol] == 0) {
                continue;
            }
            var unused = maxBitSize - bits[symbol];
            var start = codes[symbol] << unused;
            Arrays.fill(lookup, start, start + (1 << unused), (byte) symbol);
        }
    }

    /**
     * Reads one symbol ({@code ReadHuffmanValue}).
     *
     * @param reader the bit reader
     * @param signed whether the symbol is a signed value of {@link #valueBits} bits
     * @return the symbol, sign-extended when {@code signed}
     */
    int read(Atrac9BitReader reader, boolean signed) {
        var symbol = lookup[reader.peek(maxBitSize)] & 0xFF;
        reader.skip(bits[symbol]);
        return signed ? Atrac9BitReader.signExtend(symbol, valueBits) : symbol;
    }
}
