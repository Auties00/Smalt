package com.github.auties00.smalt.video.ffv1.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the sample differences of FFV1 slices (RFC 9043, sections 3 and 4.7) and hands each decoded line of
 * samples to a {@link Ffv1SampleSink}.
 *
 * <p>Instances are not thread-safe; one instance decodes one slice at a time and keeps its line buffers between
 * slices.
 */
public final class Ffv1SliceDecoder {
    private final Ffv1RangeDecoder range = new Ffv1RangeDecoder();

    private final Ffv1BitReader bits = new Ffv1BitReader();

    private final byte[] headerStates = new byte[Ffv1Tables.CONTEXT_SIZE];

    /**
     * The three lines of each plane: two above the current one and the current one, each with two samples of
     * border on the left and one on the right.
     */
    private int[][][] lines = new int[4][3][0];

    private int[] red = new int[0];

    private int[] green = new int[0];

    private int[] blue = new int[0];

    private int runIndex;

    /**
     * {@return the range decoder of the slice being decoded}
     */
    public Ffv1RangeDecoder range() {
        return range;
    }

    /**
     * Reads a version 3 slice header (RFC 9043, section 4.6) from the range decoder.
     *
     * @param parameters the stream parameters
     * @param header receives the slice position, size, quantization table sets, picture structure and aspect
     * ratio, in that order; at least {@code 7 + Ffv1Contexts.GROUPS} entries
     * @throws MediaException.Malformed if a value is out of range
     */
    public void readHeader(Ffv1Parameters parameters, int[] header) {
        Arrays.fill(headerStates, (byte) 128);
        range.transitions(parameters.oneState, parameters.zeroState);
        header[0] = range.unsigned(headerStates, "slice x", parameters.horizontalSlices - 1);
        header[1] = range.unsigned(headerStates, "slice y", parameters.verticalSlices - 1);
        header[2] = range.unsigned(headerStates, "slice width", parameters.horizontalSlices - 1 - header[0]) + 1;
        header[3] = range.unsigned(headerStates, "slice height", parameters.verticalSlices - 1 - header[1]) + 1;
        var sets = parameters.contextCounts.length;
        var indices = parameters.quantIndexCount();
        for (var group = 0; group < Ffv1Contexts.GROUPS; group++) {
            header[7 + group] = 0;
        }
        for (var i = 0; i < indices; i++) {
            var index = range.unsigned(headerStates, "quantization table set index", sets - 1);
            var group = i == indices - 1 && parameters.extraPlane ? 2 : i;
            header[7 + group] = index;
        }
        header[4] = range.unsigned(headerStates, "picture structure", Integer.MAX_VALUE);
        header[5] = range.unsigned(headerStates, "sample aspect ratio numerator", Integer.MAX_VALUE);
        header[6] = range.unsigned(headerStates, "sample aspect ratio denominator", Integer.MAX_VALUE);
    }

    /**
     * Decodes the content of a slice whose range decoder is positioned after its header.
     *
     * @param parameters the stream parameters
     * @param contexts the context states of the slice position, already reset for a key frame
     * @param data the frame
     * @param end the end of the slice's coded bytes, its footer excluded
     * @param x the left edge of the slice in luma samples
     * @param y the top edge of the slice in luma samples
     * @param width the width of the slice in luma samples
     * @param height the height of the slice in luma samples
     * @param frameWidth the width of the frame
     * @param frameHeight the height of the frame
     * @param sink receives the decoded lines
     */
    public void decodeContent(Ffv1Parameters parameters, Ffv1Contexts contexts, byte[] data, int end, int x, int y,
                              int width, int height, int frameWidth, int frameHeight, Ffv1SampleSink sink) {
        var golomb = parameters.coderType == 0;
        if (golomb) {
            var start = range.terminate(parameters.version >= 3);
            if (start > end) {
                throw new MediaException.Malformed("FFV1 slice header runs past the slice");
            }
            bits.reset(data, start, end);
        } else {
            range.transitions(parameters.oneState, parameters.zeroState);
        }
        if (parameters.colorspace == 1) {
            decodeRgb(parameters, contexts, golomb, x, y, width, height, sink);
            return;
        }
        var signed = parameters.signedPrediction();
        var mask = (1 << parameters.bits) - 1;
        decodePlane(parameters, contexts, 0, golomb, signed, parameters.bits, mask, 0, x, y, width, height, width,
                height, sink);
        if (parameters.chromaPlanes) {
            var shiftX = parameters.chromaShiftX;
            var shiftY = parameters.chromaShiftY;
            var cx = x >> shiftX;
            var cy = y >> shiftY;
            var cw = -(-width >> shiftX);
            var ch = -(-height >> shiftY);
            // A chroma column or row a slice shares with the slice to its right or below is kept from that slice,
            // which comes later in the frame, whatever order the slices are decoded in.
            var right = x + width;
            var bottom = y + height;
            var keptWidth = right < frameWidth && (right & (1 << shiftX) - 1) != 0 ? cw - 1 : cw;
            var keptHeight = bottom < frameHeight && (bottom & (1 << shiftY) - 1) != 0 ? ch - 1 : ch;
            decodePlane(parameters, contexts, 1, golomb, signed, parameters.bits, mask, 1, cx, cy, cw, ch, keptWidth,
                    keptHeight, sink);
            decodePlane(parameters, contexts, 1, golomb, signed, parameters.bits, mask, 2, cx, cy, cw, ch, keptWidth,
                    keptHeight, sink);
        }
        if (parameters.extraPlane) {
            decodePlane(parameters, contexts, 2, golomb, signed, parameters.bits, mask, 3, x, y, width, height, width,
                    height, sink);
        }
    }

