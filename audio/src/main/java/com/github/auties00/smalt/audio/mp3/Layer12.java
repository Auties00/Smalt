package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.header.MpegAudioHeader;

import java.util.Arrays;

/**
 * Decodes the bit allocation, scalefactors and samples of Layer I and Layer II frames into
 * subband samples, twelve time slots at a time.
 *
 * <p>A Layer I frame holds one group of twelve slots per subband; a Layer II frame holds three.
 * Each group is handed to the synthesis filterbank as soon as it is dequantized and scaled.
 *
 * @implNote This implementation is a port of minimp3's Layer I/II decoder (CC0, the
 * {@code L12_*} functions of {@code minimp3.h}), including its allocation tables that fold
 * ISO/IEC 11172-3 Tables B.1 and B.2a to B.2d and ISO/IEC 13818-3 Table B.1 into band groups.
 */
final class Layer12 {
    /**
     * The allocation band groups of Layer I: table offset, code width and band count.
     */
    private static final int[] ALLOC_LAYER1 = {76, 4, 32};

    /**
     * The allocation band groups of MPEG-2 Layer II (ISO/IEC 13818-3 Table B.1).
     */
    private static final int[] ALLOC_LAYER2_MPEG2 = {60, 4, 4, 44, 3, 7, 44, 2, 19};

    /**
     * The allocation band groups of MPEG-1 Layer II at high rates (ISO/IEC 11172-3 Tables B.2a
     * and B.2b).
     */
    static final int[] ALLOC_LAYER2_MPEG1 = {0, 4, 3, 16, 4, 8, 32, 3, 12, 40, 2, 7};

    /**
     * The allocation band groups of MPEG-1 Layer II at low rates (ISO/IEC 11172-3 Tables B.2c
     * and B.2d).
     */
    private static final int[] ALLOC_LAYER2_MPEG1_LOW = {44, 4, 2, 44, 3, 10};

    /**
     * Holds the dequantization step of each quantizer class times each of the three scalefactor
     * fractions {@code 2^(-k/3)}, scaled to the decoder's 16-bit output range (minimp3
     * {@code g_deq_L12}).
     */
    private static final float[] DEQUANTIZATION = dequantizationTable();

    /**
     * The number of bands coded per channel.
     */
    private int totalBands;

    /**
     * The number of bands coded independently for each channel; higher bands share samples.
     */
    private int stereoBands;

    /**
     * The quantizer class of each band and channel, indexed {@code 2 * band + channel}.
     */
    private final int[] allocation = new int[64];

    /**
     * The allocation index as coded of each band and channel, indexed {@code 2 * band + channel}; a
     * band above the joint stereo bound gives both channels the coded index.
     */
    private final int[] rawAllocation = new int[64];

    /**
     * The scalefactor selection code of each band and channel.
     */
    private final int[] scfsiCodes = new int[64];

    /**
     * The three scale gains of each band and channel, six per band.
     */
    private final float[] scales = new float[3 * 64];

    /**
     * The scalefactor index behind each entry of {@link #scales}, 63 where the band has no
     * allocation.
     */
    private final int[] scaleIndices = new int[3 * 64];

    /**
     * The bit position, in the frame reader, where the bits the CRC-16 protects end: after the
     * allocation (Layer I) or after the scalefactor selection (Layer II).
     */
    private int protectedEnd;

    /**
     * Builds {@link #DEQUANTIZATION}.
     *
     * @return the table
     */
    private static float[] dequantizationTable() {
        int[] divisors = {3, 7, 15, 31, 63, 127, 255, 511, 1023, 2047, 4095, 8191, 16383, 32767, 65535, 3, 5, 9};
        var table = new float[18 * 3];
        for (var i = 0; i < divisors.length; i++) {
            table[3 * i] = 9.53674316e-07f / divisors[i];
            table[3 * i + 1] = 7.56931807e-07f / divisors[i];
            table[3 * i + 2] = 6.00777173e-07f / divisors[i];
        }
        return table;
    }

