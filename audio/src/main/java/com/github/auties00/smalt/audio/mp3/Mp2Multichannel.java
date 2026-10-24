/* Ports the multichannel extension decoding of the ISO/IEC 13818-5 MPEG-2 audio reference software (dist10,
 * mc/decoder/decode.c and common.c: mc_header, mc_composite_status_info, II_decode_bitalloc_mc, II_decode_scale,
 * II_buffer_sample_mc, II_dequantize_sample_mc, II_denormalize_sample_mc, II_lfe_calc, dematricing_mc,
 * denormalizing_mc, mc_error_check, mc_ext_error_check and the prediction). Copyright ISO MPEG Audio Subgroup
 * Software Simulation Group; ISO/IEC 13818-5 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes the ISO/IEC 13818-3 multichannel extension of a Layer II frame: the center, surround and
 * low-frequency enhancement channels carried in the ancillary data of an MPEG-1 compatible frame and, when
 * the frame's extension does not fit, in an extension frame.
 *
 * <p>The base frame carries the compatible stereo signal {@code Lo/Ro}; the extension carries transmission
 * channels {@code T2} to {@code T4}, which dematrixing turns, with {@code Lo/Ro}, back into the left, right,
 * center and surround channels. The tools are the transmission channel allocation per subband group,
 * dynamic crosstalk (subbands whose samples are shared with another channel), the prediction of the
 * transmission channels from {@code Lo/Ro}, the four dematrixing procedures, phantom center coding and the
 * low-frequency enhancement channel, coded at 1/96 of the sampling rate. A second stereo programme
 * ({@code 3/0 + 2/0} and {@code 2/0 + 2/0}) is decoded but is not part of the output; multilingual channels
 * are skipped.
 *
 * <p>Subband samples are held per channel, subband {@code sb}, time slot {@code t} (0 to 35) at
 * {@code sb * 36 + t}, in the scale of the decoder's synthesis input. Instances are not thread-safe.
 *
 * @implNote This implementation follows the reference decoder: where its handling of an illegal or
 * ambiguous combination differs from a literal reading of the syntax, the reference's behavior is kept, since
 * the ISO/IEC 13818-4 reference outputs were checked against it.
 */
final class Mp2Multichannel {
    /**
     * The extension frame sync word, 12 bits.
     */
    static final int EXTENSION_SYNC = 0x7FF;

    /**
     * The largest number of channels the extension decodes: {@code Lo}, {@code Ro} and five transmission
     * channels.
     */
    private static final int CHANNELS = 7;

    /**
     * The delay line of the prediction, in time slots of history.
     */
    private static final int PREDICTION_HISTORY = 2 + 7;

    /**
     * The step of the prediction coefficients.
     */
    private static final float PREDICTION_STEP = 0.03125f;

    /**
     * The last subband of each of the 12 subband groups.
     */
    private static final int[] SUBBAND_GROUP_ENDS = {0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 15, 26};

    /**
     * The subband group of every subband.
     */
    private static final int[] GROUP = new int[32];

