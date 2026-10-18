package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the coefficients of a code-block from its codeword segments (ISO/IEC 15444-1 annex C
 * and D): the MQ decoder, the raw decoder of the arithmetic coding bypass, and the
 * significance propagation, magnitude refinement and cleanup passes under every code-block style
 * (bypass, context reset, termination on each pass, vertically causal contexts, predictable
 * termination and segmentation symbols).
 *
 * <p>The decoded values carry one extra fractional bit: a coefficient whose magnitude bits were
 * all decoded is twice its quantized value, and one whose last bit-planes are missing is placed at
 * the middle of its uncertainty interval. An instance is reused for many code-blocks but is not
 * thread-safe.
 *
 * @implNote This implementation follows OpenJPEG's {@code t1.c} and {@code mqc_inl.h} decoders:
 * the significance, sign, visited and refinement state of four vertically adjacent samples is
 * packed in one flag word per column of a four-row stripe, and the codeword buffer ends with an
 * artificial {@code 0xFF 0xFF} marker so the byte input never checks the end of the data.
 */
final class Tier1Decoder {
    /**
     * The significance flags of the 3x6 neighborhood of a stripe column: bit {@code 3 * row + 1}
     * is the sample of stripe row {@code row} (shifted by the column of the neighbor).
     */
    private static final int SIGMA_NEIGHBORS = 0x1EF;

    /**
     * The significance of the sample itself, at stripe row 0.
     */
    private static final int SIGMA_THIS = 1 << 4;

    /**
     * The sign of the sample itself, at stripe row 0.
     */
    private static final int CHI_THIS_SHIFT = 19;

    /**
     * The refined flag of the sample, at stripe row 0.
     */
    private static final int MU_THIS = 1 << 20;

    /**
     * The visited flag of the sample, at stripe row 0.
     */
    private static final int PI_THIS = 1 << 21;

    /**
     * The visited flags of the four stripe rows.
     */
    private static final int PI_ALL = (1 << 21) | (1 << 24) | (1 << 27) | (1 << 30);

    /**
     * The largest code-block area.
     */
    private static final int MAX_AREA = 4096;

    /**
     * The decoded coefficients, row-major.
     */
    private final int[] data = new int[MAX_AREA];

    /**
     * The flag words: one per column of each stripe, with a border column on each side and a
     * border stripe above and below; 3078 words cover the largest layout, a 1024x4 block.
     */
    private final int[] flags = new int[3078];

    /**
     * The context states.
     */
    private final int[] contexts = new int[Tier1Tables.CONTEXTS];

    /**
     * The codeword bytes of the code-block, followed by two spare bytes.
     */
    private byte[] buffer = new byte[8192];

    /**
     * The code-block width.
     */
    private int width;

    /**
     * The code-block height.
     */
    private int height;

    /**
     * The offset of the zero coding contexts of the current orientation.
     */
    private int zeroContexts;

    /**
     * The MQ interval register.
     */
    private int a;

    /**
     * The MQ code register.
     */
    private int c;

    /**
     * The number of bits left before the next byte input.
     */
    private int ct;

    /**
     * The index of the current byte.
     */
    private int bp;

    /**
     * Constructs a decoder.
     */
    Tier1Decoder() {
    }

    /**
     * Returns the decoded coefficients of the last code-block.
     *
     * @return the coefficients, row-major with the code-block's width as stride
     */
    int[] data() {
        return data;
    }

