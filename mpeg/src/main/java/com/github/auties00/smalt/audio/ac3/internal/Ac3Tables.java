package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Holds the constant tables of ATSC A/52 (AC-3) and its Annex E (E-AC-3) shared by the decoder and
 * the encoder; the sync frame tables (frame sizes, bit rates, sampling rates, blocks per frame) are
 * those of {@link com.github.auties00.smalt.internal.codec.Ac3Configs}.
 *
 * <p>Every table is named after the specification table it reproduces. Callers never modify them.
 */
public final class Ac3Tables {
    /**
     * The number of full-bandwidth channels by audio coding mode {@code acmod} (A/52 table 5.8).
     */
    public static final int[] CHANNELS = {2, 1, 2, 3, 3, 4, 4, 5};

    /**
     * The reduced sampling rates of E-AC-3 by {@code fscod2} (A/52 table E2.3).
     */
    public static final int[] REDUCED_RATES = {24000, 22050, 16000};

    /**
     * The slow decay table {@code slowdec[]} (A/52 table 7.6).
     */
    public static final int[] SLOW_DECAY = {0x0f, 0x11, 0x13, 0x15};

    /**
     * The fast decay table {@code fastdec[]} (A/52 table 7.7).
     */
    public static final int[] FAST_DECAY = {0x3f, 0x53, 0x67, 0x7b};

    /**
     * The slow gain table {@code slowgain[]} (A/52 table 7.8).
     */
    public static final int[] SLOW_GAIN = {0x540, 0x4d8, 0x478, 0x410};

    /**
     * The dB per bit table {@code dbpbtab[]} (A/52 table 7.9).
     */
    public static final int[] DB_PER_BIT = {0x000, 0x700, 0x900, 0xb00};

    /**
     * The masking floor table {@code floortab[]} (A/52 table 7.10), {@code 0xf800} read as the
     * signed value {@code -2048}.
     */
    public static final int[] FLOOR = {0x2f0, 0x2b0, 0x270, 0x230, 0x1f0, 0x170, 0x0f0, -2048};

    /**
     * The fast gain table {@code fastgain[]} (A/52 table 7.11).
     */
    public static final int[] FAST_GAIN = {0x080, 0x100, 0x180, 0x200, 0x280, 0x300, 0x380, 0x400};

