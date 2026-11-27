package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Decodes the X96 frequency extension of the DTS core (ETSI TS 102 114 clause 6.2): the subbands 32 to 63 of a
 * 64-band representation at twice the core rate, and a residual refining the core subbands, carried in the core
 * frame or in the extension substream.
 *
 * <p>Instances are not thread-safe.
 */
final class X96Decoder {
    /**
     * The LFE 2x interpolation filter (Table 6-11), scaled by the interpolation factor.
     */
    private static final float[] LFE_FILTER = {0.12553677676342990f, 0.49999913800216800f, 0.74892817046880420f,
            0.49999913800216800f, 0.12553677676342990f};

    /**
     * The tap of the linear two times LFE interpolation on the nearer sample.
     */
    private static final float LFE_NEAR = DtsFixedTables.LFE_DOUBLE_NEAR / (float) (1 << 23);

    /**
     * The tap of the linear two times LFE interpolation on the farther sample.
     */
    private static final float LFE_FAR = DtsFixedTables.LFE_DOUBLE_FAR / (float) (1 << 23);

    private static final DtsHuffman[] SCALE_BOOKS = {DtsHuffman.book("SA129"), DtsHuffman.book("SB129"),
            DtsHuffman.book("SC129"), DtsHuffman.book("SD129"), DtsHuffman.book("SE129")};

    /**
     * The code book letter by SEL.
     */
    private static final String[] PREFIXES = {"A", "B", "C", "D", "E", "F", "G"};

    /**
     * The number of quantizer levels by ABITS, for the block and Huffman coded ones.
     */
    private static final int[] LEVELS = {0, 3, 5, 7, 9, 13, 17, 25, 33, 65, 129};

    /**
     * The block code width in bits by ABITS.
     */
    private static final int[] BLOCK_BITS = {0, 7, 10, 12, 13, 15, 17, 19};

    /**
     * The reader of the extension.
     */
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * The last active subband plus one by channel of the set ({@code SBE96}).
     */
    private final int[] lastBand = new int[8];

    /**
     * The last active subband plus one by plane of the subband buffer, which a joint intensity source of a later
     * channel set names by its plane.
     */
    private final int[] planeLastBand = new int[SubbandBuffer.MAX_CHANNELS];

    /**
     * The joint intensity index by channel ({@code JOINX96}).
     */
    private final int[] joint = new int[8];

    /**
     * The plane of the joint intensity source by channel: an index up to the channel's own position counts the
     * channels of the set, a higher one the planes of the decoder.
     */
    private final int[] sourcePlane = new int[8];

    /**
     * The scale factor code book by channel.
     */
    private final int[] scaleBook = new int[8];

    /**
     * The bit allocation code book by channel.
     */
    private final int[] allocationBook = new int[8];

    /**
     * The quantization index code book selector by channel and ABITS ({@code SEL96}).
     */
    private final int[][] select = new int[8][16];

    /**
     * The prediction mode by channel and subband.
     */
    private final int[][] predictionMode = new int[8][64];

    /**
     * The prediction coefficient vector by channel and subband.
     */
    private final int[][] predictionVector = new int[8][64];

    /**
     * The bit allocation index by channel and subband.
     */
    private final int[][] allocation = new int[8][64];

    /**
     * The scale factor by channel and subband.
     */
    private final float[][] scales = new float[8][64];

    /**
     * The joint intensity scale factor by channel and subband.
     */
    private final float[][] jointScales = new float[8][64];

    /**
     * The joint scale factor code book by channel.
     */
    private final int[] jointBook = new int[8];

    /**
     * The quantization indexes of the eight samples being read.
     */
    private final int[] codes = new int[8];

    /**
     * The size in bytes of each channel set.
     */
    private final int[] setSizes = new int[4];

    /**
     * The number of channels of each channel set.
     */
    private final int[] setChannels = new int[4];

    /**
     * The X96 residual of the core subbands by plane and subband: {@link SubbandBuffer#HISTORY} samples of
     * prediction history, then the frame.
     */
    private final float[][][] residual = new float[SubbandBuffer.MAX_CHANNELS][32][SubbandBuffer.HISTORY
            + SubbandBuffer.MAX_SAMPLES];