    /**
     * Reads the bit allocation and scalefactors of a frame.
     *
     * @param header the frame header
     * @param bs     the reader positioned after the header and CRC
     */
    void readScaleInfo(int header, BitReader bs) {
        var groups = allocationGroups(header);
        var codes = Mp3Tables.BIT_ALLOCATION_CODES;
        var group = 0;
        var nextGroup = 0;
        var width = 0;
        var tableOffset = 0;
        for (var i = 0; i < totalBands; i++) {
            if (i == nextGroup) {
                tableOffset = groups[group];
                width = groups[group + 1];
                nextGroup += groups[group + 2];
                group += 3;
            }
            var index = bs.read(width);
            var code = codes[tableOffset + index];
            allocation[2 * i] = code;
            rawAllocation[2 * i] = index;
            if (i < stereoBands) {
                index = bs.read(width);
                code = codes[tableOffset + index];
            }
            allocation[2 * i + 1] = stereoBands != 0 ? code : 0;
            rawAllocation[2 * i + 1] = stereoBands != 0 ? index : 0;
        }
        Arrays.fill(rawAllocation, 2 * totalBands, rawAllocation.length, 0);
        var layer1 = MpegAudioHeader.layer(header) == 1;
        for (var i = 0; i < 2 * totalBands; i++) {
            scfsiCodes[i] = allocation[i] != 0 ? (layer1 ? 2 : bs.read(2)) : 6;
        }
        protectedEnd = bs.position;
        readScalefactors(bs, 2 * totalBands);
        for (var i = stereoBands; i < totalBands; i++) {
            allocation[2 * i + 1] = 0;
        }
    }

    /**
     * Selects the allocation band groups of a frame and sets {@link #totalBands} and
     * {@link #stereoBands}.
     *
     * @param header the frame header
     * @return the groups as {@code (table offset, code width, band count)} triples
     */
    private int[] allocationGroups(int header) {
        var mode = MpegAudioHeader.mode(header);
        var stereo = mode == MpegAudioHeader.MODE_MONO ? 0
                : mode == MpegAudioHeader.MODE_JOINT_STEREO ? (MpegAudioHeader.modeExtension(header) << 2) + 4 : 32;
        var bands = bandCount(header);
        totalBands = bands;
        stereoBands = Math.min(stereo, bands);
        return bandGroups(header);
    }

    /**
     * Returns the number of subbands a frame codes: 32 for Layer I, and for Layer II the
     * {@code sblimit} of the allocation table its version, sampling rate and bitrate per channel
     * select.
     *
     * @param header the frame header
     * @return the number of coded subbands, from 8 to 32
     */
    static int bandCount(int header) {
        if (MpegAudioHeader.layer(header) == 1) {
            return 32;
        }
        if (!MpegAudioHeader.isMpeg1(header)) {
            return 30;
        }
        var kbps = perChannelKbps(header);
        if (kbps < 56) {
            return MpegAudioHeader.sampleRateIndex(header) == 2 ? 12 : 8;
        }
        return kbps >= 96 && MpegAudioHeader.sampleRateIndex(header) != 1 ? 30 : 27;
    }

    /**
     * Returns the allocation band groups of a frame.
     *
     * @param header the frame header
     * @return the groups as {@code (table offset, code width, band count)} triples, the table
     * offset indexing {@link Mp3Tables#BIT_ALLOCATION_CODES}
     */
    static int[] bandGroups(int header) {
        if (MpegAudioHeader.layer(header) == 1) {
            return ALLOC_LAYER1;
        }
        if (!MpegAudioHeader.isMpeg1(header)) {
            return ALLOC_LAYER2_MPEG2;
        }
        return perChannelKbps(header) < 56 ? ALLOC_LAYER2_MPEG1_LOW : ALLOC_LAYER2_MPEG1;
    }

    /**
     * Returns the bitrate per channel of a Layer II frame that selects its allocation table,
     * with free-format frames counted as 192 kbit/s.
     *
     * @param header the frame header
     * @return the bitrate in kilobits per second
     */
    private static int perChannelKbps(int header) {
        var kbps = MpegAudioHeader.bitrateKbps(header) >> (MpegAudioHeader.mode(header) != MpegAudioHeader.MODE_MONO ? 1 : 0);
        return kbps == 0 ? 192 : kbps;
    }

