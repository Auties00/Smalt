package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes the sync frames of one AC-3 stream or one E-AC-3 substream into PCM, one frame at a
 * time, keeping the overlap state of every channel between frames.
 *
 * <p>Decoding a frame runs in three steps. {@link #parse(byte[], int, int, boolean, boolean)} reads the
 * whole frame: the bit stream information, the E-AC-3 audio frame header, and every audio block,
 * whose exponents, bit allocation and mantissas become transform coefficients while the side
 * information of each block is kept in a {@link BlockState}. {@link #reconstruct()} then rebuilds
 * the coupled, rematrixed and spectrally extended coefficients of each block.
 * {@link #synthesize(float[][], float, float[][], int)} finally applies the gains, the inverse
 * transform and, for E-AC-3, the transient pre-noise processing. A frame that fails its CRC or its
 * syntax checks is concealed: every block repeats the last good coefficients of its channel,
 * fading out.
 *
 * <p>The coded channels are numbered in bit stream order: the full-bandwidth channels from 0, then
 * the LFE. Instances are not thread-safe.
 */
public final class FrameDecoder {
    /**
     * The slot of the coupling channel in the per-channel arrays.
     */
    static final int CPL = 5;

    /**
     * The slot of the LFE channel in the per-channel arrays.
     */
    static final int LFE = 6;

    /**
     * The number of channel slots.
     */
    private static final int SLOTS = BlockState.SLOTS;

    /**
     * The rematrixing band edges (A/52 tables 7.25 to 7.28), clipped by the channel bandwidth.
     */
    private static final int[] REMATRIX_EDGES = {13, 25, 37, 61, 253};

    /**
     * The values of the 3-level quantizer (A/52 table 7.19).
     */
    private static final float[] LEVELS_3 = symmetric(3);

    /**
     * The values of the 5-level quantizer (A/52 table 7.20).
     */
    private static final float[] LEVELS_5 = symmetric(5);

    /**
     * The values of the 7-level quantizer (A/52 table 7.21).
     */
    private static final float[] LEVELS_7 = symmetric(7);

    /**
     * The values of the 11-level quantizer (A/52 table 7.22).
     */
    private static final float[] LEVELS_11 = symmetric(11);

    /**
     * The values of the 15-level quantizer (A/52 table 7.23).
     */
    private static final float[] LEVELS_15 = symmetric(15);

    /**
     * The three 3-level values of each group code from 0 to 26 (A/52 section 7.3.5), at
     * {@code 3 * code}: {@code code / 9}, {@code (code % 9) / 3} and {@code code % 3}.
     */
    private static final float[] UNGROUPED_3 = ungrouped(LEVELS_3, 3, 3);

    /**
     * The three 5-level values of each group code from 0 to 124, at {@code 3 * code}:
     * {@code code / 25}, {@code (code % 25) / 5} and {@code code % 5}.
     */
    private static final float[] UNGROUPED_5 = ungrouped(LEVELS_5, 5, 3);

    /**
     * The two 11-level values of each group code from 0 to 120, at {@code 2 * code}:
     * {@code code / 11} and {@code code % 11}.
     */
    private static final float[] UNGROUPED_11 = ungrouped(LEVELS_11, 11, 2);

    /**
     * The scale of the dither that replaces zero-bit mantissas (A/52 section 7.3.4 recommends a
     * uniform distribution over plus or minus 0.707).
     */
    private static final float DITHER_SCALE = 0.707f;

    /**
     * The amplitude of the uniform noise of spectral extension, which has unit variance.
     */
    private static final float SPX_NOISE_SCALE = (float) Math.sqrt(3);

    /**
     * The gain applied to each concealed block relative to the one before it.
     */
    private static final float CONCEALMENT_FADE = 0.7f;

    /**
     * The three exponent differences of each grouped exponent code from 0 to 124 (A/52 section
     * 7.1.3): {@code code / 25 - 2}, {@code (code % 25) / 5 - 2} and {@code code % 5 - 2}.
     */
    private static final int[] EXPONENT_DELTAS = exponentDeltas();

    /**
     * The signal that the frame being parsed is invalid.
     */
    private static final InvalidFrame INVALID = new InvalidFrame();

    /**
     * The header of the frame being decoded.
     */
    private final FrameHeader header = new FrameHeader();

    /**
     * The side information of each block of the frame.
     */
    private final BlockState[] blocks = new BlockState[6];

    /**
     * The transform coefficients of each block and channel slot.
     */
    private final float[][][] coefficients = new float[6][SLOTS][256];

    /**
     * The coupling channel value of each block and bin; zero for zero-bit mantissas.
     */
    private final float[][] couplingValues = new float[6][256];

    /**
     * The exponent scale of each zero-bit mantissa of the coupling channel, which the dither of
     * each coupled channel is multiplied by; zero where a mantissa was coded.
     */
    private final float[][] couplingDither = new float[6][256];

    /**
     * The first and one past the last coupling channel bin of each block, or zeros without
     * coupling.
     */
    private final int[][] couplingRange = new int[6][2];

    /**
     * The exponents of each channel slot.
     */
    private final int[][] exponents = new int[SLOTS][256];

    /**
     * The end of the exponents last decoded for each channel slot.
     */
    private final int[] exponentEnd = new int[SLOTS];

    /**
     * The start of the exponents last decoded for the coupling channel.
     */
    private int couplingExponentStart;

    /**
     * The bit allocation pointers of each channel slot.
     */
    private final int[][] pointers = new int[SLOTS][256];

    /**
     * The bit allocation routine.
     */
    private final BitAllocation allocation = new BitAllocation();

    /**
     * The number of times the exponents of each channel slot were decoded, which tells the bit
     * allocation whether they changed.
     */
    private final int[] exponentVersion = new int[SLOTS];

    /**
     * The number of times the delta bit allocation of each channel slot was set or cleared.
     */
    private final int[] deltaVersion = new int[SLOTS];

    /**
     * Whether {@link #pointers} of each channel slot hold the result of a bit allocation.
     */
    private final boolean[] allocated = new boolean[SLOTS];

    /**
     * The inputs of the bit allocation that produced {@link #pointers}, four packed words per
     * channel slot: the exponent and delta versions, the range and SNR offset, the fast gain with
     * the parameter codes and the pointer table, and the coupling leaks.
     */
    private final long[] allocationInputs = new long[4 * SLOTS];

    /**
     * The bit allocation parameters of the current block.
     */
    private final BitAllocation.Parameters parameters = new BitAllocation.Parameters();

    /**
     * The transforms.
     */
    private final Transform transform;

    /**
     * The DSP kernels.
     */
    private final Ac3Kernel kernel;

    /**
     * The overlap delay of each coded channel.
     */
    private final float[][] delay = new float[6][256];

    /**
     * The coefficients of the last block of the last good frame, by coded channel.
     */
    private final float[][] lastCoefficients = new float[6][256];

    /**
     * The gain of the next concealed block.
     */
    private float concealmentGain;

    /**
     * The windowed output of one inverse transform.
     */
    private final float[] windowed = new float[512];

    /**
     * The reader of the frame being parsed.
     */
    private MsbBitReader reader;

    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The bytes of the frame being parsed.
     */
    private byte[] inputData;

    /**
     * The index of the frame's first byte in {@link #inputData}.
     */
    private int inputOffset;

    /**
     * The index of the first bit position from which an 8-byte load at the position's byte would
     * leave the frame.
     */
    private long inputBitLimit;

    /**
     * Whether the frame parsed last is valid.
     */
    private boolean valid;

    /**
     * Whether any good frame was decoded.
     */
    private boolean started;

    /**
     * The state of the dither and noise generator.
     */
    private int seed = 0x2545F491;

    /**
     * Whether full exponent strategy syntax is present in each block ({@code expstre}).
     */
    private boolean exponentStrategySyntax;

    /**
     * Whether the adaptive hybrid transform is enabled ({@code ahte}).
     */
    private boolean ahtEnabled;

    /**
     * The SNR offset strategy ({@code snroffststr}).
     */
    private int snrOffsetStrategy;

    /**
     * Whether block switch flags are present ({@code blkswe}).
     */
    private boolean blockSwitchSyntax;

    /**
     * Whether dither flags are present ({@code dithflage}).
     */
    private boolean ditherSyntax;

    /**
     * Whether bit allocation parameters are present ({@code bamode}).
     */
    private boolean bitAllocationSyntax;

    /**
     * Whether fast gain codes are present ({@code frmfgaincode}).
     */
    private boolean fastGainSyntax;

    /**
     * Whether delta bit allocation syntax is present ({@code dbaflde}).
     */
    private boolean deltaSyntax;

    /**
     * Whether skip fields are present ({@code skipflde}).
     */
    private boolean skipSyntax;

    /**
     * The coupling strategy flag of each block ({@code cplstre}).
     */
    private final boolean[] blockCouplingStrategy = new boolean[6];

    /**
     * The coupling flag of each block ({@code cplinu}).
     */
    private final boolean[] blockCoupling = new boolean[6];

    /**
     * The coupling exponent strategy of each block.
     */
    private final int[] blockCouplingExponents = new int[6];

    /**
     * The channel exponent strategy of each block and channel.
     */
    private final int[][] blockChannelExponents = new int[6][5];

    /**
     * The LFE exponent strategy of each block.
     */
    private final int[] blockLfeExponents = new int[6];

    /**
     * The frame coarse SNR offset ({@code frmcsnroffst}).
     */
    private int frameCoarseSnr;

    /**
     * The frame fine SNR offset ({@code frmfsnroffst}).
     */
    private int frameFineSnr;

    /**
     * Whether each channel uses spectral extension attenuation ({@code chinspxatten}).
     */
    private final boolean[] spxAttenuation = new boolean[5];

    /**
     * The spectral extension attenuation code of each channel ({@code spxattencod}).
     */
    private final int[] spxAttenuationCode = new int[5];

    /**
     * The adaptive hybrid transform state of the coupling channel: 0 unused, 1 to read, -1 read.
     */
    private int couplingAht;

    /**
     * The adaptive hybrid transform state of each full-bandwidth channel.
     */
    private final int[] channelAht = new int[5];

    /**
     * The adaptive hybrid transform state of the LFE channel.
     */
    private int lfeAht;

    /**
     * Whether coupling is in use in the current block.
     */
    private boolean couplingInUse;

    /**
     * Whether enhanced coupling is in use in the current block.
     */
    private boolean ecplInUse;

    /**
     * Whether phase flags are in use.
     */
    private boolean phaseFlagsInUse;

    /**
     * Whether each channel is coupled.
     */
    private final boolean[] coupled = new boolean[SLOTS];

    /**
     * The first coupling sub-band.
     */
    private int couplingBegin;

    /**
     * The number of coupling sub-bands.
     */
    private int couplingSubbands;

    /**
     * The coupling band structure by absolute sub-band.
     */
    private final boolean[] couplingStructure = new boolean[18];

    /**
     * The coupling band of each absolute sub-band.
     */
    private final int[] couplingBandOf = new int[18];

    /**
     * The number of coupling bands.
     */
    private int couplingBands;

    /**
     * The coupling coordinates of each channel and band, scaled by 8.
     */
    private final float[][] couplingCoordinates = new float[SLOTS][18];

    /**
     * The phase flags of each coupling band.
     */
    private final boolean[] phaseFlags = new boolean[18];

    /**
     * Whether the next coupling coordinates of each channel are implied present.
     */
    private final boolean[] firstCouplingCoordinates = new boolean[SLOTS];

    /**
     * Whether the next coupling leak values are implied present.
     */
    private boolean firstCouplingLeak;

    /**
     * The first enhanced coupling sub-band.
     */
    private int ecplBegin;

    /**
     * One past the last enhanced coupling sub-band.
     */
    private int ecplEnd;

    /**
     * The enhanced coupling begin code ({@code ecplbegf}).
     */
    private int ecplBeginCode;

    /**
     * The enhanced coupling band structure by sub-band.
     */
    private final boolean[] ecplStructure = new boolean[22];

    /**
     * The enhanced coupling band of each sub-band.
     */
    private final int[] ecplBandOf = new int[22];

    /**
     * The number of enhanced coupling bands.
     */
    private int ecplBands;

    /**
     * The enhanced coupling amplitude codes of each channel and band.
     */
    private final int[][] ecplAmplitudeCodes = new int[SLOTS][22];

    /**
     * The rematrixing flags.
     */
    private final boolean[] rematrixFlags = new boolean[4];

    /**
     * The number of rematrixing bands with flags.
     */
    private int rematrixBands;

    /**
     * Whether spectral extension is in use.
     */
    private boolean spxInUse;

    /**
     * Whether each channel uses spectral extension.
     */
    private final boolean[] inSpx = new boolean[SLOTS];

    /**
     * The spectral extension copy start code.
     */
    private int spxStart;

    /**
     * The spectral extension begin code ({@code spxbegf}).
     */
    private int spxBeginCode;

    /**
     * The first spectral extension sub-band.
     */
    private int spxBegin;

    /**
     * One past the last spectral extension sub-band.
     */
    private int spxEnd;

    /**
     * The spectral extension band structure by sub-band.
     */
    private final boolean[] spxStructure = new boolean[17];

    /**
     * The number of spectral extension bands.
     */
    private int spxBands;

    /**
     * The width of each spectral extension band.
     */
    private final int[] spxBandSize = new int[17];

    /**
     * The spectral extension coordinates of each channel and band, scaled by 32.
     */
    private final float[][] spxCoordinates = new float[SLOTS][17];

    /**
     * The noise blending factors of each channel and band.
     */
    private final float[][] spxNoise = new float[SLOTS][17];

    /**
     * The signal blending factors of each channel and band.
     */
    private final float[][] spxSignal = new float[SLOTS][17];

    /**
     * Whether the next spectral extension coordinates of each channel are implied present.
     */
    private final boolean[] firstSpxCoordinates = new boolean[SLOTS];

    /**
     * The exponent strategy of each channel slot in the current block.
     */
    private final int[] exponentStrategy = new int[SLOTS];

    /**
     * The bandwidth code of each full-bandwidth channel.
     */
    private final int[] bandwidthCode = new int[5];

    /**
     * The end mantissa of each channel slot.
     */
    private final int[] endMantissa = new int[SLOTS];

    /**
     * The coarse SNR offset.
     */
    private int coarseSnr;

    /**
     * The fine SNR offset of each channel slot.
     */
    private final int[] fineSnr = new int[SLOTS];

    /**
     * The fast gain code of each channel slot.
     */
    private final int[] fastGainCode = new int[SLOTS];

    /**
     * The coupling fast leak code.
     */
    private int couplingFastLeak;

    /**
     * The coupling slow leak code.
     */
    private int couplingSlowLeak;

    /**
     * The bit allocation parameter codes: {@code sdcycod}, {@code fdcycod}, {@code sgaincod},
     * {@code dbpbcod}, {@code floorcod}.
     */
    private final int[] allocationCodes = new int[5];

    /**
     * Whether delta bit allocation applies to each channel slot.
     */
    private final boolean[] deltaActive = new boolean[SLOTS];

    /**
     * The number of delta segments of each channel slot.
     */
    private final int[] deltaSegments = new int[SLOTS];

    /**
     * The delta offsets of each channel slot and segment.
     */
    private final int[][] deltaOffsets = new int[SLOTS][8];

    /**
     * The delta lengths of each channel slot and segment.
     */
    private final int[][] deltaLengths = new int[SLOTS][8];

    /**
     * The delta codes of each channel slot and segment.
     */
    private final int[][] deltaValues = new int[SLOTS][8];

    /**
     * The dynamic range word of each program channel.
     */
    private final int[] dynamicRange = new int[2];

    /**
     * The delta bit allocation exists codes of the channels of the current block.
     */
    private final int[] deltaCodes = new int[5];

    /**
     * The banded RMS energy of the translated coefficients of spectral extension.
     */
    private final float[] spxEnergy = new float[17];

    /**
     * The index in {@link #UNGROUPED_3} of the next value of the pending 3-level mantissa group.
     */
    private int group3Next;

    /**
     * The index in {@link #UNGROUPED_3} after the last value of the pending 3-level group, equal
     * to {@link #group3Next} when no value is pending.
     */
    private int group3End;

    /**
     * The index in {@link #UNGROUPED_5} of the next value of the pending 5-level mantissa group.
     */
    private int group5Next;

    /**
     * The index in {@link #UNGROUPED_5} after the last value of the pending 5-level group.
     */
    private int group5End;

    /**
     * The index in {@link #UNGROUPED_11} of the next value of the pending 11-level mantissa group.
     */
    private int group11Next;

    /**
     * The index in {@link #UNGROUPED_11} after the last value of the pending 11-level group.
     */
    private int group11End;

    /**
     * The six transform-domain mantissas of one adaptive hybrid transform bin.
     */
    private final float[] ahtIn = new float[6];

    /**
     * The six block mantissas of one adaptive hybrid transform bin.
     */
    private final float[] ahtOut = new float[6];

    /**
     * The gain index of each active gain-adaptive bin.
     */
    private final int[] gaqGains = new int[256];

    /**
     * The bit positions of the dither flags of the frame parsed last, relative to its sync word,
     * recorded for test tooling when not {@code null}.
     */
    int[] ditherPositions;

    /**
     * The number of entries of {@link #ditherPositions}.
     */
    int ditherCount;

    /**
     * The bit position of the dither flag syntax enable bit of the E-AC-3 frame parsed last, or
     * {@code -1}, recorded with {@link #ditherPositions}.
     */
    int ditherSyntaxPosition = -1;

    /**
     * The bit position, in each block of an E-AC-3 frame without dither flag syntax, where the
     * flags would be, recorded with {@link #ditherPositions}.
     */
    final int[] ditherInsertions = new int[6];

    /**
     * The transient location {@code 4 * transprocloc} of each full-bandwidth channel of the frame
     * parsed last, or {@code -1} without transient pre-noise data.
     */
    private final int[] transientLocation = new int[5];

    /**
     * The transient processing length {@code transproclen} of each full-bandwidth channel of the
     * frame parsed last.
     */
    private final int[] transientLength = new int[5];

    /**
     * The transient pre-noise processor, created on the first E-AC-3 frame.
     */
    private TransientPreNoise preNoise;

    /**
     * Creates a frame decoder running on a kernel.
     *
     * @param kernel the DSP kernels
     */
    public FrameDecoder(Ac3Kernel kernel) {
        this.kernel = kernel;
        this.transform = new Transform(kernel);
        for (var i = 0; i < blocks.length; i++) {
            blocks[i] = new BlockState();
        }
    }

    /**
     * Builds {@link #EXPONENT_DELTAS}.
     *
     * @return the differences, three per code
     */
    private static int[] exponentDeltas() {
        var deltas = new int[3 * 125];
        for (var code = 0; code < 125; code++) {
            deltas[3 * code] = code / 25 - 2;
            deltas[3 * code + 1] = (code % 25) / 5 - 2;
            deltas[3 * code + 2] = code % 5 - 2;
        }
        return deltas;
    }

    /**
     * Builds the values of a symmetric quantizer (A/52 tables 7.19 to 7.23).
     *
     * @param levels the number of levels
     * @return {@code (2k - (levels - 1)) / levels} for each code {@code k}
     */
    private static float[] symmetric(int levels) {
        var values = new float[levels];
        for (var k = 0; k < levels; k++) {
            values[k] = (float) (2 * k - (levels - 1)) / levels;
        }
        return values;
    }

    /**
     * Expands the values of every group code of a grouped quantizer: the code {@code c} holds the
     * level indices of its values as the base-{@code levels} digits of {@code c}, most significant
     * first.
     *
     * @param values the values of the quantizer's levels
     * @param levels the number of levels
     * @param size the number of values per group, 3 or 2
     * @return the {@code size} values of each code, the values of code {@code c} at {@code size * c}
     */
    private static float[] ungrouped(float[] values, int levels, int size) {
        var codes = size == 3 ? levels * levels * levels : levels * levels;
        var table = new float[codes * size];
        for (var code = 0; code < codes; code++) {
            var remainder = code;
            for (var k = size - 1; k >= 0; k--) {
                table[size * code + k] = values[remainder % levels];
                remainder /= levels;
            }
        }
        return table;
    }

    /**
     * Returns the header of the frame parsed last.
     *
     * @return the header
     */
    public FrameHeader header() {
        return header;
    }

    /**
     * Returns whether the frame parsed last was valid; an invalid frame is concealed.
     *
     * @return {@code true} for a valid frame
     */
    public boolean valid() {
        return valid;
    }

    /**
     * Returns the dynamic range word of a block of the frame parsed last.
     *
     * @param block the audio block index within the frame
     * @param program the program channel: 0, or 1 for the second channel of dual mono
     * @return the signed 8-bit word, {@code 0} for unity gain
     */
    public int dynamicRange(int block, int program) {
        return blocks[block].dynamicRange[program];
    }

    /**
     * Returns the number of coded channels of the frame parsed last, LFE included.
     *
     * @return the channel count
     */
    public int codedChannels() {
        return header.channels + (header.lfe ? 1 : 0);
    }

    /**
     * Resets the overlap and concealment state, as after a seek.
     */
    public void reset() {
        for (var line : delay) {
            Arrays.fill(line, 0);
        }
        for (var line : lastCoefficients) {
            Arrays.fill(line, 0);
        }
        started = false;
        concealmentGain = 0;
        if (preNoise != null) {
            preNoise.reset();
        }
    }

    /**
     * Parses one sync frame.
     *
     * <p>The frame is checked against its CRC words first; a frame that fails them, is flagged
     * corrupt, or breaks a syntax rule is marked invalid and will be concealed. The header is
     * parsed in every case where the sync information is readable.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the length of the frame, as its header declares
     * @param corrupt whether the container flagged the frame as damaged
     * @param checkCrc whether a frame failing its CRC is invalid
     * @return {@code true} when the header was readable, so the frame has a known channel
     * configuration and length
     */
    public boolean parse(byte[] data, int offset, int length, boolean corrupt, boolean checkCrc) {
        reader = new MsbBitReader(data, offset, length);
        inputData = data;
        inputOffset = offset;
        inputBitLimit = (long) (offset + length - 8) * 8;
        if (!header.parse(reader, length)) {
            valid = false;
            return false;
        }
        valid = !corrupt && (!checkCrc || crcValid(data, offset));
        if (!valid) {
            return true;
        }
        try {
            parseFrame();
            valid = !reader.overrun();
        } catch (InvalidFrame invalid) {
            valid = false;
        }
        return true;
    }

    /**
     * Checks the CRC words of the frame: {@code crc1} over the first five eighths and {@code crc2}
     * over the whole frame for AC-3, the single {@code crc2} for E-AC-3.
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @return whether the CRCs check
     */
    private boolean crcValid(byte[] data, int offset) {
        var bytes = header.frameBytes;
        if (!header.enhanced) {
            var words = bytes >> 1;
            var fiveEighths = ((words >> 1) + (words >> 3)) * 2;
            var crc = Ac3Crc.compute(data, offset + 2, fiveEighths - 2);
            return crc == 0 && Ac3Crc.update(crc, data, offset + fiveEighths, bytes - fiveEighths) == 0;
        }
        return Ac3Crc.compute(data, offset + 2, bytes - 2) == 0;
    }

    /**
     * Parses the audio frame and the blocks of a valid frame.
     */
    private void parseFrame() {
        ditherCount = 0;
        Arrays.fill(transientLocation, -1);
        var blockCount = header.blocks;
        for (var b = 0; b < blockCount; b++) {
            for (var line : coefficients[b]) {
                Arrays.fill(line, 0);
            }
            Arrays.fill(couplingValues[b], 0);
            Arrays.fill(couplingDither[b], 0);
            couplingRange[b][0] = 0;
            couplingRange[b][1] = 0;
        }
        Arrays.fill(deltaActive, false);
        for (var slot = 0; slot < SLOTS; slot++) {
            deltaVersion[slot]++;
        }
        Arrays.fill(rematrixFlags, false);
        Arrays.fill(exponentEnd, 0);
        Arrays.fill(firstCouplingCoordinates, true);
        Arrays.fill(firstSpxCoordinates, true);
        Arrays.fill(phaseFlags, false);
        firstCouplingLeak = true;
        couplingInUse = false;
        ecplInUse = false;
        spxInUse = false;
        Arrays.fill(coupled, false);
        Arrays.fill(inSpx, false);
        System.arraycopy(Ac3Tables.DEFAULT_COUPLING_BANDS, 0, couplingStructure, 0, 18);
        System.arraycopy(Ac3Tables.DEFAULT_SPX_BANDS, 0, spxStructure, 0, 17);
        System.arraycopy(Ac3Tables.DEFAULT_ECPL_BANDS, 0, ecplStructure, 0, 22);
        couplingAht = 0;
        Arrays.fill(channelAht, 0);
        lfeAht = 0;
        if (header.enhanced) {
            parseAudioFrame();
        }
        for (var b = 0; b < blockCount; b++) {
            parseBlock(b);
        }
    }

    /**
     * Parses the E-AC-3 audio frame header (A/52 Annex E section 2.2.3).
     */
    private void parseAudioFrame() {
        var channels = header.channels;
        var blockCount = header.blocks;
        if (header.numblkscod == 3) {
            exponentStrategySyntax = read(1) != 0;
            ahtEnabled = read(1) != 0;
        } else {
            exponentStrategySyntax = true;
            ahtEnabled = false;
        }
        snrOffsetStrategy = read(2);
        if (snrOffsetStrategy == 3) {
            throw INVALID;
        }
        var transientProcessing = read(1) != 0;
        blockSwitchSyntax = read(1) != 0;
        ditherSyntaxPosition = (int) reader.bitPosition();
        ditherSyntax = read(1) != 0;
        bitAllocationSyntax = read(1) != 0;
        fastGainSyntax = read(1) != 0;
        deltaSyntax = read(1) != 0;
        skipSyntax = read(1) != 0;
        var spxAttenuationSyntax = read(1) != 0;
        var couplingBlocks = 0;
        if (header.acmod > 1) {
            blockCouplingStrategy[0] = true;
            blockCoupling[0] = read(1) != 0;
            for (var b = 1; b < blockCount; b++) {
                blockCouplingStrategy[b] = read(1) != 0;
                blockCoupling[b] = blockCouplingStrategy[b] ? read(1) != 0 : blockCoupling[b - 1];
            }
        } else {
            Arrays.fill(blockCouplingStrategy, false);
            Arrays.fill(blockCoupling, false);
        }
        for (var b = 0; b < blockCount; b++) {
            couplingBlocks += blockCoupling[b] ? 1 : 0;
        }
        if (exponentStrategySyntax) {
            for (var b = 0; b < blockCount; b++) {
                blockCouplingExponents[b] = blockCoupling[b] ? read(2) : 0;
                for (var ch = 0; ch < channels; ch++) {
                    blockChannelExponents[b][ch] = read(2);
                }
            }
        } else {
            var couplingCode = header.acmod > 1 && couplingBlocks > 0 ? read(5) : 0;
            for (var b = 0; b < 6; b++) {
                blockCouplingExponents[b] = Ac3Tables.FRAME_EXPONENT_STRATEGIES[couplingCode][b];
            }
            for (var ch = 0; ch < channels; ch++) {
                var strategies = Ac3Tables.FRAME_EXPONENT_STRATEGIES[read(5)];
                for (var b = 0; b < 6; b++) {
                    blockChannelExponents[b][ch] = strategies[b];
                }
            }
        }
        if (header.lfe) {
            for (var b = 0; b < blockCount; b++) {
                blockLfeExponents[b] = read(1);
            }
        }
        if (header.streamType == 0) {
            var converter = header.numblkscod != 3 ? read(1) != 0 : true;
            if (converter) {
                reader.skip(5L * channels);
            }
        }
        if (ahtEnabled) {
            var couplingRegions = 0;
            for (var b = 0; b < 6; b++) {
                if (blockCouplingStrategy[b] || blockCouplingExponents[b] != 0) {
                    couplingRegions++;
                }
            }
            couplingAht = couplingBlocks == 6 && couplingRegions == 1 ? read(1) : 0;
            for (var ch = 0; ch < channels; ch++) {
                var regions = 0;
                for (var b = 0; b < 6; b++) {
                    regions += blockChannelExponents[b][ch] != 0 ? 1 : 0;
                }
                channelAht[ch] = regions == 1 ? read(1) : 0;
            }
            if (header.lfe) {
                var regions = 0;
                for (var b = 0; b < 6; b++) {
                    regions += blockLfeExponents[b] != 0 ? 1 : 0;
                }
                lfeAht = regions == 1 ? read(1) : 0;
            }
        }
        if (snrOffsetStrategy == 0) {
            frameCoarseSnr = read(6);
            frameFineSnr = read(4);
        }
        if (transientProcessing) {
            for (var ch = 0; ch < channels; ch++) {
                if (read(1) != 0) {
                    transientLocation[ch] = read(10) << 2;
                    transientLength[ch] = read(8);
                }
            }
        }
        Arrays.fill(spxAttenuation, false);
        if (spxAttenuationSyntax) {
            for (var ch = 0; ch < channels; ch++) {
                spxAttenuation[ch] = read(1) != 0;
                if (spxAttenuation[ch]) {
                    spxAttenuationCode[ch] = read(5);
                }
            }
        }
        if (header.numblkscod != 0 && read(1) != 0) {
            var words = header.frameBytes >> 1;
            var bits = 32 - Integer.numberOfLeadingZeros(words - 1);
            reader.skip((long) (blockCount - 1) * (4 + bits));
        }
    }

    /**
     * Parses one audio block (A/52 section 5.3.3 and Annex E section 2.2.4).
     *
     * @param b the block index
     */
    private void parseBlock(int b) {
        var enhanced = header.enhanced;
        var channels = header.channels;
        var acmod = header.acmod;
        var state = blocks[b];
        for (var ch = 0; ch < channels; ch++) {
            state.shortBlocks[ch] = enhanced ? blockSwitchSyntax && read(1) != 0 : read(1) != 0;
        }
        state.shortBlocks[LFE] = false;
        if (ditherPositions != null && enhanced && !ditherSyntax) {
            ditherInsertions[b] = (int) reader.bitPosition();
        }
        for (var ch = 0; ch < channels; ch++) {
            if (ditherPositions != null && (!enhanced || ditherSyntax)) {
                ditherPositions[ditherCount++] = (int) reader.bitPosition();
            }
            state.dither[ch] = enhanced ? !ditherSyntax || read(1) != 0 : read(1) != 0;
        }
        for (var program = 0; program < (acmod == 0 ? 2 : 1); program++) {
            if (read(1) != 0) {
                dynamicRange[program] = (byte) read(8);
            } else if (b == 0) {
                dynamicRange[program] = 0;
            }
        }
        state.dynamicRange[0] = dynamicRange[0];
        state.dynamicRange[1] = acmod == 0 ? dynamicRange[1] : dynamicRange[0];
        if (enhanced) {
            parseSpectralExtension(b);
        }
        parseCoupling(b);
        if (acmod == 2) {
            var strategy = enhanced ? b == 0 || read(1) != 0 : read(1) != 0;
            if (strategy) {
                rematrixBands = rematrixBandCount();
                for (var band = 0; band < rematrixBands; band++) {
                    rematrixFlags[band] = read(1) != 0;
                }
            } else if (b == 0) {
                throw INVALID;
            }
        }
        parseExponents(b);
        parseBitAllocation(b);
        computeBitAllocation(b);
        parseMantissas(b);
        snapshot(state);
    }

    /**
     * Parses the spectral extension strategy and coordinates of an E-AC-3 block.
     *
     * @param b the block index
     */
    private void parseSpectralExtension(int b) {
        var channels = header.channels;
        var strategy = b == 0 || read(1) != 0;
        if (strategy) {
            spxInUse = read(1) != 0;
            if (spxInUse) {
                if (header.acmod == 1) {
                    inSpx[0] = true;
                } else {
                    for (var ch = 0; ch < channels; ch++) {
                        inSpx[ch] = read(1) != 0;
                    }
                }
                spxStart = read(2);
                spxBeginCode = read(3);
                var endCode = read(3);
                spxBegin = spxBeginCode < 6 ? spxBeginCode + 2 : spxBeginCode * 2 - 3;
                spxEnd = endCode < 3 ? endCode + 5 : endCode * 2 + 3;
                if (spxBegin >= spxEnd || spxStart >= spxBegin) {
                    throw INVALID;
                }
                if (read(1) != 0) {
                    for (var band = spxBegin + 1; band < spxEnd; band++) {
                        spxStructure[band] = read(1) != 0;
                    }
                }
                spxBands = 1;
                spxBandSize[0] = 12;
                for (var band = spxBegin + 1; band < spxEnd; band++) {
                    if (!spxStructure[band]) {
                        spxBandSize[spxBands++] = 12;
                    } else {
                        spxBandSize[spxBands - 1] += 12;
                    }
                }
            } else {
                for (var ch = 0; ch < channels; ch++) {
                    inSpx[ch] = false;
                    firstSpxCoordinates[ch] = true;
                }
            }
        }
        if (!spxInUse) {
            return;
        }
        for (var ch = 0; ch < channels; ch++) {
            if (!inSpx[ch]) {
                firstSpxCoordinates[ch] = true;
                continue;
            }
            boolean present;
            if (firstSpxCoordinates[ch]) {
                present = true;
                firstSpxCoordinates[ch] = false;
            } else {
                present = read(1) != 0;
            }
            if (!present) {
                continue;
            }
            var blend = read(5);
            var master = read(2);
            var noiseOffset = blend / 32.0f;
            var mantissaIndex = 25 + 12 * spxBegin;
            var top = 25 + 12 * spxEnd;
            for (var band = 0; band < spxBands; band++) {
                var exponent = read(4);
                var mantissa = read(2);
                var value = exponent == 15 ? mantissa / 4.0f : (mantissa + 4) / 8.0f;
                spxCoordinates[ch][band] = value * Ac3Tables.POW2_NEG[exponent + 3 * master] * 32;
                var size = spxBandSize[band];
                var ratio = (mantissaIndex + 0.5f * size) / top - noiseOffset;
                ratio = Math.min(1.0f, Math.max(0.0f, ratio));
                spxNoise[ch][band] = (float) Math.sqrt(ratio);
                spxSignal[ch][band] = (float) Math.sqrt(1 - ratio);
                mantissaIndex += size;
            }
        }
    }

    /**
     * Parses the coupling strategy and the coupling coordinates of a block.
     *
     * @param b the block index
     */
    private void parseCoupling(int b) {
        var enhanced = header.enhanced;
        var channels = header.channels;
        var acmod = header.acmod;
        boolean strategy;
        if (enhanced) {
            strategy = blockCouplingStrategy[b];
            couplingInUse = blockCoupling[b];
        } else {
            strategy = read(1) != 0;
            if (b == 0 && !strategy) {
                throw INVALID;
            }
            if (strategy) {
                couplingInUse = read(1) != 0;
            }
        }
        if (strategy) {
            if (couplingInUse) {
                if (acmod < 2) {
                    throw INVALID;
                }
                ecplInUse = enhanced && read(1) != 0;
                if (enhanced && acmod == 2) {
                    coupled[0] = true;
                    coupled[1] = true;
                } else {
                    for (var ch = 0; ch < channels; ch++) {
                        coupled[ch] = read(1) != 0;
                    }
                }
                if (!ecplInUse) {
                    parseCouplingStrategy(enhanced, acmod);
                } else {
                    parseEnhancedCouplingStrategy();
                }
                var count = 0;
                for (var ch = 0; ch < channels; ch++) {
                    count += coupled[ch] ? 1 : 0;
                }
                if (count < 2) {
                    throw INVALID;
                }
            } else {
                for (var ch = 0; ch < channels; ch++) {
                    coupled[ch] = false;
                    firstCouplingCoordinates[ch] = true;
                }
                firstCouplingLeak = true;
                phaseFlagsInUse = false;
                ecplInUse = false;
            }
        }
        if (!couplingInUse) {
            return;
        }
        if (ecplInUse) {
            parseEnhancedCouplingCoordinates();
            return;
        }
        var anyNew = false;
        var newLeft = false;
        var newRight = false;
        for (var ch = 0; ch < channels; ch++) {
            if (!coupled[ch]) {
                if (enhanced) {
                    firstCouplingCoordinates[ch] = true;
                }
                continue;
            }
            boolean present;
            if (enhanced && firstCouplingCoordinates[ch]) {
                present = true;
                firstCouplingCoordinates[ch] = false;
            } else {
                present = read(1) != 0;
            }
            if (!present) {
                continue;
            }
            anyNew = true;
            if (ch == 0) {
                newLeft = true;
            } else if (ch == 1) {
                newRight = true;
            }
            var master = read(2);
            for (var band = 0; band < couplingBands; band++) {
                var exponent = read(4);
                var mantissa = read(4);
                var value = exponent == 15 ? mantissa / 16.0f : (mantissa + 16) / 32.0f;
                couplingCoordinates[ch][band] = value * Ac3Tables.POW2_NEG[exponent + 3 * master] * 8;
            }
        }
        if (acmod == 2 && phaseFlagsInUse && (newLeft || newRight)) {
            for (var band = 0; band < couplingBands; band++) {
                phaseFlags[band] = read(1) != 0;
            }
        } else if (acmod != 2 || !phaseFlagsInUse) {
            Arrays.fill(phaseFlags, false);
        }
        if (!anyNew && b == 0 && !enhanced) {
            throw INVALID;
        }
    }

    /**
     * Parses the standard coupling strategy after the coupled channels.
     *
     * @param enhanced whether the frame is E-AC-3
     * @param acmod the audio coding mode
     */
    private void parseCouplingStrategy(boolean enhanced, int acmod) {
        phaseFlagsInUse = acmod == 2 && read(1) != 0;
        couplingBegin = read(4);
        int endCode;
        if (!enhanced || !spxInUse) {
            endCode = read(4);
        } else {
            endCode = spxBeginCode < 6 ? spxBeginCode - 2 : spxBeginCode * 2 - 7;
        }
        couplingSubbands = 3 + endCode - couplingBegin;
        if (couplingSubbands < 1 || couplingBegin + couplingSubbands > 18) {
            throw INVALID;
        }
        if (!enhanced || read(1) != 0) {
            for (var subband = couplingBegin + 1; subband < couplingBegin + couplingSubbands; subband++) {
                couplingStructure[subband] = read(1) != 0;
            }
        }
        var band = 0;
        couplingBandOf[couplingBegin] = 0;
        for (var subband = couplingBegin + 1; subband < couplingBegin + couplingSubbands; subband++) {
            if (!couplingStructure[subband]) {
                band++;
            }
            couplingBandOf[subband] = band;
        }
        couplingBands = band + 1;
    }

    /**
     * Parses the enhanced coupling strategy after the coupled channels (A/52 Annex E section
     * 2.2.4).
     */
    private void parseEnhancedCouplingStrategy() {
        var beginCode = read(4);
        ecplBeginCode = beginCode;
        ecplBegin = beginCode < 3 ? beginCode * 2 : beginCode < 13 ? beginCode + 2 : beginCode * 2 - 10;
        if (!spxInUse) {
            ecplEnd = read(4) + 7;
        } else {
            ecplEnd = spxBeginCode < 6 ? spxBeginCode + 5 : spxBeginCode * 2;
        }
        if (ecplBegin >= ecplEnd || ecplEnd > 22) {
            throw INVALID;
        }
        if (read(1) != 0) {
            for (var subband = Math.max(9, ecplBegin + 1); subband < ecplEnd; subband++) {
                ecplStructure[subband] = read(1) != 0;
            }
        }
        var band = -1;
        var limit = Math.max(8, ecplBegin);
        for (var subband = ecplBegin; subband < ecplEnd; subband++) {
            if (subband <= limit || !ecplStructure[subband]) {
                band++;
            }
            ecplBandOf[subband] = band;
        }
        ecplBands = band + 1;
    }

    /**
     * Parses the enhanced coupling parameters of each coupled channel (ETSI TS 102 366 clause
     * E.1.2.4): the amplitudes, which rebuild the channel, and the angle and chaos fields, which are
     * skipped.
     *
     * @implNote This implementation skips 9 bits per enhanced coupling band where the parameters 2
     * flag ({@code ecplparam2e}, {@code rsvdfieldse} in V1.3.1 and later) is set, the count of
     * ATSC A/52 Annex E and ETSI TS 102 366 V1.2.1 (a 6-bit angle and a 3-bit chaos value per
     * band), not the {@code 9 * (necplbnd - 1)} reserved bits of V1.3.1 and later: a stream of the
     * Dolby encoder that shipped with Adobe Audition parses in every frame with the former and in
     * none with the latter, and Dolby's own decoder (the Windows Media Foundation decoder) reads it
     * the same way. The angles, chaos values, the angle interpolation flag and the transient flags
     * are ignored, as V1.3.1 and later specify and as that decoder does: decoding the stream with
     * them applied departs from its output by about 10 dB, decoding it without them matches it to
     * the precision of a float decode.
     */
    private void parseEnhancedCouplingCoordinates() {
        var channels = header.channels;
        var first = -1;
        reader.skip(1);
        for (var ch = 0; ch < channels; ch++) {
            if (!coupled[ch]) {
                firstCouplingCoordinates[ch] = true;
                continue;
            }
            if (first == -1) {
                first = ch;
            }
            boolean amplitudes;
            boolean reserved;
            if (firstCouplingCoordinates[ch]) {
                amplitudes = true;
                reserved = ch > first;
                firstCouplingCoordinates[ch] = false;
            } else {
                amplitudes = read(1) != 0;
                reserved = ch > first && read(1) != 0;
            }
            if (amplitudes) {
                for (var band = 0; band < ecplBands; band++) {
                    ecplAmplitudeCodes[ch][band] = read(5);
                }
            }
            if (reserved) {
                reader.skip(9L * ecplBands);
            }
            if (ch > first) {
                reader.skip(1);
            }
        }
    }

    /**
     * Returns the number of rematrixing bands of the current block (A/52 table 5.15 and Annex E
     * section 3.3.2).
     *
     * @return the band count
     */
    private int rematrixBandCount() {
        if (couplingInUse) {
            if (ecplInUse) {
                var code = ecplBeginCode;
                if (code == 0) {
                    return 0;
                }
                if (code == 1) {
                    return 1;
                }
                if (code == 2) {
                    return 2;
                }
                return code < 5 ? 3 : 4;
            }
            if (couplingBegin == 0) {
                return 2;
            }
            return couplingBegin < 3 ? 3 : 4;
        }
        if (spxInUse) {
            return spxBeginCode < 2 ? 3 : 4;
        }
        return 4;
    }

    /**
     * Parses the exponent strategies, bandwidth codes and exponents of a block.
     *
     * @param b the block index
     */
    private void parseExponents(int b) {
        var enhanced = header.enhanced;
        var channels = header.channels;
        int couplingStrategy;
        if (enhanced) {
            couplingStrategy = couplingInUse ? blockCouplingExponents[b] : 0;
            for (var ch = 0; ch < channels; ch++) {
                exponentStrategy[ch] = blockChannelExponents[b][ch];
            }
            exponentStrategy[LFE] = header.lfe ? blockLfeExponents[b] : 0;
        } else {
            couplingStrategy = couplingInUse ? read(2) : 0;
            for (var ch = 0; ch < channels; ch++) {
                exponentStrategy[ch] = read(2);
            }
            exponentStrategy[LFE] = header.lfe ? read(1) : 0;
        }
        exponentStrategy[CPL] = couplingStrategy;
        for (var ch = 0; ch < channels; ch++) {
            if (exponentStrategy[ch] != 0 && !coupled[ch] && !(spxInUse && inSpx[ch])) {
                bandwidthCode[ch] = read(6);
                if (bandwidthCode[ch] > 60) {
                    throw INVALID;
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            int end;
            if (coupled[ch] && couplingInUse) {
                end = ecplInUse ? Ac3Tables.ECPL_SUBBAND_START[ecplBegin] : 37 + 12 * couplingBegin;
            } else if (spxInUse && inSpx[ch]) {
                end = 25 + 12 * spxBegin;
            } else {
                end = 37 + 3 * (bandwidthCode[ch] + 12);
            }
            endMantissa[ch] = end;
        }
        endMantissa[LFE] = 7;
        int couplingStart = 0;
        int couplingEnd = 0;
        if (couplingInUse) {
            if (ecplInUse) {
                couplingStart = Ac3Tables.ECPL_SUBBAND_START[ecplBegin];
                couplingEnd = Ac3Tables.ECPL_SUBBAND_START[ecplEnd];
            } else {
                couplingStart = 37 + 12 * couplingBegin;
                couplingEnd = 37 + 12 * (couplingBegin + couplingSubbands);
            }
        }
        endMantissa[CPL] = couplingEnd;
        couplingRange[b][0] = couplingStart;
        couplingRange[b][1] = couplingEnd;
        if (couplingInUse) {
            if (couplingStrategy != 0) {
                var absolute = read(4) << 1;
                var groupSize = 1 << (couplingStrategy - 1);
                var groups = (couplingEnd - couplingStart) / (3 * groupSize);
                decodeExponents(exponents[CPL], couplingStart - 1, absolute, groups, groupSize, 7);
                exponentVersion[CPL]++;
                couplingExponentStart = couplingStart;
                exponentEnd[CPL] = couplingStart + groups * 3 * groupSize;
            } else if (exponentEnd[CPL] < couplingEnd || couplingExponentStart > couplingStart) {
                throw INVALID;
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var strategy = exponentStrategy[ch];
            var end = endMantissa[ch];
            if (strategy == 0) {
                if (exponentEnd[ch] < end) {
                    throw INVALID;
                }
                continue;
            }
            var groupSize = 1 << (strategy - 1);
            var groups = (end - 1 + 3 * groupSize - 3) / (3 * groupSize);
            var absolute = read(4);
            decodeExponents(exponents[ch], 0, absolute, groups, groupSize, 7);
            exponentVersion[ch]++;
            exponentEnd[ch] = 1 + groups * 3 * groupSize;
            reader.skip(2);
        }
        if (header.lfe) {
            if (exponentStrategy[LFE] != 0) {
                var absolute = read(4);
                decodeExponents(exponents[LFE], 0, absolute, 2, 1, 7);
                exponentVersion[LFE]++;
                exponentEnd[LFE] = 7;
            } else if (exponentEnd[LFE] < 7) {
                throw INVALID;
            }
        }
    }

    /**
     * Decodes one set of grouped differential exponents (A/52 section 7.1.3).
     *
     * @param out the exponent array
     * @param first the index receiving the absolute exponent; the decoded exponents follow it
     * @param absolute the absolute exponent
     * @param groups the number of 7-bit groups
     * @param groupSize the number of mantissas sharing each exponent: 1, 2 or 4
     * @param bits the width of a group code
     */
    private void decodeExponents(int[] out, int first, int absolute, int groups, int groupSize, int bits) {
        if (first >= 0) {
            out[first] = absolute;
        }
        var previous = absolute;
        var index = first + 1;
        var deltas = EXPONENT_DELTAS;
        for (var group = 0; group < groups; group++) {
            var code = read(bits);
            if (code > 124) {
                throw INVALID;
            }
            for (var k = 0; k < 3; k++) {
                previous += deltas[3 * code + k];
                if (previous < 0 || previous > 24) {
                    throw INVALID;
                }
                for (var j = 0; j < groupSize; j++) {
                    out[index++] = previous;
                }
            }
        }
    }

    /**
     * Parses the bit allocation parameters, SNR offsets, fast gains, coupling leaks, delta bit
     * allocation and skip field of a block.
     *
     * @param b the block index
     */
    private void parseBitAllocation(int b) {
        var enhanced = header.enhanced;
        var channels = header.channels;
        var lfe = header.lfe;
        if (enhanced) {
            if (bitAllocationSyntax) {
                if (read(1) != 0) {
                    readAllocationCodes();
                } else if (b == 0) {
                    throw INVALID;
                }
            } else {
                allocationCodes[0] = 2;
                allocationCodes[1] = 1;
                allocationCodes[2] = 1;
                allocationCodes[3] = 2;
                allocationCodes[4] = 7;
            }
            if (snrOffsetStrategy == 0) {
                coarseSnr = frameCoarseSnr;
                Arrays.fill(fineSnr, frameFineSnr);
            } else if (b == 0 || read(1) != 0) {
                coarseSnr = read(6);
                if (snrOffsetStrategy == 1) {
                    Arrays.fill(fineSnr, read(4));
                } else {
                    if (couplingInUse) {
                        fineSnr[CPL] = read(4);
                    }
                    for (var ch = 0; ch < channels; ch++) {
                        fineSnr[ch] = read(4);
                    }
                    if (lfe) {
                        fineSnr[LFE] = read(4);
                    }
                }
            }
            if (fastGainSyntax && read(1) != 0) {
                if (couplingInUse) {
                    fastGainCode[CPL] = read(3);
                }
                for (var ch = 0; ch < channels; ch++) {
                    fastGainCode[ch] = read(3);
                }
                if (lfe) {
                    fastGainCode[LFE] = read(3);
                }
            } else {
                Arrays.fill(fastGainCode, 4);
            }
            if (header.streamType == 0 && read(1) != 0) {
                reader.skip(10);
            }
            if (couplingInUse) {
                boolean leak;
                if (firstCouplingLeak) {
                    leak = true;
                    firstCouplingLeak = false;
                } else {
                    leak = read(1) != 0;
                }
                if (leak) {
                    couplingFastLeak = read(3);
                    couplingSlowLeak = read(3);
                }
            }
            if (deltaSyntax) {
                parseDelta(channels);
            }
            if (skipSyntax && read(1) != 0) {
                reader.skip(8L * read(9));
            }
            return;
        }
        if (read(1) != 0) {
            readAllocationCodes();
        } else if (b == 0) {
            throw INVALID;
        }
        if (read(1) != 0) {
            coarseSnr = read(6);
            if (couplingInUse) {
                fineSnr[CPL] = read(4);
                fastGainCode[CPL] = read(3);
            }
            for (var ch = 0; ch < channels; ch++) {
                fineSnr[ch] = read(4);
                fastGainCode[ch] = read(3);
            }
            if (lfe) {
                fineSnr[LFE] = read(4);
                fastGainCode[LFE] = read(3);
            }
        } else if (b == 0) {
            throw INVALID;
        }
        if (couplingInUse) {
            if (read(1) != 0) {
                couplingFastLeak = read(3);
                couplingSlowLeak = read(3);
            } else if (b == 0) {
                throw INVALID;
            }
            firstCouplingLeak = false;
        } else {
            firstCouplingLeak = true;
        }
        parseDelta(channels);
        if (read(1) != 0) {
            reader.skip(8L * read(9));
        }
    }

    /**
     * Reads the five bit allocation parameter codes.
     */
    private void readAllocationCodes() {
        allocationCodes[0] = read(2);
        allocationCodes[1] = read(2);
        allocationCodes[2] = read(2);
        allocationCodes[3] = read(2);
        allocationCodes[4] = read(3);
    }

    /**
     * Parses the delta bit allocation information.
     *
     * @param channels the number of full-bandwidth channels
     */
    private void parseDelta(int channels) {
        if (read(1) == 0) {
            return;
        }
        var couplingCode = couplingInUse ? read(2) : 0;
        var codes = deltaCodes;
        for (var ch = 0; ch < channels; ch++) {
            codes[ch] = read(2);
        }
        if (couplingInUse) {
            deltaCode(CPL, couplingCode);
        }
        for (var ch = 0; ch < channels; ch++) {
            deltaCode(ch, codes[ch]);
        }
    }

    /**
     * Applies one delta bit allocation exists code, reading the segments it announces.
     *
     * @param slot the channel slot
     * @param code {@code 0} reuse, {@code 1} new segments, {@code 2} or {@code 3} none
     */
    private void deltaCode(int slot, int code) {
        if (code == 1) {
            var segments = read(3) + 1;
            for (var segment = 0; segment < segments; segment++) {
                deltaOffsets[slot][segment] = read(5);
                deltaLengths[slot][segment] = read(4);
                deltaValues[slot][segment] = read(3);
            }
            deltaSegments[slot] = segments;
            deltaActive[slot] = true;
            deltaVersion[slot]++;
        } else if (code >= 2) {
            deltaActive[slot] = false;
            deltaVersion[slot]++;
        }
    }

    /**
     * Runs the bit allocation of every exponent set of a block.
     *
     * @param b the block index
     */
    private void computeBitAllocation(int b) {
        var channels = header.channels;
        parameters.set(header.rateIndex, allocationCodes[0], allocationCodes[1], allocationCodes[2],
                allocationCodes[3], allocationCodes[4]);
        var allZero = coarseSnr == 0;
        if (allZero) {
            for (var ch = 0; ch < channels && allZero; ch++) {
                allZero = fineSnr[ch] == 0;
            }
            allZero &= !couplingInUse || fineSnr[CPL] == 0;
            allZero &= !header.lfe || fineSnr[LFE] == 0;
        }
        for (var ch = 0; ch < channels; ch++) {
            if (channelAht[ch] == -1) {
                continue;
            }
            allocate(ch, 0, endMantissa[ch], allZero, channelAht[ch] == 1);
        }
        if (couplingInUse && couplingAht != -1) {
            allocate(CPL, couplingRange[b][0], couplingRange[b][1], allZero, couplingAht == 1);
        }
        if (header.lfe && lfeAht != -1) {
            allocate(LFE, 0, 7, allZero, lfeAht == 1);
        }
    }

    /**
     * Runs the bit allocation of one exponent set.
     *
     * @param slot the channel slot
     * @param start the first mantissa
     * @param end one past the last mantissa
     * @param allZero whether every SNR offset is zero, which zeroes every pointer
     * @param hybrid whether the set uses the adaptive hybrid transform pointers
     */
    private void allocate(int slot, int start, int end, boolean allZero, boolean hybrid) {
        var snrOffset = (((coarseSnr - 15) << 4) + fineSnr[slot]) << 2;
        var fastGain = Ac3Tables.FAST_GAIN[fastGainCode[slot]];
        var fastLeak = (couplingFastLeak << 8) + 768;
        var slowLeak = (couplingSlowLeak << 8) + 768;
        var segments = deltaActive[slot] && slot != LFE ? deltaSegments[slot] : 0;
        var inputs = allocationInputs;
        var at = 4 * slot;
        var versions = ((long) exponentVersion[slot] << 32) | (deltaVersion[slot] & 0xFFFFFFFFL);
        var range = ((long) start << 48) | ((long) end << 32) | (snrOffset & 0xFFFFFFFFL);
        var settings = ((long) fastGain << 32) | ((long) parameterCodes() << 2) | (hybrid ? 2 : 0) | (allZero ? 1 : 0);
        var leaks = slot == CPL ? ((long) fastLeak << 32) | (slowLeak & 0xFFFFFFFFL) : 0;
        if (allocated[slot] && inputs[at] == versions && inputs[at + 1] == range && inputs[at + 2] == settings
                && inputs[at + 3] == leaks) {
            return;
        }
        allocated[slot] = true;
        inputs[at] = versions;
        inputs[at + 1] = range;
        inputs[at + 2] = settings;
        inputs[at + 3] = leaks;
        if (allZero) {
            Arrays.fill(pointers[slot], start, end, 0);
            return;
        }
        allocation.allocate(exponents[slot], start, end, parameters, fastGain, snrOffset, fastLeak, slowLeak, segments,
                deltaOffsets[slot], deltaLengths[slot], deltaValues[slot], hybrid ? Ac3Tables.HEBAP : Ac3Tables.BAP,
                pointers[slot]);
    }

    /**
     * Packs the bit allocation parameter codes of the current block and the hearing threshold row.
     *
     * @return the codes, three bits each
     */
    private int parameterCodes() {
        var codes = allocationCodes;
        return header.rateIndex | codes[0] << 3 | codes[1] << 6 | codes[2] << 9 | codes[3] << 12 | codes[4] << 15;
    }

    /**
     * Parses the mantissas of a block.
     *
     * @param b the block index
     */
    private void parseMantissas(int b) {
        var channels = header.channels;
        var state = blocks[b];
        group3Next = group3End = 0;
        group5Next = group5End = 0;
        group11Next = group11End = 0;
        var gotCoupling = false;
        for (var ch = 0; ch < channels; ch++) {
            if (channelAht[ch] == 0) {
                readChannel(coefficients[b][ch], exponents[ch], pointers[ch], 0, endMantissa[ch], state.dither[ch]);
            } else if (channelAht[ch] == 1) {
                readHybrid(ch, 0, endMantissa[ch], state.dither[ch]);
                channelAht[ch] = -1;
            }
            if (couplingInUse && coupled[ch] && !gotCoupling) {
                if (couplingAht == 0) {
                    readCoupling(b);
                } else if (couplingAht == 1) {
                    readHybrid(CPL, couplingRange[b][0], couplingRange[b][1], false);
                    couplingAht = -1;
                }
                gotCoupling = true;
            }
        }
        if (header.lfe) {
            if (lfeAht == 0) {
                readChannel(coefficients[b][LFE], exponents[LFE], pointers[LFE], 0, 7, false);
            } else if (lfeAht == 1) {
                readHybrid(LFE, 0, 7, false);
                lfeAht = -1;
            }
        }
    }

    /**
     * Reads the mantissas of one channel and turns them into transform coefficients.
     *
     * @param out receives the coefficients
     * @param exponents the exponent of each coefficient of the channel
     * @param pointers the bit allocation pointers
     * @param start the first mantissa
     * @param end one past the last mantissa
     * @param dither whether zero-bit mantissas are replaced with dither
     */
    private void readChannel(float[] out, int[] exponents, int[] pointers, int start, int end, boolean dither) {
        var scale = Ac3Tables.POW2_NEG;
        var widths = Ac3Tables.MANTISSA_BITS;
        var bin = start;
        while (bin < end) {
            var pointer = pointers[bin];
            float value;
            if (pointer >= 6) {
                var next = readPlainRun(out, exponents, pointers, bin, end);
                if (next == bin) {
                    var bits = widths[pointer];
                    var shift = 32 - bits;
                    out[bin] = ((reader.read(bits) << shift) >> shift) * scale[bits - 1 + exponents[bin]];
                    next++;
                }
                bin = next;
                continue;
            } else if (pointer == 0) {
                value = dither ? noise() * DITHER_SCALE : 0;
            } else {
                value = mantissa(pointer);
            }
            out[bin] = value * scale[exponents[bin]];
            bin++;
        }
    }

    /**
     * Reads the mantissas of the run of consecutive mantissas that start at {@code from} and have
     * a pointer of 6 or more, the plain two's complement fields of A/52 section 7.3, and stops at
     * the first other pointer or when fewer than eight bytes of the frame remain.
     *
     * @implNote This implementation reads each field with an unaligned 8-byte load at its bit
     * position, so that the only value carried from one field to the next is the position.
     *
     * @param out receives the coefficients
     * @param exponents the exponent of each coefficient of the channel
     * @param pointers the bit allocation pointers
     * @param from the first mantissa of the run
     * @param end one past the last mantissa
     * @return one past the last mantissa read; {@code from} when none was read
     */
    private int readPlainRun(float[] out, int[] exponents, int[] pointers, int from, int end) {
        var scale = Ac3Tables.POW2_NEG;
        var widths = Ac3Tables.MANTISSA_BITS;
        var data = inputData;
        var base = (long) inputOffset * 8;
        var origin = base + reader.bitPosition();
        var position = origin;
        var limit = inputBitLimit;
        var bin = from;
        while (bin < end && position <= limit) {
            var pointer = pointers[bin];
            if (pointer < 6) {
                break;
            }
            var bits = widths[pointer];
            var word = (long) LONG_BE.get(data, (int) (position >>> 3)) << (position & 7);
            out[bin] = (int) (word >> (64 - bits)) * scale[bits - 1 + exponents[bin]];
            position += bits;
            bin++;
        }
        if (position != origin) {
            reader.skip(position - origin);
        }
        return bin;
    }

    /**
     * Reads the coupling channel mantissas of a block.
     *
     * @param b the block index
     */
    private void readCoupling(int b) {
        var values = couplingValues[b];
        var dither = couplingDither[b];
        var exponents = this.exponents[CPL];
        var pointers = this.pointers[CPL];
        var scale = Ac3Tables.POW2_NEG;
        for (var bin = couplingRange[b][0]; bin < couplingRange[b][1]; bin++) {
            var pointer = pointers[bin];
            if (pointer == 0) {
                values[bin] = 0;
                dither[bin] = scale[exponents[bin]];
            } else {
                values[bin] = mantissa(pointer) * scale[exponents[bin]];
                dither[bin] = 0;
            }
        }
    }

    /**
     * Reads one mantissa of a non-zero bit allocation pointer, ungrouping grouped codes (A/52
     * section 7.3).
     *
     * @param pointer the bit allocation pointer, from 1 to 15
     * @return the mantissa as a fraction
     */
    private float mantissa(int pointer) {
        switch (pointer) {
            case 1 -> {
                if (group3Next == group3End) {
                    var code = read(5);
                    if (code > 26) {
                        throw INVALID;
                    }
                    group3Next = 3 * code;
                    group3End = group3Next + 3;
                }
                return UNGROUPED_3[group3Next++];
            }
            case 2 -> {
                if (group5Next == group5End) {
                    var code = read(7);
                    if (code > 124) {
                        throw INVALID;
                    }
                    group5Next = 3 * code;
                    group5End = group5Next + 3;
                }
                return UNGROUPED_5[group5Next++];
            }
            case 3 -> {
                var code = read(3);
                if (code > 6) {
                    throw INVALID;
                }
                return LEVELS_7[code];
            }
            case 4 -> {
                if (group11Next == group11End) {
                    var code = read(7);
                    if (code > 120) {
                        throw INVALID;
                    }
                    group11Next = 2 * code;
                    group11End = group11Next + 2;
                }
                return UNGROUPED_11[group11Next++];
            }
            case 5 -> {
                var code = read(4);
                if (code > 14) {
                    throw INVALID;
                }
                return LEVELS_15[code];
            }
            default -> {
                var bits = Ac3Tables.MANTISSA_BITS[pointer];
                return signed(bits) * Ac3Tables.POW2_NEG[bits - 1];
            }
        }
    }

    /**
     * Reads the adaptive hybrid transform mantissas of one exponent set in the first block and
     * spreads them over the blocks of the frame (A/52 Annex E section 3.4).
     *
     * @param slot the channel slot
     * @param start the first mantissa
     * @param end one past the last mantissa
     * @param dither whether zero-bit mantissas are replaced with dither: the dither flag of the
     * first block for a full-bandwidth channel, never for the coupling and LFE
     * channels
     */
    private void readHybrid(int slot, int start, int end, boolean dither) {
        var mode = read(2);
        var endPointer = mode < 2 ? 12 : 17;
        var hebap = pointers[slot];
        var active = 0;
        for (var bin = start; bin < end; bin++) {
            if (hebap[bin] > 7 && hebap[bin] < endPointer) {
                active++;
            }
        }
        if (mode == 1 || mode == 2) {
            for (var k = 0; k < active; k++) {
                gaqGains[k] = read(1) != 0 ? mode : 0;
            }
        } else if (mode == 3) {
            for (var k = 0; k < active; k += 3) {
                var code = read(5);
                if (code > 26) {
                    throw INVALID;
                }
                gaqGains[k] = code / 9;
                if (k + 1 < active) {
                    gaqGains[k + 1] = (code % 9) / 3;
                }
                if (k + 2 < active) {
                    gaqGains[k + 2] = code % 3;
                }
            }
        }
        var gainIndex = 0;
        var scale = Ac3Tables.POW2_NEG;
        var exponents = this.exponents[slot];
        for (var bin = start; bin < end; bin++) {
            var pointer = hebap[bin];
            if (pointer >= 8) {
                var gain = 0;
                if (pointer < endPointer) {
                    gain = mode != 0 ? gaqGains[gainIndex] : 0;
                    gainIndex++;
                }
                for (var n = 0; n < 6; n++) {
                    ahtIn[n] = gaqMantissa(pointer, gain);
                }
            } else if (pointer == 0) {
                if (dither) {
                    for (var n = 0; n < 6; n++) {
                        ahtIn[n] = noise() * DITHER_SCALE;
                    }
                } else {
                    Arrays.fill(ahtIn, 0);
                }
            } else {
                Aht.vector(pointer, read(Ac3Tables.VQ_BITS[pointer]), ahtIn);
            }
            Aht.inverse(ahtIn, ahtOut);
            var exponentScale = scale[exponents[bin]];
            if (slot == CPL) {
                var zero = pointer == 0;
                for (var m = 0; m < 6; m++) {
                    couplingValues[m][bin] = ahtOut[m] * exponentScale;
                    couplingDither[m][bin] = zero ? exponentScale : 0;
                }
            } else {
                for (var m = 0; m < 6; m++) {
                    coefficients[m][slot][bin] = ahtOut[m] * exponentScale;
                }
            }
        }
    }

    /**
     * Reads one gain-adaptive quantized mantissa (A/52 Annex E section 3.4.4.2).
     *
     * @param pointer the high-efficiency bit allocation pointer, from 8 to 19
     * @param gain the gain index: 0 for gain 1, 1 for gain 2, 2 for gain 4
     * @return the mantissa as a fraction
     */
    private float gaqMantissa(int pointer, int gain) {
        var bits = Ac3Tables.GAQ_BITS[pointer - 8];
        var pow = Ac3Tables.POW2_NEG;
        if (gain == 0) {
            return Aht.remap(pointer, 0, signed(bits) * pow[bits - 1]);
        }
        if (gain == 1) {
            var small = signed(bits - 1);
            if (small == -(1 << (bits - 2))) {
                return Aht.remap(pointer, 1, signed(bits - 1) * pow[bits - 2]);
            }
            return small * pow[bits - 1];
        }
        var small = signed(bits - 2);
        if (small == -(1 << (bits - 3))) {
            return Aht.remap(pointer, 2, signed(bits) * pow[bits - 1]);
        }
        return small * pow[bits - 1];
    }

    /**
     * Copies the side information of the current block into its state.
     *
     * @param state the block state
     */
    private void snapshot(BlockState state) {
        var channels = header.channels;
        state.coupling = couplingInUse;
        state.enhancedCoupling = couplingInUse && ecplInUse;
        System.arraycopy(endMantissa, 0, state.endMantissa, 0, SLOTS);
        for (var ch = 0; ch < SLOTS; ch++) {
            state.coupled[ch] = ch < channels && couplingInUse && coupled[ch];
            state.inSpx[ch] = ch < channels && spxInUse && inSpx[ch];
        }
        if (state.coupling && !state.enhancedCoupling) {
            state.couplingBegin = couplingBegin;
            state.couplingSubbands = couplingSubbands;
            System.arraycopy(couplingBandOf, 0, state.couplingBandOf, 0, 18);
            for (var ch = 0; ch < channels; ch++) {
                System.arraycopy(couplingCoordinates[ch], 0, state.couplingCoordinates[ch], 0, 18);
            }
            for (var band = 0; band < 18; band++) {
                state.phaseFlags[band] = header.acmod == 2 && phaseFlagsInUse && phaseFlags[band];
            }
        }
        if (state.enhancedCoupling) {
            state.ecplBegin = ecplBegin;
            state.ecplEnd = ecplEnd;
            System.arraycopy(ecplBandOf, 0, state.ecplBandOf, 0, 22);
            for (var ch = 0; ch < channels; ch++) {
                for (var band = 0; band < ecplBands; band++) {
                    var code = ecplAmplitudeCodes[ch][band];
                    state.ecplAmplitude[ch][band] = code == 31 ? 0.0f
                            : Ac3Tables.ECPL_AMP_MANTISSA[code] / 32.0f * Ac3Tables.POW2_NEG[Ac3Tables.ECPL_AMP_EXPONENT[code]];
                }
            }
        }
        state.rematrixBands = header.acmod == 2 ? rematrixBands : 0;
        System.arraycopy(rematrixFlags, 0, state.rematrix, 0, 4);
        state.spx = spxInUse;
        if (spxInUse) {
            state.spxBegin = spxBegin;
            state.spxEnd = spxEnd;
            state.spxStart = spxStart;
            state.spxBands = spxBands;
            System.arraycopy(spxBandSize, 0, state.spxBandSize, 0, 17);
            for (var ch = 0; ch < channels; ch++) {
                System.arraycopy(spxCoordinates[ch], 0, state.spxCoordinates[ch], 0, 17);
                System.arraycopy(spxNoise[ch], 0, state.spxNoise[ch], 0, 17);
                System.arraycopy(spxSignal[ch], 0, state.spxSignal[ch], 0, 17);
            }
        }
    }

    /**
     * Rebuilds the full coefficients of every block of a valid frame: decoupling (standard or
     * enhanced), rematrixing and spectral extension.
     */
    public void reconstruct() {
        if (!valid) {
            return;
        }
        var channels = header.channels;
        for (var b = 0; b < header.blocks; b++) {
            var state = blocks[b];
            var out = coefficients[b];
            if (state.enhancedCoupling) {
                decoupleEnhanced(state, couplingValues[b], couplingDither[b], channels, out);
            } else if (state.coupling) {
                decouple(state, couplingValues[b], couplingDither[b], channels, out);
            }
            finishBlock(state, channels, out);
        }
    }

    /**
     * Applies the steps after decoupling to one block: rematrixing and spectral extension.
     *
     * @param state the block state
     * @param channels the number of full-bandwidth channels
     * @param out the coefficients of the block
     */
    private void finishBlock(BlockState state, int channels, float[][] out) {
        if (state.rematrixBands > 0) {
            rematrix(state, out);
        }
        if (state.spx) {
            for (var ch = 0; ch < channels; ch++) {
                if (state.inSpx[ch]) {
                    extend(state, ch, out[ch], spxAttenuation[ch], spxAttenuationCode[ch]);
                }
            }
        }
    }

    /**
     * Rebuilds the coupled channels of a block from the coupling channel (A/52 section 7.4.3).
     *
     * @param state the block state
     * @param values the coupling channel of the block
     * @param dither the dither scale of each zero-bit coupling mantissa of the block
     * @param channels the number of full-bandwidth channels
     * @param out the coefficients of the block
     */
    private void decouple(BlockState state, float[] values, float[] dither, int channels, float[][] out) {
        var begin = state.couplingBegin;
        var end = begin + state.couplingSubbands;
        for (var ch = 0; ch < channels; ch++) {
            if (!state.coupled[ch]) {
                continue;
            }
            var target = out[ch];
            var withDither = state.dither[ch];
            var coordinates = state.couplingCoordinates[ch];
            for (var subband = begin; subband < end; subband++) {
                var band = state.couplingBandOf[subband];
                var coordinate = coordinates[band];
                if (ch == 1 && state.phaseFlags[band]) {
                    coordinate = -coordinate;
                }
                var from = 37 + 12 * subband;
                for (var bin = from; bin < from + 12; bin++) {
                    var value = values[bin];
                    if (withDither && dither[bin] != 0) {
                        value = noise() * DITHER_SCALE * dither[bin];
                    }
                    target[bin] = value * coordinate;
                }
            }
        }
    }

    /**
     * Rebuilds the coupled channels of a block that uses enhanced coupling (ETSI TS 102 366 clause
     * E.2.5.5): every coefficient of an enhanced coupling band is the coupling channel scaled by the
     * band's amplitude.
     *
     * @param state the block state
     * @param values the coupling channel of the block
     * @param dither the dither scale of each zero-bit coupling mantissa of the block
     * @param channels the number of full-bandwidth channels
     * @param out the coefficients of the block
     */
    private void decoupleEnhanced(BlockState state, float[] values, float[] dither, int channels, float[][] out) {
        var starts = Ac3Tables.ECPL_SUBBAND_START;
        for (var ch = 0; ch < channels; ch++) {
            if (!state.coupled[ch]) {
                continue;
            }
            var target = out[ch];
            var withDither = state.dither[ch];
            var amplitudes = state.ecplAmplitude[ch];
            for (var subband = state.ecplBegin; subband < state.ecplEnd; subband++) {
                var amplitude = amplitudes[state.ecplBandOf[subband]];
                for (var bin = starts[subband]; bin < starts[subband + 1]; bin++) {
                    var value = values[bin];
                    if (withDither && dither[bin] != 0) {
                        value = noise() * DITHER_SCALE * dither[bin];
                    }
                    target[bin] = value * amplitude;
                }
            }
        }
    }

    /**
     * Undoes the rematrixing of a 2/0 block (A/52 section 7.5.4).
     *
     * @param state the block state
     * @param out the coefficients of the block
     */
    private static void rematrix(BlockState state, float[][] out) {
        var left = out[0];
        var right = out[1];
        var limit = Math.min(state.endMantissa[0], state.endMantissa[1]);
        if (state.coupling && !state.enhancedCoupling) {
            limit = Math.min(limit, 37 + 12 * state.couplingBegin);
        }
        for (var band = 0; band < state.rematrixBands; band++) {
            if (!state.rematrix[band]) {
                continue;
            }
            var from = REMATRIX_EDGES[band];
            var to = Math.min(REMATRIX_EDGES[band + 1], limit);
            for (var bin = from; bin < to; bin++) {
                var l = left[bin];
                var r = right[bin];
                left[bin] = l + r;
                right[bin] = l - r;
            }
        }
    }

    /**
     * Synthesizes the spectral extension region of one channel (A/52 Annex E section 3.6.4).
     *
     * @param state the block state
     * @param ch the channel
     * @param tc the coefficients of the channel, extended in place
     * @param attenuated whether the channel uses spectral extension attenuation
     * @param attenuationCode the spectral extension attenuation code of the channel
     */
    private void extend(BlockState state, int ch, float[] tc, boolean attenuated, int attenuationCode) {
        var copyStart = 25 + 12 * state.spxStart;
        var copyEnd = 25 + 12 * state.spxBegin;
        var copy = copyStart;
        var insert = copyEnd;
        var bands = state.spxBands;
        var wrapped = 0;
        for (var band = 0; band < bands; band++) {
            var size = state.spxBandSize[band];
            if (copy + size > copyEnd) {
                copy = copyStart;
                wrapped |= 1 << band;
            }
            for (var bin = 0; bin < size; bin++) {
                if (copy == copyEnd) {
                    copy = copyStart;
                }
                tc[insert++] = tc[copy++];
            }
        }
        var rms = spxEnergy;
        var position = copyEnd;
        for (var band = 0; band < bands; band++) {
            var size = state.spxBandSize[band];
            var sum = 0.0f;
            for (var bin = 0; bin < size; bin++) {
                var value = tc[position++];
                sum += value * value;
            }
            rms[band] = (float) Math.sqrt(sum / size);
        }
        if (attenuated) {
            var code = attenuationCode;
            var border = copyEnd;
            notch(tc, border, code);
            for (var band = 0; band < bands; band++) {
                if (band > 0 && (wrapped & (1 << band)) != 0) {
                    notch(tc, border, code);
                }
                border += state.spxBandSize[band];
            }
        }
        position = copyEnd;
        for (var band = 0; band < bands; band++) {
            var size = state.spxBandSize[band];
            var noiseScale = rms[band] * state.spxNoise[ch][band];
            var signalScale = state.spxSignal[ch][band];
            var coordinate = state.spxCoordinates[ch][band];
            for (var bin = 0; bin < size; bin++) {
                var blended = tc[position] * signalScale + noise() * SPX_NOISE_SCALE * noiseScale;
                tc[position++] = blended * coordinate;
            }
        }
    }

    /**
     * Applies the five-bin spectral extension notch filter centered on a border bin (A/52 Annex E
     * section 3.6.4.2.3).
     *
     * @param tc the coefficients
     * @param border the first bin after the border
     * @param code the attenuation code
     */
    private static void notch(float[] tc, int border, int code) {
        for (var k = -2; k <= 2; k++) {
            var bin = border + k;
            if (bin >= 0 && bin < 256) {
                tc[bin] *= SpxAttenuation.FACTORS[code][2 - Math.abs(k)];
            }
        }
    }

    /**
     * Synthesizes the PCM of the frame parsed last, or conceals it when it was invalid.
     *
     * @param gains the gain of each block and program channel: dynamic range and any output gain
     * @param scale the gain applied to every channel on top of {@code gains}
     * @param pcm receives the samples of each coded channel in bit stream order, LFE last
     * @param offset the index of the first sample in each plane
     */
    public void synthesize(float[][] gains, float scale, float[][] pcm, int offset) {
        var blockCount = header.blocks;
        var channels = header.channels;
        var coded = codedChannels();
        var acmod = header.acmod;
        if (!valid) {
            conceal(coded, channels, pcm, offset);
            return;
        }
        for (var b = 0; b < blockCount; b++) {
            synthesizeBlock(blocks[b], coefficients[b], gains[b], scale, acmod, channels, coded, pcm, offset + 256 * b);
        }
        finishFrame(coefficients[blockCount - 1], gains[blockCount - 1], scale, acmod, channels, coded, pcm, offset,
                256 * blockCount, header.enhanced, transientLocation, transientLength);
    }

    /**
     * Applies the gains and the inverse transform to one block and overlaps it into the output.
     *
     * @param state the block state
     * @param coefficients the coefficients of the block, by channel slot
     * @param gains the gain of each program channel
     * @param scale the gain applied to every channel on top of {@code gains}
     * @param acmod the audio coding mode
     * @param channels the number of full-bandwidth channels
     * @param coded the number of coded channels, LFE included
     * @param pcm receives the samples of each coded channel
     * @param at the index of the block's first sample in each plane
     */
    private void synthesizeBlock(BlockState state, float[][] coefficients, float[] gains, float scale, int acmod,
                                 int channels, int coded, float[][] pcm, int at) {
        for (var ch = 0; ch < coded; ch++) {
            var slot = ch < channels ? ch : LFE;
            var program = acmod == 0 && ch == 1 ? 1 : 0;
            var gain = 2 * gains[program] * scale;
            transform.inverse(coefficients[slot], state.shortBlocks[slot], gain, windowed);
            kernel.overlap(windowed, delay[ch], pcm[ch], at, 256);
        }
    }

    /**
     * Ends the synthesis of a valid frame: keeps the coefficients of its last block for
     * concealment and, for E-AC-3, runs the transient pre-noise processing.
     *
     * @param last the coefficients of the last block, by channel slot
     * @param gains the gain of each program channel in the last block
     * @param scale the gain applied to every channel on top of {@code gains}
     * @param acmod the audio coding mode
     * @param channels the number of full-bandwidth channels
     * @param coded the number of coded channels, LFE included
     * @param pcm the samples of each coded channel
     * @param offset the index of the frame's first sample in each plane
     * @param samples the samples of the frame
     * @param enhanced whether the frame is E-AC-3
     * @param locations the transient location of each full-bandwidth channel, or {@code -1}
     * @param lengths the transient processing length of each full-bandwidth channel
     */
    private void finishFrame(float[][] last, float[] gains, float scale, int acmod, int channels, int coded,
                             float[][] pcm, int offset, int samples, boolean enhanced, int[] locations, int[] lengths) {
        for (var ch = 0; ch < coded; ch++) {
            var slot = ch < channels ? ch : LFE;
            var program = acmod == 0 && ch == 1 ? 1 : 0;
            kernel.scale(last[slot], 0, gains[program] * scale, lastCoefficients[ch], 0, 256);
        }
        if (enhanced) {
            if (preNoise == null || preNoise.channels() != channels) {
                preNoise = new TransientPreNoise(channels);
            }
            for (var ch = 0; ch < channels; ch++) {
                preNoise.process(ch, pcm[ch], offset, samples, locations[ch], lengths[ch]);
            }
        }
        started = true;
        concealmentGain = 1;
    }

    /**
     * Conceals a damaged frame by repeating the last good coefficients of each channel with a fade.
     *
     * @param coded the number of coded channels
     * @param channels the number of full-bandwidth channels
     * @param pcm receives the samples
     * @param offset the index of the first sample in each plane
     */
    private void conceal(int coded, int channels, float[][] pcm, int offset) {
        for (var b = 0; b < header.blocks; b++) {
            concealmentGain *= CONCEALMENT_FADE;
            for (var ch = 0; ch < coded; ch++) {
                var gain = started ? 2 * concealmentGain : 0;
                transform.inverse(lastCoefficients[ch], false, gain, windowed);
                kernel.overlap(windowed, delay[ch], pcm[ch], offset + 256 * b, 256);
            }
        }
        if (preNoise != null && preNoise.channels() == channels) {
            for (var ch = 0; ch < channels; ch++) {
                preNoise.process(ch, pcm[ch], offset, 256 * header.blocks, -1, 0);
            }
        }
    }

    /**
     * Returns the next value of the dither and noise generator.
     *
     * @return a value uniformly distributed in {@code [-1, 1)}
     */
    private float noise() {
        var x = seed;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        seed = x;
        return x * 0x1p-31f;
    }

    /**
     * Reads an unsigned field.
     *
     * @param bits the width
     * @return the value
     */
    private int read(int bits) {
        return reader.read(bits);
    }

    /**
     * Reads a two's complement field.
     *
     * @param bits the width, from 1 to 16
     * @return the sign-extended value
     */
    private int signed(int bits) {
        var value = reader.read(bits);
        return (value << (32 - bits)) >> (32 - bits);
    }

    /**
     * Signals an invalid frame without the cost of a stack trace.
     */
    private static final class InvalidFrame extends RuntimeException {
        /**
         * Creates the signal.
         */
        InvalidFrame() {
            super("invalid AC-3 frame", null, false, false);
        }
    }
}