    /**
     * The state of the noise generator of subbands without bits.
     */
    private int seed = 1;

    /**
     * Whether the current channel set uses the high resolution quantizers.
     */
    private boolean highResolution;

    /**
     * The first subband the current channel set codes ({@code SBS96}).
     */
    private int firstBand;

    /**
     * Decodes an X96 extension carried in the core frame (DTS_BCCORE_X96).
     *
     * @param data the bytes
     * @param offset the index of the sync word
     * @param length the number of bytes up to the end of the core frame
     * @param core the core decoder, its frame decoded
     * @throws MediaException.Malformed if the extension breaks the syntax
     */
    void decodeCore(byte[] data, int offset, int length, CoreDecoder core) {
        reader.reset(data, offset, length);
        reader.skip(32 + 12);
        var revision = reader.read(4);
        if (revision > 8) {
            return;
        }
        decodeSet(core, 0, core.primary().channels, revision, false, 0);
        core.markX96();
    }

    /**
     * Decodes an X96 component of the extension substream (DTS_EXSUB_STREAM_X96).
     *
     * @param data the bytes
     * @param offset the index of the component
     * @param length the component size
     * @param core the core decoder, its frame decoded
     * @throws MediaException.Malformed if the component breaks the syntax
     */
    void decodeSubstream(byte[] data, int offset, int length, CoreDecoder core) {
        if (length < 8 || CoreDecoder.readInt(data, offset) != CoreDecoder.X96_SYNC) {
            throw new MediaException.Malformed("missing DTS X96 sync word");
        }
        reader.reset(data, offset, length);
        reader.skip(32);
        var headerSize = reader.read(6) + 1;
        if (headerSize > length || !DtsCrc.check(data, offset + 4, headerSize - 4)) {
            throw new MediaException.Malformed("DTS X96 header CRC mismatch");
        }
        var revision = reader.read(4);
        var setCrc = reader.readFlag();
        var setCount = reader.read(2) + 1;
        for (var s = 0; s < setCount; s++) {
            setSizes[s] = reader.read(12) + 1;
        }
        for (var s = 0; s < setCount; s++) {
            setChannels[s] = reader.read(3) + 1;
        }
        if (revision > 8) {
            return;
        }
        var position = offset + headerSize;
        var firstPlane = 0;
        for (var s = 0; s < setCount; s++) {
            if (position + setSizes[s] > offset + length) {
                throw new MediaException.Malformed("DTS X96 channel set overruns the component");
            }
            if (firstPlane + setChannels[s] > core.channels()) {
                throw new MediaException.Malformed("DTS X96 extends more channels than the core decoded");
            }
            reader.reset(data, position, setSizes[s]);
            var headerBytes = reader.read(7) + 1;
            if (setCrc && !DtsCrc.check(data, position, headerBytes)) {
                throw new MediaException.Malformed("DTS X96 channel set header CRC mismatch");
            }
            decodeSet(core, firstPlane, setChannels[s], revision, true, headerBytes);
            firstPlane += setChannels[s];
            position += setSizes[s];
        }
        core.markX96();
    }

