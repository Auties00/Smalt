/* Ports libspeex 1.2.1 (libspeex/modes.c, libspeex/modes_wb.c). Copyright 2002-2008 Xiph.org Foundation, Jean-Marc
   Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.speex;

/**
 * The narrowband and sub-band sub-modes of Speex: how each codes its line spectral pairs, pitch and innovation.
 */
final class SpeexModes {
    private SpeexModes() {
    }

    /**
     * How a sub-mode codes its line spectral pairs.
     */
    enum Lsp {
        /** The narrowband quantizer of the higher rates. */
        NB,
        /** The narrowband quantizer of the lower rates. */
        LBR,
        /** The high band quantizer. */
        HIGH
    }

    /**
     * The parameters of a three-tap pitch predictor.
     *
     * @param gains the gain codebook, four bytes per entry
     * @param gainBits the bits of a gain index
     * @param pitchBits the bits of a pitch value
     */
    record Ltp(byte[] gains, int gainBits, int pitchBits) {
    }

    /**
     * The parameters of a split shape codebook.
     *
     * @param subvectorSize the samples of a sub-vector
     * @param subvectors the sub-vectors of a subframe
     * @param shapes the shapes, {@code subvectorSize} bytes each
     * @param shapeBits the bits of a shape index
     * @param hasSign whether a sign bit precedes each index
     */
    record SplitCodebook(int subvectorSize, int subvectors, byte[] shapes, int shapeBits, boolean hasSign) {
    }

    /**
     * A sub-mode.
     *
     * @param lbrPitch -1 for a pitch per subframe, otherwise the range of the variation around a frame pitch
     * @param forcedPitchGain whether one pitch gain is coded for the frame
     * @param subframeGain the bits of a subframe gain correction, 0, 1 or 3
     * @param doubleCodebook whether the innovation is coded twice
     * @param lsp the line spectral pair quantizer
     * @param ltp the three-tap pitch parameters, or {@code null} for a forced pitch (or none in the high band)
     * @param innovation the innovation codebook, or {@code null} for noise (narrowband) or folding (high band)
     * @param combGain the gain of the enhancer's comb filter, negative for none
     * @param bitsPerFrame the bits of a frame
     */
    record Submode(int lbrPitch, boolean forcedPitchGain, int subframeGain, boolean doubleCodebook, Lsp lsp, Ltp ltp,
                   SplitCodebook innovation, int combGain, int bitsPerFrame) {
    }

    private static final Ltp LTP_NB = new Ltp(SpeexTables.GAIN_NB, 7, 7);

    private static final Ltp LTP_VLBR = new Ltp(SpeexTables.GAIN_LBR, 5, 0);

    private static final Ltp LTP_LBR = new Ltp(SpeexTables.GAIN_LBR, 5, 7);

    private static final SplitCodebook CB_VLBR = new SplitCodebook(10, 4, SpeexTables.EXC_10_16, 4, false);

    private static final SplitCodebook CB_ULBR = new SplitCodebook(20, 2, SpeexTables.EXC_20_32, 5, false);

    private static final SplitCodebook CB_LBR = new SplitCodebook(10, 4, SpeexTables.EXC_10_32, 5, false);

    private static final SplitCodebook CB_NB = new SplitCodebook(5, 8, SpeexTables.EXC_5_64, 6, false);

    private static final SplitCodebook CB_MED = new SplitCodebook(8, 5, SpeexTables.EXC_8_128, 7, false);

    private static final SplitCodebook CB_SB = new SplitCodebook(5, 8, SpeexTables.EXC_5_256, 8, false);

    private static final SplitCodebook CB_HIGH = new SplitCodebook(8, 5, SpeexTables.HEXC, 7, true);

    private static final SplitCodebook CB_HIGH_LBR = new SplitCodebook(10, 4, SpeexTables.HEXC_10_32, 5, false);

    /**
     * The narrowband sub-modes by sub-mode number, {@code null} for the empty sub-mode 0 and the reserved ones.
     */
    static final Submode[] NARROWBAND = {
            null,
            new Submode(0, true, 0, false, Lsp.LBR, null, null, -1, 43),
            new Submode(0, false, 0, false, Lsp.LBR, LTP_VLBR, CB_VLBR, SpeexFixed.q16(.6, 15), 119),
            new Submode(-1, false, 1, false, Lsp.LBR, LTP_LBR, CB_LBR, SpeexFixed.q16(.55, 15), 160),
            new Submode(-1, false, 1, false, Lsp.LBR, LTP_LBR, CB_MED, SpeexFixed.q16(.45, 15), 220),
            new Submode(-1, false, 3, false, Lsp.NB, LTP_NB, CB_NB, SpeexFixed.q16(.25, 15), 300),
            new Submode(-1, false, 3, false, Lsp.NB, LTP_NB, CB_SB, SpeexFixed.q16(.15, 15), 364),
            new Submode(-1, false, 3, true, Lsp.NB, LTP_NB, CB_NB, SpeexFixed.q16(.05, 15), 492),
            new Submode(0, true, 0, false, Lsp.LBR, null, CB_ULBR, SpeexFixed.q16(.5, 15), 79),
            null, null, null, null, null, null, null
    };

    private static final Submode WB1 = new Submode(0, false, 1, false, Lsp.HIGH, null, null, -1, 36);

    /**
     * The wideband high band sub-modes by sub-mode number.
     */
    static final Submode[] WIDEBAND = {
            null,
            WB1,
            new Submode(0, false, 1, false, Lsp.HIGH, null, CB_HIGH_LBR, -1, 112),
            new Submode(0, false, 1, false, Lsp.HIGH, null, CB_HIGH, -1, 192),
            new Submode(0, false, 1, true, Lsp.HIGH, null, CB_HIGH, -1, 352),
            null, null, null
    };

    /**
     * The ultra-wideband high band sub-modes by sub-mode number.
     */
    static final Submode[] ULTRA_WIDEBAND = {null, WB1, null, null, null, null, null, null};
}