    private int[][] lines(int plane, int width) {
        var set = lines[plane];
        if (set[0].length < width + 3) {
            for (var i = 0; i < 3; i++) {
                set[i] = new int[width + 3];
            }
        }
        return set;
    }

    private void decodePlane(Ffv1Parameters parameters, Ffv1Contexts contexts, int group, boolean golomb,
                             boolean signed, int codingBits, int mask, int plane, int x, int y, int width,
                             int height, int keptWidth, int keptHeight, Ffv1SampleSink sink) {
        var rows = lines(plane, width);
        Arrays.fill(rows[0], 0, width + 3, 0);
        Arrays.fill(rows[1], 0, width + 3, 0);
        var quant = parameters.quantTables[contexts.quantSet[group]];
        runIndex = 0;
        for (var row = 0; row < height; row++) {
            var above2 = rows[0];
            var above = rows[1];
            var current = rows[2];
            current[0] = 0;
            current[1] = above[2];
            if (golomb) {
                golombLine(contexts, group, quant, current, above, above2, width, codingBits, mask, signed);
            } else {
                rangeLine(contexts.states[group], quant, current, above, above2, width, mask, signed);
            }
            current[width + 2] = current[width + 1];
            if (row < keptHeight && keptWidth > 0) {
                sink.line(plane, x, y + row, current, 2, keptWidth);
            }
            rotate(rows);
        }
    }

    /**
     * Rotates the line buffers of a plane so that the line just decoded becomes the line above.
     */
    private static void rotate(int[][] rows) {
        var oldest = rows[0];
        rows[0] = rows[1];
        rows[1] = rows[2];
        rows[2] = oldest;
    }

    private void decodeRgb(Ffv1Parameters parameters, Ffv1Contexts contexts, boolean golomb, int x, int y,
                           int width, int height, Ffv1SampleSink sink) {
        var planes = parameters.extraPlane ? 4 : 3;
        var depth = parameters.bits;
        for (var plane = 0; plane < planes; plane++) {
            var rows = lines(plane, width);
            Arrays.fill(rows[0], 0, width + 3, 0);
            Arrays.fill(rows[1], 0, width + 3, 0);
        }
        if (red.length < width) {
            red = new int[width];
            green = new int[width];
            blue = new int[width];
        }
        runIndex = 0;
        var offset = 1 << depth;
        var swapped = depth >= 9 && depth <= 15 && !parameters.extraPlane;
        for (var row = 0; row < height; row++) {
            for (var plane = 0; plane < planes; plane++) {
                var rows = lines[plane];
                var group = plane == 0 ? 0 : plane < 3 ? 1 : 2;
                var codingBits = depth + 1;
                var mask = (1 << codingBits) - 1;
                var quant = parameters.quantTables[contexts.quantSet[group]];
                var current = rows[2];
                current[0] = 0;
                current[1] = rows[1][2];
                if (golomb) {
                    golombLine(contexts, group, quant, current, rows[1], rows[0], width, codingBits, mask, false);
                } else {
                    rangeLine(contexts.states[group], quant, current, rows[1], rows[0], width, mask, false);
                }
                current[width + 2] = current[width + 1];
            }
            var luma = lines[0][2];
            var cb = lines[1][2];
            var cr = lines[2][2];
            for (var i = 0; i < width; i++) {
                var b = cb[i + 2] - offset;
                var r = cr[i + 2] - offset;
                var base = luma[i + 2] - (b + r >> 2);
                if (swapped) {
                    blue[i] = base;
                    red[i] = r + base;
                    green[i] = b + base;
                } else {
                    green[i] = base;
                    red[i] = r + base;
                    blue[i] = b + base;
                }
            }
            sink.rgbLine(x, y + row, red, green, blue, parameters.extraPlane ? lines[3][2] : null, width);
            for (var plane = 0; plane < planes; plane++) {
                rotate(lines[plane]);
            }
        }
    }

