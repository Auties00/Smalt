package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Holds the side information of one audio block that the reconstruction needs after the whole
 * frame is parsed: block switching and dither, the coupling, enhanced coupling, rematrixing and
 * spectral extension parameters in effect for the block, and the dynamic range words.
 *
 * <p>The parser snapshots the parameters into one instance per block, since later blocks may
 * replace them before the frame is reconstructed. Instances are reused and not thread-safe.
 */
final class BlockState {
    /**
     * The number of channel slots: five full-bandwidth channels, the coupling channel and the LFE.
     */
    static final int SLOTS = 7;

    /**
     * Whether each channel uses two short transforms ({@code blksw}).
     */
    final boolean[] shortBlocks = new boolean[SLOTS];

    /**
     * Whether each channel reproduces zero-bit mantissas with dither ({@code dithflag}).
     */
    final boolean[] dither = new boolean[SLOTS];

    /**
     * The dynamic range word of each program channel ({@code dynrng}, {@code dynrng2}), signed.
     */
    final int[] dynamicRange = new int[2];

    /**
     * The end mantissa of each channel.
     */
    final int[] endMantissa = new int[SLOTS];

    /**
     * Whether coupling is in use.
     */
    boolean coupling;

    /**
     * Whether the coupling is enhanced coupling.
     */
    boolean enhancedCoupling;

    /**
     * Whether each channel is coupled.
     */
    final boolean[] coupled = new boolean[SLOTS];

    /**
     * The first coupling sub-band ({@code cplbegf}).
     */
    int couplingBegin;

    /**
     * The number of coupling sub-bands ({@code ncplsubnd}).
     */
    int couplingSubbands;

    /**
     * The coupling band of each absolute coupling sub-band.
     */
    final int[] couplingBandOf = new int[18];

    /**
     * The coupling coordinate of each channel and coupling band, the factor 8 of A/52 section
     * 7.4.3 included.
     */
    final float[][] couplingCoordinates = new float[SLOTS][18];

    /**
     * Whether each coupling band of the right channel is phase-inverted in 2/0 mode.
     */
    final boolean[] phaseFlags = new boolean[18];

    /**
     * The first enhanced coupling sub-band.
     */
    int ecplBegin;

    /**
     * One past the last enhanced coupling sub-band.
     */
    int ecplEnd;

    /**
     * The enhanced coupling band of each enhanced coupling sub-band.
     */
    final int[] ecplBandOf = new int[22];

    /**
     * The enhanced coupling amplitude of each channel and band.
     */
    final float[][] ecplAmplitude = new float[SLOTS][22];

    /**
     * The number of rematrixing bands with a flag, {@code 0} outside 2/0 mode.
     */
    int rematrixBands;

    /**
     * The rematrixing flag of each band.
     */
    final boolean[] rematrix = new boolean[4];

    /**
     * Whether spectral extension is in use.
     */
    boolean spx;

    /**
     * Whether each channel uses spectral extension.
     */
    final boolean[] inSpx = new boolean[SLOTS];

    /**
     * The first spectral extension sub-band ({@code spx_begin_subbnd}).
     */
    int spxBegin;

    /**
     * One past the last spectral extension sub-band ({@code spx_end_subbnd}).
     */
    int spxEnd;

    /**
     * The copy start code ({@code spxstrtf}).
     */
    int spxStart;

    /**
     * The number of spectral extension bands.
     */
    int spxBands;

    /**
     * The width in bins of each spectral extension band.
     */
    final int[] spxBandSize = new int[17];

    /**
     * The spectral extension coordinate of each channel and band, the factor 32 of A/52 Annex E
     * section 3.6.4.3 included.
     */
    final float[][] spxCoordinates = new float[SLOTS][17];

    /**
     * The noise blending factor of each channel and band.
     */
    final float[][] spxNoise = new float[SLOTS][17];

    /**
     * The signal blending factor of each channel and band.
     */
    final float[][] spxSignal = new float[SLOTS][17];

    /**
     * Creates an empty block state.
     */
    BlockState() {
    }
}
