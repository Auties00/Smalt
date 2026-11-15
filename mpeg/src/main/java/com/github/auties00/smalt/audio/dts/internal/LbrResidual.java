package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * Decodes the residual coding of the LBR low bit rate coding of DTS Express (ETSI TS 102 114 clause 9.6.3), in the 8,
 * 16 or 32 subbands of the sample rate (8 to 12 kHz, 16 to 24 kHz, 32 to 48 kHz): the scale factor grids, the
 * quantized time samples and the LPC of the lowest subbands, the white noise that replaces missing samples, the joint
 * coding of channel pairs, and the subband half of the hybrid filter bank (clause 9.6.4) that turns the subband
 * samples into the MDCT spectrum the tonal components are added to.
 *
 * <p>The text of clause 9.6.3 is incomplete and in places wrong: the bit layout of the chunks, the time sample
 * codes, the LPC coefficient dequantization, the reconstruction of the high resolution scale factors, the noise
 * generator, level and shaping and the joint coding of channel pairs were measured from ffmpeg 7.1's decoder, run as
 * a black box on self-made streams.
 *
 * <p>The text leaves the white noise substitution generator open; this decoder draws it like ffmpeg's: one linear
 * congruential sequence for the whole stream, consumed pair by pair, channel by channel, subband by subband and sample
 * by sample by the samples the stream does not carry from subband 2 up, so frames with missing samples match ffmpeg's
 * output sample by sample.
 *
 * <p>The channels are coded in pairs, the last one alone when their number is odd. The second channel of a pair
 * carries its own samples only in the subbands where the stream codes them (always below {@code MinMonoSubband} and
 * from {@code MaxMonoSubband}, in the others while the chunk has room); elsewhere it copies the first channel, each
 * scaled by the stereo gains of the grid 1 chunk.
 *
 * <p>Instances are not thread-safe.
 */
final class LbrResidual {
    /**
     * The largest number of subbands, at 32 to 48 kHz.
     */
    static final int SUBBANDS = 32;

    /**
     * The number of time samples of a subband in a frame.
     */
    private static final int SAMPLES = 128;

    /**
     * The samples kept from the previous frame for the subband transform.
     */
    private static final int HISTORY = 4;

    /**
     * The number of subbands with LPC.
     */
    private static final int LPC_SUBBANDS = 3;

    /**
     * The LPC order.
     */
    private static final int ORDER = 8;

    /**
     * The first subband whose transform coefficients are mixed with the next subband's (clause 9.6.4), also the
     * entry of the second time samples chunk that carries the remaining grid 3 subbands.
     */
    private static final int ALIAS_START = 12;

    /**
     * The number of bits that must be left after an entry of the second channel below {@link #stereoStart} for its
     * samples to be used.
     */
    private static final int PRESENT_BITS = 20;

    /**
     * The number of bits that must be left in the second time samples chunk for its next entry to be read, as
     * measured: ffmpeg's decoder ignores up to 27 trailing bits.
     */
    private static final int ENTRY_BITS = 28;

    /**
     * The number of bits that must be left for the samples of an entry to be read, as measured; the 2-level
     * samples are then read 8 at a time.
     */
    private static final int SAMPLE_BITS = 20;

    /**
     * The alias cancellation coefficients of clause 9.6.4.
     */
    private static final float ALIAS_1 = 0.30865828381746f;
    private static final float ALIAS_2 = 0.03806023374436f;

    /**
     * The short window of the subband transform (Table 9-34).
     */
    private static final double[] SHORT_WINDOW = {0.02281089288256, 0.41799773023326, 0.90844807089885,
            0.99973979773034, 0.99973979773034, 0.90844807089885, 0.41799773023326, 0.02281089288256};

    /**
     * The subband transform: 8 windowed samples to 4 coefficients, half of the MDCT of Table 9-34's window.
     */
    private static final float[] TRANSFORM = new float[8 * 4];

    /**
     * The bound of the uniform noise of subbands 5 to 9 relative to the amplitude of their scale factor at a bit rate
     * of 32 kbit/s per channel and above, as measured; subbands 2 to 4 take 1/4, 2/4 and 3/4 of it.
     */
    private static final float NOISE_LEVEL = 0.785f;

    /**
     * The first subband whose noise follows the envelope of subbands 2 to 5.
     */
    private static final int NOISE_SHAPED = 10;

    /**
     * The number of grid 1 bands of the 32 subbands ({@code ScalefactorToGrid1[31] + 1}, Table 9-26).
     */
    private static final int GRID1_BANDS = 11;

    /**
     * The subband each grid 1 band is centred on; the scale factor of a subband between two centres interpolates
     * the two bands. Band 4 covers subbands 4 and 5.
     */
    private static final int[] GRID1_CENTER = {0, 1, 2, 3, 4, 6, 9, 13, 18, 24, 31};

    /**
     * The direction in which ffmpeg's decoder rounds the weight of the lower band of an interpolated subband, by
     * subband: {@code 1} rounds an exact product down once more when the lower band is the smaller, {@code -1} when
     * it is the larger. Measured on every pair of band values up to 22 apart.
     */
    private static final int[] GRID1_ROUNDING = {0, 0, 0, 0, 0, 0, 0, -1, 1, 0, 0, 0, 0, 0, -1, 1, -1, 1, 0, 1, -1,
            0, 1, -1, 0, 1, -1, -1, 1, 1, -1, 0};