    private void rangeLine(byte[] states, int[][] quant, int[] current, int[] above, int[] above2, int width,
                           int mask, boolean signed) {
        var q0 = quant[0];
        var q1 = quant[1];
        var q2 = quant[2];
        var q3 = quant[3];
        var q4 = quant[4];
        var decoder = range;
        for (int i = 2, end = width + 2; i < end; i++) {
            var l = current[i - 1];
            var t = above[i];
            var tl = above[i - 1];
            var context = q0[l - tl & 0xFF] + q1[tl - t & 0xFF] + q2[t - above[i + 1] & 0xFF]
                    + q3[current[i - 2] - l & 0xFF] + q4[above2[i] - t & 0xFF];
            int predicted;
            if (signed) {
                predicted = median((short) l, (short) t, (short) l + (short) t - (short) tl);
            } else {
                predicted = median(l, t, l + t - tl);
            }
            int difference;
            if (context < 0) {
                difference = -decoder.symbol(states, -context * Ffv1Tables.CONTEXT_SIZE, true);
            } else {
                difference = decoder.symbol(states, context * Ffv1Tables.CONTEXT_SIZE, true);
            }
            current[i] = predicted + difference & mask;
        }
    }

    private void golombLine(Ffv1Contexts contexts, int group, int[][] quant, int[] current, int[] above,
                            int[] above2, int width, int codingBits, int mask, boolean signed) {
        var q0 = quant[0];
        var q1 = quant[1];
        var q2 = quant[2];
        var q3 = quant[3];
        var q4 = quant[4];
        var drift = contexts.drift[group];
        var errorSum = contexts.errorSum[group];
        var bias = contexts.bias[group];
        var count = contexts.count[group];
        var runMode = 0;
        var runCount = 0;
        for (int i = 2, end = width + 2; i < end; i++) {
            var l = current[i - 1];
            var t = above[i];
            var tl = above[i - 1];
            var context = q0[l - tl & 0xFF] + q1[tl - t & 0xFF] + q2[t - above[i + 1] & 0xFF]
                    + q3[current[i - 2] - l & 0xFF] + q4[above2[i] - t & 0xFF];
            int predicted;
            if (signed) {
                predicted = median((short) l, (short) t, (short) l + (short) t - (short) tl);
            } else {
                predicted = median(l, t, l + t - tl);
            }
            var negative = context < 0;
            if (negative) {
                context = -context;
            }
            int difference;
            if (context == 0 && runMode == 0) {
                runMode = 1;
            }
            if (runMode != 0) {
                if (runCount == 0 && runMode == 1) {
                    if (bits.bit() != 0) {
                        runCount = 1 << Ffv1Tables.LOG2_RUN[runIndex];
                        if (i - 2 + runCount <= width && runIndex < Ffv1Tables.LOG2_RUN.length - 1) {
                            runIndex++;
                        }
                    } else {
                        var log2 = Ffv1Tables.LOG2_RUN[runIndex];
                        runCount = log2 == 0 ? 0 : bits.bits(log2);
                        if (runIndex > 0) {
                            runIndex--;
                        }
                        runMode = 2;
                    }
                }
                if (--runCount < 0) {
                    runMode = 0;
                    runCount = 0;
                    difference = vlc(drift, errorSum, bias, count, context, codingBits);
                    if (difference >= 0) {
                        difference++;
                    }
                } else {
                    difference = 0;
                }
            } else {
                difference = vlc(drift, errorSum, bias, count, context, codingBits);
            }
            if (negative) {
                difference = -difference;
            }
            current[i] = predicted + difference & mask;
        }
    }

    /**
     * Decodes one Golomb Rice sample difference and adapts its context (RFC 9043, section 3.8.2.4).
     */
    private int vlc(int[] drift, int[] errorSum, int[] bias, int[] count, int context, int codingBits) {
        var n = count[context];
        var sum = errorSum[context];
        var k = 0;
        for (var i = n; i < sum; i += i) {
            k++;
        }
        var code = bits.unsignedGolomb(Math.min(k, 31), codingBits);
        var v = (code & 1) != 0 ? -(code >>> 1) - 1 : code >>> 1;
        var d = drift[context];
        if (2 * d < -n) {
            v = -1 - v;
        }
        var result = v + bias[context];
        result = result << 32 - codingBits >> 32 - codingBits;
        sum += Math.abs(v);
        d += v;
        if (n == 128) {
            n >>= 1;
            d >>= 1;
            sum >>= 1;
        }
        n++;
        var b = bias[context];
        if (d <= -n) {
            b = Math.max(b - 1, -128);
            d = Math.max(d + n, -n + 1);
        } else if (d > 0) {
            b = Math.min(b + 1, 127);
            d = Math.min(d - n, 0);
        }
        drift[context] = d;
        errorSum[context] = sum;
        bias[context] = b;
        count[context] = n;
        return result;
    }

    static int median(int a, int b, int c) {
        if (a > b) {
            var swap = a;
            a = b;
            b = swap;
        }
        return c <= a ? a : Math.min(c, b);
    }
}
