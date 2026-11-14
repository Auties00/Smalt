package com.github.auties00.smalt.video.ffv1.internal;

import java.util.Arrays;

/**
 * Encodes FFV1 slices: the slice header, the sample differences of every plane and the slice footer, the inverse
 * of {@link Ffv1SliceDecoder}.
 *
 * <p>Instances are not thread-safe; one instance encodes one slice at a time and keeps its buffers between slices.
 */
public final class Ffv1SliceEncoder {
    private final Ffv1RangeEncoder range = new Ffv1RangeEncoder();

    private final Ffv1BitWriter bits = new Ffv1BitWriter();

    private final byte[] headerStates = new byte[Ffv1Tables.CONTEXT_SIZE];

    private final byte[] keyState = new byte[1];

    private int[][][] lines = new int[4][3][0];

    private int[] red = new int[0];

    private int[] green = new int[0];

    private int[] blue = new int[0];

    private int[] alpha = new int[0];

    private byte[] slice = new byte[0];

    private int sliceSize;

    private int runIndex;

    /**
     * {@return the bytes of the last encoded slice from index 0, valid until the next slice}
     */
    public byte[] slice() {
        return slice;
    }

    /**
     * {@return the number of bytes of the last encoded slice, footer included}
     */
    public int sliceSize() {
        return sliceSize;
    }

    /**
     * Encodes a version 0 or 1 frame: the key frame flag, the parameters of a key frame and the one slice.
     *
     * @param parameters the stream parameters
     * @param contexts the context states, already reset for a key frame
     * @param keyframe whether the frame is a key frame
     * @param width the frame width
     * @param height the frame height
     * @param source the samples
     */
    public void encodeFrame(Ffv1Parameters parameters, Ffv1Contexts contexts, boolean keyframe, int width,
                            int height, Ffv1SampleSource source) {
        range.reset();
        keyState[0] = (byte) 128;
        range.bit(keyState, 0, keyframe ? 1 : 0);
        if (keyframe) {
            parameters.write(range);
        }
        content(parameters, contexts, 0, 0, width, height, width, height, source);
        finishSlice(parameters, false);
    }

    /**
     * Encodes one version 3 slice with its header and footer.
     *
     * @param parameters the stream parameters
     * @param contexts the context states of the slice position, already reset for a key frame
     * @param first whether the slice starts the frame, and so carries the key frame flag
     * @param keyframe whether the frame is a key frame
     * @param column the slice's column in the slice raster
     * @param row the slice's row in the slice raster
     * @param pictureStructure the picture structure of RFC 9043, section 4.6.7
     * @param aspectNumerator the sample aspect ratio numerator, 0 when unknown
     * @param aspectDenominator the sample aspect ratio denominator, 0 when unknown
     * @param frameWidth the frame width
     * @param frameHeight the frame height
     * @param source the samples
     */
    public void encodeSlice(Ffv1Parameters parameters, Ffv1Contexts contexts, boolean first, boolean keyframe,
                            int column, int row, int pictureStructure, int aspectNumerator, int aspectDenominator,
                            int frameWidth, int frameHeight, Ffv1SampleSource source) {
        range.reset();
        if (first) {
            keyState[0] = (byte) 128;
            range.bit(keyState, 0, keyframe ? 1 : 0);
        }
        range.transitions(parameters.oneState, parameters.zeroState);
        Arrays.fill(headerStates, (byte) 128);
        range.symbol(headerStates, 0, column, false);
        range.symbol(headerStates, 0, row, false);
        range.symbol(headerStates, 0, 0, false);
        range.symbol(headerStates, 0, 0, false);
        var indices = parameters.quantIndexCount();
        for (var i = 0; i < indices; i++) {
            var group = i == indices - 1 && parameters.extraPlane ? 2 : i;
            range.symbol(headerStates, 0, contexts.quantSet[group], false);
        }
        range.symbol(headerStates, 0, pictureStructure, false);
        range.symbol(headerStates, 0, aspectNumerator, false);
        range.symbol(headerStates, 0, aspectDenominator, false);
        var x0 = (int) ((long) column * frameWidth / parameters.horizontalSlices);
        var y0 = (int) ((long) row * frameHeight / parameters.verticalSlices);
        var x1 = (int) ((long) (column + 1) * frameWidth / parameters.horizontalSlices);
        var y1 = (int) ((long) (row + 1) * frameHeight / parameters.verticalSlices);
        content(parameters, contexts, x0, y0, x1 - x0, y1 - y0, frameWidth, frameHeight, source);
        finishSlice(parameters, true);
    }

