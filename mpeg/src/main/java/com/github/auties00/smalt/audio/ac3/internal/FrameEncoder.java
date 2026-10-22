package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.Ac3Configs;

import java.util.Arrays;

/**
 * Encodes AC-3 sync frames of six blocks, or E-AC-3 independent or dependent substream frames of
 * one, two, three or six blocks, following the encoder of ATSC A/52 section 8 and the syntax of
 * Annex E (ETSI TS 102 366 Annex E).
 *
 * <p>Each block of each full-bandwidth channel is transformed with one 512-sample transform, or
 * with two 256-sample transforms when the transient detector of A/52 section 8.2.2 fires; from
 * 64 kbit/s per full-bandwidth channel the detector requires twice the peak ratios of section
 * 8.2.2. Above the coupling begin frequency, the channels of a block that use long transforms share one
 * coupling channel (A/52 sections 7.4 and 8.2.5): their average, with the right channel of a 2/0
 * program sign-flipped in the bands where the two channels are out of phase (phase flags), and one
 * coupling coordinate per channel and coupling band that restores each channel's band energy. A
 * channel whose high band the coupling channel would rebuild with an error above half its energy
 * over the frame, or with an error above the quantization error of its previous frame, stays
 * uncoupled. Coordinates are sent when the coupling strategy changes and
 * whenever a band's energy ratio drifts from the one in effect by more than a factor of four. In
 * 2/0 mode each rematrixing band codes the sum and difference when that lowers the smaller channel
 * energy.
 *
 * <p>The exponent strategy of every exponent set (each full-bandwidth channel, the coupling
 * channel and the LFE) is chosen by dynamic programming over the blocks of the frame: every run of
 * blocks that may share exponents is costed with each of D15, D25 and D45 as the exponent bits plus
 * the mantissa bits the shared exponents cost at the SNR offset of the previous frame, and the
 * cheapest partition wins; six-block E-AC-3 frames use the frame-based strategy syntax when the
 * strategies it can express cost less. The bit allocation runs the parametric model of the
 * decoder, and one coarse and fine SNR offset for the whole frame is searched so that the
 * mantissas use as many bits as the frame holds. A frame whose side information would not fit is
 * analyzed again with long transforms only. Instances are not thread-safe.
 *
 * <p>One-block E-AC-3 frames, the frames of a low-latency stream, take a faster search, and so do
 * the frames of an encoder created with it (the real-time AC-3 preset): every eighth one-block
 * frame, or every fourth frame of more blocks, is coded under each dB per bit code and the code of
 * the smaller quantization error is kept for the frames up to the next such frame, which are coded
 * under that code alone, and the strategy search stops at the first strategy that costs no less
 * than the finer one before it.
 *
 * <p>The two short transforms of a switched block leak energy into every coefficient, including
 * those above the coded bandwidth, and dropping those breaks the time-domain alias cancellation
 * with the neighboring blocks; the resulting error lies above the bandwidth, so it is inaudible,
 * but it bounds a full-band SNR measurement of a transient block to about 60 dB.
 *
 * <p>E-AC-3 frames may use the tools {@link EncoderTools} selects. With spectral extension
 * ({@link SpxAnalysis}) every full-bandwidth channel codes its coefficients up to the extension
 * region, coupling (when used) ends where the region begins, and the coordinates of each channel are
 * computed after the final bit allocation from the coefficients the decoder reconstructs below the
 * region. With the adaptive hybrid transform, an exponent set of a six-block frame whose exponents
 * are sent once and whose channel uses long transforms throughout is coded through
 * {@link AhtEncoder} when the error it adds, weighed against the bits it saves at the exchange rate
 * of about 6 dB per bit spread over every coded mantissa of the frame, lowers the frame's
 * quantization error at the SNR offset of the previous frame; such a channel is coded without
 * dither, because decoders dither its zero-bit mantissas in the transform domain, six times the
 * power of AC-3 dither. With transient pre-noise processing, {@link PreNoiseAnalysis} chooses the
 * data from the time-domain quantization error of the frame at its final bit allocation, and the
 * SNR offset is lowered until the data fits. A dependent substream frame carries its custom channel
 * map and leaves out the converter fields of independent substreams.
 *
 * <p>The input of {@link #encode(float[][], int)} is in coded channel order: the full-bandwidth
 * channels in the order of A/52 table 5.8, then the LFE.
 *
 * @implNote This implementation sets the coupling thresholds, the leak initialization codes, the dB
 * per bit codes searched and how often, and the relaxed transient ratios by measuring round-trip SNR
 * on music, film sound and synthetic signals. In E-AC-3 a frame that cannot couple in block 0 does
 * not couple at all, and coupled channels use D15 or D25 exponents, because widely deployed decoders
 * reject the other cases Annex E allows.
 */
public final class FrameEncoder {
    /**
     * The slow decay code of the bit allocation.
     */
    private static final int SLOW_DECAY_CODE = 2;

    /**
     * The fast decay code of the bit allocation.
     */
    private static final int FAST_DECAY_CODE = 1;

    /**
     * The slow gain code of the bit allocation.
     */
    private static final int SLOW_GAIN_CODE = 1;

    /**
     * The dB per bit codes the encoder chooses from, frame by frame: 3, which spends fewer bits on
     * the quiet bands of dense spectra, and 2, the default of A/52 section 8.2.12, which serves
     * sparse, tonal spectra better.
     */
    private static final int[] DB_PER_BIT_CODES = {3, 2};

    /**
     * The number of one-block frames of the fast search between two frames that cost every dB per
     * bit code and choose the code of the frames that follow.
     */
    private static final int PROBE_INTERVAL = 8;

    /**
     * The number of frames of more than one block of the fast search between two frames that cost
     * every dB per bit code and choose the code of the frames that follow.
     */
    private static final int LONG_FRAME_PROBE_INTERVAL = 4;

    /**
     * The floor code of the bit allocation.
     */
    private static final int FLOOR_CODE = 4;

    /**
     * The fast gain code of every exponent set.
     */
    private static final int FAST_GAIN_CODE = 4;

    /**
     * The coupling fast leak initialization code {@code cplfleak}.
     */
    private static final int FAST_LEAK_CODE = 3;

    /**
     * The coupling slow leak initialization code {@code cplsleak}.
     */
    private static final int SLOW_LEAK_CODE = 3;


    /**
     * The bit allocation parameter codes E-AC-3 implies when {@code bamode} is 0: {@code sdcycod},
     * {@code fdcycod}, {@code sgaincod}, {@code dbpbcod}, {@code floorcod} (A/52 Annex E table E1.4).
     */
    private static final int[] ENHANCED_DEFAULT_CODES = {2, 1, 1, 2, 7};

    /**
     * The silence threshold of the transient detector, 100 / 32768 (A/52 section 8.2.2).
     */
    private static final float SILENCE = 100.0f / 32768;

    /**
     * The peak ratio thresholds of the three levels of the transient detector.
     */
    private static final float[] THRESHOLDS = {0.1f, 0.075f, 0.05f};

    /**
     * The bit rate per full-bandwidth channel from which the transient detector requires twice
     * the peak ratios of A/52 section 8.2.2 before it switches a block to short transforms.
     */
    private static final int RELAXED_TRANSIENTS = 64_000;

    /**
     * The largest coefficient magnitude, which keeps every normalized mantissa below one.
     */
    private static final float MAX_COEFFICIENT = 0.99999f;

    /**
     * The largest change of a coupling band's energy ratio, as a factor of power, tolerated before
     * new coupling coordinates are sent: 4, about 6 dB.
     */
    private static final float COORDINATE_DRIFT = 4.0f;

    /**
     * The largest coupling error a coupled channel may have, as a fraction of its energy in the
     * coupling region over the frame.
     */
    private static final double MEMBER_QUALITY = 0.5;

    /**
     * The largest coupling error a coupled channel may have, as a multiple of the quantization
     * error energy of the channel in the previous frame.
     */
    private static final double NOISE_RATIO = 1;

    /**
     * The error the quantization of the coupling coordinates adds, as a fraction of a coupled
     * channel's energy in the coupling region: 2^-12, about -36 dB, the power of the rounding of
     * a coordinate with a 4-bit mantissa and an implied leading one.
     */
    private static final double COORDINATE_ERROR = 0x1p-12;

    /**
     * The SNR offset the exponent strategies of the first frame are costed at.
     */
    private static final int INITIAL_OFFSET = 15 << 4;

    /**
     * The mantissa bits, in sixths of a bit, of each address into {@link Ac3Tables#BAP}: grouped
     * quantizers count their share of a group, 5/3, 7/3 and 7/2 bits for pointers 1, 2 and 4.
     */
    private static final long[] MANTISSA_COST = mantissaCost();

    /**
     * {@link #MANTISSA_COST} padded for {@link ExponentRuns#tallyPair}.
     */
    private static final long[] MANTISSA_PADDED = ExponentRuns.padCost(MANTISSA_COST);

    /**
     * The tally of each address into {@link Ac3Tables#BAP}, four 16-bit fields that
     * {@link ExponentRuns#tally(int[], int, int, long[])} sums over the mantissas of a block: the
     * bits of the mantissas that are not grouped (pointers 3 and 5 and above) in bits 0 to 15, and
     * the number of mantissas of pointers 1, 2 and 4, whose bits depend on how many share a group,
     * in bits 16, 32 and 48.
     *
     * @implNote This implementation sums the tally instead of counting each pointer, which leaves
     * a plain reduction in the loop instead of a read-modify-write of a counter per mantissa. No
     * field overflows: a block holds at most 1792 mantissas of at most 16 bits.
     */
    private static final long[] MANTISSA_TALLY = mantissaTally();

    /**
     * {@link #MANTISSA_TALLY} padded for {@link ExponentRuns#tally}.
     */
    private static final long[] TALLY_PADDED = ExponentRuns.padCost(MANTISSA_TALLY);

    /**
     * The levels of the symmetric quantizers of bit allocation pointers 1 to 5 (A/52 table 7.18).
     */
    private static final int[] LEVELS = {0, 3, 5, 7, 11, 15};

    /**
     * The value the decoder reconstructs for each code of the symmetric quantizers of bit
     * allocation pointers 1 to 5: {@code RECONSTRUCTION[pointer][code]}.
     */
    private static final float[][] RECONSTRUCTION = reconstruction();

    /**
     * The window of the transform in reverse order, which weighs the new samples of a block.
     */
    private static final float[] WINDOW_REVERSED = reversedWindow();

    /**
     * The reciprocal of the quantizer limit {@code 2^(bits - 1)} of each bit allocation pointer
     * from 6 to 15, exactly.
     */
    private static final float[] RECIPROCAL_LIMIT = reciprocalLimits();

    /**
     * Whether the frames use the E-AC-3 syntax.
     */
    private final boolean enhanced;

    /**
     * The number of blocks per frame.
     */
    private final int blocks;

    /**
     * The audio coding mode.
     */
    private final int acmod;

    /**
     * Whether the LFE channel is coded.
     */
    private final boolean lfe;

    /**
     * The number of full-bandwidth channels.
     */
    private final int channels;

    /**
     * The number of coded channels, LFE included.
     */
    private final int coded;

    /**
     * The slot of the coupling channel in the per-slot arrays.
     */
    private final int cpl;

    /**
     * The sample rate code {@code fscod}: 3 for the reduced rates of E-AC-3.
     */
    private final int fscod;

    /**
     * The reduced sample rate code {@code fscod2}, used when {@link #fscod} is 3.
     */
    private final int fscod2;

    /**
     * The index of the bit rate in {@link Ac3Configs#BITRATES}, for AC-3.
     */
    private final int bitrateIndex;

    /**
     * The dialogue normalization code, from 1 to 31.
     */
    private final int dialnorm;

    /**
     * Whether short transforms are used on transients.
     */
    private final boolean blockSwitching;

    /**
     * The factor by which a segment peak must exceed the peak ratio thresholds of the transient
     * detector over the previous peak: 1, or 2 from {@link #RELAXED_TRANSIENTS}.
     */
    private final float transientRatio;

    /**
     * The bandwidth code of the uncoupled full-bandwidth channels.
     */
    private final int bandwidthCode;

    /**
     * The end mantissa of the uncoupled full-bandwidth channels: the first coefficient of the
     * extension region with spectral extension, the end of the coded bandwidth without.
     */
    private final int channelEnd;

    /**
     * The spectral extension analysis, or {@code null} without spectral extension.
     */
    private final SpxAnalysis spx;

    /**
     * The transient pre-noise processing analysis, or {@code null} without transient pre-noise
     * processing data.
     */
    private final PreNoiseAnalysis preNoise;

    /**
     * The coefficients of the left (or only) and right channel of a block as the decoder
     * reconstructs them, scratch of {@link #measurePreNoise()}.
     */
    private final float[][] preNoiseDecoded;

    /**
     * The original coefficients of the left (or only) and right channel of a block, scratch of
     * {@link #measurePreNoise()}.
     */
    private final float[][] preNoiseOriginal;

    /**
     * The custom channel map of the dependent substream the frames belong to, or {@code -1} for an
     * independent substream.
     */
    private final int channelMap;

    /**
     * The coefficients of a channel below the extension region as the decoder reconstructs them,
     * without dither, scratch of {@link #codeSpectralExtension()}.
     */
    private final float[][] reconstructed;

    /**
     * The expected power of the dither the decoder adds to each coefficient of
     * {@link #reconstructed}, scratch.
     */
    private final float[][] ditherPower;

    /**
     * The expected power of the dither of a coefficient with a zero-bit mantissa, relative to the
     * square of its exponent's scale: the decoder draws uniform noise in {@code [-0.707, 0.707)}.
     */
    private static final float DITHER_POWER = 0.707f * 0.707f / 3;

    /**
     * Whether coupling is used.
     */
    private final boolean coupling;

    /**
     * The coupling begin frequency code {@code cplbegf}: the first coupling sub-band.
     */
    private final int couplingBegin;

    /**
     * The coupling end frequency code {@code cplendf}: the last coupling sub-band minus 2.
     */
    private final int couplingEndCode;

    /**
     * The number of coupling sub-bands.
     */
    private final int couplingSubbands;

    /**
     * The first coupling channel mantissa.
     */
    private final int couplingStart;

    /**
     * One past the last coupling channel mantissa.
     */
    private final int couplingEnd;

    /**
     * The coupling band structure by absolute sub-band: whether the sub-band joins the band below.
     */
    private final boolean[] couplingStructure = new boolean[18];

    /**
     * Whether {@link #couplingStructure} equals the E-AC-3 default over the coupled sub-bands.
     */
    private final boolean defaultStructure;

    /**
     * The number of coupling bands.
     */
    private final int couplingBands;

    /**
     * The first mantissa of each coupling band, and one past the last band at index
     * {@link #couplingBands}.
     */
    private final int[] couplingBandStart = new int[19];

    /**
     * The exact number of 16-bit words a frame averages.
     */
    private final double exactWords;

    /**
     * The accumulated fraction of a word owed by the frame size alternation.
     */
    private double paddingDebt;

    /**
     * The number of blocks encoded before the current frame, which places the E-AC-3 converter
     * synchronization points every six blocks.
     */
    private long blockCount;

    /**
     * The combined SNR offset of the previous frame, at which the exponent strategies are costed.
     */
    private int referenceOffset = INITIAL_OFFSET;

    /**
     * The quantized mantissas of the sets of the frame at the offset of each candidate dB per bit
     * code: {@code quantizedCodes[code][block][slot][mantissa]}, the code of a symmetric quantizer
     * or the two's complement value of a plain one.
     */
    private final short[][][][] quantizedCodes;

    /**
     * The combined SNR offset the quantized mantissas of each candidate dB per bit code are for,
     * or {@code -1}.
     */
    private final int[] quantizedOffset = new int[DB_PER_BIT_CODES.length];

    /**
     * The index of the dB per bit code whose {@link #quantizedCodes} the mantissa writer and the
     * spectral extension analysis use, or {@code -1} when they quantize the coefficients themselves.
     */
    private int reuseCode = -1;

    /**
     * The index of the dB per bit code whose exponent strategies the exponents of the frame being
     * coded were coded with, or {@code -1} when the search of the frame ran after the last coding.
     */
    private int appliedCode = -1;

    /**
     * The cheapest cover of each exponent set under each candidate dB per bit code, the first block
     * and the strategy of its runs packed in six bits each: {@code chainKeys[code][slot]}.
     */
    private final long[][] chainKeys;

    /**
     * The transforms.
     */
    private final Transform transform;

    /**
     * The bit allocation routine.
     */
    private final BitAllocation allocation = new BitAllocation();

    /**
     * The bit allocation parameters.
     */
    private final BitAllocation.Parameters parameters = new BitAllocation.Parameters();

    /**
     * Whether the frames are analyzed with the faster search: one dB per bit code per frame,
     * re-chosen every {@link #probeInterval} frames.
     */
    private final boolean fastSearch;

    /**
     * The number of frames between two frames of the fast search that cost every dB per bit code:
     * {@link #PROBE_INTERVAL} for one-block frames, {@link #LONG_FRAME_PROBE_INTERVAL} otherwise.
     */
    private final int probeInterval;

    /**
     * The index in {@link #DB_PER_BIT_CODES} of the first code the frame being coded is costed
     * under.
     */
    private int codeFrom;

    /**
     * One past the index in {@link #DB_PER_BIT_CODES} of the last code the frame being coded is
     * costed under.
     */
    private int codeTo = DB_PER_BIT_CODES.length;

    /**
     * The index in {@link #DB_PER_BIT_CODES} of the code the last probing frame of the fast search
     * chose.
     */
    private int preferredCode;

    /**
     * The dB per bit code of the frame being coded.
     */
    private int dbPerBitCode = DB_PER_BIT_CODES[0];


    /**
     * The masking floor of {@link #parameters}.
     */
    private final int floor;

    /**
     * The fast gain of every exponent set.
     */
    private final int fastGain;

    /**
     * The initial fast leak of the coupling channel's bit allocation.
     */
    private final int fastLeak;

    /**
     * The initial slow leak of the coupling channel's bit allocation.
     */
    private final int slowLeak;

    /**
     * The last 256 input samples of every coded channel, the first half of the next window.
     */
    private final float[][] history;

    /**
     * The high-pass filter state of every full-bandwidth channel: x1, x2, y1, y2.
     */
    private final float[][] filterState;

    /**
     * The last segment peak of each level of every channel's transient detector.
     */
    private final float[][] lastPeaks;

    /**
     * Whether the block before the current one was short, by channel.
     */
    private final boolean[] previousShort;

    /**
     * The coefficients of each block and slot; the coupling slot holds the coupling channel.
     */
    private final float[][][] coefficients;

    /**
     * The overlap history saved before the analysis of the frame.
     */
    private final float[][] savedHistory;

    /**
     * The transient detector filter state saved before the analysis of the frame.
     */
    private final float[][] savedFilterState;

    /**
     * The transient detector peaks saved before the analysis of the frame.
     */
    private final float[][] savedPeaks;

    /**
     * The transform length of the last block saved before the analysis of the frame.
     */
    private final boolean[] savedPreviousShort;

