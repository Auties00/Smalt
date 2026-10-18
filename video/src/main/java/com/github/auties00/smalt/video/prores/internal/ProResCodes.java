package com.github.auties00.smalt.video.prores.internal;

import com.github.auties00.smalt.video.intra.internal.IntraBitReader;
import com.github.auties00.smalt.video.intra.internal.IntraBitWriter;

/**
 * Decodes, encodes and measures the Golomb-Rice/exponential-Golomb combination codewords of the
 * ProRes entropy coder (SMPTE RDD 36 clause 7.1.1), for codebooks packed as
 * {@link ProResTables} describes.
 */
public final class ProResCodes {
    /**
     * The largest number of leading zeros of the exponential-Golomb part of a codeword the decoder
     * accepts; longer prefixes, which no symbol a valid stream holds needs, are malformed.
     */
    private static final int MAX_EXP_ZEROS = 24;

    /**
     * The value {@link #decode} returns for a malformed codeword.
     */
    public static final int INVALID = -1;

    /**
     * Prevents instantiation.
     */
    private ProResCodes() {
        throw new AssertionError();
    }

    /**
     * Decodes one codeword.
     *
     * @param reader the reader, positioned at the codeword
     * @param codebook the packed codebook
     * @return the symbol, non-negative, or {@link #INVALID} when the codeword's prefix is longer
     * than any valid codeword's
     */
    public static int decode(IntraBitReader reader, int codebook) {
        var packed = peek(reader.window(), codebook);
        if (packed < 0) {
            return INVALID;
        }
        reader.skip((int) (packed & 0xFF));
        return (int) (packed >>> 8);
    }

    /**
     * Decodes the codeword at the start of a bit window without consuming it.
     *
     * @param window the next 64 bits of the stream, as {@link IntraBitReader#window()} returns
     * them
     * @param codebook the packed codebook
     * @return the symbol in the bits above the low eight and the length of the codeword in bits
     * in the low eight, or a negative value when the codeword's prefix is longer than any
     * valid codeword's
     */
    public static long peek(long window, int codebook) {
        var zeros = Long.numberOfLeadingZeros(window);
        var riceLimit = codebook >>> 8;
        var kRice = (codebook >>> 4) & 15;
        var kExp = codebook & 15;
        if (zeros < riceLimit) {
            var length = zeros + 1 + kRice;
            if (kRice == 0) {
                return (long) zeros << 8 | length;
            }
            var symbol = zeros << kRice | (int) ((window << (zeros + 1)) >>> (64 - kRice));
            return (long) symbol << 8 | length;
        }
        var expZeros = zeros - riceLimit;
        if (expZeros > MAX_EXP_ZEROS) {
            return -1;
        }
        var bits = expZeros + kExp + 1;
        var value = (int) ((window << zeros) >>> (64 - bits));
        var symbol = value - (1 << kExp) + (riceLimit << kRice);
        return (long) symbol << 8 | (zeros + bits);
    }

    /**
     * Writes one codeword.
     *
     * @param writer the bit writer that receives the code
     * @param codebook the packed codebook
     * @param symbol the symbol, non-negative and below {@code 2^24}
     */
    public static void encode(IntraBitWriter writer, int codebook, int symbol) {
        var riceLimit = codebook >>> 8;
        var kRice = (codebook >>> 4) & 15;
        var kExp = codebook & 15;
        var riceRange = riceLimit << kRice;
        if (symbol < riceRange) {
            var quotient = symbol >>> kRice;
            writer.write(1 << kRice | (symbol & ((1 << kRice) - 1)), quotient + 1 + kRice);
            return;
        }
        var value = symbol - riceRange + (1 << kExp);
        var bits = 32 - Integer.numberOfLeadingZeros(value);
        writer.writeZeros(riceLimit + bits - kExp - 1);
        writer.write(value, bits);
    }

    /**
     * Returns the length of the codeword of a symbol.
     *
     * @param codebook the packed codebook
     * @param symbol the symbol, non-negative and below {@code 2^24}
     * @return the length in bits
     */
    public static int length(int codebook, int symbol) {
        var riceLimit = codebook >>> 8;
        var kRice = (codebook >>> 4) & 15;
        var kExp = codebook & 15;
        var riceRange = riceLimit << kRice;
        if (symbol < riceRange) {
            return (symbol >>> kRice) + 1 + kRice;
        }
        var bits = 32 - Integer.numberOfLeadingZeros(symbol - riceRange + (1 << kExp));
        return riceLimit + 2 * bits - kExp - 1;
    }

    /**
     * Maps a signed value to its symbol: {@code S(n)} of RDD 36 clause 7.1.1.2.
     *
     * @param value the signed coefficient value
     * @return {@code 2n} for {@code n >= 0}, {@code -2n - 1} otherwise
     */
    public static int toSymbol(int value) {
        return value >= 0 ? value << 1 : -(value << 1) - 1;
    }

    /**
     * Maps a symbol back to its signed value: the inverse of {@link #toSymbol(int)}.
     *
     * @param symbol the symbol, non-negative
     * @return the value
     */
    public static int fromSymbol(int symbol) {
        return (symbol & 1) == 0 ? symbol >>> 1 : -((symbol + 1) >>> 1);
    }
}
