package com.github.auties00.smalt.audio.dts.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitReader;

import java.util.Arrays;

/**
 * Decodes the subband samples of a set of channels coded with the DTS core tools (ETSI TS 102 114 clauses 5.4.3
 * to 5.6): the core primary channels, the XCh channel and each XXCH channel set.
 *
 * <p>The audio coding header is read once per frame with {@link #readHeader}; each subframe then reads its side
 * information ({@link #readSideInformation}) and audio data ({@link #readVectors}, {@link #readSubsubframe}),
 * writing dequantized subband samples (inverse ADPCM and joint intensity applied) into the planes of
 * {@link SubbandBuffer}.
 *
 * <p>Instances are not thread-safe.
 */
final class ChannelSet {
    /**
     * The scale factor adjustments by ADJ index (Table 5-27), multiples of 1/16; the adjusted scale factor is
     * truncated to an integer, as the deployed decoders do.
     */
    private static final float[] ADJUSTMENTS = {1.0f, 1.125f, 1.25f, 1.4375f};

    /**
     * The number of levels of each quantizer by ABITS (Table 5-26), for the block and Huffman coded ones.
     */
    private static final int[] LEVELS = {0, 3, 5, 7, 9, 13, 17, 25, 33, 65, 129};

    /**
     * The Huffman code books of the audio data by ABITS and SEL (Table 5-26); {@code null} where SEL selects block
     * or plain coding.
     */
    private static final DtsHuffman[][] AUDIO_BOOKS = audioBooks();

    /**
     * The scale factor code books by SHUFF 0 to 4.
     */
    private static final DtsHuffman[] SCALE_BOOKS = books("SA129", "SB129", "SC129", "SD129", "SE129");

    /**
     * The bit allocation code books by BHUFF 0 to 4.
     */
    private static final DtsHuffman[] ALLOCATION_BOOKS = books("A12", "B12", "C12", "D12", "E12");

    /**
     * The transient mode code books by THUFF.
     */
    private static final DtsHuffman[] TRANSIENT_BOOKS = books("A4", "B4", "C4", "D4");

    /**
     * The block code widths in bits, by ABITS 1 to 7.
     */
    private static final int[] BLOCK_BITS = {0, 7, 10, 12, 13, 15, 17, 19};

    /**
     * The 6-bit scale factor levels (D.1.1).
     */
    static final float[] RMS6 = floats(DtsCoreTables.RMS6);

    /**
     * The 7-bit scale factor levels (D.1.2).
     */
    static final float[] RMS7 = floats(DtsCoreTables.RMS7);

    /**
     * The largest number of channels a set holds.
     */
    static final int MAX_CHANNELS = 8;

    /**
     * The number of channels of the set.
     */
    int channels;

    /**
     * The index of the first plane of the set in the {@link SubbandBuffer}.
     */
    int firstPlane;

    /**
     * The active subband count by plane of the subband buffer, shared by the channel sets of one decoder: a joint
     * intensity source ({@code JOINX}) names a plane, which may belong to an earlier channel set.
     */
    int[] planeBands = new int[SubbandBuffer.MAX_CHANNELS];

    /**
     * The active subband count by channel ({@code nSUBS}).
     */
    final int[] subbands = new int[MAX_CHANNELS];

    /**
     * The first high frequency VQ subband by channel ({@code nVQSUB}); the subbands below it and below
     * {@link #subbands} carry scalar coded samples.
     */
    final int[] vqStart = new int[MAX_CHANNELS];

    /**
     * The joint intensity source channel plus one by channel, or {@code 0} ({@code JOINX}).
     */
    final int[] joint = new int[MAX_CHANNELS];

    /**
     * The plane of the joint intensity source by channel, {@code -1} without one: {@code JOINX} counts the
     * channels of the set, and an index no channel before the current one of the set has (the single channel of
     * XCh names a core channel) counts the planes of the decoder.
     */
    final int[] sourcePlane = new int[MAX_CHANNELS];

    /**
     * The transient mode code book by channel ({@code THUFF}).
     */
    final int[] transientBook = new int[MAX_CHANNELS];

    /**
     * The scale factor code book by channel ({@code SHUFF}).
     */
    final int[] scaleBook = new int[MAX_CHANNELS];

    /**
     * The bit allocation code book by channel ({@code BHUFF}).
     */
    final int[] allocationBook = new int[MAX_CHANNELS];

    /**
     * The quantization index code book by channel and ABITS minus one ({@code SEL}).
     */
    final int[][] select = new int[MAX_CHANNELS][10];

