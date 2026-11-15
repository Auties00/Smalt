/* Ports the Layer I and II coding of the ISO/IEC 13818-5 MPEG-2 audio reference software (dist10,
 * lsf/encoder/encode.c: scalefactors, scalefactor selection, bit allocation, quantization and bitstream formatting).
 * Copyright ISO MPEG Audio Subgroup Software Simulation Group; ISO/IEC 13818-5 reference software licence.
 * Modified for Smalt. */
package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.header.MpegAudioHeader;

import java.util.Arrays;

/**
 * Codes frames of planar samples into MPEG-1 and MPEG-2 Layer I and Layer II frames.
 *
 * <p>Every frame is coded on its own, at the bitrate of the options: the polyphase filterbank splits each
 * channel into 32 subbands, every block of 12 samples of a subband gets a scalefactor (Layer II sends one,
 * two or three of the frame's three, as their differences allow), the psychoacoustic model gives each
 * subband's signal-to-mask ratio, and the bits of the frame go one quantizer step at a time to the subband
 * whose noise is least masked. Instances are not thread-safe.
 */
final class Mp2FrameEncoder {
    /**
     * The scalefactor multipliers {@code 2^(1 - i / 3)}; entry 63 stands for zero.
     */
    static final double[] MULTIPLE = new double[64];

    /**
     * The signal-to-noise ratio of each Layer II quantizer, in decibels, by quantizer index plus one; entry 0
     * is no allocation (ISO/IEC 11172-3 Table C.5).
     */
    private static final double[] SNR = {0.00, 7.00, 11.00, 16.00, 20.84, 25.28, 31.59, 37.75, 43.84, 49.89,
            55.93, 61.96, 67.98, 74.01, 80.03, 86.05, 92.01, 98.01};

    /**
     * The signal-to-noise ratio of each Layer I allocation, in decibels.
     */
    private static final double[] SNR_LAYER1 = {0.00, 7.00, 16.00, 25.28, 31.59, 37.75, 43.84, 49.89, 55.93, 61.96,
            67.98, 74.01, 80.03, 86.05, 92.01, 98.01};

    /**
     * The quantizer scale of each Layer II quantizer index ({@code a} of ISO/IEC 11172-3 Table C.6).
     */
    private static final double[] A = {0.750000000, 0.625000000, 0.875000000, 0.562500000, 0.937500000,
            0.968750000, 0.984375000, 0.992187500, 0.996093750, 0.998046875, 0.999023438, 0.999511719, 0.999755859,
            0.999877930, 0.999938965, 0.999969482, 0.999984741};

    /**
     * The quantizer offset of each Layer II quantizer index ({@code b} of ISO/IEC 11172-3 Table C.6).
     */
    private static final double[] B = {-0.250000000, -0.375000000, -0.125000000, -0.437500000, -0.062500000,
            -0.031250000, -0.015625000, -0.007812500, -0.003906250, -0.001953125, -0.000976563, -0.000488281,
            -0.000244141, -0.000122070, -0.000061035, -0.000030518, -0.000015259};

    /**
     * The number of scalefactors each Layer II scalefactor selection code sends.
     */
    private static final int[] SCALEFACTORS_PER_SCFSI = {3, 2, 1, 2};

    /**
     * The Layer II scalefactor selection by the classes of the two scalefactor differences of a subband,
     * as the transmission pattern of ISO/IEC 11172-3 Table C.4.
     */
    private static final int[][] PATTERN = {
            {0x123, 0x122, 0x122, 0x133, 0x123},
            {0x113, 0x111, 0x111, 0x444, 0x113},
            {0x111, 0x111, 0x111, 0x333, 0x113},
            {0x222, 0x222, 0x222, 0x333, 0x123},
            {0x123, 0x122, 0x122, 0x133, 0x123}};

    static {
        for (var i = 0; i < 63; i++) {
            MULTIPLE[i] = StrictMath.pow(2.0, 1.0 - i / 3.0);
        }
        MULTIPLE[63] = 1e-20;
    }

    /**
     * Whether the frames are Layer I.
     */
    private final boolean layer1;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * Whether joint stereo coding may be chosen per frame.
     */
    private final boolean joint;

    /**
     * Whether frames carry a CRC-16.
     */
    private final boolean crc;

    /**
     * The frame header without the padding, mode extension and protection bits.
     */
    private final int header;