    private void content(Ffv1Parameters parameters, Ffv1Contexts contexts, int x, int y, int width, int height,
                         int frameWidth, int frameHeight, Ffv1SampleSource source) {
        var golomb = parameters.coderType == 0;
        if (golomb) {
            bits.reset();
        } else {
            range.transitions(parameters.oneState, parameters.zeroState);
        }
        if (parameters.colorspace == 1) {
            encodeRgb(parameters, contexts, golomb, x, y, width, height, source);
            return;
        }
        var signed = parameters.signedPrediction();
        var depth = parameters.bits;
        encodePlane(parameters, contexts, 0, golomb, signed, depth, 0, x, y, width, height, source);
        if (parameters.chromaPlanes) {
            var shiftX = parameters.chromaShiftX;
            var shiftY = parameters.chromaShiftY;
            var cx = x >> shiftX;
            var cy = y >> shiftY;
            var cw = -(-width >> shiftX);
            var ch = -(-height >> shiftY);
            encodePlane(parameters, contexts, 1, golomb, signed, depth, 1, cx, cy, cw, ch, source);
            encodePlane(parameters, contexts, 1, golomb, signed, depth, 2, cx, cy, cw, ch, source);
        }
        if (parameters.extraPlane) {
            encodePlane(parameters, contexts, 2, golomb, signed, depth, 3, x, y, width, height, source);
        }
    }

    /**
     * Ends the coded bytes of the slice and appends its footer.
     */
    private void finishSlice(Ffv1Parameters parameters, boolean footer) {
        int size;
        if (parameters.coderType == 0) {
            bits.align();
            var data = bits.size();
            range.terminate(data > 0 ? bits.buffer()[0] & 0xFF : 0, parameters.version >= 3);
            var header = range.size();
            size = header + Math.max(data, 1);
            ensure(size + 8);
            System.arraycopy(range.buffer(), 0, slice, 0, header);
            if (data > 0) {
                System.arraycopy(bits.buffer(), 0, slice, header, data);
            } else {
                slice[header] = 0;
            }
        } else if (footer) {
            range.sentinel();
            range.terminate(range.terminatedSize() >>> 16 & 0xFF, false);
            size = range.size();
            ensure(size + 8);
            System.arraycopy(range.buffer(), 0, slice, 0, size);
        } else {
            range.finish();
            size = range.size();
            ensure(size + 8);
            System.arraycopy(range.buffer(), 0, slice, 0, size);
        }
        if (footer) {
            slice[size] = (byte) (size >>> 16);
            slice[size + 1] = (byte) (size >>> 8);
            slice[size + 2] = (byte) size;
            size += 3;
            if (parameters.ec != 0) {
                slice[size++] = 0;
                var crc = Ffv1Tables.crc(slice, 0, size);
                slice[size] = (byte) (crc >>> 24);
                slice[size + 1] = (byte) (crc >>> 16);
                slice[size + 2] = (byte) (crc >>> 8);
                slice[size + 3] = (byte) crc;
                size += 4;
            }
        }
        sliceSize = size;
    }