    /**
     * The scale factor adjustment by channel and ABITS minus one.
     */
    final float[][] adjust = new float[MAX_CHANNELS][10];

    /**
     * The prediction mode by channel and subband ({@code PMODE}).
     */
    private final int[][] predictionMode = new int[MAX_CHANNELS][32];

    /**
     * The prediction coefficient vector by channel and subband ({@code PVQ}).
     */
    private final int[][] predictionVector = new int[MAX_CHANNELS][32];

    /**
     * The bit allocation index by channel and subband ({@code ABITS}).
     */
    private final int[][] allocation = new int[MAX_CHANNELS][32];

    /**
     * The transient mode by channel and subband ({@code TMODE}).
     */
    private final int[][] transientMode = new int[MAX_CHANNELS][32];

    /**
     * The scale factors by channel and subband, before and after the transient.
     */
    private final float[][][] scales = new float[MAX_CHANNELS][32][2];

    /**
     * The joint intensity scale factors by channel and subband.
     */
    private final float[][] jointScales = new float[MAX_CHANNELS][32];

    /**
     * The joint scale factor code book by channel ({@code JOIN_SHUFF}).
     */
    private final int[] jointBook = new int[MAX_CHANNELS];

    /**
     * The quantization indexes of the eight samples being read.
     */
    private final int[] codes = new int[8];