    /**
     * The number of samples per channel of a frame.
     */
    private final int frameSamples;

    /**
     * The number of coded subbands.
     */
    private final int bands;

    /**
     * The allocation field width of each subband.
     */
    private final int[] width = new int[32];

    /**
     * The offset into {@link Mp3Tables#BIT_ALLOCATION_CODES} of each subband's allocation codes.
     */
    private final int[] codes = new int[32];

    /**
     * The bitrate per channel in kilobits per second, for the psychoacoustic model.
     */
    private final int kbpsPerChannel;

    /**
     * The bits of a slot, 32 for Layer I and 8 for Layer II.
     */
    private final int slotBits;

    /**
     * The whole slots of every frame.
     */
    private final int wholeSlots;

    /**
     * The fraction of a slot each frame adds, as a numerator of {@link #slotDenominator}.
     */
    private final long slotNumerator;

    /**
     * The denominator of the slot fraction.
     */
    private final long slotDenominator;

    /**
     * The fractions of a slot accumulated, from 0 to {@link #slotDenominator}.
     */
    private long slotLag;

    /**
     * The analysis filterbank of each channel.
     */
    private final Mp3AnalysisFilterbank[] filterbanks;

    /**
     * The psychoacoustic model.
     */
    private final Mp2Psychoacoustics psychoacoustics;

    /**
     * The subband samples of each channel, subband {@code k}, slot {@code t} at {@code k * 36 + t}.
     */
    private final float[][] samples;

    /**
     * The mean of the two channels' subband samples, for joint stereo.
     */
    private final float[] jointSamples = new float[32 * 36];

    /**
     * The scalefactor index of each channel, block and subband, at {@code block * 32 + subband}.
     */
    private final int[][] scalefactors;

    /**
     * The scalefactor index of the joint stereo signal of each block and subband.
     */
    private final int[] jointScalefactors = new int[3 * 32];

    /**
     * The scalefactor selection code of each channel and subband.
     */
    private final int[][] scfsi;

    /**
     * The allocation index of each channel and subband.
     */
    private final int[][] allocation;

    /**
     * The largest scale of each channel and subband, for the psychoacoustic model.
     */
    private final double[][] scale;

    /**
     * The signal-to-mask ratio of each channel and subband, in decibels.
     */
    private final double[][] smr;

    /**
     * The mask-to-noise ratio of each channel and subband during the allocation, in decibels.
     */
    private final double[][] mnr;

    /**
     * The allocation state of each channel and subband: 0 no bits, 1 bits, 2 no further step.
     */
    private final byte[][] used;

    /**
     * The first subband coded as intensity stereo, or {@link #bands} when none is.
     */
    private int jointBound;

    /**
     * The mode extension of the frame being coded.
     */
    private int modeExtension;

    /**
     * The writer of the frame being coded.
     */
    private final MsbBitWriter writer = new MsbBitWriter(8192);