    /**
     * Whether each block of each coded channel uses short transforms.
     */
    private final boolean[][] shortBlocks;

    /**
     * Whether each block of each coded channel flags dither.
     */
    private final boolean[][] ditherFlags;

    /**
     * Whether coupling is in use in each block.
     */
    private final boolean[] couplingInUse = new boolean[6];

    /**
     * Whether each block carries a new coupling strategy ({@code cplstre}).
     */
    private final boolean[] couplingStrategy = new boolean[6];

    /**
     * Whether each full-bandwidth channel is coupled in each block.
     */
    private final boolean[][] coupled;

    /**
     * Whether new coupling coordinates of each channel are sent in each block ({@code cplcoe}).
     */
    private final boolean[][] coordinatesSent;

    /**
     * The master coupling coordinate of each block and channel.
     */
    private final int[][] coordinateMaster;

    /**
     * The coupling coordinate exponent of each block, channel and band.
     */
    private final int[][][] coordinateExponents;

    /**
     * The coupling coordinate mantissa of each block, channel and band.
     */
    private final int[][][] coordinateMantissas;

    /**
     * The phase flags in effect in each block, by band.
     */
    private final boolean[][] phaseFlags = new boolean[6][18];

    /**
     * The phase flags in effect, carried from block to block.
     */
    private final boolean[] currentPhase = new boolean[18];

    /**
     * The phase flags the current block's correlations call for, scratch.
     */
    private final boolean[] candidatePhase = new boolean[18];

    /**
     * The coordinates in effect of each channel and band, as the decoder holds them.
     */
    private final float[][] currentCoordinates;

    /**
     * The coordinates in effect in each block, by channel and band.
     */
    private final float[][][] blockCoordinates;

    /**
     * Whether each full-bandwidth channel may be coupled in the frame.
     */
    private final boolean[] eligible;

    /**
     * The coupling region energy of each channel over the frame, scratch of
     * {@link #selectMembers()}.
     */
    private final double[] memberEnergy;

    /**
     * The energy of the coupling error of each channel over the frame, scratch of
     * {@link #selectMembers()}.
     */
    private final double[] memberError;

    /**
     * The quantization error energy of each slot at the SNR offset being evaluated, scratch of
     * {@link #quantizationNoise(int, int, double)}.
     */
    private final double[] slotNoise;

    /**
     * The quantization error energy of each full-bandwidth channel in the previous frame, which
     * bounds the coupling error a channel may take on.
     */
    private final double[] previousNoise;

    /**
     * The band energies of each channel and of the coupling channel, scratch.
     */
    private final double[][] bandEnergy;

    /**
     * The quantized candidate coordinates of each channel and band, scratch.
     */
    private final float[][] candidates;

    /**
     * The end mantissa of each block and slot.
     */
    private final int[][] endMantissa;

    /**
     * The raw exponents of each block and slot.
     */
    private final int[][][] rawExponents;

    /**
     * The exponents the decoder reconstructs, by block and slot.
     */
    private final int[][][] exponents;

    /**
     * The exponent strategy of each block and slot: 0 reuse, 1 D15, 2 D25, 3 D45.
     */
    private final int[][] strategies;

    /**
     * The differential exponent groups to code, by block and slot: the absolute exponent first,
     * then the grouped 7-bit codes.
     */
    private final int[][][] exponentCodes;

    /**
     * The number of entries of {@link #exponentCodes}, by block and slot.
     */
    private final int[][] exponentCodeCount;

    /**
     * The masking curve of each block and slot.
     */
    private final int[][][] masks;

    /**
     * The bit allocation pointers of each block and slot.
     */
    private final int[][][] pointers;

    /**
     * The exponents of each block and slot as runs, which {@link #mantissaBits(int)} tallies.
     */
    private final ExponentRuns[][] runs;

    /**
     * The block whose masking curve, exponent runs and pointers each exponent set of each block
     * shares, filled by {@link #computeMasks()}: the block itself when it carries new exponents,
     * otherwise the origin of the block before it, whose exponents it reuses.
     */
    private final int[][] maskOrigin;

    /**
     * The tally of the exponent set of each slot in the block {@link #mantissaBits(int)} visited
     * last, which the blocks that reuse its exponents add again.
     */
    private final long[] slotTally;

    /**
     * The rematrixing flags of each block.
     */
    private final boolean[][] rematrix = new boolean[6][4];

    /**
     * The number of rematrixing bands of each block.
     */
    private final int[] rematrixBands = new int[6];

    /**
     * Whether the frame uses the frame-based exponent strategies of E-AC-3 ({@code expstre} 0).
     */
    private boolean frameStrategies;

    /**
     * The frame exponent strategy code of each full-bandwidth channel and, at slot
     * {@link #cpl}, of the coupling channel, by candidate dB per bit code.
     */
    private final int[][] frameStrategyCodes;

    /**
     * The frame exponent strategy codes of the strategies applied last, written when
     * {@link #frameStrategies} holds.
     */
    private final int[] appliedFrameStrategyCodes;

    /**
     * The pending mantissa codes of a block, in bit stream order.
     */
    private final int[] tokenValues;

    /**
     * The bit width of each pending mantissa code.
     */
    private final int[] tokenBits;

    /**
     * Whether the exponent sets of six-block frames may use the adaptive hybrid transform.
     */
    private final boolean hybrid;

    /**
     * Whether each slot uses the adaptive hybrid transform in the frame being coded.
     */
    private final boolean[] hybridSlots;

    /**
     * Whether any slot uses the adaptive hybrid transform in the frame being coded ({@code ahte}).
     */
    private boolean hybridFrame;

    /**
     * The quantizers of the adaptive hybrid transform, or {@code null} without it.
     */
    private final AhtEncoder ahtEncoder;

    /**
     * The transform-domain mantissas of each slot, six per bin, normalized by the bin's exponent.
     */
    private final float[][] hybridValues;

    /**
     * The gain-adaptive quantization mode {@code gaqmod} of each slot.
     */
    private final int[] gaqMode;

    /**
     * The gain index of each slot and bin: 0 for gain 1, 1 for gain 2, 2 for gain 4.
     */
    private final int[][] gaqGain;

    /**
     * The dither flag of each full-bandwidth channel in the first block as the analysis set it,
     * before {@link #chooseHybrid()} clears it for the channels coded with the adaptive hybrid
     * transform.
     */
    private final boolean[] firstDither = new boolean[5];

    /**
     * The quantization error energy of each slot coded without the adaptive hybrid transform at
     * the reference SNR offset, scratch of {@link #chooseHybrid()}.
     */
    private final double[] hybridPlainNoise = new double[8];

    /**
     * The high-efficiency bit allocation pointers of a slot at an SNR offset being costed,
     * scratch.
     */
    private final int[] hybridPointers = new int[256];

    /**
     * The six mantissas of one bin, scratch.
     */
    private final float[] hybridIn = new float[6];

    /**
     * The six reconstructed mantissas of one bin, scratch.
     */
    private final float[] hybridOut = new float[6];

    /**
     * The six transform-domain values of one bin, scratch.
     */
    private final float[] hybridBin = new float[6];

    /**
     * The codewords of one gain-adaptive mantissa, scratch.
     */
    private final int[] gaqCodes = new int[2];

    /**
     * The widths of the codewords of one gain-adaptive mantissa, scratch.
     */
    private final int[] gaqWidths = new int[2];

    /**
     * The windowed samples of one block.
     */
    private final float[] windowed = new float[512];

    /**
     * The high-pass filtered samples of one block of up to three channels filtered together.
     */
    private final float[][] filteredGroup = new float[3][256];

    /**
     * The filter state of the padding lanes of a group of fewer than three channels.
     */
    private final float[] paddingState = new float[4];

    /**
     * Whether the transient detector found a transient, by block and channel.
     */
    private final boolean[][] transients;

    /**
     * The segment peaks of a block of filtered samples for the transient detector, laid out as a binary
     * tree: index 1 holds the peak of the whole block, 2 and 3 those of its halves, 4 to 7 those
     * of its quarters.
     */
    private final float[] segmentPeaks = new float[8];

    /**
     * The writer the frames are assembled in, reused from frame to frame.
     */
    private final MsbBitWriter frameWriter = new MsbBitWriter(4096);

    /**
     * The shared exponents of the run being coded, the absolute exponent first.
     */
    private final int[] groupShared = new int[257];

    /**
     * Receives the limit of {@link #limitDifferences(int[], int, int)} coming from the lower indices.
     */
    private final int[] limitUp = new int[257];

    /**
     * Receives the limit of {@link #limitDifferences(int[], int, int)} coming from the higher indices.
     */
    private final int[] limitDown = new int[257];

    /**
     * The smallest raw exponent of each bin over the run being coded.
     */
    private final int[] groupMinimum = new int[256];

    /**
     * The smallest raw exponent of each mantissa over the run the exponent strategy search is
     * costing, grown block by block.
     */
    private final int[] runMinimum = new int[256];

    /**
     * The exponents of a candidate run, as the decoder would reconstruct them.
     */
    private final int[] candidateExponents = new int[256];

    /**
     * The mantissa cost of a candidate run under the first and the last candidate dB per bit code.
     */
    private final long[] pairSums = new long[2];

    /**
     * The masking curve of a candidate run under each candidate dB per bit code.
     */
    private final int[][] candidateMasks = new int[DB_PER_BIT_CODES.length][51];

    /**
     * The bit allocation parameters of each candidate dB per bit code.
     */
    private final BitAllocation.Parameters[] candidateParameters = new BitAllocation.Parameters[DB_PER_BIT_CODES.length];

    /**
     * The cost of each run of blocks and strategy, scratch of the exponent strategy search:
     * {@code runCost[code][first][last][strategy]}, in sixths of a bit.
     */
    private final long[][][][] runCost = new long[DB_PER_BIT_CODES.length][6][7][4];

    /**
     * The cheapest cost of coding the blocks before each block under each candidate dB per bit
     * code, scratch of the exponent strategy search.
     */
    private final long[][] bestCost = new long[DB_PER_BIT_CODES.length][7];

    /**
     * The first block of the last run of the cheapest coding of the blocks before each block, by
     * candidate dB per bit code and slot, or {@code -1} when the set is absent from the block
     * before.
     */
    private final int[][][] bestFirst;

    /**
     * The strategy of the last run of the cheapest coding of the blocks before each block, by
     * candidate dB per bit code and slot.
     */
    private final int[][][] bestStrategy;

    /**
     * The number of strategies costed for each run of blocks of the exponent set being searched,
     * {@code 0} for a run not costed yet: {@code costedStrategies[first][last]}.
     */
    private final int[][] costedStrategies = new int[6][7];

    /**
     * The mantissa cost of the first half of each screened split of a run, by split block.
     */
    private final long[][] splitFirstCost = new long[2][7];

    /**
     * The mantissa cost of the second half of each screened split of a run, by split block.
     */
    private final long[][] splitLastCost = new long[2][7];

    /**
     * The runs of the cheapest cover under each candidate dB per bit code, one bit per run.
     */
    private final long[] chainRuns = new long[DB_PER_BIT_CODES.length];

    /**
     * The smallest raw exponents of a half of a screened split.
     */
    private final int[] splitMinimum = new int[256];

    /**
     * The mantissa cost a coarser strategy adds to a block of the run being screened, per strategy,
     * in sixths of a bit.
     */
    private final double[] splitPenalty = new double[4];

    /**
     * The exponent bits of the run being screened under each strategy.
     */
    private final int[] splitBits = new int[4];

    /**
     * The cost of the frame's exponents under each candidate dB per bit code with the per-block
     * strategy syntax (row 0) and with the frame-based one (row 1), scratch of the search.
     */
    private final long[][] strategySyntaxCost = new long[2][DB_PER_BIT_CODES.length];

    /**
     * Whether the frame-based strategy syntax codes the frame under each candidate dB per bit code.
     */
    private final boolean[] useFrameStrategies = new boolean[DB_PER_BIT_CODES.length];

    /**
     * The open grouped mantissa words of the block being written: the code index and fill of the
     * 3-, 5- and 11-level groups.
     */
    private final int[] grouping = new int[6];

    /**
     * The syntax state of the frame being written.
     */
    private final SyntaxState syntax;

    /**
     * The sink that counts the side information.
     */
    private final BitSink counter = new BitSink(null);

    /**
     * The sink that writes the frame.
     */
    private final BitSink sink = new BitSink(frameWriter);

    /**
     * The biquad coefficients of the 8 kHz high-pass filter: b0, b1, b2, a1, a2.
     */
    private final float[] highPass;

    /**
     * Creates a frame encoder.
     *
     * <p>With spectral extension, the full-bandwidth channels code their coefficients up to the
     * extension region and coupling, when used, ends where the extension region begins; coupling is
     * dropped when the extension region begins at or below the coupling begin frequency. Coupling is
     * used only when the bit rate per channel is low enough to profit and the mode has two or more
     * full-bandwidth channels.
     *
     * @param acmod the audio coding mode, from 1 to 7
     * @param lfe whether an LFE channel is coded
     * @param sampleRate the sampling rate: 32000, 44100 or 48000, or for E-AC-3 also 16000,
     * 22050 or 24000 with six blocks per frame
     * @param bitrate the bit rate of this substream in bits per second: one of A/52 table
     * 5.18 for AC-3, any rate whose frames fit 2048 words for E-AC-3
     * @param dialogueLevel the dialogue level in dBFS, from -31 to -1
     * @param blockSwitching whether short transforms are used on transients
     * @param coupling whether channel coupling may be used
     * @param enhanced whether to write E-AC-3 frames
     * @param blocks the blocks per frame: 6 for AC-3; 1, 2, 3 or 6 for E-AC-3
     * @param tools the E-AC-3 tools; ignored for AC-3
     * @param fastSearch whether frames of more than one block take the faster search too;
     * one-block E-AC-3 frames always take it
     * @param kernel the DSP kernels
     */
    public FrameEncoder(int acmod, boolean lfe, int sampleRate, int bitrate, int dialogueLevel, boolean blockSwitching,
                        boolean coupling, boolean enhanced, int blocks, EncoderTools tools, boolean fastSearch,
                        Ac3Kernel kernel) {
        this.enhanced = enhanced;
        this.blocks = blocks;
        this.acmod = acmod;
        this.lfe = lfe;
        this.channels = Ac3Tables.CHANNELS[acmod];
        this.coded = channels + (lfe ? 1 : 0);
        this.cpl = coded;
        var reduced = sampleRate == 24000 || sampleRate == 22050 || sampleRate == 16000;
        this.fscod = reduced ? 3 : sampleRate == 48000 ? 0 : sampleRate == 44100 ? 1 : 2;
        this.fscod2 = reduced ? (sampleRate == 24000 ? 0 : sampleRate == 22050 ? 1 : 2) : 0;
        if (enhanced) {
            this.bitrateIndex = -1;
        } else {
            var index = 0;
            while (Ac3Configs.BITRATES[index] * 1000 != bitrate) {
                index++;
            }
            this.bitrateIndex = index;
        }
        this.dialnorm = -dialogueLevel;
        this.blockSwitching = blockSwitching;
        this.fastSearch = fastSearch || enhanced && blocks == 1;
        this.probeInterval = blocks == 1 ? PROBE_INTERVAL : LONG_FRAME_PROBE_INTERVAL;
        this.exactWords = bitrate * 256.0 * blocks / sampleRate / 16;
        this.transform = new Transform(kernel);
        var perChannel = bitrate / channels;
        this.transientRatio = perChannel >= RELAXED_TRANSIENTS ? 2 : 1;
        var cutoff = cutoff(perChannel, sampleRate);
        var useCoupling = coupling && acmod >= 2 && perChannel < 80_000;
        var bandwidthBin = (int) Math.round(cutoff * 512.0 / sampleRate);
        this.channelMap = enhanced ? tools.channelMap() : -1;
        this.hybrid = enhanced && blocks == 6 && tools.hybridTransform();
        this.preNoise = enhanced && blocks >= 3 && tools.preNoise()
                ? new PreNoiseAnalysis(channels, 256 * blocks) : null;
        this.preNoiseDecoded = preNoise != null ? new float[2][256] : null;
        this.preNoiseOriginal = preNoise != null ? new float[2][256] : null;
        this.spx = enhanced && tools.spectralExtension()
                ? new SpxAnalysis(tools.spxStartCode(), tools.spxBeginCode(), tools.spxEndCode(), channels) : null;
        var beginBand = 0;
        var endBand = 0;
        if (useCoupling) {
            beginBand = Math.clamp(Math.round((couplingFrequency(perChannel, channels) * 512.0 / sampleRate - 37) / 12), 0, 15);
            if (spx != null) {
                endBand = SpxAnalysis.beginSubband(spx.beginCode) - 2;
                useCoupling = endBand >= beginBand;
            } else {
                endBand = Math.clamp(Math.round((cutoff * 512.0 / sampleRate - 37) / 12) - 1, 0, 17);
                useCoupling = endBand >= 2 && endBand > beginBand;
            }
        }
        this.coupling = useCoupling;
        if (useCoupling) {
            if (spx == null) {
                bandwidthBin = 37 + 12 * (endBand + 1);
            }
            this.couplingBegin = beginBand;
            this.couplingEndCode = endBand - 2;
            this.couplingSubbands = endBand + 1 - beginBand;
        } else {
            this.couplingBegin = 0;
            this.couplingEndCode = 0;
            this.couplingSubbands = 0;
        }
        this.bandwidthCode = Math.max(0, Math.min(60, (bandwidthBin - 37) / 3 - 12));
        var bandwidthEnd = 37 + 3 * (bandwidthCode + 12);
        this.channelEnd = spx != null ? spx.begin : bandwidthEnd;
        this.reconstructed = spx != null ? new float[2][256] : null;
        this.ditherPower = spx != null ? new float[2][256] : null;
        this.couplingStart = 37 + 12 * couplingBegin;
        this.couplingEnd = 37 + 12 * (couplingBegin + couplingSubbands);
        var isDefault = true;
        var bands = 0;
        for (var subband = couplingBegin; subband < couplingBegin + couplingSubbands; subband++) {
            var join = subband > couplingBegin && Ac3Tables.DEFAULT_COUPLING_BANDS[subband];
            couplingStructure[subband] = join;
            isDefault &= subband == couplingBegin || join == Ac3Tables.DEFAULT_COUPLING_BANDS[subband];
            if (!join) {
                couplingBandStart[bands++] = 37 + 12 * subband;
            }
        }
        couplingBandStart[bands] = couplingEnd;
        this.couplingBands = bands;
        this.defaultStructure = isDefault;
        var slots = coded + 1;
        this.history = new float[coded][256];
        this.quantizedCodes = new short[DB_PER_BIT_CODES.length][blocks][coded + 1][256];
        this.chainKeys = new long[DB_PER_BIT_CODES.length][coded + 1];
        this.filterState = new float[channels][4];
        this.transients = new boolean[blocks][channels];
        this.lastPeaks = new float[channels][3];
        this.previousShort = new boolean[channels];
        this.savedHistory = new float[coded][256];
        this.savedFilterState = new float[channels][4];
        this.savedPeaks = new float[channels][3];
        this.savedPreviousShort = new boolean[channels];
        this.coefficients = new float[6][slots][256];
        this.shortBlocks = new boolean[6][coded];
        this.ditherFlags = new boolean[6][coded];
        this.coupled = new boolean[6][channels];
        this.coordinatesSent = new boolean[6][channels];
        this.coordinateMaster = new int[6][channels];
        this.coordinateExponents = new int[6][channels][18];
        this.coordinateMantissas = new int[6][channels][18];
        this.currentCoordinates = new float[channels][18];
        this.blockCoordinates = new float[6][channels][18];
        this.eligible = new boolean[channels];
        this.memberEnergy = new double[channels];
        this.memberError = new double[channels];
        this.slotNoise = new double[slots];
        this.previousNoise = new double[channels];
        Arrays.fill(previousNoise, Double.MAX_VALUE);

        this.bandEnergy = new double[channels + 1][18];
        this.candidates = new float[channels][18];
        this.endMantissa = new int[6][slots];
        this.rawExponents = new int[6][slots][256];
        this.exponents = new int[6][slots][256];
        this.strategies = new int[6][slots];
        this.exponentCodes = new int[6][slots][128];
        this.exponentCodeCount = new int[6][slots];
        this.masks = new int[6][slots][51];
        this.pointers = new int[6][slots][256];
        this.runs = new ExponentRuns[6][slots];
        this.maskOrigin = new int[6][slots];
        this.slotTally = new long[slots];
        for (var b = 0; b < 6; b++) {
            for (var slot = 0; slot < slots; slot++) {
                runs[b][slot] = new ExponentRuns();
            }
        }
        this.hybridSlots = new boolean[slots];
        this.ahtEncoder = hybrid ? new AhtEncoder() : null;
        this.hybridValues = hybrid ? new float[slots][6 * 256] : null;
        this.gaqMode = new int[slots];
        this.gaqGain = hybrid ? new int[slots][256] : null;
        this.tokenValues = new int[hybrid ? 7 * 256 * 13 + 64 : 7 * 256];
        this.tokenBits = new int[tokenValues.length];
        this.frameStrategyCodes = new int[DB_PER_BIT_CODES.length][slots];
        this.appliedFrameStrategyCodes = new int[slots];
        this.bestFirst = new int[DB_PER_BIT_CODES.length][slots][7];
        this.bestStrategy = new int[DB_PER_BIT_CODES.length][slots][7];
        this.syntax = new SyntaxState(channels);
        this.highPass = highPass(sampleRate);
        parameters.set(fscod == 3 ? fscod2 : fscod, SLOW_DECAY_CODE, FAST_DECAY_CODE, SLOW_GAIN_CODE,
                DB_PER_BIT_CODES[0], FLOOR_CODE);
        for (var c = 0; c < DB_PER_BIT_CODES.length; c++) {
            candidateParameters[c] = new BitAllocation.Parameters();
            candidateParameters[c].set(fscod == 3 ? fscod2 : fscod, SLOW_DECAY_CODE, FAST_DECAY_CODE, SLOW_GAIN_CODE,
                    DB_PER_BIT_CODES[c], FLOOR_CODE);
        }
        this.floor = Ac3Tables.FLOOR[FLOOR_CODE];
        this.fastGain = Ac3Tables.FAST_GAIN[FAST_GAIN_CODE];
        this.fastLeak = (FAST_LEAK_CODE << 8) + 768;
        this.slowLeak = (SLOW_LEAK_CODE << 8) + 768;
    }