    private void ensure(int size) {
        if (slice.length < size) {
            slice = new byte[Math.max(size, slice.length * 2)];
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

    private static void rotate(int[][] rows) {
        var oldest = rows[0];
        rows[0] = rows[1];
        rows[1] = rows[2];
        rows[2] = oldest;
    }

    private void encodePlane(Ffv1Parameters parameters, Ffv1Contexts contexts, int group, boolean golomb,
                             boolean signed, int codingBits, int plane, int x, int y, int width, int height,
                             Ffv1SampleSource source) {
        var rows = lines(plane, width);
        Arrays.fill(rows[0], 0, width + 3, 0);
        Arrays.fill(rows[1], 0, width + 3, 0);
        var quant = parameters.quantTables[contexts.quantSet[group]];
        runIndex = 0;
        for (var row = 0; row < height; row++) {
            var current = rows[2];
            source.line(plane, x, y + row, current, 2, width);
            current[0] = 0;
            current[1] = rows[1][2];
            current[width + 2] = current[width + 1];
            if (golomb) {
                golombLine(contexts, group, quant, current, rows[1], rows[0], width, codingBits, signed);
            } else {
                rangeLine(contexts.states[group], quant, current, rows[1], rows[0], width, codingBits, signed);
            }
            rotate(rows);
        }
    }

    private void encodeRgb(Ffv1Parameters parameters, Ffv1Contexts contexts, boolean golomb, int x, int y,
                           int width, int height, Ffv1SampleSource source) {
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
            alpha = new int[width];
        }
        runIndex = 0;
        var offset = 1 << depth;
        var swapped = depth >= 9 && depth <= 15 && !parameters.extraPlane;
        for (var row = 0; row < height; row++) {
            source.rgbLine(x, y + row, red, green, blue, parameters.extraPlane ? alpha : null, width);
            var luma = lines[0][2];
            var cb = lines[1][2];
            var cr = lines[2][2];
            for (var i = 0; i < width; i++) {
                int b;
                int r;
                int base;
                if (swapped) {
                    b = green[i] - blue[i];
                    r = red[i] - blue[i];
                    base = blue[i];
                } else {
                    b = blue[i] - green[i];
                    r = red[i] - green[i];
                    base = green[i];
                }
                luma[i + 2] = base + (b + r >> 2);
                cb[i + 2] = b + offset;
                cr[i + 2] = r + offset;
            }
            if (parameters.extraPlane) {
                System.arraycopy(alpha, 0, lines[3][2], 2, width);
            }
            for (var plane = 0; plane < planes; plane++) {
                var rows = lines[plane];
                var group = plane == 0 ? 0 : plane < 3 ? 1 : 2;
                var codingBits = depth + 1;
                var quant = parameters.quantTables[contexts.quantSet[group]];
                var current = rows[2];
                current[0] = 0;
                current[1] = rows[1][2];
                current[width + 2] = current[width + 1];
                if (golomb) {
                    golombLine(contexts, group, quant, current, rows[1], rows[0], width, codingBits, false);
                } else {
                    rangeLine(contexts.states[group], quant, current, rows[1], rows[0], width, codingBits, false);
                }
                rotate(rows);
            }
        }
    }

    private static int fold(int value, int codingBits) {
        return value << 32 - codingBits >> 32 - codingBits;
    }

    private void rangeLine(byte[] states, int[][] quant, int[] current, int[] above, int[] above2, int width,
                           int codingBits, boolean signed) {
        var q0 = quant[0];
        var q1 = quant[1];
        var q2 = quant[2];
        var q3 = quant[3];
        var q4 = quant[4];
        var encoder = range;
        for (int i = 2, end = width + 2; i < end; i++) {
            var l = current[i - 1];
            var t = above[i];
            var tl = above[i - 1];
            var context = q0[l - tl & 0xFF] + q1[tl - t & 0xFF] + q2[t - above[i + 1] & 0xFF]
                    + q3[current[i - 2] - l & 0xFF] + q4[above2[i] - t & 0xFF];
            int predicted;
            if (signed) {
                predicted = Ffv1SliceDecoder.median((short) l, (short) t, (short) l + (short) t - (short) tl);
            } else {
                predicted = Ffv1SliceDecoder.median(l, t, l + t - tl);
            }
            var difference = current[i] - predicted;
            if (context < 0) {
                context = -context;
                difference = -difference;
            }
            encoder.symbol(states, context * Ffv1Tables.CONTEXT_SIZE, fold(difference, codingBits), true);
        }
    }

    private void golombLine(Ffv1Contexts contexts, int group, int[][] quant, int[] current, int[] above,
                            int[] above2, int width, int codingBits, boolean signed) {
        var q0 = quant[0];
        var q1 = quant[1];
        var q2 = quant[2];
        var q3 = quant[3];
        var q4 = quant[4];
        var drift = contexts.drift[group];
        var errorSum = contexts.errorSum[group];
        var bias = contexts.bias[group];
        var count = contexts.count[group];
        var runMode = false;
        var runCount = 0;
        for (int i = 2, end = width + 2; i < end; i++) {
            var l = current[i - 1];
            var t = above[i];
            var tl = above[i - 1];
            var context = q0[l - tl & 0xFF] + q1[tl - t & 0xFF] + q2[t - above[i + 1] & 0xFF]
                    + q3[current[i - 2] - l & 0xFF] + q4[above2[i] - t & 0xFF];
            int predicted;
            if (signed) {
                predicted = Ffv1SliceDecoder.median((short) l, (short) t, (short) l + (short) t - (short) tl);
            } else {
                predicted = Ffv1SliceDecoder.median(l, t, l + t - tl);
            }
            var difference = current[i] - predicted;
            if (context < 0) {
                context = -context;
                difference = -difference;
            }
            difference = fold(difference, codingBits);
            if (context == 0 && !runMode) {
                runMode = true;
            }
            if (runMode) {
                if (difference == 0) {
                    if (++runCount == 1 << Ffv1Tables.LOG2_RUN[runIndex]) {
                        bits.bits(1, 1);
                        runCount = 0;
                        if (runIndex < Ffv1Tables.LOG2_RUN.length - 1) {
                            runIndex++;
                        }
                    }
                    continue;
                }
                bits.bits(0, 1);
                bits.bits(runCount, Ffv1Tables.LOG2_RUN[runIndex]);
                if (runIndex > 0) {
                    runIndex--;
                }
                runMode = false;
                runCount = 0;
                vlc(drift, errorSum, bias, count, context, difference > 0 ? difference - 1 : difference,
                        codingBits);
            } else {
                vlc(drift, errorSum, bias, count, context, difference, codingBits);
            }
        }
        if (runMode && runCount > 0) {
            bits.bits(1, 1);
        }
    }

    /**
     * Encodes one Golomb Rice sample difference and adapts its context, the inverse of the decoder's.
     */
    private void vlc(int[] drift, int[] errorSum, int[] bias, int[] count, int context, int value,
                     int codingBits) {
        var n = count[context];
        var sum = errorSum[context];
        var k = 0;
        for (var i = n; i < sum; i += i) {
            k++;
        }
        var d = drift[context];
        var v = fold(value - bias[context], codingBits);
        var raw = 2 * d < -n ? -1 - v : v;
        var code = raw >= 0 ? 2 * raw : -2 * raw - 1;
        bits.unsignedGolomb(code, Math.min(k, 31), codingBits);
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
    }
}
