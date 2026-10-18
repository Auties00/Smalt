package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.header.MpegAudioHeader;

import java.util.Arrays;

/**
 * Decodes the Layer III frames of one stream: side information, the bit reservoir, scalefactors,
 * Huffman-coded spectra, requantization, stereo processing, reordering, alias reduction and the
 * hybrid IMDCT, leaving subband samples for the synthesis filterbank.
 *
 * <p>The instance carries the state that spans frames: the bit reservoir (up to 511 bytes of
 * main data from earlier frames) and the IMDCT overlap of each channel.
 *
 * @implNote This implementation is a port of minimp3's Layer III decoder (CC0, the
 * {@code L3_*} functions of {@code minimp3.h}). Two deliberate divergences: the private bits that
 * precede the MPEG-1 {@code scfsi} flags are masked off (minimp3 lets them leak into the
 * first granule's flags), and stereo processing is applied only to two-channel frames.
 */
final class Layer3 {
    /**
     * The largest reservoir carried between frames, in bytes (ISO/IEC 11172-3 2.4.3.4.2).
     */
    static final int MAX_RESERVOIR = 511;

    /**
     * The largest frame payload accepted, in bytes; covers every standard and free-format frame.
     */
    static final int MAX_FRAME_PAYLOAD = 8192;

    /**
     * The zero padding after the main data, which absorbs the look-ahead of the Huffman decoder
     * on corrupt granules (at most 288 pairs of 47 bits each past the granule start).
     */
    private static final int MAIN_DATA_SLACK = 2048;

    /**
     * The largest scalefactor exponent, in quarter powers of two ({@code MAX_SCFI} of minimp3).
     */
    private static final int MAX_SCFI = 44;

    /**
     * The side information of up to two granules of two channels, indexed
     * {@code granule * channels + channel}.
     */
    final GranuleInfo[] granules = {new GranuleInfo(), new GranuleInfo(), new GranuleInfo(), new GranuleInfo()};

    /**
     * The main data of the current frame: reservoir bytes followed by the frame's own payload.
     */
    private final byte[] mainData = new byte[MAX_RESERVOIR + MAX_FRAME_PAYLOAD + MAIN_DATA_SLACK];

    /**
     * The reader over {@link #mainData}.
     */
    private final BitReader bits = new BitReader();

    /**
     * The kernel that runs the long-block IMDCT.
     */
    private final SynthesisKernel kernel;

    /**
     * The reservoir bytes saved from earlier frames.
     */
    private final byte[] reservoir = new byte[MAX_RESERVOIR];

    /**
     * The number of valid bytes in {@link #reservoir}.
     */
    private int reservoirLength;

    /**
     * The IMDCT overlap of each channel: 9 values for each of the 32 subbands.
     */
    private final float[][] overlap = new float[2][9 * 32];

    /**
     * The scalefactor gains of the current granule and channel.
     */
    private final float[] scalefactors = new float[40];

    /**
     * The integer scalefactors of the current granule and channel.
     */
    private final int[] integerScalefactors = new int[40];

    /**
     * The scalefactor lengths of the four band groups.
     */
    private final int[] scalefactorSizes = new int[4];

    /**
     * The scalefactors of each channel kept for {@code scfsi} reuse and intensity positions, where
     * 255 marks an illegal intensity position.
     */
    private final int[][] intensityPositions = new int[2][40];

    /**
     * The highest non-zero band of each short window, or of the whole granule.
     */
    private final int[] maxBand = new int[3];

    /**
     * The scratch area of the short-block reordering.
     */
    private final float[] reorderScratch = new float[576];

    /**
     * The scratch area of the 9-point DCTs of the long IMDCT.
     */
    private final float[] dctScratch = new float[18];