    /**
     * Constructs the frame encoder of a configuration.
     *
     * @param options the settings
     */
    Mp2FrameEncoder(Mp2EncoderOptions options) {
        this.layer1 = options.layer() == 1;
        this.channels = options.layout().channels();
        this.joint = channels == 2 && options.stereoMode() == Mp3StereoMode.JOINT_STEREO;
        this.crc = options.crc();
        this.frameSamples = layer1 ? 384 : 1152;
        var rate = options.sampleRate();
        var mpeg1 = Mp2EncoderOptions.isMpeg1(rate);
        var kbps = options.effectiveBitrate() / 1000;
        var table = Mp2EncoderOptions.bitrates(mpeg1, options.layer());
        var bitrateIndex = 0;
        for (var i = 1; i < table.length; i++) {
            if (table[i] == kbps) {
                bitrateIndex = i;
            }
        }
        var tableRate = rateIndex(rate);
        var mode = channels == 1 ? MpegAudioHeader.MODE_MONO
                : switch (options.stereoMode()) {
                    case STEREO -> 0;
                    case JOINT_STEREO -> MpegAudioHeader.MODE_JOINT_STEREO;
                    case DUAL_CHANNEL -> 2;
                };
        this.header = 0xFFF00000 | (mpeg1 ? 1 : 0) << 19 | (4 - options.layer()) << 17 | 1 << 16 | bitrateIndex << 12
                | (tableRate % 3) << 10 | mode << 6;
        this.bands = Layer12.bandCount(header);
        var groups = Layer12.bandGroups(header);
        for (int band = 0, group = 0, nextGroup = 0, groupWidth = 0, offset = 0; band < bands; band++) {
            if (band == nextGroup) {
                offset = groups[group];
                groupWidth = groups[group + 1];
                nextGroup += groups[group + 2];
                group += 3;
            }
            width[band] = groupWidth;
            codes[band] = offset;
        }
        this.kbpsPerChannel = kbps / channels;
        this.slotBits = layer1 ? 32 : 8;
        var numerator = (long) frameSamples * kbps * 1000;
        this.slotDenominator = (long) slotBits * rate;
        this.wholeSlots = (int) (numerator / slotDenominator);
        this.slotNumerator = numerator % slotDenominator;
        this.filterbanks = new Mp3AnalysisFilterbank[channels];
        for (var ch = 0; ch < channels; ch++) {
            filterbanks[ch] = new Mp3AnalysisFilterbank(Mp3EncoderKernel.create());
        }
        this.psychoacoustics = new Mp2Psychoacoustics(options.layer(), tableRate, channels);
        this.samples = new float[channels][32 * 36];
        this.scalefactors = new int[channels][3 * 32];
        this.scfsi = new int[channels][32];
        this.allocation = new int[channels][32];
        this.scale = new double[channels][32];
        this.smr = new double[channels][32];
        this.mnr = new double[channels][32];
        this.used = new byte[channels][32];
    }

    /**
     * Returns the index of a sampling rate in the tables of the psychoacoustic model.
     *
     * @param sampleRate the rate in hertz
     * @return 0 for 44.1 kHz, 1 for 48 kHz, 2 for 32 kHz, 3 for 22.05 kHz, 4 for 24 kHz, 5 for 16 kHz, or
     * {@code -1} for a rate Layers I and II do not code
     */
    static int rateIndex(int sampleRate) {
        return switch (sampleRate) {
            case 44_100 -> 0;
            case 48_000 -> 1;
            case 32_000 -> 2;
            case 22_050 -> 3;
            case 24_000 -> 4;
            case 16_000 -> 5;
            default -> -1;
        };
    }

    /**
     * {@return the number of samples per channel of a frame, 384 or 1152}
     */
    int frameSamples() {
        return frameSamples;
    }