    /**
     * Builds {@link #RECONSTRUCTION}.
     *
     * @return the reconstructed values, indexed by pointer and code
     */
    private static float[][] reconstruction() {
        var table = new float[LEVELS.length][];
        for (var pointer = 1; pointer < LEVELS.length; pointer++) {
            var levels = LEVELS[pointer];
            table[pointer] = new float[levels];
            for (var code = 0; code < levels; code++) {
                table[pointer][code] = (2 * code - (levels - 1)) / (float) levels;
            }
        }
        return table;
    }

    /**
     * Builds {@link #WINDOW_REVERSED}.
     *
     * @return the reversed window
     */
    private static float[] reversedWindow() {
        var reversed = new float[256];
        for (var n = 0; n < 256; n++) {
            reversed[n] = Ac3Tables.WINDOW[255 - n];
        }
        return reversed;
    }

    /**
     * Builds {@link #RECIPROCAL_LIMIT}.
     *
     * @return the reciprocals, indexed by pointer
     */
    private static float[] reciprocalLimits() {
        var table = new float[16];
        for (var pointer = 6; pointer < 16; pointer++) {
            table[pointer] = 1.0f / (1 << Ac3Tables.MANTISSA_BITS[pointer] - 1);
        }
        return table;
    }

    /**
     * Builds {@link #MANTISSA_TALLY}.
     *
     * @return the tally of each address
     */
    private static long[] mantissaTally() {
        var tally = new long[64];
        for (var address = 0; address < 64; address++) {
            var pointer = Ac3Tables.BAP[address];
            tally[address] = switch (pointer) {
                case 0 -> 0;
                case 1 -> 1L << 16;
                case 2 -> 1L << 32;
                case 4 -> 1L << 48;
                default -> Ac3Tables.MANTISSA_BITS[pointer];
            };
        }
        return tally;
    }

    /**
     * Builds {@link #MANTISSA_COST}.
     *
     * @return the cost of each address
     */
    private static long[] mantissaCost() {
        var sixths = new long[16];
        sixths[1] = 10;
        sixths[2] = 14;
        sixths[3] = 18;
        sixths[4] = 21;
        sixths[5] = 24;
        for (var bap = 6; bap < 16; bap++) {
            sixths[bap] = 6L * Ac3Tables.MANTISSA_BITS[bap];
        }
        var cost = new long[64];
        for (var address = 0; address < 64; address++) {
            cost[address] = sixths[Ac3Tables.BAP[address]];
        }
        return cost;
    }

    /**
     * Returns the audio cutoff for a bit rate per full-bandwidth channel.
     *
     * @param perChannel the bit rate per full-bandwidth channel
     * @param sampleRate the sampling rate
     * @return the cutoff in hertz
     */
    private static double cutoff(int perChannel, int sampleRate) {
        int cutoff;
        if (perChannel >= 96_000) {
            cutoff = 20_000;
        } else if (perChannel >= 80_000) {
            cutoff = 18_000;
        } else if (perChannel >= 64_000) {
            cutoff = 15_500;
        } else if (perChannel >= 48_000) {
            cutoff = 13_000;
        } else if (perChannel >= 40_000) {
            cutoff = 10_000;
        } else {
            cutoff = 7_000;
        }
        return Math.min(cutoff, sampleRate * 0.45);
    }

    /**
     * Returns the coupling begin frequency for a bit rate per full-bandwidth channel.
     *
     * @param perChannel the bit rate per full-bandwidth channel
     * @param channels the number of full-bandwidth channels
     * @return the frequency in hertz
     */
    private static double couplingFrequency(int perChannel, int channels) {
        if (perChannel < 56_000) {
            return 3_500;
        }
        return channels >= 3 ? 9_000 : 4_500;
    }

    /**
     * Designs the second-order Butterworth high-pass filter at 8 kHz of the transient detector.
     *
     * @param sampleRate the sampling rate
     * @return b0, b1, b2, a1, a2
     */
    private static float[] highPass(int sampleRate) {
        var omega = 2 * Math.PI * Math.min(8000, sampleRate * 0.4) / sampleRate;
        var alpha = StrictMath.sin(omega) / (2 * Math.sqrt(0.5));
        var cos = StrictMath.cos(omega);
        var a0 = 1 + alpha;
        return new float[]{(float) ((1 + cos) / 2 / a0), (float) (-(1 + cos) / a0), (float) ((1 + cos) / 2 / a0),
                (float) (-2 * cos / a0), (float) ((1 - alpha) / a0)};
    }

    /**
     * Returns whether the encoder uses channel coupling; a test hook.
     *
     * @return {@code true} when coupling is on
     */
    public boolean coupling() {
        return coupling;
    }

    /**
     * Returns the first coupled mantissa, the coupling begin frequency; a test hook.
     *
     * @return the first mantissa of the coupling channel, or {@code 0} without coupling
     */
    public int couplingStart() {
        return coupling ? couplingStart : 0;
    }

    /**
     * Encodes one frame.
     *
     * @param input the {@code 256 * blocks} samples of each coded channel, in coded order
     * @param offset the index of the first sample in each plane
     * @return the frame
     */
    public byte[] encode(float[][] input, int offset) {
        var frameWords = frameWords();
        var frmsizecod = enhanced ? 0 : 2 * bitrateIndex
                + (frameWords != Ac3Configs.FRAME_WORDS[2 * bitrateIndex][fscod] ? 1 : 0);
        var frameBits = frameWords * 16;
        if (blockSwitching) {
            saveAnalysis();
        }
        if (preNoise != null) {
            for (var ch = 0; ch < channels; ch++) {
                preNoise.push(ch, input[ch], offset);
            }
        }
        appliedCode = -1;
        var probe = !fastSearch || blockCount / blocks % probeInterval == 0;
        codeFrom = probe ? 0 : preferredCode;
        codeTo = probe ? DB_PER_BIT_CODES.length : preferredCode + 1;
        analyzeFrame(input, offset, false);
        var bestCode = codeFrom;
        var bestOffset = 0;
        var bestNoise = Double.MAX_VALUE;
        var applied = -1;
        Arrays.fill(quantizedOffset, -1);
        reuseCode = -1;
        for (var c = codeFrom; c < codeTo; c++) {
            var sideBits = allocate(c, frameWords, frmsizecod);
            applied = c;
            // the auxiliary data exists flag, crcrsv (or encinfo) and crc2 close the frame
            if (sideBits + 18 > frameBits) {
                if (c == codeFrom && blockSwitching) {
                    restoreAnalysis();
                    analyzeFrame(input, offset, true);
                    appliedCode = -1;
                    sideBits = allocate(c, frameWords, frmsizecod);
                } else {
                    continue;
                }
            }
            var candidate = searchOffset(frameBits - sideBits - 18);
            // the noise compares the codes and feeds the coupling decisions of the next frame
            var noise = codeTo - codeFrom > 1 || coupling ? quantizationNoise(candidate, c, bestNoise) : 0;
            if (noise < bestNoise) {
                bestNoise = noise;
                bestOffset = candidate;
                bestCode = c;
                System.arraycopy(slotNoise, 0, previousNoise, 0, channels);
            }
        }
        if (bestCode != applied) {
            allocate(bestCode, frameWords, frmsizecod);
        }
        if (probe) {
            preferredCode = bestCode;
        }
        var low = bestOffset;
        if (low > 0) {
            referenceOffset = low;
        }
        computePointers(low);
        reuseCode = low > 0 && quantizedOffset[bestCode] == low ? bestCode : -1;
        if (spx != null) {
            codeSpectralExtension();
        }
        if (preNoise != null) {
            preNoise.saveTails();
            measurePreNoise();
            // the processing data must fit the bits the offset search left over
            while (low > 0 && writeFrame(null, low, frameWords, frmsizecod, true) + 18 > frameBits) {
                low--;
                computePointers(low);
                reuseCode = low > 0 && quantizedOffset[bestCode] == low ? bestCode : -1;
                if (spx != null) {
                    codeSpectralExtension();
                }
                preNoise.restoreTails();
                measurePreNoise();
            }
        }
        var writer = frameWriter;
        writer.reset();
        writeFrame(writer, low, frameWords, frmsizecod, true);
        reuseCode = -1;
        var used = (int) writer.bitCount();
        var padding = frameBits - 18 - used;
        if (padding < 0) {
            throw new IllegalStateException("AC-3 frame overflow by " + -padding + " bits");
        }
        while (padding > 0) {
            var chunk = Math.min(31, padding);
            writer.write(0, chunk);
            padding -= chunk;
        }
        writer.write(0, 2);
        writer.write(0, 16);
        var frame = writer.toByteArray();
        if (enhanced) {
            var crc = Ac3Crc.compute(frame, 2, frame.length - 4);
            if (crc == 0x0B77) {
                frame[frame.length - 3] ^= 1;
                crc = Ac3Crc.compute(frame, 2, frame.length - 4);
            }
            frame[frame.length - 2] = (byte) (crc >> 8);
            frame[frame.length - 1] = (byte) crc;
        } else {
            var fiveEighths = ((frameWords >> 1) + (frameWords >> 3)) * 2;
            var residue = Ac3Crc.compute(frame, 2, fiveEighths - 2);
            var crc1 = Ac3Crc.prefix(residue, fiveEighths - 2);
            frame[2] = (byte) (crc1 >> 8);
            frame[3] = (byte) crc1;
            var crc2 = Ac3Crc.compute(frame, fiveEighths, frame.length - fiveEighths - 2);
            if (crc2 == 0x0B77) {
                frame[frame.length - 3] ^= 1;
                crc2 = Ac3Crc.compute(frame, fiveEighths, frame.length - fiveEighths - 2);
            }
            frame[frame.length - 2] = (byte) (crc2 >> 8);
            frame[frame.length - 1] = (byte) crc2;
        }
        blockCount += blocks;
        return frame;
    }

    /**
     * Computes the spectral extension coordinates of every block and channel that sends them, from
     * the coefficients the decoder reconstructs below the extension region at the frame's final bit
     * allocation.
     */
    private void codeSpectralExtension() {
        var pair = acmod == 2 ? 2 : 1;
        for (var b = 0; b < blocks; b++) {
            for (var ch = 0; ch < channels; ch++) {
                if (!spx.sent[b][ch]) {
                    continue;
                }
                if (pair == 2 && ch < 2) {
                    if (ch == 1 && spx.sent[b][0]) {
                        spx.code(b, 1, coefficients[b][1], reconstructed[1], ditherPower[1]);
                        continue;
                    }
                    reconstruct(b, 0, reconstructed[0], ditherPower[0]);
                    reconstruct(b, 1, reconstructed[1], ditherPower[1]);
                    unmatrix(b, reconstructed[0], reconstructed[1], ditherPower[0], ditherPower[1]);
                    spx.code(b, ch, coefficients[b][ch], reconstructed[ch], ditherPower[ch]);
                    continue;
                }
                reconstruct(b, ch, reconstructed[0], ditherPower[0]);
                spx.code(b, ch, coefficients[b][ch], reconstructed[0], ditherPower[0]);
            }
        }
    }