    /**
     * Decodes a code-block.
     *
     * @param block the code-block with its segments
     * @param body the tile body holding the codeword chunks
     * @param orientation the sub-band orientation, 0 to 3
     * @param roiShift the region-of-interest shift
     * @param style the code-block style flags
     * @throws MediaException.Malformed if the block declares more than 30 bit-planes
     */
    void decode(CodeBlock block, byte[] body, int orientation, int roiShift, int style) {
        allocate(block.width(), block.height());
        zeroContexts = orientation << 9;
        var bitPlane = roiShift + block.bitPlanes;
        if (bitPlane >= 31) {
            throw new MediaException.Malformed("JPEG 2000 code-block with " + bitPlane + " bit-planes");
        }
        resetContexts();
        if (block.corrupted || block.chunkEntries == 0) {
            return;
        }
        var total = 0;
        for (var i = 1; i < block.chunkEntries; i += 2) {
            total += block.chunks[i];
        }
        if (buffer.length < total + 2) {
            buffer = new byte[Math.max(total + 2, buffer.length * 2)];
        }
        var length = 0;
        for (var i = 0; i < block.chunkEntries; i += 2) {
            System.arraycopy(body, block.chunks[i], buffer, length, block.chunks[i + 1]);
            length += block.chunks[i + 1];
        }
        buffer[total] = 0;
        buffer[total + 1] = 0;
        var passType = 2;
        var offset = 0;
        var vsc = (style & ComponentCoding.VERTICALLY_CAUSAL) != 0;
        for (var segment = 0; segment < block.dataSegments; segment++) {
            var segmentLength = block.segmentLengths[segment];
            var raw = bitPlane <= block.bitPlanes - 4 && passType < 2 && (style & ComponentCoding.BYPASS) != 0;
            var end = offset + segmentLength;
            if (end > total) {
                break;
            }
            var saved0 = buffer[end];
            var saved1 = buffer[end + 1];
            buffer[end] = (byte) 0xFF;
            buffer[end + 1] = (byte) 0xFF;
            if (raw) {
                bp = offset;
                c = 0;
                ct = 0;
            } else {
                initMq(offset, segmentLength);
            }
            var passes = block.segmentDataPasses[segment];
            for (var pass = 0; pass < passes && bitPlane >= 1; pass++) {
                switch (passType) {
                    case 0 -> {
                        if (raw) {
                            significanceRaw(bitPlane, vsc);
                        } else {
                            significance(bitPlane, vsc);
                        }
                    }
                    case 1 -> {
                        if (raw) {
                            refinementRaw(bitPlane);
                        } else {
                            refinement(bitPlane);
                        }
                    }
                    default -> {
                        cleanup(bitPlane, vsc);
                        if ((style & ComponentCoding.SEGMENTATION_SYMBOLS) != 0) {
                            for (var k = 0; k < 4; k++) {
                                decode(Tier1Tables.UNI);
                            }
                        }
                    }
                }
                if ((style & ComponentCoding.RESET) != 0 && !raw) {
                    resetContexts();
                }
                if (++passType == 3) {
                    passType = 0;
                    bitPlane--;
                }
            }
            buffer[end] = saved0;
            buffer[end + 1] = saved1;
            offset = end;
        }
    }

    /**
     * Prepares the coefficient and flag arrays for a code-block.
     *
     * @param w the code-block width
     * @param h the code-block height
     */
    private void allocate(int w, int h) {
        width = w;
        height = h;
        Arrays.fill(data, 0, w * h, 0);
        var stride = w + 2;
        var stripes = (h + 3) / 4;
        Arrays.fill(flags, 0, (stripes + 2) * stride, 0);
        Arrays.fill(flags, 0, stride, PI_ALL);
        Arrays.fill(flags, (stripes + 1) * stride, (stripes + 2) * stride, PI_ALL);
        if ((h & 3) != 0) {
            var v = switch (h & 3) {
                case 1 -> (1 << 24) | (1 << 27) | (1 << 30);
                case 2 -> (1 << 27) | (1 << 30);
                default -> 1 << 30;
            };
            Arrays.fill(flags, stripes * stride, (stripes + 1) * stride, v);
        }
    }

    /**
     * Resets every context to its initial state (Table D.7).
     */
    private void resetContexts() {
        Arrays.fill(contexts, 0);
        contexts[Tier1Tables.UNI] = 46 << 1;
        contexts[Tier1Tables.AGG] = 3 << 1;
        contexts[Tier1Tables.ZC] = 4 << 1;
    }