    /**
     * Creates a decoder.
     *
     * @param kernel the kernel that runs the long-block IMDCT
     */
    Layer3(SynthesisKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * The scratch area of the short IMDCT.
     */
    private final float[] shortScratch = new float[18];

    /**
     * Forgets the reservoir and the IMDCT overlap, as after a discontinuity.
     */
    void reset() {
        reservoirLength = 0;
        Arrays.fill(overlap[0], 0);
        Arrays.fill(overlap[1], 0);
    }

    /**
     * Reads the side information of a frame into {@link #granules}.
     *
     * @param bs     the reader positioned after the header and CRC
     * @param header the frame header
     * @return the {@code main_data_begin} back-pointer, or {@code -1} when the side information
     *         is inconsistent
     */
    int readSideInfo(BitReader bs, int header) {
        var srIndex = MpegAudioHeader.sampleRateClass(header);
        if (srIndex != 0) {
            srIndex--;
        }
        var mono = MpegAudioHeader.isMono(header);
        var mpeg1 = MpegAudioHeader.isMpeg1(header);
        var count = mono ? 1 : 2;
        int mainDataBegin;
        var scfsi = 0;
        if (mpeg1) {
            count *= 2;
            mainDataBegin = bs.read(9);
            scfsi = bs.read(7 + count) & ((1 << (2 * count)) - 1);
        } else {
            mainDataBegin = bs.read(8 + count) >> count;
        }
        var part23Sum = 0;
        for (var g = 0; g < count; g++) {
            var gr = granules[g];
            if (mono) {
                scfsi <<= 4;
            }
            gr.part23Length = bs.read(12);
            part23Sum += gr.part23Length;
            gr.bigValues = bs.read(9);
            if (gr.bigValues > 288) {
                return -1;
            }
            gr.globalGain = bs.read(8);
            gr.scalefacCompress = bs.read(mpeg1 ? 4 : 9);
            gr.sfb = Mp3Tables.SFB_LONG[srIndex];
            gr.longBands = 22;
            gr.shortBands = 0;
            int tables;
            if (bs.read(1) != 0) {
                gr.blockType = bs.read(2);
                if (gr.blockType == 0) {
                    return -1;
                }
                gr.mixedBlock = bs.read(1) != 0;
                gr.regionCount[0] = 7;
                gr.regionCount[1] = 255;
                gr.regionCount[2] = 255;
                if (gr.blockType == 2) {
                    scfsi &= 0x0F0F;
                    if (!gr.mixedBlock) {
                        gr.regionCount[0] = 8;
                        gr.sfb = Mp3Tables.SFB_SHORT[srIndex];
                        gr.longBands = 0;
                        gr.shortBands = 39;
                    } else {
                        gr.sfb = Mp3Tables.SFB_MIXED[srIndex];
                        gr.longBands = mpeg1 ? 8 : 6;
                        gr.shortBands = 30;
                    }
                }
                tables = bs.read(10) << 5;
                gr.subblockGain[0] = bs.read(3);
                gr.subblockGain[1] = bs.read(3);
                gr.subblockGain[2] = bs.read(3);
            } else {
                gr.blockType = 0;
                gr.mixedBlock = false;
                tables = bs.read(15);
                gr.regionCount[0] = bs.read(4);
                gr.regionCount[1] = bs.read(3);
                gr.regionCount[2] = 255;
            }
            gr.tableSelect[0] = tables >> 10;
            gr.tableSelect[1] = (tables >> 5) & 31;
            gr.tableSelect[2] = tables & 31;
            gr.preflag = mpeg1 ? bs.read(1) != 0 : gr.scalefacCompress >= 500;
            gr.scalefacScale = bs.read(1);
            gr.count1Table = bs.read(1);
            gr.scfsi = (scfsi >> 12) & 15;
            scfsi <<= 4;
        }
        if (part23Sum + bs.position > bs.limit + mainDataBegin * 8) {
            return -1;
        }
        return mainDataBegin;
    }

    /**
     * Assembles the main data of a frame from the reservoir and the frame payload.
     *
     * @param frame         the reader over the frame, positioned after the side information
     * @param mainDataBegin the back-pointer into the reservoir
     * @return {@code true} when the reservoir held every byte the frame refers to
     */
    boolean restoreReservoir(BitReader frame, int mainDataBegin) {
        var frameBytes = Math.max(0, (frame.limit - frame.position) / 8);
        frameBytes = Math.min(frameBytes, MAX_FRAME_PAYLOAD);
        var have = Math.min(reservoirLength, mainDataBegin);
        System.arraycopy(reservoir, Math.max(0, reservoirLength - mainDataBegin), mainData, 0, have);
        System.arraycopy(frame.data, frame.start + frame.position / 8, mainData, have, frameBytes);
        bits.init(mainData, 0, have + frameBytes);
        return reservoirLength >= mainDataBegin;
    }

    /**
     * Keeps the unused tail of the main data as the reservoir of the next frame.
     */
    void saveReservoir() {
        var position = (bits.position + 7) / 8;
        var remains = bits.limit / 8 - position;
        if (remains > MAX_RESERVOIR) {
            position += remains - MAX_RESERVOIR;
            remains = MAX_RESERVOIR;
        }
        if (remains > 0) {
            System.arraycopy(mainData, position, reservoir, 0, remains);
        }
        reservoirLength = Math.max(0, remains);
    }

    /**
     * Decodes one granule of every channel into subband samples.
     *
     * @param header   the frame header
     * @param granule  the index in {@link #granules} of the granule's first channel
     * @param channels the number of channels
     * @param grbuf    receives 576 subband samples per channel, channel 1 at index 576; must be
     *                 zero on entry
     */
    void decodeGranule(int header, int granule, int channels, float[] grbuf) {
        for (var ch = 0; ch < channels; ch++) {
            var gr = granules[granule + ch];
            var limit = bits.position + gr.part23Length;
            decodeScalefactors(header, intensityPositions[ch], gr, ch);
            huffman(grbuf, ch * 576, gr, limit);
        }
        if (channels == 2) {
            if (MpegAudioHeader.isIntensityStereo(header)) {
                intensityStereo(grbuf, intensityPositions[1], granules[granule], granules[granule + 1], header);
            } else if (MpegAudioHeader.isMsStereo(header)) {
                midSide(grbuf, 0, 576);
            }
        }
        var eightKhz = MpegAudioHeader.sampleRateClass(header) == 2;
        for (var ch = 0; ch < channels; ch++) {
            var gr = granules[granule + ch];
            var base = ch * 576;
            var antialiasBands = 31;
            var longBands = (gr.mixedBlock ? 2 : 0) << (eightKhz ? 1 : 0);
            if (gr.shortBands != 0) {
                antialiasBands = longBands - 1;
                reorder(grbuf, base + longBands * 18, gr.sfb, gr.longBands);
            }
            antialias(grbuf, base, antialiasBands);
            imdct(grbuf, base, overlap[ch], gr.blockType, longBands);
            changeSign(grbuf, base);
        }
    }

    /**
     * Reads the scalefactors of one channel and converts them to band gains in
     * {@link #scalefactors}.
     *
     * @param header    the frame header
     * @param positions the channel's scalefactor history and intensity positions
     * @param gr        the granule side information
     * @param ch        the channel index
     */
    private void decodeScalefactors(int header, int[] positions, GranuleInfo gr, int ch) {
        var partitions = Mp3Tables.SCF_PARTITIONS[(gr.shortBands != 0 ? 1 : 0) + (gr.longBands == 0 ? 1 : 0)];
        var partitionOffset = 0;
        var sizes = scalefactorSizes;
        var shift = gr.scalefacScale + 1;
        var scfsi = gr.scfsi;
        if (MpegAudioHeader.isMpeg1(header)) {
            var part = Mp3Tables.SCFC_DECODE[gr.scalefacCompress];
            sizes[0] = sizes[1] = part >> 2;
            sizes[2] = sizes[3] = part & 3;
        } else {
            var intensity = MpegAudioHeader.isIntensityStereo(header) && ch != 0 ? 1 : 0;
            var sfc = gr.scalefacCompress >> intensity;
            var k = intensity * 3 * 4;
            var product = 1;
            for (; sfc >= 0 && k < 24; sfc -= product, k += 4) {
                product = 1;
                for (var i = 3; i >= 0; i--) {
                    sizes[i] = sfc / product % Mp3Tables.SCF_MODULI[k + i];
                    product *= Mp3Tables.SCF_MODULI[k + i];
                }
            }
            partitionOffset = Math.min(k, 24);
            scfsi = -16;
        }
        var iscf = integerScalefactors;
        readScalefactors(iscf, positions, sizes, partitions, partitionOffset, scfsi);
        var bands = gr.longBands + gr.shortBands;
        if (gr.shortBands != 0) {
            var sh = 3 - shift;
            for (var i = 0; i < gr.shortBands; i += 3) {
                iscf[gr.longBands + i] += gr.subblockGain[0] << sh;
                iscf[gr.longBands + i + 1] += gr.subblockGain[1] << sh;
                iscf[gr.longBands + i + 2] += gr.subblockGain[2] << sh;
            }
        } else if (gr.preflag) {
            for (var i = 0; i < 10; i++) {
                iscf[11 + i] += Mp3Tables.PREAMP[i];
            }
        }
        var gainExponent = gr.globalGain - 4 - 210 - (MpegAudioHeader.isMsStereo(header) ? 2 : 0);
        var gain = ldexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExponent);
        for (var i = 0; i < bands; i++) {
            scalefactors[i] = ldexpQ2(gain, iscf[i] << shift);
        }
    }

