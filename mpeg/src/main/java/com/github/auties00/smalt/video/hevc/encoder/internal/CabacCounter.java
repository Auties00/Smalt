/* Ports HM-16.21, the ITU-T / ISO/IEC H.265 reference software (source/Lib/TLibCommon/ContextModel.cpp: the
   m_entropyBits table of FAST_BIT_EST). Copyright (c) 2010-2017, ITU/ISO/IEC; BSD 3-Clause License. Modified for
   Smalt. */
package com.github.auties00.smalt.video.hevc.encoder.internal;

/**
 * Estimates the bits CABAC would spend on a sequence of bins without producing them.
 *
 * <p>A context-coded bin costs {@code -log2} of its probability under the current state and updates the
 * state as {@link CabacWriter} does; a bypass bin costs one bit. Costs accumulate in units of
 * {@code 1/32768} bit ({@link #ONE_BIT}).
 */
final class CabacCounter extends BinCoder {
    /**
     * The cost of one bit in the units of {@link #bits}.
     */
    static final int ONE_BIT = 1 << 15;

    /**
     * The cost of a bin, indexed {@code state ^ bin}, in units of {@code 1/32768} bit.
     */
    static final int[] ENTROPY = {
            0x07b23, 0x085f9, 0x074a0, 0x08cbc, 0x06ee4, 0x09354, 0x067f4, 0x09c1b, 0x060b0, 0x0a62a, 0x05a9c, 0x0af5b,
            0x0548d, 0x0b955, 0x04f56, 0x0c2a9, 0x04a87, 0x0cbf7, 0x045d6, 0x0d5c3, 0x04144, 0x0e01b, 0x03d88, 0x0e937,
            0x039e0, 0x0f2cd, 0x03663, 0x0fc9e, 0x03347, 0x10600, 0x03050, 0x10f95, 0x02d4d, 0x11a02, 0x02ad3, 0x12333,
            0x0286e, 0x12cad, 0x02604, 0x136df, 0x02425, 0x13f48, 0x021f4, 0x149c4, 0x0203e, 0x1527b, 0x01e4d, 0x15d00,
            0x01c99, 0x166de, 0x01b18, 0x17017, 0x019a5, 0x17988, 0x01841, 0x18327, 0x016df, 0x18d50, 0x015d9, 0x19547,
            0x0147c, 0x1a083, 0x0138e, 0x1a8a3, 0x01251, 0x1b418, 0x01166, 0x1bd27, 0x01068, 0x1c77b, 0x00f7f, 0x1d18e,
            0x00eda, 0x1d91a, 0x00e19, 0x1e254, 0x00d4f, 0x1ec9a, 0x00c90, 0x1f6e0, 0x00c01, 0x1fef8, 0x00b5f, 0x208b1,
            0x00ab6, 0x21362, 0x00a15, 0x21e46, 0x00988, 0x2285d, 0x00934, 0x22ea8, 0x008a8, 0x239b2, 0x0081d, 0x24577,
            0x007c9, 0x24ce6, 0x00763, 0x25663, 0x00710, 0x25e8f, 0x006a0, 0x26a26, 0x00672, 0x26f23, 0x005e8, 0x27ef8,
            0x005ba, 0x284b5, 0x0055e, 0x29057, 0x0050c, 0x29bab, 0x004c1, 0x2a674, 0x004a7, 0x2aa5e, 0x0046f, 0x2b32f,
            0x0041f, 0x2c0ad, 0x003e7, 0x2ca8d, 0x003ba, 0x2d323, 0x0010c, 0x3bfbb
    };

    /**
     * The accumulated cost in units of {@code 1/32768} bit.
     */
    long bits;

    /**
     * Constructs a counter.
     */
    CabacCounter() {
    }

    @Override
    void bin(int ctx, int bin) {
        var s = states[ctx] & 0xFF;
        bits += ENTROPY[s ^ bin];
        states[ctx] = NEXT_STATE[(s << 1) | bin];
    }

    @Override
    void bypass(int bin) {
        bits += ONE_BIT;
    }

    @Override
    void bypassBins(int value, int count) {
        bits += (long) ONE_BIT * count;
    }

    @Override
    void terminate(int bin) {
        bits += ENTROPY[126 ^ bin];
    }

    /**
     * Returns the cost of one context-coded bin under the current state, without coding it.
     *
     * @param ctx the context index
     * @param bin the bin value, 0 or 1
     * @return the cost in units of {@code 1/32768} bit
     */
    int cost(int ctx, int bin) {
        return ENTROPY[(states[ctx] & 0xFF) ^ bin];
    }
}