    /**
     * The grid 2 band of each subband ({@code ScalefactorToGrid2}, Table 9-27).
     */
    private static final int[] GRID2_BAND = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2,
            2, 2, 2, 2, 2, 2, 2, 2};

    /**
     * The grid 2 code table ({@code Grid2Codes}): indexed by the next 5 bits, the code length in the top 3 bits and
     * the value in the low 5, 31 being an escape.
     */
    private static final int[] GRID2_CODES = {66, 99, 65, 64, 66, 132, 65, 64, 66, 99, 65, 64, 66, 165, 65, 64, 66, 99,
            65, 64, 66, 132, 65, 64, 66, 99, 65, 64, 66, 191, 65, 64};

    /**
     * The grid 3 code table ({@code Grid3Codes}), laid out as {@link #GRID2_CODES}.
     */
    private static final int[] GRID3_CODES = {113, 48, 79, 48, 142, 48, 79, 48, 113, 48, 79, 48, 178, 48, 79, 48, 113,
            48, 79, 48, 142, 48, 79, 48, 113, 48, 79, 48, 191, 48, 79, 48};

    /**
     * The grid 3 values after an escape, measured on ffmpeg's decoder: bits are read one at a time and the
     * {@code i}th bit ends the code when it equals {@code i & 1}, giving the {@code i}th value
     * ({@code 0 -> 13, 11 -> 19, 100 -> 12, 1011 -> 20, 10100 -> 11, 101011 -> 21, 1010100 -> 10}).
     */
    private static final int[] GRID3_ESCAPES = {13, 19, 12, 20, 11, 21, 10};

    /**
     * The exponent, in quarters of an octave, of the stereo gain of each value of the partial stereo grid (a grid
     * code plus its channel's minimum), as measured: even values amplify, odd values attenuate.
     */
    private static final int[] STEREO_EXPONENTS = {0, 0, 1, -1, 3, -3, 5, -5, 7, -7, 9, -9, 11, -11, 13, -13, 15,
            -15, 17, -17, 19, -19, 23, -23, 27, -27, 31, -31, 35, -35, 39, -39};

    /**
     * The linear stereo gains by value of the partial stereo grid.
     */
    private static final float[] STEREO_GAINS = new float[STEREO_EXPONENTS.length];

    /**
     * The number of subbands a gain of the partial stereo grid covers, from {@link #monoStart} (the spec loops
     * from subband 2; ffmpeg's decoder starts the groups at the first subband a pair may copy, as measured).
     */
    private static final int STEREO_GROUP = 4;

    /**
     * The largest number of partial stereo grid groups, at 32 subbands.
     */
    private static final int STEREO_GROUPS = 8;

    /**
     * The grid 1 band of each subband ({@code ScalefactorToGrid1}, Table 9-26).
     */
    private static final int[] GRID1_OF_SUBBAND = {0, 1, 2, 3, 4, 4, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7, 8, 8, 8, 8, 8, 8,
            9, 9, 9, 9, 10, 10, 10, 10, 10, 10};

    /**
     * The number of samples a gain of the partial stereo grid lasts.
     */
    private static final int STEREO_STEP = 32;

    /**
     * The subband whose formula level each of the lowest subbands takes, by subband of maximum energy (the low 3 bits
     * of the quantizer profile), as measured on ffmpeg's decoder; Table 9-24 only moves the level of subband 0 to the
     * subband of maximum energy, which matches for 1 and 3 alone.
     */
    private static final int[][] PEAK_ORDER = {
            {0},
            {1, 0},
            {3, 1, 0, 2},
            {1, 2, 3, 0},
            {1, 2, 5, 3, 0, 4},
            {1, 2, 2, 5, 3, 0, 4, 6},
            {1, 2, 2, 6, 5, 3, 0, 4},
            {1, 2, 2, 6, 5, 4, 0, 3}
    };

    /**
     * The dequantized values of the 2-level quantizer (Table 9-48).
     */
    private static final float[] LEVELS_2 = {-0.47f, 0.47f};

    /**
     * The dequantized values of the 3-level quantizer (Table 9-47).
     */
    private static final float[] LEVELS_3 = {-0.645f, 0f, 0.645f};

    /**
     * The dequantized values of the 5-level quantizer (Table 9-49).
     */
    private static final float[] LEVELS_5 = {-0.875f, -0.375f, 0f, 0.375f, 0.875f};

    /**
     * The dequantized values of the 8-level quantizer (Table 9-46).
     */
    private static final float[] LEVELS_8 = {-1f, -0.625f, -0.291666667f, 0f, 0.25f, 0.5f, 0.75f, 1f};

    /**
     * The 8-level quantizer codes, indexed by the next 6 bits: the length in the top 4 bits, the level in the low 4.
     */
    private static final int[] CODES_8 = new int[64];

    /**
     * The reflection coefficients of the LPC by 4-bit index: {@code sin(pi (i - 8) / 17)} below 8 and
     * {@code sin(pi (i - 8) / 15)} from 8.
     */
    private static final float[] REFLECTION = new float[16];

    /**
     * The linear amplitude of a residual scale factor, times {@code 2^25 sqrt 2}; scale factors below 0 or above 55
     * are silent, as measured.
     */
    private static final float[] AMPLITUDE = new float[56];

    /**
     * The powers of 3 that unpack 5 samples from a byte.
     */
    private static final int[] POWERS_3 = {1, 3, 9, 27, 81};

    /**
     * The powers of 5 that unpack 3 samples from 7 bits.
     */
    private static final int[] POWERS_5 = {1, 5, 25};

    static {
        for (var n = 0; n < 8; n++) {
            for (var k = 0; k < 4; k++) {
                TRANSFORM[n * 4 + k] = (float) (0.5 * SHORT_WINDOW[n]
                        * StrictMath.cos(Math.PI / 4 * (n + 2.5) * (k + 0.5)));
            }
        }
        for (var i = 0; i < 16; i++) {
            REFLECTION[i] = (float) StrictMath.sin(Math.PI * (i - 8) / (i < 8 ? 17 : 15));
        }
        // the codes are least significant bit first: 10 11 010 011 000 0010 00110 001110 001111
        int[][] codes = {{0b01, 2, 2}, {0b11, 2, 3}, {0b010, 3, 4}, {0b110, 3, 0}, {0b000, 3, 1}, {0b0100, 4, 5},
                {0b01100, 5, 6}, {0b011100, 6, 7}, {0b111100, 6, 4}};
        for (var code : codes) {
            for (var rest = 0; rest < 64 >> code[1]; rest++) {
                CODES_8[code[0] | rest << code[1]] = code[1] << 4 | code[2];
            }
        }
        var scale = (float) (StrictMath.pow(2, -25) / StrictMath.sqrt(2));
        AMPLITUDE[0] = 0.125f * scale;
        AMPLITUDE[1] = 0.374462f * scale;
        for (var i = 2; i < AMPLITUDE.length; i++) {
            AMPLITUDE[i] = (float) (0.296875 * StrictMath.pow(2, i * 0.5)) * scale;
        }
        for (var i = 0; i < STEREO_GAINS.length; i++) {
            STEREO_GAINS[i] = (float) StrictMath.pow(2, STEREO_EXPONENTS[i] / 4.0);
        }
    }

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sample rate.
     */
    private final int sampleRate;

    /**
     * The number of subbands of the sample rate: 8, 16 or 32.
     */
    private final int subbands;

    /**
     * The number of lines of a synthesis block, {@code 4 * subbands} or more under a band limit.
     */
    private final int lines;

    /**
     * The gain of the spectrum for the lines of a synthesis block, {@code sqrt(lines / (4 * subbands))}: the
     * synthesis window of a block of more lines has the lower gain.
     */
    private final float gain;

    /**
     * The number of grid 1 bands of the subbands.
     */
    private final int grid1Bands;

    /**
     * The number of grid 2 bands of the subbands ({@code ScalefactorToGrid2[nTotalSubbands - 1] + 1}).
     */
    private final int grid2Bands;

    /**
     * The number of partial stereo grid groups: one per 4 subbands from {@link #monoStart}.
     */
    private final int stereoGroups;

    /**
     * The width of the subband field of the entries of the second time samples chunk.
     */
    private final int targetBits;

    /**
     * The first subband a pair may code for its first channel alone ({@code MinMonoSubband}): below it both channels
     * carry their own grid 1 scale factors (in the grid 1 chunk, band by band after the first channel's) and time
     * samples (in the first time samples chunk), and the second channel never copies the first. It is 2, or 4 at 8,
     * 16 and 32 kHz.
     */
    private final int monoStart;

    /**
     * The first subband whose time samples are coded for both channels of a pair in the same entry
     * ({@code MaxMonoSubband}).
     */
    private final int stereoStart;

    /**
     * The first subband whose grid 3 holds only its average ({@code G3AvgOnlyStartSB}).
     */
    private final int averageOnly;

    /**
     * The output subband samples by channel and subband: {@link #HISTORY} samples of the previous frame, then the
     * frame.
     */
    private final float[][][] samples;

    /**
     * The LPC output history by channel and LPC subband, newest first.
     */
    private final float[][][] lpcHistory;

    /**
     * The LPC direct form coefficients by channel, LPC subband and segment: the segment that ends in the first
     * 16 samples of the frame, then the frame's own (two for subbands 0 and 1, one for subband 2).
     */
    private final float[][][][] lpc;

    /**
     * The last stereo gain point of each channel and partial stereo grid group, where the next frame's gains start.
     */
    private final float[][] stereoState;

    /**
     * The scaled subband samples of the two channels of the pair being decoded, before the joint coding.
     */
    private final float[][][] scaled = new float[2][SUBBANDS][SAMPLES];

    /**
     * The grid 1 scale factors of the channels of the pair being decoded, by band and time slot.
     */
    private final int[][][] grid1 = new int[2][GRID1_BANDS][8];

    /**
     * The grid 3 averages by channel and subband from 4.
     */
    private final int[][] averages = new int[2][SUBBANDS];

    /**
     * The grid 3 scale factors by channel, subband and time slot.
     */
    private final int[][][] grid3 = new int[2][SUBBANDS][8];

    /**
     * The subbands whose grid 3 the time samples chunks have read for the channel being read.
     */
    private final boolean[] grid3Read = new boolean[SUBBANDS];

    /**
     * The grid 2 scale factors by channel, band and high resolution slot.
     */
    private final int[][][] grid2 = new int[2][3][64];

    /**
     * The partial stereo grid of the pair being decoded: the grid value of each channel, group and subsample.
     */
    private final int[][][] stereoGrid = new int[2][STEREO_GROUPS][4];

    /**
     * Whether the grid 1 chunk of the pair being decoded carries a partial stereo grid.
     */
    private boolean stereo;

    /**
     * The quantizer level of each subband, from 1 (2 levels) to 5 (16 levels).
     */
    private final int[] levels = new int[SUBBANDS];

    /**
     * The levels of the lowest subbands given by the formula, before their reordering.
     */
    private final int[] formulaLevels = new int[8];

    /**
     * The number of time samples read for each channel and subband of the pair being decoded.
     */
    private final int[][] coded = new int[2][SUBBANDS];

    /**
     * The mid/side flags of each subband, one bit per time slot of 16 samples.
     */
    private final int[] midSide = new int[SUBBANDS];

    /**
     * The channel swap flags of each subband, one bit per time slot of 16 samples.
     */
    private final int[] swap = new int[SUBBANDS];

    /**
     * Whether the second channel of the pair codes its own samples in each subband.
     */
    private final boolean[] present = new boolean[SUBBANDS];

    /**
     * The subband each entry of the time samples chunks names.
     */
    private final int[] targets = new int[SUBBANDS];

    /**
     * The LPC reflection indices read for the channels of the pair: subbands 0 and 1 two sets each, subband 2 one.
     */
    private final int[][] lpcIndices = new int[2][5 * ORDER];

    /**
     * The stereo gains of one channel of a frame, by sample, for the group being combined.
     */
    private final float[] gains = new float[SAMPLES];

    /**
     * The sum of the magnitudes of the unscaled samples of subbands 2 to 5 of the channel being scaled, by sample,
     * which shapes the noise of the subbands from {@link #NOISE_SHAPED}.
     */
    private final float[] envelope = new float[SAMPLES];

    /**
     * The bound of the noise of subbands 5 to 9 for the bit rate of the stream.
     */
    private final float noiseLevel;

    /**
     * The block of transform coefficients being synthesized.
     */
    private final float[] block = new float[4 * SUBBANDS];

    /**
     * The chunks of the frame by kind (grid 1, high resolution grid, time samples 1 and 2) and channel pair:
     * offsets and sizes, a negative size when absent.
     */
    private final int[] chunkOffsets = new int[4 * 16];
    private final int[] chunkSizes = new int[4 * 16];

    /**
     * The reader of the chunk being decoded.
     */
    private final LbrDecoder.Bits bits = new LbrDecoder.Bits();

    /**
     * The noise generator state.
     */
    private int seed;

    /**
     * Creates the residual decoder of a stream.
     *
     * @param channels the number of full band channels decoded, the first of the stream
     * @param streamChannels the number of full band channels of the stream, which with the bit rate selects the
     *        residual profile
     * @param sampleRate the sample rate the residual is coded for, the header's divided by its band limit
     * @param bitRate the original bit rate of the header
     * @param scaledBitRate the scaled bit rate of the header, which per channel sets the noise level
     * @param lines the number of lines of a synthesis block, at least {@code 4 * subbands}
     */
    LbrResidual(int channels, int streamChannels, int sampleRate, int bitRate, int scaledBitRate, int lines) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        noiseLevel = noiseLevel((double) scaledBitRate / streamChannels);
        var base = 500 + 250 * streamChannels;
        var profile = bitRate >= 176 * base ? 2 : bitRate >= 100 * base ? 1 : 0;
        var averageFrequency = switch (profile) {
            case 0 -> 16;
            case 1 -> 18;
            default -> 24;
        };
        subbands = sampleRate < 16000 ? 8 : sampleRate < 32000 ? 16 : 32;
        this.lines = lines;
        gain = (float) StrictMath.sqrt((double) lines / (4 * subbands));
        grid1Bands = GRID1_OF_SUBBAND[subbands - 1] + 1;
        grid2Bands = GRID2_BAND[subbands - 1] + 1;
        targetBits = Integer.numberOfTrailingZeros(subbands);
        var half = sampleRate / 2;
        averageOnly = Math.min(subbands, subbands * 1000 * averageFrequency / half);
        monoStart = Math.min(subbands, subbands * 1000 * 2 / half);
        stereoGroups = (subbands - monoStart + STEREO_GROUP - 1) / STEREO_GROUP;
        stereoStart = Math.min(subbands, subbands * 1000 * 14 / half);
        samples = new float[channels][SUBBANDS][HISTORY + SAMPLES];
        lpcHistory = new float[channels][LPC_SUBBANDS][ORDER];
        lpc = new float[channels][LPC_SUBBANDS][3][ORDER];
        stereoState = new float[channels][STEREO_GROUPS];
        reset();
    }

    /**
     * Clears the state, as at the start of a stream.
     */
    void reset() {
        for (var ch = 0; ch < channels; ch++) {
            for (var sb = 0; sb < SUBBANDS; sb++) {
                Arrays.fill(samples[ch][sb], 0);
            }
            for (var sb = 0; sb < LPC_SUBBANDS; sb++) {
                Arrays.fill(lpcHistory[ch][sb], 0);
                for (var segment = 0; segment < 3; segment++) {
                    Arrays.fill(lpc[ch][sb][segment], 0);
                }
            }
            Arrays.fill(stereoState[ch], 1);
        }
        seed = 1;
        clearChunks();
    }

    /**
     * Forgets the chunks of the previous frame.
     */
    void clearChunks() {
        Arrays.fill(chunkSizes, -1);
    }

    /**
     * {@return whether an id is a residual chunk}
     *
     * @param kind the chunk id without its length bit
     */
    static boolean isResidual(int kind) {
        return kind >= 0x30 && kind <= 0x6F;
    }

    /**
     * Records a residual chunk of the frame.
     *
     * @param kind the chunk id without its length bit, from {@code 0x30} to {@code 0x6F}
     * @param offset the index of the payload
     * @param size the payload size
     */
    void addChunk(int kind, int offset, int size) {
        var pair = kind & 0x0F;
        if (2 * pair >= channels) {
            return;
        }
        var index = ((kind >> 4) - 3) * 16 + pair;
        chunkOffsets[index] = offset;
        chunkSizes[index] = size;
    }

    /**
     * Decodes the residual of every channel of the frame and adds its spectrum to the synthesis.
     *
     * @param data the bytes of the frame
     * @param spectrum the MDCT spectrum ring of each channel, {@code ring} blocks of {@link #lines} lines
     * @param ring the number of blocks of the ring
     * @param firstBlock the block of the ring that ends the previous frame
     */
    void decode(byte[] data, float[][] spectrum, int ring, long firstBlock) {
        for (var ch = 0; ch < channels; ch += 2) {
            var pair = ch + 1 < channels;
            decodePair(data, ch, pair);
            synthesize(ch, spectrum[ch], ring, firstBlock);
            if (pair) {
                synthesize(ch + 1, spectrum[ch + 1], ring, firstBlock);
            }
        }
    }

    /**
     * Reads the chunks of a channel pair, or of the last channel coded alone, and computes its output subband
     * samples.
     *
     * @param ch the first channel
     * @param pair whether the second channel follows
     */
    private void decodePair(byte[] data, int ch, boolean pair) {
        var index = ch >> 1;
        var count = pair ? 2 : 1;
        for (var c = 0; c < count; c++) {
            Arrays.fill(coded[c], 0);
            Arrays.fill(lpcIndices[c], 8);
            for (var band = 0; band < GRID1_BANDS; band++) {
                Arrays.fill(grid1[c][band], 0);
            }
            Arrays.fill(averages[c], 16);
            for (var sb = 0; sb < SUBBANDS; sb++) {
                Arrays.fill(grid3[c][sb], 16);
            }
            for (var band = 0; band < 3; band++) {
                Arrays.fill(grid2[c][band], 0);
            }
        }
        Arrays.fill(midSide, 0);
        Arrays.fill(swap, 0);
        Arrays.fill(present, false);
        if (pair) {
            Arrays.fill(present, 2, monoStart, true);
        }
        stereo = false;
        var size = chunkSizes[index];
        if (size >= 0) {
            bits.reset(data, chunkOffsets[index], size);
            readGrid1(pair);
        }
        var hasHighResolution = chunkSizes[16 + index] >= 0;
        if (hasHighResolution) {
            bits.reset(data, chunkOffsets[16 + index], chunkSizes[16 + index]);
            readHighResolution(pair);
            size = chunkSizes[32 + index];
            if (size >= 0) {
                bits.reset(data, chunkOffsets[32 + index], size);
                readTimeSamples1(pair);
                size = chunkSizes[48 + index];
                if (size >= 0) {
                    bits.reset(data, chunkOffsets[48 + index], size);
                    readTimeSamples2(pair);
                }
            }
        }
        for (var c = 0; c < count; c++) {
            scaleAndFill(c);
        }
        if (pair) {
            combine(ch);
        } else {
            for (var sb = 0; sb < subbands; sb++) {
                System.arraycopy(scaled[0][sb], 0, samples[ch][sb], HISTORY, SAMPLES);
            }
        }
        for (var c = 0; c < count; c++) {
            if (hasHighResolution) {
                filter(ch + c, c, pair);
            } else {
                // without time samples ffmpeg's decoder leaves the LPC subbands silent for the whole frame, the end
                // of the previous frame's last LPC segment included
                for (var sb = 0; sb < LPC_SUBBANDS; sb++) {
                    Arrays.fill(lpcHistory[ch + c][sb], 0);
                    Arrays.fill(lpc[ch + c][sb][0], 0);
                    if (sb < 2) {
                        Arrays.fill(samples[ch + c][sb], HISTORY, HISTORY + SAMPLES, 0);
                    }
                }
                if (pair && c == 0 && !present[2]) {
                    // the first output of a copied subband 2 takes its stereo gains after the LPC, which is skipped
                    stereoGains(ch, 0, 0);
                    var out = samples[ch][2];
                    for (var n = 0; n < SAMPLES; n++) {
                        out[HISTORY + n] *= gains[n];
                    }
                }
            }
        }
        if (pair) {
            for (var c = 0; c < 2; c++) {
                for (var group = 0; group < stereoGroups; group++) {
                    stereoState[ch + c][group] = stereo ? stereoGain(c, group, 3) : 1;
                }
            }
        }
    }

    /**
     * Reads the grid 1 chunk (Table 9-23): the scale factors of bands 2 and up and the grid 3 averages of the first
     * channel, then for a pair the partial stereo grid.
     */
    private void readGrid1(boolean pair) {
        for (var band = 2; band < grid1Bands; band++) {
            readScaleFactors(grid1[0][band]);
            if (pair && band < monoStart) {
                readScaleFactors(grid1[1][band]);
            }
        }
        if (!pair) {
            for (var sb = 4; sb < subbands + 1 && bits.left() > 0; sb++) {
                var value = bits.variable(LbrTables.AVG_G3);
                if (sb < subbands) {
                    averages[0][sb] = value;
                }
            }
            return;
        }
        readAverages(averages[0]);
        if (bits.left() < 8) {
            return;
        }
        stereo = true;
        var minimum0 = bits.read(4);
        var minimum1 = bits.read(4);
        for (var group = 0; group < stereoGroups; group++) {
            for (var c = 0; c < 2; c++) {
                var minimum = c == 0 ? minimum0 : minimum1;
                for (var k = 0; k < 4; k++) {
                    stereoGrid[c][group][k] = bits.variable(LbrTables.ST_GRID) + minimum;
                }
            }
        }
    }

    /**
     * Reads the grid 3 averages of the subbands from 4 of a channel of a pair.
     */
    private void readAverages(int[] out) {
        for (var sb = 4; sb < subbands; sb++) {
            out[sb] = bits.variable(LbrTables.AVG_G3);
        }
    }

    /**
     * Reads the 8 time slots of a grid 1 band (Table 9-29): points coded as distances and differences, with the
     * slots between two points interpolated. Reading stops, leaving the rest of the band at 0, when fewer than 20
     * bits are left before a difference.
     */
    private void readScaleFactors(int[] out) {
        if (bits.left() < 20) {
            return;
        }
        var previous = bits.variable(LbrTables.FST_RSD_AMP);
        out[0] = previous;
        var slot = 0;
        while (slot < 7) {
            var distance = bits.variable(LbrTables.RSD_APPX) + 1;
            if (bits.left() < 20 || distance <= 0 || slot + distance > 7) {
                return;
            }
            var code = bits.variable(LbrTables.RSD_AMP);
            var next = (code & 1) != 0 ? previous + ((code + 1) >> 1) : previous - (code >> 1);
            for (var i = 1; i < distance; i++) {
                out[slot + i] = previous + (next - previous) * i / distance;
            }
            out[slot + distance] = next;
            slot += distance;
            previous = next;
        }
    }

    /**
     * Reads the high resolution grid chunk (Table 9-24): the quantizer profile, the LPC of subbands 0 and 1 of each
     * channel, the mid/side flags and samples of subbands 0 and 1, then their grid 1 scale factors, channel by
     * channel within each subband.
     */
    private void readHighResolution(boolean pair) {
        computeLevels(bits.read(8));
        var count = pair ? 2 : 1;
        for (var sb = 0; sb < 2; sb++) {
            for (var c = 0; c < count; c++) {
                for (var i = 0; i < 2 * ORDER; i++) {
                    lpcIndices[c][sb * 2 * ORDER + i] = bits.read(4);
                }
            }
        }
        for (var sb = 0; sb < 2; sb++) {
            if (pair) {
                midSide[sb] = bits.read(8);
            }
            for (var c = 0; c < count; c++) {
                readSamples(sb, sb, c);
            }
        }
        for (var sb = 0; sb < 2; sb++) {
            for (var c = 0; c < count; c++) {
                readScaleFactors(grid1[c][sb]);
            }
        }
    }

    /**
     * Reads the first time samples chunk (Table 9-22): the LPC of subband 2 of each channel, the samples of
     * subbands 2 and 3 of the first channel, its first grid 2 band, then its subbands 4 and 5 with their grid 3;
     * for a pair each subband also carries its mid/side flags.
     */
    private void readTimeSamples1(boolean pair) {
        var count = pair ? 2 : 1;
        for (var c = 0; c < count; c++) {
            for (var i = 0; i < ORDER; i++) {
                lpcIndices[c][4 * ORDER + i] = bits.read(4);
            }
        }
        Arrays.fill(grid3Read, false);
        for (var sb = 2; sb < 4; sb++) {
            readMidSide(sb, pair);
            readSamples(sb, sb, 0);
            if (pair && sb < monoStart) {
                readSamples(sb, sb, 1);
            }
        }
        readGrid2(grid2[0][0]);
        for (var sb = 4; sb < 6; sb++) {
            readGrid3(grid3[0][sb]);
            grid3Read[sb] = true;
            readMidSide(sb, pair);
            readSamples(sb, sb, 0);
        }
    }

    /**
     * Reads the mid/side flags of a subband of a pair.
     */
    private void readMidSide(int sb, boolean pair) {
        if (pair) {
            midSide[sb] = bits.read(8);
        }
    }

    /**
     * Reads the second time samples chunk (Table 9-25): grid 2 bands 1 and 2 of the first channel, then for every
     * entry from 6 the subband its samples belong to, the grid 3 of subbands below 12 (all the remaining grid 3
     * subbands at entry 12) and the samples. For a pair, the second channel follows: its grid 1 scale factors from
     * band 2, averages and grid 2, then its entries from 2 with their swap flags, and from entry 18 the entries
     * that code both channels.
     */
    private void readTimeSamples2(boolean pair) {
        for (var band = 1; band < grid2Bands; band++) {
            readGrid2(grid2[0][band]);
        }
        var end = pair ? stereoStart : subbands;
        for (var index = 6; index < end; index++) {
            if (bits.left() < ENTRY_BITS) {
                return;
            }
            var target = readTarget();
            targets[index] = target;
            readEntryGrid3(index, target, 0);
            readMidSide(target, pair);
            readSamples(index, target, 0);
        }
        if (!pair) {
            return;
        }
        for (var band = monoStart; band < grid1Bands; band++) {
            readScaleFactors(grid1[1][band]);
        }
        readAverages(averages[1]);
        for (var band = 0; band < grid2Bands; band++) {
            readGrid2(grid2[1][band]);
        }
        Arrays.fill(grid3Read, false);
        for (var index = monoStart; index < stereoStart; index++) {
            if (bits.left() < ENTRY_BITS) {
                return;
            }
            var target = index < 6 ? index : targets[index];
            if (index >= 4) {
                readEntryGrid3(index, target, 1);
            }
            swap[target] = bits.read(8);
            readSamples(index, target >= 2 ? target : index, 1);
            present[target] = bits.left() >= PRESENT_BITS;
            if (!present[target]) {
                // the samples of an entry without room after it are not used: the second channel draws noise there,
                // which the copy of the first channel hides
                coded[1][target] = 0;
            }
        }
        for (var index = stereoStart; index < subbands; index++) {
            if (bits.left() < ENTRY_BITS) {
                return;
            }
            var target = readTarget();
            midSide[target] = bits.read(8);
            swap[target] = bits.read(8);
            readSamples(index, target, 0);
            readSamples(index, target, 1);
            present[target] = true;
        }
    }

    /**
     * {@return the subband an entry of the second time samples chunk names, in as many bits as the subbands need}
     * Encoders name subbands from 6; a smaller value names subband 6, as in ffmpeg's decoder, so its samples replace
     * those of subband 6 and the entry's own subband is left to the noise unless another entry names it.
     */
    private int readTarget() {
        return Math.max(6, bits.read(targetBits));
    }

    /**
     * Reads the grid 3 an entry of a time samples chunk carries: below entry 12 the named subband's, unless an
     * earlier entry read it, at entry 12 every subband from 4 whose grid 3 is not read yet.
     */
    private void readEntryGrid3(int index, int target, int c) {
        if (index < ALIAS_START) {
            if (!grid3Read[target]) {
                readGrid3(grid3[c][target]);
                grid3Read[target] = true;
            }
        } else if (index == ALIAS_START) {
            for (var sb = 4; sb < averageOnly; sb++) {
                if (!grid3Read[sb]) {
                    readGrid3(grid3[c][sb]);
                    grid3Read[sb] = true;
                }
            }
        }
    }

    /**
     * Computes the quantizer level of each subband from a quantizer profile (Table 9-24, in integer arithmetic) and
     * reorders the levels of the lowest subbands for the subband of maximum energy ({@link #PEAK_ORDER}).
     */
    private void computeLevels(int profile) {
        var overall = (profile & 0x38) >> 3;
        var steepness = (profile & 0xC0) >> 6;
        var peak = profile & 0x07;
        for (var sb = 0; sb < subbands; sb++) {
            var frequency = sb * sampleRate / subbands;
            var snr = 18000 / (12 * frequency / 1000 + 100 + 40 * steepness) + 20 * overall;
            levels[sb] = snr <= 95 ? 1 : snr <= 140 ? 2 : snr <= 180 ? 3 : snr <= 230 ? 4 : 5;
        }
        var order = PEAK_ORDER[peak];
        System.arraycopy(levels, 0, formulaLevels, 0, order.length);
        for (var sb = 0; sb < order.length; sb++) {
            levels[sb] = formulaLevels[order[sb]];
        }
    }

    /**
     * Reads a grid 2 band: per group of 8 high resolution slots, a flag then the 8 values.
     */
    private void readGrid2(int[] out) {
        for (var group = 0; group < 8; group++) {
            if (bits.read(1) == 0) {
                continue;
            }
            for (var j = 0; j < 8; j++) {
                out[group * 8 + j] = readGrid2Value();
            }
        }
    }

    /**
     * Reads one grid 2 value.
     */
    private int readGrid2Value() {
        var code = GRID2_CODES[bits.peek(5)];
        bits.skip(code >> 5);
        var value = code & 31;
        if (value < 31) {
            return value;
        }
        for (var v = 6; v <= 9; v++) {
            if (bits.read(1) != 0) {
                return v;
            }
        }
        // measured, bits in reading order: 00 10, 11 11, 010 12, 011 16, 10000 18, 10001 13, 1001 17, 1010 14,
        // 10110 15, then 10111 and a value of a 3-bit length plus one bits
        if (bits.read(1) == 0) {
            return bits.read(1) == 0 ? 10 : bits.read(1) == 0 ? 12 : 16;
        }
        if (bits.read(1) != 0) {
            return 11;
        }
        if (bits.read(1) == 0) {
            if (bits.read(1) == 0) {
                return bits.read(1) == 0 ? 18 : 13;
            }
            return 17;
        }
        if (bits.read(1) == 0) {
            return 14;
        }
        if (bits.read(1) == 0) {
            return 15;
        }
        return bits.read(bits.read(3) + 1);
    }

    /**
     * Reads the 8 time slots of a grid 3 subband.
     */
    private void readGrid3(int[] out) {
        for (var slot = 0; slot < 8; slot++) {
            out[slot] = readGrid3Value();
        }
    }

    /**
     * Reads one grid 3 value.
     */
    private int readGrid3Value() {
        var code = GRID3_CODES[bits.peek(5)];
        bits.skip(code >> 5);
        var value = code & 31;
        if (value < 31) {
            return value;
        }
        for (var i = 0; i < GRID3_ESCAPES.length; i++) {
            if (bits.read(1) == (i & 1)) {
                return GRID3_ESCAPES[i];
            }
        }
        // measured: a 3-bit length plus one, then the value, kept whatever it is (Table 9-30 reads 4 more bits and
        // maps values above 56 to 16)
        return bits.read(bits.read(3) + 1);
    }

    /**
     * Reads the time samples of one subband entry of a channel: the coding method bit, then the samples in the code
     * of the entry's quantizer level. Samples the chunk ends before are left to the noise, all of them when fewer
     * than {@link #SAMPLE_BITS} bits are left.
     *
     * @param index the entry, whose level applies
     * @param target the subband the samples belong to
     * @param c the channel of the pair
     */
    private void readSamples(int index, int target, int c) {
        if (bits.left() < SAMPLE_BITS) {
            coded[c][target] = 0;
            return;
        }
        var method = bits.read(1);
        var level = levels[index];
        var out = scaled[c][target];
        var count = 0;
        switch (level) {
            case 1 -> {
                while (count < SAMPLES && bits.left() >= 8) {
                    for (var j = 0; j < 8; j++) {
                        out[count++] = LEVELS_2[bits.read(1)];
                    }
                }
            }
            case 2 -> {
                if (method == 0) {
                    while (count < SAMPLES && bits.left() >= 8) {
                        var packed = bits.read(8);
                        for (var d = 0; d < 5 && count < SAMPLES; d++) {
                            var digit = packed / POWERS_3[4 - d] % 3;
                            out[count++] = LEVELS_3[digit];
                        }
                    }
                } else {
                    while (count < SAMPLES && bits.left() >= 2) {
                        out[count++] = bits.read(1) == 0 ? 0f : LEVELS_3[bits.read(1) * 2];
                    }
                }
            }
            case 3 -> {
                while (count < SAMPLES && bits.left() >= 7) {
                    var packed = bits.read(7);
                    for (var d = 0; d < 3 && count < SAMPLES; d++) {
                        var digit = packed / POWERS_5[2 - d] % 5;
                        out[count++] = LEVELS_5[Math.min(digit, 4)];
                    }
                }
            }
            case 4 -> {
                while (count < SAMPLES && bits.left() >= 6) {
                    var code = CODES_8[bits.peek(6)];
                    bits.skip(code >> 4);
                    out[count++] = LEVELS_8[code & 15];
                }
            }
            default -> {
                while (count < SAMPLES && bits.left() >= 4) {
                    out[count++] = -1.3125f + 0.175f * bits.read(4);
                }
            }
        }
        coded[c][target] = count;
    }

    /**
     * Scales the read samples of a channel of the pair by their scale factors and fills the samples the stream does
     * not carry with noise.
     */
    private void scaleAndFill(int c) {
        var channel = scaled[c];
        Arrays.fill(envelope, 0);
        for (var sb = 0; sb < subbands; sb++) {
            var out = channel[sb];
            var count = coded[c][sb];
            var level = sb < 5 ? noiseLevel * (sb - 1) * 0.25f : noiseLevel;
            for (var n = 0; n < SAMPLES; n++) {
                var factor = scaleFactor(c, sb, n);
                var amplitude = factor < 0 || factor >= AMPLITUDE.length ? 0f : AMPLITUDE[factor];
                float value;
                if (n < count) {
                    value = out[n];
                } else if (sb < 2) {
                    value = 0;
                } else if (sb < NOISE_SHAPED) {
                    value = level * noise();
                } else {
                    value = noiseLevel * (0.5f + 0.25f * envelope[n]) * noise();
                }
                if (sb >= 2 && sb < 6) {
                    envelope[n] += Math.abs(value);
                }
                out[n] = value * amplitude;
            }
        }
    }

    /**
     * {@return the bound of the noise of subbands 5 to 9 relative to the amplitude of their scale factor} It falls
     * linearly from {@link #NOISE_LEVEL} at 32 kbit/s per channel to 85% of it at 14 kbit/s and below, as measured.
     *
     * @param channelBitRate the scaled bit rate of the header divided by the number of full band channels
     */
    private static float noiseLevel(double channelBitRate) {
        var position = Math.clamp((channelBitRate - 14000) / 18000, 0, 1);
        return (float) (NOISE_LEVEL * (0.85 + 0.15 * position));
    }

    /**
     * {@return the scale factor of a sample of a subband of a channel of the pair}
     *
     * @param c the channel of the pair
     * @param sb the subband
     * @param n the sample in the frame
     */
    private int scaleFactor(int c, int sb, int n) {
        var slot = n >> 4;
        if (sb < 2) {
            return grid1[c][sb][slot];
        }
        var value = interpolate(grid1[c], sb, slot);
        if (sb >= 4) {
            value -= averages[c][sb] - 16;
            value -= grid3[c][sb][slot] - 16;
            value -= grid2[c][GRID2_BAND[sb]][n >> 1];
        }
        return value;
    }

    /**
     * {@return the grid 1 scale factor of a subband at a time slot, interpolated between the bands around it}
     */
    private static int interpolate(int[][] grid, int sb, int slot) {
        var upper = 0;
        while (GRID1_CENTER[upper] < sb) {
            upper++;
        }
        if (GRID1_CENTER[upper] == sb || sb == 5) {
            return grid[sb == 5 ? 4 : upper][slot];
        }
        var lower = upper - 1;
        var high = grid[upper][slot];
        var difference = grid[lower][slot] - high;
        var span = GRID1_CENTER[upper] - GRID1_CENTER[lower];
        var product = (GRID1_CENTER[upper] - sb) * difference;
        var quotient = Math.floorDiv(product, span);
        if (Math.floorMod(product, span) == 0 && GRID1_ROUNDING[sb] * difference < 0) {
            quotient--;
        }
        return high + quotient;
    }

    /**
     * {@return the next noise sample, uniform in [-1, 1)} The generator is linear congruential with the multiplier
     * and increment of the C standard's example {@code rand}; its whole 32-bit state is read as a signed fraction and
     * it is seeded with 1 at the start of the stream, as in ffmpeg's decoder.
     */
    private float noise() {
        seed = seed * 1103515245 + 12345;
        return seed * 0x1p-31f;
    }

    /**
     * Combines the scaled samples of a pair into its two output channels, slot by slot, before the LPC: subbands 0
     * and 1 by their mid/side flags; the subbands where the second channel codes its samples by their swap flags
     * (and their mid/side flags, which then invert the second output); the others copy the first channel, each
     * output scaled by its stereo gain and the second inverted by the mid/side flags when the frame carries a
     * partial stereo grid.
     *
     * <p>In subband 2 the first output of a copy is scaled only after its LPC, which {@link #filter} applies.
     */
    private void combine(int ch) {
        var left = samples[ch];
        var right = samples[ch + 1];
        for (var sb = 0; sb < subbands; sb++) {
            var y0 = scaled[0][sb];
            var y1 = scaled[1][sb];
            var l = left[sb];
            var r = right[sb];
            if (sb < Math.max(2, monoStart)) {
                var flags = midSide[sb];
                for (var n = 0; n < SAMPLES; n++) {
                    if ((flags >> (n >> 4) & 1) != 0) {
                        l[HISTORY + n] = 0.5f * (y0[n] + y1[n]);
                        r[HISTORY + n] = 0.5f * (y0[n] - y1[n]);
                    } else {
                        l[HISTORY + n] = y0[n];
                        r[HISTORY + n] = y1[n];
                    }
                }
            } else if (present[sb]) {
                var swapped = swap[sb];
                var inverted = midSide[sb];
                for (var n = 0; n < SAMPLES; n++) {
                    var slot = n >> 4;
                    if ((swapped >> slot & 1) != 0) {
                        l[HISTORY + n] = y1[n];
                        r[HISTORY + n] = (inverted >> slot & 1) != 0 ? -y0[n] : y0[n];
                    } else {
                        l[HISTORY + n] = y0[n];
                        r[HISTORY + n] = y1[n];
                    }
                }
            } else {
                var group = (sb - monoStart) / STEREO_GROUP;
                var inverted = stereo ? midSide[sb] : 0;
                stereoGains(ch + 1, 1, group);
                for (var n = 0; n < SAMPLES; n++) {
                    var value = gains[n] * y0[n];
                    r[HISTORY + n] = (inverted >> (n >> 4) & 1) != 0 ? -value : value;
                }
                if (sb == 2) {
                    System.arraycopy(y0, 0, l, HISTORY, SAMPLES);
                } else {
                    stereoGains(ch, 0, group);
                    for (var n = 0; n < SAMPLES; n++) {
                        l[HISTORY + n] = gains[n] * y0[n];
                    }
                }
            }
        }
    }

    /**
     * Fills {@link #gains} with the stereo gain of a channel of the pair and a group over the frame: from the last
     * point of the previous frame linearly to each of the frame's four points, or 1 without a partial stereo grid.
     *
     * @param channel the output channel, whose previous point applies
     * @param c the channel of the pair, whose grid applies
     * @param group the partial stereo grid group
     */
    private void stereoGains(int channel, int c, int group) {
        if (!stereo) {
            Arrays.fill(gains, 1);
            return;
        }
        var previous = stereoState[channel][group];
        for (var k = 0; k < 4; k++) {
            var next = stereoGain(c, group, k);
            for (var j = 0; j < STEREO_STEP; j++) {
                gains[k * STEREO_STEP + j] = previous + (next - previous) * j / STEREO_STEP;
            }
            previous = next;
        }
    }

    /**
     * {@return the linear gain of the partial stereo grid value of a channel, group and subsample} The 32 gains
     * cover the values the code book reaches without its escape and a minimum below 9; a value past them leaves
     * the samples unscaled. ffmpeg's decoder reads past its gain table there: it applies a gain of 1 to the values
     * 34 and from 36 up, as this decoder does, and gains that depend on the memory after its table to 32, 33 and 35.
     */
    private float stereoGain(int c, int group, int k) {
        var value = stereoGrid[c][group][k];
        return value >= 0 && value < STEREO_GAINS.length ? STEREO_GAINS[value] : 1;
    }

    /**
     * Runs the LPC synthesis filters of subbands 0 to 2 of an output channel over the frame (clause 9.6.3.5): the
     * coefficients of a frame apply from its sample 16 to sample 15 of the next frame, in two segments for subbands
     * 0 and 1. In subband 2 of a pair whose second channel copies the first, the second output is not filtered
     * (its history becomes the copy) and the first output is scaled by its stereo gains after the filter.
     *
     * @param ch the output channel
     * @param c the channel of the pair
     * @param pair whether the channel belongs to a pair
     */
    private void filter(int ch, int c, boolean pair) {
        for (var sb = 0; sb < LPC_SUBBANDS; sb++) {
            var sets = lpc[ch][sb];
            var segments = sb < 2 ? 2 : 1;
            for (var s = 0; s < segments; s++) {
                stepUp(lpcIndices[c], (sb < 2 ? sb * 2 + s : 4) * ORDER, sets[1 + s]);
            }
            var out = samples[ch][sb];
            var history = lpcHistory[ch][sb];
            var copy = pair && sb == 2 && !present[2];
            if (copy && c == 1) {
                for (var j = 0; j < ORDER; j++) {
                    history[j] = out[HISTORY + SAMPLES - 1 - j];
                }
            } else {
                for (var n = 0; n < SAMPLES; n++) {
                    float[] a;
                    if (n < 16) {
                        a = sets[0];
                    } else if (segments == 2 && n >= 80) {
                        a = sets[2];
                    } else {
                        a = sets[1];
                    }
                    var y = out[HISTORY + n];
                    for (var j = 0; j < ORDER; j++) {
                        y -= a[j] * history[j];
                    }
                    System.arraycopy(history, 0, history, 1, ORDER - 1);
                    history[0] = y;
                    out[HISTORY + n] = y;
                }
                if (copy) {
                    stereoGains(ch, 0, 0);
                    for (var n = 0; n < SAMPLES; n++) {
                        out[HISTORY + n] *= gains[n];
                    }
                    for (var j = 0; j < ORDER; j++) {
                        history[j] = out[HISTORY + SAMPLES - 1 - j];
                    }
                }
            }
            System.arraycopy(sets[segments], 0, sets[0], 0, ORDER);
        }
    }

    /**
     * Converts 8 reflection coefficient indices to direct form coefficients with the step-up recursion.
     */
    private static void stepUp(int[] indices, int offset, float[] out) {
        Arrays.fill(out, 0);
        for (var m = 0; m < ORDER; m++) {
            var k = REFLECTION[indices[offset + m]];
            for (var j = 0; j < (m + 1) / 2; j++) {
                var low = out[j];
                var high = out[m - 1 - j];
                out[j] = low + k * high;
                out[m - 1 - j] = high + k * low;
            }
            out[m] = k;
        }
    }

    /**
     * Transforms the output subband samples of a channel into the 32 spectrum blocks of the frame and adds them to
     * the spectrum ring, then keeps the last samples for the next frame.
     */
    private void synthesize(int ch, float[] spectrum, int ring, long firstBlock) {
        var channel = samples[ch];
        var coded = 4 * subbands;
        for (var b = 0; b < 32; b++) {
            for (var sb = 0; sb < subbands; sb++) {
                var s = channel[sb];
                var start = 4 * b;
                for (var k = 0; k < 4; k++) {
                    var sum = 0f;
                    for (var n = 0; n < 8; n++) {
                        sum += TRANSFORM[n * 4 + k] * s[start + n];
                    }
                    block[4 * sb + k] = sum;
                }
            }
            for (var i = ALIAS_START; i < subbands - 1; i++) {
                alias(4 * i + 3, 4 * i + 4, ALIAS_1);
                alias(4 * i + 2, 4 * i + 5, ALIAS_2);
            }
            var slot = (int) Math.floorMod(firstBlock + 1 + b, ring) * lines;
            for (var k = 0; k < coded; k++) {
                spectrum[slot + k] += block[k] * gain;
            }
        }
        for (var sb = 0; sb < subbands; sb++) {
            System.arraycopy(channel[sb], SAMPLES, channel[sb], 0, HISTORY);
        }
    }

    /**
     * Applies one alias cancellation butterfly of clause 9.6.4 between two lines.
     */
    private void alias(int low, int high, float coefficient) {
        var a = block[low] * coefficient;
        var b = block[high] * coefficient;
        block[low] += b - a;
        block[high] -= b + a;
    }
}