    /**
     * Reads the raw scalefactors of the four band groups.
     *
     * @param scf             receives the scalefactors
     * @param positions       the scalefactor history, updated with the new values
     * @param sizes           the bit length of each group
     * @param partitions      the band count table
     * @param partitionOffset the index in {@code partitions} of the first group's count
     * @param scfsi           the reuse flags (bit 3 first), or a negative value for MPEG-2, which
     *                        marks the all-ones value as an illegal intensity position
     */
    private void readScalefactors(int[] scf, int[] positions, int[] sizes, int[] partitions, int partitionOffset,
                                  int scfsi) {
        var index = 0;
        for (var i = 0; i < 4; i++, scfsi *= 2) {
            var count = partitions[partitionOffset + i];
            if (count == 0) {
                break;
            }
            if ((scfsi & 8) != 0) {
                System.arraycopy(positions, index, scf, index, count);
            } else {
                var length = sizes[i];
                if (length == 0) {
                    Arrays.fill(scf, index, index + count, 0);
                    Arrays.fill(positions, index, index + count, 0);
                } else {
                    var max = scfsi < 0 ? (1 << length) - 1 : -1;
                    for (var k = 0; k < count; k++) {
                        var value = bits.read(length);
                        positions[index + k] = value == max ? 255 : value;
                        scf[index + k] = value;
                    }
                }
            }
            index += count;
        }
        scf[index] = 0;
        scf[index + 1] = 0;
        scf[index + 2] = 0;
    }