    /**
     * The first mantissa of each of the 50 bit allocation bands, {@code bndtab[]} (A/52 table
     * 7.12), followed by the end of the last band.
     */
    public static final int[] BAND_START = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 31, 34, 37, 40, 43, 46, 49, 55, 61, 67, 73, 79, 85, 97, 109, 121, 133, 157,
            181, 205, 229, 253};

    /**
     * The bit allocation band of each mantissa, {@code masktab[]} (A/52 table 7.13), derived from
     * {@link #BAND_START}; bins 253 to 255 belong to no band and map to band 0 as in the table.
     */
    public static final int[] MASK = masks();

    /**
     * The log-addition table {@code latab[]} (A/52 table 7.14).
     */
    public static final int[] LOG_ADD = {
            64, 63, 62, 61, 60, 59, 58, 57, 56, 55, 54, 53, 52, 52, 51, 50, 49, 48, 47, 47, 46, 45, 44, 44, 43,
            42, 41, 41, 40, 39, 38, 38, 37, 36, 36, 35, 35, 34, 33, 33, 32, 32, 31, 30, 30, 29, 29, 28, 28, 27,
            27, 26, 26, 25, 25, 24, 24, 23, 23, 22, 22, 21, 21, 21, 20, 20, 19, 19, 19, 18, 18, 18, 17, 17, 17,
            16, 16, 16, 15, 15, 15, 14, 14, 14, 13, 13, 13, 13, 12, 12, 12, 12, 11, 11, 11, 11, 10, 10, 10, 10,
            10, 9, 9, 9, 9, 9, 8, 8, 8, 8, 8, 8, 7, 7, 7, 7, 7, 7, 6, 6, 6, 6, 6, 6, 6,
            6, 5, 5, 5, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0};

    /**
     * The hearing threshold {@code hth[fscod][band]} (A/52 table 7.15), rows for 48, 44.1 and 32 kHz.
     */
    public static final int[][] HEARING_THRESHOLD = {
            {0x4d0, 0x4d0, 0x440, 0x400, 0x3e0, 0x3c0, 0x3b0, 0x3b0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x390,
                    0x390, 0x390, 0x380, 0x380, 0x370, 0x370, 0x360, 0x360, 0x350, 0x350, 0x340, 0x340, 0x330,
                    0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x310, 0x340, 0x390, 0x3e0, 0x420,
                    0x460, 0x490, 0x4a0, 0x460, 0x440, 0x440, 0x520, 0x800, 0x840, 0x840},
            {0x4f0, 0x4f0, 0x460, 0x410, 0x3e0, 0x3d0, 0x3c0, 0x3b0, 0x3b0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0,
                    0x390, 0x390, 0x390, 0x380, 0x380, 0x380, 0x370, 0x370, 0x360, 0x360, 0x350, 0x350, 0x340,
                    0x340, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x320, 0x350, 0x390, 0x3e0,
                    0x420, 0x450, 0x4a0, 0x490, 0x460, 0x440, 0x480, 0x630, 0x840, 0x840},
            {0x580, 0x580, 0x4b0, 0x450, 0x420, 0x3f0, 0x3e0, 0x3d0, 0x3c0, 0x3b0, 0x3b0, 0x3b0, 0x3a0, 0x3a0,
                    0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x3a0, 0x390, 0x390, 0x390, 0x390, 0x380, 0x380, 0x380,
                    0x370, 0x360, 0x350, 0x340, 0x330, 0x320, 0x310, 0x300, 0x2f0, 0x2f0, 0x2f0, 0x300, 0x310,
                    0x330, 0x350, 0x3c0, 0x410, 0x470, 0x4a0, 0x460, 0x440, 0x450, 0x4e0}};

    /**
     * The bit allocation pointer table {@code baptab[]} (A/52 table 7.16).
     */
    public static final int[] BAP = {0, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8,
            9, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11, 12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 14, 14, 14, 14,
            15, 15, 15, 15, 15, 15, 15, 15, 15};

    /**
     * The high-efficiency bit allocation pointer table {@code hebaptab[]} of the adaptive hybrid
     * transform (A/52 table E3.1).
     */
    public static final int[] HEBAP = {0, 1, 2, 3, 4, 5, 6, 7, 8, 8, 8, 8, 9, 9, 9, 10, 10, 10, 10, 11, 11, 11, 11,
            12, 12, 12, 12, 13, 13, 13, 13, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 18,
            18, 18, 18, 18, 18, 19, 19, 19, 19, 19, 19, 19, 19, 19};

    /**
     * The mantissa bits of the asymmetric quantizers by bit allocation pointer, {@code qntztab[]}
     * (A/52 table 7.18); entries 0 to 5 are grouped or symmetric and hold the bits per mantissa of
     * the ungrouped symmetric quantizers 3 and 5 only.
     */
    public static final int[] MANTISSA_BITS = {0, 0, 0, 3, 0, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16};

    /**
     * The mantissa bits of the gain-adaptive quantizers by high-efficiency bit allocation pointer
     * 8 to 19 (A/52 table E3.2), indexed by {@code hebap - 8}.
     */
    public static final int[] GAQ_BITS = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 16};

    /**
     * The index bits of the vector quantizers by high-efficiency bit allocation pointer 1 to 7
     * (A/52 table E3.2), indexed by {@code hebap}.
     */
    public static final int[] VQ_BITS = {0, 2, 3, 4, 5, 7, 8, 9};

    /**
     * The frame-based exponent strategies of E-AC-3 by {@code frmcplexpstr} or
     * {@code frmchexpstr} (A/52 table E2.10): six block strategies each, {@code 0} reuse,
     * {@code 1} D15, {@code 2} D25, {@code 3} D45.
     */
    public static final int[][] FRAME_EXPONENT_STRATEGIES = {
            {1, 0, 0, 0, 0, 0}, {1, 0, 0, 0, 0, 3}, {1, 0, 0, 0, 2, 0}, {1, 0, 0, 0, 3, 3},
            {2, 0, 0, 2, 0, 0}, {2, 0, 0, 2, 0, 3}, {2, 0, 0, 3, 2, 0}, {2, 0, 0, 3, 3, 3},
            {2, 0, 1, 0, 0, 0}, {2, 0, 2, 0, 0, 3}, {2, 0, 2, 0, 2, 0}, {2, 0, 2, 0, 3, 3},
            {2, 0, 3, 2, 0, 0}, {2, 0, 3, 2, 0, 3}, {2, 0, 3, 3, 2, 0}, {2, 0, 3, 3, 3, 3},
            {3, 1, 0, 0, 0, 0}, {3, 1, 0, 0, 0, 3}, {3, 2, 0, 0, 2, 0}, {3, 2, 0, 0, 3, 3},
            {3, 2, 0, 2, 0, 0}, {3, 2, 0, 2, 0, 3}, {3, 2, 0, 3, 2, 0}, {3, 2, 0, 3, 3, 3},
            {3, 3, 1, 0, 0, 0}, {3, 3, 2, 0, 0, 3}, {3, 3, 2, 0, 2, 0}, {3, 3, 2, 0, 3, 3},
            {3, 3, 3, 2, 0, 0}, {3, 3, 3, 2, 0, 3}, {3, 3, 3, 3, 2, 0}, {3, 3, 3, 3, 3, 3}};

    /**
     * The default coupling band structure of E-AC-3 {@code defcplbndstrc[]} by coupling sub-band
     * (A/52 table E2.12); sub-band 0 starts a band.
     */
    public static final boolean[] DEFAULT_COUPLING_BANDS = {false, false, false, false, false, false, false, false,
            true, false, true, true, false, true, true, true, true, true};

    /**
     * The default spectral extension band structure {@code defspxbndstrc[]} by sub-band (A/52
     * table E2.11).
     */
    public static final boolean[] DEFAULT_SPX_BANDS = {false, false, false, false, false, false, false, false, true,
            false, true, false, true, false, true, false, true};

    /**
     * The default enhanced coupling band structure {@code defecplbndstrc[]} by sub-band (A/52
     * table E2.13).
     */
    public static final boolean[] DEFAULT_ECPL_BANDS = {false, false, false, false, false, false, false, false,
            false, true, false, true, false, true, false, true, true, true, false, true, true, true};

    /**
     * The first transform coefficient of each enhanced coupling sub-band, {@code ecplsubbndtab[]}
     * (A/52 table E3.9), followed by the end of the last sub-band.
     */
    public static final int[] ECPL_SUBBAND_START = {13, 19, 25, 31, 37, 49, 61, 73, 85, 97, 109, 121, 133, 145, 157,
            169, 181, 193, 205, 217, 229, 241, 253};

    /**
     * The exponent of each enhanced coupling amplitude code, {@code ecplampexptab[]} (A/52 table
     * E3.10); code 31 is minus infinity.
     */
    public static final int[] ECPL_AMP_EXPONENT = {0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
            5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 0};

    /**
     * The mantissa of each enhanced coupling amplitude code, {@code ecplampmanttab[]} (A/52 table
     * E3.10).
     */
    public static final int[] ECPL_AMP_MANTISSA = {0x20, 0x1b, 0x17, 0x13, 0x10, 0x1b, 0x17, 0x13, 0x10, 0x1b, 0x17,
            0x13, 0x10, 0x1b, 0x17, 0x13, 0x10, 0x1b, 0x17, 0x13, 0x10, 0x1b, 0x17, 0x13, 0x10, 0x1b, 0x17, 0x13, 0x10,
            0x1b, 0x17, 0x00};

    /**
     * The large-mantissa remapping constants of the gain-adaptive quantizer (A/52 table E3.6): for
     * each {@code hebap - 8}, the 16-bit fractions {@code a} for gains 1, 2 and 4, then {@code b}
     * for gains 2 and 4 with a non-negative codeword, then {@code b} for gains 2 and 4 with a
     * negative codeword; {@code b} is zero for gain 1.
     */
    public static final int[][] GAQ_REMAP = {
            {0x1249, 0xd555, 0xedb7, 0x4000, 0x2000, 0xeaab, 0xfb6e},
            {0x0889, 0xc925, 0xe666, 0x4000, 0x2000, 0xd249, 0xeccd},
            {0x0421, 0xc444, 0xe319, 0x4000, 0x2000, 0xc889, 0xe632},
            {0x0208, 0xc211, 0xe186, 0x4000, 0x2000, 0xc421, 0xe30c},
            {0x0102, 0xc104, 0xe0c2, 0x4000, 0x2000, 0xc208, 0xe183},
            {0x0081, 0xc081, 0xe060, 0x4000, 0x2000, 0xc102, 0xe0c1},
            {0x0040, 0xc040, 0xe030, 0x4000, 0x2000, 0xc081, 0xe060},
            {0x0020, 0xc020, 0xe018, 0x4000, 0x2000, 0xc040, 0xe030},
            {0x0010, 0xc010, 0xe00c, 0x4000, 0x2000, 0xc020, 0xe018},
            {0x0008, 0, 0, 0, 0, 0, 0},
            {0x0002, 0, 0, 0, 0, 0, 0},
            {0x0000, 0, 0, 0, 0, 0, 0}};

    /**
     * The transform window {@code w[n]} of A/52 table 7.33: the first half of a 512-point
     * Kaiser-Bessel derived window with alpha 5.
     */
    public static final float[] WINDOW = window();

    /**
     * The value {@code 2^-e} for every exponent {@code e} from 0 to 63.
     */
    public static final float[] POW2_NEG = powers();

    /**
     * The value {@code 2^e} for every exponent {@code e} from 0 to 63, the exact reciprocal of the
     * entry of {@link #POW2_NEG}.
     */
    public static final float[] POW2_POS = reciprocals();

    /**
     * Prevents instantiation.
     */
    private Ac3Tables() {
        throw new AssertionError();
    }

    /**
     * Derives {@code masktab[]} from the band starts.
     *
     * @return the band of each of the 256 mantissas
     */
    private static int[] masks() {
        var masks = new int[256];
        for (var band = 0; band < 50; band++) {
            for (var bin = BAND_START[band]; bin < BAND_START[band + 1]; bin++) {
                masks[bin] = band;
            }
        }
        return masks;
    }

    /**
     * Computes the Kaiser-Bessel derived window.
     *
     * @implNote This implementation evaluates the window from its definition (alpha 5, 512 points)
     * in double precision; the values agree with the five-digit printed table to within its
     * rounding.
     * @return the 256 values of the rising half
     */
    private static float[] window() {
        var kaiser = new double[257];
        var total = 0.0;
        for (var n = 0; n <= 256; n++) {
            var x = 4.0 * n / 512 - 1;
            kaiser[n] = besselI0(Math.PI * 5 * Math.sqrt(1 - x * x));
            total += kaiser[n];
        }
        var window = new float[256];
        var sum = 0.0;
        for (var n = 0; n < 256; n++) {
            sum += kaiser[n];
            window[n] = (float) Math.sqrt(sum / total);
        }
        return window;
    }

    /**
     * Computes the zeroth-order modified Bessel function of the first kind.
     *
     * @param x the argument
     * @return {@code I0(x)}
     */
    private static double besselI0(double x) {
        var sum = 1.0;
        var term = 1.0;
        for (var k = 1; k < 200; k++) {
            var half = x / (2.0 * k);
            term *= half * half;
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }

    /**
     * Derives {@link #POW2_POS}.
     *
     * @return {@code 2^e} for {@code e} from 0 to 63
     */
    private static float[] reciprocals() {
        var powers = new float[64];
        for (var e = 0; e < 64; e++) {
            powers[e] = (float) Math.scalb(1.0, e);
        }
        return powers;
    }

    /**
     * Computes the powers of two.
     *
     * @return {@code 2^-e} for {@code e} from 0 to 63
     */
    private static float[] powers() {
        var powers = new float[64];
        for (var e = 0; e < 64; e++) {
            powers[e] = (float) Math.scalb(1.0, -e);
        }
        return powers;
    }
}
