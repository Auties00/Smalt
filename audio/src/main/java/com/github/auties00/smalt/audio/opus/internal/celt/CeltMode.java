/* Ports libopus 1.5.2 (celt/modes.h, celt/modes.c, celt/static_modes_float.h). Copyright Xiph.Org Foundation, CSIRO
   and Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Describes a CELT mode: the band layout, allocation tables, window and transforms shared by the
 * encoder and the decoder ({@code OpusCustomMode}).
 *
 * <p>Opus uses a single mode, {@link #MODE_48000_960}: 48 kHz, 20 ms frames of 960 samples built
 * from up to eight 2.5 ms short MDCTs, 21 bands and a 120-sample overlap. Encoders and decoders
 * running at lower rates use the same mode and resample.
 *
 * @implNote This implementation supports only the static mode {@code mode48000_960_120}; custom modes are not
 * supported.
 */
public final class CeltMode {
    /**
     * The only mode Opus uses: 48 kHz with 960-sample frames.
     */
    public static final CeltMode MODE_48000_960 = createStandardMode();

    /**
     * The sampling rate in hertz.
     */
    public final int Fs;

    /**
     * The MDCT overlap in samples.
     */
    public final int overlap;

    /**
     * The number of energy bands.
     */
    public final int nbEBands;

    /**
     * The number of bands actually coded.
     */
    public final int effEBands;

    /**
     * The pre-emphasis filter coefficients.
     */
    public final float[] preemph;

    /**
     * The band edges in units of short-MDCT bins, {@link #nbEBands} + 1 entries.
     */
    public final short[] eBands;

    /**
     * The base-2 logarithm of the largest number of short MDCTs per frame.
     */
    public final int maxLM;

    /**
     * The largest number of short MDCTs per frame.
     */
    public final int nbShortMdcts;

    /**
     * The size in coefficients of one short MDCT.
     */
    public final int shortMdctSize;

    /**
     * The number of rows of {@link #allocVectors}.
     */
    public final int nbAllocVectors;

    /**
     * The bit allocation vectors, {@link #nbAllocVectors} rows of {@link #nbEBands} unsigned
     * bytes.
     */
    public final short[] allocVectors;

    /**
     * The base-2 logarithm of each band width, in 1/8 bits.
     */
    public final short[] logN;

    /**
     * The overlap window, {@link #overlap} samples.
     */
    public final float[] window;

    /**
     * The MDCT configurations.
     */
    public final MdctLookup mdct;

    /**
     * The PVQ bit-cost cache.
     */
    public final PulseCache cache;

    /**
     * Creates a mode from its tables.
     *
     * @param Fs             the sampling rate
     * @param overlap        the MDCT overlap
     * @param nbEBands       the number of bands
     * @param effEBands      the number of coded bands
     * @param preemph        the pre-emphasis coefficients
     * @param eBands         the band edges
     * @param maxLM          the largest LM
     * @param nbShortMdcts   the number of short MDCTs per frame
     * @param shortMdctSize  the short MDCT size
     * @param nbAllocVectors the number of allocation vectors
     * @param allocVectors   the allocation vectors
     * @param logN           the band width logarithms
     * @param window         the overlap window
     * @param mdct           the MDCT lookup
     * @param cache          the pulse cache
     */
    private CeltMode(int Fs, int overlap, int nbEBands, int effEBands, float[] preemph, short[] eBands, int maxLM,
                     int nbShortMdcts, int shortMdctSize, int nbAllocVectors, short[] allocVectors, short[] logN,
                     float[] window, MdctLookup mdct, PulseCache cache) {
        this.Fs = Fs;
        this.overlap = overlap;
        this.nbEBands = nbEBands;
        this.effEBands = effEBands;
        this.preemph = preemph;
        this.eBands = eBands;
        this.maxLM = maxLM;
        this.nbShortMdcts = nbShortMdcts;
        this.shortMdctSize = shortMdctSize;
        this.nbAllocVectors = nbAllocVectors;
        this.allocVectors = allocVectors;
        this.logN = logN;
        this.window = window;
        this.mdct = mdct;
        this.cache = cache;
    }

    /**
     * Builds {@code mode48000_960_120} from the static tables.
     *
     * @return the standard mode
     */
    private static CeltMode createStandardMode() {
        var twiddles = StaticTwiddles.FFT_TWIDDLES48000_960;
        var kfft = new KissFftState[]{
                new KissFftState(480, 0.002083333f, -1,
                        new short[]{5, 96, 3, 32, 4, 8, 2, 4, 4, 1, 0, 0, 0, 0, 0, 0},
                        StaticModesFloat.FFT_BITREV480, twiddles),
                new KissFftState(240, 0.004166667f, 1,
                        new short[]{5, 48, 3, 16, 4, 4, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                        StaticModesFloat.FFT_BITREV240, twiddles),
                new KissFftState(120, 0.008333333f, 2,
                        new short[]{5, 24, 3, 8, 2, 4, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                        StaticModesFloat.FFT_BITREV120, twiddles),
                new KissFftState(60, 0.016666667f, 3,
                        new short[]{5, 12, 3, 4, 4, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                        StaticModesFloat.FFT_BITREV60, twiddles)
        };
        var mdct = new MdctLookup(1920, 3, kfft, StaticTwiddles.MDCT_TWIDDLES960);
        var cache = new PulseCache(392, StaticModesFloat.CACHE_INDEX50, StaticModesFloat.CACHE_BITS50,
                StaticModesFloat.CACHE_CAPS50);
        return new CeltMode(48000, 120, 21, 21, new float[]{0.85000610f, 0.0000000f, 1.0000000f, 1.0000000f},
                StaticModesFloat.EBAND5MS, 3, 8, 120, 11, StaticModesFloat.BAND_ALLOCATION,
                StaticModesFloat.LOGN400, StaticModesFloat.WINDOW120, mdct, cache);
    }
}