    /**
     * Initializes the MQ decoder on a segment (C.3.5).
     *
     * @param offset the index of the segment's first byte
     * @param length the segment length
     */
    private void initMq(int offset, int length) {
        bp = offset;
        c = length == 0 ? 0xFF << 16 : (buffer[bp] & 0xFF) << 16;
        byteIn();
        c <<= 7;
        ct -= 7;
        a = 0x8000;
    }

    /**
     * Reads the next byte into the code register (C.3.4).
     */
    private void byteIn() {
        var next = buffer[bp + 1] & 0xFF;
        if ((buffer[bp] & 0xFF) == 0xFF) {
            if (next > 0x8F) {
                c += 0xFF00;
                ct = 8;
            } else {
                bp++;
                c += next << 9;
                ct = 7;
            }
        } else {
            bp++;
            c += next << 8;
            ct = 8;
        }
    }

    /**
     * Decodes one decision with the MQ decoder (C.3.2).
     *
     * @param context the context index
     * @return the decision, 0 or 1
     */
    private int decode(int context) {
        var state = contexts[context];
        var qe = Tier1Tables.QE[state];
        int d;
        a -= qe;
        if ((c >>> 16) < qe) {
            if (a < qe) {
                d = Tier1Tables.MPS[state];
                contexts[context] = Tier1Tables.NEXT_MPS[state];
            } else {
                d = 1 - Tier1Tables.MPS[state];
                contexts[context] = Tier1Tables.NEXT_LPS[state];
            }
            a = qe;
            renormalize();
        } else {
            c -= qe << 16;
            if ((a & 0x8000) == 0) {
                if (a < qe) {
                    d = 1 - Tier1Tables.MPS[state];
                    contexts[context] = Tier1Tables.NEXT_LPS[state];
                } else {
                    d = Tier1Tables.MPS[state];
                    contexts[context] = Tier1Tables.NEXT_MPS[state];
                }
                renormalize();
            } else {
                d = Tier1Tables.MPS[state];
            }
        }
        return d;
    }

    /**
     * Renormalizes the interval register (C.3.3).
     */
    private void renormalize() {
        do {
            if (ct == 0) {
                byteIn();
            }
            a <<= 1;
            c <<= 1;
            ct--;
        } while (a < 0x8000);
    }

    /**
     * Reads one raw bit of an arithmetic coding bypass segment (D.6).
     *
     * @return the bit
     */
    private int rawBit() {
        if (ct == 0) {
            if (c == 0xFF) {
                if ((buffer[bp] & 0xFF) > 0x8F) {
                    c = 0xFF;
                    ct = 8;
                } else {
                    c = buffer[bp] & 0xFF;
                    bp++;
                    ct = 7;
                }
            } else {
                c = buffer[bp] & 0xFF;
                bp++;
                ct = 8;
            }
        }
        ct--;
        return (c >>> ct) & 1;
    }

    /**
     * Computes the sign neighborhood index of a stripe row from its flag word and those of the
     * west and east columns.
     *
     * @param f the flag word
     * @param west the flag word of the west column
     * @param east the flag word of the east column
     * @param row the stripe row, 0 to 3
     * @return the index into the sign tables
     */
    private static int signIndex(int f, int west, int east, int row) {
        var index = (f >>> (row * 3)) & ((1 << 1) | (1 << 3) | (1 << 5) | (1 << 7));
        index |= (west >>> (CHI_THIS_SHIFT + row * 3)) & 1;
        index |= (east >>> (CHI_THIS_SHIFT - 2 + row * 3)) & (1 << 2);
        if (row == 0) {
            index |= (f >>> (18 - 4)) & (1 << 4);
        } else {
            index |= (f >>> (CHI_THIS_SHIFT - 4 + (row - 1) * 3)) & (1 << 4);
        }
        index |= (f >>> (22 - 6 + row * 3)) & (1 << 6);
        return index;
    }