    /**
     * Decodes one channel set: its header, then its subframes.
     */
    private void decodeSet(CoreDecoder core, int firstPlane, int channels, int revision, boolean substream,
                           int headerBytes) {
        highResolution = reader.readFlag();
        if (revision < 8) {
            firstBand = reader.read(5);
            if (firstBand > 27) {
                throw new MediaException.Malformed("invalid DTS X96 first subband " + firstBand);
            }
        } else {
            firstBand = 32;
        }
        for (var ch = 0; ch < channels; ch++) {
            lastBand[ch] = reader.read(6) + 1;
            planeLastBand[firstPlane + ch] = lastBand[ch];
        }
        for (var ch = 0; ch < channels; ch++) {
            joint[ch] = reader.read(3);
            sourcePlane[ch] = sourcePlane(joint[ch], firstPlane, ch);
            if (sourcePlane[ch] >= firstPlane + channels) {
                throw new MediaException.Malformed("invalid DTS X96 joint intensity source " + joint[ch]);
            }
            if (sourcePlane[ch] >= firstPlane + channels) {
                throw new MediaException.Malformed("invalid DTS X96 joint intensity source " + joint[ch]);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            scaleBook[ch] = reader.read(3);
            if (scaleBook[ch] > 5) {
                throw new MediaException.Malformed("invalid DTS X96 scale factor code book");
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            allocationBook[ch] = reader.read(3);
        }
        for (var ch = 0; ch < channels; ch++) {
            select[ch][0] = reader.read(1);
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < channels; ch++) {
                select[ch][n] = reader.read(2);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            select[ch][5] = reader.read(3);
        }
        if (highResolution) {
            for (var n = 6; n < 10; n++) {
                for (var ch = 0; ch < channels; ch++) {
                    select[ch][n] = reader.read(3);
                }
            }
        }
        if (substream) {
            reader.seek(headerBytes * 8L);
        } else if (core.header().crcPresent) {
            reader.skip(16);
        }
        var buffer = core.buffer();
        var steps = core.steps();
        var everySubsubframe = core.header().syncEverySubsubframe;
        if (!core.header().predictorHistory) {
            for (var ch = 0; ch < channels; ch++) {
                for (var band : residual[firstPlane + ch]) {
                    Arrays.fill(band, 0, SubbandBuffer.HISTORY, 0);
                }
            }
        }
        for (var subframe = 0; subframe < core.subframes(); subframe++) {
            var subsubframes = core.subsubframes(subframe);
            var partial = core.partial(subframe);
            var offset = core.subframeOffset(subframe);
            readSideInformation(channels, core.header().crcPresent && !substream);
            var count = subsubframes * 8 + partial;
            var base = SubbandBuffer.HISTORY + offset;
            for (var ch = 0; ch < channels; ch++) {
                for (var n = firstBand; n < lastBand[ch]; n++) {
                    var samples = samples(buffer, firstPlane + ch, n);
                    if (allocation[ch][n] == 0) {
                        noise(samples, base, count, scales[ch][n]);
                    } else if (allocation[ch][n] == 1) {
                        var scale = (int) scales[ch][n];
                        for (var half = 0; half * 16 < count; half++) {
                            var index = reader.read(10);
                            var elements = Math.min(16, count - half * 16);
                            for (var m = 0; m < elements; m++) {
                                var value = DtsVqTables.highFrequency(index, m) * scale + (1 << 3) >> 4;
                                samples[base + half * 16 + m] = ChannelSet.clip(value);
                            }
                        }
                    }
                }
            }
            var last = partial > 0 ? subsubframes : subsubframes - 1;
            for (var subsubframe = 0; subsubframe <= last; subsubframe++) {
                var length = subsubframe < subsubframes ? 8 : partial;
                for (var ch = 0; ch < channels; ch++) {
                    for (var n = firstBand; n < lastBand[ch]; n++) {
                        var abits = allocation[ch][n] - 1;
                        if (abits <= 0) {
                            continue;
                        }
                        readCodes(ch, abits, length);
                        var step = (long) scales[ch][n] * steps[abits];
                        var samples = samples(buffer, firstPlane + ch, n);
                        var at = base + 8 * subsubframe;
                        for (var m = 0; m < length; m++) {
                            samples[at + m] = ChannelSet.dequantize(codes[m], step);
                        }
                    }
                }
                if (subsubframe >= subsubframes - 1 || everySubsubframe) {
                    CoreDecoder.checkSync(reader, "DTS X96");
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = firstBand; n < lastBand[ch]; n++) {
                    if (predictionMode[ch][n] != 0) {
                        ChannelSet.inverseAdpcm(samples(buffer, firstPlane + ch, n), base, count,
                                predictionVector[ch][n]);
                    }
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                if (joint[ch] == 0) {
                    continue;
                }
                for (var n = lastBand[ch]; n < planeLastBand[sourcePlane[ch]]; n++) {
                    var source = samples(buffer, sourcePlane[ch], n);
                    var target = samples(buffer, firstPlane + ch, n);
                    for (var m = base; m < base + count; m++) {
                        target[m] = jointScales[ch][n] * source[m];
                    }
                }
            }
        }
        reader.checkNotOverrun();
        var frameSamples = core.samples();
        for (var ch = 0; ch < channels; ch++) {
            var plane = firstPlane + ch;
            buffer.saveHistory(plane, 32, 64, frameSamples);
            var active = joint[ch] == 0 ? lastBand[ch] : Math.max(lastBand[ch], planeLastBand[sourcePlane[ch]]);
            for (var n = 0; n < 32; n++) {
                var samples = residual[plane][n];
                if (n < firstBand || n >= active) {
                    Arrays.fill(samples, 0);
                    continue;
                }
                var core32 = buffer.planes[plane][n];
                for (var m = SubbandBuffer.HISTORY; m < SubbandBuffer.HISTORY + frameSamples; m++) {
                    core32[m] += samples[m];
                }
                System.arraycopy(samples, frameSamples, samples, 0, SubbandBuffer.HISTORY);
            }
        }
    }

    /**
     * {@return the samples X96 codes for a subband of a plane} The upper subbands are the plane's own; the core
     * subbands get a residual of their own, with its own prediction history, which joins the core subband once
     * the frame is decoded, as in dcadec and ffmpeg, whose 64-band synthesis adds the two.
     *
     * @param buffer the subband buffer of the core
     * @param plane the plane
     * @param band the subband
     */
    private float[] samples(SubbandBuffer buffer, int plane, int band) {
        return band < 32 ? residual[plane][band] : buffer.planes[plane][band];
    }

    /**
     * Reads the side information of one subframe (Table 6-9).
     */
    private void readSideInformation(int channels, boolean crc) {
        for (var ch = 0; ch < channels; ch++) {
            for (var n = firstBand; n < lastBand[ch]; n++) {
                predictionMode[ch][n] = reader.read(1);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            for (var n = firstBand; n < lastBand[ch]; n++) {
                if (predictionMode[ch][n] != 0) {
                    predictionVector[ch][n] = reader.read(12);
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var book = allocationBook[ch];
            var sum = 0;
            for (var n = firstBand; n < lastBand[ch]; n++) {
                int value;
                if (book == 7) {
                    value = reader.read(highResolution ? 4 : 3);
                } else {
                    sum += DtsHuffman.book(PREFIXES[book] + (highResolution ? "33" : "17")).decode(reader);
                    value = sum;
                }
                if (value < 0 || value > (highResolution ? 15 : 7)) {
                    throw new MediaException.Malformed("invalid DTS X96 bit allocation index " + value);
                }
                allocation[ch][n] = value;
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var sum = 0;
            for (var n = firstBand; n < lastBand[ch]; n++) {
                int index;
                if (scaleBook[ch] == 5) {
                    index = reader.read(6);
                } else {
                    sum += SCALE_BOOKS[scaleBook[ch]].decode(reader);
                    index = sum;
                }
                if (index < 0 || index >= ChannelSet.RMS6.length) {
                    throw new MediaException.Malformed("invalid DTS X96 scale factor index " + index);
                }
                scales[ch][n] = ChannelSet.RMS6[index];
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            if (joint[ch] > 0) {
                jointBook[ch] = reader.read(3);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            if (joint[ch] > 0) {
                for (var n = lastBand[ch]; n < planeLastBand[sourcePlane[ch]]; n++) {
                    var index = (jointBook[ch] == 5 ? reader.read(6) : SCALE_BOOKS[jointBook[ch]].decode(reader))
                            + 64;
                    if (index < 0 || index >= DtsCoreTables.JOINT_SCALES.length) {
                        throw new MediaException.Malformed("invalid DTS X96 joint scale index " + index);
                    }
                    jointScales[ch][n] = (float) DtsCoreTables.JOINT_SCALES[index];
                }
            }
        }
        if (crc) {
            reader.skip(16);
        }
    }

    /**
     * Reads the quantization indexes of one subband with the core quantizer {@code abits}: {@code count} of them, or
     * the blocks of four that cover them for block codes.
     */
    private void readCodes(int ch, int abits, int count) {
        var sel = abits <= 10 ? select[ch][abits - 1] : 0;
        var huffmanBooks = abits == 1 ? 1 : abits <= 5 ? 3 : abits <= 10 ? 7 : 0;
        if (sel < huffmanBooks) {
            var book = DtsHuffman.book(PREFIXES[sel] + LEVELS[abits]);
            for (var m = 0; m < count; m++) {
                codes[m] = book.decode(reader);
            }
        } else if (abits <= 7) {
            var levels = LEVELS[abits];
            var offset = (levels - 1) >> 1;
            for (var block = 0; block < (count + 3) >> 2; block++) {
                var code = reader.read(BLOCK_BITS[abits]);
                for (var m = 0; m < 4; m++) {
                    codes[block * 4 + m] = code % levels - offset;
                    code /= levels;
                }
                if (code != 0) {
                    throw new MediaException.Malformed("invalid DTS X96 block code");
                }
            }
        } else {
            for (var m = 0; m < count; m++) {
                codes[m] = reader.readSigned(abits - 3);
            }
        }
    }

    /**
     * Fills a subband without allocated bits with uniform noise at its scale factor, as dcadec and ffmpeg do: the
     * low 31 bits of the generator centred on zero, times the scale factor in Q31 with rounding; a subband whose
     * scale factor is at most 1 is silent and draws nothing.
     */
    private void noise(float[] samples, int base, int count, float scale) {
        if (scale <= 1) {
            Arrays.fill(samples, base, base + count, 0);
            return;
        }
        var factor = (long) scale;
        for (var m = base; m < base + count; m++) {
            seed = seed * 1103515245 + 12345;
            var random = (seed & 0x7FFFFFFF) - 0x40000000;
            samples[m] = (random * factor + (1L << 30)) >> 31;
        }
    }

    /**
     * Resolves a joint intensity index ({@code JOINX96}) to the plane of its source channel, as the deployed decoders
     * resolve it: an index up to the channel's own position counts the channels of the set from 1; in a channel set
     * after the first a higher index counts them from 2 (the right rear surround of X96 over XXCH names the left one
     * 2), and in the first channel set it names a plane of the decoder.
     *
     * @param index the index, 0 without joint intensity coding
     * @param firstPlane the first plane of the channel set
     * @param channel the channel of the set the index belongs to
     * @return the source plane, or {@code -1} for index 0
     */
    private static int sourcePlane(int index, int firstPlane, int channel) {
        if (index == 0) {
            return -1;
        }
        if (index <= channel) {
            return firstPlane + index - 1;
        }
        if (firstPlane > 0 && index - 2 < channel) {
            return firstPlane + index - 2;
        }
        return index - 1;
    }

    /**
     * Interpolates the LFE channel twice by linear interpolation halfway between the samples, the two times
     * interpolation of the fixed-point core reconstruction the lossless extension codes its residuals against,
     * continuing from the samples of the previous call.
     *
     * @param in the LFE samples at the core rate
     * @param count the number of input samples
     * @param out receives {@code 2 * count} samples
     * @param history the last two input samples of the previous call, newest last; updated
     */
    static void interpolateLfeLinearly(float[] in, int count, float[] out, float[] history) {
        var last = history[1];
        for (var n = 0; n < count; n++) {
            var current = in[n];
            out[2 * n] = LFE_FAR * current + LFE_NEAR * last;
            out[2 * n + 1] = LFE_NEAR * current + LFE_FAR * last;
            last = current;
        }
        if (count >= 2) {
            history[0] = in[count - 2];
            history[1] = in[count - 1];
        } else if (count == 1) {
            history[0] = history[1];
            history[1] = in[0];
        }
    }

    /**
     * Interpolates the LFE channel twice (Table 6-11), continuing from the samples of the previous call.
     *
     * @param in the LFE samples at the core rate
     * @param count the number of input samples
     * @param out receives {@code 2 * count} samples
     * @param history the last two input samples of the previous call, newest last; updated
     */
    static void interpolateLfe(float[] in, int count, float[] out, float[] history) {
        for (var n = 0; n < 2 * count; n++) {
            var sum = 0.0f;
            for (var k = n & 1; k < LFE_FILTER.length; k += 2) {
                var index = (n - k) >> 1;
                var sample = index >= 0 ? in[index] : history[2 + index];
                sum += LFE_FILTER[k] * sample;
            }
            out[n] = sum;
        }
        if (count >= 2) {
            history[0] = in[count - 2];
            history[1] = in[count - 1];
        } else if (count == 1) {
            history[0] = history[1];
            history[1] = in[0];
        }
    }
}
