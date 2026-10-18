package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The first LZW decoder (byte-at-a-time bit refills and string copies), kept as the A/B baseline
 * of {@link LzwBenchmark}.
 */
final class BaselineLzwDecoder {
    private final int[] starts = new int[4096];
    private final int[] lengths = new int[4096];

    int decode(byte[] input, int inputOffset, int inputLength, byte[] output, int outOffset, int outLength) {
        var position = inputOffset;
        var end = inputOffset + inputLength;
        var out = outOffset;
        var outEnd = outOffset + outLength;
        var buffer = 0L;
        var bits = 0;
        var width = 9;
        var next = 258;
        var oldStart = -1;
        var oldLength = 0;
        while (out < outEnd) {
            while (bits < width) {
                if (position >= end) {
                    return out - outOffset;
                }
                buffer = (buffer << 8) | (input[position++] & 0xFF);
                bits += 8;
            }
            bits -= width;
            var code = (int) (buffer >>> bits) & ((1 << width) - 1);
            if (code == 256) {
                width = 9;
                next = 258;
                oldStart = -1;
                continue;
            }
            if (code == 257) {
                break;
            }
            if (oldStart < 0) {
                output[out] = (byte) code;
                oldStart = out++;
                oldLength = 1;
                continue;
            }
            int length;
            if (code < 256) {
                output[out] = (byte) code;
                length = 1;
            } else if (code < next) {
                length = lengths[code];
                var source = starts[code];
                var copy = Math.min(length, outEnd - out);
                if (copy >= 16) {
                    System.arraycopy(output, source, output, out, copy);
                } else {
                    for (var i = 0; i < copy; i++) {
                        output[out + i] = output[source + i];
                    }
                }
            } else if (code == next && next < 4096) {
                length = oldLength + 1;
                var copy = Math.min(length, outEnd - out);
                for (var i = 0; i < copy; i++) {
                    output[out + i] = output[oldStart + i];
                }
            } else {
                throw new MediaException.Malformed("undefined LZW code " + code);
            }
            if (next < 4096) {
                starts[next] = oldStart;
                lengths[next] = oldLength + 1;
                next++;
                if (next >= (1 << width) - 1 && width < 12) {
                    width++;
                }
            }
            oldStart = out;
            oldLength = length;
            out += Math.min(length, outEnd - out);
        }
        return out - outOffset;
    }
}