    /**
     * Reads the scalefactors of every band and channel with a non-zero allocation.
     *
     * @param bs    the reader
     * @param bands the number of band-channel pairs
     */
    private void readScalefactors(BitReader bs, int bands) {
        var out = 0;
        for (var i = 0; i < bands; i++) {
            var s = 0.0f;
            var index = 63;
            var code = allocation[i];
            var mask = code != 0 ? 4 + ((19 >> scfsiCodes[i]) & 3) : 0;
            for (var m = 4; m != 0; m >>= 1) {
                if ((mask & m) != 0) {
                    var b = bs.read(6);
                    s = DEQUANTIZATION[code * 3 - 6 + b % 3] * (float) ((1 << 21) >> (b / 3));
                    index = b;
                }
                scaleIndices[out] = index;
                scales[out++] = s;
            }
        }
    }

    /**
     * Dequantizes one group of samples of every band and channel.
     *
     * @param grbuf     the granule buffer
     * @param offset    the index of the group's first time slot
     * @param bs        the reader
     * @param groupSize the samples per band per group: 1 for Layer I, 3 for Layer II
     * @return the number of time slots produced, {@code 4 * groupSize}
     */
    int dequantizeGranule(float[] grbuf, int offset, BitReader bs, int groupSize) {
        for (var j = 0; j < 4; j++) {
            var dst = offset + groupSize * j;
            var channelOffset = 576;
            for (var i = 0; i < 2 * totalBands; i++) {
                var code = allocation[i];
                if (code != 0) {
                    if (code < 17) {
                        var half = (1 << (code - 1)) - 1;
                        for (var k = 0; k < groupSize; k++) {
                            grbuf[dst + k] = bs.read(code) - half;
                        }
                    } else {
                        var modulus = (2 << (code - 17)) + 1;
                        var value = bs.read(modulus + 2 - (modulus >> 3));
                        for (var k = 0; k < groupSize; k++, value /= modulus) {
                            grbuf[dst + k] = value % modulus - modulus / 2;
                        }
                    }
                }
                dst += channelOffset;
                channelOffset = 18 - channelOffset;
            }
        }
        return groupSize * 4;
    }

    /**
     * Scales twelve dequantized time slots of every band by their scalefactors, and copies the
     * shared bands of joint-stereo frames to the second channel.
     *
     * @param grbuf the granule buffer
     * @param part  the index of the scalefactor (0 to 2) applying to these slots
     */
    void applyScalefactors(float[] grbuf, int part) {
        System.arraycopy(grbuf, stereoBands * 18, grbuf, 576 + stereoBands * 18, (totalBands - stereoBands) * 18);
        var scf = part;
        for (var i = 0; i < totalBands; i++, scf += 6) {
            var dst = i * 18;
            var left = scales[scf];
            var right = scales[scf + 3];
            for (var k = 0; k < 12; k++) {
                grbuf[dst + k] *= left;
                grbuf[dst + k + 576] *= right;
            }
        }
    }

    /**
     * Returns the allocation index of a band and channel of the frame read last, as the bit allocation
     * table numbers it.
     *
     * @param band the subband
     * @param channel the channel, 0 or 1
     * @return the index, 0 where the band is not coded
     */
    int allocationIndex(int band, int channel) {
        return rawAllocation[2 * band + channel];
    }

    /**
     * Returns the scalefactor index of a band, channel and block of the frame read last.
     *
     * @param band the subband
     * @param channel the channel, 0 or 1
     * @param part the block of twelve slots, 0 to 2
     * @return the index, 63 where the band of the channel has no allocation
     */
    int scaleIndex(int band, int channel, int part) {
        return scaleIndices[6 * band + 3 * channel + part];
    }

    /**
     * {@return the bit position, in the reader passed to {@link #readScaleInfo}, where the bits the
     * CRC-16 of the frame protects end}
     */
    int protectedEnd() {
        return protectedEnd;
    }

    /**
     * Clears the allocation state, used when a frame is abandoned.
     */
    void reset() {
        Arrays.fill(allocation, 0);
    }
}