    /**
     * Computes the coefficients of a full-bandwidth channel below the extension region as the
     * decoder reconstructs them, before rematrixing: the dequantized mantissas of the channel, and
     * in its coupled range the dequantized coupling channel scaled by the coordinates in effect.
     *
     * @param b the block
     * @param ch the channel
     * @param values receives the coefficients without dither
     * @param variance receives the expected power of the dither of each coefficient
     */
    private void reconstruct(int b, int ch, float[] values, float[] variance) {
        var end = endMantissa[b][ch];
        dequantizeSet(b, ch, spx.copyStart, Math.min(end, spx.begin), ditherFlags[b][ch], values, variance);
        if (end >= spx.begin) {
            return;
        }
        dequantizeSet(b, cpl, end, spx.begin, ditherFlags[b][ch], values, variance);
        var coordinates = blockCoordinates[b][ch];
        for (var band = 0; band < couplingBands; band++) {
            var coordinate = coordinates[band] * 8;
            var power = coordinate * coordinate;
            for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                values[bin] *= coordinate;
                variance[bin] *= power;
            }
        }
    }

    /**
     * Dequantizes a range of mantissas of an exponent set at the pointers of the final bit
     * allocation.
     *
     * @param b the block
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param from the first mantissa
     * @param to one past the last mantissa
     * @param dither whether zero-bit mantissas receive dither
     * @param values receives the dequantized coefficients, zero for zero-bit mantissas
     * @param variance receives the expected dither power of each coefficient
     */
    private void dequantizeSet(int b, int slot, int from, int to, boolean dither, float[] values, float[] variance) {
        var pow = Ac3Tables.POW2_NEG;
        var bap = pointers[b][slot];
        var exps = exponents[b][slot];
        var source = coefficients[b][slot];
        if (hybridSlots[slot]) {
            System.arraycopy(source, from, values, from, Math.max(0, to - from));
            Arrays.fill(variance, from, Math.max(from, to), 0);
            return;
        }
        var cached = reuseCode >= 0 ? quantizedCodes[reuseCode][b][slot] : null;
        var cachedFrom = slot == cpl ? couplingStart : 0;
        var cachedTo = endMantissa[b][slot];
        for (var bin = from; bin < to; bin++) {
            var scale = pow[exps[bin]];
            var pointer = bap[bin];
            if (pointer == 0) {
                values[bin] = 0;
                variance[bin] = dither ? DITHER_POWER * scale * scale : 0;
            } else if (cached != null && bin >= cachedFrom && bin < cachedTo) {
                values[bin] = (pointer <= 5 ? RECONSTRUCTION[pointer][cached[bin]] : cached[bin] * RECIPROCAL_LIMIT[pointer])
                        * scale;
                variance[bin] = 0;
            } else {
                values[bin] = dequantize(source[bin] * Ac3Tables.POW2_POS[exps[bin]], pointer) * scale;
                variance[bin] = 0;
            }
        }
    }

    /**
     * Undoes the rematrixing of a 2/0 block on reconstructed coefficients below the extension
     * region, as the decoder does before spectral extension.
     *
     * @param b the block
     * @param left the left channel, or the sum where rematrixed, rebuilt in place
     * @param right the right channel, or the difference where rematrixed, rebuilt in place
     * @param leftVariance the dither power of the left channel, rebuilt in place
     * @param rightVariance the dither power of the right channel, rebuilt in place
     */
    private void unmatrix(int b, float[] left, float[] right, float[] leftVariance, float[] rightVariance) {
        var limit = Math.min(endMantissa[b][0], endMantissa[b][1]);
        if (couplingInUse[b]) {
            limit = Math.min(limit, couplingStart);
        }
        for (var band = 0; band < rematrixBands[b]; band++) {
            if (!rematrix[b][band]) {
                continue;
            }
            var from = Math.max(spx.copyStart, band == 0 ? 13 : band == 1 ? 25 : band == 2 ? 37 : 61);
            var to = Math.min(band == 0 ? 25 : band == 1 ? 37 : band == 2 ? 61 : 253, limit);
            for (var bin = from; bin < to; bin++) {
                var sum = left[bin];
                var difference = right[bin];
                left[bin] = sum + difference;
                right[bin] = sum - difference;
                var power = leftVariance[bin] + rightVariance[bin];
                leftVariance[bin] = power;
                rightVariance[bin] = power;
            }
        }
    }

    /**
     * Measures the time-domain quantization error of every full-bandwidth channel over the frame
     * at the final bit allocation and chooses the transient pre-noise processing data.
     */
    private void measurePreNoise() {
        var decoded = preNoiseDecoded;
        var original = preNoiseOriginal;
        for (var b = 0; b < blocks; b++) {
            for (var ch = 0; ch < channels; ch++) {
                if (acmod == 2 && ch == 1) {
                    continue;
                }
                decodeChannel(b, ch, decoded[0], original[0]);
                if (acmod == 2) {
                    decodeChannel(b, 1, decoded[1], original[1]);
                    unmatrixAll(b, decoded[0], decoded[1]);
                    unmatrixAll(b, original[0], original[1]);
                }
                for (var c = 0; c < (acmod == 2 ? 2 : 1); c++) {
                    var difference = original[c];
                    var rebuilt = decoded[c];
                    for (var bin = 0; bin < 256; bin++) {
                        difference[bin] -= rebuilt[bin];
                    }
                    preNoise.addBlock(ch + c, b, difference, shortBlocks[b][ch + c], transform);
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            preNoise.choose(ch, highPass);
        }
    }

    /**
     * Computes the coefficients of a full-bandwidth channel of a block as the decoder reconstructs
     * them before rematrixing, without dither, and copies its coded coefficients: the dequantized
     * mantissas, the coupled range from the dequantized coupling channel, and the extension region
     * taken as reproduced exactly.
     *
     * @param b the block
     * @param ch the channel
     * @param decoded receives the reconstructed coefficients
     * @param original receives the coded coefficients
     */
    private void decodeChannel(int b, int ch, float[] decoded, float[] original) {
        var source = coefficients[b][ch];
        System.arraycopy(source, 0, original, 0, 256);
        Arrays.fill(decoded, 0);
        dequantizeInto(b, ch, 0, endMantissa[b][ch], decoded);
        if (couplingInUse[b] && coupled[b][ch]) {
            dequantizeInto(b, cpl, couplingStart, couplingEnd, decoded);
            var coordinates = blockCoordinates[b][ch];
            for (var band = 0; band < couplingBands; band++) {
                var coordinate = coordinates[band] * 8;
                if (acmod == 2 && ch == 1 && phaseFlags[b][band]) {
                    coordinate = -coordinate;
                }
                for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                    decoded[bin] *= coordinate;
                }
            }
        }
        if (spx != null) {
            System.arraycopy(source, spx.begin, decoded, spx.begin, spx.end - spx.begin);
        }
    }

    /**
     * Dequantizes a range of mantissas of an exponent set at the pointers of the final bit
     * allocation, zero-bit mantissas as zero; a set coded with the adaptive hybrid transform is
     * taken as reproduced exactly.
     *
     * @param b the block
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param from the first mantissa
     * @param to one past the last mantissa
     * @param out receives the coefficients
     */
    private void dequantizeInto(int b, int slot, int from, int to, float[] out) {
        var source = coefficients[b][slot];
        if (hybridSlots[slot]) {
            System.arraycopy(source, from, out, from, Math.max(0, to - from));
            return;
        }
        var pow = Ac3Tables.POW2_NEG;
        var inverse = Ac3Tables.POW2_POS;
        var bap = pointers[b][slot];
        var exps = exponents[b][slot];
        for (var bin = from; bin < to; bin++) {
            var pointer = bap[bin];
            var scale = pow[exps[bin]];
            out[bin] = pointer == 0 ? 0 : dequantize(source[bin] * inverse[exps[bin]], pointer) * scale;
        }
    }

    /**
     * Turns the sum and difference of the rematrixed bands of a 2/0 block back into the left and
     * right channel, as the decoder does.
     *
     * @param b the block
     * @param left the left channel, or the sum where rematrixed, rebuilt in place
     * @param right the right channel, or the difference where rematrixed, rebuilt in place
     */
    private void unmatrixAll(int b, float[] left, float[] right) {
        var limit = Math.min(endMantissa[b][0], endMantissa[b][1]);
        if (couplingInUse[b]) {
            limit = Math.min(limit, couplingStart);
        }
        for (var band = 0; band < rematrixBands[b]; band++) {
            if (!rematrix[b][band]) {
                continue;
            }
            var from = band == 0 ? 13 : band == 1 ? 25 : band == 2 ? 37 : 61;
            var to = Math.min(band == 0 ? 25 : band == 1 ? 37 : band == 2 ? 61 : 253, limit);
            for (var bin = from; bin < to; bin++) {
                var sum = left[bin];
                var difference = right[bin];
                left[bin] = sum + difference;
                right[bin] = sum - difference;
            }
        }
    }

    /**
     * Analyzes a frame and decides everything that does not depend on the dB per bit code:
     * transforms, coupling, rematrixing, and the exponent strategies under every candidate code.
     *
     * @param input the samples in coded order
     * @param offset the index of the first sample
     * @param longOnly whether every block uses one long transform, the fallback of a frame whose
     * side information would not fit otherwise
     */
    private void analyzeFrame(float[][] input, int offset, boolean longOnly) {
        analyze(input, offset, longOnly);
        if (coupling) {
            couple();
        } else {
            Arrays.fill(couplingInUse, false);
            Arrays.fill(couplingStrategy, false);
            couplingStrategy[0] = true;
        }
        if (spx != null) {
            spx.analyze(coefficients, blocks);
        }
        if (acmod == 2) {
            chooseRematrixing();
        }
        extractExponents();
        searchStrategies();
    }

    /**
     * Switches the frame to a candidate dB per bit code: codes the exponents the search chose
     * under it and computes the masking curves.
     *
     * @param c the index of the code in {@link #DB_PER_BIT_CODES}
     * @param frameWords the frame size in 16-bit words
     * @param frmsizecod the frame size code of AC-3
     * @return the number of bits before the mantissas
     */
    private int allocate(int c, int frameWords, int frmsizecod) {
        dbPerBitCode = DB_PER_BIT_CODES[c];
        parameters.set(fscod == 3 ? fscod2 : fscod, SLOW_DECAY_CODE, FAST_DECAY_CODE, SLOW_GAIN_CODE, dbPerBitCode,
                FLOOR_CODE);
        if (appliedCode < 0 || !sameStrategies(c, appliedCode)) {
            applyStrategies(c);
        }
        appliedCode = c;
        computeMasks();
        var modes = 0;
        if (hybrid) {
            chooseHybrid();
            for (var slot = 0; slot <= coded; slot++) {
                modes += hybridSlots[slot] ? 2 : 0;
            }
        }
        return writeFrame(null, 0, frameWords, frmsizecod, false) + modes;
    }

    /**
     * Decides which exponent sets of a six-block frame use the adaptive hybrid transform: every
     * set whose exponents are sent once for the six blocks, with long transforms in every block,
     * and whose mantissas cost fewer bits through the transform than without it at the SNR offset
     * of the previous frame.
     */
    private void chooseHybrid() {
        hybridFrame = false;
        Arrays.fill(hybridSlots, false);
        System.arraycopy(firstDither, 0, ditherFlags[0], 0, channels);
        var any = false;
        for (var slot = 0; slot <= coded && !any; slot++) {
            any = hybridEligible(slot);
        }
        if (!any) {
            return;
        }
        var snr = offsetValue(referenceOffset);
        var total = quantizationNoise(referenceOffset, -1, Double.MAX_VALUE);
        var codedCount = 0;
        for (var b = 0; b < 6; b++) {
            for (var slot = 0; slot <= coded; slot++) {
                if (!present(b, slot)) {
                    continue;
                }
                var bap = pointers[b][slot];
                for (var bin = slot == cpl ? couplingStart : 0; bin < endMantissa[b][slot]; bin++) {
                    codedCount += bap[bin] != 0 ? 1 : 0;
                }
            }
        }
        System.arraycopy(slotNoise, 0, hybridPlainNoise, 0, coded + 1);
        for (var slot = 0; slot <= coded; slot++) {
            if (!hybridEligible(slot)) {
                continue;
            }
            prepareHybrid(slot);
            var plain = 0L;
            for (var b = 0; b < 6; b++) {
                var origin = maskOrigin[b][slot];
                plain += runs[origin][slot].tally(masks[origin][slot], snr, floor, MANTISSA_PADDED);
            }
            hybridSlots[slot] = true;
            var saved = plain / 6.0 - 2 - hybridBits(slot, referenceOffset);
            var start = slot == cpl ? couplingStart : 0;
            BitAllocation.pointers(exponents[0][slot], start, endMantissa[0][slot], masks[0][slot], snr, floor,
                    Ac3Tables.HEBAP, pointers[0][slot]);
            var noise = hybridNoise(slot);
            var exchange = StrictMath.pow(10, -0.602 * saved / Math.max(1, codedCount));
            var after = (total - hybridPlainNoise[slot] + noise) * exchange;
            if (after < total) {
                hybridFrame = true;
                if (slot < channels) {
                    ditherFlags[0][slot] = false;
                }
            } else {
                hybridSlots[slot] = false;
            }
        }
    }

    /**
     * Returns whether an exponent set may use the adaptive hybrid transform in the frame: it is
     * present in every block with exponents sent in the first block only, its channel uses long
     * transforms in every block, and for the coupling channel the coupling strategy is sent in the
     * first block only.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return whether the set is eligible
     */
    private boolean hybridEligible(int slot) {
        for (var b = 0; b < 6; b++) {
            if (!present(b, slot) || (strategies[b][slot] != 0) != (b == 0)) {
                return false;
            }
            if (slot < channels && shortBlocks[b][slot] || slot == cpl && b > 0 && couplingStrategy[b]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the number of blocks of the frame in which an exponent set starts a new region: new
     * exponents, and for the coupling channel a new coupling strategy ({@code ncplregs},
     * {@code nchregs}, {@code nlferegs}).
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return the number of regions
     */
    private int regions(int slot) {
        var count = 0;
        for (var b = 0; b < 6; b++) {
            if (strategies[b][slot] != 0 || slot == cpl && couplingStrategy[b]) {
                count++;
            }
        }
        return count;
    }

    /**
     * Transforms the mantissas of an exponent set over the six blocks into the hybrid transform
     * domain, normalized by the exponents of the first block.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     */
    private void prepareHybrid(int slot) {
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[0][slot];
        var exps = exponents[0][slot];
        var values = hybridValues[slot];
        for (var bin = start; bin < end; bin++) {
            var scale = Ac3Tables.POW2_NEG[exps[bin]];
            for (var m = 0; m < 6; m++) {
                hybridIn[m] = coefficients[m][slot][bin] / scale;
            }
            AhtEncoder.forward(hybridIn, hybridBin);
            System.arraycopy(hybridBin, 0, values, 6 * bin, 6);
        }
    }

    /**
     * Returns the mantissa bits of an exponent set coded with the adaptive hybrid transform at a
     * combined SNR offset, gain words included and the two-bit mode excluded, with the cheapest
     * gain-adaptive quantization mode.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param offset the combined offset {@code 16 * csnroffst + fsnroffst}
     * @return the number of bits
     */
    private int hybridBits(int slot, int offset) {
        if (offset == 0) {
            return 0;
        }
        var start = slot == cpl ? couplingStart : 0;
        BitAllocation.pointers(exponents[0][slot], start, endMantissa[0][slot], masks[0][slot], offsetValue(offset),
                floor, Ac3Tables.HEBAP, hybridPointers);
        return chooseGains(slot, hybridPointers, false);
    }

    /**
     * Chooses the gain-adaptive quantization mode of an exponent set coded with the adaptive
     * hybrid transform and the gain of each bin: the combination that codes the mantissas in the
     * fewest bits, the lower mode and the lower gain on ties.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param hebap the high-efficiency bit allocation pointers of the set
     * @param store whether to record the mode and the gains in {@link #gaqMode} and
     * {@link #gaqGain}
     * @return the mantissa bits with the gain words, the two-bit mode excluded
     */
    private int chooseGains(int slot, int[] hebap, boolean store) {
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[0][slot];
        var values = hybridValues[slot];
        var vectors = 0;
        var plain = 0;
        var mode1 = 0;
        var mode2 = 0;
        var mode3 = 0;
        var active = 0;
        for (var bin = start; bin < end; bin++) {
            var pointer = hebap[bin];
            if (pointer == 0) {
                continue;
            }
            if (pointer < 8) {
                vectors += Ac3Tables.VQ_BITS[pointer];
                continue;
            }
            System.arraycopy(values, 6 * bin, hybridBin, 0, 6);
            var bits0 = AhtEncoder.bits(pointer, 0, hybridBin);
            plain += bits0;
            if (pointer < 17) {
                var bits1 = AhtEncoder.bits(pointer, 1, hybridBin);
                var bits2 = AhtEncoder.bits(pointer, 2, hybridBin);
                mode1 += pointer < 12 ? 1 + Math.min(bits0, bits1) : bits0;
                mode2 += 1 + Math.min(bits0, bits2);
                mode3 += Math.min(bits0, Math.min(bits1, bits2));
                active++;
            } else {
                mode1 += bits0;
                mode2 += bits0;
                mode3 += bits0;
            }
        }
        mode3 += 5 * ((active + 2) / 3);
        var mode = 0;
        var best = plain;
        if (mode1 < best) {
            mode = 1;
            best = mode1;
        }
        if (mode2 < best) {
            mode = 2;
            best = mode2;
        }
        if (mode3 < best) {
            mode = 3;
            best = mode3;
        }
        if (store) {
            gaqMode[slot] = mode;
            var gains = gaqGain[slot];
            for (var bin = start; bin < end; bin++) {
                var pointer = hebap[bin];
                gains[bin] = 0;
                if (mode == 0 || pointer < 8 || pointer >= (mode == 1 ? 12 : 17)) {
                    continue;
                }
                System.arraycopy(values, 6 * bin, hybridBin, 0, 6);
                var bits0 = AhtEncoder.bits(pointer, 0, hybridBin);
                var bits1 = mode == 2 ? Integer.MAX_VALUE : AhtEncoder.bits(pointer, 1, hybridBin);
                var bits2 = mode == 1 ? Integer.MAX_VALUE : AhtEncoder.bits(pointer, 2, hybridBin);
                if (bits1 < bits0 && bits1 <= bits2) {
                    gains[bin] = 1;
                } else if (bits2 < bits0 && bits2 < bits1) {
                    gains[bin] = 2;
                }
            }
        }
        return vectors + best;
    }

    /**
     * Returns the energy of the quantization error of an exponent set coded with the adaptive
     * hybrid transform over the six blocks, at the pointers in the first block of
     * {@link #pointers}.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return the error energy
     */
    private double hybridNoise(int slot) {
        var hebap = pointers[0][slot];
        chooseGains(slot, hebap, true);
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[0][slot];
        var exps = exponents[0][slot];
        var energy = 0.0;
        for (var bin = start; bin < end; bin++) {
            var scale = Ac3Tables.POW2_NEG[exps[bin]];
            hybridReconstruct(slot, bin, hebap[bin], -1);
            for (var m = 0; m < 6; m++) {
                var error = coefficients[m][slot][bin] - hybridOut[m] * scale;
                energy += (double) error * error;
            }
        }
        return energy;
    }

    /**
     * Quantizes the six mantissas of one bin of an exponent set coded with the adaptive hybrid
     * transform into {@link #hybridOut}, as the decoder reconstructs them before the exponent
     * scaling, and optionally queues their codes.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param bin the coefficient index
     * @param pointer the high-efficiency bit allocation pointer of the bin
     * @param tokens the number of pending codes to append the bin's codes to, or {@code -1} to
     * queue nothing
     * @return the new number of pending codes, or {@code -1}
     */
    private int hybridReconstruct(int slot, int bin, int pointer, int tokens) {
        if (pointer == 0) {
            Arrays.fill(hybridOut, 0);
            return tokens;
        }
        System.arraycopy(hybridValues[slot], 6 * bin, hybridBin, 0, 6);
        if (pointer < 8) {
            var index = ahtEncoder.nearest(pointer, hybridBin);
            Aht.vector(pointer, index, hybridIn);
            if (tokens >= 0) {
                tokenValues[tokens] = index;
                tokenBits[tokens++] = Ac3Tables.VQ_BITS[pointer];
            }
        } else {
            var gain = gaqGain[slot][bin];
            for (var n = 0; n < 6; n++) {
                hybridIn[n] = AhtEncoder.quantize(pointer, gain, hybridBin[n], gaqCodes, gaqWidths);
                if (tokens >= 0) {
                    tokenValues[tokens] = gaqCodes[0];
                    tokenBits[tokens++] = gaqWidths[0];
                    if (gaqWidths[1] > 0) {
                        tokenValues[tokens] = gaqCodes[1];
                        tokenBits[tokens++] = gaqWidths[1];
                    }
                }
            }
        }
        Aht.inverse(hybridIn, hybridOut);
        return tokens;
    }

    /**
     * Queues the codes of an exponent set coded with the adaptive hybrid transform, all six
     * blocks of it in the first block (A/52 Annex E section 3.4): the gain-adaptive quantization
     * mode, the gains of the active bins, then the codes of every bin.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param tokens the number of pending codes
     * @return the new number of pending codes
     */
    private int quantizeHybrid(int slot, int tokens) {
        var hebap = pointers[0][slot];
        chooseGains(slot, hebap, true);
        var mode = gaqMode[slot];
        tokenValues[tokens] = mode;
        tokenBits[tokens++] = 2;
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[0][slot];
        var gains = gaqGain[slot];
        if (mode != 0) {
            var top = mode == 1 ? 12 : 17;
            var group = 0;
            var filled = 0;
            for (var bin = start; bin < end; bin++) {
                if (hebap[bin] < 8 || hebap[bin] >= top) {
                    continue;
                }
                if (mode < 3) {
                    tokenValues[tokens] = gains[bin] != 0 ? 1 : 0;
                    tokenBits[tokens++] = 1;
                    continue;
                }
                group = group * 3 + gains[bin];
                if (++filled == 3) {
                    tokenValues[tokens] = group;
                    tokenBits[tokens++] = 5;
                    group = 0;
                    filled = 0;
                }
            }
            if (filled > 0) {
                while (filled++ < 3) {
                    group *= 3;
                }
                tokenValues[tokens] = group;
                tokenBits[tokens++] = 5;
            }
        }
        for (var bin = start; bin < end; bin++) {
            tokens = hybridReconstruct(slot, bin, hebap[bin], tokens);
        }
        return tokens;
    }

    /**
     * Saves the analysis state that {@link #analyze(float[][], int, boolean)} advances: the
     * overlap history, the transient detector and the last block's transform length.
     */
    private void saveAnalysis() {
        for (var ch = 0; ch < coded; ch++) {
            System.arraycopy(history[ch], 0, savedHistory[ch], 0, 256);
        }
        for (var ch = 0; ch < channels; ch++) {
            System.arraycopy(filterState[ch], 0, savedFilterState[ch], 0, 4);
            System.arraycopy(lastPeaks[ch], 0, savedPeaks[ch], 0, 3);
        }
        System.arraycopy(previousShort, 0, savedPreviousShort, 0, channels);
    }

    /**
     * Restores the analysis state {@link #saveAnalysis()} saved.
     */
    private void restoreAnalysis() {
        for (var ch = 0; ch < coded; ch++) {
            System.arraycopy(savedHistory[ch], 0, history[ch], 0, 256);
        }
        for (var ch = 0; ch < channels; ch++) {
            System.arraycopy(savedFilterState[ch], 0, filterState[ch], 0, 4);
            System.arraycopy(savedPeaks[ch], 0, lastPeaks[ch], 0, 3);
        }
        System.arraycopy(savedPreviousShort, 0, previousShort, 0, channels);
    }

    /**
     * Returns the largest combined SNR offset whose mantissas fit a budget.
     *
     * @param budget the bits left for the mantissas
     * @return the combined offset {@code 16 * csnroffst + fsnroffst}
     */
    private int searchOffset(int budget) {
        var low = 0;
        var high = 63 * 16 + 15;
        while (low < high) {
            var mid = (low + high + 1) >>> 1;
            if (mantissaBits(mid) <= budget) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * Returns the energy of the quantization error of every exponent set of the frame at a
     * combined SNR offset: the error of every coded mantissa plus the energy of every mantissa
     * that receives no bits.
     *
     * <p>When {@code code} is not negative, the quantized mantissas of the non-hybrid sets are kept
     * in {@link #quantizedCodes} for {@link #quantize(int, int, int, int, int[])}.
     *
     * @param offset the combined offset {@code 16 * csnroffst + fsnroffst}
     * @param code the index of the dB per bit code whose cache receives the quantized mantissas,
     * or {@code -1} for none
     * @param bound the error energy beyond which the caller does not need the exact value
     * @return the error energy, or the partial energy of the blocks costed so far when it exceeds
     * {@code bound}; the quantized mantissas are then incomplete
     */
    private double quantizationNoise(int offset, int code, double bound) {
        computePointers(offset);
        var pow = Ac3Tables.POW2_NEG;
        var inverse = Ac3Tables.POW2_POS;
        var noise = 0.0;
        Arrays.fill(slotNoise, 0);
        for (var b = 0; b < blocks; b++) {
            for (var slot = 0; slot <= coded; slot++) {
                if (!present(b, slot)) {
                    continue;
                }
                if (hybridSlots[slot]) {
                    if (b == 0) {
                        var energy = hybridNoise(slot);
                        slotNoise[slot] += energy;
                        noise += energy;
                    }
                    continue;
                }
                var start = slot == cpl ? couplingStart : 0;
                var end = endMantissa[b][slot];
                var bap = pointers[b][slot];
                var exps = exponents[b][slot];
                var values = coefficients[b][slot];
                var cache = code >= 0 ? quantizedCodes[code][b][slot] : null;
                var energy = 0.0;
                for (var bin = start; bin < end; bin++) {
                    var value = values[bin];
                    var pointer = bap[bin];
                    if (pointer == 0) {
                        energy += (double) value * value;
                        continue;
                    }
                    var scale = pow[exps[bin]];
                    var mantissa = value * inverse[exps[bin]];
                    int quantized;
                    float reconstructed;
                    if (pointer <= 5) {
                        quantized = symmetric(mantissa, LEVELS[pointer]);
                        reconstructed = RECONSTRUCTION[pointer][quantized];
                    } else {
                        var limit = 1 << Ac3Tables.MANTISSA_BITS[pointer] - 1;
                        quantized = (int) Math.floor(mantissa * limit + 0.5f);
                        quantized = Math.max(-limit, Math.min(limit - 1, quantized));
                        reconstructed = quantized * RECIPROCAL_LIMIT[pointer];
                    }
                    if (cache != null) {
                        cache[bin] = (short) quantized;
                    }
                    var error = value - reconstructed * scale;
                    energy += (double) error * error;
                }
                slotNoise[slot] += energy;
                noise += energy;
            }
            if (noise > bound) {
                return noise;
            }
        }
        if (code >= 0) {
            quantizedOffset[code] = offset;
        }
        return noise;
    }

    /**
     * Quantizes a normalized mantissa with the quantizer of a bit allocation pointer and returns
     * the value the decoder reconstructs (A/52 section 7.3).
     *
     * @param mantissa the normalized mantissa
     * @param pointer the bit allocation pointer, from 1 to 15
     * @return the reconstructed mantissa
     */
    private static float dequantize(float mantissa, int pointer) {
        if (pointer <= 5) {
            return RECONSTRUCTION[pointer][symmetric(mantissa, LEVELS[pointer])];
        }
        var bits = Ac3Tables.MANTISSA_BITS[pointer];
        var limit = 1 << (bits - 1);
        var quantized = (int) Math.floor(mantissa * limit + 0.5f);
        quantized = Math.max(-limit, Math.min(limit - 1, quantized));
        return quantized * RECIPROCAL_LIMIT[pointer];
    }

    /**
     * Returns the size of the next frame, alternating two sizes when the bit rate does not divide
     * into whole words so that the average bit rate is exact.
     *
     * @return the frame size in 16-bit words
     */
    private int frameWords() {
        if (enhanced) {
            var base = (int) exactWords;
            paddingDebt += exactWords - base;
            if (paddingDebt >= 1) {
                paddingDebt -= 1;
                return base + 1;
            }
            return base;
        }
        var base = Ac3Configs.FRAME_WORDS[2 * bitrateIndex][fscod];
        if (fscod != 1) {
            return base;
        }
        paddingDebt += exactWords - base;
        if (paddingDebt >= 1) {
            paddingDebt -= 1;
            return base + 1;
        }
        return base;
    }

    /**
     * Windows and transforms every block of every channel, detecting transients.
     *
     * @param input the samples in coded order
     * @param offset the index of the first sample
     * @param longOnly whether to use long transforms only; the transient detector still runs
     */
    private void analyze(float[][] input, int offset, boolean longOnly) {
        var w = Ac3Tables.WINDOW;
        var reversed = WINDOW_REVERSED;
        if (blockSwitching) {
            detectTransients(input, offset);
        }
        for (var ch = 0; ch < coded; ch++) {
            var isLfe = lfe && ch == coded - 1;
            var past = history[ch];
            var plane = input[ch];
            for (var b = 0; b < blocks; b++) {
                var start = offset + 256 * b;
                for (var n = 0; n < 256; n++) {
                    windowed[n] = past[n] * w[n];
                    windowed[256 + n] = plane[start + n] * reversed[n];
                }
                var shortBlock = !isLfe && blockSwitching && transients[b][ch] && !longOnly;
                shortBlocks[b][ch] = shortBlock;
                ditherFlags[b][ch] = !isLfe && !shortBlock && !(b == 0 ? ch < channels && previousShort[ch]
                        : shortBlocks[b - 1][ch]);
                var out = coefficients[b][ch];
                transform.forward(windowed, shortBlock, out);
                for (var bin = 0; bin < 256; bin++) {
                    out[bin] = Math.max(-MAX_COEFFICIENT, Math.min(MAX_COEFFICIENT, out[bin]));
                }
                System.arraycopy(plane, start, past, 0, 256);
            }
            if (ch < channels) {
                previousShort[ch] = shortBlocks[blocks - 1][ch];
                firstDither[ch] = ditherFlags[0][ch];
            }
        }
    }

    /**
     * Returns the exponent of a coefficient: its number of leading zeros, at most 24.
     *
     * @param value the coefficient, below one in magnitude
     * @return the exponent
     */
    private static int exponentOf(float value) {
        return Math.min(24, 126 - (Float.floatToRawIntBits(value) >>> 23 & 0xFF));
    }

    /**
     * Runs the transient detector of A/52 section 8.2.2 on the 256 new samples of a block, with
     * the peak ratios scaled by {@link #transientRatio}.
     *
     * @param ch the channel
     * @param filtered the high-pass filtered samples of the block
     * @return whether the block has a transient
     */
    private boolean detectTransient(int ch, float[] filtered) {
        var quarters = segmentPeaks;
        for (var segment = 0; segment < 4; segment++) {
            quarters[4 + segment] = peak(filtered, 64 * segment, 64);
        }
        quarters[2] = Math.max(quarters[4], quarters[5]);
        quarters[3] = Math.max(quarters[6], quarters[7]);
        quarters[1] = Math.max(quarters[2], quarters[3]);
        var peaks = lastPeaks[ch];
        var detected = false;
        var overall = quarters[1];
        for (var level = 0; level < 3; level++) {
            var segments = 1 << level;
            var previous = peaks[level];
            for (var segment = 0; segment < segments; segment++) {
                var current = quarters[segments + segment];
                if (overall >= SILENCE && current * THRESHOLDS[level] > previous * transientRatio) {
                    detected = true;
                }
                previous = current;
            }
            peaks[level] = previous;
        }
        return detected;
    }

    /**
     * Returns the largest magnitude of a segment of a block of filtered samples.
     *
     * @param filtered the filtered samples of a block
     * @param from the first sample
     * @param length the number of samples
     * @return the peak
     */
    private static float peak(float[] filtered, int from, int length) {
        var peak = 0.0f;
        for (var n = from; n < from + length; n++) {
            peak = Math.max(peak, Math.abs(filtered[n]));
        }
        return peak;
    }

    /**
     * Runs the transient detector on every block of the frame for every full-bandwidth channel
     * that has one, filtering up to three channels at once so that their independent filter
     * recurrences overlap, and records the results in {@link #transients}.
     *
     * @param input the samples in coded order
     * @param offset the index of the first sample
     */
    private void detectTransients(float[][] input, int offset) {
        var detected = lfe ? coded - 1 : coded;
        for (var b = 0; b < blocks; b++) {
            var start = offset + 256 * b;
            for (var first = 0; first < detected; first += 3) {
                var second = Math.min(first + 1, detected - 1);
                var third = Math.min(first + 2, detected - 1);
                filterGroup(input[first], input[second], input[third], start,
                        filterState[first], second == first ? paddingState : filterState[second],
                        third == second ? paddingState : filterState[third]);
                for (var ch = first; ch <= third && ch < first + 3 && ch < detected; ch++) {
                    transients[b][ch] = detectTransient(ch, filteredGroup[ch - first]);
                }
            }
        }
    }

    /**
     * Filters 256 samples of three channels with the high-pass filter of the transient detector,
     * into {@link #filteredGroup}, advancing their filter states.
     *
     * @param planeA the samples of the first channel
     * @param planeB the samples of the second channel
     * @param planeC the samples of the third channel
     * @param start the index of the first sample
     * @param stateA the filter state of the first channel
     * @param stateB the filter state of the second channel
     * @param stateC the filter state of the third channel
     */
    private void filterGroup(float[] planeA, float[] planeB, float[] planeC, int start, float[] stateA, float[] stateB,
                             float[] stateC) {
        var b0 = highPass[0];
        var b1 = highPass[1];
        var b2 = highPass[2];
        var a1 = highPass[3];
        var a2 = highPass[4];
        var outA = filteredGroup[0];
        var outB = filteredGroup[1];
        var outC = filteredGroup[2];
        var xa1 = stateA[0];
        var xa2 = stateA[1];
        var ya1 = stateA[2];
        var ya2 = stateA[3];
        var xb1 = stateB[0];
        var xb2 = stateB[1];
        var yb1 = stateB[2];
        var yb2 = stateB[3];
        var xc1 = stateC[0];
        var xc2 = stateC[1];
        var yc1 = stateC[2];
        var yc2 = stateC[3];
        for (var n = 0; n < 256; n++) {
            var xa = planeA[start + n];
            var xb = planeB[start + n];
            var xc = planeC[start + n];
            var ya = b0 * xa + b1 * xa1 + b2 * xa2 - a1 * ya1 - a2 * ya2;
            var yb = b0 * xb + b1 * xb1 + b2 * xb2 - a1 * yb1 - a2 * yb2;
            var yc = b0 * xc + b1 * xc1 + b2 * xc2 - a1 * yc1 - a2 * yc2;
            xa2 = xa1;
            xa1 = xa;
            ya2 = ya1;
            ya1 = ya;
            xb2 = xb1;
            xb1 = xb;
            yb2 = yb1;
            yb1 = yb;
            xc2 = xc1;
            xc1 = xc;
            yc2 = yc1;
            yc1 = yc;
            outA[n] = ya;
            outB[n] = yb;
            outC[n] = yc;
        }
        stateA[0] = xa1;
        stateA[1] = xa2;
        stateA[2] = ya1;
        stateA[3] = ya2;
        stateB[0] = xb1;
        stateB[1] = xb2;
        stateB[2] = yb1;
        stateB[3] = yb2;
        stateC[0] = xc1;
        stateC[1] = xc2;
        stateC[2] = yc1;
        stateC[3] = yc2;
    }

    /**
     * Decides the coupling of every block and forms the coupling channel and the coordinates.
     *
     * <p>A block couples the full-bandwidth channels that use long transforms and that
     * {@link #selectMembers()} allows, when there are at least two of them; in E-AC-3 a frame
     * that cannot couple in block 0 does not couple at all. A block that couples another set of
     * channels than the block before it
     * carries a new coupling strategy and new coordinates for every coupled channel; otherwise the
     * coordinates of a channel are sent again when its band energy ratio drifts from the ratio in
     * effect by more than {@link #COORDINATE_DRIFT}, or when the phase flags change.
     */
    private void couple() {
        selectMembers();
        for (var b = 0; b < blocks; b++) {
            var count = 0;
            for (var ch = 0; ch < channels; ch++) {
                if (!shortBlocks[b][ch] && eligible[ch]) {
                    count++;
                }
            }
            var inUse = count >= 2 && (!enhanced || b == 0 || couplingInUse[0]);
            couplingInUse[b] = inUse;
            var changed = b == 0 || inUse != couplingInUse[b - 1];
            for (var ch = 0; ch < channels; ch++) {
                var member = inUse && !shortBlocks[b][ch] && eligible[ch];
                changed |= b > 0 && member != coupled[b - 1][ch];
                coupled[b][ch] = member;
                coordinatesSent[b][ch] = false;
            }
            couplingStrategy[b] = changed;
            if (!inUse) {
                continue;
            }
            var phaseChanged = false;
            if (acmod == 2) {
                var left = coefficients[b][0];
                var right = coefficients[b][1];
                for (var band = 0; band < couplingBands; band++) {
                    var correlation = 0.0;
                    for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                        correlation += (double) left[bin] * right[bin];
                    }
                    candidatePhase[band] = correlation < 0;
                    phaseChanged |= !changed && candidatePhase[band] != currentPhase[band];
                }
                if (changed || phaseChanged) {
                    System.arraycopy(candidatePhase, 0, currentPhase, 0, 18);
                }
            }
            System.arraycopy(currentPhase, 0, phaseFlags[b], 0, 18);
            formCouplingChannel(b, count);
            measureBands(b);
            for (var ch = 0; ch < channels; ch++) {
                if (!coupled[b][ch]) {
                    continue;
                }
                var send = changed || phaseChanged;
                if (!send) {
                    var current = currentCoordinates[ch];
                    var candidate = candidates[ch];
                    for (var band = 0; band < couplingBands && !send; band++) {
                        var a = candidate[band];
                        var c = current[band];
                        var ratio = (a * a + 1e-12f) / (c * c + 1e-12f);
                        send = ratio > COORDINATE_DRIFT || ratio < 1 / COORDINATE_DRIFT;
                    }
                }
                if (send) {
                    coordinatesSent[b][ch] = true;
                    quantizeCoordinates(b, ch);
                }
                System.arraycopy(currentCoordinates[ch], 0, blockCoordinates[b][ch], 0, couplingBands);
            }
        }
    }

    /**
     * Decides which full-bandwidth channels may be coupled in the frame.
     *
     * <p>For every block with two or more long-transform channels, the coupling channel of all of
     * them is formed and each channel is rebuilt from it with energy-preserving coordinates, as
     * the decoder would. A channel is kept out of coupling when its rebuilt coupling region, summed
     * over the frame, misses its own by more than {@link #MEMBER_QUALITY} of its energy, which
     * means it carries content the others lack, or when that error plus the
     * {@link #COORDINATE_ERROR} of its coordinates exceeds the quantization error the channel had
     * in the previous frame, which means coupling would add more noise than the bits it saves can
     * remove.
     */
    private void selectMembers() {
        Arrays.fill(memberEnergy, 0);
        Arrays.fill(memberError, 0);
        for (var b = 0; b < blocks; b++) {
            var count = 0;
            for (var ch = 0; ch < channels; ch++) {
                coupled[b][ch] = !shortBlocks[b][ch];
                count += coupled[b][ch] ? 1 : 0;
            }
            if (count < 2) {
                continue;
            }
            if (acmod == 2) {
                var left = coefficients[b][0];
                var right = coefficients[b][1];
                for (var band = 0; band < couplingBands; band++) {
                    var correlation = 0.0;
                    for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                        correlation += (double) left[bin] * right[bin];
                    }
                    phaseFlags[b][band] = correlation < 0;
                }
            }
            formCouplingChannel(b, count);
            var couplingChannel = coefficients[b][cpl];
            for (var band = 0; band < couplingBands; band++) {
                var couplingPower = 0.0;
                for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                    couplingPower += (double) couplingChannel[bin] * couplingChannel[bin];
                }
                for (var ch = 0; ch < channels; ch++) {
                    if (!coupled[b][ch]) {
                        continue;
                    }
                    var source = coefficients[b][ch];
                    var sign = acmod == 2 && ch == 1 && phaseFlags[b][band] ? -1.0 : 1.0;
                    var power = 0.0;
                    var cross = 0.0;
                    for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                        power += (double) source[bin] * source[bin];
                        cross += (double) source[bin] * couplingChannel[bin] * sign;
                    }
                    memberEnergy[ch] += power;
                    if (couplingPower > 0) {
                        memberError[ch] += 2 * (power - Math.sqrt(power / couplingPower) * cross);
                    } else {
                        memberError[ch] += power;
                    }
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            eligible[ch] = memberError[ch] <= memberEnergy[ch] * MEMBER_QUALITY
                    && memberError[ch] + memberEnergy[ch] * COORDINATE_ERROR <= previousNoise[ch] * NOISE_RATIO;
        }
    }

    /**
     * Forms the coupling channel of a block: the average of the coupled channels, with the right
     * channel of a 2/0 program sign-flipped in the bands whose phase flag is set.
     *
     * @param b the block
     * @param count the number of coupled channels
     */
    private void formCouplingChannel(int b, int count) {
        var target = coefficients[b][cpl];
        Arrays.fill(target, 0);
        var scale = 1.0f / count;
        for (var ch = 0; ch < channels; ch++) {
            if (!coupled[b][ch]) {
                continue;
            }
            var source = coefficients[b][ch];
            for (var band = 0; band < couplingBands; band++) {
                var sign = acmod == 2 && ch == 1 && phaseFlags[b][band] ? -scale : scale;
                for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                    target[bin] += source[bin] * sign;
                }
            }
        }
    }

    /**
     * Computes the band energies of the coupled channels and the coupling channel of a block, and
     * the quantized coordinate each coupled channel would get from them.
     *
     * @param b the block
     */
    private void measureBands(int b) {
        var couplingChannel = coefficients[b][cpl];
        var couplingPower = bandEnergy[channels];
        for (var band = 0; band < couplingBands; band++) {
            var sum = 0.0;
            for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                sum += (double) couplingChannel[bin] * couplingChannel[bin];
            }
            couplingPower[band] = sum;
        }
        for (var ch = 0; ch < channels; ch++) {
            if (!coupled[b][ch]) {
                continue;
            }
            var source = coefficients[b][ch];
            var power = bandEnergy[ch];
            var candidate = candidates[ch];
            for (var band = 0; band < couplingBands; band++) {
                var sum = 0.0;
                for (var bin = couplingBandStart[band]; bin < couplingBandStart[band + 1]; bin++) {
                    sum += (double) source[bin] * source[bin];
                }
                power[band] = sum;
                var value = couplingPower[band] > 0 ? (float) (Math.sqrt(sum / couplingPower[band]) / 8) : 0;
                candidate[band] = Math.min(value, 0.96875f);
            }
        }
    }

    /**
     * Quantizes the candidate coordinates of a channel into the coordinate codes of a block
     * (A/52 section 7.4.3) and makes them the coordinates in effect.
     *
     * @param b the block
     * @param ch the channel
     */
    private void quantizeCoordinates(int b, int ch) {
        var candidate = candidates[ch];
        var smallest = 24;
        for (var band = 0; band < couplingBands; band++) {
            smallest = Math.min(smallest, leadingZeros(candidate[band]));
        }
        var master = Math.min(3, smallest / 3);
        coordinateMaster[b][ch] = master;
        var exps = coordinateExponents[b][ch];
        var mants = coordinateMantissas[b][ch];
        var current = currentCoordinates[ch];
        var pow = Ac3Tables.POW2_NEG;
        for (var band = 0; band < couplingBands; band++) {
            var value = candidate[band];
            var exponent = leadingZeros(value) - 3 * master;
            int mantissa;
            if (exponent >= 15) {
                exponent = 15;
                mantissa = Math.min(15, Math.round(value * (1 << (15 + 3 * master)) * 16));
            } else {
                mantissa = Math.round(value * (1 << (exponent + 3 * master)) * 32) - 16;
                if (mantissa >= 16) {
                    if (exponent > 0) {
                        exponent--;
                        mantissa = 0;
                    } else {
                        mantissa = 15;
                    }
                }
                mantissa = Math.max(0, mantissa);
            }
            exps[band] = exponent;
            mants[band] = mantissa;
            var fraction = exponent == 15 ? mantissa / 16.0f : (mantissa + 16) / 32.0f;
            current[band] = fraction * pow[exponent + 3 * master];
        }
    }

    /**
     * Returns the number of leading zeros of a coordinate: the smallest {@code e} with
     * {@code value * 2^e >= 0.5}, at most 24.
     *
     * @param value the coordinate, from 0 to below 1
     * @return the count
     */
    private static int leadingZeros(float value) {
        if (value < 0x1p-25f) {
            return 24;
        }
        return Math.min(24, -Math.getExponent(value) - 1);
    }

    /**
     * Returns the number of rematrixing bands of a block (A/52 table 5.15).
     *
     * @param b the block
     * @return the band count
     */
    private int rematrixBandCount(int b) {
        if (!couplingInUse[b]) {
            return spx == null || spx.beginCode >= 2 ? 4 : 3;
        }
        if (couplingBegin == 0) {
            return 2;
        }
        return couplingBegin <= 2 ? 3 : 4;
    }

    /**
     * Chooses the rematrixing flags of each 2/0 block (A/52 section 7.5.3): a band codes the sum
     * and difference when the smaller of their energies is below the smaller channel energy.
     */
    private void chooseRematrixing() {
        for (var b = 0; b < blocks; b++) {
            var flags = rematrix[b];
            Arrays.fill(flags, false);
            var bands = rematrixBandCount(b);
            rematrixBands[b] = bands;
            if (shortBlocks[b][0] != shortBlocks[b][1]) {
                continue;
            }
            var limit = couplingInUse[b] ? Math.min(channelEnd, couplingStart) : channelEnd;
            var left = coefficients[b][0];
            var right = coefficients[b][1];
            for (var band = 0; band < bands; band++) {
                var from = band == 0 ? 13 : band == 1 ? 25 : band == 2 ? 37 : 61;
                var to = Math.min(band == 0 ? 25 : band == 1 ? 37 : band == 2 ? 61 : 253, limit);
                double l = 0;
                double r = 0;
                double sum = 0;
                double difference = 0;
                for (var bin = from; bin < to; bin++) {
                    var a = left[bin];
                    var c = right[bin];
                    l += a * a;
                    r += c * c;
                    var s = 0.5 * (a + c);
                    var d = 0.5 * (a - c);
                    sum += s * s;
                    difference += d * d;
                }
                if (Math.min(sum, difference) < Math.min(l, r)) {
                    flags[band] = true;
                    for (var bin = from; bin < to; bin++) {
                        var a = left[bin];
                        var c = right[bin];
                        left[bin] = 0.5f * (a + c);
                        right[bin] = 0.5f * (a - c);
                    }
                }
            }
        }
    }

    /**
     * Records the coded range of every exponent set and extracts the raw exponents.
     */
    private void extractExponents() {
        for (var b = 0; b < blocks; b++) {
            for (var ch = 0; ch < coded; ch++) {
                var isLfe = lfe && ch == coded - 1;
                var end = isLfe ? 7 : ch < channels && coupled[b][ch] && couplingInUse[b] ? couplingStart : channelEnd;
                endMantissa[b][ch] = end;
                var raw = rawExponents[b][ch];
                var values = coefficients[b][ch];
                for (var bin = 0; bin < end; bin++) {
                    raw[bin] = exponentOf(values[bin]);
                }
            }
            endMantissa[b][cpl] = couplingInUse[b] ? couplingEnd : 0;
            if (couplingInUse[b]) {
                var raw = rawExponents[b][cpl];
                var values = coefficients[b][cpl];
                for (var bin = couplingStart; bin < couplingEnd; bin++) {
                    raw[bin] = exponentOf(values[bin]);
                }
            }
        }
    }

    /**
     * Returns whether an exponent set is coded in a block.
     *
     * @param b the block
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return whether the set is present
     */
    private boolean present(int b, int slot) {
        return slot != cpl || couplingInUse[b];
    }

    /**
     * Returns whether an exponent set must carry new exponents in a block: the first block of the
     * frame, a change of transform length or coded range, and for the coupling channel the first
     * block of a coupling strategy.
     *
     * @param b the block
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return whether the exponents must be new
     */
    private boolean forcedNew(int b, int slot) {
        if (b == 0) {
            return true;
        }
        if (slot == cpl) {
            return couplingStrategy[b] || !couplingInUse[b - 1];
        }
        if (endMantissa[b][slot] != endMantissa[b - 1][slot]) {
            return true;
        }
        return slot < channels && shortBlocks[b][slot] != shortBlocks[b - 1][slot];
    }

    /**
     * Searches the exponent strategies of the frame by dynamic programming over the blocks, under
     * each candidate dB per bit code at once, and for six-block E-AC-3 frames decides between the
     * per-block and the frame-based strategy syntax; {@link #applyStrategies(int)} then codes the
     * exponents of one code's choice.
     */
    private void searchStrategies() {
        var slots = coded + 1;
        var freeCost = strategySyntaxCost[0];
        var tableCost = strategySyntaxCost[1];
        Arrays.fill(freeCost, 0);
        Arrays.fill(tableCost, 0);
        var tableValid = enhanced && blocks == 6;
        for (var slot = 0; slot < slots; slot++) {
            if (slot == cpl && !coupling) {
                continue;
            }
            var isLfe = lfe && slot == coded - 1;
            for (var c = codeFrom; c < codeTo; c++) {
                for (var first = 0; first < blocks; first++) {
                    for (var last = first + 1; last <= blocks; last++) {
                        Arrays.fill(runCost[c][first][last], Long.MAX_VALUE);
                    }
                }
            }
            for (var first = 0; first < blocks; first++) {
                Arrays.fill(costedStrategies[first], 0);
            }
            var cuts = isLfe ? allCuts() : initialCuts(slot);
            solveRuns(slot, isLfe, cuts);
            while (!isLfe) {
                var grown = screenSplits(slot, cuts);
                if (grown == cuts) {
                    break;
                }
                cuts = grown;
                solveRuns(slot, isLfe, cuts);
            }
            for (var c = codeFrom; c < codeTo; c++) {
                var key = 0L;
                var last = blocks;
                while (last > 0) {
                    var first = bestFirst[c][slot][last];
                    if (first < 0) {
                        last--;
                        continue;
                    }
                    key = key << 6 | first << 3 | bestStrategy[c][slot][last];
                    last = first;
                }
                chainKeys[c][slot] = key;
            }
            if (tableValid && !isLfe) {
                for (var c = codeFrom; c < codeTo; c++) {
                    var bestTable = Long.MAX_VALUE;
                    var bestPattern = -1;
                    for (var code = 0; code < 32; code++) {
                        var total = patternCost(c, slot, Ac3Tables.FRAME_EXPONENT_STRATEGIES[code]);
                        if (total < bestTable) {
                            bestTable = total;
                            bestPattern = code;
                        }
                    }
                    if (bestPattern < 0) {
                        tableValid = false;
                        break;
                    }
                    frameStrategyCodes[c][slot] = bestPattern;
                    tableCost[c] += bestTable + 5 * 6;
                    var blocksPresent = 0;
                    for (var b = 0; b < 6; b++) {
                        blocksPresent += present(b, slot) ? 1 : 0;
                    }
                    freeCost[c] += bestCost[c][blocks] + 2 * 6 * blocksPresent;
                }
            }
        }
        for (var c = codeFrom; c < codeTo; c++) {
            useFrameStrategies[c] = tableValid && tableCost[c] < freeCost[c];
        }
    }

    /**
     * Returns the set of run boundaries that includes every block boundary.
     *
     * @return the boundaries, one bit per block boundary from 0 to {@link #blocks}
     */
    private int allCuts() {
        return (1 << blocks + 1) - 1;
    }

    /**
     * Returns the run boundaries an exponent set always has: the frame's first and last boundary,
     * the boundaries no run may cross, and the boundaries next to an absent set.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @return the boundaries, one bit per block boundary from 0 to {@link #blocks}
     */
    private int initialCuts(int slot) {
        var cuts = 1 | 1 << blocks;
        for (var b = 1; b < blocks; b++) {
            if (forcedNew(b, slot) || !present(b - 1, slot) || !present(b, slot)) {
                cuts |= 1 << b;
            }
        }
        return cuts;
    }

    /**
     * Finds the cheapest cover of the blocks of an exponent set by runs of shared exponents that
     * start and end at the given boundaries, under each candidate dB per bit code, costing the
     * runs not costed yet.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param isLfe whether the slot is the LFE channel
     * @param cuts the allowed run boundaries, one bit per block boundary
     */
    private void solveRuns(int slot, boolean isLfe, int cuts) {
        for (var c = codeFrom; c < codeTo; c++) {
            bestCost[c][0] = 0;
        }
        for (var last = 1; last <= blocks; last++) {
            if (!present(last - 1, slot)) {
                for (var c = codeFrom; c < codeTo; c++) {
                    bestCost[c][last] = bestCost[c][last - 1];
                    bestFirst[c][slot][last] = -1;
                }
                continue;
            }
            for (var c = codeFrom; c < codeTo; c++) {
                bestCost[c][last] = Long.MAX_VALUE;
            }
            if ((cuts >> last & 1) == 0) {
                continue;
            }
            var start = slot == cpl ? couplingStart : 0;
            var end = endMantissa[last - 1][slot];
            System.arraycopy(rawExponents[last - 1][slot], start, runMinimum, start, end - start);
            for (var first = last - 1; first >= 0; first--) {
                if (!present(first, slot)) {
                    break;
                }
                if (first < last - 1 && forcedNew(first + 1, slot)) {
                    break;
                }
                if (first < last - 1) {
                    var raw = rawExponents[first][slot];
                    for (var bin = start; bin < end; bin++) {
                        runMinimum[bin] = Math.min(runMinimum[bin], raw[bin]);
                    }
                }
                if ((cuts >> first & 1) == 0) {
                    continue;
                }
                var costed = costedStrategies[first][last];
                if (costed == 0) {
                    var strategies = isLfe ? 1 : enhanced && slot < channels && coupled[first][slot] && couplingInUse[first] ? 2 : 3;
                    costed = costRuns(slot, first, last, strategies);
                    costedStrategies[first][last] = costed;
                }
                for (var c = codeFrom; c < codeTo; c++) {
                    for (var strategy = 1; strategy <= costed; strategy++) {
                        var total = bestCost[c][first] + runCost[c][first][last][strategy];
                        if (total < bestCost[c][last]) {
                            bestCost[c][last] = total;
                            bestFirst[c][slot][last] = first;
                            bestStrategy[c][slot][last] = strategy;
                        }
                    }
                }
            }
        }
    }

    /**
     * Screens the runs of the cheapest cover under each candidate dB per bit code for splits that
     * could lower the cost, and adds the split blocks to the run boundaries.
     *
     * <p>A run splits at a block when the cost of its two halves, with the exponents each half
     * would carry under the same strategy as the run and the mantissas costed under the half's own
     * masking curve, is below the cost of the run: the halves can then cost less than the run.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param cuts the current run boundaries
     * @return the run boundaries with the blocks worth a split
     */
    private int screenSplits(int slot, int cuts) {
        var grown = cuts;
        var screened = 0L;
        for (var c = codeFrom; c < codeTo; c++) {
            var runs = 0L;
            var last = blocks;
            while (last > 0) {
                var first = bestFirst[c][slot][last];
                if (first < 0) {
                    last--;
                    continue;
                }
                runs |= 1L << first * 7 + last;
                last = first;
            }
            chainRuns[c] = runs;
            screened |= runs;
        }
        for (var first = 0; first < blocks; first++) {
            for (var last = first + 2; last <= blocks; last++) {
                if ((screened >> first * 7 + last & 1) != 0) {
                    grown |= splitBlocks(slot, first, last, cuts);
                }
            }
        }
        return grown;
    }

    /**
     * Returns the blocks at which a run could split to lower its cost under the candidate dB per
     * bit codes whose cheapest cover contains it.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param first the first block of the run
     * @param last one past the last block of the run
     * @param cuts the current run boundaries
     * @return the split blocks, one bit per block boundary
     */
    private int splitBlocks(int slot, int first, int last, int cuts) {
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[first][slot];
        var snr = offsetValue(referenceOffset);
        var minimum = splitMinimum;
        System.arraycopy(rawExponents[first][slot], start, minimum, start, end - start);
        for (var m = first + 1; m < last; m++) {
            if ((cuts >> m & 1) == 0) {
                splitMantissa(slot, start, end, minimum, snr, splitFirstCost, m);
            }
            if (m < last - 1) {
                var raw = rawExponents[m][slot];
                for (var bin = start; bin < end; bin++) {
                    minimum[bin] = Math.min(minimum[bin], raw[bin]);
                }
            }
        }
        System.arraycopy(rawExponents[last - 1][slot], start, minimum, start, end - start);
        for (var m = last - 1; m > first; m--) {
            if ((cuts >> m & 1) == 0) {
                splitMantissa(slot, start, end, minimum, snr, splitLastCost, m);
            }
            if (m > first + 1) {
                var raw = rawExponents[m - 1][slot];
                for (var bin = start; bin < end; bin++) {
                    minimum[bin] = Math.min(minimum[bin], raw[bin]);
                }
            }
        }
        var costed = costedStrategies[first][last];
        var length = last - first;
        var syntax = slot < channels ? 2 + (coupled[first][slot] && couplingInUse[first] ? 0 : 6) : 0;
        var bits = splitBits;
        for (var strategy = 1; strategy <= costed; strategy++) {
            bits[strategy] = 4 + 7 * groupCount(slot, start, end, strategy) + syntax;
        }
        var found = 0;
        for (var c = codeFrom; c < codeTo; c++) {
            if ((chainRuns[c] >> first * 7 + last & 1) == 0) {
                continue;
            }
            var cost = runCost[c][first][last];
            var base = cost[1] - 6L * bits[1];
            var penalty = splitPenalty;
            for (var strategy = 1; strategy <= costed; strategy++) {
                penalty[strategy] = (cost[strategy] - 6L * bits[strategy] - base) / (double) length;
            }
            var parent = cost[bestStrategy[c][slot][last]];
            var firstCost = splitFirstCost[c - codeFrom];
            var lastCost = splitLastCost[c - codeFrom];
            for (var m = first + 1; m < last; m++) {
                if ((cuts >> m & 1) != 0) {
                    continue;
                }
                var a = Double.MAX_VALUE;
                var z = Double.MAX_VALUE;
                for (var strategy = 1; strategy <= costed; strategy++) {
                    a = Math.min(a, 6.0 * bits[strategy] + (m - first) * (firstCost[m] + penalty[strategy]));
                    z = Math.min(z, 6.0 * bits[strategy] + (last - m) * (lastCost[m] + penalty[strategy]));
                }
                if (a + z < parent) {
                    found |= 1 << m;
                }
            }
        }
        return found;
    }

    /**
     * Costs the D15 exponents of a half of a split under the masking curve of the half, for the
     * first and the last candidate dB per bit code.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param minimum the smallest raw exponents of the half
     * @param snr the SNR offset in the units of the masking curve
     * @param costs receives, at index {@code m}, the cost of the mantissas of one block in sixths of
     * a bit under the first costed code in row 0 and under the last in row 1
     * @param m the split block
     */
    private void splitMantissa(int slot, int start, int end, int[] minimum, int snr, long[][] costs, int m) {
        shareMinimum(slot, end, 1, minimum, candidateExponents);
        allocation.spread(candidateExponents, start, end, candidateParameters[0], fastGain,
                slot == cpl ? fastLeak : 0, slot == cpl ? slowLeak : 0);
        for (var c = codeFrom; c < codeTo; c++) {
            allocation.finish(candidateParameters[c], candidateMasks[c]);
        }
        ExponentRuns.tallyPair(candidateExponents, start, end, candidateMasks[codeFrom],
                candidateMasks[codeTo - 1], snr, floor, MANTISSA_PADDED, pairSums);
        costs[0][m] = pairSums[0];
        costs[1][m] = pairSums[1];
    }

    /**
     * Returns the number of 7-bit exponent groups of a set under a strategy.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param start the first mantissa of the set
     * @param end one past the last mantissa of the set
     * @param strategy the strategy: 1 D15, 2 D25, 3 D45
     * @return the number of groups
     */
    private int groupCount(int slot, int start, int end, int strategy) {
        var groupSize = 1 << (strategy - 1);
        if (slot == cpl) {
            return (end - start) / (3 * groupSize);
        }
        if (end == 7) {
            return 2;
        }
        return (end - 1 + 3 * groupSize - 3) / (3 * groupSize);
    }

    /**
     * Returns the cost, in sixths of a bit, of coding an exponent set with the strategies of a
     * frame exponent strategy code, or {@link Long#MAX_VALUE} when the pattern reuses exponents
     * where new ones are required.
     *
     * @param c the index of the dB per bit code in {@link #DB_PER_BIT_CODES}
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param pattern the strategy of each block (A/52 Annex E table E2.10)
     * @return the cost
     */
    private long patternCost(int c, int slot, int[] pattern) {
        var total = 0L;
        var b = 0;
        while (b < 6) {
            if (!present(b, slot)) {
                b++;
                continue;
            }
            if (pattern[b] == 0) {
                return Long.MAX_VALUE;
            }
            var end = b + 1;
            while (end < 6 && present(end, slot) && pattern[end] == 0) {
                end++;
            }
            var cost = runCost[c][b][end][pattern[b]];
            if (cost == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            total += cost;
            b = end;
        }
        return total;
    }

    /**
     * Returns whether the search chose the same exponent strategies under two candidate dB per bit
     * codes, so that the exponents coded under one are the exponents of the other.
     *
     * @param a the index of the first code in {@link #DB_PER_BIT_CODES}
     * @param b the index of the second code
     * @return whether the strategies are the same
     */
    private boolean sameStrategies(int a, int b) {
        if (a == b) {
            return true;
        }
        if (useFrameStrategies[a] != useFrameStrategies[b]) {
            return false;
        }
        for (var slot = 0; slot <= coded; slot++) {
            if (slot == cpl && !coupling) {
                continue;
            }
            var frameBased = useFrameStrategies[a] && !(lfe && slot == coded - 1);
            if (frameBased ? frameStrategyCodes[a][slot] != frameStrategyCodes[b][slot]
                    : chainKeys[a][slot] != chainKeys[b][slot]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Codes the exponents of every exponent set with the strategies the search chose under one dB
     * per bit code.
     *
     * @param c the index of the code in {@link #DB_PER_BIT_CODES}
     */
    private void applyStrategies(int c) {
        frameStrategies = useFrameStrategies[c];
        System.arraycopy(frameStrategyCodes[c], 0, appliedFrameStrategyCodes, 0, coded + 1);
        for (var slot = 0; slot <= coded; slot++) {
            if (slot == cpl && !coupling) {
                continue;
            }
            if (frameStrategies && !(lfe && slot == coded - 1)) {
                var pattern = Ac3Tables.FRAME_EXPONENT_STRATEGIES[frameStrategyCodes[c][slot]];
                var b = 0;
                while (b < 6) {
                    if (!present(b, slot)) {
                        strategies[b][slot] = 0;
                        b++;
                        continue;
                    }
                    var end = b + 1;
                    while (end < 6 && present(end, slot) && pattern[end] == 0) {
                        end++;
                    }
                    codeGroup(slot, b, end, pattern[b]);
                    b = end;
                }
                continue;
            }
            var last = blocks;
            while (last > 0) {
                var first = bestFirst[c][slot][last];
                if (first < 0) {
                    strategies[last - 1][slot] = 0;
                    last--;
                    continue;
                }
                codeGroup(slot, first, last, bestStrategy[c][slot][last]);
                last = first;
            }
        }
    }

    /**
     * Costs a run of blocks with shared exponents under each strategy and each candidate dB per
     * bit code, in sixths of a bit, into {@link #runCost}: the exponent bits plus the mantissa bits
     * the shared exponents cost at the reference SNR offset in every block of the run.
     *
     * <p>The run's smallest raw exponents are in {@link #runMinimum}. The masking curve of the D15
     * exponents prices the mantissas of every strategy: the coarser strategies raise a few
     * exponents' power and so their own masking slightly, which the estimate leaves out. The fast
     * search stops at the first strategy that costs no less than the finer one before it under
     * every code.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param first the first block of the run
     * @param last one past the last block of the run
     * @param strategies the strategies to cost, from D15: 1, 2 or 3
     * @return the number of strategies costed, from D15
     */
    private int costRuns(int slot, int first, int last, int strategies) {
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[first][slot];
        var snr = offsetValue(referenceOffset);
        for (var strategy = 1; strategy <= strategies; strategy++) {
            var groups = shareMinimum(slot, end, strategy, runMinimum, candidateExponents);
            var exponentBits = 4 + 7 * groups;
            if (slot < channels) {
                exponentBits += 2 + (coupled[first][slot] && couplingInUse[first] ? 0 : 6);
            }
            if (strategy == 1) {
                allocation.spread(candidateExponents, start, end, candidateParameters[0], fastGain,
                        slot == cpl ? fastLeak : 0, slot == cpl ? slowLeak : 0);
                for (var c = codeFrom; c < codeTo; c++) {
                    allocation.finish(candidateParameters[c], candidateMasks[c]);
                }
            }
            var coarserPays = false;
            ExponentRuns.tallyPair(candidateExponents, start, end, candidateMasks[codeFrom],
                    candidateMasks[codeTo - 1], snr, floor, MANTISSA_PADDED, pairSums);
            for (var c = codeFrom; c < codeTo; c++) {
                var mantissa = pairSums[c - codeFrom];
                var cost = 6L * exponentBits + (long) (last - first) * mantissa;
                runCost[c][first][last][strategy] = cost;
                coarserPays |= strategy == 1 || cost < runCost[c][first][last][strategy - 1];
            }
            if (fastSearch && !coarserPays) {
                return strategy;
            }
        }
        return strategies;
    }

    /**
     * Computes the exponents a run of blocks shares: the smallest raw exponent of each mantissa
     * over the run, shared within the groups of the strategy and limited to the differences the
     * code can carry.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param first the first block of the run
     * @param last one past the last block of the run
     * @param strategy the strategy: 1 D15, 2 D25, 3 D45
     * @param decoded receives the exponents the decoder reconstructs, by mantissa
     * @return the number of 7-bit groups
     */
    private int shareExponents(int slot, int first, int last, int strategy, int[] decoded) {
        var start = slot == cpl ? couplingStart : 0;
        var end = endMantissa[first][slot];
        var minimum = groupMinimum;
        Arrays.fill(minimum, 24);
        for (var b = first; b < last; b++) {
            var raw = rawExponents[b][slot];
            for (var bin = start; bin < end; bin++) {
                minimum[bin] = Math.min(minimum[bin], raw[bin]);
            }
        }
        return shareMinimum(slot, end, strategy, minimum, decoded);
    }

    /**
     * Shares the smallest exponents of a run within the groups of a strategy and limits them to
     * the differences the code can carry, leaving the coded values in {@link #groupShared}.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param end one past the last mantissa of the set
     * @param strategy the strategy: 1 D15, 2 D25, 3 D45
     * @param minimum the smallest raw exponent of each mantissa over the run
     * @param decoded receives the exponents the decoder reconstructs, by mantissa
     * @return the number of 7-bit groups
     */
    private int shareMinimum(int slot, int end, int strategy, int[] minimum, int[] decoded) {
        var groupSize = 1 << (strategy - 1);
        var start = slot == cpl ? couplingStart : 0;
        int groups;
        if (slot == cpl) {
            groups = (end - start) / (3 * groupSize);
        } else if (end == 7) {
            groups = 2;
        } else {
            groups = (end - 1 + 3 * groupSize - 3) / (3 * groupSize);
        }
        var values = groups * 3;
        var shared = groupShared;
        var base = slot == cpl ? start : 1;
        var full = Math.min(values, (end - base) / groupSize);
        if (groupSize == 1) {
            for (var v = 0; v < full; v++) {
                shared[v + 1] = minimum[base + v];
            }
        } else if (groupSize == 2) {
            for (var v = 0; v < full; v++) {
                var bin = base + 2 * v;
                shared[v + 1] = Math.min(minimum[bin], minimum[bin + 1]);
            }
        } else {
            for (var v = 0; v < full; v++) {
                var bin = base + 4 * v;
                shared[v + 1] = Math.min(Math.min(minimum[bin], minimum[bin + 1]),
                        Math.min(minimum[bin + 2], minimum[bin + 3]));
            }
        }
        for (var v = full; v < values; v++) {
            var smallest = 24;
            for (var j = 0; j < groupSize; j++) {
                var bin = base + v * groupSize + j;
                if (bin < end) {
                    smallest = Math.min(smallest, minimum[bin]);
                }
            }
            shared[v + 1] = smallest;
        }
        if (slot == cpl) {
            limitDifferences(shared, 1, values);
            shared[0] = Math.min(30, shared[1] & ~1);
        } else {
            shared[0] = Math.min(15, minimum[0]);
            limitDifferences(shared, 0, values);
        }
        if (slot != cpl) {
            decoded[0] = shared[0];
        }
        var top = Math.min(256, base + values * groupSize);
        if (groupSize == 1) {
            System.arraycopy(shared, 1, decoded, base, top - base);
        } else {
            var shift = strategy - 1;
            for (var offset = 0; offset < top - base; offset++) {
                decoded[base + offset] = shared[1 + (offset >> shift)];
            }
        }
        return groups;
    }

    /**
     * Lowers a run of exponents until neighbors differ by at most 2: after the call,
     * {@code values[v]} is the smallest {@code values[u] + 2 * |u - v|} over the run, the largest
     * exponents that do not exceed the input and that differential coding with the deltas -2 to 2
     * can carry.
     *
     * @implNote This implementation computes the limit coming from the lower indices as a running
     * minimum of {@code values[u] - 2u}, plus {@code 2v}, and the limit coming from the higher
     * indices as a running minimum of {@code values[u] + 2u}, minus {@code 2v}, and takes the smaller:
     * the same values as relaxing each exponent against its lowered neighbor in two passes. The two
     * running minima do not depend on each other, so one loop advances both and the processor
     * overlaps their serial chains.
     *
     * @param values the exponents, lowered in place
     * @param first the first index of the run
     * @param last the last index of the run, inclusive
     */
    private void limitDifferences(int[] values, int first, int last) {
        var up = limitUp;
        var down = limitDown;
        var low = Integer.MAX_VALUE;
        var high = Integer.MAX_VALUE;
        for (var i = 0; first + i <= last; i++) {
            var v = first + i;
            var w = last - i;
            low = Math.min(low, values[v] - 2 * v);
            high = Math.min(high, values[w] + 2 * w);
            up[v] = low + 2 * v;
            down[w] = high - 2 * w;
        }
        for (var v = first; v <= last; v++) {
            values[v] = Math.min(up[v], down[v]);
        }
    }

    /**
     * Codes the exponents a run of blocks shares and gives every block of the run the exponents
     * the decoder reconstructs.
     *
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param first the first block of the run
     * @param last one past the last block of the run
     * @param strategy the strategy: 1 D15, 2 D25, 3 D45
     */
    private void codeGroup(int slot, int first, int last, int strategy) {
        var decoded = exponents[first][slot];
        var groups = shareExponents(slot, first, last, strategy, decoded);
        var shared = groupShared;
        var codes = exponentCodes[first][slot];
        codes[0] = slot == cpl ? shared[0] >> 1 : shared[0];
        for (var group = 0; group < groups; group++) {
            var m1 = shared[3 * group + 1] - shared[3 * group] + 2;
            var m2 = shared[3 * group + 2] - shared[3 * group + 1] + 2;
            var m3 = shared[3 * group + 3] - shared[3 * group + 2] + 2;
            codes[group + 1] = 25 * m1 + 5 * m2 + m3;
        }
        exponentCodeCount[first][slot] = groups + 1;
        strategies[first][slot] = strategy;
        for (var b = first + 1; b < last; b++) {
            strategies[b][slot] = 0;
            System.arraycopy(decoded, 0, exponents[b][slot], 0, 256);
        }
    }

    /**
     * Computes the masking curve of every exponent set of the frame and collects the exponent runs
     * {@link #mantissaBits(int)} tallies into {@link #runs}. A set that reuses the exponents of the
     * block before it has the same curve and runs, which the block copies or shares instead of
     * computing again.
     */
    private void computeMasks() {
        for (var b = 0; b < blocks; b++) {
            for (var slot = 0; slot <= coded; slot++) {
                if (!present(b, slot)) {
                    continue;
                }
                var isCoupling = slot == cpl;
                var start = isCoupling ? couplingStart : 0;
                var origin = b;
                if (b > 0 && strategies[b][slot] == 0 && present(b - 1, slot)
                        && endMantissa[b][slot] == endMantissa[b - 1][slot]) {
                    origin = maskOrigin[b - 1][slot];
                }
                maskOrigin[b][slot] = origin;
                if (origin != b) {
                    System.arraycopy(masks[origin][slot], 0, masks[b][slot], 0, 51);
                    continue;
                }
                allocation.mask(exponents[b][slot], start, endMantissa[b][slot], parameters,
                        fastGain, isCoupling ? fastLeak : 0, isCoupling ? slowLeak : 0, masks[b][slot]);
                runs[b][slot].collect(exponents[b][slot], start, endMantissa[b][slot]);
            }
        }
    }

    /**
     * Returns the {@code snroffset} of a combined SNR offset (A/52 section 7.2.2.7).
     *
     * @param offset the combined offset {@code 16 * csnroffst + fsnroffst}
     * @return the offset in the units of the masking curve
     */
    private static int offsetValue(int offset) {
        return (((offset >> 4) - 15 << 4) + (offset & 15)) << 2;
    }

    /**
     * Returns the mantissa bits of the frame at a combined SNR offset.
     *
     * @param offset the combined offset {@code 16 * csnroffst + fsnroffst}
     * @return the number of mantissa bits
     */
    private int mantissaBits(int offset) {
        if (offset == 0) {
            return 0;
        }
        var snr = offsetValue(offset);
        var total = 0;
        for (var b = 0; b < blocks; b++) {
            var tally = 0L;
            for (var slot = 0; slot <= coded; slot++) {
                if (!present(b, slot)) {
                    continue;
                }
                if (hybridSlots[slot]) {
                    if (b == 0) {
                        total += hybridBits(slot, offset);
                    }
                    continue;
                }
                if (maskOrigin[b][slot] == b) {
                    slotTally[slot] = runs[b][slot].tally(masks[b][slot], snr, floor, TALLY_PADDED);
                }
                tally += slotTally[slot];
            }
            var ungrouped = (int) (tally & 0xFFFF);
            var threes = (int) (tally >>> 16 & 0xFFFF);
            var fives = (int) (tally >>> 32 & 0xFFFF);
            var elevens = (int) (tally >>> 48);
            total += (threes + 2) / 3 * 5 + (fives + 2) / 3 * 7 + (elevens + 1) / 2 * 7 + ungrouped;
        }
        return total;
    }

    /**
     * Computes the bit allocation pointers of every exponent set at a combined SNR offset.
     *
     * @param offset the combined offset {@code 16 * csnroffst + fsnroffst}
     */
    private void computePointers(int offset) {
        var snr = offsetValue(offset);
        for (var b = 0; b < blocks; b++) {
            for (var slot = 0; slot <= coded; slot++) {
                if (!present(b, slot)) {
                    continue;
                }
                var start = slot == cpl ? couplingStart : 0;
                if (offset == 0) {
                    Arrays.fill(pointers[b][slot], start, endMantissa[b][slot], 0);
                } else if (!hybridSlots[slot] || b == 0) {
                    var origin = maskOrigin[b][slot];
                    if (origin != b) {
                        System.arraycopy(pointers[origin][slot], start, pointers[b][slot], start,
                                endMantissa[b][slot] - start);
                        continue;
                    }
                    BitAllocation.pointers(exponents[b][slot], start, endMantissa[b][slot], masks[b][slot], snr, floor,
                            hybridSlots[slot] ? Ac3Tables.HEBAP : Ac3Tables.BAP, pointers[b][slot]);
                }
            }
        }
    }

    /**
     * Writes the frame, or counts its side information.
     *
     * @param writer the writer, or {@code null} to count the bits before the mantissas only
     * @param offset the combined SNR offset
     * @param frameWords the frame size in 16-bit words
     * @param frmsizecod the frame size code of AC-3
     * @param mantissas whether to write the mantissas
     * @return the number of bits written or counted
     */
    private int writeFrame(MsbBitWriter writer, int offset, int frameWords, int frmsizecod, boolean mantissas) {
        var out = writer == null ? counter : sink;
        out.bits = 0;
        out.put(0x0B77, 16);
        if (enhanced) {
            writeEnhancedHeader(out, offset, frameWords);
        } else {
            out.put(0, 16);
            out.put(fscod, 2);
            out.put(frmsizecod, 6);
            out.put(8, 5);
            out.put(0, 3);
            out.put(acmod, 3);
            if ((acmod & 1) != 0 && acmod != 1) {
                out.put(1, 2);
            }
            if ((acmod & 4) != 0) {
                out.put(1, 2);
            }
            if (acmod == 2) {
                out.put(0, 2);
            }
            out.put(lfe ? 1 : 0, 1);
            out.put(dialnorm, 5);
            out.put(0, 1);
            out.put(0, 1);
            out.put(0, 1);
            out.put(0, 1);
            out.put(1, 1);
            out.put(0, 1);
            out.put(0, 1);
            out.put(0, 1);
        }
        var state = syntax;
        state.reset();
        for (var b = 0; b < blocks; b++) {
            writeBlock(out, state, b, offset, mantissas);
        }
        return out.bits;
    }

    /**
     * Returns whether the frame flags dither in every block of every full-bandwidth channel, so
     * that E-AC-3 may leave the dither flags out.
     *
     * @return whether dither is on everywhere
     */
    private boolean ditherEverywhere() {
        for (var b = 0; b < blocks; b++) {
            for (var ch = 0; ch < channels; ch++) {
                if (!ditherFlags[b][ch]) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Returns whether any block of the frame uses short transforms.
     *
     * @return whether block switch flags are needed
     */
    private boolean anyShortBlock() {
        for (var b = 0; b < blocks; b++) {
            for (var ch = 0; ch < channels; ch++) {
                if (shortBlocks[b][ch]) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether the bit allocation parameters differ from the ones E-AC-3 implies without
     * {@code bamode}.
     *
     * @return whether the parameters must be sent
     */
    private boolean customAllocation() {
        return SLOW_DECAY_CODE != ENHANCED_DEFAULT_CODES[0] || FAST_DECAY_CODE != ENHANCED_DEFAULT_CODES[1]
                || SLOW_GAIN_CODE != ENHANCED_DEFAULT_CODES[2] || dbPerBitCode != ENHANCED_DEFAULT_CODES[3]
                || FLOOR_CODE != ENHANCED_DEFAULT_CODES[4];
    }

    /**
     * Writes the bit stream information and the audio frame header of an E-AC-3 independent
     * substream frame (A/52 Annex E sections 2.2.2 and 2.2.3).
     *
     * @param out the sink
     * @param offset the combined SNR offset
     * @param frameWords the frame size in 16-bit words
     */
    private void writeEnhancedHeader(BitSink out, int offset, int frameWords) {
        var numblkscod = blocks == 1 ? 0 : blocks == 2 ? 1 : blocks == 3 ? 2 : 3;
        var independent = channelMap < 0;
        out.put(independent ? 0 : 1, 2);
        out.put(0, 3);
        out.put(frameWords - 1, 11);
        out.put(fscod, 2);
        out.put(fscod == 3 ? fscod2 : numblkscod, 2);
        out.put(acmod, 3);
        out.put(lfe ? 1 : 0, 1);
        out.put(16, 5);
        out.put(dialnorm, 5);
        out.put(0, 1);
        if (!independent) {
            out.put(1, 1);
            out.put(channelMap, 16);
        }
        out.put(0, 1);
        out.put(0, 1);
        var syncPoint = blockCount % 6 == 0;
        if (independent && numblkscod != 3) {
            out.put(syncPoint ? 1 : 0, 1);
        }
        out.put(0, 1);
        if (numblkscod == 3) {
            out.put(frameStrategies ? 0 : 1, 1);
            out.put(hybridFrame ? 1 : 0, 1);
        }
        out.put(0, 2);
        out.put(anyPreNoise() ? 1 : 0, 1);
        out.put(anyShortBlock() ? 1 : 0, 1);
        out.put(ditherEverywhere() ? 0 : 1, 1);
        out.put(customAllocation() ? 1 : 0, 1);
        out.put(0, 1);
        out.put(0, 1);
        out.put(0, 1);
        out.put(0, 1);
        if (acmod > 1) {
            out.put(couplingInUse[0] ? 1 : 0, 1);
            for (var b = 1; b < blocks; b++) {
                out.put(couplingStrategy[b] ? 1 : 0, 1);
                if (couplingStrategy[b]) {
                    out.put(couplingInUse[b] ? 1 : 0, 1);
                }
            }
        }
        if (frameStrategies) {
            var couplingBlocks = 0;
            for (var b = 0; b < blocks; b++) {
                couplingBlocks += couplingInUse[b] ? 1 : 0;
            }
            if (acmod > 1 && couplingBlocks > 0) {
                out.put(appliedFrameStrategyCodes[cpl], 5);
            }
            for (var ch = 0; ch < channels; ch++) {
                out.put(appliedFrameStrategyCodes[ch], 5);
            }
        } else {
            for (var b = 0; b < blocks; b++) {
                if (couplingInUse[b]) {
                    out.put(strategies[b][cpl], 2);
                }
                for (var ch = 0; ch < channels; ch++) {
                    out.put(strategies[b][ch], 2);
                }
            }
        }
        if (lfe) {
            for (var b = 0; b < blocks; b++) {
                out.put(strategies[b][coded - 1] != 0 ? 1 : 0, 1);
            }
        }
        var converter = independent && (numblkscod == 3 || syncPoint);
        if (independent && numblkscod != 3) {
            out.put(converter ? 1 : 0, 1);
        }
        if (converter) {
            for (var ch = 0; ch < channels; ch++) {
                out.put(converterStrategy(ch), 5);
            }
        }
        if (hybridFrame) {
            var couplingBlocks = 0;
            for (var b = 0; b < 6; b++) {
                couplingBlocks += couplingInUse[b] ? 1 : 0;
            }
            if (couplingBlocks == 6 && regions(cpl) == 1) {
                out.put(hybridSlots[cpl] ? 1 : 0, 1);
            }
            for (var ch = 0; ch < channels; ch++) {
                if (regions(ch) == 1) {
                    out.put(hybridSlots[ch] ? 1 : 0, 1);
                }
            }
            if (lfe && regions(coded - 1) == 1) {
                out.put(hybridSlots[coded - 1] ? 1 : 0, 1);
            }
        }
        out.put(offset >> 4, 6);
        out.put(offset & 15, 4);
        if (anyPreNoise()) {
            for (var ch = 0; ch < channels; ch++) {
                var location = preNoise.location[ch];
                out.put(location >= 0 ? 1 : 0, 1);
                if (location >= 0) {
                    out.put(location >> 2, 10);
                    out.put(preNoise.length[ch], 8);
                }
            }
        }
        if (numblkscod != 0) {
            out.put(0, 1);
        }
    }

    /**
     * Returns whether the frame carries transient pre-noise processing data for any channel.
     *
     * @return {@code true} when a channel has a transient location
     */
    private boolean anyPreNoise() {
        if (preNoise == null) {
            return false;
        }
        for (var ch = 0; ch < channels; ch++) {
            if (preNoise.location[ch] >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the converter exponent strategy of a channel ({@code convexpstr}): the frame
     * strategy code matching the channel's strategies in a six-block frame, and the code of new
     * D45 exponents in every block otherwise, which a converter can always honor.
     *
     * @param ch the channel
     * @return the code of A/52 Annex E table E2.10
     */
    private int converterStrategy(int ch) {
        if (blocks != 6) {
            return 31;
        }
        for (var code = 0; code < 32; code++) {
            var pattern = Ac3Tables.FRAME_EXPONENT_STRATEGIES[code];
            var match = true;
            for (var b = 0; b < 6 && match; b++) {
                match = pattern[b] == strategies[b][ch];
            }
            if (match) {
                return code;
            }
        }
        return 31;
    }

    /**
     * Writes one audio block.
     *
     * @param out the sink
     * @param state the syntax state carried from block to block
     * @param b the block
     * @param offset the combined SNR offset
     * @param mantissas whether to write the mantissas
     */
    private void writeBlock(BitSink out, SyntaxState state, int b, int offset, boolean mantissas) {
        if (!enhanced || anyShortBlock()) {
            for (var ch = 0; ch < channels; ch++) {
                out.put(shortBlocks[b][ch] ? 1 : 0, 1);
            }
        }
        if (!enhanced || !ditherEverywhere()) {
            for (var ch = 0; ch < channels; ch++) {
                out.put(ditherFlags[b][ch] ? 1 : 0, 1);
            }
        }
        out.put(0, 1);
        if (enhanced) {
            writeSpectralExtension(out, b);
        }
        var inUse = couplingInUse[b];
        if (!enhanced) {
            out.put(couplingStrategy[b] ? 1 : 0, 1);
            if (couplingStrategy[b]) {
                out.put(inUse ? 1 : 0, 1);
            }
        }
        if (couplingStrategy[b]) {
            if (inUse) {
                if (enhanced) {
                    out.put(0, 1);
                }
                if (!enhanced || acmod != 2) {
                    for (var ch = 0; ch < channels; ch++) {
                        out.put(coupled[b][ch] ? 1 : 0, 1);
                    }
                }
                if (acmod == 2) {
                    out.put(1, 1);
                }
                out.put(couplingBegin, 4);
                if (spx == null) {
                    out.put(couplingEndCode, 4);
                }
                if (enhanced) {
                    out.put(defaultStructure ? 0 : 1, 1);
                }
                if (!enhanced || !defaultStructure) {
                    for (var subband = couplingBegin + 1; subband < couplingBegin + couplingSubbands; subband++) {
                        out.put(couplingStructure[subband] ? 1 : 0, 1);
                    }
                }
            } else {
                Arrays.fill(state.firstCoordinates, true);
                state.firstLeak = true;
            }
        }
        if (inUse) {
            var anySent = false;
            for (var ch = 0; ch < channels; ch++) {
                if (!coupled[b][ch]) {
                    state.firstCoordinates[ch] = true;
                    continue;
                }
                var sent = coordinatesSent[b][ch];
                if (enhanced && state.firstCoordinates[ch]) {
                    state.firstCoordinates[ch] = false;
                } else {
                    out.put(sent ? 1 : 0, 1);
                }
                if (sent) {
                    anySent |= ch < 2;
                    out.put(coordinateMaster[b][ch], 2);
                    for (var band = 0; band < couplingBands; band++) {
                        out.put(coordinateExponents[b][ch][band], 4);
                        out.put(coordinateMantissas[b][ch][band], 4);
                    }
                }
            }
            if (acmod == 2 && anySent) {
                for (var band = 0; band < couplingBands; band++) {
                    out.put(phaseFlags[b][band] ? 1 : 0, 1);
                }
            }
        }
        if (acmod == 2) {
            if (!enhanced || b > 0) {
                out.put(1, 1);
            }
            for (var band = 0; band < rematrixBands[b]; band++) {
                out.put(rematrix[b][band] ? 1 : 0, 1);
            }
        }
        if (!enhanced) {
            if (inUse) {
                out.put(strategies[b][cpl], 2);
            }
            for (var ch = 0; ch < channels; ch++) {
                out.put(strategies[b][ch], 2);
            }
            if (lfe) {
                out.put(strategies[b][coded - 1] != 0 ? 1 : 0, 1);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            if (strategies[b][ch] != 0 && !(inUse && coupled[b][ch]) && spx == null) {
                out.put(bandwidthCode, 6);
            }
        }
        if (inUse && strategies[b][cpl] != 0) {
            var codes = exponentCodes[b][cpl];
            out.put(codes[0], 4);
            for (var i = 1; i < exponentCodeCount[b][cpl]; i++) {
                out.put(codes[i], 7);
            }
        }
        for (var ch = 0; ch < coded; ch++) {
            if (strategies[b][ch] == 0) {
                continue;
            }
            var codes = exponentCodes[b][ch];
            out.put(codes[0], 4);
            for (var i = 1; i < exponentCodeCount[b][ch]; i++) {
                out.put(codes[i], 7);
            }
            if (ch < channels) {
                out.put(0, 2);
            }
        }
        var firstCouplingBlock = inUse && (b == 0 || !couplingInUse[b - 1]);
        if (!enhanced || customAllocation()) {
            out.put(b == 0 ? 1 : 0, 1);
            if (b == 0) {
                out.put(SLOW_DECAY_CODE, 2);
                out.put(FAST_DECAY_CODE, 2);
                out.put(SLOW_GAIN_CODE, 2);
                out.put(dbPerBitCode, 2);
                out.put(FLOOR_CODE, 3);
            }
        }
        if (enhanced) {
            if (channelMap < 0) {
                out.put(0, 1);
            }
        } else {
            var snr = b == 0 || (firstCouplingBlock && !state.couplingSnrSent);
            out.put(snr ? 1 : 0, 1);
            if (snr) {
                out.put(offset >> 4, 6);
                if (inUse) {
                    out.put(offset & 15, 4);
                    out.put(FAST_GAIN_CODE, 3);
                    state.couplingSnrSent = true;
                }
                for (var ch = 0; ch < coded; ch++) {
                    out.put(offset & 15, 4);
                    out.put(FAST_GAIN_CODE, 3);
                }
            }
        }
        if (inUse) {
            if (enhanced && state.firstLeak) {
                state.firstLeak = false;
                out.put(FAST_LEAK_CODE, 3);
                out.put(SLOW_LEAK_CODE, 3);
            } else {
                out.put(firstCouplingBlock ? 1 : 0, 1);
                if (firstCouplingBlock) {
                    out.put(FAST_LEAK_CODE, 3);
                    out.put(SLOW_LEAK_CODE, 3);
                }
            }
        }
        if (!enhanced) {
            out.put(0, 1);
            out.put(0, 1);
        }
        if (mantissas) {
            writeMantissas(out, b);
        }
    }

    /**
     * Writes the spectral extension strategy and coordinates of an E-AC-3 block (A/52 Annex E
     * section 2.2.4): the strategy in the first block only, with every full-bandwidth channel in
     * spectral extension and the default band structure, then the coordinates of every channel,
     * implied present in the first block.
     *
     * @param out the sink
     * @param b the block
     */
    private void writeSpectralExtension(BitSink out, int b) {
        if (b > 0) {
            out.put(0, 1);
        } else {
            out.put(spx != null ? 1 : 0, 1);
            if (spx != null) {
                if (acmod != 1) {
                    for (var ch = 0; ch < channels; ch++) {
                        out.put(1, 1);
                    }
                }
                out.put(spx.startCode, 2);
                out.put(spx.beginCode, 3);
                out.put(spx.endCode, 3);
                out.put(0, 1);
            }
        }
        if (spx == null) {
            return;
        }
        for (var ch = 0; ch < channels; ch++) {
            var sent = spx.sent[b][ch];
            if (b > 0) {
                out.put(sent ? 1 : 0, 1);
            }
            if (sent) {
                out.put(spx.blend[ch], 5);
                out.put(spx.master[b][ch], 2);
                var exps = spx.exponents[b][ch];
                var mants = spx.mantissas[b][ch];
                for (var band = 0; band < spx.bands; band++) {
                    out.put(exps[band], 4);
                    out.put(mants[band], 2);
                }
            }
        }
    }

    /**
     * Quantizes and writes the mantissas of one block, grouping the 3-, 5- and 11-level codes; the
     * coupling channel follows the first coupled channel.
     *
     * @param out the sink
     * @param b the block
     */
    private void writeMantissas(BitSink out, int b) {
        var grouping = this.grouping;
        Arrays.fill(grouping, -1);
        var tokens = 0;
        var gotCoupling = false;
        for (var ch = 0; ch < coded; ch++) {
            if (!hybridSlots[ch]) {
                tokens = quantize(b, ch, 0, tokens, grouping);
            } else if (b == 0) {
                tokens = quantizeHybrid(ch, tokens);
            }
            if (couplingInUse[b] && ch < channels && coupled[b][ch] && !gotCoupling) {
                if (!hybridSlots[cpl]) {
                    tokens = quantize(b, cpl, couplingStart, tokens, grouping);
                } else if (b == 0) {
                    tokens = quantizeHybrid(cpl, tokens);
                }
                gotCoupling = true;
            }
        }
        for (var i = 0; i < tokens; i++) {
            out.put(tokenValues[i], tokenBits[i]);
        }
    }

    /**
     * Quantizes the mantissas of one exponent set into pending codes.
     *
     * @param b the block
     * @param slot the exponent set: a coded channel, or {@link #cpl} for the coupling channel
     * @param start the first mantissa
     * @param tokens the number of pending codes
     * @param grouping the open groups: the code index and fill of the 3-, 5- and 11-level groups
     * @return the new number of pending codes
     */
    private int quantize(int b, int slot, int start, int tokens, int[] grouping) {
        var inverse = Ac3Tables.POW2_POS;
        var end = endMantissa[b][slot];
        var bap = pointers[b][slot];
        var exps = exponents[b][slot];
        var values = coefficients[b][slot];
        if (reuseCode >= 0) {
            var cached = quantizedCodes[reuseCode][b][slot];
            for (var bin = start; bin < end; bin++) {
                var pointer = bap[bin];
                switch (pointer) {
                    case 0 -> {
                    }
                    case 1 -> tokens = group(tokens, grouping, 0, cached[bin], 3, 5, 3);
                    case 2 -> tokens = group(tokens, grouping, 2, cached[bin], 5, 7, 3);
                    case 3 -> {
                        tokenValues[tokens] = cached[bin];
                        tokenBits[tokens++] = 3;
                    }
                    case 4 -> tokens = group(tokens, grouping, 4, cached[bin], 11, 7, 2);
                    case 5 -> {
                        tokenValues[tokens] = cached[bin];
                        tokenBits[tokens++] = 4;
                    }
                    default -> {
                        var bits = Ac3Tables.MANTISSA_BITS[pointer];
                        tokenValues[tokens] = cached[bin] & ((1 << bits) - 1);
                        tokenBits[tokens++] = bits;
                    }
                }
            }
            return tokens;
        }
        for (var bin = start; bin < end; bin++) {
            var pointer = bap[bin];
            if (pointer == 0) {
                continue;
            }
            var mantissa = values[bin] * inverse[exps[bin]];
            switch (pointer) {
                case 1 -> tokens = group(tokens, grouping, 0, symmetric(mantissa, 3), 3, 5, 3);
                case 2 -> tokens = group(tokens, grouping, 2, symmetric(mantissa, 5), 5, 7, 3);
                case 3 -> {
                    tokenValues[tokens] = symmetric(mantissa, 7);
                    tokenBits[tokens++] = 3;
                }
                case 4 -> tokens = group(tokens, grouping, 4, symmetric(mantissa, 11), 11, 7, 2);
                case 5 -> {
                    tokenValues[tokens] = symmetric(mantissa, 15);
                    tokenBits[tokens++] = 4;
                }
                default -> {
                    var bits = Ac3Tables.MANTISSA_BITS[pointer];
                    var limit = 1 << (bits - 1);
                    var quantized = (int) Math.floor(mantissa * limit + 0.5f);
                    quantized = Math.max(-limit, Math.min(limit - 1, quantized));
                    tokenValues[tokens] = quantized & ((1 << bits) - 1);
                    tokenBits[tokens++] = bits;
                }
            }
        }
        return tokens;
    }

    /**
     * Adds a code to a grouped mantissa word, opening a new word when none is open.
     *
     * @param tokens the number of pending codes
     * @param grouping the open groups
     * @param index the index of the group's word in {@code grouping}, its fill follows
     * @param code the mantissa code to add, below {@code levels}
     * @param levels the number of levels
     * @param bits the width of the grouped word
     * @param size the codes per word
     * @return the new number of pending codes
     */
    private int group(int tokens, int[] grouping, int index, int code, int levels, int bits, int size) {
        if (grouping[index] < 0) {
            grouping[index] = tokens;
            grouping[index + 1] = 0;
            tokenValues[tokens] = 0;
            tokenBits[tokens++] = bits;
        }
        var fill = grouping[index + 1];
        var weight = 1;
        for (var k = fill + 1; k < size; k++) {
            weight *= levels;
        }
        tokenValues[grouping[index]] += code * weight;
        if (++grouping[index + 1] == size) {
            grouping[index] = -1;
        }
        return tokens;
    }

    /**
     * Quantizes a mantissa with a symmetric quantizer of A/52 tables 7.19 to 7.23.
     *
     * @param mantissa the normalized mantissa, in {@code [-1, 1]}
     * @param levels the number of levels
     * @return the code, from 0 to {@code levels - 1}
     */
    private static int symmetric(float mantissa, int levels) {
        var code = (int) Math.floor((mantissa * levels + (levels - 1)) / 2 + 0.5f);
        return Math.max(0, Math.min(levels - 1, code));
    }

    /**
     * Holds the syntax state a frame carries from block to block while it is written: the
     * implied first coupling coordinates and leak values of E-AC-3, and whether the coupling SNR
     * offset of AC-3 was sent.
     */
    private static final class SyntaxState {
        /**
         * Whether the next coordinates of each channel are implied present ({@code firstcplcos}).
         */
        final boolean[] firstCoordinates;

        /**
         * Whether the next coupling leak values are implied present ({@code firstcplleak}).
         */
        boolean firstLeak = true;

        /**
         * Whether the coupling fine SNR offset was sent in the frame.
         */
        boolean couplingSnrSent;

        /**
         * Creates the state.
         *
         * @param channels the number of full-bandwidth channels
         */
        SyntaxState(int channels) {
            this.firstCoordinates = new boolean[channels];
        }

        /**
         * Resets the state to the one of a frame's first block.
         */
        void reset() {
            Arrays.fill(firstCoordinates, true);
            firstLeak = true;
            couplingSnrSent = false;
        }
    }

    /**
     * Writes bits to a writer, or only counts them.
     */
    private static final class BitSink {
        /**
         * The writer, or {@code null} when only counting.
         */
        private final MsbBitWriter writer;

        /**
         * The number of bits written or counted.
         */
        private int bits;

        /**
         * Creates a sink.
         *
         * @param writer the writer, or {@code null} to count only
         */
        BitSink(MsbBitWriter writer) {
            this.writer = writer;
        }

        /**
         * Writes a field.
         *
         * @param value the field value, in its low {@code width} bits
         * @param width the width in bits
         */
        void put(int value, int width) {
            if (writer != null) {
                writer.write(value, width);
            }
            bits += width;
        }
    }
}