    /**
     * Multiplies by a power of two expressed in quarter steps: {@code y * 2^(-exponent / 4)}.
     *
     * @param y        the value
     * @param exponent the non-negative exponent in quarter powers of two
     * @return the scaled value
     */
    static float ldexpQ2(float y, int exponent) {
        do {
            var e = Math.min(30 * 4, exponent);
            y *= Mp3Tables.EXP_FRACTIONS[e & 3] * (float) ((1 << 30) >> (e >> 2));
            exponent -= e;
        } while (exponent > 0);
        return y;
    }

    /**
     * Returns {@code sign(x) * |x|^(4/3)} for a non-negative quantized value.
     *
     * @param x the value, at most {@code 15 + 2^13}
     * @return {@code x^(4/3)}
     */
    private static float pow43(int x) {
        if (x < 129) {
            return Mp3Tables.POW43[16 + x];
        }
        var multiplier = 256;
        if (x < 1024) {
            multiplier = 16;
            x <<= 3;
        }
        var sign = 2 * x & 64;
        var fraction = (float) ((x & 63) - sign) / ((x & ~63) + sign);
        return Mp3Tables.POW43[16 + ((x + sign) >> 6)] * (1.0f + fraction * ((4.0f / 3) + fraction * (2.0f / 9))) * multiplier;
    }