    /**
     * The number of predictors by prediction mode and dynamic crosstalk mode.
     */
    private static final int[][] PREDICTORS = {
            {6, 4, 4, 4, 2, 2, 2, 0, 2, 2, 2, 0, 0, 0, 0, 0},
            {4, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {4, 2, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}};

    /**
     * The scalefactor gains {@code 2^(-i / 3)} in the scale of the synthesis input, half the multipliers of
     * ISO/IEC 11172-3 Table B.1; entry 63 stands for zero.
     */
    private static final float[] GAIN = new float[64];

    /**
     * The scalefactor multipliers {@code 2^(1 - i / 3)} of ISO/IEC 11172-3 Table B.1, for the
     * low-frequency enhancement channel, which is not synthesized.
     */
    private static final double[] MULTIPLE = new double[64];

    static {
        var group = 0;
        for (var sb = 1; sb < 32; sb++) {
            for (var c = 1; c < 12; c++) {
                if (SUBBAND_GROUP_ENDS[c - 1] < sb && sb <= SUBBAND_GROUP_ENDS[c]) {
                    group = c;
                    break;
                }
            }
            GROUP[sb] = group;
        }
        for (var i = 0; i < 63; i++) {
            GAIN[i] = (float) StrictMath.pow(2.0, -i / 3.0);
            MULTIPLE[i] = StrictMath.pow(2.0, 1.0 - i / 3.0);
        }
        GAIN[63] = (float) StrictMath.pow(2.0, -21.0);
        MULTIPLE[63] = 1e-20;
    }

    /**
     * The bits of the extension: the end of the base frame's audio data, then the extension frame's
     * data.
     */
    private byte[] data = new byte[4096];

    /**
     * The number of bits in {@link #data}.
     */
    private int bits;

    /**
     * The reader over {@link #data}.
     */
    private final BitReader reader = new BitReader();

    private int extensionPresent;

    private int ancillaryBytes;

    private int center;

    private int surround;

    private int lfe;

    private int audioMix;

    private int dematrix;

    private int multilingual;

    private int multilingualFs;

    private int multilingualLayer;

    private int copyrightBit;

    private int copyrightStart;

    /**
     * The number of base channels, 1 or 2.
     */
    private int stereo;

    /**
     * The number of transmission channels, {@code mc_channel}.
     */
    private int mcChannels;

    /**
     * The width of a transmission channel allocation.
     */
    private int allocationBits;

    /**
     * The width of a dynamic crosstalk mode.
     */
    private int crosstalkBits;

    /**
     * The row of {@link #PREDICTORS}.
     */
    private int predictionMode;

    /**
     * The number of subbands the extension codes.
     */
    private int bands;

    private final int[] width = new int[32];

    private final int[] codes = new int[32];

    private int tcSelect;

    private int crosstalkOn;

    private int predictionOn;

    private int tcAllocationAll;

    private final int[] tcAllocation = new int[12];

    private int crosstalkLR;

    private final int[] crosstalkMode = new int[12];

    private final int[] secondStereo = new int[12];

    private final int[] predicted = new int[8];

    private final int[][] predictorCoefficients = new int[8][6];

    private final int[][] delayCompensation = new int[8][6];

    private final int[][][] coefficients = new int[8][6][3];

    private int lfeAllocation;

    private int lfeScalefactor;

    private final int[][] allocation = new int[CHANNELS][32];

    private final int[][] scfsi = new int[CHANNELS][32];

    private final int[][][] scalefactor = new int[CHANNELS][3][32];

    /**
     * The subband samples of every channel; entries below {@link #stereo} are the base channels.
     */
    private final float[][] samples = new float[CHANNELS][32 * 36];

    /**
     * The low-frequency enhancement samples of the frame, in {@code [-1, 1]}.
     */
    private final float[] lfeSamples = new float[12];

    /**
     * The channel configuration {@link #cachedMap} and {@link #cachedLayout} were computed for, or {@code -1}.
     */
    private int outputKey = -1;

    /**
     * The channel map of the configuration {@link #outputKey}.
     */
    private int[] cachedMap;

    /**
     * The layout of the configuration {@link #outputKey}.
     */
    private ChannelLayout cachedLayout;

    /**
     * The prediction delay line of the base channels' first eight subbands.
     */
    private final float[][][] history = new float[2][8][36 + PREDICTION_HISTORY];

    /**
     * Gives the extension's subband sample buffer of a base channel, for the caller to fill.
     *
     * @param channel the base channel, 0 or 1
     * @return the buffer, subband {@code sb}, time slot {@code t} at {@code sb * 36 + t}
     */
    float[] baseSamples(int channel) {
        return samples[channel];
    }

    /**
     * Returns the subband samples of a decoded channel.
     *
     * @param channel the channel in the reference order: {@code 0} left, {@code 1} right, then the channels
     * of {@link #outputChannels()}
     * @return the samples, subband {@code sb}, time slot {@code t} at {@code sb * 36 + t}
     */
    float[] channelSamples(int channel) {
        return samples[channel];
    }

    /**
     * {@return the low-frequency enhancement samples of the frame, 12 in {@code [-1, 1]}}
     */
    float[] lfeSamples() {
        return lfeSamples;
    }

    /**
     * {@return whether the frame has a low-frequency enhancement channel}
     */
    boolean hasLfe() {
        return lfe != 0;
    }

    /**
     * Returns the extension frame length of the bytes at an offset, if they start one.
     *
     * @param data the bytes
     * @param offset the index of the candidate extension frame
     * @param length the number of bytes available from {@code offset}
     * @return the extension frame length in bytes, or {@code 0} when no extension frame starts there
     */
    static int extensionFrameLength(byte[] data, int offset, int length) {
        if (length < 5 || (data[offset] & 0xFF) != 0x7F || (data[offset + 1] & 0xF0) != 0xF0) {
            return 0;
        }
        var bytes = ((data[offset + 3] & 0x0F) << 7) | ((data[offset + 4] & 0xFE) >> 1);
        return bytes >= 5 && bytes <= length ? bytes : 0;
    }

    /**
     * Checks the CRC-16 of an extension frame header and of the first bits of its data.
     *
     * @param data the bytes
     * @param offset the index of the extension frame
     * @param length the extension frame length
     * @return {@code true} when the CRC matches
     */
    static boolean extensionCrcMatches(byte[] data, int offset, int length) {
        var stored = (data[offset + 1] & 0x0F) << 12 | (data[offset + 2] & 0xFF) << 4 | (data[offset + 3] & 0xF0) >> 4;
        var covered = Math.min(128, length * 8 - 28);
        if (covered < 12) {
            return false;
        }
        var crc = 0xFFFF;
        for (var i = 0; i < covered; i++) {
            var position = 28 + i;
            var bit = (data[offset + (position >> 3)] >> (7 - (position & 7))) & 1;
            crc = MpegAudioCrc.update(crc, bit, 1);
        }
        return crc == stored;
    }

    /**
     * Gathers the bits of the extension: the base frame's bits after its audio data, less its ancillary
     * bytes, followed by the data of the extension frame when one is given.
     *
     * @param base the bytes of the base frame
     * @param start the bit offset in {@code base} where the audio data ends
     * @param end the bit offset in {@code base} where the frame ends
     * @param extension the bytes holding the extension frame, or {@code null}
     * @param extensionOffset the index of the extension frame
     * @param extensionLength the extension frame length in bytes
     * @return {@code false} when the base frame has no room for an extension header
     */
    boolean gather(byte[] base, int start, int end, byte[] extension, int extensionOffset, int extensionLength) {
        if (end - start < 32) {
            return false;
        }
        var present = (base[start >> 3] >> (7 - (start & 7))) & 1;
        var ancillary = 0;
        if (present == 1) {
            for (var i = 1; i <= 8; i++) {
                var position = start + i;
                ancillary = ancillary << 1 | ((base[position >> 3] >> (7 - (position & 7))) & 1);
            }
        }
        var baseBits = end - start - ancillary * 8;
        if (baseBits < 32) {
            return false;
        }
        var extensionBits = present == 1 && extension != null ? (extensionLength - 5) * 8 : 0;
        var total = baseBits + extensionBits;
        if (data.length < (total + 7) / 8 + 4) {
            data = new byte[(total + 7) / 8 + 1024];
        }
        Arrays.fill(data, 0, (total + 7) / 8 + 4, (byte) 0);
        copyBits(base, start, data, 0, baseBits);
        if (extensionBits > 0) {
            copyBits(extension, (extensionOffset + 5) * 8, data, baseBits, extensionBits);
        }
        bits = total;
        return true;
    }

    /**
     * Copies a run of bits between byte arrays, most significant bit first.
     *
     * @param source the source bytes
     * @param from the first source bit
     * @param target the target bytes, zero where bits are written
     * @param to the first target bit
     * @param count the number of bits
     */
    private static void copyBits(byte[] source, int from, byte[] target, int to, int count) {
        var i = 0;
        if ((from & 7) == 0 && (to & 7) == 0) {
            var whole = count >> 3;
            System.arraycopy(source, from >> 3, target, to >> 3, whole);
            i = whole << 3;
        }
        for (; i < count; i++) {
            var s = from + i;
            var t = to + i;
            if (((source[s >> 3] >> (7 - (s & 7))) & 1) != 0) {
                target[t >> 3] |= (byte) (0x80 >>> (t & 7));
            }
        }
    }

    /**
     * {@return whether the extension of the frame gathered last announces an extension frame}
     */
    boolean wantsExtensionFrame() {
        return (data[0] & 0x80) != 0;
    }

    /**
     * Parses the extension gathered last and decodes its channels, the base channels' subband samples
     * having been stored with {@link #baseSamples(int)}.
     *
     * @param baseChannels the number of base channels, 1 or 2
     * @param sampleRate the sampling rate
     * @param baseScale gives the scalefactor index of a base channel's subband and block
     * @param crcCheck whether a CRC mismatch rejects the extension
     * @return {@code true} when the extension decoded; on {@code false} the base channels are left as they
     * are and the caller decodes the frame as plain stereo
     */
    boolean decode(int baseChannels, int sampleRate, BaseScale baseScale, boolean crcCheck) {
        stereo = baseChannels;
        var bs = reader;
        bs.init(data, 0, (bits + 7) / 8);
        bs.limit = bits;
        readHeader(bs);
        var storedCrc = bs.read(16);
        if (!configure(sampleRate)) {
            return false;
        }
        readCompositeStatus(bs);
        for (var ch = 0; ch < 2; ch++) {
            for (var band = 0; band < 32; band++) {
                allocation[ch][band] = ch < stereo ? baseScale.allocation(ch, band) : 0;
            }
        }
        readAllocation(bs);
        readScfsi(bs);
        readPredictors(bs);
        if (lfe != 0 && lfeAllocation != 0) {
            lfeScalefactor = bs.read(6);
        }
        readScalefactors(bs);
        if (bs.overrun()) {
            return false;
        }
        var crc = protectedCrc();
        if (crc != storedCrc && crcCheck) {
            return false;
        }
        readSamples(bs, baseScale);
        if (bs.overrun()) {
            return false;
        }
        updateHistory();
        dematrix();
        denormalize();
        return true;
    }

    /**
     * Computes the CRC-16 of the extension from the values read ({@code mc_error_check}).
     *
     * @return the CRC
     */
    private int protectedCrc() {
        var crc = MpegAudioCrc.update(0xFFFF, extensionPresent, 1);
        if (extensionPresent == 1) {
            crc = MpegAudioCrc.update(crc, ancillaryBytes, 8);
        }
        crc = MpegAudioCrc.update(crc, center, 2);
        crc = MpegAudioCrc.update(crc, surround, 2);
        crc = MpegAudioCrc.update(crc, lfe, 1);
        crc = MpegAudioCrc.update(crc, audioMix, 1);
        crc = MpegAudioCrc.update(crc, dematrix, 2);
        crc = MpegAudioCrc.update(crc, multilingual, 3);
        crc = MpegAudioCrc.update(crc, multilingualFs, 1);
        crc = MpegAudioCrc.update(crc, multilingualLayer, 1);
        crc = MpegAudioCrc.update(crc, copyrightBit, 1);
        crc = MpegAudioCrc.update(crc, copyrightStart, 1);
        crc = MpegAudioCrc.update(crc, tcSelect, 1);
        crc = MpegAudioCrc.update(crc, crosstalkOn, 1);
        crc = MpegAudioCrc.update(crc, predictionOn, 1);
        if (tcSelect == 1) {
            crc = MpegAudioCrc.update(crc, tcAllocationAll, allocationBits);
        } else {
            for (var g = 0; g < 12; g++) {
                crc = MpegAudioCrc.update(crc, tcAllocation[g], allocationBits);
            }
        }
        if (crosstalkOn == 1) {
            crc = MpegAudioCrc.update(crc, crosstalkLR, 1);
            for (var g = 0; g < 12; g++) {
                crc = MpegAudioCrc.update(crc, crosstalkMode[g], crosstalkBits);
                if (surround == 3) {
                    crc = MpegAudioCrc.update(crc, secondStereo[g], 1);
                }
            }
        }
        if (predictionOn == 1) {
            for (var g = 0; g < 8; g++) {
                crc = MpegAudioCrc.update(crc, predicted[g], 1);
                if (predicted[g] == 1) {
                    for (var p = 0; p < PREDICTORS[predictionMode][crosstalkMode[g]]; p++) {
                        crc = MpegAudioCrc.update(crc, predictorCoefficients[g][p], 2);
                    }
                }
            }
        }
        if (lfe != 0) {
            crc = MpegAudioCrc.update(crc, lfeAllocation, width[0]);
        }
        var first = stereo;
        var last = stereo + mcChannels;
        for (var i = 0; i < bands; i++) {
            if (crosstalkOn == 0) {
                for (var m = first; m < last; m++) {
                    if (i < 12 || m != 2 || center != 3) {
                        crc = MpegAudioCrc.update(crc, allocation[m][i], width[i]);
                    }
                }
                continue;
            }
            var g = GROUP[i];
            var mode = crosstalkMode[g];
            if (mode == 0) {
                for (var m = first; m < last; m++) {
                    if (center != 3 || i < 12 || m != 2) {
                        if (surround == 3 && secondStereo[g] == 1) {
                            if ((center == 0 || m != 4) && (center != 0 || m != first + 1)) {
                                crc = MpegAudioCrc.update(crc, allocation[m][i], width[i]);
                            }
                        } else {
                            crc = MpegAudioCrc.update(crc, allocation[m][i], width[i]);
                        }
                    }
                }
            } else if (crosstalkBits == 1) {
                if (surround == 3) {
                    crc = MpegAudioCrc.update(crc, allocation[3][i], width[i]);
                    if (secondStereo[g] == 0) {
                        crc = MpegAudioCrc.update(crc, allocation[4][i], width[i]);
                    }
                }
            } else if (crosstalkBits == 3) {
                if ((center != 3 || i < 12) && (mode == 1 || mode == 4)) {
                    crc = MpegAudioCrc.update(crc, allocation[2][i], width[i]);
                }
                if (mode == 2) {
                    crc = MpegAudioCrc.update(crc, allocation[3][i], width[i]);
                }
            } else if (crosstalkBits == 4) {
                if (center != 3 || i < 12) {
                    switch (mode) {
                        case 1, 2, 4, 8, 9, 10, 11, 12, 14 -> crc = MpegAudioCrc.update(crc, allocation[2][i], width[i]);
                        default -> {
                        }
                    }
                }
                switch (mode) {
                    case 1, 3, 5, 8, 10, 13 -> crc = MpegAudioCrc.update(crc, allocation[3][i], width[i]);
                    default -> {
                    }
                }
                switch (mode) {
                    case 2, 3, 6, 9 -> crc = MpegAudioCrc.update(crc, allocation[4][i], width[i]);
                    default -> {
                    }
                }
            }
        }
        for (var i = 0; i < bands; i++) {
            for (var m = first; m < last; m++) {
                if (allocation[m][i] != 0) {
                    crc = MpegAudioCrc.update(crc, scfsi[m][i], 2);
                }
            }
        }
        return crc;
    }

    /**
     * Reads {@code mc_header}.
     *
     * @param bs the reader at the start of the extension
     */
    private void readHeader(BitReader bs) {
        extensionPresent = bs.read(1);
        ancillaryBytes = extensionPresent == 1 ? bs.read(8) : 0;
        center = bs.read(2);
        surround = bs.read(2);
        lfe = bs.read(1);
        audioMix = bs.read(1);
        dematrix = bs.read(2);
        // TODO: output the multilingual channels and the second stereo programme (3/0 + 2/0, 2/0 + 2/0), which need
        //  an API to select a programme; they are skipped or decoded without being output.
        multilingual = bs.read(3);
        multilingualFs = bs.read(1);
        multilingualLayer = bs.read(1);
        copyrightBit = bs.read(1);
        copyrightStart = bs.read(1);
    }

    /**
     * Derives the channel configuration and the allocation table of the extension ({@code mc_hdr_to_frps}).
     *
     * @param sampleRate the sampling rate
     * @return {@code false} for a configuration the base frame cannot carry
     */
    private boolean configure(int sampleRate) {
        var hasCenter = center == 1 || center == 3;
        if (surround == 3) {
            mcChannels = hasCenter ? 3 : 2;
            allocationBits = hasCenter ? 2 : 0;
            crosstalkBits = hasCenter ? 1 : 0;
            predictionMode = hasCenter ? 2 : 5;
        } else if (surround == 2) {
            mcChannels = hasCenter ? 3 : 2;
            allocationBits = hasCenter ? 3 : 2;
            crosstalkBits = hasCenter ? 4 : 3;
            predictionMode = hasCenter ? 0 : 3;
        } else if (surround == 1) {
            mcChannels = hasCenter ? 2 : 1;
            allocationBits = hasCenter ? 3 : 2;
            crosstalkBits = hasCenter ? 3 : 1;
            predictionMode = hasCenter ? 1 : 4;
        } else {
            mcChannels = hasCenter ? 1 : 0;
            allocationBits = hasCenter ? 2 : 0;
            crosstalkBits = hasCenter ? 1 : 0;
            predictionMode = hasCenter ? 2 : 5;
        }
        if (center == 2 || (multilingual > 0 && multilingualLayer > 0)) {
            return false;
        }
        if (stereo == 1 && !(surround == 3 && !hasCenter)) {
            return false;
        }
        if (stereo + mcChannels > CHANNELS) {
            return false;
        }
        bands = sampleRate == 48000 ? 27 : 30;
        var groups = Layer12.ALLOC_LAYER2_MPEG1;
        for (int band = 0, group = 0, next = 0, groupWidth = 0, offset = 0; band < 32; band++) {
            if (band == next && group < groups.length) {
                offset = groups[group];
                groupWidth = groups[group + 1];
                next += groups[group + 2];
                group += 3;
            }
            width[band] = groupWidth;
            codes[band] = offset;
        }
        return true;
    }

    /**
     * Reads {@code mc_composite_status_info}.
     *
     * @param bs the reader
     */
    private void readCompositeStatus(BitReader bs) {
        tcSelect = bs.read(1);
        crosstalkOn = bs.read(1);
        predictionOn = bs.read(1);
        if (tcSelect == 1) {
            tcAllocationAll = bs.read(allocationBits);
            Arrays.fill(tcAllocation, tcAllocationAll);
        } else {
            tcAllocationAll = 0;
            for (var g = 0; g < 12; g++) {
                tcAllocation[g] = bs.read(allocationBits);
            }
        }
        if (crosstalkOn == 1) {
            crosstalkLR = bs.read(1);
            for (var g = 0; g < 12; g++) {
                crosstalkMode[g] = bs.read(crosstalkBits);
                secondStereo[g] = surround == 3 ? bs.read(1) : 0;
            }
        } else {
            crosstalkLR = 0;
            Arrays.fill(crosstalkMode, 0);
            Arrays.fill(secondStereo, 0);
        }
        if (predictionOn == 1) {
            for (var g = 0; g < 8; g++) {
                predicted[g] = bs.read(1);
                if (predicted[g] == 1) {
                    for (var p = 0; p < PREDICTORS[predictionMode][crosstalkMode[g]]; p++) {
                        predictorCoefficients[g][p] = bs.read(2);
                    }
                }
            }
        } else {
            Arrays.fill(predicted, 0);
        }
    }

    /**
     * Reads the allocation of the low-frequency enhancement channel and of the transmission channels,
     * copying the allocations of the channels that dynamic crosstalk shares ({@code II_decode_bitalloc_mc}).
     *
     * @param bs the reader
     */
    private void readAllocation(BitReader bs) {
        var first = stereo;
        var last = stereo + mcChannels;
        if (lfe != 0) {
            lfeAllocation = bs.read(width[0]);
        }
        for (var i = 0; i < bands; i++) {
            if (crosstalkOn == 0) {
                for (var j = first; j < last; j++) {
                    allocation[j][i] = center != 3 || i < 12 || j != 2 ? bs.read(width[i]) : 0;
                }
                continue;
            }
            var g = GROUP[i];
            var mode = crosstalkMode[g];
            if (mode == 0) {
                for (var j = first; j < last; j++) {
                    if (center == 3 && i >= 12 && j == 2) {
                        allocation[j][i] = 0;
                    } else if (surround == 3 && secondStereo[g] == 1) {
                        if (center != 0 && j == 4) {
                            allocation[j][i] = allocation[3][i];
                        } else if (center == 0 && j == 3) {
                            allocation[j][i] = allocation[2][i];
                        } else {
                            allocation[j][i] = bs.read(width[i]);
                        }
                    } else {
                        allocation[j][i] = bs.read(width[i]);
                    }
                }
            } else if (crosstalkBits == 1) {
                if (center == 3 && i >= 12) {
                    allocation[2][i] = 0;
                } else if (tcAllocation[g] == 1) {
                    allocation[2][i] = allocation[0][i];
                } else if (tcAllocation[g] == 2) {
                    allocation[2][i] = allocation[1][i];
                } else {
                    allocation[2][i] = crosstalkLR == 1 ? allocation[1][i] : allocation[0][i];
                }
                if (surround == 3) {
                    allocation[3][i] = bs.read(width[i]);
                    allocation[4][i] = secondStereo[g] == 1 ? allocation[3][i] : bs.read(width[i]);
                }
            } else if (crosstalkBits == 3) {
                if (center == 3 && i >= 12) {
                    allocation[2][i] = 0;
                } else if (mode == 1 || mode == 4) {
                    allocation[2][i] = bs.read(width[i]);
                } else if (surround == 2 || tcAllocation[g] == 1 || tcAllocation[g] == 5
                        || (tcAllocation[g] != 2 && crosstalkLR == 0)) {
                    allocation[2][i] = allocation[0][i];
                } else {
                    allocation[2][i] = allocation[1][i];
                }
                if (mode == 2) {
                    allocation[3][i] = bs.read(width[i]);
                } else if (mode == 4) {
                    allocation[3][i] = allocation[2][i];
                } else if (surround == 2 || tcAllocation[g] == 4 || tcAllocation[g] == 5
                        || (tcAllocation[g] < 3 && crosstalkLR == 1)) {
                    allocation[3][i] = allocation[1][i];
                } else {
                    allocation[3][i] = allocation[0][i];
                }
            } else if (crosstalkBits == 4) {
                if (center == 3 && i >= 12) {
                    allocation[2][i] = 0;
                } else {
                    switch (mode) {
                        case 1, 2, 4, 8, 9, 10, 11, 12, 14 -> allocation[2][i] = bs.read(width[i]);
                        case 3, 5, 6, 7, 13 -> {
                            if (tcAllocation[g] == 1 || tcAllocation[g] == 7) {
                                allocation[2][i] = allocation[0][i];
                            } else if (tcAllocation[g] == 2 || tcAllocation[g] == 6) {
                                allocation[2][i] = allocation[1][i];
                            } else {
                                allocation[2][i] = crosstalkLR == 1 ? allocation[1][i] : allocation[0][i];
                            }
                        }
                        default -> {
                        }
                    }
                }
                switch (mode) {
                    case 1, 3, 5, 8, 10, 13 -> allocation[3][i] = bs.read(width[i]);
                    case 2, 4, 6, 7, 12 -> allocation[3][i] = allocation[0][i];
                    case 9, 11, 14 -> allocation[3][i] = allocation[2][i];
                    default -> {
                    }
                }
                switch (mode) {
                    case 2, 3, 6, 9 -> allocation[4][i] = bs.read(width[i]);
                    case 1, 4, 5, 7, 11 -> allocation[4][i] = allocation[1][i];
                    case 10, 12, 14 -> allocation[4][i] = allocation[2][i];
                    case 8, 13 -> allocation[4][i] = allocation[3][i];
                    default -> {
                    }
                }
            }
        }
        for (var i = bands; i < 32; i++) {
            for (var j = first; j < last; j++) {
                allocation[j][i] = 0;
            }
        }
    }

    /**
     * Reads the scalefactor selection of the transmission channels.
     *
     * @param bs the reader
     */
    private void readScfsi(BitReader bs) {
        for (var i = 0; i < bands; i++) {
            for (var j = stereo; j < stereo + mcChannels; j++) {
                scfsi[j][i] = allocation[j][i] != 0 ? bs.read(2) : 4;
            }
        }
    }

    /**
     * Reads the delay compensation and coefficients of the predictors.
     *
     * @param bs the reader
     */
    private void readPredictors(BitReader bs) {
        if (predictionOn != 1) {
            return;
        }
        for (var g = 0; g < 8; g++) {
            if (predicted[g] != 1) {
                continue;
            }
            for (var p = 0; p < PREDICTORS[predictionMode][crosstalkMode[g]]; p++) {
                if (predictorCoefficients[g][p] != 0) {
                    delayCompensation[g][p] = bs.read(3);
                    for (var c = 0; c < predictorCoefficients[g][p]; c++) {
                        coefficients[g][p][c] = bs.read(8);
                    }
                } else {
                    coefficients[g][p][0] = 127;
                    delayCompensation[g][p] = 0;
                }
            }
        }
    }

    /**
     * Reads the scalefactors of the transmission channels.
     *
     * @param bs the reader
     */
    private void readScalefactors(BitReader bs) {
        for (var i = 0; i < 32; i++) {
            for (var j = stereo; j < stereo + mcChannels; j++) {
                var scf = scalefactor[j];
                if (i >= bands || allocation[j][i] == 0) {
                    scf[0][i] = scf[1][i] = scf[2][i] = 63;
                    continue;
                }
                switch (scfsi[j][i]) {
                    case 0 -> {
                        scf[0][i] = bs.read(6);
                        scf[1][i] = bs.read(6);
                        scf[2][i] = bs.read(6);
                    }
                    case 1 -> {
                        scf[0][i] = scf[1][i] = bs.read(6);
                        scf[2][i] = bs.read(6);
                    }
                    case 3 -> {
                        scf[0][i] = bs.read(6);
                        scf[1][i] = scf[2][i] = bs.read(6);
                    }
                    default -> scf[0][i] = scf[1][i] = scf[2][i] = bs.read(6);
                }
            }
        }
    }

    /**
     * Returns whether the samples of a transmission channel's subband are in the bit stream, rather than
     * shared with another channel by dynamic crosstalk.
     *
     * @param j the channel
     * @param i the subband
     * @return {@code true} when the samples are transmitted
     */
    private boolean transmitted(int j, int i) {
        if (allocation[j][i] == 0) {
            return false;
        }
        if (crosstalkOn != 1) {
            return true;
        }
        var g = GROUP[i];
        var mode = crosstalkMode[g];
        if (crosstalkBits == 4 && ((mode == 1 && j == 4) || (mode == 2 && j == 3) || (mode == 3 && j == 2)
                || (mode == 4 && j != 2) || (mode == 5 && j != 3) || (mode == 6 && j != 4) || mode == 7
                || (mode == 8 && j == 4) || (mode == 9 && j == 3) || (mode == 10 && j == 4) || (mode == 11 && j != 2)
                || (mode == 12 && j != 2) || (mode == 13 && j != 3) || (mode == 14 && j != 2))) {
            return false;
        }
        if (crosstalkBits == 3 && ((mode == 1 && j == 3) || (mode == 2 && j == 2) || mode == 3
                || (mode == 4 && j == 3))) {
            return false;
        }
        if (crosstalkBits == 1 && mode == 1 && j == 2) {
            return false;
        }
        if (surround == 3 && secondStereo[g] == 1) {
            if ((center == 1 || center == 3) && j == 4) {
                return false;
            }
            return center != 0 || j != 3;
        }
        return true;
    }

    /**
     * Reads, dequantizes and scales the samples of the low-frequency enhancement and transmission channels
     * ({@code II_buffer_sample_mc}, {@code II_dequantize_sample_mc}, {@code II_denormalize_sample_mc},
     * {@code II_lfe_calc}).
     *
     * @param bs the reader
     * @param baseScale the scalefactor indices of the base channels
     */
    private void readSamples(BitReader bs, BaseScale baseScale) {
        var first = stereo;
        var last = stereo + mcChannels;
        for (var j = first; j < last; j++) {
            Arrays.fill(samples[j], 0);
        }
        var codesTable = Mp3Tables.BIT_ALLOCATION_CODES;
        for (var gr = 0; gr < 12; gr++) {
            if (lfe != 0) {
                if (lfeAllocation > 0) {
                    var code = bs.read(lfeAllocation + 1);
                    var type = codesTable[codes[0] + lfeAllocation];
                    var levels = type == 17 ? 3 : (1 << type) - 1;
                    lfeSamples[gr] = levels > 0 ? (float) (dequantize(code, levels) * MULTIPLE[lfeScalefactor]) : 0;
                } else {
                    lfeSamples[gr] = 0;
                }
            }
            for (var i = 0; i < bands; i++) {
                for (var j = first; j < last; j++) {
                    if (!transmitted(j, i)) {
                        continue;
                    }
                    var type = codesTable[codes[i] + allocation[j][i]];
                    if (type == 0) {
                        continue;
                    }
                    var base = i * 36 + 3 * gr;
                    if (type >= 17) {
                        var levels = type == 17 ? 3 : type == 18 ? 5 : 9;
                        var value = bs.read(type == 17 ? 5 : type == 18 ? 7 : 10);
                        for (var k = 0; k < 3; k++) {
                            samples[j][base + k] = (float) dequantize(value % levels, levels);
                            value /= levels;
                        }
                    } else {
                        var levels = (1 << type) - 1;
                        for (var k = 0; k < 3; k++) {
                            samples[j][base + k] = (float) dequantize(bs.read(type), levels);
                        }
                    }
                }
            }
        }
        for (var i = 0; i < bands; i++) {
            for (var t = 0; t < 36; t++) {
                scale(i, t, t / 12, baseScale);
            }
        }
    }

    /**
     * Returns the dequantized value of a sample code, {@code (2 code - (levels - 1)) / levels}.
     *
     * @param code the code, from 0 to {@code levels - 1}
     * @param levels the number of quantizer levels
     * @return the value, in {@code (-1, 1)}
     */
    private static double dequantize(int code, int levels) {
        return (2.0 * code - (levels - 1)) / levels;
    }

    /**
     * Returns the base channel's sample of a subband and slot, normalized by its scalefactor and scaled by
     * a transmission channel's.
     *
     * @param from the base channel
     * @param to the transmission channel
     * @param i the subband
     * @param t the time slot
     * @param x the block of 12 slots
     * @param baseScale the scalefactor indices of the base channels
     * @return the sample
     */
    private float fromBase(int from, int to, int i, int t, int x, BaseScale baseScale) {
        return samples[from][i * 36 + t] / GAIN[baseScale.index(from, i, x)] * GAIN[scalefactor[to][x][i]];
    }

    /**
     * Returns a transmission channel's sample scaled from another transmission channel's normalized one.
     *
     * @param from the transmission channel the sample is taken from, already scaled
     * @param to the transmission channel
     * @param i the subband
     * @param t the time slot
     * @param x the block of 12 slots
     * @return the sample
     */
    private float fromScaled(int from, int to, int i, int t, int x) {
        return samples[from][i * 36 + t] / GAIN[scalefactor[from][x][i]] * GAIN[scalefactor[to][x][i]];
    }

    /**
     * Scales one sample of every transmission channel by its scalefactor, filling the channels whose samples
     * dynamic crosstalk shares ({@code II_denormalize_sample_mc}).
     *
     * @param i the subband
     * @param t the time slot
     * @param x the block of 12 slots
     * @param baseScale the scalefactor indices of the base channels
     */
    private void scale(int i, int t, int x, BaseScale baseScale) {
        var index = i * 36 + t;
        var g = GROUP[i];
        if (crosstalkOn == 0) {
            for (var j = stereo; j < stereo + mcChannels; j++) {
                samples[j][index] *= GAIN[scalefactor[j][x][i]];
            }
            return;
        }
        var mode = crosstalkMode[g];
        var tc = tcAllocation[g];
        switch (crosstalkBits) {
            case 0 -> {
                if (surround == 3) {
                    var a = stereo;
                    samples[a][index] *= GAIN[scalefactor[a][x][i]];
                    if (secondStereo[g] == 0) {
                        samples[a + 1][index] *= GAIN[scalefactor[a + 1][x][i]];
                    } else {
                        samples[a + 1][index] = fromScaled(a, a + 1, i, t, x);
                    }
                }
            }
            case 1 -> {
                if (mode == 0) {
                    samples[2][index] *= GAIN[scalefactor[2][x][i]];
                } else if (mode == 1) {
                    if (tc == 0) {
                        samples[2][index] = fromBase(crosstalkLR == 1 ? 1 : 0, 2, i, t, x, baseScale);
                    }
                    if (tc == 1) {
                        samples[2][index] = fromBase(0, 2, i, t, x, baseScale);
                    }
                    if (tc == 2) {
                        samples[2][index] = fromBase(1, 2, i, t, x, baseScale);
                    }
                }
                if ((mode == 0 || mode == 1) && surround == 3) {
                    samples[3][index] *= GAIN[scalefactor[3][x][i]];
                    if (secondStereo[g] == 0) {
                        samples[4][index] *= GAIN[scalefactor[4][x][i]];
                    } else {
                        samples[4][index] = fromScaled(3, 4, i, t, x);
                    }
                }
            }
            case 3 -> {
                switch (mode) {
                    case 0 -> {
                        for (var j = stereo; j < stereo + mcChannels; j++) {
                            samples[j][index] *= GAIN[scalefactor[j][x][i]];
                        }
                    }
                    case 1 -> {
                        samples[2][index] *= GAIN[scalefactor[2][x][i]];
                        samples[3][index] = fromBase(surroundFromRight(tc) ? 1 : 0, 3, i, t, x, baseScale);
                    }
                    case 2 -> {
                        samples[3][index] *= GAIN[scalefactor[3][x][i]];
                        samples[2][index] = fromBase(centerFromLeft(tc) ? 0 : 1, 2, i, t, x, baseScale);
                    }
                    case 3 -> {
                        samples[2][index] = fromBase(centerFromLeft(tc) ? 0 : 1, 2, i, t, x, baseScale);
                        samples[3][index] = fromBase(surroundFromRight(tc) ? 1 : 0, 3, i, t, x, baseScale);
                    }
                    case 4 -> {
                        samples[3][index] = samples[2][index];
                        samples[2][index] *= GAIN[scalefactor[2][x][i]];
                        samples[3][index] *= GAIN[scalefactor[3][x][i]];
                    }
                    default -> {
                    }
                }
            }
            case 4 -> scale32(index, i, t, x, mode, tc, baseScale);
            default -> {
            }
        }
    }

    /**
     * Returns whether a {@code 3/1} or {@code 2/2} stream takes the shared {@code T2} subband from
     * {@code Lo}.
     *
     * @param tc the transmission channel allocation of the subband group
     * @return {@code true} for {@code Lo}, {@code false} for {@code Ro}
     */
    private boolean centerFromLeft(int tc) {
        return surround == 2 || tc == 1 || tc == 5 || (tc != 2 && crosstalkLR == 0);
    }

    /**
     * Returns whether a {@code 3/1} or {@code 2/2} stream takes the shared {@code T3} subband from
     * {@code Ro}.
     *
     * @param tc the transmission channel allocation of the subband group
     * @return {@code true} for {@code Ro}, {@code false} for {@code Lo}
     */
    private boolean surroundFromRight(int tc) {
        return surround == 2 || tc == 4 || tc == 5 || (tc != 3 && crosstalkLR == 1);
    }

    /**
     * Scales one sample of the transmission channels of a {@code 3/2} stream under dynamic crosstalk.
     *
     * @param index the sample index
     * @param i the subband
     * @param t the time slot
     * @param x the block of 12 slots
     * @param mode the dynamic crosstalk mode
     * @param tc the transmission channel allocation
     * @param baseScale the scalefactor indices of the base channels
     */
    private void scale32(int index, int i, int t, int x, int mode, int tc, BaseScale baseScale) {
        var c = samples[2];
        var ls = samples[3];
        var rs = samples[4];
        switch (mode) {
            case 0 -> {
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 1 -> {
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] = fromBase(1, 4, i, t, x, baseScale);
            }
            case 2 -> {
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] = fromBase(0, 3, i, t, x, baseScale);
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 3 -> {
                c[index] = fromBase(centerSource(tc), 2, i, t, x, baseScale);
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 4 -> {
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] = fromBase(0, 3, i, t, x, baseScale);
                rs[index] = fromBase(1, 4, i, t, x, baseScale);
            }
            case 5 -> {
                c[index] = fromBase(centerSource(tc), 2, i, t, x, baseScale);
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] = fromBase(1, 4, i, t, x, baseScale);
            }
            case 6 -> {
                c[index] = fromBase(centerSource(tc), 2, i, t, x, baseScale);
                ls[index] = fromBase(0, 3, i, t, x, baseScale);
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 7 -> {
                c[index] = fromBase(centerSource(tc), 2, i, t, x, baseScale);
                ls[index] = fromBase(0, 3, i, t, x, baseScale);
                rs[index] = fromBase(1, 4, i, t, x, baseScale);
            }
            case 8 -> {
                c[index] *= GAIN[scalefactor[2][x][i]];
                rs[index] = ls[index];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 9 -> {
                ls[index] = c[index];
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 10 -> {
                rs[index] = c[index];
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 11 -> {
                ls[index] = c[index];
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] = fromBase(1, 4, i, t, x, baseScale);
            }
            case 12 -> {
                rs[index] = c[index];
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] = fromBase(0, 3, i, t, x, baseScale);
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 13 -> {
                c[index] = fromBase(centerSource(tc), 2, i, t, x, baseScale);
                rs[index] = ls[index];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            case 14 -> {
                rs[index] = c[index];
                ls[index] = c[index];
                c[index] *= GAIN[scalefactor[2][x][i]];
                ls[index] *= GAIN[scalefactor[3][x][i]];
                rs[index] *= GAIN[scalefactor[4][x][i]];
            }
            default -> {
            }
        }
    }

    /**
     * Returns the base channel a {@code 3/2} stream takes the shared center subband from.
     *
     * @param tc the transmission channel allocation of the subband group
     * @return 0 for {@code Lo}, 1 for {@code Ro}
     */
    private int centerSource(int tc) {
        if (tc == 1 || tc == 7) {
            return 0;
        }
        if (tc == 2 || tc == 6) {
            return 1;
        }
        return crosstalkLR == 1 ? 1 : 0;
    }

    /**
     * Shifts the prediction delay line and appends the frame's base samples of the first eight subbands.
     */
    private void updateHistory() {
        if (predictionOn != 1) {
            return;
        }
        for (var k = 0; k < Math.min(2, stereo); k++) {
            for (var sb = 0; sb < 8; sb++) {
                var line = history[k][sb];
                System.arraycopy(line, 36, line, 0, PREDICTION_HISTORY);
                System.arraycopy(samples[k], sb * 36, line, PREDICTION_HISTORY, 36);
            }
        }
    }

    /**
     * Returns the prediction of a sample from a base channel ({@code predict}).
     *
     * @param sb the subband, below 8
     * @param t the time slot
     * @param ch the base channel
     * @param p the predictor
     * @return the prediction
     */
    private float predict(int sb, int t, int ch, int p) {
        var sum = 0.0f;
        var line = history[ch][sb];
        for (var i = 0; i < predictorCoefficients[sb][p]; i++) {
            sum += line[PREDICTION_HISTORY + t - i - delayCompensation[sb][p]] * (coefficients[sb][p][i] - 127)
                    * PREDICTION_STEP;
        }
        return sum;
    }

    /**
     * Adds the predictions to the transmission channels and dematrixes the channels ({@code dematricing_mc}).
     */
    private void dematrix() {
        var hasCenter = center != 0;
        for (var k = 0; k < 32; k++) {
            var tc = tcSelect == 1 ? tcAllocationAll : tcAllocation[GROUP[k]];
            var predict = predictionOn == 1 && k < 8 && predicted[k] == 1;
            for (var t = 0; t < 36; t++) {
                var n = k * 36 + t;
                if (predict) {
                    addPrediction(k, t, n, hasCenter);
                }
                if (dematrix != 3) {
                    dematrixSample(n, tc, hasCenter);
                }
            }
        }
    }

    /**
     * Adds the predictions of one sample.
     *
     * @param k the subband
     * @param t the time slot
     * @param n the sample index
     * @param hasCenter whether the configuration has a center channel
     */
    private void addPrediction(int k, int t, int n, boolean hasCenter) {
        var mode = crosstalkMode[k];
        if (surround == 2 && hasCenter) {
            switch (mode) {
                case 0 -> {
                    samples[2][n] += pair(k, t, 0);
                    samples[3][n] += pair(k, t, 2);
                    samples[4][n] += pair(k, t, 4);
                }
                case 1 -> {
                    samples[2][n] += pair(k, t, 0);
                    samples[3][n] += pair(k, t, 2);
                }
                case 2 -> {
                    samples[2][n] += pair(k, t, 0);
                    samples[4][n] += pair(k, t, 2);
                }
                case 3 -> {
                    samples[3][n] += pair(k, t, 0);
                    samples[4][n] += pair(k, t, 2);
                }
                case 4, 8 -> samples[2][n] += pair(k, t, 0);
                case 5, 10 -> samples[3][n] += pair(k, t, 0);
                case 6, 9 -> samples[4][n] += pair(k, t, 0);
                default -> {
                }
            }
        } else if ((surround == 1 && hasCenter) || (surround == 2 && !hasCenter)) {
            switch (mode) {
                case 0 -> {
                    samples[2][n] += pair(k, t, 0);
                    samples[3][n] += pair(k, t, 2);
                }
                case 1 -> samples[2][n] += pair(k, t, 0);
                case 2 -> samples[3][n] += pair(k, t, 0);
                default -> {
                }
            }
        } else if (((surround == 0 || surround == 3) && hasCenter) || (surround == 1 && !hasCenter)) {
            if (mode == 0) {
                samples[2][n] += pair(k, t, 0);
            }
        }
    }

    /**
     * Returns the sum of the predictions from {@code Lo} by predictor {@code p} and from {@code Ro} by
     * predictor {@code p + 1}.
     *
     * @param k the subband
     * @param t the time slot
     * @param p the first predictor
     * @return the prediction
     */
    private float pair(int k, int t, int p) {
        return predict(k, t, 0, p) + predict(k, t, 1, p + 1);
    }

    /**
     * Dematrixes one sample of every channel.
     *
     * @param n the sample index
     * @param tc the transmission channel allocation of the subband
     * @param hasCenter whether the configuration has a center channel
     */
    private void dematrixSample(int n, int tc, boolean hasCenter) {
        var l = samples[0];
        var r = samples[1];
        var t2 = samples[2];
        var t3 = samples[3];
        var t4 = samples[4];
        var procedure2 = dematrix == 2;
        if (surround == 2 && hasCenter) {
            switch (tc) {
                case 0 -> {
                    if (procedure2) {
                        var s = (t3[n] + t4[n]) / 2.0f;
                        l[n] = l[n] - t2[n] + s;
                        r[n] = r[n] - t2[n] - s;
                    } else {
                        l[n] = l[n] - t2[n] - t3[n];
                        r[n] = r[n] - t2[n] - t4[n];
                    }
                }
                case 1 -> {
                    var left = t2[n];
                    if (procedure2) {
                        var s = (t3[n] + t4[n]) / 2.0f;
                        t2[n] = l[n] - t2[n] + s;
                        r[n] = r[n] - t2[n] - s;
                    } else {
                        t2[n] = l[n] - t2[n] - t3[n];
                        r[n] = r[n] - t2[n] - t4[n];
                    }
                    l[n] = left;
                }
                case 2 -> {
                    var right = t2[n];
                    if (procedure2) {
                        var s = (t3[n] + t4[n]) / 2.0f;
                        t2[n] = r[n] - t2[n] - s;
                        l[n] = l[n] - t2[n] + s;
                    } else {
                        t2[n] = r[n] - t2[n] - t4[n];
                        l[n] = l[n] - t2[n] - t3[n];
                    }
                    r[n] = right;
                }
                case 3 -> {
                    var left = t3[n];
                    if (procedure2) {
                        t3[n] = -2.0f * (l[n] - t2[n] - t3[n]) - t4[n];
                        r[n] = l[n] + r[n] - 2.0f * t2[n] - left;
                    } else {
                        t3[n] = l[n] - t3[n] - t2[n];
                        r[n] = r[n] - t2[n] - t4[n];
                    }
                    l[n] = left;
                }
                case 4 -> {
                    var right = t4[n];
                    if (procedure2) {
                        l[n] = l[n] + r[n] - 2.0f * t2[n] - t4[n];
                        t4[n] = 2.0f * r[n] - 2.0f * (t2[n] + t4[n]) - t3[n];
                    } else {
                        l[n] = l[n] - t2[n] - t3[n];
                        t4[n] = r[n] - t4[n] - t2[n];
                    }
                    r[n] = right;
                }
                case 5 -> {
                    if (procedure2) {
                        var left = t3[n];
                        t3[n] = 0.5f * (r[n] - l[n] + t3[n] - t4[n]);
                        l[n] = left;
                        r[n] = t4[n];
                        t4[n] = t3[n];
                    } else {
                        var left = t3[n];
                        t3[n] = l[n] - t3[n] - t2[n];
                        l[n] = left;
                        var right = t4[n];
                        t4[n] = r[n] - t4[n] - t2[n];
                        r[n] = right;
                    }
                }
                case 6 -> {
                    if (procedure2) {
                        var right = t2[n];
                        var left = t3[n];
                        t3[n] = r[n] - l[n] - t2[n] + t3[n] - t4[n];
                        t2[n] = 0.5f * (l[n] + r[n] - t2[n] - left);
                        r[n] = right;
                        l[n] = left;
                    } else {
                        var right = t2[n];
                        t2[n] = r[n] - t2[n] - t4[n];
                        r[n] = right;
                        var left = t3[n];
                        t3[n] = l[n] - t3[n] - t2[n];
                        l[n] = left;
                    }
                }
                case 7 -> {
                    if (procedure2) {
                        var left = t2[n];
                        var right = t4[n];
                        t4[n] = r[n] - l[n] + t2[n] - t3[n] - t4[n];
                        t2[n] = 0.5f * (l[n] + r[n] - t2[n] - right);
                        l[n] = left;
                        r[n] = right;
                    } else {
                        var left = t2[n];
                        t2[n] = l[n] - t2[n] - t3[n];
                        l[n] = left;
                        var right = t4[n];
                        t4[n] = r[n] - t4[n] - t2[n];
                        r[n] = right;
                    }
                }
                default -> {
                }
            }
        } else if (surround == 1 && hasCenter) {
            switch (tc) {
                case 0 -> {
                    l[n] = procedure2 ? l[n] - t2[n] + t3[n] : l[n] - t2[n] - t3[n];
                    r[n] = r[n] - t2[n] - t3[n];
                }
                case 1 -> {
                    var left = t2[n];
                    t2[n] = procedure2 ? l[n] - t2[n] + t3[n] : l[n] - t2[n] - t3[n];
                    r[n] = r[n] - t2[n] - t3[n];
                    l[n] = left;
                }
                case 2 -> {
                    var right = t2[n];
                    t2[n] = r[n] - t2[n] - t3[n];
                    l[n] = procedure2 ? l[n] - t2[n] + t3[n] : l[n] - t2[n] - t3[n];
                    r[n] = right;
                }
                case 3 -> {
                    var left = t3[n];
                    t3[n] = procedure2 ? -l[n] + t2[n] + t3[n] : l[n] - t2[n] - t3[n];
                    r[n] = r[n] - t2[n] - t3[n];
                    l[n] = left;
                }
                case 4 -> {
                    var right = t3[n];
                    t3[n] = r[n] - t2[n] - t3[n];
                    l[n] = procedure2 ? l[n] - t2[n] + t3[n] : l[n] - t2[n] - t3[n];
                    r[n] = right;
                }
                case 5 -> {
                    var left = t2[n];
                    var right = t3[n];
                    t2[n] = 0.5f * (l[n] + r[n] - left - right);
                    t3[n] = 0.5f * (r[n] - l[n] + left - right);
                    l[n] = left;
                    r[n] = right;
                }
                default -> {
                }
            }
        } else if (surround == 1 || hasCenter) {
            switch (tc) {
                case 0 -> {
                    l[n] = l[n] - t2[n];
                    r[n] = r[n] - t2[n];
                }
                case 1 -> {
                    var left = t2[n];
                    t2[n] = l[n] - t2[n];
                    r[n] = r[n] - t2[n];
                    l[n] = left;
                }
                case 2 -> {
                    var right = t2[n];
                    t2[n] = r[n] - t2[n];
                    l[n] = l[n] - t2[n];
                    r[n] = right;
                }
                default -> {
                }
            }
        } else if (surround == 2) {
            switch (tc) {
                case 0 -> {
                    l[n] = l[n] - t2[n];
                    r[n] = r[n] - t3[n];
                }
                case 1 -> {
                    var left = t2[n];
                    r[n] = r[n] - t3[n];
                    t2[n] = l[n] - t2[n];
                    l[n] = left;
                }
                case 2 -> {
                    var right = t3[n];
                    l[n] = l[n] - t2[n];
                    t3[n] = r[n] - t3[n];
                    r[n] = right;
                }
                case 3 -> {
                    var left = t2[n];
                    t2[n] = l[n] - t2[n];
                    l[n] = left;
                    var right = t3[n];
                    t3[n] = r[n] - t3[n];
                    r[n] = right;
                }
                default -> {
                }
            }
        }
    }

    /**
     * Undoes the attenuation of the compatible downmix ({@code denormalizing_mc}).
     */
    private void denormalize() {
        if (dematrix == 3) {
            return;
        }
        var sqrt2 = StrictMath.sqrt(2.0);
        double matr1;
        double matr2;
        double matr3;
        if (dematrix == 1) {
            matr1 = 1.5 + 0.5 * sqrt2;
            matr2 = sqrt2 * matr1;
            matr3 = 2 * matr1;
        } else {
            matr1 = 1 + sqrt2;
            matr2 = sqrt2 * matr1;
            matr3 = sqrt2 * matr1;
        }
        var f1 = (float) matr1;
        var f2 = (float) matr2;
        var f3 = (float) matr3;
        var channels = stereo + mcChannels;
        for (var n = 0; n < 32 * 36; n++) {
            for (var i = 0; i < stereo; i++) {
                samples[i][n] *= f1;
            }
            if (dematrix != 1) {
                if (surround == 3) {
                    if (center != 0) {
                        samples[2][n] *= f2;
                    }
                } else {
                    for (var l = 2; l < channels; l++) {
                        samples[l][n] *= f2;
                    }
                }
            } else if (surround == 3) {
                if (center != 0) {
                    samples[2][n] *= f2;
                }
            } else if (mcChannels == 3) {
                samples[2][n] *= f2;
                samples[3][n] *= f3;
                samples[4][n] *= f3;
            } else if (mcChannels == 2) {
                if (surround == 2) {
                    samples[2][n] *= f3;
                    samples[3][n] *= f3;
                } else {
                    samples[2][n] *= f2;
                    samples[3][n] *= f3;
                }
            } else if (mcChannels == 1) {
                samples[2][n] *= center == 0 ? f3 : f2;
            }
        }
    }

    /**
     * Returns the channels the frame outputs, in the order of the output buffer: each entry is the channel in
     * the reference order ({@code 0} left, {@code 1} right, {@code 2} to {@code 4} the transmission channels
     * after dematrixing), or {@code -1} for the low-frequency enhancement channel.
     *
     * @return the channel map
     */
    int[] outputChannels() {
        cacheOutput();
        return cachedMap;
    }

    /**
     * Recomputes {@link #cachedMap} and {@link #cachedLayout} when the channel configuration changed since they
     * were computed.
     */
    private void cacheOutput() {
        var key = stereo | center << 2 | surround << 4 | lfe << 6;
        if (key != outputKey) {
            outputKey = key;
            cachedMap = computeOutputChannels();
            cachedLayout = computeOutputLayout();
        }
    }

    /**
     * Computes the channel map of {@link #outputChannels()}.
     *
     * @return the channel map
     */
    private int[] computeOutputChannels() {
        var hasCenter = center != 0;
        var lfeChannel = lfe != 0;
        if (stereo == 1) {
            return lfeChannel ? new int[]{0, -1} : new int[]{0};
        }
        return switch (surround) {
            case 1 -> hasCenter ? channels(lfeChannel, 0, 1, 2, -2, 3) : channels(lfeChannel, 0, 1, -2, 2);
            case 2 -> hasCenter ? channels(lfeChannel, 0, 1, 2, -2, 3, 4) : channels(lfeChannel, 0, 1, -2, 2, 3);
            default -> hasCenter ? channels(lfeChannel, 0, 1, 2, -2) : channels(lfeChannel, 0, 1, -2);
        };
    }

    /**
     * Builds a channel map, replacing the placeholder {@code -2} by the low-frequency enhancement channel or
     * dropping it.
     *
     * @param lfe whether the low-frequency enhancement channel is present
     * @param order the channels, with {@code -2} where the low-frequency enhancement channel goes
     * @return the channel map
     */
    private static int[] channels(boolean lfe, int... order) {
        var out = new int[lfe ? order.length : order.length - 1];
        var n = 0;
        for (var channel : order) {
            if (channel == -2) {
                if (lfe) {
                    out[n++] = -1;
                }
            } else {
                out[n++] = channel;
            }
        }
        return out;
    }

    /**
     * Returns the output layout of the frame.
     *
     * @return the layout, in WAVE order
     */
    ChannelLayout outputLayout() {
        cacheOutput();
        return cachedLayout;
    }

    /**
     * Computes the layout of {@link #outputLayout()}.
     *
     * @return the layout
     */
    private ChannelLayout computeOutputLayout() {
        var hasCenter = center != 0;
        var lfeChannel = lfe != 0;
        if (stereo == 1) {
            return lfeChannel ? new ChannelLayout(List.of(Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY)) : ChannelLayout.MONO;
        }
        var speakers = new java.util.ArrayList<Speaker>(6);
        speakers.add(Speaker.FRONT_LEFT);
        speakers.add(Speaker.FRONT_RIGHT);
        if (hasCenter) {
            speakers.add(Speaker.FRONT_CENTER);
        }
        if (lfeChannel) {
            speakers.add(Speaker.LOW_FREQUENCY);
        }
        if (surround == 1) {
            speakers.add(Speaker.BACK_CENTER);
        } else if (surround == 2) {
            speakers.add(Speaker.BACK_LEFT);
            speakers.add(Speaker.BACK_RIGHT);
        }
        return new ChannelLayout(speakers);
    }

    /**
     * Clears the prediction history, for a new stream or after a frame that did not decode.
     */
    void reset() {
        for (var channel : history) {
            for (var line : channel) {
                Arrays.fill(line, 0);
            }
        }
    }

    /**
     * Gives the scalefactor index of a base channel's subband and block.
     */
    interface BaseScale {
        /**
         * Returns the scalefactor index of a base channel's subband and block.
         *
         * @param channel the base channel
         * @param band the subband
         * @param part the block of 12 slots
         * @return the index, 63 where the subband has no allocation
         */
        int index(int channel, int band, int part);

        /**
         * Returns the allocation index of a base channel's subband.
         *
         * @param channel the base channel
         * @param band the subband
         * @return the index, 0 where the subband is not coded
         */
        int allocation(int channel, int band);
    }
}