    /**
     * Reads the per-channel fields of the audio coding header from {@code nSUBS} on (Table 5-21 after
     * {@code PCHS}), for {@link #channels} channels.
     *
     * @param reader the bit stream
     * @throws MediaException.Malformed if a field is out of range
     */
    void readHeader(MsbBitReader reader) {
        for (var ch = 0; ch < channels; ch++) {
            subbands[ch] = reader.read(5) + 2;
            if (subbands[ch] > 32) {
                throw new MediaException.Malformed("DTS subband count " + subbands[ch] + " above 32");
            }
            planeBands[firstPlane + ch] = subbands[ch];
        }
        for (var ch = 0; ch < channels; ch++) {
            vqStart[ch] = reader.read(5) + 1;
        }
        for (var ch = 0; ch < channels; ch++) {
            joint[ch] = reader.read(3);
            sourcePlane[ch] = sourcePlane(joint[ch], firstPlane, channels);
        }
        for (var ch = 0; ch < channels; ch++) {
            transientBook[ch] = reader.read(2);
        }
        for (var ch = 0; ch < channels; ch++) {
            scaleBook[ch] = reader.read(3);
            if (scaleBook[ch] == 7) {
                throw new MediaException.Malformed("invalid DTS scale factor code book 7");
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            allocationBook[ch] = reader.read(3);
            if (allocationBook[ch] == 7) {
                throw new MediaException.Malformed("invalid DTS bit allocation code book 7");
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            select[ch][0] = reader.read(1);
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < channels; ch++) {
                select[ch][n] = reader.read(2);
            }
        }
        for (var n = 5; n < 10; n++) {
            for (var ch = 0; ch < channels; ch++) {
                select[ch][n] = reader.read(3);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            Arrays.fill(adjust[ch], 1.0f);
        }
        for (var ch = 0; ch < channels; ch++) {
            if (select[ch][0] == 0) {
                adjust[ch][0] = ADJUSTMENTS[reader.read(2)];
            }
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < channels; ch++) {
                if (select[ch][n] < 3) {
                    adjust[ch][n] = ADJUSTMENTS[reader.read(2)];
                }
            }
        }
        for (var n = 5; n < 10; n++) {
            for (var ch = 0; ch < channels; ch++) {
                if (select[ch][n] < 7) {
                    adjust[ch][n] = ADJUSTMENTS[reader.read(2)];
                }
            }
        }
    }

    /**
     * Reads the side information of one subframe (Table 5-28 from {@code PMODE} to the joint scale factors).
     *
     * @param reader the bit stream
     * @param subsubframes the number of subsubframes of the subframe ({@code nSSC})
     * @throws MediaException.Malformed if a field is out of range
     */
    void readSideInformation(MsbBitReader reader, int subsubframes) {
        for (var ch = 0; ch < channels; ch++) {
            for (var n = 0; n < subbands[ch]; n++) {
                predictionMode[ch][n] = reader.read(1);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            for (var n = 0; n < subbands[ch]; n++) {
                if (predictionMode[ch][n] != 0) {
                    predictionVector[ch][n] = reader.read(12);
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var book = allocationBook[ch];
            for (var n = 0; n < vqStart[ch]; n++) {
                var abits = switch (book) {
                    case 5 -> reader.read(4);
                    case 6 -> reader.read(5);
                    default -> ALLOCATION_BOOKS[book].decode(reader);
                };
                if (abits > 26) {
                    throw new MediaException.Malformed("invalid DTS bit allocation index " + abits);
                }
                allocation[ch][n] = abits;
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            Arrays.fill(transientMode[ch], 0);
            if (subsubframes > 1) {
                var book = TRANSIENT_BOOKS[transientBook[ch]];
                for (var n = 0; n < vqStart[ch]; n++) {
                    if (allocation[ch][n] > 0) {
                        transientMode[ch][n] = book.decode(reader);
                    }
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            var book = scaleBook[ch];
            var table = book == 6 ? RMS7 : RMS6;
            var sum = 0;
            for (var n = 0; n < 32; n++) {
                scales[ch][n][0] = 0;
                scales[ch][n][1] = 0;
            }
            for (var n = 0; n < vqStart[ch]; n++) {
                if (allocation[ch][n] > 0) {
                    sum = scaleIndex(reader, book, sum);
                    scales[ch][n][0] = lookUp(table, sum);
                    if (transientMode[ch][n] > 0) {
                        sum = scaleIndex(reader, book, sum);
                        scales[ch][n][1] = lookUp(table, sum);
                    }
                }
            }
            for (var n = vqStart[ch]; n < subbands[ch]; n++) {
                sum = scaleIndex(reader, book, sum);
                scales[ch][n][0] = lookUp(table, sum);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            if (joint[ch] > 0) {
                jointBook[ch] = reader.read(3);
                if (jointBook[ch] == 7) {
                    throw new MediaException.Malformed("invalid DTS joint scale factor code book 7");
                }
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            if (joint[ch] > 0) {
                var source = sourcePlane[ch];
                for (var n = subbands[ch]; n < planeBands[source]; n++) {
                    var index = rawScale(reader, jointBook[ch]) + 64;
                    if (index < 0 || index >= DtsCoreTables.JOINT_SCALES.length) {
                        throw new MediaException.Malformed("invalid DTS joint scale factor index " + index);
                    }
                    jointScales[ch][n] = (float) DtsCoreTables.JOINT_SCALES[index];
                }
            }
        }
    }

    /**
     * Reads the high frequency VQ subbands of one subframe (Table 5-29, {@code HFREQ}).
     *
     * @param reader the bit stream
     * @param buffer the subband planes
     * @param offset the index of the first sample of the subframe in each plane, history excluded
     * @param subsubframes the number of subsubframes of the subframe
     */
    void readVectors(MsbBitReader reader, SubbandBuffer buffer, int offset, int subsubframes) {
        var count = subsubframes * 8;
        for (var ch = 0; ch < channels; ch++) {
            var planes = buffer.planes[firstPlane + ch];
            for (var n = vqStart[ch]; n < subbands[ch]; n++) {
                var index = reader.read(10);
                var scale = scales[ch][n][0] * (1.0f / 16);
                var plane = planes[n];
                var base = SubbandBuffer.HISTORY + offset;
                for (var m = 0; m < count; m++) {
                    plane[base + m] = scale * DtsVqTables.highFrequency(index, m);
                }
            }
        }
    }

    /**
     * Reads one subsubframe of audio data of every channel (Table 5-29, {@code AUDIO}), dequantizes it and runs
     * the inverse ADPCM.
     *
     * @param reader the bit stream
     * @param buffer the subband planes
     * @param offset the index of the first sample of the subsubframe in each plane, history excluded
     * @param subsubframe the index of the subsubframe in its subframe
     * @param subsubframes the number of subsubframes of the subframe
     * @param steps the step sizes times 2^22 by ABITS
     */
    void readSubsubframe(MsbBitReader reader, SubbandBuffer buffer, int offset, int subsubframe, int subsubframes,
                         int[] steps) {
        for (var ch = 0; ch < channels; ch++) {
            var planes = buffer.planes[firstPlane + ch];
            for (var n = 0; n < vqStart[ch]; n++) {
                var abits = allocation[ch][n];
                var plane = planes[n];
                var base = SubbandBuffer.HISTORY + offset;
                if (abits == 0) {
                    for (var m = 0; m < 8; m++) {
                        plane[base + m] = 0;
                    }
                } else {
                    readCodes(reader, ch, abits);
                    var mode = transientMode[ch][n];
                    var scale = subsubframe < (mode == 0 ? subsubframes : mode) ? scales[ch][n][0]
                            : scales[ch][n][1];
                    var adjusted = (long) scale;
                    if (abits <= 10) {
                        adjusted = adjusted * (long) (adjust[ch][abits - 1] * 16) >> 4;
                    }
                    var step = adjusted * steps[abits];
                    for (var m = 0; m < 8; m++) {
                        plane[base + m] = dequantize(codes[m], step);
                    }
                }
                if (predictionMode[ch][n] != 0) {
                    inverseAdpcm(plane, base, predictionVector[ch][n]);
                }
            }
        }
    }

    /**
     * Copies the joint intensity subbands of one subframe from their source channels (clause C.3.4).
     *
     * @param buffer the subband planes
     * @param offset the index of the first sample of the subframe in each plane, history excluded
     * @param count the number of samples of the subframe
     */
    void applyJointIntensity(SubbandBuffer buffer, int offset, int count) {
        for (var ch = 0; ch < channels; ch++) {
            if (joint[ch] == 0) {
                continue;
            }
            var source = sourcePlane[ch];
            var target = buffer.planes[firstPlane + ch];
            var from = buffer.planes[source];
            for (var n = subbands[ch]; n < planeBands[source]; n++) {
                var scale = jointScales[ch][n];
                var base = SubbandBuffer.HISTORY + offset;
                for (var m = 0; m < count; m++) {
                    target[n][base + m] = scale * from[n][base + m];
                }
            }
        }
    }

    /**
     * Zeroes the subbands of one subframe above the active ones of each channel, leaving the joint intensity
     * subbands.
     *
     * @param buffer the subband planes
     * @param offset the index of the first sample of the subframe in each plane, history excluded
     * @param count the number of samples of the subframe
     */
    void clearInactive(SubbandBuffer buffer, int offset, int count) {
        for (var ch = 0; ch < channels; ch++) {
            var top = joint[ch] > 0 ? Math.max(subbands[ch], planeBands[sourcePlane[ch]]) : subbands[ch];
            var planes = buffer.planes[firstPlane + ch];
            for (var n = top; n < 32; n++) {
                Arrays.fill(planes[n], SubbandBuffer.HISTORY + offset,
                        SubbandBuffer.HISTORY + offset + count, 0);
            }
        }
    }

    /**
     * {@return the bit allocation index of a subband in the current subframe}
     *
     * @param ch the channel of the set
     * @param n the subband
     */
    int allocation(int ch, int n) {
        return allocation[ch][n];
    }

    /**
     * {@return the scale factor of a subband in the current subframe}
     *
     * @param ch the channel of the set
     * @param n the subband
     * @param second whether to return the scale factor after the transient
     */
    float scale(int ch, int n, boolean second) {
        return scales[ch][n][second ? 1 : 0];
    }

    /**
     * {@return the transient mode of a subband in the current subframe}
     *
     * @param ch the channel of the set
     * @param n the subband
     */
    int transientMode(int ch, int n) {
        return transientMode[ch][n];
    }

    /**
     * Reads the eight quantization indexes of one subband and subsubframe into {@link #codes}.
     */
    private void readCodes(MsbBitReader reader, int ch, int abits) {
        var sel = abits <= 10 ? select[ch][abits - 1] : 0;
        var book = abits <= 10 ? AUDIO_BOOKS[abits][sel] : null;
        if (book != null) {
            for (var m = 0; m < 8; m++) {
                codes[m] = book.decode(reader);
            }
        } else if (abits <= 7) {
            var levels = LEVELS[abits];
            var offset = (levels - 1) >> 1;
            var bits = BLOCK_BITS[abits];
            for (var block = 0; block < 2; block++) {
                var code = reader.read(bits);
                for (var m = 0; m < 4; m++) {
                    codes[block * 4 + m] = code % levels - offset;
                    code /= levels;
                }
                if (code != 0) {
                    throw new MediaException.Malformed("invalid DTS block code");
                }
            }
        } else {
            var bits = abits - 3;
            for (var m = 0; m < 8; m++) {
                codes[m] = reader.readSigned(bits);
            }
        }
    }

    /**
     * Runs the inverse ADPCM over the eight samples at {@code base} (clause C.3.3), the four samples before them
     * being the history, in the integer arithmetic of the subband samples: the prediction is the sum of the Q13
     * coefficients times the previous samples, rounded at 2^13, and each reconstructed sample is clipped.
     *
     * @param plane the subband plane
     * @param base the index of the first of the eight samples
     * @param vector the prediction coefficient vector ({@code PVQ})
     */
    static void inverseAdpcm(float[] plane, int base, int vector) {
        long c0 = DtsVqTables.adpcm(vector, 0);
        long c1 = DtsVqTables.adpcm(vector, 1);
        long c2 = DtsVqTables.adpcm(vector, 2);
        long c3 = DtsVqTables.adpcm(vector, 3);
        for (var m = base; m < base + 8; m++) {
            var sum = c0 * (long) plane[m - 1] + c1 * (long) plane[m - 2] + c2 * (long) plane[m - 3]
                    + c3 * (long) plane[m - 4];
            plane[m] = clip(plane[m] + ((sum + (1 << 12)) >> 13));
        }
    }

    /**
     * Dequantizes a subband sample in the integer arithmetic of the decoder's 24-bit subband samples: the code times
     * the scale factor and step size product, rounded at 2^22, then clipped.
     *
     * @param code the quantization index
     * @param step the scale factor times the step size times 2^22
     * @return the subband sample
     */
    static float dequantize(int code, long step) {
        var product = (double) code * step;
        if (product >= 0x1p62 || product <= -0x1p62) {
            return product > 0 ? 8388607.0f : -8388608.0f;
        }
        return clip((code * step + (1 << 21)) >> 22);
    }

    /**
     * Resolves a joint intensity index ({@code JOINX}) to the plane of its source channel: an index up to the
     * channel count of the set names a channel of the set (a channel naming itself copies no subband), a higher one
     * a plane of an earlier channel set (the single channel of XCh names a core channel so).
     *
     * @param index the index, 0 without joint intensity coding
     * @param firstPlane the first plane of the channel set
     * @param channels the number of channels of the set
     * @return the source plane, or {@code -1} for index 0
     * @throws MediaException.Malformed if the index names no channel decoded before the set
     */
    static int sourcePlane(int index, int firstPlane, int channels) {
        if (index == 0) {
            return -1;
        }
        if (index <= channels) {
            return firstPlane + index - 1;
        }
        if (index - 1 < firstPlane) {
            return index - 1;
        }
        throw new MediaException.Malformed("DTS joint intensity source channel " + index
                + " names no decoded channel");
    }

    /**
     * Clips a dequantized subband sample to the 24-bit range of the decoder's subband samples, as the deployed
     * decoders do (an encoder may signal a scale factor and code whose product exceeds it).
     *
     * @param sample the subband sample
     * @return the sample within [-2^23, 2^23 - 1]
     */
    static float clip(float sample) {
        return Math.clamp(sample, -8388608.0f, 8388607.0f);
    }

    /**
     * Reads one scale factor index of a channel, accumulating the Huffman coded differences.
     */
    private static int scaleIndex(MsbBitReader reader, int book, int sum) {
        return switch (book) {
            case 5 -> reader.read(6);
            case 6 -> reader.read(7);
            default -> sum + SCALE_BOOKS[book].decode(reader);
        };
    }

    /**
     * Reads one raw joint scale factor code: a Huffman level or a linear index.
     */
    private static int rawScale(MsbBitReader reader, int book) {
        return switch (book) {
            case 5 -> reader.read(6);
            case 6 -> reader.read(7);
            default -> SCALE_BOOKS[book].decode(reader);
        };
    }

    /**
     * Looks a scale factor index up in a root square table.
     */
    private static float lookUp(float[] table, int index) {
        if (index < 0 || index >= table.length) {
            throw new MediaException.Malformed("invalid DTS scale factor index " + index);
        }
        return table[index];
    }

    /**
     * Converts integer table entries to floats.
     *
     * @param values the entries
     * @return the entries as floats
     */
    private static float[] floats(int[] values) {
        var floats = new float[values.length];
        for (var i = 0; i < values.length; i++) {
            floats[i] = values[i];
        }
        return floats;
    }

    /**
     * Looks Huffman code books up by name.
     *
     * @param names the book names
     * @return the books
     */
    private static DtsHuffman[] books(String... names) {
        var books = new DtsHuffman[names.length];
        for (var i = 0; i < names.length; i++) {
            books[i] = DtsHuffman.book(names[i]);
        }
        return books;
    }

    /**
     * Builds the audio code books by ABITS and SEL.
     *
     * @return the books, {@code null} where SEL selects block or plain coding
     */
    private static DtsHuffman[][] audioBooks() {
        var books = new DtsHuffman[11][];
        String[] prefixes = {"A", "B", "C", "D", "E", "F", "G"};
        int[] huffmanCounts = {0, 1, 3, 3, 3, 3, 7, 7, 7, 7, 7};
        for (var abits = 1; abits <= 10; abits++) {
            books[abits] = new DtsHuffman[8];
            for (var sel = 0; sel < huffmanCounts[abits]; sel++) {
                books[abits][sel] = DtsHuffman.book(prefixes[sel] + LEVELS[abits]);
            }
        }
        return books;
    }
}