    /**
     * Records a newly significant sample in its own flag word and its neighbors'.
     *
     * @param f the flag word of the sample's column (not yet stored)
     * @param fp the index of that flag word
     * @param row the stripe row of the sample
     * @param sign the sign, 1 for negative
     * @param stride the flag words per stripe
     * @param vsc whether the stripe above must not see the sample (vertically causal mode)
     * @return the updated flag word of the sample's column
     */
    private int markSignificant(int f, int fp, int row, int sign, int stride, boolean vsc) {
        var flagWords = flags;
        flagWords[fp - 1] |= (1 << 5) << (3 * row);
        f |= ((sign << CHI_THIS_SHIFT) | SIGMA_THIS) << (3 * row);
        flagWords[fp + 1] |= (1 << 3) << (3 * row);
        if (row == 0 && !vsc) {
            var north = fp - stride;
            flagWords[north] |= (sign << 31) | (1 << 16);
            flagWords[north - 1] |= 1 << 17;
            flagWords[north + 1] |= 1 << 15;
        }
        if (row == 3) {
            var south = fp + stride;
            flagWords[south] |= (sign << 18) | (1 << 1);
            flagWords[south - 1] |= 1 << 2;
            flagWords[south + 1] |= 1;
        }
        return f;
    }

    /**
     * Runs a significance propagation pass with the MQ decoder (D.3.1).
     *
     * @param bitPlane the bit-plane, plus one for the extra fractional bit
     * @param vsc whether contexts are vertically causal
     */
    private void significance(int bitPlane, boolean vsc) {
        var one = 1 << bitPlane;
        var oneHalf = one | (one >> 1);
        var w = width;
        var stride = w + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, dp += 3 * w, fp += 2) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                var f = flags[fp];
                if (f == 0) {
                    continue;
                }
                for (var row = 0; row < 4; row++) {
                    f = significanceStep(f, fp, dp + row * w, row, oneHalf, stride, row == 0 && vsc);
                }
                flags[fp] = f;
            }
        }
        if (k < height) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                for (var row = 0; row < height - k; row++) {
                    flags[fp] = significanceStep(flags[fp], fp, dp + row * w, row, oneHalf, stride, vsc);
                }
            }
        }
    }

    /**
     * Codes one sample of a significance propagation pass.
     *
     * @param f the flag word of the sample's column
     * @param fp its index
     * @param index the sample index
     * @param row the stripe row
     * @param oneHalf the reconstruction value of a newly significant sample
     * @param stride the flag words per stripe
     * @param vsc whether the stripe above must not see the sample
     * @return the updated flag word
     */
    private int significanceStep(int f, int fp, int index, int row, int oneHalf, int stride, boolean vsc) {
        var shift = row * 3;
        if ((f & ((SIGMA_THIS | PI_THIS) << shift)) == 0 && (f & (SIGMA_NEIGHBORS << shift)) != 0) {
            if (decode(Tier1Tables.ZERO_CONTEXT[zeroContexts + ((f >>> shift) & SIGMA_NEIGHBORS)]) != 0) {
                var lookup = signIndex(f, flags[fp - 1], flags[fp + 1], row);
                var v = decode(Tier1Tables.SIGN_CONTEXT[lookup]) ^ Tier1Tables.SIGN_PREDICTION[lookup];
                data[index] = v != 0 ? -oneHalf : oneHalf;
                f = markSignificant(f, fp, row, v, stride, vsc);
            }
            f |= PI_THIS << shift;
        }
        return f;
    }

    /**
     * Runs a significance propagation pass on raw bits (D.6).
     *
     * @param bitPlane the bit-plane, plus one for the extra fractional bit
     * @param vsc whether contexts are vertically causal
     */
    private void significanceRaw(int bitPlane, boolean vsc) {
        var one = 1 << bitPlane;
        var oneHalf = one | (one >> 1);
        var w = width;
        var stride = w + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, fp += 2, dp += 3 * w) {
            for (var i = 0; i < w; i++, fp++, dp++) {
                if (flags[fp] != 0) {
                    for (var row = 0; row < 4; row++) {
                        significanceRawStep(fp, dp + row * w, row, oneHalf, stride, row == 0 && vsc);
                    }
                }
            }
        }
        if (k < height) {
            for (var i = 0; i < w; i++, fp++, dp++) {
                for (var row = 0; row < height - k; row++) {
                    significanceRawStep(fp, dp + row * w, row, oneHalf, stride, vsc);
                }
            }
        }
    }

    /**
     * Codes one sample of a raw significance propagation pass.
     *
     * @param fp the index of the sample column's flag word
     * @param index the sample index
     * @param row the stripe row
     * @param oneHalf the reconstruction value of a newly significant sample
     * @param stride the flag words per stripe
     * @param vsc whether the stripe above must not see the sample
     */
    private void significanceRawStep(int fp, int index, int row, int oneHalf, int stride, boolean vsc) {
        var f = flags[fp];
        var shift = row * 3;
        if ((f & ((SIGMA_THIS | PI_THIS) << shift)) == 0 && (f & (SIGMA_NEIGHBORS << shift)) != 0) {
            if (rawBit() != 0) {
                var v = rawBit();
                data[index] = v != 0 ? -oneHalf : oneHalf;
                f = markSignificant(f, fp, row, v, stride, vsc);
            }
            flags[fp] = f | (PI_THIS << shift);
        }
    }

    /**
     * Runs a magnitude refinement pass with the MQ decoder (D.3.3).
     *
     * @param bitPlane the bit-plane, plus one for the extra fractional bit
     */
    private void refinement(int bitPlane) {
        var half = (1 << bitPlane) >> 1;
        var w = width;
        var stride = w + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, dp += 3 * w, fp += 2) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                var f = flags[fp];
                if (f == 0) {
                    continue;
                }
                for (var row = 0; row < 4; row++) {
                    f = refinementStep(f, dp + row * w, row, half);
                }
                flags[fp] = f;
            }
        }
        if (k < height) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                for (var row = 0; row < height - k; row++) {
                    flags[fp] = refinementStep(flags[fp], dp + row * w, row, half);
                }
            }
        }
    }

    /**
     * Codes one sample of a magnitude refinement pass.
     *
     * @param f the flag word of the sample's column
     * @param index the sample index
     * @param row the stripe row
     * @param half half of the bit-plane's weight
     * @return the updated flag word
     */
    private int refinementStep(int f, int index, int row, int half) {
        var shift = row * 3;
        if ((f & ((SIGMA_THIS | PI_THIS) << shift)) == (SIGMA_THIS << shift)) {
            var neighborhood = f >>> shift;
            var context = (neighborhood & MU_THIS) != 0 ? Tier1Tables.MAG + 2
                    : (neighborhood & SIGMA_NEIGHBORS) != 0 ? Tier1Tables.MAG + 1 : Tier1Tables.MAG;
            var v = decode(context);
            data[index] += (v ^ (data[index] < 0 ? 1 : 0)) != 0 ? half : -half;
            f |= MU_THIS << shift;
        }
        return f;
    }

    /**
     * Runs a magnitude refinement pass on raw bits (D.6).
     *
     * @param bitPlane the bit-plane, plus one for the extra fractional bit
     */
    private void refinementRaw(int bitPlane) {
        var half = (1 << bitPlane) >> 1;
        var w = width;
        var stride = w + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, fp += 2, dp += 3 * w) {
            for (var i = 0; i < w; i++, fp++, dp++) {
                if (flags[fp] != 0) {
                    for (var row = 0; row < 4; row++) {
                        refinementRawStep(fp, dp + row * w, row, half);
                    }
                }
            }
        }
        if (k < height) {
            for (var i = 0; i < w; i++, fp++, dp++) {
                for (var row = 0; row < height - k; row++) {
                    refinementRawStep(fp, dp + row * w, row, half);
                }
            }
        }
    }

    /**
     * Codes one sample of a raw magnitude refinement pass.
     *
     * @param fp the index of the sample column's flag word
     * @param index the sample index
     * @param row the stripe row
     * @param half half of the bit-plane's weight
     */
    private void refinementRawStep(int fp, int index, int row, int half) {
        var f = flags[fp];
        var shift = row * 3;
        if ((f & ((SIGMA_THIS | PI_THIS) << shift)) == (SIGMA_THIS << shift)) {
            var v = rawBit();
            data[index] += (v ^ (data[index] < 0 ? 1 : 0)) != 0 ? half : -half;
            flags[fp] = f | (MU_THIS << shift);
        }
    }

    /**
     * Runs a cleanup pass (D.3.4).
     *
     * @param bitPlane the bit-plane, plus one for the extra fractional bit
     * @param vsc whether contexts are vertically causal
     */
    private void cleanup(int bitPlane, boolean vsc) {
        var one = 1 << bitPlane;
        var oneHalf = one | (one >> 1);
        var w = width;
        var stride = w + 2;
        var fp = stride + 1;
        var dp = 0;
        var k = 0;
        for (; k < (height & ~3); k += 4, dp += 3 * w, fp += 2) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                var f = flags[fp];
                if (f == 0) {
                    if (decode(Tier1Tables.AGG) == 0) {
                        continue;
                    }
                    var run = decode(Tier1Tables.UNI) << 1;
                    run |= decode(Tier1Tables.UNI);
                    f = cleanupSign(f, fp, dp + run * w, run, oneHalf, stride, run == 0 && vsc);
                    for (var row = run + 1; row < 4; row++) {
                        f = cleanupStep(f, fp, dp + row * w, row, oneHalf, stride, false);
                    }
                } else {
                    for (var row = 0; row < 4; row++) {
                        if ((f & ((SIGMA_THIS | PI_THIS) << (row * 3))) == 0) {
                            f = cleanupStep(f, fp, dp + row * w, row, oneHalf, stride, row == 0 && vsc);
                        }
                    }
                }
                flags[fp] = f & ~PI_ALL;
            }
        }
        if (k < height) {
            for (var i = 0; i < w; i++, dp++, fp++) {
                for (var row = 0; row < height - k; row++) {
                    var f = flags[fp];
                    if ((f & ((SIGMA_THIS | PI_THIS) << (row * 3))) == 0) {
                        flags[fp] = cleanupStep(f, fp, dp + row * w, row, oneHalf, stride, vsc);
                    }
                }
                flags[fp] &= ~PI_ALL;
            }
        }
    }

    /**
     * Codes one sample of a cleanup pass that is neither significant nor visited.
     *
     * @param f the flag word of the sample's column
     * @param fp its index
     * @param index the sample index
     * @param row the stripe row
     * @param oneHalf the reconstruction value of a newly significant sample
     * @param stride the flag words per stripe
     * @param vsc whether the stripe above must not see the sample
     * @return the updated flag word
     */
    private int cleanupStep(int f, int fp, int index, int row, int oneHalf, int stride, boolean vsc) {
        if (decode(Tier1Tables.ZERO_CONTEXT[zeroContexts + ((f >>> (row * 3)) & SIGMA_NEIGHBORS)]) == 0) {
            return f;
        }
        return cleanupSign(f, fp, index, row, oneHalf, stride, vsc);
    }

    /**
     * Decodes the sign of a sample the cleanup pass found significant.
     *
     * @param f the flag word of the sample's column
     * @param fp its index
     * @param index the sample index
     * @param row the stripe row
     * @param oneHalf the reconstruction value
     * @param stride the flag words per stripe
     * @param vsc whether the stripe above must not see the sample
     * @return the updated flag word
     */
    private int cleanupSign(int f, int fp, int index, int row, int oneHalf, int stride, boolean vsc) {
        var lookup = signIndex(f, flags[fp - 1], flags[fp + 1], row);
        var v = decode(Tier1Tables.SIGN_CONTEXT[lookup]) ^ Tier1Tables.SIGN_PREDICTION[lookup];
        data[index] = v != 0 ? -oneHalf : oneHalf;
        return markSignificant(f, fp, row, v, stride, vsc);
    }
}