    /**
     * Codes one frame.
     *
     * @param pcm the frame's samples, one plane per channel, {@link #frameSamples()} each, in {@code [-1, 1]}
     * @return the frame, header included
     */
    byte[] encode(float[][] pcm) {
        var padding = false;
        slotLag += slotNumerator;
        if (slotLag >= slotDenominator) {
            slotLag -= slotDenominator;
            padding = true;
        }
        var frameBits = (wholeSlots + (padding ? 1 : 0)) * slotBits;
        var blocks = layer1 ? 1 : 3;
        for (var ch = 0; ch < channels; ch++) {
            for (var block = 0; block < blocks; block++) {
                filterbanks[ch].process(pcm[ch], block * 384, 12, samples[ch], block * 12, 36);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            computeScalefactors(samples[ch], scalefactors[ch], blocks);
            for (var band = 0; band < 32; band++) {
                var smallest = scalefactors[ch][band];
                for (var block = 1; block < blocks; block++) {
                    smallest = Math.min(smallest, scalefactors[ch][block * 32 + band]);
                }
                scale[ch][band] = band < bands ? MULTIPLE[smallest] : 1e-20;
            }
        }
        if (joint) {
            var left = samples[0];
            var right = samples[1];
            for (var i = 0; i < left.length; i++) {
                jointSamples[i] = 0.5f * (left[i] + right[i]);
            }
            computeScalefactors(jointSamples, jointScalefactors, blocks);
        }
        for (var ch = 0; ch < channels; ch++) {
            psychoacoustics.analyze(ch, pcm[ch], scale[ch], bands, kbpsPerChannel, smr[ch]);
        }
        if (!layer1) {
            for (var ch = 0; ch < channels; ch++) {
                transmissionPattern(scalefactors[ch], scfsi[ch]);
            }
        }
        chooseBound(frameBits);
        allocate(frameBits);
        return write(padding, frameBits);
    }

    /**
     * Chooses each subband block's scalefactor: the index of the smallest multiplier not below the
     * block's peak.
     *
     * @param signal the subband samples
     * @param out receives the scalefactor indices
     * @param blocks the number of blocks of 12 samples
     */
    private void computeScalefactors(float[] signal, int[] out, int blocks) {
        for (var block = 0; block < blocks; block++) {
            for (var band = 0; band < 32; band++) {
                if (band >= bands) {
                    out[block * 32 + band] = 63;
                    continue;
                }
                var base = band * 36 + block * 12;
                var peak = 0.0;
                for (var t = 0; t < 12; t++) {
                    peak = Math.max(peak, Math.abs(signal[base + t]));
                }
                var index = 0;
                for (var j = 62; j >= 0; j--) {
                    if (peak <= MULTIPLE[j]) {
                        index = j;
                        break;
                    }
                }
                out[block * 32 + band] = index;
            }
        }
    }

    /**
     * Chooses which of a Layer II subband's three scalefactors are sent and merges the others
     * ({@code II_transmission_pattern}).
     *
     * @param scf the scalefactor indices of the channel, rewritten to the ones the decoder will use
     * @param select receives the scalefactor selection code of each subband
     */
    private void transmissionPattern(int[] scf, int[] select) {
        for (var band = 0; band < bands; band++) {
            var first = scf[band];
            var second = scf[32 + band];
            var third = scf[64 + band];
            var pattern = PATTERN[differenceClass(first - second)][differenceClass(second - third)];
            switch (pattern) {
                case 0x123 -> select[band] = 0;
                case 0x122 -> {
                    select[band] = 3;
                    scf[64 + band] = second;
                }
                case 0x133 -> {
                    select[band] = 3;
                    scf[32 + band] = third;
                }
                case 0x113 -> {
                    select[band] = 1;
                    scf[32 + band] = first;
                }
                case 0x111 -> {
                    select[band] = 2;
                    scf[32 + band] = first;
                    scf[64 + band] = first;
                }
                case 0x222 -> {
                    select[band] = 2;
                    scf[band] = second;
                    scf[64 + band] = second;
                }
                case 0x333 -> {
                    select[band] = 2;
                    scf[band] = third;
                    scf[32 + band] = third;
                }
                default -> {
                    select[band] = 2;
                    var kept = Math.min(first, third);
                    scf[band] = kept;
                    scf[32 + band] = kept;
                    scf[64 + band] = kept;
                }
            }
        }
    }

    /**
     * Returns the class of a scalefactor difference for the transmission pattern.
     *
     * @param difference the difference of two consecutive scalefactor indices
     * @return the class, from 0 to 4
     */
    private static int differenceClass(int difference) {
        if (difference <= -3) {
            return 0;
        }
        if (difference < 0) {
            return 1;
        }
        if (difference == 0) {
            return 2;
        }
        return difference < 3 ? 3 : 4;
    }

    /**
     * Chooses the joint stereo bound of the frame: the highest bound at which the bits keep every
     * subband's noise masked, or the lowest bound when none does ({@code *_main_bit_allocation}).
     *
     * @implNote This implementation signals every frame of a joint stereo stream as joint stereo, where
     * the reference encoder signals the frames that need no intensity stereo as plain stereo: decoders
     * that synchronize on the channel mode of consecutive headers then never take a mode switch for
     * a lost frame.
     *
     * @param frameBits the bits of the frame
     */
    private void chooseBound(int frameBits) {
        jointBound = layer1 ? 32 : bands;
        modeExtension = 0;
        if (!joint) {
            return;
        }
        for (var extension = 3; extension >= 0; extension--) {
            modeExtension = extension;
            jointBound = 4 * (extension + 1);
            if (bitsForNoNoise() <= frameBits) {
                return;
            }
        }
    }

    /**
     * Returns the bits a frame needs for every subband's mask-to-noise ratio to reach zero at the current
     * joint stereo bound ({@code *_bits_for_nonoise}).
     *
     * @return the bits
     */
    private int bitsForNoNoise() {
        var bits = 32 + (crc ? 16 : 0);
        if (layer1) {
            bits += 4 * (jointBound * channels + (32 - jointBound));
            for (var band = 0; band < 32; band++) {
                for (var ch = 0; ch < (band < jointBound ? channels : 1); ch++) {
                    var step = 0;
                    while (step < 14 && -smr[ch][band] + SNR_LAYER1[step] < 0) {
                        step++;
                    }
                    if (channels == 2 && band >= jointBound) {
                        while (step < 14 && -smr[1 - ch][band] + SNR_LAYER1[step] < 0) {
                            step++;
                        }
                    }
                    if (step > 0) {
                        bits += (step + 1) * 12 + 6 * (band >= jointBound ? channels : 1);
                    }
                }
            }
            return bits;
        }
        for (var band = 0; band < bands; band++) {
            bits += (band < jointBound ? channels : 1) * width[band];
        }
        for (var band = 0; band < bands; band++) {
            var maximum = (1 << width[band]) - 1;
            for (var ch = 0; ch < (band < jointBound ? channels : 1); ch++) {
                var step = 0;
                while (step < maximum - 1 && -smr[ch][band] + stepSnr(band, step) < 0) {
                    step++;
                }
                if (channels == 2 && band >= jointBound) {
                    while (step < maximum - 1 && -smr[1 - ch][band] + stepSnr(band, step) < 0) {
                        step++;
                    }
                }
                if (step > 0) {
                    bits += 12 * groupBits(band, step) + 2 + 6 * SCALEFACTORS_PER_SCFSI[scfsi[ch][band]];
                    if (channels == 2 && band >= jointBound) {
                        bits += 2 + 6 * SCALEFACTORS_PER_SCFSI[scfsi[1 - ch][band]];
                    }
                }
            }
        }
        return bits;
    }

    /**
     * Allocates the bits of the frame one quantizer step at a time to the subband and channel with the
     * lowest mask-to-noise ratio, until no step fits ({@code *_a_bit_allocation}).
     *
     * @param frameBits the bits of the frame
     */
    private void allocate(int frameBits) {
        var available = frameBits - 32 - (crc ? 16 : 0);
        var coded = layer1 ? 32 : bands;
        for (var band = 0; band < coded; band++) {
            available -= (band < jointBound ? channels : 1) * (layer1 ? 4 : width[band]);
        }
        for (var ch = 0; ch < channels; ch++) {
            Arrays.fill(allocation[ch], 0);
            Arrays.fill(used[ch], (byte) 0);
            for (var band = 0; band < coded; band++) {
                mnr[ch][band] = SNR[0] - smr[ch][band];
            }
        }
        var spent = 0;
        while (true) {
            var smallest = Double.MAX_VALUE;
            var band = -1;
            var ch = -1;
            for (var b = 0; b < coded; b++) {
                for (var c = 0; c < channels; c++) {
                    if (used[c][b] != 2 && smallest > mnr[c][b]) {
                        smallest = mnr[c][b];
                        band = b;
                        ch = c;
                    }
                }
            }
            if (band < 0) {
                break;
            }
            var other = 1 - ch;
            var shared = channels == 2 && band >= jointBound;
            int increment;
            int side;
            if (layer1) {
                increment = used[ch][band] != 0 ? 12 : 24;
                side = used[ch][band] != 0 ? 0 : 6 * (shared ? 2 : 1);
            } else {
                var step = allocation[ch][band];
                increment = 12 * groupBits(band, step + 1) - (used[ch][band] != 0 ? 12 * groupBits(band, step) : 0);
                side = 0;
                if (used[ch][band] == 0) {
                    side = 2 + 6 * SCALEFACTORS_PER_SCFSI[scfsi[ch][band]];
                    if (shared) {
                        side += 2 + 6 * SCALEFACTORS_PER_SCFSI[scfsi[other][band]];
                    }
                }
            }
            if (available >= spent + side + increment) {
                spent += side + increment;
                var step = ++allocation[ch][band];
                used[ch][band] = 1;
                mnr[ch][band] = -smr[ch][band] + (layer1 ? SNR_LAYER1[step] : SNR[quantizer(band, step) + 1]);
                if (step >= (layer1 ? 14 : (1 << width[band]) - 1)) {
                    used[ch][band] = 2;
                }
            } else {
                used[ch][band] = 2;
            }
            if (shared) {
                var step = allocation[other][band] = allocation[ch][band];
                used[other][band] = used[ch][band];
                mnr[other][band] = -smr[other][band]
                        + (layer1 ? SNR_LAYER1[step] : step == 0 ? SNR[0] : SNR[quantizer(band, step) + 1]);
            }
        }
    }

    /**
     * Returns the signal-to-noise ratio a Layer II allocation step gives, the first step counting as none.
     *
     * @param band the subband
     * @param step the allocation index
     * @return the ratio in decibels
     */
    private double stepSnr(int band, int step) {
        return step == 0 ? SNR[0] : SNR[quantizer(band, step) + 1];
    }

    /**
     * Returns the quantizer class of a Layer II allocation index.
     *
     * @param band the subband
     * @param step the allocation index, at least 1
     * @return the class: 2 to 16 for a quantizer of {@code 2^class - 1} levels coded in {@code class} bits,
     * 17, 18 and 19 for the grouped quantizers of 3, 5 and 9 levels
     */
    private int quantizerClass(int band, int step) {
        return Mp3Tables.BIT_ALLOCATION_CODES[codes[band] + step];
    }

    /**
     * Returns the quantizer index of a Layer II allocation index into {@link #A}, {@link #B} and
     * {@link #SNR}: 0 to 3 for 3, 5, 7 and 9 levels, and the bit count from 15 levels on.
     *
     * @param band the subband
     * @param step the allocation index, at least 1
     * @return the index
     */
    private int quantizer(int band, int step) {
        var type = quantizerClass(band, step);
        return switch (type) {
            case 17 -> 0;
            case 18 -> 1;
            case 3 -> 2;
            case 19 -> 3;
            default -> type;
        };
    }

    /**
     * Returns the bits of one sample triple of a Layer II allocation index.
     *
     * @param band the subband
     * @param step the allocation index; 0 gives 0
     * @return the bits: one grouped codeword, or three samples
     */
    private int groupBits(int band, int step) {
        if (step == 0) {
            return 0;
        }
        var type = quantizerClass(band, step);
        return switch (type) {
            case 17 -> 5;
            case 18 -> 7;
            case 19 -> 10;
            default -> 3 * type;
        };
    }

    /**
     * Writes the frame: header, CRC, allocation, scalefactor selection, scalefactors, samples, and zeros up to
     * the frame size.
     *
     * @param padding whether the frame carries the padding slot
     * @param frameBits the bits of the frame
     * @return the frame
     */
    private byte[] write(boolean padding, int frameBits) {
        var out = writer;
        out.reset();
        var frameHeader = header & ~(1 << 16) | (padding ? 1 : 0) << 9 | (crc ? 0 : 1) << 16 | modeExtension << 4;
        out.write(frameHeader >>> 16, 16);
        out.write(frameHeader & 0xFFFF, 16);
        if (crc) {
            out.write(0, 16);
        }
        var coded = layer1 ? 32 : bands;
        var bound = Math.min(jointBound, coded);
        for (var band = 0; band < coded; band++) {
            for (var ch = 0; ch < (band < bound ? channels : 1); ch++) {
                out.write(allocation[ch][band], layer1 ? 4 : width[band]);
            }
        }
        if (!layer1) {
            for (var band = 0; band < bands; band++) {
                for (var ch = 0; ch < channels; ch++) {
                    if (allocation[ch][band] != 0) {
                        out.write(scfsi[ch][band], 2);
                    }
                }
            }
        }
        var protectedBits = (int) out.bitCount() - 48;
        for (var band = 0; band < coded; band++) {
            for (var ch = 0; ch < channels; ch++) {
                if (allocation[ch][band] == 0) {
                    continue;
                }
                var scf = scalefactors[ch];
                if (layer1) {
                    out.write(scf[band], 6);
                    continue;
                }
                switch (scfsi[ch][band]) {
                    case 0 -> {
                        out.write(scf[band], 6);
                        out.write(scf[32 + band], 6);
                        out.write(scf[64 + band], 6);
                    }
                    case 1, 3 -> {
                        out.write(scf[band], 6);
                        out.write(scf[64 + band], 6);
                    }
                    default -> out.write(scf[band], 6);
                }
            }
        }
        writeSamples(out, coded, bound);
        while (out.bitCount() < frameBits) {
            var gap = (int) Math.min(24, frameBits - out.bitCount());
            out.write(0, gap);
        }
        var frame = Arrays.copyOf(out.array(), frameBits / 8);
        if (crc) {
            var check = MpegAudioCrc.frame(frame, 0, protectedBits);
            frame[4] = (byte) (check >> 8);
            frame[5] = (byte) check;
        }
        return frame;
    }

    /**
     * Quantizes and writes the samples of the frame.
     *
     * @param out the writer
     * @param coded the number of coded subbands
     * @param bound the first subband coded as intensity stereo
     */
    private void writeSamples(MsbBitWriter out, int coded, int bound) {
        if (layer1) {
            for (var t = 0; t < 12; t++) {
                for (var band = 0; band < 32; band++) {
                    for (var ch = 0; ch < (band < bound ? channels : 1); ch++) {
                        var step = allocation[ch][band];
                        if (step != 0) {
                            var bits = step + 1;
                            var value = quantizeLayer1(sample(ch, band, 0, t, band >= bound), step);
                            out.write(value, bits);
                        }
                    }
                }
            }
            return;
        }
        for (var block = 0; block < 3; block++) {
            for (var t = 0; t < 12; t += 3) {
                for (var band = 0; band < coded; band++) {
                    for (var ch = 0; ch < (band < bound ? channels : 1); ch++) {
                        var step = allocation[ch][band];
                        if (step == 0) {
                            continue;
                        }
                        var type = quantizerClass(band, step);
                        var q = quantizer(band, step);
                        var shared = channels == 2 && band >= bound;
                        var s0 = quantize(sample(ch, band, block, t, shared), q);
                        var s1 = quantize(sample(ch, band, block, t + 1, shared), q);
                        var s2 = quantize(sample(ch, band, block, t + 2, shared), q);
                        if (type >= 17) {
                            var levels = type == 17 ? 3 : type == 18 ? 5 : 9;
                            out.write(s0 + s1 * levels + s2 * levels * levels, groupBits(band, step));
                        } else {
                            out.write(s0, type);
                            out.write(s1, type);
                            out.write(s2, type);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns a subband sample divided by its scalefactor.
     *
     * @param ch the channel
     * @param band the subband
     * @param block the block of 12 samples
     * @param t the sample in the block
     * @param shared whether the subband is coded as intensity stereo, so the joint signal and its own
     * scalefactor are used
     * @return the normalized sample, in {@code [-1, 1]}
     */
    private double sample(int ch, int band, int block, int t, boolean shared) {
        var index = band * 36 + block * 12 + t;
        if (shared) {
            return jointSamples[index] / MULTIPLE[jointScalefactors[block * 32 + band]];
        }
        return samples[ch][index] / MULTIPLE[scalefactors[ch][block * 32 + band]];
    }

    /**
     * Quantizes a normalized sample with a Layer II quantizer ({@code II_subband_quantization}).
     *
     * @param x the normalized sample
     * @param q the quantizer index
     * @return the code, from 0 to the number of levels less one
     */
    private static int quantize(double x, int q) {
        var levels = q == 0 ? 3 : q == 1 ? 5 : q == 2 ? 7 : q == 3 ? 9 : (1 << q) - 1;
        var n = 32 - Integer.numberOfLeadingZeros(levels - 1) - 1;
        return code(x * A[q] + B[q], n, levels);
    }

    /**
     * Quantizes a normalized sample with a Layer I quantizer of {@code 2^(step + 1) - 1} levels.
     *
     * @param x the normalized sample
     * @param step the allocation index, from 1 to 14
     * @return the code
     */
    private static int quantizeLayer1(double x, int step) {
        var q = step == 1 ? 0 : step == 2 ? 2 : step + 1;
        return code(x * A[q] + B[q], step, (2 << step) - 1);
    }

    /**
     * Returns the code of a scaled sample: its {@code n} leading fraction bits with the sign bit inverted on
     * top, kept below the number of levels.
     *
     * @param d the scaled sample, in {@code [-1, 1]}
     * @param n the number of bits below the sign
     * @param levels the number of levels of the quantizer
     * @return the code, from 0 to {@code levels - 1}
     */
    private static int code(double d, int n, int levels) {
        var positive = d >= 0;
        if (!positive) {
            d += 1.0;
        }
        var value = (int) (d * (1L << n));
        if (positive) {
            return Math.clamp(value, 0, levels - 1 - (1 << n)) | 1 << n;
        }
        return Math.clamp(value, 0, (1 << n) - 1);
    }
}
