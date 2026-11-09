/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/cae.h and cae.cpp). Copyright 1996-2000 Microsoft Corporation, Toshiba Corporation and the contributors named
 * in the sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

/**
 * The binary arithmetic decoder of the context-based arithmetic shape coding (7.5.3), with the zero-run stuffing
 * that keeps it from emulating start codes ({@code arcodec}).
 */
final class ArCodec {
    private static final int CODE_BITS = 32;
    private static final long HALF = 1L << (CODE_BITS - 1);
    private static final long QUARTER = 1L << (CODE_BITS - 2);
    private static final long MASK = 0xFFFFFFFFL;
    private static final int MAX_HEADING = 3;
    private static final int MAX_MIDDLE = 10;
    private static final int MAX_TRAILING = 2;

    private long low;
    private long range;
    private long value;
    private long arpipe;
    private int nzeros;
    private int nonzero;
    private int nzerosf;
    private int extrabits;

    /**
     * Starts decoding at the current position ({@code StartArDecoder}).
     */
    void start(InBits bits) {
        value = 0;
        nzerosf = MAX_HEADING;
        extrabits = 0;
        for (var i = 1; i < CODE_BITS; i++) {
            var j = bits.peekOneBit(i + extrabits) ? 1 : 0;
            value = (value + value + j) & MASK;
            if (j == 0) {
                nzerosf--;
                if (nzerosf == 0) {
                    extrabits++;
                    nzerosf = MAX_MIDDLE;
                }
            } else {
                nzerosf = MAX_MIDDLE;
            }
        }
        low = 0;
        range = HALF - 1;
        arpipe = value;
        nzeros = MAX_HEADING;
        nonzero = 0;
    }

    /**
     * Ends decoding, consuming the termination bits ({@code StopArDecoder}).
     */
    void stop(InBits bits) {
        var a = (int) (low >>> (CODE_BITS - 3));
        var b = (int) (((range + low) & MASK) >>> (CODE_BITS - 3));
        if (b == 0) {
            b = 8;
        }
        var count = b - a >= 4 || (b - a == 3 && (a & 1) != 0) ? 2 : 3;
        for (var i = 1; i <= count - 1; i++) {
            addNextInputBit(bits);
        }
        if (nzeros < MAX_MIDDLE - MAX_TRAILING || nonzero == 0) {
            bits.getBits(1);
        }
    }

    private void addNextInputBit(InBits bits) {
        if (((arpipe >>> (CODE_BITS - 2)) & 1) == 0) {
            nzeros--;
            if (nzeros == 0) {
                bits.getBits(1);
                extrabits--;
                nzeros = MAX_MIDDLE;
                nonzero = 1;
            }
        } else {
            nzeros = MAX_MIDDLE;
            nonzero = 1;
        }
        bits.getBits(1);
        var i = bits.peekOneBit(CODE_BITS - 1 + extrabits) ? 1 : 0;
        value = (value + value + i) & MASK;
        arpipe = (arpipe + arpipe + i) & MASK;
        if (i == 0) {
            nzerosf--;
            if (nzerosf == 0) {
                nzerosf = MAX_MIDDLE;
                extrabits++;
            }
        } else {
            nzerosf = MAX_MIDDLE;
        }
    }

    /**
     * Decodes one binary symbol whose probability of being 0 is {@code c0 / 65536} ({@code ArDecodeSymbol}).
     */
    int decode(int c0, InBits bits) {
        var c1 = (1 << 16) - c0;
        var lps = c0 > c1 ? 1 : 0;
        var cLps = lps == 1 ? c1 : c0;
        var rLps = (range >>> 16) * cLps;
        int bit;
        if (((value - low) & MASK) >= ((range - rLps) & MASK)) {
            bit = lps;
            low = (low + range - rLps) & MASK;
            range = rLps & MASK;
        } else {
            bit = 1 - lps;
            range = (range - rLps) & MASK;
        }
        while (range < QUARTER) {
            if (low >= HALF) {
                value = (value - HALF) & MASK;
                low -= HALF;
            } else if (low + range > HALF) {
                value = (value - QUARTER) & MASK;
                low -= QUARTER;
            }
            low = (low + low) & MASK;
            range = (range + range) & MASK;
            addNextInputBit(bits);
        }
        return bit;
    }
}