    /**
     * Decodes the Huffman-coded spectrum of one granule and channel and requantizes it.
     *
     * @param dst   the output buffer
     * @param base  the index of the channel's first value
     * @param gr    the granule side information
     * @param limit the bit position in the main data where the granule ends
     */
    private void huffman(float[] dst, int base, GranuleInfo gr, int limit) {
        var buf = bits.data;
        var scf = scalefactors;
        var sfb = gr.sfb;
        var sfbIndex = 0;
        var scfIndex = 0;
        var out = base;
        var one = 0.0f;
        var region = 0;
        var bigValues = gr.bigValues;
        var next = bits.start + (bits.position >> 3);
        var cache = ((buf[next] & 0xFF) << 24 | (buf[next + 1] & 0xFF) << 16 | (buf[next + 2] & 0xFF) << 8
                | (buf[next + 3] & 0xFF)) << (bits.position & 7);
        var sh = (bits.position & 7) - 8;
        next += 4;
        var huffman = Mp3Tables.HUFFMAN;
        var pow = Mp3Tables.POW43;
        while (bigValues > 0) {
            var table = gr.tableSelect[region];
            var bandCount = gr.regionCount[region++];
            var codebook = Mp3Tables.HUFFMAN_INDEX[table];
            var linbits = Mp3Tables.LINBITS[table];
            do {
                var np = sfb[sfbIndex++] / 2;
                if (np == 0) {
                    bits.position = limit;
                    return;
                }
                var pairs = Math.min(bigValues, np);
                one = scf[scfIndex++];
                if (linbits != 0) {
                    do {
                        var w = 5;
                        int leaf = huffman[codebook + (cache >>> 27)];
                        while (leaf < 0) {
                            cache <<= w;
                            sh += w;
                            w = leaf & 7;
                            leaf = huffman[codebook + (cache >>> (32 - w)) - (leaf >> 3)];
                        }
                        cache <<= leaf >> 8;
                        sh += leaf >> 8;
                        for (var j = 0; j < 2; j++, out++, leaf >>= 4) {
                            var lsb = leaf & 0x0F;
                            if (lsb == 15) {
                                lsb += cache >>> (32 - linbits);
                                cache <<= linbits;
                                sh += linbits;
                                while (sh >= 0) {
                                    cache |= (buf[next++] & 0xFF) << sh;
                                    sh -= 8;
                                }
                                dst[out] = one * pow43(lsb) * (cache < 0 ? -1 : 1);
                            } else {
                                dst[out] = pow[16 + lsb - 16 * (cache >>> 31)] * one;
                            }
                            var sign = (-lsb) >>> 31;
                            cache <<= sign;
                            sh += sign;
                        }
                        while (sh >= 0) {
                            cache |= (buf[next++] & 0xFF) << sh;
                            sh -= 8;
                        }
                    } while (--pairs > 0);
                } else {
                    do {
                        var w = 5;
                        int leaf = huffman[codebook + (cache >>> 27)];
                        while (leaf < 0) {
                            cache <<= w;
                            sh += w;
                            w = leaf & 7;
                            leaf = huffman[codebook + (cache >>> (32 - w)) - (leaf >> 3)];
                        }
                        cache <<= leaf >> 8;
                        sh += leaf >> 8;
                        var lsb = leaf & 0x0F;
                        dst[out] = pow[16 + lsb - 16 * (cache >>> 31)] * one;
                        var sign = (-lsb) >>> 31;
                        cache <<= sign;
                        sh += sign;
                        lsb = (leaf >> 4) & 0x0F;
                        dst[out + 1] = pow[16 + lsb - 16 * (cache >>> 31)] * one;
                        sign = (-lsb) >>> 31;
                        cache <<= sign;
                        sh += sign;
                        out += 2;
                        while (sh >= 0) {
                            cache |= (buf[next++] & 0xFF) << sh;
                            sh -= 8;
                        }
                    } while (--pairs > 0);
                }
                bigValues -= np;
            } while (bigValues > 0 && --bandCount >= 0);
            if (region >= 3) {
                break;
            }
        }
        var count1 = gr.count1Table != 0 ? Mp3Tables.COUNT1_B : Mp3Tables.COUNT1_A;
        var np = 1 - bigValues;
        for (; ; out += 4) {
            var leaf = count1[cache >>> 28];
            if ((leaf & 8) == 0) {
                leaf = count1[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
            }
            cache <<= leaf & 7;
            sh += leaf & 7;
            if ((next - bits.start) * 8 - 24 + sh > limit) {
                break;
            }
            if (--np == 0) {
                np = sfb[sfbIndex++] / 2;
                if (np == 0) {
                    break;
                }
                one = scf[scfIndex++];
            }
            if ((leaf & 128) != 0) {
                dst[out] = cache < 0 ? -one : one;
                cache <<= 1;
                sh++;
            }
            if ((leaf & 64) != 0) {
                dst[out + 1] = cache < 0 ? -one : one;
                cache <<= 1;
                sh++;
            }
            if (--np == 0) {
                np = sfb[sfbIndex++] / 2;
                if (np == 0) {
                    break;
                }
                one = scf[scfIndex++];
            }
            if ((leaf & 32) != 0) {
                dst[out + 2] = cache < 0 ? -one : one;
                cache <<= 1;
                sh++;
            }
            if ((leaf & 16) != 0) {
                dst[out + 3] = cache < 0 ? -one : one;
                cache <<= 1;
                sh++;
            }
            while (sh >= 0) {
                cache |= (buf[next++] & 0xFF) << sh;
                sh -= 8;
            }
        }
        bits.position = limit;
    }

    /**
     * Converts mid/side values to left/right in place.
     *
     * @param grbuf the granule buffer, right channel at offset 576
     * @param left  the index of the first left value
     * @param count the number of values
     */
    private static void midSide(float[] grbuf, int left, int count) {
        for (var i = left; i < left + count; i++) {
            var a = grbuf[i];
            var b = grbuf[i + 576];
            grbuf[i] = a + b;
            grbuf[i + 576] = a - b;
        }
    }

    /**
     * Applies intensity stereo, with mid/side stereo on the bands below the intensity region.
     *
     * @param grbuf     the granule buffer
     * @param positions the intensity positions of the right channel
     * @param gr        the left channel's granule side information
     * @param right     the right channel's granule side information
     * @param header    the frame header
     */
    private void intensityStereo(float[] grbuf, int[] positions, GranuleInfo gr, GranuleInfo right, int header) {
        var sfbCount = gr.longBands + gr.shortBands;
        var maxBlocks = gr.shortBands != 0 ? 3 : 1;
        topBand(grbuf, 576, gr.sfb, sfbCount);
        if (gr.longBands != 0) {
            var max = Math.max(Math.max(maxBand[0], maxBand[1]), maxBand[2]);
            maxBand[0] = maxBand[1] = maxBand[2] = max;
        }
        var mpeg1 = MpegAudioHeader.isMpeg1(header);
        for (var i = 0; i < maxBlocks; i++) {
            var defaultPosition = mpeg1 ? 3 : 0;
            var top = sfbCount - maxBlocks + i;
            var previous = top - maxBlocks;
            if (top < 0 || previous < 0) {
                continue;
            }
            positions[top] = maxBand[i] >= previous ? defaultPosition : positions[previous];
        }
        stereoProcess(grbuf, positions, gr.sfb, header, right.scalefacCompress & 1);
    }

    /**
     * Finds the highest band of the right channel holding a non-zero value, per short window.
     *
     * @param grbuf the granule buffer
     * @param right the index of the right channel's first value
     * @param sfb   the band widths
     * @param bands the number of bands
     */
    private void topBand(float[] grbuf, int right, int[] sfb, int bands) {
        maxBand[0] = maxBand[1] = maxBand[2] = -1;
        for (var i = 0; i < bands; i++) {
            for (var k = 0; k < sfb[i]; k += 2) {
                if (grbuf[right + k] != 0 || grbuf[right + k + 1] != 0) {
                    maxBand[i % 3] = i;
                    break;
                }
            }
            right += sfb[i];
        }
    }

    /**
     * Applies intensity or mid/side processing band by band.
     *
     * @param grbuf     the granule buffer
     * @param positions the intensity positions
     * @param sfb       the band widths, zero-terminated
     * @param header    the frame header
     * @param mpeg2Sh   the MPEG-2 intensity scale ({@code intensity_scale})
     */
    private void stereoProcess(float[] grbuf, int[] positions, int[] sfb, int header, int mpeg2Sh) {
        var mpeg1 = MpegAudioHeader.isMpeg1(header);
        var maxPosition = mpeg1 ? 7 : 64;
        var ms = (header & 0x20) != 0;
        var left = 0;
        for (var i = 0; sfb[i] != 0; i++) {
            var position = positions[i];
            if (i > maxBand[i % 3] && position < maxPosition) {
                var s = ms ? 1.41421356f : 1;
                float kl;
                float kr;
                if (mpeg1) {
                    kl = Mp3Tables.INTENSITY_PAN[2 * position];
                    kr = Mp3Tables.INTENSITY_PAN[2 * position + 1];
                } else {
                    kl = 1;
                    kr = ldexpQ2(1, (position + 1) >> 1 << mpeg2Sh);
                    if ((position & 1) != 0) {
                        kl = kr;
                        kr = 1;
                    }
                }
                var gl = kl * s;
                var gr = kr * s;
                for (var k = left; k < left + sfb[i]; k++) {
                    grbuf[k + 576] = grbuf[k] * gr;
                    grbuf[k] = grbuf[k] * gl;
                }
            } else if (ms) {
                midSide(grbuf, left, sfb[i]);
            }
            left += sfb[i];
        }
    }

    /**
     * Reorders short-block values from band-major to window-interleaved order.
     *
     * @param grbuf  the granule buffer
     * @param base   the index of the first short-block value
     * @param sfb    the band widths
     * @param offset the index in {@code sfb} of the first short band
     */
    private void reorder(float[] grbuf, int base, int[] sfb, int offset) {
        var src = base;
        var dst = 0;
        var scratch = reorderScratch;
        for (var b = offset; sfb[b] != 0; b += 3) {
            var length = sfb[b];
            for (var i = 0; i < length; i++, src++) {
                scratch[dst++] = grbuf[src];
                scratch[dst++] = grbuf[src + length];
                scratch[dst++] = grbuf[src + 2 * length];
            }
            src += 2 * length;
        }
        System.arraycopy(scratch, 0, grbuf, base, dst);
    }

    /**
     * Applies the alias-reduction butterflies between adjacent subbands.
     *
     * @param grbuf the granule buffer
     * @param base  the index of the channel's first value
     * @param bands the number of subband boundaries to process
     */
    private static void antialias(float[] grbuf, int base, int bands) {
        var cs = Mp3Tables.ANTIALIAS[0];
        var ca = Mp3Tables.ANTIALIAS[1];
        for (var b = base; bands > 0; bands--, b += 18) {
            for (var i = 0; i < 8; i++) {
                var u = grbuf[b + 18 + i];
                var d = grbuf[b + 17 - i];
                grbuf[b + 18 + i] = u * cs[i] - d * ca[i];
                grbuf[b + 17 - i] = u * ca[i] + d * cs[i];
            }
        }
    }

    /**
     * Computes a 3-point IDCT.
     *
     * @param x0     the first input
     * @param x1     the second input
     * @param x2     the third input
     * @param dst    receives three outputs
     * @param offset the index of the first output
     */
    private static void idct3(float x0, float x1, float x2, float[] dst, int offset) {
        var m1 = x1 * 0.86602540f;
        var a1 = x0 - x2 * 0.5f;
        dst[offset + 1] = x0 + x2;
        dst[offset] = a1 + m1;
        dst[offset + 2] = a1 - m1;
    }

    /**
     * Applies one windowed 12-point IMDCT with overlap-add.
     *
     * @param x        the interleaved short-window input
     * @param xi       the index of the window's first value (stride 3)
     * @param dst      receives six outputs
     * @param di       the index of the first output
     * @param overlap  the overlap buffer
     * @param oi       the index of the three overlap values
     */
    private void imdct12(float[] x, int xi, float[] dst, int di, float[] overlap, int oi) {
        var tw = Mp3Tables.TWIDDLE_12;
        var co = dctScratch;
        idct3(-x[xi], x[xi + 6] + x[xi + 3], x[xi + 12] + x[xi + 9], co, 0);
        idct3(x[xi + 15], x[xi + 12] - x[xi + 9], x[xi + 6] - x[xi + 3], co, 3);
        co[4] = -co[4];
        for (var i = 0; i < 3; i++) {
            var previous = overlap[oi + i];
            var sum = co[i] * tw[3 + i] + co[3 + i] * tw[i];
            overlap[oi + i] = co[i] * tw[i] - co[3 + i] * tw[3 + i];
            dst[di + i] = previous * tw[2 - i] - sum * tw[5 - i];
            dst[di + 5 - i] = previous * tw[5 - i] + sum * tw[2 - i];
        }
    }

    /**
     * Applies the short-block IMDCT with overlap-add to a run of subbands.
     *
     * @param grbuf   the granule buffer
     * @param base    the index of the first subband
     * @param overlap the channel's overlap buffer
     * @param ovl     the index in {@code overlap} of the first subband's overlap
     * @param bands   the number of subbands
     */
    private void imdctShort(float[] grbuf, int base, float[] overlap, int ovl, int bands) {
        var tmp = shortScratch;
        for (; bands > 0; bands--, ovl += 9, base += 18) {
            System.arraycopy(grbuf, base, tmp, 0, 18);
            System.arraycopy(overlap, ovl, grbuf, base, 6);
            imdct12(tmp, 0, grbuf, base + 6, overlap, ovl + 6);
            imdct12(tmp, 1, grbuf, base + 12, overlap, ovl + 6);
            imdct12(tmp, 2, overlap, ovl, overlap, ovl + 6);
        }
    }

    /**
     * Applies the hybrid IMDCT of one channel of a granule.
     *
     * @param grbuf     the granule buffer
     * @param base      the index of the channel's first value
     * @param overlap   the channel's overlap buffer
     * @param blockType the granule's block type
     * @param longBands the number of long-block subbands of a mixed granule, else zero
     */
    private void imdct(float[] grbuf, int base, float[] overlap, int blockType, int longBands) {
        var ovl = 0;
        if (longBands != 0) {
            kernel.imdct36(grbuf, base, overlap, 0, Mp3Tables.MDCT_WINDOW[0], longBands);
            base += 18 * longBands;
            ovl += 9 * longBands;
        }
        if (blockType == 2) {
            imdctShort(grbuf, base, overlap, ovl, 32 - longBands);
        } else {
            kernel.imdct36(grbuf, base, overlap, ovl, Mp3Tables.MDCT_WINDOW[blockType == 3 ? 1 : 0], 32 - longBands);
        }
    }

    /**
     * Negates every odd sample of every odd subband (frequency inversion).
     *
     * @param grbuf the granule buffer
     * @param base  the index of the channel's first value
     */
    private static void changeSign(float[] grbuf, int base) {
        for (var b = base + 18; b < base + 576; b += 36) {
            for (var i = 1; i < 18; i += 2) {
                grbuf[b + i] = -grbuf[b + i];
            }
        }
    }
}
